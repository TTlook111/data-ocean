const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context);
    Object.assign(context, context.window);
  }
  return { demo: context.DataSourceDemo, people: context.PeopleDemo, impacts: context.GovernanceImpactDemo };
}
test('connection drafts never persist raw credentials or grant data access', () => {
  const { demo, people } = fixture(), before = JSON.stringify(people.users);
  const value = { ...demo.configDefaults, name: '新接入库', host: 'new-db.example', database: 'new_database', password: 'must-not-persist' };
  const source = demo.saveConfig(value, null, false, true);
  assert.equal(source.connectionState, '草稿');
  assert.equal(source.health, '未测试');
  assert.equal(JSON.stringify(source).includes('must-not-persist'), false);
  assert.equal(JSON.stringify(people.users), before);
  assert.ok(demo.startTask(source).error);
});
test('partial enumeration never turns unknown object totals into a complete snapshot', () => {
  const { demo } = fixture();
  const failed = demo.tasks.find(task => !task.enumeration && task.state === '失败');
  assert.equal(failed.total, null);
  const { task } = demo.startTask(demo.find('retail'), failed);
  assert.equal(task.retryOnly, false);
  assert.equal(task.enumeration, false);
  assert.equal(task.total, null);
  demo.advanceTask(task);
  assert.equal(task.state, '进行中');
  assert.equal(task.enumeration, false);
});
test('retrying a subset retains the original task and last verified full collection', () => {
  const { demo } = fixture(), source = demo.find('retail'), parent = demo.tasks[0];
  const original = JSON.stringify(parent), lastFull = source.lastFull;
  const { task } = demo.startTask(source, parent);
  for (let i = 0; i < 3; i++) demo.advanceTask(task);
  assert.equal(task.retryOnly, true);
  assert.equal(task.total, 2);
  assert.equal(task.state, '部分成功');
  assert.equal(task.failed, 2);
  assert.equal(source.lastFull, lastFull);
  assert.equal(JSON.stringify(parent), original);
});
test('one active collection per source, and cancellation cannot later become success', () => {
  const { demo } = fixture(), source = demo.find('retail');
  const { task } = demo.startTask(source);
  demo.advanceTask(task);
  assert.ok(demo.startTask(source).error);
  assert.equal(demo.cancelTask(task), true);
  const snapshot = JSON.stringify(task);
  demo.advanceTask(task); demo.advanceTask(task);
  assert.equal(JSON.stringify(task), snapshot);
  assert.equal(task.state, '已取消');
  assert.equal(task.databaseState, '已确认结束');
});
test('stopping a source cancels its running collection and restoring retains prohibitions', () => {
  const { demo } = fixture(), source = demo.find('retail'), forbidden = source.forbidden;
  const { task } = demo.startTask(source);
  demo.advanceTask(task);
  demo.lifecycle(source, '停用数据源', '维护');
  assert.equal(task.state, '已取消');
  assert.ok(demo.startTask(source).error);
  demo.lifecycle(source, '恢复数据源', '核对后恢复');
  assert.equal(source.forbidden, forbidden);
  assert.equal(source.connectionState, '已启用');
});
test('a network failure leaves later connection capabilities unverified', () => {
  const { demo } = fixture(), source = demo.find('finance');
  const results = demo.testResults(demo.configuration(source), source);
  assert.equal(results[1].state, '失败');
  assert.ok(results.slice(2).every(item => item.state === '未验证'));
  assert.equal(demo.canEnable(results), false);
});
test('database version comes from inspection rather than a manual value', () => {
  const { demo } = fixture(), data = { ...demo.configDefaults, version: '999-manual' };
  const result = demo.inspectConnection(data);
  assert.equal(demo.configDefaults.version, '');
  assert.equal(result.version, '8.0.46');
  assert.equal(result.schemas.length, 0);
  assert.equal(result.results.some(item => item.detail.includes('999-manual')), false);
});
test('minimal connection settings use platform policy without per-source TLS or timeout fields', () => {
  const { demo } = fixture();
  const data = { ...demo.configDefaults, name: '平台策略连接', host: 'policy.example', database: 'business', account: 'sample_reader', tls: 'DISABLED', certificate: '', timeout: 0 };
  assert.equal(demo.error(data), undefined);
  const source = demo.saveConfig(data, null, false, true);
  for (const key of ['tls', 'certificate', 'timeout']) assert.equal(key in source.config, false);
  assert.equal(demo.connectionPolicy.verifyServerCertificate, true);
  assert.equal(demo.connectionPolicy.connectTimeoutSeconds, 15);
  assert.equal(Object.isFrozen(demo.connectionPolicy), true);
});
test('failed inspection cannot reuse a previous database version or schema list', () => {
  const { demo } = fixture(), source = demo.find('finance');
  const result = demo.inspectConnection(demo.configuration(source), source);
  assert.equal(result.version, '');
  assert.equal(result.schemas.length, 0);
  assert.equal(demo.canEnable(result.results), false);
});
test('PostgreSQL scope must use a nonempty selection from discovered schemas', () => {
  const { demo } = fixture(), data = { ...demo.configDefaults, type: 'PostgreSQL', schemas: [] };
  const discovery = demo.inspectConnection(data);
  assert.equal(discovery.version, '16.4');
  assert.ok(demo.scopeError(data, discovery.schemas));
  assert.ok(demo.scopeError({ ...data, schemas: ['not_visible'] }, discovery.schemas));
  assert.equal(demo.scopeError({ ...data, schemas: ['sales'] }, discovery.schemas), undefined);
  assert.equal(demo.scopeError({ ...data, type: 'MySQL' }, []), undefined);
});
test('selected schemas determine collection scope and distinguish same-name tables', () => {
  const { demo } = fixture();
  const data = { ...demo.configDefaults, type: 'PostgreSQL', name: 'PG 业务库', host: 'pg.example', database: 'erp', account: 'sample_reader', port: 5432, version: '16.4', schemas: ['sales', 'finance'] };
  const source = demo.saveConfig(data, null, true, true), { task } = demo.startTask(source);
  for (let i = 0; i < 3; i++) demo.advanceTask(task);
  assert.equal(task.state, '完整成功');
  assert.ok(task.scope.includes('sales、finance'));
  assert.equal(task.total, 12);
  const rows = demo.objects(source.id), repeated = rows.filter(row => row.name === 'sale_order');
  assert.equal(rows.length, 12);
  assert.equal(new Set(repeated.map(row => row.id)).size, 2);
  assert.ok(repeated.every(row => row.path.startsWith('erp.')));
  assert.ok(!rows.some(row => row.schema === 'public'));
  demo.saveConfig({ ...data, schemas: ['finance', 'sales'] }, source, true, false);
  assert.equal(source.collection, '完整成功');
  demo.saveConfig({ ...data, schemas: ['sales'] }, source, true, false);
  assert.equal(source.collection, '待重新采集');
  assert.equal(source.lastFull, '');
  assert.equal(demo.objects(source.id).length, 6);
  assert.equal(task.schemas.length, 2);
});

