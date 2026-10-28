"""Provider outages must fail safely instead of asking users to rewrite questions."""
from __future__ import annotations

import json
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import httpx
import pytest
from langgraph.checkpoint.memory import InMemorySaver
from openai import BadRequestError

from dataocean.core import error_messages as messages
from dataocean.core.exceptions import LLMException
from dataocean.iam_s1 import graph
from dataocean.iam_s1 import service as s1_service
from dataocean.iam_s1.schema import S1QueryExecuteRequest, S1RagRetrieveRequest
from dataocean.rag import service as rag_service
from dataocean.rag.collection_names import build_collection_name
from dataocean.rag.schema import RetrieveRequest, RetrieveResponse, RetrievedSchema

BUILD = 'abcdef12-3456-7890-abcd-ef1234567890'
SECRET = 'synthetic-private-key-must-not-reach-users'


def outage(code='Arrearage', status=400):
    response = httpx.Response(status, request=httpx.Request('POST', 'https://provider.invalid/v1/test'))
    cause = BadRequestError('Access denied: ' + SECRET, response=response, body={
        'error': {'code': code, 'type': code, 'message': SECRET},
    })
    error = LLMException(f'AI 服务暂时不可用：{cause}')
    error.__cause__ = cause
    return error


def state():
    return {
        'taskId': 'service-failure-test', 'userId': 7, 'datasourceId': 1,
        'activeMetadataSnapshotId': 88, 'permissionRevision': 9,
        'deadlineEpochSeconds': time.time() + 30,
        'startedEpochSeconds': time.time(), 'question': '统计订单数',
        'candidateCatalog': {'datasourceId': 1, 'activeMetadataSnapshotId': 88, 'permissionRevision': 9,
            'tables': [{'tableName': 'orders', 'governanceStatus': 'NORMAL', 'columns': [{
                'columnMetaId': 1, 'columnName': 'id', 'dataType': 'INT',
                'protectionLevel': 'NORMAL', 'governanceStatus': 'NORMAL',
                'allowedUsages': ['PROJECTION'], 'grantSources': [{
                    'grantId': 1, 'subjectType': 'USER', 'subjectId': 7,
                    'sourceSummary': 'isolated unit-test grant', 'departmentScope': None,
                    'grantSource': 'MANUAL', 'sourceReferenceId': None,
                    'validFrom': None, 'validUntil': None, 'explicitColumns': ['id'], 'rowCondition': None,
                }],
            }]}]},
        'capabilities': {'query': True, 'viewSql': False, 'export': False},
        'safeRag': [], 'ragChunks': [], 'glossaryTerms': [], 'fewShotExamples': [],
        'ragBuildId': None, 'ragSourceSnapshotId': 88, 'ragRefreshCount': 0,
        'attemptCount': 0, 'llmCalls': 0, 'status': 'PROCESSING',
        'linkedResources': [{'tableName': 'orders', 'columns': ['id']}],
        'linkedSchema': [{'tableName': 'orders', 'columns': [{'columnName': 'id'}]}],
    }


@pytest.mark.parametrize(('error', 'expected'), [
    (outage(), messages.LLM_QUOTA_EXHAUSTED),
    (outage('insufficient_quota', 429), messages.LLM_QUOTA_EXHAUSTED),
    (outage('invalid_api_key', 401), messages.LLM_AUTH_FAILED),
    (outage('rate_limit_exceeded', 429), messages.LLM_RATE_LIMITED),
    (LLMException('Error code: 400 Arrearage ' + SECRET), messages.LLM_QUOTA_EXHAUSTED),
    (LLMException('API timed out ' + SECRET), messages.LLM_TIMEOUT),
    (LLMException('could not generate answer ' + SECRET), messages.LLM_UNAVAILABLE),
])
def test_provider_errors_have_safe_actionable_messages(error, expected):
    assert messages.sanitize_error(error) == expected
    assert graph._public_error(error) == expected
    assert SECRET not in expected


@pytest.mark.asyncio
@pytest.mark.parametrize('bad_response', [False, True])
async def test_planning_failure_is_not_clarification(monkeypatch, bad_response):
    current = state()
    monkeypatch.setattr(graph, '_link_candidates', lambda _state: current['linkedSchema'])
    monkeypatch.setattr(graph, '_unmapped_requested_object', lambda *_args: False)
    async def invalid_output(input_state, *_args, **_kwargs):
        return 'not valid JSON',dict(input_state,llmCalls=input_state.get('llmCalls',0)+1)
    call = AsyncMock(side_effect=invalid_output) if bad_response else AsyncMock(side_effect=outage())
    monkeypatch.setattr(graph, '_budgeted_llm', call)
    updated = await graph._plan_node(current)
    assert updated['status'] == 'FAILED'
    assert graph._after_plan(updated) == 'fail'
    assert not updated['clarification']
    final = (await graph._fail_node(updated))['finalResult']
    assert final['status'] == 'FAILED'
    assert SECRET not in json.dumps(final)
    assert 'clarification' not in final
    if bad_response:
        assert updated['llmCalls'] == 2
        assert call.await_count == 2
        assert [item.args[4] for item in call.await_args_list] == ['plan:0','plan:0:repair']
        assert updated['errorType'] == 'MODEL_OUTPUT_INVALID'
    else:
        call.assert_awaited_once()


