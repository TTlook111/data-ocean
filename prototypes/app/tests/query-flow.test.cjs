'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
global.window = {}; require('../dialogue.js'); global.QueryDemo = window.QueryDemo;
require('../query-flow.js'); const flow = window.QueryFlow;
const now = Date.UTC(2026, 10, 15, 3), question = '城南店上周牛奶净销量，按天列';
const open = (options = {}) => flow.open({ id: 'QS-test', sourceId: 'retail', text: question, now, ...options });
function finish(request) { for (let i = 0; i < 10 && request.status === '运行中'; i++) flow.advance(request, now + i * 100); return request; }

test('已确认条件经过完整过程后交付，保存数据快照及未知更新时间', () => {
  const r = open(); assert.equal(r.reply, null); assert.equal(r.executions, 0);
  finish(r); assert.equal(r.status, '已交付结果'); assert.equal(r.executions, 1);
  assert.equal(r.reply.result.value, 1120); assert.equal(r.reply.result.rows.length, 7);
  assert.equal(r.reply.result.queriedAt, now + 500); assert.equal(r.reply.result.knowledge[0].revision, 1);
  assert.equal(r.reply.result.watermark, undefined);
  flow.advance(r); assert.equal(r.executions, 1);
});
test('仅补缺项继续任务，新增分组回理解，固定样例时间不漂移', () => {
  const first = open({ text: '城南店上周牛奶卖了多少盒？' });
  assert.equal(first.status, '等待补充'); assert.equal(first.executions, 0);
  const filled = finish(open({ text: '扣掉退货', previous: first.state }));
  assert.match(filled.resume, /继续原任务/); assert.equal(filled.reply.result.value, 1120);
  const changed = finish(open({ text: '扣掉，顺便按天列', previous: first.state }));
  assert.match(changed.resume, /重新理解/); assert.equal(changed.state.group, 'day');
  assert.deepEqual(filled.reply.result.rows, changed.reply.result.rows);
});
test('等待到期和取消不执行，之后创建关联的新请求', () => {
  const r = open({ text: '城南店上周牛奶卖了多少盒？' });
  flow.expire(r, now + 1800000); assert.equal(r.status, '等待到期未继续'); assert.equal(r.executions, 0);
  const next = open({ id: 'QS-new', text: '扣掉退货', previous: r.state, now: now + 1800000, related: r.id });
  assert.equal(next.related, r.id); assert.match(next.resume, /到期/);
  flow.cancel(next); finish(next); assert.equal(next.executions, 0); assert.equal(next.status, '用户取消');
});
test('执行中停止保存实际取消状态，不再交付', () => {
  const r = open(); for (let i = 0; i < 5; i++) flow.advance(r, now);
  assert.equal(r.executions, 1); flow.cancel(r); finish(r);
  assert.equal(r.database, '取消已确认'); assert.equal(r.reply.result, undefined);
});
for (const [scenario, status, database, executions] of [
  ['permission', '权限不允许', '未提交', 0], ['budget', '资源或预算超限', '未提交', 0],
  ['connection', '技术失败', '提交失败', 0], ['unknown', '技术失败', '状态未知', 1]
]) test(scenario + ' 的终态与数据库状态分别记录', () => {
  const r = finish(open({ scenario })); assert.equal(r.status, status); assert.equal(r.database, database);
  assert.equal(r.executions, executions); assert.equal(r.reply.result, undefined);
});
test('解释重试保留查询次数、原时间和原结果，失权到期及预算独立阻断', () => {
  const r = finish(open({ scenario: 'explanation' })), result = structuredClone(r.reply.result);
  assert.equal(r.explanation, '失败'); assert.equal(flow.retryExplanation(r, false, now), '结果已不可用，请重新核对访问范围或重新查询。');
  assert.equal(flow.retryExplanation(r, true, now), ''); assert.equal(r.explanation, '生成中');
  assert.deepEqual(r.reply.result, result); assert.equal(r.executions, 1); assert.equal(r.status, '已交付结果');
  r.explanation = '已停止'; assert.equal(flow.retryExplanation(r, true, now), ''); r.explanation = '失败';
  assert.match(flow.retryExplanation(r, true, now), /预算/);
  r.reply.result.retainedUntil = now; assert.match(flow.retryExplanation(r, true, now), /不可用/);
});
for (const scenario of ['empty', 'zero', 'null', 'truncated']) test(scenario + ' 不混淆无记录、零和未知，也不冒充完整合计', () => {
  const r = finish(open({ scenario })); assert.equal(r.status, '已交付结果');
  if (scenario === 'empty') { assert.deepEqual(r.reply.result.rows, []); assert.deepEqual(r.reply.result.totals, []); }
  if (scenario === 'zero') assert.equal(r.reply.result.rows[0][1], 0);
  if (scenario === 'null') { assert.match(r.reply.result.rows[0][1], /未知/); assert.deepEqual(r.reply.result.totals, []); }
  if (scenario === 'truncated') assert.match(r.reply.result.completeness, /不代表完整总量/);
});
test('比较先追问，完整答复有新增条件时不静默丢弃', () => {
  const r = open({ text: '本周和上周牛奶净销量比较' }); assert.equal(r.status, '等待补充');
  const unsupported = open({ text: '按整周比较，顺便只看城北店', previous: r.state });
  assert.equal(unsupported.planned.result, undefined); assert.match(unsupported.reply.text, /新增条件/);
  const done = finish(open({ text: '按整周比较', previous: r.state })); assert.match(done.reply.result.title, /110/);
});
test('临时公式与原因对比保留单位、零分母规则及因果边界', () => {
  const formula = finish(open({ text: '本周净销量除以上周净销量，按整周比较' })); assert.match(formula.reply.text, /分母为零或缺失/);
  const cause = finish(open({ text: '为什么本周比上周净销量下降，按相同进度比较' })); assert.match(cause.reply.text, /不能证明/);
});
test('未支持的指标和新增比较条件不能被静默替换为牛奶净销量',()=>{
  for(const text of ['本周和上周销售额比较','本周和上周酸奶净销量比较','本周和上周牛奶净销量比较，按天列','去年同比销量']) {
    const r=open({text});assert.equal(r.reply.result,undefined);assert.match(r.reply.text,/未覆盖/);
  }
});
test('商品只使用已发布同版指标，无发布或未支持依赖时交治理', () => {
  const text = '商品101的原销售数量是多少？', sourceId = 'products';
  assert.equal(open({ sourceId, text }).status, '知识不足');
  const context = { sample: {source:'products',rows:[{product:101,code:'0001',quantity:10,unit:'盒'},{product:101,code:'0001',quantity:2,unit:'盒'}]}, documents: [{ id: 'DOC-1', revision: 2, title: '原销售数量', entries: [{ id: 'metric:sales-quantity', revision: 3, content: { meaning: '汇总原销量', usage: '不扣退货' } }] }] };
  const done = finish(open({ sourceId, text, context })); assert.equal(done.reply.result.rows[0][2], 12); assert.equal(done.reply.result.governance, 3);
  assert.equal(done.reply.result.knowledge[0].revision, 2);
  for (const bad of ['商品102的原销售数量是多少？', '今天商品101的净销量', '商品101的原销售数量，只看城北店']) assert.equal(open({ sourceId, text: bad, context }).reply.result, undefined);
});
test('毛利输出修复仍核对原问题并重检，最终不带成本列',()=>{
  const request=open({scenario:'output-repair',caseReply:{kind:'answer',result:{type:'business',example:'profit-summary',title:'毛利额4620元',headings:['门店','毛利额（元）'],rows:[['城南店',4620]],totals:['合计',4620]}}});
  finish(request);assert.equal(request.repairs,1);assert.equal(request.executions,1);
  assert.equal(request.events.filter(event=>event==='核对查询与输出').length,2);
  assert.doesNotMatch(JSON.stringify(request.reply.result),/成本/);
});
