import unittest
from unittest.mock import AsyncMock, patch

from langchain_core.documents import Document
from pydantic import BaseModel

from dataocean.rag.fallback import fallback_retrieve
from dataocean.rag.retriever import retrieve_from_milvus
from dataocean.rag.schema import (
    ChunkDocumentRequest,
    DeleteVectorsRequest,
    DeleteVectorsResponse,
    RetrievedSchema,
    RetrieveRequest,
    RetrieveResponse,
    VectorizeRequest,
)
from dataocean.rag.vector_store import SearchHit, build_filter_expr
from dataocean.rag.vectorizer import _switch_doc_version, switch_version
from dataocean.rag.vectorizer import vectorize_chunks


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
        self.inserted_entities = None

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

    def insert(self, entities) -> None:
        self.inserted_entities = entities

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
                "docId": 99,
                "metadataSnapshotId": 5,
                "knowledgeVersionNo": 3,
                "previousVersionNo": 2,
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
        self.assertEqual(vectorize.doc_id, 99)
        self.assertEqual(vectorize.snapshot_id, 5)
        self.assertEqual(vectorize.version_no, 3)
        self.assertEqual(vectorize.previous_version_no, 2)
        self.assertEqual(vectorize.chunks[0].related_table, "orders")

        chunk_request = ChunkDocumentRequest.model_validate(
            {
                "datasourceId": 10,
                "docId": 99,
                "metadataSnapshotId": 5,
                "knowledgeVersionNo": 3,
                "content": "## 核心表说明\n### orders - 订单表\n适用表: orders",
            }
        )
        self.assertEqual(chunk_request.datasource_id, 10)
        self.assertEqual(chunk_request.doc_id, 99)
        self.assertEqual(chunk_request.snapshot_id, 5)
        self.assertEqual(chunk_request.version_no, 3)

    def test_chunker_splits_skills_md_by_h3(self) -> None:
        from dataocean.rag.chunker import chunk_skills_md

        chunks = chunk_skills_md(
            """
## 文档来源
### ignored
不参与检索

## 核心表说明
### orders - 订单表
适用表: orders
字段: order_id, pay_amount

### customers - 客户表
适用表: customers
字段: customer_id

## Join Path
### orders ↔ customers
涉及表: orders, customers
ON orders.customer_id = customers.customer_id
"""
        )

        self.assertEqual(len(chunks), 3)
        self.assertEqual(chunks[0].chunk_type, "TABLE_DESC")
        self.assertEqual(chunks[0].related_table, "orders")
        self.assertEqual(chunks[1].related_table, "customers")
        self.assertEqual(chunks[2].chunk_type, "JOIN_PATH")

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
        scoped_delete_request = DeleteVectorsRequest.model_validate(
            {"datasourceId": 10, "docId": 99, "knowledgeVersionNo": 3}
        )
        self.assertEqual(scoped_delete_request.doc_id, 99)
        self.assertEqual(scoped_delete_request.version_no, 3)
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
        filter_expr = build_filter_expr(request.datasource_id, request.active_snapshot_id)
        self.assertIn("datasource_id == 10", filter_expr)
        self.assertIn("snapshot_id == 5", filter_expr)
        self.assertIn('review_status == "APPROVED"', filter_expr)
        self.assertIn('"NORMAL"', filter_expr)
        self.assertIn('"RECOMMENDED"', filter_expr)
        self.assertNotIn('"SENSITIVE"', filter_expr)

        with patch(
            "dataocean.rag.retriever.search_by_vector",
            new=AsyncMock(return_value=[
                SearchHit(
                    document=Document(
                        page_content="orders table",
                        metadata={
                            "related_table": "orders",
                            "related_column": "order_id,pay_amount",
                            "chunk_type": "TABLE_DESC",
                            "snapshot_id": 5,
                            "knowledge_version_no": 3,
                            "governance_status": "NORMAL",
                            "review_status": "APPROVED",
                        },
                    ),
                    score=0.82,
                )
            ]),
        ):
            results = await retrieve_from_milvus([0.1, 0.2], request)

        self.assertEqual(results[0].table_name, "orders")
        self.assertEqual(results[0].snapshot_id, 5)

    @patch("dataocean.rag.vectorizer._count_vectors", return_value=2)
    def test_switch_version_requires_exact_count_before_deleting_old_snapshot(self, mock_count) -> None:
        collection = FakeCollection(count=2)

        with self.assertRaises(ValueError):
            switch_version(collection, 10, 5, 4, expected_count=3)
        self.assertEqual(collection.deleted_expr, "")

        switch_version(collection, 10, 5, 4, expected_count=2)
        self.assertEqual(collection.deleted_expr, "datasource_id == 10 and snapshot_id == 4")
        self.assertTrue(collection.flushed)

    @patch("dataocean.rag.vectorizer._count_vectors", return_value=2)
    def test_switch_doc_version_deletes_only_previous_doc_version(self, mock_count) -> None:
        collection = FakeCollection(count=2)

        _switch_doc_version(
            collection,
            datasource_id=10,
            doc_id=99,
            new_version_no=3,
            old_version_no=2,
            expected_count=2,
        )

        self.assertEqual(
            collection.deleted_expr,
            "datasource_id == 10 and doc_id == 99 and knowledge_version_no == 2",
        )
        self.assertTrue(collection.flushed)

    @patch("dataocean.rag.vectorizer._count_vectors", return_value=1)
    async def test_force_rebuild_cleans_old_doc_version_vectors_after_successful_write(self, mock_count) -> None:
        collection = FakeCollection(count=1)
        chunk = {
            "sourceId": 201,
            "chunkType": "TABLE_DESC",
            "chunkText": "orders table",
            "tableName": "orders",
            "governanceStatus": "NORMAL",
            "reviewStatus": "APPROVED",
        }

        delete_mock = AsyncMock(return_value=True)
        with patch("dataocean.rag.vectorizer.ensure_collection", return_value=collection), patch(
            "dataocean.rag.vectorizer.delete_by_expr",
            new=delete_mock,
        ), patch(
            "dataocean.rag.vectorizer.embed_texts",
            new=AsyncMock(return_value=[[0.1, 0.2]]),
        ), patch(
            "dataocean.rag.vectorizer.add_chunk_embeddings",
            new=AsyncMock(return_value=["1"]),
        ):
            response = await vectorize_chunks(
                datasource_id=10,
                snapshot_id=5,
                version_no=3,
                chunks=[VectorizeRequest.model_validate({
                    "datasourceId": 10,
                    "metadataSnapshotId": 5,
                    "knowledgeVersionNo": 3,
                    "chunks": [chunk],
                }).chunks[0]],
                doc_id=99,
                force=True,
            )

        self.assertEqual(response.status, "COMPLETED")
        delete_mock.assert_awaited_with(
            "datasource_id == 10 and doc_id == 99 and knowledge_version_no == 3 and id not in [1]",
            None,
        )

    async def test_force_rebuild_keeps_existing_doc_vectors_when_embedding_fails(self) -> None:
        collection = FakeCollection(count=1)
        chunk = {
            "sourceId": 201,
            "chunkType": "TABLE_DESC",
            "chunkText": "orders table",
            "tableName": "orders",
            "governanceStatus": "NORMAL",
            "reviewStatus": "APPROVED",
        }

        delete_mock = AsyncMock(return_value=True)
        with patch("dataocean.rag.vectorizer.ensure_collection", return_value=collection), patch(
            "dataocean.rag.vectorizer.delete_by_expr",
            new=delete_mock,
        ), patch(
            "dataocean.rag.vectorizer.embed_texts",
            new=AsyncMock(side_effect=RuntimeError("embedding down")),
        ):
            response = await vectorize_chunks(
                datasource_id=10,
                snapshot_id=5,
                version_no=3,
                chunks=[VectorizeRequest.model_validate({
                    "datasourceId": 10,
                    "metadataSnapshotId": 5,
                    "knowledgeVersionNo": 3,
                    "chunks": [chunk],
                }).chunks[0]],
                doc_id=99,
                force=True,
            )

        self.assertEqual(response.status, "FAILED")
        delete_mock.assert_not_awaited()

    def test_first_non_none_preserves_falsy_zero(self) -> None:
        from dataocean.rag.fallback import _first_non_none

        # 值为 0 时应返回 0，不跳过
        self.assertEqual(_first_non_none({"a": 0, "b": 5}, "a", "b"), 0)
        # 值为空字符串时应返回空字符串，不跳过
        self.assertEqual(_first_non_none({"a": "", "b": "x"}, "a", "b"), "")
        # 值为 None 时应跳过
        self.assertEqual(_first_non_none({"a": None, "b": 5}, "a", "b"), 5)
        # 所有键都不存在时返回 None
        self.assertIsNone(_first_non_none({}, "a", "b"))

    def test_reranker_does_not_mutate_original_scores(self) -> None:
        from dataocean.rag.reranker import rerank

        request = RetrieveRequest.model_validate(
            {"datasourceId": 1, "query": "orders", "topK": 5, "activeSnapshotId": 1}
        )
        item = RetrievedSchema(
            table_name="orders", score=0.8, chunk_type="TABLE_DESC", chunk_text="orders table"
        )
        original_score = item.score

        results = rerank([item], request)

        # 原始对象的 score 不应被修改
        self.assertEqual(item.score, original_score)
        # 返回的新对象 score 应包含加权（表名命中 +0.2）
        self.assertAlmostEqual(results[0].score, 1.0, places=3)
        # relevance_score 保持原始值
        self.assertAlmostEqual(results[0].relevance_score, 0.8, places=3)

    def test_langchain_json_parsers_handle_fenced_and_pydantic_output(self) -> None:
        from dataocean.infra.parsers import JsonBlockOutputParser, PydanticJsonBlockOutputParser

        class RewritePayload(BaseModel):
            rewritten_query: str

        parsed = JsonBlockOutputParser().parse('```json\n{"rewritten_query": "orders"}\n```')
        self.assertEqual(parsed["rewritten_query"], "orders")

        model = PydanticJsonBlockOutputParser(pydantic_object=RewritePayload).parse(
            '{"rewritten_query": "customers"}'
        )
        self.assertEqual(model.rewritten_query, "customers")


if __name__ == "__main__":
    unittest.main()