@pytest.mark.asyncio
async def test_genuine_planner_clarification_stays_clarification(monkeypatch):
    current = state()
    monkeypatch.setattr(graph, '_link_candidates', lambda _state: current['linkedSchema'])
    monkeypatch.setattr(graph, '_unmapped_requested_object', lambda *_args: False)
    monkeypatch.setattr(graph, '_budgeted_llm', AsyncMock(return_value=(json.dumps({
        'clarificationNeeded': True, 'clarification': '请补充时间范围', 'selectedResources': [],
    }), current)))
    updated = await graph._plan_node(current)
    assert updated['status'] == 'CLARIFICATION_REQUIRED'
    assert graph._after_plan(updated) == 'clarify'


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', [graph.BudgetExceeded('call budget'), TimeoutError('deadline')])
async def test_planning_preserves_budget_and_deadline_controls(monkeypatch, failure):
    current = state()
    monkeypatch.setattr(graph, '_link_candidates', lambda _state: current['linkedSchema'])
    monkeypatch.setattr(graph, '_unmapped_requested_object', lambda *_args: False)
    monkeypatch.setattr(graph, '_budgeted_llm', AsyncMock(side_effect=failure))
    with pytest.raises(type(failure)):
        await graph._plan_node(current)


@pytest.mark.asyncio
async def test_sql_generation_outage_stops_before_validation_or_execution(monkeypatch):
    current = dict(state(), currentSql='old SQL', currentAttemptId='old-attempt',
                   currentSqlHash='a' * 64, protectedResult={'status': 'PROTECTED'})
    monkeypatch.setattr(graph, 'render_sql_prompt', AsyncMock(return_value='SQL prompt'))
    monkeypatch.setattr(graph, '_budgeted_llm', AsyncMock(side_effect=outage()))
    validate = Mock(side_effect=AssertionError('provider outage must stop before SQL validation'))
    monkeypatch.setattr(graph, 'validate_sql', validate)
    updated = await graph._generate_sql_node(current)
    assert updated['status'] == 'FAILED'
    assert graph._after_generate(updated) == 'fail'
    assert updated['error'] == messages.LLM_QUOTA_EXHAUSTED
    assert not updated['currentSql'] and not updated['currentAttemptId']
    assert not updated['protectedResult']
    validate.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize('node', ['semantic', 'verification'])
async def test_later_provider_failure_cannot_follow_stale_retry_or_success(monkeypatch, node):
    current = dict(state(), repairFeedback='old retry', semanticDecision='MATCH',
                   currentAttemptId='attempt-1', currentSqlHash='a' * 64,
                   protectedResult={'status': 'PROTECTED', 'attemptId': 'attempt-1', 'sqlHash': 'a' * 64,
                                    'data': [{'id': 1}], 'columns': [{'name': 'id'}]})
    monkeypatch.setattr(graph, '_budgeted_llm', AsyncMock(side_effect=outage()))
    if node == 'semantic':
        updated = await graph._semantic_check_node(current)
        route = graph._after_semantic(updated)
    else:
        updated = await graph._verify_result_node(current)
        route = graph._after_result(updated)
    assert updated['status'] == 'FAILED'
    assert route == 'fail'
    assert not updated['repairFeedback']
    final = (await graph._fail_node(updated))['finalResult']
    assert 'data' not in final and 'sql' not in final
    assert SECRET not in json.dumps(final)


@pytest.mark.asyncio
@pytest.mark.parametrize('node', ['semantic', 'verification'])
@pytest.mark.parametrize('response', ['not valid JSON', '{}', '{"decision":"INVALID"}'])
async def test_invalid_model_decision_is_failure_not_clarification_or_success(monkeypatch, node, response):
    current = dict(state(), repairFeedback='old retry', semanticDecision='MATCH',
                   currentAttemptId='attempt-1', currentSqlHash='a' * 64,
                   protectedResult={'status': 'PROTECTED', 'attemptId': 'attempt-1', 'sqlHash': 'a' * 64,
                                    'data': [{'id': 1}], 'columns': [{'name': 'id'}]})
    call = AsyncMock(return_value=(response, dict(current, llmCalls=1)))
    monkeypatch.setattr(graph, '_budgeted_llm', call)
    if node == 'semantic':
        updated = await graph._semantic_check_node(current)
        route = graph._after_semantic(updated)
    else:
        updated = await graph._verify_result_node(current)
        route = graph._after_result(updated)
    assert updated['status'] == 'FAILED'
    assert route == 'fail'
    assert not updated['clarification']
    assert updated['llmCalls'] == 1
    assert 'data' not in (await graph._fail_node(updated))['finalResult']
    call.assert_awaited_once()