test('retesting unchanged settings and reordering schema selection do not create config revisions', () => {
  const { demo } = fixture(), source = demo.find('retail');
  const revision = source.revision, history = source.history.length;
  demo.saveConfig(demo.configuration(source), source, false, false);
  demo.saveConfig({ ...demo.configuration(source), schemas: ['ignored_for_mysql'] }, source, false, false);
  assert.equal(source.revision, revision);
  assert.equal(source.history.length, history);
  const data = { ...demo.configDefaults, name: 'PG 修订核对', type: 'PostgreSQL', schemas: ['sales', 'finance'] };
  const pg = demo.saveConfig(data, null, false, false), pgRevision = pg.revision;
  demo.saveConfig({ ...data, schemas: ['finance', 'sales'] }, pg, false, false);
  assert.equal(pg.revision, pgRevision);
  demo.saveConfig({ ...data, purpose: '更新用途' }, pg, false, false);
  assert.equal(pg.revision, pgRevision + 1);
});

test('enabling a saved draft reuses its configuration revision while credential rotation creates one', () => {
  const { demo } = fixture(), source = demo.saveConfig({ ...demo.configDefaults, name: '保存后启用' }, null, false, false);
  const revision = source.revision;
  demo.saveConfig(demo.configuration(source), source, true, false);
  assert.equal(source.revision, revision);
  assert.equal(source.connectionState, '已启用');
  demo.saveConfig(demo.configuration(source), source, false, true);
  assert.equal(source.revision, revision + 1);
});

