const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js', 'knowledge-update-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context); Object.assign(context, context.window);
  }
  const { DataSourceDemo: data, GovernanceDemo: gov, GovernanceImpactDemo: impacts, PeopleDemo: people } = context;
  const actor = people.users[0], record = gov.get(data.objects('retail').find(table => table.name === 'sale_order'));
  const note = impacts.saveNote(null, { source: 'retail', title: '本次录入的订单定义', content: '订单表每行代表一张订单，以订单编号识别。', files: [] }, actor).note;
  return { data, gov, impacts, actor, record, note, knowledge: context.KnowledgeUpdateDemo };
}
test('business definitions start empty without invented reviewers or human evidence', () => {
  const { gov } = fixture();
  for (const record of gov.records.values()) for (const entry of record.items.filter(entry => !entry.retired)) {
    assert.ok(Object.values(entry.content).every(value => value === ''));
    assert.equal(entry.review, '待确认'); assert.equal(entry.reviewer, ''); assert.equal(entry.reviewedAt, ''); assert.equal(entry.basis, '');
  }
  assert.ok([...gov.records.values()].every(record => record.items.every(entry => !['relations', 'metrics'].includes(entry.kind))));
});
test('preparing AI inputs does not fabricate model output or a supporting citation', () => {
  const { data, gov, record, actor } = fixture();
  assert.equal(gov.generate(record, ['overview'], actor), undefined);
  const entry = record.items[0], task = gov.tasksFor(record)[0];
  assert.equal(task.state, '未生成'); assert.equal(task.outputs.length, 0);
  assert.equal(Object.values(entry.ai.content).some(Boolean), false);
  assert.equal(entry.ai.references.length, 0);
  assert.ok(data.tasks.some(collection => collection.id === entry.ai.structure.collectionId && collection.source === record.table.source && collection.version === entry.ai.structure.snapshot));
  assert.match(gov.act(record, entry, 'adopt', actor), /可采用/);
  assert.equal(entry.basis, '');
});
test('manual evidence binds an existing same-source revision and exact excerpt', () => {
  const { data, gov, impacts, record, actor, note } = fixture(), entry = record.items[0];
  const value = { meaning: '订单主记录', values: '一行一张订单', usage: '', basis: '已核对订单记录粒度', gap: '' };
  const seeded = data.notes.find(note => note.origin === '预置示例');
  assert.ok(!gov.availableNotes('retail').includes(seeded));
  assert.match(gov.act(record, entry, 'save', actor, { ...value, evidence: { noteId: seeded.id, revision: seeded.revision, excerpt: seeded.content } }), /预置示例/);
  assert.equal(gov.act(record, entry, 'save', actor, value), undefined);
  assert.equal(gov.confirmIssue(record, entry), '');
  assert.ok(entry.definitionSource.structure.collectionId);
  assert.match(gov.act(record, entry, 'save', actor, { ...value, evidence: { noteId: note.id, revision: note.revision, excerpt: '原文没有这一句' } }), /原文/);
  const other = impacts.saveNote(null, { source: 'products', title: '另一来源', content: note.content, files: [] }, actor).note;
  assert.match(gov.act(record, entry, 'save', actor, { ...value, evidence: { noteId: other.id, revision: 1, excerpt: other.content } }), /数据源/);
  assert.equal(gov.act(record, entry, 'save', actor, { ...value, evidence: { noteId: note.id, revision: 1, excerpt: note.content } }), undefined);
  const frozen = JSON.stringify(entry.manualEvidence);
  assert.equal(entry.manualEvidence.id, note.id); assert.equal(entry.manualEvidence.revision, 1);
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  assert.equal(entry.reviewer, actor.name);
  assert.ok(data.noteUses.some(use => use.noteId === note.id && use.entryId === entry.id && use.excerpt === note.content));
  impacts.saveNote(note, { ...note, content: '修订后的资料正文' }, actor, data.noteToken(note));
  assert.equal(JSON.stringify(entry.manualEvidence), frozen);
  assert.equal(entry.review, '待确认');
});
test('adoption keeps AI citations separate from manual comments and does not invent a review', () => {
  const { gov, record, actor, note } = fixture(), entry = record.items[0];
  gov.act(record, entry, 'save', actor, { meaning: '人工草稿', values: '一行一张订单', gap: '', basis: '实际填写的补充说明', evidence: { noteId: note.id, revision: 1, excerpt: note.content } });
  entry.ai = { taskId: 'unit-model', content: { meaning: '模型测试草稿', values: '一行一张订单', usage: '' }, gap: '', references: [structuredClone(entry.manualEvidence)], basis: ['模型输入不能冒充人工核对'] };
  assert.equal(gov.act(record, entry, 'adopt', actor), undefined);
  assert.equal(entry.basis, ''); assert.equal(entry.manualEvidence, null);
  assert.equal(entry.review, '待确认'); assert.equal(entry.reviewer, '');
  assert.equal(gov.citations(entry)[0].excerpt, note.content);
  assert.ok(entry.history.some(event => event.basis === '实际填写的补充说明'));
});
test('withdrawn source remains traceable but cannot authorize a new confirmation', () => {
  const { data, gov, record, actor, note } = fixture(), entry = record.items[0];
  gov.act(record, entry, 'save', actor, { meaning: '订单说明', values: '一行一张订单', gap: '', evidence: { noteId: note.id, revision: 1, excerpt: note.content } });
  data.changeNote(note, '撤下', { reason: '资料需重核' }, actor, data.noteToken(note));
  assert.match(gov.confirmIssue(record, entry), /撤下/);
  assert.equal(entry.manualEvidence.content, note.content);
});
test('knowledge freezes the cited source and does not treat preset cases as approved validation', () => {
  const { gov, record, actor, note, knowledge } = fixture(), entry = record.items[0];
  assert.equal(knowledge.testCases.length, 0);
  gov.act(record, entry, 'save', actor, { meaning: '有资料支撑的测试定义', values: '测试粒度', gap: '', evidence: { noteId: note.id, revision: 1, excerpt: note.content } });
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  const { record: document, error } = knowledge.generateDraft(record.table, actor);
  assert.equal(error, undefined);
  assert.equal(document.entries[0].references[0].id, note.id);
  assert.equal(document.entries[0].references[0].excerpt, note.content);
  const impact = knowledge.impactFor(document);
  assert.equal(knowledge.casesFor(impact).length, 0);
  assert.equal(knowledge.act(document, 'confirm', actor), undefined);
  assert.equal(knowledge.act(document, 'build', actor), undefined);
  assert.match(knowledge.act(document, 'test', actor), /已审核题|题单/);
  assert.match(knowledge.act(document, 'publish', actor), /未验证通过/);
});
