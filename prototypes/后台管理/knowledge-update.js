/* 三个任务入口共用后台控件；长正文在独立页编辑，发布及恢复操作各自核权。 */
(() => {
  'use strict';
  const model = KnowledgeWorkspace, queries = new Map(), origins = new Map(), documentViews = new Map();
  let main, ui, source, section, editor = null, allowNavigation = false, pendingNavigation = null, caseTask = null, caseDocument = null, versionTask = null;
  const el = (tag, cls, text) => ui.el(tag, cls, text);
  const allowed = ability => model.has(ui.currentUser, ability);
  const route = (area, target = '', extra = '') => '#' + area + '/' + source.id + (target ? '/' + encodeURIComponent(target) : '') + (extra ? '/' + encodeURIComponent(extra) : '');
  const titleOf = area => ({ knowledge: '知识文档', 'test-cases': '标准题单', releases: '发布与版本' }[area]);
  const focusLater = selector => requestAnimationFrame(() => requestAnimationFrame(() => main?.querySelector(selector)?.focus({ preventScroll: true })));
  const currentOrigin = () => history.state?.kwOrigin || origins.get(location.hash);
  function captureView() {
    return { hash: location.hash, previous: currentOrigin() || null, top: main.scrollTop, focus: document.activeElement?.dataset.focus,
      scrolls: [...main.querySelectorAll('.rt-scroll,.kw-content,.kw-editor-pane')].map(node => ({ top: node.scrollTop, left: node.scrollLeft })) };
  }
  function navigate(hash, remember = true, origin) {
    if (hash === location.hash) return;
    pendingNavigation = { hash, origin: origin !== undefined ? origin : remember ? captureView() : origins.get(hash) || null };
    location.hash = hash;
  }
  function syncOrigin() {
    const origin = pendingNavigation?.hash === location.hash ? pendingNavigation.origin : history.state?.kwOrigin;
    if (origin) origins.set(location.hash, origin); else origins.delete(location.hash);
    history.replaceState({ ...history.state, kwOrigin: origin || null }, '', location.href); pendingNavigation = null;
  }
  function routeLabel(hash) {
    const [area, , target, detail] = (hash || '').slice(1).split('/');
    if (area === 'knowledge') return target === 'plan' ? '生成规划' : target === 'replace' ? '合并拆分' : target ? '文档详情' : '知识文档';
    if (area === 'test-cases') return ['for-task', 'for-document'].includes(target) ? '相关标准题' : target ? '题目详情' : '标准题单';
    if (area === 'releases') return target === 'active' ? detail ? '版本详情' : '当前生效' : target === 'active-batch' ? '本批生效版本' : target && target !== 'tasks' ? '发布任务' : '发布与版本';
    return ({ governance: '治理页面', catalog: '元数据目录', collections: '采集记录', 'business-notes': '企业业务说明' })[area];
  }
  function refresh(focus = '[data-primary]') {
    const positions = main?.querySelector('.kw-shell') ? [...main.querySelectorAll('.kw-content,.kw-editor-pane')].map(node => node.scrollTop) : [];
    window.dispatchEvent(new Event('hashchange'));
    if (positions.length) requestAnimationFrame(() => main.querySelectorAll('.kw-content,.kw-editor-pane').forEach((node,index) => { node.scrollTop = positions[index] || 0; }));
    focusLater(focus);
  }
  function refreshTask(view) {
    if (location.hash !== view) return;
    const dialog = document.querySelector('dialog[open]');
    // 后台阶段完成不打断正在填写的确认表单，也不抢走其他页面的焦点。
    if (dialog) {
      if (!dialog.dataset.kwPendingRefresh) {
        dialog.dataset.kwPendingRefresh = 'true';
        dialog.addEventListener('close', () => requestAnimationFrame(() => { if (location.hash === view) refresh(); }), { once: true });
      }
      return;
    }
    refresh();
  }
  function execute(fn, message = '处理结果已更新', focus) {
    const result = fn(); if (typeof result === 'string' && result) { ui.notify(result); return result; }
    ui.notify(message); refresh(focus); return '';
  }
  function button(label, action, primary = false, key = label, issue = '') {
    const node = ui.button(label, action, 'admin-button kw-action' + (primary ? ' is-primary' : ''), 'kw-' + key);
    node.kwAction = action;
    if (primary) node.dataset.primary = 'true';
    if (issue) { node.disabled = true; node.title = issue; node.setAttribute('aria-describedby', 'kw-issue'); }
    return node;
  }
  function tag(text) {
    return el('span', 'status-tag' + (/失败|未通过|暂停|异常|撤下|失效|不可用|阻断/.test(text) ? ' ds-status-danger' : /取消|回退|替代/.test(text) ? ' kw-status-neutral' : /草稿|待|未验证/.test(text) ? ' is-pending' : ''), text);
  }
  const stack = (first, second) => { const node = el('div'); node.append(el('strong', '', first), el('small', 'cell-sub', second || '')); return node; };
  const paragraph = text => el('p', 'ds-prose', text);
  function back(defaultHash) {
    const origin = currentOrigin(), target = origin?.hash || defaultHash;
    navigate(target, false, origin?.previous || null);
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (location.hash !== target) return;
      main.scrollTop = origin?.top || 0;
      [...main.querySelectorAll('.rt-scroll,.kw-content,.kw-editor-pane')].forEach((node, index) => { node.scrollTop = origin?.scrolls?.[index]?.top || 0; node.scrollLeft = origin?.scrolls?.[index]?.left || 0; });
      (origin?.focus && main.querySelector('[data-focus="' + CSS.escape(origin.focus) + '"]') || main.querySelector('[data-primary],h1'))?.focus({ preventScroll: true });
    }));
  }
  function finishEditing(hash) {
    const origin = currentOrigin(); editor = null;
    navigate(hash, false, origin?.hash === hash ? origin.previous : origin);
  }
  function syncSaveAction() {
    if (editor?.hash !== location.hash) return;
    main.querySelectorAll('.gov-page-footer button').forEach(node => { if (node.textContent === '保存草稿') { node.disabled = !editor.dirty(); node.title = node.disabled ? '尚未修改内容' : ''; } });
  }
  function shell(title, parent = titleOf(section), parentHash = route(section)) {
    ui.setTitle(title, parent, parentHash);
    const root = el('section', 'gov-shell kw-shell'), header = el('header', 'gov-toolbar'), identity = el('div', 'gov-identity');
    identity.append(el('strong', '', title), el('small', '', source.name));
    header.append(identity, button('返回' + (routeLabel(currentOrigin()?.hash) || parent), () => back(parentHash), false, 'back'));
    root.append(header); const content = el('div', 'gov-content kw-content'); root.append(content);
    main.replaceChildren(ui.pageHeading(title), root); return { root, content, header };
  }
  function block(content, title, ...children) {
    const node = el('section', 'kw-section'); node.append(el('h2', '', title), ...children); content.append(node); return node;
  }
  function footer(root, buttons, issue = '') {
    const node = el('footer', 'gov-page-footer');
    if (issue) { const text = el('p', 'gov-action-issue', issue); text.id = 'kw-issue'; text.setAttribute('role', 'status'); node.append(text); }
    const available = buttons.filter(Boolean), primaries = available.filter(item => item.classList.contains('is-primary'));
    const primary = primaries.filter(item => !item.disabled).at(-1) || primaries.at(-1);
    primaries.filter(item => item !== primary).forEach(item => item.classList.remove('is-primary'));
    if (available.length > 3 && ui.moreActions) {
      const secondary = available.find(item => item !== primary && /查看文档|重新运行验证|返回/.test(item.textContent)) || available.find(item => item !== primary && !/取消|撤下|回退/.test(item.textContent));
      const remaining = available.filter(item => item !== primary && item !== secondary);
      node.append(ui.moreActions(remaining.map(item => ({ label: item.textContent, action: trigger => item.kwAction(trigger), disabled: item.disabled, reason: item.title, danger: /取消|撤下/.test(item.textContent) })), 'kw-footer-more', undefined, document.body, 'top-end'));
      if (secondary) node.append(secondary); if (primary) node.append(primary);
    } else { available.filter(item => item !== primary).forEach(item => node.append(item)); if (primary) node.append(primary); }
    root.append(node); syncSaveAction(); return node;
  }
  function context(kind) {
    const node = el('section', 'gov-context kw-context prototype-ui');
    PrototypeUI.mount(node, { data: () => ({ selected: source.id, sources: DataSourceDemo.sources }), methods: { change(value) { navigate('#' + section + '/' + value + (section === 'releases' && kind === 'active' ? '/active' : ''), false, null); } },
      template: '<label id="kw-source">当前数据源</label><el-select v-model="selected" aria-labelledby="kw-source" @change="change"><el-option v-for="item in sources" :key="item.id" :value="item.id" :label="item.name"></el-option></el-select>' });
    return node;
  }
  function listView(kind, rows, columns, row, statuses, actions = [], fields = [{ key: 'keyword', label: '名称' }, { key: 'object', label: '来源对象' }]) {
    const scopedTask = caseTask || versionTask;
    const queryKey = section + '/' + source.id + '/' + kind + (scopedTask ? '/' + scopedTask.id : caseDocument ? '/' + caseDocument.id : '');
    if (!queries.has(queryKey)) queries.set(queryKey, { keyword: '', object: '', status: 'all', page: 1, pageSize: 10 });
    const query = queries.get(queryKey), filtered = rows.filter(item => AdminQuery.matches([item.searchName || item.title || item.question || item.id], query.keyword) &&
      AdminQuery.matches([item.searchObject || ''], query.object) && (query.status === 'all' || (item.listStates || [item.listState]).includes(query.status)));
    query.page = Math.max(1, Math.min(query.page, Math.ceil(filtered.length / query.pageSize)));
    const scope = context(kind), commands = actions.filter(Boolean);
    if (kind === 'documents' && allowed('governance.view')) commands.unshift(button('查看治理依据', () => navigate('#governance/' + source.id)));
    const filters = RecordControls.filters({ value: query, fields, includeDates: false, statusLabel: ({ documents: '整理状态', cases: '审核状态', tasks: '任务阶段', active: '当前可用性' })[kind], statuses: [{ label: '全部状态', value: 'all' }, ...statuses.map(value => ({ label: value, value }))],
      onApply: value => { Object.assign(query, value); refresh(); }, onReset: () => { Object.assign(query, { keyword: '', object: '', status: 'all', page: 1 }); refresh(); } });
    const panel = el('section', 'record-panel'), toolbar = el('div', 'kw-context-actions');
    if (section !== 'releases') { commands.forEach(action => toolbar.append(action)); if (commands.length) scope.append(toolbar); }
    if (scopedTask) {
      const trail = el('div', 'kw-task-context'); trail.append(tag(scopedTask.kind === '恢复验证' ? '恢复验证' : '发布任务'), el('span', '', scopedTask.id + ' · ' + scopedTask.documents.length + ' 份文档'), button('返回任务', () => back(route('releases', scopedTask.id)), false, 'return-task')); scope.append(trail);
    }
    if (caseDocument) {
      const trail = el('div', 'kw-task-context'); trail.append(el('span', '', caseDocument.title + ' · r' + caseDocument.draft.revision), button('返回文档', () => back(route('knowledge', caseDocument.id)), false, 'return-document')); scope.append(trail);
    }
    const empty = el('div', 'empty-state');
    if (kind === 'documents' && !rows.length) {
      empty.classList.add('kw-generation-empty'); empty.tabIndex = -1;
      const available = model.suggested(source.id).length > 0;
      const illustration = el('div', 'prototype-ui'); PrototypeUI.mount(illustration, { template: '<el-empty :image-size="80" description="还没有知识文档"></el-empty>' });
      empty.append(illustration, paragraph(available ? '已有已审核的表字段、关联或指标，可生成知识草稿。' : '当前数据源暂无已审核定义。请先完成治理审核。'));
      const commands = el('div', 'kw-empty-actions');
      if (!available && allowed('governance.view')) commands.append(button('前往治理', () => navigate('#governance/' + source.id), true));
      empty.append(commands);
    } else empty.append(WorkbenchIcon('file-text'), paragraph(rows.length ? '没有匹配的记录' : '暂无记录'));
    panel.append(RecordTable.render({ caption: titleOf(section), columns, items: filtered.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), row, empty }),
      RecordControls.pager({ ...query, total: filtered.length }, page => { query.page = page; refresh(); }, size => { query.pageSize = size; query.page = 1; refresh(); }));
    main.replaceChildren(ui.pageHeading(titleOf(section)), scope, ...(section === 'releases' ? [tabs(kind, commands)] : []), filters, panel);
  }
  function tabs(current, actions = []) {
    const outer = el('div', 'kw-tabs'), host = el('div', 'kw-tabs-control prototype-ui'); outer.append(host);
    PrototypeUI.mount(host, { data: () => ({ value: current }), methods: { change(value) { navigate(route('releases', value === 'active' ? 'active' : ''), false); } },
      template: '<el-tabs v-model="value" @tab-change="change"><el-tab-pane name="tasks" label="发布任务"></el-tab-pane><el-tab-pane name="active" label="当前生效"></el-tab-pane></el-tabs>' });
    const commands = el('div', 'kw-tab-actions'); actions.forEach(action => commands.append(action)); if (actions.length) outer.append(commands); return outer;
  }
  function confirmAction(title, fields, value, save, trigger, options = {}) {
    ui.openForm({ title, fields, value, save, trigger, saveLabel: options.label || '确认', danger: Boolean(options.danger), note: options.note || '',
      onSaved: () => { options.saved?.(); focusLater('[data-primary], [data-focus="kw-back"]'); }, success: options.success || '处理结果已更新' });
  }
  // 只保护实际未保存输入；侧栏、面包屑、浏览器前进后退共用同一离开检查。
  window.addEventListener('hashchange', event => {
    if (allowNavigation || !editor || location.hash === editor.hash || !editor.dirty()) return;
    const destination = location.hash, current = editor, destinationOrigin = pendingNavigation?.origin ?? history.state?.kwOrigin ?? null;
    event.stopImmediatePropagation(); history.replaceState({ ...history.state, kwOrigin: current.origin }, '', location.pathname + location.search + current.hash); pendingNavigation = null;
    ElementPlus.ElMessageBox.confirm('尚有未保存的修改。放弃后将丢失本次输入。', '放弃当前修改？', { confirmButtonText: '继续编辑', cancelButtonText: '放弃修改', distinguishCancelAndClose: true, closeOnClickModal: false, closeOnHashChange: false, appendTo: current.dialog || document.body, customClass: 'prototype-ui' })
      .catch(action => { if (action === 'cancel') { current.discard?.(); editor = null; allowNavigation = true; navigate(destination, false, destinationOrigin); setTimeout(() => { allowNavigation = false; }, 0); } });
  }, true);
  window.addEventListener('beforeunload', event => { if (editor?.dirty()) { event.preventDefault(); event.returnValue = ''; } });
  function longForm(content, initial, fields, commit) {
    const host = el('div', 'kw-form prototype-ui'), original = JSON.stringify(initial); content.append(host);
    const groups = [...new Set(fields.map(field => field.group).filter(Boolean))];
    PrototypeUI.mount(host, {
      data: () => ({ value: structuredClone(initial), fields, error: '', reviewSubmit: false, groups, panel: groups[0] || '' }),
      mounted() { const vm = this; editor = { hash: location.hash, origin: currentOrigin(), dirty: () => JSON.stringify(vm.value) !== original, submit: action => vm.submit(action) }; },
      watch: { value: { deep: true, handler() { this.error = ''; syncSaveAction(); } } },
      methods: { async submit(action) {
        this.error = ''; this.reviewSubmit = action === 'confirm';
        const missing = fields.find(field => (field.required || this.reviewSubmit && field.reviewRequired) && (Array.isArray(this.value[field.key]) ? !this.value[field.key].length : !String(this.value[field.key] || '').trim()));
        if (missing?.group) this.panel = missing.group;
        await this.$nextTick(); const valid = await this.$refs.form.validate().catch(() => false);
        if (!valid) { host.querySelector('.el-form-item.is-error input,.el-form-item.is-error textarea')?.focus(); return; }
        const message = commit(JSON.parse(JSON.stringify(this.value)), action);
        if (message) { this.error = message; await this.$nextTick(); host.querySelector('[role="alert"]')?.focus(); }
      } },
      template: `<div><el-tabs v-if="groups.length" v-model="panel"><el-tab-pane v-for="group in groups" :key="group" :name="group" :label="group"></el-tab-pane></el-tabs><el-form ref="form" :model="value" label-position="top" scroll-to-error @submit.prevent="submit('save')">
        <p v-if="error" role="alert" tabindex="-1" class="ds-notice is-danger">{{error}}</p>
        <el-form-item v-for="field in fields" v-show="(!field.group||field.group===panel)&&(field.key!=='tolerance'||value.type==='数值')" :key="field.key" :label="field.label" :prop="field.key" :rules="(field.required || (reviewSubmit && field.reviewRequired)) ? [{required:true,message:'请填写'+field.label,trigger:'blur'}] : []">
          <el-select v-if="field.options" v-model="value[field.key]" :multiple="field.multiple" collapse-tags collapse-tags-tooltip :max-collapse-tags="2" :aria-label="field.label" filterable><el-option v-for="option in field.options" :key="option.value" v-bind="option"></el-option></el-select>
          <el-input v-else v-model="value[field.key]" :aria-label="field.label" :type="field.type || 'text'" :rows="field.rows || 3" :maxlength="field.maxlength || 50000" :placeholder="field.placeholder || ''"></el-input>
        </el-form-item></el-form></div>`
    });
  }
  function documentList() {
    const rows = [...model.documents.values()].filter(doc => doc.source === source.id && !doc.cancelledReplacement).map(doc => ({ ...doc, searchName: doc.title + ' ' + (doc.draft.purpose || ''), searchObject: doc.draft.entries.map(entry => entry.object).join(' '),
      listState: doc.draft.confirmed ? '已确认' : '草稿', listStates: [doc.draft.confirmed ? '已确认' : '草稿', ...(model.needsUpdate(doc) ? ['待更新'] : [])] }));
    const generate = allowed('knowledge.generate') && button('规划知识文档', trigger => generateDocuments(trigger), true, 'generate');
    if (generate && !model.suggested(source.id).length) { generate.disabled = true; generate.title = '当前数据源暂无可生成的已审核内容'; }
    listView('documents', rows, [{ key: 'title', label: '文档 / 用途', width: 240 }, { key: 'active', label: '正式使用', width: 120 }, { key: 'draft', label: '整理修订', width: 110 },
      { key: 'state', label: '整理状态', width: 100 }, { key: 'updated', label: '创建时间', width: 160 }, { key: 'actions', label: '操作', width: 120 }], doc => [
      stack(doc.title, doc.draft.purpose || [...new Set(doc.draft.entries.map(entry => entry.object))].join('、')), model.active.has(doc.id) ? stack('r' + model.active.get(doc.id).snapshot.revision, model.activeState(model.active.get(doc.id))) : '未发布', 'r' + doc.draft.revision,
      doc.listStates.map(tag), doc.at, button('查看文档', () => navigate(route('knowledge', doc.id)), false, 'doc-' + doc.id)
    ], ['草稿', '已确认', '待更新'], [generate],
    [{ key: 'keyword', label: '文档名称或用途' }, { key: 'object', label: '来源对象' }]);
  }
  function generateDocuments(trigger, selected = []) {
    const result = model.createPlan(source.id, ui.currentUser); if (result.error) return ui.notify(result.error);
    navigate(route('knowledge', 'plan', result.plan.id));
  }
  function planningPage(plan) {
    const { root, content, header } = shell('生成规划'); const originTag = tag('示例建议'); originTag.title = '当前原型使用样例规划；正式流程在发起规划时由 AI 根据已确认治理成果提出建议。'; header.append(originTag);
    const host = el('div', 'kw-planning prototype-ui'); content.append(host);
    const options = DataSourceDemo.objects(source.id).flatMap(table => GovernanceDemo.get(table).items.filter(entry => entry.review === '已确认').map(entry => ({ value: table.id + '/' + entry.id, label: GovernanceDemo.objectName(table) + ' / ' + entry.name })));
    let controller, saved = JSON.stringify(plan.rows);
    const sync = vm => { plan.rows = JSON.parse(JSON.stringify(vm.rows)); };
    PrototypeUI.mount(host, {
      data: () => ({ rows: structuredClone(plan.rows), selected: [], mergeMode: false, expanded: '', checked: Boolean(plan.checked), result: '', options }),
      mounted() { controller = this; editor = { hash: location.hash, origin: currentOrigin(), dirty: () => JSON.stringify(this.rows) !== saved }; requestAnimationFrame(() => this.syncActions()); },
      methods: {
        syncActions() {
          const pending = this.rows.filter(row => row.operation !== '暂不处理' && !row.result?.document), ready = pending.filter(row => !row.issue || row.existingId);
          const added = pending.filter(row => row.operation === '新建').length, existing = pending.length - added;
          const check = main.querySelector('[data-focus="kw-plan-check"]'), submit = main.querySelector('[data-focus="kw-plan-submit"]');
          if (check) { check.classList.toggle('is-primary', !this.checked); check.textContent = this.checked ? '重新核对' : '核对范围'; }
          if (submit) { submit.disabled = !this.checked || !ready.length; submit.title = !this.checked ? '请先核对范围' : !ready.length ? '暂无可办理项目' : ''; submit.classList.toggle('is-primary', this.checked && ready.length > 0); submit.textContent = added && existing ? '生成并处理文档' : existing ? '打开已有文档' : '生成 ' + added + ' 份草稿'; }
          if (check && submit) check.parentElement.append(this.checked ? submit : check);
          const summary = main.querySelector('.kw-plan-summary'); if (summary) summary.textContent = '新建 ' + added + ' · 已有 ' + existing + ' · 待补充 ' + pending.filter(row => row.issue).length;
        },
        altered(row) { this.checked = false; plan.checked = false; this.result = ''; if (row) row.needsCheck = true; this.syncActions(); },
        async check() { sync(this); model.checkPlan(plan); plan.rows.forEach(row => { row.needsCheck = false; }); this.rows = structuredClone(plan.rows); saved = JSON.stringify(plan.rows); this.checked = true; plan.checked = true; this.result = ''; this.syncActions(); },
        async submit() {
          if (!this.checked) { ui.notify('请先核对本次范围。'); return; }
          sync(this); const result = model.submitPlan(plan, ui.currentUser); if (result.error) return ui.notify(result.error);
          this.rows = structuredClone(plan.rows); saved = JSON.stringify(plan.rows); this.result = '已完成 ' + result.success + ' 项' + (result.failed ? '，失败 ' + result.failed + ' 项，可单项重试' : ''); this.syncActions();
        },
        merge() { sync(this); const issue = model.mergeSuggestions(plan, this.selected); if (issue) return ui.notify(issue); this.rows = structuredClone(plan.rows); this.selected = []; this.mergeMode = false; this.altered(); },
        split(row) { sync(this); const issue = model.splitSuggestion(plan, row.id); if (issue) return ui.notify(issue); this.rows = structuredClone(plan.rows); this.altered(); },
        add() { const row = { id: DataSourceDemo.nextId('ITEM-'), key: DataSourceDemo.nextId('purpose/'), source: source.id, title: '', purpose: '', conditions: '', questions: [], excluded: [], bindings: [], operation: '新建', result: null, ...model.inspectSpec({ source: source.id, bindings: [] }) }; this.rows.push(row); this.expanded = row.id; this.altered(row); },
        remove(row) { this.rows = this.rows.filter(item => item.id !== row.id); this.altered(); },
        core(row) { return row.bindings.map(ref => ref.table + '/' + ref.entry); },
        requiredBy(row, entry) { return row.entries.filter(parent => (parent.dependencies || []).some(value => { const ref = typeof value === 'string' ? { table: parent.table, entry: value } : value; return ref.table === entry.table && ref.entry === entry.id; })).map(parent => parent.name).join('、'); },
        setCore(row, values) {
          row.bindings = values.map(value => { const index = value.lastIndexOf('/'); return { table: value.slice(0, index), entry: value.slice(index + 1) }; }); this.altered(row);
        },
        open(row) { const docId = row.result?.document || row.existingId; if (docId) navigate(route('knowledge', docId, row.result?.proposal ? 'proposal~' + plan.id + '~' + row.id : row.operation === '核对更新' && model.needsUpdate(model.documents.get(docId)) ? 'merge' : '')); },
        coverage(row) { return row.operation === '暂不处理' ? '本次不处理' : row.needsCheck ? '待核对' : row.issue ? '待补充' : row.existingId ? '已有文档' : '依据齐全'; }
      },
      template: `<div class="kw-plan-tools"><span>{{mergeMode?'选择要合并的建议':'文档建议'}} · {{rows.length}}</span><el-button v-if="!mergeMode" @click="add">补充用途</el-button><el-button v-if="!mergeMode && rows.filter(row=>!row.existingId&&!row.result?.document).length>1" @click="mergeMode=true">合并建议</el-button><template v-if="mergeMode"><el-button @click="mergeMode=false;selected=[]">取消选择</el-button><el-button @click="merge" :disabled="selected.length < 2">合并所选建议</el-button></template></div>
        <p v-if="result" class="ds-notice" role="status">{{result}}</p>
        <article v-for="row in rows" :key="row.id" class="kw-plan-row" :class="{'is-skipped':row.operation==='暂不处理'}">
          <div class="kw-plan-heading"><el-checkbox v-if="mergeMode" v-model="selected" :value="row.id" :disabled="!!row.result?.document||!!row.existingId" :aria-label="'合并'+row.title"></el-checkbox>
            <div class="kw-plan-identity"><strong>{{row.title}}</strong><p>{{row.purpose || '待明确用途'}}</p></div>
            <span class="status-tag" :class="{'is-pending':!!row.issue}">{{coverage(row)}}</span>
            <el-select v-model="row.operation" :aria-label="row.title+'本次操作'" @change="altered(row)" :disabled="!!row.result?.document"><el-option v-for="value in row.existingId?['定位已有','核对更新','暂不处理']:['新建','暂不处理']" :key="value" :value="value" :label="value"></el-option></el-select>
          </div>
          <div class="kw-plan-meta"><span>涉及表：{{row.tables.join('、') || '未选择'}}</span><el-button v-if="row.existingId" link @click="open(row)">查看已有文档</el-button><el-button link @click="expanded=expanded===row.id?'':row.id">{{expanded===row.id?'收起':'调整职责与范围'}}</el-button></div>
          <p v-if="row.issue&&!row.needsCheck" class="ds-notice is-danger" role="status">{{row.issue}}</p>
          <p v-if="row.controls.length" class="ds-notice">{{row.controls.join('；')}}；草稿整理不解除使用限制。</p>
          <div v-if="expanded===row.id" class="kw-plan-fields">
            <el-form label-position="top" @submit.prevent><el-form-item label="文档名称" required><el-input v-model="row.title" @input="altered(row)" :disabled="!!row.result?.document" aria-label="文档名称"></el-input></el-form-item>
              <el-form-item label="用途" required><el-input v-model="row.purpose" @input="altered(row)" :disabled="!!row.result?.document" aria-label="用途"></el-input></el-form-item>
              <el-form-item label="适用条件"><el-input v-model="row.conditions" @input="altered(row)" :disabled="!!row.result?.document" aria-label="适用条件"></el-input></el-form-item>
              <el-form-item label="代表性问题"><el-input :model-value="row.questions.join('\\n')" @input="value=>{row.questions=value.split('\\n').filter(Boolean);altered(row)}" type="textarea" :disabled="!!row.result?.document" aria-label="代表性问题"></el-input></el-form-item>
              <el-form-item label="不支持的内容"><el-input :model-value="row.excluded.join('\\n')" @input="value=>{row.excluded=value.split('\\n').filter(Boolean);altered(row)}" type="textarea" :disabled="!!row.result?.document" aria-label="不支持的内容"></el-input></el-form-item>
              <el-form-item label="主动选择条目"><el-select :model-value="core(row)" @update:model-value="value=>setCore(row,value)" multiple filterable :disabled="!!row.result?.document" aria-label="主动选择条目"><el-option v-for="option in options" :key="option.value" v-bind="option"></el-option></el-select></el-form-item>
            </el-form>
            <details><summary>表的作用与完整依据 · {{row.entries.length}} 项</summary><div v-for="item in row.tableRoles" :key="item.table" class="kw-dependency-row"><strong>{{item.table}}</strong><span>{{item.role}}</span></div><div v-for="entry in row.entries" :key="entry.table+'/'+entry.id" class="kw-dependency-row"><span>{{entry.object}}{{entry.field?'.'+entry.field:''}} · {{entry.name}}</span><span>{{core(row).includes(entry.table+'/'+entry.id)?'主动选择':'必要依赖：'+requiredBy(row,entry)}} · r{{entry.revision}}</span></div></details>
            <p v-if="row.dependencies.length" class="kw-dependency-note">必要依赖随所选治理条目补齐。移除需先收窄用途，或返回治理调整依赖定义。</p>
            <template v-if="!row.result?.document&&!row.existingId"><el-button @click="split(row)">拆分建议</el-button><el-button link @click="remove(row)">移除建议</el-button></template>
          </div>
          <div v-if="row.result?.document" class="kw-plan-result"><span>{{row.result.reused?'已定位原文档':'草稿已生成'}}</span><el-button link @click="open(row)">{{row.operation==='核对更新'?'核对更新':'查看文档'}}</el-button></div>
          <p v-else-if="row.result?.error" role="alert" class="ds-notice is-danger">{{row.result.error}}</p>
        </article>
        <div v-if="!rows.length" class="empty-state"><p>当前源暂无已确认用途，请先完成治理定义。</p></div>
        <p v-if="checked" role="status" class="kw-plan-checked">已核对 {{rows.filter(row=>row.operation!=='暂不处理').length}} 项 · {{rows.filter(row=>row.issue&&row.operation!=='暂不处理').length}} 项待补充</p>`
    });
    const actions = footer(root, [button('核对范围', () => controller?.check(), true, 'plan-check'), button('生成草稿', () => controller?.submit(), false, 'plan-submit', '请先核对范围')]);
    actions.prepend(el('span', 'kw-plan-summary'));
  }
  function basis(content, entries, bindings = []) {
    const host = el('details', 'kw-section kw-section-basis'), roots = new Set(bindings.map(ref => ref.table + '/' + ref.entry));
    host.append(el('summary', '', '治理依据与范围 · ' + entries.length + ' 项')); content.append(host);
    host.append(RecordTable.render({ caption: '治理依据', columns: [{ key: 'object', label: '来源对象 / 条目', width: 240 }, { key: 'scope', label: '范围来源', width: 100 }, { key: 'revision', label: '治理修订', width: 100 }, { key: 'reviewer', label: '确认人', width: 130 }, { key: 'actions', label: '操作', width: 120 }], items: entries.map((entry, index) => ({ ...entry, rowId: index })), row: entry => [
      stack(entry.object + (entry.field ? '.' + entry.field : ''), entry.name), roots.size ? roots.has(model.key(entry)) ? '主动选择' : '必要依赖' : '发布引用', 'r' + entry.revision, entry.reviewer || '—', button('查看依据', trigger => {
        const modal = ui.modal(entry.object + ' / ' + entry.name, trigger, true), body = el('div', 'dialog-body');
        body.append(paragraph(Object.entries(entry.content).filter(([, value]) => value).map(([name, value]) => (GovernanceDemo.contentSections(entry.kind).find(item => item.key === name)?.label || name) + '：' + value).join('\n')));
        (entry.references || []).forEach(ref => { if (allowed('business-notes.view')) body.append(button(ref.title + ' · r' + ref.revision, target => {
          if (!ref.simulated) return DataSourcesPage.viewGovernanceReference(main, ui, ref, target);
          const evidence = ui.modal(ref.title + ' · r' + ref.revision, target, true); evidence.append(el('div', 'dialog-body kw-history-copy', ref.content)); evidence.showModal();
        }), el('blockquote', '', ref.excerpt)); });
        if (allowed('governance.view') && !entry.simulated) body.append(button('查看当前治理条目', () => { modal.close(); GovernancePage.open(source.id, entry.object, { tab: entry.kind, field: entry.field || entry.id, from: 'knowledge' }); }));
        modal.append(body); modal.showModal();
      }, false, 'basis-' + entry.table + '-' + entry.id)
    ] }));
    return host;
  }
  function replacementForm(doc, trigger) {
    const candidates = [...model.documents.values()].filter(item => item.source === source.id && item.id !== doc.id && !item.replacedBy && !item.cancelledReplacement && !item.pendingReplacement);
    const modal = ui.modal('合并或拆分文档', trigger), host = el('div', 'kw-modal-form'); modal.append(host);
    let controller;
    const original = JSON.stringify({ mode: 'split', others: [], drafts: { split: [{ title: '', purpose: '' }, { title: '', purpose: '' }], merge: [{ title: '', purpose: '' }] } });
    PrototypeUI.mount(host, {
      data: () => ({ ...JSON.parse(original), candidates: candidates.map(doc => ({ value: doc.id, label: doc.title })), error: '', field: '', popupTarget: modal, closing: false }),
      computed: { targets() { return this.drafts[this.mode]; } },
      mounted() { controller = this; editor = { hash: location.hash, origin: currentOrigin(), dialog: modal, dirty: () => this.dirty(), discard: () => modal.close() }; },
      methods: {
        dirty() { return JSON.stringify({ mode: this.mode, others: this.others, drafts: this.drafts }) !== original; },
        async cancel() { if (!this.dirty()) { modal.close(); return; } if (this.closing) return; this.closing = true;
          try { await ElementPlus.ElMessageBox.confirm('尚有未保存的目标文档。', '放弃本次调整？', { confirmButtonText: '继续编辑', cancelButtonText: '放弃修改', distinguishCancelAndClose: true, closeOnClickModal: false, appendTo: modal, customClass: 'prototype-ui' }); }
          catch(action) { if (action === 'cancel') modal.close(); } finally { this.closing = false; }
        },
        async submit() {
          this.error = ''; this.field = '';
          if (this.mode === 'merge' && !this.others.length) { this.error = '请选择一同合并的文档。'; this.field = 'others'; }
          const index = this.targets.findIndex(row => !row.title.trim() || !row.purpose.trim());
          if (!this.error && index >= 0) { this.error = '请填写每份目标文档的名称和用途。'; this.field = 'target-' + index; }
          if (this.error) { await this.$nextTick(); host.querySelector('[data-replacement-field="' + this.field + '"] input')?.focus(); return; }
          const ids = [doc.id, ...(this.mode === 'merge' ? this.others : [])], originals = ids.map(docId => model.documents.get(docId));
          const bindings = [...new Map(originals.flatMap(item => item.bindings).map(ref => [ref.table + '/' + ref.entry, ref])).values()];
          const result = model.createReplacement(ids, this.targets.map(row => ({ title: row.title.trim(), purpose: row.purpose.trim(), conditions: '', questions: [], excluded: [], kind: '业务说明', bindings })), ui.currentUser);
          if (result.error) { this.error = result.error; return; }
          modal.close(); ui.notify('目标草稿已创建'); navigate(route('knowledge', 'replace', result.replacement.id));
        }
      },
      template: `<div class="dialog-body"><p class="kw-dependency-note">原文档保留，目标草稿核对后同批替代。发布前可取消。</p><p v-if="error" role="alert" class="ds-notice is-danger">{{error}}</p><el-form label-position="top" @submit.prevent="submit"><el-form-item label="处理方式"><el-radio-group v-model="mode" aria-label="处理方式"><el-radio-button value="split">拆分文档</el-radio-button><el-radio-button value="merge" :disabled="!candidates.length">合并文档</el-radio-button></el-radio-group></el-form-item><el-form-item v-if="mode==='merge'" label="一同合并的文档" required data-replacement-field="others" :error="field==='others'?error:undefined"><el-select v-model="others" multiple filterable :append-to="popupTarget" aria-label="一同合并的文档"><el-option v-for="option in candidates" :key="option.value" v-bind="option"></el-option></el-select></el-form-item><section v-for="(target,index) in targets" :key="mode+index" class="kw-target-fields" :data-replacement-field="'target-'+index"><div class="kw-target-heading"><strong>目标文档 {{index+1}}</strong><el-button v-if="mode==='split'&&targets.length>2" link @click="targets.splice(index,1)">移除</el-button></div><el-form-item label="文档名称" required :error="field==='target-'+index&&!target.title.trim()?error:undefined"><el-input v-model="target.title" :aria-label="'目标文档'+(index+1)+'名称'"></el-input></el-form-item><el-form-item label="用途" required :error="field==='target-'+index&&!target.purpose.trim()?error:undefined"><el-input v-model="target.purpose" :aria-label="'目标文档'+(index+1)+'用途'"></el-input></el-form-item></section><el-button v-if="mode==='split'" @click="targets.push({title:'',purpose:''})">增加目标文档</el-button></el-form></div><footer class="kw-modal-actions"><el-button @click="cancel">取消</el-button><el-button type="primary" @click="submit">创建目标草稿</el-button></footer>`
    });
    modal.addEventListener('close', () => { if (editor?.dialog === modal) editor = null; }, { once: true });
    modal.closeGuard = () => controller?.cancel(); modal.showModal();
  }
  function replacementPage(replacement) {
    const { root, content, header } = shell('合并拆分'); header.append(tag(replacement.state));
    block(content, '来源文档', ...replacement.sources.map(docId => button(model.documents.get(docId).title + ' · 原稿保留', () => navigate(route('knowledge', docId)))));
    const targets = block(content, '目标草稿');
    targets.append(RecordTable.render({ caption: '替代目标', columns: [{ key: 'title', label: '文档 / 用途', width: 280 }, { key: 'revision', label: '整理修订', width: 100 }, { key: 'state', label: '确认状态', width: 110 }, { key: 'actions', label: '操作', width: 120 }], items: replacement.targets.map(docId => model.documents.get(docId)), row: doc => [stack(doc.title, doc.draft.purpose), 'r' + doc.draft.revision, tag(doc.draft.confirmed ? '已确认' : '草稿'), button('核对目标', () => navigate(route('knowledge', doc.id)), false, 'replacement-target-' + doc.id)] }));
    const incoming = model.replacementReferences(replacement), node = incoming.length ? block(content, '引用去向', ...incoming.map(item => paragraph(item.title + ' 引用了原文档'))) : el('div');
    const host = el('div', 'prototype-ui kw-form'); node.append(host);
    const redirects = Object.fromEntries(Object.entries(replacement.redirects).map(([key,value]) => [key, Array.isArray(value)?[...value]:value?[value]:[]]));
    let controller, original = JSON.stringify(redirects), expected = JSON.stringify(replacement.redirects);
    if (incoming.length) PrototypeUI.mount(host, {
      data: () => ({ redirects: structuredClone(redirects), sources: replacement.sources.filter(docId => incoming.some(item => item.sources.includes(docId))).map(docId => ({ id: docId, title: model.documents.get(docId).title })), targets: replacement.targets.map(docId => ({ value: docId, label: model.documents.get(docId).title })), editable: replacement.state === '整理中' && allowed('knowledge.edit') }),
      mounted() { controller = this; editor = { hash: location.hash, origin: currentOrigin(), dirty: () => JSON.stringify(this.redirects) !== original }; },
      methods: { save() { const issue = model.saveReplacement(replacement, JSON.parse(JSON.stringify(this.redirects)), ui.currentUser, expected); if (issue) { ui.notify(issue); return issue; } original = JSON.stringify(replacement.redirects); expected = original; ui.notify('引用去向已保存'); } },
      template: `<el-form label-position="top"><el-form-item v-for="item in sources" :key="item.id" :label="item.title+'的引用去向'"><el-select v-model="redirects[item.id]" multiple :aria-label="item.title+'的引用去向'" :disabled="!editable" clearable><el-option v-for="option in targets" :key="option.value" v-bind="option"></el-option></el-select></el-form-item></el-form>`
    });
    block(content, '发布范围', paragraph('目标内容全部通过验证后生效，原文档在同批退出。其他文档共享的治理事实继续保留。'));
    footer(root, replacement.state === '整理中' ? [allowed('knowledge.edit') && button('取消合并拆分', trigger => confirmAction('取消合并拆分', [], {}, () => { const issue = model.cancelReplacement(replacement, ui.currentUser); if (!issue) { editor = null; refresh(); } return issue; }, trigger, { danger: true, note: '原稿及原正式版本保留；目标人工草稿保留供回看。' })), incoming.length && allowed('knowledge.edit') && button('保存引用去向', () => controller?.save()),
      allowed('releases.build') && button('发起整批发布', () => { if (allowed('knowledge.edit') && controller?.save()) return; const result = model.createTask(source.id, replacement.targets, ui.currentUser); if (result.error) return ui.notify(result.error); editor = null; navigate(route('releases', result.task.id)); }, true)] : []);
  }
  // 长文按章节持续编辑；切换章节只换可见区，不重建输入，不把来源变化静默合入人工正文。
  function documentEditor(doc, mode, root, content) {
    const proposalParts = mode.split('~'), proposal = proposalParts[0] === 'proposal' && model.plans.get(proposalParts[1])?.rows.find(row => row.id === proposalParts[2] && row.result?.document === doc.id)?.result.proposal;
    const initialDetails = model.responsibility(doc), details = { ...initialDetails, ...(proposal || {}) }, revision = doc.draft.revision;
    const originalSections = model.clone(doc.draft.sections?.length ? doc.draft.sections : [{ title: '知识正文', text: doc.draft.note, citations: doc.draft.entries.map(entry => ({ table: entry.table, entry: entry.id })) }]);
    const initial = { title: details.title, purpose: details.purpose, conditions: details.conditions, questions: details.questions.join('\n'), excluded: details.excluded.join('\n'), bindings: details.bindings.map(ref => ref.table + '/' + ref.entry) };
    const chapters = originalSections.map(part => ({ title: part.title, text: part.text, citations: part.citations.map(ref => ref.table + '/' + ref.entry) }));
    const original = JSON.stringify({ value: initial, chapters }), host = el('div', 'kw-editor-layout prototype-ui'); content.classList.add('kw-editor-content'); content.append(host);
    const coreOptions = DataSourceDemo.objects(source.id).flatMap(table => GovernanceDemo.get(table).items.filter(entry => entry.review === '已确认').map(entry => ({ value: table.id + '/' + entry.id, label: GovernanceDemo.objectName(table) + ' / ' + entry.name })));
    let controller;
    PrototypeUI.mount(host, {
      data: () => ({ value: structuredClone(initial), chapters: structuredClone(chapters), selected: mode === 'merge' ? 'chapter0' : 'info', error: '', errorField: '', scope: null, scopeDirty: true, sourceChanged: mode === 'merge' || model.needsUpdate(doc), sourceBasis: '', suggestions: [], coreOptions,
        fields: [{ key: 'title', label: '文档名称', required: true }, { key: 'purpose', label: '用途', required: true }, { key: 'conditions', label: '适用条件' }, { key: 'questions', label: '代表性问题', rows: 3 }, { key: 'excluded', label: '不支持的内容', rows: 3 }], proposal: Boolean(proposal) }),
      mounted() { controller = this; editor = { hash: location.hash, origin: currentOrigin(), dirty: () => this.proposal || JSON.stringify({ value: this.value, chapters: this.chapters }) !== original, submit: action => this.submit(action) }; this.checkScope(false); },
      methods: {
        metadata() { return { title: this.value.title.trim(), purpose: this.value.purpose.trim(), conditions: this.value.conditions.trim(), questions: this.value.questions.split('\n').filter(Boolean), excluded: this.value.excluded.split('\n').filter(Boolean), bindings: this.value.bindings.map(value => { const index = value.lastIndexOf('/'); return { table: value.slice(0,index), entry: value.slice(index+1) }; }) }; },
        changed(scope = false) { this.error = ''; this.errorField = ''; if (scope) this.scopeDirty = true; syncSaveAction(); },
        async fail(message, panel, field) { this.error = message; this.errorField = field || ''; this.selected = panel; await this.$nextTick(); const node = field && host.querySelector('[data-editor-field="' + field + '"] input,[data-editor-field="' + field + '"] textarea'); (node || host.querySelector('[role="alert"]'))?.focus(); },
        checkScope(report = true) {
          const metadata = this.metadata(), current = model.live(doc);
          this.scope = doc.simulated ? { ...current, basis: model.stamp(current.entries), dependencies: [], controls: [], tables: [] } : model.inspectSpec({ source: doc.source, ...metadata });
          if (this.scope.issue) { this.scopeDirty = true; if (report) this.fail(this.scope.issue, 'scope'); return false; }
          this.sourceChanged = this.sourceChanged || model.needsUpdate(doc); this.sourceBasis = model.stamp(current.entries);
          this.suggestions = model.sectionsFor(this.scope.entries, metadata); this.scopeDirty = false;
          if (report) { this.error = ''; ui.notify('范围与确切治理修订已核对'); } return true;
        },
        adopt(index) { const suggested = this.suggestions.find(part => part.title === this.chapters[index].title); if (suggested) { this.chapters[index].text = suggested.text; this.chapters[index].citations = suggested.citations.map(ref => ref.table + '/' + ref.entry); this.changed(); } },
        async submit(action) {
          for (const field of this.fields.filter(field => field.required)) if (!this.value[field.key]?.trim()) return this.fail('请填写' + field.label + '。', 'info', field.key);
          if (this.scopeDirty || !this.scope) return this.fail('范围已调整，请先核对完整依据。', 'scope');
          const metadata = this.metadata(), entries = this.scope.entries;
          for (let index = 0; index < this.chapters.length; index++) {
            const part = this.chapters[index];
            if (action === 'confirm' && !part.text.trim()) return this.fail('请补齐本节正文。', 'chapter' + index, 'chapter' + index);
            if (part.citations.some(ref => !entries.some(entry => model.key(entry) === ref)) || action === 'confirm' && !part.citations.length) return this.fail('请核对本节实际使用的治理依据。', 'chapter' + index, 'refs' + index);
          }
          const sections = this.chapters.map(part => ({ title: part.title, text: part.text.trim(), citations: entries.filter(entry => part.citations.includes(model.key(entry))).map(entry => ({ table: entry.table, entry: entry.id, revision: entry.revision, objectId: entry.objectId, generation: entry.generation })) }));
          const oldPrefix = initialDetails.purpose + '。' + initialDetails.conditions + '\n\n';
          if (sections[0]?.text.startsWith(oldPrefix)) sections[0].text = metadata.purpose + '。' + metadata.conditions + '\n\n' + sections[0].text.slice(oldPrefix.length);
          const note = sections.map(part => part.title + '\n' + part.text).join('\n\n'); metadata.sections = sections;
          let issue;
          const changed = editor?.dirty();
          if (this.sourceChanged) issue = model.applyUpdate(doc, note, ui.currentUser, this.sourceBasis, revision, metadata, this.scope.basis);
          else if (changed) issue = model.saveDocument(doc, note, ui.currentUser, revision, metadata, this.scope.basis);
          if (!issue && action === 'confirm' && !doc.draft.confirmed) issue = model.confirmDocument(doc, ui.currentUser, true);
          if (issue) return this.fail(issue, this.selected);
          ui.notify(action === 'confirm' ? '文档修订已确认' : '草稿已保存'); finishEditing(route('knowledge', doc.id));
        }
      },
      template: `<nav class="kw-editor-nav" aria-label="文档编辑章节"><button type="button" :aria-current="selected==='info'?'page':undefined" :class="{'is-active':selected==='info'}" @click="selected='info'">文档职责</button><button type="button" :aria-current="selected==='scope'?'page':undefined" :class="{'is-active':selected==='scope'}" @click="selected='scope'">治理范围<span v-if="scopeDirty">待核对</span></button><div class="kw-nav-divider"></div><button v-for="(part,index) in chapters" :key="index" type="button" :aria-current="selected==='chapter'+index?'page':undefined" :class="{'is-active':selected==='chapter'+index}" @click="selected='chapter'+index">{{part.title}}</button></nav>
      <div class="kw-editor-pane"><p v-if="proposal" class="ds-notice">本次带入规划调整，保存前原文档保持原修订。</p><p v-if="error" id="kw-editor-error" role="alert" tabindex="-1" class="ds-notice is-danger">{{error}}</p>
        <el-form v-if="selected==='info'" label-position="top" @submit.prevent><el-form-item v-for="field in fields" :key="field.key" :label="field.label" :required="field.required" :data-editor-field="field.key" :error="errorField===field.key?error:undefined"><el-input v-model="value[field.key]" :aria-label="field.label" :aria-describedby="errorField===field.key?'kw-editor-error':undefined" :type="field.rows?'textarea':'text'" :rows="field.rows" @input="changed(true)"></el-input></el-form-item></el-form>
        <section v-else-if="selected==='scope'"><h2>主动选择与必要依赖</h2><el-form label-position="top" @submit.prevent><el-form-item label="主动选择条目" required><el-select v-model="value.bindings" multiple filterable collapse-tags collapse-tags-tooltip :max-collapse-tags="3" aria-label="主动选择条目" @change="changed(true)"><el-option v-for="option in coreOptions" :key="option.value" v-bind="option"></el-option></el-select></el-form-item></el-form><el-button @click="checkScope()">核对范围</el-button><p v-if="scopeDirty" class="kw-dependency-note">待核对当前范围</p><p v-else class="kw-dependency-note">已固定 {{scope.entries.length}} 项依据 · 必要依赖 {{scope.dependencies.length}} 项</p><div v-if="scope" class="kw-scope-list"><div v-for="entry in scope.entries" :key="entry.table+'/'+entry.id" class="kw-dependency-row"><span>{{entry.object}}{{entry.field?'.'+entry.field:''}} · {{entry.name}}</span><span>{{value.bindings.includes(entry.table+'/'+entry.id)?'主动选择':'必要依赖'}} · r{{entry.revision}}</span></div></div></section>
        <template v-else><section v-for="(part,index) in chapters" :key="index" v-show="selected==='chapter'+index"><h2>{{part.title}}</h2><details v-if="sourceChanged" class="kw-source-comparison"><summary>查看新治理内容</summary><p class="ds-prose">{{suggestions.find(item=>item.title===part.title)?.text || '当前范围无此章节'}}</p><el-button @click="adopt(index)" :disabled="scopeDirty">采用本节建议</el-button></details><el-form label-position="top" @submit.prevent><el-form-item label="正文" :data-editor-field="'chapter'+index" :error="errorField==='chapter'+index?error:undefined"><el-input v-model="part.text" type="textarea" :rows="12" :aria-label="part.title+'正文'" @input="changed()"></el-input></el-form-item><el-form-item label="本节治理依据" :data-editor-field="'refs'+index" :error="errorField==='refs'+index?error:undefined"><el-select v-model="part.citations" multiple filterable collapse-tags collapse-tags-tooltip :max-collapse-tags="2" :aria-label="part.title+'的治理依据'" @change="changed()"><el-option v-for="entry in scope?.entries || []" :key="entry.table+'/'+entry.id" :value="entry.table+'/'+entry.id" :label="entry.object+(entry.field?'.'+entry.field:'')+' / '+entry.name+' r'+entry.revision"></el-option></el-select></el-form-item></el-form></section></template>
      </div>`
    });
    footer(root, [button('取消编辑', () => back(route('knowledge', doc.id))), button('保存草稿', () => controller?.submit('save')), allowed('knowledge.confirm') && button('保存并确认', () => controller?.submit('confirm'), true)]);
  }
  function documentReader(doc, content, formal = false) {
    const snapshot = formal ? model.active.get(doc.id).snapshot : doc.draft, viewKey = doc.id + (formal ? '/formal' : '/draft');
    if (!documentViews.has(viewKey)) documentViews.set(viewKey, { tab: 'body', chapter: 0 });
    const view = documentViews.get(viewKey), tabs = el('div', 'kw-document-tabs prototype-ui'), pane = el('div', 'kw-document-view'); content.append(tabs, pane);
    function render() {
      pane.replaceChildren(); pane.className = 'kw-document-view' + (view.tab === 'body' ? ' kw-editor-layout' : ' kw-editor-pane');
      if (view.tab === 'body') {
        const chapters = snapshot.sections?.length ? snapshot.sections : [{ title: '知识正文', text: snapshot.note, citations: [] }];
        const navigation = el('nav', 'kw-editor-nav'), reading = el('div', 'kw-editor-pane kw-reading-pane'); navigation.setAttribute('aria-label', '知识正文章节'); pane.append(navigation, reading);
        const names = [...chapters.map(part => part.title), ...(snapshot.questions?.length ? ['代表性问题'] : []), ...(!formal && allowed('test-cases.view') ? ['相关标准题'] : [])];
        view.chapter = Math.min(view.chapter, names.length - 1);
        names.forEach((name, index) => { const control = el('button', index === view.chapter ? 'is-active' : '', name); control.type = 'button'; if (index === view.chapter) control.setAttribute('aria-current', 'page'); control.onclick = () => { view.chapter = index; render(); pane.querySelectorAll('.kw-editor-nav button')[index]?.focus(); }; navigation.append(control); });
        const part = chapters[view.chapter];
        if (part) {
          const node = block(reading, part.title, paragraph(part.text));
          if (part.citations?.length) { const refs = el('details', 'kw-section-basis'); refs.append(el('summary', '', '本节治理依据 · ' + part.citations.length + ' 项')); part.citations.forEach(ref => { const entry = snapshot.entries.find(entry => model.key(entry) === ref.table + '/' + ref.entry); refs.append(paragraph((entry?.object || ref.table) + ' / ' + (entry?.name || ref.entry) + ' · r' + ref.revision)); }); node.append(refs); }
        } else if (names[view.chapter] === '代表性问题') block(reading, '代表性问题', ...snapshot.questions.map(paragraph));
        else { const tests = model.relatedCases([doc.id]); block(reading, '相关标准题', paragraph(tests.length ? tests.length + ' 道题 · 已审核 ' + tests.filter(test => test.review === '已审核' && !model.caseStale(test)).length + ' 道' : '尚无相关标准题'), button('管理相关题单', () => navigate(route('test-cases', 'for-document', doc.id)), true, 'document-cases')); }
      } else if (view.tab === 'basis') {
        basis(pane, snapshot.entries, snapshot.bindings || doc.bindings).open = true;
        if (snapshot.excluded?.length || doc.excluded.length) block(pane, '未纳入内容', paragraph((snapshot.excluded || doc.excluded).join('\n')));
      } else {
        if (doc.revisions.length) block(pane, '修订记录', ...doc.revisions.slice().reverse().map(version => button('r' + version.revision + (version.confirmed ? ' · 已确认' : ' · 草稿'), trigger => { const modal = ui.modal('文档修订 r' + version.revision, trigger, true); modal.append(el('div', 'dialog-body kw-history-copy', version.note)); modal.showModal(); })));
        const related = [...model.tasks.values()].filter(task => task.documents.includes(doc.id));
        if (related.length) block(pane, '关联发布', ...related.map(task => button(task.id + ' · ' + model.taskFeedback(task).state, () => navigate(route('releases', task.id)))));
        if (!doc.revisions.length && !related.length) block(pane, '修订与发布', paragraph('当前为首次整理，尚无历史修订或发布任务。'));
      }
    }
    PrototypeUI.mount(tabs, { data: () => ({ selected: view.tab }), methods: { change(value) { view.tab = value; render(); } }, template: '<el-tabs v-model="selected" @tab-change="change"><el-tab-pane name="body" label="正文"></el-tab-pane><el-tab-pane name="basis" label="范围与依据"></el-tab-pane><el-tab-pane name="history" label="修订与发布"></el-tab-pane></el-tabs>' });
    content.classList.add('kw-reading-content'); render();
  }
  function documentDetail(doc, mode) {
    const published = model.active.get(doc.id), formal = mode === 'published' && Boolean(published);
    const { root, content, header } = shell(formal ? published.snapshot.title : doc.title, '知识文档', route('knowledge'));
    header.append(tag(formal ? '正式 r' + published.snapshot.revision + ' · ' + model.activeState(published) : doc.draft.confirmed ? '已确认 r' + doc.draft.revision : '草稿 r' + doc.draft.revision));
    if (doc.simulated) header.append(tag('流程演示'));
    if (doc.pendingReplacement) block(content, '合并拆分', paragraph('原稿保留，目标草稿待核对。'), button('继续合并拆分', () => navigate(route('knowledge', 'replace', doc.pendingReplacement))));
    if (doc.replacedBy) content.append(el('p', 'ds-notice', '已被目标文档替代；原正文与历史保留。'));
    if (published && !['edit', 'merge'].includes(mode) && !mode?.startsWith('proposal~')) {
      header.append(button(formal ? '查看整理稿 r' + doc.draft.revision : '查看正式 r' + published.snapshot.revision, () => navigate(route('knowledge', doc.id, formal ? '' : 'published')), false, 'document-version'));
    }
    if (model.needsUpdate(doc) && mode !== 'merge') content.append(el('p', 'ds-notice', '来源依据已变化，请核对差异后更新文档。'));
    if (mode === 'edit' || mode === 'merge' || mode?.startsWith('proposal~')) {
      if (!allowed('knowledge.edit')) { content.append(el('p', 'ds-notice is-danger', '当前账号没有编辑文档的操作权限。')); footer(root, [button('返回文档', () => back(route('knowledge', doc.id)))]); return; }
      return documentEditor(doc, mode, root, content);
    }
    documentReader(doc, content, formal);
    if (formal) { footer(root, [button('查看当前版本', () => navigate(route('releases', 'active', doc.id)), true)]); return; }
    const actions = [allowed('knowledge.edit') && button(model.needsUpdate(doc) ? '核对并更新' : '编辑文档', () => navigate(route('knowledge', doc.id, model.needsUpdate(doc) ? 'merge' : 'edit')), !doc.draft.confirmed)];
    if (!doc.draft.confirmed && allowed('knowledge.confirm')) actions.push(button('确认文档', trigger => confirmAction('确认文档与治理依据', [], {}, () => model.confirmDocument(doc, ui.currentUser, true), trigger, { note: '确认已逐节核对正文与确切治理依据，未新增算法、关联或状态含义。', saved: () => refresh(), label: '确认当前修订' }), true, 'confirm-doc', model.needsUpdate(doc) ? '请先核对来源更新。' : ''));
    if (!doc.pendingReplacement && !doc.replacedBy && !doc.cancelledReplacement && allowed('knowledge.edit') && allowed('knowledge.generate')) actions.push(button('合并或拆分', trigger => replacementForm(doc, trigger)));
    if (doc.draft.confirmed && allowed('releases.build')) {
      const published = model.active.get(doc.id);
      actions.push(published?.snapshot.revision === doc.draft.revision ? button('查看当前生效', () => navigate(route('releases', 'active', doc.id)), true) : button('发起发布', trigger => createTask(trigger, [doc.id]), true, 'publish-doc', model.needsUpdate(doc) ? '请先核对来源更新。' : ''));
    }
    footer(root, actions, model.needsUpdate(doc) ? '治理依据已变化，原候选成绩不能沿用。' : '');
  }
  function openTaskCases(task) { navigate(route('test-cases', 'for-task', task.id)); }
  function caseList() {
    const rows = [...model.cases.values()].filter(test => test.source === source.id && (!caseTask || test.documents.some(docId => caseTask.documents.includes(docId))) && (!caseDocument || test.documents.includes(caseDocument.id))).map(test => ({ ...test, searchObject: test.documents.map(docId => model.documents.get(docId)?.title).join(' '), listState: model.caseStale(test) && test.review === '已审核' ? '待复核' : test.review }));
    if (caseTask) rows.sort((a, b) => Number(a.listState === '已审核') - Number(b.listState === '已审核'));
    listView('cases', rows, [{ key: 'title', label: '标准问题', width: 270 }, { key: 'object', label: '关联文档', width: 200 }, { key: 'revision', label: '修订', width: 80 }, { key: 'state', label: '审核状态', width: 100 }, { key: 'actions', label: '操作', width: 120 }], test => [
      stack(test.question, test.id), test.searchObject, 'r' + test.revision, tag(test.listState), button('查看题目', () => navigate(route('test-cases', test.id)), false, 'case-' + test.id)
    ], ['草稿', '已审核', '待复核', '已停用'], [allowed('test-cases.edit') && button('新增标准题', () => navigate(route('test-cases', 'new')), true),
      allowed('test-cases.edit') && button('生成候选题', trigger => suggestCase(trigger, caseTask))], [{ key: 'keyword', label: '标准问题' }, { key: 'object', label: '关联文档' }]);
  }
  function suggestCase(trigger, task) {
    const docs = [...model.documents.values()].filter(doc => doc.source === source.id && (!task || task.documents.includes(doc.id)) && (!caseDocument || doc.id === caseDocument.id));
    if (task) docs.sort((a, b) => Number(model.relatedCases([a.id]).some(test => test.review === '已审核' && !model.caseStale(test))) - Number(model.relatedCases([b.id]).some(test => test.review === '已审核' && !model.caseStale(test))));
    if (!docs.length) return ui.notify('请先生成当前数据源的知识文档。');
    confirmAction('生成候选题', [{ key: 'document', label: '依据文档', options: docs.map(doc => ({ value: doc.id, label: doc.title })), required: true }], { document: docs[0].id }, value => {
      const doc = model.documents.get(value.document), result = model.suggestCases(doc, ui.currentUser);
      if (result.error) return result.error;
      if (result.tests.length === 1) navigate(route('test-cases', result.tests[0].id, 'edit'));
      else { ui.notify('已生成 ' + result.tests.length + ' 道候选题，请逐题核对并审核'); refresh(); }
    }, trigger, { label: '生成草稿' });
  }
  function caseDetail(test, mode) {
    const isNew = !test, { root, content, header } = shell(isNew ? '新增标准题' : test.question, '标准题单', route('test-cases'));
    const viewingRevision = test?.revision, viewingDependencies = test && model.questionDependencies(test.documents);
    if (test) header.append(tag(test.review));
    const originParts = currentOrigin()?.hash?.slice(1).split('/') || [], fromTask = originParts[0] === 'test-cases' && originParts[2] === 'for-task' && model.tasks.get(originParts[3]);
    const fromDocument = originParts[0] === 'test-cases' && originParts[2] === 'for-document' && model.documents.get(originParts[3]);
    const docIds = fromTask?.documents || (fromDocument ? [fromDocument.id] : null);
    const docs = [...model.documents.values()].filter(doc => doc.source === source.id && (!isNew || !docIds || docIds.includes(doc.id)));
    if (isNew || mode === 'edit') {
      const expectedRevision = test?.revision;
      const value = test ? { question: test.question, documents: test.documents, type: test.type, expected: test.expected, basis: test.basis, rule: test.rule, identity: test.identity, tolerance: String(test.tolerance || 0) } :
        { question: '', documents: docIds?.length === 1 ? [...docIds] : [], type: '数值', expected: '', basis: '', rule: '', identity: '', tolerance: '0' };
      longForm(content, value, [
        { key: 'question', label: '标准问题', type: 'textarea', required: true }, { key: 'documents', label: '关联文档', multiple: true, options: docs.map(doc => ({ value: doc.id, label: doc.title })), required: true },
        { key: 'type', label: '判定方式', options: ['数值', '规则', '说明'].map(value => ({ value, label: value })), required: true }, { key: 'expected', label: '预期行为或答案', type: 'textarea', reviewRequired: true },
        { key: 'basis', label: '答案及数据依据', type: 'textarea', reviewRequired: true }, { key: 'rule', label: '判定规则', type: 'textarea', reviewRequired: true }, { key: 'identity', label: '受控执行身份', reviewRequired: true }, { key: 'tolerance', label: '数值允许误差' }
      ].map(field => ({ ...field, group: ['question','documents','type'].includes(field.key) ? '问题与范围' : '预期与判定' })), (data, action) => {
        const next = { ...data, source: source.id };
        if (action === 'confirm') { const issue = model.caseIssue(next); if (issue) return issue; }
        const result = model.saveCase(test, next, ui.currentUser, expectedRevision); if (result.error) return result.error;
        if (action === 'confirm') { const issue = model.reviewCase(result.test, ui.currentUser); if (issue) return issue; }
        ui.notify(action === 'confirm' ? '标准题已审核' : '题目草稿已保存'); finishEditing(route('test-cases', result.test.id));
      });
      const sample = test && model.sampleData(test.documents); if (sample) header.append(button('查看验证数据', trigger => { const modal = ui.modal('固定验证数据', trigger, true), body = el('div', 'dialog-body'); sampleTable(body, sample); modal.append(body); modal.showModal(); }));
      footer(root, [button('取消', () => back(route('test-cases'))), button('保存草稿', () => editor?.submit('save')), allowed('test-cases.review') && button('保存并审核', () => editor?.submit('confirm'), true)]); return;
    }
    block(content, '问题与预期', paragraph(test.question), paragraph('预期：' + (test.expected || '尚未补充')));
    block(content, '判定依据', paragraph(test.basis || '尚未填写数据依据'), paragraph('判定方式：' + test.type + '\n规则：' + (test.rule || '尚未填写') + '\n执行身份：' + (test.identity || '尚未填写') + '\n允许误差：' + (test.tolerance || 0)));
    const sample = model.sampleData(test.documents); if (sample) sampleTable(content, sample);
    block(content, '关联文档', ...test.documents.map(docId => button(model.documents.get(docId)?.title || docId, () => navigate(route('knowledge', docId)))));
    if (model.caseStale(test)) content.append(el('p', 'ds-notice', '关联依据已变化，请复核预期和判定规则。'));
    if (test.events.length) { const history = block(content, '审核与修订记录'); test.events.forEach(event => history.append(paragraph(event.action + ' · ' + event.actor + ' · ' + event.at + '\n' + event.reason))); }
    if (test.history.length) block(content, '以往题目修订', ...test.history.map(version => button('r' + version.revision + ' · ' + version.review, trigger => {
      const modal = ui.modal('标准题修订 r' + version.revision, trigger, true); modal.append(el('div', 'dialog-body kw-history-copy', version.question + '\n预期：' + version.expected + '\n依据：' + version.basis + '\n规则：' + version.rule)); modal.showModal();
    })));
    const executions = [...model.tasks.values()].flatMap(task => task.candidates.flatMap(candidate => candidate.runs.filter(run => run.results.some(row => row.test === test.id)).map(run => ({ task, candidate, run }))));
    if (executions.length) block(content, '关联运行记录', ...executions.map(({ task, candidate, run }) => button(task.id + ' / ' + candidate.id + ' / ' + run.id + ' · ' + run.result, trigger => {
      const modal = ui.modal('标准题运行记录', trigger, true), result = run.results.find(row => row.test === test.id);
      modal.append(el('div', 'dialog-body kw-history-copy', candidate.id + ' · ' + run.id + '\n' + result.question + '\n预期：' + result.expected + '\n实际：' + (result.error || String(result.actual ?? '未取得结果')) + '\n判定：' + result.result + '\n依据：' + result.basis)); modal.showModal();
    })));
    footer(root, [allowed('test-cases.edit') && button('修订题目', () => navigate(route('test-cases', test.id, 'edit'))), allowed('test-cases.edit') && test.review !== '已停用' && button('停用', trigger => confirmAction('停用标准题', [{ key: 'reason', label: '停用原因', type: 'textarea', required: true, full: true }], { reason: '' }, value => model.stopCase(test, ui.currentUser, value.reason), trigger, { danger: true, note: '相关发布缺少必要题目时将被阻断。' })),
      allowed('test-cases.review') && test.review !== '已停用' && (test.review !== '已审核' || model.caseStale(test)) && button('审核题目', () => execute(() => model.reviewCase(test, ui.currentUser, viewingRevision, viewingDependencies), '题目已审核'), true)]);
  }
  function sampleTable(content, sample) {
    const panel = block(content, '固定验证数据 · ' + sample.id + ' r' + sample.revision);
    if (sample.products) panel.append(RecordTable.render({ caption: '商品档案固定样例', columns: [{ key: 'id', label: '商品ID', width: 110 }, { key: 'code', label: '商品编码', width: 120 }, { key: 'status', label: '原始状态编码', width: 140 }], items: sample.products, row: row => [row.id, row.code, row.status] }));
    else panel.append(RecordTable.render({ caption: '销售数量固定样例', columns: [{ key: 'id', label: '销售明细', width: 110 }, { key: 'product', label: '商品ID', width: 110 }, { key: 'code', label: '商品编码', width: 120 }, { key: 'quantity', label: '原销售数量', width: 140 }, { key: 'unit', label: '单位', width: 90 }], items: sample.rows, row: row => [row.id, row.product, row.code, row.quantity, row.unit] }));
    panel.append(paragraph('原型使用此固定样例验证，尚未连接真实查询服务。'));
  }
  function actualResult(result) {
    const value = result.error || String(result.actual ?? '未取得结果'), node = el('div', 'kw-result-actual');
    node.append(el('p', 'kw-result-preview', value), el('small', 'cell-sub', result.rule));
    if (value.length > 120) node.append(button('查看完整结果', trigger => {
      const modal = ui.modal('实际结果 · ' + result.question, trigger, true);
      modal.append(el('div', 'dialog-body kw-history-copy', result.question + '\n\n预期：' + result.expected + '\n\n实际：' + value + '\n\n判定规则：' + result.rule + '\n依据：' + result.basis)); modal.showModal();
    }));
    return node;
  }
  function createTask(trigger, selected = [], changing = null) {
    const docs = [...model.documents.values()].filter(doc => doc.source === source.id && doc.draft.confirmed && !model.needsUpdate(doc) && (changing || selected.includes(doc.id) || model.active.get(doc.id)?.snapshot.revision !== doc.draft.revision));
    if (!docs.length) return ui.notify('请先确认依赖完整的知识文档。');
    let task;
    ui.openForm({ title: changing ? '调整发布范围' : '创建发布任务', trigger, value: { documents: selected }, fields: [{ key: 'documents', label: '本次发布文档', options: docs.map(doc => ({ value: doc.id, label: doc.title + ' · r' + doc.draft.revision })), multiple: true, required: true, full: true }],
      summary: true, previewTitle: '核对本批完整范围', previewLabel: '核对范围', saveLabel: changing ? '确认调整' : '创建任务', previewContentLabel: '目标修订',
      validate: value => value.documents.length ? '' : '请选择至少一份文档。', preview: value => model.expandDocuments(value.documents).map(docId => { const doc = model.documents.get(docId), current = model.active.get(docId); return { label: doc.title, after: (current ? 'r' + current.snapshot.revision + ' → ' : '首次发布 → ') + 'r' + doc.draft.revision }; }),
      save: value => { if (changing) { const issue = model.changeScope(changing, value.documents, ui.currentUser); task = changing; return issue; } const result = model.createTask(source.id, value.documents, ui.currentUser); task = result.task; return result.error; }, onSaved: () => navigate(route('releases', task.id)), success: changing ? '范围已调整，请重新构建' : '发布任务已创建' });
  }
  function taskList() {
    const rows = [...model.tasks.values()].filter(task => task.source === source.id).map(task => { const feedback = model.taskFeedback(task); return { ...task, title: task.id, searchObject: task.documents.map(docId => model.documents.get(docId)?.title).join('、'), listState: feedback.state, issue: feedback.issue }; }).reverse();
    listView('tasks', rows, [{ key: 'title', label: '发布任务 / 范围', width: 300 }, { key: 'candidate', label: '固定候选', width: 130 }, { key: 'state', label: '阶段', width: 120 }, { key: 'at', label: '创建时间', width: 160 }, { key: 'actions', label: '操作', width: 120 }], task => [
      stack(task.id, task.searchObject), task.currentId || '尚未构建', [tag(task.listState), el('small', 'cell-sub', task.issue)], task.at, button('查看任务', () => navigate(route('releases', task.id)), false, 'task-' + task.id)
    ], ['准备中', '构建失败', '待验证', '待裁定', '验证未通过', '执行异常', '待发布', '被阻断', '结果待核实', '已发布', '验证已完成', '已取消'], [allowed('releases.build') && button('创建发布任务', trigger => createTask(trigger), true)], [{ key: 'keyword', label: '发布编号' }, { key: 'object', label: '发布文档' }]);
  }
  async function progress(task, start, finish, autoTest = false) {
    const actor = ui.currentUser, view = '#releases/' + task.source + '/' + encodeURIComponent(task.id), error = model[start](task, actor); if (error) return ui.notify(error);
    const expectedId = task.currentId;
    refreshTask(view); await new Promise(resolve => setTimeout(resolve, 450));
    model[finish](task, actor, expectedId); refreshTask(view);
    if (autoTest && task.stage === '待验证' && !model.candidateIssue(task)) progress(task, 'run', 'finishRun');
  }
  function taskDetail(task) {
    const { root, content, header } = shell(task.id, '发布与版本', route('releases'));
    const feedback = model.taskFeedback(task), current = model.candidate(task);
    header.append(tag(feedback.state));
    if (task.stage === '已发布') {
      const states = [...new Set(task.documents.map(docId => model.releaseStatus(task, docId).state))];
      if (states.some(state => state !== '当前生效')) header.append(tag(states.length === 1 ? states[0] : '部分已被替代'));
    }
    if (feedback.expired) header.append(tag('验证依据已失效'));
    if (task.kind === '恢复验证') header.append(tag('恢复验证'));
    const steps = el('div', 'kw-steps prototype-ui'), stages = task.kind === '恢复验证' ? ['条件核对', '隔离验证', '验证完成'] : ['范围核对', '候选构建', '相关验证', '发布检查', '切换完成'];
    const phase = task.kind === '恢复验证' ? task.stage === '验证已完成' ? 3 : task.stage === '待验证' ? 0 : 1 : { '构建失败': 1, '待验证': 2, '待裁定': 2, '验证未通过': 2, '执行异常': 2, '切换中': 3, '结果待核实': 3, '已发布': 5 }[task.stage];
    if (task.stage !== '已取消') {
      const phaseIndex = phase ?? ({ '准备中': 0, '构建中': 1, '验证中': 2, '待发布': 3, '被阻断': current ? 2 : 0 }[task.stage] || 0);
      PrototypeUI.mount(steps, { data: () => ({ index: phaseIndex, stages, process: feedback.issue || ['构建失败', '验证未通过', '执行异常'].includes(task.stage) ? 'error' : 'process' }), template: '<el-steps :active="index" :process-status="process" finish-status="success" simple><el-step v-for="stage in stages" :key="stage" :title="stage"></el-step></el-steps>' }); content.append(steps);
    } else block(content, '任务已取消', paragraph(task.events.findLast(event => event.action === '取消发布任务')?.reason || '本任务已取消，未切换正式入口。'));
    const scope = block(content, '本批文档与修订');
    scope.append(RecordTable.render({ caption: '本批完整范围', columns: [{ key: 'title', label: '文档', width: 220 }, { key: 'before', label: '发布前', width: 110 }, { key: 'after', label: '目标修订', width: 110 }, { key: 'reason', label: '纳入依据', width: 140 }, { key: 'actions', label: '操作', width: 120 }],
      items: task.documents.map(docId => model.documents.get(docId)), row: doc => { const fixed = current?.documents.find(item => item.id === doc.id), previous = current ? current.baselines[doc.id] : model.active.get(doc.id) ? '/r' + model.active.get(doc.id).snapshot.revision : '';
        return [fixed?.title || doc.title, previous ? 'r' + previous.match(/\/r(\d+)/)?.[1] : '未发布', 'r' + (fixed?.revision || doc.draft.revision), task.selected.includes(doc.id) ? '本次选择' : '必要协同', button('查看文档', () => navigate(route('knowledge', doc.id)), false, 'scope-' + doc.id)]; } }));
    if (task.replacement) {
      const replacement = current?.replacement || model.replacements.get(task.replacement);
      const exits = block(content, '同批退出的原文档', ...replacement.sources.map(docId => paragraph(model.documents.get(docId).title + ' → ' + replacement.targets.map(target => model.documents.get(target).title).join('、'))));
      exits.append(button('核对替代与引用去向', () => navigate(route('knowledge', 'replace', replacement.id))));
    }
    if (current) {
      scope.append(paragraph('固定候选：' + current.id + ' · 构建' + (current.built ? '已完成' : '尚未完成')));
      const tests = block(content, '候选验证');
      const execution = current.runs.at(-1);
      if (!execution) tests.append(paragraph('尚未运行验证'));
      else {
        tests.append(paragraph(execution.id + ' · ' + execution.at + ' · ' + execution.result));
        tests.append(RecordTable.render({ caption: '逐题结果', columns: [{ key: 'question', label: '标准问题', width: 230 }, { key: 'expected', label: '预期', width: 160 }, { key: 'actual', label: '实际及依据', width: 220 }, { key: 'state', label: '结果', width: 100 }, { key: 'actions', label: '操作', width: 120 }], items: execution.results, row: result => [
          stack(result.question, result.test + ' · r' + result.revision), el('p', 'kw-result-preview', result.expected), actualResult(result), tag(result.result), result.result === '待裁定' && allowed('test-cases.judge') ? button('办理裁定', trigger => confirmAction('裁定候选结果', [
            { key: 'question', label: '标准问题', readonly: true, full: true }, { key: 'expected', label: '预期', readonly: true, full: true }, { key: 'actual', label: '实际结果', readonly: true, full: true },
            { key: 'decision', label: '裁定结论', options: ['通过', '未通过'].map(value => ({ value, label: value })), required: true }, { key: 'reason', label: '裁定依据', type: 'textarea', required: true, full: true }
          ], { question: result.question, expected: result.expected, actual: result.error || String(result.actual ?? '未取得结果'), decision: '未通过', reason: '' }, value => model.adjudicate(task, result.test, value.decision, value.reason, ui.currentUser, execution.id), trigger), false, 'judge-' + result.test) : button('查看题目', () => navigate(route('test-cases', result.test)), false, 'test-' + result.test)
        ] }));
      }
      if (current.runs.length > 1) block(content, '以往运行', ...current.runs.slice(0, -1).map(run => paragraph(run.id + ' · ' + run.result + ' · ' + run.at)));
      if (current.sample) sampleTable(content, current.sample);
    }
    const issue = feedback.issue;
    if (task.stage === '已发布') {
      const result = block(content, '发布结果与当前版本');
      result.append(RecordTable.render({ caption: '本次发布与当前生效', columns: [{ key: 'title', label: '文档', width: 220 }, { key: 'published', label: '本次发布', width: 110 }, { key: 'active', label: '当前生效', width: 110 }, { key: 'state', label: '后续状态', width: 140 }],
        items: task.documents.map(docId => ({ id: docId, ...model.releaseStatus(task, docId) })), row: status => [model.documents.get(status.id).title, 'r' + status.revision, status.current ? 'r' + status.current : '暂无', tag(status.state)] }));
    }
    if (task.stage === '验证已完成') block(content, '恢复验证结果', paragraph(feedback.expired ? '本次运行曾通过；当前依据已变化，需要重新核验后才能恢复。' : '隔离验证已通过，生产限制仍保持，等待有权人员显式恢复。'));
    if (task.candidates.length > 1) block(content, '历史候选', ...task.candidates.filter(item => item !== current).map(item => paragraph(item.id + ' · ' + item.documents.map(doc => doc.title + ' r' + doc.revision).join('、') + ' · ' + (item.runs.at(-1)?.result || '未验证'))));
    const history = block(content, '处理记录'); task.events.forEach(event => history.append(paragraph(event.action + ' · ' + event.actor + ' · ' + event.at + '\n' + event.reason)));
    const busy = ['构建中', '验证中', '切换中'].includes(task.stage), finished = ['已发布', '已取消', '验证已完成'].includes(task.stage), actions = [];
    if (issue && !busy) {
      if (feedback.next === 'cases' && allowed('test-cases.view')) actions.push(button('处理相关题单', () => openTaskCases(task), true, 'task-cases'));
      if (feedback.next === 'document' && allowed('knowledge.view')) actions.push(button('处理文档', () => navigate(route('knowledge', feedback.document)), true, 'task-document'));
      if (feedback.next === 'source' && allowed('sources.view')) actions.push(button('检查数据源', () => navigate('#sources'), true));
      if (task.kind === '恢复验证' && feedback.next === 'restart' && allowed('releases.test')) actions.push(button('重新发起恢复验证', () => {
        const result = model.restartRecovery(task, ui.currentUser); if (result.error) return ui.notify(result.error);
        navigate(route('releases', result.task.id));
      }, true, 'restart-recovery'));
    }
    if (!finished && !busy && task.stage !== '结果待核实') {
      if (allowed('releases.build') && task.kind !== '恢复验证') actions.push(button('调整范围', trigger => createTask(trigger, task.selected, task)));
      if (allowed('releases.build') && task.kind !== '恢复验证' && (!current || feedback.next === 'build' || !issue)) actions.push(button(current ? '重新构建候选' : '构建候选', () => progress(task, 'build', 'finishBuild', true), !current || feedback.next === 'build' || task.stage === '构建失败', 'build', model.prepareIssue(task)));
      if (current?.built && allowed('releases.test') && !issue) actions.push(button(current.runs.length ? '重新运行验证' : '运行验证', () => progress(task, 'run', 'finishRun'), task.stage !== '待发布', 'test'));
      if (allowed('releases.publish') && task.stage === '待发布' && !issue) actions.push(button('确认发布', trigger => confirmAction('确认整批发布', [
        { key: 'scope', label: '本批范围', readonly: true, full: true }, { key: 'candidate', label: '固定候选', readonly: true }, { key: 'result', label: '验证结果', readonly: true }
      ], { scope: current.documents.map(doc => doc.title + ' r' + doc.revision).join('\n') + (current.replacement ? '\n同批退出：' + current.replacement.sources.map(docId => model.documents.get(docId).title).join('、') : ''), candidate: current.id, result: current.runs.at(-1)?.result }, () => {
        const error = model.publish(task, ui.currentUser); if (error) return error;
        const expectedId = task.currentId, view = location.hash, actor = ui.currentUser;
        setTimeout(() => { model.finishPublish(task, actor, expectedId); refreshTask(view); }, 450);
      }, trigger, { label: '确认发布', note: '本批整体切换；仍存在的禁止与暂停保持生效。' }), true, 'publish', issue));
      if (allowed('releases.build')) actions.unshift(button('取消任务', trigger => confirmAction('取消发布任务', [{ key: 'reason', label: '取消原因', type: 'textarea', required: true, full: true }], { reason: '' }, value => model.cancel(task, ui.currentUser, value.reason), trigger, { danger: true })));
    }
    if (task.stage === '结果待核实' && allowed('releases.publish')) actions.push(button('核实实际生效入口', () => execute(() => model.reconcile(task, ui.currentUser), '已核对实际入口'), true));
    if (['构建中', '验证中'].includes(task.stage) && allowed('releases.build')) actions.push(button('取消任务', trigger => confirmAction('取消未生效任务', [{ key: 'reason', label: '取消原因', type: 'textarea', required: true, full: true }], { reason: '' }, value => model.cancel(task, ui.currentUser, value.reason), trigger, { danger: true, note: '本任务尚未切换正式入口，取消后保留现有版本。' })));
    if (task.stage === '已发布') actions.push(button('查看当前生效', () => navigate(task.documents.length === 1 ? route('releases', 'active', task.documents[0]) : route('releases', 'active-batch', task.id)), true));
    if (task.stage === '验证已完成') actions.push(button('返回恢复核对', () => navigate(route('releases', 'active', task.recoveryDocument)), !feedback.expired));
    if (task.stage === '已取消') actions.push(button('返回发布任务', () => navigate(route('releases')), true));
    footer(root, actions, busy ? task.stage + '，请稍候。' : issue);
  }
  function activeList() {
    const rows = [...model.active.values()].filter(row => model.documents.get(row.document)?.source === source.id && (!versionTask || versionTask.documents.includes(row.document))).map(row => ({ ...row, id: row.document, title: row.snapshot.title,
      searchObject: row.snapshot.entries.map(entry => entry.object).join(' '), listState: model.activeState(row) }));
    listView('active', rows, [{ key: 'title', label: '文档 / 来源对象', width: 260 }, { key: 'revision', label: '生效修订', width: 100 }, { key: 'release', label: '来源发布', width: 150 }, { key: 'state', label: '当前可用性', width: 120 }, { key: 'actions', label: '操作', width: 120 }], row => [
      stack(row.title, [...new Set(row.snapshot.entries.map(entry => entry.object))].join('、')), 'r' + row.snapshot.revision, row.release, tag(row.listState), button('查看版本', () => navigate(route('releases', 'active', row.document)), false, 'active-' + row.document)
    ], ['正常', '待复核', '部分暂停', '暂停', '来源不可用', '已撤下'], [], [{ key: 'keyword', label: '文档名称' }, { key: 'object', label: '来源对象' }]);
  }
  function activeDetail(row) {
    const doc = model.documents.get(row.document), { root, content, header } = shell(row.snapshot.title + ' · 当前 r' + row.snapshot.revision, '发布与版本', route('releases', 'active'));
    header.append(tag(model.activeState(row)));
    block(content, '正式版本', paragraph(row.snapshot.purpose || row.snapshot.title), paragraph('发布批次：' + row.release), button('阅读正式正文', () => navigate(route('knowledge', doc.id, 'published')), true, 'read-published')); basis(content, row.snapshot.entries, row.snapshot.bindings || doc.bindings);
    const restrictions = model.restrictions(row); if (restrictions.length) block(content, '暂停范围与原因', ...restrictions.map(paragraph));
    if (row.previous) block(content, '直接上一版', paragraph('r' + row.previous.snapshot.revision + ' · ' + row.previous.release), button('查看上一版正文', trigger => { const modal = ui.modal('上一版 r' + row.previous.snapshot.revision, trigger, true); modal.append(el('div', 'dialog-body kw-history-copy', row.previous.snapshot.note)); modal.showModal(); }));
    else block(content, '直接上一版', paragraph('首次发布，没有可回退的上一版。'));
    const recovery = model.recoveryFeedback(row);
    if (row.recovery) block(content, '恢复验证', tag(recovery.state), paragraph(row.recovery.at), ...(row.recovery.task ? [button('查看验证记录', () => navigate(route('releases', row.recovery.task)))] : []));
    if (row.events.length) { const history = block(content, '版本与恢复记录'); row.events.forEach(event => history.append(paragraph(event.action + ' · ' + event.actor + ' · ' + event.at + '\n' + event.reason))); }
    const release = model.releases.get(row.release), rollbackIssue = model.rollbackIssue(release);
    const actions = [button('查看文档修订', () => navigate(route('knowledge', doc.id)))];
    if (allowed('versions.pause') && !restrictions.length && !row.withdrawn) actions.push(button('暂停知识', trigger => confirmAction('暂停知识', [{ key: 'scope', label: '暂停范围', readonly: true, full: true }, { key: 'reason', label: '暂停原因', type: 'textarea', required: true, full: true }], { scope: doc.title, reason: '' }, value => model.pause(row, value.reason, ui.currentUser), trigger, { danger: true, note: '暂停后，受影响知识不能继续用于问数。' })));
    if (restrictions.length || row.withdrawn) {
      if (allowed('releases.test')) actions.push(button(recovery.state === '已失效' ? '重新运行恢复验证' : '运行恢复验证', () => {
        const result = model.createRecoveryTask(row, ui.currentUser); if (result.error) return ui.notify(result.error);
        navigate(route('releases', result.task.id)); setTimeout(() => progress(result.task, 'run', 'finishRun'), 0);
      }, !recovery.ready, 'verify-recovery', recovery.eligibility));
      if (allowed('governance.allow') && recovery.ready) actions.push(button('确认恢复', trigger => confirmAction('核对并恢复知识', [{ key: 'scope', label: '恢复范围', readonly: true, full: true }, { key: 'reason', label: '风险已消除的依据', type: 'textarea', required: true, full: true }], { scope: doc.title + '\n' + restrictions.join('\n'), reason: '' }, value => model.recover(row, ui.currentUser, value.reason), trigger, { danger: true, note: '只恢复本次核对通过的范围；提交时重新核验来源、修订和验证依据。' }), true, 'recover'));
    }
    if (allowed('releases.withdraw') && !row.withdrawn) actions.unshift(button('撤下知识', trigger => confirmAction('撤下知识及必要依赖', [{ key: 'scope', label: '完整撤下范围', readonly: true, full: true }, { key: 'reason', label: '撤下原因', type: 'textarea', required: true, full: true }], { scope: model.withdrawalScope(row.document).map(docId => model.documents.get(docId).title).join('\n'), reason: '' }, value => model.withdraw(row, ui.currentUser, value.reason), trigger, { danger: true, note: '停止本次范围的新请求和受影响的未完成查询；历史记录保留。' })));
    if (allowed('versions.rollback')) actions.push(button('回退整批上一版', trigger => confirmAction('整批回退预检', [{ key: 'scope', label: '完整回退范围', readonly: true, full: true }, { key: 'reason', label: '回退原因', type: 'textarea', required: true, full: true }], { scope: release.targets.map(target => target.after.title + ' → r' + target.before?.snapshot.revision).join('\n'), reason: '' }, value => model.rollback(release, ui.currentUser, value.reason), trigger, { danger: true, label: '确认整批回退', note: '回退保留当前权限、禁止和暂停状态。' }), false, 'rollback', rollbackIssue));
    footer(root, actions, restrictions.length || row.withdrawn ? recovery.issue : '');
  }
  function open(impact) {
    navigate('#knowledge/' + impact.source + '/impact-' + impact.id);
  }
  function impactDetail(impact) {
    const { root, content } = shell(impact.title, '知识文档', route('knowledge'));
    const scope = GovernanceDemo.publicationScope(impact.source, impact.bindings, { allowBlocked: true, allowRetired: true });
    block(content, '已审核的来源范围', ...scope.entries.map(({ table, entry }) => paragraph(GovernanceDemo.objectName(table) + ' / ' + entry.name + ' · r' + entry.revision)));
    const affected = model.affectedDocuments(impact);
    if (affected.length) {
      const host = block(content, '全部受影响文档');
      affected.forEach(({ document: doc, versions }) => { const row = el('div', 'kw-impact-row'); row.append(stack(doc.title, [...new Set(versions.map(version => version.kind))].join('、')), tag(model.needsUpdate(doc) ? '待核对更新' : model.active.has(doc.id) ? model.activeState(model.active.get(doc.id)) : '草稿'),
        button('核对文档', () => navigate(route('knowledge', doc.id, model.needsUpdate(doc) ? 'merge' : ''))));
        if (allowed('knowledge.confirm') && !model.needsUpdate(doc)) row.append(button('核对无影响', trigger => confirmAction('记录无影响核对', [{ key: 'reason', label: '核对依据', required: true, type: 'textarea', full: true }], { reason: '' }, value => model.noImpact(impact, doc, ui.currentUser, value.reason), trigger, { saved: () => refresh() }))); host.append(row); });
      footer(root, [button('返回知识文档', () => back(route('knowledge')))], impact.knowledge || '分别核对全部实际影响，必要处理完成后回写来源事项。');
    } else footer(root, [allowed('knowledge.generate') && button('规划知识文档', trigger => generateDocuments(trigger), true, 'impact-generate', scope.issues[0] || '')], scope.issues[0] || '');
  }
  function render(target, controls, area, sourceId, targetId, mode) {
    main = target; ui = controls; section = area; source = DataSourceDemo.find(sourceId || DataSourceDemo.selected);
    if (!source || !allowed(area + '.view') || (area === 'releases' && !allowed('knowledge.view'))) { main.replaceChildren(ui.pageHeading('不可访问'), el('p', 'ds-notice is-danger', '当前账号没有此页面或知识内容查看权限，或找不到数据源。')); return; }
    syncOrigin(); caseTask = null; caseDocument = null; versionTask = null;
    if (editor && editor.hash !== location.hash) editor = null;
    DataSourceDemo.selected = source.id;
    if (area === 'knowledge') {
      if (!targetId) return documentList();
      if (targetId === 'plan') { const plan = model.plans.get(mode); if (plan?.source === source.id) return planningPage(plan); }
      if (targetId === 'replace') { const replacement = model.replacements.get(mode); if (replacement?.source === source.id) return replacementPage(replacement); }
      if (targetId.startsWith('impact-')) { const impact = GovernanceImpactDemo.records.find(item => item.id === targetId.slice(7) && item.source === source.id); if (impact) return impactDetail(impact); }
      const doc = model.documents.get(targetId); if (doc?.source === source.id) return documentDetail(doc, mode);
    }
    if (area === 'test-cases') {
      if (!targetId) return caseList();
      if (targetId === 'for-task') {
        const task = model.tasks.get(mode); if (task?.source === source.id) { caseTask = task; return caseList(); }
      }
      if (targetId === 'for-document') {
        const doc = model.documents.get(mode); if (doc?.source === source.id) { caseDocument = doc; return caseList(); }
      }
      const test = model.cases.get(targetId); if (targetId === 'new' && allowed('test-cases.edit')) return caseDetail(null, 'edit');
      if (test?.source === source.id) return caseDetail(test, mode);
    }
    if (area === 'releases') {
      if (!targetId || targetId === 'tasks') return taskList();
      if (targetId === 'active-batch') { const task = model.tasks.get(mode); if (task?.source === source.id) { versionTask = task; return activeList(); } }
      if (targetId === 'active') { const row = model.active.get(mode); return row && model.documents.get(row.document)?.source === source.id ? activeDetail(row) : activeList(); }
      const task = model.tasks.get(targetId); if (task?.source === source.id) return taskDetail(task);
    }
    main.replaceChildren(ui.pageHeading('记录不可访问'), el('p', 'ds-notice', '记录不存在或不属于当前数据源。'), button('返回' + titleOf(area), () => navigate(route(area), false, null), true));
  }
  window.KnowledgeUpdatePage = { render, open };
})();
