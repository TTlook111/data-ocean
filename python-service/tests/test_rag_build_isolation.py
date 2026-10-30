from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest
from fastapi import HTTPException

from dataocean.rag import router
from dataocean.rag.router import BuildCollectionRequest
from dataocean.rag.schema import VectorizeRequest


BUILD_ID = "abcdef12-3456-7890-abcd-ef1234567890"
COLLECTION = "dataocean_rag_ds10_babcdef1234567890abcdef1234567890"


class _FakeClient:
    def __init__(self):
        self.exists = True
        self.dropped = False

    def has_collection(self, name: str) -> bool:
        return self.exists

    def query(self, **kwargs):
        return [{"count(*)": 3 if self.exists else 0}]

    def drop_collection(self, name: str):
        self.dropped = True
        self.exists = False


@pytest.mark.asyncio
async def test_collection_cleanup_drops_only_build_owned_collection_and_verifies_zero():
    client = _FakeClient()
    request = BuildCollectionRequest(buildId=BUILD_ID, collectionName=COLLECTION)
    with patch.object(router, "get_client", return_value=client):
        response = await router.delete_build_collection(request)

    assert response["beforeCount"] == 3
    assert response["vectorCount"] == 0
    assert response["collectionExists"] is False
    assert response["verified"] is True
    assert client.dropped is True


@pytest.mark.asyncio
async def test_build_cleanup_refuses_default_or_unowned_collection():
    request = BuildCollectionRequest(buildId=BUILD_ID, collectionName="schema_knowledge")
    with patch.object(router, "get_client") as get_client:
        with pytest.raises(HTTPException) as error:
            await router.delete_build_collection(request)
    assert error.value.status_code == 400
    get_client.assert_not_called()


@pytest.mark.asyncio
async def test_vectorize_route_requires_confirmed_build_collection_contract():
    request = VectorizeRequest(datasourceId=10, snapshotId=88, chunks=[])
    with patch.object(router, "vectorize_chunks", new=AsyncMock()) as vectorize_chunks:
        with pytest.raises(HTTPException) as error:
            await router.vectorize(request)
    assert error.value.status_code == 400
    vectorize_chunks.assert_not_awaited()
