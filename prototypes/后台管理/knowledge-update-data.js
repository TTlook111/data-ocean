/* 页面内知识更新与发布门槛：冻结审核和来源修订，不预置题单或通过结果，真实服务尚未接入。 */
(() => {
  'use strict';
  const records = new Map(), documents = new Map(), copy = value => structuredClone(value);
  const has = (actor, ability) => SourceApprovalDemo.hasAbility(actor, ability);
  const resolve = (id, source) => [...GovernanceImpactDemo.records, ...documents.values()].find(item => item.id === id && item.source === source);
  const impactFor = record => resolve(record.impactId, record.source);
  const changeFor = impact => DataSourceDemo.changes.find(change => impact.kind === '结构变化' && change.source === impact.source && change.id === impact.originId);
  // 已审核题单示例按技术依赖关联，不能以手填“通过”替代候选运行。
  const testCases = [];
  const scopeFor = impact => GovernanceDemo.publicationScope(impact.source, impact.bindings, { allowBlocked: impact.kind !== '治理草稿', allowRetired: impact.kind !== '治理草稿' });
  const entriesFor = impact => scopeFor(impact).entries;
  // 普通采集快照递增不代表定义变化，不能因此要求重做草稿和验证。
  const signature = impact => {
    const entries = entriesFor(impact), keys = new Set(entries.map(({ table, entry }) => table.id + '/' + entry.id));
    // 新来源变化也使候选失效；处理日志和普通采集编号变化不改变知识依据。
    const sources = GovernanceImpactDemo.records.filter(item => item.source === impact.source && item.bindings.some(binding => keys.has(binding.table + '/' + binding.entry))).map(item => [item.id, item.originId, item.noteRevision, item.bindings.map(binding => [binding.table, binding.entry])]);
    return JSON.stringify([entries.map(({ table, entry }) => [table.id, table.identity, table.generation, entry.id, entry.revision, entry.review, entry.validity]), sources]);
  };
  function casesFor(impact) {
    const paths = new Set(entriesFor(impact).map(({ table, entry }) => GovernanceDemo.objectName(table) + (entry.field ? '.' + entry.field : '')));
    return testCases.filter(test => test.origin !== '预置示例' && test.source === impact.source && paths.has(test.dependency) && test.review === '已确认');
  }
  const caseSignature = impact => JSON.stringify(casesFor(impact).map(test => [test.id, test.revision, test.question, test.expected]));
  const current = record => record.candidates.find(candidate => candidate.id === record.currentId);
  function readiness(impact) {
    if (!impact || !impact.bindings.length) return '请先完成本次范围的业务审核。';
    return scopeFor(impact).issues[0] || '';
  }
  // 治理只交付冻结草稿；知识文档页承接后续确认、验证和发布，权限分别校验。
  function generateDraft(table, actor) {
    if (!has(actor, 'knowledge.view') || !has(actor, 'knowledge.generate')) return { error: '当前账号没有生成知识草稿的权限。' };
    if (!table || !DataSourceDemo.objects(table.source).includes(table)) return { error: '来源对象已变化，请重新选择。' };
    const scope = GovernanceDemo.draftEligibility(GovernanceDemo.get(table));
    if (!scope.eligible.length) return { error: scope.excluded[0]?.reason || '暂无依赖完整的已确认内容。' };
    const document = { id: DataSourceDemo.nextId('DOC-'), source: table.source, kind: '治理草稿', title: table.label + '业务知识', targets: scope.entries.map(({ table, entry }) => GovernanceDemo.objectName(table) + ' / ' + entry.name).join('、'), state: '知识处理中', bindings: copy(scope.bindings), events: [], revision: 1, at: DataSourceDemo.now(), excluded: scope.excluded.map(row => row.entry.name) };
    documents.set(document.id, document);
    return start(document, actor);
  }
  function event(record, actor, action, detail = '') {
    const value = { actor: actor.name, action, reason: detail, at: DataSourceDemo.now() };
    record.events.push(value);
    const impact = impactFor(record); impact.events.push(copy(value)); impact.revision++; impact.updated = value.at;
  }
  function draft(record, impact) {
    // 知识只接收已审核定义及其定位和依据，未采用建议、候选提案及编辑历史留在治理侧回看。
    record.entries = entriesFor(impact).map(({ table, entry }) => ({
      source: table.source, table: table.id, objectId: table.identity, generation: table.generation, object: GovernanceDemo.objectName(table),
      id: entry.id, kind: entry.kind, name: entry.name, field: entry.field, type: entry.type, revision: entry.revision,
      content: copy(entry.content), valueDetails: copy(entry.valueDetails || null), dependencies: copy(entry.dependencies || []), dependencyRevisions: copy(entry.dependencyRevisions || []), review: entry.review, validity: entry.validity,
      definitionSource: copy(entry.definitionSource || null), confirmedStructure: copy(entry.confirmedStructure || null),
      retired: Boolean(entry.retired), blocked: Boolean(entry.blocked), basis: entry.basis, reviewer: entry.reviewer, reviewedAt: entry.reviewedAt, references: copy(GovernanceDemo.citations(entry))
    }));
    record.signature = signature(impact); record.confirmed = false; record.currentId = '';
    record.mode = record.entries.every(entry => entry.retired) ? '停用依赖知识' : '更新知识';
    // 正文保留语义标签与精确定位；后续问数按同发布修订补取字段定义，不能仅靠业务名称猜测。
    record.note = record.entries.map(entry => {
      const path = entry.object + (entry.field ? '.' + entry.field : '');
      const sections = GovernanceDemo.contentSections(entry.kind).filter(({ key }) => entry.content[key]).map(({ key, label }) => label + '：' + entry.content[key]);
      if (entry.valueDetails?.unit) sections.push('单位：' + entry.valueDetails.unit);
      if (entry.valueDetails?.mappings?.length) sections.push('编码与含义：\n' + entry.valueDetails.mappings.map(row => row.code + ' — ' + row.meaning).join('\n'));
      return path + ' · ' + entry.name + ' · 治理修订 ' + entry.revision + '\n' + sections.join('\n');
    }).join('\n\n');
    record.state = '草稿'; record.revision++;
  }
  function start(impact, actor) {
    if (!has(actor, 'knowledge.view') || !has(actor, 'knowledge.generate')) return { error: '当前账号没有生成知识草稿的权限。' };
    if (resolve(impact.id, impact.source) !== impact || impact.state !== '知识处理中') return { error: '该内容尚未进入知识文档处理阶段。' };
    const error = readiness(impact); if (error) return { error };
    let record = records.get(impact.id);
    if (record && (record.state === '已完成' || (record.state === '已发布待解除禁止' && record.signature === signature(impact)))) return { error: '本次知识已发布，请回看发布结果。' };
    if (!record) {
      const change = changeFor(impact), source = DataSourceDemo.find(impact.source);
      // 仅沿用明确的技术依赖，不从业务名称推测受影响知识。
      const paths = new Set(entriesFor(impact).map(({table,entry})=>GovernanceDemo.objectName(table)+(entry.field?'.'+entry.field:'')));
      const dependencies = source.forbiddenKnowledge.filter(item => paths.has(item.dependency) || (change && item.dependency === DataSourceDemo.changeTarget(change)));
      record = { id: DataSourceDemo.nextId('GX-'), impactId: impact.id, source: impact.source, title: impact.title, revision: 0,
        note: '', dependencies: [...dependencies], candidates: [], releases: [], events: [], at: DataSourceDemo.now() };
      records.set(impact.id, record);
    }
    // 发布后修复依据又变更时保留原发布，重新走当前修订，不能沿用旧验证解除禁止。
    if(record.release){record.releases.push(copy(record.release));delete record.release;}
    draft(record, impact); event(record, actor, '生成知识更新草稿', '冻结当前已审核治理修订'); return { record };
  }
  function complete(record, actor, result) {
    const impact = impactFor(record);
    record.state = '已完成'; impact.state = '已完成'; impact.knowledge = record.mode === '停用依赖知识' ? '依赖知识已停用' : '已更新'; impact.completion = result;
    event(record, actor, '变更影响处理完成', result);
  }
  const entryKey = binding => binding.table + '/' + (binding.entry || binding.id);
  function coveredImpacts(record) {
    const keys = new Set(record.entries.map(entryKey));
    return GovernanceImpactDemo.records.filter(item => item.source === record.source && item.state === '知识处理中' && item.bindings.length && item.bindings.every(binding => keys.has(entryKey(binding))) && !readiness(item));
  }
  // 一次发布覆盖同一修订的多条来源变化时共同回写，不能互相等待对方先结案。
  function finishCovered(record, actor, result) {
    const primary = impactFor(record);
    coveredImpacts(record).filter(item => item !== primary).forEach(item => {
      item.state = '已完成'; item.knowledge = '已更新'; item.completion = result + '；沿用发布 ' + record.release.id;
      item.events.push({ action: '由同范围知识发布完成', actor: actor.name, at: DataSourceDemo.now(), reason: record.release.id });
      item.revision++;
      const other = records.get(item.id);
      if (other) { other.state = '已完成'; other.coveredBy = record.release.id; }
    });
    complete(record, actor, result);
  }
  // 页面禁用与提交使用相同门槛；发布不要求来源变化预先结案。
  function actionIssue(record, action) {
    const impact = record && impactFor(record);
    if (!impact) return '找不到当前知识文档。';
    const ready = readiness(impact); if (ready) return ready;
    if (record.signature !== signature(impact)) return '治理修订已变化，请重新生成草稿、构建并验证候选。';
    if (action !== 'allow' && ['已发布待解除禁止', '已完成'].includes(record.state)) return '本次知识已发布，不能重复修改或发布。';
    const candidate = current(record);
    if (['test', 'publish'].includes(action) && candidate && candidate.caseSignature !== caseSignature(impact)) return '相关已审核题单已变化，请重新构建并验证候选。';
    if (action === 'build' && !record.confirmed) return '请先确认当前知识草稿。';
    if (action === 'test') {
      if (!candidate || candidate.revision !== record.revision) return '请先构建当前修订的候选。';
      if (!candidate.tests.length) return '没有相关已审核题，请先在标准题单补题并审核后重新构建。';
    }
    if (action === 'publish' && (!candidate || !record.confirmed || candidate.revision !== record.revision || candidate.attempts.at(-1)?.result !== '通过')) return '当前候选尚未验证通过，不能发布。';
    if (['publish', 'allow'].includes(action)) {
      const source = DataSourceDemo.find(record.source);
      if (source.connectionState !== '已启用' || source.health !== '正常') return '来源当前不可用，请恢复并核对后再发布。';
    }
    if (action === 'allow' && (record.state !== '已发布待解除禁止' || !candidate?.published || candidate.attempts.at(-1)?.result !== '通过')) return '请先完成当前知识更新的验证与发布。';
    return '';
  }
  function act(record, action, actor, data = {}, revision = record?.revision) {
    const ability = { save: 'knowledge.edit', confirm: 'knowledge.confirm', build: 'releases.build', test: 'releases.test', publish: 'releases.publish', allow: 'governance.allow' }[action];
    if (!record || records.get(record.impactId) !== record || !ability || !has(actor, ability) || !has(actor, 'knowledge.view') || (['build','test','publish'].includes(action) && !has(actor,'releases.view'))) return '当前账号没有执行此操作的权限。';
    if (record.revision !== revision) return '知识更新草稿已变化，请重新核对。';
    const issue = actionIssue(record, action); if (issue) return issue;
    const impact = impactFor(record), ready = readiness(impact); if (ready) return ready;
    if (record.signature !== signature(impact)) return '治理修订已变化，请重新生成草稿、构建并验证候选。';
    const candidate = current(record);
    if (['test','publish'].includes(action) && data.candidateId && data.candidateId !== candidate?.id) return '当前候选已变化，请重新核对候选及验证结果。';
    if (['test','publish'].includes(action) && candidate && candidate.caseSignature !== caseSignature(impact)) return '相关已审核题单已变化，请重新构建并验证候选。';
    if (action !== 'allow' && ['已发布待解除禁止','已完成'].includes(record.state)) return '本次知识已发布，不能重复修改或发布。';
    if (action === 'save') {
      if (!data.note?.trim()) return '请填写知识正文。';
      if (record.note === data.note.trim()) return '草稿未变化。';
      record.note = data.note.trim(); record.confirmed = false; record.currentId = ''; record.revision++; record.state = '草稿';
      event(record, actor, '修改知识草稿', record.note);
    } else if (action === 'confirm') {
      if (!record.note) return '请先补齐知识正文。';
      if (record.confirmed) return '当前草稿已确认。';
      record.confirmed = true; record.state = '待构建'; event(record, actor, '确认知识更新草稿', '草稿修订 ' + record.revision);
    } else if (action === 'build') {
      if (!record.confirmed) return '请先确认当前知识更新草稿。';
      const next = { id: DataSourceDemo.nextId('CAND-'), revision: record.revision, signature: record.signature,
        entries: copy(record.entries), note: record.note, tests: copy(casesFor(impact)), caseSignature: caseSignature(impact), mode: record.mode, attempts: [], at: DataSourceDemo.now() };
      record.candidates.push(next); record.currentId = next.id; record.state = '待验证'; event(record, actor, '构建知识候选', next.id);
    } else if (action === 'test') {
      if (!candidate || candidate.revision !== record.revision) return '请先构建当前修订的候选。';
      if (!candidate.tests.length) return '没有相关已审核题，请先在标准题单补题并审核后重新构建。';
      const results = candidate.tests.map(test => ({ id: test.id, question: test.question, expected: test.expected, actual: test.actual,
        result: test.actual === undefined ? '待裁定' : test.actual === test.expected ? '通过' : '未通过' }));
      const result = results.some(test => test.result === '未通过') ? '未通过' : results.some(test => test.result === '待裁定') ? '待裁定' : '通过';
      const evidence = results.map(test => test.id + ' · ' + test.result + '\n预期：' + test.expected + '\n实际：' + (test.actual ?? '待裁定')).join('\n');
      candidate.attempts.push({ id: DataSourceDemo.nextId('RUN-'), result, evidence, results, actor: actor.name, at: DataSourceDemo.now() });
      record.state = result === '通过' ? '待发布' : result === '待裁定' ? '被阻断' : '验证未通过'; event(record, actor, '运行候选验证：' + result, candidate.id + '\n' + evidence);
    } else if (action === 'publish') {
      if (!candidate || !record.confirmed || candidate.revision !== record.revision || candidate.attempts.at(-1)?.result !== '通过') return '当前候选尚未验证通过，不能发布。';
      const source = DataSourceDemo.find(record.source);
      if (source.connectionState !== '已启用' || source.health !== '正常') return '来源当前不可用，请恢复并核对后再发布。';
      candidate.published = { actor: actor.name, at: DataSourceDemo.now() };
      record.release = { id: DataSourceDemo.nextId('REL-'), candidate: candidate.id, ...candidate.published };
      entriesFor(impact).forEach(({ entry }) => { entry.publication = entry.retired ? '已停用' : '已发布'; });
      event(record, actor, '发布知识更新', record.release.id + ' · ' + candidate.id);
      const needsAllow = record.entries.some(entry => entry.blocked && !entry.retired);
      if (record.mode === '停用依赖知识') {
        source.forbiddenKnowledge = source.forbiddenKnowledge.filter(item => !record.dependencies.includes(item)); source.forbidden = source.forbiddenKnowledge.length;
        source.knowledge = Math.max(0, source.knowledge - record.dependencies.length);
        finishCovered(record, actor, '依赖知识已停用并发布；删除' + (changeFor(impact)?.kind === '删除对象' ? '对象' : '字段') + '保持禁止使用');
      } else if (needsAllow) {
        record.state = '已发布待解除禁止'; impact.knowledge = '已发布，待显式解除禁止';
      } else {
        if (!record.dependencies.length) source.knowledge++;
        finishCovered(record, actor, '知识已更新、验证并发布');
      }
    } else if (action === 'allow') {
      if (record.state !== '已发布待解除禁止' || !candidate?.published || candidate.attempts.at(-1)?.result !== '通过') return '请先完成当前知识更新的验证与发布。';
      const source = DataSourceDemo.find(record.source);
      if (source.connectionState !== '已启用' || source.health !== '正常') return '来源当前不可用，不能解除禁止。';
      if (record.entries.every(entry => entry.retired)) return '删除字段不能解除禁止。';
      // 本次定义及新来源变化已由 readiness 和 signature 核对；其他条目的未完成事项不扩大本次解除范围。
      const released=entriesFor(impact).filter(({entry})=>!entry.retired);
      released.forEach(({ entry }) => { entry.blocked = false; });
      const paths=new Set(released.map(({table,entry})=>GovernanceDemo.objectName(table)+(entry.field?'.'+entry.field:'')));
      // 各次变化展示同一对象的当前使用控制；解除后同步，历史核实与发布记录仍保留。
      DataSourceDemo.changes.filter(change=>change.source===impact.source&&paths.has(DataSourceDemo.changeTarget(change))).forEach(change=>{change.control='未禁止';});
      source.forbiddenKnowledge = source.forbiddenKnowledge.filter(item => !record.dependencies.includes(item)); source.forbidden = source.forbiddenKnowledge.length;
      finishCovered(record, actor, '知识已更新、验证并发布；已显式解除禁止');
    }
  }
  const navigationSnapshot=()=>copy({records:[...records],documents:[...documents],testCases});
  function restoreNavigation(snapshot){records.clear();snapshot.records.forEach(([key,value])=>records.set(key,value));documents.clear();snapshot.documents.forEach(([key,value])=>documents.set(key,value));testCases.splice(0,testCases.length,...snapshot.testCases);}
  window.KnowledgeUpdateDemo = { records, testCases,navigationSnapshot,restoreNavigation, casesFor, impactFor, entriesFor, current, readiness, signature, resolve, start, generateDraft, actionIssue, act,
    list: source => [...GovernanceImpactDemo.records, ...documents.values()].filter(item => item.source === source && (item.state === '知识处理中' || records.has(item.id))) };
})();
