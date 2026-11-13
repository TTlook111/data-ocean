/* 来源结论与治理条目衔接；进度随条目更新，不另办变化确认或结案。 */
(() => {
  'use strict';
  const demo = DataSourceDemo, records = [];
  const has = (user, ability) => SourceApprovalDemo.hasAbility(user, ability);
  const token = item => item.revision;
  const forChange = change => records.find(item => item.kind === '结构变化' && item.originId === change.id);
  const forNote = note => records.filter(item => item.kind === '业务说明' && item.originId === note.id);
  const progress = change => change.fact === '待核实' ? '待核实' : change.fact === '已排除' ? '已完成' : forChange(change)?.state || '待治理';
  const completion = change => change.fact === '已排除' ? change.resolution || '已排除结构变化' : forChange(change)?.completion || '—';
  function blockedUse(change) {
    if (change.control !== '已禁止使用') return '';
    const target = demo.changeTarget(change);
    // 只列当前源内明确依赖该对象的已禁止知识，不从影响描述推断禁止范围。
    const knowledge = demo.find(change.source).forbiddenKnowledge.filter(item => item.dependency === target || (change.kind === '删除对象' && item.dependency.startsWith(target + '.')));
    return target + ' 已禁止使用' + (knowledge.length ? '；已暂停知识：' + knowledge.map(item => item.name).join('、') : '');
  }
  const verificationChoices = change => change.attribute === '目录可见性' ? ['对象已删除', '对象仍存在'] : ['确认变化', '排除结构变化'];
  const verificationQuestion = change => change.attribute === '目录可见性' ? '请确认 ' + change.object + ' 是否仍然存在，以及采集账号是否有读取权限、对象是否在采集范围内。' : '请对照来源对象和采集证据，确认本次' + change.attribute + '差异是否真实发生。';
  function checksFor(change) {
    const subject = change.field || change.object;
    if (change.kind === '新增字段') return '确认 ' + subject + ' 的业务含义、取值与空值含义，以及适用的业务时间。';
    if (change.kind === '类型变化') return '核对 ' + subject + ' 的取值范围、精度和单位，确认现有计算与汇总口径是否适用。';
    if (change.kind === '删除字段') return '核对依赖 ' + subject + ' 的业务定义、关联和指标，停用或替换相关引用；删除字段保持禁止使用。';
    if (change.kind === '删除对象') return subject + ' 已删除。确认原业务改从哪里取数，并停用或替换依赖该对象的定义、关联和指标。';
    if (['表注释', '视图注释'].includes(change.attribute)) return '对照新旧注释，核对当前用途、一行代表的业务粒度和使用限制是否仍然适用。';
    return '对照本次' + change.attribute + '变化，核对 ' + subject + ' 的业务含义、取值说明和使用限制。';
  }
  const event = (item, action, reason, actor) => { item.events.push({ action, reason: reason.trim(), actor: actor.name, at: demo.now() }); item.revision++; item.updated = demo.now(); };
  function add(data) {
    const item = { id: demo.nextId('YXF-'), state: '待治理', completion: '', owner: '', knowledge: '待判断', revision: 1, bindings: [], events: [], updated: demo.now(), ...data };
    records.unshift(item); return item;
  }
  function addChange(change) {
    if (!demo.collectionSucceeded(demo.tasks.find(task => task.id === change.taskId && task.source === change.source))) return;
    const existing = forChange(change); if (existing) return existing;
    const item = add({ source: change.source, kind: '结构变化', originId: change.id, title: demo.changeTarget(change), targets: change.impact, before: change.before, after: change.after, at: change.at });
    // 仅目录可见性改变不改写业务定义；来源访问与已有知识禁止仍单独保留。
    if (change.attribute === '目录可见性' && change.kind !== '删除对象') Object.assign(item, { state: '已完成', knowledge: '无需更新', completion: '业务定义无需调整；目录访问异常另行处理', governanceResult: '仅完成变更影响判断，未表示来源访问已恢复。' });
    window.GovernanceDemo?.prepareImpact(item); return item;
  }
  // 只处理成功采集的差异；系统判得清的直接确认，事实不明才交人工。
  function recognizeChange(change) {
    if (!demo.collectionSucceeded(demo.tasks.find(task => task.id === change.taskId && task.source === change.source))) return;
    // 使用控制不承载核实或治理进度；未知原因保留缺口，不从差异猜测。
    change.control = change.control === '已禁止使用' ? '已禁止使用' : '未禁止';
    change.reason ||= '';
    change.reasonBasis ||= change.reason ? change.evidence : '';
    // 读取异常独立于核实与治理进度，只由后续成功读取解除。
    if (change.kind === '访问丢失') {
      const table = demo.objects(change.source).find(table => demo.changesFor(table).includes(change));
      if (table) table.readState = '访问丢失';
    }
    const reviewed = change.state === '已复核';
    change.needsVerification ??= change.compatible === null;
    change.fact = change.needsVerification ? '待核实' : '已确认';
    change.factSource = reviewed ? '人工核实' : change.fact === '已确认' ? '系统判定' : '来源待核实';
    change.verifications = []; delete change.state;
    if (change.fact === '已确认') {
      const item = addChange(change);
      if (reviewed) item.events.push({ action: '来源核实', reason: change.result, actor: change.reviewer, at: change.reviewed });
    }
  }
  demo.changes.forEach(recognizeChange);
  // 仅比较页面内的结构示例，不替代真实适配器、快照一致性或对象身份服务。
  function analyzeCollection(task) {
    if (!demo.collectionSucceeded(task) || task.analysis) return false;
    const previous = demo.tasks.find(item => item.source === task.source && item.version === task.beforeSnapshot && demo.collectionSucceeded(item));
    if (!previous) { task.analysis = '初始基线已建立'; return true; }
    const before = (previous.resultObjects || []).filter(table => !task.schemas.length || task.schemas.includes(table.schema));
    const name = table => (table.schema ? table.schema + '.' : '') + table.name;
    const detected = [];
    const add = (table, field, attribute, kind, oldValue, newValue, compatible) => detected.push({ id: demo.nextId('CH-'), source: task.source, taskId: task.id, beforeSnapshot: previous.version, afterSnapshot: task.version, object: name(table), field, attribute, kind, before: oldValue, after: newValue, compatible, control: compatible === false ? '已禁止使用' : '未禁止', impact: '对应业务定义待核对', affected: 1, at: task.ended, evidence: compatible === null ? '本次采集成功；仅凭当前可见清单或名称不足以判定对象删除、重建或权限变化。' : '前后均为成功采集；对象标识相同，相关结构属性已完整读取。' });
    task.resultObjects.forEach(table => {
      const old = before.find(row => name(row) === name(table));
      if (!old) { add(table, '', '对象定义', '新增对象', '未发现', table.type, true); return; }
      if (!old.identity || old.identity !== table.identity) { add(table, '', '对象身份', '身份待核实', old.identity || '未知', table.identity || '未知', null); return; }
      if (old.comment !== table.comment) add(table, '', table.type === '视图' ? '视图注释' : '表注释', '修改注释', old.comment, table.comment, true);
      table.fields.forEach(field => {
        const prior = old.fields.find(row => row.name === field.name);
        if (!prior) { add(table, field.name, '字段定义', '新增字段', '不存在', field.type, true); return; }
        for (const [key, label] of [['type', '字段类型'], ['nullable', '允许空值'], ['default', '默认值'], ['comment', '字段注释']]) {
          if (prior[key] !== field[key]) add(table, field.name, label, key === 'type' ? '类型变化' : '字段属性变化', String(prior[key] ?? ''), String(field[key] ?? ''), key === 'type' ? false : true);
        }
      });
      old.fields.filter(field => !table.fields.some(row => row.name === field.name)).forEach(field => add(table, field.name, '字段定义', '删除字段', field.type, '不存在', false));
    });
    before.filter(table => !task.resultObjects.some(row => name(row) === name(table))).forEach(table => add(table, '', '目录可见性', '访问丢失', '目录可见', '本次未发现', null));
    detected.forEach(change => { demo.changes.unshift(change); recognizeChange(change); });
    task.analysis = detected.length ? '发现 ' + detected.length + ' 项结构变化' : '未发现结构变化';
    return true;
  }
  function verify(change, data, actor, revision) {
    if (!has(actor, 'collections.review')) return '当前账号没有核实变化的权限。';
    if (!demo.collectionSucceeded(demo.tasks.find(task => task.id === change.taskId && task.source === change.source))) return '来源采集尚未成功，不能核实结构变化。';
    if (!demo.changes.includes(change) || change.fact !== '待核实' || change.verifications.length !== revision) return '变化已由其他操作处理，请重新核对。';
    if (!verificationChoices(change).includes(data.decision) || !data.reason?.trim()) return '请选择核实结论并填写依据。';
    change.verifications.push({ action: data.decision, reason: data.reason.trim(), actor: actor.name, at: demo.now() });
    change.fact = ['确认变化', '对象已删除'].includes(data.decision) ? '已确认' : '已排除'; change.factSource = '人工核实';
    if (data.decision === '对象仍存在') change.resolution = '对象仍存在，已排除结构变化；读取异常待恢复';
    // 只有明确的删除结论才禁用对象并进入治理，历史采集差异保持原样。
    if (data.decision === '对象已删除') {
      change.detectedKind = change.kind; change.kind = '删除对象'; change.resolution = '来源对象已确认删除'; change.control = '已禁止使用';
      const table = demo.objects(change.source).find(table => demo.changesFor(table).includes(change));
      if (table) table.readState = '已确认移除';
    }
    if (change.fact === '已确认') addChange(change);
  }
  function touch(item, actor) {
    if (!records.includes(item) || !has(actor, 'governance.view') || !['edit', 'suggest', 'supplement'].some(key => has(actor, 'governance.' + key))) return;
    if (['待治理', '治理中', '知识处理中'].includes(item.state)) { item.state = '治理中'; item.completion = ''; item.owner = actor.id; event(item, '整理治理内容', '', actor); }
  }
  // 条目人工审核即更新事项进度，不再另填结果或再次确认。
  function reviewed(item, actor) {
    if (!records.includes(item) || !has(actor, 'governance.view') || !has(actor, 'governance.confirm') || !window.GovernanceDemo?.impactReady(item) || !['待治理', '治理中'].includes(item.state)) return;
    const needsKnowledge = window.GovernanceDemo.impactNeedsKnowledge(item);
    item.knowledge = needsKnowledge ? '待更新' : '无需更新'; item.state = needsKnowledge ? '知识处理中' : '已完成'; item.owner ||= actor.id;
    item.governanceResult = needsKnowledge ? '受影响条目已人工审核，知识处理中。' : '受影响条目已人工审核，业务定义无需更新。';
    item.completion = needsKnowledge ? '' : '业务定义无需调整';
    event(item, '条目审核完成', item.governanceResult, actor);
  }
  const noteSnapshot = note => [note.content || '无文字说明', '附件：' + (note.files.map(file => file.name).join('、') || '无')].join('\n');
  function noteImpact(note, previous, action) {
    const uses = demo.noteUsesFor(note); if (!uses.length) return;
    const item = add({ source: note.source, kind: '业务说明', originId: note.id, title: note.title, beforeRevision: previous.revision, noteRevision: note.revision, action, references: structuredClone(uses), targets: [...new Set(uses.map(use => use.name))].join('、'), before: noteSnapshot(previous), after: action === '撤下' ? '资料已撤下；' + note.reason : noteSnapshot(note), at: demo.now() });
    window.GovernanceDemo?.prepareImpact(item); return item;
  }
  function saveNote(note, data, actor, revision) {
    if (!has(actor, note ? 'business-notes.replace' : 'business-notes.submit')) return { error: '当前账号没有保存资料的权限。' };
    if (note && (!demo.notes.includes(note) || demo.noteToken(note) !== revision || note.status !== '有效')) return { error: '资料已变化，请关闭后重新核对。' };
    if (!data.title?.trim() || (!data.content?.trim() && !data.files.length)) return { error: '请填写标题及业务说明或附件。' };
    const value = { title: data.title.trim(), content: data.content.trim(), files: structuredClone(data.files), updated: demo.now() };
    let impact;
    if (note) {
      const previous = { revision: note.revision, updated: note.updated, title: note.title, content: note.content, files: structuredClone(note.files) };
      const bodyChanged = previous.content !== value.content || data.replacedFile || JSON.stringify(previous.files) !== JSON.stringify(value.files);
      if (previous.title === value.title && !bodyChanged) return { error: '资料内容未变化。' };
      note.history.unshift(previous); Object.assign(note, value, { revision: note.revision + 1 });
      if (bodyChanged) impact = noteImpact(note, previous, '内容更新');
    } else {
      note = { ...value, id: demo.nextId('ZL-'), source: data.source, author: actor.id, revision: 1, status: '有效', history: [] }; demo.notes.unshift(note);
    }
    return { note, impact };
  }
  window.GovernanceImpactDemo = { records, token, forChange, forNote, progress, completion, blockedUse, verificationChoices, verificationQuestion, checksFor, analyzeCollection, verify, touch, reviewed, saveNote,
    onWithdraw: note => noteImpact(note, note, '撤下') };
})();
