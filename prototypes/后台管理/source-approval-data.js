/* 仅为原型建立数据源审批责任，不配置连接、数据查询授权或真实授予上限。 */
(() => {
  'use strict';
  const { users, dataSources } = PeopleDemo;
  const names = id => users.find(user => user.id === id)?.name || '未指定';
  const defaults = { retail: ['u01', 'u02'], finance: ['u13', 'u01'], products: ['u09', 'u01'], aftersales: ['', ''] };
  for (const source of dataSources) {
    const [primary, alternate] = defaults[source.id];
    source.approval = { primary, alternate, revision: 1, updated: primary ? '2026-11-10 09:00' : '' };
  }
  function hasAbility(user, ability) {
    return user?.status === '正常' && RoleDemo.records.some(role => role.status === '启用' && user.roles.includes(role.name) && role.permissions.includes(ability));
  }
  const canReview = user => hasAbility(user, 'access-requests.review');
  const eligible = () => users.filter(canReview);
  function error(config) {
    if (!config.primary || !config.alternate) return '请同时配置主审批人和替代负责人。';
    if (config.primary === config.alternate) return '主审批人和替代负责人不能是同一人。';
    if (!canReview(users.find(user => user.id === config.primary))) return '主审批人当前不可用或没有审批功能权限。';
    if (!canReview(users.find(user => user.id === config.alternate))) return '替代负责人当前不可用或没有审批功能权限。';
  }
  function state(source) { return !source.approval.primary && !source.approval.alternate ? '未配置' : error(source.approval) ? '需复核' : '已配置'; }
  function resolve(sourceId, applicant) {
    const source = dataSources.find(source => source.id === sourceId);
    if (!source) return { reviewer: '', kind: 'unassigned', reason: '数据源待核定。', key: 'unknown:' + sourceId };
    const invalid = error(source.approval);
    if (invalid) return { reviewer: '', kind: 'unassigned', reason: state(source) === '未配置' ? '该数据源尚未配置审批责任。' : invalid, key: sourceId + ':' + source.approval.revision + ':' + invalid };
    const substitute = source.approval.primary === applicant;
    const reviewer = substitute ? source.approval.alternate : source.approval.primary;
    return { reviewer, kind: substitute ? 'substitute' : 'primary', reason: substitute ? '申请人是主审批人，已转替代负责人。' : '', key: sourceId + ':' + source.approval.revision + ':' + reviewer };
  }
  function save(source, data, actor, revision) {
    if (!hasAbility(actor, 'sources.approval-config')) return '当前账号没有配置审批责任的权限。';
    if (source.approval.revision !== revision) return '审批责任已变化，请重新核对。';
    const message = error(data); if (message) return message;
    if (source.approval.primary === data.primary && source.approval.alternate === data.alternate) return '审批责任未变化。';
    source.approval = { primary: data.primary, alternate: data.alternate, revision: revision + 1, updated: new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0,16) };
    // 只重新匹配未处理子项；已经办理的人员与结果保留，不重放审批或执行。
    window.AccessRequestDemo?.refreshSource(source.id, actor);
  }
  window.SourceApprovalDemo = { hasAbility, canReview, eligible, error, state, resolve, save, names };
})();
