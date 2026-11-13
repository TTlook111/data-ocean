/* 表是工作入口，表说明、字段、关系和指标各自审核；复用现有后台外壳和组件。 */
(() => {
  'use strict';
  const demo = GovernanceDemo, queries = new Map(), returns = new Map(), itemQueries = new Map();
  const kinds = [['overview', '表说明'], ['fields', '字段'], ['relations', '关联'], ['metrics', '指标'], ['evidence', '状态与依据']];
  let main, ui, route, activeRecord;
  const allowed = ability => SourceApprovalDemo.hasAbility(ui.currentUser, ability);
  const person = id => { const user = PeopleDemo.users.find(user => user.id === id); return user ? user.name + '（' + user.login + '）' : '未指定'; };
  const refresh = () => window.dispatchEvent(new Event('hashchange'));
  const el = (tag, cls, text) => ui.el(tag, cls, text);
  const button = (label, fn, primary = false, key) => ui.button(label, fn, 'admin-button' + (primary ? ' is-primary' : ''), key);
  const action = (label, fn, key) => ui.button(label, fn, 'do-action is-primary', key);
  function badge(text) { return el('span', 'status-tag' + (['待确认', '业务定义待核对', '待治理', '治理中', '知识处理中'].includes(text) ? ' is-pending' : text === '未发布' ? ' is-disabled' : text === '禁止使用' ? ' ds-status-danger' : ''), text); }
  function stack(primary, secondary) { const node = el('div'); node.append(typeof primary === 'string' ? ui.textCell(primary) : primary); if (secondary) node.append(el('small', 'cell-sub', secondary)); return node; }
  function path(source, table, params = {}) { const query = new URLSearchParams(Object.entries(params).filter(([, value]) => value)); return '#governance/' + encodeURIComponent(source) + (table ? '/' + encodeURIComponent(table) : '') + (query.size ? '?' + query : ''); }
  function open(source, object, options = {}) {
    const table = DataSourceDemo.objects(source).find(table => demo.objectName(table) === object);
    if (!table) { ui?.notify('当前目录中找不到对应治理对象。'); return; }
    // 同表的事实疑点先核实；其他对象不受阻，可靠采集差异无需重复人工确认。
    if (DataSourceDemo.pendingVerification(table).length) {
      DataSourcesPage.openChanges(source, object);
      return;
    }
    const currentMain = document.getElementById('admin-main'), scroll = currentMain?.querySelector('.rt-scroll');
    const sameTask=options.impact&&activeRecord&&location.hash.split('?')[0]===path(activeRecord.table.source,demo.objectName(activeRecord.table))&&new URLSearchParams(location.hash.split('?')[1]||'').get('impact')===options.impact;
    returns.set(table.id, sameTask&&returns.get(activeRecord.table.id) || { hash: location.hash, focus: document.activeElement?.dataset.focus, row: [...(scroll?.querySelectorAll('tbody tr') || [])].indexOf(document.activeElement?.closest('tr')), scroll: scroll?.scrollTop || 0 });
    DataSourceDemo.selected = source;
    const from = options.from || (location.hash.startsWith('#catalog') ? 'catalog' : location.hash.startsWith('#collections') ? 'collections' : '');
    const next = path(source, object, { tab: options.tab || (options.field ? 'fields' : ''), field: options.field, impact: options.impact, from });
    if (location.hash === next) refresh(); else location.hash = next;
  }
  function openImpact(impact, options = {}) {
    // 资料尚无映射时也保存真实来源，后续关联和跨表查看沿用同一个返回位置。
    const originKey = 'impact:' + impact.id;
    if (!location.hash.startsWith('#governance')) {
      const scroll = document.getElementById('admin-main')?.querySelector('.rt-scroll');
      returns.set(originKey, { hash: location.hash, focus: document.activeElement?.dataset.focus, scroll: scroll?.scrollTop || 0 });
    }
    demo.prepareImpact(impact);
    const current=new URLSearchParams(location.hash.split('?')[1]||''),from=options.from||(current.get('impact')===impact.id?current.get('from'):'');
    const { table, field, entryId } = demo.impactTarget(impact);
    if (table) {
      open(impact.source, demo.objectName(table), { ...options, from, impact: impact.id, field: field || (entryId !== 'overview' ? entryId : ''), tab: entryId === 'overview' ? 'overview' : entryId?.startsWith('relation:') ? 'relations' : entryId?.startsWith('metric:') ? 'metrics' : 'fields' });
      const origin = returns.get(originKey); if (origin) returns.set(table.id, origin);
    }
    else { DataSourceDemo.selected = impact.source; location.hash = path(impact.source, '', { impact: impact.id, from }); }
  }
  function select(tab, field) {
    route.params.set('tab', tab); if (field) route.params.set('field', field); else route.params.delete('field');
    history.replaceState(null, '', path(route.source.id, demo.objectName(activeRecord.table), Object.fromEntries(route.params)));
    refresh(); requestAnimationFrame(() => main.querySelector(field ? '[data-entry-title]' : '.gov-tabs .is-active')?.focus({ preventScroll: true }));
  }
  function context(source) {
    const host = el('section', 'gov-context prototype-ui');
    PrototypeUI.mount(host, { data: () => ({ selected: source.id, sources: DataSourceDemo.sources }), methods: { change(id) { DataSourceDemo.selected = id; location.hash = path(id); } }, template: '<label id="gov-source-label">当前数据源</label><el-select v-model="selected" aria-labelledby="gov-source-label" filterable @change="change"><el-option v-for="source in sources" :key="source.id" :label="source.name" :value="source.id"></el-option></el-select>' }); return host;
  }
  function openEntry(record, entry, impact) {
    open(record.table.source, demo.objectName(record.table), { tab: entry.kind, field: entry.field || (entry.id !== 'overview' ? entry.id : ''), impact: impact?.id });
  }
  function openTable(table) {
    const record = demo.get(table), work = demo.attention(record);
    openEntry(record, work.affected[0] || work.pending[0] || record.items[0]);
  }
  function workCell(record) {
    const cell = el('div', 'gov-work-cell'), work = demo.attention(record);
    const pending = work.pending.filter(entry => !work.affected.includes(entry));
    if (pending.length) cell.append(action(pending.length + ' 项待补充或审核', () => open(record.table.source, demo.objectName(record.table), { tab: 'evidence' })));
    if (work.knowledge.length) {
      const impact = work.knowledge[0], binding = impact.bindings.find(binding => binding.table === record.table.id);
      cell.append(action('知识处理中 ' + work.knowledge.length + ' 项', () => openEntry(record, record.items.find(entry => entry.id === binding.entry), impact)));
    }
    if (!cell.childNodes.length) cell.append(ui.textCell('—'));
    return cell;
  }
  function impactCell(record) {
    const cell = el('div', 'gov-work-cell'), work = demo.attention(record);
    if (DataSourceDemo.pendingVerification(record.table).length) return ui.textCell('来源事实待核实');
    work.affected.slice(0, 2).forEach(entry => cell.append(action((entry.kind === 'overview' ? '表说明' : entry.name) + ' · 业务定义待核对', () => openEntry(record, entry), 'gov-affected-' + record.table.id + '-' + entry.id)));
    if (work.affected.length > 2) cell.append(action('另 ' + (work.affected.length - 2) + ' 项业务定义待核对', () => open(record.table.source, demo.objectName(record.table), { tab: 'evidence' })));
    if (!cell.childNodes.length) cell.append(ui.textCell('无待核对项'));
    return cell;
  }
  function list(source) {
    if (!queries.has(source.id)) queries.set(source.id, { keyword: '', attention: '', impact: '', status: 'all', page: 1, pageSize: 10 });
    const query = queries.get(source.id), tables = DataSourceDemo.objects(source.id);
    const rows = tables.filter(table => {
      const record = demo.get(table), state = demo.summary(record).state, work = demo.attention(record);
      const matchesWork = !query.attention || (query.attention === 'pending' ? work.pending.some(entry => !work.affected.includes(entry)) : work.knowledge.length);
      const matchesImpact = !query.impact || (query.impact === 'affected' ? work.affected.length : query.impact === 'source' ? DataSourceDemo.pendingVerification(table).length : !work.affected.length && !DataSourceDemo.pendingVerification(table).length);
      return matchesWork && matchesImpact && AdminQuery.matches([demo.objectName(table), table.label, table.comment], query.keyword) && (query.status === 'all' || query.status === state);
    });
    const reset = () => { Object.assign(query, { keyword: '', attention: '', impact: '', status: 'all', page: 1 }); refresh(); };
    const filters = RecordControls.filters({ value: query, includeDates: false, fields: [{ key: 'keyword', label: '表名、业务名称或原注释', placeholder: '表名、业务名称或原注释' }, { key: 'attention', label: '待处理内容', options: [{ value: 'pending', label: '待补充或审核' }, { value: 'knowledge', label: '知识处理中' }] }, { key: 'impact', label: '变更后待核对项', options: [{ value: 'affected', label: '业务定义待核对' }, { value: 'clear', label: '无待核对项' }, { value: 'source', label: '来源事实待核实' }] }], statuses: ['全部治理进度', '待治理', '治理中', '已治理'].map((label, index) => ({ label, value: index ? label : 'all' })), statusLabel: '治理进度', onApply: value => { Object.assign(query, value); refresh(); }, onReset: reset });
    query.page = Math.max(1, Math.min(query.page, Math.ceil(rows.length / query.pageSize)));
    const panel = el('section', 'record-panel'), empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('search'), el('p', '', tables.length ? '没有匹配的治理对象' : '当前数据源尚无已采集对象'));
    if (tables.length) empty.append(button('清空筛选', reset));
    panel.append(RecordTable.render({ caption: '表治理', columns: [{ key: 'name', label: '业务名称 / 表', width: 180 }, { key: 'meaning', label: '表用途 / 原注释', width: 210 }, { key: 'work', label: '待处理内容', width: 160 }, { key: 'impact', label: '变更后待核对项', width: 240 }, { key: 'status', label: '治理进度', width: 190 }, { key: 'publication', label: '知识发布', width: 100 }, { key: 'actions', label: '操作', width: 100, fixed: 'right' }], items: rows.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), empty,
      row: table => { const record = demo.get(table), progress = demo.summary(record); return [stack(ui.button(table.label || demo.objectName(table), () => openTable(table), 'cell-title', 'gov-object-' + table.id), demo.objectName(table)), stack(record.items[0].content.meaning || '尚未填写', '原注释：' + (table.comment || '无注释')), workCell(record), impactCell(record), stack(badge(progress.state), '表说明与字段 ' + progress.total + ' / ' + progress.confirmed), badge('未发布'), action('治理', () => openTable(table), 'gov-open-' + table.id)]; } }),
      RecordControls.pager({ ...query, total: rows.length }, page => { query.page = page; refresh(); }, size => { query.pageSize = size; query.page = 1; refresh(); }));
    // 缺少技术映射的资料留在当前表列表定位；不另建变化清单，也不重复核实来源。
    const unresolved = GovernanceImpactDemo.records.filter(impact => impact.source === source.id && impact.kind === '业务说明' && impact.state !== '已完成' && !impact.bindings.length);
    const selected = GovernanceImpactDemo.records.find(impact => impact.source === source.id && impact.id === route.params.get('impact'));
    if (selected && !demo.impactTarget(selected).table && !unresolved.includes(selected)) unresolved.unshift(selected);
    main.replaceChildren(ui.pageHeading('治理工作台'), context(source), ...unresolved.map(impact => unmappedImpact(impact, impact === selected)), filters, panel);
    restoreList(source, panel);
  }
  function restoreList(source, panel) {
    const back = [...returns.entries()].find(([id, state]) => id.startsWith(source.id + ':') && state.focus && state.hash === location.hash);
    if (back) requestAnimationFrame(() => { const state = back[1]; panel.querySelector('.rt-scroll').scrollTop = state.scroll; const rows = panel.querySelectorAll('tbody tr'); const target=main.querySelector('[data-focus="' + CSS.escape(state.focus) + '"]') || rows[Math.max(0, Math.min(state.row, rows.length - 1))]?.querySelector('button') || main.querySelector('.rl-filters input'); target?.focus({ preventScroll: true }); returns.delete(back[0]); });
  }
  function associateImpact(impact, trigger) {
    const revision=GovernanceImpactDemo.token(impact), options=DataSourceDemo.objects(impact.source).flatMap(table=>demo.get(table).items.map(entry=>({value:JSON.stringify({table:table.id,entry:entry.id}),label:demo.objectName(table)+' / '+entry.name})));
    ui.openForm({title:'关联治理条目 · '+impact.title,trigger,value:{targets:[]},fields:[{key:'targets',label:'治理条目',options,multiple:true,required:true,full:true}],saveLabel:'打开治理',save:data=>demo.associate(impact,data.targets.map(value=>JSON.parse(value)),ui.currentUser,revision),onSaved:()=>openImpact(impact),success:'已关联治理条目'});
  }
  function impactEvidence(impact, entry) {
    const change=DataSourceDemo.changes.find(change=>impact.kind==='结构变化'&&change.id===impact.originId&&change.source===impact.source);
    const host=el('details','gov-change-evidence'),label=change?(change.kind+' · '+DataSourceDemo.changeTarget(change)+' · '+change.attribute):('业务说明：'+impact.title+' · 修订 '+impact.beforeRevision+' → '+impact.noteRevision+' · '+impact.action);
    // 待治理时直接展开本项差异；已审核事项保留摘要与来源回看。
    host.open=!['已完成', '知识处理中'].includes(impact.state); const summary=el('summary','',label);summary.title=label;summary.append(badge(impact.state));host.append(summary);
    if(impact.kind==='结构变化'||allowed('business-notes.view')) { const diff=el('dl','gov-diff'); diff.append(ui.pair('变化前',impact.before),ui.pair('变化后',impact.after)); host.append(diff); }
    else host.append(el('p','ds-notice','当前账号无权查看业务说明原文，请联系有权人员核对本次修订。'));
    const focus = el('section', 'gov-check-focus');
    const subject = change?.field || entry?.name || '当前条目';
    let checks;
    if (change) checks = GovernanceImpactDemo.checksFor(change);
    else {
      const scope = entry?.kind === 'relations' ? '关联条件、完整关联键和关系类型' : entry?.kind === 'metrics' ? '计算条件、单位和时间口径' : entry?.kind === 'overview' ? '业务用途、粒度和使用限制' : '业务含义、取值说明和使用限制';
      checks = impact.bindings.length ? '对照业务说明本次' + impact.action + '，核对 ' + subject + ' 的' + scope + '是否需要修订。' : '关联受影响的治理条目，核对本次资料变化涉及的业务定义。';
    }
    focus.append(el('strong', '', ['知识处理中', '已完成'].includes(impact.state) ? '核对事项' : '需要核对'), el('p', 'ds-prose', checks)); host.append(focus);
    const status = el('div', 'gov-source-status');
    if (change) {
      const evidence = el('details', 'gov-basis');
      evidence.append(el('summary', '', '来源依据'), el('p', '', change.evidence), el('p', '', '来源结论：' + change.fact + ' · ' + change.factSource));
      if (change.reason) evidence.append(el('p', '', '来源说明：' + change.reason), ...(change.reasonBasis ? [el('p', '', '说明依据：' + change.reasonBasis)] : []));
      change.verifications.forEach(event => evidence.append(el('p', '', event.actor + ' · ' + event.at + '\n' + event.reason)));
      if (change.result) evidence.append(el('p', '', '已有核实依据：' + change.result));
      status.append(evidence);
    }
    if (impact.state === '知识处理中' && allowed('knowledge.view')) status.append(action('处理知识更新', () => KnowledgeUpdatePage.open(impact), 'gov-knowledge-' + impact.id));
    if (allowed(impact.kind === '结构变化' ? 'collections.view' : 'business-notes.view')) status.append(action('查看来源', trigger => DataSourcesPage.viewImpactSource(main, ui, impact, trigger)));
    host.append(status);
    if (!entry) host.append(el('p','ds-prose',(['知识处理中', '已完成'].includes(impact.state) ? '原影响说明：' : '受影响内容：')+impact.targets));
    if(impact.governanceResult) host.append(el('p','ds-prose',impact.governanceResult));
    if(impact.completion) host.append(el('p','ds-prose','完成结果：'+impact.completion));
    if(impact.bindings.length>1) { const links=el('div','gov-related-items'); impact.bindings.forEach(binding=>{const table=DataSourceDemo.objects(impact.source).find(table=>table.id===binding.table), entry=table&&demo.get(table).items.find(entry=>entry.id===binding.entry); if(entry) links.append(action(demo.objectName(table)+' / '+entry.name+' · '+entry.review,()=>open(impact.source,demo.objectName(table),{impact:impact.id,tab:entry.kind,field:entry.field||entry.id,from:route.params.get('from')})));}); host.append(links); }
    return host;
  }
  function unmappedImpact(impact, selected = false) {
    const panel = el('details', 'gov-unmapped'); panel.open = selected;
    panel.append(el('summary', '', '待定位资料 · ' + impact.title), impactEvidence(impact));
    const actions = el('div', 'gov-toolbar-actions');
    if (selected && route.params.get('from') === 'business-notes') actions.append(button('返回业务说明', () => { DataSourceDemo.selected = impact.source; location.hash = '#business-notes'; }));
    if (impact.kind === '业务说明' && !impact.bindings.length && allowed('governance.edit')) actions.append(button('关联治理条目', trigger => associateImpact(impact, trigger), true));
    if (impact.kind === '结构变化') panel.append(el('p', 'ds-notice', '当前目录中找不到来源对象，请先核对来源与采集范围。'));
    panel.append(actions); return panel;
  }
  function definition(content, kind) {
    const dl = el('dl', 'gov-definition');
    const labels = kind === 'overview' ? ['业务用途', '一行代表什么 / 范围', '使用限制'] : kind === 'relations' ? ['关联条件', '关系类型与完整键', '适用业务与限制'] : kind === 'metrics' ? ['指标含义', '计算方法与条件', '单位、时间与使用限制'] : ['业务含义', '取值说明', '使用注意'];
    ['meaning', 'values', 'usage'].forEach((key, index) => { const row = el('div'); row.append(el('dt', '', labels[index]), el('dd', content?.[key] ? '' : 'gov-value-empty', content?.[key] || (index ? '—' : '尚未填写'))); dl.append(row); }); return dl;
  }
  function edit(record, entry, trigger) {
    const labels = entry.kind === 'overview' ? ['业务用途', '一行代表什么与范围', '使用限制'] : entry.kind === 'relations' ? ['关联条件', '关系类型与完整键', '适用业务与限制'] : entry.kind === 'metrics' ? ['指标含义', '计算方法与条件', '单位、时间与使用限制'] : ['业务含义', '取值说明', '使用注意'];
    const value = { ...entry.content, basis: entry.basis, gap: entry.gap };
    ui.openForm({ title: '编辑治理内容 · ' + entry.name, trigger, value, fields: [...['meaning', 'values', 'usage'].map((key, index) => ({ key, label: labels[index], type: 'textarea', required: !index, full: true, ...(entry.kind === 'fields' && key === 'values' ? { placeholder: '记录实际取值、编码和空值含义' } : {}) })), { key: 'basis', label: '核对依据', type: 'textarea', required: true, full: true }, { key: 'gap', label: '待确认问题', placeholder: '尚未解决的问题；全部核清后留空', type: 'textarea', full: true }], protectChanges: true, hasChanges: data => JSON.stringify(data) !== JSON.stringify(value), saveLabel: '保存待确认内容', save: data => demo.act(record, entry, 'save', ui.currentUser, data), success: '内容已保存，等待人工确认' });
  }
  function reason(record, entry, actionName, trigger) {
    ui.openForm({ title: (actionName === 'pending' ? '保留待确认' : '不采用 AI 建议') + ' · ' + entry.name, trigger, value: { reason: actionName === 'pending' ? entry.gap : '' }, fields: [{ key: 'reason', label: actionName === 'pending' ? '尚缺的业务信息' : '不采用依据', type: 'textarea', required: true, full: true }], saveLabel: '保存', save: data => demo.act(record, entry, actionName, ui.currentUser, data), success: actionName === 'pending' ? '已保留待确认问题' : '已记录不采用依据' });
  }
  function apply(record, entry, actionName, trigger) {
    const error = demo.act(record, entry, actionName, ui.currentUser);
    if (error) { ui.notify(error); return; }
    const impact = GovernanceImpactDemo.records.find(impact => impact.bindings.some(binding => binding.table === record.table.id && binding.entry === entry.id));
    refresh(); ui.notify(actionName === 'adopt' ? '建议已填入待确认内容' : impact?.state === '知识处理中' ? '当前条目已确认，变化事项知识处理中' : '当前条目已确认');
    requestAnimationFrame(() => (main.querySelector('[data-focus="' + trigger.dataset.focus + '"]') || main.querySelector('[data-entry-title]'))?.focus({ preventScroll: true }));
  }
  function generate(record, trigger, entry) {
    const notes = demo.availableNotes(record.table.source), dialog = ui.modal('生成 AI 建议 · ' + record.table.label, trigger), host = el('div', 'gov-generation-form');
    // 资料自动纳入，只读展示取用范围；只有提交生成才创建任务和引用。
    dialog.classList.add('gov-generation-dialog');
    dialog.append(host);
    PrototypeUI.mount(host, {
      data: () => ({ model: { kinds: entry ? [entry.kind] : ['overview', 'fields'] }, rules: entry ? {} : { kinds: [{ required: true, message: '请选择生成范围', trigger: 'change' }] }, notes, source: DataSourceDemo.find(record.table.source).name, scope: entry?.name, scopeError: false, error: '', notesId: dialog.getAttribute('aria-labelledby') + '-notes', options: kinds.slice(0, 4).filter(([kind]) => record.items.some(item => item.kind === kind && !item.retired)).map(([value, label]) => ({ value, label })) }),
      methods: {
        cancel() { dialog.close(); },
        submit() {
          this.$refs.form.validate(valid => {
            if (!valid) { this.$nextTick(() => host.querySelector('input[type="checkbox"]').focus()); return; }
            this.error = demo.generate(record, [...this.model.kinds], ui.currentUser, entry?.id) || '';
            if (this.error) { this.$nextTick(() => host.querySelector('.form-error').focus()); return; }
            dialog.close(); refresh(); ui.notify('建议已生成，取用记录已保留');
          });
        }
      },
      template: `<el-form ref="form" :model="model" :rules="rules" label-position="top" @validate="(_,valid)=>scopeError=!valid" @submit.prevent="submit">
        <div class="dialog-body">
          <p v-if="error" class="form-error" role="alert" tabindex="-1">{{error}}</p>
          <div class="gov-generation-context">
            <dl><dt>当前数据源</dt><dd>{{source}}</dd></dl>
            <dl v-if="scope"><dt>生成范围</dt><dd>{{scope}}</dd></dl>
            <el-form-item v-else label="生成范围" prop="kinds">
              <el-checkbox-group v-model="model.kinds" aria-label="生成范围" :aria-invalid="String(scopeError)" :aria-describedby="scopeError ? notesId+'-scope-error' : undefined" @change="error=''">
                <el-checkbox v-for="option in options" :key="option.value" :value="option.value">{{option.label}}</el-checkbox>
              </el-checkbox-group>
              <template #error="{error:message}"><div class="el-form-item__error" :id="notesId+'-scope-error'" role="alert">{{message}}</div></template>
            </el-form-item>
          </div>
          <section class="gov-generation-notes">
            <header><h3 :id="notesId">自动取用业务说明</h3><span class="gov-generation-count">{{notes.length}} 份</span></header>
            <div v-if="notes.length" class="gov-generation-note-scroll" role="region" :aria-labelledby="notesId" tabindex="0">
              <ul><li v-for="note in notes" :key="note.id"><span>{{note.title}}</span><small>第 {{note.revision}} 版</small></li></ul>
            </div>
            <p v-else class="gov-generation-empty">当前数据源暂无有效业务说明</p>
          </section>
        </div>
        <footer class="dialog-footer"><el-button @click="cancel">取消</el-button><el-button type="primary" native-type="submit">生成建议</el-button></footer>
      </el-form>`
    });
    dialog.showModal();
  }
  function taskLink(task, label = '查看取用记录') { return action(label, trigger => DataSourcesPage.viewGovernanceTask(main, ui, task, trigger)); }
  function adopt(record, entry, trigger) {
    if (!Object.values(entry.content).some(value => value.trim()) && !entry.basis && entry.gap === entry.ai.gap) { apply(record, entry, 'adopt', trigger); return; }
    const node = ui.modal('采用 AI 建议 · ' + entry.name, trigger), body = el('div', 'dialog-body'), compare = el('div', 'gov-compare has-ai'), bar = el('footer', 'dialog-footer');
    node.classList.add('gov-adopt-dialog');
    body.append(el('p', 'ds-notice', '将替换当前待确认内容、核对依据和待确认问题。原内容保留在修改记录中。'));
    for (const [label, content, gap] of [['当前内容', entry.content, entry.gap], ['AI 建议', entry.ai.content, entry.ai.gap]]) { const column = el('section', 'gov-column'); column.append(el('h3', '', label), definition(content, entry.kind)); if (gap) column.append(el('p', 'ds-notice', gap)); compare.append(column); }
    body.append(compare); bar.append(button('取消', () => node.close()), button('采用并替换', () => { node.close(); apply(record, entry, 'adopt', trigger); }, true)); node.append(body, bar); node.showModal();
  }
  function review(record, entry) {
    const body = el('section', 'gov-review'), heading = el('header', 'gov-entry-title'), title = el('h2', '', entry.name); title.tabIndex = -1; title.dataset.entryTitle = entry.id;
    const sources = GovernanceImpactDemo.records.filter(impact => impact.source === record.table.source && impact.bindings.some(binding => binding.table === record.table.id && binding.entry === entry.id));
    heading.append(title, badge(entry.review), ...(entry.validity !== '适用' ? [badge(entry.validity === '待复核' ? '业务定义待核对' : entry.validity)] : []), ...(entry.blocked ? [badge('禁止使用')] : []), el('span', 'gov-publication', '知识：' + entry.publication)); body.append(heading);
    const field = record.table.fields.find(field => field.name === entry.field), source = el('dl', 'gov-source');
    source.append(ui.pair(entry.kind === 'fields' ? '来源字段' : '来源对象', record.table.path + (entry.field ? '.' + entry.field : '')));
    if (entry.kind === 'fields') source.append(ui.pair('数据类型', entry.type), ...(field ? [ui.pair('允许空值', field.nullable), ...(record.table.type === '普通表' ? [ui.pair('默认值', field.default)] : [])] : []));
    source.append(ui.pair(entry.kind === 'fields' ? '字段注释' : '原注释', (entry.kind === 'fields' ? entry.comment : record.table.comment) || '无注释')); body.append(source);
    const compare = el('div', 'gov-compare' + (entry.ai ? ' has-ai' : '')), ai = el('section', 'gov-column gov-ai'), manual = el('section', 'gov-column gov-manual'); ai.append(el('h3', '', 'AI 建议')); manual.append(el('h3', '', '业务定义'));
    if (entry.ai) {
      ai.append(definition(entry.ai.content, entry.kind));
      const task = demo.tasks.find(task => task.id === entry.ai.taskId);
      const evidence = el('details', 'gov-basis'); evidence.append(el('summary', '', '建议依据'), ...entry.ai.basis.filter(text => !text.startsWith('业务说明来源：')).map(text => el('p', '', text)));
      if (task) { evidence.append(el('p', '', task.id + ' · 自动取用 ' + task.notes.length + ' 份业务说明'), taskLink(task)); }
      ai.append(evidence);
      if (entry.ai.gap) ai.append(el('p', 'ds-notice', entry.ai.gap));
      if (entry.ai.rejected) ai.append(el('p', 'ds-notice', '未采用：' + entry.ai.rejected));
    } else ai.append(el('p', 'ds-prose', entry.retired ? '来源对象或字段已移除，不再生成业务解释建议。' : '尚未生成建议'));
    if (!entry.retired && allowed('governance.suggest')) ai.append(action(entry.ai ? '重新生成当前项建议' : '生成当前项建议', trigger => generate(record, trigger, entry)));
    if (Object.values(entry.content).some(value => value.trim())) manual.append(definition(entry.content, entry.kind));
    else manual.append(el('p', 'ds-prose gov-value-empty', '尚未填写业务定义'));
    if (entry.gap) manual.append(el('p', 'ds-notice', entry.gap));
    if (entry.basis) { const evidence = el('details', 'gov-basis'); evidence.append(el('summary', '', '人工核对依据'), el('p', '', entry.basis)); manual.append(evidence); }
    if (entry.reviewer) manual.append(el('p', 'gov-status-line', entry.reviewer + ' · ' + entry.reviewedAt + ' · 修订 ' + entry.revision));
    // 当前定义优先阅读；相关变化紧随其后，待治理的差异仍默认展开。
    compare.append(manual, ai); body.append(compare); sources.forEach(impact => body.append(impactEvidence(impact, entry))); return body;
  }
  function entryActions(record, entry) {
    const bar = el('footer', 'gov-page-footer'), current = el('div', 'gov-current-item');
    current.append(el('strong', '', entry.name));
    const issue = entry.review !== '已确认' ? demo.confirmIssue(record, entry) : '';
    if (issue) { const message = el('small', '', issue); message.id = 'gov-confirm-issue'; current.append(message); }
    const secondary = [];
    bar.append(current);
    if (allowed('governance.edit')) {
      if (entry.ai && !entry.ai.rejected && entry.review !== '已确认') {
        const adopted = entry.sourceTaskId === entry.ai.taskId && JSON.stringify(entry.content) === JSON.stringify(entry.ai.content) && entry.gap === entry.ai.gap;
        const adoptButton = button(adopted ? '已采用建议' : '采用建议', trigger => adopt(record, entry, trigger), false, 'gov-adopt-' + entry.id); adoptButton.disabled = adopted; bar.append(adoptButton);
        secondary.push({label:'不采用 AI 建议',action:trigger=>reason(record,entry,'reject',trigger)});
      }
      bar.append(button('编辑内容', trigger => edit(record, entry, trigger), Boolean(issue), 'gov-edit-' + entry.id));
    }
    if(allowed('governance.edit')||allowed('governance.supplement')) secondary.push({label:'保留待确认',action:trigger=>reason(record,entry,'pending',trigger)});
    if(secondary.length===1) bar.append(button(secondary[0].label,secondary[0].action));
    else if(secondary.length>1) bar.append(ui.moreActions(secondary,'gov-secondary-'+entry.id));
    if (allowed('governance.confirm') && entry.review !== '已确认') { const confirm = button(entry.retired ? '确认移除处理' : '确认当前内容', trigger => apply(record, entry, 'confirm', trigger), !issue, 'gov-confirm-' + entry.id); if (issue) { confirm.disabled = true; confirm.title = issue; confirm.setAttribute('aria-describedby', 'gov-confirm-issue'); } bar.append(confirm); }
    if (issue && allowed('governance.edit')) bar.append(bar.querySelector('[data-focus="' + CSS.escape('gov-edit-' + entry.id) + '"]'));
    const controls = el('div', 'gov-entry-actions'); controls.append(...[...bar.children].slice(1)); bar.append(controls);
    return bar;
  }
  function draftPreview(record, trigger) {
    const node = ui.modal('生成知识草稿 · ' + record.table.label, trigger), body = el('div', 'dialog-body'), bar = el('footer', 'dialog-footer');
    const eligible = record.items.filter(entry => entry.review === '已确认' && entry.validity === '适用' && !entry.blocked && !entry.retired && !entry.dependencies?.some(id => { const dep = record.items.find(item => item.id === id); return !dep || dep.review !== '已确认' || dep.blocked; }));
    body.append(el('h3', '', '纳入草稿'), el('p', 'ds-prose', eligible.map(entry => entry.name).join('、') || '暂无可纳入条目'));
    const excluded = record.items.filter(entry => !eligible.includes(entry));
    if (excluded.length) body.append(el('h3', '', '未纳入'), el('p', 'ds-prose', excluded.map(entry => entry.name + '（' + (entry.blocked ? '禁止使用' : entry.validity === '待复核' ? '业务定义待核对' : entry.validity !== '适用' ? entry.validity : entry.review !== '已确认' ? '待确认' : '依赖不完整') + '）').join('\n')));
    bar.append(button('取消', () => node.close())); const create = button('生成草稿', () => { const result = demo.generateDraft(record, ui.currentUser); if (result.error) return ui.notify(result.error); node.close(); location.hash = '#knowledge/' + encodeURIComponent(record.table.source) + '/' + encodeURIComponent(result.draft.id); }, true); create.disabled = !eligible.length; bar.append(create); node.append(body, bar); node.showModal();
  }
  function detail(record) {
    activeRecord = record;
    const table = record.table, tab = kinds.some(([key]) => key === route.params.get('tab')) ? route.params.get('tab') : 'overview';
    const selected = record.items.find(entry=>entry.kind===tab&&(entry.field===route.params.get('field')||entry.id===route.params.get('field'))) || record.items.find(entry=>entry.kind===tab);
    const origin = returns.get(table.id), fromCatalog = route.params.get('from') === 'catalog';
    const rootOrigin = origin?.hash.split('?')[0].split('/').length<=2 && origin?.hash.startsWith('#governance');
    const originSection = route.params.get('from');
    const catalogParent = origin?.hash.startsWith('#catalog/') ? '结构详情' : '元数据目录';
    const parent = originSection === 'catalog' ? catalogParent : originSection === 'collections' ? '结构变化' : originSection === 'business-notes' ? '企业业务说明' : originSection === 'knowledge-update' ? '知识更新' : '治理工作台';
    const parentHref = origin?.hash || (originSection === 'catalog' ? '#catalog' : originSection === 'collections' ? '#catalog/' + table.source + '/changes/' + encodeURIComponent(demo.objectName(table)) : originSection === 'business-notes' ? '#business-notes' : originSection === 'knowledge-update' ? '#knowledge/' + table.source + '/' + route.params.get('impact') : rootOrigin ? origin.hash : path(table.source));
    ui.setTitle(table.label + ' · ' + demo.objectName(table), parent, parentHref);
    const shell = el('section', 'gov-shell'), toolbar = el('header', 'gov-toolbar'), identity = el('div', 'gov-identity'), buttons = el('div', 'gov-toolbar-actions');
    const progress = demo.summary(record);
    identity.append(el('span', 'gov-object-context', route.source.name + ' · ' + table.type + ' · 采集快照 ' + table.version), el('span', 'gov-progress', '表说明与字段：' + progress.state + '（' + progress.total + ' / ' + progress.confirmed + '）'));
    if (fromCatalog) buttons.append(button('返回' + catalogParent, () => { DataSourceDemo.selected = table.source; location.hash = origin?.hash || '#catalog'; }));
    else if(['collections','business-notes'].includes(route.params.get('from'))) buttons.append(button(route.params.get('from')==='collections'?'返回结构变化':'返回业务说明',()=>{DataSourceDemo.selected=table.source;location.hash=parentHref;}));
    else if(originSection==='knowledge-update') buttons.append(button('返回知识更新',()=>{DataSourceDemo.selected=table.source;location.hash=parentHref;}));
    if (allowed('knowledge.generate')) buttons.append(button('生成知识草稿', trigger => draftPreview(record, trigger), false, 'gov-draft'));
    if (allowed('governance.suggest')) buttons.append(button('批量生成 AI 建议', trigger => generate(record, trigger), false, 'gov-generate'));
    toolbar.append(identity, buttons); shell.append(toolbar);
    const detailKinds = kinds.map(([key, label]) => { const entries = record.items.filter(entry => entry.kind === key); return [key, key === 'overview' ? '表说明' : key === 'evidence' ? label : label + '（' + entries.length + ' / ' + entries.filter(entry => entry.review === '已确认' && entry.validity === '适用').length + '）']; });
    const tabs = el('div', 'gov-tabs prototype-ui'); PrototypeUI.mount(tabs, { data: () => ({ tab, kinds: detailKinds }), methods: { change: value => select(value) }, template: '<el-tabs v-model="tab" @tab-change="change"><el-tab-pane v-for="kind in kinds" :key="kind[0]" :name="kind[0]" :label="kind[1]"></el-tab-pane></el-tabs>' }); shell.append(tabs);
    const content = el('div', 'gov-content' + (['fields', 'relations', 'metrics'].includes(tab) ? ' has-item-nav' : '')); let currentEntry;
    const sources = GovernanceImpactDemo.records.filter(item => item.source === table.source && (tab === 'evidence' ? item.bindings.some(binding => binding.table === table.id) : item.bindings.some(binding => binding.table === table.id && binding.entry === selected?.id)));
    if (tab === 'evidence') {
      sources.forEach(item => content.append(impactEvidence(item, selected)));
      const history = el('section', 'gov-history'); history.append(el('h2', '', '待补充或审核内容'));
      record.items.filter(entry => entry.review !== '已确认' || entry.validity !== '适用').forEach(entry => { const line = el('div', 'ds-note-use'); line.append(action(entry.name + ' · ' + entry.review + (entry.blocked ? ' · 禁止使用' : ''), () => select(entry.kind, entry.field || entry.id)), el('p', 'ds-prose', entry.gap || entry.content.usage)); history.append(line); });
      history.append(el('h2', '', 'AI 与人工处理记录'));
      demo.tasksFor(record).forEach(task => { const line = el('div', 'ds-note-use'); line.append(taskLink(task, task.id + ' · ' + task.name), el('small', 'cell-sub', person(task.actor) + ' · ' + task.at + ' · 自动取用 ' + task.notes.length + ' 份资料 · ' + task.state)); history.append(line); });
      const events = record.items.flatMap(entry => entry.history.map(event => ({ ...event, name: entry.name }))).sort((a, b) => b.at.localeCompare(a.at));
      sources.forEach(item => item.events.forEach(event => events.push({ ...event, name: item.title })));
      events.sort((a, b) => b.at.localeCompare(a.at));
      events.forEach(event => { const line = el('details', 'ds-note-use'); line.append(el('summary', '', event.name + ' · ' + event.action + ' · ' + event.actor + ' · ' + event.at), el('p', 'ds-prose', [event.revision && '修订 ' + event.revision, event.review].filter(Boolean).join(' · ') + '\n' + (event.reason || Object.values(event.content || {}).filter(Boolean).join('\n')))); history.append(line); });
      if (!events.length) history.append(el('p', 'ds-prose', '暂无新增处理记录')); content.append(history);
    } else {
      const entries = record.items.filter(entry => entry.kind === tab);
      const entry = entries.find(entry => entry.field === route.params.get('field') || entry.id === route.params.get('field')) || entries[0];
      currentEntry = entry;
      if (!entry) { const empty = el('div', 'gov-empty'); empty.append(el('p', '', tab === 'relations' ? '暂无关联建议' : '暂无指标定义')); content.append(empty); }
      else if (tab === 'overview') content.append(review(record, entry));
      else {
        const split = el('div', 'gov-split'), nav = el('nav', 'gov-items'), items = el('div', 'gov-item-list'); nav.setAttribute('aria-label', tab === 'fields' ? '治理字段' : '治理条目');
        const key = table.id + ':' + tab, search = el('div', 'gov-item-search prototype-ui'), empty = el('p', 'gov-nav-empty', '无匹配条目'); empty.setAttribute('role', 'status');
        const filter = keyword => { itemQueries.set(key, keyword); let count = 0; entries.forEach((item, index) => { const matches = AdminQuery.matches([item.name, item.comment], keyword); items.children[index].hidden = !matches; if (matches) count++; }); empty.hidden = count > 0; };
        const reveal = () => { const selected = items.querySelector('[aria-current="true"]'); if (items.isConnected && selected && !selected.hidden) { const itemRect = selected.getBoundingClientRect(), listRect = items.getBoundingClientRect(); if (itemRect.bottom > listRect.bottom) items.scrollTop += itemRect.bottom - listRect.bottom; else if (itemRect.top < listRect.top) items.scrollTop += itemRect.top - listRect.top; } };
        entries.forEach(item => { const control = ui.button('', () => select(tab, item.field || item.id), 'gov-item', 'gov-item-' + item.id); control.setAttribute('aria-current', String(item.id === entry.id)); control.append(el('strong', '', item.name)); if (item.comment) control.append(el('small', 'gov-item-comment', item.comment)); const meta = el('span', 'gov-item-meta'); meta.append(badge(item.validity === '待复核' ? '业务定义待核对' : item.review), ...(item.blocked ? [badge('禁止使用')] : []), el('small', '', item.type || '')); control.append(meta); items.append(control); });
        PrototypeUI.mount(search, { data: () => ({ keyword: itemQueries.get(key) || '' }), methods: { filter }, mounted() { this.resize = new ResizeObserver(reveal); this.resize.observe(items); requestAnimationFrame(reveal); }, beforeUnmount() { this.resize.disconnect(); }, template: '<el-input v-model="keyword" clearable aria-label="搜索当前治理条目" placeholder="搜索名称或注释" @input="filter"></el-input>' });
        nav.append(search, items, empty); filter(itemQueries.get(key) || ''); split.append(nav, review(record, entry)); content.append(split);
      }
    }
    shell.append(content); if (currentEntry) shell.append(entryActions(record, currentEntry)); main.replaceChildren(ui.pageHeading(table.label + '治理详情'), shell);
  }
  function render(target, controls) {
    main = target; ui = controls;
    if (!allowed('governance.view')) { main.replaceChildren(ui.pageHeading('不可访问'), el('p', 'ds-notice is-danger', '当前账号没有治理查看权限。')); return; }
    const raw = location.hash.slice(1).split('?'), parts = raw[0].split('/'), source = DataSourceDemo.find(decodeURIComponent(parts[1] || '')) || (!parts[1] ? DataSourceDemo.find(DataSourceDemo.selected) : null);
    if (!source) { main.replaceChildren(ui.pageHeading('治理对象不可访问'), el('p', 'ds-notice is-danger', '找不到当前数据源，请返回治理工作台重新选择。')); return; }
    DataSourceDemo.selected = source.id; route = { source, params: new URLSearchParams(raw[1] || '') };
    const tableName = decodeURIComponent(parts[2] || ''), table = tableName && DataSourceDemo.objects(source.id).find(table => demo.objectName(table) === tableName);
    if (tableName && !table) { main.replaceChildren(ui.pageHeading('治理对象不可访问'), el('p', 'ds-notice is-danger', '当前目录中找不到此对象，请重新核对数据源与对象。'), button('返回表列表', () => { location.hash = path(source.id); })); return; }
    if (table && !DataSourceDemo.canGovern(table)) {
      ui.setTitle('待采集成功 · ' + demo.objectName(table), '治理工作台', path(source.id));
      main.replaceChildren(ui.pageHeading('待采集成功'), el('p', 'ds-notice', '该对象尚无成功采集的结构，请先完成元数据采集。'));
      return;
    }
    if (table && DataSourceDemo.pendingVerification(table).length) {
      ui.setTitle('变化待核实 · ' + demo.objectName(table), '治理工作台', path(source.id));
      main.replaceChildren(ui.pageHeading('变化待核实'), el('p', 'ds-notice', '该对象仍有 ' + DataSourceDemo.pendingVerification(table).length + ' 项变化待核实，核实后可进入治理。'), ...(allowed('collections.view') ? [button('查看待核实变化', () => DataSourcesPage.openChanges(source.id, demo.objectName(table)), true)] : [el('p', 'ds-prose', '请联系有权人员核对结构变化。')]));
      return;
    }
    if (table) detail(demo.get(table)); else list(source);
  }
  function renderDraft(target, controls, sourceId, draftId) {
    main = target; ui = controls; const draft = demo.drafts.get(draftId);
    if (!allowed('knowledge.view') || !draft || draft.source !== sourceId) return false;
    const table = DataSourceDemo.objects(sourceId).find(table => table.id === draft.table); if (!table) return false;
    DataSourceDemo.selected = sourceId; ui.setTitle(draft.title, '知识文档', '#knowledge');
    const shell = el('section', 'gov-shell'), header = el('header', 'gov-toolbar'), identity = el('div', 'gov-identity'); identity.append(el('strong', '', draft.title), el('small', '', DataSourceDemo.find(sourceId).name + ' / ' + demo.objectName(table))); header.append(identity, badge('未发布'), button('返回表治理', () => { location.hash = path(sourceId, demo.objectName(table)); })); shell.append(header);
    const content = el('div', 'gov-content'), body = el('article', 'gov-draft');
    if (draft.excluded.length) body.append(el('p', 'ds-notice', '本草稿未纳入：' + draft.excluded.join('、')));
    draft.items.forEach(entry => { const section = el('section'); section.append(el('h2', '', entry.name), definition(entry.content, entry.kind), el('p', 'gov-status-line', entry.reviewer + ' · 治理修订 ' + entry.revision)); body.append(section); }); content.append(body); shell.append(content); main.replaceChildren(ui.pageHeading(draft.title), shell); return true;
  }
  window.GovernancePage = { render, open, openImpact, renderDraft };
})();
