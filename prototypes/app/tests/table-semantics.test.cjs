const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture(reviewed = false) {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js', 'knowledge-update-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context); Object.assign(context, context.window);
  }
  if (reviewed) require('./helpers/governance-scenarios.cjs')(context);
  return { data: context.DataSourceDemo, gov: context.GovernanceDemo, knowledge: context.KnowledgeUpdateDemo, actor: context.PeopleDemo.users[0] };
}
test('confirmed table examples have a record grain and do not use generic filler', () => {
  const { data, gov } = fixture();
  for (const table of data.tables) {
    const entry = gov.get(table).items[0];
    assert.doesNotMatch(JSON.stringify(entry.content), /的业务记录。|按已确认的字段含义使用/);
    if (entry.review === '已确认') {
      assert.ok(entry.content.meaning.trim(), table.id);
      assert.ok(entry.content.values.trim(), table.id + ' lacks record grain');
      assert.ok(entry.basis.trim(), table.id);
    }
  }
});
test('missing record grain blocks both table confirmation and document inclusion', () => {
  const { data, gov, actor } = fixture(), record = gov.get(data.objects('retail').find(table => table.name === 'sale_order')), entry = record.items[0];
  entry.content.values = ''; entry.gap = '';
  entry.content.meaning = '待核对的订单用途';
  assert.match(gov.confirmIssue(record, entry), /一行|记录粒度/);
  assert.equal(gov.act(record, entry, 'confirm', actor), gov.confirmIssue(record, entry));
  assert.ok(!gov.draftEligibility(record).eligible.includes(entry));
});
test('knowledge drafts keep semantic labels and exact field revision references for SQL follow-up', () => {
  const { data, gov, knowledge, actor } = fixture(true), table = data.objects('retail').find(table => table.name === 'sale_order'), record = gov.get(table);
  const id = record.items.find(item => item.id === 'field:id');
  id.ai = { content: { meaning: '尚未审核的新建议' } };
  id.proposal = { meaning: '未经确认的候选解释' };
  const { record: document, error } = knowledge.generateDraft(table, actor);
  assert.equal(error, undefined);
  assert.match(document.note, /一行代表什么/);
  assert.match(document.note, /业务含义/);
  assert.match(document.note, /sale_order\.id/);
  const entry = document.entries.find(item => item.id === 'field:id');
  assert.equal(entry.source, table.source);
  assert.equal(entry.table, table.id);
  assert.equal(entry.objectId, table.identity);
  assert.equal(entry.generation, table.generation);
  assert.equal(entry.revision, record.items.find(item => item.id === 'field:id').revision);
  assert.equal(Object.hasOwn(entry, 'ai'), false);
  assert.equal(Object.hasOwn(entry, 'proposal'), false);
  assert.equal(Object.hasOwn(entry, 'history'), false);
  assert.doesNotMatch(JSON.stringify(document.entries), /尚未审核的新建议|未经确认的候选解释/);
  assert.ok(!document.entries.some(item => item.id === 'field:status' || item.id === 'field:paid_at'));
  const frozen = document.note;
  record.items.find(item => item.id === 'field:id').content.meaning = '后续编辑';
  assert.equal(document.note, frozen);
});

test('knowledge text retains confirmed table scope, updates, calculation rules and structured field values', () => {
  const { data, gov, knowledge, actor } = fixture(true), table = data.objects('retail').find(table => table.name === 'sale_order'), record = gov.get(table);
  const overview = record.items[0], field = record.items.find(item => item.id === 'field:id');
  assert.equal(gov.act(record, overview, 'save', actor, { ...overview.content, scope: '正式订单，不含测试单', updates: '每小时同步，保留上线以来记录' }), undefined);
  assert.equal(gov.act(record, overview, 'confirm', actor), undefined);
  assert.equal(gov.act(record, field, 'save', actor, { ...field.content, rules: '按编号去重计数', valueDetails: { unit: '笔', mappings: [{ code: '01', meaning: '示例标识' }] } }), undefined);
  assert.equal(gov.act(record, field, 'confirm', actor), undefined);
  const { record: document, error } = knowledge.generateDraft(table, actor);
  assert.equal(error, undefined);
  assert.match(document.note, /数据范围：正式订单，不含测试单/);
  assert.match(document.note, /时间与更新：每小时同步/);
  assert.match(document.note, /使用口径：按编号去重计数/);
  assert.match(document.note, /单位：笔/); assert.match(document.note, /01 — 示例标识/);
  field.valueDetails.mappings[0].meaning = '后续草稿';
  assert.doesNotMatch(document.note, /后续草稿/);
  assert.equal(document.entries.find(item => item.id === field.id).valueDetails.mappings[0].meaning, '示例标识');
});
