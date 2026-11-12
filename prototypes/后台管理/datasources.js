/* 复用后台查询、表格、抽屉和 Element Plus；表达数据源到治理的交接，不实现真实服务。 */
(() => {
  'use strict';
  const demo = DataSourceDemo;
  const impacts = GovernanceImpactDemo;
  const queries = new Map();
  const positions = new Map();
  let main, ui, section = 'sources', collectionTab = 'tasks', detailSequence = 0, changeScope = '';
  const refresh = () => window.dispatchEvent(new Event('hashchange'));
  const allowed = ability => SourceApprovalDemo.hasAbility(ui.currentUser, ability);
  const person = id => { const user = PeopleDemo.users.find(item => item.id === id); return user ? user.name + '（' + user.login + '）' : '未指定'; };
  const objectName = table => (table.schema ? table.schema + '.' : '') + table.name;
  const getQuery = key => { if (!queries.has(key)) queries.set(key, { keyword: '', owner: '', type: '', status: 'all', fromDate: '', toDate: '', page: 1, pageSize: 10 }); return queries.get(key); };
  function badge(value) {
    const tag = ui.el('span', 'status-tag', value);
    if (['失败', '网络异常', '认证异常', '已禁止使用'].includes(value)) tag.classList.add('ds-status-danger');
    else if (['部分成功', '部分确认', '未测试', '未验证', '待治理', '待复核', '待核实', '处理中', '待知识更新', '新发现', '结构变化', '属性缺失', '访问丢失', '访问丢失待核实', '进行中', '排队', '未知', '未采集', '待重新采集', '不支持', '无权限'].includes(value)) tag.classList.add('is-pending');
    else if (['草稿', '已停用', '已退役', '已取消', '已撤下'].includes(value)) tag.classList.add('is-disabled');
    return tag;
  }
  function stack(primary, secondary) { const node = ui.el('div'); node.append(typeof primary === 'string' ? ui.textCell(primary) : primary); if (secondary) node.append(ui.el('small', 'cell-sub', secondary)); return node; }
  function collectionCell(source) {
    const task = demo.activeTask(source.id), node = stack(badge(task ? task.state : source.collection), task ? task.at : source.lastCollected || '尚无采集记录');
    node.dataset.sourceCollection = source.id;
    return node;
  }
  function actions(...nodes) { const node = ui.el('div', 'ds-actions'); node.append(...nodes.filter(Boolean)); return node; }
  function link(label, action, key) { return ui.button(label, action, 'do-action is-primary', key); }
  function navigate(key, source, filter) {
    if (source) demo.selected = source.id;
    if (filter) Object.assign(getQuery(key + ':' + demo.selected), filter, { page: 1 });
    if (location.hash === '#' + key) refresh(); else location.hash = key;
  }
  // 返回恢复原列表位置；抽屉中的按钮使用原记录入口作为焦点落点。
  function rememberPosition(trigger) {
    const scroll = main?.querySelector('.rt-scroll');
    positions.set(location.hash, { source: demo.selected, focus: trigger?.dataset.focus || document.activeElement?.dataset.focus, top: scroll?.scrollTop || 0, left: scroll?.scrollLeft || 0 });
  }
  function restorePosition() {
    const saved = positions.get(location.hash); if (!saved) return;
    positions.delete(location.hash);
    requestAnimationFrame(() => {
      const scroll = main.querySelector('.rt-scroll'); if (scroll) { scroll.scrollTop = saved.top; scroll.scrollLeft = saved.left; }
      const target = saved.focus && main.querySelector('[data-focus="' + CSS.escape(saved.focus) + '"]');
      (target || main.querySelector('[data-focus="ds-scope-governance"]') || main.querySelector('tbody button') || main.querySelector('input'))?.focus({ preventScroll: true });
    });
  }
  const activeChanges = table => demo.changesFor(table).filter(change => impacts.progress(change) !== '已完成');
  function catalogStatus(table) {
    const pending = demo.pendingVerification(table).length, active = activeChanges(table).length;
    return stack(actions(...demo.catalogStates(table).map(badge)), pending ? pending + ' 项待核实' : active ? active + ' 项变化待处理' : '');
  }
  function catalogNextAction(table) {
    if (activeChanges(table).length && allowed('collections.view')) return link('查看变化', trigger => openChanges(table.source, objectName(table), trigger), 'ds-next-' + table.id);
    if (!allowed('governance.view')) return null;
    if (demo.pendingVerification(table).length) return ui.el('span', 'cell-sub', '待有权人员核实');
    return link('治理', trigger => navigateGovernance(demo.find(table.source), objectName(table), trigger), 'ds-next-' + table.id);
  }
  function note(text, danger = false) { const node = ui.el('p', 'ds-notice' + (danger ? ' is-danger' : ''), text); node.setAttribute('role', danger ? 'alert' : 'status'); return node; }
  function queryRows(rows, query, values) {
    return rows.filter(row => {
      const value = values(row), matchesStatus = query.status === 'all' || (Array.isArray(value.status) ? value.status.includes(query.status) : value.status === query.status);
      return matchesStatus && AdminQuery.matchesFields(value.fields, query) && (!query.fromDate || (row.at || row.updated || '').slice(0, 10) >= query.fromDate) && (!query.toDate || (row.at || row.updated || '').slice(0, 10) <= query.toDate);
    });
  }
  function filters(query, fields, statuses, dates = false, primary) {
    const statusLabel = section === 'catalog' ? '目录状态' : section === 'sources' ? '配置状态' : section === 'collections' ? collectionTab === 'tasks' ? '任务状态' : '处理进度' : section.startsWith('governance') ? '处理进度' : '资料状态';
    const form = RecordControls.filters({ value: query, fields, statusLabel, statuses: [{ value: 'all', label: '全部' + statusLabel }, ...statuses.map(value => ({ value, label: value }))], includeDates: dates, dateBeforeStatus: dates, dateLabel: section === 'collections' ? collectionTab === 'tasks' ? '采集开始日期' : '结构发现日期' : '更新日期', onApply: value => { Object.assign(query, value); refresh(); }, onReset: () => { Object.assign(query, { keyword: '', owner: '', type: '', status: 'all', fromDate: '', toDate: '', page: 1 }); refresh(); } });
    if (!primary) return form;
    const wrap = ui.el('div', 'people-query-card ds-query-card'); wrap.append(form, primary); return wrap;
  }
  function tablePanel(rows, query, columns, row, emptyText, tabs) {
    const panel = ui.el('section', 'record-panel ds-record-panel');
    if (tabs) panel.append(tabs);
    query.page = Math.max(1, Math.min(query.page, Math.ceil(rows.length / query.pageSize)));
    const empty = ui.el('div', 'empty-state'); empty.append(WorkbenchIcon('search'), ui.el('p', '', emptyText));
    if (query.keyword || query.owner || query.type || query.status !== 'all' || query.fromDate) empty.append(ui.button('清空筛选', () => { Object.assign(query, { keyword: '', owner: '', type: '', status: 'all', fromDate: '', toDate: '', page: 1 }); refresh(); }));
    panel.append(RecordTable.render({ caption: document.title.split(' · ')[0], columns, items: rows.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), row, empty }), RecordControls.pager({ ...query, total: rows.length }, page => { query.page = page; refresh(); }, size => { query.pageSize = size; query.page = 1; refresh(); }));
    return panel;
  }
  function tabs(items, active, changed) {
    const host = ui.el('div', 'prototype-ui ds-tabs');
    PrototypeUI.mount(host, { data: () => ({ active }), methods: { change(value) { changed(value); requestAnimationFrame(() => main.querySelector('.ds-tabs .is-active')?.focus()); } }, template: '<el-tabs v-model="active" @tab-change="change">' + items.map(([name, label]) => '<el-tab-pane name="' + name + '" label="' + label + '"></el-tab-pane>').join('') + '</el-tabs>' });
    return host;
  }
  function context(source, primary) {
    const host = ui.el('section', 'ds-context'), select = ui.el('div', 'prototype-ui ds-source-select');
    PrototypeUI.mount(select, { data: () => ({ selected: source.id, options: demo.sources.map(item => ({ id: item.id, name: item.name })) }), methods: { changed(value) { demo.selected = value; if (section === 'collections') navigate('collections/' + value + '/' + collectionTab); else refresh(); } }, template: '<label class="ds-context-label" id="ds-current-source">当前数据源</label><el-select v-model="selected" aria-labelledby="ds-current-source" filterable @change="changed"><el-option v-for="item in options" :key="item.id" :value="item.id" :label="item.name"></el-option></el-select>' });
    host.append(select, actions(badge(source.connectionState), badge(source.health), source.collection === '待重新采集' && badge(source.collection)), primary || ui.button('数据源详情', trigger => sourceDetail(source, trigger)));
    return host;
  }
  function showDetail(title, trigger, build, footer) {
    const node = ui.modal(title, trigger, true); node.classList.add('ds-detail-dialog');
    // 知识明细可从源详情打开，两个抽屉分别关联自己的标题。
    const label = node.querySelector('.dialog-heading h2'); label.id = 'ds-detail-' + (++detailSequence); label.title = title; node.setAttribute('aria-labelledby', label.id);
    const body = ui.el('div', 'dialog-body'); build(body, node);
    const bar = ui.el('footer', 'dialog-footer'); bar.append(ui.button('关闭', () => node.close())); footer?.(bar, node);
    node.append(body, bar); node.showModal();
    // 标题过长时固定抽屉边界，在正文保留可阅读和复制的完整名称。
    if (label.scrollWidth > label.clientWidth) body.prepend(fields(body, [['完整名称', title, true]]));
    return node;
  }
  function fields(body, entries) {
    const list = ui.el('dl', 'detail-fields ds-fields');
    entries.forEach(([label, value, fullWidth]) => { const item = ui.pair(label, String(value ?? '—')); if (fullWidth) item.classList.add('full-width'); list.append(item); });
    body.append(list); return list;
  }
  // 调查所需的来源信息保留在折叠区，不抢占字段和业务解释的首屏位置。
  function sourceFacts(body, entries, popupTarget) {
    const host = ui.el('div', 'prototype-ui ds-source-facts');
    PrototypeUI.mount(host, { data: () => ({ entries, helpVisible: false }), methods: { dismissHelp(event) { if (!this.helpVisible) return; event.preventDefault(); event.stopPropagation(); this.helpVisible = false; } }, setup: () => ({ popupTarget }), template: '<el-collapse><el-collapse-item title="来源与采集信息" name="facts"><dl class="detail-fields ds-fields"><div v-for="entry in entries" :key="entry[0]"><dt class="ds-fact-label">{{entry[0]}}<el-tooltip v-if="entry[2]" v-model:visible="helpVisible" :content="entry[2]" :trigger="[\'hover\',\'focus\']" :append-to="popupTarget" placement="top-end" popper-class="ds-fact-tooltip" :show-after="200"><button type="button" class="ds-fact-help" :aria-label="entry[0]+\'说明\'" @keydown.esc="dismissHelp"><span aria-hidden="true">?</span></button></el-tooltip></dt><dd>{{entry[1]}}</dd></div></dl></el-collapse-item></el-collapse>' });
    body.append(host);
  }
  function heading(body, title) { body.append(ui.el('h3', 'ds-section-heading', title)); }
  function renderSources(parentTabs) {
    const query = getQuery('sources');
    const rows = queryRows(demo.sources, query, source => ({ status: source.connectionState, fields: { keyword: source.name, owner: person(source.config.technical), type: source.config.type } })).sort((a, b) => b.updated.localeCompare(a.updated) || b.id.localeCompare(a.id));
    const tools = filters(query, [{ key: 'keyword', label: '数据源名称' }, { key: 'type', label: '数据库类型', options: ['MySQL', 'PostgreSQL'].map(value => ({ value, label: value })) }, { key: 'owner', label: '技术联系人', placeholder: '技术联系人姓名或登录名' }], ['草稿', '已启用', '已停用', '已退役'], false, allowed('sources.create') && ui.button('新增数据源', trigger => connectionEditor(null, trigger), 'admin-button is-primary', 'ds-create'));
    const panel = tablePanel(rows, query, [
      { key: 'name', label: '数据源', width: 210 }, { key: 'type', label: '数据库类型', width: 130 }, { key: 'owner', label: '技术联系人', width: 140 }, { key: 'state', label: '配置 / 连接', width: 138 }, { key: 'collection', label: '结构采集', width: 160 }, { key: 'knowledge', label: '治理 / 知识', width: 210 }, { key: 'actions', label: '操作', width: 192, fixed: 'right' }
    ], source => [stack(ui.button(source.name, trigger => sourceDetail(source, trigger), 'cell-title', 'ds-source-' + source.id), source.config.database || '未配置连接'), stack(source.config.type, source.config.version || '版本未识别'), stack(PeopleDemo.users.find(user => user.id === source.config.technical)?.name || '未指定', PeopleDemo.users.find(user => user.id === source.config.technical)?.login), stack(badge(source.connectionState), source.health), collectionCell(source), stack('完整确认 ' + source.governed + ' · 部分 ' + source.partialGoverned, source.knowledge + ' 项已发布 · ' + source.forbidden + ' 项禁止使用'), actions(allowed('sources.edit') && source.connectionState !== '已退役' && link('编辑连接', trigger => connectionEditor(source, trigger), 'ds-edit-' + source.id), sourceMenu(source))], '没有匹配的数据源', parentTabs);
    main.replaceChildren(ui.pageHeading('数据源管理'), tools, panel);
  }
  function sourceMenu(source) {
    const items = [];
    if (allowed('sources.test') && source.connectionState !== '已退役') items.push({ label: '测试连接', action: trigger => connectionEditor(source, trigger, source.connectionState === '草稿' ? 'edit' : 'test') });
    if (allowed('collections.collect') && source.connectionState === '已启用') items.push({ label: '发起采集', action: trigger => collect(source, trigger) });
    items.push({ label: '查看结构目录', action: () => navigate('catalog', source) });
    if (allowed('sources.edit') && source.credentialSet && source.connectionState !== '已退役') items.push({ label: '更新连接密码', action: trigger => connectionEditor(source, trigger, 'credential') });
    if (source.connectionState === '已启用' && allowed('sources.pause')) items.push({ label: source.paused ? '恢复定时采集' : '暂停定时采集', action: trigger => lifecycle(source, source.paused ? '恢复定时采集' : '暂停定时采集', trigger) });
    if (source.connectionState === '已启用' && allowed('sources.disable')) items.push({ label: '停用数据源', danger: true, separator: true, action: trigger => lifecycle(source, '停用数据源', trigger) });
    if (source.connectionState === '已停用' && allowed('sources.enable')) items.push({ label: '复核并恢复', action: trigger => restoreSource(source, trigger) });
    if (source.connectionState === '已停用' && allowed('sources.retire')) items.push({ label: '退役数据源', danger: true, separator: true, action: trigger => lifecycle(source, '退役数据源', trigger) });
    return ui.moreActions(items, 'ds-more-' + source.id);
  }
  function sourceDetail(source, trigger) {
    const count = demo.objects(source.id).length;
    showDetail(source.name, trigger, body => {
      body.append(actions(badge(source.connectionState), badge(source.health), badge(source.collection)));
      if (source.health === '网络异常') body.append(note('网络连接超时。请检查连接路径，再重新测试；已有治理和知识继续保留。', true));
      if (source.paused) body.append(note('定时采集已暂停；已有查询能力不因此停止，结构可能陈旧。'));
      fields(body, [['数据源编号', source.id], ['用途说明', source.config.purpose || '未填写', true], ['技术联系人', person(source.config.technical)], ['业务联系人', person(source.config.business)], ['数据库', source.config.database || '未配置'], ...(source.config.type === 'PostgreSQL' ? [['Schema 范围', source.config.schemas.join('、') || '尚未选择']] : []), ['数据库类型', source.config.type], ['数据库版本', source.config.version || '未识别'], ['连接地址', source.config.host ? source.config.host + ':' + source.config.port : '未配置'], ['只读账号', source.config.account], ['数据库密码', source.credentialSet ? '已设置' : '未设置']]);
      heading(body, '采集与知识');
      const summary = fields(body, [['已发现对象', count ? count + ' 个（当前连接可见范围）' : '尚未采集'], ['最近采集', source.lastCollected || '暂无'], ['最近完整采集', source.lastFull || '暂无'], ['定时采集', source.paused ? '已暂停' : '每 ' + source.config.syncHours + ' 小时'], ['完整确认对象', source.governed + ' 个'], ['部分确认对象', source.partialGoverned + ' 个'], ['已发布知识', source.knowledge + ' 项']]);
      const forbidden = ui.pair('已禁止使用的知识', source.forbidden + ' 项');
      if (source.forbidden) {
        const view = ui.button(source.forbidden + ' 项', trigger => forbiddenKnowledgeDetail(source, trigger), 'cell-title');
        view.setAttribute('aria-label', '查看' + source.name + '已禁止使用的知识，共 ' + source.forbidden + ' 项');
        forbidden.querySelector('dd').replaceChildren(view);
      }
      summary.append(forbidden);
      heading(body, '运行限制');
      fields(body, [['最大连接数', source.config.connections], ['采集并发', source.config.concurrency], ['重试上限', source.config.retries], ['采集任务时限', source.config.taskTimeout + ' 秒'], ['查询时限', source.config.queryTimeout + ' 秒'], ['结果行数上限', source.config.rowLimit], ['采集时段', source.config.window]]);
      const paths = ui.el('div', 'ds-detail-links');
      for (const [key, label] of [['catalog', '查看结构目录'], ['collections', '查看采集与变化'], ['business-notes', '查看业务说明']]) paths.append(ui.button(label, () => navigate(key, source)));
      body.append(paths);
      heading(body, '配置记录');
      fields(body, [['有效配置', '修订 ' + source.revision]]);
      source.history.slice(0, 5).forEach(item => body.append(ui.el('p', 'ds-history', item.at + ' · ' + item.action + ' · 配置修订 ' + item.revision + (item.reason ? ' · ' + item.reason : ''))));
    }, (bar, node) => {
      if (source.connectionState !== '已退役' && allowed('sources.edit')) bar.append(ui.button('编辑连接', () => { node.close(); connectionEditor(source, trigger); }, 'admin-button is-primary'));
    });
  }
  function forbiddenKnowledgeDetail(source, trigger) {
    showDetail('已禁止使用的知识 · ' + source.name, trigger, body => {
      source.forbiddenKnowledge.forEach(item => {
        const section = ui.el('section', 'ds-forbidden-item');
        heading(section, item.name);
        fields(section, [['知识内容', item.content], ['依赖对象', item.dependency], ['禁止原因', item.reason]]);
        body.append(section);
      });
    });
  }
  function lifecycle(source, action, trigger) {
    const impacts = action === '停用数据源' ? '确认后停止新查询及结果交付，并取消在途采集。已经交付的内容无法收回。' : action === '退役数据源' ? '退役后该连接不再使用。保留治理、已发布知识及历史审计，不删除源数据库。' : action === '暂停定时采集' ? '暂停后不再产生新的定时采集；已有查询不因此停止，目录可能陈旧。' : '按已保存的周期和采集时段恢复定时采集。';
    const schedule = action.includes('定时'), nextState = schedule ? action === '暂停定时采集' ? '已暂停' : '每 ' + source.config.syncHours + ' 小时' : action === '停用数据源' ? '已停用' : '已退役';
    ui.openForm({ title: action + ' · ' + source.name, trigger, note: impacts, value: { reason: '' }, fields: [{ key: 'reason', label: '操作原因', type: 'textarea', required: action !== '恢复定时采集', full: true }], danger: ['停用数据源', '退役数据源'].includes(action), saveLabel: action.slice(0, 2), previewTitle: '核对操作与影响', preview: data => [{ label: schedule ? '定时采集' : '配置状态', before: schedule ? source.paused ? '已暂停' : '每 ' + source.config.syncHours + ' 小时' : source.connectionState, after: nextState }, ...(!schedule ? [{ label: '治理与知识', before: source.governed + ' 个治理对象、' + source.knowledge + ' 项已发布知识', after: action === '退役数据源' ? '保留内容与历史，停止该连接使用' : impacts }, { label: '采集任务', before: demo.activeTask(source.id)?.id || '无进行中任务', after: action === '停用数据源' ? '停止并保留已完成范围' : '保留历史任务' }] : []), ...(data.reason.trim() ? [{ label: '操作原因', before: '—', after: data.reason.trim() }] : [])], save: data => demo.lifecycle(source, action, data.reason.trim()), success: source.name + '已' + action.slice(0, 2) });
  }
  function restoreSource(source, trigger) {
    const node = ui.modal('复核并恢复 · ' + source.name, trigger), host = ui.el('div'); node.append(host);
    PrototypeUI.mount(host, { data: () => ({ checked: false, running: false, error: '', reason: '' }), methods: {
      async check() { this.running = true; this.error = ''; await new Promise(resolve => setTimeout(resolve, 500)); if (!node.isConnected) return; const results = demo.testResults(demo.configuration(source), source); this.checked = demo.canEnable(results); this.error = this.checked ? '' : '连接检查未通过，请先编辑连接并重新测试。'; this.running = false; },
      close() { node.close(); },
      save() { if (!this.checked || !this.reason.trim()) { this.error = !this.checked ? '请先检查连接与读取能力。' : '请填写恢复原因。'; this.$nextTick(() => node.querySelector('textarea')?.focus()); return; } if (!allowed('sources.enable')) return; const message = demo.lifecycle(source, '恢复数据源', this.reason.trim()); if (message) { this.error = message; return; } node.close(); refresh(); ui.notify('数据源已恢复；现有知识禁止状态保留'); }
    }, template: '<div class="dialog-body"><p class="form-note">恢复前核对连接与读取能力；结构变化及知识禁止仍需另行复核。</p><el-button :loading="running" @click="check">检查连接与读取能力</el-button><div v-if="checked" class="ds-check-summary"><p>连接与读取能力：通过</p><p>保留 ' + source.forbidden + ' 项知识禁止状态</p></div><p v-if="error" id="ds-restore-error" class="form-error" role="alert">{{error}}</p><el-form label-position="top"><el-form-item label="恢复原因" required><el-input v-model="reason" aria-label="恢复原因" :aria-invalid="Boolean(error && !reason.trim())" :aria-describedby="error?\'ds-restore-error\':undefined" @input="error=\'\'" type="textarea" :maxlength="300"></el-input></el-form-item></el-form></div><footer class="dialog-footer"><el-button @click="close">取消</el-button><el-button type="primary" :disabled="!checked" @click="save">确认恢复</el-button></footer>' });
    node.showModal();
  }
  function connectionEditor(source, trigger, mode = 'edit') {
    const credential = mode === 'credential', testOnly = mode === 'test';
    const node = ui.modal((credential ? '更新连接密码' : testOnly ? '测试连接' : source ? '编辑连接' : '新增数据源') + (source ? ' · ' + source.name : ''), trigger); node.classList.add('ds-connection-dialog');
    const host = ui.el('div'); node.append(host); const uid = 'ds-connection-' + (++detailSequence);
    const initial = source ? demo.configuration(source) : { ...demo.configDefaults, name: '', purpose: '' };
    const initialRevision = source?.revision;
    const groups = [
      { name: '基本信息', fields: [['name', '数据源名称'], ['technical', '技术联系人', 'people'], ['business', '业务联系人', 'people'], ['purpose', '用途说明', 'textarea']] },
      { name: '数据库连接', fields: [['type', '数据库类型', ['MySQL', 'PostgreSQL']], ['version', '数据库版本', 'detected'], ['host', '连接地址'], ['port', '端口', 'number'], ['database', '数据库'], ['schemas', 'Schema 范围', 'schemas'], ['account', '数据库只读账号'], ['password', '数据库密码', 'password']] }
    ];
    const limits = [['connections', '最大连接数', 1, 20], ['concurrency', '采集并发', 1, 8], ['retries', '重试上限', 0, 5], ['taskTimeout', '采集任务时限（秒）', 30, 3600], ['queryTimeout', '查询时限（秒）', 1, 300], ['rowLimit', '结果行数上限', 1, 10000], ['syncHours', '采集周期（小时）', 1, 720]];
    PrototypeUI.mount(host, {
      data: () => ({ model: { ...initial, schemas: [...initial.schemas], password: '' }, groups: credential ? [{ name: '连接密码', fields: [['password', '数据库密码', 'password']] }] : testOnly ? groups.filter(group => group.name === '数据库连接') : groups, limits, people: PeopleDemo.users.filter(user => user.status === '正常').map(user => ({ value: user.id, label: person(user.id) })), busy: false, results: [], review: '', error: '', advanced: [], tested: '', schemaOptions: [], probeAttempted: false, scopeChosen: Boolean(initial.schemas.length), credential, testOnly, uid, readonly: testOnly,
        rules: Object.fromEntries(['name', 'type', 'host', 'port', 'database', 'account', ...((!source?.credentialSet || credential) ? ['password'] : [])].map(key => [key, [{ required: true, message: '请填写或选择此项', trigger: 'blur' }, ...(['name', 'host', 'database', 'account', 'password'].includes(key) ? [{ validator: (_, value, done) => done(String(value || '').trim() ? undefined : new Error('此项不能为空')), trigger: 'blur' }] : [])]])) }),
      computed: {
        unchanged() { return source && !demo.configChanged(this.model, source, Boolean(this.model.password)); },
        passed() { return demo.canEnable(this.results) && this.tested === this.fingerprint(); },
        connectionKey() { return this.fingerprint(); },
        scopeRules() { return [{ validator: (_, value, done) => { const message = demo.scopeError(this.model, this.schemaOptions); done(message ? new Error(message) : undefined); }, trigger: 'change' }]; },
        reviewItems() {
          const labels = Object.fromEntries([...groups.flatMap(group => group.fields), ...limits, ['window', '采集时段']].map(field => [field[0], field[1]]));
          const keys = credential ? ['account'] : source ? Object.keys(initial).filter(key => JSON.stringify(this.model[key]) !== JSON.stringify(initial[key])) : ['name', 'technical', 'business', 'type', 'version', 'host', 'port', 'database', 'schemas', 'account', 'syncHours', 'window'];
          const display = (key, value) => ['technical', 'business'].includes(key) ? person(value) : key === 'schemas' ? value.join('、') || '尚未选择' : key === 'version' ? value || '未识别' : String(value ?? '') || '未填写';
          return keys.filter(key => key !== 'schemas' || this.model.type === 'PostgreSQL').map(key => ({ label: labels[key] || key, before: source ? display(key, initial[key]) : '—', after: display(key, this.model[key]) })).concat(this.model.password ? [{ label: '数据库密码', before: source?.credentialSet ? '已设置' : '未设置', after: '已提供新密码，不回显' }] : []);
        }
      },
      watch: {
        // 识别结果与 Schema 选择不参与连接指纹，避免返回版本或缩小范围后循环重测。
        connectionKey() { this.error = ''; this.results = []; this.tested = ''; this.review = ''; this.model.version = ''; this.schemaOptions = []; this.probeAttempted = false; },
        'model.type'(value) { if (!source || source.connectionState === '草稿') { this.model.port = value === 'PostgreSQL' ? 5432 : 3306; this.model.schemas = []; this.scopeChosen = false; this.$nextTick(() => this.$refs.form.clearValidate('schemas')); } }
      },
      methods: {
        fingerprint() { return JSON.stringify(Object.fromEntries(['type', 'host', 'port', 'database', 'account', 'password'].map(key => [key, this.model[key]]))); },
        clearSecret() { this.model.password = ''; },
        changeSchemas() { this.scopeChosen = true; this.review = ''; this.$nextTick(() => { this.$refs.form.clearValidate('schemas'); this.fieldChanged('schemas', true); }); },
        fieldChanged(key, valid) { this.$nextTick(() => { const item = this.$refs.form.getField(key); for (const input of item?.$el.querySelectorAll('input,textarea') || []) { input.setAttribute('aria-invalid', String(!valid)); if (!valid) input.setAttribute('aria-describedby', uid + '-' + key + '-error'); else input.removeAttribute('aria-describedby'); } }); },
        valid(includeScope = false) { const keys = [...Object.keys(this.rules), ...(includeScope && !credential && this.model.type === 'PostgreSQL' ? ['schemas'] : [])]; return new Promise(resolve => this.$refs.form.validateField(keys, ok => { if (!ok) this.$nextTick(() => node.querySelector('.is-error input,.is-error textarea')?.focus()); resolve(ok); })); },
        fail(message) { this.error = message; this.$nextTick(() => node.querySelector('.form-error')?.focus()); },
        async test() {
          if (!await this.valid()) return; const invalid = demo.error(this.model, source?.id); if (invalid) { this.fail(invalid); return; }
          this.busy = true; this.review = ''; this.results = []; this.model.version = ''; this.schemaOptions = []; this.probeAttempted = true; this.$refs.form.clearValidate('schemas'); const fingerprint = this.fingerprint(), priorSchemas = [...this.model.schemas];
          await new Promise(resolve => setTimeout(resolve, 650)); if (!node.isConnected) return;
          this.busy = false; if (fingerprint !== this.fingerprint()) { this.fail('连接参数已变化，请重新测试。'); return; }
          const response = demo.inspectConnection(this.model, source);
          this.model.version = response.version; this.schemaOptions = response.schemas; this.results = response.results; this.tested = fingerprint;
          if (this.model.type === 'PostgreSQL' && !credential && !testOnly) this.model.schemas = this.scopeChosen ? priorSchemas.filter(name => response.schemas.includes(name)) : [...response.schemas];
          this.$nextTick(() => { const target = node.querySelector('.ds-test-results'); target?.focus(); target?.scrollIntoView({ block: 'nearest' }); });
        },
        async prepare(action) {
          if (this.unchanged && (action === 'draft' || source.connectionState !== '草稿')) { this.fail('配置未修改。'); return; }
          // 草稿允许连接资料暂缺；启用或更新有效配置仍须完整校验和分项测试。
          if (action === 'draft') {
            if (!this.model.name.trim()) { this.$refs.form.validateField('name', () => {}); this.$nextTick(() => node.querySelector('input[aria-label="数据源名称"]')?.focus()); return; }
            const invalidName = demo.nameError(this.model.name, source?.id); if (invalidName) { this.fail(invalidName); return; }
            this.review = action; this.$nextTick(() => node.querySelector('.ds-review')?.focus()); return;
          }
          if (!await this.valid(true)) return;
          const invalid = demo.error(this.model, source?.id); if (invalid) { this.fail(invalid); return; }
          if (action === 'enable' && !this.passed) { this.fail('请先完成当前参数的分项连接测试。'); return; }
          // 地址及数据库身份变化必须新建源，避免把旧知识和授权套入另一套系统。
          if (source?.connectionState !== '草稿' && source && (this.model.host !== source.config.host || this.model.port !== source.config.port || this.model.database !== source.config.database || this.model.type !== source.config.type)) { this.fail('连接身份发生变化，请新建数据源；当前原型不提供数据库迁移流程。'); return; }
          this.review = action; this.$nextTick(() => node.querySelector('.ds-review')?.focus());
        },
        commit() {
          if (!allowed(source ? 'sources.edit' : 'sources.create')) return;
          if (source && (source.revision !== initialRevision || source.connectionState === '已退役')) { this.fail('数据源配置或状态已变化，请关闭后重新编辑。'); return; }
          if (source && this.model.type === 'PostgreSQL' && demo.activeTask(source.id) && JSON.stringify([...this.model.schemas].sort()) !== JSON.stringify([...source.config.schemas].sort())) { this.fail('采集中不能修改 Schema 范围，请等待任务结束。'); return; }
          const action = this.review, activating = action === 'enable' && (!source || source.connectionState === '草稿');
          if (action === 'enable' && !this.passed) { this.fail('测试结果已失效，请重新测试。'); return; }
          if (credential && !this.model.password.trim()) return;
          const data = Object.fromEntries(Object.keys(initial).map(key => [key, this.model[key]]));
          const target = demo.saveConfig(data, source, activating, Boolean(this.model.password));
          // 先记录办理结果，再清除秘密和重绘；提示不能读取已被重置的表单状态。
          this.clearSecret(); node.close();
          if (!source) Object.assign(getQuery('sources'), { keyword: '', owner: '', type: '', status: 'all', page: 1 });
          demo.selected = target.id;
          if (activating) navigate('catalog', target); else refresh();
          ui.notify(credential ? '连接密码已更新' : activating ? '连接已启用' : action === 'enable' ? '连接配置已保存' : '连接草稿已保存');
        }, close() { this.clearSecret(); node.close(); }
      },
      mounted() { node.addEventListener('close', () => this.clearSecret(), { once: true }); },
      template: `<el-form ref="form" :model="model" :rules="rules" label-position="top" @validate="fieldChanged" @submit.prevent="test">
        <div class="dialog-body"><p v-if="error" class="form-error" role="alert" tabindex="-1">{{error}}</p>
        <div v-show="!review"><section v-for="group in groups" :key="group.name" class="ds-form-section"><h3 v-if="!credential">{{group.name}}</h3><div class="form-grid">
          <el-form-item v-for="field in group.fields.filter(item=>item[2]!=='schemas'||model.type==='PostgreSQL')" :key="field[0]" :label="field[1]" :prop="field[0]" :rules="field[2]==='schemas'?scopeRules:undefined" :required="readonly?false:field[2]==='schemas' && schemaOptions.length ? true : undefined" :class="{'full-width':field[2]==='textarea' || field[0]==='name' || field[2]==='schemas'}">
            <div v-if="field[2]==='detected'" class="ds-detected-version" role="status" aria-label="数据库版本">{{model.version || (busy?'识别中':probeAttempted?'未识别':'测试后识别')}}</div>
            <div v-else-if="readonly" class="readonly-value">{{field[0]==='password'?(hasCredential?'已设置':'未设置'):field[0]==='schemas'?(model.schemas.join('、') || '尚未选择'):model[field[0]]}}</div>
            <el-select v-else-if="field[2]==='schemas'" v-model="model.schemas" aria-label="Schema 范围" multiple collapse-tags collapse-tags-tooltip :max-collapse-tags="3" :append-to="popupTarget" :disabled="readonly || busy || !schemaOptions.length || scopeLocked" :placeholder="schemaOptions.length?'选择 Schema':'测试连接后选择'" @change="changeSchemas"><el-option v-for="name in schemaOptions" :key="name" :value="name" :label="name"></el-option></el-select>
            <el-select v-else-if="Array.isArray(field[2]) || field[2]==='people'" v-model="model[field[0]]" :aria-label="field[1]" :disabled="readonly || busy || (sourceLocked && ['type'].includes(field[0]))" :clearable="field[2]==='people'" :append-to="popupTarget" filterable><el-option v-for="item in field[2]==='people'?people:field[2].map(value=>({value,label:value}))" :key="item.value" :value="item.value" :label="item.label"></el-option></el-select>
            <el-input-number v-else-if="field[2]==='number'" v-model="model[field[0]]" :aria-label="field[1]" :disabled="readonly || busy || (sourceLocked && field[0]==='port')" :min="1" :max="field[0]==='port'?65535:120" controls-position="right"></el-input-number>
            <el-input v-else v-model="model[field[0]]" :aria-label="field[1]" :type="field[2] || 'text'" :disabled="readonly || busy || (sourceLocked && ['host','database'].includes(field[0]))" :maxlength="field[2]==='textarea'?1000:160" :rows="3" :autocomplete="field[2]==='password'?'new-password':'off'"></el-input>
            <span v-if="field[2]==='password' && hasCredential && !credential && !readonly" class="field-help">留空沿用已有密码。</span>
            <span v-if="field[2]==='password' && credential" class="field-help">填写该账号在数据库中使用的密码。</span>
            <span v-if="sourceLocked && field[0]==='database' && !readonly" class="field-help">变更数据库身份请新建数据源。</span>
            <span v-if="field[2]==='schemas' && scopeLocked" class="field-help">采集进行中，结束后再调整范围。</span>
            <template #error="{error:message}"><div class="el-form-item__error" :id="uid+'-'+field[0]+'-error'" role="alert">{{message}}</div></template>
          </el-form-item></div></section>
          <el-collapse v-if="!credential && !testOnly" v-model="advanced" class="ds-advanced"><el-collapse-item title="运行与定时采集" name="limits"><div class="form-grid"><el-form-item v-for="field in limits" :key="field[0]" :label="field[1]"><el-input-number v-model="model[field[0]]" :aria-label="field[1]" :min="field[2]" :max="field[3]" :disabled="readonly || busy" controls-position="right"></el-input-number></el-form-item><el-form-item label="采集时段"><el-input v-model="model.window" aria-label="采集时段" :disabled="readonly || busy" :maxlength="40"></el-input></el-form-item></div></el-collapse-item></el-collapse>
          <section v-if="results.length" class="ds-test-results" tabindex="-1" aria-label="分项连接测试结果"><h3>分项连接测试</h3><div v-for="item in results" :key="item.label" class="ds-test-row"><span>{{item.label}}</span><el-tag :type="item.state==='失败'?'danger':item.state==='通过'?'success':'warning'">{{item.state}}</el-tag><span>{{item.detail}}</span></div></section>
        </div><section v-if="review" class="ds-review change-preview is-summary" tabindex="-1"><h3>核对{{credential?'密码更新':review==='enable'?(sourceLocked?'连接变更':'连接启用'):'连接草稿'}} · {{model.name}}</h3><table><thead><tr><th>内容</th><th>当前</th><th>保存后</th></tr></thead><tbody><tr v-for="item in reviewItems" :key="item.label"><th>{{item.label}}</th><td>{{item.before}}</td><td>{{item.after}}</td></tr></tbody></table><p class="form-note" style="margin-top:16px">{{review==='enable'?(sourceLocked?'当前参数测试通过；保存配置修订':'当前配置通过测试并启用'):'保留为草稿，尚未启用'}}</p></section></div>
        <footer class="dialog-footer"><el-button @click="close">{{testOnly?'关闭':'取消'}}</el-button><template v-if="review"><el-button @click="review=''">返回修改</el-button><el-button type="primary" @click="commit">确认{{credential?'更新':review==='enable'?(sourceLocked?'保存':'启用'):'保存草稿'}}</el-button></template><template v-else><el-button v-if="!testOnly && !credential && !sourceLocked" :disabled="busy || unchanged" @click="prepare('draft')">保存草稿</el-button><el-button :loading="busy" native-type="submit">{{results.length?'重新测试':'测试连接'}}</el-button><el-button v-if="!testOnly" type="primary" :disabled="!passed || busy || (unchanged && sourceLocked)" @click="prepare('enable')">{{credential?'核对密码更新':sourceLocked?'核对并保存':'核对并启用'}}</el-button></template></footer>
      </el-form>`,
      setup() { return { popupTarget: node, hasCredential: Boolean(source?.credentialSet), sourceLocked: Boolean(source && source.connectionState !== '草稿'), scopeLocked: Boolean(source && demo.activeTask(source.id)) }; }
    }); node.showModal();
  }
  function renderCatalog() {
    const source = demo.find(demo.selected), query = getQuery('catalog:' + source.id);
    const rows = queryRows(demo.objects(source.id), query, table => ({ status: demo.catalogStates(table), fields: { keyword: [objectName(table), table.label], type: table.type, owner: table.governance } }));
    const tools = filters(query, [{ key: 'keyword', label: '对象名称或解释', placeholder: '对象名或解释' }, { key: 'type', label: '对象类型', options: ['普通表', '视图', '物化视图', '分区表', '系统表', '临时表', '外部表'].map(value => ({ value, label: value })) }, { key: 'owner', label: '治理状态', options: ['已确认', '部分确认', '待治理', '待复核'].map(value => ({ value, label: value })) }], ['已采集', '属性缺失', '新发现', '结构变化', '访问丢失待核实', '已确认移除']);
    const panel = tablePanel(rows, query, [{ key: 'name', label: '对象 / 解释', width: 235 }, { key: 'type', label: '对象类型', width: 110 }, { key: 'governance', label: '治理状态', width: 110 }, { key: 'state', label: '目录状态', width: 170 }, { key: 'version', label: '采集快照', width: 115 }, { key: 'at', label: '采集时间', width: 170 }, { key: 'actions', label: '操作', width: 192, fixed: 'right' }], table => [stack(ui.button(objectName(table), trigger => objectDetail(table, trigger), 'cell-title', 'ds-object-' + table.id), table.label), stack(table.type, table.parent ? '父表 ' + table.parent : ''), badge(table.governance), catalogStatus(table), ui.textCell(table.version), ui.textCell(table.collected), actions(link('查看结构', trigger => objectDetail(table, trigger)), catalogNextAction(table))], demo.objects(source.id).length ? '没有匹配的对象' : source.connectionState === '已启用' ? '尚无结构目录，发起首次采集后显示' : '尚无结构目录，请先测试并启用数据源');
    main.replaceChildren(ui.pageHeading('元数据目录'), context(source, allowed('collections.collect') && source.connectionState === '已启用' && ui.button('发起采集', trigger => collect(source, trigger), 'admin-button is-primary')), tools, panel);
  }
  function objectDetail(table, trigger) {
    const source = demo.find(table.source), ordinaryView = table.type === '视图';
    showDetail(objectName(table) + ' · ' + table.label, trigger, (body, node) => {
      // 只展示已确认重建且仍待核对的提示，不凭同名或普通结构修改推断重建。
      if (table.rebuildReviewPending) body.append(note('检测到同名' + (table.type.includes('视图') ? '视图' : '表') + '重建，需重新核对对象身份及关联知识。'));
      const pending = demo.pendingVerification(table).length;
      if (pending) body.append(note('该对象有 ' + pending + ' 项变化待核实，请先核对结构变化。'));
      // 注释属于当前表或视图，保留来源原文，不限定语言。
      fields(body, [['数据源', source.name], ['对象路径', table.path], ['对象类型', table.type], ['目录状态', demo.catalogStates(table).join('、')], ['治理状态', table.governance], [table.type.includes('视图') ? '视图注释' : '表注释', table.commentState === '已读取' ? table.comment || '无注释' : table.commentState, true]]);
      const blocked = source.forbiddenKnowledge.filter(item => item.dependency === objectName(table) || item.dependency.startsWith(objectName(table) + '.'));
      if (blocked.length) body.append(note('关联知识已禁止使用：' + blocked.map(item => item.name + '（' + item.dependency + '）').join('、') + '。', true));
      heading(body, '字段');
      const region = ui.el('div', 'ds-inner-table'); region.append(RecordTable.render({ caption: objectName(table) + ' 字段', columns: [{ key: 'name', label: '字段', width: 160 }, { key: 'meaning', label: '业务解释', width: 240 }, { key: 'type', label: '类型', width: 140 }, { key: 'null', label: '可空', width: 70 }, ...(ordinaryView ? [] : [{ key: 'default', label: '默认值 / 生成', width: 150 }]), { key: 'comment', label: '字段注释', width: 160 }], items: table.fields, row: field => [ui.textCell(field.name), ui.el('span', 'ds-field-meaning', field.meaning), ui.textCell(field.type), field.nullable, ...(ordinaryView ? [] : [ui.textCell(field.default)]), ui.el('span', 'ds-field-meaning', field.comment || '无注释')] }));
      const fieldScroll = region.querySelector('.rt-scroll'); fieldScroll.tabIndex = 0; fieldScroll.setAttribute('aria-label', objectName(table) + ' 的字段，可横向滚动');
      body.append(region);
      heading(body, ordinaryView ? '业务关系' : '键、关系与索引');
      fields(body, [...(ordinaryView ? [] : [['主键', table.fields[0].name], ['唯一约束', '已读取：未声明其他唯一约束'], ['来源外键', table.name === 'sale_item' ? 'product_id → product.id（数据库声明）' : '已读取：未声明外键']]), ['业务关系', '需在治理中核对；来源外键不等于已确认业务关系'], ...(ordinaryView ? [] : [['索引读取', table.indexState], ['索引定义', table.indexState === '无权限' ? '未知：账号无索引目录读取权限' : 'PRIMARY (' + table.fields[0].name + ')']])]);
      if (table.indexState === '无权限') body.append(note('请数据库负责人确认索引目录读取权限。字段等已取得属性保留，不将缺项解释为没有索引。'));
      if (table.type === '视图') { heading(body, '视图来源'); fields(body, [['依赖', 'sale_order、sale_item'], ['定义读取', '已读取；仅同源对象'], ['刷新信息', '普通视图，按查询实时读取']]); }
      const related = demo.changesFor(table);
      if (related.length && allowed('collections.view')) { heading(body, '相关变化'); related.forEach(change => body.append(link(demo.changeTarget(change) + ' · ' + impacts.progress(change), () => { node.close(); changeDetail(change, trigger); }))); }
      sourceFacts(body, [['来源对象标识', table.identity], ['对象世代', '第 ' + table.generation + ' 代', '用于区分同名对象删除后重新创建的情况。例如 sale_order 删除重建后，从第 1 代变为第 2 代。表、视图等都适用；重新采集、改字段或修改视图定义不会自动换代。'], ['采集快照', table.version], ['采集时间', table.collected], ['来源只读能力', table.readState], ['注释读取状态', table.commentState]], node);
    }, (bar, node) => {
      if (activeChanges(table).length && allowed('collections.view')) bar.append(ui.button('查看变化', () => { node.close(); openChanges(table.source, objectName(table), trigger); }, 'admin-button is-primary'));
      else if (allowed('governance.view') && !demo.pendingVerification(table).length) bar.append(ui.button('进入治理', () => { node.close(); navigateGovernance(source, objectName(table), trigger); }, 'admin-button is-primary'));
    });
  }
  function navigateGovernance(source, object, trigger) { rememberPosition(trigger); GovernancePage.open(source.id, object, { from: 'catalog' }); }
  function collect(source, trigger, parent) {
    if (!allowed(parent ? 'collections.retry' : 'collections.collect')) return;
    // 元数据读取保留一次范围与账号确认，不再重复比较相同的变更前后内容。
    ui.openForm({ title: parent ? '重试采集 · ' + source.name : '发起采集 · ' + source.name, trigger, value: { scope: parent?.enumeration && parent.failures.length ? parent.failures.join('、') : demo.scopeLabel(source) + ' · 全部可见对象', account: source.config.account }, fields: [{ key: 'scope', label: '采集范围', readonly: true, full: true }, { key: 'account', label: '采集账号', readonly: true, full: true }], note: parent && !parent.enumeration ? '原任务枚举不完整，需要重新枚举连接范围；原结果保留。' : parent ? '仅重新读取失败对象；原任务结论保留，不将子范围重试视为整源完整采集。' : '', saveLabel: '开始采集', validate: () => demo.activeTask(source.id) ? '该数据源已有进行中的任务，请先查看当前任务。' : source.connectionState !== '已启用' ? '请先启用数据源。' : '', save: () => {
      const result = demo.startTask(source, parent); if (result.error) return result.error;
      // 计时器只推进这一笔示例任务，取消或停用后不会继续写成功结果。
      const timer = setInterval(() => { demo.advanceTask(result.task); if (!['排队', '进行中'].includes(result.task.state)) clearInterval(timer); window.dispatchEvent(new CustomEvent('datasource-task-updated', { detail: result.task.id })); }, 3000);
      collectionTab = 'tasks'; demo.selected = source.id; Object.assign(getQuery('tasks:' + source.id), { keyword: '', owner: '', status: 'all', fromDate: '', toDate: '', page: 1 });
    }, onSaved: () => { navigate('collections', source); }, success: '采集任务已创建' });
  }
  function renderCollections() {
    const source = demo.find(demo.selected), isTasks = collectionTab === 'tasks', query = getQuery(collectionTab + ':' + source.id + (!isTasks && changeScope ? ':' + changeScope : ''));
    const base = (isTasks ? demo.tasks : demo.changes).filter(item => item.source === source.id && (isTasks || !changeScope || item.object === changeScope)).sort((a, b) => b.at.localeCompare(a.at) || b.id.localeCompare(a.id));
    const rows = queryRows(base, query, item => ({ status: isTasks ? item.state : impacts.progress(item), fields: isTasks ? { keyword: item.id, owner: item.trigger } : { keyword: [demo.changeTarget(item), item.attribute], owner: item.kind, type: item.fact } }));
    const choices = isTasks ? ['手动', '定时', '重试'] : ['新增字段', '类型变化', '删除字段', '访问丢失', '修改注释'];
    const tools = filters(query, [{ key: 'keyword', label: isTasks ? '采集编号' : '变化对象', placeholder: isTasks ? '采集编号' : '对象名、字段名或变化项' }, { key: 'owner', label: isTasks ? '发起方式' : '变化类型', options: choices.map(value => ({ value, label: value })) }, ...(!isTasks ? [{ key: 'type', label: '变化事实', options: ['待核实', '已确认', '已排除'].map(value => ({ value, label: value })) }] : [])], isTasks ? ['排队', '进行中', '完整成功', '部分成功', '失败', '已取消'] : ['待核实', '待治理', '处理中', '待知识更新', '已完成'], true);
    const tabbar = tabs([['tasks', '采集记录'], ['changes', '结构变化']], collectionTab, value => navigate('collections/' + source.id + '/' + value, source));
    if (!isTasks && changeScope) {
      const scope = ui.el('div', 'ds-change-scope'), identity = ui.el('div', 'ds-actions');
      const pending = base.filter(change => change.fact === '待核实').length;
      const factStatus = badge(pending ? '待核实 ' + pending + ' 项' : base.length ? '事实已核实' : '暂无变化');
      if (pending) factStatus.classList.add('is-pending');
      identity.append(ui.el('strong', '', changeScope), factStatus);
      const controls = actions(ui.button('返回元数据目录', () => navigate('catalog', source)), ui.button('查看全源变化', () => openChanges(source.id)));
      const next = base.find(change => change.fact === '已确认' && impacts.progress(change) !== '已完成');
      if (!pending && next && allowed('governance.view')) controls.append(ui.button('进入治理', trigger => openImpactPage(impacts.forChange(next), trigger), 'admin-button is-primary', 'ds-scope-governance'));
      scope.append(identity, controls); tabbar.append(scope);
      ui.setTitle('结构变化 · ' + changeScope, '元数据目录', '#catalog');
    }
    const columns = isTasks ? [{ key: 'id', label: '采集编号', width: 152 }, { key: 'trigger', label: '发起方式', width: 105 }, { key: 'scope', label: '采集范围', width: 190 }, { key: 'at', label: '开始时间', width: 166 }, { key: 'state', label: '任务状态', width: 112 }, { key: 'coverage', label: '处理 / 已发现', width: 150 }, { key: 'actions', label: '操作', width: 156, fixed: 'right' }] : [{ key: 'object', label: '变化对象', width: 210 }, { key: 'kind', label: '变化类型', width: 112 }, { key: 'impact', label: '影响及使用控制', width: 210 }, { key: 'fact', label: '变化事实', width: 110 }, { key: 'at', label: '发现时间', width: 166 }, { key: 'progress', label: '处理进度', width: 120 }, { key: 'actions', label: '操作', width: 118, fixed: 'right' }];
    const verificationDone = !isTasks && changeScope && query.type === '待核实' && base.length && base.every(change => change.fact !== '待核实');
    const emptyMessage = verificationDone ? '该对象的变化已全部核实' : base.length ? '没有匹配的记录' : isTasks ? '尚无采集记录' : '尚未发现结构变化';
    const panel = tablePanel(rows, query, columns, item => isTasks ? [ui.button(item.id, trigger => taskDetail(item, trigger), 'cell-title', 'ds-task-' + item.id), stack(item.trigger, item.actor), ui.textCell(item.scope), ui.textCell(item.at), badge(item.state), stack(item.processed + ' / ' + item.discovered, item.enumeration ? '总数 ' + item.total + ' · 失败 ' + item.failed : '总数未知 · 枚举不完整'), actions(link('详情', trigger => taskDetail(item, trigger), 'ds-task-detail-' + item.id), ['排队', '进行中'].includes(item.state) && allowed('collections.cancel') ? link('取消', trigger => cancelTask(item, trigger)) : ['部分成功', '失败'].includes(item.state) && allowed('collections.retry') && source.connectionState === '已启用' ? link('重试', trigger => collect(source, trigger, item)) : null)] : [stack(ui.button(demo.changeTarget(item), trigger => changeDetail(item, trigger), 'cell-title', 'ds-change-' + item.id), item.attribute), ui.textCell(item.kind), stack(ui.textCell(item.impact), item.control), stack(badge(item.fact), item.factSource), ui.textCell(item.at), badge(impacts.progress(item)), link(item.fact === '待核实' && allowed('collections.review') ? '核实变化' : '查看详情', trigger => changeDetail(item, trigger))], emptyMessage, tabbar);
    main.replaceChildren(ui.pageHeading('采集记录与结构变化'), context(source, allowed('collections.collect') && source.connectionState === '已启用' && ui.button(demo.activeTask(source.id) ? '查看进行中任务' : '发起采集', trigger => demo.activeTask(source.id) ? taskDetail(demo.activeTask(source.id), trigger) : collect(source, trigger), 'admin-button is-primary')), tools, panel);
  }
  function taskDetail(task, trigger) {
    const node = showDetail('采集任务 · ' + task.id, trigger, body => {
      body.dataset.taskBody = task.id; taskBody(task, body);
    }, (bar, dialog) => taskActions(task, bar, dialog, trigger));
    node.taskTrigger = trigger;
    return node;
  }
  // 首次打开与实时结束共用操作判断，避免部分成功后丢失重试入口。
  function taskActions(task, bar, dialog, trigger) {
    const source = demo.find(task.source), hadFocus = bar.contains(document.activeElement);
    bar.replaceChildren(ui.button('关闭', () => { dialog.close(); refresh(); }));
    if (['排队', '进行中'].includes(task.state) && allowed('collections.cancel')) bar.append(ui.button('取消采集', () => { dialog.close(); cancelTask(task, trigger); }, 'admin-button is-danger'));
    else if (['部分成功', '失败', '已取消'].includes(task.state) && allowed('collections.retry') && source.connectionState === '已启用') bar.append(ui.button(task.enumeration && task.failures.length ? '重试失败对象' : '重新采集', () => { dialog.close(); collect(source, trigger, task); }, 'admin-button is-primary'));
    if (hadFocus) bar.querySelector('button')?.focus({ preventScroll: true });
  }
  function taskBody(task, body) {
    body.replaceChildren(actions(badge(task.state)));
    fields(body, [['数据源', demo.find(task.source).name], ['任务编号', task.id], ['配置修订', task.configRevision ? '修订 ' + task.configRevision : '未记录'], ['采集范围', task.scope], ['采集账号', task.account], ['发起方式', task.trigger + ' · ' + task.actor], ['开始时间', task.at], ['结束时间', task.ended || '尚未结束'], ['枚举覆盖', task.enumeration ? '枚举完整' : '不完整，总数未知'], ['已发现 / 已处理', task.discovered + ' / ' + task.processed], ['失败对象', task.failed], [task.state === '完整成功' ? '采集快照' : '候选采集快照', task.version], ['数据库任务状态', task.databaseState], ...(task.parent ? [['原任务', task.parent]] : [])]);
    if (task.enumeration) { const host = ui.el('div', 'prototype-ui ds-progress'); PrototypeUI.mount(host, { data: () => ({ percentage: task.total ? Math.round(task.processed / task.total * 100) : 0, state: task.state }), template: '<el-progress :percentage="percentage" :status="state===\'完整成功\'?\'success\':state===\'部分成功\'?\'warning\':state===\'失败\'?\'exception\':undefined"></el-progress>' }); body.append(host); }
    if (task.reason) { heading(body, task.state === '已取消' ? '取消原因' : '采集说明'); body.append(note(task.reason, task.state === '失败')); }
    if (task.failures.length) { heading(body, '失败对象'); const list = ui.el('div', 'ds-failure-list'); task.failures.forEach(name => list.append(stack(name, task.enumeration ? '索引：无权限；字段、类型等可靠属性已保留' : '未完整取得结构，请重新枚举确认'))); body.append(list); }
    heading(body, '结果与来源');
    const changes = demo.changes.filter(change => change.source === task.source && change.taskId === task.id);
    fields(body, [['发现变化', changes.length + ' 项']]);
    if (allowed('collections.view')) changes.forEach(change => body.append(link(demo.changeTarget(change) + ' · ' + change.attribute, trigger => changeDetail(change, trigger))));
    body.append(ui.el('p', 'ds-prose', task.retryOnly ? '本次仅覆盖失败对象，不代表整源完整采集；原任务及最近完整采集记录保留。' : task.enumeration ? '本次结果独立保留采集快照与采集时间，人工确认的业务说明不会被同步覆盖。' : '当前仅记录已发现及已完成范围，未发现对象不作为删除证据。'));
  }
  function cancelTask(task, trigger) {
    ui.openForm({ title: '取消采集 · ' + task.id, trigger, note: '停止采集并保留已完成范围；未完成部分不作为对象删除依据。', value: { reason: '' }, fields: [{ key: 'reason', label: '取消原因', type: 'textarea', required: true, full: true }], danger: true, saveLabel: '取消采集', preview: data => [{ label: '任务', before: task.state, after: '已取消' }, { label: '已完成范围', before: task.processed + ' 个对象', after: '保留已取得属性' }, { label: '取消原因', before: '—', after: data.reason.trim() }], save: data => demo.cancelTask(task, data.reason) ? undefined : '任务已结束，请回看最新结果。', success: '采集已取消' });
  }
  function changeDetail(change, trigger) {
    const source = demo.find(change.source);
    const table = demo.objects(change.source).find(table => objectName(table) === change.object);
    const pending = table ? demo.pendingVerification(table).length : 0;
    showDetail('结构变化 · ' + demo.changeTarget(change), trigger, (body, dialog) => {
      if (pending && change.fact !== '待核实') body.append(note('该对象仍有 ' + pending + ' 项变化待核实，核实后可进入治理。'));
      fields(body, [['数据源', source.name], ['变化编号', change.id], ['变化对象', demo.changeTarget(change), true], ['变化项', change.attribute], ['变化类型', change.kind], ['发现时间', change.at], ['变化事实', change.fact + ' · ' + change.factSource], ['处理进度', impacts.progress(change)]]);
      sourceFacts(body, [['来源采集', change.taskId || '未关联'], ['对比前快照', change.beforeSnapshot || '未记录'], ['本次快照', change.afterSnapshot || '未记录']], dialog);
      heading(body, '前后差异'); fields(body, [['原值', change.before], ['新值', change.after], ['来源证据', change.evidence, true], ['受影响内容', change.impact, true], ['知识使用控制', change.control]]);
      if (change.compatible === false) body.append(note('相关知识已禁止使用。记录核实结论不解除禁止；修复、验证及必要发布后由有权人员显式解除。', true));
      if (change.compatible === null) body.append(note('目录可见性变化不等于对象删除；排除结构变化后仍需恢复来源访问。'));
      if (change.verifications.length) { heading(body, '核实记录'); processEvents(body, change.verifications); }
      if (change.result) { heading(body, '已有核实依据'); body.append(ui.el('p', 'ds-prose', change.result)); }
      const item = impacts.forChange(change); if (item) impactSummary(body, item, dialog);
    }, (bar, node) => {
      const sourceTask = demo.tasks.find(task => task.id === change.taskId && task.source === change.source);
      if (sourceTask) bar.append(ui.button('查看来源采集', trigger => taskDetail(sourceTask, trigger)));
      if (change.fact === '待核实' && allowed('collections.review')) bar.append(ui.button('核实变化', () => { node.close(); verifyEditor(change, trigger); }, 'admin-button is-primary'));
      const item = impacts.forChange(change);
      if (item && pending && allowed('collections.view')) bar.append(ui.button('查看待核实变化', () => { node.close(); openChanges(change.source, change.object, trigger, true); }, 'admin-button is-primary'));
      else if (item && !pending && allowed('governance.view')) bar.append(ui.button('进入治理', () => { node.close(); openImpactPage(item, trigger); }, 'admin-button is-primary'));
    });
  }
  function verifyEditor(change, trigger) {
    const revision = change.verifications.length;
    ui.openForm({ title: '核实变化 · ' + demo.changeTarget(change), trigger, value: { decision: '', reason: '' },
      fields: [{ key: 'decision', label: '核实结论', options: ['确认变化', '排除结构变化'].map(value => ({ value, label: value })), required: true, full: true }, { key: 'reason', label: '核实依据', type: 'textarea', required: true, full: true }],
      saveLabel: '保存核实结论',
      save: data => impacts.verify(change, data, ui.currentUser, revision), success: '核实结论已记录', onSaved: () => {
        // 保存只推进事实核实，保留列表任务；进入治理由明确入口触发。
        rememberPosition(trigger); refresh();
      } });
  }
  function processEvents(body, events) {
    events.forEach(entry => { const row = ui.el('div', 'ds-note-use'); row.append(ui.el('strong', '', entry.action), ui.el('small', 'cell-sub', entry.actor + ' · ' + entry.at)); if (entry.reason) row.append(ui.el('p', 'ds-prose', entry.reason)); body.append(row); });
  }
  function impactSummary(body, item, dialog, title = '治理处理') {
    heading(body, title);
    fields(body, [['处理编号', item.id], ['处理进度', item.state], ['处理人', item.owner ? person(item.owner) : '尚未处理'], ['知识更新', item.knowledge], ...(item.governanceResult ? [['治理结果', item.governanceResult, true]] : [])]);
    if (title === '变更影响' && allowed('governance.view')) body.append(link('打开治理', () => { dialog.close(); openImpactPage(item); }));
  }
  function openImpactPage(item, trigger) {
    rememberPosition(trigger);
    GovernancePage.openImpact(item, { from: location.hash.startsWith('#catalog') ? 'catalog' : location.hash.startsWith('#collections') ? 'collections' : location.hash.startsWith('#business-notes') ? 'business-notes' : '' });
  }
  function renderNotes() {
    const source = demo.find(demo.selected), query = getQuery('business-notes:' + source.id);
    const base = demo.notes.filter(item => item.source === source.id);
    const rows = queryRows(base, query, item => ({ status: item.status, fields: { keyword: item.title, owner: person(item.author) } })).sort((a, b) => b.updated.localeCompare(a.updated) || b.id.localeCompare(a.id));
    const tools = filters(query, [{ key: 'keyword', label: '资料标题' }, { key: 'owner', label: '提交人', placeholder: '提交人姓名或登录名' }], ['有效', '已撤下'], false, allowed('business-notes.submit') && source.connectionState !== '已退役' && ui.button('新增业务说明', trigger => noteEditor(source, null, trigger), 'admin-button is-primary'));
    const panel = tablePanel(rows, query, [{ key: 'title', label: '资料标题', width: 240 }, { key: 'author', label: '提交人', width: 145 }, { key: 'at', label: '更新时间', width: 166 }, { key: 'state', label: '状态', width: 80 }, { key: 'actions', label: '操作', width: 216, fixed: 'right' }], item => {
      const title = ui.button(item.title, trigger => noteDetail(item, trigger), 'cell-title', 'ds-note-' + item.id), author = PeopleDemo.users.find(user => user.id === item.author);
      title.title = item.title; title.setAttribute('aria-label', '查看业务说明：' + item.title);
      return [stack(title, '第 ' + item.revision + ' 版' + (item.files.length ? ' · ' + item.files.length + ' 个附件' : '')), stack(ui.textCell(author?.name), author?.login), ui.textCell(item.updated), badge(item.status), noteActions(source, item)];
    }, base.length ? '没有匹配的业务资料' : '尚无业务说明，可先描述系统用途和已知业务规则');
    panel.classList.add('ds-notes-panel');
    main.replaceChildren(ui.pageHeading('企业业务说明'), context(source), tools, panel);
  }
  function noteUsage(item) {
    const uses = demo.noteUsesFor(item);
    if (uses.some(use => use.kind === '治理任务' && ['排队', '进行中'].includes(use.state))) return '治理任务进行中';
    if (!uses.length) return '未使用';
    return uses.every(use => use.revision !== item.revision) ? '历史修订已引用' : '已有治理引用';
  }
  function noteMenu(item, closeDetail, popupTarget) {
    if (!allowed('business-notes.delete')) return;
    const reason = demo.noteDeleteBlock(item);
    return ui.moreActions([{ label: '删除', disabled: Boolean(reason), reason, description: reason, danger: true, action: trigger => { const origin = closeDetail?.() || trigger; noteLifecycle(item, '删除', origin); } }], 'ds-note-more-' + item.id, undefined, popupTarget || document.body, popupTarget ? 'top-end' : 'bottom-end');
  }
  function noteActions(source, item) {
    const node = actions();
    if (item.status === '有效') {
      if (allowed('business-notes.replace')) node.append(link('编辑', trigger => noteEditor(source, item, trigger), 'ds-note-edit-' + item.id));
      if (allowed('business-notes.withdraw')) node.append(link('撤下', trigger => noteLifecycle(item, '撤下', trigger), 'ds-note-state-' + item.id));
    } else if (allowed('business-notes.restore')) node.append(link('恢复', trigger => noteLifecycle(item, '恢复', trigger), 'ds-note-state-' + item.id));
    const menu = noteMenu(item); if (menu) node.append(menu);
    return node;
  }
  function noteLifecycle(item, action, trigger) {
    const token = demo.noteToken(item), blocked = action === '删除' && demo.noteDeleteBlock(item);
    if (blocked) { ui.notify(blocked); return; }
    const row = main.querySelector('[data-record-id="' + item.id + '"]'), neighbor = (row?.nextElementSibling || row?.previousElementSibling)?.dataset.recordId;
    // 标题明确目标；删除仅一次后果确认，撤下原因沿用共用核对表单。
    ui.openForm({ title: action + '资料 · ' + item.title, trigger, value: {},
      fields: action === '撤下' ? [{ key: 'reason', label: '撤下原因', type: 'textarea', required: true, full: true }] : [],
      note: action === '删除' ? '将删除这条说明的正文、附件和全部修订，删除后无法恢复。' : action === '撤下' ? '停止后续治理取用，保留历史依据；已有治理引用需复核。' : '恢复后可供后续治理任务取用。',
      danger: action !== '恢复', saveLabel: action === '撤下' ? '撤下' : '确认' + action,
      ...(action === '撤下' ? { previewTitle: '核对撤下资料', previewLabel: '核对撤下', preview: data => [{ label: '资料状态', before: '有效', after: '已撤下' }, { label: '引用情况', before: noteUsage(item), after: demo.noteUsesFor(item).length ? '保留来源，相关治理内容需复核' : '停止后续取用' }, { label: '撤下原因', before: '—', after: data.reason.trim() }] } : {}),
      save: data => demo.changeNote(item, action, data, ui.currentUser, token), success: '资料已' + action,
      onSaved: () => requestAnimationFrame(() => {
        const target = main.querySelector('[data-focus="ds-note-' + item.id + '"]') || (neighbor && main.querySelector('[data-focus="ds-note-' + neighbor + '"]')) || main.querySelector('.ds-notes-panel .cell-title') || main.querySelector('.ds-query-card input');
        target?.focus({ preventScroll: true });
      })
    });
  }
  function noteDetail(item, trigger) {
    const source = demo.find(item.source);
    showDetail(item.title, trigger, (body, dialog) => {
      body.append(badge(item.status));
      if (item.status === '已撤下') body.append(note('已停止后续治理取用，已有引用保留并需复核。'));
      fields(body, [['数据源', source.name], ['提交人', person(item.author)], ['更新时间', item.updated], ['当前修订', '第 ' + item.revision + ' 版']]);
      heading(body, '业务说明'); body.append(ui.el('p', 'ds-prose', item.content || '未填写文字说明'));
      if (item.files.length) { heading(body, '附件'); item.files.forEach(file => body.append(ui.el('p', 'ds-file-name', file.name + ' · ' + Math.ceil(file.size / 1024) + ' KB'))); }
      const uses = demo.noteUsesFor(item);
      // 引用与变更影响仅在有记录时展示，不作为资料生效流程。
      if (uses.length) {
        heading(body, '引用记录');
        uses.forEach(use => { const row = ui.el('div', 'ds-note-use'); row.append(ui.el('strong', '', use.name), ui.el('small', 'cell-sub', use.kind + ' · 第 ' + use.revision + ' 版 · ' + use.state)); body.append(row); });
        body.append(ui.el('p', 'ds-note-block', demo.noteDeleteBlock(item)));
      }
      impacts.forNote(item).forEach(record => impactSummary(body, record, dialog, '变更影响'));
      if (item.history.length) { heading(body, '修订记录'); item.history.forEach(entry => { const history = ui.el('details', 'ds-note-history'), summary = ui.el('summary', '', '第 ' + entry.revision + ' 版 · ' + entry.updated); history.append(summary, ui.el('p', 'ds-prose', entry.title), ui.el('p', 'ds-prose', entry.content || '无文字说明')); entry.files.forEach(file => history.append(ui.el('p', 'ds-file-name', file.name))); body.append(history); }); }
      const events = demo.noteEvents.filter(event => event.noteId === item.id);
      if (events.length) { heading(body, '操作记录'); events.forEach(event => { const row = ui.el('div', 'ds-note-use'); row.append(ui.el('strong', '', event.action + ' · ' + event.actor), ui.el('small', 'cell-sub', event.at)); if (event.reason) row.append(ui.el('p', 'ds-prose', event.reason)); body.append(row); }); }
    }, (bar, node) => {
      const menu = noteMenu(item, () => { node.close(); return trigger; }, node); if (menu) bar.prepend(menu);
      if (item.status === '有效' && allowed('business-notes.withdraw')) bar.append(ui.button('撤下资料', () => { node.close(); noteLifecycle(item, '撤下', trigger); }, 'admin-button is-danger'));
      if (item.status === '已撤下' && allowed('business-notes.restore')) bar.append(ui.button('恢复资料', () => { node.close(); noteLifecycle(item, '恢复', trigger); }, 'admin-button is-primary'));
      if (item.status === '有效' && allowed('business-notes.replace')) bar.append(ui.button('编辑业务说明', () => { node.close(); noteEditor(source, item, trigger); }, 'admin-button is-primary'));
    });
  }
  function noteEditor(source, item, trigger) {
    const node = ui.modal((item ? '编辑业务说明' : '新增业务说明') + ' · ' + source.name, trigger); node.classList.add('ds-note-dialog'); const host = ui.el('div'); node.append(host);
    const uid = 'ds-note-form-' + (++detailSequence), initialToken = item && demo.noteToken(item);
    PrototypeUI.mount(host, { data: () => ({ model: { title: item?.title || '', content: item?.content || '', files: (item?.files || []).map((file, i) => ({ ...file, uid: i + 1 })) }, error: '', uid, rules: { title: [{ required: true, whitespace: true, message: '请填写资料标题', trigger: 'blur' }] } }), computed: {
      unchanged() { return item && this.model.title.trim() === item.title && this.model.content.trim() === item.content && !this.model.files.some(file => file.raw) && JSON.stringify(this.model.files.map(file => ({ name: file.name, size: file.size || 0 }))) === JSON.stringify(item.files); }
    }, methods: {
      fileChanged(file, list) { if ((file.size || 0) > 20 * 1024 * 1024 || !/\.(pdf|docx|xlsx|csv|txt|md)$/i.test(file.name)) { this.model.files = list.filter(entry => entry.uid !== file.uid); this.error = '请选择 20 MB 以内的 PDF、DOCX、XLSX、CSV、TXT 或 Markdown 文件。'; return; } this.model.files = list; this.error = ''; },
      validField(key, valid) { this.$nextTick(() => { const input = this.$refs.form.getField(key)?.$el.querySelector('input'); if (input) { input.setAttribute('aria-invalid', String(!valid)); if (!valid) input.setAttribute('aria-describedby', uid + '-title-error'); else input.removeAttribute('aria-describedby'); } }); },
      // 新增和编辑在表单校验通过后直接保存，不增加核对步骤。
      submit() { if (this.unchanged) return; this.$refs.form.validate(valid => { if (!valid) { this.$nextTick(() => node.querySelector('.is-error input')?.focus()); return; } if (!this.model.content.trim() && !this.model.files.length) { this.error = '请填写业务说明或选择附件。'; this.$nextTick(() => node.querySelector('textarea')?.focus()); return; } this.error = ''; this.save(); }); },
      save() {
        if (!allowed(item ? 'business-notes.replace' : 'business-notes.submit')) { this.error = '当前账号没有保存资料的权限。'; return; }
        if (this.unchanged) return;
        if (item && (!demo.notes.includes(item) || demo.noteToken(item) !== initialToken || item.status !== '有效')) { this.error = '资料已变化，请关闭后重新打开。'; return; }
        // 引用继续绑定旧修订；正文或附件变化关联工作台待办，保留直达治理条目的入口。
        const result = impacts.saveNote(item, { title: this.model.title, content: this.model.content, files: this.model.files.map(file => ({ name: file.name, size: file.size || file.raw?.size || 0 })), replacedFile: this.model.files.some(file => file.raw), source: source.id }, ui.currentUser, initialToken);
        if (result.error) { this.error = result.error; return; }
        const saved = result.note;
        if (!item) Object.assign(getQuery('business-notes:' + source.id), { keyword: '', owner: '', status: 'all', page: 1 });
        node.close(); refresh(); ui.notify(result.impact ? '新修订已保存，已加入治理工作台变化待办' : item ? '业务说明已保存新修订' : '业务说明已保存');
        requestAnimationFrame(() => (main.querySelector('[data-focus="ds-note-' + saved.id + '"]') || main.querySelector('.ds-notes-panel .cell-title') || main.querySelector('.ds-query-card input'))?.focus({ preventScroll: true }));
      }
    }, template: `<el-form ref="form" :model="model" :rules="rules" label-position="top" @validate="validField" @submit.prevent="submit"><div class="dialog-body"><p v-if="error" class="form-error" role="alert">{{error}}</p><el-form-item label="资料标题" prop="title"><el-input v-model="model.title" aria-label="资料标题" :maxlength="120"></el-input><template #error="{error:message}"><div class="el-form-item__error" :id="uid+'-title-error'" role="alert">{{message}}</div></template></el-form-item><el-form-item label="业务说明"><el-input v-model="model.content" aria-label="业务说明" type="textarea" :rows="7" :maxlength="5000" show-word-limit></el-input></el-form-item><el-form-item label="附件"><el-upload v-model:file-list="model.files" :auto-upload="false" :limit="5" :on-change="fileChanged" :on-exceed="()=>error='最多选择 5 个附件。'" accept=".pdf,.docx,.xlsx,.csv,.txt,.md" multiple><el-button>选择附件</el-button><template #tip><div class="el-upload__tip">PDF、DOCX、XLSX、CSV、TXT、Markdown；单个文件不超过 20 MB</div></template></el-upload></el-form-item></div><footer class="dialog-footer"><el-button @click="$el.closest('dialog').close()">取消</el-button><el-button type="primary" :disabled="unchanged" native-type="submit">保存</el-button></footer></el-form>` }); node.showModal();
  }
  function render(target, controls, options = {}) {
    main = target; ui = controls; section = options.section || 'sources';
    if (options.sourceId && demo.find(options.sourceId)) demo.selected = options.sourceId;
    else if (positions.has(location.hash)) demo.selected = positions.get(location.hash).source;
    if (section === 'collections') {
      if (['tasks', 'changes'].includes(options.target)) collectionTab = options.target;
      changeScope = collectionTab === 'changes' && options.target === 'changes' ? decodeURIComponent(location.hash.split('/')[3] || '') : '';
    }
    if (!allowed(section + '.view')) { main.replaceChildren(ui.pageHeading('不可访问'), note('当前账号没有此页面的查看权限。', true)); return; }
    if (section === 'sources') renderSources(options.tabs);
    else if (section === 'catalog') renderCatalog();
    else if (section === 'collections') renderCollections();
    else renderNotes();
    restorePosition();
  }
  window.addEventListener('datasource-task-updated', event => {
    const body = document.querySelector('[data-task-body="' + event.detail + '"]'), task = demo.tasks.find(item => item.id === event.detail);
    // 跨页只更新只读状态单元格，保留筛选输入、分页和焦点。
    if (task && allowed('sources.view')) document.querySelectorAll('[data-source-collection]').forEach(cell => { if (cell.dataset.sourceCollection === task.source) cell.replaceWith(collectionCell(demo.find(task.source))); });
    if (body && task) { taskBody(task, body); if (!['排队', '进行中'].includes(task.state)) { const dialog = body.closest('dialog'); taskActions(task, dialog.querySelector('.dialog-footer'), dialog, dialog.taskTrigger); } }
    else if (location.hash === '#collections' && !document.querySelector('dialog[open]') && collectionTab === 'tasks') { const focused = document.activeElement; if (focused === document.body || main.contains(focused) && !focused.closest('.rl-filters,.rl-pager')) refresh(); }
  });
  function viewImpactSource(target, controls, item, trigger) {
    main = target; ui = controls;
    const structural = item.kind === '结构变化', source = (structural ? demo.changes : demo.notes).find(source => source.id === item.originId && source.source === item.source);
    if (!allowed(structural ? 'collections.view' : 'business-notes.view')) return ui.notify('当前账号没有查看该来源的权限。');
    if (!source) return ui.notify('来源记录当前不可用。');
    (structural ? changeDetail : noteDetail)(source, trigger);
  }
  function openChanges(sourceId, object = '', trigger, pendingOnly = false) {
    if (ui && !allowed('collections.view')) return ui.notify('当前账号没有查看结构变化的权限。');
    rememberPosition(trigger);
    demo.selected = sourceId; collectionTab = 'changes';
    if (pendingOnly) Object.assign(getQuery('changes:' + sourceId + (object ? ':' + object : '')), { keyword: '', owner: '', type: '待核实', status: 'all', fromDate: '', toDate: '', page: 1 });
    navigate('collections/' + encodeURIComponent(sourceId) + '/changes' + (object ? '/' + encodeURIComponent(object) : ''));
  }
  window.DataSourcesPage = { render, connectionEditor, viewImpactSource, openChanges };
})();