test('cancellation retains the submitted reason and never replaces a verified structure snapshot', () => {
  const { demo } = fixture(), source = demo.find('retail');
  const versions = demo.objects(source.id).map(row => row.version), full = source.lastFull;
  const { task } = demo.startTask(source);
  demo.advanceTask(task);
  assert.equal(demo.cancelTask(task, '  业务系统正在维护  '), true);
  assert.equal(task.reason, '业务系统正在维护');
  assert.equal(source.lastCollected, task.ended);
  assert.equal(source.lastFull, full);
  assert.deepEqual(demo.objects(source.id).map(row => row.version), versions);
});

test('failed retry records the attempt without downgrading the last full-scope outcome', () => {
  const { demo } = fixture(), source = demo.find('retail'), parent = demo.tasks[0];
  const state = source.collection, full = source.lastFull;
  const { task } = demo.startTask(source, parent);
  source.health = '网络异常'; demo.advanceTask(task); demo.advanceTask(task);
  assert.equal(task.state, '失败');
  assert.equal(source.collection, state);
  assert.equal(source.lastCollected, task.ended);
  assert.equal(source.lastFull, full);
});

test('lifecycle operations preserve config revisions and ignore repeated state transitions', () => {
  const { demo } = fixture(), source = demo.find('retail'), revision = source.revision;
  demo.lifecycle(source, '暂停定时采集', '维护');
  const history = source.history.length;
  assert.ok(demo.lifecycle(source, '暂停定时采集', '重复操作'));
  assert.equal(source.history.length, history);
  assert.equal(source.revision, revision);
  demo.lifecycle(source, '停用数据源', '维护');
  demo.lifecycle(source, '退役数据源', '不再使用');
  assert.ok(demo.lifecycle(source, '恢复数据源', '过期操作'));
  assert.equal(source.connectionState, '已退役');
});

test('new collection snapshots do not manufacture structure changes or change object generations', () => {
  const { demo } = fixture(), source = demo.find('products');
  const changes = JSON.stringify(demo.changes), generations = demo.objects(source.id).map(row => row.generation), revision = source.revision;
  const first = demo.startTask(source).task;
  for (let i = 0; i < 3; i++) demo.advanceTask(first);
  const second = demo.startTask(source).task;
  assert.equal(Number(second.version.slice(2)), Number(first.version.slice(2)) + 1);
  for (let i = 0; i < 3; i++) demo.advanceTask(second);
  assert.equal(JSON.stringify(demo.changes), changes);
  assert.deepEqual(demo.objects(source.id).map(row => row.generation), generations);
  assert.equal(source.revision, revision);
});

test('change targets separate actual field names from table attributes without language heuristics', () => {
  const { demo } = fixture();
  const comment = demo.changes.find(item => item.id === 'CH-006');
  assert.equal(comment.field, '');
  assert.equal(comment.attribute, '表注释');
  assert.equal(demo.changeTarget(comment), 'inventory_movement');
  const visibility = demo.changes.find(item => item.id === 'CH-012');
  assert.equal(demo.changeTarget(visibility), 'supplier');
  assert.equal(visibility.attribute, '目录可见性');
  assert.equal(demo.changeTarget(demo.changes.find(item => item.id === 'CH-015')), 'sale_item.quantity');
  assert.equal(demo.changeTarget({ object: '库存表', field: '来源注释' }), '库存表.来源注释');
  assert.equal(demo.changeTarget({ object: 'sales.sale_order', field: 'paid_at' }), 'sales.sale_order.paid_at');
  assert.equal(demo.changeTarget({ object: 'sales_view', field: '', attribute: '视图注释' }), 'sales_view');
});

