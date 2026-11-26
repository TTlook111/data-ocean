import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
import { IdentityClient } from './iam-client.mjs';
import { developmentPassword, IdentityFixture } from './iam-fixtures.mjs';

// 本轮用例只允许命名好的隔离库、环回服务和临时凭据文件。
const address = process.env.DATAOCEAN_ACCEPTANCE_URL;
const fixtureFile = process.env.DATAOCEAN_GOVERNANCE_FIXTURE;
const uiPasswordFile = process.env.DATAOCEAN_GOVERNANCE_UI_PASSWORD_FILE;
assert.match(address ?? '', /^http:\/\/127\.0\.0\.1:\d+$/);
assert.match(fixtureFile ?? '', /^\/tmp\/dataocean-gov\.[A-Za-z0-9]+\/fixture\.env$/);
assert.ok(uiPasswordFile);
const distro = process.env.DATAOCEAN_B_DOCKER_DISTRO ?? 'Ubuntu2204';
const fixture = spawnSync('wsl.exe', ['-d', distro, '--exec', 'cat', fixtureFile], { encoding: 'utf8' });
assert.equal(fixture.status, 0, '隔离库专用配置不可读');
const secrets = Object.fromEntries(fixture.stdout.trim().split(/\r?\n/).map((line) => line.split('=', 2)));
const out = new URL('../../output/acceptance/c-governance-20261119/', import.meta.url);
const reportPath = new URL('api-report.json', out);
const report = JSON.parse(await readFile(reportPath, 'utf8').catch(
  () => JSON.stringify({ cases: [], sources: {}, runtime: address }),
));
const admin = new IdentityClient(address);
const uiPassword = (await readFile(uiPasswordFile, 'utf8')).trim();

/**
 * 记录已执行用例及可复验的安全业务标识。
 */
function passed(id, facts) {
  report.cases.push({ id, facts });
  writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n', 'utf8');
  console.log(id, '通过');
}

/**
 * 通过当前线上等价身份会话写入，命令回执使用唯一编号。
 */
function write(path, body, method = 'POST', command = crypto.randomUUID(), status = 200) {
  return admin.ok(path, { method, body, command }, status);
}

/**
 * 数据源只连接本轮已核对的独立验收库。
 */
async function createSource(databaseType) {
  const postgresql = databaseType === 'POSTGRESQL';
  const name = postgresql ? 'C批合成商品 PostgreSQL' : 'C批合成商品 MySQL';
  const options = await admin.ok('/datasources/options?purpose=GOVERNANCE');
  const present = options.find((source) => source.name === name);
  if (present) return present.id;
  // 核对真实源的只读账户及配置，凭据不进入报告。
  const configuration = {
    name, purpose: 'C 批隔离业务源', databaseType, host: '127.0.0.1',
    port: postgresql ? 5433 : 3307, databaseName: 'do_gov_source_20261119',
    account: postgresql ? 'do_gov_pg_reader_19' : 'do_gov_reader_19',
    schemas: postgresql ? ['c_sales'] : [], technicalContactId: null, businessContactId: null,
  };
  const runtimePolicy = {
    maxConnections: 4, collectionConcurrency: 2, retryLimit: 1, taskTimeoutSeconds: 180,
    queryTimeoutSeconds: 12, rowLimit: 200, syncHours: 24, windowStart: '02:00', windowEnd: '05:00',
  };
  const password = postgresql ? secrets.GOV_POSTGRES_PASSWORD : secrets.GOV_SOURCE_PASSWORD;
  assert.ok(password, '专用只读账户未配置');
  const check = await write('/datasources/connection-checks', { configuration, runtimePolicy, password },
    'POST', crypto.randomUUID(), 201);
  assert.equal(check.canEnable, true, '真实只读连接检查失败');
  const proposal = { configuration, runtimePolicy, credentialProvided: true, checkId: check.checkId, targetLifecycle: 'ENABLED' };
  const preview = await admin.ok('/datasources/impact-preview', { method: 'POST', body: { proposal } });
  const result = await write('/datasources', { proposal, password, reviewToken: preview.reviewToken },
    'POST', crypto.randomUUID(), 201);
  return result.targetId;
}

/**
 * 对本轮建立的来源发起正式采集，读取原任务的真正终态。
 */
