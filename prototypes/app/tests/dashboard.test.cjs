'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const context = vm.createContext({ window: {} });
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理/dashboard-data.js'), 'utf8'), context);
const demo = context.window.DashboardDemo;
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理/module-dashboards.js'), 'utf8'), context);
const query = (from, to, source = 'all') => demo.view({ range: [from, to], source });

test('7天和30天均包含开始、结束日期，不多算前一天', () => {
  for (const days of [7, 30]) {
    const range = demo.presetRange(days), result = demo.view({ range });
    assert.equal(result.points.length, days);
    assert.equal(result.points[0].from, range[0]);
    assert.equal(result.points.at(-1).to, demo.asOf);
    assert.equal(result.count, days * demo.sources.length);
  }
});
test('自定义单日有真实统计点，交付数不会超过本期请求数', () => {
  const result = query('2026-11-03', '2026-11-03');
  assert.equal(result.points.length, 1);
  assert.ok(result.totals.requests > 0);
  assert.ok(result.totals.delivered <= result.totals.requests);
});
test('数据源筛选只统计所选源，全部源按同一期间相加', () => {
  const range = ['2026-10-17', '2026-11-15'];
  const all = demo.view({ range });
  for (const key of ['requests', 'delivered', 'collectionFailure', 'fieldConfirmed', 'availableDocuments', 'feedbackSolved']) {
    const sum = demo.sources.reduce((total, source) => total + demo.view({ range, source: source.id }).totals[key], 0);
    assert.equal(all.totals[key], sum, key);
  }
  assert.ok(demo.view({ range, source: 'unknown' }).error);
});
test('知识存量取期末快照，按周汇总不能把每日存量相加', () => {
  const result = query('2026-09-01', '2026-11-15');
  assert.equal(result.granularity, 'week');
  for (const point of result.points.filter(point => point.from >= demo.startsAt)) {
    const last = query(point.to, point.to).totals.availableDocuments;
    assert.equal(point.availableDocuments, last);
  }
  assert.equal(result.totals.availableDocuments, query('2026-11-15', '2026-11-15').totals.availableDocuments);
});
test('无统计与零区分，部分覆盖保留空缺，月度存量仍取期末', () => {
  const empty = query('2026-08-01', '2026-08-07');
  assert.equal(empty.count, 0);
  assert.equal(empty.totals.availableDocuments, null);
  assert.ok(empty.points.every(point => point.requests === null));
  const monthly = query('2026-01-01', '2026-11-15');
  assert.equal(monthly.granularity, 'month');
  assert.equal(monthly.partial, true);
  assert.equal(monthly.points[0].availableDocuments, null);
  assert.equal(monthly.points.at(-1).availableDocuments, query('2026-11-15', '2026-11-15').totals.availableDocuments);
});
test('倒置区间、无效日期和样例截止日之后的日期不会产生统计', () => {
  for (const range of [['2026-11-10', '2026-11-01'], ['2026-02-30', '2026-03-01'], ['2026-11-15', '2026-11-16'], ['', '2026-11-15']]) {
    assert.ok(demo.validateRange(range));
    assert.equal(demo.view({ range }).points.length, 0);
  }
});

test('模块快照取期末，等待验证是未结问题的子集，结案类型分别计算', () => {
  const month = query('2026-10-17', '2026-11-15'), end = query('2026-11-15', '2026-11-15');
  for (const key of ['pendingChanges','governanceTodo','governanceDoing','governanceDone','governanceReview','knowledgeUpdates','knowledgePaused','knowledgeForbidden','feedbackOpen','feedbackAwaitingVerification']) {
    assert.equal(month.totals[key], end.totals[key], key);
  }
  assert.ok(month.points.every(point => point.feedbackAwaitingVerification <= point.feedbackOpen));
  assert.equal(month.totals.closedIssues, month.totals.feedbackSolved + month.totals.feedbackOtherClosed + month.totals.feedbackMerged);
  const before = query('2026-10-16', '2026-10-16').totals.feedbackOpen;
  assert.equal(month.totals.feedbackOpen, before + month.totals.feedbackNew - month.totals.closedIssues);
});

test('平均耗时按实际完成量加权，空时段不显示零耗时', () => {
  const range = ['2026-10-17','2026-11-15'];
  const all = demo.view({ range });
  const perSource = demo.sources.map(source => demo.view({range,source:source.id}).totals);
  for (const [average,numerator,denominator] of [['collectionDuration','collectionSeconds','collectionSuccess'],['feedbackHours','feedbackResolutionHours','feedbackSolved']]) {
    const expected = Number((perSource.reduce((sum,row)=>sum+row[numerator],0)/perSource.reduce((sum,row)=>sum+row[denominator],0)).toFixed(1));
    assert.equal(all.totals[average], expected);
    assert.equal(query('2026-08-01','2026-08-07').totals[average], null);
  }
});

test('四个模块图表的全部指标可筛选，每页至少三张折线且维护状态不用环形占比', () => {
  const profiles = context.window.ModuleDashboardProfiles;
  assert.equal(Object.keys(profiles).length,4);
  assert.equal(new Set(Object.values(profiles).map(profile=>profile.layout)).size,4);
  for (const profile of Object.values(profiles)) {
    assert.ok(profile.charts.filter(chart=>!chart.kind).length>=3);
    for (const chart of profile.charts) for (const source of ['all',...demo.sources.map(item=>item.id)]) {
      const result = query('2026-11-01','2026-11-07',source);
      for (const item of [...chart.series,...chart.metrics]) assert.equal(typeof result.totals[item.key],'number',profile.title+': '+item.key);
    }
  }
  assert.equal(profiles['knowledge-dashboard'].charts.find(chart=>chart.id==='knowledge-attention').kind,'dots');
});
