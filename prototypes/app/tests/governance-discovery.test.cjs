const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture(name = 'sale_item') {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js', 'governance-prototype.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context); Object.assign(context, context.window);
  }
  const { DataSourceDemo: data, GovernanceDemo: gov, GovernancePrototype: prototype, PeopleDemo: people } = context;
  const actor = people.users[0], table = data.objects('products').find(table => table.name === name), record = gov.get(table);
  const note = (id, content) => data.notes.unshift({ id, source: table.source, title: id, content, revision: 1, status: '有效', origin: '用户录入', files: [], author: actor.id, updated: data.now() });
  return { data, gov, prototype, actor, table, record, note };
}
test('foreign keys create visible draft relations only after discovery and do not confirm business cardinality', () => {
  const { gov, prototype, actor, record } = fixture(), before = gov.summary(record);
  assert.equal(gov.relationsFor(record).length, 0);
  const result = prototype.discover(record, ['relations'], actor);
  assert.equal(result.error, undefined); assert.equal(result.created.length, 1);
  const { entry } = result.created[0];
  assert.match(entry.content.meaning, /sale_item.product_id = product.id/);
  assert.equal(entry.content.values, ''); assert.equal(entry.dependencies.length, 2);
  assert.equal(entry.review, '待确认'); assert.equal(entry.reviewer, ''); assert.ok(entry.checkContext.questions.some(question => question.key === 'values'));
  assert.equal(gov.relationsFor(record)[0].entry, entry);
  assert.equal(gov.summary(record).total, before.total); assert.equal(gov.summary(record).confirmed, before.confirmed);
  assert.ok(gov.tasksFor(record)[0].discoveryStructure.some(table => table.foreignKeys.length));
});
test('the same discovered relation is referenced at both tables and repeated discovery preserves edits', () => {
  const { data, gov, prototype, actor, record } = fixture('product');
  const first = prototype.discover(record, ['relations'], actor), { record: owner, entry } = first.created[0];
  assert.equal(owner.table.name, 'sale_item'); assert.equal(gov.relationsFor(record)[0].entry, entry); assert.ok(!record.items.includes(entry));
  assert.equal(gov.act(owner, entry, 'save', actor, { ...entry.content, name: '人工修改后的商品关联' }), undefined);
  const frozen = JSON.stringify(entry), repeated = prototype.discover(owner, ['relations'], actor);
  assert.equal(repeated.created.length, 0); assert.equal(repeated.skipped.length, 1); assert.equal(JSON.stringify(entry), frozen);
  const other = data.objects('products').find(table => table.name === 'sale_item'); assert.equal(gov.relationsFor(gov.get(other)).length, 1);
});
test('structural metrics are drafts with exact dependencies and aggregation uncertainties stay pending', () => {
  const { gov, prototype, actor, record } = fixture();
  const result = prototype.discover(record, ['metrics'], actor);
  assert.equal(result.created.length, 2);
  const count = result.created.find(row => /COUNT/.test(row.entry.content.values)).entry, sum = result.created.find(row => /SUM/.test(row.entry.content.values)).entry;
  assert.match(count.content.values, /COUNT\(DISTINCT sale_item.sale_item_id\)/);
  assert.ok(count.dependencies.some(ref => ref.entry === 'overview')); assert.equal(count.review, '待确认');
  assert.ok(sum.checkContext.questions.some(question => question.key === 'values')); assert.match(gov.confirmIssue(record, count), /依赖/);
  assert.equal(record.items.filter(item => item.kind === 'relations').length, 0);
});
test('business material discovers named relations and metrics without prior manual creation', () => {
  const { gov, prototype, actor, record, note } = fixture('sale_order');
  note('业务定义', 'sale_order.订单门店 关联条件：sale_order.store_id = store.id\nsale_order.订单门店 数量关系与完整键：多个订单对应一个门店。\nsale_order.订单笔数 指标含义：订单数量。\nsale_order.订单笔数 公式与筛选条件：COUNT(DISTINCT sale_order.id)');
  const result = prototype.discover(record, ['relations', 'metrics'], actor);
  assert.equal(result.error, undefined); assert.equal(result.created.length, 2);
  const relation = result.created.find(row => row.entry.kind === 'relations').entry, metric = result.created.find(row => row.entry.kind === 'metrics').entry;
  assert.equal(relation.name, '订单门店'); assert.equal(relation.dependencies.length, 2); assert.equal(relation.checkContext, null);
  assert.equal(metric.name, '订单笔数'); assert.equal(metric.dependencies.length, 2); assert.ok(metric.dependencies.some(ref => ref.entry === 'overview')); assert.equal(metric.checkContext, null);
  assert.ok(metric.references.every(ref => ref.id === '业务定义' && ref.content.includes(ref.excerpt)));
  assert.equal(gov.summary(record).confirmed, 0);
});
test('business relation detail enriches the same FK candidate rather than duplicating it', () => {
  const { prototype, actor, record, note } = fixture();
  note('商品关联', 'sale_item.明细商品 关联条件：sale_item.product_id = product.id\nsale_item.明细商品 数量关系与完整键：多对一；完整商品编号关联。');
  const result = prototype.discover(record, ['relations'], actor);
  assert.equal(result.created.length, 1); assert.equal(result.created[0].entry.name, '明细商品');
  assert.equal(result.created[0].entry.checkContext, null);
});
test('conflicting relation materials remain a concrete question with both sources', () => {
  const { prototype, actor, record, note } = fixture('sale_order');
  note('制度甲', 'sale_order.订单门店 关联条件：sale_order.store_id = store.id\nsale_order.订单门店 数量关系与完整键：多对一。');
  note('制度乙', 'sale_order.订单门店 数量关系与完整键：一对一。');
  const { entry } = prototype.discover(record, ['relations'], actor).created[0];
  assert.equal(entry.content.values, ''); assert.ok(entry.checkContext.questions[0].conflict);
  assert.equal(new Set(entry.references.map(ref => ref.id)).size, 2);
});
test('discovery respects permissions, type scope, no-evidence empty results and stale input protection', () => {
  const { gov, prototype, data, actor, record } = fixture('store'), count = record.items.length;
  assert.match(prototype.discover(record, ['relations'], { ...actor, roles: [] }).error, /权限/); assert.equal(record.items.length, count);
  const empty = prototype.discover(record, ['relations'], actor); assert.equal(empty.created.length, 0);
  const signature = prototype.discoverySignature(record); record.table.comment += '变更'; record.table.fields[0].comment += '变更';
  assert.match(prototype.discover(record, ['metrics'], actor, signature).error, /变化/); assert.equal(record.items.length, count);
  assert.equal(gov.summary(record).confirmed, 0); assert.equal(data.objects(record.table.source).filter(table => table.name === 'store').length, 1);
});
test('named metric declarations retain references and unresolved field mappings require supplementation', () => {
  const { prototype, actor, record, note } = fixture('sale_order');
  note('指标说明', '指标 支付合计：SUM(sale_order.unknown_amount)\nsale_order.支付合计 指标含义：支付金额合计。');
  const result = prototype.discover(record, ['metrics'], actor), metric = result.created.find(row => row.entry.name === '支付合计');
  // 无法定位的全新技术映射不凭名称猜定，明确对象的资料仍可形成待补充草稿。
  assert.ok(metric); assert.match(metric.entry.checkContext.question, /表字段|定位/);
  assert.match(metric.entry.content.values, /SUM\(sale_order.unknown_amount\)/);
});

