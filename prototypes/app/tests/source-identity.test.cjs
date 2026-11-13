const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');

function fixture(edit = () => {}) {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context);
    Object.assign(context, context.window);
  }
  const { DataSourceDemo: demo, GovernanceImpactDemo: impacts, GovernanceDemo: governance, PeopleDemo: people } = context;
  const source = demo.find('products'), table = demo.objects(source.id)[0], record = governance.get(table), actor = people.users[0];
  const { task } = demo.startTask(source), result = task.resultObjects.find(row => row.id === table.id);
  result.identity = 'replacement-object'; edit(result);
  for (let i = 0; i < 3; i++) demo.advanceTask(task);
  const change = demo.changes.find(row => row.taskId === task.id && row.kind === '身份待核实');
  return { demo, impacts, governance, actor, table, record, task, change };
}

test('same-object verification closes identity attention without inventing a new generation', () => {
  const { demo, impacts, actor, table, change } = fixture();
  assert.deepEqual(Array.from(impacts.verificationChoices(change)), ['仍为原对象', '确认同名重建']);
  assert.equal(impacts.verify(change, { decision: '仍为原对象', reason: '已向来源维护人员核对标识变更，未发生重建。' }, actor, 0), undefined);
  assert.equal(change.fact, '已排除');
  assert.equal(impacts.progress(change), '已完成');
  assert.equal(table.generation, 1);
  assert.equal(demo.pendingVerification(table).length, 0);
  assert.deepEqual(Array.from(demo.catalogStates(table)), ['已采集']);
  assert.match(impacts.completion(change), /原对象/);
});

test('same-object verification recovers structural differences hidden by the identity question', () => {
  const { demo, impacts, actor, table, task, change } = fixture(row => {
    row.fields[0].type = 'VARCHAR(255)';
    row.fields.push({ name: 'business_at', type: 'DATETIME', nullable: '是', default: '', comment: '业务发生时间' });
  });
  assert.equal(impacts.verify(change, { decision: '仍为原对象', reason: '已核对来源身份和完整字段清单。' }, actor, 0), undefined);
  const recovered = demo.changes.filter(row => row.taskId === task.id && row.id !== change.id);
  assert.deepEqual(Array.from(recovered.map(row => row.kind)).sort(), ['新增字段', '类型变化']);
  assert.ok(recovered.every(row => row.fact === '已确认' && impacts.progress(row) === '待治理'));
  assert.equal(task.analysis, '发现 3 项结构变化');
  assert.ok(demo.catalogStates(table).includes('结构变化'));
});

test('confirmed rebuilding updates generation once and hands off all affected entries through the existing integration', () => {
  const { demo, impacts, actor, table, record, change } = fixture(row => {
    row.fields = row.fields.filter(field => field.name !== 'code');
    row.fields.push({ name: 'new_code', type: 'VARCHAR(32)', nullable: '否', default: '', comment: '新编码' });
  });
  const before = JSON.stringify(change.beforeObject);
  assert.equal(impacts.verify(change, { decision: '确认同名重建', reason: '已核对来源 DDL 及新对象标识。' }, actor, 0), undefined);
  assert.equal(change.kind, '对象重建');
  assert.equal(change.fact, '已确认');
  assert.equal(table.generation, 2);
  assert.equal(demo.pendingVerification(table).length, 0);
  const item = impacts.forChange(change);
  assert.equal(impacts.progress(change), '待治理');
  assert.ok(item.bindings.some(binding => binding.entry === 'field:new_code'));
  assert.ok(record.items.every(entry => item.bindings.some(binding => binding.entry === entry.id) && entry.review === '待确认'));
  assert.ok(record.items.find(entry => entry.id === 'field:code').retired);
  assert.ok(record.items.every(entry => entry.blocked));
  assert.equal(JSON.stringify(change.beforeObject), before);
  assert.match(impacts.verify(change, { decision: '确认同名重建', reason: '重复提交' }, actor, 0), /已由其他操作处理/);
  assert.equal(table.generation, 2);
  assert.ok(demo.catalogStates(table).includes('结构变化'));
  item.state = '知识处理中';
  assert.ok(demo.catalogStates(table).includes('结构变化'));
  item.state = '已完成';
  assert.deepEqual(Array.from(demo.catalogStates(table)), ['已采集']);
});

test('identity decisions require permission, evidence and the currently collected identity', () => {
  const { impacts, actor, table, change } = fixture();
  const before = JSON.stringify(change);
  assert.match(impacts.verify(change, { decision: '确认变化', reason: '通用确认' }, actor, 0), /请选择/);
  assert.match(impacts.verify(change, { decision: '确认同名重建', reason: ' ' }, actor, 0), /依据/);
  assert.match(impacts.verify(change, { decision: '确认同名重建', reason: '核对' }, { ...actor, roles: ['普通用户'] }, 0), /权限/);
  assert.equal(JSON.stringify(change), before);
  table.identity = 'yet-another-object';
  assert.match(impacts.verify(change, { decision: '确认同名重建', reason: '旧结论' }, actor, 0), /身份.*变化/);
  assert.equal(JSON.stringify(change), before);
});

