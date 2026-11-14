const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture(name = 'sale_order') {
  const c = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js', 'governance-prototype.js', 'knowledge-update-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), c); Object.assign(c, c.window);
  }
  const data = c.DataSourceDemo, gov = c.GovernanceDemo, ai = c.GovernancePrototype, actor = c.PeopleDemo.users[0], table = data.objects('products').find(table => table.name === name), record = gov.get(table);
  const note = (id, content) => data.notes.unshift({ id, source: table.source, title: id, content, status: '有效', origin: '用户录入', revision: 1, author: actor.id, files: [], updated: data.now() });
  const approve = (row, entry, content = {}) => {
    const error = gov.act(row, entry, 'save', actor, { ...entry.content, meaning: entry.content.meaning || '测试记录标识', ...content });
    assert.ok(!error || error === '内容未变化。', error); assert.equal(gov.act(row, entry, 'confirm', actor), undefined);
  };
  const amount = () => { const field = { name: 'paid_amount', type: 'DECIMAL(12,2)', comment: '实际支付金额', nullable: '是' }; table.fields.push(field); const entry = { ...structuredClone(record.items[1]), ...field, id: 'field:paid_amount', field: 'paid_amount', content: { meaning: '', values: '', rules: '', usage: '' } }; record.items.push(entry); return entry; };
  return { data, gov, ai, actor, table, record, note, approve, amount, knowledge: c.KnowledgeUpdateDemo, impacts: c.GovernanceImpactDemo };
}
test('manual and AI drafts share critical confirmation gates while partial saving stays possible', () => {
  const f = fixture(), entry = f.amount();
  assert.equal(f.gov.act(f.record, entry, 'save', f.actor, { meaning: '实际支付金额' }), undefined);
  assert.match(f.gov.confirmIssue(f.record, entry), /单位/); assert.match(f.gov.act(f.record, entry, 'confirm', f.actor), /单位/);
  f.ai.generate(f.record, ['fields'], f.actor, entry.id, undefined, { replacements: { [entry.id]: ['meaning', 'values', 'rules', 'usage'] } });
  assert.ok(f.gov.confirmIssue(f.record, entry));
  const model = { ...entry.content, valueDetails: structuredClone(entry.valueDetails), checkContext: structuredClone(entry.checkContext), gap: entry.gap };
  for (const q of model.checkContext.questions) { q.response = q.key === 'values' ? '单位：元。' : '不用于净收入，退款单独扣除。'; f.gov.confirmAnswer(model, q.key, f.actor); }
  f.approve(f.record, entry, model); assert.equal(entry.review, '已确认');
});
test('business edits invalidate transitive dependents and freeze newly accepted dependency revisions', () => {
  const f = fixture('sale_item'), quantity = f.record.items.find(entry => entry.field === 'quantity'), id = f.record.items.find(entry => entry.field === 'sale_item_id');
  f.approve(f.record, quantity, { values: '单位：件。', rules: '同单位汇总原销售数量。', valueDetails: { unit: '件', mappings: [] } }); f.approve(f.record, id);
  const metric = f.gov.addRelation(f.record, f.actor, { name: '销售数量合计', meaning: '原销售数量合计', values: 'SUM(sale_item.quantity)', dependencies: [{ table: f.table.id, entry: quantity.id }] }, 'metrics').entry;
  f.approve(f.record, metric);
  const derived = f.gov.addRelation(f.record, f.actor, { name: '双倍合计', meaning: '合计乘以二', values: '销售数量合计 × 2', dependencies: [{ table: f.table.id, entry: metric.id }] }, 'metrics').entry; f.approve(f.record, derived);
  const frozen = structuredClone(metric.dependencyRevisions), oldId = JSON.stringify(id), oldContent = JSON.stringify(metric.content);
  f.gov.act(f.record, quantity, 'save', f.actor, { ...quantity.content, values: '单位：千克。', valueDetails: { unit: '千克', mappings: [] } });
  assert.equal(metric.review, '待确认'); assert.equal(derived.review, '待确认'); assert.equal(metric.validity, '待复核');
  assert.equal(JSON.stringify(metric.content), oldContent); assert.equal(JSON.stringify(id), oldId); assert.deepEqual(metric.lastConfirmation.dependencyRevisions, frozen);
  assert.ok(f.gov.confirmIssue(f.record, metric)); f.gov.act(f.record, quantity, 'confirm', f.actor);
  assert.equal(metric.review, '待确认'); assert.ok(!f.gov.draftEligibility(f.record).eligible.includes(metric));
  assert.equal(f.gov.act(f.record, metric, 'confirm', f.actor), undefined); assert.equal(metric.dependencyRevisions[0].revision, quantity.revision);
  assert.equal(derived.review, '待确认'); assert.equal(f.gov.act(f.record, derived, 'confirm', f.actor), undefined);
});
test('formula references, unknown fields and selected dependencies must agree before confirmation', () => {
  const f = fixture(), id = f.record.items.find(entry => entry.field === 'id'); f.approve(f.record, id);
  const metric = f.gov.addRelation(f.record, f.actor, { name: '数量合计', meaning: '销售数量合计', values: 'SUM(sale_item.quantity)', dependencies: [{ table: f.table.id, entry: id.id }] }, 'metrics').entry;
  assert.match(f.gov.confirmIssue(f.record, metric), /尚未纳入依赖.*sale_item.quantity/);
  f.gov.act(f.record, metric, 'save', f.actor, { ...metric.content, values: 'SUM(sale_order.missing_amount)' }); assert.match(f.gov.confirmIssue(f.record, metric), /无法定位.*missing_amount/);
  f.gov.act(f.record, metric, 'save', f.actor, { ...metric.content, values: "COUNT(DISTINCT sale_order.id)，说明文字 'sale_item.quantity' 不作为引用" });
  assert.equal(f.gov.act(f.record, metric, 'confirm', f.actor), undefined);
});
test('dismissed candidates leave work queues, cannot publish and are not recreated by discovery', () => {
  const f = fixture('sale_item'), entry = f.ai.discover(f.record, ['metrics'], f.actor).created.find(row => /SUM/.test(row.entry.content.values)).entry;
  assert.equal(f.gov.act(f.record, entry, 'dismiss', f.actor, { disposition: '不适用' }), undefined);
  assert.ok(!f.gov.attention(f.record).pending.includes(entry)); assert.ok(!f.gov.draftEligibility(f.record).eligible.includes(entry));
  assert.match(f.gov.act(f.record, entry, 'confirm', f.actor), /退出|恢复/);
  assert.equal(f.ai.discover(f.record, ['metrics'], f.actor).created.length, 0); assert.equal(f.gov.isConfirmed(entry), false);
  assert.equal(f.gov.act(f.record, entry, 'restore', f.actor), undefined); assert.ok(f.gov.attention(f.record).pending.includes(entry)); assert.equal(entry.review, '待确认');
});
test('different complete relationship conditions remain separate candidates', () => {
  const f = fixture();
  f.note('分范围关联', 'sale_order.线上订单门店 关联条件：sale_order.store_id = store.id AND sale_order.status = 1\nsale_order.线上订单门店 数量关系与完整键：多对一，仅线上订单。\nsale_order.线下订单门店 关联条件：sale_order.store_id = store.id AND sale_order.status = 2\nsale_order.线下订单门店 数量关系与完整键：多对一，仅线下订单。');
  const result = f.ai.discover(f.record, ['relations'], f.actor);
  assert.equal(result.created.length, 2); assert.ok(result.created.every(row => row.entry.content.meaning && !row.entry.checkContext));
  assert.equal(f.ai.discover(f.record, ['relations'], f.actor).created.length, 0);
});
test('complementary facts merge, while contradictory units, values and negated scopes remain conflicts', () => {
  const f = fixture(), amount = f.amount();
  f.note('单位', 'sale_order.paid_amount 取值说明：单位：元。\nsale_order.paid_amount 使用口径：原支付金额，退款不回写。');
  f.note('空值', 'sale_order.paid_amount 取值说明：空值表示未知。');
  f.ai.generate(f.record, ['fields'], f.actor, amount.id);
  assert.equal(amount.checkContext, null); assert.match(amount.content.values, /单位：元/); assert.match(amount.content.values, /空值表示未知/);
  f.note('范围甲', 'sale_order 数据范围：包含测试订单。'); f.note('范围乙', 'sale_order 数据范围：不包含测试订单。');
  f.ai.generate(f.record, ['overview'], f.actor, 'overview'); assert.ok(f.record.items[0].checkContext.questions.some(q => q.key === 'scope' && q.conflict));
});
test('foreign-key changes are detected and review only related definitions without erasing them', () => {
  const f = fixture('sale_item'), { entry } = f.ai.discover(f.record, ['relations'], f.actor).created[0];
  for (const ref of entry.dependencies) { const table = f.data.objects(f.table.source).find(table => table.id === ref.table), row = f.gov.get(table); f.approve(row, row.items.find(item => item.id === ref.entry)); }
  const model = { ...entry.content, checkContext: structuredClone(entry.checkContext), gap: entry.gap }; model.checkContext.questions[0].response = '多对一，按完整商品编号关联。'; f.gov.confirmAnswer(model, 'values', f.actor); f.approve(f.record, entry, model);
  const content = JSON.stringify(entry.content), id = f.record.items.find(item => item.field === 'sale_item_id'); f.approve(f.record, id); const unchanged = JSON.stringify(id);
  const { task } = f.data.startTask(f.data.find(f.table.source)); task.resultObjects.find(table => table.id === f.table.id).foreignKeys = []; for (let n = 0; n < 3; n++) f.data.advanceTask(task);
  assert.ok(f.data.changes.some(change => change.taskId === task.id && change.kind === '外键变化'));
  assert.equal(entry.review, '待确认'); assert.equal(entry.validity, '待复核'); assert.equal(JSON.stringify(entry.content), content); assert.equal(JSON.stringify(id), unchanged);
});