test('ordinary view examples do not claim physical table indexes or generated defaults', () => {
  const { demo } = fixture(), view = demo.objects('retail').find(item => item.type === '视图');
  assert.equal(view.indexState, '不适用');
  assert.ok(view.fields.every(field => field.default === '—'));
});

test('catalog examples with unresolved definitions are not reported as fully confirmed', () => {
  const { demo } = fixture();
  assert.ok(demo.tables.some(table => table.governance === '部分确认'));
  assert.ok(demo.tables.filter(table => table.governance === '已确认').every(table => table.fields.every(field => !field.meaning.includes('待确认'))));
  for (const source of demo.sources) {
    assert.equal(source.governed, demo.objects(source.id).filter(table => table.governance === '已确认').length);
    assert.equal(source.partialGoverned, demo.objects(source.id).filter(table => table.governance === '部分确认').length);
  }
});

test('collection records retain the configuration revision used when they start', () => {
  const { demo } = fixture(), source = demo.find('retail'), revision = source.revision;
  const { task } = demo.startTask(source);
  demo.saveConfig({ ...demo.configuration(source), purpose: '修改说明，不改本次采集依据' }, source, false, false);
  assert.equal(task.configRevision, revision);
  assert.equal(source.revision, revision + 1);
  assert.ok(demo.tasks.every(item => Number.isInteger(item.configRevision)));
});

test('catalog filters retain missing attributes alongside structural changes without inventing comments', () => {
  const { demo } = fixture(), rows = demo.objects('retail');
  const legacy = rows.find(item => item.name === 'legacy_price');
  assert.ok(demo.catalogStates(legacy).includes('结构变化'));
  assert.ok(demo.catalogStates(legacy).includes('属性缺失'));
  assert.equal(rows.filter(item => demo.catalogStates(item).includes('属性缺失')).length, 2);
  const system = rows.find(item => item.name === 'sys_config');
  assert.equal(system.commentState, '不支持');
  assert.equal(system.comment, '');
  assert.deepEqual(Array.from(demo.catalogStates(system)), ['已采集']);
});

test('change examples refer to the same-source discovery task and a prior reliable snapshot', () => {
  const { demo } = fixture();
  for (const change of demo.changes) {
    const task = demo.tasks.find(item => item.id === change.taskId);
    const before = demo.tasks.find(item => item.source === change.source && item.version === change.beforeSnapshot);
    assert.ok(task && before);
    assert.equal(task.source, change.source);
    assert.equal(task.version, change.afterSnapshot);
    assert.equal(task.ended, change.at);
    assert.equal(before.state, '完整成功');
    assert.ok(before.ended < task.at);
  }
});

test('current catalog examples agree with discovered fields, comments and successful source snapshots', () => {
  const { demo } = fixture(), retail = demo.objects('retail');
  const added = retail.find(table => table.name === 'sale_order').fields.find(field => field.name === 'paid_at');
  assert.equal(added.type, 'DATETIME');
  assert.equal(added.nullable, '是');
  for (const change of demo.changes.filter(item => item.kind === '修改注释')) {
    assert.equal(retail.find(table => table.name === change.object).comment, change.after);
  }
  const task = demo.tasks.find(item => item.source === 'products');
  assert.equal(task.state, '完整成功');
  assert.ok(demo.objects('products').every(table => table.version === task.version && table.collected === task.ended));
});

test('unused notes can be deleted in either state, retaining only the operation record', () => {
  for (const status of ['有效', '已撤下']) {
    const { demo, people } = fixture(), item = demo.notes.find(row => row.id === 'ZL-012');
    item.status = status;
    const before = demo.notes.length, actor = people.users.find(user => user.id === 'u01');
    assert.equal(demo.changeNote(item, '删除', {}, actor, demo.noteToken(item)), undefined);
    assert.equal(demo.notes.length, before - 1);
    assert.equal(demo.notes.includes(item), false);
    assert.equal(demo.noteEvents[0].action, '删除');
    assert.equal(demo.noteEvents[0].actor, actor.name);
    assert.equal(demo.noteEvents[0].content, undefined);
    assert.equal(demo.noteEvents[0].files, undefined);
  }
});

