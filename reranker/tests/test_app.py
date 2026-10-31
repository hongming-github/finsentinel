"""API contract with a fake scorer: no model download. The real model is exercised by RetrievalEvalTest (ADR 0030)."""

from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

from app import Settings, create_app

SETTINGS = replace(Settings.from_env(), model="test/model", max_passages=3, max_chars=50)


def by_length(query: str, passages: list[str]) -> list[float]:
    return [len(p) / 100 for p in passages]


@pytest.fixture
def client():
    with TestClient(create_app(SETTINGS, lambda _: by_length)) as c:
        yield c


def test_scores_come_back_in_request_order(client):
    r = client.post("/rerank", json={"model": "test/model", "query": "q", "passages": ["ccc", "a", "bb"]})
    assert r.status_code == 200
    assert r.json() == {"model": "test/model", "scores": [0.03, 0.01, 0.02]}


def test_no_passages_scores_nothing(client):
    r = client.post("/rerank", json={"model": "test/model", "query": "q", "passages": []})
    assert r.json()["scores"] == []


def test_another_model_is_refused(client):
    r = client.post("/rerank", json={"model": "other/model", "query": "q", "passages": ["a"]})
    assert r.status_code == 409


@pytest.mark.parametrize("body", [
    {"model": "test/model", "query": "  ", "passages": ["a"]},
    {"model": "test/model", "query": "q", "passages": ["a", "b", "c", "d"]},
    {"model": "test/model", "query": "q", "passages": ["x" * 51]},
    {"model": "test/model", "query": "q"},
])
def test_bad_requests_are_refused(client, body):
    assert client.post("/rerank", json=body).status_code == 422


def test_a_malformed_request_is_not_echoed(client):
    r = client.post("/rerank", json={"model": "test/model", "query": "secret question", "passages": "not a list"})
    assert r.status_code == 422
    assert "secret question" not in r.text and "not a list" not in r.text
    assert r.json()["fields"] == ["body.passages"]


def test_health_names_the_model(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["model"] == "test/model"


def test_health_is_down_before_the_model_loads():
    # Without the context manager the lifespan never runs, as while the model is still loading.
    c = TestClient(create_app(SETTINGS, lambda _: by_length))
    assert c.get("/health").status_code == 503
