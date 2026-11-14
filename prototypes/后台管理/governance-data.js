/* 治理原型：输入、业务定义、资料引用和人工操作分存，仅维护页面内状态，不调用模型或数据库。 */
(() => {
  'use strict';
  const records = new Map(), tasks = [];
  const has = (actor, ability) => SourceApprovalDemo.hasAbility(actor, ability);
  const objectName = table => (table.schema ? table.schema + '.' : '') + table.name;
  const copy = value => structuredClone(value);
  // 只抽取明确写出的单位，让资料起草和人工核清都能同步独立单位输入。
  const unitFromText = value => value?.match(/(?:计量)?单位\s*(?:为|是|[:：])\s*([^\s，,。；;）)]+)/)?.[1] || value?.match(/[（(](万元|元|分|千克|公斤|件|个|箱|瓶)[）)]/)?.[1] || value?.match(/^(万元|元|分|千克|公斤|件|个|箱|瓶)[。！!]?$/)?.[1] || value?.match(/(?:采用|使用|以)(万元|元|分|千克|公斤|件|个|箱|瓶)(?:计|为|，|,|。|；|;|$)/)?.[1] || '';
  const isDismissed = entry => ['不采用', '不适用'].includes(entry.disposition);
  const hasConfirmedHistory = entry => entry.review === '已确认' || Boolean(entry.lastConfirmation) || entry.history.some(event => event.action === '人工确认');
  const isConfirmed = entry => !isDismissed(entry) && entry.review === '已确认' && entry.validity !== '待复核';
  const unresolvedText = value => /^(?:待确认|待核实|待补充|未知|尚未明确|尚未确定|暂不确定|暂不明确)[。；;]?$/.test((value || '').trim()) || /(?:单位|业务含义|编码含义|时区|计算口径|退款口径|更新机制|统计边界|关联条件|数量关系|完整公式|业务范围|格式|唯一性)(?:尚未明确|尚未确定|未知|不明|待确认|待核实)|尚需核实|仍需核对|(?:元|分)还是(?:元|分)/.test(value || '');
  const normalizedUnit = value => ({ 人民币元: '元', CNY: '元', RMB: '元', 公斤: '千克', kg: '千克' })[value] || value;
  function fieldProfile(field) {
    const text = (field.field || field.name || '') + ' ' + (field.comment || '');
    if (/amount|price|money|金额|单价|费用|收入/i.test(text)) return 'amount';
    if (/quantity|qty|数量|重量|销量/i.test(text)) return 'quantity';
    if (/status|state|category|flag|(?:^|[_\s])type(?:[_\s]|$)|状态|分类|类型|标记|标志/i.test(text)) return 'enum';
    if (/DATE|TIME/i.test(field.type || '')) return /updated|modified|更新时间|修改时间/i.test(text) ? 'updated' : 'time';
    if (/code|编码/i.test(text)) return 'code';
    return '';
  }
  const criticalKeys = entry => {
    if (entry.kind !== 'fields') return [];
    const profile = fieldProfile(entry), price = profile === 'amount' && /price|单价|标价|售价/i.test((entry.field || entry.name) + entry.comment) && !/total|amount|总价|总额|金额/i.test((entry.field || entry.name) + entry.comment);
    return price ? ['values'] : ['amount', 'quantity', 'enum', 'time'].includes(profile) ? ['values', 'rules'] : profile === 'updated' ? ['values'] : profile === 'code' ? ['values', 'usage'] : [];
  };
  // 只核对明确的技术引用；字符串常量和附件名称不作为字段路径。
  function fieldReferences(source, text, availableTables = DataSourceDemo.objects(source)) {
    const found = [], missing = [], aliases = new Map();
    const input = text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, ' ').replace(/'(?:''|[^'])*'/g, ' ').replace(/[`\"]/g, '');
    const declarations = [...input.matchAll(/\b(?:FROM|JOIN)\s+([\w.]+)/gi)].map(match => ({ start: match.index + match[0].lastIndexOf(match[1]), end: match.index + match[0].length }));
    const resolve = name => { const rows = availableTables.filter(table => objectName(table) === name || table.path === name || table.name === name); return rows.length === 1 ? rows[0] : undefined; };
    for (const match of input.matchAll(/\b(?:FROM|JOIN)\s+([\w.]+)\s+(?:AS\s+)?(\w+)/gi)) if (!/^(?:WHERE|JOIN|ON|LEFT|RIGHT|INNER|GROUP|ORDER)$/i.test(match[2])) aliases.set(match[2], match[1]);
    for (const match of input.matchAll(/[\p{L}_][\p{L}\p{N}_]*(?:\.[\p{L}_][\p{L}\p{N}_]*){1,3}/gu)) {
      if (declarations.some(range => match.index >= range.start && match.index < range.end)) continue;
      const parts = match[0].split('.'), field = parts.pop();
      if (/^(?:pdf|docx?|xlsx?|csv|md|txt)$/i.test(field)) continue;
      const name = parts.join('.'), table = resolve(aliases.get(name) || name);
      if (table?.fields.some(column => column.name === field)) found.push({ table: table.id, entry: 'field:' + field }); else missing.push(match[0]);
    }
    return { found: [...new Map(found.map(ref => [ref.table + '/' + ref.entry, ref])).values()], missing: [...new Set(missing)] };
  }
  const normalizedDependencies = (record, entry) => (entry.dependencies || []).map(dep => typeof dep === 'string' ? { table: record.table.id, entry: dep } : dep);
  const foreignKeyIdentity = key => JSON.stringify([key.target, (key.fields || []).map((field, index) => [field, key.targetFields?.[index]]).sort((a, b) => a[0].localeCompare(b[0]))]);
  function dependencyClosure(record, entry) {
    const result = new Map(), visit = ref => {
      const key = ref.table + '/' + ref.entry; if (result.has(key)) return;
      const table = DataSourceDemo.objects(record.table.source).find(table => table.id === ref.table), row = table && get(table), target = row?.items.find(item => item.id === ref.entry);
      result.set(key, { table, record: row, entry: target });
      if (target) normalizedDependencies(row, target).forEach(visit);
    };
    normalizedDependencies(record, entry).forEach(visit); return result;
  }
  function referenceIssue(record, entry) {
    if (!['relations', 'metrics'].includes(entry.kind) || entry.retired) return null;
    const closure = dependencyClosure(record, entry);
    for (const key of ['meaning', 'values', 'usage']) {
      const refs = fieldReferences(record.table.source, entry.content[key] || '');
      if (refs.missing.length) return { key, message: '无法定位规则引用：' + refs.missing.join('、') + '，请核对当前数据源的表字段。' };
      const absent = refs.found.filter(ref => !closure.has(ref.table + '/' + ref.entry));
      if (absent.length) return { key: 'dependencies', message: '规则引用尚未纳入依赖：' + absent.map(ref => { const table = DataSourceDemo.objects(record.table.source).find(table => table.id === ref.table); return objectName(table) + '.' + ref.entry.slice(6); }).join('、') + '。' };
    }
    return null;
  }
  const contentSections = kind => (kind === 'overview' ? [['meaning', '业务用途'], ['values', '一行代表什么'], ['scope', '数据范围'], ['updates', '时间与更新']] : kind === 'fields' ? [['meaning', '业务含义'], ['values', '取值含义'], ['rules', '使用口径'], ['usage', '使用注意事项']] : [['meaning', kind === 'relations' ? '关联条件' : '指标含义'], ['values', kind === 'relations' ? '数量关系与完整键' : '公式与筛选条件'], ['usage', kind === 'relations' ? '适用条件与限制' : '单位与统计边界']]).map(([key, label]) => ({ key, label }));
  const answerCurrent = question => question.resolved === true && Boolean(question.answer?.trim()) && question.answer === (question.response ?? question.answer ?? '').trim();
  const unansweredQuestions = value => (value.checkContext?.questions || []).filter(question => !answerCurrent(question));
  // 核清结论作为记录保留；后续编辑正文仍走治理确认，不重新要求回答已核清的问题。
  function refreshAnswers(model) {
    const questions = model.checkContext?.questions; if (!questions?.length) return;
    questions.forEach(question => { if (question.resolved && !answerCurrent(question)) question.resolved = false; });
    model.gap = unansweredQuestions(model).map(question => question.question).join('\n');
  }
  function confirmAnswer(model, key, actor) {
    const question = model.checkContext?.questions?.find(question => question.key === key);
    if (!question) return '当前没有对应的 AI 问题。';
    const response = question.response ?? model[key] ?? '';
    if (!response.trim()) return '请回答 AI 关于“' + question.label + '”的问题。';
    question.answer = response.trim();
    // 指标已知公式与待补充的范围分开处理，核清条件时保留原有公式。
    const existing = question.preserveExisting ? (model[key] || '').trim() : '';
    model[key] = existing && !question.answer.includes(existing) ? existing + '\n' + question.answer : question.answer;
    question.resolved = true; question.deferred = false;
    const unit = key === 'values' && model.valueDetails ? unitFromText(question.answer) : '';
    if (unit) model.valueDetails.unit = unit;
    if (actor) { question.answeredBy = actor.name; question.answeredAt = DataSourceDemo.now(); }
    refreshAnswers(model);
  }
  // 按当前源自动取用有效资料；执行时重新取最新修订，预览和查看不会产生引用。
  const availableNotes = source => DataSourceDemo.notes.filter(note => note.source === source && note.status === '有效' && note.origin !== '预置示例');
  const tasksFor = record => tasks.filter(task => task.table === record.table.id);
  // 结构是已采集的输入事实；它可以辅助推断，但不自动证明业务解释。
  function structureFor(record, entry) {
    const table = record.table, field = table.fields.find(field => field.name === entry?.field);
    return { source: table.source, objectId: table.identity, generation: table.generation, path: table.path + (field ? '.' + field.name : ''), snapshot: table.version, comment: field ? field.comment : table.comment, primaryKey: copy(table.primaryKey || []), foreignKeys: copy(table.foreignKeys || []), ...(field ? { type: field.type, nullable: field.nullable } : {}), collectionId: DataSourceDemo.tasks.find(task => task.source === table.source && task.version === table.version && DataSourceDemo.collectionSucceeded(task))?.id || '' };
  }
  // 人工采用与确认补入原任务，重新生成不会把处理过程记到另一轮任务上。
  function taskEvent(entry, action, actor) {
    const task = tasks.find(task => task.id === entry.sourceTaskId);
    if (!task || task.events.some(event => event.action === action && event.entryId === entry.id && event.revision === entry.revision)) return;
    task.events.push({ action, actor: actor.name, at: DataSourceDemo.now(), detail: entry.name + ' · 治理修订 ' + entry.revision, entryId: entry.id, revision: entry.revision });
  }
  const verificationError = record => !DataSourceDemo.canGovern(record.table) ? '该对象尚无成功采集的结构，请先完成元数据采集。' : DataSourceDemo.pendingVerification(record.table).length ? '该对象仍有变化待核实，请先核对结构变化。' : '';
  function get(table) {
    if (records.has(table.id)) return records.get(table.id);
    // 采集结构只证明技术事实；未实际填写、引用和审核的业务定义一律从空白开始。
    const item = (id, kind, name, content, gap = '') => ({ id, kind, name, content, gap, revision: 1, review: '待确认', validity: '适用', publication: '未发布', reviewer: '', reviewedAt: '', basis: '', manualEvidence: null, references: [], ai: null, history: [] });
    const items = [item('overview', 'overview', '表说明', { meaning: '', values: '', scope: '', updates: '' })];
    table.fields.forEach(field => {
      const gap = '';
      const entry = item('field:' + field.name, 'fields', field.name, { meaning: '', values: '', rules: '', usage: '' }, gap);
      entry.field = field.name; entry.type = field.type; entry.comment = field.comment;
      const change = DataSourceDemo.changes.find(change => change.fact === '已确认' && change.source === table.source && change.object === objectName(table) && change.field === field.name);
      if (change) { entry.validity = '待复核'; entry.review = '待确认'; entry.reviewer = ''; entry.reviewedAt = ''; entry.blocked = change.control === '已禁止使用'; }
      items.push(entry);
    });
    DataSourceDemo.changes.filter(change => change.fact === '已确认' && change.source === table.source && change.object === objectName(table) && change.kind === '删除字段' && !table.fields.some(field => field.name === change.field)).forEach(change => {
      const entry = item('field:' + change.field, 'fields', change.field, { meaning: '来源字段已删除。', values: '', usage: '停止依赖该字段，相关知识需修订。' });
      Object.assign(entry, { field: change.field, type: '已移除', comment: '', retired: true, blocked: true, validity: '失效', basis: change.evidence }); items.push(entry);
    });
    // 关联与指标须来自明确的治理输入；不因表名或同名字段预造业务条目。
    const record = { table, items };
    records.set(table.id, record);
    GovernanceImpactDemo.records.filter(impact => impact.kind === '结构变化' && impact.source === table.source && ['待治理', '治理中'].includes(impact.state)).forEach(impact => {
      const change = DataSourceDemo.changes.find(change => change.id === impact.originId && change.object === objectName(table));
      if (change) prepareImpact(impact);
    });
    return record;
  }
  function log(entry, action, actor, reason = '') {
    entry.history.unshift({ action, actor: actor.name, at: DataSourceDemo.now(), reason, name: entry.name, content: copy(entry.content), valueDetails: copy(entry.valueDetails || null), basis: entry.basis, checkContext: copy(entry.checkContext || null), definitionSource: copy(entry.definitionSource || null), confirmedStructure: copy(entry.confirmedStructure || null), manualEvidence: copy(entry.manualEvidence || null), dependencies: copy(entry.dependencies || []), dependencyRevisions: copy(entry.dependencyRevisions || []), reviewer: entry.reviewer, reviewedAt: entry.reviewedAt, disposition: entry.disposition || '', revision: entry.revision, review: entry.review, references: copy(entry.references || []) });
  }
  const bindDependencyRevisions = (record, entry) => normalizedDependencies(record, entry).map(ref => {
    const table = DataSourceDemo.objects(record.table.source).find(table => table.id === ref.table), target = table && get(table).items.find(item => item.id === ref.entry);
    return { ...ref, revision: target?.revision, objectId: table?.identity, generation: table?.generation };
  });
  function dependencyRevisionIssue(record, entry) {
    if (!entry.dependencies?.length || entry.review !== '已确认' || entry.retired) return '';
    const expected = entry.dependencyRevisions || [];
    for (const ref of bindDependencyRevisions(record, entry)) {
      const table = DataSourceDemo.objects(record.table.source).find(table => table.id === ref.table), target = table && get(table).items.find(item => item.id === ref.entry);
      if (target?.retired) continue;
      const prior = expected.find(old => old.table === ref.table && old.entry === ref.entry);
      if (!prior || prior.revision !== ref.revision || prior.objectId !== ref.objectId || prior.generation !== ref.generation) return '依赖修订已变化，请复核并重新确认当前定义。';
    }
    return '';
  }
  function markForReview(record, entry, actor, reason) {
    if (isDismissed(entry)) return;
    if (entry.review === '已确认') {
      log(entry, '复核前确认内容', actor, reason);
      entry.lastConfirmation = { revision: entry.revision, reviewer: entry.reviewer, reviewedAt: entry.reviewedAt, dependencyRevisions: copy(entry.dependencyRevisions || []) };
      entry.revision++; entry.review = '待确认'; entry.reviewer = ''; entry.reviewedAt = '';
    }
    entry.validity = entry.retired ? '失效' : '待复核'; entry.reviewReason = reason;
    sync(record);
  }
  // 上游实际修订变化沿显式依赖传播，保留原定义及确认记录，不自动替下游确认。
  function invalidateDependents(record, entry, actor, reason = '依赖定义已修改') {
    const queue = [{ record, entry }], seen = new Set([record.table.id + '/' + entry.id]);
    while (queue.length) {
      const current = queue.shift();
      for (const row of records.values()) if (row.table.source === record.table.source) for (const target of row.items) {
        const key = row.table.id + '/' + target.id;
        if (seen.has(key) || isDismissed(target) || !normalizedDependencies(row, target).some(ref => ref.table === current.record.table.id && ref.entry === current.entry.id)) continue;
        seen.add(key);
        if (target.review === '已确认' || target.lastConfirmation || target.validity === '待复核') markForReview(row, target, actor, reason + '：' + objectName(current.record.table) + ' / ' + current.entry.name);
        queue.push({ record: row, entry: target });
      }
    }
  }
  // 关联只在所属表保存一份；两端的表说明、字段和关联页签都读取同一条记录。
  function relationsFor(record, field) {
    const result = [];
    for (const owner of records.values()) {
      if (owner.table.source !== record.table.source) continue;
      for (const entry of owner.items.filter(item => item.kind === 'relations')) {
        const linked = (entry.dependencies || []).some(dep => {
          const ref = typeof dep === 'string' ? { table: owner.table.id, entry: dep } : dep;
          return ref.table === record.table.id && (!field || ref.entry === 'field:' + field);
        });
        if (linked || (!field && owner === record)) result.push({ record: owner, entry });
      }
    }
    return result;
  }
  function addRelation(record, actor, data, kind = 'relations') {
    if (!has(actor, 'governance.view') || !has(actor, 'governance.edit')) return { error: '当前账号没有编辑关联或指标的权限。' };
    if (verificationError(record)) return { error: verificationError(record) };
    if (!['relations', 'metrics'].includes(kind)) return { error: '治理条目类型不正确。' };
    if (!data.name?.trim()) return { error: '请填写' + (kind === 'relations' ? '关联' : '指标') + '名称。' };
    const entry = { id: (kind === 'relations' ? 'relation:' : 'metric:') + DataSourceDemo.nextId('GL-'), kind, name: data.name.trim(), content: { meaning: '', values: '', usage: '' }, gap: '', dependencies: [], revision: 0, review: '待确认', validity: '适用', publication: '未发布', reviewer: '', reviewedAt: '', basis: '', manualEvidence: null, references: [], ai: null, history: [] };
    record.items.push(entry);
    const error = act(record, entry, 'save', actor, data);
    if (error === '内容未变化。') { entry.revision = 1; log(entry, '保存待确认内容', actor); return { entry }; }
    if (error) { record.items.splice(record.items.indexOf(entry), 1); return { error }; }
    return { entry };
  }
  function summary(record) {
    // 表级进度仅汇总表说明与字段；变化影响独立统计，关联和指标仍逐项审核。
    const necessary = record.items.filter(entry => ['overview', 'fields'].includes(entry.kind));
    const confirmed = necessary.filter(entry => entry.review === '已确认' && entry.validity !== '待复核').length;
    const started = necessary.some(entry => Boolean(entry.definitionSource));
    return { total: necessary.length, confirmed, pending: necessary.length - confirmed, state: confirmed === necessary.length ? '已治理' : confirmed || started ? '治理中' : '待治理', affected: attention(record).affected.length };
  }
  function sync(record) {
    const progress = summary(record);
    record.table.governance = progress.state;
    record.table.governanceAffected = progress.affected > 0;
    record.table.fields.forEach(field => { const entry = record.items.find(entry => entry.id === 'field:' + field.name); if (entry) field.meaning = entry.content.meaning ? entry.content.meaning + (entry.review === '已确认' ? '' : '（待确认）') : '待确认'; });
    const source = DataSourceDemo.find(record.table.source), tables = DataSourceDemo.objects(source.id);
    source.governed = tables.filter(table => table.governance === '已治理').length;
    source.partialGoverned = tables.filter(table => table.governance === '治理中').length;
  }
  function generate(record, kinds, actor, entryId, entryIds) {
    if (!has(actor, 'governance.view') || !has(actor, 'governance.suggest')) return '当前账号没有生成 AI 建议的权限。';
    if (verificationError(record)) return verificationError(record);
    if (!kinds.length) return '请选择需要生成建议的内容。';
    const entries = record.items.filter(entry => kinds.includes(entry.kind) && !entry.retired && !isDismissed(entry) && (!entryId || entry.id === entryId) && (!entryIds || entryIds.includes(entry.id)));
    if (!entries.length) return '当前范围没有可生成建议的条目。';
    const at = DataSourceDemo.now(), task = { id: DataSourceDemo.nextId('RW-'), source: record.table.source, table: record.table.id, object: objectName(record.table), name: record.table.label + ' · ' + (entryId ? entries[0].name : '批量') + '建议', actor: actor.id, at, state: '未生成', method: '自动取用 · 待生成输入', entries: entries.map(entry => ({ id: entry.id, name: entry.name, kind: entry.kind })), notes: availableNotes(record.table.source).map(note => copy({ id: note.id, source: note.source, title: note.title, revision: note.revision, content: note.content, files: note.files, author: note.author, origin: note.origin, updated: note.updated })), outputs: [] };
    // 生成输入单独冻结，后续采集或重新生成不改写这次看过的表字段上下文。
    // 必填与选填只影响人工确认条件，AI 生成始终检查当前条目的全部内容项。
    task.entries = entries.map(entry => ({ id: entry.id, name: entry.name, kind: entry.kind, revision: entry.revision, sections: contentSections(entry.kind), structure: structureFor(record, entry) }));
    task.tableContext = record.table.fields.map(field => ({ name: field.name, type: field.type, nullable: field.nullable, comment: field.comment }));
    task.failure = '当前原型尚未接入 AI 生成服务，本次没有生成建议。';
    task.sampling = { state: '未执行', reads: [] };
    // 任务与资料引用同次形成；历史来源保存快照，不随资料编辑、撤下或再次生成改绑。
    tasks.unshift(task);
    task.notes.forEach(note => DataSourceDemo.noteUses.unshift({ id: task.id, taskId: task.id, noteId: note.id, revision: note.revision, snapshot: copy(note), kind: '治理任务', name: task.name, state: task.state, actor: task.actor, at, method: task.method, purpose: '为 ' + record.table.path + ' 的' + entries.map(entry => entry.name).join('、') + '准备输入资料；取用不等于支持业务结论。', result: '已保留输入资料，尚无模型输出。', ended: at }));
    for (const entry of entries) {
      // 当前没有真实模型服务；只保留可回看的输入，不用固定文案伪造建议或逐项引用。
      entry.ai = { taskId: task.id, references: [], content: { meaning: '', values: '', usage: '' }, gap: task.failure, at, basis: [], structure: structureFor(record, entry) };
      log(entry, '准备 AI 输入资料', actor);
      linked(record, entry).filter(impact => impact.state !== '知识处理中').forEach(impact => GovernanceImpactDemo.touch(impact, actor));
    }
    task.ended = DataSourceDemo.now();
    task.events = [{ action: '自动取用有效业务说明', at, detail: task.notes.length + ' 份资料，绑定各自修订' }, { action: '草稿未生成', at: task.ended, detail: '仅保留输入资料，未取得模型输出，也未形成人工确认。' }];
  }
  // 同一组内容缺口同时约束初始化、审核与知识范围，防止缺少粒度的表说明进入知识草稿。
  function definitionIssues(entry) {
    if (entry.retired) return [];
    const issues = [], add = (key, message, control) => issues.push({ key, message, control });
    if (['relations', 'metrics'].includes(entry.kind) && !entry.name?.trim()) add('name', '请填写关联或指标名称。');
    if (!entry.content.meaning?.trim()) add('meaning', entry.kind === 'overview' ? '请先说明这张表记录什么业务。' : '请先补齐业务含义。');
    if (entry.kind === 'overview' && !entry.content.values?.trim()) add('values', '请先说明一行记录代表什么（记录粒度）。');
    if (entry.kind === 'relations' && !entry.content.values?.trim()) add('values', '请先明确数量关系与完整关联键。');
    if (entry.kind === 'metrics' && !entry.content.values?.trim()) add('values', '请先明确指标公式与筛选条件。');
    const profile = fieldProfile(entry);
    for (const key of criticalKeys(entry)) {
      const value = (entry.content[key] || '').trim();
      if (key === 'values' && ['amount', 'quantity'].includes(profile)) {
        const unit = entry.valueDetails?.unit?.trim() || unitFromText(value);
        if (unresolvedText(unit) || unresolvedText(value) || !unit && !/单位.{0,12}(?:随|由|按|引用|字段)|(?:按|引用).{0,16}单位|不适用/.test(value)) add(key, '请明确' + (profile === 'amount' ? '金额' : '数量') + '单位。', '单位');
        else if (entry.valueDetails?.unit && unitFromText(value) && normalizedUnit(entry.valueDetails.unit) !== normalizedUnit(unitFromText(value))) add(key, '单位输入与取值说明不一致，请核对后确认。', '单位');
      } else if (!value && !(key === 'values' && entry.valueDetails?.mappings?.length)) add(key, '请补充' + contentSections('fields').find(section => section.key === key).label + '，或明确说明当前定义不适用。');
      else if (unresolvedText(value)) add(key, '当前' + contentSections('fields').find(section => section.key === key).label + '仍未核清。');
      else if (key === 'values' && profile === 'enum' && !entry.valueDetails?.mappings?.length && !/[A-Za-z0-9_-]+\s*(?:=|＝|为|表示|代表|[:：])\s*[^，,；;]+/.test(value) && !/自由文本|取值即|无编码|不使用编码|不适用|仅原样(?:展示|返回)编码/.test(value)) add(key, '请明确编码对应的业务含义；格式和空值说明不能代替编码解释。');
      else if (key === 'values' && ['time', 'updated'].includes(profile) && !/^DATE$/i.test(entry.type) && !/UTC|GMT|[A-Za-z]+\/[A-Za-z_]+|北京时间|东八区|[+-]\d{2}:\d{2}|无时区|不涉及时区|不适用/.test(value)) add(key, '请明确该时间字段采用的业务时区。');
    }
    return issues;
  }
  const definitionIssue = entry => definitionIssues(entry)[0]?.message || '';
  // 输入资料不等于逐项依据；只有带原文的具体引用才能支持审核，人工补充说明单独保存。
  const citations = entry => [...new Map([...(entry.references || []), entry.manualEvidence].filter(Boolean).map(ref => [ref.id + ':' + ref.revision + ':' + ref.excerpt, ref])).values()];
  function citationIssue(record, entry) {
    if (entry.retired) return '';
    const refs = citations(entry);
    // 文档不是每个定义的必需前提；结构、实际读取和人工核对也可以提供依据。
    if (!refs.length) return '';
    if (refs.some(ref => DataSourceDemo.notes.find(note => note.id === ref.id && note.source === ref.source)?.status !== '有效')) return '来源资料已撤下或不可用，请重新核对有效依据。';
    if (refs.some(ref => ref.origin === '预置示例' || ref.source !== record.table.source || !DataSourceDemo.notes.some(note => note.id === ref.id && note.source === ref.source) || !ref.excerpt?.trim() || !ref.content?.includes(ref.excerpt) || !Number.isInteger(ref.revision))) return '来源资料或引用原文不完整，请重新关联具体修订。';
    return '';
  }
  function recordCitations(record, entry, actor, kind) {
    for (const ref of citations(entry)) {
      if (DataSourceDemo.noteUses.some(use => use.kind === kind && use.table === record.table.id && use.entryId === entry.id && use.resultRevision === entry.revision && use.noteId === ref.id && use.revision === ref.revision && use.excerpt === ref.excerpt)) continue;
      const sourceTask = ref === entry.manualEvidence ? null : tasks.find(task => task.id === entry.sourceTaskId && task.notes.some(note => note.id === ref.id && note.revision === ref.revision));
      DataSourceDemo.noteUses.unshift({ id: DataSourceDemo.nextId(kind === '治理成果' ? 'CG-' : 'YJ-'), taskId: sourceTask?.id, table: record.table.id, entryId: entry.id, resultRevision: entry.revision, noteId: ref.id, revision: ref.revision, snapshot: copy(ref), excerpt: ref.excerpt, kind, name: record.table.label + ' · ' + entry.name, state: entry.review, actor: actor.id, at: DataSourceDemo.now(), method: ref === entry.manualEvidence ? '人工关联来源资料' : '采用草稿的具体引用', purpose: '核对 ' + record.table.path + ' 的' + entry.name, result: Object.values(entry.content).filter(Boolean).join('\n'), ...(kind === '治理成果' ? { reviewer: actor.id, reviewedAt: entry.reviewedAt } : {}) });
    }
  }
  // 页面与提交共用确认条件，禁用原因不能只在提交后才出现。
  function confirmIssue(record, entry) {
    if (entry.retired) return '';
    if (isDismissed(entry)) return '当前候选已' + entry.disposition + '，请先恢复处理。';
    const missing = definitionIssues(entry).find(issue => ['name', 'meaning'].includes(issue.key) || entry.kind !== 'fields'); if (missing) return missing.message;
    if (entry.valueDetails?.mappings?.some(row => !row.code.trim() || !row.meaning.trim())) return '请补齐已添加的编码及含义，或移除不适用的行。';
    const unanswered = unansweredQuestions(entry)[0];
    if (unanswered) return (unanswered.response?.trim() ? '请确认对“' : '请先回答 AI 关于“') + unanswered.label + (unanswered.response?.trim() ? '”的回答。' : '”的问题。');
    if (entry.gap) return '请先处理待核对问题，并标记已核清。';
    const critical = definitionIssue(entry); if (critical) return critical;
    const evidence = citationIssue(record, entry); if (evidence) return evidence;
    if (['relations', 'metrics'].includes(entry.kind) && !entry.dependencies?.length) return '请明确关联或指标使用的依赖条目。';
    // 业务审核不等待发布后的解除禁止；但依赖定义和结构事实必须已核清。
    const dependencies = (entry.dependencies || []).map(dep => typeof dep === 'string' ? { table: record.table.id, entry: dep } : dep);
    if (entry.kind === 'relations' && new Set(dependencies.filter(dep => dep.entry.startsWith('field:')).map(dep => dep.table + '/' + dep.entry)).size < 2) return '请选择关联双方的字段定义。';
    const references = referenceIssue(record, entry); if (references) return references.message;
    const issue = publicationScope(record.table.source, dependencies, { allowBlocked: true, requireReadable: false }).issues[0];
    if (issue) return '依赖字段或条目尚未确认：' + issue;
    return '';
  }
  function act(record, entry, action, actor, data = {}) {
    const permitted = action === 'confirm' ? has(actor, 'governance.confirm') : action === 'pending' ? has(actor, 'governance.edit') || has(actor, 'governance.supplement') : has(actor, 'governance.edit');
    if (!has(actor, 'governance.view') || !permitted) return '当前账号没有此项治理操作权限。';
    if (verificationError(record)) return verificationError(record);
    if (!record.items.includes(entry)) return '治理条目已变化，请重新打开。';
    if (isDismissed(entry) && !['restore', 'dismiss', 'reject'].includes(action)) return '当前候选已退出，请先恢复处理。';
    if (action === 'confirm') {
      const issue = confirmIssue(record, entry); if (issue) return issue;
      log(entry, '确认前内容', actor); entry.review = '已确认'; entry.reviewer = actor.name; entry.reviewedAt = DataSourceDemo.now();
      entry.dependencyRevisions = bindDependencyRevisions(record, entry); entry.reviewReason = '';
      entry.confirmedStructure = structureFor(record, entry);
      entry.definitionSource ||= { kind: '人工确认', actor: actor.id, at: entry.reviewedAt, structure: structureFor(record, entry) };
      if (!entry.retired) entry.validity = '适用';
      recordCitations(record, entry, actor, '治理成果');
      taskEvent(entry, '人工确认治理内容', actor);
      log(entry, entry.retired ? '确认移除处理' : '人工确认', actor);
    } else if (action === 'adopt') {
      if (!entry.ai) return '请先生成 AI 建议。';
      if (!Object.values(entry.ai.content).some(value => value.trim())) return '尚无可采用的 AI 草稿。';
      if (entry.review === '已确认') return '已确认内容请通过编辑修改，AI 建议不会覆盖人工定义。';
      log(entry, '采用建议前内容', actor); entry.content = copy(entry.ai.content); entry.gap = entry.ai.gap; entry.basis = ''; entry.checkContext = copy(entry.ai.checkContext || null); entry.manualEvidence = null; entry.references = copy(entry.ai.references); entry.sourceTaskId = entry.ai.taskId; entry.revision++;
      entry.definitionSource = { kind: 'AI 建议采用', actor: actor.id, at: DataSourceDemo.now(), taskId: entry.ai.taskId, structure: copy(entry.ai.structure || structureFor(record, entry)) };
      log(entry, '采用为待确认内容', actor);
      recordCitations(record, entry, actor, '治理依据');
      taskEvent(entry, '采用建议，保留待确认', actor);
    } else if (action === 'save') {
      let manualEvidence = entry.manualEvidence || null;
      if (Object.hasOwn(data, 'evidence')) {
        const selected = data.evidence;
        if (!selected) manualEvidence = null;
        else if (!(manualEvidence?.id === selected.noteId && manualEvidence.revision === selected.revision && manualEvidence.excerpt === selected.excerpt?.trim())) {
          if (!has(actor, 'business-notes.view')) return '当前账号没有查看来源资料的权限。';
          const note = DataSourceDemo.notes.find(note => note.id === selected.noteId && note.source === record.table.source);
          if (!note) return '请选择当前数据源的来源资料。';
          if (note.origin === '预置示例') return '预置示例不能作为业务确认依据，请使用实际录入的来源资料。';
          if (note.status !== '有效' || note.revision !== selected.revision) return '来源资料修订或状态已变化，请重新选择。';
          const excerpt = selected.excerpt?.trim();
          if (!excerpt || !note.content.includes(excerpt)) return '引用原文必须是所选资料该修订中实际存在的文字。';
          manualEvidence = copy({ id: note.id, source: note.source, title: note.title, revision: note.revision, content: note.content, files: note.files, author: note.author, origin: note.origin, updated: note.updated, excerpt, recordedBy: actor.id, recordedAt: DataSourceDemo.now() });
        }
      }
      const dependencies = data.dependencies ?? entry.dependencies ?? [];
      if (!Array.isArray(dependencies) || dependencies.some(dep => typeof dep !== 'string' && (!dep || typeof dep.table !== 'string' || typeof dep.entry !== 'string'))) return '依赖条目格式不正确。';
      // 表范围、更新规则和字段使用口径随当前草稿保存，空白选填项不额外阻挡确认。
      const keys = contentSections(entry.kind).map(section => section.key);
      const content = Object.fromEntries(keys.map(key => [key, (data[key] ?? entry.content[key] ?? '').trim()])), gap = (data.gap ?? entry.gap ?? '').trim(), basis = (data.basis ?? entry.basis ?? '').trim();
      const name = ['relations', 'metrics'].includes(entry.kind) ? (data.name ?? entry.name).trim() : entry.name;
      if (!name) return '请填写关联或指标名称。';
      const valueDetails = entry.kind === 'fields' ? copy(data.valueDetails ?? entry.valueDetails ?? { unit: '', mappings: [] }) : null;
      if (valueDetails && (typeof valueDetails.unit !== 'string' || !Array.isArray(valueDetails.mappings) || valueDetails.mappings.some(row => typeof row.code !== 'string' || typeof row.meaning !== 'string'))) return '取值说明格式不正确。';
      if (valueDetails) { valueDetails.unit = valueDetails.unit.trim(); valueDetails.mappings = valueDetails.mappings.filter(row => row.code.trim() || row.meaning.trim()).map(row => ({ code: row.code.trim(), meaning: row.meaning.trim() })); }
      const checkContext = copy(data.checkContext === undefined ? entry.checkContext || null : data.checkContext);
      if (checkContext && (!checkContext.question?.trim() || !checkContext.reason?.trim() || !Array.isArray(checkContext.targets) || !checkContext.targets.length || checkContext.targets.some(key => !keys.includes(key)))) return '请明确核对的问题、原因和涉及内容。';
      if (checkContext?.questions && (!Array.isArray(checkContext.questions) || checkContext.questions.some(question => !keys.includes(question.key) || !question.question?.trim() || !question.reason?.trim()))) return 'AI 核实问题与对应内容不完整。';
      if (entry.kind === 'overview' && entry.content.usage) content.usage = entry.content.usage;
      if (JSON.stringify([entry.content, entry.gap, entry.basis, entry.checkContext || null, entry.dependencies || [], entry.manualEvidence || null, entry.valueDetails || null, entry.name]) === JSON.stringify([content, gap, basis, checkContext, dependencies, manualEvidence, valueDetails, name])) return '内容未变化。';
      log(entry, '修改前内容', actor); entry.content = content; entry.dependencies = copy(dependencies); entry.gap = gap; entry.basis = basis; entry.manualEvidence = manualEvidence; entry.editedBy = actor.id; entry.editedAt = DataSourceDemo.now(); entry.review = '待确认'; entry.reviewer = ''; entry.reviewedAt = ''; entry.revision++;
      entry.checkContext = checkContext;
      entry.name = name;
      if (valueDetails) entry.valueDetails = valueDetails;
      entry.definitionSource = { kind: '人工填写', actor: actor.id, at: entry.editedAt, structure: structureFor(record, entry), ...(entry.ai?.simulated ? { simulated: true } : {}) };
      log(entry, '保存待确认内容', actor);
      recordCitations(record, entry, actor, '治理依据');
      invalidateDependents(record, entry, actor);
    } else if (action === 'pending') {
      if (!data.reason?.trim()) return '请填写尚缺的业务信息。';
      log(entry, '待确认前内容', actor); entry.review = '待确认'; entry.gap = data.reason.trim(); entry.reviewer = ''; entry.reviewedAt = ''; log(entry, '保留待确认', actor, data.reason.trim());
    } else if (action === 'reject') {
      if (!entry.ai || !data.reason?.trim()) return '请填写不采用该建议的依据。';
      if (['relations', 'metrics'].includes(entry.kind) && hasConfirmedHistory(entry)) return '该定义已有确认记录，请完成复核；不能作为未采用候选退出。';
      entry.ai.rejected = data.reason.trim(); log(entry, '不采用 AI 建议', actor, data.reason.trim());
      if (['relations', 'metrics'].includes(entry.kind) && entry.review !== '已确认') entry.disposition = '不采用';
    } else if (action === 'dismiss') {
      if (!['relations', 'metrics'].includes(entry.kind) || !['不采用', '不适用'].includes(data.disposition)) return '请选择关联或指标候选的退出方式。';
      if (hasConfirmedHistory(entry)) return '该定义已有确认记录，请完成复核；历史知识的停用由知识库与发布处理。';
      entry.disposition = data.disposition; log(entry, data.disposition + '候选', actor, data.reason || '');
    } else if (action === 'restore') {
      if (!isDismissed(entry)) return '当前候选无需恢复。';
      entry.disposition = ''; entry.review = '待确认'; log(entry, '恢复候选', actor);
    }
    sync(record);
    linked(record, entry).forEach(impact => {
      if (action === 'confirm' || isDismissed(entry) && ['dismiss', 'reject'].includes(action)) {
        if (!impactReady(impact)) { impact.state = '治理中'; impact.owner ||= actor.id; }
        GovernanceImpactDemo.reviewed(impact, actor);
      } else if (action !== 'reject') GovernanceImpactDemo.touch(impact, actor);
    });
  }
  const linked = (record, entry) => GovernanceImpactDemo.records.filter(impact => impact.bindings.some(binding => binding.table === record.table.id && binding.entry === entry.id) && impact.state !== '已完成');
  // 表列表直接定位业务条目；知识处理中只读回看，不再重复审核业务内容。
  function attention(record) {
    const impacts = GovernanceImpactDemo.records.filter(impact => impact.state !== '已完成' && impact.bindings.some(binding => binding.table === record.table.id));
    const pending = record.items.filter(entry => !isDismissed(entry) && (entry.review !== '已确认' || entry.validity === '待复核'));
    const affected = pending.filter(entry => impacts.some(impact => impact.bindings.some(binding => binding.table === record.table.id && binding.entry === entry.id)));
    return { pending, affected, knowledge: impacts.filter(impact => impact.state === '知识处理中') };
  }
  function bindImpact(record, impact, entryId) {
    const entry = record.items.find(item => item.id === entryId);
    if (!entry || isDismissed(entry) || impact.state === '已完成' || impact.bindings.some(binding => binding.table === record.table.id && binding.entry === entry.id)) return;
    impact.bindings.push({ table: record.table.id, entry: entry.id, before: copy(entry.content), beforeValueDetails: copy(entry.valueDetails || null), beforeDependencies: copy(entry.dependencies || []), beforeDependencyRevisions: copy(entry.dependencyRevisions || []) });
    const actor = { id: 'system', name: '系统' };
    markForReview(record, entry, actor, '来源依据发生变化');
    invalidateDependents(record, entry, actor, '上游来源依据发生变化');
    // 来源事实已确认；仅保留真实业务缺口，不强迫为无实质修改另填结案依据。
    sync(record);
  }
  function impactTarget(impact) {
    const change = DataSourceDemo.changes.find(change => impact.kind === '结构变化' && change.id === impact.originId && change.source === impact.source);
    const binding = impact.bindings[0];
    const table = DataSourceDemo.objects(impact.source).find(table => change ? objectName(table) === change.object : table.id === binding?.table);
    const entryId = ['主键变化', '外键变化'].includes(change?.kind) && binding ? binding.entry : change ? change.field ? 'field:' + change.field : 'overview' : binding?.entry;
    const targetTable = ['主键变化', '外键变化'].includes(change?.kind) && binding ? DataSourceDemo.objects(impact.source).find(table => table.id === binding.table) : table;
    return { table: targetTable, field: change?.field || (entryId?.startsWith('field:') ? entryId.slice(6) : ''), entryId };
  }
  function impactReady(impact) {
    return impact.bindings.length > 0 && impact.bindings.every(binding => {
      const table = DataSourceDemo.objects(impact.source).find(table => table.id === binding.table), entry = table && get(table).items.find(entry => entry.id === binding.entry);
      return Boolean(entry && (isDismissed(entry) || entry.review === '已确认' && entry.validity !== '待复核' && !entry.gap));
    });
  }
  function prepareImpact(impact) {
    if (impact.kind === '业务说明' && !impact.bindings.length) {
      const mapped = []; let unknown = false;
      // 复用实际成果或任务的技术映射，不能从资料标题和业务名称猜测依赖。
      (impact.references || []).forEach(reference => {
        const task = tasks.find(task => task.id === reference.taskId && task.source === impact.source);
        const table = DataSourceDemo.objects(impact.source).find(table => table.id === (reference.table || task?.table));
        if (!table) { unknown = true; return; }
        const record = get(table);
        const entries = reference.entryId ? record.items.filter(entry => entry.id === reference.entryId) : task ? record.items.filter(entry => entry.ai?.taskId === task.id || entry.sourceTaskId === task.id) : [];
        if (!entries.length) unknown = true;
        mapped.push(...entries);
        entries.forEach(entry => bindImpact(record, impact, entry.id));
      });
      if (!unknown && mapped.length && mapped.every(isDismissed)) Object.assign(impact, { state: '已完成', knowledge: '无需更新', completion: '资料仅涉及已退出候选，历史引用保留，无需重新治理' });
    }
    const { table, entryId } = impactTarget(impact);
    const change = DataSourceDemo.changes.find(change => impact.kind === '结构变化' && change.source === impact.source && change.id === impact.originId);
    const keyTable = change && DataSourceDemo.objects(impact.source).find(row => objectName(row) === change.object);
    if (keyTable && ['主键变化', '外键变化'].includes(change?.kind)) {
      const table = keyTable, record = get(table), before = JSON.parse(change.before || '[]'), after = JSON.parse(change.after || '[]');
      if (change.kind === '主键变化') for (const id of new Set(['overview', ...before.map(name => 'field:' + name), ...after.map(name => 'field:' + name)])) bindImpact(record, impact, id);
      else {
        const changed = [...before.filter(key => !after.some(other => foreignKeyIdentity(other) === foreignKeyIdentity(key))), ...after.filter(key => !before.some(other => foreignKeyIdentity(other) === foreignKeyIdentity(key)))], fields = new Set(changed.flatMap(key => key.fields || []));
        for (const row of records.values()) if (row.table.source === table.source) for (const entry of row.items.filter(entry => entry.kind === 'relations')) if (normalizedDependencies(row, entry).some(ref => ref.table === table.id && fields.has(ref.entry.slice(6)))) bindImpact(row, impact, entry.id);
      }
      if (!impact.bindings.length) Object.assign(impact, { state: '已完成', knowledge: '无需更新', completion: '键约束变化未影响现有业务定义，采集证据已保留' });
      return;
    }
    if (table && change?.kind === '删除对象') {
      const record = get(table);
      record.items.forEach(entry => {
        Object.assign(entry, { retired: true, blocked: true, validity: '失效', gap: '' });
        bindImpact(record, impact, entry.id);
      });
    } else if (table && entryId) {
      const record = get(table);
      if (change?.field) {
        const field = table.fields.find(field => field.name === change.field);
        let entry = record.items.find(entry => entry.id === entryId);
        if (!entry) {
          entry = { id: entryId, kind: 'fields', name: change.field, field: change.field, content: { meaning: '', values: '', usage: '' }, gap: '', revision: 1, review: '待确认', validity: '待复核', publication: '未发布', reviewer: '', reviewedAt: '', basis: '', ai: null, history: [] };
          record.items.push(entry);
        }
        entry.type = field?.type || '已移除'; entry.comment = field?.comment || '';
        entry.blocked ||= change.control === '已禁止使用';
        if (change.kind === '删除字段') Object.assign(entry, { retired: true, validity: '失效', gap: '' });
      }
      bindImpact(record, impact, entryId);
    }
  }
  function associate(impact, targets, actor, revision) {
    if (!has(actor, 'governance.view') || !has(actor, 'governance.edit')) return '当前账号没有关联治理对象的权限。';
    if (!GovernanceImpactDemo.records.includes(impact) || impact.kind !== '业务说明' || impact.bindings.length || impact.revision !== revision) return '变化事项已更新，请重新核对。';
    const selected = targets.map(target => { const table = DataSourceDemo.objects(impact.source).find(table => table.id === target.table); return { table, entry: table && get(table).items.find(entry => entry.id === target.entry) }; });
    if (!selected.length || selected.some(target => !target.table || !target.entry)) return '请选择当前数据源中可访问的治理条目。';
    selected.forEach(target => bindImpact(get(target.table), impact, target.entry.id));
    GovernanceImpactDemo.touch(impact, actor);
  }
  function impactNeedsKnowledge(impact) {
    const change = DataSourceDemo.changes.find(change => impact.kind === '结构变化' && change.id === impact.originId);
    return ['新增对象', '新增字段', '删除字段', '删除对象'].includes(change?.kind) || impact.bindings.some(binding => { const entry = records.get(binding.table)?.items.find(entry => entry.id === binding.entry); return entry && (entry.blocked || entry.retired || JSON.stringify(entry.content) !== JSON.stringify(binding.before) || JSON.stringify(entry.valueDetails || null) !== JSON.stringify(binding.beforeValueDetails || null) || JSON.stringify(entry.dependencies || []) !== JSON.stringify(binding.beforeDependencies || []) || binding.beforeDependencyRevisions && JSON.stringify(entry.dependencyRevisions || []) !== JSON.stringify(binding.beforeDependencyRevisions)); });
  }
  // 草稿预览、生成和发布共用完整依赖范围；显式依赖可以跨表，但不能跨数据源猜测。
  function publicationScope(source, bindings, { allowBlocked = false, allowRetired = false, requireReadable = true } = {}) {
    const entries = [], issues = [], seen = new Set(), visiting = new Set();
    const visit = binding => {
      const key = binding.table + '/' + binding.entry;
      if (visiting.has(key)) { issues.push('依赖存在循环：' + binding.entry); return; }
      if (seen.has(key)) return;
      const table = DataSourceDemo.objects(source).find(row => row.id === binding.table), entry = table && get(table).items.find(row => row.id === binding.entry);
      if (!table || !entry) { issues.push('当前数据源缺少来源对象或依赖条目：' + binding.entry); return; }
      visiting.add(key);
      const name = objectName(table) + ' / ' + entry.name;
      if (!DataSourceDemo.canGovern(table)) issues.push(name + ' 尚未成功采集');
      else if (DataSourceDemo.pendingVerification(table).length) issues.push(name + ' 的来源仍有变化待核实');
      if (requireReadable && table.readState === '访问丢失') issues.push(name + ' 仍无法读取，请恢复读取');
      if (entry.retired && !allowRetired) issues.push(name + ' 已移除');
      else if (isDismissed(entry)) issues.push(name + ' 已' + entry.disposition);
      else if (definitionIssue(entry)) issues.push(name + '：' + definitionIssue(entry));
      else if (citationIssue(get(table), entry)) issues.push(name + '：' + citationIssue(get(table), entry));
      else if (entry.review !== '已确认' || entry.gap || (!entry.retired && entry.validity !== '适用')) issues.push(name + ' 尚未完成业务审核确认');
      else if (dependencyRevisionIssue(get(table), entry)) issues.push(name + '：' + dependencyRevisionIssue(get(table), entry));
      if (!entry.retired && referenceIssue(get(table), entry)) issues.push(name + '：' + referenceIssue(get(table), entry).message);
      if (entry.blocked && !allowBlocked) issues.push(name + ' 禁止使用');
      if (!entry.retired) (entry.dependencies || []).forEach(dep => visit(typeof dep === 'string' ? { table: table.id, entry: dep } : dep));
      visiting.delete(key); seen.add(key); entries.push({ table, entry });
    };
    bindings.forEach(visit);
    entries.filter(({ entry }) => !entry.retired).forEach(({ table, entry }) => (entry.dependencies || []).forEach(dep => {
      const ref = typeof dep === 'string' ? { table: table.id, entry: dep } : dep;
      if (entries.some(node => node.table.id === ref.table && node.entry.id === ref.entry && node.entry.retired)) issues.push(objectName(table) + ' / ' + entry.name + ' 仍依赖已移除条目，须先修改业务定义');
    }));
    return { entries, issues: [...new Set(issues)] };
  }
  function draftEligibility(record) {
    const eligible = [], excluded = [];
    record.items.forEach(entry => {
      const scope = publicationScope(record.table.source, [{ table: record.table.id, entry: entry.id }]);
      if (scope.issues.length) excluded.push({ entry, reason: scope.issues[0] }); else eligible.push(entry);
    });
    const bindings = eligible.map(entry => ({ table: record.table.id, entry: entry.id }));
    return { eligible, excluded, bindings, entries: publicationScope(record.table.source, bindings).entries };
  }
  window.GovernanceDemo = { get, records, tasks, tasksFor, availableNotes, objectName, structureFor, contentSections, unitFromText, normalizedUnit, unresolvedText, fieldProfile, criticalKeys, foreignKeyIdentity, definitionIssues, fieldReferences, referenceIssue, bindDependencyRevisions, dependencyRevisionIssue, markForReview, invalidateDependents, isDismissed, hasConfirmedHistory, isConfirmed, unansweredQuestions, refreshAnswers, confirmAnswer, citations, summary, refreshProgress: sync, attention, generate, confirmIssue, act, relationsFor, addRelation, impactTarget, impactReady, prepareImpact, associate, impactNeedsKnowledge, publicationScope, draftEligibility };
  // 接入概况、目录和治理工作台共用当前进度，避免访问顺序改变统计口径。
  DataSourceDemo.tables.forEach(table => sync(get(table)));
})();
