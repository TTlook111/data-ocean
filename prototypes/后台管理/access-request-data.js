/* 只在页面内演示审批后自动生效，不连接真实授权服务或计算授予上限。 */
(() => {
  'use strict';
  const { extraRequests, users, dataSources, outputRules } = PeopleDemo;
  const routing = SourceApprovalDemo;
  const sourceName = id => dataSources.find(source => source.id === id)?.name || '待核定数据源';
  const person = id => users.find(user => user.id === id);
  const timestamp = () => new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16);
  const rulesFor = source => structuredClone(outputRules.filter(rule => (source === 'finance' ? ['privacy', 'account'] : ['privacy', 'cost']).includes(rule.id)));
  const reviewIdentity = part => part.reviewedBy ? part.reviewedBy + (part.reviewedLogin ? '（' + part.reviewedLogin + '）' : '') : '未记录';
  // 已办示例的历史批准人固定保存，不能用当前数据源责任反推历史事实。
  const seedReviewers = { 'SQ-DEMO-011': ['u13'], 'SQ-DEMO-012': ['u13'], 'SQ-DEMO-014': ['u09'], 'SQ-DEMO-015': ['u02'], 'SQ-DEMO-016': ['u13'],
    'SQ-DEMO-017': ['u09'], 'SQ-DEMO-018': ['u02', 'u13'], 'SQ-DEMO-019': ['u01'], 'SQ-DEMO-020': ['u01'], 'SQ-DEMO-021': ['u01'], 'SQ-DEMO-022': ['u01'] };
  const item = (source, index, status = '待审批') => ({ id: 'item-' + (index + 1), source, rules: source && routing.state(dataSources.find(value=>value.id===source)) === '已配置' ? rulesFor(source) : [], status, reviewer: '', reason: '' });
  const seeds = [
    ['u11', '核对季度应收与费用', '汇总本季度应收、费用及回款差异。', ['finance']],
    ['u09', '核对商品上架与退市', '查看商品基础目录、上架日期与在售状态。', ['products']],
    ['u06', '编制滨江店经营周报', '需要近两周销售数量、退货与库存汇总。', ['retail']],
    ['u03', '核对销售与回款差异', '需要销售与财务数据共同完成同一笔对账。', ['retail', 'finance'], 'joint'],
    ['u08', '分析城北店退货情况', '按日统计退货数量与主要商品。', ['retail']],
    ['u12', '分别分析商品与销售', '分别查看商品基础资料与销售汇总，两个任务可独立开展。', ['products', 'retail']],
    ['u13', '核对门店销售汇总', '查看本月各门店的销售与退货汇总，数据源交由负责人核定。', ['']],
    ['u10', '核对仓储与销售差异', '按商品对照入库、销售与期末库存。', ['retail']],
    ['u07', '核对城北店售后', '查看本周售后与退货处理汇总。', ['aftersales']],
    ['u15', '核对旧城店历史记录', '查看关店前的销售汇总，不需要个人明细。', ['retail']],
    ['u04', '核对月度回款', '核对本月回款与应收差额。', ['finance'], 'independent', '需补充'],
    ['u02', '查看商品基础目录', '查看在售商品名称与分类。', ['products'], 'independent', '生效失败'],
    ['u14', '查询员工个人资料', '需要员工联系信息。', ['retail'], 'independent', '已驳回'],
    ['u05', '核对历史采购记录', '申请人已撤回本次数据访问申请。', ['products'], 'independent', '已撤回']
  ];
  for (const [index, [user, reason, requestedScope, sources, mode = 'independent', status = '待审批']] of seeds.entries()) {
    const request = { id: 'SQ-REVIEW-' + String(index + 1).padStart(3, '0'), user, department: person(user).department, reason, requestedScope,
      sourceIds: sources.filter(Boolean), mode, status, revision: 1, access: [], submitted: '2026-11-10 ' + String(10 - Math.floor(index / 4)).padStart(2, '0') + ':' + String(50 - index).padStart(2, '0'),
      approvalItems: sources.map((source, i) => item(source, i, status)), history: [] };
    if (status !== '待审批') {
      request.approvalItems[0].reviewer = 'u01';
      request.approvalItems[0].decision = status === '生效失败' ? '已批准' : status;
      if (status !== '已撤回') Object.assign(request.approvalItems[0], { reviewedById: 'u01', reviewedBy: person('u01').name, reviewedLogin: person('u01').login, reviewedAt: request.submitted });
      request.approvalItems[0].reason = status === '需补充' ? '请补充具体核对月份及所需汇总指标。' : status === '已驳回' ? '当前业务用途不足以支持所申请的个人联系信息。' : status === '已撤回' ? '本次工作已取消。' : '已批准查看商品基础资料。';
      if (status === '生效失败') request.approvalItems[0].activationError = '授权更新失败，当前未生效。';
      request.history.push({ time: request.submitted, actor: status === '已撤回' ? person(user).name : '林管理员', action: status === '生效失败' ? '已批准' : status, content: request.approvalItems[0].reason });
      if (status === '生效失败') request.history.push({ time: request.submitted, actor: '系统', action: '生效失败', content: request.approvalItems[0].activationError });
    }
    extraRequests.push(request);
  }
  function ensure(request) {
    if (!request.approvalItems) {
      const sources = request.sourceIds || request.access.map(value => value.source);
      const effective = request.status === '已生效';
      const initial = effective ? '已生效' : ['已驳回', '已撤回', '需补充', '生效失败'].includes(request.status) ? request.status : '待审批';
      request.sourceIds = sources.filter(Boolean);
      request.approvalItems = (sources.length ? sources : ['']).map((source, index) => ({ ...item(source, index, initial), rules: effective ? structuredClone(request.access.find(value => value.source === source)?.rules || []) : [] }));
      // 旧示例的待审 access 只表示申请意向，不能被当成已生效来源。
      if (!effective) request.access = [];
      request.mode = 'independent'; request.revision = 1; request.history = [];
      for (const [index, reviewerId] of (seedReviewers[request.id] || []).entries()) {
        const part = request.approvalItems[index], reviewer = person(reviewerId);
        Object.assign(part, { reviewer: reviewerId, reviewedById: reviewerId, reviewedBy: reviewer.name, reviewedLogin: reviewer.login, reviewedAt: request.submitted,
          decision: effective ? '已批准' : initial, reason: request.result || '' });
        if (effective) part.activatedAt = request.submitted;
        request.history.push({ time: part.reviewedAt, actor: reviewIdentity(part), action: part.decision, content: sourceName(part.source) + (part.reason ? '：' + part.reason : '') });
      }
      if (effective) request.history.push({ time: request.submitted, actor: '系统', action: '自动生效', content: sources.map(sourceName).join('、') });
    }
    refresh(request); return request;
  }
  const open = part => ['待审批', '待分流'].includes(part.status);
  const inProgress = request => ['待审批', '待分流', '部分已生效'].includes(request.status) && request.approvalItems.some(open);
  const effectiveAccess = request => request.approvalItems ? request.approvalItems.filter(part => part.status === '已生效').map(part => ({ source: part.source, rules: part.rules })) : request.status === '已生效' ? request.access : [];
  function aggregate(request) {
    const states = request.approvalItems.map(value => value.status);
    request.status = request.mode === 'joint' && states.includes('已驳回') ? '已驳回' : request.mode === 'joint' && states.includes('需补充') ? '需补充' : request.mode === 'joint' && states.includes('已撤回') ? '已撤回'
      : states.every(state => state === '已生效') ? '已生效' : states.includes('已生效') ? '部分已生效'
      : states.includes('待分流') ? '待分流' : states.includes('待审批') ? '待审批' : states.includes('生效失败') ? '生效失败'
      : states.includes('需补充') ? '需补充' : states.includes('已撤销') ? '已撤销' : states.every(state => state === '已撤回') ? '已撤回' : '已驳回';
  }
  function refresh(request, bump = true) {
    if (!inProgress(request)) return false;
    let changed = false;
    for (const part of request.approvalItems.filter(open)) {
      const match = routing.resolve(part.source, request.user);
      if (part.routeKey && part.routeKey !== match.key) changed = true;
      Object.assign(part, { reviewer: match.reviewer, routeKind: match.kind, routeReason: match.reason, routeKey: match.key, status: match.reviewer ? '待审批' : '待分流' });
    }
    if (changed && bump) request.revision += 1;
    aggregate(request); return changed;
  }
  const records = () => extraRequests.map(ensure);
  const decisionOf = part => part.decision || (['已生效','已撤销','生效失败','已批准'].includes(part.status) ? '已批准' : part.status);
  // 分派中的负责人不是已经批准的人；已办身份只读取当时保存的记录。
  function reviewInfo(part, request) {
    if (part.status === '已撤回' || open(part) && !inProgress(request)) return null;
    if (open(part)) {
      const reviewer = person(part.reviewer);
      return { label: '审批负责人', identity: reviewer ? reviewer.name + '（' + reviewer.login + '）' : '待分流', timeLabel: '', time: '' };
    }
    const approved = decisionOf(part) === '已批准';
    return { label: approved ? '批准人' : '办理人', identity: reviewIdentity(part), timeLabel: approved ? '批准时间' : '办理时间', time: part.reviewedAt || '未记录' };
  }
  // 收件范围先于数量和分页；同一组合中的他人子项不会成为当前账号待办。
  function partsFor(request, actor, tab, query = {}) {
    const parts = tab === 'routing' ? routing.hasAbility(actor, 'access-requests.route') && inProgress(request) ? request.approvalItems.filter(part => part.status === '待分流') : []
      : routing.canReview(actor) ? request.approvalItems.filter(part => part.reviewer === actor.id && (tab === 'pending' ? inProgress(request) && part.status === '待审批' : !open(part) && part.status !== '已撤回')) : [];
    return parts.filter(part => (!query.sourceId || part.source === query.sourceId) && (!query.status || query.status === 'all' || (tab === 'done' ? decisionOf(part) : part.status) === query.status));
  }
  const triageReason = part => !part.source ? '待核定数据源' : PeopleDemo.dataSources.find(source => source.id === part.source)?.approval.primary ? '审批责任需复核' : '缺审批责任';
  function inbox(actor, tab, query = {}) {
    return records().filter(request => partsFor(request, actor, tab, query).length &&
      AdminQuery.matchesFields({ requestId: request.id, applicant: [person(request.user).name, person(request.user).login] }, query) &&
      (!query.fromDate || request.submitted.slice(0,10) >= query.fromDate) && (!query.toDate || request.submitted.slice(0,10) <= query.toDate))
      .sort((a,b) => b.submitted.localeCompare(a.submitted) || a.id.localeCompare(b.id));
  }
  function refreshSource(sourceId, actor) {
    for (const request of records().filter(request => inProgress(request) && request.approvalItems.some(part => part.source === sourceId && open(part)))) {
      request.history.push({ time: timestamp(), actor: actor.name, action: '审批责任更新', content: sourceName(sourceId) + '的未处理子项已按新责任匹配。' });
    }
  }
  function blocked(request, part, actor, action = 'approve') {
    if (!inProgress(request)) return '当前申请已处理，请回看办理记录。';
    if (part.status === '待分流') return part.routeReason + (part.source ? '请先配置有效审批责任。' : '请先核定数据源。');
    if (request.user === actor.id) return '本人申请由' + (part.routeKind === 'substitute' ? '替代负责人' : '审批负责人') + person(part.reviewer).name + '审批。';
    if (request.status === '已撤回' || part.status !== '待审批') return '当前子项已' + (request.status === '已撤回' ? '撤回' : '处理') + '，请回看办理记录。';
    if (action === 'approve' && person(request.user)?.status !== '正常') return '申请账号当前已停用或尚未完成首次登录，批准前需先复核账号状态。';
    if (part.reviewer !== actor.id) return '该子项由' + person(part.reviewer).name + '审批。';
    if (!routing.canReview(actor)) return '当前账号没有可用的审批功能权限。';
    if (action === 'approve' && request.mode === 'joint' && request.approvalItems.some(value => ['已驳回', '需补充', '已撤回'].includes(value.status))) return '联合办理已有子项需补充或未通过，本组合暂不继续批准。';
    return '';
  }
  function decide(request, partId, action, data, actor, revision) {
    refresh(request);
    const part = request.approvalItems.find(value => value.id === partId);
    if (!part || request.revision !== revision) return '申请内容已变化，请重新核对当前修订。';
    const reason = blocked(request, part, actor, action); if (reason) return reason;
    if (!['approve', 'supplement', 'reject'].includes(action)) return '请选择有效的审批操作。';
    if (action !== 'approve' && !data.reason.trim()) return '请填写具体说明。';
    if (action === 'approve') {
      const access = data.access;
      if (access.length !== 1 || !dataSources.some(source => source.id === access[0].source) || !access[0].rules.length || access[0].rules.some(rule => !outputRules.some(current => current.id === rule.id))) return '请核定一个数据源及至少一条有效输出限制规则。';
      if (access[0].source !== part.source) return '数据源变化后须先重新核定与分派，不能沿用原审批负责人。';
      part.rules = structuredClone(access[0].rules);
    }
    part.status = { approve: '已批准', supplement: '需补充', reject: '已驳回' }[action];
    part.decision = part.status;
    part.reason = data.reason.trim();
    part.reviewedAt = timestamp(); part.reviewedById = actor.id; part.reviewedBy = actor.name; part.reviewedLogin = actor.login;
    request.history.push({ time: part.reviewedAt, actor: actor.name, action: part.status, content: sourceName(part.source) + (part.reason ? '：' + part.reason : '') });
    if (action === 'approve') {
      // 独立项立即联动；联合项全部通过后一起联动，首个批准不会产生部分授权。
      const ready = request.mode === 'joint' ? request.approvalItems.every(value => value.status === '已批准') ? request.approvalItems : [] : [part];
      for (const value of ready) { value.status = '已生效'; value.activatedAt = timestamp(); }
      if (ready.length) {
        request.access = structuredClone(effectiveAccess(request));
        request.history.push({ time: timestamp(), actor: '系统', action: '自动生效', content: ready.map(value => sourceName(value.source)).join('、') });
      }
    }
    aggregate(request);
    request.revision += 1;
  }
  function withdraw(request, actor, revision) {
    ensure(request);
    if (request.user !== actor.id) return '只能撤回本人申请。';
    if (request.revision !== revision || !inProgress(request)) return '申请状态已变化，请重新核对。';
    for (const part of request.approvalItems.filter(open)) part.status = '已撤回';
    aggregate(request); request.revision += 1;
    request.history.push({ time: timestamp(), actor: actor.name, action: '撤回未处理子项', content: '未处理子项已撤回。' });
  }
  function reassign(request, partId, data, actor, revision) {
    refresh(request);
    const part = request.approvalItems.find(value => value.id === partId);
    if (!routing.hasAbility(actor, 'access-requests.route')) return '当前账号没有核定与分派权限。';
    if (!part || revision !== request.revision) return '申请已变化，请重新核对当前修订。';
    if (!open(part) || ['已撤回', '已驳回', '需补充'].includes(request.status)) return '已处理子项不能直接改源，须修订后重新申请。';
    if (!dataSources.some(source => source.id === data.source)) return '请选择有效的数据源。';
    if (request.approvalItems.some(value => value.id !== partId && value.source === data.source)) return '该数据源已有审批子项，请勿重复核定。';
    if (part.source !== data.source) { part.source = data.source; part.rules = []; }
    refresh(request, false); request.revision += 1;
    request.history.push({ time: timestamp(), actor: actor.name, action: '核定与分派', content: sourceName(part.source) + '：' + (part.reviewer ? routing.names(part.reviewer) + (part.routeReason ? '；' + part.routeReason : '') : part.routeReason) });
  }
  window.AccessRequestDemo = { records, ensure, blocked, decide, withdraw, effectiveAccess, aggregate, decisionOf, reviewIdentity, reviewInfo, sourceName, open, inProgress, partsFor, inbox, triageReason, refreshSource, reassign };
})();
