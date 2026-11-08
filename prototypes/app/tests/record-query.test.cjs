'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

global.window = { QueryCases: { resources: { profit: '仅毛利额', profitWithBasis: '毛利额及计算依据' } } };
require('../../后台管理/record-query.js');
const { run } = window.AdminQuery;

const day = 86_400_000;
const base = Date.parse('2026-11-01T00:00:00+08:00');

function record(index, status = 'pending') {
  return {
    id: 'SQ-' + String(index).padStart(3, '0'), type: 'access', status,
    applicant: index % 2 ? '周店长' : '李店长',
    title: '城南店毛利汇总访问申请', scope: '城南店经营复盘',
    purpose: index % 3 ? '月度经营分析' : '补充核对周报',
    submittedAt: base + index * 60_000,
    decision: { resource: index % 2 ? 'profit' : 'profitWithBasis' },
    receipt: { completedAt: base + index * day }
  };
}

for (const count of [0, 1, 10, 11]) {
  test(count + '条记录的默认页与总数', () => {
    const result = run(Array.from({ length: count }, (_, i) => record(i + 1)));
    assert.equal(result.total, count);
    assert.equal(result.items.length, Math.min(count, 10));
    assert.equal(result.page, 1);
    assert.equal(result.pageCount, Math.ceil(count / 10));
    assert.equal(result.from, count ? 1 : 0);
    assert.equal(result.to, Math.min(count, 10));
  });
}

test('一百零五条记录分页，最后页、越界与每页条数均一致', () => {
  const rows = Array.from({ length: 105 }, (_, i) => record(i + 1));
  const second = run(rows, { page: 2, pageSize: 10 });
  assert.deepEqual(second.items.map(item => item.id), rows.slice(85, 95).reverse().map(item => item.id));
  assert.equal(second.from, 11);
  assert.equal(second.to, 20);
  const last = run(rows, { page: 99 });
  assert.equal(last.page, 11);
  assert.equal(last.items.length, 5);
  assert.equal(last.from, 101);
  assert.equal(last.to, 105);
  for (const size of [20, 50]) {
    const result = run(rows, { page: 2, pageSize: String(size) });
    assert.equal(result.pageSize, size);
    assert.equal(result.items.length, size);
    assert.equal(result.from, size + 1);
  }
  assert.equal(run(rows, { page: -2, pageSize: 17 }).page, 1);
  assert.equal(run(rows, { page: Infinity, pageSize: 17 }).pageSize, 10);
});

test('多字段多关键词包含匹配，先过滤再计算分页', () => {
  const rows = Array.from({ length: 105 }, (_, i) => record(i + 1));
  const result = run(rows, { keyword: '  周店长   周报  ', page: 2 });
  const expected = rows.filter(item => item.applicant === '周店长' && item.purpose.includes('周报')).reverse();
  assert.equal(result.total, expected.length);
  assert.deepEqual(result.items.map(item => item.id), expected.slice(10, 20).map(item => item.id));
  assert.equal(run(rows, { keyword: 'ｓｑ－１０５' }).items[0].id, 'SQ-105');
  assert.equal(run(rows, { keyword: '城南 计算依据' }).total, 52);
  assert.equal(run(rows, { keyword: '完全不存在', page: 8 }).page, 1);
});

test('关键词中的正则符号按原文匹配，原问题也可搜索', () => {
  const rows = [record(1), record(2)];
  rows[0].purpose = '比较 A+B (C) 与 [D] 的情况';
  rows[0].links = [{ text: '本店上周的毛利额是多少？' }];
  assert.deepEqual(run(rows, { keyword: 'a+b (c)' }).items.map(item => item.id), ['SQ-001']);
  assert.equal(run(rows, { keyword: '.*' }).total, 0);
  assert.equal(run(rows, { keyword: '上周的毛利额' }).total, 1);
});

test('申请分组和精确状态组合，草稿及治理不进入申请列表', () => {
  const states = ['pending', 'approved', 'effective', 'rejected', 'executionFailed', 'expired', 'draft'];
  const rows = states.map((status, i) => record(i + 1, status));
  rows.push({ ...record(20), type: 'knowledge' });
  assert.equal(run(rows).total, 6);
  assert.equal(run(rows, { group: 'todo' }).total, 2);
  assert.equal(run(rows, { group: 'done' }).total, 4);
  assert.equal(run(rows, { group: 'todo', status: 'approved' }).total, 1);
  assert.equal(run(rows, { group: 'todo', status: 'effective' }).total, 0);
  assert.equal(run(rows, { status: 'unknown' }).total, 0);
});

test('日期用上海业务日，首尾全天都计入并排除缺失时间', () => {
  const rows = [
    { ...record(1), submittedAt: Date.parse('2026-11-08T00:00:00+08:00') },
    { ...record(2), submittedAt: Date.parse('2026-11-08T23:59:59.999+08:00') },
    { ...record(3), submittedAt: Date.parse('2026-11-09T00:00:00+08:00') },
    { ...record(4), submittedAt: undefined }
  ];
  const result = run(rows, { fromDate: '2026-11-08', toDate: '2026-11-08' });
  assert.deepEqual(result.items.map(item => item.id), ['SQ-002', 'SQ-001']);
  assert.equal(run(rows, { fromDate: '2026-11-09', toDate: '2026-11-08' }).total, 0);
  assert.equal(run(rows, { fromDate: '2026-02-30' }).total, 0);
  assert.equal(run(rows, { fromDate: 'invalid' }).total, 0);
});

test('授权列表仅显示实际生效或到期的记录，按生效时间查询排序', () => {
  const pending = record(5);
  const effective = record(1, 'effective');
  const expired = record(2, 'expired');
  // 申请提交时间的先后不改变授权记录的生效时间排序。
  effective.submittedAt = base + 9 * day;
  expired.submittedAt = base;
  const result = run([pending, effective, expired], {}, 'grants');
  assert.deepEqual(result.items.map(item => item.id), ['SQ-002', 'SQ-001']);
  assert.equal(run([effective, expired], { status: 'effective' }, 'grants').total, 1);
  assert.equal(run([effective, expired], { fromDate: '2026-11-03', toDate: '2026-11-03' }, 'grants').items[0].id, expired.id);
});

test('排序稳定且不修改调用者数组和查询条件', () => {
  const first = record(1);
  const second = { ...record(2), submittedAt: first.submittedAt };
  const rows = Object.freeze([Object.freeze(first), Object.freeze(second)]);
  const query = Object.freeze({ keyword: '城南', page: 1, pageSize: 10 });
  const before = JSON.stringify(rows);
  assert.deepEqual(run(rows, query).items.map(item => item.id), ['SQ-002', 'SQ-001']);
  assert.equal(JSON.stringify(rows), before);
  assert.equal(run(undefined).total, 0);
  assert.equal(run([null, { type: 'access', status: 'draft' }]).total, 0);
});
