const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js', 'knowledge-update-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context); Object.assign(context, context.window);
  }
  require('./helpers/governance-scenarios.cjs')(context);
  const { DataSourceDemo: data, GovernanceDemo: gov, GovernanceImpactDemo: impacts, KnowledgeUpdateDemo: knowledge, PeopleDemo: people } = context;
  const actor = people.users[0];
  const review = (record, entry) => {
    assert.equal(gov.act(record, entry, 'save', actor, { meaning: '已核对的' + entry.name, values: ['time', 'updated'].includes(gov.fieldProfile(entry)) ? '北京时间，空值表示尚未产生对应时间。' : '已核对取值', usage: '按适用范围使用', basis: '已核对业务制度', gap: '' }), undefined);
    assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  };
  const publish = record => { for (const action of ['confirm', 'build', 'test', 'publish']) assert.equal(knowledge.act(record, action, actor), undefined, action); };
  return { data, gov, impacts, knowledge, actor, review, publish };
}

test('draft eligibility checks every dependency rather than only the next field', () => {
  const f = fixture(), table = f.data.objects('retail').find(row => row.name === 'sale_order'), record = f.gov.get(table);
  const root = record.items[0], middle = record.items.find(row => row.id === 'field:id'), leaf = record.items.find(row => row.id === 'field:status');
  root.dependencies = [middle.id]; middle.dependencies = [leaf.id];
  root.review = middle.review = '已确认'; root.validity = middle.validity = '适用';
  const scope = f.gov.draftEligibility(record);
  assert.ok(!scope.eligible.includes(root));
  assert.ok(!scope.eligible.includes(middle));
});

test('ordinary governance drafts enter publication and do not wait for unrelated objects', () => {
  const f = fixture(), table = f.data.objects('retail').find(row => row.name === 'sale_order'), record = f.gov.get(table);
  f.review(record, record.items.find(row => row.id === 'field:paid_at'));
  assert.equal(f.gov.summary(record).state, '治理中');
  const result = f.knowledge.generateDraft(table, f.actor);
  assert.equal(result.error, undefined);
  assert.equal(f.knowledge.list('retail').some(row => row.id === result.record.impactId), true);
  f.publish(result.record);
  assert.equal(result.record.state, '已完成');
  assert.equal(f.gov.summary(record).state, '治理中');
  const change = f.data.changes.find(row => row.id === 'CH-014');
  assert.equal(f.impacts.progress(change), '已完成');
  assert.equal(f.impacts.progress(f.data.changes.find(row => row.id === 'CH-015')), '待治理');
});

test('knowledge documents freeze reviewed content while generation cannot publish or alter governance', () => {
  const f = fixture(), table = f.data.objects('retail').find(row => row.name === 'product'), record = f.gov.get(table), before = JSON.stringify(record);
  const result = f.knowledge.generateDraft(table, f.actor);
  assert.equal(result.error, undefined);
  assert.equal(JSON.stringify(record), before);
  assert.ok(result.record.entries.length);
  assert.ok(!result.record.entries.some(row => row.id === 'field:status'));
  const original = result.record.entries[0].content.meaning;
  record.items.find(row => row.id === result.record.entries[0].id).content.meaning = '后续修订';
  assert.equal(result.record.entries[0].content.meaning, original);
  assert.equal(result.record.confirmed, false);
  assert.equal(result.record.release, undefined);
  assert.match(f.knowledge.generateDraft(table, { ...f.actor, roles: ['数据治理员'] }).error, /权限/);
});

test('publication checks live dependency revisions and blocks unreviewed dependencies', () => {
  const f = fixture(), change = f.data.changes.find(row => row.id === 'CH-014'), impact = f.impacts.forChange(change), record = f.gov.get(f.gov.impactTarget(impact).table);
  const entry = record.items.find(row => row.id === 'field:paid_at'), dep = record.items.find(row => row.id === 'field:id');
  entry.dependencies = [dep.id]; f.review(record, entry);
  const update = f.knowledge.start(impact, f.actor).record;
  for (const action of ['confirm', 'build', 'test']) assert.equal(f.knowledge.act(update, action, f.actor), undefined);
  f.gov.act(record, dep, 'pending', f.actor, { reason: '需重新核对编号含义' });
  assert.match(f.knowledge.act(update, 'publish', f.actor), /依赖|审核|确认/);
  assert.equal(update.release, undefined);
});

test('cyclic and cross-source dependencies cannot be published', () => {
  const f = fixture(), table = f.data.objects('retail').find(row => row.name === 'sale_order'), record = f.gov.get(table), entry = record.items[0], dep = record.items.find(row => row.id === 'field:id');
  entry.dependencies = [dep.id]; dep.dependencies = [entry.id];
  assert.match(f.gov.publicationScope(table.source, [{ table: table.id, entry: entry.id }]).issues.join(' '), /循环/);
  dep.dependencies = [{ table: 'finance:sale_order', entry: 'field:id' }];
  assert.match(f.gov.publicationScope(table.source, [{ table: table.id, entry: entry.id }]).issues.join(' '), /当前数据源|来源对象/);
});

test('reviewed fields awaiting release do not prevent dependent business review', () => {
  const f = fixture(), table = f.data.objects('retail').find(row => row.name === 'sale_item'), record = f.gov.get(table), field = record.items.find(row => row.id === 'field:quantity'), metric = record.items.find(row => row.id === 'metric:quantity');
  f.review(record, field);
  assert.equal(field.blocked, true);
  f.review(record, metric);
  assert.equal(metric.review, '已确认');
  assert.equal(field.blocked, true);
});