test('current, historical and in-flight note use all prevent deletion', () => {
  const { demo, people } = fixture(), actor = people.users.find(user => user.id === 'u01');
  for (const id of ['ZL-015', 'ZL-014', 'ZL-013', 'ZL-001']) {
    const item = demo.notes.find(row => row.id === id), before = JSON.stringify(item);
    assert.ok(demo.noteDeleteBlock(item));
    assert.ok(demo.changeNote(item, '删除', {}, actor, demo.noteToken(item)));
    assert.equal(JSON.stringify(item), before);
    assert.ok(demo.notes.includes(item));
  }
  const historical = demo.notes.find(row => row.id === 'ZL-013');
  assert.ok(demo.noteUsesFor(historical).every(use => use.revision !== historical.revision));
});

test('delete confirmation rechecks new references and current operation permission', () => {
  const { demo, people } = fixture(), item = demo.notes.find(row => row.id === 'ZL-012'), actor = people.users.find(user => user.id === 'u01');
  const token = demo.noteToken(item);
  demo.noteUses.push({ noteId: item.id, revision: item.revision, kind: '治理任务', state: '排队' });
  assert.ok(demo.changeNote(item, '删除', {}, actor, token));
  demo.noteUses.pop(); actor.roles = ['普通用户'];
  assert.match(demo.changeNote(item, '删除', {}, actor, token), /权限/);
  assert.ok(demo.notes.includes(item));
});

test('withdraw and restore preserve revisions, uses, knowledge and prior reasons', () => {
  const { demo, people } = fixture(), item = demo.notes[0], actor = people.users.find(user => user.id === 'u01');
  const revision = item.revision, uses = JSON.stringify(demo.noteUses), sources = JSON.stringify(demo.sources), history = JSON.stringify(item.history), oldToken = demo.noteToken(item);
  assert.match(demo.changeNote(item, '撤下', { reason: '  ' }, actor, oldToken), /原因/);
  assert.equal(demo.changeNote(item, '撤下', { reason: '  口径需重新核对  ' }, actor, oldToken), undefined);
  assert.equal(item.reason, '口径需重新核对');
  assert.equal(item.status, '已撤下');
  assert.equal(demo.changeNote(item, '恢复', {}, actor, demo.noteToken(item)), undefined);
  assert.equal(item.status, '有效');
  assert.equal(item.reason, '');
  assert.equal(item.revision, revision);
  assert.equal(JSON.stringify(item.history), history);
  assert.equal(JSON.stringify(demo.noteUses), uses);
  assert.equal(JSON.stringify(demo.sources), sources);
  assert.equal(demo.noteEvents.find(event => event.noteId === item.id && event.action === '撤下').reason, '口径需重新核对');
  assert.notEqual(demo.noteToken(item), oldToken);
  assert.match(demo.changeNote(item, '撤下', { reason: '旧确认' }, actor, oldToken), /已变化/);
});

test('restoration is separately permission-controlled and deleted notes reject stale actions', () => {
  const { demo, people } = fixture(), actor = people.users.find(user => user.id === 'u01'), item = demo.notes.find(row => row.id === 'ZL-012');
  const token = demo.noteToken(item);
  assert.equal(demo.changeNote(item, '删除', {}, actor, token), undefined);
  assert.match(demo.changeNote(item, '撤下', { reason: '旧窗口' }, actor, token), /已变化/);
  const withdrawn = demo.notes.find(row => row.status === '已撤下');
  assert.match(demo.changeNote(withdrawn, '恢复', {}, { ...actor, roles: ['普通用户'] }, demo.noteToken(withdrawn)), /权限/);
  assert.equal(withdrawn.status, '已撤下');
});

test('reliable differences are confirmed without treating governance as completed', () => {
  const { demo, impacts } = fixture(), change = demo.changes.find(item => item.id === 'CH-015');
  assert.equal(change.fact, '已确认');
  assert.equal(change.factSource, '采集证据');
  assert.equal(impacts.progress(change), '待治理');
  assert.equal(change.control, '已禁止使用');
  assert.equal(impacts.forChange(change).state, '待治理');
});

