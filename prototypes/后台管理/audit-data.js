/* 少量历史示例及当前标签页内审批操作用于审计评审，不保存真实审计。 */
(() => {
  'use strict';
  const person = id => {
    const user = PeopleDemo.users.find(row => row.id === id);
    return { id, name: user.name, login: user.login };
  };
  const rows = [];
  const target = (name, reference) => ({ name, reference });
  const userTarget = id => { const user = person(id); return target(user.name, user.login); };
  function add(time, actor, action, subject, result, reason, changes, reference, requestId = '') {
    const number = String(rows.length + 1).padStart(3, '0');
    const row = { id: 'AUD-202611-' + number, time, actor: structuredClone(actor), action, target: subject, result, reason,
      changes: changes.map(([label, before, after]) => ({ label, before, after })), reference, requestId, version: 'PV-202611-' + number };
    rows.push(row); return row;
  }
  add('2026-11-10 11:20', person('u01'), '创建账号', userTarget('u05'), '成功', '城南店新增成员。', [['账号状态', '未创建', '待首次登录']], 'USER-u05');
  add('2026-11-10 11:00', person('u01'), '停用账号', userTarget('u15'), '成功', '旧城店人员离岗。', [['账号状态', '正常', '停用']], 'USER-u15');
  add('2026-11-10 10:40', person('u01'), '恢复账号', userTarget('u08'), '成功', '复核当前归属与保留配置后恢复。', [['账号状态', '停用', '正常']], 'USER-u08');
  add('2026-11-10 10:20', person('u01'), '分配角色', userTarget('u12'), '成功', '兼顾分析与本人问数工作。', [['个人角色', '普通用户', '数据分析师、普通用户']], 'ROLE-ASSIGN-u12');
  add('2026-11-10 10:00', person('u01'), '修改角色功能', target('数据分析师', 'role-analyst'), '成功', '允许查看已获准的元数据目录。', [['元数据目录查看', '未配置', '已配置']], 'ROLE-role-analyst');
  add('2026-11-10 09:40', person('u01'), '调整部门归属', userTarget('u07'), '成功', '调整城北片区岗位归属。', [['主部门', '城北片区', '城北店']], 'MEMBER-u07');
  add('2026-11-10 09:20', person('u01'), '配置部门初始访问', target('财务部', 'DEP-013'), '成功', '配置财务分析所需的初始数据访问。', [['财务分析库', '未配置', '用户信息限制、收款账户限制']], 'DEPT-CONFIG-013');
  add('2026-11-10 09:00', person('u01'), '配置审批责任', target('财务分析库', 'finance'), '成功', '明确数据源审批责任。', [['主审批人', '未配置', '刘芳（liu.fang）'], ['替代负责人', '未配置', '林管理员（lin.admin）']], 'SOURCE-APPROVAL-finance');
  add('2026-11-09 16:30', person('u01'), '恢复受保护权限', userTarget('u01'), '成功', '完成受保护管理能力核验。', [['受保护恢复能力', '待恢复', '已恢复']], 'RECOVERY-001');
  add('2026-11-09 15:20', person('u02'), '撤销授权', userTarget('u07'), '成功', '临时财务核对工作已结束。', [['财务分析库临时申请来源', '已生效', '已撤销']], 'REVOKE-HISTORY-006');
  add('2026-11-09 14:10', person('u02'), '受保护操作拒绝', target('管理员', 'role-admin'), '已拒绝', '本次受保护功能配置超出操作者的可授予范围。', [], 'DENIED-003');
  // 每次批准只留一条审计，把该次审批的结论及生效结果合并到批准人名下。
  function recordApproval(request, part, actor, ready) {
    const waiting = part.status !== '已生效';
    const changes = [[waiting ? '子项审批结论' : '审批结论', '待审批', waiting ? '当前子项已通过' : '已批准'], ...ready.map(value => [AccessRequestDemo.sourceName(value.source) + '申请来源', '未生效', '已生效'])];
    const row = add(part.reviewedAt, actor, waiting ? '通过子项审批' : '批准申请', userTarget(request.user), '成功', part.reason || '按业务用途核定数据源与输出限制规则。', changes, request.id, request.id);
    row.effect = waiting ? '申请待其余审批' : '已生效';
  }
  const requests = AccessRequestDemo.records();
  for (const id of ['SQ-DEMO-022', 'SQ-REVIEW-012']) {
    const request = requests.find(row => row.id === id), part = request.approvalItems[0];
    const actor = { id: part.reviewedById, name: part.reviewedBy, login: part.reviewedLogin };
    recordApproval(request, part, actor, [part]);
  }
  const actions = [...new Set([...rows.map(row => row.action), '通过子项审批'])];
  const canView = actor => SourceApprovalDemo.hasAbility(actor, 'audit.view');
  function records(actor, query = {}) {
    if (!canView(actor)) return [];
    return rows.filter(row => (!query.action || row.action === query.action) && (!query.status || query.status === 'all' || row.result === query.status) &&
      (!query.fromDate || row.time.slice(0, 10) >= query.fromDate) && (!query.toDate || row.time.slice(0, 10) <= query.toDate) &&
      AdminQuery.matchesFields({ actor: [row.actor.name, row.actor.login], target: [row.target.name, row.target.reference] }, query))
      .sort((a, b) => b.time.localeCompare(a.time) || b.id.localeCompare(a.id));
  }
  const get = (id, actor) => records(actor).find(row => row.id === id);
  window.AuditDemo = { actions, canView, records, get, recordApproval };
})();
