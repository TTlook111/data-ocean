'use strict';
const assert = require('node:assert/strict');
const test = require('node:test');
global.window = {};
require('../../后台管理/people-data.js');
require('../../后台管理/business-scopes.js');
const { createStore, boundaryFor, selectableScopes, validateAccess, validateUntil } = window.BusinessScopePage;
const fixture = () => structuredClone(window.PeopleDemo);
const review = { name: '已核对的门店销售数量数据', description: '本部门业务日期与销售数量，不含净额和成本。', reason: '已核对字段、行条件与开放边界。', reviewed: true };

test('生成仅创建候选，同一治理版本重复生成不新增也不覆盖', () => {
  const data = fixture(), before = JSON.stringify(data);
  const store = createStore(data.dataSources, () => '2026-11-09 10:00');
  assert.deepEqual(store.generate('retail'), { added: 1, reused: 2, blocked: 1 });
  assert.equal(store.generate('retail').added, 0);
  assert.equal(store.records().filter(row => row.state === '待确认').length, 1);
  assert.equal(store.records().filter(row => row.scope.name.includes('退货')).length, 0);
  assert.equal(JSON.stringify(data), before);
});

test('人工确认要求完整核对，仅增加可选定义，人员与授权来源保持不变', () => {
  const data = fixture(), original = JSON.stringify([data.users, data.departments, data.extraRequests]);
  const store = createStore(data.dataSources, () => '2026-11-09 10:00');
  store.generate('retail');
  const candidate = store.records().find(row => row.scope.name === '门店销售数量数据');
  assert.match(store.confirm(candidate.id, { ...review, reviewed: false }, '核对人'), /核对/);
  assert.match(store.confirm(candidate.id, { ...review, reason: '' }, '核对人'), /核对/);
  assert.equal(data.dataSources[0].scopes.length, 2);
  assert.equal(store.confirm(candidate.id, review, '核对人'), undefined);
  assert.equal(data.dataSources[0].scopes.length, 3);
  assert.equal(data.dataSources[0].scopes[2].name, review.name);
  assert.equal(data.dataSources[0].scopes[2].rows, candidate.scope.rows);
  assert.equal(candidate.actor, '核对人');
  assert.equal(candidate.state, '已确认');
  assert.equal(JSON.stringify([data.users, data.departments, data.extraRequests]), original);
  assert.match(store.confirm(candidate.id, review, '核对人'), /不能确认/);
  assert.equal(store.generate('retail').added, 0);
  assert.equal(data.dataSources[0].scopes[2].name, review.name);
});

test('已生成候选在字段治理未确认或确认记录缺失时也不能确认', () => {
  const data = fixture(), store = createStore(data.dataSources);
  store.generate('retail');
  const candidate = store.records().find(row => row.state === '待确认');
  const basis = store.snapshot('retail').objects.find(object => object.base === 'sales');
  basis.checks['字段含义与开放限制'] = '待确认';
  assert.equal(store.records().find(row => row.id === candidate.id).state, '需补充');
  assert.match(store.confirm(candidate.id, review, '核对人'), /字段.*不能确认/);
  basis.checks['字段含义与开放限制'] = '已确认';
  basis.evidence = '';
  assert.match(store.confirm(candidate.id, review, '核对人'), /确认记录.*不能确认/);
  assert.equal(data.dataSources[0].scopes.length, 2);
});

test('多个数据源候选分别绑定治理快照，缺少数据源时不生成', () => {
  const store = createStore(fixture().dataSources);
  assert.ok(store.generate('all').error);
  for (const id of ['retail', 'finance', 'products']) store.generate(id);
  assert.equal(store.records().length, 6);
  for (const row of store.records()) assert.equal(row.revision, store.snapshot(row.source).revision);
});

