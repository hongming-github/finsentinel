# Cross-encoder reranker (ADR 0030). CPU only; the model (about 2.3 GB) is downloaded on first start into /models,
# a volume, so it survives image rebuilds. Base images pinned by digest.
#   docker compose -f deploy/docker-compose.yml --profile reranker up -d --build

FROM ghcr.io/astral-sh/uv:0.13.1@sha256:4a1f3666816dafadd20ceb71eac9a7d56d7ef30279f7d64048519ab901ada336 AS uv

FROM python:3.15.0-slim-trixie@sha256:f5b92f2a669a4c347044f29367f31983280b648250ca94dea66f98c53bc87e58 AS build
COPY --from=uv /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
COPY pyproject.toml uv.lock ./
# Exactly the lock file's versions, without the test dependencies.
RUN uv sync --frozen --no-dev --no-install-project

FROM python:3.15.0-slim-trixie@sha256:f5b92f2a669a4c347044f29367f31983280b648250ca94dea66f98c53bc87e58
RUN useradd --system --uid 10001 --create-home reranker \
    && mkdir /models && chown reranker /models
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
COPY app.py ./
ENV PATH=/app/.venv/bin:$PATH HF_HOME=/models PYTHONUNBUFFERED=1
USER reranker
EXPOSE 8000
# Answers only once the model is loaded; the first start downloads it, hence the long start period.
HEALTHCHECK --interval=10s --timeout=5s --start-period=10m --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8000/health', timeout=4)"]
CMD ["uvicorn", "app:main", "--factory", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
