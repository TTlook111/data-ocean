/* 合并示例来源的输出限制并联动撤销；不连接真实权限服务或中断查询。 */
(() => {
  'use strict';
  const { users, departments, dataSources, extraRequests } = PeopleDemo, requests = AccessRequestDemo, ability = SourceApprovalDemo.hasAbility;
  const now = () => new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0,16);
  const departmentRows = [], activeDepartments = new Map();
  let sequence = 0, initialized = false;
  function syncDepartments() {
    const current = new Set();
    for (const user of users) for (const departmentId of new Set([user.department, ...user.extra])) {
      const department = departments.find(item => item.id === departmentId);
      if (!department) continue;
      for (const access of department.access) {
        const key = [user.id, department.id, access.source].join(':'), fingerprint = JSON.stringify(access.rules);
        current.add(key);
        let row = activeDepartments.get(key);
        if (row && row.fingerprint !== fingerprint) { row.endedAt = now(); activeDepartments.delete(key); row = null; }
        if (!row) {
          row = { id: 'DEPT-GRANT-' + (++sequence), type: 'department', user: user.id, department: department.id, source: access.source, rules: structuredClone(access.rules), fingerprint,
            activatedAt: initialized ? now() : department.created, endedAt: '' };
          departmentRows.push(row); activeDepartments.set(key,row);
        }
      }
    }
    // 来源结束后保留旧记录；重新配置产生新记录，不把历史重新变成有效来源。
    for (const [key,row] of activeDepartments) if (!current.has(key)) { row.endedAt = now(); activeDepartments.delete(key); }
    initialized = true;
  }
  syncDepartments();
  const canView = actor => ability(actor, 'grants.view');
  const canRevoke = actor => canView(actor) && ability(actor, 'grants.revoke');
  const sourceName = source => dataSources.find(item => item.id === source)?.name || '数据源不可用';
  const identity = id => { const user = users.find(item => item.id === id); return user ? user.name + '（' + user.login + '）' : '—'; };
  function rawRecords() {
    syncDepartments(); requests.records();
    const rows = departmentRows.map(row => {
      const department = departments.find(item => item.id === row.department);
      return { ...row, state: row.endedAt ? '来源已结束' : department.status !== '正常' ? '来源停用' : department.requiresReview ? '待复核' : '已生效',
        origin: department.name, originCode: department.code, typeLabel: '部门初始访问' };
    });
    for (const request of extraRequests) {
      if (request.status === '已生效' && !request.approvalItems) requests.ensure(request);
      for (const part of request.approvalItems || []) if (['已生效','已撤销'].includes(part.status)) rows.push({
        id: 'REQUEST-GRANT-' + request.id + '-' + part.id, type: 'request', typeLabel: '申请授权', user: request.user, source: part.source, rules: part.rules,
        state: part.status, activatedAt: part.activatedAt || (part.status === '生效失败' ? '' : request.submitted), request, part, origin: request.id, originCode: request.id
      });
    }
    return rows;
  }
  function records(actor, query = {}) {
    if (!canView(actor)) return [];
    return rawRecords().filter(row => (!query.status || query.status === 'all' || row.state === query.status) && (!query.source || row.source === query.source) &&
      (!query.fromDate || row.activatedAt.slice(0,10) >= query.fromDate) && (!query.toDate || Boolean(row.activatedAt) && row.activatedAt.slice(0,10) <= query.toDate) &&
      AdminQuery.matchesFields({ person: identity(row.user), approver: row.part ? [row.part.reviewedBy,row.part.reviewedLogin] : [], origin: [row.origin,row.originCode] },query))
      .sort((a,b) => b.activatedAt.localeCompare(a.activatedAt) || a.id.localeCompare(b.id));
  }
  function get(id, actor) { return records(actor).find(row => row.id === id); }
  function mergeRules(rows) {
    const byContent = new Map();
    for (const row of rows) for (const rule of row.rules) {
      const key = rule.content.trim();
      if (!byContent.has(key)) byContent.set(key,{rule:{...rule,displayKey:key},names:new Set()});
      byContent.get(key).names.add(rule.name);
    }
    return [...byContent.values()].map(item=>({...item.rule,name:[...item.names].join(' / ')}));
  }
  function accessGroups(user, query = {}) {
    const groups = new Map();
    for (const row of rawRecords().filter(row => row.user === user.id && ['已生效','来源停用','待复核'].includes(row.state))) {
      if (!groups.has(row.source)) groups.set(row.source,{ id:row.source, sourceId:row.source, source:sourceName(row.source), origins:[] });
      groups.get(row.source).origins.push(row);
    }
    return [...groups.values()].map(group => {
      const active = group.origins.filter(row => row.state === '已生效'), rules = mergeRules(active);
      return {...group,rules,ruleNames:[...new Set(rules.map(rule=>rule.name))].join('、'),activeCount:active.length,state:active.length?'已生效':group.origins.some(row=>row.state==='待复核')?'待复核':'来源停用'};
    // 查询只定位聚合后的数据源，不裁剪参与限制合并的有效来源。
    }).filter(group => AdminQuery.matchesFields({keyword:group.source,rule:group.ruleNames},query))
      .sort((a,b)=>a.source.localeCompare(b.source,'zh-CN'));
  }
  // 联合组合一起结束，独立项只结束当前源；其他申请和部门来源分别保留。
  function targets(row) { return row.request.mode === 'joint' ? row.request.approvalItems.filter(part => part.status === '已生效') : [row.part]; }
  function blocked(row, actor) {
    if (!canRevoke(actor)) return '当前账号没有撤销授权的权限。';
    if (!row) return '授权记录不存在。';
    if (row.type !== 'request') return '部门初始访问请在部门配置中调整。';
    if (row.state !== '已生效') return row.state === '已撤销' ? '该授权已撤销。' : '当前记录没有可撤销的已生效授权。';
    return '';
  }
  function impact(id, actor) {
    const row = rawRecords().find(row => row.id === id);
    if (blocked(row,actor)) return null;
    const affected = targets(row), excluded = new Set(affected.map(part => 'REQUEST-GRANT-' + row.request.id + '-' + part.id));
    const others = rawRecords().filter(item => item.user === row.user && item.state === '已生效' && !excluded.has(item.id));
    return { row, targets: affected, sources: affected.map(part => {
      const remaining=others.filter(item=>item.source===part.source),ending=affected.filter(item=>item.source===part.source);
      return {source:part.source,name:sourceName(part.source),remaining:remaining.length,beforeRules:mergeRules([...remaining,...ending]),afterRules:mergeRules(remaining)};
    }) };
  }
  function revoke(id, reason, actor, revision) {
    const row = rawRecords().find(row => row.id === id), message = blocked(row,actor);
    if (message) return message;
    if (row.request.revision !== revision) return '授权记录已变化，请重新核对。';
    if (!reason.trim()) return '请填写撤销原因。';
    const affected = targets(row), time = now();
    for (const part of affected) Object.assign(part, { status: '已撤销', revokedAt: time, revokedBy: actor.name, revokedReason: reason.trim() });
    row.request.access = structuredClone(requests.effectiveAccess(row.request)); requests.aggregate(row.request); row.request.revision += 1;
    row.request.history.push({ time, actor: actor.name, action: '授权撤销', content: affected.map(part => sourceName(part.source)).join('、') + '：' + reason.trim() });
  }
  window.GrantDemo = { records, get, canView, canRevoke, blocked, impact, revoke, mergeRules, accessGroups, sourceName, identity };
})();