test('已有治理但尚无范围定义时能从零生成，缺少治理的源不生成', () => {
  const sources = fixture().dataSources;
  sources.forEach(source => { source.scopes = []; });
  sources.push({ id: 'ungoverned', name: '尚未治理的示例源', scopes: [] });
  const store = createStore(sources);
  assert.equal(store.records().length, 0);
  assert.equal(store.generate('retail').added, 3);
  assert.equal(sources[0].scopes.length, 0);
  const candidate = store.records().find(record => record.scope.name === '门店销售汇总数据');
  assert.equal(store.confirm(candidate.id, review, '核对人'), undefined);
  assert.equal(sources[0].scopes.length, 1);
  assert.match(sources[0].scopes[0].fields, /销售数量/);
  assert.match(store.generate('ungoverned').error, /治理依据/);
});

test('核对界面区分可见数据与排除项，缺口线索不冒充完整授权范围', () => {
  const store = createStore(fixture().dataSources);
  store.generate('retail');
  const stock = store.records().find(record => record.scope.id === 'stock');
  const parts = boundaryFor(stock);
  assert.equal(parts.excluded, '采购成本');
  assert.match(parts.visible, /可用库存/);
  assert.doesNotMatch(parts.visible, /不含/);
  const incomplete = { gaps: ['对象含义未确认'], scope: {} };
  assert.equal(boundaryFor(incomplete).visible, '尚未完整确定');
  assert.doesNotMatch(boundaryFor(incomplete).content, /销售汇总/);
  assert.equal(boundaryFor({ ...incomplete, scope: {} }).visible, '尚未完整确定');
});

test('没有可信治理时先阻断生成，部分可信只使用已确认子集', () => {
  const sources = fixture().dataSources, store = createStore(sources);
  const assessment = store.assessment('retail');
  assert.equal(assessment.items.filter(item => !item.issues.length).length, 3);
  assert.ok(assessment.items.find(item => item.base === 'returns').issues.length);
  assert.match(store.generate('aftersales').error, /先完成治理核对/);
  const before = store.records().length;
  for (const object of store.snapshot('retail').objects) object.actor = '';
  assert.match(store.generate('retail').error, /治理依据/);
  assert.equal(store.records().length, before);
});

test('候选不能引用已变化的治理版本，旧确认不被重新生成覆盖', () => {
  const store = createStore(fixture().dataSources);
  store.generate('retail');
  const candidate = store.records().find(row => row.state === '待确认');
  store.snapshot('retail').revision = 'GOV-RETAIL-02';
  assert.equal(store.records().find(row => row.id === candidate.id).state, '需补充');
  assert.match(store.confirm(candidate.id, review, '核对人'), /版本已变化/);
});

test('部门和申请只选已确认范围，重复、跨源、缺失和不完整项均被阻断', () => {
  const sources = fixture().dataSources;
  sources[0].scopes.push({ id: 'pending', state: '待确认' }, { id: 'implicit' });
  assert.equal(selectableScopes(sources[0]).length, 2);
  assert.equal(validateAccess([], sources), undefined);
  assert.match(validateAccess([], sources, true), /至少/);
  assert.equal(validateAccess([{ source: 'retail', scopes: ['stock', 'sales'] }], sources, true), undefined);
  for (const access of [
    [{ source: '', scopes: [] }],
    [{ source: 'retail', scopes: [] }],
    [{ source: 'retail', scopes: ['pending'] }],
    [{ source: 'retail', scopes: ['implicit'] }],
    [{ source: 'retail', scopes: ['receivables'] }],
    [{ source: 'retail', scopes: ['stock', 'stock'] }],
    [{ source: 'retail', scopes: ['stock'] }, { source: 'retail', scopes: ['sales'] }]
  ]) assert.ok(validateAccess(access, sources, true));
});

test('期限支持当天与未来，阻断过去、非法日期和必填空值', () => {
  assert.equal(validateUntil('', false, '2026-11-09'), undefined);
  assert.equal(validateUntil('2026-11-09', true, '2026-11-09'), undefined);
  assert.equal(validateUntil('2026-12-01', true, '2026-11-09'), undefined);
  for (const date of ['', '2026-11-08', '2026-02-30', '2026-13-01', 'bad']) assert.ok(validateUntil(date, true, '2026-11-09'));
});
