'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

global.window = {};
require('../dialogue.js');
const { next } = window.QueryDemo;

const openedAt = Date.UTC(2026, 10, 8, 9);
const minute = 60_000;
const askMilk = () => next(null, '本店上周牛奶卖了多少盒？', openedAt);
const follow = (turn, text, minutes = 1) => next(turn.state, text, openedAt + minutes * minute);

function answer(turn, type, metric, value) {
  assert.equal(turn.reply.kind, 'answer', turn.reply.text);
  const result = turn.reply.result;
  assert.equal(result.type, type);
  assert.equal(result.metric, metric);
  assert.equal(result.value, value);
  const column = { net: 'net', gross: 'sold', stock: 'stock' }[metric];
  assert.equal(result.rows.reduce((sum, row) => sum + row[column], 0), value);
  return result;
}

for (const correction of ['改成本周，不看上周了', '把上周改成本周，其他不变']) {
  test(correction, () => {
    const first = follow(askMilk(), '扣掉，顺便按天列');
    const changed = answer(follow(first, correction), 'sales', 'net', 1010);
    assert.equal(changed.group, 'day');
    assert.equal(changed.rows.length, 7);
    assert.equal(changed.rows[0].date, '2026-11-02');
    assert.equal(changed.rows.at(-1).date, '2026-11-08');
    assert.equal(first.reply.result.value, 1120);
    assert.equal(first.reply.result.rows[0].date, '2026-10-26');
  });
}

for (const filter of ['扣掉退货，只看周末两天', '只看上周六和周日的合计', '那上周末呢？']) {
  test(filter, () => {
    const pending = askMilk();
    const before = filter.startsWith('扣掉') ? pending : follow(pending, '扣掉，顺便按天列');
    const result = answer(follow(before, filter), 'sales', 'net', 382);
    if (filter.includes('合计')) assert.equal(result.group, 'total');
    assert.deepEqual(result.rows.map(row => [row.date, row.net]), [
      ['2026-10-31', 196], ['2026-11-01', 186]
    ]);
  });
}

test('周末筛选下换周并保留其余条件', () => {
  const weekend = follow(askMilk(), '扣掉退货，只看周末两天');
  const result = answer(follow(weekend, '改成本周，其他条件不变'), 'sales', 'net', 277);
  assert.deepEqual(result.rows.map(row => row.date), ['2026-11-07', '2026-11-08']);
});

test('从周末改看整周时恢复七天明细', () => {
  const weekend = follow(askMilk(), '扣掉退货，只看周末两天');
  const week = answer(follow(weekend, '改看整周'), 'sales', 'net', 1120);
  assert.equal(week.rows.length, 7);
  assert.equal(week.rows[0].date, '2026-10-26');
  assert.equal(week.rows.at(-1).date, '2026-11-01');
});

test('首次提问也能只查上周工作日', () => {
  const weekdays = next(null, '上周工作日的牛奶净销量', openedAt);
  const result = answer(weekdays, 'sales', 'net', 738);
  assert.deepEqual(result.rows.map(row => row.date), [
    '2026-10-26', '2026-10-27', '2026-10-28', '2026-10-29', '2026-10-30'
  ]);
});

for (const [correction, total, first, last, count] of [
  ['改成本周，不看周末', 733, '2026-11-02', '2026-11-06', 5],
  ['上周，不看周一', 992, '2026-10-27', '2026-11-01', 6]
]) {
  test(correction, () => {
    const week = follow(askMilk(), '扣掉退货');
    const selected = answer(follow(week, correction), 'sales', 'net', total);
    assert.equal(selected.rows.length, count);
    assert.equal(selected.rows[0].date, first);
    assert.equal(selected.rows.at(-1).date, last);
  });
}

test('按卖出去的算与按售出盒数算使用同一口径', () => {
  const pending = askMilk();
  const spoken = answer(follow(pending, '按卖出去的算，按天列'), 'sales', 'gross', 1200);
  const button = answer(follow(pending, '按售出盒数算'), 'sales', 'gross', 1200);
  assert.equal(spoken.group, 'day');
  assert.deepEqual(spoken.rows, button.rows);
});

for (const question of ['扣掉是什么意思？', '这两种算法有什么区别？']) {
  test(question, () => {
    const pending = askMilk();
    const explained = follow(pending, question, 10);
    assert.ok(!explained.reply.result, '询问含义时应让店长继续选口径');
    assert.match(explained.reply.text, /退货/);
    assert.match(explained.reply.text, /售出|卖出/);
    assert.deepEqual(explained.reply.choices, pending.reply.choices);
    assert.equal(explained.state.request, pending.state.request);
    assert.equal(explained.state.waitingUntil, pending.state.waitingUntil);

    const selected = follow(explained, '按售出盒数算', 29);
    answer(selected, 'sales', 'gross', 1200);
    assert.equal(selected.state.request, pending.state.request);
    const late = follow(explained, '扣掉，顺便按天列', 30);
    const result = answer(late, 'sales', 'net', 1120);
    assert.equal(result.group, 'day');
    assert.equal(late.state.request, pending.state.request + 1);
    assert.equal(late.state.relatedRequest, pending.state.request);
  });
}

test('切到销量后，仍将“它”理解为先前询问的酸奶', () => {
  const stock = next(null, '今天的酸奶库存', openedAt);
  answer(stock, 'inventory', 'stock', 96);
  const sales = follow(stock, '那它上周的净销量呢？');
  assert.equal(sales.reply.kind, 'notice');
  assert.match(sales.reply.text, /酸奶/);
  assert.match(sales.reply.text, /销售|销量|卖出|售出/);
  assert.ok(!sales.reply.result);
});

test('换题时区分本店库存、指定商品和多个商品', () => {
  const sale = follow(askMilk(), '扣掉，顺便按天列');
  const milk = answer(follow(sale, '那今天牛奶还剩几盒？'), 'inventory', 'stock', 248);
  assert.deepEqual(milk.rows.map(row => row.name), ['盒装牛奶']);
  const store = follow(sale, '算了，先看今天库存');
  const total = answer(store, 'inventory', 'stock', 416);
  assert.equal(total.rows.length, 3);
  const pair = answer(follow(store, '那酸奶和豆奶呢？'), 'inventory', 'stock', 168);
  assert.deepEqual(pair.rows.map(row => row.name).sort(), ['盒装豆奶', '盒装酸奶']);
});

for (const question of ['燕麦奶还剩几盒？', '酸奶和燕麦奶呢？']) {
  test('库存样例不完整时不报合计：' + question, () => {
    const store = next(null, '今天库存还有多少？', openedAt);
    answer(store, 'inventory', 'stock', 416);
    const response = follow(store, question);
    assert.equal(response.reply.kind, 'notice', response.reply.text);
    assert.ok(!response.reply.result);
  });
}

test('店长原有的日销量、换周和独立换题流程', () => {
  const pending = askMilk();
  assert.equal(pending.reply.kind, 'clarify');
  const sale = follow(pending, '扣掉，顺便按天列');
  const first = answer(sale, 'sales', 'net', 1120);
  assert.deepEqual(first.rows.map(row => row.net), [128, 146, 132, 158, 174, 196, 186]);
  const changed = follow(sale, '改成本周，其他条件不变', 2);
  answer(changed, 'sales', 'net', 1010);
  answer(follow(changed, '算了，先看今天库存', 3), 'inventory', 'stock', 416);
});
