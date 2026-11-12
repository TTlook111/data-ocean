const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context);
    Object.assign(context, context.window);
  }
  return { demo: context.DataSourceDemo, people: context.PeopleDemo };
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
