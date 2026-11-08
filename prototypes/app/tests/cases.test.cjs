'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

global.window = {};
require('../dialogue.js');
require('../cases.js');

const profit = '本店上周的毛利额是多少？';
const returns = '本店上周牛奶的退货率是多少？';
const fields = { scope: '城南店经营复盘', purpose: '核对上周门店经营情况', term: '7 天' };
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
  assert.equal(desk.simulate(id, 'approve').record.status, 'approved');
  assert.equal(desk.ask(profit, origin(2, '其他会话')).caseId, id);
  assert.equal(desk.get(id).receipt, null);
  assert.equal(desk.canView({ type: 'business', metric: 'grossProfit' }), false);

  const effective = desk.simulate(id, 'activate').record;
  assert.equal(effective.status, 'effective');
  assert.equal(effective.receipt.canRetry, true);
  assert.equal(effective.receipt.completedAt, now);
  assert.match(effective.receipt.text, /城南店.*有效至/);
  assert.equal(effective.expiresAt, now + 7 * day);
  assert.equal(desk.simulate(id, 'activate').ok, false);
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
    if (action === 'activate-fail') desk.simulate(first.caseId, 'approve');
    const outcome = desk.simulate(first.caseId, action).record;
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
  desk.simulate(id, 'approve');
  desk.simulate(id, 'activate');
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
