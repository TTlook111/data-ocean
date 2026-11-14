/* 三个任务入口共用后台控件；长正文在独立页编辑，发布及恢复操作各自核权。 */
(() => {
  'use strict';
  const model = KnowledgeWorkspace, queries = new Map(), origins = new Map();
  let main, ui, source, section, editor = null, allowNavigation = false, pendingNavigation = null, caseTask = null, caseDocument = null, versionTask = null;
  const el = (tag, cls, text) => ui.el(tag, cls, text);
  const allowed = ability => model.has(ui.currentUser, ability);
  const route = (area, target = '', extra = '') => '#' + area + '/' + source.id + (target ? '/' + encodeURIComponent(target) : '') + (extra ? '/' + encodeURIComponent(extra) : '');
  const titleOf = area => ({ knowledge: '知识文档', 'test-cases': '标准题单', releases: '发布与版本' }[area]);
  const focusLater = selector => requestAnimationFrame(() => requestAnimationFrame(() => main?.querySelector(selector)?.focus({ preventScroll: true })));
  const currentOrigin = () => history.state?.kwOrigin || origins.get(location.hash);
  function captureView() {
    return { hash: location.hash, previous: currentOrigin() || null, top: main.scrollTop, focus: document.activeElement?.dataset.focus,
      scrolls: [...main.querySelectorAll('.rt-scroll,.kw-content')].map(node => ({ top: node.scrollTop, left: node.scrollLeft })) };
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
    if (area === 'knowledge') return target ? '文档详情' : '知识文档';
    if (area === 'test-cases') return ['for-task', 'for-document'].includes(target) ? '相关标准题' : target ? '题目详情' : '标准题单';
    if (area === 'releases') return target === 'active' ? detail ? '版本详情' : '当前生效' : target === 'active-batch' ? '本批生效版本' : target && target !== 'tasks' ? '发布任务' : '发布与版本';
    return ({ governance: '治理页面', catalog: '元数据目录', collections: '采集记录', 'business-notes': '企业业务说明' })[area];
  }
  function refresh(focus = '[data-primary]') {
    window.dispatchEvent(new Event('hashchange')); focusLater(focus);
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
      [...main.querySelectorAll('.rt-scroll,.kw-content')].forEach((node, index) => { node.scrollTop = origin?.scrolls?.[index]?.top || 0; node.scrollLeft = origin?.scrolls?.[index]?.left || 0; });
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
    ElementPlus.ElMessageBox.confirm('尚有未保存的修改。放弃后将丢失本次输入。', '放弃当前修改？', { confirmButtonText: '继续编辑', cancelButtonText: '放弃修改', distinguishCancelAndClose: true, closeOnClickModal: false, closeOnHashChange: false, customClass: 'prototype-ui' })
      .catch(action => { if (action === 'cancel') { editor = null; allowNavigation = true; navigate(destination, false, destinationOrigin); setTimeout(() => { allowNavigation = false; }, 0); } });
  }, true);
  window.addEventListener('beforeunload', event => { if (editor?.dirty()) { event.preventDefault(); event.returnValue = ''; } });
  function longForm(content, initial, fields, commit) {
    const host = el('div', 'kw-form prototype-ui'), original = JSON.stringify(initial); content.append(host);
    PrototypeUI.mount(host, {
      data: () => ({ value: structuredClone(initial), fields, error: '', reviewSubmit: false }),
      mounted() { const vm = this; editor = { hash: location.hash, origin: currentOrigin(), dirty: () => JSON.stringify(vm.value) !== original, submit: action => vm.submit(action) }; },
      watch: { value: { deep: true, handler() { this.error = ''; syncSaveAction(); } } },
      methods: { async submit(action) {
        this.error = ''; this.reviewSubmit = action === 'confirm'; await this.$nextTick(); const valid = await this.$refs.form.validate().catch(() => false);
        if (!valid) { host.querySelector('.el-form-item.is-error input,.el-form-item.is-error textarea')?.focus(); return; }
        const message = commit(JSON.parse(JSON.stringify(this.value)), action);
        if (message) { this.error = message; await this.$nextTick(); host.querySelector('[role="alert"]')?.focus(); }
      } },
      template: `<el-form ref="form" :model="value" label-position="top" scroll-to-error @submit.prevent="submit('save')">
        <p v-if="error" role="alert" tabindex="-1" class="ds-notice is-danger">{{error}}</p>
        <el-form-item v-for="field in fields" :key="field.key" :label="field.label" :prop="field.key" :rules="(field.required || (reviewSubmit && field.reviewRequired)) ? [{required:true,message:'请填写'+field.label,trigger:'blur'}] : []">
          <el-select v-if="field.options" v-model="value[field.key]" :multiple="field.multiple" :aria-label="field.label" filterable><el-option v-for="option in field.options" :key="option.value" v-bind="option"></el-option></el-select>
          <el-input v-else v-model="value[field.key]" :aria-label="field.label" :type="field.type || 'text'" :rows="field.rows || 3" :maxlength="field.maxlength || 50000" :placeholder="field.placeholder || ''"></el-input>
        </el-form-item></el-form>`
    });
  }
  function documentList() {
    const rows = [...model.documents.values()].filter(doc => doc.source === source.id).map(doc => ({ ...doc, searchObject: doc.draft.entries.map(entry => entry.object).join(' '),
      listState: doc.draft.confirmed ? '已确认' : '草稿', listStates: [doc.draft.confirmed ? '已确认' : '草稿', ...(model.needsUpdate(doc) ? ['待更新'] : [])] }));
    const generate = allowed('knowledge.generate') && button('生成知识文档', trigger => generateDocuments(trigger), true, 'generate');
    if (generate && !model.suggested(source.id).length) { generate.disabled = true; generate.title = '当前数据源暂无可生成的已审核内容'; }
    listView('documents', rows, [{ key: 'title', label: '文档 / 来源对象', width: 240 }, { key: 'active', label: '正式修订', width: 110 }, { key: 'draft', label: '整理修订', width: 110 },
      { key: 'state', label: '整理状态', width: 100 }, { key: 'updated', label: '创建时间', width: 160 }, { key: 'actions', label: '操作', width: 120 }], doc => [
      stack(doc.title, [...new Set(doc.draft.entries.map(entry => entry.object))].join('、')), model.active.has(doc.id) ? 'r' + model.active.get(doc.id).snapshot.revision : '未发布', 'r' + doc.draft.revision,
      doc.listStates.map(tag), doc.at, button('查看文档', () => navigate(route('knowledge', doc.id)), false, 'doc-' + doc.id)
    ], ['草稿', '已确认', '待更新'], [generate],
    [{ key: 'keyword', label: '文档名称' }, { key: 'object', label: '来源对象' }]);
  }
  function generateDocuments(trigger, selected = []) {
    const suggestions = model.suggested(source.id);
    if (!suggestions.length) { main.querySelector('.kw-generation-empty')?.focus(); ui.notify('当前源暂无已审核定义，请先完成治理审核。'); return; }
    let results;
    const entryOptions = [...new Map(suggestions.filter(spec => spec.kind === '表说明').flatMap(spec => spec.bindings.map(binding => {
      const entry = model.live({ ...spec, draft: {} }).entries.find(item => item.table === binding.table && item.id === binding.entry);
      return [binding.table + '/' + binding.entry, { value: binding.table + '/' + binding.entry, label: entry.object + ' / ' + entry.name }];
    }))).values()];
    const chosenSpecs = value => value.selected.map(selected => { const spec = suggestions.find(item => item.key === selected); if (spec.kind !== '表说明' || value.entries.includes('all')) return spec;
      return { ...spec, automaticScope: false, bindings: spec.bindings.filter(binding => value.entries.includes(binding.table + '/' + binding.entry)) };
    });
    ui.openForm({ title: '生成知识文档', trigger, fields: [
      { key: 'selected', label: '选择文档范围', options: suggestions.map(spec => ({ value: spec.key, label: spec.title + (spec.existing ? ' · 已有文档' : ''), disabled: Boolean(spec.issue) })), multiple: true, required: true, full: true },
      { key: 'entries', label: '表说明纳入条目', options: [{ value: 'all', label: '全部已审核内容' }, ...entryOptions], multiple: true, required: true, full: true }
    ], value: { selected, entries: ['all'] },
      summary: true, previewTitle: '核对文档及依赖', previewLabel: '核对生成范围', saveLabel: '生成草稿', previewContentLabel: '本次内容',
      validate: value => !value.selected.length ? '请选择至少一项文档范围。' : chosenSpecs(value).some(spec => !spec.bindings.length) ? '所选表说明没有纳入条目，请调整范围。' : '',
      preview: value => chosenSpecs(value).map(spec => { const scope = model.live(spec.existing || { ...spec, draft: {} }); return { label: spec.title, after: (spec.existing ? '定位已有文档，保留人工内容。\n' : '') + scope.entries.map(entry => entry.object + ' / ' + entry.name + ' r' + entry.revision).join('\n') + (spec.excluded.length ? '\n未纳入：' + spec.excluded.join('；') : '') }; }),
      save: value => { const specs = chosenSpecs(value); const issue = specs.map(spec => model.live({ ...spec, draft: {} }).issue).find(Boolean); if (issue) return issue; results = specs.map(spec => model.createDocument(spec, ui.currentUser)); return results.find(result => result.error)?.error; },
      onSaved: () => { if (results?.length === 1) navigate(route('knowledge', results[0].document.id)); }, success: '知识草稿已准备好' });
  }
  function basis(content, entries) {
    const host = block(content, '治理依据与范围');
    host.append(RecordTable.render({ caption: '治理依据', columns: [{ key: 'object', label: '来源对象 / 条目', width: 240 }, { key: 'revision', label: '治理修订', width: 100 }, { key: 'reviewer', label: '审核人', width: 130 }, { key: 'actions', label: '操作', width: 120 }], items: entries.map((entry, index) => ({ ...entry, rowId: index })), row: entry => [
      stack(entry.object + (entry.field ? '.' + entry.field : ''), entry.name), 'r' + entry.revision, entry.reviewer || '—', button('查看依据', trigger => {
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
  }
  function documentDetail(doc, mode) {
    const { root, content, header } = shell(doc.title, '知识文档', route('knowledge'));
    header.append(tag(doc.draft.confirmed ? '已确认 r' + doc.draft.revision : '草稿 r' + doc.draft.revision));
    if (doc.simulated) header.append(tag('流程演示'));
    if (model.active.has(doc.id)) block(content, '正式使用', paragraph('当前修订 r' + model.active.get(doc.id).snapshot.revision + ' · ' + model.activeState(model.active.get(doc.id))));
    if (model.needsUpdate(doc) && mode !== 'merge') content.append(el('p', 'ds-notice', '来源依据已变化，请核对差异后更新文档。'));
    if (mode === 'edit' || mode === 'merge') {
      const preview = mode === 'merge' ? model.updatePreview(doc) : null, revision = doc.draft.revision;
      if (preview?.issue) { content.append(el('p', 'ds-notice is-danger', preview.issue)); footer(root, []); return; }
      if (preview) {
        const compare = el('div', 'kw-comparison');
        for (const [label, text] of [['当前正文', preview.before], ['新治理内容', preview.generated]]) { const part = el('section'); part.append(el('h2', '', label), paragraph(text)); compare.append(part); }
        content.append(compare);
      }
      const initial = { note: preview ? (preview.manual ? preview.before : preview.generated) : doc.draft.note };
      longForm(content, initial, [{ key: 'note', label: preview ? '合并后的知识正文' : '知识正文', type: 'textarea', rows: 18, required: true }], (value, action) => {
        let issue;
        if (preview) issue = model.applyUpdate(doc, value.note, ui.currentUser, model.stamp(preview.entries));
        else if (value.note.trim() !== doc.draft.note) issue = model.saveDocument(doc, value.note, ui.currentUser, revision);
        if (issue) return issue;
        if (action === 'confirm') { issue = model.confirmDocument(doc, ui.currentUser); if (issue) return issue; }
        ui.notify(action === 'confirm' ? '文档已确认' : '草稿已保存'); finishEditing(route('knowledge', doc.id));
      });
      footer(root, [button('取消编辑', () => back(route('knowledge', doc.id))), allowed('knowledge.edit') && button('保存草稿', () => editor?.submit('save')),
        allowed('knowledge.confirm') && button('保存并确认', () => editor?.submit('confirm'), true)]);
      return;
    }
    block(content, '知识正文', paragraph(doc.draft.note)); basis(content, doc.draft.entries);
    if (doc.excluded.length) block(content, '未纳入内容', paragraph(doc.excluded.join('\n')));
    if (allowed('test-cases.view')) {
      const tests = model.relatedCases([doc.id]), related = block(content, '相关标准题', paragraph(tests.length ? tests.length + ' 道题 · 已审核 ' + tests.filter(test => test.review === '已审核' && !model.caseStale(test)).length + ' 道' : '尚未生成相关标准题'));
      related.append(button('管理相关题单', () => navigate(route('test-cases', 'for-document', doc.id)), false, 'document-cases'));
    }
    if (doc.revisions.length) {
      const history = block(content, '修订记录');
      doc.revisions.slice().reverse().forEach(version => history.append(button('r' + version.revision + (version.confirmed ? ' · 已确认' : ' · 草稿'), trigger => { const modal = ui.modal('文档修订 r' + version.revision, trigger, true); modal.append(el('div', 'dialog-body kw-history-copy', version.note)); modal.showModal(); })));
    }
    const related = [...model.tasks.values()].filter(task => task.documents.includes(doc.id));
    if (related.length) block(content, '关联发布', ...related.map(task => button(task.id + ' · ' + model.taskFeedback(task).state, () => navigate(route('releases', task.id)))));
    const actions = [allowed('knowledge.edit') && button(model.needsUpdate(doc) ? '核对并更新' : '编辑文档', () => navigate(route('knowledge', doc.id, model.needsUpdate(doc) ? 'merge' : 'edit')), !doc.draft.confirmed)];
    if (!doc.draft.confirmed && allowed('knowledge.confirm')) actions.push(button('确认文档', () => execute(() => model.confirmDocument(doc, ui.currentUser), '当前文档修订已确认'), true, 'confirm-doc', model.needsUpdate(doc) ? '请先核对来源更新。' : ''));
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
      ], (data, action) => {
        const next = { ...data, source: source.id };
        if (action === 'confirm') { const issue = model.caseIssue(next); if (issue) return issue; }
        const result = model.saveCase(test, next, ui.currentUser, expectedRevision); if (result.error) return result.error;
        if (action === 'confirm') { const issue = model.reviewCase(result.test, ui.currentUser); if (issue) return issue; }
        ui.notify(action === 'confirm' ? '标准题已审核' : '题目草稿已保存'); finishEditing(route('test-cases', result.test.id));
      });
      const sample = test && model.sampleData(test.documents); if (sample) sampleTable(content, sample);
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
      allowed('test-cases.review') && test.review !== '已停用' && (test.review !== '已审核' || model.caseStale(test)) && button('审核题目', () => execute(() => model.reviewCase(test, ui.currentUser), '题目已审核'), true)]);
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
      modal.append(el('div', 'dialog-body kw-history-copy', value + '\n\n判定规则：' + result.rule + '\n依据：' + result.basis)); modal.showModal();
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
        return [doc.title, previous ? 'r' + previous.match(/\/r(\d+)/)?.[1] : '未发布', 'r' + (fixed?.revision || doc.draft.revision), task.selected.includes(doc.id) ? '本次选择' : '必要协同', button('查看文档', () => navigate(route('knowledge', doc.id)), false, 'scope-' + doc.id)]; } }));
    if (current) {
      scope.append(paragraph('固定候选：' + current.id + ' · 构建' + (current.built ? '已完成' : '尚未完成')));
      const tests = block(content, '候选验证');
      const execution = current.runs.at(-1);
      if (!execution) tests.append(paragraph('尚未运行验证'));
      else {
        tests.append(paragraph(execution.id + ' · ' + execution.at + ' · ' + execution.result));
        tests.append(RecordTable.render({ caption: '逐题结果', columns: [{ key: 'question', label: '标准问题', width: 230 }, { key: 'expected', label: '预期', width: 160 }, { key: 'actual', label: '实际及依据', width: 220 }, { key: 'state', label: '结果', width: 100 }, { key: 'actions', label: '操作', width: 120 }], items: execution.results, row: result => [
          stack(result.question, result.test + ' · r' + result.revision), result.expected, actualResult(result), tag(result.result), result.result === '待裁定' && allowed('test-cases.judge') ? button('办理裁定', trigger => confirmAction('裁定候选结果', [
            { key: 'decision', label: '裁定结论', options: ['通过', '未通过'].map(value => ({ value, label: value })), required: true }, { key: 'reason', label: '裁定依据', type: 'textarea', required: true, full: true }
          ], { decision: '未通过', reason: '' }, value => model.adjudicate(task, result.test, value.decision, value.reason, ui.currentUser), trigger), false, 'judge-' + result.test) : button('查看题目', () => navigate(route('test-cases', result.test)), false, 'test-' + result.test)
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
      ], { scope: current.documents.map(doc => doc.title + ' r' + doc.revision).join('\n'), candidate: current.id, result: current.runs.at(-1)?.result }, () => {
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
    const rows = [...model.active.values()].filter(row => model.documents.get(row.document)?.source === source.id && (!versionTask || versionTask.documents.includes(row.document))).map(row => ({ ...row, id: row.document, title: model.documents.get(row.document).title,
      searchObject: row.snapshot.entries.map(entry => entry.object).join(' '), listState: model.activeState(row) }));
    listView('active', rows, [{ key: 'title', label: '文档 / 来源对象', width: 260 }, { key: 'revision', label: '生效修订', width: 100 }, { key: 'release', label: '来源发布', width: 150 }, { key: 'state', label: '当前可用性', width: 120 }, { key: 'actions', label: '操作', width: 120 }], row => [
      stack(row.title, [...new Set(row.snapshot.entries.map(entry => entry.object))].join('、')), 'r' + row.snapshot.revision, row.release, tag(row.listState), button('查看版本', () => navigate(route('releases', 'active', row.document)), false, 'active-' + row.document)
    ], ['正常', '待复核', '部分暂停', '暂停', '来源不可用', '已撤下'], [], [{ key: 'keyword', label: '文档名称' }, { key: 'object', label: '来源对象' }]);
  }
  function activeDetail(row) {
    const doc = model.documents.get(row.document), { root, content, header } = shell(doc.title + ' · 当前 r' + row.snapshot.revision, '发布与版本', route('releases', 'active'));
    header.append(tag(model.activeState(row)));
    block(content, '正式版本', paragraph('发布批次：' + row.release), paragraph(row.snapshot.note)); basis(content, row.snapshot.entries);
    const restrictions = model.restrictions(row); if (restrictions.length) block(content, '暂停范围与原因', ...restrictions.map(paragraph));
    if (row.previous) block(content, '直接上一版', paragraph('r' + row.previous.snapshot.revision + ' · ' + row.previous.release), paragraph(row.previous.snapshot.note));
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
    const doc = [...model.documents.values()].find(item => item.source === impact.source && impact.bindings.every(binding => item.draft.entries.some(entry => model.key(entry) === binding.table + '/' + binding.entry)));
    navigate('#knowledge/' + impact.source + '/' + (doc?.id || 'impact-' + impact.id));
  }
  function impactDetail(impact) {
    const { root, content } = shell(impact.title, '知识文档', route('knowledge'));
    const scope = GovernanceDemo.publicationScope(impact.source, impact.bindings, { allowBlocked: true, allowRetired: true });
    block(content, '已审核的来源范围', ...scope.entries.map(({ table, entry }) => paragraph(GovernanceDemo.objectName(table) + ' / ' + entry.name + ' · r' + entry.revision)));
    footer(root, [allowed('knowledge.generate') && button('生成更新草稿', () => { const result = model.importImpact(impact, ui.currentUser); if (result.error) return ui.notify(result.error); navigate(route('knowledge', result.document.id)); }, true, 'impact-generate', scope.issues[0] || '')], scope.issues[0] || '');
  }
  function render(target, controls, area, sourceId, targetId, mode) {
    main = target; ui = controls; section = area; source = DataSourceDemo.find(sourceId || DataSourceDemo.selected);
    if (!source || !allowed(area + '.view') || (area === 'releases' && !allowed('knowledge.view'))) { main.replaceChildren(ui.pageHeading('不可访问'), el('p', 'ds-notice is-danger', '当前账号没有此页面或知识内容查看权限，或找不到数据源。')); return; }
    syncOrigin(); caseTask = null; caseDocument = null; versionTask = null;
    if (editor && editor.hash !== location.hash) editor = null;
    DataSourceDemo.selected = source.id;
    if (area === 'knowledge') {
      if (!targetId) return documentList();
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
