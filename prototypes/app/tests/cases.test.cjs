'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

global.window = {};
require('../dialogue.js');
require('../cases.js');

const profit = '本店上周的毛利额是多少？';
const returns = '本店上周牛奶的退货率是多少？';
const fields = { scope: '城南店经营复盘', purpose: '核对上周门店经营情况', term: '7 天' };
const fullGrant = { resource: 'profitWithBasis', term: '7 天' };
const origin = (turnId, threadId = '门店复盘') => ({ threadId, turnId });
const now = Date.UTC(2026, 10, 8, 9);
const day = 86400000;
const create = (saved, clock = () => now) => window.QueryCases.create(saved, clock);

test('申请按用途提交，审批待执行时仍不可查，同一原问不会被自动改成答案', () => {
  const desk = create();
  const first = desk.ask(profit, origin(1));
  const id = first.caseId;
  assert.equal(first.kind, 'case');
  assert.equal(desk.get(id).status, 'draft');
  assert.equal(desk.apply(id, { ...fields, purpose: '  ' }).ok, false);
  assert.equal(desk.apply(id, { ...fields, scope: '其他门店' }).ok, false);
  assert.equal(desk.apply(id, { ...fields, term: '永久' }).ok, false);
  assert.equal(desk.simulate(id, 'activate').ok, false);
  assert.equal(desk.apply(id, fields).record.status, 'pending');
  assert.equal(desk.apply(id, fields).ok, false);
  assert.equal(desk.simulate(id, 'approve').ok, false);
  assert.equal(desk.decide(id, 'approve', fullGrant).record.status, 'approved');
  assert.equal(desk.ask(profit, origin(2, '其他会话')).caseId, id);
  assert.equal(desk.get(id).receipt, null);
  assert.equal(desk.canView({ type: 'business', metric: 'grossProfit' }), false);

  const effective = desk.decide(id, 'activate').record;
  assert.equal(effective.status, 'effective');
  assert.equal(effective.receipt.canRetry, true);
  assert.equal(effective.receipt.completedAt, now);
  assert.match(effective.receipt.text, /城南店.*有效至/);
  assert.equal(effective.expiresAt, now + 7 * day);
  assert.equal(desk.decide(id, 'activate').ok, false);
  assert.deepEqual(desk.ask(profit, origin(1)), first);

  const result = desk.ask(profit, origin(3)).result;
  assert.equal(desk.canView(result), true);
  assert.equal(result.metric, 'grossProfit');
  assert.equal(result.value, result.rows[0][1] - result.rows[0][2]);
  assert.equal(result.value, result.totals[3]);
  assert.equal(desk.list().length, 1);
});

for (const [action, state] of [['reject', 'rejected'], ['activate-fail', 'executionFailed']]) {
  test(state + '保留原记录和可公开的结果，再次申请使用新草稿', () => {
    const desk = create();
    const first = desk.ask(profit, origin(1));
    desk.apply(first.caseId, fields);
    if (action === 'activate-fail') desk.decide(first.caseId, 'approve', fullGrant);
    const outcome = desk.decide(first.caseId, action, { reason: '请明确需要核对的经营周报。' }).record;
    assert.equal(outcome.status, state);
    assert.equal(outcome.receipt.canRetry, false);
    assert.equal(desk.canView({ type: 'business', metric: 'grossProfit' }), false);
    const next = desk.ask(profit, origin(2));
    assert.notEqual(first.caseId, next.caseId);
    assert.deepEqual(desk.get(first.caseId), outcome);
    assert.equal(desk.get(next.caseId).status, 'draft');
  });
}

test('授权期限届满后回看和新查询都重新核对，刷新保留原申请', () => {
  let time = now;
  let desk = create(undefined, () => time);
  const id = desk.ask(profit, origin(1)).caseId;
  desk.apply(id, fields);
  desk.decide(id, 'approve', fullGrant);
  desk.decide(id, 'activate');
  const receipt = desk.get(id).receipt;
  const result = desk.ask(profit, origin(2)).result;
  desk = create(JSON.parse(JSON.stringify(desk.snapshot())), () => time);
  time += 7 * day - 1;
  assert.equal(desk.canView(result), true);
  time += 1;
  assert.equal(desk.canView(result), false);
  assert.equal(desk.get(id).status, 'expired');
  assert.deepEqual(desk.get(id).receipt, { ...receipt, canRetry: false });
  assert.notEqual(desk.ask(profit, origin(3)).caseId, id);
});

test('负责人匹配实际范围及期限，执行后店长仅能查询获准的汇总', () => {
  const desk = create();
  const id = desk.ask(profit, origin(1)).caseId;
  desk.apply(id, { ...fields, term: '14 天' });
  const pending = desk.get(id);
  assert.equal(pending.applicant, '周店长');
  assert.equal(pending.submittedAt, now);
  for (const invalid of [{}, { resource: '另一个门店', term: '7 天' },
    { resource: 'profit', term: '30 天' }, { resource: 'profit', term: '永久' }]) {
    assert.equal(desk.decide(id, 'approve', invalid).ok, false);
  }
  assert.deepEqual(desk.get(id), pending);
  const grant = { resource: 'profit', term: '7 天' };
  const approved = desk.decide(id, 'approve', grant).record;
  assert.equal(approved.decision.resource, grant.resource);
  assert.equal(approved.decision.term, grant.term);
  assert.equal(approved.decision.at, now);
  assert.equal(approved.term, '14 天');
  assert.equal(desk.ask(profit, origin(2)).kind, 'case');
  const effective = desk.decide(id, 'activate').record;
  assert.equal(effective.expiresAt, now + 7 * day);
  assert.match(effective.receipt.text, /毛利/);
  assert.doesNotMatch(effective.receipt.text, /计算依据/);
  const answer = desk.ask(profit, origin(3));
  assert.equal(answer.result.value, 4620);
  assert.deepEqual(answer.result.totals, ['合计', 4620]);
  assert.deepEqual(answer.result.rows, [['城南店', 4620]]);
  assert.deepEqual(answer.result.headings, ['门店', '毛利额（元）']);
  assert.doesNotMatch(JSON.stringify(answer), /18460|13840|18,460|13,840|销售成本|销售净额/);
  assert.equal(desk.canView(answer.result), true);
});