@pytest.mark.asyncio
async def test_graph_writes_failed_terminal_checkpoint_and_never_generates_sql(monkeypatch):
    current = state()
    request = S1QueryExecuteRequest.model_validate({
        'protocolVersion': 'IAM-SIMPLE-1', 'taskId': current['taskId'], 'userId': 7, 'datasourceId': 1,
        'activeMetadataSnapshotId': 88, 'permissionRevision': 9,
        'conversationId': 42, 'conversationThreadId': 'iam-s1:7:1:42',
        'question': current['question'], 'candidateCatalog': current['candidateCatalog'],
        'capabilities': current['capabilities'],
        'conversationHistory': [], 'conversationSummary': None,
        'ragChunks': [], 'fallbackChunks': [], 'glossaryTerms': [], 'fewShotExamples': [],
    })
    monkeypatch.setattr(graph, '_checkpointer', InMemorySaver())
    monkeypatch.setattr(graph, '_compiled_graph', None)
    monkeypatch.setattr(graph, '_retrieve_node', AsyncMock(return_value={'safeRag': []}))
    monkeypatch.setattr(graph, '_link_candidates', lambda _state: current['linkedSchema'])
    monkeypatch.setattr(graph, '_unmapped_requested_object', lambda *_args: False)
    monkeypatch.setattr(graph, '_budgeted_llm', AsyncMock(side_effect=outage()))
    generate = AsyncMock(side_effect=AssertionError('outage must stop before SQL'))
    monkeypatch.setattr(graph, '_generate_sql_node', generate)
    result = await graph.run_query_graph(request)
    assert result['status'] == 'FAILED'
    assert result['error'] == messages.LLM_QUOTA_EXHAUSTED
    checkpoint = await (await graph._graph()).aget_state({'configurable': {'thread_id': 'iam-s1:7:1:42'}})
    assert checkpoint.values['finalResult'] == result
    assert not checkpoint.next
    generate.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize('usable_fallback', [False, True])
async def test_embedding_outage_only_degrades_when_fallback_is_usable(monkeypatch, usable_fallback):
    request = RetrieveRequest(datasourceId=1, question='统计订单数', activeSnapshotId=88,
                             buildId=BUILD, collectionName=build_collection_name(1, BUILD),
                             authorizedChunkIds=[1])
    monkeypatch.setattr(rag_service, '_get_redis', AsyncMock(return_value=SimpleNamespace(get=AsyncMock(return_value=None))))
    monkeypatch.setattr(rag_service, 'embed_single', AsyncMock(side_effect=outage()))
    fallback = RetrieveResponse(results=[RetrievedSchema(table_name='orders', columns=[], score=.5)]
                                if usable_fallback else [], degraded=True)
    monkeypatch.setattr(rag_service, 'fallback_retrieve', lambda *_args, **_kwargs: fallback)
    if usable_fallback:
        assert await rag_service.retrieve_schemas(request) is fallback
    else:
        with pytest.raises(LLMException):
            await rag_service.retrieve_schemas(request)


@pytest.mark.asyncio
async def test_s1_retrieval_does_not_swallow_outage_with_empty_authorized_fallback(monkeypatch):
    current = state()
    snapshot = graph._planning_snapshot(current)
    request = S1RagRetrieveRequest(protocolVersion='IAM-SIMPLE-1', taskId=current['taskId'], userId=7,
        datasourceId=1, activeMetadataSnapshotId=88, permissionRevision=9, permissionSnapshot=snapshot,
        question=current['question'], chunks=[], ragBuildId=BUILD, ragSourceSnapshotId=88,
        ragCollectionName=build_collection_name(1, BUILD),
        ragEmbeddingConfig={'providerId': 'test', 'model': 'test'})
    monkeypatch.setattr(rag_service, 'retrieve_schemas', AsyncMock(side_effect=outage()))
    with pytest.raises(LLMException):
        await s1_service.retrieve(request)
    monkeypatch.setattr(graph, 'retrieve', AsyncMock(side_effect=outage()))
    updated = await graph._retrieve_node(current)
    assert updated['status'] == 'FAILED'
    assert graph._after_retrieve(updated) == 'fail'


@pytest.mark.asyncio
async def test_model_outage_keeps_durable_reservation_without_auto_retry(monkeypatch):
    current = state()
    monkeypatch.setattr(graph, 'reserve_model_call', AsyncMock(return_value={'allowed': True, 'callsUsed': 1}))
    call = AsyncMock(side_effect=outage())
    settle = AsyncMock()
    monkeypatch.setattr(graph, 'call_llm_with_usage', call)
    monkeypatch.setattr(graph, 'settle_model_call', settle)
    with pytest.raises(LLMException):
        await graph._budgeted_llm(current, 'schema_linking', 'system', 'prompt', 'plan:0')
    call.assert_awaited_once()
    settle.assert_not_awaited()
