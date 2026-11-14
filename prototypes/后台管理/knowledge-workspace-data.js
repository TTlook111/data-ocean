/* 知识库页面状态：文档修订、题目审核、固定候选和正式入口分别保存，仅在页面内存运行。 */
(() => {
  'use strict';
  const documents = new Map(), cases = new Map(), tasks = new Map(), active = new Map(), releases = new Map();
  const demoDefinitions = new Map(), exampleDefinitions = new Map(), reviewSpecs = new Map(), bans = new Map();
  const prototypeCases = new Map();
  let prototypeSeeded = false;
  const clone = value => structuredClone(value), now = () => DataSourceDemo.now(), id = prefix => DataSourceDemo.nextId(prefix);
  const key = entry => entry.table + '/' + entry.id;
  const has = (actor, ability) => SourceApprovalDemo.hasAbility(actor, ability);
  const permission = (actor, ability, page = ability.split('.')[0]) => has(actor, page + '.view') && has(actor, ability) && (page !== 'releases' || has(actor, 'knowledge.view')) ? '' : '当前账号没有此操作权限。';
  const log = (object, actor, action, reason = '') => object.events.push({ action, reason, actor: actor.name, at: now() });
  const stamp = entries => JSON.stringify(entries.map(entry => [key(entry), entry.objectId, entry.generation, entry.revision, entry.review, entry.validity, entry.content, entry.dependencies, entry.references]));
  const latest = doc => doc.draft;
  const syncSampleSource = () => { if (prototypeSeeded) DataSourceDemo.find('products').knowledge = [...active.values()].filter(row => documents.get(row.document)?.source === 'products' && !row.withdrawn).length; };
  const sourceIssue = source => { const row = DataSourceDemo.find(source); return !row || row.connectionState !== '已启用' || row.health !== '正常' ? '来源当前不可用，请恢复并核对。' : ''; };
  function entriesFromScope(source, bindings) {
    const scope = GovernanceDemo.publicationScope(source, bindings, { allowBlocked: true, allowRetired: true });
    return { issue: scope.issues[0] || '', entries: scope.entries.map(({ table, entry }) => ({
      source, table: table.id, object: GovernanceDemo.objectName(table), objectId: table.identity, generation: table.generation,
      id: entry.id, kind: entry.kind, name: entry.name, field: entry.field, revision: entry.revision, review: entry.review,
      validity: entry.validity, content: clone(entry.content), dependencies: clone(entry.dependencies || []), retired: Boolean(entry.retired),
      valueDetails: clone(entry.valueDetails || null), dependencyRevisions: clone(entry.dependencyRevisions || []), definitionSource: clone(entry.definitionSource || null), confirmedStructure: clone(entry.confirmedStructure || null),
      blocked: Boolean(entry.blocked), references: clone(GovernanceDemo.citations(entry)), reviewer: entry.reviewer, reviewedAt: entry.reviewedAt
    })) };
  }
  function suggested(source) {
    const suggestions = [];
    DataSourceDemo.objects(source).forEach(table => {
      const record = GovernanceDemo.get(table), eligible = GovernanceDemo.draftEligibility(record);
      const base = eligible.eligible.filter(entry => ['overview', 'fields'].includes(entry.kind));
      if (base.length) suggestions.push({ key: table.id + '/base', title: table.label + '说明', kind: '表说明', source,
        bindings: base.map(entry => ({ table: table.id, entry: entry.id })), excluded: eligible.excluded.map(row => row.entry.name + '：' + row.reason) });
      record.items.filter(entry => ['relations', 'metrics'].includes(entry.kind) && entry.review === '已确认').forEach(entry => {
        const scope = entriesFromScope(source, [{ table: table.id, entry: entry.id }]);
        suggestions.push({ key: table.id + '/' + entry.id, title: entry.name + '说明', kind: entry.kind === 'relations' ? '关联说明' : '指标说明', source,
          bindings: [{ table: table.id, entry: entry.id }], excluded: [], issue: scope.issue });
      });
    });
    return suggestions.map(spec => ({ ...spec, existing: [...documents.values()].find(doc => doc.source === source && doc.key === spec.key) }));
  }
  function live(doc) {
    if (doc.simulated) {
      const entries = doc.bindings.map(binding => clone(demoDefinitions.get(binding.table + '/' + binding.entry))).filter(Boolean);
      return { entries, issue: entries.length !== doc.bindings.length ? '来源条目已缺失。' : entries.some(entry => entry.review !== '已确认') ? '相关治理定义尚未审核。' : '' };
    }
    const spec = doc.kind === '表说明' && doc.automaticScope !== false && suggested(doc.source).find(item => item.key === doc.key);
    return entriesFromScope(doc.source, spec?.bindings || doc.bindings);
  }
  const needsUpdate = doc => { const scope = live(doc); return Boolean(scope.issue || stamp(scope.entries) !== doc.draft.basis); };
  function textFor(entries) {
    return entries.map(entry => {
      const labels = GovernanceDemo.contentSections(entry.kind);
      const sections = labels.filter(item => entry.content[item.key]).map(item => item.label + '：' + entry.content[item.key]);
      if (entry.valueDetails?.unit) sections.push('单位：' + entry.valueDetails.unit);
      if (entry.valueDetails?.mappings?.length) sections.push('编码与含义：' + entry.valueDetails.mappings.map(item => item.code + '＝' + item.meaning).join('；'));
      return entry.object + (entry.field ? '.' + entry.field : '') + ' · ' + entry.name + '\n' + sections.join('\n');
    }).join('\n\n');
  }
  function createDocument(spec, actor) {
    const denied = permission(actor, 'knowledge.generate'); if (denied) return { error: denied };
    if (!spec?.bindings?.length) return { error: '请选择依赖完整的已审核内容。' };
    const existing = [...documents.values()].find(doc => doc.key === spec.key && doc.source === spec.source);
    if (existing) return { document: existing, reused: true };
    const scope = entriesFromScope(spec.source, spec.bindings); if (scope.issue) return { error: scope.issue };
    const note = textFor(scope.entries), doc = { id: id('DOC-'), source: spec.source, key: spec.key, title: spec.title, kind: spec.kind,
      bindings: clone(spec.bindings), automaticScope: spec.automaticScope !== false, excluded: spec.excluded || [], revisions: [], events: [], requires: [], at: now(),
      draft: { revision: 1, note, generated: note, entries: scope.entries, basis: stamp(scope.entries), confirmed: false } };
    documents.set(doc.id, doc); log(doc, actor, '生成文档草稿', '冻结已审核的治理修订');
    return { document: doc };
  }
  function saveDocument(doc, note, actor, expectedRevision = doc.draft.revision) {
    const denied = permission(actor, 'knowledge.edit'); if (denied) return denied;
    if (expectedRevision !== doc.draft.revision) return '文档已由其他操作修订，请保留输入并重新核对。';
    if (!note?.trim()) return '请填写知识正文。';
    if (note.trim() === doc.draft.note) return '正文未变化。';
    doc.revisions.push(clone(doc.draft));
    doc.draft = { ...clone(doc.draft), note: note.trim(), confirmed: false, revision: doc.draft.revision + 1 };
    log(doc, actor, '保存文档草稿', '修订 r' + doc.draft.revision);
  }
  function confirmDocument(doc, actor) {
    const denied = permission(actor, 'knowledge.confirm'); if (denied) return denied;
    if (needsUpdate(doc)) return '治理依据已变化，请先核对更新差异。';
    if (!doc.draft.note.trim()) return '请补齐文档正文。';
    if (doc.draft.confirmed) return '当前修订已确认。';
    Object.assign(doc.draft, { confirmed: true, actor: actor.name, confirmedAt: now() }); log(doc, actor, '确认文档', 'r' + doc.draft.revision);
  }
  function updatePreview(doc) {
    const scope = live(doc); return { ...scope, generated: textFor(scope.entries), before: doc.draft.note,
      manual: doc.draft.note !== doc.draft.generated, changed: scope.entries.filter(entry => !doc.draft.entries.some(old => key(old) === key(entry) && stamp([old]) === stamp([entry]))) };
  }
  function applyUpdate(doc, note, actor, expectedBasis) {
    const denied = permission(actor, 'knowledge.edit'); if (denied) return denied;
    const preview = updatePreview(doc); if (preview.issue) return preview.issue;
    if (expectedBasis !== stamp(preview.entries)) return '来源修订再次变化，请重新核对差异。';
    if (!note?.trim()) return '请核对合并后的知识正文。';
    doc.revisions.push(clone(doc.draft));
    doc.draft = { revision: doc.draft.revision + 1, note: note.trim(), generated: preview.generated, entries: preview.entries, basis: stamp(preview.entries), confirmed: false };
    if (!doc.simulated && doc.automaticScope !== false) { const spec = suggested(doc.source).find(item => item.key === doc.key); if (spec) doc.bindings = clone(spec.bindings); }
    log(doc, actor, '合并来源更新', '保留原修订，等待重新确认');
  }
  function importImpact(impact, actor) {
    const found = [...documents.values()].find(doc => doc.source === impact.source && impact.bindings.every(binding => doc.draft.entries.some(entry => key(entry) === binding.table + '/' + binding.entry)));
    if (found) return { document: found };
    const scope = entriesFromScope(impact.source, impact.bindings); if (scope.issue) return { error: scope.issue };
    return createDocument({ source: impact.source, key: 'impact/' + impact.id, title: impact.title + '知识说明', kind: '变更说明', bindings: impact.bindings }, actor);
  }
  function caseIssue(value) {
    for (const [field, label] of [['question', '标准问题'], ['expected', '预期行为或答案'], ['basis', '答案依据'], ['rule', '判定规则'], ['identity', '执行身份']]) if (!value[field]?.trim()) return '请填写' + label + '。';
    if (!value.documents?.length) return '请选择关联文档。';
    if (value.documents.some(docId => documents.get(docId)?.source !== value.source)) return '题目及关联文档必须属于同一数据源。';
    if (value.type === '数值' && !Number.isFinite(Number(value.expected))) return '数值题的预期答案须为有效数值。';
    if (!Number.isFinite(Number(value.tolerance || 0)) || Number(value.tolerance || 0) < 0) return '允许误差须为非负数。';
    return '';
  }
  function saveCase(test, value, actor, revision = test?.revision) {
    const denied = permission(actor, 'test-cases.edit'); if (denied) return { error: denied };
    if (!value.question?.trim() || !value.documents?.length) return { error: '请填写标准问题并选择关联文档。' };
    if (value.documents.some(docId => documents.get(docId)?.source !== value.source)) return { error: '题目及文档必须属于当前数据源。' };
    if (test && revision !== test.revision) return { error: '题目修订已变化，请重新核对。' };
    // 只保存实际修改；重复确认不会制造题目修订并使候选无故失效。
    if (test && test.review !== '已停用' && ['source', 'question', 'documents', 'type', 'expected', 'basis', 'rule', 'identity'].every(field => JSON.stringify(test[field]) === JSON.stringify(value[field])) && Number(test.tolerance || 0) === Number(value.tolerance || 0)) return { test, unchanged: true };
    if (test) { test.history.push(clone({ ...test, history: [], events: [] })); Object.assign(test, clone(value), { revision: test.revision + 1, review: '草稿' }); }
    else { test = { ...clone(value), id: id('TC-'), revision: 1, review: '草稿', events: [], history: [], at: now() }; cases.set(test.id, test); }
    test.dependencyStamp = JSON.stringify(test.documents.map(docId => [docId, documents.get(docId)?.draft.basis]));
    log(test, actor, '保存题目', 'r' + test.revision); return { test };
  }
  const caseStale = test => test.dependencyStamp !== JSON.stringify(test.documents.map(docId => [docId, documents.get(docId)?.draft.basis])) || test.documents.some(docId => needsUpdate(documents.get(docId)));
  function reviewCase(test, actor) {
    const denied = permission(actor, 'test-cases.review'); if (denied) return denied;
    if (test.review === '已停用') return '请先修订已停用题目，再重新审核启用。';
    const issue = caseIssue(test); if (issue) return issue;
    if (test.documents.some(docId => needsUpdate(documents.get(docId)))) return '请先更新关联文档，再审核题目依据。';
    if (test.review === '已审核' && !caseStale(test)) return;
    if (test.review === '已审核' && caseStale(test)) { test.history.push(clone({ ...test, history: [], events: [] })); test.revision++; }
    test.dependencyStamp = JSON.stringify(test.documents.map(docId => [docId, documents.get(docId)?.draft.basis]));
    test.review = '已审核'; test.reviewer = actor.name; test.reviewedAt = now(); log(test, actor, '审核题目', '确认预期、数据依据及判定规则');
  }
  function stopCase(test, actor, reason) {
    const denied = permission(actor, 'test-cases.edit'); if (denied) return denied;
    if (!reason?.trim()) return '请说明停用原因。';
    test.review = '已停用'; log(test, actor, '停用题目', reason);
  }
  const relatedCases = docIds => [...cases.values()].filter(test => test.documents.some(docId => docIds.includes(docId)));
  // 候选题只形成待审核草稿；标准答案和执行结果各自保存，生成文档不会自动审核题目。
  function suggestCases(doc, actor) {
    const denied = permission(actor, 'test-cases.edit'); if (denied) return { error: denied };
    const templates = prototypeCases.get(doc.key) || [{ question: '请说明' + doc.title + '的业务含义及使用条件。', type: '说明', expected: '', basis: '核对文档及其已审核治理依据。', rule: '', identity: '' }];
    const result = [];
    for (const template of templates) {
      const existing = relatedCases([doc.id]).find(test => test.question === template.question && test.review !== '已停用');
      if (existing) { result.push(existing); continue; }
      const saved = saveCase(null, { ...clone(template), source: doc.source, documents: [doc.id], tolerance: 0 }, actor);
      if (saved.error) return saved;
      result.push(saved.test);
    }
    return { tests: result };
  }
  function sampleData(docIds) {
    if (!docIds.length || docIds.some(docId => !prototypeCases.has(documents.get(docId)?.key))) return null;
    if (docIds.every(docId => documents.get(docId).key === 'products:product/base')) return { id: 'SAMPLE-PRODUCT', revision: 1, source: 'products', products: [{ id: 101, code: '0001', status: 1 }, { id: 102, code: '0002', status: 0 }, { id: 103, code: '0003', status: 2 }] };
    return { id: 'SAMPLE-PRODUCT-SALES', revision: 1, source: 'products', rows: clone(reviewSpecs.get('example/sales-quantity').samples) };
  }
  function sampleOutcome(test, current) {
    const templates = test.documents.flatMap(docId => prototypeCases.get(documents.get(docId)?.key) || []);
    const template = templates.find(row => row.sampleCheck === test.sampleCheck && row.question === test.question && row.type === test.type);
    if (!template) return { error: '该题超出固定样例的执行范围，尚未接入真实验证服务。' };
    if (test.sampleCheck === 'sales-quantity') return { actual: String(current.sample.rows.filter(row => row.product === 101 && row.unit === '盒').reduce((sum, row) => sum + row.quantity, 0)) };
    if (test.sampleCheck === 'net-quantity') return { actual: '提示缺少退货口径' };
    return { actual: current.documents.filter(doc => test.documents.includes(doc.id)).map(doc => doc.note).join('\n\n') };
  }
  const caseStamp = docIds => JSON.stringify(relatedCases(docIds).filter(test => test.review === '已审核').map(test => [test.id, test.revision, test.expected, test.rule, test.basis, test.identity, test.dependencyStamp, test.tolerance]));
  function expandDocuments(selected) {
    const result = new Set(selected), visit = docId => {
      const doc = documents.get(docId); if (!doc) return;
      const requiredIds = new Set(doc.requires || []);
      // 只沿显式依赖定位已有基础文档；其他引用同一字段的文档不会因此自动纳入。
      doc.draft.entries.forEach(entry => (entry.dependencies || []).forEach(binding => {
        const ref = typeof binding === 'string' ? { table: entry.table, entry: binding } : binding;
        const owner = [...documents.values()].find(other => other.id !== doc.id && other.source === doc.source && other.kind === '表说明' && other.bindings.some(root => root.table === ref.table && root.entry === ref.entry));
        if (owner) requiredIds.add(owner.id);
      }));
      requiredIds.forEach(required => { const dep = documents.get(required); if (dep && active.get(required)?.snapshot.revision !== dep.draft.revision && !result.has(required)) { result.add(required); visit(required); } });
    };
    selected.forEach(visit); return [...result];
  }
  function createTask(source, docIds, actor) {
    const denied = permission(actor, 'releases.build'); if (denied) return { error: denied };
    if (!docIds.length || docIds.some(docId => documents.get(docId)?.source !== source)) return { error: '请选择当前数据源的文档。' };
    const task = { id: id('PUB-'), source, documents: expandDocuments(docIds), selected: [...docIds], stage: '准备中', candidates: [], currentId: '', events: [], at: now(), scenario: '正常' };
    tasks.set(task.id, task); log(task, actor, '创建发布任务'); return { task };
  }
  function changeScope(task, docIds, actor) {
    const denied = permission(actor, 'releases.build'); if (denied) return denied;
    if (['已发布', '已取消', '构建中', '验证中', '切换中', '结果待核实'].includes(task.stage)) return '当前阶段不能调整范围。';
    if (!docIds.length || docIds.some(docId => documents.get(docId)?.source !== task.source)) return '请选择当前数据源文档。';
    task.selected = [...docIds]; task.documents = expandDocuments(docIds); task.currentId = ''; task.stage = '准备中';
    log(task, actor, '调整本批范围', '原候选保留；新范围需重新构建和验证');
  }
  const candidate = task => task.candidates.find(item => item.id === task.currentId);
  const token = docId => { const row = active.get(docId); return row ? row.release + '/r' + row.snapshot.revision + (row.withdrawn ? '/撤下' : '') : ''; };
  function prepareIssue(task) {
    if (task.kind === '恢复验证') {
      const row = active.get(task.recoveryDocument), doc = row && documents.get(row.document);
      if (!row || token(row.document) !== task.recoveryToken) return '正式版本已变化，请重新发起恢复验证。';
      const scope = live(doc);
      if (scope.issue || scope.entries.some(entry => entry.retired)) return scope.issue || '删除对象不能恢复。';
      if (stamp(scope.entries) !== row.snapshot.basis) return '治理依据与正式版本不一致，请先更新并发布。';
      return sourceIssue(task.source);
    }
    if (!task.documents.length) return '本批尚未选择文档。';
    if (expandDocuments(task.selected).some(docId => !task.documents.includes(docId))) return '必要协同范围已变化，请调整本批范围后重新构建。';
    for (const docId of task.documents) {
      const doc = documents.get(docId); if (!doc || doc.source !== task.source) return '本批包含不可用或其他数据源文档。';
      if (!doc.draft.confirmed) return doc.title + '的当前修订尚未确认。';
      if (needsUpdate(doc)) return doc.title + '的治理依据已变化，请更新并确认。';
    }
    return sourceIssue(task.source);
  }
  function candidateIssue(task, publishing = false) {
    const current = candidate(task); if (!current) return '请先构建固定候选。';
    const issue = prepareIssue(task); if (issue) return issue;
    for (const snapshot of current.documents) {
      const doc = documents.get(snapshot.id);
      if (task.kind !== '恢复验证' && (doc.draft.revision !== snapshot.revision || doc.draft.basis !== snapshot.basis || doc.draft.note !== snapshot.note)) return doc.title + '已修订，请重新构建并验证。';
      if (token(doc.id) !== current.baselines[doc.id]) return doc.title + '的基础版本已变化，请重新核对。';
    }
    if (current.caseStamp !== caseStamp(task.documents)) return task.kind === '恢复验证' ? '相关已审核题单已变化，请重新发起恢复验证。' : '相关已审核题单已变化，请重新构建并验证。';
    const tests = relatedCases(task.documents).filter(test => test.review === '已审核' && !caseStale(test));
    if (relatedCases(task.documents).some(test => test.review === '已审核' && caseStale(test))) return '相关标准题依据已变化，请先复核并审核题目。';
    const missing = task.documents.find(docId => !tests.some(test => test.documents.includes(docId)));
    if (missing) return documents.get(missing).title + '缺少相关已审核题，请补题并审核。';
    if (publishing && (!current.built || current.runs.at(-1)?.result !== '通过')) return '当前候选的必要验证尚未全部通过。';
    return '';
  }
  // 显示状态和下一步使用同一门槛；保留历史阶段，不将已完成的运行改写成失败。
  function taskFeedback(task) {
    if (['已发布', '已取消'].includes(task.stage)) return { state: task.stage, issue: '', next: '' };
    if (['构建中', '验证中', '切换中', '结果待核实'].includes(task.stage)) return { state: task.stage, issue: '', next: '' };
    const current = candidate(task), issue = current ? candidateIssue(task, task.stage === '待发布') : prepareIssue(task);
    if (!issue) return { state: task.stage, issue: '', next: task.stage === '构建失败' ? 'build' : '' };
    const recoveryRow = task.kind === '恢复验证' && active.get(task.recoveryDocument), recoveryDoc = recoveryRow && documents.get(recoveryRow.document);
    let doc;
    if (recoveryDoc && stamp(live(recoveryDoc).entries) !== recoveryRow.snapshot.basis) doc = recoveryDoc;
    else if (task.kind !== '恢复验证') doc = task.documents.map(docId => documents.get(docId)).find(doc => doc && (!doc.draft.confirmed || needsUpdate(doc)));
    let next;
    if (sourceIssue(task.source)) next = 'source';
    else if (doc) next = 'document';
    else if (task.kind === '恢复验证' && prepareIssue(task)) next = 'restart';
    else if (expandDocuments(task.selected).some(docId => !task.documents.includes(docId))) next = 'scope';
    else {
      const tests = relatedCases(task.documents).filter(test => test.review === '已审核');
      const missing = task.documents.some(docId => !tests.some(test => !caseStale(test) && test.documents.includes(docId)));
      next = missing || tests.some(caseStale) ? 'cases' : task.kind === '恢复验证' ? 'restart' : 'build';
    }
    return { state: task.stage === '验证已完成' ? task.stage : '被阻断', issue, next, document: doc?.id, expired: task.stage === '验证已完成' };
  }
  function build(task, actor) {
    const denied = permission(actor, 'releases.build'); if (denied) return denied;
    if (task.kind === '恢复验证') return '恢复验证固定当前正式版本，无需重新构建。';
    if (['已发布', '已取消', '构建中', '验证中', '切换中', '结果待核实'].includes(task.stage)) return '当前任务不能重新构建。';
    const issue = prepareIssue(task); if (issue) return issue;
    const current = { id: id('CAND-'), documents: task.documents.map(docId => ({ id: docId, title: documents.get(docId).title, ...clone(documents.get(docId).draft) })),
      baselines: Object.fromEntries(task.documents.map(docId => [docId, token(docId)])), tests: clone(relatedCases(task.documents).filter(test => test.review === '已审核')),
      caseStamp: caseStamp(task.documents), sample: sampleData(task.documents), runs: [], built: false, at: now() };
    task.candidates.push(current); task.currentId = current.id; task.stage = '构建中'; log(task, actor, '固定并构建候选', current.id);
  }
  function finishBuild(task, actor, expectedId = task.currentId) {
    if (expectedId !== task.currentId) return '候选已变化，旧构建结果不能写入新候选。';
    if (task.stage !== '构建中') return '构建任务已变化。';
    if (task.scenario === '构建失败' && task.documents.every(docId => documents.get(docId).simulated)) { task.stage = '构建失败'; log(task, actor, '构建失败', '演示：一个必要片段未完成'); return; }
    candidate(task).built = true; task.stage = '待验证'; log(task, actor, '候选构建完成', candidate(task).id);
  }
  function compare(test, actual, error) {
    if (error || actual === undefined) return '执行异常';
    if (test.type === '说明') return '待裁定';
    if (test.type === '数值') return Number.isFinite(Number(actual)) && Math.abs(Number(actual) - Number(test.expected)) <= Number(test.tolerance || 0) ? '通过' : '未通过';
    return String(actual).trim() === test.expected.trim() ? '通过' : '未通过';
  }
  function run(task, actor) {
    const denied = permission(actor, 'releases.test'); if (denied) return denied;
    if (!['待验证', '验证未通过', '待裁定', '执行异常', '待发布'].includes(task.stage)) return '当前阶段不能运行验证。';
    const issue = candidateIssue(task); if (issue) return issue;
    task.stage = '验证中'; log(task, actor, '运行候选验证', candidate(task).id);
  }
  function finishRun(task, actor, expectedId = task.currentId) {
    if (expectedId !== task.currentId) return '候选已变化，旧验证结果不能写入新候选。';
    if (task.stage !== '验证中') return '验证任务已变化。';
    const current = candidate(task), issue = candidateIssue(task);
    if (issue) { task.stage = '被阻断'; log(task, actor, '验证依据已变化', issue); return; }
    const results = current.tests.map(test => {
      // 固定样例独立计算实际值，不回填预期；超出受控范围且未接执行服务时明确异常。
      const simulated = task.documents.every(docId => documents.get(docId).simulated), outcome = current.sample ? sampleOutcome(test, current) : { actual: simulated ? test.demoActual : undefined, error: simulated ? '' : '验证服务尚未接入。' };
      const actual = task.scenario === '结果不符' && simulated ? '999' : outcome.actual;
      const error = task.scenario === '执行异常' && simulated ? '演示：执行服务超时' : outcome.error || '';
      return { test: test.id, revision: test.revision, question: test.question, expected: test.expected, actual, error,
        rule: test.rule, basis: test.basis, identity: test.identity, documents: test.documents, result: compare(test, actual, error), decisions: [] };
    });
    const execution = { id: id('RUN-'), candidate: current.id, results, at: now(), actor: actor.name };
    current.runs.push(execution); settle(task); log(task, actor, '候选验证结束', execution.result);
  }
  function settle(task) {
    const execution = candidate(task).runs.at(-1), values = execution.results.map(row => row.result);
    execution.result = values.includes('执行异常') ? '执行异常' : values.includes('未通过') ? '未通过' : values.includes('待裁定') ? '待裁定' : values.length ? '通过' : '未验证';
    task.stage = { 通过: '待发布', 未通过: '验证未通过', 待裁定: '待裁定', 执行异常: '执行异常' }[execution.result] || '被阻断';
    if (task.kind === '恢复验证' && execution.result === '通过') {
      const row = active.get(task.recoveryDocument);
      row.recovery = { token: token(row.document), basis: row.snapshot.basis, cases: caseStamp([row.document]), at: now(), result: '通过', task: task.id, run: execution.id };
      task.stage = '验证已完成';
    }
  }
  function adjudicate(task, testId, decision, reason, actor) {
    const denied = permission(actor, 'test-cases.judge'); if (denied) return denied;
    if (!has(actor, 'releases.view')) return '当前账号没有发布任务查看权限。';
    const issue = candidateIssue(task); if (issue) return issue;
    const row = candidate(task)?.runs.at(-1)?.results.find(item => item.test === testId);
    if (!row || row.result !== '待裁定') return '只有当前候选待裁定的结果可以办理。';
    if (!['通过', '未通过'].includes(decision)) return '请选择有效的裁定结论。';
    if (!reason?.trim()) return '请填写裁定依据。';
    row.decisions.push({ actor: actor.name, reason, decision, at: now() }); row.result = decision; settle(task); log(task, actor, '裁定验证结果', testId + '：' + decision + '；' + reason);
  }
  function cancel(task, actor, reason) {
    const denied = permission(actor, 'releases.build'); if (denied) return denied;
    if (['已发布', '验证已完成', '切换中', '结果待核实'].includes(task.stage)) return '当前任务不能取消，请先核实实际切换结果。';
    if (!reason?.trim()) return '请说明取消原因。';
    task.stage = '已取消'; log(task, actor, '取消发布任务', reason);
  }
  function publish(task, actor) {
    const denied = permission(actor, 'releases.publish'); if (denied) return denied;
    if (task.stage !== '待发布') return '当前任务尚未到达待发布阶段。';
    const issue = candidateIssue(task, true); if (issue) return issue;
    task.stage = '切换中'; log(task, actor, '确认发布', candidate(task).id);
  }
  function finishPublish(task, actor, expectedId = task.currentId) {
    if (expectedId !== task.currentId) return '候选已变化，不能切换旧候选。';
    if (task.stage !== '切换中') return '发布任务已变化。';
    const issue = candidateIssue(task, true); if (issue) { task.stage = '被阻断'; log(task, actor, '切换前检查阻断', issue); return issue; }
    if (task.scenario === '结果待核实') { task.stage = '结果待核实'; log(task, actor, '切换结果待核实', '需读取实际生效入口，不重复提交'); return; }
    const current = candidate(task), release = { id: task.id, source: task.source, candidate: current.id, at: now(), actor: actor.name,
      targets: current.documents.map(snapshot => ({ document: snapshot.id, before: clone(active.get(snapshot.id) || null), after: clone(snapshot) })), events: [] };
    // 先完成整批预检，再一次更新页面内入口；任一必要项失败时都不写入。
    release.targets.forEach(target => active.set(target.document, { document: target.document, release: release.id, snapshot: target.after,
      pauses: clone(target.before?.pauses || []), previous: target.before, events: [], recovery: null, withdrawn: target.after.entries.every(entry => entry.retired) }));
    releases.set(release.id, release); task.stage = '已发布'; log(task, actor, '整批切换完成', release.targets.map(target => target.after.title + ' r' + target.after.revision).join('、'));
    finishImpacts(release, actor);
    syncSampleSource();
  }
  function reconcile(task, actor) {
    const denied = permission(actor, 'releases.publish'); if (denied) return denied;
    if (task.stage !== '结果待核实') return '当前任务不需要核实切换结果。';
    task.stage = task.documents.every(docId => active.get(docId)?.release === task.id) ? '已发布' : '待发布';
    log(task, actor, '核实实际生效入口', task.stage === '已发布' ? '已确认本批生效' : '当前仍为原版本，可重新检查后发布');
  }
  function restrictions(row) {
    const doc = documents.get(row.document), scope = live(doc);
    const reasons = [...row.pauses.map(item => item.reason)];
    row.snapshot.entries.forEach(entry => {
      if (entry.retired) return;
      const current = scope.entries.find(item => key(item) === key(entry));
      if (bans.has(key(entry))) reasons.push(bans.get(key(entry)));
      if (!doc.simulated && current?.blocked) reasons.push(entry.object + (entry.field ? '.' + entry.field : '') + '禁止使用');
      if (current?.retired && !entry.retired) reasons.push(entry.name + '已删除，依赖知识暂停');
    });
    return [...new Set(reasons)];
  }
  function activeState(row) {
    if (row.withdrawn) return '已撤下';
    if (restrictions(row).length) {
      const doc = documents.get(row.document), entries = live(doc).entries;
      const usable = row.snapshot.entries.filter(entry => !entry.retired), blocked = usable.filter(entry => bans.has(key(entry)) || entries.some(current => key(current) === key(entry) && (current.retired || (!doc.simulated && current.blocked))));
      return !row.pauses.length && blocked.length && blocked.length < usable.length ? '部分暂停' : '暂停';
    }
    if (sourceIssue(documents.get(row.document).source)) return '来源不可用';
    if (needsUpdate(documents.get(row.document))) return '待复核';
    return '正常';
  }
  function pause(row, reason, actor) {
    const denied = permission(actor, 'versions.pause', 'releases'); if (denied) return denied;
    if (!reason?.trim()) return '请填写暂停原因。';
    row.pauses.push({ id: id('PAUSE-'), reason, actor: actor.name, at: now() }); row.recovery = null; log(row, actor, '暂停知识', reason);
  }
  function recoveryIssue(row) {
    const doc = documents.get(row.document), scope = live(doc), source = sourceIssue(doc.source);
    if (source) return source;
    if (scope.issue || scope.entries.some(entry => entry.retired)) return scope.issue || '删除对象保持禁止，不能恢复。';
    if (stamp(scope.entries) !== row.snapshot.basis) return '当前治理修订与生效版本不一致，请先完成文档更新、验证和发布。';
    if (!doc.simulated && !sampleData([doc.id])) return '恢复验证服务尚未接入，不能以手填通过解除禁止。';
    const tests = relatedCases([doc.id]).filter(test => test.review === '已审核' && !caseStale(test));
    if (!tests.length) return '缺少相关已审核题。';
    if (sampleData([doc.id])) return row.recovery?.result === '通过' && row.recovery.cases === caseStamp([doc.id]) ? '' : '请先完成当前正式版本的固定样例恢复验证。';
    if ((!row.recovery || row.recovery.cases !== caseStamp([row.document])) && tests.some(test => compare(test, test.demoActual, '') !== '通过')) return '恢复验证未全部通过，请在发布任务处理验证结果。';
    return '';
  }
  function createRecoveryTask(row, actor) {
    const denied = permission(actor, 'releases.test'); if (denied) return { error: denied };
    const task = { id: id('CHECK-'), source: documents.get(row.document).source, kind: '恢复验证', recoveryDocument: row.document, recoveryToken: token(row.document), documents: [row.document], selected: [row.document],
      stage: '待验证', candidates: [], currentId: id('CAND-'), events: [], at: now(), scenario: '正常' };
    const issue = prepareIssue(task); if (issue) return { error: issue };
    task.candidates.push({ id: task.currentId, documents: [clone(row.snapshot)], baselines: { [row.document]: token(row.document) },
      tests: clone(relatedCases(task.documents).filter(test => test.review === '已审核')), caseStamp: caseStamp(task.documents), sample: sampleData(task.documents), runs: [], built: true, at: now() });
    tasks.set(task.id, task); log(task, actor, '创建当前版本隔离恢复验证', row.release); return { task };
  }
  function restartRecovery(task, actor) {
    if (task.kind !== '恢复验证' || ['验证中', '切换中'].includes(task.stage)) return { error: '当前任务不能重新发起恢复验证。' };
    const row = active.get(task.recoveryDocument); if (!row) return { error: '当前正式版本不存在，请返回当前生效查看。' };
    const result = createRecoveryTask(row, actor);
    if (!result.error) { task.supersededBy = result.task.id; log(task, actor, '重新发起恢复验证', result.task.id); }
    return result;
  }
  function recoveryFeedback(row) {
    const source = documents.get(row.document).source;
    const eligibility = prepareIssue({ source, kind: '恢复验证', recoveryDocument: row.document, recoveryToken: token(row.document) });
    if (eligibility) return { state: row.recovery ? '已失效' : '未验证', issue: eligibility, eligibility, ready: false };
    if (!row.recovery) return { state: '未验证', issue: '请先运行当前版本的隔离恢复验证。', eligibility: '', ready: false };
    if (row.recovery.token !== token(row.document) || row.recovery.basis !== row.snapshot.basis || row.recovery.cases !== caseStamp([row.document])) return { state: '已失效', issue: '恢复依据或题单已变化，请重新验证。', eligibility: '', ready: false };
    const issue = recoveryIssue(row); return { state: issue ? '已失效' : '通过', issue, eligibility: '', ready: !issue };
  }
  function releaseStatus(task, docId) {
    const release = releases.get(task.id), target = release?.targets.find(item => item.document === docId), row = active.get(docId);
    if (!target) return { revision: null, current: row?.snapshot.revision, state: '未发布' };
    const rolledBack = release.events.some(event => event.action === '整批回退到直接上一版');
    return { revision: target.after.revision, current: row?.snapshot.revision,
      state: row?.release === release.id ? row.withdrawn ? '已撤下' : '当前生效' : rolledBack ? '已回退' : '已被替代' };
  }
  function verifyRecovery(row, actor) {
    const denied = permission(actor, 'releases.test'); if (denied) return denied;
    const issue = recoveryIssue(row); if (issue) return issue;
    row.recovery = { token: token(row.document), basis: row.snapshot.basis, cases: caseStamp([row.document]), at: now(), result: '通过' };
    log(row, actor, '隔离恢复验证通过', '生产限制保持不变');
  }
  function recover(row, actor, reason) {
    const denied = permission(actor, 'governance.allow', 'releases'); if (denied) return denied;
    const issue = recoveryIssue(row); if (issue) return issue;
    if (!reason?.trim()) return '请填写原风险已经消除的核对依据。';
    if (!row.recovery || row.recovery.token !== token(row.document) || row.recovery.cases !== caseStamp([row.document])) return '请先完成当前版本的隔离恢复验证。';
    const doc = documents.get(row.document);
    if (!doc.simulated && !sampleData([doc.id])) return '真实解除服务尚未接入。';
    row.snapshot.entries.forEach(entry => bans.delete(key(entry))); row.pauses = []; row.withdrawn = false; row.recovery = null;
    log(row, actor, '显式恢复知识', reason); finishImpacts(releases.get(row.release), actor);
    syncSampleSource();
  }
  function rollbackIssue(release) {
    if (!release) return '找不到当前发布批次。';
    if (sourceIssue(release.source)) return sourceIssue(release.source);
    for (const target of release.targets) {
      const row = active.get(target.document);
      if (row?.release !== release.id) return '本批部分目标已被后续发布替换，不能覆盖后续版本。';
      if (!target.before || target.before.withdrawn) return '本批存在没有直接上一版的目标，不能只回退部分内容。';
      if (restrictions(row).length) return '本批仍有禁止或暂停，请先核对风险；回退不能解除限制。';
      const doc = documents.get(target.document), scope = live(doc);
      if (scope.issue || stamp(scope.entries) !== target.before.snapshot.basis) return '直接上一版与当前治理或结构不兼容。';
      if (!doc.simulated) return '回退验证服务尚未接入。';
      const tests = relatedCases([doc.id]).filter(test => test.review === '已审核' && !caseStale(test));
      if (!tests.length || tests.some(test => compare(test, test.demoActual, '') !== '通过')) return '直接上一版的相关验证未全部通过。';
    }
    return '';
  }
  function rollback(release, actor, reason) {
    const denied = permission(actor, 'versions.rollback', 'releases'); if (denied) return denied;
    const issue = rollbackIssue(release); if (issue) return issue;
    if (!reason?.trim()) return '请填写回退原因。';
    release.targets.forEach(target => { const current = active.get(target.document), before = clone(target.before); before.pauses = clone(current.pauses); log(before, actor, '整批回退完成', release.id + '；' + reason); active.set(target.document, before); });
    log(release, actor, '整批回退到直接上一版', reason);
    if (tasks.has(release.id)) log(tasks.get(release.id), actor, '整批回退到直接上一版', reason);
    syncSampleSource();
  }
  function withdrawalScope(docId) {
    const result = new Set([docId]); let changed = true;
    while (changed) { changed = false; for (const row of active.values()) {
      if (!row.withdrawn && !result.has(row.document) && (documents.get(row.document).requires || []).some(required => result.has(required))) { result.add(row.document); changed = true; }
    } }
    return [...result];
  }
  function withdraw(row, actor, reason) {
    const denied = permission(actor, 'releases.withdraw'); if (denied) return denied;
    if (!reason?.trim()) return '请填写撤下原因。';
    withdrawalScope(row.document).forEach(docId => { const target = active.get(docId); target.withdrawn = true; target.recovery = null; log(target, actor, '撤下知识', reason); });
    syncSampleSource();
  }
  function finishImpacts(release, actor) {
    if (!release) return;
    const snapshots = release.targets.map(target => active.get(target.document)).filter(Boolean), entries = snapshots.flatMap(row => row.snapshot.entries), keys = new Set(entries.map(key));
    if (snapshots.some(row => documents.get(row.document)?.simulated)) return;
    GovernanceImpactDemo.records.filter(impact => impact.source === release.source && impact.state === '知识处理中' && impact.bindings.length && impact.bindings.every(binding => keys.has(binding.table + '/' + binding.entry))).forEach(impact => {
      const impactKeys = new Set(impact.bindings.map(binding => binding.table + '/' + binding.entry));
      if (snapshots.filter(row => row.snapshot.entries.some(entry => impactKeys.has(key(entry)))).some(row => !row.withdrawn && restrictions(row).length)) { impact.knowledge = '已发布，待显式解除禁止'; return; }
      impact.state = '已完成'; impact.knowledge = '已更新'; impact.completion = entries.every(entry => entry.retired) ? '依赖知识已停用并发布；删除对象保持禁止使用' : '知识已更新、验证并发布';
      impact.revision++; impact.events.push({ action: '知识发布回写完成', actor: actor.name, at: now(), reason: release.id });
    });
  }
  // 评审示例使用独立定义，不填充真实治理标签，不在普通页面加载审核题或通过结果。
  function seedReview(actor) {
    seedExamples();
    if ([...documents.values()].some(doc => doc.simulated)) return;
    const tables = DataSourceDemo.objects('retail').slice(0, 15);
    tables.forEach((table, index) => {
      const entry = { source: table.source, table: table.id, object: GovernanceDemo.objectName(table), objectId: table.identity, generation: table.generation,
        id: 'overview', kind: 'overview', name: '表说明', revision: 1, review: '已确认', validity: '适用', dependencies: [], retired: false,
        content: { meaning: '演示资料：' + table.label + '用于记录对应业务。', values: '演示资料：一行对应一条' + table.label + '记录。', scope: '', updates: '' }, references: [], reviewer: '演示审核人', reviewedAt: '2026-11-14 09:00' };
      if (exampleDefinitions.has(key(entry))) Object.assign(entry, clone(exampleDefinitions.get(key(entry))));
      demoDefinitions.set(key(entry), clone(entry));
      const note = textFor([entry]), doc = { id: 'DEMO-DOC-' + (index + 1), key: 'demo/' + table.id, source: 'retail', title: table.label + '说明', kind: '表说明', simulated: true,
        bindings: [{ table: table.id, entry: 'overview' }], requires: [], excluded: [], events: [], revisions: [], at: '2026-11-14 09:' + String(index).padStart(2, '0'),
        draft: { revision: 1, note, generated: note, entries: [clone(entry)], basis: stamp([entry]), confirmed: index !== 4, actor: '演示审核人', confirmedAt: '2026-11-14 09:00' } };
      documents.set(doc.id, doc);
      const test = { id: 'DEMO-TC-' + (index + 1), source: 'retail', documents: [doc.id], question: index === 1 ? '销售10盒，退货1盒和2盒，净销量是多少？' : table.label + '的业务说明是否保留完整含义？',
        type: index === 1 ? '数值' : '规则', expected: index === 1 ? '7' : '含义与粒度完整', tolerance: 0, basis: index === 1 ? '演示数据：销售10，退货1、2；已核对10－1－2＝7。' : '演示资料：已审核含义及记录粒度', rule: index === 1 ? '按盒计算，先汇总退货，结果等于7。' : '含义和粒度均存在，结果标记为含义与粒度完整。',
        identity: '演示验证账号', demoActual: index === 1 ? String(10 - 1 - 2) : '含义与粒度完整', revision: 1, review: index === 3 ? '草稿' : '已审核', history: [], events: [], at: doc.at };
      test.dependencyStamp = JSON.stringify([[doc.id, doc.draft.basis]]); cases.set(test.id, test);
      if (index < 3 || index > 6) {
        const releaseId = 'DEMO-BASE-' + (index + 1), snapshot = { id: doc.id, title: doc.title, ...clone(doc.draft) };
        active.set(doc.id, { document: doc.id, release: releaseId, snapshot, pauses: [], previous: null, events: [], recovery: null });
        releases.set(releaseId, { id: releaseId, source: 'retail', at: doc.at, actor: '演示发布人', targets: [{ document: doc.id, before: null, after: snapshot }], events: [] });
      }
    });
    const sales = documents.get('DEMO-DOC-2'), returns = documents.get('DEMO-DOC-6');
    for (const [number, title, kind, dependencies] of [
      [16, '销售与退货关联说明', 'relations', [sales, returns]],
      [17, '净销量口径说明', 'metrics', [sales, returns]]
    ]) {
      if (number === 17) dependencies.push(documents.get('DEMO-DOC-16'));
      const entry = { ...clone(sales.draft.entries[0]), id: kind === 'metrics' ? 'metric:net' : 'relation:returns', kind, name: title,
        dependencies: dependencies.map(doc => ({ table: doc.draft.entries[0].table, entry: doc.draft.entries[0].id })),
        content: { meaning: '演示资料：' + title, values: kind === 'metrics' ? '净销量＝销售数量－退货数量，先汇总同一销售行的退货，单位：盒。' : '按原销售记录关联退货；一条销售允许多次退货，退货先汇总。', usage: '演示受控数据：销售10盒，两次退货1盒与2盒。' } };
      demoDefinitions.set(key(entry), clone(entry));
      const entries = [...new Map([...dependencies.flatMap(doc => doc.draft.entries), entry].map(item => [key(item), clone(item)])).values()], note = textFor(entries);
      const doc = { id: 'DEMO-DOC-' + number, key: 'demo/' + kind, source: 'retail', title, kind: kind === 'metrics' ? '指标说明' : '关联说明', simulated: true,
        bindings: entries.map(item => ({ table: item.table, entry: item.id })), requires: dependencies.map(item => item.id), excluded: [], events: [], revisions: [], at: '2026-11-14 09:20',
        draft: { revision: 1, note, generated: note, entries, basis: stamp(entries), confirmed: true } };
      documents.set(doc.id, doc);
      const test = { id: 'DEMO-TC-' + number, source: 'retail', documents: [doc.id], question: kind === 'metrics' ? '销售10盒、退货1盒和2盒，净销量是多少？' : '同一销售行多次退货应如何关联？', type: kind === 'metrics' ? '数值' : '规则',
        expected: kind === 'metrics' ? '7' : '退货先汇总', demoActual: kind === 'metrics' ? String(10 - 1 - 2) : '退货先汇总', tolerance: 0,
        basis: '演示受控数据及已确认关联口径', rule: kind === 'metrics' ? '按盒计算，结果为7。' : '先按原销售记录汇总退货再关联。', identity: '演示验证账号', revision: 1, review: '已审核', history: [], events: [], at: doc.at };
      test.dependencyStamp = JSON.stringify([[doc.id, doc.draft.basis]]); cases.set(test.id, test);
    }
  }
  function seedExamples() {
    if (reviewSpecs.size) return;
    const product = DataSourceDemo.objects('retail').find(table => table.name === 'product'), sales = DataSourceDemo.objects('retail').find(table => table.name === 'sale_item');
    const materials = {
      product: { id: 'EX-NOTE-PRODUCT', title: '商品档案编码与状态约定', revision: 1, simulated: true, excerpt: '商品以内部id唯一标识，业务编码按文本保存，1表示在售、0表示停售，其他编码暂未确认。', content: '商品档案一行一件商品，以内部id唯一标识。code是展示用业务编码，按原始文本保留前导零。status中1为当前在售、0为当前停售；空值和其他编码未确认，不推定含义。当前状态不能说明历史在售情况，也不能证明有库存。' },
      sales: { id: 'EX-NOTE-SALES', title: '商品销售数量统计口径', revision: 1, simulated: true, excerpt: '销售明细按product_id关联商品id，多对一；按相同单位汇总原销售数量，不扣退货。', content: 'sale_item一行一条销售商品明细，以sale_item_id标识。product_id关联product.id，多条明细对应一件商品。quantity为原销售数量，本示例单位为盒；仅同单位汇总。该统计不扣退货，不是净销量。商品当前在售状态不用于默认过滤历史销售明细。受控样例为商品101的两条明细，销售数量分别为10盒和2盒，汇总为12盒。' }
    };
    const add = (table, entryId, kind, name, content, dependencies = [], reference = materials.product) => {
      const entry = { source: 'retail', table: table.id, object: GovernanceDemo.objectName(table), objectId: table.identity, generation: table.generation, id: entryId, kind, name,
        ...(entryId.startsWith('field:') ? { field: entryId.slice(6), type: table.fields.find(field => field.name === entryId.slice(6))?.type } : {}),
        content, dependencies, revision: 1, review: '已确认', validity: '适用', retired: false, references: [clone(reference)], reviewer: '示例业务审核人', reviewedAt: '2026-11-14 09:30' };
      exampleDefinitions.set(key(entry), entry); return { table: table.id, entry: entryId };
    };
    const pOverview = add(product, 'overview', 'overview', '表说明', { meaning: '维护商品基础档案，用于识别商品及解释业务编码和当前销售状态。', values: '一行一件商品，以id唯一标识；不按业务编码判断唯一性。', scope: '包含商品基础信息，不包含门店实时库存、成交价格或退货事实。', updates: '展示当前档案状态，不能还原历史某日的商品状态。' });
    const pId = add(product, 'field:id', 'fields', '商品内部编号', { meaning: '商品在本系统内的唯一标识。', values: '非空整数，一件商品对应一个id。', rules: '识别和唯一定位商品时使用id，不使用展示编码替代。', usage: '不能把其他系统相同数字的id直接视为同一商品。' });
    const pCode = add(product, 'field:code', 'fields', '商品业务编码', { meaning: '面向业务人员展示和检索的商品编码。', values: '按文本保存，例如0001；保留前导零。', rules: '展示及按编码检索时使用原始文本。', usage: '不把编码自动视为唯一关联键。' });
    add(product, 'field:status', 'fields', '商品当前销售状态', { meaning: '商品当前是否允许销售。', values: '1＝在售；0＝停售。空值和其他编码的含义尚未确认。', rules: '明确查询当前在售商品时筛选1，查询当前停售商品时筛选0。', usage: '不能据此推断库存是否大于零，不能还原历史在售状态，不猜测编码2的含义。' });
    const sOverview = add(sales, 'overview', 'overview', '表说明', { meaning: '记录每条销售商品明细，用于按商品统计原销售数量。', values: '一行一条商品销售明细，以sale_item_id唯一标识。', scope: '本次口径只统计原销售数量，不包含退货扣减。', updates: '读取获准的实际业务数据，知识发布时间不代表数据更新时间。' }, [], materials.sales);
    const sId = add(sales, 'field:sale_item_id', 'fields', '销售明细编号', { meaning: '销售商品行的唯一标识。', values: '非空整数，受控样例为1001和1002，不代表完整取值集合。', rules: '按明细行识别和核对数量，不重复累计同一行。', usage: '不把商品编号当成明细唯一编号。' }, [], materials.sales);
    const sProduct = add(sales, 'field:product_id', 'fields', '销售商品编号', { meaning: '该销售明细对应的商品内部id。', values: '可在多条明细中重复。受控样例为101，不代表只有这一商品。', rules: '关联product.id。', usage: '关联规则以已审核的关联条目为准。' }, [], materials.sales);
    const sQuantity = add(sales, 'field:quantity', 'fields', '原销售数量', { meaning: '本条销售商品明细的原始销售数量。', values: '本示例计量单位为盒，允许小数。', rules: '只对相同单位的数量求和，不默认扣减退货。', usage: '不能将原销售数量称为净销量。' }, [], materials.sales);
    const relation = add(sales, 'relation:product', 'relations', '销售明细与商品', { meaning: 'sale_item.product_id＝product.id。', values: '多条销售明细对应一个商品；商品id唯一，关联后不能增加明细行数。', usage: '不使用商品编码关联，不按商品当前在售状态默认过滤历史销售。' }, [sProduct, pId], materials.sales);
    add(sales, 'metric:sales-quantity', 'metrics', '商品原销售数量', { meaning: '按商品汇总原销售数量，不扣退货。', values: '按 product_id 分组，对同单位的 sale_item.quantity 求 SUM；经已审核关联读取商品编码。', usage: '单位：盒；只支持原销售数量，不支持净销量和实时库存。' }, [sOverview, sId, sQuantity, relation, pOverview, pCode], materials.sales);
    const samples = [{ id: 1001, product: 101, code: '0001', quantity: 10, unit: '盒' }, { id: 1002, product: 101, code: '0001', quantity: 2, unit: '盒' }];
    reviewSpecs.set('example/product', {
      tests: [{ question: '商品编号、编码和当前状态如何使用？', type: '说明', expected: 'id唯一；code保留前导零；status仅解释0和1', demoActual: '核对候选正文中的商品唯一标识、编码文本规则和0／1状态映射，其他编码保留未知。', basis: '示例商品档案约定及已审核字段标签。', rule: '人工对照正文与来源：保留id唯一性、编码前导零、已确认状态映射；不推断其他编码。' }] });
    reviewSpecs.set('example/sales-quantity', { samples,
      tests: [{ question: '商品101的原销售数量是多少盒？', type: '数值', expected: '12', demoActual: String(samples.reduce((sum, row) => sum + row.quantity, 0)), basis: '受控样例：销售明细1001为10盒、1002为2盒，均属商品101。', rule: '按盒汇总两条明细，结果为12；同一行不能因关联被重复累计。' },
        { question: '商品101的净销量是多少？', type: '规则', expected: '提示缺少退货口径', demoActual: '提示缺少退货口径', basis: '本次已审核范围只含原销售数量，退货口径未确认。', rule: '明确知识缺口，不把12盒原销量冒充净销量。' }] });
  }
  // 在现有商品中心库预置可追溯的已审核输入，文档、题单、发布和通过结果均由页面操作产生。
  function seedPrototype(actor) {
    if (prototypeSeeded) return;
    seedExamples();
    const source = 'products', remap = tableId => tableId.replace(/^retail:/, source + ':');
    const definitions = [...exampleDefinitions.values()].map(value => {
      const entry = clone(value); entry.source = source; entry.table = remap(entry.table);
      entry.dependencies = entry.dependencies.map(ref => ({ table: remap(ref.table), entry: ref.entry }));
      entry.references.forEach(ref => {
        delete ref.simulated; ref.source = source; ref.origin = '流程样例'; ref.author = actor.id; ref.files = []; ref.status = '有效'; ref.updated = '2026-11-14 09:30:00'; ref.excerpt = ref.content;
        if (!DataSourceDemo.notes.some(note => note.id === ref.id)) DataSourceDemo.notes.unshift({ ...clone(ref), history: [] });
      });
      return entry;
    });
    for (const value of definitions) {
      const table = DataSourceDemo.objects(source).find(table => table.id === value.table), record = GovernanceDemo.get(table);
      let entry = record.items.find(entry => entry.id === value.id);
      if (!entry) { entry = { id: value.id, kind: value.kind, name: value.name, history: [] }; record.items.push(entry); }
      Object.assign(entry, { content: clone(value.content), dependencies: clone(value.dependencies), references: [], manualEvidence: clone(value.references[0]), gap: '', basis: '业务负责人已核对的原型样例', revision: 1, review: '待确认', validity: '适用', publication: '未发布', reviewer: '', reviewedAt: '', ai: null });
      if (value.kind === 'fields') entry.valueDetails = { unit: value.field === 'quantity' ? '盒' : '', mappings: value.field === 'status' ? [{ code: '1', meaning: '在售' }, { code: '0', meaning: '停售' }] : [] };
      const issue = GovernanceDemo.act(record, entry, 'confirm', actor); if (issue) throw new Error('流程样例审核失败：' + value.id + '：' + issue);
    }
    const identity = '原型固定样例 · 只读', product = reviewSpecs.get('example/product').tests[0], sales = reviewSpecs.get('example/sales-quantity').tests;
    const register = (key, values) => prototypeCases.set(source + ':' + key, values.map(value => { const row = { ...clone(value), identity }; delete row.demoActual; return row; }));
    register('product/base', [{ ...product, sampleCheck: 'product-rules', basis: '商品档案编码与状态约定 r1；商品表说明及id、code、status的已审核定义。' }]);
    register('sale_item/base', [{ question: '销售明细的一行代表什么，数量如何使用？', type: '说明', expected: '一行一条销售明细；以sale_item_id标识；quantity单位为盒，表示未扣退货的原销售数量。', basis: '商品销售数量统计口径 r1及已审核的销售明细标签。', rule: '人工核对记录粒度、唯一标识、单位及退货边界。', sampleCheck: 'sales-rules' }]);
    register('sale_item/relation:product', [{ question: '销售明细与商品如何关联，如何避免重复累计？', type: '说明', expected: '按product_id关联product.id，多对一；关联不能增加销售明细行数。', basis: '商品销售数量统计口径 r1及已审核关联。', rule: '人工核对完整关联键及多对一约束，不使用商品编码替代内部id。', sampleCheck: 'relation-rules' }]);
    register('sale_item/metric:sales-quantity', sales.map((value, index) => ({ ...value, sampleCheck: index ? 'net-quantity' : 'sales-quantity' })));
    DataSourceDemo.find(source).knowledge = 0;
    DataSourceDemo.find(source).config.purpose = '维护商品基础档案与销售明细，按已审核口径统计商品原销售数量。';
    prototypeSeeded = true;
  }
  function simulateChange(doc, kind, actor) {
    if (!doc.simulated) return '只能在显式流程演示中模拟来源变化。';
    const definitions = demoDefinitions;
    doc.bindings.forEach(binding => { const entry = definitions.get(binding.table + '/' + binding.entry); entry.revision++;
      if (kind === '删除对象') { entry.retired = true; bans.set(key(entry), entry.object + '已删除'); }
      else if (kind === '业务存疑') { entry.validity = '待复核'; }
      else { entry.content.meaning += '\n演示修订：业务说明已补充。'; entry.validity = '适用'; }
    }); log(doc, actor, '演示来源变化', kind);
  }
  window.KnowledgeWorkspace = { documents, cases, tasks, active, releases, suggested, seedPrototype, sampleData, latest, live, needsUpdate, textFor, stamp, key, clone, has, sourceIssue,
    createDocument, saveDocument, confirmDocument, updatePreview, applyUpdate, importImpact, caseIssue, saveCase, suggestCases, reviewCase, stopCase, caseStale, relatedCases,
    createTask, changeScope, expandDocuments, candidate, prepareIssue, candidateIssue, taskFeedback, releaseStatus, build, finishBuild, run, finishRun, adjudicate, cancel, publish, finishPublish, reconcile,
    restrictions, activeState, pause, withdrawalScope, withdraw, recoveryIssue, recoveryFeedback, createRecoveryTask, restartRecovery, verifyRecovery, recover, rollbackIssue, rollback, seedReview, simulateChange };
})();