async function collect(source) {
  const path = '/datasources/' + source;
  const row = await admin.ok(path);
  const input = { action: 'START_COLLECTION', expectedVersion: row.source.version, proposal: {} };
  const review = await admin.ok(path + '/impact-preview', { method: 'POST', body: input });
  const accepted = await write(path + '/collections', {
    expectedVersion: row.source.version, reviewToken: review.reviewToken,
  }, 'POST', crypto.randomUUID(), 202);
  for (let turn = 0; turn < 120; turn++) {
    // 使用真实终态和快照编号，不重复提交后台任务。
    const detail = await admin.ok('/collection-tasks/' + accepted.taskId);
    if (!['QUEUED', 'RUNNING'].includes(detail.state)) {
      assert.equal(detail.state, 'SUCCEEDED', JSON.stringify(detail.content));
      return detail.content.snapshotId;
    }
    await delay(500);
  }
  throw new Error('隔离源采集未在预算内取得终态');
}

/**
 * 由已打开条目构造明确的版本绑定。
 */
function precondition(item) {
  return {
    expectedVersion: item.version,
    expectedStructureRevisionId: item.structureBasis.columnRevisionId ?? item.structureBasis.objectRevisionId,
  };
}

/**
 * 保存企业资料现行正文，保存结果随固定修订回读。
 */
async function submitNote(source, title, body, id, expectedVersion) {
  const multipart = new FormData();
  multipart.set('metadata', new Blob([JSON.stringify({ sourceId: source, title, body, expectedVersion })],
    { type: 'application/json' }));
  const result = await write(id ? '/business-notes/' + id + '/revisions' : '/business-notes', multipart);
  return admin.ok('/business-notes/' + result.targetId);
}

/**
 * 由实际回读的资料正文与修订生成业务字段证据。
 */
function reference(note, contentPath) {
  return {
    contentPath, noteId: note.note.id, revisionId: note.content.revisionId,
    location: { section: 'BODY', start: 0, end: note.content.body.length },
  };
}

/**
 * 只改动已预置的合成对象，正式采集仍用 B 批只读连接。
 */
