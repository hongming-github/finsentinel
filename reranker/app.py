"""Cross-encoder reranking of regulation passages for the FinSentinel platform (ADR 0030).

POST /rerank scores each passage against the query with BAAI/bge-reranker-v2-m3 (Apache-2.0) and returns the scores
in the order the passages came; the caller sorts. GET /health answers once the model is loaded.

Settings come from the environment (see Settings). Query and passage text are never logged: the platform's spans and
logs carry no prompt text (ADR 0020), and the same holds here.
"""

import logging
import os
import threading
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Callable, Protocol

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

log = logging.getLogger("reranker")


@dataclass(frozen=True)
class Settings:
    model: str
    # A commit of the model repository: a moved "main" would change rankings without anyone deciding it.
    revision: str
    device: str
    fp16: bool
    # Tokens per query + passage pair; longer pairs are truncated. 512 covers a 2,400-character chunk and a question.
    max_length: int
    batch_size: int
    max_passages: int
    max_chars: int

    @staticmethod
    def from_env() -> "Settings":
        s = Settings(
            model=os.environ.get("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3"),
            revision=os.environ.get("RERANKER_REVISION", "953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e"),
            device=os.environ.get("RERANKER_DEVICE", "cpu"),
            fp16=os.environ.get("RERANKER_FP16", "false").lower() == "true",
            max_length=int(os.environ.get("RERANKER_MAX_LENGTH", "512")),
            batch_size=int(os.environ.get("RERANKER_BATCH_SIZE", "8")),
            max_passages=int(os.environ.get("RERANKER_MAX_PASSAGES", "64")),
            max_chars=int(os.environ.get("RERANKER_MAX_CHARS", "20000")),
        )
        if min(s.max_length, s.batch_size, s.max_passages, s.max_chars) <= 0:
            raise ValueError("RERANKER_* sizes must be positive")
        return s


class Scorer(Protocol):
    def __call__(self, query: str, passages: list[str]) -> list[float]: ...


def flag_reranker(settings: Settings) -> Scorer:
    """Downloads the pinned revision into HF_HOME once (a volume in Docker), then loads it."""
    from FlagEmbedding import FlagReranker
    from huggingface_hub import snapshot_download

    path = snapshot_download(settings.model, revision=settings.revision)
    model = FlagReranker(path, use_fp16=settings.fp16, devices=settings.device, max_length=settings.max_length,
                         batch_size=settings.batch_size, normalize=True)
    # One inference at a time: torch already uses every core, parallel requests would only thrash them.
    lock = threading.Lock()

    def score(query: str, passages: list[str]) -> list[float]:
        with lock:
            scores = model.compute_score([(query, p) for p in passages])
        return [float(s) for s in (scores if isinstance(scores, list) else [scores])]

    return score


class RerankRequest(BaseModel):
    # The model the caller expects; a different one here is refused rather than silently ranking differently.
    model: str
    query: str
    passages: list[str]


class RerankResponse(BaseModel):
    model: str
    # Relevance in [0, 1] (sigmoid of the cross-encoder's logit), one per passage, in request order.
    scores: list[float]


def create_app(settings: Settings, load: Callable[[Settings], Scorer] = flag_reranker) -> FastAPI:
    state: dict[str, Scorer] = {}

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        log.info("loading %s@%s on %s", settings.model, settings.revision[:8], settings.device)
        state["scorer"] = load(settings)
        log.info("model ready")
        yield

    app = FastAPI(title="FinSentinel reranker", lifespan=lifespan)

    # FastAPI's default 422 body echoes the offending input, which the caller would then log: name the fields only.
    @app.exception_handler(RequestValidationError)
    async def invalid(_: Request, e: RequestValidationError) -> JSONResponse:
        fields = sorted({".".join(str(p) for p in err.get("loc", ())) for err in e.errors()})
        return JSONResponse({"detail": "invalid request", "fields": fields}, status_code=422)

    @app.get("/health")
    def health() -> dict[str, str]:
        if "scorer" not in state:
            raise HTTPException(503, "model loading")
        return {"status": "UP", "model": settings.model, "revision": settings.revision, "device": settings.device}

    # A plain def: FastAPI runs it on its thread pool, so a long inference does not block /health.
    @app.post("/rerank")
    def rerank(request: RerankRequest) -> RerankResponse:
        if request.model != settings.model:
            raise HTTPException(409, f"this service runs {settings.model}")
        if not request.query.strip():
            raise HTTPException(422, "empty query")
        if len(request.passages) > settings.max_passages:
            raise HTTPException(422, f"more than {settings.max_passages} passages")
        if any(len(t) > settings.max_chars for t in [request.query, *request.passages]):
            raise HTTPException(422, f"a text is longer than {settings.max_chars} characters")
        if not request.passages:
            return RerankResponse(model=settings.model, scores=[])
        scores = state["scorer"](request.query, request.passages)
        if len(scores) != len(request.passages):
            raise HTTPException(500, "scorer returned a different number of scores")
        return RerankResponse(model=settings.model, scores=scores)

    return app


def main() -> FastAPI:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    # The model download logs every request URL at INFO.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    return create_app(Settings.from_env())
