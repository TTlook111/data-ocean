const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');

function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js', 'governance-prototype.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context); Object.assign(context, context.window);
  }
  const { DataSourceDemo: data, GovernanceDemo: gov, GovernancePrototype: prototype, PeopleDemo: people } = context;
  const actor = people.users[0], table = data.objects('products').find(row => row.name === 'sale_order'), record = gov.get(table);
  const note = (id, content) => data.notes.unshift({ id, source: table.source, title: id, content, revision: 1, status: '有效', origin: '用户录入', files: [], author: actor.id, updated: '2026-11-14 14:00:00' });
  const field = (name, type, comment) => {
    table.fields.push({ name, type, comment, nullable: '是', default: '' });
    const entry = { ...structuredClone(record.items[1]), id: 'field:' + name, field: name, name, type, comment, content: { meaning: '', values: '', rules: '', usage: '' } };
    record.items.push(entry); return entry;
  };
  return { data, gov, prototype, actor, table, record, note, field };
}

test('every selected content section is attempted across table, field, relation and metric entries', () => {
  const { gov, prototype, record, actor, note } = fixture();
  const relation = gov.addRelation(record, actor, { name: '订单门店' }).entry;
  const metric = gov.addRelation(record, actor, { name: '订单笔数' }, 'metrics').entry;
  note('资料', 'sale_order.订单门店 关联条件：通过完整门店编号关联。\nsale_order.订单门店 数量关系与完整键：多对一，按租户及门店编号匹配。\nsale_order.订单笔数 指标含义：订单数量。\nsale_order.订单笔数 公式与筛选条件：按订单编号去重计数。');
  const ids = ['overview', 'field:id', relation.id, metric.id];
  assert.equal(prototype.generate(record, ['overview', 'fields', 'relations', 'metrics'], actor, undefined, undefined, { entryIds: ids }), undefined);
  const task = gov.tasksFor(record)[0];
  assert.equal(task.outputs.length, 4);
  for (const output of task.outputs) {
    assert.equal(output.coverage.length, gov.contentSections(output.kind).length);
    assert.ok(output.coverage.every(section => section.attempted));
  }
  assert.match(relation.content.values, /多对一/); assert.match(metric.content.values, /去重/);
  assert.equal(relation.checkContext, null); assert.equal(metric.checkContext, null);
  assert.ok(record.items.filter(entry => !ids.includes(entry.id)).every(entry => entry.ai === null));
});

test('clear amount evidence fills descriptions and unit without questions but remains pending item confirmation', () => {
  const { gov, prototype, record, actor, note, field } = fixture(), entry = field('checkout_total', 'DECIMAL(12,2)', '实际支付金额');
  note('支付说明', 'sale_order.checkout_total 取值说明：单位：元，空值表示未知。\nsale_order.checkout_total 使用口径：表示原支付金额，退款不回写。');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.equal(entry.valueDetails.unit, '元'); assert.match(entry.content.values, /空值表示未知/);
  assert.match(entry.content.rules, /退款不回写/); assert.equal(entry.checkContext, null);
  assert.equal(entry.review, '待确认'); assert.equal(entry.reviewer, ''); assert.equal(gov.summary(record).confirmed, 0);
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined); assert.equal(entry.review, '已确认');
});

test('unknown amount units and refund rules ask specific questions for an arbitrary field name', () => {
  const { prototype, record, actor, field } = fixture(), entry = field('settlement_value', 'DECIMAL(14,2)', '结算金额');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.deepEqual(Array.from(entry.checkContext.questions, question => question.key), ['values', 'rules']);
  assert.match(entry.checkContext.questions[0].question, /元、分/);
  assert.match(entry.checkContext.questions[1].question, /退款|调整/);
  assert.ok(entry.ai.coverage.filter(section => !['values', 'rules'].includes(section.key)).every(section => !section.needsSupplement));
});

test('known units in source comments are drafted rather than redundantly requested', () => {
  const { prototype, record, actor, field } = fixture(), entry = field('balance_due', 'DECIMAL(12,2)', '应付金额（元）');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.equal(entry.valueDetails.unit, '元'); assert.match(entry.content.values, /单位：元/);
  assert.deepEqual(Array.from(entry.checkContext.questions, question => question.key), ['rules']);
});

test('a known unit price does not request unrelated payment or refund rules', () => {
  const { prototype, record, actor, field } = fixture(), entry = field('list_price', 'DECIMAL(12,2)', '商品标价（元）');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.equal(entry.valueDetails.unit, '元'); assert.equal(entry.checkContext, null);
  assert.match(entry.content.rules, /不能按行求和/); assert.doesNotMatch(entry.gap, /退款/);
});

