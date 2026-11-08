'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
global.window = {};
require('../account-access.js');
const access = window.AccountAccess;
const request = { applicantId: 'user-zhou', applicant: '周店长', scope: '城南店经营复盘', status: 'pending' };
test('普通账号无后台入口，直接指定模块也不能进入', () => {
  const user = access.fixture('employee');
  assert.deepEqual(access.entries(user), []);
  assert.equal(access.canEnter(user, 'access-requests'), false);
  assert.equal(access.canEnter(user, 'grants'), false);
});
test('角色名称不产生权限，修改名称不改变已授予能力', () => {
  const user = access.fixture('employee');
  user.roles = ['超级管理员'];
  assert.equal(access.entries(user).length, 0);
  user.permissions.push('grants.read');
  assert.deepEqual(access.entries(user).map(item => item.key), ['grants']);
});
test('后台查看、审批与执行分别授权，并检查对象范围', () => {
  const reader = access.fixture('manager'), reviewer = access.fixture('approver');
  assert.equal(access.entries(reader).length, 2);
  assert.equal(access.canDecide(reader, request, 'approve'), false);
  assert.equal(access.canDecide(reviewer, request, 'approve'), true);
  assert.equal(access.canDecide(reviewer, { ...request, scope: '城北店经营复盘' }, 'approve'), false);
  reviewer.permissions = ['access.read', 'access.review'];
  assert.equal(access.canDecide(reviewer, { ...request, status: 'approved' }, 'activate'), false);
});
test('本人不能批准或驳回自己的申请，状态变化后旧操作失效', () => {
  const user = access.fixture('approver');
  assert.equal(access.canDecide(user, { ...request, applicantId: user.id }, 'approve'), false);
  assert.equal(access.canDecide(user, { ...request, applicantId: user.id }, 'reject'), false);
  assert.equal(access.canDecide(user, { ...request, status: 'effective' }, 'activate'), false);
});
test('停用账号失去入口及办理能力，记录归属以稳定账号编号判断', () => {
  const user = access.fixture('approver');
  assert.equal(access.owns(user, { ...request, applicant: user.name }), false);
  user.active = false;
  assert.deepEqual(access.entries(user), []);
  assert.equal(access.canDecide(user, request, 'approve'), false);
});

require('../dialogue.js');
require('../cases.js');
test('不同账号不能复用他人的申请或由他人的授权得到答案', () => {
  const now = () => Date.UTC(2026, 10, 8, 10);
  const owner = access.fixture('manager'), reviewer = access.fixture('approver');
  const first = window.QueryCases.create(null, now, owner);
  const origin = { threadId: 'same-thread-id', turnId: 'same-turn-id' };
  const id = first.ask('本店上周的毛利额是多少？', origin).caseId;
  first.apply(id, { purpose: '核对经营周报', scope: '城南店经营复盘', term: '7 天' });
  const second = window.QueryCases.create(first.snapshot(), now, reviewer);
  assert.equal(second.apply(id, { purpose: '改写他人申请', scope: '城南店经营复盘', term: '7 天' }).ok, false);
  assert.notEqual(second.ask('本店上周的毛利额是多少？', origin).caseId, id);
  second.decide(id, 'approve', { resource: 'profit', term: '7 天' });
  second.decide(id, 'activate');
  assert.equal(second.canView({ type: 'business', metric: 'grossProfit', resource: 'profit' }), false);
  const restored = window.QueryCases.create(second.snapshot(), now, owner);
  assert.equal(restored.ask('本店上周的毛利额是多少？', { ...origin, turnId: 'new-turn' }).kind, 'answer');
});
