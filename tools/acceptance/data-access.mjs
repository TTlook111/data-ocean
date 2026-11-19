import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { IdentityFixture } from './iam-fixtures.mjs';

/**
 * 在本机已确认的 MySQL 服务中准备独立的合成业务源。
 */
function prepareSource(prefix) {
  assert.equal(process.env.DATAOCEAN_B_FIXTURE, 'mysql-loopback');
  assert.match(prefix, /^[a-z0-9]{4,20}$/);
  const name = `dataocean_access_${prefix}`;
  const user = `ro_${prefix}`;
  const password = `Fixture${crypto.randomUUID().replaceAll('-', '')}!`;
  // 隔离合成库只创建独立名称，应用访问用户只允许读取该库。
  const sql = [
    `CREATE DATABASE \`${name}\`;`,
    `CREATE TABLE \`${name}\`.sample_item (id INT PRIMARY KEY, name VARCHAR(80) NOT NULL);`,
    `INSERT INTO \`${name}\`.sample_item(id,name) VALUES (1,'synthetic');`,
    `CREATE USER '${user}'@'%' IDENTIFIED BY '${password}';`,
    `GRANT SELECT, SHOW VIEW ON \`${name}\`.* TO '${user}'@'%';`,
  ].join('\n');
  const setup = spawnSync('docker', [
    'exec', '-i', 'mysql', 'sh', '-ec', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root --batch',
  ], { input: sql, encoding: 'utf8' });
  // 不输出包含 SQL 参数及凭据的原始错误流。
  assert.equal(setup.status, 0, '独立合成源初始化失败');
  return { name, user, password };
}

/**
 * 普通防重提交，明确复用公共会话的安全边界。
 */
function write(client, path, body, method = 'POST', command = crypto.randomUUID()) {
  return client.ok(path, { method, body, command });
}

/**
 * 当前共享配置修改使用后台返回的真实核对凭据。
 */
async function configure(client, path, body) {
  const preview = await client.ok(`${path}/impact-preview`, { method: 'POST', body });
  return write(client, path, { ...body, reviewToken: preview.reviewToken }, 'PUT');
}

/**
 * 审批、核定和撤回都使用真实后端核对。
 */
async function action(client, row, payload) {
  const path = `/access/requests/${row.id}`;
  const body = { expectedVersion: row.version, ...payload };
  const preview = await client.ok(`${path}/action-preview`, { method: 'POST', body });
  return write(client, `${path}/actions`, { ...body, reviewToken: preview.reviewToken });
}

/**
 * 使用真实连接测试及核对创建一个启用的数据源。
 */
async function addSource(admin, template, database, name) {
  const configuration = {
    ...template.configuration,
    name,
    databaseName: database.name,
    account: database.user,
    technicalContactId: null,
    businessContactId: null,
  };
  const runtimePolicy = template.runtimePolicy;
  const verified = await admin.ok('/datasources/connection-checks', {
    method: 'POST',
    command: crypto.randomUUID(),
    body: { configuration, runtimePolicy, password: database.password },
  }, 201);
  assert.equal(verified.canEnable, true, '合成只读源的真实连接检查必须通过');
  const proposal = {
    configuration, runtimePolicy, credentialProvided: true,
    checkId: verified.checkId, targetLifecycle: 'ENABLED',
  };
  const review = await admin.ok('/datasources/impact-preview', { method: 'POST', body: { proposal } });
  return (await admin.ok('/datasources', {
    method: 'POST',
    command: crypto.randomUUID(),
    body: { proposal, password: database.password, reviewToken: review.reviewToken },
  }, 201)).targetId;
}

/**
 * 以实际版本和已保存的凭据测试处理数据源生命周期。
 */
async function lifecycle(admin, sourceId, actionCode) {
  const path = `/datasources/${sourceId}`;
  const row = await admin.ok(path);
  const proposal = { reason: '合成验收源生命周期复验' };
  if (actionCode === 'RESTORE_SOURCE') {
    // 恢复使用保存的数据库账号开展同一版本的只读测试。
    const check = await admin.ok(`${path}/connection-checks`, {
      method: 'POST',
      body: { configuration: row.configuration, runtimePolicy: row.runtimePolicy, expectedVersion: row.source.version },
    }, 201);
    assert.equal(check.canEnable, true);
    proposal.checkId = check.checkId;
  }
  const body = { action: actionCode, expectedVersion: row.source.version, proposal };
  const preview = await admin.ok(`${path}/impact-preview`, { method: 'POST', body });
  await write(admin, `${path}/lifecycle`, { ...body, reviewToken: preview.reviewToken });
  return admin.ok(path);
}