test('primary-key changes invalidate the affected identifier and its dependents, not unrelated fields', () => {
  const f = fixture('sale_item'), id = f.record.items.find(entry => entry.field === 'sale_item_id'), other = f.record.items.find(entry => entry.field === 'product_id');
  f.approve(f.record, id); f.approve(f.record, other); f.approve(f.record, f.record.items[0], { values: '一行一条订单商品明细。' });
  const metric = f.gov.addRelation(f.record, f.actor, { name: '记录数', meaning: '记录数量', values: 'COUNT(DISTINCT sale_item.sale_item_id)', dependencies: [{ table: f.table.id, entry: id.id }] }, 'metrics').entry; f.approve(f.record, metric);
  const old = JSON.stringify(other), { task } = f.data.startTask(f.data.find(f.table.source)); task.resultObjects.find(table => table.id === f.table.id).primaryKey = ['store_id']; for (let n = 0; n < 3; n++) f.data.advanceTask(task);
  assert.ok(f.data.changes.some(change => change.taskId === task.id && change.kind === '主键变化'));
  assert.equal(id.review, '待确认'); assert.equal(metric.review, '待确认'); assert.equal(JSON.stringify(other), old);
});

test('FK renaming with unchanged pairs does not manufacture a business review task', () => {
  const f = fixture('sale_item'), { task } = f.data.startTask(f.data.find(f.table.source)); task.resultObjects.find(table => table.id === f.table.id).foreignKeys[0].name = 'renamed_fk';
  for (let n = 0; n < 3; n++) f.data.advanceTask(task);
  assert.ok(!f.data.changes.some(change => change.taskId === task.id && change.kind === '外键变化'));
});

