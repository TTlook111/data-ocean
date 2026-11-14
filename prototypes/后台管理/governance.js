/* 表是工作入口，表说明、字段、关系和指标各自审核；复用现有后台外壳和组件。 */
(() => {
  'use strict';
  const demo = GovernanceDemo, queries = new Map(), returns = new Map(), itemQueries = new Map(), itemFilters = new Map(), itemSelections = new Map(), viewPositions = new Map();
  const kinds = [['overview', '表说明'], ['fields', '字段'], ['relations', '关联'], ['metrics', '指标'], ['evidence', '状态与依据']];
  // 填写内容按治理对象组织；仅基础含义和表粒度必填，其他内容按实际用途补充。
  const labelFields = {
    overview: [
      { key: 'meaning', label: '业务用途', required: true, placeholder: '说明记录什么业务，适合回答哪些问题' },
      { key: 'values', label: '一行代表什么', required: true, placeholder: '说明每条记录对应的业务对象或事件，以及同一对象是否可能有多条记录' },
      { key: 'scope', label: '数据范围', placeholder: '说明包含的业务数据和排除条件' },
      { key: 'updates', label: '时间与更新', placeholder: '说明覆盖时间、更新规律及已核实的数据截至时间' }
    ],
    fields: [
      { key: 'meaning', label: '业务含义', required: true, placeholder: '说明字段表示的业务对象、属性或事件' },
      { key: 'values', label: '取值含义', placeholder: '说明编码、单位、格式，以及空值、空字符串和 0 的含义' },
      { key: 'rules', label: '使用口径', placeholder: '说明筛选、计算或统计时需要遵守的规则' },
      { key: 'usage', label: '使用注意事项', placeholder: '说明容易误解或影响结果的情况' }
    ],
    relations: [
      { key: 'meaning', label: '关联条件', required: true, placeholder: '说明双方表、完整关联字段和附加条件' },
      { key: 'values', label: '数量关系与完整键', required: true, placeholder: '说明一对一、一对多或多对多，以及关联后是否漏数或重复' },
      { key: 'usage', label: '适用条件与限制', placeholder: '说明适用范围及关联方向、过滤和重复统计的注意事项' }
    ],
    metrics: [
      { key: 'meaning', label: '指标含义', required: true, placeholder: '说明指标衡量的业务内容' },
      { key: 'values', label: '公式与筛选条件', required: true, placeholder: '说明公式、统计范围、去重及退款等调整规则' },
      { key: 'usage', label: '单位与统计边界', placeholder: '说明单位、业务时间、统计粒度和适用限制' }
    ]
  };
  const entriesFor = (record, kind) => kind === 'relations' ? demo.relationsFor(record).map(ref => ref.entry) : record.items.filter(entry => entry.kind === kind);
  let main, ui, route, activeRecord;
  const labelDrafts = new Map();
  const discoveryRuns = new Map();
  let labelSession = null;
  const allowed = ability => SourceApprovalDemo.hasAbility(ui.currentUser, ability);
  const person = id => { const user = PeopleDemo.users.find(user => user.id === id); return user ? user.name + '（' + user.login + '）' : '未指定'; };
  const refresh = () => window.dispatchEvent(new Event('hashchange'));
  const el = (tag, cls, text) => ui.el(tag, cls, text);
  const button = (label, fn, primary = false, key) => ui.button(label, fn, 'admin-button' + (primary ? ' is-primary' : ''), key);
  const action = (label, fn, key) => ui.button(label, fn, 'do-action is-primary', key);
  const needsWork = entry => !demo.isDismissed(entry) && !demo.isConfirmed(entry);
  const entryState = entry => demo.isDismissed(entry) ? entry.disposition : entry.validity === '待复核' ? '业务定义待核对' : entry.review;
  const entryLabel = entry => entry.kind === 'fields' ? entry.comment || entry.name : entry.name;
  const kindLabel = kind => kinds.find(([key]) => key === kind)?.[1] || '条目';
  const hasDefinition = entry => Object.values(entry.content).some(value => value.trim()) || Boolean(entry.valueDetails?.unit || entry.valueDetails?.mappings?.length || entry.dependencies?.length);
  const hasSuggestion = entry => Boolean(entry.ai && Object.values(entry.ai.content).some(value => value.trim()));
  const readingPane = root => root?.querySelector('.gov-content .gov-inline-review') || root?.querySelector('.gov-content.has-item-nav .gov-review') || root?.querySelector('.gov-content');
  const isLabel = entry => Boolean(labelFields[entry.kind]) && !entry.retired;
  const labelKey = (record, entry) => record.table.id + '/' + entry.id;
  function labelValue(entry) {
    let context = structuredClone(entry.checkContext || null);
    if (!context && entry.gap) context = { question: entry.gap, reason: entry.ai?.reasoning || '现有依据不足以确认这项业务定义。', targets: ['values'] };
    // 已有核对记录保留原问题，界面也只允许回答，不提供改写问题的入口。
    if (context && !Array.isArray(context.questions)) {
      const key = context.targets[0], label = labelFields[entry.kind].find(field => field.key === key)?.label || key, resolved = !entry.gap && Boolean(entry.content[key]?.trim());
      context.questions = [{ key, label, origin: context.origin || '历史核对', question: context.question, reason: context.reason, response: resolved ? entry.content[key] || '' : '', answer: resolved ? entry.content[key] || '' : '', resolved, deferred: false }];
    }
    const model = { ...entry.content, ...Object.fromEntries(labelFields[entry.kind].map(({ key }) => [key, entry.content[key] || ''])), ...(['relations', 'metrics'].includes(entry.kind) ? { name: entry.name } : {}), ...(entry.kind === 'fields' ? { valueDetails: structuredClone(entry.valueDetails || { unit: '', mappings: [] }) } : {}), dependencies: structuredClone(entry.dependencies || []), gap: entry.gap || '', basis: entry.basis || '', checkContext: context };
    demo.refreshAnswers(model); return model;
  }
  const labelDirty = state => JSON.stringify(state.model) !== JSON.stringify(state.baseline);
  function labelState(record, entry) {
    const key = labelKey(record, entry);
    let state = labelDrafts.get(key);
    if (!state || state.revision !== entry.revision) {
      state = { key, record, entry, revision: entry.revision, model: labelValue(entry), baseline: labelValue(entry), busy: false, token: 0 };
      labelDrafts.set(key, state);
    }
    return state;
  }
  // 行内修改在真正离开时保护；继续编辑为默认选择，明确放弃才切换条目或菜单。
  function beforeNavigation() {
    const state = labelSession;
    if (!state?.host?.isConnected) { labelSession = null; return false; }
    if (location.hash === state.hash) return false;
    viewPositions.set(state.viewKey, { reading: state.host.scrollTop, nav: main.querySelector('.gov-item-list')?.scrollTop || 0 });
    if (state.busy) { state.token++; state.busy = false; state.vm.busy = false; }
    if (!labelDirty(state)) return false;
    const next = location.hash; history.replaceState(null, '', state.hash);
    route.params = new URLSearchParams(state.hash.split('?')[1] || '');
    if (!state.asking) {
      state.asking = true;
      ElementPlus.ElMessageBox.confirm('当前条目有未保存的修改。放弃后将丢失本次输入。', '离开当前条目？', {
        confirmButtonText: '继续编辑', cancelButtonText: '放弃修改', distinguishCancelAndClose: true,
        closeOnClickModal: false, closeOnHashChange: false, customClass: 'prototype-ui'
      }).catch(action => { if (action === 'cancel') { labelDrafts.delete(state.key); labelSession = null; location.hash = next; } }).finally(() => { state.asking = false; });
    }
    return true;
  }
  function beforeTabLeave() {
    const state = labelSession;
    if (!state?.host?.isConnected) return true;
    if (state.busy) { state.token++; state.busy = false; state.vm.busy = false; }
    if (!labelDirty(state)) return true;
    // 使用组件的离开钩子，在页签选中状态改变前保护输入。
    if (!state.leaveRequest) state.leaveRequest = ElementPlus.ElMessageBox.confirm('当前条目有未保存的修改。放弃后将丢失本次输入。', '离开当前条目？', {
      confirmButtonText: '继续编辑', cancelButtonText: '放弃修改', distinguishCancelAndClose: true,
      closeOnClickModal: false, closeOnHashChange: false, customClass: 'prototype-ui'
    }).then(() => Promise.reject()).catch(action => {
      if (action === 'cancel') { labelDrafts.delete(state.key); labelSession = null; return true; }
      return Promise.reject();
    }).finally(() => { state.leaveRequest = null; });
    return state.leaveRequest;
  }
  const labelPreview = (state, model = state.model) => ({ ...state.entry, name: model.name ?? state.entry.name, content: Object.fromEntries(labelFields[state.entry.kind].map(({ key }) => [key, model[key]])), valueDetails: model.valueDetails, dependencies: model.dependencies, gap: model.gap, basis: model.basis, checkContext: model.checkContext });
  const labelIssue = state => demo.confirmIssue(state.record, labelPreview(state));
  // 确认受阻时直达能修正问题的输入，不让使用者对着灰色按钮猜位置。
  function focusLabelIssue(state) {
    const pending = demo.unansweredQuestions(state.model)[0];
    const issue = demo.definitionIssues(labelPreview(state))[0] || demo.referenceIssue(state.record, labelPreview(state));
    const missing = issue && labelFields[state.entry.kind].find(field => field.key === issue.key);
    const incompleteCode = state.model.valueDetails?.mappings.findIndex(row => !row.code.trim() || !row.meaning.trim());
    const label = state.model.name !== undefined && !state.model.name.trim() ? kindLabel(state.entry.kind) + '名称' : pending ? (pending.response || '').trim() ? '确认' + pending.label + '回答' : pending.label + '的回答' : issue?.control || (missing ? missing.label : incompleteCode >= 0 ? (state.model.valueDetails.mappings[incompleteCode].code.trim() ? '编码含义 ' : '编码 ') + (incompleteCode + 1) : '依赖条目');
    const input = state.host.querySelector('[aria-label="' + label + '"]');
    if (input) { input.focus(); input.scrollIntoView({ block: 'center' }); }
    else (state.host.querySelector('.gov-dependency-editor') || state.host.querySelector('.gov-source-context'))?.scrollIntoView({ block: 'center' });
  }
  function updateLabelActions(state) {
    const controls = state.controls; if (!controls) return;
    const dirty = labelDirty(state), entry = state.entry;
    const issue = labelIssue(state), dismissed = demo.isDismissed(entry), done = demo.isConfirmed(entry) && !dirty;
    // 首次进入不预铺缺项提示；实际核对问题或提交失败时再显示原因和定位。
    const showIssue = Boolean(state.vm?.error || state.model.gap);
    controls.reason.textContent = dismissed ? '' : state.busy ? '生成中…' : issue && showIssue ? issue : dirty ? '未保存修改，确认时一并保存' : '';
    controls.status.textContent = dismissed ? entry.disposition : dirty ? '未保存修改' : entry.validity === '待复核' ? '业务定义待核对' : entry.review !== '已确认' && entry.definitionSource?.kind.startsWith('AI') ? 'AI 草稿' : entry.review;
    controls.status.classList.toggle('is-pending', !dismissed && (dirty || entry.review !== '已确认' || entry.validity === '待复核'));
    if (controls.generate) { controls.generate.disabled = state.busy; controls.generate.classList.toggle('is-primary', !done && !state.model.meaning.trim()); }
    if (controls.save) { controls.save.disabled = state.busy || !dirty; controls.save.classList.remove('is-primary'); }
    if (controls.confirm) { controls.confirm.hidden = done || dismissed; controls.confirm.disabled = state.busy; controls.confirm.classList.toggle('is-primary', !issue && !done); controls.confirm.title = issue; }
    if (controls.repair) { controls.repair.hidden = dismissed || !issue || !showIssue || state.busy || done || demo.unansweredQuestions(state.model).length > 0; controls.repair.textContent = '定位需补充内容'; }
    if (controls.next) controls.next.hidden = !done && !dismissed;
    if (state.modifiedNotice) {
      const changed = entry.ai && labelFields[entry.kind].some(({ key }) => state.model[key] !== (entry.ai.content[key] || ''));
      state.modifiedNotice.hidden = !changed;
    }
  }
  function saveLabel(state, confirm = false) {
    if (state.busy) return;
    if (confirm && labelIssue(state)) { state.vm.error = labelIssue(state); updateLabelActions(state); focusLabelIssue(state); return; }
    let error;
    if (labelDirty(state)) error = demo.act(state.record, state.entry, 'save', ui.currentUser, JSON.parse(JSON.stringify(state.model)));
    if (error === '内容未变化。') error = undefined;
    if (!error && confirm) error = demo.act(state.record, state.entry, 'confirm', ui.currentUser);
    if (error) { state.vm.error = error; updateLabelActions(state); focusLabelIssue(state); return; }
    labelDrafts.delete(state.key); labelSession = null; refresh();
    ui.notify(confirm ? '当前条目已确认' : '草稿已保存，仍待审核');
  }
  function generationDiff(record, state, prepared, apply) {
    const dialog = ui.modal('核对 AI 建议替换范围', state.controls?.generate), host = el('div', 'gov-diff-form prototype-ui');
    dialog.classList.add('gov-diff-dialog'); dialog.append(host);
    const rows = prepared.outputs.flatMap(output => {
      const entry = record.items.find(entry => entry.id === output.id), current = entry === state.entry ? state.model : entry.content;
      return labelFields[entry.kind].map(field => ({ id: entry.id + '/' + field.key, entry: entry.id, key: field.key, label: entry.name + ' · ' + field.label, before: current[field.key] || '', after: output.content[field.key] || '', question: output.checkContext?.questions?.find(question => question.key === field.key)?.question, protected: entry.review === '已确认' || Boolean(entry.editedBy) }));
    });
    PrototypeUI.mount(host, {
      data: () => ({ rows, selected: rows.filter(row => !row.before && row.after && !row.protected).map(row => row.id), error: '' }),
      methods: { cancel() { dialog.close(); }, submit() {
        if (!this.selected.length) { this.error = '请选择需要写入的内容。'; return; }
        const replacements = {};
        rows.filter(row => this.selected.includes(row.id)).forEach(row => (replacements[row.entry] ||= []).push(row.key));
        dialog.close(); apply(replacements);
      } },
      template: `<div><div class="dialog-body"><p class="gov-diff-warning">仅替换勾选内容。人工修改和已确认内容需主动选择，原修订保留。</p><p v-if="error" class="form-error" role="alert">{{error}}</p><el-checkbox-group v-model="selected" aria-label="替换范围"><div v-for="row in rows" :key="row.id" class="gov-diff-row"><el-checkbox :value="row.id" :aria-label="row.label+' 替换'">{{row.label}}<span v-if="row.protected" class="gov-protected">受保护</span></el-checkbox><div class="gov-diff-values"><div><small>当前内容</small><p>{{row.before || '未填写'}}</p></div><div><small>AI 草稿</small><p>{{row.after || '无建议，选择后清空此项'}}</p><p v-if="row.question" class="gov-diff-question">需人工补充：{{row.question}}</p></div></div></div></el-checkbox-group></div><div class="dialog-footer"><el-button @click="cancel">继续编辑</el-button><el-button type="primary" @click="submit">替换所选内容</el-button></div></div>`
    });
    dialog.showModal();
  }
  function generateLabel(record, entry, kinds = [entry.kind], batch = false, entryIds) {
    const state = labelState(record, entry);
    const targets = record.items.filter(item => !item.retired && kinds.includes(item.kind) && (batch || item.id === entry.id) && (!entryIds || entryIds.includes(item.id)));
    const run = async () => {
      const token = ++state.token, revision = entry.revision, snapshot = record.table.version, revisions = targets.map(item => [item, item.revision]);
      state.busy = true; state.vm.busy = true; state.vm.error = ''; state.vm.phase = '整理表与字段上下文'; updateLabelActions(state);
      const phases = ['整理表与字段上下文', '纳入 ' + demo.availableNotes(record.table.source).length + ' 份业务说明', entry.kind === 'overview' ? '演示完整记录核对' : entry.kind === 'fields' ? '演示字段样本核对' : '整理业务规则与依赖', '形成待审核建议'];
      for (const phase of phases) {
        if (state.token !== token) return;
        state.vm.phase = phase;
        await new Promise(resolve => setTimeout(resolve, 360));
      }
      if (state.token !== token) return;
      const prepared = record.table.version !== snapshot ? { error: '来源结构已变化，请重新生成。' } : revisions.some(([item, revision]) => item.revision !== revision) ? { error: '治理条目已修改，请重新核对后生成。' } : GovernancePrototype.preview(record, kinds, ui.currentUser, batch ? undefined : entry.id, targets.map(item => item.id));
      state.busy = false; state.vm.busy = false; state.vm.phase = '';
      if (prepared.error) { state.vm.error = prepared.error; updateLabelActions(state); return; }
      const apply = replacements => {
        const error = GovernancePrototype.generate(record, kinds, ui.currentUser, batch ? undefined : entry.id, batch ? undefined : revision, { preview: prepared, replacements, baseModels: { [entry.id]: JSON.parse(JSON.stringify(state.model)) } });
        if (error) { state.vm.error = error; updateLabelActions(state); return; }
        targets.forEach(item => labelDrafts.delete(labelKey(record, item)));
        labelSession = null; select(entry.kind, entry.field || entry.id);
        const task = demo.tasksFor(record)[0], pending = task.outputs.reduce((count, output) => count + (output.checkContext?.questions?.filter(question => output.replacedSections?.includes(question.key)).length || 0), 0);
        ui.notify('已处理 ' + task.outputs.length + ' 个条目' + (pending ? '，' + pending + ' 项需人工补充' : '，草稿待确认'));
        requestAnimationFrame(() => { const input = main.querySelector('.gov-ai-question:not(.is-answered) textarea') || main.querySelector('.gov-label-form textarea'); input?.focus({ preventScroll: true }); input?.closest('.el-form-item')?.scrollIntoView({ block: 'start' }); });
      };
      updateLabelActions(state);
      if (labelDirty(state) || targets.some(hasDefinition) || targets.some(item => item.review === '已确认' || item.editedBy)) generationDiff(record, state, prepared, apply);
      else apply();
    };
    run().catch(() => { state.busy = false; state.vm.busy = false; state.vm.error = '生成失败，当前输入已保留，请重试。'; updateLabelActions(state); });
  }
  function labelReview(record, entry) {
    const state = labelState(record, entry), body = el('section', 'gov-review gov-inline-review'), heading = el('header', 'gov-entry-title');
    // 编辑和参考各占一栏，表单与底栏沿用同一列宽；窄 PC 窗口由 CSS 收为上下分区。
    const layout = el('div', 'gov-editor-layout'), editor = el('div', 'gov-editing-main'), reference = el('aside', 'gov-editing-aside');
    reference.setAttribute('aria-label', '治理参考');
    const impacts = GovernanceImpactDemo.records.filter(impact => impact.bindings.some(binding => binding.table === record.table.id && binding.entry === entry.id));
    state.hash = location.hash; state.viewKey = activeRecord.table.id + ':' + entry.kind + ':' + entry.id; state.host = body; labelSession = state;
    const title = el('h2', '', entry.kind === 'overview' ? record.table.label : entryLabel(entry)); title.tabIndex = -1; title.dataset.entryTitle = entry.id;
    const status = badge(entryState(entry)); state.status = status;
    heading.append(title, ...(entry.kind === 'fields' ? [el('span', 'gov-entry-code', entry.field + ' · ' + entry.type)] : []), status, ...(entry.blocked ? [badge('禁止使用')] : []));
    if (entry.ai?.simulated) heading.append(el('span', 'gov-prototype-badge', '原型演示'));
    editor.append(heading);
    const inputs = el('div', 'gov-input-summary'), notes = demo.availableNotes(record.table.source);
    if (allowed('business-notes.view')) inputs.append(action('业务说明 · ' + notes.length + ' 份', trigger => DataSourcesPage.viewGovernanceNotes(main, ui, record.table.source, trigger), 'gov-input-notes'));
    else inputs.append(el('span', '', '业务说明原文查看受限'));
    const host = el('div', 'gov-label-form prototype-ui');
    host.style.setProperty('--gov-label-width', ['relations', 'metrics'].includes(entry.kind) ? '144px' : '112px');
    const fields = labelFields[entry.kind];
    const dependencyOptions = ['relations', 'metrics'].includes(entry.kind) ? DataSourceDemo.objects(record.table.source).flatMap(table => demo.get(table).items.filter(item => !item.retired && !(table.id === record.table.id && item.id === entry.id) && (entry.kind !== 'relations' || item.kind === 'fields')).map(item => ({ value: JSON.stringify({ table: table.id, entry: item.id }), label: demo.objectName(table) + ' / ' + item.name }))) : [];
    const fieldType = entry.kind === 'fields' ? /status|state|type|category|状态|分类/i.test(entry.field + ' ' + entry.comment) ? 'enum' : /amount|price|money|quantity|金额|数量|单价/i.test(entry.field + ' ' + entry.comment) ? 'measure' : 'other' : '';
    PrototypeUI.mount(host, {
      data: () => ({ model: state.model, busy: false, error: '', phase: '', kind: entry.kind, fieldType, dependencyOptions, reviewReason: entry.reviewReason || '', answerErrors: {}, readonly: !allowed('governance.edit') || demo.isDismissed(entry) }),
      computed: {
        questions() { return this.model.checkContext?.questions || []; },
        validationIssues() { return demo.definitionIssues(labelPreview(state, this.model)); },
        groups() { return this.kind === 'fields' ? [{ fields: fields.slice(0, 1) }, { fields: fields.slice(1, 2) }, { title: '使用规则与注意事项', fields: fields.slice(2) }] : [{ fields }]; },
        selectedDependencies: { get() { return this.model.dependencies.map(dep => JSON.stringify(typeof dep === 'string' ? { table: record.table.id, entry: dep } : dep)); }, set(values) { this.model.dependencies = values.map(value => JSON.parse(value)); } }
      },
      watch: { model: { deep: true, handler() { demo.refreshAnswers(this.model); this.error = ''; updateLabelActions(state); } } },
      mounted() { state.vm = this; },
      methods: {
        // 核对或补充问题跟随对应输入框，沿用同一内容键同步结论。
        questionsFor(field) { return this.questions.filter(question => question.key === field.key); },
        pendingQuestions(field) { return demo.isDismissed(entry) ? [] : this.questionsFor(field).filter(question => !question.resolved); },
        checkedQuestions(field) { return this.questionsFor(field).filter(question => question.resolved); },
        viewChecks(field, event) { DataSourcesPage.viewGovernanceChecks(main, ui, record, entry, this.checkedQuestions(field), event.currentTarget); },
        focusQuestion(question) { this.$nextTick(() => { const target = host.querySelector('textarea[aria-label="' + question.label + '的回答"]'); target?.focus(); host.querySelector('#gov-ai-question-' + question.key)?.scrollIntoView({ block: 'center' }); }); },
        answer(question) {
          const problem = demo.confirmAnswer(this.model, question.key, ui.currentUser); this.answerErrors[question.key] = problem || '';
          if (problem) this.focusQuestion(question);
          else { this.$nextTick(() => host.querySelector('textarea[aria-label="' + question.label + '"]')?.focus({ preventScroll: true })); ui.notify('核对结论已更新至对应说明'); }
          updateLabelActions(state);
        },
        edited(question) { question.deferred = false; this.answerErrors[question.key] = ''; },
        defer(question) { question.resolved = false; question.deferred = true; demo.refreshAnswers(this.model); updateLabelActions(state); },
        addMapping() { this.model.valueDetails.mappings.push({ code: '', meaning: '' }); },
        dependency(value) { const ref = JSON.parse(value), table = DataSourceDemo.objects(record.table.source).find(table => table.id === ref.table), item = table && demo.get(table).items.find(item => item.id === ref.entry); return { table, item }; },
        dependencyState(value) { const { item, table } = this.dependency(value), ref = JSON.parse(value), bound = entry.dependencyRevisions?.find(dep => dep.table === ref.table && dep.entry === ref.entry); return !item ? '缺少依赖' : demo.isDismissed(item) ? item.disposition : needsWork(item) ? '待确认' : bound && (bound.revision !== item.revision || bound.objectId !== table.identity || bound.generation !== table.generation) ? '已确认 · 修订 ' + item.revision + '（原 ' + bound.revision + '）' : '已确认'; },
        openDependency(value, event) { const { table, item } = this.dependency(value), ref = JSON.parse(value), bound = entry.dependencyRevisions?.find(dep => dep.table === ref.table && dep.entry === ref.entry); if (item && bound && (bound.revision !== item.revision || bound.objectId !== table.identity || bound.generation !== table.generation)) DataSourcesPage.viewGovernanceDependency(main, ui, record, entry, ref, event.currentTarget); else if (item) open(table.source, demo.objectName(table), { tab: item.kind, field: item.field || item.id }); }
      },
      template: `<div><div v-if="busy" class="gov-generation-progress" role="status"><span class="gov-progress-dot"></span>{{phase}}<small>原型演示</small></div>
        <p v-if="error" id="gov-editor-error" class="form-error" role="alert">{{error}}</p>
        <el-form :model="model" label-position="left" :label-width="model.name!==undefined ? '144px' : '112px'" :disabled="busy" @submit.prevent>
          <el-form-item v-if="model.name!==undefined" :label="kind==='relations' ? '关联名称' : '指标名称'" required><el-input v-model="model.name" :readonly="readonly" :aria-label="kind==='relations' ? '关联名称' : '指标名称'" :maxlength="80"></el-input></el-form-item>
          <section v-for="(group,index) in groups" :key="index" class="gov-field-group"><h3 v-if="group.title">{{group.title}}</h3>
            <template v-for="field in group.fields" :key="field.key">
              <template v-if="kind==='fields' && field.key==='values'">
                <el-form-item v-if="fieldType==='measure' || model.valueDetails.unit" label="单位"><el-input v-model="model.valueDetails.unit" :readonly="readonly" aria-label="单位" :aria-invalid="!!error && validationIssues.some(issue=>issue.control==='单位')" :aria-describedby="error ? 'gov-editor-error' : undefined" placeholder="如：元、件、千克"></el-input></el-form-item>
                <el-form-item v-if="fieldType==='enum' || model.valueDetails.mappings.length" label="编码与含义"><div class="gov-enum-editor"><table class="gov-value-table"><thead><tr><th>编码</th><th>含义</th><th v-if="!readonly" aria-label="操作"></th></tr></thead><tbody><tr v-for="(row,i) in model.valueDetails.mappings" :key="i"><td><el-input v-model="row.code" :readonly="readonly" :aria-label="'编码 '+(i+1)"></el-input></td><td><el-input v-model="row.meaning" :readonly="readonly" :aria-label="'编码含义 '+(i+1)"></el-input></td><td v-if="!readonly"><el-button link type="danger" :aria-label="'移除编码 '+(i+1)" @click="model.valueDetails.mappings.splice(i,1)">移除</el-button></td></tr></tbody></table><el-button v-if="!readonly" link type="primary" @click="addMapping">添加编码</el-button></div></el-form-item>
              </template>
              <el-form-item :label="kind==='fields' && field.key==='values' ? '取值说明' : field.label" :required="!!field.required">
                <el-input v-model="model[field.key]" type="textarea" :autosize="{minRows:2,maxRows:8}" :maxlength="600" :readonly="readonly" :aria-label="field.label" :aria-required="!!field.required" :aria-describedby="error && validationIssues.some(issue=>issue.key===field.key) ? 'gov-editor-error' : pendingQuestions(field).length ? 'gov-ai-question-'+field.key : undefined" :aria-invalid="!!error && validationIssues.some(issue=>issue.key===field.key && !issue.control)" :placeholder="field.placeholder"></el-input>
                <article v-for="question in pendingQuestions(field)" :key="question.key" :id="'gov-ai-question-'+question.key" class="gov-ai-question" :aria-label="field.label+'待核对'">
                  <header><strong :id="'gov-ai-question-'+question.key+'-prompt'">{{question.question}}</strong><span class="status-tag is-pending">{{question.deferred ? '暂不确定' : '待核对'}}</span></header><small>{{question.reason}}</small>
                  <el-input v-model="question.response" type="textarea" :autosize="{minRows:2,maxRows:6}" :readonly="readonly || busy" :aria-label="question.label+'的回答'" :aria-invalid="!!answerErrors[question.key]" :aria-describedby="'gov-ai-question-'+question.key+'-prompt'+(answerErrors[question.key] ? ' gov-answer-error-'+question.key : '')" placeholder="填写核对结论" @input="edited(question)"></el-input>
                  <p v-if="answerErrors[question.key]" :id="'gov-answer-error-'+question.key" class="gov-check-error" role="alert">{{answerErrors[question.key]}}</p>
                  <div v-if="!readonly" class="gov-ai-answer-actions"><el-button link :disabled="busy" :aria-label="'暂不确定'+question.label" @click="defer(question)">暂不确定</el-button><el-button :disabled="busy" :aria-label="'确认'+question.label+'回答'" @click="answer(question)">确认核对结论</el-button></div>
                </article>
                <el-button v-if="checkedQuestions(field).length" link type="primary" class="gov-check-history" :aria-label="'查看'+field.label+'核对记录'" @click="viewChecks(field,$event)">查看核对记录</el-button>
              </el-form-item>
            </template>
          </section>
          <section v-if="dependencyOptions.length" class="gov-dependency-editor"><el-form-item label="依赖条目"><el-select v-model="selectedDependencies" multiple filterable :disabled="readonly" aria-label="依赖条目" placeholder="选择关联、筛选或计算使用的定义"><el-option v-for="option in dependencyOptions" :key="option.value" :label="option.label" :value="option.value"></el-option></el-select></el-form-item><div class="gov-inline-dependencies"><p v-if="reviewReason" class="gov-review-reason">{{reviewReason}}</p><el-button v-for="value in selectedDependencies" :key="value" link type="primary" @click="openDependency(value,$event)">{{dependencyOptions.find(option=>option.value===value)?.label || '缺少依赖'}} · {{dependencyState(value)}}</el-button></div></section>
          <el-form-item v-if="model.basis && !questions.length" label="人工补充说明"><el-input v-model="model.basis" type="textarea" :autosize="{minRows:2,maxRows:6}" :readonly="readonly" aria-label="人工补充说明"></el-input></el-form-item>
        </el-form></div>`
    });
    editor.append(host);
    if (entry.reviewer) editor.append(el('p', 'gov-status-line', entry.reviewer + ' · ' + entry.reviewedAt + ' · 修订 ' + entry.revision));
    reference.append(sourceContext(record, entry, impacts, state), inputs);
    if (['overview', 'fields'].includes(entry.kind)) reference.append(relationReferences(record, entry));
    layout.append(editor, reference); body.append(layout);
    return body;
  }
  function relationReferences(record, entry) {
    const section = el('section', 'gov-relation-references'), header = el('header'), refs = demo.relationsFor(record, entry.field);
    header.append(el('h3', '', entry.kind === 'overview' ? '与其他表的关系' : '关联含义'), action('管理关联', () => select('relations')));
    section.append(header);
    if (!refs.length) section.append(el('p', 'gov-empty-hint', '暂无关联信息'));
    refs.forEach(ref => {
      const row = el('article', 'gov-relation-reference'), heading = el('header');
      heading.append(action(ref.entry.name, () => open(ref.record.table.source, demo.objectName(ref.record.table), { tab: 'relations', field: ref.entry.id }), 'gov-relation-' + ref.entry.id), badge(entryState(ref.entry)), ...(ref.entry.blocked ? [badge('禁止使用')] : []));
      row.append(heading);
      if (hasDefinition(ref.entry)) row.append(definition(ref.entry.content, 'relations', true));
      section.append(row);
    });
    return section;
  }
  // 正文只保留生成依据摘要、当前结构和直接差异，完整记录复用同一来源抽屉。
  function sourceContext(record, entry, impacts, state) {
    const card = el('section', 'gov-source-context'); card.setAttribute('aria-label', '依据与来源');
    if (entry.ai) {
      const task = demo.tasks.find(task => task.id === entry.ai.taskId), proof = el('section', 'gov-proof-summary'), line = el('header', 'gov-proof-header');
      line.append(el('h3', '', '生成依据'));
      if (task) line.append(action('查看详情', trigger => DataSourcesPage.viewGovernanceSuggestion(main, ui, task, entry.id, trigger), 'gov-ai-evidence'));
      proof.append(line);
      const explanation = entry.ai.reasoning || task?.failure || entry.ai.gap;
      if (explanation) { const text = el('p', 'gov-proof-reason', explanation); text.title = explanation; proof.append(text); }
      const facts = el('div', 'gov-proof-facts');
      if (entry.ai.coverage?.length) facts.append(el('span', '', '已检查 ' + entry.ai.coverage.length + ' 项内容'));
      if (entry.ai.structure?.snapshot) facts.append(el('span', '', '结构快照 ' + entry.ai.structure.snapshot));
      if (entry.ai.sampling?.count) facts.append(el('span', '', entry.ai.sampling.count + (entry.ai.sampling.columns ? ' 行' + (entry.ai.sampling.simulated ? '模拟完整记录' : '完整记录') : ' 条' + (entry.ai.sampling.simulated ? '模拟样本' : '样本'))));
      if (task?.notes?.length) facts.append(el('span', '', task.notes.length + ' 份业务说明'));
      if (facts.childNodes.length) proof.append(facts);
      const notice = el('p', 'gov-empty-hint', '当前标签已修改，详情保留生成时的原始建议。');
      notice.hidden = !hasSuggestion(entry) || JSON.stringify(entry.content) === JSON.stringify(entry.ai.content);
      if (state) state.modifiedNotice = notice; proof.append(notice); card.append(proof);
    }
    const snapshot = demo.structureFor(record, entry), header = el('header', 'gov-context-header');
    const sourcePath = entry.retired ? record.table.path + (entry.field ? '.' + entry.field : '') : snapshot.path;
    const path = el('code', 'gov-context-path', sourcePath); path.title = sourcePath;
    header.append(el('h3', '', entry.retired ? '移除依据' : ['relations', 'metrics'].includes(entry.kind) ? '相关结构' : '当前来源'), path);
    if (!entry.retired && allowed('catalog.view')) header.append(action('查看结构', trigger => DataSourcesPage.viewGovernanceStructure(main, ui, snapshot, trigger), 'gov-context-structure'));
    card.append(header);
    const field = record.table.fields.find(field => field.name === entry.field), comment = entry.kind === 'fields' ? field?.comment ?? entry.comment ?? '' : record.table.comment || '';
    const facts = el('div', 'gov-context-facts');
    const values = entry.retired ? ['已移除'] : entry.kind === 'fields' ? [entry.type, ...(field?.nullable ? [field.nullable === '是' ? '可空' : field.nullable === '否' ? '非空' : field.nullable] : [])] : [record.table.type];
    values.forEach(value => facts.append(el('span', '', value)));
    if (!entry.retired) { const annotation = el('span', 'gov-context-comment', comment ? '原注释：' + comment : '无原注释'); annotation.title = comment || '无原注释'; facts.append(annotation); } card.append(facts);
    if (impacts.length) {
      const changes = el('ul', 'gov-context-changes');
      impacts.forEach(impact => {
        const structural = impact.kind === '结构变化';
        const change = DataSourceDemo.changes.find(change => structural && change.id === impact.originId && change.source === impact.source);
        const row = el('li', 'gov-context-change');
        row.append(el('span', 'gov-change-kind', change?.kind || (structural ? '结构变化' : '资料修订')));
        const diff = el('div', 'gov-context-diff');
        if (change) {
          const display = value => value === '' ? '（空）' : String(value ?? '未读取');
          const before = el('span', 'gov-context-before', display(change.before)), after = el('span', 'gov-context-after', display(change.after));
          before.title = '变化前：' + display(change.before); after.title = '变化后：' + display(change.after);
          diff.append(before, el('span', 'gov-context-arrow', '→'), after);
        } else {
          const text = structural ? '来源变化记录当前不可用' : allowed('business-notes.view') ? impact.title + ' · 修订 ' + impact.beforeRevision + ' → ' + impact.noteRevision : '业务说明发生变化，原文查看需要相应权限';
          const summary = el('span', 'gov-context-note', text); summary.title = text; diff.append(summary);
        }
        row.append(diff);
        if (structural && !change) row.append(el('span', 'gov-context-restricted', '来源不可用'));
        else if (allowed(structural ? 'collections.view' : 'business-notes.view')) {
          const view = action('查看证据', trigger => DataSourcesPage.viewImpactSource(main, ui, impact, trigger), 'gov-context-impact-' + impact.id);
          view.setAttribute('aria-label', '查看' + (change?.kind || '资料修订') + '来源证据：' + impact.originId); row.append(view);
        }
        else row.append(el('span', 'gov-context-restricted', '查看受限'));
        changes.append(row);
      });
      card.append(changes);
    }
    const refs = demo.citations(entry); if (refs.length) card.append(citationList(refs, '引用的业务资料'));
    return card;
  }
  function labelActions(record, entry) {
    const state = labelState(record, entry), bar = el('footer', 'gov-page-footer gov-inline-footer'), layout = el('div', 'gov-footer-layout'), content = el('div', 'gov-footer-main'), identity = el('div', 'gov-current-item'), reason = el('small'); reason.id = 'gov-confirm-issue'; reason.setAttribute('aria-live', 'polite'); identity.append(el('strong', '', entryLabel(entry)), reason); content.append(identity);
    const actions = el('div', 'gov-entry-actions'), controls = { reason, status: state.status }; state.controls = controls;
    controls.repair = action('定位需补充内容', () => focusLabelIssue(state), 'gov-repair-' + entry.id); identity.append(controls.repair);
    const dismissed = demo.isDismissed(entry);
    if (!dismissed && allowed('governance.suggest')) { controls.generate = button(entry.ai ? '重新生成 AI 建议' : '生成 AI 建议', () => generateLabel(record, entry), false, 'gov-generate-' + entry.id); actions.append(controls.generate); }
    if (!dismissed && allowed('governance.edit')) { controls.save = button('保存草稿', () => saveLabel(state), false, 'gov-save-' + entry.id); actions.append(controls.save); }
    if (!dismissed && allowed('governance.confirm')) { controls.confirm = button('确认治理', () => saveLabel(state, true), true, 'gov-confirm-' + entry.id); controls.confirm.setAttribute('aria-describedby', reason.id); actions.append(controls.confirm); }
    if (['relations', 'metrics'].includes(entry.kind) && allowed('governance.edit')) {
      const lifecycle = disposition => {
        if (state.busy) return;
        if (!dismissed && labelDirty(state)) {
          const error = demo.act(record, entry, 'save', ui.currentUser, { ...JSON.parse(JSON.stringify(state.model)), name: state.model.name?.trim() || entry.name });
          if (error && error !== '内容未变化。') { state.vm.error = error; return; }
        }
        const error = demo.act(record, entry, dismissed ? 'restore' : 'dismiss', ui.currentUser, { disposition });
        if (error) { ui.notify(error); return; }
        labelDrafts.delete(state.key); labelSession = null; refresh(); ui.notify(dismissed ? '候选已恢复，仍待确认' : '候选已' + disposition + '，记录已保留');
      };
      if (dismissed) actions.append(button('恢复处理', () => lifecycle(), false, 'gov-restore-' + entry.id));
      else if (!demo.hasConfirmedHistory(entry)) actions.append(ui.moreActions([{ label: '不采用', action: () => lifecycle('不采用') }, { label: '不适用', action: () => lifecycle('不适用') }], 'gov-candidate-actions', undefined, document.body, 'top-end'));
    }
    const next = nextPending(activeRecord, entry); controls.next = button(next ? '下一待处理项' : '查看状态与依据', () => next ? select(next.kind, next.field || next.id) : select('evidence'), true, 'gov-next'); actions.append(controls.next);
    content.append(actions); layout.append(content); bar.append(layout); updateLabelActions(state); return bar;
  }
  // 待核对项优先，但不重排来源字段；显式定位和回到页签时保留用户选择。
  function pendingEntries(record) {
    const affected = new Set(demo.attention(record).affected.map(entry => entry.id));
    return record.items.filter(needsWork).sort((a, b) => Number(affected.has(b.id)) - Number(affected.has(a.id)));
  }
  function selectedEntry(record, tab) {
    const entries = entriesFor(record, tab), explicit = route.params.get('field');
    return entries.find(entry => entry.field === explicit || entry.id === explicit) || entries.find(entry => entry.id === itemSelections.get(record.table.id + ':' + tab)) || pendingEntries(record).find(entry => entry.kind === tab) || entries[0];
  }
  function nextPending(record, current) { return pendingEntries(record).find(entry => entry.id !== current?.id); }
  function workReason(record, entry) {
    if (entry.gap) return entry.gap;
    const source = GovernanceImpactDemo.records.find(item => item.state !== '已完成' && item.source === record.table.source && item.bindings.some(binding => binding.table === record.table.id && binding.entry === entry.id));
    const change = source && DataSourceDemo.changes.find(change => change.id === source.originId && change.source === source.source);
    return change ? GovernanceImpactDemo.checksFor(change) : demo.confirmIssue(record, entry) || '核对当前定义和依据后确认。';
  }
  function badge(text) { return el('span', 'status-tag' + (['未填写', '待审核', '变更待核对', '有待核对问题', '待确认', '业务定义待核对', '待治理', '治理中', '知识处理中'].includes(text) ? ' is-pending' : ['未发布', '不采用', '不适用'].includes(text) ? ' is-disabled' : text === '禁止使用' ? ' ds-status-danger' : ''), text); }
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
    const reading = readingPane(currentMain);
    returns.set(table.id, sameTask&&returns.get(activeRecord.table.id) || { hash: location.hash, previous: returns.get(table.id), focus: document.activeElement?.dataset.focus, row: [...(scroll?.querySelectorAll('tbody tr') || [])].indexOf(document.activeElement?.closest('tr')), scroll: scroll?.scrollTop || 0, readingTop: reading?.scrollTop || 0, panels: [...(currentMain?.querySelectorAll('details[data-gov-panel][open]') || [])].map(node => node.dataset.govPanel) });
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
    if (!field && ['fields', 'relations', 'metrics'].includes(tab)) field = itemSelections.get(activeRecord.table.id + ':' + tab);
    route.params.set('tab', tab); if (field) route.params.set('field', field); else route.params.delete('field');
    history.replaceState(null, '', path(route.source.id, demo.objectName(activeRecord.table), Object.fromEntries(route.params)));
    refresh(); requestAnimationFrame(() => main.querySelector(field ? '[data-entry-title]' : '.gov-tabs .is-active')?.focus({ preventScroll: true }));
  }
  function context(source) {
    const host = el('section', 'gov-context prototype-ui');
    PrototypeUI.mount(host, { data: () => ({ selected: source.id, sources: DataSourceDemo.sources }), methods: { change(id) { DataSourceDemo.selected = id; location.hash = path(id); } }, template: '<el-select v-model="selected" aria-label="当前数据源" filterable @change="change"><el-option v-for="source in sources" :key="source.id" :label="source.name" :value="source.id"></el-option></el-select>' });
    if (allowed('business-notes.view')) host.append(button('业务说明', () => { location.hash = '#business-notes/' + source.id; }));
    if (allowed('knowledge.view')) host.append(button('知识文档', () => { location.hash = '#knowledge/' + source.id; }));
    return host;
  }
  function scopeNotice(source) {
    const change = source.scopeChange;
    if (!change) return [];
    const panel = el('aside', 'ds-notice gov-scope-notice');
    panel.setAttribute('role', 'note'); panel.setAttribute('aria-label', '采集范围调整');
    panel.append(el('strong', '', '采集范围已调整'));
    const addressChanged = change.beforeAddress !== change.afterAddress;
    panel.append(el('p', 'ds-prose', (addressChanged ? change.beforeAddress + ' · ' : '') + change.before + ' → ' + (addressChanged ? change.afterAddress + ' · ' : '') + change.after));
    panel.append(el('p', 'ds-prose', change.collected ? '请按需核对已有定义、关联和指标是否仍适用。' : '请重新采集成功后按需复核。'));
    return [panel];
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
    if (pending.length) {
      const parts = [['overview', '表说明'], ['fields', '个字段'], ['relations', '条关联'], ['metrics', '个指标']].flatMap(([kind, label]) => { const count = pending.filter(entry => entry.kind === kind).length; return count ? [kind === 'overview' ? label : count + ' ' + label] : []; });
      cell.append(action(parts.join('、'), () => open(record.table.source, demo.objectName(record.table), { tab: 'evidence' })));
    }
    if (!cell.childNodes.length) cell.append(ui.textCell('—'));
    return cell;
  }
  function impactCell(record) {
    const cell = el('div', 'gov-work-cell'), work = demo.attention(record);
    if (DataSourceDemo.pendingVerification(record.table).length) return ui.textCell('来源事实待核实');
    work.affected.slice(0, 2).forEach(entry => { const label = entry.kind === 'overview' ? '表说明' : entryLabel(entry); const control = action(label, () => openEntry(record, entry), 'gov-affected-' + record.table.id + '-' + entry.id); control.title = entry.name + ' · 业务定义待核对'; cell.append(control); });
    if (work.affected.length > 2) cell.append(action('另 ' + (work.affected.length - 2) + ' 项', () => open(record.table.source, demo.objectName(record.table), { tab: 'evidence' })));
    if (!cell.childNodes.length) cell.append(ui.textCell('—'));
    return cell;
  }
  function list(source) {
    if (!queries.has(source.id)) queries.set(source.id, { keyword: '', attention: '', impact: '', status: 'all', page: 1, pageSize: 10 });
    const query = queries.get(source.id), tables = DataSourceDemo.objects(source.id);
    const rows = tables.filter(table => {
      const record = demo.get(table), state = demo.summary(record).state, work = demo.attention(record);
      const matchesWork = !query.attention || work.pending.some(entry => !work.affected.includes(entry));
      const matchesImpact = !query.impact || (query.impact === 'affected' ? work.affected.length : query.impact === 'source' ? DataSourceDemo.pendingVerification(table).length : !work.affected.length && !DataSourceDemo.pendingVerification(table).length);
      return matchesWork && matchesImpact && AdminQuery.matches([demo.objectName(table), table.label, table.comment], query.keyword) && (query.status === 'all' || query.status === state);
    });
    const reset = () => { Object.assign(query, { keyword: '', attention: '', impact: '', status: 'all', page: 1 }); refresh(); };
    const filters = RecordControls.filters({ value: query, includeDates: false, fields: [{ key: 'keyword', label: '表名、业务名称或原注释', placeholder: '表名、业务名称或原注释' }, { key: 'attention', label: '待处理内容', options: [{ value: 'pending', label: '待补充或审核' }] }, { key: 'impact', label: '变更后待核对项', options: [{ value: 'affected', label: '业务定义待核对' }, { value: 'clear', label: '无待核对项' }, { value: 'source', label: '来源事实待核实' }] }], statuses: ['全部治理进度', '待治理', '治理中', '已治理'].map((label, index) => ({ label, value: index ? label : 'all' })), statusLabel: '治理进度', onApply: value => { Object.assign(query, value); refresh(); }, onReset: reset });
    query.page = Math.max(1, Math.min(query.page, Math.ceil(rows.length / query.pageSize)));
    const panel = el('section', 'record-panel'), empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('search'), el('p', '', tables.length ? '没有匹配的治理对象' : '当前数据源尚无已采集对象'));
    if (tables.length) empty.append(button('清空筛选', reset));
    panel.append(RecordTable.render({ caption: '表治理', columns: [{ key: 'name', label: '业务名称 / 表', width: 180 }, { key: 'meaning', label: '表用途', width: 180 }, { key: 'work', label: '待处理内容', width: 160 }, { key: 'impact', label: '变更后待核对项', width: 200 }, { key: 'status', label: '表说明与字段进度', width: 170 }, { key: 'actions', label: '操作', width: 100, fixed: 'right' }], items: rows.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), empty,
      row: table => { const record = demo.get(table), progress = demo.summary(record), meaning = ui.textCell(record.items[0].content.meaning || '—'); if (!record.items[0].content.meaning) meaning.classList.add('gov-value-empty'); return [stack(ui.button(table.label || demo.objectName(table), () => openTable(table), 'cell-title', 'gov-object-' + table.id), demo.objectName(table)), meaning, workCell(record), impactCell(record), stack(badge(progress.state), progress.total + ' 项 / 已确认 ' + progress.confirmed), action('治理', () => openTable(table), 'gov-open-' + table.id)]; } }),
      RecordControls.pager({ ...query, total: rows.length }, page => { query.page = page; refresh(); }, size => { query.pageSize = size; query.page = 1; refresh(); }));
    // 缺少技术映射的资料留在当前表列表定位；不另建变化清单，也不重复核实来源。
    const unresolved = GovernanceImpactDemo.records.filter(impact => impact.source === source.id && impact.kind === '业务说明' && impact.state !== '已完成' && !impact.bindings.length);
    const selected = GovernanceImpactDemo.records.find(impact => impact.source === source.id && impact.id === route.params.get('impact'));
    if (selected && !demo.impactTarget(selected).table && !unresolved.includes(selected)) unresolved.unshift(selected);
    const queryPanel = el('section', 'gov-query-panel'); queryPanel.append(context(source), filters);
    main.replaceChildren(ui.pageHeading('治理工作台'), queryPanel, ...scopeNotice(source), ...unresolved.map(impact => unmappedImpact(impact, impact === selected)), panel);
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
  function unmappedImpact(impact, selected = false) {
    const panel = el('section', 'gov-unmapped'), text = el('div');
    const readable = impact.kind === '结构变化' || allowed('business-notes.view');
    text.append(el('strong', '', readable ? impact.title : '业务说明待关联'), el('p', '', impact.kind === '业务说明' ? '资料变化尚未关联治理条目' : '来源对象尚未定位，请核对采集范围'));
    const actions = el('div', 'gov-toolbar-actions');
    if (allowed(impact.kind === '结构变化' ? 'collections.view' : 'business-notes.view')) actions.append(action('查看来源', trigger => DataSourcesPage.viewImpactSource(main, ui, impact, trigger)));
    if (impact.kind === '业务说明' && !impact.bindings.length && allowed('governance.edit')) actions.append(button('关联条目', trigger => associateImpact(impact, trigger), true));
    if (selected && route.params.get('from') === 'business-notes') actions.append(button('返回业务说明', () => { DataSourceDemo.selected = impact.source; location.hash = '#business-notes'; }));
    panel.append(text, actions); return panel;
  }
  function definition(content, kind, omitEmpty = false) {
    const dl = el('dl', 'gov-definition');
    const labels = kind === 'relations' ? ['关联条件', '关系类型与完整键', '适用业务与限制'] : ['指标含义', '计算方法与条件', '单位、时间与使用限制'];
    const fields = labelFields[kind] || ['meaning', 'values', 'usage'].map((key, index) => ({ key, label: labels[index] }));
    fields.forEach(({ key, label }) => { if (omitEmpty && !content?.[key]) return; const row = el('div'); row.append(el('dt', '', label), el('dd', (content?.[key] ? '' : 'gov-value-empty') + (kind === 'metrics' && key === 'values' ? ' gov-expression' : ''), content?.[key] || '未填写')); dl.append(row); }); return dl;
  }
  function createEntry(record, kind, trigger) {
    let savedEntry;
    ui.openForm({ title: '新增' + kindLabel(kind), trigger, value: { name: '' }, fields: [{ key: 'name', label: kindLabel(kind) + '名称', required: true, full: true }], protectChanges: true, saveLabel: '开始整理', save: data => {
      const result = demo.addRelation(record, ui.currentUser, data, kind); savedEntry = result.entry; return result.error;
    }, onSaved: () => select(kind, savedEntry.id), success: '已创建待确认条目' });
  }
  function apply(record, entry, trigger) {
    const error = demo.act(record, entry, 'confirm', ui.currentUser);
    if (error) { ui.notify(error); return; }
    refresh(); ui.notify('当前条目已确认');
    requestAnimationFrame(() => (main.querySelector('[data-focus="' + trigger.dataset.focus + '"]') || main.querySelector('[data-entry-title]'))?.focus({ preventScroll: true }));
  }
  async function discoverCandidates(record, selectedKinds, trigger, navigate = true) {
    try { if (labelSession?.host?.isConnected && labelDirty(labelSession)) await beforeTabLeave(); } catch { return { cancelled: true }; }
    const previous = discoveryRuns.get(record.table.id); if (previous?.busy) return { cancelled: true };
    const state = { busy: true, kinds: [...selectedKinds], message: '' }, hash = location.hash, signature = GovernancePrototype.discoverySignature(record), session = labelSession?.host?.isConnected ? labelSession : null;
    discoveryRuns.set(record.table.id, state);
    const original = trigger?.textContent; if (trigger) { trigger.disabled = true; trigger.textContent = '发现中…'; }
    const feedback = el('p', 'gov-discovery-progress', '正在整理结构与有效业务资料…'); feedback.setAttribute('role', 'status'); main.querySelector('.gov-content')?.prepend(feedback);
    if (session) { session.busy = true; session.vm.busy = true; session.vm.phase = '正在发现新的关联与指标候选'; updateLabelActions(session); }
    try {
      await new Promise(resolve => setTimeout(resolve, 520));
      if (location.hash !== hash) return { cancelled: true };
      const result = GovernancePrototype.discover(record, selectedKinds, ui.currentUser, signature);
      if (result.error) { state.busy = false; state.message = result.error; ui.notify(result.error); refresh(); return result; }
      state.message = result.created.length ? '发现 ' + result.created.length + ' 个新候选' : result.skipped.length ? '相关候选已存在，保留当前内容' : '当前结构和业务资料中未发现' + selectedKinds.map(kindLabel).join('或') + '候选';
      const next = result.created[0] || result.skipped.find(row => !demo.isDismissed(row.entry));
      state.busy = false; labelSession = null;
      if (navigate && next) select(next.entry.kind, next.entry.id); else refresh();
      ui.notify(state.message); return result;
    } catch { state.busy = false; state.message = '发现候选失败，已有内容已保留，请重试。'; ui.notify(state.message); refresh(); return { error: state.message }; }
    finally { state.busy = false; feedback.remove(); if (session) { session.busy = false; session.vm.busy = false; session.vm.phase = ''; updateLabelActions(session); } if (trigger?.isConnected) { trigger.disabled = false; trigger.textContent = original; } }
  }
  function generate(record, trigger, entry) {
    if (entry && isLabel(entry)) { generateLabel(record, entry); return; }
    if (labelSession?.host?.isConnected && labelDirty(labelSession)) { Promise.resolve(beforeTabLeave()).then(() => generate(record, trigger, entry)).catch(() => {}); return; }
    const notes = demo.availableNotes(record.table.source), dialog = ui.modal((entry?.kind === 'overview' ? '生成 AI 草稿 · ' : '生成 AI 建议 · ') + record.table.label, trigger), host = el('div', 'gov-generation-form');
    // 资料自动纳入，只读展示取用范围；只有提交生成才创建任务和引用。
    dialog.classList.add('gov-generation-dialog');
    dialog.append(host);
    PrototypeUI.mount(host, {
      data: () => ({ model: { kinds: entry ? [entry.kind] : ['overview', 'fields'], entries: record.items.filter(item => !item.retired && ['overview', 'fields'].includes(item.kind) && !demo.isDismissed(item) && item.review !== '已确认' && !item.editedBy).map(item => item.id) }, previousKinds: entry ? [entry.kind] : ['overview', 'fields'], rules: entry ? {} : { kinds: [{ required: true, message: '请选择生成范围', trigger: 'change' }] }, notes, prototypeMode: true, source: DataSourceDemo.find(record.table.source).name, scope: entry?.name, scopeError: false, error: '', notesId: dialog.getAttribute('aria-labelledby') + '-notes', options: kinds.slice(0, 4).map(([value, label]) => ({ value, label })) }),
      computed: { entries() { return [...record.items.filter(item => !item.retired && this.model.kinds.includes(item.kind)), ...this.model.kinds.filter(kind => ['relations', 'metrics'].includes(kind)).map(kind => ({ id: 'discover:' + kind, name: '发现新的' + kindLabel(kind) + '候选' }))]; } },
      methods: {
        cancel() { dialog.close(); },
        kindsChanged(values) { for (const kind of values.filter(kind => ['relations', 'metrics'].includes(kind) && !this.previousKinds.includes(kind))) if (!this.model.entries.includes('discover:' + kind)) this.model.entries.push('discover:' + kind); this.previousKinds = [...values]; },
        submit() {
          this.$refs.form.validate(valid => {
            if (!valid) { this.$nextTick(() => host.querySelector('input[type="checkbox"]').focus()); return; }
            const selectedKinds = [...this.model.kinds], ids = entry ? [entry.id] : this.model.entries.filter(id => record.items.some(item => item.id === id && selectedKinds.includes(item.kind))), discoverKinds = selectedKinds.filter(kind => this.model.entries.includes('discover:' + kind)), target = entry || record.items.find(item => ids.includes(item.id));
            if (!ids.length && !discoverKinds.length) { this.error = '请选择需要生成建议的条目。'; return; }
            if (discoverKinds.length) {
              dialog.close();
              discoverCandidates(record, discoverKinds, trigger, !ids.length).then(result => { if (result?.error || result?.cancelled || !target) return; select(target.kind, target.field || target.id); requestAnimationFrame(() => generateLabel(record, target, selectedKinds, true, ids)); });
              return;
            }
            if (target && isLabel(target)) {
              dialog.close(); select(target.kind, target.field || target.id);
              requestAnimationFrame(() => generateLabel(record, target, selectedKinds, true, ids));
            } else {
              this.error = demo.generate(record, selectedKinds, ui.currentUser, entry?.id) || '';
              if (this.error) { this.$nextTick(() => host.querySelector('.form-error').focus()); return; }
              dialog.close(); refresh(); ui.notify('输入资料已保留，尚未取得 AI 草稿');
            }
          });
        }
      },
      template: `<el-form ref="form" :model="model" :rules="rules" label-position="top" @validate="(_,valid)=>scopeError=!valid" @submit.prevent="submit">
        <div class="dialog-body">
          <p v-if="prototypeMode" class="gov-demo-warning">原型交互演示：生成结果、SQL 与样本均为模拟。</p>
          <p v-if="error" class="form-error" role="alert" tabindex="-1">{{error}}</p>
          <div class="gov-generation-context">
            <dl><dt>当前数据源</dt><dd>{{source}}</dd></dl>
            <dl v-if="scope"><dt>生成范围</dt><dd>{{scope}}</dd></dl>
            <el-form-item v-else label="生成范围" prop="kinds">
              <el-checkbox-group v-model="model.kinds" aria-label="生成范围" :aria-invalid="String(scopeError)" :aria-describedby="scopeError ? notesId+'-scope-error' : undefined" @change="value=>{error='';kindsChanged(value)}">
                <el-checkbox v-for="option in options" :key="option.value" :value="option.value">{{option.label}}</el-checkbox>
              </el-checkbox-group>
              <template #error="{error:message}"><div class="el-form-item__error" :id="notesId+'-scope-error'" role="alert">{{message}}</div></template>
            </el-form-item>
          </div>
          <section v-if="!scope" class="gov-generation-targets"><h3>治理条目</h3><el-checkbox-group v-model="model.entries" aria-label="批量治理条目"><el-checkbox v-for="item in entries" :key="item.id" :value="item.id" :aria-label="'批量生成 '+item.name" :disabled="!!item.disposition">{{item.name}}<span v-if="item.disposition || item.review==='已确认' || item.editedBy" class="gov-protected">{{item.disposition || (item.review==='已确认' ? '已确认' : '人工内容')}}</span></el-checkbox></el-checkbox-group></section>
          <section class="gov-generation-notes">
            <header><h3 :id="notesId">自动取用业务说明</h3><span class="gov-generation-count">{{notes.length}} 份</span></header>
            <div v-if="notes.length" class="gov-generation-note-scroll" role="region" :aria-labelledby="notesId" tabindex="0">
              <ul><li v-for="note in notes" :key="note.id"><span>{{note.title}}</span><small>第 {{note.revision}} 版{{note.origin ? ' · '+note.origin : ''}}</small></li></ul>
            </div>
            <p v-else class="gov-generation-empty">暂无实际录入的业务说明；预置示例不用于治理。</p>
          </section>
        </div>
        <footer class="dialog-footer"><el-button @click="cancel">取消</el-button><el-button type="primary" native-type="submit">生成建议</el-button></footer>
      </el-form>`
    });
    dialog.showModal();
  }
  function taskLink(task, label = '查看取用记录') { return action(label, trigger => DataSourcesPage.viewGovernanceTask(main, ui, task, trigger)); }
  function citationList(refs, label) {
    const section = el('section', 'gov-citations'); section.append(el('h3', '', label));
    const groups = new Map();
    refs.forEach(ref => { const key = JSON.stringify([ref.source, ref.id, ref.revision]); if (!groups.has(key)) groups.set(key, []); groups.get(key).push(ref); });
    groups.forEach(group => {
      const ref = group[0];
      const row = el('div', 'gov-citation');
      if (allowed('business-notes.view')) {
        row.append(action(ref.title + ' · 第 ' + ref.revision + ' 版', trigger => DataSourcesPage.viewGovernanceReference(main, ui, ref, trigger)), el('small', 'cell-sub', ref.id + ' · ' + (ref.origin || '来源记录') + ' · ' + person(ref.author)));
        [...new Set(group.map(ref => ref.excerpt).filter(Boolean))].forEach(excerpt => row.append(el('blockquote', 'ds-prose gov-citation-excerpt', excerpt)));
      } else row.append(el('p', 'ds-prose', '已关联业务说明，当前账号无原文查看权限。'));
      section.append(row);
    });
    return section;
  }
  function review(record, entry) {
    if (isLabel(entry)) return labelReview(record, entry);
    const body = el('section', 'gov-review gov-record-review'), heading = el('header', 'gov-entry-title'), title = el('h2', '', entryLabel(entry));
    title.tabIndex = -1; title.dataset.entryTitle = entry.id;
    heading.append(title, badge(entryState(entry)), ...(entry.blocked ? [badge('禁止使用')] : [])); body.append(heading);
    if (entry.gap && needsWork(entry)) { const gap = el('aside', 'gov-task-focus'); gap.append(el('strong', '', '待核对问题'), el('p', '', entry.gap)); body.append(gap); }
    if (hasDefinition(entry)) body.append(definition(entry.content, entry.kind, entry.retired)); else if (!entry.retired) body.append(el('p', 'gov-value-empty', '尚未填写'));
    if (entry.dependencies?.length && !entry.retired) {
      const dependencies = el('section', 'gov-dependencies'); dependencies.append(el('h3', '', '依赖条目'));
      const list = el('div', 'gov-dependency-list');
      entry.dependencies.forEach(dep => {
        const ref = typeof dep === 'string' ? { table: record.table.id, entry: dep } : dep;
        const table = DataSourceDemo.objects(record.table.source).find(table => table.id === ref.table), target = table && demo.get(table).items.find(item => item.id === ref.entry);
        if (!target) { list.append(el('span', 'ds-notice is-danger', '缺少依赖：' + ref.entry)); return; }
        const control = button(demo.objectName(table) + '.' + (target.field || target.name), () => open(table.source, demo.objectName(table), { tab: target.kind, field: target.field || target.id }), false, 'gov-dependency-' + ref.table + '-' + ref.entry);
        control.append(badge(needsWork(target) ? '待确认' : '已确认'), ...(target.blocked ? [badge('禁止使用')] : [])); list.append(control);
      });
      dependencies.append(list); body.append(dependencies);
    }
    const impacts = GovernanceImpactDemo.records.filter(impact => impact.bindings.some(binding => binding.table === record.table.id && binding.entry === entry.id));
    body.append(sourceContext(record, entry, impacts));
    if (entry.basis) body.append(el('p', 'gov-record-note', entry.basis));
    if (entry.reviewer) body.append(el('p', 'gov-status-line', entry.reviewer + ' · ' + entry.reviewedAt + ' · 修订 ' + entry.revision));
    return body;
  }
  function entryActions(record, entry) {
    if (isLabel(entry)) return labelActions(record, entry);
    const bar = el('footer', 'gov-page-footer'), identity = el('div', 'gov-current-item'), actions = el('div', 'gov-entry-actions');
    const issue = needsWork(entry) ? demo.confirmIssue(record, entry) : '';
    identity.append(el('strong', '', entryLabel(entry)));
    if (issue) { const reason = el('small', '', issue); reason.id = 'gov-confirm-issue'; identity.append(reason); }
    if (allowed('governance.confirm') && needsWork(entry)) {
      const confirm = button(entry.retired ? '确认移除处理' : '确认治理', trigger => apply(record, entry, trigger), !issue, 'gov-confirm-' + entry.id);
      confirm.disabled = Boolean(issue); if (issue) { confirm.title = issue; confirm.setAttribute('aria-describedby', 'gov-confirm-issue'); } actions.append(confirm);
    }
    if (!needsWork(entry)) { const next = nextPending(record, entry); actions.append(button(next ? '下一待处理项' : '查看状态与依据', () => record !== activeRecord ? open(record.table.source, demo.objectName(record.table), { tab: next?.kind || 'evidence', field: next && (next.field || next.id) }) : next ? select(next.kind, next.field || next.id) : select('evidence'), true, 'gov-next')); }
    if (!actions.childNodes.length) identity.append(el('small', '', '当前账号仅可查看，处理需相应权限。'));
    bar.append(identity, actions); return bar;
  }
  function evidencePage(record, sources) {
    const page = el('section', 'gov-evidence-page'), pending = pendingEntries(record), head = el('header', 'gov-section-head');
    head.append(el('h2', '', pending.length ? '待处理 · ' + pending.length + ' 项' : '暂无待处理事项'));
    if (pending.length > 1) head.append(button('继续处理', () => select(pending[0].kind, pending[0].field || pending[0].id), true, 'gov-continue'));
    page.append(head);
    const subject = entry => {
      const host = el('div', 'gov-record-subject'); host.append(el('strong', '', entryLabel(entry)));
      if (entryLabel(entry) !== entry.name) host.append(el('small', '', entry.name));
      if (entry.blocked) host.append(badge('禁止使用'));
      return host;
    };
    const section = (title, count) => { const host = el('section', 'gov-record-section'); host.append(el('h3', '', title + ' · ' + count)); page.append(host); return host; };
    if (pending.length) {
      const queue = el('ul', 'gov-work-queue');
      pending.forEach(entry => {
        const row = el('li', 'gov-work-row'), content = subject(entry);
        const status = entry.gap ? '有待核对问题' : entry.validity === '待复核' ? '变更待核对' : hasDefinition(entry) || entry.retired ? '待审核' : '未填写';
        if (entry.gap) content.append(el('p', '', entry.gap));
        row.append(content, badge(status), action(hasDefinition(entry) || entry.retired ? '核对' : '填写', () => select(entry.kind, entry.field || entry.id), 'gov-queue-' + entry.id));
        if (entry.kind !== 'fields' && kindLabel(entry.kind) !== entryLabel(entry)) content.append(el('small', '', kindLabel(entry.kind)));
        queue.append(row);
      }); page.append(queue);
    }
    const confirmed = record.items.filter(demo.isConfirmed);
    const dismissed = record.items.filter(demo.isDismissed);
    if (dismissed.length) {
      const excluded = section('不采用与不适用', dismissed.length);
      dismissed.forEach(entry => { const row = el('div', 'gov-record-row'); row.append(subject(entry), badge(entry.disposition), action('查看', () => select(entry.kind, entry.id))); excluded.append(row); });
    }
    if (confirmed.length) {
      const done = section('已确认', confirmed.length);
      confirmed.forEach(entry => { const row = el('div', 'gov-record-row'); row.append(subject(entry), el('small', 'gov-record-meta', [entry.reviewer, entry.reviewedAt, '修订 ' + entry.revision].filter(Boolean).join(' · ')), action('查看', () => select(entry.kind, entry.field || entry.id))); done.append(row); });
    }
    if (sources.length) {
      const changes = section('来源变更', sources.length);
      sources.forEach(impact => {
        const change = DataSourceDemo.changes.find(change => impact.kind === '结构变化' && change.id === impact.originId && change.source === impact.source);
        const readable = allowed(impact.kind === '结构变化' ? 'collections.view' : 'business-notes.view');
        const title = change ? change.kind + ' · ' + DataSourceDemo.changeTarget(change) : (readable ? impact.title : '业务说明修订');
        const row = el('div', 'gov-record-row'); row.append(el('span', 'gov-record-title', title), badge(impact.state));
        if (readable) row.append(action('查看来源', trigger => DataSourcesPage.viewImpactSource(main, ui, impact, trigger)));
        else row.append(el('small', 'gov-record-meta', '原文查看受限'));
        changes.append(row);
      });
    }
    const tasks = demo.tasksFor(record);
    if (tasks.length) {
      const generated = section('AI 生成记录', tasks.length);
      tasks.forEach(task => {
        const scope = task.entries.length === 1 ? task.entries[0].name : task.entries.length + ' 个条目';
        const row = el('div', 'gov-record-row'), name = el('div', 'gov-record-subject'); name.append(el('span', '', scope), el('small', '', task.id + ' · ' + person(task.actor) + ' · ' + task.at));
        row.append(name, badge(task.state), taskLink(task, '查看记录')); generated.append(row);
      });
    }
    // 操作前快照保留在事件详情，不把同一次动作前后各铺成一条可折叠记录。
    const labels = { '保存待确认内容': '保存草稿', '人工确认': '确认治理', '确认移除处理': '确认移除处理', '保留待确认': '记录待核对问题', '不采用 AI 建议': '不采用建议', '采用为待确认内容': '采用历史建议', '不采用候选': '不采用候选', '不适用候选': '不适用候选', '恢复候选': '恢复候选', '复核前确认内容': '依赖或来源变化前的确认' };
    const events = record.items.flatMap(entry => entry.history.filter(event => labels[event.action]).map(event => ({ entry, event, label: labels[event.action] }))).sort((a, b) => b.event.at.localeCompare(a.event.at));
    if (events.length) {
      const history = section('操作记录', events.length);
      events.forEach(({ entry, event, label }) => {
        const row = el('div', 'gov-record-row'), title = el('div', 'gov-record-subject'); title.append(el('span', '', label + ' · ' + (entry.kind === 'overview' ? '表说明' : entry.name)), el('small', '', event.actor + ' · ' + event.at));
        row.append(title, el('small', 'gov-record-meta', '修订 ' + event.revision), action('查看详情', trigger => DataSourcesPage.viewGovernanceOperation(main, ui, record, entry, event, trigger))); history.append(row);
      });
    }
    return page;
  }
  function detail(record) {
    activeRecord = record;
    const table = record.table, requested = route.params.get('tab'), field = route.params.get('field');
    const target = record.items.find(entry => field && (entry.field === field || entry.id === field));
    const tab = kinds.some(([key]) => key === requested) ? requested : target?.kind || pendingEntries(record)[0]?.kind || 'overview';
    const selected = selectedEntry(record, tab), owner = tab === 'relations' ? demo.relationsFor(record).find(ref => ref.entry === selected)?.record || record : record;
    if (selected) itemSelections.set(table.id + ':' + tab, selected.id);
    let origin = returns.get(table.id);
    // 同表条目跳转也有真实上一级；返回后还原原来的来源，避免面包屑指向自身。
    if (origin?.hash === location.hash) { origin = origin.previous; if (origin) returns.set(table.id, origin); else returns.delete(table.id); }
    const fromCatalog = route.params.get('from') === 'catalog';
    const rootOrigin = origin?.hash.split('?')[0].split('/').length<=2 && origin?.hash.startsWith('#governance');
    const originSection = route.params.get('from');
    const fromOtherObject = !originSection && origin?.hash.startsWith('#governance/') && origin.hash.split('?')[0].split('/').length > 2;
    const catalogParent = origin?.hash.startsWith('#catalog/') ? '结构详情' : '元数据目录';
    const parent = originSection === 'knowledge' ? '文档详情' : originSection === 'catalog' ? catalogParent : originSection === 'collections' ? '结构变化' : originSection === 'business-notes' ? '企业业务说明' : originSection === 'knowledge-update' ? '知识更新' : fromOtherObject ? '上一治理页' : '治理工作台';
    const parentHref = origin?.hash || (originSection === 'catalog' ? '#catalog' : originSection === 'collections' ? '#catalog/' + table.source + '/changes/' + encodeURIComponent(demo.objectName(table)) : originSection === 'business-notes' ? '#business-notes' : originSection === 'knowledge-update' ? '#knowledge/' + table.source + '/' + route.params.get('impact') : rootOrigin ? origin.hash : path(table.source));
    ui.setTitle(table.label + ' · ' + demo.objectName(table), parent, parentHref);
    const shell = el('section', 'gov-shell'), toolbar = el('header', 'gov-toolbar'), identity = el('div', 'gov-identity'), buttons = el('div', 'gov-toolbar-actions');
    const progress = demo.summary(record);
    identity.append(el('span', 'gov-object-context', route.source.name + ' · ' + table.type + ' · 采集快照 ' + table.version), el('span', 'gov-progress', '表说明与字段：' + progress.state + '（' + progress.total + ' / ' + progress.confirmed + '）'));
    if (fromCatalog) buttons.append(button('返回' + catalogParent, () => { DataSourceDemo.selected = table.source; location.hash = origin?.hash || '#catalog'; }));
    else if(['collections','business-notes'].includes(route.params.get('from'))) buttons.append(button(route.params.get('from')==='collections'?'返回结构变化':'返回业务说明',()=>{DataSourceDemo.selected=table.source;location.hash=parentHref;}));
    else if(originSection==='knowledge-update') buttons.append(button('返回知识更新',()=>{DataSourceDemo.selected=table.source;location.hash=parentHref;}));
    else if(originSection==='knowledge') buttons.append(button('返回文档详情',()=>{DataSourceDemo.selected=table.source;location.hash=parentHref;}));
    else if(fromOtherObject) buttons.append(button('返回上一治理页',()=>{location.hash=origin.hash;requestAnimationFrame(()=>requestAnimationFrame(()=>{
      (origin.panels || []).forEach(key=>{const panel=main.querySelector('details[data-gov-panel="'+CSS.escape(key)+'"]');if(panel)panel.open=true;});
      const reading=readingPane(main);if(reading)reading.scrollTop=origin.readingTop||0;
      main.querySelector('[data-focus="'+CSS.escape(origin.focus||'')+'"]')?.focus({preventScroll:true});
    }));}));
    if (allowed('governance.suggest')) buttons.append(button('批量生成 AI 建议', trigger => generate(record, trigger), false, 'gov-generate'));
    if (['relations', 'metrics'].includes(tab) && allowed('governance.suggest')) {
      const discover = button('发现 AI ' + kindLabel(tab), trigger => discoverCandidates(record, [tab], trigger), !entriesFor(record, tab).length, 'gov-discover-' + tab);
      discover.disabled = Boolean(discoveryRuns.get(table.id)?.busy); buttons.append(discover);
    }
    if (['relations', 'metrics'].includes(tab) && allowed('governance.edit')) buttons.append(button('新增' + kindLabel(tab), trigger => createEntry(record, tab, trigger), false, 'gov-create-' + tab));
    if (allowed('knowledge.view')) buttons.append(button('知识文档', () => { DataSourceDemo.selected = table.source; location.hash = '#knowledge/' + table.source; }, false, 'gov-knowledge'));
    toolbar.append(identity, buttons); shell.append(toolbar, ...scopeNotice(route.source));
    const detailKinds = kinds.map(([key, label]) => { const entries = entriesFor(record, key); return [key, key === 'overview' ? '表说明' : key === 'evidence' ? label : label + '（' + entries.length + ' / ' + entries.filter(demo.isConfirmed).length + '）']; });
    const tabs = el('div', 'gov-tabs prototype-ui'); PrototypeUI.mount(tabs, { data: () => ({ tab, kinds: detailKinds }), methods: { change: value => select(value), beforeLeave: beforeTabLeave }, template: '<el-tabs v-model="tab" :default-value="tab" :before-leave="beforeLeave" @tab-change="change"><el-tab-pane v-for="kind in kinds" :key="kind[0]" :name="kind[0]" :label="kind[1]"></el-tab-pane></el-tabs>' }); shell.append(tabs);
    tabs.append(el('span', 'gov-count-key', '总数 / 已确认'));
    const entries = entriesFor(record, tab), hasNav = tab === 'fields' || (['relations', 'metrics'].includes(tab) && entries.length > 1);
    const content = el('div', 'gov-content' + (hasNav ? ' has-item-nav' : '')); let currentEntry;
    const sources = GovernanceImpactDemo.records.filter(item => item.source === table.source && (tab === 'evidence' ? item.bindings.some(binding => binding.table === table.id) : item.bindings.some(binding => binding.table === owner.table.id && binding.entry === selected?.id)));
    if (tab === 'evidence') {
      content.append(evidencePage(record, sources));
    } else {
      const entry = selected;
      currentEntry = entry;
      if (!entry) {
        const discovery = discoveryRuns.get(table.id), empty = el('div', 'gov-empty'); empty.setAttribute('role', 'status'); empty.append(WorkbenchIcon('file-text'), el('p', '', discovery?.kinds.includes(tab) && discovery.message || '暂无' + kindLabel(tab) + '候选'));
        if (['relations', 'metrics'].includes(tab) && allowed('business-notes.view')) empty.append(action('业务说明 · ' + demo.availableNotes(table.source).length + ' 份', trigger => DataSourcesPage.viewGovernanceNotes(main, ui, table.source, trigger)));
        content.append(empty);
      }
      else if (!hasNav) content.append(review(owner, entry));
      else {
        const split = el('div', 'gov-split'), nav = el('nav', 'gov-items'), items = el('div', 'gov-item-list'); nav.setAttribute('aria-label', tab === 'fields' ? '治理字段' : '治理条目');
        const key = table.id + ':' + tab, search = el('div', 'gov-item-search prototype-ui'), empty = el('p', 'gov-nav-empty', '无匹配条目'); empty.setAttribute('role', 'status');
        const matches = (item, keyword, status) => AdminQuery.matches([item.name, item.comment], keyword) && (status === 'pending' ? needsWork(item) : status === 'confirmed' ? demo.isConfirmed(item) : true);
        const filter = (keyword, status = itemFilters.get(key) || 'all', move = false) => {
          itemQueries.set(key, keyword); itemFilters.set(key, status); let count = 0;
          entries.forEach((item, index) => { const visible = matches(item, keyword, status); items.children[index].hidden = !visible; if (visible) count++; }); empty.hidden = count > 0;
          if (move && count && !matches(entry, keyword, status)) { const next = entries.find(item => matches(item, keyword, status)); select(tab, next.field || next.id); requestAnimationFrame(() => main.querySelector('.gov-item-filter input[value="' + status + '"]')?.focus({ preventScroll:true })); }
        };
        const reveal = () => { const selected = items.querySelector('[aria-current="true"]'); if (items.isConnected && selected && !selected.hidden) { const itemRect = selected.getBoundingClientRect(), listRect = items.getBoundingClientRect(); if (itemRect.bottom > listRect.bottom) items.scrollTop += itemRect.bottom - listRect.bottom; else if (itemRect.top < listRect.top) items.scrollTop += itemRect.top - listRect.top; } };
        entries.forEach(item => {
          const control = ui.button('', () => select(tab, item.field || item.id), 'gov-item', 'gov-item-' + item.id), name = el('span', 'gov-item-name'), meta = el('span', 'gov-item-meta');
          control.setAttribute('aria-current', String(item.id === entry.id));
          control.title = entryLabel(item) + (entryLabel(item) !== item.name ? ' · ' + item.name : '') + (item.type ? ' · ' + item.type : '');
          name.append(el('strong', '', entryLabel(item)), badge(entryState(item)));
          if (entryLabel(item) !== item.name) meta.append(el('small', 'gov-item-comment', item.name));
          if (item.type) meta.append(el('small', '', item.type));
          if (item.blocked) meta.append(badge('禁止使用'));
          control.append(name, meta); items.append(control);
        });
        PrototypeUI.mount(search, { data: () => ({ keyword: itemQueries.get(key) || '', status: itemFilters.get(key) || 'all' }), methods: { search(value) { filter(value, this.status); }, change(value) { filter(this.keyword, value, true); } }, mounted() { this.resize = new ResizeObserver(reveal); this.resize.observe(items); requestAnimationFrame(reveal); }, beforeUnmount() { this.resize.disconnect(); }, template: '<el-input v-model="keyword" clearable aria-label="搜索当前治理条目" placeholder="搜索名称或注释" @input="search"></el-input><el-radio-group v-model="status" size="small" class="gov-item-filter" aria-label="条目审核状态" @change="change"><el-radio-button value="all">全部</el-radio-button><el-radio-button value="pending">待处理</el-radio-button><el-radio-button value="confirmed">已确认</el-radio-button></el-radio-group>' });
        const reset = button('清空筛选', () => { itemQueries.delete(key); itemFilters.delete(key); select(tab, entry.field || entry.id); }); empty.append(reset);
        nav.append(search, items, empty); filter(itemQueries.get(key) || ''); split.append(nav, review(owner, entry)); content.append(split);
      }
    }
    shell.append(content); if (currentEntry) shell.append(entryActions(owner, currentEntry)); main.replaceChildren(ui.pageHeading(table.label + '治理详情'), shell);
    const position = currentEntry && viewPositions.get(table.id + ':' + currentEntry.kind + ':' + currentEntry.id);
    if (position) requestAnimationFrame(() => { const reading = readingPane(main), nav = main.querySelector('.gov-item-list'); if (reading) reading.scrollTop = position.reading; if (nav) nav.scrollTop = position.nav; });
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
  window.GovernancePage = { render, open, openImpact, beforeNavigation };
  window.addEventListener('beforeunload', event => { if (labelSession?.host?.isConnected && labelDirty(labelSession)) { event.preventDefault(); event.returnValue = ''; } });
})();