test('supplementing metric scope preserves the known formula and records only the human conclusion', () => {
  const { gov, prototype, actor, record } = fixture();
  const sum = prototype.discover(record, ['metrics'], actor).created.find(row => /SUM/.test(row.entry.content.values)).entry;
  const model = { ...sum.content, gap: sum.gap, checkContext: structuredClone(sum.checkContext) }, formula = sum.content.values;
  model.checkContext.questions[0].response = '仅汇总有效销售明细，退货由独立指标扣除。';
  gov.confirmAnswer(model, 'values', actor);
  assert.ok(model.values.startsWith(formula)); assert.match(model.values, /有效销售明细/);
  assert.equal(model.checkContext.questions[0].answer, '仅汇总有效销售明细，退货由独立指标扣除。');
});

test('regeneration of a named declaration uses the current material revision', () => {
  const { gov, prototype, data, actor, record, note } = fixture('sale_order');
  note('记录指标', '指标 订单笔数：COUNT(DISTINCT sale_order.id)');
  const entry = prototype.discover(record, ['metrics'], actor).created.find(row => row.entry.name === '订单笔数').entry;
  const material = data.notes.find(note => note.id === '记录指标'); material.content = '指标 订单笔数：COUNT(DISTINCT sale_order.code)'; material.revision++;
  assert.equal(prototype.generate(record, ['metrics'], actor, entry.id), undefined);
  assert.match(entry.content.values, /sale_order.code/); assert.ok(entry.references.every(ref => ref.revision === 2));
  assert.equal(gov.tasksFor(record)[0].notes[0].revision, 2);
});

test('confirmed candidates survive repeated discovery and source-key snapshots remain frozen', () => {
  const { gov, prototype, actor, record, data } = fixture();
  const { entry, record: owner } = prototype.discover(record, ['relations'], actor).created[0];
  const task = gov.tasksFor(owner)[0];
  for (const ref of entry.dependencies) {
    const table = data.objects(owner.table.source).find(table => table.id === ref.table), row = gov.get(table), field = row.items.find(item => item.id === ref.entry);
    gov.act(row, field, 'save', actor, { meaning: '测试确认字段定义' }); gov.act(row, field, 'confirm', actor);
  }
  const model = { ...entry.content, checkContext: structuredClone(entry.checkContext), gap: entry.gap };
  model.checkContext.questions[0].response = '多对一，按完整商品编号关联，不增加明细行。'; gov.confirmAnswer(model, 'values', actor);
  gov.act(owner, entry, 'save', actor, model); assert.equal(gov.act(owner, entry, 'confirm', actor), undefined);
  const confirmed = JSON.stringify(entry);
  assert.equal(prototype.discover(owner, ['relations'], actor).created.length, 0); assert.equal(JSON.stringify(entry), confirmed);
  record.table.foreignKeys[0].target = 'store';
  assert.equal(task.entries[0].structure.foreignKeys[0].target, 'product');
  assert.equal(task.discoveryStructure.find(table => table.id === record.table.id).foreignKeys[0].target, 'product');
});
