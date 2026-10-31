# Reranker service

Cross-encoder reranking of regulation passages for the platform (ADR 0030): `BAAI/bge-reranker-v2-m3` (Apache-2.0)
through FlagEmbedding, behind FastAPI. The platform uses it when `finsentinel.knowledge.search.rerank.using` is
`cross-encoder`; the default is the LLM reranker.

```bash
# In Docker, CPU (first start downloads the model, ~2.3 GB, into the reranker-models volume)
docker compose -f deploy/docker-compose.yml --profile reranker up -d --build

# Natively, e.g. on a Mac's GPU (in Docker Desktop's 4-vCPU VM it was about 5x slower than the Mac's own CPU, ADR 0030)
cd reranker && uv sync --frozen
RERANKER_DEVICE=mps RERANKER_FP16=true uv run uvicorn app:main --factory --port 8099

uv run pytest   # API tests with a fake scorer; no model download
```

`POST /rerank` `{"model", "query", "passages": [...]}` returns `{"model", "scores": [...]}`, one score in [0, 1] per
passage in request order; `GET /health` answers 200 once the model is loaded. Settings are `RERANKER_*` environment
variables (`app.py`, `Settings`). Query and passage text are never logged or echoed in errors.
