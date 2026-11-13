const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');

function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context);
    Object.assign(context, context.window);
  }
  return { demo: context.DataSourceDemo, impacts: context.GovernanceImpactDemo, governance: context.GovernanceDemo };
}
const finish = (demo, task) => { for (let i = 0; i < 3; i++) demo.advanceTask(task); };

test('failures and cancellations preserve the catalog, change records and existing governance', () => {
  for (const outcome of ['queued-cancel', 'running-cancel', 'network-failure', 'object-failure', 'partial-structure']) {
    const { demo, impacts, governance } = fixture(), source = demo.find('products');
    for (const table of demo.objects(source.id)) governance.get(table);
    const baseline = demo.successfulTask(source.id);
    const snapshot = () => JSON.stringify({ tables: demo.tables, changes: demo.changes, impacts: impacts.records, governance: [...governance.records.values()], lastFull: source.lastFull, baseline: baseline.resultObjects });
    const before = snapshot(), { task } = demo.startTask(source);
    task.resultObjects[0].fields[0].type = 'VARCHAR(100)';
    assert.equal(snapshot(), before, outcome);
    if (outcome === 'queued-cancel') demo.cancelTask(task, '排队取消');
    else {
      demo.advanceTask(task);
      demo.advanceTask(task);
      if (outcome === 'running-cancel') demo.cancelTask(task, '运行取消');
      else {
        if (outcome === 'network-failure') source.health = '网络异常';
        if (outcome === 'object-failure') task.resultObjects[1].readState = '读取失败';
        if (outcome === 'partial-structure') task.resultObjects[1].indexState = '无权限';
        demo.advanceTask(task);
      }
    }
    assert.ok(['采集失败', '已取消'].includes(task.state), outcome);
    assert.equal(impacts.analyzeCollection(task), false, outcome);
    demo.advanceTask(task);
    assert.equal(snapshot(), before, outcome);
    assert.ok(demo.objects(source.id).every(demo.canGovern), outcome);
    assert.equal(demo.changes.some(change => change.taskId === task.id), false, outcome);
  }
});

test('successful retries compare to the latest success and skip every failed or cancelled candidate', () => {
  const { demo } = fixture(), source = demo.find('products');
  const first = demo.startTask(source).task;
  first.resultObjects[0].fields[0].type = 'DECIMAL(12,2)';
  finish(demo, first);
  const failed = demo.startTask(source).task;
  failed.resultObjects[0].fields[0].type = 'VARCHAR(100)';
  failed.resultObjects[1].readState = '读取失败';
  finish(demo, failed);
  const cancelled = demo.startTask(source, failed).task;
  cancelled.resultObjects[0].fields[0].type = 'TEXT';
  demo.advanceTask(cancelled); demo.advanceTask(cancelled); demo.cancelTask(cancelled);
  const retry = demo.startTask(source, cancelled).task;
  assert.equal(retry.beforeSnapshot, first.version);
  retry.resultObjects[0].fields[0].type = 'DECIMAL(18,4)';
  finish(demo, retry);
  const changes = demo.changes.filter(change => change.taskId === retry.id);
  assert.equal(changes.length, 1);
  assert.equal(changes[0].before, 'DECIMAL(12,2)');
  assert.equal(changes[0].after, 'DECIMAL(18,4)');
  assert.equal(changes[0].beforeSnapshot, first.version);
  const unchanged = demo.startTask(source).task;
  assert.equal(unchanged.beforeSnapshot, retry.version);
  finish(demo, unchanged);
  assert.equal(unchanged.analysis, '未发现结构变化');
  assert.equal(demo.changes.some(change => change.taskId === unchanged.id), false);
});

test('incomparable database or schema scopes establish a baseline instead of inventing changes', () => {
  for (const dimension of ['database', 'host', 'port', 'schemas']) {
    const { demo } = fixture();
    const source = demo.saveConfig({ ...demo.configDefaults, type: 'PostgreSQL', name: '范围核对', host: 'pg.example', database: 'erp', port: 5432, account: 'reader', schemas: ['sales'] }, null, true, true);
    const first = demo.startTask(source).task; finish(demo, first);
    const data = demo.configuration(source);
    if (dimension === 'database') data.database = 'erp_next';
    if (dimension === 'host') data.host = 'other-pg.example';
    if (dimension === 'port') data.port = 5433;
    if (dimension === 'schemas') data.schemas = ['sales', 'finance'];
    demo.saveConfig(data, source, true, false);
    const task = demo.startTask(source).task;
    assert.equal(task.beforeSnapshot, '', dimension);
    finish(demo, task);
    assert.equal(task.analysis, '初始基线已建立', dimension);
    assert.equal(demo.changes.some(change => change.taskId === task.id), false, dimension);
  }
});

test('schema order and descriptive edits keep the latest comparable success as their baseline', () => {
  const { demo } = fixture();
  const source = demo.saveConfig({ ...demo.configDefaults, type: 'PostgreSQL', name: '可比范围', host: 'pg.example', database: 'erp', port: 5432, account: 'reader', schemas: ['sales', 'finance'] }, null, true, true);
  const first = demo.startTask(source).task; finish(demo, first);
  demo.saveConfig({ ...demo.configuration(source), schemas: ['finance', 'sales'], purpose: '补充用途' }, source, true, false);
  const second = demo.startTask(source).task;
  assert.equal(second.beforeSnapshot, first.version);
  finish(demo, second);
  assert.equal(second.analysis, '未发现结构变化');
});

test('seeded changes and discovery tasks share the immediately preceding successful snapshot', () => {
  const { demo } = fixture();
  for (const change of demo.changes) {
    const task = demo.tasks.find(task => task.source === change.source && task.id === change.taskId);
    const previous = demo.tasks.filter(other => other.source === task.source && other.scopeKey === task.scopeKey && other.at < task.at && demo.collectionSucceeded(other)).sort((a, b) => b.at.localeCompare(a.at))[0];
    assert.ok(demo.collectionSucceeded(task), change.id);
    assert.equal(change.beforeSnapshot, previous.version, change.id);
    assert.equal(task.beforeSnapshot, previous.version, change.id);
  }
});