test('驳回必须填写可公开理由，通知和处理记录保留同一结论', () => {
  const desk = create();
  const id = desk.ask(profit, origin(1)).caseId;
  desk.apply(id, fields);
  assert.equal(desk.decide(id, 'reject', { reason: '  ' }).ok, false);
  const reason = '请说明具体用途，补充需要核对的周报。';
  const denied = desk.decide(id, 'reject', { reason }).record;
  assert.equal(denied.decision.reason, reason);
  assert.ok(denied.receipt.text.includes(reason));
  assert.equal(denied.receipt.canRetry, false);
  assert.equal(desk.decide(id, 'approve', fullGrant).ok, false);
  assert.equal(desk.decide(id, 'reject', { reason: '重复点击' }).ok, false);
  assert.deepEqual(desk.get(id), denied);
});

test('旧版宽授权和历史答案可恢复，续为窄授权后不能回看或导出旧宽答案', () => {
  let time = now;
  let desk = create(undefined, () => time);
  const old = desk.ask(profit, origin(1)).caseId;
  desk.apply(old, fields);
  desk.decide(old, 'approve', fullGrant);
  const oldReceipt = desk.decide(old, 'activate').record.receipt;
  const historical = desk.ask(profit, origin(2)).result;
  delete historical.resource;
  const snapshot = desk.snapshot();
  delete snapshot.records[0].decision;
  delete snapshot.records[0].submittedAt;
  delete snapshot.records[0].applicant;
  desk = create(snapshot, () => time);
  assert.equal(desk.canView(historical), true);
  assert.equal(desk.get(old).decision.resource, 'profitWithBasis');
  assert.deepEqual(desk.get(old).receipt, oldReceipt);
  time += 8 * day;
  const nextId = desk.ask(profit, origin(3)).caseId;
  desk.apply(nextId, fields);
  desk.decide(nextId, 'approve', { resource: 'profit', term: '7 天' });
  desk.decide(nextId, 'activate');
  assert.equal(desk.canView(historical), false);
  assert.equal(desk.canView(desk.ask(profit, origin(4)).result), true);
  const again = create(desk.snapshot(), () => time);
  assert.equal(again.canView(historical), false);
});

test('两次独立提问关联同一知识缺口并仅回告一次，可补充且复验通过前不交付', () => {
  const desk = create();
  const first = desk.ask(returns, origin(1));
  const id = first.caseId;
  assert.equal(desk.get(id).status, 'processing');
  assert.equal(desk.ask(returns, origin(1, '第二次复盘')).caseId, id);
  assert.deepEqual(desk.ask(returns, origin(1)), first);
  assert.equal(desk.get(id).links.length, 2);
  assert.equal(desk.supplement(id, '  ').ok, false);
  assert.equal(desk.supplement(id, '请明确跨日退货归属').record.notes.length, 1);
  assert.equal(desk.simulate(id, 'verify').ok, false);
  desk.simulate(id, 'publish');
  assert.equal(desk.ask(returns, origin(2)).caseId, id);
  assert.equal(desk.get(id).receipt, null);
  assert.equal(desk.simulate(id, 'fail').record.status, 'processing');
  assert.equal(desk.get(id).receipt, null);
  desk.simulate(id, 'publish');
  const resolved = desk.simulate(id, 'verify').record;
  assert.equal(resolved.status, 'resolved');
  assert.equal(resolved.receipt.canRetry, true);
  assert.equal(desk.simulate(id, 'verify').ok, false);
  assert.equal(desk.supplement(id, '重复补充').ok, false);
  assert.equal(desk.list().filter(item => item.receipt).length, 1);
  assert.deepEqual(desk.ask(returns, origin(1)), first);
  const { result } = desk.ask(returns, origin(3));
  assert.equal(result.metric, 'returnRate');
  assert.equal(result.rows.length, 7);
  assert.deepEqual(result.totals, ['合计', '1,200', '80', '6.67%']);
  assert.equal(desk.canView(result), true);
  assert.equal(desk.ask(profit, origin(4)).kind, 'case');
});

test('有依据但不适用时按核查结论回告，不能将解释冒充修复', () => {
  const desk = create();
  const id = desk.ask(returns, origin(1)).caseId;
  const record = desk.simulate(id, 'explain').record;
  assert.equal(record.status, 'explained');
  assert.equal(record.receipt.canRetry, false);
  assert.match(record.receipt.text, /现行经营报表/);
  assert.doesNotMatch(record.receipt.text, /已解决|已修复/);
  assert.equal(desk.ask(returns, origin(2)).caseId, id);
  assert.equal(desk.canView({ type: 'business', metric: 'returnRate' }), false);
});

test('离线样例不替换其他门店、时间、商品或分组，不接管已存在的正常问题', () => {
  const desk = create();
  for (const text of ['城北店上周毛利额', '本店本周毛利额', '本店上周的毛利额按天列',
    '上周酸奶退货率', '本店上周末牛奶退货率', '上周牛奶退货率和毛利额']) {
    const reply = desk.ask(text, origin(1));
    assert.equal(reply.kind, 'notice', text);
    assert.ok(!reply.result);
  }
  assert.equal(desk.list().length, 0);
  assert.equal(desk.ask('本店上周牛奶净销量', origin(2)), null);
});
