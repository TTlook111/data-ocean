/* 变更知识更新的页面内流程；候选冻结治理修订，相关题自动比对固定示例，不调用真实构建或测试服务。 */
(() => {
  'use strict';
  const records = new Map(), copy = value => structuredClone(value);
  const has = (actor, ability) => SourceApprovalDemo.hasAbility(actor, ability);
  const impactFor = record => GovernanceImpactDemo.records.find(item => item.id === record.impactId && item.source === record.source);
  const changeFor = impact => DataSourceDemo.changes.find(change => impact.kind === '结构变化' && change.source === impact.source && change.id === impact.originId);
  // 已审核题单示例按技术依赖关联，不能以手填“通过”替代候选运行。
  const testCases = [
    { id: 'TC-001', source: 'retail', dependency: 'sale_item.quantity', revision: 1, review: '已确认', question: '同单位销售数量 1.250 与 2.500 汇总', expected: '3.750', actual: '3.750' },
    { id: 'TC-002', source: 'retail', dependency: 'sale_order.paid_at', revision: 1, review: '已确认', question: '支付时间为空的订单不计入已支付订单', expected: '排除空支付时间', actual: '排除空支付时间' },
    { id: 'TC-003', source: 'retail', dependency: 'legacy_price.cost_price', revision: 1, review: '已确认', question: '停用知识不再引用已删除的成本价字段', expected: '无旧成本价依赖', actual: '无旧成本价依赖' },
    { id: 'TC-004', source: 'retail', dependency: 'supplier', revision: 1, review: '已确认', question: '停用知识不再引用已确认删除的供应商表', expected: '无已删除供应商表依赖', actual: '无已删除供应商表依赖' }
  ];
  const entriesFor = impact => impact.bindings.map(binding => {
    const table = DataSourceDemo.objects(impact.source).find(table => table.id === binding.table);
    return { table, entry: table && GovernanceDemo.get(table).items.find(entry => entry.id === binding.entry) };
  });
  // 普通采集快照递增不代表定义变化，不能因此要求重做草稿和验证。
  const signature = impact => JSON.stringify(entriesFor(impact).map(({ table, entry }) => [table?.id, table?.identity, table?.generation, entry?.id, entry?.revision, entry?.review, entry?.validity]));
  function casesFor(impact) {
    const paths = new Set(entriesFor(impact).map(({ table, entry }) => GovernanceDemo.objectName(table) + (entry.field ? '.' + entry.field : '')));
    return testCases.filter(test => test.source === impact.source && paths.has(test.dependency) && test.review === '已确认');
  }
  const caseSignature = impact => JSON.stringify(casesFor(impact).map(test => [test.id, test.revision, test.question, test.expected]));
  const current = record => record.candidates.find(candidate => candidate.id === record.currentId);
  function readiness(impact) {
    if (!impact || !GovernanceDemo.impactReady(impact)) return '请先完成全部受影响条目的业务审核。';
    if (entriesFor(impact).some(({ table }) => !table || DataSourceDemo.pendingVerification(table).length)) return '来源仍有变化待核实，请先核实。';
    if (entriesFor(impact).some(({ table }) => table.readState === '访问丢失')) return '来源对象仍无法读取，请恢复读取后再处理知识更新。';
    return '';
  }
  function event(record, actor, action, detail = '') {
    const value = { actor: actor.name, action, reason: detail, at: DataSourceDemo.now() };
    record.events.push(value);
    const impact = impactFor(record); impact.events.push(copy(value)); impact.revision++; impact.updated = value.at;
  }
  function draft(record, impact) {
    record.entries = entriesFor(impact).map(({ table, entry }) => ({ table: table.id, object: GovernanceDemo.objectName(table), ...copy(entry) }));
    record.signature = signature(impact); record.confirmed = false; record.currentId = '';
    record.mode = record.entries.every(entry => entry.retired) ? '停用依赖知识' : '更新知识';
    record.note = record.entries.map(entry => entry.object + ' / ' + entry.name + '\n' + Object.values(entry.content).filter(Boolean).join('\n')).join('\n\n');
    record.state = '草稿'; record.revision++;
  }
  function start(impact, actor) {
    if (!has(actor, 'knowledge.view') || !has(actor, 'knowledge.generate')) return { error: '当前账号没有生成知识草稿的权限。' };
    if (!GovernanceImpactDemo.records.includes(impact) || impact.state !== '知识处理中') return { error: '该变化尚未进入知识处理中阶段。' };
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
  function act(record, action, actor, data = {}, revision = record?.revision) {
    const ability = { save: 'knowledge.edit', confirm: 'knowledge.confirm', build: 'releases.build', test: 'releases.test', publish: 'releases.publish', allow: 'governance.allow' }[action];
    if (!record || records.get(record.impactId) !== record || !ability || !has(actor, ability) || !has(actor, 'knowledge.view') || (['build','test','publish'].includes(action) && !has(actor,'releases.view'))) return '当前账号没有执行此操作的权限。';
    if (record.revision !== revision) return '知识更新草稿已变化，请重新核对。';
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
        complete(record, actor, '依赖知识已停用并发布；删除' + (changeFor(impact)?.kind === '删除对象' ? '对象' : '字段') + '保持禁止使用');
      } else if (needsAllow) {
        record.state = '已发布待解除禁止'; impact.knowledge = '已发布，待显式解除禁止';
      } else {
        if (!record.dependencies.length) source.knowledge++;
        complete(record, actor, '知识已更新、验证并发布');
      }
    } else if (action === 'allow') {
      if (record.state !== '已发布待解除禁止' || !candidate?.published || candidate.attempts.at(-1)?.result !== '通过') return '请先完成当前知识更新的验证与发布。';
      const source = DataSourceDemo.find(record.source);
      if (source.connectionState !== '已启用' || source.health !== '正常') return '来源当前不可用，不能解除禁止。';
      if (record.entries.every(entry => entry.retired)) return '删除字段不能解除禁止。';
      const other = GovernanceImpactDemo.records.some(item => item !== impact && item.state !== '已完成' && item.bindings.some(binding => impact.bindings.some(target => binding.table === target.table && binding.entry === target.entry)));
      if (other) return '同一条目仍有其他变更未完成，不能解除禁止。';
      const released=entriesFor(impact).filter(({entry})=>!entry.retired);
      released.forEach(({ entry }) => { entry.blocked = false; });
      const paths=new Set(released.map(({table,entry})=>GovernanceDemo.objectName(table)+(entry.field?'.'+entry.field:'')));
      // 各次变化展示同一对象的当前使用控制；解除后同步，历史核实与发布记录仍保留。
      DataSourceDemo.changes.filter(change=>change.source===impact.source&&paths.has(DataSourceDemo.changeTarget(change))).forEach(change=>{change.control='未禁止';});
      source.forbiddenKnowledge = source.forbiddenKnowledge.filter(item => !record.dependencies.includes(item)); source.forbidden = source.forbiddenKnowledge.length;
      complete(record, actor, '知识已更新、验证并发布；已显式解除禁止');
    }
  }
  window.KnowledgeUpdateDemo = { records, testCases, casesFor, impactFor, entriesFor, current, readiness, signature, start, act,
    list: source => GovernanceImpactDemo.records.filter(item => item.source === source && (item.state === '知识处理中' || records.has(item.id))) };
})();
