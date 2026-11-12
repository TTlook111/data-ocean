/* 复用后台查询、表格、抽屉和 Element Plus；只补齐数据源任务，不实现数据库或治理服务。 */
(() => {
  'use strict';
  const demo = DataSourceDemo;
  const queries = new Map();
  let main, ui, section = 'sources', collectionTab = 'tasks', detailSequence = 0;
  const refresh = () => window.dispatchEvent(new Event('hashchange'));
  const allowed = ability => SourceApprovalDemo.hasAbility(ui.currentUser, ability);
  const person = id => { const user = PeopleDemo.users.find(item => item.id === id); return user ? user.name + '（' + user.login + '）' : '未指定'; };
  const objectName = table => (table.schema ? table.schema + '.' : '') + table.name;
  const getQuery = key => { if (!queries.has(key)) queries.set(key, { keyword: '', owner: '', type: '', status: 'all', fromDate: '', toDate: '', page: 1, pageSize: 10 }); return queries.get(key); };
  function badge(value) {
    const tag = ui.el('span', 'status-tag', value);
    if (['失败', '网络异常', '认证异常', '已禁止使用'].includes(value)) tag.classList.add('ds-status-danger');
    else if (['部分成功', '未测试', '未验证', '待治理', '待复核', '新发现', '结构变化', '属性缺失', '访问丢失', '访问丢失待核实', '进行中', '排队', '未知', '未采集', '待重新采集', '不支持', '无权限'].includes(value)) tag.classList.add('is-pending');
    else if (['草稿', '已停用', '已退役', '已取消', '已撤下'].includes(value)) tag.classList.add('is-disabled');
    return tag;
  }
  function stack(primary, secondary) { const node = ui.el('div'); node.append(typeof primary === 'string' ? ui.textCell(primary) : primary); if (secondary) node.append(ui.el('small', 'cell-sub', secondary)); return node; }
  function actions(...nodes) { const node = ui.el('div', 'ds-actions'); node.append(...nodes.filter(Boolean)); return node; }
  function link(label, action, key) { return ui.button(label, action, 'do-action is-primary', key); }
  function navigate(key, source, filter) {
    if (source) demo.selected = source.id;
    if (filter) Object.assign(getQuery(key + ':' + demo.selected), filter, { page: 1 });
    if (location.hash === '#' + key) refresh(); else location.hash = key;
  }
  function note(text, danger = false) { const node = ui.el('p', 'ds-notice' + (danger ? ' is-danger' : ''), text); node.setAttribute('role', danger ? 'alert' : 'status'); return node; }
  function queryRows(rows, query, values) {
    return rows.filter(row => (query.status === 'all' || values(row).status === query.status) && AdminQuery.matchesFields(values(row).fields, query) && (!query.fromDate || (row.at || row.updated || '').slice(0, 10) >= query.fromDate) && (!query.toDate || (row.at || row.updated || '').slice(0, 10) <= query.toDate));
  }
  function filters(query, fields, statuses, dates = false, primary) {
    const statusLabel = section === 'catalog' ? '采集状态' : section === 'sources' ? '配置状态' : section === 'collections' ? collectionTab === 'tasks' ? '任务状态' : '复核状态' : '资料状态';
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
    PrototypeUI.mount(select, { data: () => ({ selected: source.id, options: demo.sources.map(item => ({ id: item.id, name: item.name })) }), methods: { changed(value) { demo.selected = value; refresh(); } }, template: '<label class="ds-context-label" id="ds-current-source">当前数据源</label><el-select v-model="selected" aria-labelledby="ds-current-source" filterable @change="changed"><el-option v-for="item in options" :key="item.id" :value="item.id" :label="item.name"></el-option></el-select>' });
    host.append(select, actions(badge(source.connectionState), badge(source.health), source.collection === '待重新采集' && badge(source.collection)), primary || ui.button('数据源详情', trigger => sourceDetail(source, trigger)));
    return host;
  }
  function showDetail(title, trigger, build, footer) {
    const node = ui.modal(title, trigger, true); node.classList.add('ds-detail-dialog');
    const body = ui.el('div', 'dialog-body'); build(body, node);
    const bar = ui.el('footer', 'dialog-footer'); bar.append(ui.button('关闭', () => node.close())); footer?.(bar, node);
    node.append(body, bar); node.showModal(); return node;
  }
  function fields(body, entries) { const list = ui.el('dl', 'detail-fields ds-fields'); entries.forEach(([label, value]) => list.append(ui.pair(label, String(value ?? '—')))); body.append(list); }
  // 调查所需的来源信息保留在折叠区，不抢占字段和业务解释的首屏位置。
  function sourceFacts(body, entries) {
    const host = ui.el('div', 'prototype-ui ds-source-facts');
    PrototypeUI.mount(host, { data: () => ({ entries }), template: '<el-collapse><el-collapse-item title="来源与采集信息" name="facts"><dl class="detail-fields ds-fields"><div v-for="entry in entries" :key="entry[0]"><dt>{{entry[0]}}</dt><dd>{{entry[1]}}</dd></div></dl></el-collapse-item></el-collapse>' });
    body.append(host);
  }
  function heading(body, title) { body.append(ui.el('h3', 'ds-section-heading', title)); }
  function renderSources(parentTabs) {
    const query = getQuery('sources');
    const rows = queryRows(demo.sources, query, source => ({ status: source.connectionState, fields: { keyword: source.name, owner: person(source.config.technical), type: source.config.type } })).sort((a, b) => b.updated.localeCompare(a.updated) || b.id.localeCompare(a.id));
    const tools = filters(query, [{ key: 'keyword', label: '数据源名称' }, { key: 'type', label: '数据库类型', options: ['MySQL', 'PostgreSQL'].map(value => ({ value, label: value })) }, { key: 'owner', label: '技术联系人', placeholder: '技术联系人姓名或登录名' }], ['草稿', '已启用', '已停用', '已退役'], false, allowed('sources.create') && ui.button('新增数据源', trigger => connectionEditor(null, trigger), 'admin-button is-primary', 'ds-create'));
    const panel = tablePanel(rows, query, [
      { key: 'name', label: '数据源', width: 210 }, { key: 'type', label: '数据库类型', width: 130 }, { key: 'owner', label: '技术联系人', width: 140 }, { key: 'state', label: '配置 / 连接', width: 138 }, { key: 'collection', label: '结构采集', width: 160 }, { key: 'knowledge', label: '治理 / 知识', width: 170 }, { key: 'actions', label: '操作', width: 192, fixed: 'right' }
    ], source => [stack(ui.button(source.name, trigger => sourceDetail(source, trigger), 'cell-title', 'ds-source-' + source.id), source.config.database || '未配置连接'), stack(source.config.type, source.config.version || '版本未识别'), stack(PeopleDemo.users.find(user => user.id === source.config.technical)?.name || '未指定', PeopleDemo.users.find(user => user.id === source.config.technical)?.login), stack(badge(source.connectionState), source.health), stack(badge(source.collection), source.lastCollected || '尚无采集记录'), stack(source.governed + ' 个对象已确认', source.knowledge + ' 项已发布 · ' + source.forbidden + ' 项禁止使用'), actions(allowed('sources.edit') && source.connectionState !== '已退役' && link('编辑连接', trigger => connectionEditor(source, trigger), 'ds-edit-' + source.id), sourceMenu(source))], '没有匹配的数据源', parentTabs);
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
      fields(body, [['数据源编号', source.id], ['用途说明', source.config.purpose || '未填写'], ['技术联系人', person(source.config.technical)], ['业务联系人', person(source.config.business)], ['数据库', source.config.database || '未配置'], ...(source.config.type === 'PostgreSQL' ? [['Schema 范围', source.config.schemas.join('、') || '尚未选择']] : []), ['数据库类型', source.config.type], ['数据库版本', source.config.version || '未识别'], ['连接地址', source.config.host ? source.config.host + ':' + source.config.port : '未配置'], ['只读账号', source.config.account], ['数据库密码', source.credentialSet ? '已设置' : '未设置']]);
      heading(body, '采集与知识');
      fields(body, [['已发现对象', count ? count + ' 个（当前连接可见范围）' : '尚未采集'], ['最近采集', source.lastCollected || '暂无'], ['最近完整采集', source.lastFull || '暂无'], ['定时采集', source.paused ? '已暂停' : '每 ' + source.config.syncHours + ' 小时'], ['已确认治理', source.governed + ' 个对象'], ['已发布知识', source.knowledge + ' 项'], ['知识禁止', source.forbidden + ' 项'], ['有效配置', '修订 ' + source.revision]]);
      heading(body, '运行限制');
      fields(body, [['最大连接数', source.config.connections], ['采集并发', source.config.concurrency], ['重试上限', source.config.retries], ['采集任务时限', source.config.taskTimeout + ' 秒'], ['查询时限', source.config.queryTimeout + ' 秒'], ['结果行数上限', source.config.rowLimit], ['采集时段', source.config.window]]);
      const paths = ui.el('div', 'ds-detail-links');
      for (const [key, label] of [['catalog', '查看结构目录'], ['collections', '查看采集与变化'], ['business-notes', '查看业务说明']]) paths.append(ui.button(label, () => navigate(key, source)));
      body.append(paths);
      if (source.history.length) { heading(body, '配置记录'); source.history.slice(0, 5).forEach(item => body.append(ui.el('p', 'ds-history', item.at + ' · ' + item.action + (item.reason ? ' · ' + item.reason : '')))); }
    }, (bar, node) => {
      if (source.connectionState !== '已退役' && allowed('sources.edit')) bar.append(ui.button('编辑连接', () => { node.close(); connectionEditor(source, trigger); }, 'admin-button is-primary'));
    });
  }
  function lifecycle(source, action, trigger) {
    const impacts = action === '停用数据源' ? '确认后停止新查询及结果交付，并取消在途采集。已经交付的内容无法收回。' : action === '退役数据源' ? '退役后该连接不再使用。保留治理、已发布知识及历史审计，不删除源数据库。' : action === '暂停定时采集' ? '暂停后不再产生新的定时采集；已有查询不因此停止，目录可能陈旧。' : '按已保存的周期和采集时段恢复定时采集。';
    const schedule = action.includes('定时'), nextState = schedule ? action === '暂停定时采集' ? '已暂停' : '每 ' + source.config.syncHours + ' 小时' : action === '停用数据源' ? '已停用' : '已退役';
    ui.openForm({ title: action + ' · ' + source.name, trigger, note: impacts, value: { reason: '' }, fields: [{ key: 'reason', label: '操作原因', type: 'textarea', required: true, full: true }], danger: ['停用数据源', '退役数据源'].includes(action), saveLabel: action.slice(0, 2), previewTitle: '核对操作与影响', preview: () => [{ label: schedule ? '定时采集' : '配置状态', before: schedule ? source.paused ? '已暂停' : '每 ' + source.config.syncHours + ' 小时' : source.connectionState, after: nextState }, ...(!schedule ? [{ label: '治理与知识', before: source.governed + ' 个治理对象、' + source.knowledge + ' 项已发布知识', after: action === '退役数据源' ? '保留内容与历史，停止该连接使用' : impacts }, { label: '采集任务', before: demo.activeTask(source.id)?.id || '无进行中任务', after: action === '停用数据源' ? '停止并保留已完成范围' : '保留历史任务' }] : [])], save: data => demo.lifecycle(source, action, data.reason), success: source.name + '已' + action.slice(0, 2) });
  }
  function restoreSource(source, trigger) {
    const node = ui.modal('复核并恢复 · ' + source.name, trigger), host = ui.el('div'); node.append(host);
    PrototypeUI.mount(host, { data: () => ({ checked: false, running: false, error: '', reason: '' }), methods: {
      async check() { this.running = true; this.error = ''; await new Promise(resolve => setTimeout(resolve, 500)); if (!node.isConnected) return; const results = demo.testResults(demo.configuration(source), source); this.checked = demo.canEnable(results); this.error = this.checked ? '' : '连接检查未通过，请先编辑连接并重新测试。'; this.running = false; },
      close() { node.close(); },
      save() { if (!this.checked || !this.reason.trim()) { this.error = !this.checked ? '请先检查连接与结构。' : '请填写恢复原因。'; this.$nextTick(() => node.querySelector('textarea')?.focus()); return; } if (!allowed('sources.enable')) return; demo.lifecycle(source, '恢复数据源', this.reason.trim()); node.close(); refresh(); ui.notify('数据源已恢复；现有知识禁止状态保留'); }
    }, template: '<div class="dialog-body"><p class="form-note">恢复前核对连接、权限及结构变化。知识禁止状态需在治理模块另行复核解除。</p><el-button :loading="running" @click="check">检查连接与结构</el-button><div v-if="checked" class="ds-check-summary"><p>连接与读取能力：通过</p><p>与最近采集结构相比：未发现新增变化</p><p>复用有效知识检索索引，保留 ' + source.forbidden + ' 项知识禁止状态</p></div><p v-if="error" id="ds-restore-error" class="form-error" role="alert">{{error}}</p><el-form label-position="top"><el-form-item label="恢复原因" required><el-input v-model="reason" aria-label="恢复原因" :aria-invalid="Boolean(error && !reason.trim())" :aria-describedby="error?\'ds-restore-error\':undefined" @input="error=\'\'" type="textarea" :maxlength="300"></el-input></el-form-item></el-form></div><footer class="dialog-footer"><el-button @click="close">取消</el-button><el-button type="primary" :disabled="!checked" @click="save">确认恢复</el-button></footer>' });
    node.showModal();
  }
  function connectionEditor(source, trigger, mode = 'edit') {
    const credential = mode === 'credential', testOnly = mode === 'test';
    const node = ui.modal((credential ? '更新连接密码' : testOnly ? '测试连接' : source ? '编辑连接' : '新增数据源') + (source ? ' · ' + source.name : ''), trigger); node.classList.add('ds-connection-dialog');
    const host = ui.el('div'); node.append(host); const uid = 'ds-connection-' + (++detailSequence);
    const initial = source ? demo.configuration(source) : { ...demo.configDefaults, name: '', purpose: '' };
    const groups = [
      { name: '基本信息', fields: [['name', '数据源名称'], ['technical', '技术联系人', 'people'], ['business', '业务联系人', 'people'], ['purpose', '用途说明', 'textarea']] },
      { name: '数据库连接', fields: [['type', '数据库类型', ['MySQL', 'PostgreSQL']], ['version', '数据库版本', 'detected'], ['host', '连接地址'], ['port', '端口', 'number'], ['database', '数据库'], ['schemas', 'Schema 范围', 'schemas'], ['account', '数据库只读账号'], ['password', '数据库密码', 'password']] }
    ];
    const limits = [['connections', '最大连接数', 1, 20], ['concurrency', '采集并发', 1, 8], ['retries', '重试上限', 0, 5], ['taskTimeout', '采集任务时限（秒）', 30, 3600], ['queryTimeout', '查询时限（秒）', 1, 300], ['rowLimit', '结果行数上限', 1, 10000], ['syncHours', '采集周期（小时）', 1, 720]];
    PrototypeUI.mount(host, {
      data: () => ({ model: { ...initial, schemas: [...initial.schemas], password: '' }, groups: credential ? [{ name: '连接密码', fields: [['password', '数据库密码', 'password']] }] : testOnly ? groups.filter(group => group.name === '数据库连接') : groups, limits, people: PeopleDemo.users.filter(user => user.status === '正常').map(user => ({ value: user.id, label: person(user.id) })), busy: false, results: [], review: '', error: '', advanced: [], tested: '', schemaOptions: [], probeAttempted: false, scopeChosen: Boolean(initial.schemas.length), credential, testOnly, uid, readonly: testOnly,
        rules: Object.fromEntries(['name', 'type', 'host', 'port', 'database', 'account', ...((!source?.credentialSet || credential) ? ['password'] : [])].map(key => [key, [{ required: true, message: '请填写或选择此项', trigger: 'blur' }, ...(['name', 'host', 'database', 'account', 'password'].includes(key) ? [{ validator: (_, value, done) => done(String(value || '').trim() ? undefined : new Error('此项不能为空')), trigger: 'blur' }] : [])]])) }),
      computed: {
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
        fingerprint() { const { version, schemas, ...parameters } = this.model; return JSON.stringify(parameters); },
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
        <footer class="dialog-footer"><el-button @click="close">{{testOnly?'关闭':'取消'}}</el-button><template v-if="review"><el-button @click="review=''">返回修改</el-button><el-button type="primary" @click="commit">确认{{credential?'更新':review==='enable'?(sourceLocked?'保存':'启用'):'保存草稿'}}</el-button></template><template v-else><el-button v-if="!testOnly && !credential && !sourceLocked" :disabled="busy" @click="prepare('draft')">保存草稿</el-button><el-button :loading="busy" native-type="submit">{{results.length?'重新测试':'测试连接'}}</el-button><el-button v-if="!testOnly" type="primary" :disabled="!passed || busy" @click="prepare('enable')">{{credential?'核对密码更新':sourceLocked?'核对并保存':'核对并启用'}}</el-button></template></footer>
      </el-form>`,
      setup() { return { popupTarget: node, hasCredential: Boolean(source?.credentialSet), sourceLocked: Boolean(source && source.connectionState !== '草稿'), scopeLocked: Boolean(source && demo.activeTask(source.id)) }; }
    }); node.showModal();
  }
  function renderCatalog() {
    const source = demo.find(demo.selected), query = getQuery('catalog:' + source.id);
    const rows = queryRows(demo.objects(source.id), query, table => ({ status: table.collection, fields: { keyword: [objectName(table), table.label], type: table.type, owner: table.governance } }));
    const tools = filters(query, [{ key: 'keyword', label: '对象名称', placeholder: '表名或中文名称' }, { key: 'type', label: '对象类型', options: ['普通表', '视图', '物化视图', '分区表', '系统表', '临时表', '外部表'].map(value => ({ value, label: value })) }, { key: 'owner', label: '治理状态', options: ['已确认', '待治理', '待复核'].map(value => ({ value, label: value })) }], ['已采集', '属性缺失', '新发现', '结构变化', '访问丢失待核实', '已确认移除']);
    const panel = tablePanel(rows, query, [{ key: 'name', label: '对象 / 中文解释', width: 235 }, { key: 'type', label: '对象类型', width: 110 }, { key: 'governance', label: '治理状态', width: 110 }, { key: 'state', label: '采集状态', width: 120 }, { key: 'version', label: '结构版本', width: 115 }, { key: 'at', label: '采集时间', width: 170 }, { key: 'actions', label: '操作', width: 144, fixed: 'right' }], table => [stack(ui.button(objectName(table), trigger => objectDetail(table, trigger), 'cell-title', 'ds-object-' + table.id), table.label), stack(table.type, table.parent ? '父表 ' + table.parent : ''), badge(table.governance), badge(table.collection), ui.textCell(table.version), ui.textCell(table.collected), link('查看结构', trigger => objectDetail(table, trigger))], demo.objects(source.id).length ? '没有匹配的对象' : source.connectionState === '已启用' ? '尚无结构目录，发起首次采集后显示' : '尚无结构目录，请先测试并启用数据源');
    main.replaceChildren(ui.pageHeading('元数据目录'), context(source, allowed('collections.collect') && source.connectionState === '已启用' && ui.button('发起采集', trigger => collect(source, trigger), 'admin-button is-primary')), tools, panel);
  }
  function objectDetail(table, trigger) {
    const source = demo.find(table.source);
    showDetail(objectName(table) + ' · ' + table.label, trigger, body => {
      fields(body, [['数据源', source.name], ['对象路径', table.path], ['对象类型', table.type], ['采集状态', table.collection], ['治理状态', table.governance], ['数据库原注释', table.comment || '无注释']]);
      if (table.forbidden) body.append(note('该对象关联知识已禁止使用；重新采集或连接恢复不会自动解除。', true));
      heading(body, '字段');
      const region = ui.el('div', 'ds-inner-table'); region.append(RecordTable.render({ caption: objectName(table) + ' 字段', columns: [{ key: 'name', label: '字段', width: 160 }, { key: 'meaning', label: '业务解释', width: 240 }, { key: 'type', label: '类型', width: 140 }, { key: 'null', label: '可空', width: 70 }, { key: 'default', label: '默认值 / 生成', width: 150 }, { key: 'comment', label: '原注释', width: 160 }], items: table.fields, row: field => [ui.textCell(field.name), ui.el('span', 'ds-field-meaning', field.meaning), ui.textCell(field.type), field.nullable, ui.textCell(field.default), ui.el('span', 'ds-field-meaning', field.comment || '无注释')] }));
      const fieldScroll = region.querySelector('.rt-scroll'); fieldScroll.tabIndex = 0; fieldScroll.setAttribute('aria-label', objectName(table) + ' 的字段，可横向滚动');
      body.append(region);
      heading(body, '键、关系与索引');
      fields(body, [['主键', table.fields[0].name], ['唯一约束', '已读取：未声明其他唯一约束'], ['来源外键', table.name === 'sale_item' ? 'product_id → product.id（数据库声明）' : '已读取：未声明外键'], ['业务关系', '需在治理中核对；来源外键不等于已确认业务关系'], ['索引读取', table.indexState], ['索引定义', table.indexState === '无权限' ? '未知：账号无索引目录读取权限' : 'PRIMARY (' + table.fields[0].name + ')']]);
      if (table.indexState === '无权限') body.append(note('请数据库负责人确认索引目录读取权限。字段等已取得属性保留，不将缺项解释为没有索引。'));
      if (table.type === '视图') { heading(body, '视图来源'); fields(body, [['依赖', 'sale_order、sale_item'], ['定义读取', '已读取；仅同源对象'], ['刷新信息', '普通视图，按查询实时读取']]); }
      sourceFacts(body, [['来源对象标识', table.identity], ['对象世代', table.generation], ['结构版本', table.version], ['采集时间', table.collected], ['来源只读能力', table.readState], ['注释读取状态', table.commentState]]);
    }, (bar, node) => { if (allowed('governance.view')) bar.append(ui.button('进入治理', () => { node.close(); navigateGovernance(source, objectName(table)); }, 'admin-button is-primary')); });
  }
  function navigateGovernance(source, object) { demo.selected = source.id; location.hash = 'governance/' + encodeURIComponent(source.id) + (object ? '/' + encodeURIComponent(object) : ''); }
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
    const source = demo.find(demo.selected), isTasks = collectionTab === 'tasks', query = getQuery(collectionTab + ':' + source.id);
    const base = (isTasks ? demo.tasks : demo.changes).filter(item => item.source === source.id).sort((a, b) => b.at.localeCompare(a.at) || b.id.localeCompare(a.id));
    const rows = queryRows(base, query, item => ({ status: item.state, fields: isTasks ? { keyword: item.id, owner: item.trigger } : { keyword: [item.object, item.field], owner: item.kind } }));
    const choices = isTasks ? ['手动', '定时', '重试'] : ['新增字段', '类型变化', '删除字段', '访问丢失', '修改注释'];
    const tools = filters(query, [{ key: 'keyword', label: isTasks ? '采集编号' : '变化对象', placeholder: isTasks ? '采集编号' : '表名或字段名' }, { key: 'owner', label: isTasks ? '发起方式' : '变化类型', options: choices.map(value => ({ value, label: value })) }], isTasks ? ['排队', '进行中', '完整成功', '部分成功', '失败', '已取消'] : ['待复核', '已复核'], true);
    const tabbar = tabs([['tasks', '采集记录'], ['changes', '结构变化']], collectionTab, value => { collectionTab = value; refresh(); });
    const columns = isTasks ? [{ key: 'id', label: '采集编号', width: 152 }, { key: 'trigger', label: '发起方式', width: 105 }, { key: 'scope', label: '采集范围', width: 190 }, { key: 'at', label: '开始时间', width: 166 }, { key: 'state', label: '任务状态', width: 112 }, { key: 'coverage', label: '处理 / 已发现', width: 150 }, { key: 'actions', label: '操作', width: 156, fixed: 'right' }] : [{ key: 'object', label: '变化对象', width: 210 }, { key: 'kind', label: '变化类型', width: 112 }, { key: 'impact', label: '影响及使用控制', width: 230 }, { key: 'at', label: '发现时间', width: 166 }, { key: 'state', label: '复核状态', width: 100 }, { key: 'actions', label: '操作', width: 118, fixed: 'right' }];
    const panel = tablePanel(rows, query, columns, item => isTasks ? [ui.button(item.id, trigger => taskDetail(item, trigger), 'cell-title', 'ds-task-' + item.id), stack(item.trigger, item.actor), ui.textCell(item.scope), ui.textCell(item.at), badge(item.state), stack(item.processed + ' / ' + item.discovered, item.enumeration ? '总数 ' + item.total + ' · 失败 ' + item.failed : '总数未知 · 枚举不完整'), actions(link('详情', trigger => taskDetail(item, trigger), 'ds-task-detail-' + item.id), ['排队', '进行中'].includes(item.state) && allowed('collections.cancel') ? link('取消', trigger => cancelTask(item, trigger)) : ['部分成功', '失败'].includes(item.state) && allowed('collections.retry') && source.connectionState === '已启用' ? link('重试', trigger => collect(source, trigger, item)) : null)] : [stack(ui.button(item.object, trigger => changeDetail(item, trigger), 'cell-title'), item.field), ui.textCell(item.kind), stack(ui.textCell(item.impact), item.control), ui.textCell(item.at), badge(item.state), link(item.state === '待复核' && allowed('collections.review') ? '复核变化' : '查看详情', trigger => changeDetail(item, trigger))], base.length ? '没有匹配的记录' : isTasks ? '尚无采集记录' : '尚未发现结构变化', tabbar);
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
    fields(body, [['数据源', demo.find(task.source).name], ['任务编号', task.id], ['采集范围', task.scope], ['采集账号', task.account], ['发起方式', task.trigger + ' · ' + task.actor], ['开始时间', task.at], ['结束时间', task.ended || '尚未结束'], ['枚举覆盖', task.enumeration ? '枚举完整' : '不完整，总数未知'], ['已发现 / 已处理', task.discovered + ' / ' + task.processed], ['失败对象', task.failed], ['候选结构版本', task.version], ['数据库任务状态', task.databaseState], ...(task.parent ? [['原任务', task.parent]] : [])]);
    if (task.enumeration) { const host = ui.el('div', 'prototype-ui ds-progress'); PrototypeUI.mount(host, { data: () => ({ percentage: task.total ? Math.round(task.processed / task.total * 100) : 0, state: task.state }), template: '<el-progress :percentage="percentage" :status="state===\'完整成功\'?\'success\':state===\'部分成功\'?\'warning\':state===\'失败\'?\'exception\':undefined"></el-progress>' }); body.append(host); }
    if (task.reason) body.append(note(task.reason, task.state === '失败'));
    if (task.failures.length) { heading(body, '失败对象'); const list = ui.el('div', 'ds-failure-list'); task.failures.forEach(name => list.append(stack(name, task.enumeration ? '索引：无权限；字段、类型等可靠属性已保留' : '未完整取得结构，请重新枚举确认'))); body.append(list); }
    heading(body, '结果与来源');
    body.append(ui.el('p', 'ds-prose', task.retryOnly ? '本次仅覆盖失败对象，不代表整源完整采集；原任务及最近完整采集记录保留。' : task.enumeration ? '本次结果独立保留结构版本与采集时间，人工确认的业务说明不会被同步覆盖。' : '当前仅记录已发现及已完成范围，未发现对象不作为删除证据。'));
  }
  function cancelTask(task, trigger) {
    ui.openForm({ title: '取消采集 · ' + task.id, trigger, note: '停止采集并保留已完成范围；未完成部分不作为对象删除依据。', value: { reason: '' }, fields: [{ key: 'reason', label: '取消原因', type: 'textarea', required: true, full: true }], danger: true, saveLabel: '取消采集', preview: () => [{ label: '任务', before: task.state, after: '已取消' }, { label: '已完成范围', before: task.processed + ' 个对象', after: '保留已取得属性' }], save: () => demo.cancelTask(task) ? undefined : '任务已结束，请回看最新结果。', success: '采集已取消' });
  }
  function changeDetail(change, trigger) {
    const source = demo.find(change.source);
    showDetail('结构变化 · ' + change.object, trigger, body => {
      fields(body, [['数据源', source.name], ['变化编号', change.id], ['变化对象', change.object + '.' + change.field], ['变化类型', change.kind], ['发现时间', change.at], ['复核状态', change.state]]);
      heading(body, '前后差异'); fields(body, [['原值', change.before], ['新值', change.after], ['来源证据', change.evidence], ['受影响内容', change.impact], ['知识使用控制', change.control]]);
      if (change.compatible === false) body.append(note('相关知识已禁止使用。提交结构复核不解除禁止；修复、验证及必要发布后由有权人员显式解除。', true));
      if (change.compatible === null) body.append(note('来源可见性尚待核实，不能直接认定对象已删除。'));
      if (change.result) { heading(body, '复核结果'); body.append(ui.el('p', 'ds-prose', change.result)); fields(body, [['复核人', change.reviewer || '杨分析师'], ['复核时间', change.reviewed || change.at]]); }
    }, (bar, node) => { if (change.state === '待复核' && allowed('collections.review')) bar.append(ui.button('提交复核', () => { node.close(); ui.openForm({ title: '复核变化 · ' + change.object, trigger, note: change.compatible === null ? '确认当前线索并交责任人继续核实，不把访问丢失改报为已删除。' : '', value: { conclusion: '', owner: 'u11' }, fields: [{ key: 'conclusion', label: '复核结论与后续处理', type: 'textarea', required: true, full: true }, { key: 'owner', label: '后续负责人', options: PeopleDemo.users.filter(user => user.status === '正常').map(user => ({ value: user.id, label: person(user.id) })), required: true, full: true }], previewTitle: '核对结构复核', preview: data => [{ label: '变化对象', before: change.object + '.' + change.field, after: change.object + '.' + change.field }, { label: '后续处理', before: '待复核', after: data.conclusion + '\n负责人：' + person(data.owner) }, { label: '当前知识控制', before: change.control, after: change.control }], save: data => { if (!allowed('collections.review') || change.state !== '待复核') return '当前不可提交，请刷新后核对。'; Object.assign(change, { state: '已复核', result: data.conclusion, owner: data.owner, reviewer: ui.currentUser.name, reviewed: demo.now() }); }, success: '结构复核已提交' }); }, 'admin-button is-primary')); });
  }
  function renderNotes() {
    const source = demo.find(demo.selected), query = getQuery('business-notes:' + source.id);
    const base = demo.notes.filter(item => item.source === source.id && (item.author === ui.currentUser.id || allowed('business-notes.replace') || item.viewers.includes(ui.currentUser.id)));
    const rows = queryRows(base, query, item => ({ status: item.status, fields: { keyword: item.title, owner: person(item.author) } })).sort((a, b) => b.updated.localeCompare(a.updated) || b.id.localeCompare(a.id));
    const tools = filters(query, [{ key: 'keyword', label: '资料标题' }, { key: 'owner', label: '提交人', placeholder: '提交人姓名或登录名' }], ['有效', '已撤下'], false, allowed('business-notes.submit') && source.connectionState !== '已退役' && ui.button('新增业务说明', trigger => noteEditor(source, null, trigger), 'admin-button is-primary'));
    const panel = tablePanel(rows, query, [{ key: 'title', label: '资料标题', width: 240 }, { key: 'author', label: '提交人', width: 145 }, { key: 'revision', label: '修订', width: 85 }, { key: 'at', label: '更新时间', width: 166 }, { key: 'state', label: '状态', width: 100 }, { key: 'actions', label: '操作', width: 165, fixed: 'right' }], item => [stack(ui.button(item.title, trigger => noteDetail(item, trigger), 'cell-title', 'ds-note-' + item.id), item.files.length ? item.files.length + ' 个附件' : '文字说明'), ui.textCell(person(item.author)), '第 ' + item.revision + ' 版', ui.textCell(item.updated), badge(item.status), actions(link('详情', trigger => noteDetail(item, trigger)), item.status === '有效' && allowed('business-notes.replace') && link('编辑', trigger => noteEditor(source, item, trigger)))], base.length ? '没有匹配的业务资料' : '尚无业务说明，可先描述系统用途和已知业务规则');
    main.replaceChildren(ui.pageHeading('企业业务说明'), context(source), tools, panel);
  }
  function noteDetail(item, trigger) {
    const source = demo.find(item.source);
    showDetail(item.title, trigger, body => {
      fields(body, [['数据源', source.name], ['提交人', person(item.author)], ['更新时间', item.updated], ['资料状态', item.status], ['当前修订', '第 ' + item.revision + ' 版'], ['可使用人员', item.viewers.length ? '提交人、该源资料管理者及 ' + item.viewers.map(person).join('、') : '提交人与该源资料管理者']]);
      heading(body, '业务说明'); body.append(ui.el('p', 'ds-prose', item.content || '未填写文字说明'));
      if (item.files.length) { heading(body, '附件'); item.files.forEach(file => body.append(ui.el('p', 'ds-file-name', file.name + ' · ' + Math.ceil(file.size / 1024) + ' KB'))); }
      if (item.history.length) { heading(body, '修订记录'); item.history.forEach(entry => { const history = ui.el('details', 'ds-note-history'), summary = ui.el('summary', '', '第 ' + entry.revision + ' 版 · ' + entry.updated); history.append(summary, ui.el('p', 'ds-prose', entry.content || '无文字说明')); entry.files.forEach(file => history.append(ui.el('p', 'ds-file-name', file.name))); body.append(history); }); }
      if (item.status === '已撤下') body.append(note('资料已撤下，不再作为新治理任务的取用来源；关联治理内容需复核。'));
    }, (bar, node) => {
      if (item.status === '有效' && allowed('business-notes.withdraw')) bar.prepend(ui.button('撤下资料', () => { node.close(); ui.openForm({ title: '撤下资料 · ' + item.title, trigger, value: { reason: '' }, fields: [{ key: 'reason', label: '撤下原因', type: 'textarea', required: true, full: true }], danger: true, saveLabel: '撤下', note: '停止新任务取用该资料，历史修订保留；关联治理内容需复核。', preview: () => [{ label: '资料状态', before: '有效', after: '已撤下' }, { label: '关联治理', before: source.governed + ' 个已确认对象', after: '按资料依赖复核；不直接改写已确认定义' }], save: data => { item.status = '已撤下'; item.reason = data.reason; item.updated = demo.now(); }, success: '资料已撤下，关联治理待复核' }); }, 'admin-button is-danger'));
      if (item.status === '有效' && allowed('business-notes.replace')) bar.append(ui.button('编辑业务说明', () => { node.close(); noteEditor(source, item, trigger); }, 'admin-button is-primary'));
    });
  }
  function noteEditor(source, item, trigger) {
    const node = ui.modal((item ? '编辑业务说明' : '新增业务说明') + ' · ' + source.name, trigger); node.classList.add('ds-note-dialog'); const host = ui.el('div'); node.append(host);
    const uid = 'ds-note-form-' + (++detailSequence);
    PrototypeUI.mount(host, { data: () => ({ model: { title: item?.title || '', content: item?.content || '', files: (item?.files || []).map((file, i) => ({ ...file, uid: i + 1 })), viewers: [...(item?.viewers || [])] }, reviewing: false, error: '', uid, people: PeopleDemo.users.filter(user => user.status === '正常' && user.id !== (item?.author || ui.currentUser.id)).map(user => ({ value: user.id, label: person(user.id) })), rules: { title: [{ required: true, whitespace: true, message: '请填写资料标题', trigger: 'blur' }] } }), methods: {
      fileChanged(file, list) { if ((file.size || 0) > 20 * 1024 * 1024 || !/\.(pdf|docx|xlsx|csv|txt|md)$/i.test(file.name)) { this.model.files = list.filter(entry => entry.uid !== file.uid); this.error = '请选择 20 MB 以内的 PDF、DOCX、XLSX、CSV、TXT 或 Markdown 文件。'; return; } this.model.files = list; this.error = ''; },
      validField(key, valid) { this.$nextTick(() => { const input = this.$refs.form.getField(key)?.$el.querySelector('input'); if (input) { input.setAttribute('aria-invalid', String(!valid)); if (!valid) input.setAttribute('aria-describedby', uid + '-title-error'); else input.removeAttribute('aria-describedby'); } }); },
      prepare() { this.$refs.form.validate(valid => { if (!valid) { this.$nextTick(() => node.querySelector('.is-error input')?.focus()); return; } if (!this.model.content.trim() && !this.model.files.length) { this.error = '请填写业务说明或选择附件。'; this.$nextTick(() => node.querySelector('textarea')?.focus()); return; } this.error = ''; this.reviewing = true; this.$nextTick(() => node.querySelector('.ds-review')?.focus()); }); },
      save() {
        if (!allowed(item ? 'business-notes.replace' : 'business-notes.submit')) return;
        const value = { title: this.model.title.trim(), content: this.model.content.trim(), files: this.model.files.map(file => ({ name: file.name, size: file.size || file.raw?.size || 0 })), viewers: [...this.model.viewers], updated: demo.now() };
        if (item) { item.history.unshift({ revision: item.revision, updated: item.updated, content: item.content, files: structuredClone(item.files) }); Object.assign(item, value, { revision: item.revision + 1 }); }
        else demo.notes.unshift({ ...value, id: demo.nextId('ZL-'), source: source.id, author: ui.currentUser.id, revision: 1, status: '有效', history: [] });
        Object.assign(getQuery('business-notes:' + source.id), { keyword: '', owner: '', status: 'all', page: 1 }); node.close(); refresh(); ui.notify(item ? '业务说明已保存新修订，关联治理待复核' : '业务说明已保存');
      }
    }, template: `<el-form ref="form" :model="model" :rules="rules" label-position="top" @validate="validField" @submit.prevent="prepare"><div class="dialog-body"><p v-if="error" class="form-error" role="alert">{{error}}</p><div v-show="!reviewing"><el-form-item label="资料标题" prop="title"><el-input v-model="model.title" aria-label="资料标题" :maxlength="120"></el-input><template #error="{error:message}"><div class="el-form-item__error" :id="uid+'-title-error'" role="alert">{{message}}</div></template></el-form-item><el-form-item label="业务说明"><el-input v-model="model.content" aria-label="业务说明" type="textarea" :rows="7" :maxlength="5000" show-word-limit></el-input></el-form-item><el-form-item label="附件"><el-upload v-model:file-list="model.files" :auto-upload="false" :limit="5" :on-change="fileChanged" :on-exceed="()=>error='最多选择 5 个附件。'" accept=".pdf,.docx,.xlsx,.csv,.txt,.md" multiple><el-button>选择附件</el-button><template #tip><div class="el-upload__tip">PDF、DOCX、XLSX、CSV、TXT、Markdown；单个文件不超过 20 MB</div></template></el-upload></el-form-item><el-form-item label="额外可使用人员"><el-select v-model="model.viewers" multiple filterable aria-label="额外可使用人员" :append-to="popupTarget" placeholder="选择可取用此资料的人员"><el-option v-for="user in people" :key="user.value" v-bind="user"></el-option></el-select><span class="field-help">默认仅提交人与该源资料管理者可见。</span></el-form-item></div><section v-if="reviewing" class="ds-review" tabindex="-1"><h3>核对业务说明</h3><dl class="detail-fields"><div><dt>数据源</dt><dd>{{sourceName}}</dd></div><div><dt>资料标题</dt><dd>{{model.title}}</dd></div><div><dt>保存修订</dt><dd>{{nextRevision}}</dd></div><div><dt>附件</dt><dd>{{model.files.map(file=>file.name).join('、') || '无'}}</dd></div><div class="full-width"><dt>可使用人员</dt><dd>提交人与该源资料管理者<span v-if="model.viewers.length">，以及 {{model.viewers.map(id=>people.find(user=>user.value===id)?.label || id).join('、')}}</span></dd></div></dl><p class="ds-prose">{{model.content}}</p></section></div><footer class="dialog-footer"><el-button @click="$el.closest('dialog').close()">取消</el-button><el-button v-if="reviewing" @click="reviewing=false">返回修改</el-button><el-button type="primary" v-if="reviewing" @click="save">确认保存</el-button><el-button type="primary" v-else native-type="submit">核对说明</el-button></footer></el-form>`, setup: () => ({ popupTarget: node, sourceName: source.name, nextRevision: (item?.revision || 0) + 1 }) }); node.showModal();
  }
  function render(target, controls, options = {}) {
    main = target; ui = controls; section = options.section || 'sources';
    if (!allowed(section + '.view')) { main.replaceChildren(ui.pageHeading('不可访问'), note('当前账号没有此页面的查看权限。', true)); return; }
    if (section === 'sources') renderSources(options.tabs);
    else if (section === 'catalog') renderCatalog();
    else if (section === 'collections') renderCollections();
    else renderNotes();
  }
  window.addEventListener('datasource-task-updated', event => {
    const body = document.querySelector('[data-task-body="' + event.detail + '"]'), task = demo.tasks.find(item => item.id === event.detail);
    if (body && task) { taskBody(task, body); if (!['排队', '进行中'].includes(task.state)) { const dialog = body.closest('dialog'); taskActions(task, dialog.querySelector('.dialog-footer'), dialog, dialog.taskTrigger); } }
    else if (location.hash === '#collections' && !document.querySelector('dialog[open]') && collectionTab === 'tasks') { const focused = document.activeElement; if (focused === document.body || main.contains(focused) && !focused.closest('.rl-filters,.rl-pager')) refresh(); }
  });
  window.DataSourcesPage = { render, connectionEditor };
})();