/**
 * 从正式采集入口建立真实目录，检查终态和快照一致。
 */
async function collect(admin, sourceId) {
  const path = `/datasources/${sourceId}`;
  const row = await admin.ok(path);
  const body = { action: 'START_COLLECTION', expectedVersion: row.source.version, proposal: {} };
  const preview = await admin.ok(`${path}/impact-preview`, { method: 'POST', body });
  const accepted = await admin.ok(`${path}/collections`, {
    method: 'POST',
    command: crypto.randomUUID(),
    body: { expectedVersion: body.expectedVersion, reviewToken: preview.reviewToken },
  }, 202);
  for (let attempt = 0; attempt < 40; attempt += 1) {
    // 轮询原任务，不为等待中的任务重复提交。
    const task = await admin.ok(`/collection-tasks/${accepted.taskId}`);
    if (!['QUEUED', 'RUNNING'].includes(task.state)) {
      assert.equal(task.state, 'SUCCEEDED');
      const directory = await admin.ok(`${path}/catalog`);
      assert.equal(directory.currentSnapshotId, task.content.snapshotId);
      assert.equal(directory.readableObjectCount, 1);
      return task.content;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('本机合成源的真实采集未在验证预算内结束');
}

const fixture = await new IdentityFixture().start();
assert.match(fixture.admin.base, /^http:\/\/127\.0\.0\.1:\d+$/);
const { admin } = fixture;
const checked = [];
const target = process.env.DATAOCEAN_B_SOURCE_ID;
assert.match(target ?? '', /^\d+$/, '请明确已核验的本机 MySQL 合成测试源 ID');
const template = await admin.ok(`/datasources/${target}`);
assert.equal(template.configuration.databaseType, 'MYSQL');
assert.equal(template.configuration.host, '127.0.0.1');
const database = prepareSource(fixture.prefix);
const [source, second] = [
  await addSource(admin, template, database, `${fixture.prefix} 商品验收源`),
  await addSource(admin, template, database, `${fixture.prefix} 分析验收源`),
];
checked.push('真实 MySQL 专用只读账号、分项测试、核对启用');
assert.equal((await collect(admin, source)).analysis, 'INITIAL_BASELINE');
assert.equal((await collect(admin, source)).analysis, 'NO_CHANGE');
checked.push('真实 MySQL 首次采集生成基线，重复采集无变化，任务与目录快照一致');

const reviewerCodes = ['access-requests.view', 'access-requests.review', 'access-requests.reject', 'access-requests.supplement'];
const selfCodes = ['my-requests.view', 'my-requests.apply', 'my-requests.withdraw', 'my-access.view'];
const [employeeRole, reviewRole, dept, otherDept] = [
  await fixture.role('申请人', selfCodes),
  await fixture.role('审批人', reviewerCodes),
  await fixture.department('access'),
  await fixture.department('other'),
];
const applicant = await fixture.user('applicant', dept, [employeeRole, reviewRole]);
const approver = await fixture.user('approver', otherDept, [reviewRole]);
const outsider = await fixture.user('outsider', otherDept, [reviewRole]);
const user = await fixture.activate(applicant);
const reviewer = await fixture.activate(approver);
const unrelated = await fixture.activate(outsider);
for (const id of [source, second]) {
  // 申请人本人是主审批人，必须按责任分给替代人。
  await configure(admin, `/access/responsibilities/${id}`, {
    expectedVersion: 0, primaryUserId: applicant.id, alternateUserId: approver.id,
  });
}
const rule = await write(admin, '/access/rules', {
  name: `${fixture.prefix} 成本限制`, content: '不得返回合成商品成本',
});
const extra = await write(admin, '/access/rules', {
  name: `${fixture.prefix} 电话限制`, content: '不得返回合成员工电话',
});
const receive = (id) => user.ok(`/access/requests/${id}`);
const current = () => user.ok('/access/my-sources');
const submit = (suffix, sourceIds, draft = false) => write(user, '/access/my-requests', {
  purpose: `${fixture.prefix} ${suffix}`, dataNeeded: '汇总合成商品数据', sourceIds, draft,
});
const created = await submit('独立审批', [source]);
let row = await receive(created.targetId);
assert.equal(row.items[0].assignee.id, approver.id);
const denied = { expectedVersion: row.version, action: 'APPROVE', itemId: row.items[0].id, ruleIds: [rule.targetId] };
for (const client of [user, unrelated]) {
  // 越权审批和自批均由真实接口拒绝。
  const result = await client.request(`/access/requests/${row.id}/action-preview`, { method: 'POST', body: denied });
  assert.equal(result.status, 403);
}
assert.equal((await unrelated.ok('/access/requests?queue=PENDING')).total, 0);
assert.equal((await reviewer.ok(`/access/requests?queue=PENDING&requestId=${row.id}`)).total, 1);
assert.equal((await current()).length, 0);
await action(reviewer, row, { action: 'APPROVE', itemId: row.items[0].id, ruleIds: [rule.targetId] });
row = await receive(row.id);
assert.equal(row.items[0].state, 'EFFECTIVE');
assert.equal(row.items[0].handler.id, approver.id);
assert.equal((await current())[0].rules[0].content, '不得返回合成商品成本');
checked.push('替代分派、自批拒绝、非负责人拒绝、独立批准立即生效');
assert.equal((await lifecycle(admin, source, 'DISABLE_SOURCE')).source.lifecycle, 'DISABLED');
assert.equal((await current()).length, 0);
assert.equal((await lifecycle(admin, source, 'RESTORE_SOURCE')).source.lifecycle, 'ENABLED');
assert.equal((await current()).length, 1);
checked.push('停用即失去当前访问，真实检查后恢复有效来源');

const org = await admin.ok(`/access/departments/${dept.id}`);
await configure(admin, `/access/departments/${dept.id}`, {
  expectedVersion: org.version, sources: [{ sourceId: source, ruleIds: [rule.targetId, extra.targetId] }],
});
assert.equal((await current())[0].rules.length, 2);
assert.equal((await current())[0].origins.length, 2);
assert.equal((await admin.ok(`/access/department-grants?sourceId=${source}`)).total, 1);
assert.equal((await user.ok('/access/my-grants')).total, 1);
assert.equal((await user.ok('/access/my-department-grants')).items[0].state, 'EFFECTIVE');
await configure(admin, `/access/rules/${rule.targetId}`, {
  expectedVersion: rule.version, name: `${fixture.prefix} 成本限制`, content: '不得返回合成商品成本、采购单价',
});
assert.equal((await current())[0].rules[0].content, '不得返回合成商品成本、采购单价');
assert.equal((await receive(row.id)).items[0].rules[0].content, '不得返回合成商品成本');
const grant = (await admin.ok(`/access/grants?requestId=${row.id}`)).items[0];
const revoke = { expectedVersion: grant.version, reason: '撤销验收个人来源，保留直属部门来源' };
const preview = await admin.ok(`/access/grants/${grant.id}/revoke-preview`, { method: 'POST', body: revoke });
assert.match(preview.warnings.join(''), /保留/);
await write(admin, `/access/grants/${grant.id}/revoke`, { ...revoke, reviewToken: preview.reviewToken });
assert.equal((await current())[0].origins.length, 1);
assert.equal((await current())[0].origins[0].kind, 'DEPARTMENT');
assert.equal((await admin.ok(`/access/grants?requestId=${row.id}`)).items[0].state, 'ENDED');
const configured = await admin.ok(`/access/departments/${dept.id}`);
await configure(admin, `/access/departments/${dept.id}`, { expectedVersion: configured.version, sources: [] });
assert.equal((await current()).length, 0);
assert.equal((await user.ok('/access/my-department-grants')).items[0].state, 'ENDED');
checked.push('部门与个人双来源、规则正文实时合并、历史修订保留、撤销后实际失权');

const unknown = await submit('未知源补充', []);
row = await receive(unknown.targetId);
assert.equal(row.items[0].state, 'ROUTING');
assert.equal((await admin.ok(`/access/requests?queue=ROUTING&requestId=${row.id}`)).total, 1);
// 正式核对先告诉操作者真实接手人和调整范围。
const routingPreview = await admin.ok(`/access/requests/${row.id}/action-preview`, {
  method: 'POST', body: { expectedVersion: row.version, action: 'ROUTE', itemId: row.items[0].id, sourceId: source },
});
assert.match(routingPreview.changes.find(({ field }) => field === 'assignee').after, new RegExp(approver.username));
await action(admin, row, { action: 'ROUTE', itemId: row.items[0].id, sourceId: source });
row = await receive(row.id);
await action(reviewer, row, { action: 'SUPPLEMENT', itemId: row.items[0].id, reason: '说明汇总口径' });
row = await receive(row.id);
assert.equal(row.items[0].state, 'NEEDS_SUPPLEMENT');
const supplementPreview = await user.ok(`/access/my-requests/submission-preview?id=${row.id}`, {
  method: 'POST',
  body: { expectedVersion: row.version, purpose: row.purpose, dataNeeded: row.dataNeeded, supplement: '按月汇总', draft: false },
});
assert.equal(supplementPreview.changes.find(({ field }) => field === 'state').after, '待审批');
assert.ok(supplementPreview.changes.some(({ field, after }) => field === 'source' && after.includes('商品验收源')));
await write(user, `/access/my-requests/${row.id}`, {
  expectedVersion: row.version, purpose: row.purpose, dataNeeded: row.dataNeeded, supplement: '按月汇总', draft: false,
}, 'PUT');
row = await receive(row.id);
const handled = await reviewer.ok(`/access/requests/${row.id}/handled`);
assert.equal(handled.items[0].state, 'NEEDS_SUPPLEMENT');
assert.equal(handled.items[0].reason, '说明汇总口径');
assert.equal(handled.items[0].application.purpose, row.purpose);
assert.equal((await reviewer.ok(`/access/requests?queue=HANDLED&state=NEEDS_SUPPLEMENT&sourceId=${source}&requestId=${row.id}`)).total, 1);
assert.equal((await unrelated.request(`/access/requests/${row.id}/handled`)).status, 404);
await action(user, row, { action: 'WITHDRAW', itemId: row.items[0].id });
assert.equal((await receive(row.id)).items[0].state, 'WITHDRAWN');
const activities = await user.ok(`/access/my-requests/${row.id}/history`);
assert.deepEqual(activities.map(({ action }) => action), ['WITHDRAW', 'RESUBMIT', 'SUPPLEMENT', 'ROUTE', 'SUBMIT']);
assert.equal((await unrelated.request(`/access/my-requests/${row.id}/history`)).status, 403);
checked.push('未知源分流、按实际责任核定、补充重提、本人撤回');

// 首次提交草稿时间线和部分补充以真实单据记录为准。
const draft = await submit('草稿首次提交复验', [source], true);
const draftRow = await receive(draft.targetId);
assert.equal(draftRow.items[0].state, 'DRAFT');
await write(user, `/access/my-requests/${draftRow.id}`, {
  expectedVersion: draftRow.version, purpose: draftRow.purpose, dataNeeded: draftRow.dataNeeded,
  draft: false, sourceIds: [source],
}, 'PUT');
assert.deepEqual((await user.ok(`/access/my-requests/${draftRow.id}/history`)).map(({ action }) => action), ['SUBMIT', 'DRAFT']);

const multi = await submit('独立子项部分补充复验', [source, second]);
const beforePartial = await receive(multi.targetId);
const needs = beforePartial.items.find((item) => item.source.id === second);
await action(reviewer, beforePartial, { action: 'SUPPLEMENT', itemId: needs.id, reason: '补充独立子项的时间范围' });
const supplement = await receive(multi.targetId);
await write(user, `/access/my-requests/${supplement.id}`, {
  expectedVersion: supplement.version, purpose: '分析源按周限定范围', dataNeeded: '只要最近两周数据',
  supplement: '按自然周统计', draft: false,
}, 'PUT');
const afterPartial = await receive(multi.targetId);
assert.equal(afterPartial.purpose, beforePartial.purpose);
assert.equal(afterPartial.items.find((item) => item.source.id === source).application, null);
assert.equal(afterPartial.items.find((item) => item.source.id === second).application.dataNeeded, '只要最近两周数据');
assert.equal((await reviewer.ok(`/access/requests?queue=PENDING&sourceId=${second}&requestId=${multi.targetId}`)).items[0].items.length, 1);
checked.push('草稿首次提交、部分补充隔离、原处理人历史和子项查询一致');

const joint = await submit('联合审批', []);
row = await receive(joint.targetId);
await action(reviewer, row, { action: 'PLAN', sourceIds: [source, second], joint: true, reason: '需合并核查' }).catch(async (error) => {
  // 业务分流权不由审批角色继承，低权限账号直接提交被拒绝。
  assert.match(error.message, /403/);
  await action(admin, row, { action: 'PLAN', sourceIds: [source, second], joint: true, reason: '需合并核查' });
});
row = await receive(row.id);
const first = row.items[0];
await action(reviewer, row, { action: 'APPROVE', itemId: first.id, ruleIds: [rule.targetId] });
assert.equal((await current()).length, 0);
row = await receive(row.id);
assert.equal(row.items[0].state, 'APPROVED');
const last = { expectedVersion: row.version, action: 'APPROVE', itemId: row.items[1].id, ruleIds: [extra.targetId] };
const route = `/access/requests/${row.id}`;
const reviewed = await reviewer.ok(`${route}/action-preview`, { method: 'POST', body: last });
const race = await Promise.all([1, 2].map(() => reviewer.request(`${route}/actions`, {
  method: 'POST', command: crypto.randomUUID(), body: { ...last, reviewToken: reviewed.reviewToken },
})));
assert.equal(race.filter((reply) => reply.status === 200).length, 1);
assert.equal(race.filter((reply) => reply.status === 409).length, 1);
assert.equal((await current()).length, 2);
assert.equal((await lifecycle(admin, second, 'DISABLE_SOURCE')).source.lifecycle, 'DISABLED');
assert.equal((await current()).length, 0);
assert.equal((await admin.ok(`/access/grants?requestId=${row.id}&state=SUSPENDED`)).total, 2);
assert.equal((await lifecycle(admin, second, 'RESTORE_SOURCE')).source.lifecycle, 'ENABLED');
assert.equal((await current()).length, 2);
const jointGrants = await admin.ok(`/access/grants?requestId=${row.id}`);
assert.equal(jointGrants.total, 2);
const one = jointGrants.items[0];
const batchRevoke = { expectedVersion: one.version, reason: '核验联合来源整组撤销' };
const batchPreview = await admin.ok(`/access/grants/${one.id}/revoke-preview`, { method: 'POST', body: batchRevoke });
assert.equal(batchPreview.warnings.length, 2);
const commandBody = { ...batchRevoke, reviewToken: batchPreview.reviewToken };
const command = crypto.randomUUID();
await write(admin, `/access/grants/${one.id}/revoke`, commandBody, 'POST', command);
await write(admin, `/access/grants/${one.id}/revoke`, commandBody, 'POST', command);
assert.equal((await current()).length, 0);
assert.equal((await admin.ok(`/access/grants?requestId=${row.id}&state=ENDED`)).total, 2);
checked.push('联合首项等待、末项并发唯一成功、整组暂停及撤销、防重命令重放');

// 从真实上传入口保存资料和附件，后续只读回当时的具体修订。
const attachmentText = '合成说明原件\n';
const noteBody = new FormData();
noteBody.set('metadata', new Blob([JSON.stringify({
  sourceId: second, title: `${fixture.prefix} 商品业务说明`, body: '第一版合成业务说明',
})], { type: 'application/json' }));
noteBody.append('files', new Blob([attachmentText], { type: 'text/plain' }), 'fixture.txt');
const note = await write(admin, '/business-notes', noteBody);
const notePath = `/business-notes/${note.targetId}`;
const original = await admin.ok(notePath);
assert.equal(original.content.revision, 1);
assert.equal(original.content.attachments.length, 1);
assert.equal((await admin.ok(`/business-notes?sourceId=${second}&keyword=${fixture.prefix}`)).total, 1);
const noteEdit = new FormData();
noteEdit.set('metadata', new Blob([JSON.stringify({
  expectedVersion: original.note.version, title: original.note.title, body: '第二版合成业务说明',
  keptAttachmentIds: [],
})], { type: 'application/json' }));
await write(admin, `${notePath}/revisions`, noteEdit);
const currentNote = await admin.ok(notePath);
assert.equal(currentNote.content.revision, 2);
assert.equal(currentNote.content.attachments.length, 0);
const historicNote = await admin.ok(`${notePath}?revision=1`);
assert.equal(historicNote.content.body, '第一版合成业务说明');
const download = `${notePath}/revisions/1/files/${historicNote.content.attachments[0].id}`;
const deniedDownload = await unrelated.request(download);
assert.equal(deniedDownload.status, 403);
const originalFile = await fetch(`${admin.base}/api/v1${download}`, {
  headers: { Cookie: [...admin.cookies].map(([name, value]) => `${name}=${value}`).join('; ') },
});
assert.equal(originalFile.status, 200);
assert.match(originalFile.headers.get('content-disposition'), /^attachment/);
assert.equal(await originalFile.text(), attachmentText);
assert.equal((await admin.request(download.replace('/revisions/1/', '/revisions/2/'))).status, 404);
const withdrawal = { action: 'WITHDRAW', expectedVersion: currentNote.note.version, reason: '合成资料撤下验收' };
const withdrawalPreview = await admin.ok(`${notePath}/state-preview`, { method: 'POST', body: withdrawal });
await write(admin, `${notePath}/state`, { ...withdrawal, reviewToken: withdrawalPreview.reviewToken });
const withdrawnNote = await admin.ok(notePath);
assert.equal(withdrawnNote.note.state, 'WITHDRAWN');
assert.equal(withdrawnNote.content.revision, 2);
const restoration = { action: 'RESTORE', expectedVersion: withdrawnNote.note.version };
const restorationPreview = await admin.ok(`${notePath}/state-preview`, { method: 'POST', body: restoration });
await write(admin, `${notePath}/state`, { ...restoration, reviewToken: restorationPreview.reviewToken });
assert.equal((await admin.ok(notePath)).note.state, 'ACTIVE');
checked.push('企业业务说明上传、历史附件下载、跨修订与无权拒绝、撤下与恢复');

// 用专门创建的合成账号验证停用会在身份事务内结束已生效的个人来源。
const beforeDisable = await submit('账号停用复验', [second]);
const forDisable = await receive(beforeDisable.targetId);
await action(reviewer, forDisable, {
  action: 'APPROVE', itemId: forDisable.items[0].id, ruleIds: [extra.targetId],
});
assert.equal((await current()).length, 1);
const account = await admin.ok(`/iam/users/${applicant.id}`);
const disableReason = { reason: '结束本轮合成账号授权' };
await admin.reviewed('/iam/users', 'DISABLE', disableReason, {
  id: applicant.id, version: account.version, path: '/disable', method: 'POST',
});
assert.equal((await admin.ok(`/access/users/${applicant.id}/sources`)).length, 0);
assert.equal((await admin.ok(`/access/grants?requestId=${forDisable.id}&state=ENDED`)).total, 1);
assert.equal((await user.request('/access/my-sources')).status, 401);
checked.push('账号停用同事务结束个人授权并阻断原登录态');

assert.equal((await lifecycle(admin, source, 'DISABLE_SOURCE')).source.lifecycle, 'DISABLED');
assert.equal((await lifecycle(admin, source, 'RESTORE_SOURCE')).source.lifecycle, 'ENABLED');
assert.equal((await lifecycle(admin, source, 'DISABLE_SOURCE')).source.lifecycle, 'DISABLED');
const retired = await lifecycle(admin, source, 'RETIRE_SOURCE');
assert.equal(retired.source.lifecycle, 'RETIRED');
assert.equal(retired.credentialConfigured, false);
checked.push('真实只读测试、停用、恢复、退役及凭据解除');

const directory = 'output/acceptance/data-access';
await mkdir(directory, { recursive: true });
await writeFile(`${directory}/api-report.json`, JSON.stringify({
  passed: checked,
  runtime: fixture.admin.base,
  syntheticSchema: database.name,
  sources: [source, second],
  requestId: row.id,
  noteId: note.targetId,
}, null, 2) + '\n');
console.log(checked.join('\n'));