test('unresolved verification is not a submitted conclusion and confirmation creates a governance todo directly', () => {
  const { demo, people, impacts } = fixture(), change = demo.changes.find(item => item.id === 'CH-012'), actor = people.users[0];
  const before = JSON.stringify(change);
  assert.match(impacts.verify(change, { decision: '继续核实', reason: '等待源端权限证据' }, actor, 0), /请选择核实结论/);
  assert.equal(JSON.stringify(change), before);
  assert.equal(change.fact, '待核实');
  assert.equal(impacts.progress(change), '待核实');
  assert.equal(impacts.forChange(change), undefined);
  assert.equal(impacts.verify(change, { decision: '确认变化', reason: '已查明来源可见性变化，未确认删除' }, actor, 0), undefined);
  assert.ok(impacts.verify(change, { decision: '确认变化', reason: '旧窗口' }, actor, 0));
  assert.equal(change.fact, '已确认');
  assert.equal(impacts.progress(change), '待治理');
  assert.equal(change.kind, '访问丢失');
  assert.equal(change.control, '待核实');
});

test('excluding a structural change retains evidence and existing access controls', () => {
  const { demo, people, impacts } = fixture(), change = demo.changes.find(item => item.id === 'CH-012');
  assert.equal(impacts.verify(change, { decision: '排除结构变化', reason: '对象仍存在，是目录账号撤权' }, people.users[0], 0), undefined);
  assert.equal(change.fact, '已排除');
  assert.equal(impacts.progress(change), '已完成');
  assert.equal(change.control, '待核实');
  assert.equal(impacts.forChange(change), undefined);
  assert.equal(change.verifications.length, 1);
});

test('note title changes do not create impact work; body changes retain exact revision snapshots and old uses', () => {
  const { demo, people, impacts } = fixture(), note = demo.notes[0], actor = people.users[0];
  const uses = JSON.stringify(demo.noteUses), oldBody = note.content;
  assert.ok(!impacts.saveNote(note, { ...note, title: '销售与退货说明' }, actor, demo.noteToken(note)).impact);
  assert.equal(note.revision, 2);
  const result = impacts.saveNote(note, { ...note, content: '销售额改按含税金额，退货按原销售日冲减。' }, actor, demo.noteToken(note));
  assert.equal(result.impact.state, '待治理');
  assert.equal(result.impact.source, note.source);
  assert.equal(result.impact.beforeRevision, 2);
  assert.equal(result.impact.noteRevision, 3);
  assert.equal(result.impact.references[0].revision, 1);
  assert.ok(result.impact.before.includes(oldBody));
  assert.ok(result.impact.after.includes('含税金额'));
  assert.equal(JSON.stringify(demo.noteUses), uses);
  impacts.saveNote(note, { ...note, content: '另一次修改' }, actor, demo.noteToken(note));
  assert.ok(result.impact.after.includes('含税金额'));
  assert.equal(impacts.forNote(note).length, 2);
});

test('file replacement and withdrawal create review work, while unused notes and restoration do not', () => {
  const { demo, people, impacts } = fixture(), actor = people.users[0], note = demo.notes[0], unused = demo.notes.find(item => item.id === 'ZL-012');
  assert.ok(impacts.saveNote(note, { ...note, replacedFile: true }, actor, demo.noteToken(note)).impact);
  assert.ok(!impacts.saveNote(unused, { ...unused, content: '首次补充未引用资料' }, actor, demo.noteToken(unused)).impact);
  demo.changeNote(note, '撤下', { reason: '口径需确认' }, actor, demo.noteToken(note));
  const record = impacts.forNote(note)[0], count = impacts.forNote(note).length;
  assert.equal(record.action, '撤下');
  assert.equal(record.state, '待治理');
  demo.changeNote(note, '恢复', {}, actor, demo.noteToken(note));
  assert.equal(impacts.forNote(note).length, count);
  assert.equal(record.state, '待治理');
});
