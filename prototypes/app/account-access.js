/* 独立 HTML 的登录账号样例。正式登录与服务端鉴权进入实现阶段后接入。 */
(() => {
  'use strict';
  const modules = [
    { key: 'access-requests', label: '访问申请', icon: 'inbox', permission: 'access.read' },
    { key: 'grants', label: '授权记录', icon: 'layers', permission: 'grants.read' }
  ];
  const fixtures = {
    manager: { id: 'user-zhou', name: '周店长', departments: ['城南店'], roles: ['门店管理'], scope: '城南店',
      permissions: ['query.use', 'access.read', 'grants.read'], scopes: ['城南店经营复盘'], active: true },
    employee: { id: 'user-zhou', name: '周店长', departments: ['城南店'], roles: ['门店员工'], scope: '城南店',
      permissions: ['query.use'], scopes: ['城南店经营复盘'], active: true },
    approver: { id: 'user-chen', name: '陈主管', departments: ['城南片区'], roles: ['经营业务审批'], scope: '城南店',
      permissions: ['query.use', 'access.read', 'access.review', 'access.execute', 'grants.read'], scopes: ['城南店经营复盘'], active: true }
  };
  const can = (account, operation) => Boolean(account?.active && account.permissions?.includes(operation));
  const entries = account => modules.filter(item => can(account, item.permission));
  const canEnter = (account, key) => entries(account).some(item => item.key === key);
  const inScope = (account, record) => Boolean(account?.active && record && account.scopes?.includes(record.scope));
  function owns(account, record) {
    return Boolean(account?.active && record && (record.applicantId ? record.applicantId === account.id : record.applicant === account.name));
  }
  function canDecide(account, record, action) {
    if (!canEnter(account, 'access-requests') || !inScope(account, record)) return false;
    if (['approve', 'reject'].includes(action)) return can(account, 'access.review') && !owns(account, record) && record.status === 'pending';
    if (['activate', 'activate-fail'].includes(action)) return can(account, 'access.execute') && record.status === 'approved';
    return false;
  }
  window.AccountAccess = { can, entries, canEnter, inScope, owns, canDecide,
    fixture: key => structuredClone(fixtures[key] || fixtures.manager) };
})();
