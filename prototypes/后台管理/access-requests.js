/* 复用后台查询、分页、规则全文与核对弹窗，只制作审批办理界面。 */
(() => {
  'use strict';
  const { users, departments } = PeopleDemo;
  const demo = AccessRequestDemo;
  const query = { requestId: '', applicant: '', sourceId: '', status: 'all', fromDate: '', toDate: '', page: 1, pageSize: 10 };
  const state = { tab: 'pending', selected: '', part: '', lastResult: null };
  const applicant = request => users.find(user => user.id === request.user);
  const department = request => departments.find(value => value.id === request.department)?.name || '—';
  const states = ['待审批', '待分流', '需补充', '已批准', '已驳回'];
  function render(main, ui) {
    const { el, button, status, pair, pageHeading, openForm, notify, currentUser, DataAccessTable, viewResult } = ui;
    const previous = { id: main.querySelector('.approval-document')?.dataset.requestId, queue: main.querySelector('.approval-queue')?.scrollTop || 0, body: main.querySelector('.approval-document-body')?.scrollTop || 0 };
    const all = demo.records();
    const canReview = SourceApprovalDemo.canReview(currentUser), canRoute = SourceApprovalDemo.hasAbility(currentUser, 'access-requests.route');
    const availableTabs = [...(canReview ? ['pending'] : []), ...(canRoute ? ['routing'] : []), ...(canReview ? ['done'] : [])];
    if (!availableTabs.length) { const denied = el('section', 'planned-page'); denied.append(el('p', '', '当前账号没有审批或核定分派权限。')); main.replaceChildren(pageHeading('访问申请'), denied); return; }
    if (!availableTabs.includes(state.tab)) state.tab = availableTabs[0];
    const buckets = Object.fromEntries(availableTabs.map(tab => [tab, demo.inbox(currentUser, tab, query)])), rows = buckets[state.tab];
    const filtered = Boolean(query.requestId || query.applicant || query.sourceId || query.status !== 'all' || query.fromDate || query.toDate);
    const reset = () => { Object.assign(query, { requestId: '', applicant: '', sourceId: '', status: 'all', fromDate: '', toDate: '', page: 1 }); state.selected = ''; state.part = ''; render(main, ui); };
    query.page = Math.max(1, Math.min(query.page, Math.ceil(rows.length / query.pageSize)));
    const pageRows = rows.slice((query.page - 1) * query.pageSize, query.page * query.pageSize);
    const selected = pageRows.find(request => request.id === state.selected) || pageRows[0];
    state.selected = selected?.id || '';
    const tools = RecordControls.filters({ value: query, fields: [{ key: 'applicant', label: '申请人', placeholder: '申请人姓名或登录名' }, { key: 'requestId', label: '申请编号' }, { key: 'sourceId', label: '数据源', options: PeopleDemo.dataSources.map(source => ({ value: source.id, label: source.name })) }], dateLabel: '提交日期', includeStatus: state.tab === 'done',
      statuses: [{ value: 'all', label: '全部状态' }, ...states.filter(value => !['待审批', '待分流'].includes(value)).map(value => ({ value, label: value }))],
      onApply: next => { Object.assign(query, next); state.selected = ''; state.part = ''; render(main, ui); main.querySelector('input[name="applicant"]')?.focus(); }, onReset: reset
    });
    const panel = el('section', 'record-panel approval-panel'), panelHeader = el('div', 'approval-panel-header'), tabs = el('div', 'prototype-ui approval-tabs');
    PrototypeUI.mount(tabs, { data: () => ({ active: state.tab }), methods: { change(value) { state.tab = value; state.selected = ''; state.part = ''; query.page = 1; query.status = 'all'; render(main, ui); main.querySelector('.approval-tabs .el-tabs__item.is-active')?.focus({ preventScroll: true }); } },
      template: `<el-tabs v-model="active" @tab-change="change">${availableTabs.map(tab => '<el-tab-pane label="' + ({ pending: '待我审批', routing: '待分流', done: '我已审批' }[tab]) + '（' + buckets[tab].length + '）" name="' + tab + '"></el-tab-pane>').join('')}</el-tabs>` });
    panelHeader.append(tabs);
    const lastRequest = state.lastResult?.actor === currentUser.id ? all.find(request => request.id === state.lastResult.id) : null;
    if (lastRequest) {
      const feedback = el('div', 'approval-feedback'), name = el('span', 'approval-feedback-name', applicant(lastRequest).name);
      name.title = applicant(lastRequest).name;
      const lastPart = lastRequest.approvalItems.find(item => item.id === state.lastResult.partId);
      const view = button('查看结果', trigger => viewResult(lastRequest, trigger), 'do-action is-primary', 'approval-view-result');
      view.setAttribute('aria-label', '查看' + applicant(lastRequest).name + '的审批办理结果');
      feedback.append(name, status(state.lastResult.kind==='review'?demo.decisionOf(lastPart):lastPart.status), view); panelHeader.append(feedback);
    }
    const workspace = el('div', 'approval-workspace'), queue = el('nav', 'approval-queue'); queue.setAttribute('aria-label', '访问申请队列');
    for (const request of pageRows) {
      const visibleParts = demo.partsFor(request, currentUser, state.tab, query), label = state.tab === 'routing' ? [...new Set(visibleParts.map(demo.triageReason))].join('、') : state.tab==='done' ? demo.decisionOf(visibleParts[0]) : visibleParts[0].status;
      const entry = button('', () => { state.selected = request.id; state.part = ''; render(main, ui); main.querySelector('[data-approval-id="' + request.id + '"]')?.focus({ preventScroll: true }); }, 'approval-entry' + (request.id === selected?.id ? ' is-current' : ''));
      entry.dataset.approvalId = request.id; entry.setAttribute('aria-current', request.id === selected?.id ? 'true' : 'false');
      entry.setAttribute('aria-label', applicant(request).name + '，' + request.reason + '，' + label);
      const identity = el('span', 'approval-entry-identity'); identity.append(el('strong', '', applicant(request).name), status(label));
      const title = el('span', 'approval-entry-title', request.reason); title.title = request.reason;
      const metadata = el('span', 'approval-entry-meta'), time = el('time', '', request.submitted); time.dateTime = request.submitted.replace(' ', 'T');
      const id = el('small', '', request.id); id.title = request.id; metadata.append(id, time);
      entry.append(identity, title, metadata); queue.append(entry);
    }
    if (!pageRows.length) queue.append(el('p', 'approval-queue-empty', filtered ? '无匹配项' : '暂无申请'));
    const document = el('article', 'approval-document'); document.setAttribute('aria-label', selected ? '申请 ' + selected.id + ' 的办理单据' : '申请办理单据');
    document.dataset.requestId = selected?.id || '';
    if (selected) {
      const visibleParts = demo.partsFor(selected, currentUser, state.tab, query), part = visibleParts.find(item => item.id === state.part) || visibleParts[0];
      state.part = part.id;
      const header = el('header', 'approval-document-heading'), heading = el('div');
      heading.append(status(state.tab==='done'?demo.decisionOf(part):part.status), el('span', 'request-id', selected.id + ' · 修订 ' + selected.revision));
      header.append(heading, el('h2', '', selected.reason));
      const metadata = el('dl', 'approval-metadata'); metadata.append(pair('申请人', applicant(selected).name + '（' + applicant(selected).login + '）'), pair('所属部门', department(selected)), pair('提交时间', selected.submitted)); header.append(metadata);
      const body = el('div', 'approval-document-body'), request = el('section', 'approval-section');
      const requestFields = el('dl', 'approval-request-fields'); requestFields.append(pair('需要的数据', selected.requestedScope || selected.reason), pair('已知数据源', (selected.sourceIds || []).map(demo.sourceName).join('、') || '由负责人核定'));
      request.setAttribute('aria-label', '申请资料'); request.append(requestFields);
      const decision = el('section', 'approval-section'), parts = el('div', 'prototype-ui approval-item-tabs');
      const decisionHeading = el('div', 'approval-decision-heading'); decisionHeading.append(el('h3', '', '核定与审批'));
      if (demo.open(part) && part.status !== '待分流' && !['已撤回','已驳回','需补充'].includes(selected.status) && SourceApprovalDemo.hasAbility(currentUser, 'access-requests.route')) decisionHeading.append(button('重新核定数据源', trigger => assign(selected, part, trigger), 'do-action', 'reassign-source'));
      decision.append(decisionHeading);
      if (selected.approvalItems.length > 1) {
        PrototypeUI.mount(parts, { data: () => ({ active: part.id, items: visibleParts.map(item => ({ ...item, number: selected.approvalItems.indexOf(item) + 1 })) }), methods: { change(value) { state.part = value; render(main, ui); main.querySelector('.approval-item-tabs .el-tabs__item.is-active')?.focus({ preventScroll: true }); }, sourceName: demo.sourceName },
          template: `<el-tabs v-model="active" @tab-change="change"><el-tab-pane v-for="item in items" :key="item.id" :name="item.id" :label="'子项 '+item.number+' · '+sourceName(item.source)"></el-tab-pane></el-tabs>` });
        decision.append(el('p', 'approval-boundary', selected.mode === 'joint' ? '联合办理：所有必要子项通过后一起生效。' : '独立办理：各子项通过后分别生效。'), parts);
      }
      const partInfo = el('div', 'approval-part-meta');
      if(state.tab==='done'&&demo.decisionOf(part)==='已批准'&&part.status!=='已批准')partInfo.append(el('span','','当前授权'),status(part.status));
      else if(state.tab!=='done')partInfo.append(status(part.status));
      const review = demo.reviewInfo(part, selected);
      if(review){partInfo.append(el('span','',review.label+'：'+review.identity));if(review.timeLabel)partInfo.append(el('span','',review.timeLabel+'：'+review.time));}
      if (demo.open(part) && part.reviewer && part.routeReason) partInfo.append(el('span', '', part.routeReason)); decision.append(partInfo);
      if (part.source) { const table = el('div', 'prototype-ui approval-access-table'); PrototypeUI.mount(table, { render: () => Vue.h(DataAccessTable, { access: [part] }) }); decision.append(table); }
      if (part.reason) decision.append(el('h4', '', '办理说明'), el('p', 'approval-prose', part.reason));
      const blocked = demo.blocked(selected, part, currentUser);
      const history = el('section', 'approval-section'); history.append(el('h3', '', '处理记录'));
      if (!selected.history.length) history.append(el('p', 'approval-muted', '尚无审批记录'));
      else { const list = el('ol', 'approval-history'); for (const entry of selected.history) { const row = el('li'); row.append(el('small', '', entry.time + ' · ' + entry.actor), el('strong', '', entry.action), el('p', '', entry.content)); list.append(row); } history.append(list); }
      body.append(header, request, decision, history);
      const footer = el('footer', 'approval-actions'), actionContext = el('div', 'approval-action-context');
      if (selected.approvalItems.length > 1 || part.status === '待分流') {
        const current = el('span', 'approval-current-item', '子项 ' + (selected.approvalItems.indexOf(part) + 1) + ' · ' + demo.sourceName(part.source));
        current.title = current.textContent; actionContext.append(current);
      }
      if (part.status === '待分流' && !['已撤回', '已驳回', '需补充'].includes(selected.status)) {
        const canRoute = SourceApprovalDemo.hasAbility(currentUser, 'access-requests.route'), note = el('p', 'approval-action-reason', canRoute ? part.routeReason : '当前账号没有核定与分派权限。'); note.id = 'approval-action-reason';
        const buttons = el('div', 'approval-action-buttons'); actionContext.append(note);
        const canConfigure = part.source && SourceApprovalDemo.hasAbility(currentUser, 'sources.approval-config');
        const configure = button(part.source ? '重新核定数据源' : '核定数据源', trigger => assign(selected, part, trigger), canConfigure ? 'admin-button' : 'admin-button is-primary', 'assign-source'); configure.disabled = !canRoute; configure.setAttribute('aria-describedby','approval-action-reason'); buttons.append(configure);
        if (canConfigure) buttons.append(button('配置审批责任', trigger => SourceApprovalsPage.configure(PeopleDemo.dataSources.find(source => source.id === part.source), trigger, ui, () => {
          state.lastResult = { id: selected.id, partId: part.id, actor: currentUser.id,kind:'routing' }; render(main, ui);
          // 配置后原单可能已离开待分流，显式回看该申请，再回到保留的队列。
          viewResult(selected, main.querySelector('[data-focus="approval-view-result"]'));
        }), 'admin-button is-primary', 'configure-source-approval'));
        footer.append(actionContext, buttons);
      } else if (part.status === '待审批' && !['已撤回', '已驳回', '需补充'].includes(selected.status)) {
        const buttons = el('div', 'approval-action-buttons');
        for (const [action, label, style] of [['reject', '驳回', 'admin-button is-danger'], ['supplement', '要求补充', 'admin-button'], ['approve', '核定并批准', 'admin-button is-primary']]) {
          const reason = demo.blocked(selected, part, currentUser, action), control = button(label, trigger => decide(selected, part, action, trigger), style, 'approval-' + action); control.disabled = Boolean(reason); if (reason) { control.title = reason; control.setAttribute('aria-describedby', 'approval-action-reason'); } buttons.append(control);
        }
        if (blocked) { const note = el('p', 'approval-action-reason', blocked); note.id = 'approval-action-reason'; actionContext.append(note); } footer.append(actionContext, buttons);
      } else {
        const note = !demo.inProgress(selected) && demo.open(part) ? '当前申请' + selected.status + '，该子项不再继续办理。' : part.status === '已批准' ? demo.inProgress(selected) ? '等待其他必要子项审批。' : '当前申请'+selected.status+'，该组合未生效。' : '';
        if (note) { actionContext.append(el('p', '', note)); footer.append(actionContext); }
      }
      document.append(body); if (footer.childElementCount) document.append(footer);
    } else {
      const empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('inbox'), el('p', '', filtered ? '没有匹配的申请' : state.tab === 'pending' ? '暂无待我审批的申请' : state.tab === 'routing' ? '暂无待分流申请' : '暂无我已审批的申请'));
      if (filtered) empty.append(button('清空筛选', reset)); document.append(empty);
    }
    workspace.append(queue, document); panel.append(panelHeader, workspace, RecordControls.pager({ total: rows.length, ...query }, page => { query.page = page; state.selected = ''; state.part = ''; render(main, ui); main.querySelector('.approval-entry.is-current')?.focus({ preventScroll: true }); }, size => { query.pageSize = size; query.page = 1; state.selected = ''; state.part = ''; render(main, ui); main.querySelector('.el-pagination__sizes input')?.focus({ preventScroll: true }); }));
    main.replaceChildren(pageHeading('访问申请'), tools, panel);
    // 切换子项保留阅读位置；切换申请从单据顶部开始，队列选中项始终可见。
    queue.scrollTop = previous.queue;
    queue.querySelector('.is-current')?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    if (selected && previous.id === selected.id) document.querySelector('.approval-document-body').scrollTop = previous.body;
    function decide(request, part, action, trigger) {
      const blocked = demo.blocked(request, part, currentUser, action); if (blocked) { notify(blocked); return; }
      const approve = action === 'approve', label = { approve: '批准', supplement: '要求补充', reject: '驳回' }[action], revision = request.revision;
      const autoReady = request.mode !== 'joint' || request.approvalItems.every(item => item.id === part.id || item.status === '已批准');
      const queueScroll = main.querySelector('.approval-queue').scrollTop;
      const dialog = openForm({ title: label + '当前子项 · ' + applicant(request).name, trigger, value: { access: [{ source: part.source, rules: structuredClone(part.rules) }], reason: '' },
        note: request.id + ' · 子项 ' + (request.approvalItems.indexOf(part) + 1) + ' · 修订 ' + revision,
        fields: [...(approve ? [{ key: 'access', label: '核定输出限制规则', type: 'data-access', required: true, full: true, singleSource: true, sourceReadonly: true }] : []),
          { key: 'reason', label: approve ? '审批意见（选填）' : action === 'supplement' ? '需要补充的内容' : '驳回原因', type: 'textarea', required: !approve, full: true }],
        validate: data => approve && request.approvalItems.some(value => value.id !== part.id && value.source === data.access[0]?.source) ? '该数据源已有审批子项，请勿重复核定。' : undefined,
        previewTitle: '核对' + label + '结果', previewLabel: '核对' + label, previewContentLabel: '核对内容', saveLabel: '确认' + label, summary: true, danger: action === 'reject',
        preview: data => [{ label: '申请人', after: applicant(request).name }, { label: '申请与修订', after: request.id + ' · 修订 ' + revision },
          { label: '办理子项', after: '子项 ' + (request.approvalItems.indexOf(part) + 1) + ' · ' + demo.sourceName(approve ? data.access[0].source : part.source) },
          ...(approve ? [{ label: '核定范围', after: data.access.map(value => demo.sourceName(value.source) + '\n' + value.rules.map(rule=>rule.name+'：'+rule.content).join('\n')).join('\n') }] : []),
          ...(data.reason.trim() ? [{ label: '办理说明', after: data.reason.trim() }] : []), { label: '确认后状态', after: approve ? autoReady ? '审批通过，访问即生效。' : '当前子项将获批准，等待其他必要子项通过。' : action === 'reject' ? '确认后将驳回当前子项。' : '当前子项将退回申请人补充。' }],
        save: data => demo.decide(request, part.id, action, data, currentUser, revision), success: approve ? autoReady ? '审批已通过，访问已生效' : '当前子项已批准' : '申请已' + label,
        onSaved: () => {
          // 保留查询、分组与有效页码；结果通过只读抽屉回看，队列继续定位相邻申请。
          const index = pageRows.findIndex(value => value.id === request.id);
          state.selected = demo.partsFor(request, currentUser, state.tab, query).length ? request.id : (pageRows[index + 1] || pageRows[index - 1])?.id || '';
          state.part = ''; state.lastResult = { id: request.id, partId: part.id, actor: currentUser.id,kind:'review' };
          render(main, ui);
          const currentQueue = main.querySelector('.approval-queue'); currentQueue.scrollTop = queueScroll;
          currentQueue.querySelector('.is-current')?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
          // 等通用弹窗完成焦点返回后，将键盘交还当前队列或空结果。
          dialog.addEventListener('close', () => {
            const target = main.querySelector('.approval-entry.is-current') || main.querySelector('.approval-document .empty-state p');
            if (target) { target.tabIndex = target.matches('button') ? 0 : -1; target.focus({ preventScroll: true }); }
          }, { once: true });
        }
      });
    }
    function assign(request, part, trigger) {
      const revision = request.revision;
      const dialog = openForm({ title: '核定数据源与分派 · ' + applicant(request).name, trigger, value: { source: part.source, assignment: '' },
        fields: [{ key: 'source', label: '核定数据源', options: PeopleDemo.dataSources.map(source=>({value:source.id,label:source.name})), required: true, full: true },
          { key: 'assignment', label: '分派预览', type: 'approval-assignment', applicantId: request.user, full: true }],
        validate: data => request.approvalItems.some(value=>value.id!==part.id&&value.source===data.source) ? '该数据源已有审批子项，请勿重复核定。' : undefined,
        previewTitle: '核对数据源与分派', previewLabel: '核对分派', previewContentLabel: '核对内容', saveLabel: '确认核定', summary: true,
        preview: data => { const route = SourceApprovalDemo.resolve(data.source,request.user); return [
          {label:'申请人',after:applicant(request).name},{label:'核定数据源',after:demo.sourceName(data.source)},
          {label:'审批负责人',after:route.reviewer?SourceApprovalDemo.names(route.reviewer):'待分流'},
          ...(route.reason ? [{label:'分派原因',after:route.reason}] : []),{label:'确认后状态',after:route.reviewer?'待审批':'待分流'}]; },
        save: data=>demo.reassign(request,part.id,data,currentUser,revision),success:'数据源已核定，分派状态已更新',
        onSaved:()=>{
          state.lastResult={id:request.id,partId:part.id,actor:currentUser.id,kind:'routing'}; state.selected=demo.partsFor(request,currentUser,state.tab,query).length?request.id:''; state.part=''; render(main,ui);
          dialog.addEventListener('close',()=>{ (main.querySelector('.approval-entry.is-current') || main.querySelector('[data-focus="approval-view-result"]'))?.focus({preventScroll:true}); },{once:true});
        }
      });
    }
  }
  window.AccessRequestsPage = { render };
})();