test('one verified publication can finish overlapping changes without circular allowance waits', () => {
  const f = fixture(), change = f.data.changes.find(row => row.id === 'CH-015'), first = f.impacts.forChange(change), record = f.gov.get(f.gov.impactTarget(first).table), entry = record.items.find(row => row.id === 'field:quantity');
  const note = f.data.notes[0], second = f.impacts.saveNote(note, { ...note, content: '同一数量定义的说明修订' }, f.actor, f.data.noteToken(note)).impact;
  f.gov.associate(second, [{ table: record.table.id, entry: entry.id }], f.actor, f.impacts.token(second));
  f.review(record, entry);
  const update = f.knowledge.start(first, f.actor).record;
  f.publish(update);
  assert.equal(first.state, '知识处理中'); assert.equal(second.state, '知识处理中');
  assert.equal(f.knowledge.act(update, 'allow', f.actor), undefined);
  assert.equal(first.state, '已完成'); assert.equal(second.state, '已完成');
  assert.equal(entry.blocked, false);
});

test('document publication and post-publication allowance retain independent permissions', () => {
  const f = fixture(), change = f.data.changes.find(row => row.id === 'CH-014'), impact = f.impacts.forChange(change), record = f.gov.get(f.gov.impactTarget(impact).table);
  f.review(record, record.items.find(row => row.id === 'field:paid_at'));
  const document = f.knowledge.generateDraft(record.table, f.actor).record;
  for (const action of ['confirm', 'build', 'test']) assert.equal(f.knowledge.act(document, action, f.actor), undefined);
  const reviewer = { ...f.actor, roles: ['知识审核员'] }, publisher = { ...f.actor, roles: ['发布管理员'] };
  assert.match(f.knowledge.act(document, 'publish', reviewer), /权限/);
  assert.equal(document.release, undefined);
  assert.equal(f.knowledge.act(document, 'publish', publisher), undefined);
  assert.match(f.knowledge.act(document, 'allow', publisher), /权限/);
});

test('live revisions of cross-table dependencies invalidate a confirmed document candidate', () => {
  const f = fixture(), table = f.data.objects('retail').find(row => row.name === 'sale_order'), record = f.gov.get(table), relation = record.items.find(row => row.kind === 'relations');
  f.review(record, relation);
  const document = f.knowledge.generateDraft(table, f.actor).record, dep = relation.dependencies[1], related = f.gov.get(f.data.tables.find(row => row.id === dep.table));
  assert.ok(document.entries.some(row => row.table === dep.table && row.id === dep.entry));
  assert.equal(f.knowledge.act(document, 'confirm', f.actor), undefined);
  assert.equal(f.knowledge.act(document, 'build', f.actor), undefined);
  f.review(related, related.items.find(row => row.id === dep.entry));
  assert.equal(relation.review, '待确认'); assert.equal(relation.validity, '待复核');
  assert.match(f.knowledge.actionIssue(document, 'test'), /尚未完成业务审核确认/);
  assert.match(f.knowledge.act(document, 'test', f.actor), /尚未完成业务审核确认/);
});

test('active knowledge cannot retain a deleted dependency even when retirement is included in the same batch', () => {
  const f = fixture(), change = f.data.changes.find(row => row.id === 'CH-013'), impact = f.impacts.forChange(change), record = f.gov.get(f.gov.impactTarget(impact).table), removed = record.items.find(row => row.id === 'field:cost_price');
  f.gov.act(record, removed, 'confirm', f.actor);
  const overview = record.items[0]; overview.dependencies = [removed.id];
  overview.content = {meaning:'历史价格记录。',values:'一行是一条历史价格。',usage:'成本价已删除，需移除相关依赖。'};
  overview.review = '已确认'; overview.validity = '适用'; overview.gap = '';
  impact.bindings.push({ table: record.table.id, entry: overview.id });
  assert.match(f.knowledge.readiness(impact), /依赖已移除/);
});

test('unfinished unrelated entries in a shared source note do not block the reviewed publication scope', () => {
  const f = fixture(), change = f.data.changes.find(row => row.id === 'CH-015'), first = f.impacts.forChange(change), record = f.gov.get(f.gov.impactTarget(first).table), entry = record.items.find(row => row.id === 'field:quantity');
  const note = f.data.notes[0], other = f.impacts.saveNote(note, { ...note, content: '数量和商品引用说明均有修订' }, f.actor, f.data.noteToken(note)).impact;
  f.gov.associate(other, [{ table: record.table.id, entry: entry.id }, { table: record.table.id, entry: 'field:product_id' }], f.actor, f.impacts.token(other));
  f.review(record, entry);
  const document = f.knowledge.start(first, f.actor).record;
  f.publish(document);
  assert.equal(f.knowledge.act(document, 'allow', f.actor), undefined);
  assert.equal(first.state, '已完成'); assert.equal(other.state, '治理中');
  assert.equal(entry.blocked, false);
});

test('changing only declared dependencies creates a review revision and invalidates an old document', () => {
  const f = fixture(), table = f.data.objects('retail').find(row => row.name === 'sale_order'), record = f.gov.get(table), entry = record.items[0];
  const document = f.knowledge.generateDraft(table, f.actor).record, revision = entry.revision;
  assert.equal(f.gov.act(record, entry, 'save', f.actor, { ...entry.content, basis: entry.basis, gap: entry.gap, dependencies: ['field:id'] }), undefined);
  assert.equal(entry.revision, revision + 1); assert.equal(entry.review, '待确认');
  assert.equal(f.gov.act(record, entry, 'confirm', f.actor), undefined);
  assert.match(f.knowledge.actionIssue(document, 'confirm'), /治理修订已变化/);
});