test('different source units are not resolved by source order and both references remain inspectable', () => {
  const { gov, prototype, record, actor, note, field } = fixture(), entry = field('cash_value', 'DECIMAL(12,2)', '支付金额');
  note('制度甲', 'sale_order.cash_value 取值说明：单位：元。\nsale_order.cash_value 使用口径：退款不回写。');
  note('制度乙', 'sale_order.cash_value 取值说明：单位：分。\nsale_order.cash_value 使用口径：退款不回写。');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.equal(entry.content.values, ''); assert.equal(entry.ai.coverage.find(section => section.key === 'values').conflict, true);
  assert.match(entry.checkContext.questions[0].reason, /制度甲/); assert.match(entry.checkContext.questions[0].reason, /制度乙/);
  assert.equal(new Set(entry.references.map(ref => ref.id)).size, 2); assert.match(gov.confirmIssue(record, entry), /回答 AI/);
  const model = { ...entry.content, gap: entry.gap, checkContext: structuredClone(entry.checkContext) };
  model.checkContext.questions[0].response = '采用元，旧版分单位已退出使用。'; gov.confirmAnswer(model, 'values', actor);
  assert.equal(gov.act(record, entry, 'save', actor, model), undefined); assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
});

test('source comments conflicting with business material do not silently become a settled unit', () => {
  const { prototype, record, actor, note, field } = fixture(), entry = field('price_value', 'DECIMAL(12,2)', '金额（元）');
  note('接口说明', 'sale_order.price_value 取值说明：单位：分。\nsale_order.price_value 使用口径：退款不回写。');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.equal(entry.content.values, ''); assert.match(entry.checkContext.questions[0].reason, /原注释单位为元/);
});

test('complementary source detail is retained without manufacturing a conflicting definition', () => {
  const { prototype, record, actor, note, field } = fixture(), entry = field('gross_value', 'DECIMAL(12,2)', '金额');
  note('简版', 'sale_order.gross_value 取值说明：单位：元。\nsale_order.gross_value 使用口径：不扣退款。');
  note('详版', 'sale_order.gross_value 取值说明：单位：元；空值表示未知。\nsale_order.gross_value 使用口径：不扣退款。');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.equal(entry.checkContext, null); assert.match(entry.content.values, /空值表示未知/);
});

test('optional blanks or explicitly undecided table updates do not create generic supplementation tasks', () => {
  const { prototype, record, actor, note } = fixture();
  note('保留说明', 'sale_order 时间与更新：待确认。');
  prototype.generate(record, ['overview'], actor, 'overview');
  const entry = record.items[0]; assert.equal(entry.content.updates, ''); assert.equal(entry.checkContext, null);
  assert.ok(entry.ai.coverage.every(section => section.attempted));
});

test('literal unknown and pending business values are not mistaken for missing evidence', () => {
  const { prototype, record, actor, note } = fixture(), entry = record.items.find(entry => entry.id === 'field:status');
  note('状态字典', 'sale_order.status 取值说明：0=待确认，1=有效，2=完成；空值表示未知。\nsale_order.status 使用口径：有效订单使用 1、2。');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.equal(entry.checkContext, null); assert.match(entry.content.values, /0=待确认/);
});

test('explicitly uncertain critical source text still needs human supplementation', () => {
  const { prototype, record, actor, note, field } = fixture(), entry = field('net_value', 'DECIMAL(12,2)', '金额');
  note('不完整资料', 'sale_order.net_value 取值说明：单位未知。\nsale_order.net_value 使用口径：退款不回写。');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.equal(entry.content.values, ''); assert.deepEqual(Array.from(entry.checkContext.questions, question => question.key), ['values']);
});

test('human clarification updates the separate unit field along with its explanation', () => {
  const { gov, prototype, record, actor, field } = fixture(), entry = field('payment_value', 'DECIMAL(12,2)', '支付金额');
  prototype.generate(record, ['fields'], actor, entry.id);
  const model = { ...entry.content, valueDetails: structuredClone(entry.valueDetails), checkContext: structuredClone(entry.checkContext), gap: entry.gap };
  model.checkContext.questions.find(question => question.key === 'values').response = '单位：元。';
  gov.confirmAnswer(model, 'values', actor);
  assert.equal(model.valueDetails.unit, '元'); assert.equal(model.values, '单位：元。');
});

test('partial regeneration cites only evidence used by the replaced section', () => {
  const { gov, prototype, record, actor, note, field } = fixture(), entry = field('adjusted_value', 'DECIMAL(12,2)', '金额');
  note('含义说明', 'sale_order.adjusted_value 业务含义：调整后的支付金额。');
  note('单位说明', 'sale_order.adjusted_value 取值说明：单位：元。');
  const preview = prototype.preview(record, ['fields'], actor, entry.id);
  assert.equal(prototype.generate(record, ['fields'], actor, entry.id, undefined, { preview, replacements: { [entry.id]: ['meaning'] } }), undefined);
  assert.deepEqual(Array.from(entry.references, ref => ref.id), ['含义说明']);
  assert.equal(gov.tasksFor(record)[0].notes.length, 2);
  assert.equal(entry.content.values, '');
});
