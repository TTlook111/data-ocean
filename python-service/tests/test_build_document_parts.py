"""Thematic documents collectively cover a snapshot; each is not a whole book."""
import pytest
from fastapi import HTTPException
from dataocean.rag.router import chunk_build_document, chunk_document
from dataocean.rag.schema import BuildChunkDocumentRequest, ChunkDocumentRequest

PART = '''# 合成主题
## 字段解释
### 数量
<!-- dataocean-fact: {"snapshotId":1,"factType":"COLUMN_STRUCTURE","sourceIds":[101,1005],"dependencies":["table:sales_orders","column:sales_orders.quantity"],"reviewStatus":"APPROVED","governanceStatus":"NORMAL"} -->
quantity 是购买数量。
'''

def request(content=PART):
    return BuildChunkDocumentRequest(buildId="113260d5-c934-45a0-9e82-c615f35693ff", datasourceId=1,
        docId=13, metadataSnapshotId=1, knowledgeVersionNo=1, content=content)

@pytest.mark.asyncio
async def test_partial_build_document_chunks_without_inventing_missing_sections():
    result = await chunk_build_document(request())
    assert result.chunk_count > 0
    assert all(c.source_snapshot_id == 1 for c in result.chunks)

@pytest.mark.asyncio
async def test_generic_document_contract_still_requires_whole_document():
    with pytest.raises(HTTPException) as error:
        await chunk_document(ChunkDocumentRequest(content=PART, metadataSnapshotId=1, validateStructure=True))
    assert error.value.status_code == 422

@pytest.mark.asyncio
@pytest.mark.parametrize("content", [PART.replace('"snapshotId":1', '"snapshotId":2'), PART+PART,
                                    PART+'\n```sql\nSELECT 1', PART+'\n{{unfinished}}'])
async def test_build_parts_do_not_bypass_fact_or_markdown_validation(content):
    with pytest.raises(HTTPException) as error:
        await chunk_build_document(request(content))
    assert error.value.status_code == 422

@pytest.mark.asyncio
async def test_explanatory_topic_without_reviewed_facts_produces_no_retrieval_chunks():
    result = await chunk_build_document(request('# 说明\n## 未确认事项\n暂无候选，不提供任何已审核事实。'))
    assert result.chunk_count == 0

@pytest.mark.asyncio
async def test_build_members_require_exact_persisted_identifiers():
    invalid = request()
    invalid.doc_id = None
    with pytest.raises(HTTPException) as error:
        await chunk_build_document(invalid)
    assert error.value.status_code == 400