function changeSource(type, sql) {
  const mysql = type === 'MYSQL';
  const command = mysql
    ? ['mysql', 'sh', '-ec', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root --batch --default-character-set=utf8mb4 do_gov_source_20261119']
    : ['dataocean-postgres-acceptance-20261118', 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-q', '-U', 'postgres',
        '-d', 'do_gov_source_20261119'];
  const result = spawnSync('wsl.exe', ['-d', distro, '--exec', 'docker', 'exec', '-i', ...command],
    { input: sql, encoding: 'utf8' });
  assert.equal(result.status, 0, '独立合成源结构变更失败');
}

/**
 * 首次在隔离平台设置其开发管理员的开发口令，后续沿用该真实身份。
 */
async function login() {
  const existing = await admin.request('/auth/login', {
    method: 'POST', body: { username: 'admin', password: uiPassword },
  });
  if (existing.status === 200) {
    // 已完成初始化的隔离平台正常复用。
    admin.token = null;
    return;
  }
  const initial = await admin.login('admin', developmentPassword);
  assert.equal(initial.mustChangePassword, true);
  const state = await admin.ok('/auth/me');
  await write('/auth/password/initial', {
    expectedVersion: state.version, newPassword: uiPassword, confirmPassword: uiPassword,
  });
  await admin.login('admin', uiPassword);
}

/**
 * 对真实模型验证任务防重、资料取用、保存及人工确认。
 */
async function model(source, object, itemPath, note) {
  const base = '/governance/sources/' + source;
  const path = base + '/objects/' + object;
  const item = await admin.ok(itemPath);
  const input = { targets: [{ kind: 'TABLE', targetId: object, precondition: precondition(item) }] };
  const preview = await admin.ok(path + '/generation-preview', { method: 'POST', body: input });
  assert.ok(preview.notes.some((entry) => entry.noteId === note.note.id));
  const command = crypto.randomUUID();
  const body = { ...input, reviewToken: preview.reviewToken };
  const accepted = await write(path + '/generations', body, 'POST', command, 202);
  const duplicate = await write(path + '/generations', body, 'POST', command, 202);
  assert.equal(duplicate.requestId, accepted.requestId);
  let detail;
  for (let turn = 0; turn < 120; turn++) {
    // 只轮询已接收请求，保留同一个模型任务。
    detail = await admin.ok(base + '/generations/' + accepted.requestId);
    if (['SUCCEEDED', 'FAILED'].includes(detail.state)) break;
    await delay(1000);
  }
  assert.equal(detail?.state, 'SUCCEEDED', detail?.failureCode ?? '模型任务超时');
  const suggestion = detail.targets[0].suggestion;
  assert.ok(suggestion.suggestionId);
  assert.ok(detail.notes.some((entry) => entry.noteId === note.note.id && entry.state === 'USED'));
  const before = await admin.ok(itemPath);
  assert.equal(before.currentRevision.revisionId, item.currentRevision.revisionId);
  const chosen = Object.keys(suggestion.content).find((key) => !before.currentRevision.content[key])
    ?? Object.keys(suggestion.content).find((key) => key !== 'purpose');
  assert.ok(chosen, '真实建议需要至少一项可审阅的独立内容');
  const adopted = await write(itemPath + '/draft', {
    precondition: precondition(before),
    draft: {
      content: { ...before.currentRevision.content, [chosen]: suggestion.content[chosen] },
      suggestionSelection: { suggestionId: suggestion.suggestionId, contentPaths: [chosen] },
    },
  }, 'PUT');
  assert.equal(adopted.currentRevision.content.purpose, before.currentRevision.content.purpose);
  assert.equal(adopted.currentRevision.origin, 'AI_ASSISTED');
  const pending = await admin.ok(itemPath);
  assert.equal(pending.state, 'DRAFT');
  const confirmed = await write(itemPath + '/confirmations', { precondition: precondition(pending), action: 'CONFIRM' });
  passed('GOV09/GOV10', { source, requestId: accepted.requestId, suggestionId: suggestion.suggestionId,
    modelCallId: suggestion.modelCallId, savedRevision: adopted.currentRevision.revisionId,
    confirmationId: confirmed.confirmationId, chosen });
}

await mkdir(out, { recursive: true });
await login();
// 同一会话的首次 CSRF 和写入资格按顺序建立。
const requestedType = process.env.DATAOCEAN_GOVERNANCE_SOURCE;
const types = ['MYSQL', 'POSTGRESQL'].filter((type) => !requestedType || requestedType === type);
assert.ok(types.length, '验收库类型必须是 MYSQL 或 POSTGRESQL');
const tested = [];
for (const type of types) {
  // 源之间可独立复验，真实连接检查仍在相同的权威业务服务执行。
  tested.push(await createSource(type));
}
let modelFixture;
// 分来源执行差异测试，确保同名对象不能串用。
for (const [index, type] of types.entries()) {
  const source = tested[index];
  const path = '/governance/sources/' + source;
  const initial = await admin.ok(path + '/objects');
  const baseline = await collect(source);
  const listed = await admin.ok(path + '/objects?keyword=product');
  assert.ok(listed.page.items.length);
  const row = listed.page.items[0];
  const object = row.objectId;
  const table = path + '/objects/' + object + '/items/TABLE/' + object;
  const fields = await admin.ok(path + '/objects/' + object + '/columns');
  const status = fields.items.find((field) => field.name === 'status');
  assert.ok(status);
  const field = path + '/objects/' + object + '/items/COLUMN/' + status.target.targetId;
  const context = await admin.ok(table);
  assert.equal(context.structureBasis.snapshotId, baseline);
  report.sources[type] = { source, object, field: status.target.targetId, baseline };
  if (!initial.currentSnapshotId) {
    assert.equal(context.currentRevision, null);
    assert.equal(context.latestConfirmation, null);
    assert.equal(context.version, null);
  }
  const direct = await admin.request(table + '/confirmations', { method: 'POST', command: crypto.randomUUID(),
    body: { precondition: precondition(context), action: 'CONFIRM' } });
  if (!context.currentRevision) assert.equal(direct.code, 400001);
  const definition = { purpose: '管理合成商品名录', rowGrain: '每行是一种合成商品' };
  const command = crypto.randomUUID();
  const draft = { precondition: precondition(await admin.ok(table)), draft: { content: definition } };
  const first = await write(table + '/draft', draft, 'PUT', command);
  const replay = await write(table + '/draft', draft, 'PUT', command);
  assert.equal(replay.currentRevision.revisionId, first.currentRevision.revisionId);
  const same = await write(table + '/draft', { ...draft, precondition: precondition(await admin.ok(table)) }, 'PUT');
  assert.equal(same.currentRevision.revisionId, first.currentRevision.revisionId);
  const confirm = await write(table + '/confirmations', { precondition: precondition(await admin.ok(table)), action: 'CONFIRM' });
  assert.ok(confirm.confirmationId);
  passed('GOV01/GOV03/GOV05 ' + type, { object, baseline, revision: first.currentRevision.revisionId });

  const note1 = await submitNote(source, 'C批合成商品口径', '状态 1 表示在售，0 表示停用。合同商品仅包含自营品类。');
  const use = await write(field + '/confirmations', {
    precondition: precondition(await admin.ok(field)), action: 'CONFIRM',
    draft: { content: { meaning: '商品当前状态', valueMappings: [{ code: '01', meaning: '预登记' },
      { code: '1', meaning: '在售' }, { code: '0', meaning: '停用' }] }, manualEvidence: [reference(note1, 'meaning')] },
  });
  assert.ok(use.confirmationId);
  const note2 = await submitNote(source, 'C批合成商品口径更新标题', note1.content.body, note1.note.id, note1.note.version);
  assert.equal((await admin.ok(field)).pendingReview, false);
  const note3 = await submitNote(source, note2.content.title, '状态 1 表示上架，0 表示停用。商品数据只包含自营品类，不统计试销样品。',
    note1.note.id, note2.note.version);
  const affected = await admin.ok(field);
  assert.equal(affected.pendingReview, true);
  assert.ok(affected.impacts.some((impact) => impact.origin?.beforeRevision === note2.content.revision));
  assert.equal((await admin.ok(table)).pendingReview, false);
  const staleRequest = await admin.request(field + '/confirmations', { method: 'POST', command: crypto.randomUUID(),
    body: { precondition: precondition(affected), action: 'CONFIRM' } });
  assert.equal(staleRequest.code, 409301);
  const updated = await write(field + '/confirmations', {
    precondition: precondition(affected), action: 'CONFIRM',
    draft: { content: { meaning: '商品最新上架状态', valueDescription: '1 上架，0 停用' }, manualEvidence: [reference(note3, 'meaning')] },
  });
  assert.ok(updated.resolvedImpactIds.length);
  assert.equal((await admin.ok(field)).pendingReview, false);
  passed('GOV04/GOV07/GOV16 ' + type, { noteId: note3.note.id, revision: note3.content.revision,
    impact: updated.resolvedImpactIds[0] });

  const changeMarker = Date.now();
  changeSource(type, type === 'MYSQL'
    ? `ALTER TABLE product MODIFY COLUMN status INT NOT NULL COMMENT '商品上架状态有待核对 ${changeMarker}';`
    : `COMMENT ON COLUMN c_sales.product.status IS '商品上架状态有待核对 ${changeMarker}';`);
  const changedSnapshot = await collect(source);
  const after = await admin.ok(field);
  assert.equal(after.pendingReview, true);
  const structural = after.impacts.find((impact) => impact.origin?.changeId && impact.state !== 'REVIEWED');
  assert.ok(structural, '结构事实需要定位到实际稳定字段');
  assert.equal((await admin.ok(table)).pendingReview, false);
  const preserved = await write(field + '/confirmations', { precondition: precondition(after), action: 'CONFIRM' });
  assert.equal(preserved.currentRevision.revisionId, after.currentRevision.revisionId);
  assert.equal((await admin.ok(field)).pendingReview, false);
  passed('GOV12/GOV18 ' + type, { fact: structural.origin.changeId, snapshot: changedSnapshot,
    sameRevision: preserved.currentRevision.revisionId });

  if (type === 'MYSQL' && process.env.DATAOCEAN_GOVERNANCE_VERIFY_AI === '1') {
    // 先完成双库验证，再使用合成 MySQL 来源检查真实模型。
    modelFixture = { source, object, table, note: note3 };
  }
  await writeFile(new URL('api-report.json', out), JSON.stringify(report, null, 2) + '\n', 'utf8');
}

const pgContext = report.sources.POSTGRESQL;
const mysqlContext = report.sources.MYSQL;
if (pgContext && mysqlContext) {
  // 跨源身份校验需要两个确实采集过的来源。
  const mixed = await admin.request('/governance/sources/' + pgContext.source + '/objects/' + pgContext.object
    + '/items/COLUMN/' + mysqlContext.field);
  assert.equal(mixed.code, 404001);
  const roleFixture = await new IdentityFixture().start(uiPassword);
  const role = await roleFixture.role('仅元数据目录', ['catalog.view']);
  const reviewer = await roleFixture.activate(await roleFixture.user('catalogOnly', roleFixture.root, [role]));
  assert.equal((await reviewer.request('/governance/sources/' + pgContext.source + '/objects')).code, 403003);
  passed('GOV02/GOV06', { crossSource: mixed.code, allowedRole: role.id });
}
await writeFile(new URL('api-report.json', out), JSON.stringify(report, null, 2) + '\n', 'utf8');
if (modelFixture) {
  try {
    await model(modelFixture.source, modelFixture.object, modelFixture.table, modelFixture.note);
  } catch (error) {
    // 外部模型失败独立记录，保留已完成的双库验证。
    report.modelFailure = { reason: error.message.split('\n')[0] };
    await writeFile(new URL('api-report.json', out), JSON.stringify(report, null, 2) + '\n', 'utf8');
    throw error;
  }
  await writeFile(new URL('api-report.json', out), JSON.stringify(report, null, 2) + '\n', 'utf8');
}