test('ordinary recollection preserves a confirmed generation and does not reopen identity verification', () => {
  const { demo, impacts, actor, table, change } = fixture();
  impacts.verify(change, { decision: '确认同名重建', reason: '已核对重建记录' }, actor, 0);
  const { task } = demo.startTask(demo.find(table.source));
  for (let i = 0; i < 3; i++) demo.advanceTask(task);
  assert.equal(table.generation, 2);
  assert.equal(task.analysis, '未发现结构变化');
  assert.equal(demo.pendingVerification(table).length, 0);
});

test('a collection queued before verification cannot overwrite the confirmed generation', () => {
  const { demo, impacts, actor, table, change } = fixture();
  const { task } = demo.startTask(demo.find(table.source));
  assert.equal(task.resultObjects[0].generation, 1);
  impacts.verify(change, { decision: '确认同名重建', reason: '已核对重建记录' }, actor, 0);
  for (let i = 0; i < 3; i++) demo.advanceTask(task);
  assert.equal(table.generation, 2);
});

test('rebuilding immediately updates the source summary and marks old field meanings for review', () => {
  const { demo, impacts, actor, table, record, change } = fixture();
  record.items.forEach(entry => { entry.review = '已确认'; entry.validity = '适用'; });
  table.governance = '已治理';
  const before = demo.governanceSummary(table.source).confirmed;
  assert.equal(impacts.verify(change, { decision: '确认同名重建', reason: '已核对来源创建记录' }, actor, 0), undefined);
  assert.equal(demo.governanceSummary(table.source).confirmed, before - 1);
  assert.equal(table.governance, '待治理');
  assert.ok(table.governanceAffected);
  assert.ok(table.fields.every(field => field.meaning.includes('待确认')));
});

test('successive identity questions continue from the last verified object without stranding older records', () => {
  const { demo, impacts, actor, table, task: first, change: older } = fixture(row => { row.fields[0].type = 'VARCHAR(255)'; });
  const baseline = older.beforeObject.identity, originalSnapshot = older.beforeSnapshot;
  const { task } = demo.startTask(demo.find(table.source));
  task.resultObjects[0].identity = 'latest-object';
  task.resultObjects[0].fields.push({ name: 'latest_code', type: 'VARCHAR(32)', nullable: '是', default: '', comment: '' });
  for (let i = 0; i < 3; i++) demo.advanceTask(task);
  const latest = demo.changes.find(row => row.taskId === task.id && row.attribute === '对象身份');
  assert.equal(older.forwardTo, latest.id);
  assert.equal(impacts.progress(older), '已完成');
  assert.equal(demo.pendingVerification(table).length, 1);
  assert.equal(latest.identityBaseline.identity, baseline);
  assert.equal(latest.beforeObject.identity, 'replacement-object');
  assert.equal(older.taskId, first.id);
  assert.match(impacts.completion(older), /转交/);
  assert.equal(impacts.verify(latest, { decision: '仍为原对象', reason: '已核对连续采集期间来源创建记录，均为标识重新分配。' }, actor, 0), undefined);
  assert.equal(table.generation, 1);
  assert.equal(demo.pendingVerification(table).length, 0);
  const recovered = demo.changes.filter(row => row.identityVerification === latest.id);
  assert.deepEqual(Array.from(recovered.map(row => row.kind)).sort(), ['新增字段', '类型变化']);
  assert.ok(recovered.every(row => row.beforeSnapshot === originalSnapshot));
});

test('multiple unresolved identity changes increment the confirmed generation only after the current identity is reviewed', () => {
  const { demo, impacts, actor, table, change: first } = fixture();
  let latest;
  for (const identity of ['third-object', 'fourth-object']) {
    const { task } = demo.startTask(demo.find(table.source));
    task.resultObjects[0].identity = identity;
    for (let i = 0; i < 3; i++) demo.advanceTask(task);
    latest = demo.changes.find(row => row.taskId === task.id && row.attribute === '对象身份');
  }
  assert.equal(table.generation, 1);
  assert.equal(demo.pendingVerification(table).length, 1);
  assert.match(impacts.verify(first, { decision: '确认同名重建', reason: '旧记录' }, actor, 0), /已由其他操作处理/);
  assert.equal(impacts.verify(latest, { decision: '确认同名重建', reason: '已核对当前对象相对上次已确认对象发生重建。' }, actor, 0), undefined);
  assert.equal(table.generation, 2);
  assert.equal(demo.pendingVerification(table).length, 0);
  assert.ok(demo.catalogStates(table).includes('结构变化'));
});