test('publication independently rejects a silently changed dependency revision', () => {
  const f = fixture(), id = f.record.items.find(entry => entry.field === 'id'); f.approve(f.record, id);
  const metric = f.gov.addRelation(f.record, f.actor, { name: '记录数', meaning: '记录数量', values: 'COUNT(DISTINCT sale_order.id)', dependencies: [{ table: f.table.id, entry: id.id }] }, 'metrics').entry; f.approve(f.record, metric);
  id.revision++;
  assert.match(f.gov.publicationScope(f.table.source, [{ table: f.table.id, entry: metric.id }]).issues.join('；'), /依赖修订已变化/);
});

test('an accepted definition cannot be withdrawn through the unused-candidate exit', () => {
  const f = fixture(), id = f.record.items.find(entry => entry.field === 'id'); f.approve(f.record, id);
  const metric = f.gov.addRelation(f.record, f.actor, { name: '记录数', meaning: '记录数量', values: 'COUNT(DISTINCT sale_order.id)', dependencies: [{ table: f.table.id, entry: id.id }] }, 'metrics').entry; f.approve(f.record, metric);
  f.gov.act(f.record, metric, 'save', f.actor, { ...metric.content, meaning: '修订中的记录数量' });
  assert.match(f.gov.act(f.record, metric, 'dismiss', f.actor, { disposition: '不适用' }), /已有确认记录/); assert.equal(f.gov.isDismissed(metric), false);
});

test('SQL aliases and string constants resolve against actual references without false dependencies', () => {
  const f = fixture(), id = f.record.items.find(entry => entry.field === 'id'); f.approve(f.record, id);
  const metric = f.gov.addRelation(f.record, f.actor, { name: '记录数', meaning: '记录数量', values: "SELECT COUNT(DISTINCT o.id) FROM products.sale_order AS o /* ignored.missing */ WHERE 'other.source' = 'other.source'", dependencies: [{ table: f.table.id, entry: id.id }] }, 'metrics').entry;
  assert.equal(f.gov.act(f.record, metric, 'confirm', f.actor), undefined);
});
