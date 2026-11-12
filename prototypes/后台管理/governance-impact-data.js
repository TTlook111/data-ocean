/* 变化到治理的离线交接；只保存页面内记录，不执行真实治理、知识生成或发布。 */
(() => {
  'use strict';
  const demo = DataSourceDemo, records = [];
  const has = (user, ability) => SourceApprovalDemo.hasAbility(user, ability);
  const token = item => item.revision;
  const forChange = change => records.find(item => item.kind === '结构变化' && item.originId === change.id);
  const forNote = note => records.filter(item => item.kind === '业务说明' && item.originId === note.id);
  const progress = change => change.fact === '待核实' ? '待核实' : change.fact === '已排除' ? '已完成' : forChange(change)?.state || '待治理';
  const event = (item, action, reason, actor) => { item.events.push({ action, reason: reason.trim(), actor: actor.name, at: demo.now() }); item.revision++; item.updated = demo.now(); };
  function add(data) {
    const item = { id: demo.nextId('YXF-'), state: '待治理', owner: '', knowledge: '待判断', revision: 1, bindings: [], events: [], updated: demo.now(), ...data };
    records.unshift(item); return item;
  }
  function addChange(change) {
    const existing = forChange(change); if (existing) return existing;
    const item = add({ source: change.source, kind: '结构变化', originId: change.id, title: demo.changeTarget(change), targets: change.impact, before: change.before, after: change.after, at: change.at });
    window.GovernanceDemo?.prepareImpact(item); return item;
  }
  // 可靠差异自动确认；旧的核实结论不冒充治理完成，仍保留后续办理。
  demo.changes.forEach(change => {
    const reviewed = change.state === '已复核';
    change.fact = change.compatible === null ? '待核实' : '已确认';
    change.factSource = reviewed ? '人工核实' : change.fact === '已确认' ? '采集证据' : '来源待核实';
    change.verifications = []; delete change.state;
    if (change.fact === '已确认') {
      const item = addChange(change);
      if (reviewed) item.events.push({ action: '来源核实', reason: change.result, actor: change.reviewer, at: change.reviewed });
    }
  });
  function verify(change, data, actor, revision) {
    if (!has(actor, 'collections.review')) return '当前账号没有核实变化的权限。';
    if (change.fact !== '待核实' || change.verifications.length !== revision) return '变化已由其他操作处理，请重新核对。';
    if (!['确认变化', '排除结构变化'].includes(data.decision) || !data.reason?.trim()) return '请选择核实结论并填写依据。';
    change.verifications.push({ action: data.decision, reason: data.reason.trim(), actor: actor.name, at: demo.now() });
    change.fact = data.decision === '确认变化' ? '已确认' : '已排除'; change.factSource = '人工核实';
    // 可见性核实不推断对象已删除，也不解除已有使用限制。
    if (change.fact === '已确认') addChange(change);
  }
  function touch(item, actor) {
    if (!records.includes(item) || !has(actor, 'governance.view') || !['edit', 'suggest', 'supplement'].some(key => has(actor, 'governance.' + key))) return;
    if (['待治理', '处理中', '待知识更新'].includes(item.state)) { item.state = '处理中'; item.owner = actor.id; event(item, '整理治理内容', '', actor); }
  }
  // 条目人工审核即更新事项进度，不再另填结果或再次确认。
  function reviewed(item, actor) {
    if (!records.includes(item) || !has(actor, 'governance.view') || !has(actor, 'governance.confirm') || !window.GovernanceDemo?.impactReady(item) || !['待治理', '处理中'].includes(item.state)) return;
    const needsKnowledge = window.GovernanceDemo.impactNeedsKnowledge(item);
    item.knowledge = needsKnowledge ? '待更新' : '无需更新'; item.state = needsKnowledge ? '待知识更新' : '已完成'; item.owner ||= actor.id;
    item.governanceResult = needsKnowledge ? '受影响条目已人工审核，待知识更新。' : '受影响条目已人工审核，业务定义无需更新。';
    event(item, '条目审核完成', item.governanceResult, actor);
  }
  function dismiss(item, reason, actor, revision) {
    if (!has(actor, 'governance.view') || !has(actor, 'governance.confirm')) return '当前账号没有审核治理内容的权限。';
    if (!records.includes(item) || !['待治理', '处理中'].includes(item.state) || token(item) !== revision) return '变化事项已更新，请重新核对。';
    if (!reason?.trim()) return '请填写无需修改的依据。';
    if (!window.GovernanceDemo) return '治理内容尚未加载，请重新打开。';
    const error = window.GovernanceDemo.dismissError(item); if (error) return error;
    window.GovernanceDemo?.restoreImpact(item, actor, reason.trim());
    item.state = '已完成'; item.knowledge = '无需更新'; item.owner = actor.id; item.governanceResult = reason.trim(); event(item, '无需修改', reason.trim(), actor);
  }
  const noteSnapshot = note => [note.content || '无文字说明', '附件：' + (note.files.map(file => file.name).join('、') || '无')].join('\n');
  function noteImpact(note, previous, action) {
    const uses = demo.noteUsesFor(note); if (!uses.length) return;
    return add({ source: note.source, kind: '业务说明', originId: note.id, title: note.title, beforeRevision: previous.revision, noteRevision: note.revision, action, references: structuredClone(uses), targets: [...new Set(uses.map(use => use.name))].join('、'), before: noteSnapshot(previous), after: action === '撤下' ? '资料已撤下；' + note.reason : noteSnapshot(note), at: demo.now() });
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
  window.GovernanceImpactDemo = { records, token, forChange, forNote, progress, verify, touch, reviewed, dismiss, saveNote,
    onWithdraw: note => noteImpact(note, note, '撤下') };
})();
