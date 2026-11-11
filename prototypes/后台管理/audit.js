/* 审计只读查询历史快照，关联入口另外核对现有页面的查看能力。 */
(() => {
  'use strict';
  const defaults = () => ({ actor: '', action: '', target: '', status: 'all', fromDate: '', toDate: '', page: 1, pageSize: 10 });
  const query = defaults();
  function render(main, ui) {
    const { el, button, pair, textCell, pageHeading, modal, currentUser, viewRequest } = ui;
    if (!AuditDemo.canView(currentUser)) {
      const denied = el('section', 'planned-page'); denied.append(el('p', '', '当前账号没有查看权限审计的权限。'));
      main.replaceChildren(pageHeading('权限审计'), denied); return;
    }
    const reset = () => { Object.assign(query, defaults(), { pageSize: query.pageSize }); render(main, ui); main.querySelector('input[name="actor"]')?.focus(); };
    const tools = RecordControls.filters({ value: query, fields: [
      { key: 'actor', label: '操作者', placeholder: '操作者姓名或登录名' },
      { key: 'action', label: '操作类型', options: AuditDemo.actions.map(value => ({ value, label: value })) },
      { key: 'target', label: '目标对象', placeholder: '目标名称或编号' }
    ], statuses: [{ value: 'all', label: '全部结果' }, ...['成功', '失败', '已拒绝'].map(value => ({ value, label: value }))], statusLabel: '操作结果', dateLabel: '操作日期',
      onApply: next => { Object.assign(query, next); render(main, ui); main.querySelector('input[name="actor"]')?.focus(); }, onReset: reset });
    const resultTag = value => el('span', 'status-tag audit-result' + (value === '失败' ? ' is-failed' : value === '已拒绝' ? ' is-disabled' : ''), value);
    const rows = AuditDemo.records(currentUser, query), filtered = query.actor || query.action || query.target || query.status !== 'all' || query.fromDate || query.toDate;
    query.page = Math.max(1, Math.min(query.page, Math.ceil(rows.length / query.pageSize)));
    const panel = el('section', 'record-panel audit-panel'), empty = el('div', 'empty-state');
    empty.append(WorkbenchIcon('search'), el('p', '', filtered ? '没有匹配的审计记录' : '暂无审计记录'));
    if (filtered) empty.append(button('清空筛选', reset));
    panel.append(RecordTable.render({ caption: '权限审计', minWidth: 1020,
      columns: [{ key: 'actor', label: '操作者', width: 160 }, { key: 'action', label: '操作类型', width: 166 }, { key: 'target', label: '目标对象', width: 220, grow: 3 },
        { key: 'result', label: '结果', width: 128 }, { key: 'time', label: '操作时间', width: 166 }, { key: 'actions', label: '操作', width: 112, fixed: 'right' }],
      items: rows.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), empty,
      row: row => {
        const actor = el('div'), target = el('div');
        actor.append(textCell(row.actor.name)); if (row.actor.login) actor.append(el('small', 'cell-sub', row.actor.login));
        target.append(textCell(row.target.name), el('small', 'cell-sub', row.target.reference));
        const view = button('详情', trigger => detail(row.id, trigger), 'do-action is-primary', 'audit-' + row.id);
        view.setAttribute('aria-label', '查看审计详情 ' + row.id);
        const result = el('div'); result.append(resultTag(row.result));
        if (row.effect) result.append(el('small', 'cell-sub', row.effect));
        return [actor, textCell(row.action), target, result, textCell(row.time), view];
      }
    }), RecordControls.pager({ total: rows.length, ...query }, page => { query.page = page; render(main, ui); }, size => { query.pageSize = size; query.page = 1; render(main, ui); }));
    main.replaceChildren(pageHeading('权限审计'), tools, panel);
    const freshTrigger = id => main.querySelector('[data-focus="audit-' + id + '"]') || main.querySelector('input[name="actor"]');
    function detail(id, trigger) {
      const row = AuditDemo.get(id, currentUser); if (!row) return;
      const node = modal('审计详情', trigger, true), body = el('div', 'dialog-body'), fields = el('dl', 'detail-fields');
      node.classList.add('audit-detail-dialog');
      fields.append(pair('审计编号', row.id), pair('操作时间', row.time), pair('操作者', row.actor.name + (row.actor.login ? '（' + row.actor.login + '）' : '')),
        pair('操作类型', row.action), pair('目标对象', row.target.name + '（' + row.target.reference + '）'), pair('结果', row.result + (row.effect ? '，' + row.effect : '')), pair('关联编号', row.reference), pair('权限版本', row.version));
      body.append(fields, el('h3', '', row.result === '成功' ? '操作原因' : '处理原因'), el('p', 'audit-reason', row.reason));
      if (row.changes.length) {
        const comparison = el('section', 'change-preview audit-changes'), table = el('table'), head = el('thead'), headings = el('tr'), tbody = el('tbody');
        comparison.append(el('h3', '', '变更内容'));
        for (const label of ['项目', '变更前', '变更后']) { const th = el('th', '', label); th.scope = 'col'; headings.append(th); }
        head.append(headings);
        for (const change of row.changes) { const tr = el('tr'), label = el('th', '', change.label); label.scope = 'row'; tr.append(label, el('td', '', change.before), el('td', '', change.after)); tbody.append(tr); }
        table.append(head, tbody); comparison.append(table); body.append(comparison);
      }
      const footer = el('footer', 'dialog-footer'); footer.append(button('关闭', () => node.close()));
      if (row.requestId) {
        const request = AccessRequestDemo.records().find(value => value.id === row.requestId);
        const canRead = request && ((request.user === currentUser.id && SourceApprovalDemo.hasAbility(currentUser, 'my-requests.view')) ||
          (SourceApprovalDemo.hasAbility(currentUser, 'access-requests.view') && AccessRequestDemo.inbox(currentUser, 'done').some(value => value.id === request.id)));
        if (canRead) footer.append(button('查看关联申请', () => {
          node.close(); const related = viewRequest(request, freshTrigger(id));
          related.addEventListener('close', () => detail(id, freshTrigger(id)), { once: true });
        }));
        else body.append(el('p', 'form-note', request ? '当前账号没有查看关联申请的权限。' : '关联申请当前不可用。'));
        if (GrantDemo.canView(currentUser) && GrantDemo.records(currentUser, { origin: row.requestId }).length) footer.append(button('查看授权来源', () => { node.close(); GrantsPage.openOrigin(row.requestId); }));
      }
      node.append(body, footer); node.showModal();
    }
  }
  window.AuditPage = { render };
})();
