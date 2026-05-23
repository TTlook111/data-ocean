import unittest
from unittest.mock import patch

from dataocean.rag.fallback import fallback_retrieve
from dataocean.rag.retriever import retrieve_from_milvus
from dataocean.rag.schema import (
    DeleteVectorsRequest,
    DeleteVectorsResponse,
    RetrieveRequest,
    RetrieveResponse,
    VectorizeRequest,
)
from dataocean.rag.vectorizer import switch_version


class FakeHit:
    def __init__(self, entity: dict, score: float = 0.82) -> None:
        self.entity = entity
        self.score = score


class FakeCollection:
    def __init__(self, count: int = 0) -> None:
        self.count = count
        self.loaded = False
        self.search_expr = ""
        self.deleted_expr = ""
        self.flushed = False

    def load(self) -> None:
        self.loaded = True

    def search(self, **kwargs):
        self.search_expr = kwargs["expr"]
        return [
            [
                FakeHit(
                    {
                        "related_table": "orders",
                        "related_column": "order_id,pay_amount",
                        "chunk_type": "TABLE_DESC",
                        "chunk_text": "orders table",
                        "snapshot_id": 5,
                        "knowledge_version_no": 3,
                        "governance_status": "NORMAL",
                        "review_status": "APPROVED",
                    }
                )
            ]
        ]

    def query(self, expr: str, output_fields: list[str]):
        return [{"count(*)": self.count}]

    def delete(self, expr: str) -> None:
        self.deleted_expr = expr

    def flush(self) -> None:
        self.flushed = True


class RagContractTest(unittest.IsolatedAsyncioTestCase):
    def test_models_accept_java_camel_case_payloads(self) -> None:
        retrieve = RetrieveRequest.model_validate(
            {
                "datasourceId": 10,
                "query": "上月退款金额",
                "topK": 7,
                "minScore": 0.55,
                "activeSnapshotId": 5,
                "confidenceScores": {"orders": 91},
                "fallbackChunks": [{"chunkType": "CORE_TABLE", "chunkText": "orders"}],
            }
        )
        self.assertEqual(retrieve.datasource_id, 10)
        self.assertEqual(retrieve.question, "上月退款金额")
        self.assertEqual(retrieve.top_k, 7)
        self.assertEqual(retrieve.active_snapshot_id, 5)

        vectorize = VectorizeRequest.model_validate(
            {
                "datasourceId": 10,
                "taskId": 100,
                "targetType": "KNOWLEDGE_DOC",
                "metadataSnapshotId": 5,
                "knowledgeVersionNo": 3,
                "chunks": [
                    {
                        "sourceId": 201,
                        "chunkType": "CORE_TABLE",
                        "chunkText": "orders 表",
                        "tableName": "orders",
                        "governanceStatus": "NORMAL",
                        "reviewStatus": "APPROVED",
                    }
                ],
            }
        )
        self.assertEqual(vectorize.datasource_id, 10)
        self.assertEqual(vectorize.snapshot_id, 5)
        self.assertEqual(vectorize.version_no, 3)
        self.assertEqual(vectorize.chunks[0].related_table, "orders")

    def test_response_serializes_contract_aliases(self) -> None:
        response = RetrieveResponse.model_validate(
            {
                "results": [
                    {
                        "table_name": "orders",
                        "columns": "order_id,pay_amount",
                        "score": 0.91,
                        "chunk_type": "TABLE_DESC",
                        "chunk_text": "orders 表",
                        "snapshot_id": 5,
                    }
                ],
                "degraded": False,
            }
        )
        payload = response.model_dump(by_alias=True)

        self.assertIn("contexts", payload)
        self.assertEqual(payload["contexts"][0]["tableName"], "orders")
        self.assertEqual(payload["contexts"][0]["columns"][0]["name"], "order_id")
        self.assertEqual(payload["contexts"][0]["weightedScore"], 0.91)
        self.assertEqual(payload["contexts"][0]["relevanceScore"], 0.91)
        self.assertEqual(payload["totalFound"], 1)
        self.assertEqual(payload["returned"], 1)

        delete_request = DeleteVectorsRequest.model_validate(
            {"datasourceId": 10, "snapshotId": 5}
        )
        self.assertEqual(delete_request.datasource_id, 10)
        self.assertEqual(delete_request.snapshot_id, 5)
        delete_response = DeleteVectorsResponse(deleted_count=12, duration_ms=20)
        self.assertEqual(delete_response.model_dump(by_alias=True)["deletedCount"], 12)

    def test_fallback_uses_request_chunks(self) -> None:
        response = fallback_retrieve(
            10,
            [
                {
                    "chunkType": "CORE_TABLE",
                    "tableName": "orders",
                    "chunkText": "orders 核心表",
                    "metadataSnapshotId": 5,
                    "knowledgeVersionNo": 3,
                    "governanceStatus": "NORMAL",
                    "reviewStatus": "APPROVED",
                }
            ],
        )

        self.assertTrue(response.degraded)
        self.assertEqual(response.returned, 1)
        self.assertEqual(response.results[0].table_name, "orders")
        self.assertEqual(response.results[0].snapshot_id, 5)

    async def test_retriever_injects_datasource_snapshot_and_admission_filters(self) -> None:
        request = RetrieveRequest.model_validate(
            {
                "datasourceId": 10,
                "query": "订单金额",
                "topK": 5,
                "activeSnapshotId": 5,
            }
        )
        collection = FakeCollection()

        with patch("dataocean.rag.retriever.connect_milvus"), patch(
            "dataocean.rag.retriever.get_collection", return_value=collection
        ):
            results = await retrieve_from_milvus([0.1, 0.2], request)

        self.assertIn("datasource_id == 10", collection.search_expr)
        self.assertIn("snapshot_id == 5", collection.search_expr)
        self.assertIn('review_status == "APPROVED"', collection.search_expr)
        self.assertIn('"NORMAL"', collection.search_expr)
        self.assertIn('"RECOMMENDED"', collection.search_expr)
        self.assertNotIn('"SENSITIVE"', collection.search_expr)
        self.assertEqual(results[0].table_name, "orders")
        self.assertEqual(results[0].snapshot_id, 5)

    def test_switch_version_requires_exact_count_before_deleting_old_snapshot(self) -> None:
        collection = FakeCollection(count=2)

        with self.assertRaises(ValueError):
            switch_version(collection, 10, 5, 4, expected_count=3)
        self.assertEqual(collection.deleted_expr, "")

        switch_version(collection, 10, 5, 4, expected_count=2)
        self.assertEqual(collection.deleted_expr, "datasource_id == 10 and snapshot_id == 4")
        self.assertTrue(collection.flushed)


if __name__ == "__main__":
    unittest.main()
