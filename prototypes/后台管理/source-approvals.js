/* 数据源概况与审批责任共用入口，保留现有审批分派与核对流程。 */
(() => {
  'use strict';
  const { dataSources, users } = PeopleDemo, demo = SourceApprovalDemo;
  const query = { sourceName: '', primary: '', alternate: '', status: 'all', page: 1, pageSize: 10 };
  let activeTab = 'overview';
  const identity = id => { const user = users.find(user => user.id === id); return user ? user.name + '（' + user.login + '）' : '未指定'; };
  function configure(source, trigger, ui, afterSave) {
    const { openForm, currentUser } = ui, revision = source.approval.revision;
    const original = { primary: source.approval.primary, alternate: source.approval.alternate };
    const options = users.filter(user => demo.canReview(user) || Object.values(original).includes(user.id)).map(user => ({ value: user.id, label: identity(user.id) + (demo.canReview(user) ? '' : '（不可用）'), disabled: !demo.canReview(user) }));
    const affected = AccessRequestDemo.records().filter(AccessRequestDemo.inProgress).flatMap(request => request.approvalItems.filter(part => part.source === source.id && AccessRequestDemo.open(part)));
    const dialog = openForm({ title: '配置审批责任 · ' + source.name, trigger, value: original,
      fields: [{ key: 'primary', label: '主审批人', options, required: true }, { key: 'alternate', label: '替代负责人', options, required: true }],
      hasChanges: data => data.primary !== original.primary || data.alternate !== original.alternate,
      validate: data => demo.error(data), previewTitle: '核对“' + source.name + '”的审批责任', previewLabel: '核对配置', saveLabel: '保存',
      preview: data => [{ label: '主审批人', before: identity(source.approval.primary), after: identity(data.primary) }, { label: '替代负责人', before: identity(source.approval.alternate), after: identity(data.alternate) },
        { label: '未处理子项', before: affected.length + ' 项', after: '按新责任重新匹配；已办理记录保留原办理人' }],
      save: data => demo.save(source, data, currentUser, revision), success: source.name + '的审批责任已保存',
      onSaved: () => dialog.addEventListener('close', () => {
        if (afterSave) afterSave();
        else (document.querySelector('[data-focus="configure-' + source.id + '"]') || document.querySelector('input[name="sourceName"]'))?.focus({ preventScroll: true });
      }, { once: true }) });
    return dialog;
  }
  function render(main, ui) {
    const { el, button, status, pair, textCell, pageHeading, modal, currentUser } = ui;
    const panel = el('section', 'record-panel source-approval-panel'), tabs = el('div', 'prototype-ui source-approval-tabs');
    PrototypeUI.mount(tabs, { data: () => ({ active: activeTab }), methods: { changed(value) { activeTab = value; render(main, ui); main.querySelector('.source-approval-tabs .is-active')?.focus(); } },
      template: '<el-tabs v-model="active" @tab-change="changed"><el-tab-pane label="数据源概况" name="overview"></el-tab-pane><el-tab-pane label="审批责任" name="approval"></el-tab-pane></el-tabs>' });
    panel.append(tabs);
    if (activeTab === 'overview') { DataSourcesPage.render(main, ui, { tabs }); return; }
    const reset = () => { Object.assign(query, { sourceName: '', primary: '', alternate: '', status: 'all', page: 1 }); render(main, ui); };
    const tools = RecordControls.filters({ value: query, fields: [{ key: 'sourceName', label: '数据源名称' }, { key: 'primary', label: '主审批人', placeholder: '主审批人姓名或登录名' }, { key: 'alternate', label: '替代负责人', placeholder: '替代负责人姓名或登录名' }], includeDates: false,
      statuses: [{ value: 'all', label: '全部状态' }, ...['已配置', '未配置', '需复核'].map(value => ({ value, label: value }))],
      onApply: next => { Object.assign(query, next); render(main, ui); main.querySelector('input[name="sourceName"]')?.focus(); }, onReset: reset });
    tools.classList.add('source-approval-filters');
    const rows = dataSources.filter(source => (query.status === 'all' || demo.state(source) === query.status) && AdminQuery.matchesFields({ sourceName: source.name, primary: identity(source.approval.primary), alternate: identity(source.approval.alternate) }, query));
    query.page = Math.max(1, Math.min(query.page, Math.ceil(rows.length / query.pageSize)));
    const empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('search'), el('p', '', '没有匹配的数据源'));
    if (query.sourceName || query.primary || query.alternate || query.status !== 'all') empty.append(button('清空筛选', reset));
    const personCell = id => { const user = users.find(user => user.id === id), cell = el('div'); cell.append(textCell(user?.name || '未指定')); if (user) cell.append(el('small', 'cell-sub', user.login)); return cell; };
    panel.append(RecordTable.render({ caption: '数据源审批责任', minWidth: 960,
      columns: [{ key: 'source', label: '数据源',width:220 }, { key: 'primary', label: '主审批人', width: 160 }, { key: 'alternate', label: '替代负责人', width: 160 }, { key: 'state', label: '责任状态', width: 104 }, { key: 'updated', label: '更新时间', width: 166 }, { key: 'actions', label: '操作', width: 128, fixed: 'right' }],
      items: rows.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), empty,
      row: source => [button(source.name, trigger => detail(source, trigger), 'cell-title', 'source-' + source.id), personCell(source.approval.primary), personCell(source.approval.alternate), status(demo.state(source)), textCell(source.approval.updated),
        demo.hasAbility(currentUser, 'sources.approval-config') ? button('配置责任', trigger => edit(source, trigger), 'do-action is-primary', 'configure-' + source.id) : el('span', 'cell-sub', '只读')]
    }), RecordControls.pager({ total: rows.length, ...query }, page => { query.page = page; render(main, ui); }, size => { query.pageSize = size; query.page = 1; render(main, ui); }));
    main.replaceChildren(pageHeading('数据源管理'), tools, panel);
    function detail(source, trigger) {
      const node = modal('数据源审批责任', trigger), body = el('div', 'dialog-body'), fields = el('dl', 'detail-fields');
      fields.append(pair('数据源', source.name), pair('责任状态', demo.state(source)), pair('主审批人', identity(source.approval.primary)), pair('替代负责人', identity(source.approval.alternate)), pair('更新时间', source.approval.updated));
      body.append(fields); if (demo.error(source.approval)) body.append(el('p', 'form-note', demo.error(source.approval)));
      const footer = el('footer', 'dialog-footer'); footer.append(button('关闭', () => node.close()));
      if (demo.hasAbility(currentUser, 'sources.approval-config')) footer.append(button('配置审批责任', () => { node.close(); edit(source, trigger); }, 'admin-button is-primary'));
      node.append(body, footer); node.showModal();
    }
    function edit(source, trigger) {
      configure(source, trigger, ui);
    }
  }
  const AssignmentPreview = {
    props: ['sourceId', 'applicantId'],
    computed: { route() { return demo.resolve(this.sourceId, this.applicantId); }, target() { return this.route.reviewer ? identity(this.route.reviewer) : '待分流'; } },
    template: '<div class="readonly-value"><strong>{{target}}</strong><p v-if="route.reason" class="approval-route-summary">{{route.reason}}</p></div>'
  };
  window.SourceApprovalsPage = { render, configure, AssignmentPreview };
})();
