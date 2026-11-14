const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture(seed = true) {
  const c = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js', 'knowledge-update-data.js', 'knowledge-workspace-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), c); Object.assign(c, c.window);
  }
  const w = c.KnowledgeWorkspace, actor = c.PeopleDemo.users[0]; if (seed) w.seedReview(actor);
  const doc = w.documents.get('DEMO-DOC-2');
  const create = (ids = [doc.id]) => { const result = w.createTask('retail', ids, actor); assert.equal(result.error, undefined); return result.task; };
  const build = task => { assert.equal(w.build(task, actor), undefined); assert.equal(w.finishBuild(task, actor), undefined); };
  const run = task => { assert.equal(w.run(task, actor), undefined); assert.equal(w.finishRun(task, actor), undefined); };
  const publish = task => { build(task); run(task); assert.equal(w.publish(task, actor), undefined); assert.equal(w.finishPublish(task, actor), undefined); };
  return { c, w, actor, doc, create, build, run, publish };
}
test('ordinary runtime has no pre-approved cases, fabricated results or review documents', () => {
  const { w } = fixture(false); assert.equal(w.documents.size, 0); assert.equal(w.cases.size, 0); assert.equal(w.active.size, 0);
});
test('long draft revisions preserve the active version and previous candidate snapshots', () => {
  const f = fixture(), task = f.create(); f.build(task); const frozen = f.w.candidate(task), active = f.w.active.get(f.doc.id).snapshot.note;
  assert.equal(f.w.saveDocument(f.doc, f.doc.draft.note + '\n' + '人工整理'.repeat(200), f.actor), undefined);
  assert.equal(f.doc.draft.confirmed, false); assert.ok(f.doc.draft.note.length > 300); assert.equal(frozen.documents[0].note, active);
  assert.equal(f.w.active.get(f.doc.id).snapshot.note, active); assert.match(f.w.run(task, f.actor), /未确认|修订/);
});
test('source changes require an explicit merge and do not overwrite manual content', () => {
  const f = fixture(); f.w.saveDocument(f.doc, f.doc.draft.note + '\n人工段落', f.actor);
  const previous = f.doc.draft.note; f.w.simulateChange(f.doc, '定义更新', f.actor);
  const preview = f.w.updatePreview(f.doc); assert.equal(preview.manual, true); assert.equal(f.doc.draft.note, previous);
  assert.equal(f.w.applyUpdate(f.doc, preview.generated + '\n人工段落', f.actor, f.w.stamp(preview.entries)), undefined);
  assert.ok(f.doc.draft.note.includes('人工段落')); assert.equal(f.doc.draft.confirmed, false); assert.equal(f.w.needsUpdate(f.doc), false);
});
test('draft cases cannot run and edited expectations invalidate the frozen approved set', () => {
  const f = fixture(), testCase = f.w.cases.get('DEMO-TC-2'), task = f.create(); f.build(task); f.run(task);
  const result = f.w.saveCase(testCase, { ...testCase, expected: '8' }, f.actor, testCase.revision); assert.equal(result.error, undefined); assert.equal(testCase.review, '草稿');
  assert.match(f.w.publish(task, f.actor), /题单已变化/); assert.equal(f.w.reviewCase(testCase, f.actor), undefined);
  f.build(task); f.run(task); assert.equal(task.stage, '验证未通过'); assert.equal(f.w.candidate(task).runs.at(-1).results[0].actual, '7');
});
test('missing reviewed cases block validation and publication', () => {
  const f = fixture(), task = f.create(['DEMO-DOC-4']); f.build(task);
  assert.match(f.w.run(task, f.actor), /缺少相关已审核题/); assert.match(f.w.publish(task, f.actor), /阶段/);
});
test('a batch commits no target when one required document changes before switching', () => {
  const f = fixture(), other = f.w.documents.get('DEMO-DOC-3'), before = f.w.active.get(f.doc.id).release, task = f.create([f.doc.id, other.id]);
  f.build(task); f.run(task); assert.equal(f.w.publish(task, f.actor), undefined);
  f.w.saveDocument(other, other.draft.note + '\n已编辑', f.actor);
  assert.match(f.w.finishPublish(task, f.actor), /未确认/); assert.equal(f.w.active.get(f.doc.id).release, before); assert.equal(task.stage, '被阻断');
});
test('related documents are included when their necessary revisions are not active', () => {
  const f = fixture(), task = f.create(['DEMO-DOC-17']);
  assert.ok(task.documents.includes('DEMO-DOC-6')); assert.ok(task.documents.includes('DEMO-DOC-16')); assert.ok(!task.documents.includes('DEMO-DOC-3'));
});
test('only undecided results can be adjudicated and evidence is mandatory', () => {
  const f = fixture(), testCase = f.w.cases.get('DEMO-TC-2'); testCase.type = '说明';
  const task = f.create(); f.build(task); f.run(task); assert.equal(task.stage, '待裁定');
  assert.match(f.w.adjudicate(task, testCase.id, '通过', '', f.actor), /依据/);
  assert.equal(f.w.adjudicate(task, testCase.id, '通过', '已核对受控数据及完整规则', f.actor), undefined); assert.equal(task.stage, '待发布');
  assert.match(f.w.adjudicate(task, testCase.id, '通过', '重复', f.actor), /待裁定/);
});
test('publication, recovery and rollback check separate permissions', () => {
  const f = fixture(), publisher = { ...f.actor, roles: ['发布管理员'] }, task = f.create(); f.build(task); f.run(task);
  assert.match(f.w.publish(task, { ...f.actor, roles: ['数据治理员'] }), /权限/); f.publish(f.create());
  const row = f.w.active.get(f.doc.id); assert.equal(f.w.pause(row, '演示风险', f.actor), undefined);
  assert.equal(f.w.verifyRecovery(row, publisher), undefined); assert.match(f.w.recover(row, publisher, '已消除'), /权限/);
});
test('manual pause preserves version and requires verified explicit recovery', () => {
  const f = fixture(), row = f.w.active.get(f.doc.id), release = row.release;
  f.w.pause(row, '业务核对', f.actor); assert.equal(f.w.activeState(row), '暂停'); assert.equal(row.release, release);
  assert.match(f.w.recover(row, f.actor, '核对完成'), /验证/); assert.equal(f.w.verifyRecovery(row, f.actor), undefined);
  assert.equal(f.w.activeState(row), '暂停'); assert.equal(f.w.recover(row, f.actor, '原判断有误，依据已核实'), undefined); assert.equal(f.w.activeState(row), '正常');
});
test('deletion blocks restoration and rollback without clearing its ban', () => {
  const f = fixture(); f.publish(f.create()); const row = f.w.active.get(f.doc.id); f.w.simulateChange(f.doc, '删除对象', f.actor);
  assert.equal(f.w.activeState(row), '暂停'); assert.match(f.w.verifyRecovery(row, f.actor), /删除/); assert.match(f.w.rollbackIssue(f.w.releases.get(row.release)), /禁止|暂停/);
});
test('rollback uses the complete prior version set and detects overlapping later releases', () => {
  const f = fixture(), other = f.w.documents.get('DEMO-DOC-3'), before = f.w.active.get(f.doc.id).release, task = f.create([f.doc.id, other.id]); f.publish(task);
  const release = f.w.releases.get(task.id); assert.equal(f.w.rollbackIssue(release), ''); assert.equal(f.w.rollback(release, f.actor, '整体回退演示'), undefined);
  assert.equal(f.w.active.get(f.doc.id).release, before);
  const next = f.create([f.doc.id, other.id]); f.publish(next); const later = f.create([f.doc.id]); f.publish(later);
  assert.match(f.w.rollbackIssue(f.w.releases.get(next.id)), /后续发布/);
});
test('uncertain switching cannot be repeated and reconciles the actual pointer first', () => {
  const f = fixture(), task = f.create(); task.scenario = '结果待核实'; f.build(task); f.run(task); f.w.publish(task, f.actor); f.w.finishPublish(task, f.actor);
  assert.equal(task.stage, '结果待核实'); assert.match(f.w.publish(task, f.actor), /阶段/); assert.equal(f.w.reconcile(task, f.actor), undefined); assert.equal(task.stage, '待发布');
});
test('results from replaced candidates cannot complete a new build', () => {
  const f = fixture(), task = f.create(); f.build(task); const old = task.currentId; f.w.build(task, f.actor);
  assert.match(f.w.finishBuild(task, f.actor, old), /候选已变化/); assert.equal(task.stage, '构建中');
});
test('review publication cannot complete real governance change records', () => {
  const f = fixture(), impact = f.c.GovernanceImpactDemo.records[0]; impact.state = '知识处理中'; impact.bindings = [{ table: f.doc.draft.entries[0].table, entry: 'overview' }];
  f.publish(f.create()); assert.equal(impact.state, '知识处理中');
});
test('ordinary generation deduplicates a reviewed scope and never invents execution results', () => {
  const f = fixture(false), table = f.c.DataSourceDemo.objects('retail').find(row => row.name === 'product'), record = f.c.GovernanceDemo.get(table), entry = record.items[0];
  assert.equal(f.c.GovernanceDemo.act(record, entry, 'save', f.actor, { meaning: '原型核对的商品档案用途', values: '一行一条商品档案，以id标识', scope: '', updates: '', gap: '' }), undefined);
  assert.equal(f.c.GovernanceDemo.act(record, entry, 'confirm', f.actor), undefined);
  const spec = f.w.suggested('retail').find(item => item.key === table.id + '/base'); assert.ok(spec);
  const first = f.w.createDocument(spec, f.actor), second = f.w.createDocument(spec, f.actor); assert.equal(first.document, second.document); assert.equal(second.reused, true);
  const doc = first.document; f.w.confirmDocument(doc, f.actor);
  const result = f.w.saveCase(null, { source: 'retail', documents: [doc.id], question: '这张表记录什么？', type: '规则', expected: '商品档案', basis: '人工核对的治理修订', rule: '业务含义一致', identity: '原型验证身份', tolerance: 0 }, f.actor);
  assert.equal(f.w.reviewCase(result.test, f.actor), undefined); const task = f.create([doc.id]); f.build(task); f.run(task);
  assert.equal(task.stage, '执行异常'); assert.equal(f.w.candidate(task).runs.at(-1).results[0].actual, undefined);
});
test('restoration uses shared run records and supports evidence-based adjudication without publishing', () => {
  const f = fixture(), row = f.w.active.get(f.doc.id), previous = row.release, testCase = f.w.cases.get('DEMO-TC-2'); testCase.type = '说明';
  f.w.pause(row, '核对解释', f.actor); const task = f.w.createRecoveryTask(row, f.actor).task; assert.ok(task); f.run(task); assert.equal(task.stage, '待裁定');
  f.w.adjudicate(task, testCase.id, '通过', '已核对独立受控数据', f.actor); assert.equal(task.stage, '验证已完成'); assert.equal(row.release, previous); assert.equal(f.w.activeState(row), '暂停');
  assert.equal(f.w.recover(row, f.actor, '风险已经消除'), undefined); assert.equal(f.w.activeState(row), '正常'); assert.equal(row.release, previous);
});
test('withdrawal preserves history and scope changes retain old immutable candidates', () => {
  const f = fixture(), task = f.create(); f.build(task); const old = f.w.candidate(task);
  assert.equal(f.w.changeScope(task, ['DEMO-DOC-3'], f.actor), undefined); assert.equal(task.currentId, ''); assert.ok(task.candidates.includes(old));
  const row = f.w.active.get(f.doc.id), release = row.release; assert.equal(f.w.withdraw(row, f.actor, '停止使用'), undefined); assert.equal(f.w.activeState(row), '已撤下'); assert.equal(row.release, release);
});
test('reapproval after a changed dependency creates a traceable case revision', () => {
  const f = fixture(), testCase = f.w.cases.get('DEMO-TC-2'), previous = testCase.revision;
  f.w.simulateChange(f.doc, '定义更新', f.actor); const preview = f.w.updatePreview(f.doc);
  f.w.applyUpdate(f.doc, preview.generated, f.actor, f.w.stamp(preview.entries)); f.w.confirmDocument(f.doc, f.actor);
  assert.equal(f.w.caseStale(testCase), true); assert.equal(f.w.reviewCase(testCase, f.actor), undefined);
  assert.equal(testCase.revision, previous + 1); assert.equal(testCase.history[0].revision, previous); assert.equal(f.w.caseStale(testCase), false);
});
test('a missing necessary item stops only the affected part of a joint document', () => {
  const f = fixture(), task = f.create(['DEMO-DOC-17']); f.publish(task);
  f.w.simulateChange(f.doc, '删除对象', f.actor); const joint = f.w.active.get('DEMO-DOC-17');
  assert.equal(f.w.activeState(joint), '部分暂停'); assert.equal(f.w.activeState(f.w.active.get('DEMO-DOC-3')), '正常');
});
test('missing cases and stale approved sets expose the actual blocking action', () => {
  const f = fixture(), missing = f.create(['DEMO-DOC-4']); f.build(missing);
  assert.equal(f.w.taskFeedback(missing).state, '被阻断'); assert.equal(f.w.taskFeedback(missing).next, 'cases');
  const task = f.create(); f.build(task); f.run(task);
  const testCase = f.w.cases.get('DEMO-TC-2'); f.w.saveCase(testCase, { ...testCase, expected: '8' }, f.actor); f.w.reviewCase(testCase, f.actor);
  assert.equal(task.stage, '待发布'); assert.equal(f.w.taskFeedback(task).state, '被阻断'); assert.equal(f.w.taskFeedback(task).next, 'build');
});
test('saving and reviewing unchanged cases does not invalidate passed candidates', () => {
  const f = fixture(), testCase = f.w.cases.get('DEMO-TC-2'), revision = testCase.revision, task = f.create(); f.build(task); f.run(task);
  const result = f.w.saveCase(testCase, { ...testCase, tolerance: '0' }, f.actor); assert.equal(result.unchanged, true);
  f.w.reviewCase(testCase, f.actor); assert.equal(testCase.revision, revision); assert.equal(testCase.history.length, 0); assert.equal(f.w.taskFeedback(task).state, '待发布');
});
test('expired recovery evidence is visible and can restart using current reviewed cases', () => {
  const f = fixture(), row = f.w.active.get(f.doc.id); f.w.pause(row, '业务核对', f.actor);
  assert.equal(f.w.recoveryFeedback(row).state, '未验证');
  const old = f.w.createRecoveryTask(row, f.actor).task; f.run(old); const oldResults = JSON.stringify(f.w.candidate(old).runs);
  const testCase = f.w.cases.get('DEMO-TC-2'); f.w.saveCase(testCase, { ...testCase, rule: '明确按盒比较并保留单位' }, f.actor); f.w.reviewCase(testCase, f.actor);
  assert.equal(f.w.recoveryFeedback(row).state, '已失效'); assert.equal(f.w.recoveryFeedback(row).ready, false);
  assert.equal(f.w.taskFeedback(old).expired, true); assert.equal(f.w.taskFeedback(old).next, 'restart'); assert.match(f.w.taskFeedback(old).issue, /重新发起恢复验证/);
  const restarted = f.w.restartRecovery(old, f.actor); assert.equal(restarted.error, undefined); assert.notEqual(restarted.task.id, old.id); f.run(restarted.task);
  assert.equal(restarted.task.stage, '验证已完成'); assert.equal(f.w.recoveryFeedback(row).ready, true); assert.equal(f.w.activeState(row), '暂停'); assert.equal(JSON.stringify(f.w.candidate(old).runs), oldResults);
});
test('historical release outcome stays separate from later active versions and rollback', () => {
  const f = fixture(), task = f.create(); f.w.saveDocument(f.doc, f.doc.draft.note + '\n表达修订', f.actor); f.w.confirmDocument(f.doc, f.actor); f.publish(task);
  assert.equal(f.w.releaseStatus(task, f.doc.id).revision, 2); assert.equal(f.w.releaseStatus(task, f.doc.id).state, '当前生效');
  f.w.rollback(f.w.releases.get(task.id), f.actor, '回退核对'); const status = f.w.releaseStatus(task, f.doc.id);
  assert.equal(status.revision, 2); assert.equal(status.current, 1); assert.equal(status.state, '已回退'); assert.equal(task.stage, '已发布');
});
test('canceled tasks do not keep suggesting missing-case fixes', () => {
  const f = fixture(), task = f.create(['DEMO-DOC-4']); f.build(task); f.w.cancel(task, f.actor, '本批暂不发布');
  const state = f.w.taskFeedback(task); assert.equal(state.state, '已取消'); assert.equal(state.issue, ''); assert.equal(state.next, '');
});
test('recovery with changed governance guides users to update the document first', () => {
  const f = fixture(), row = f.w.active.get(f.doc.id); f.w.pause(row, '核对口径', f.actor);
  const task = f.w.createRecoveryTask(row, f.actor).task; f.run(task); f.w.simulateChange(f.doc, '定义更新', f.actor);
  const feedback = f.w.taskFeedback(task); assert.equal(feedback.next, 'document'); assert.equal(feedback.document, f.doc.id); assert.equal(feedback.expired, true);
  assert.match(f.w.createRecoveryTask(row, f.actor).error, /治理依据/);
});
test('cancellation during construction rejects late completion without changing active content', () => {
  const f = fixture(), task = f.create(), previous = f.w.active.get(f.doc.id).release;
  f.w.build(task, f.actor); assert.equal(task.stage, '构建中'); assert.equal(f.w.cancel(task, f.actor, '中止未生效构建'), undefined);
  assert.match(f.w.finishBuild(task, f.actor), /已变化/); assert.equal(f.w.taskFeedback(task).state, '已取消'); assert.equal(f.w.active.get(f.doc.id).release, previous);
});
test('the integrated source exposes the same reviewed inputs and citations used by document generation', () => {
  const f = fixture(false); f.w.seedPrototype(f.actor); f.w.seedPrototype(f.actor);
  assert.equal(f.w.documents.size, 0); assert.equal(f.w.cases.size, 0); assert.equal(f.w.active.size, 0);
  const specs = f.w.suggested('products'); assert.equal(specs.length, 4);
  const product = specs.find(spec => spec.key === 'products:product/base'), first = f.w.createDocument(product, f.actor), second = f.w.createDocument(product, f.actor);
  assert.equal(first.error, undefined); assert.equal(second.document, first.document); assert.equal(second.reused, true);
  assert.equal(first.document.simulated, undefined); assert.match(first.document.draft.note, /前导零/); assert.match(first.document.draft.note, /1＝在售/);
  assert.equal(first.document.draft.entries.length, 4);
  for (const entry of first.document.draft.entries) {
    const record = f.c.GovernanceDemo.records.get(entry.table), current = record.items.find(item => item.id === entry.id), ref = entry.references[0];
    assert.equal(current.review, '已确认'); assert.equal(current.revision, entry.revision);
    assert.equal(f.c.DataSourceDemo.notes.find(note => note.id === ref.id).content, ref.content); assert.ok(ref.content.includes(ref.excerpt));
    assert.ok(f.c.DataSourceDemo.noteUses.some(use => use.noteId === ref.id && use.table === entry.table && use.entryId === entry.id));
  }
});
test('the joint example closes dependencies and compares independent controlled sales data', () => {
  const f = fixture(false); f.w.seedPrototype(f.actor); const spec = f.w.suggested('products').find(item => item.kind === '指标说明'), result = f.w.createDocument(spec, f.actor), doc = result.document;
  assert.equal(result.error, undefined); assert.ok(doc.draft.entries.some(entry => entry.field === 'quantity')); assert.ok(doc.draft.entries.some(entry => entry.kind === 'relations')); assert.ok(doc.draft.entries.some(entry => entry.object === 'product'));
  f.w.confirmDocument(doc, f.actor); const task = f.w.createTask('products', [doc.id], f.actor).task; f.build(task); assert.match(f.w.run(task, f.actor), /缺少/);
  const tests = f.w.suggestCases(doc, f.actor).tests; assert.equal(tests.length, 2); assert.ok(tests.every(test => test.review === '草稿')); assert.match(f.w.run(task, f.actor), /题单|缺少/);
  tests.forEach(test => assert.equal(f.w.reviewCase(test, f.actor), undefined)); f.build(task); f.run(task); assert.equal(task.stage, '待发布');
  const numeric = f.w.relatedCases([doc.id]).find(test => test.type === '数值'); assert.equal(numeric.expected, '12'); assert.equal(numeric.demoActual, undefined);
  f.w.saveCase(numeric, { ...numeric, expected: '13' }, f.actor); f.w.reviewCase(numeric, f.actor); f.build(task); f.run(task);
  assert.equal(task.stage, '验证未通过'); assert.equal(f.w.candidate(task).runs.at(-1).results.find(row => row.test === numeric.id).actual, '12');
});
test('integrated product review and subsequent governance edits preserve the active version', () => {
  const f = fixture(false); f.w.seedPrototype(f.actor); const doc = f.w.createDocument(f.w.suggested('products').find(spec => spec.key === 'products:product/base'), f.actor).document;
  f.w.confirmDocument(doc, f.actor); f.w.suggestCases(doc, f.actor).tests.forEach(test => f.w.reviewCase(test, f.actor));
  const task = f.w.createTask('products', [doc.id], f.actor).task; f.build(task); f.run(task); assert.equal(task.stage, '待裁定');
  const testCase = f.w.relatedCases([doc.id])[0]; f.w.adjudicate(task, testCase.id, '通过', '逐条对照商品字段及已审核业务说明', f.actor); assert.equal(task.stage, '待发布');
  f.w.publish(task, f.actor); f.w.finishPublish(task, f.actor); const prior = f.w.active.get(doc.id).snapshot.note;
  assert.equal(f.c.DataSourceDemo.find('products').knowledge, 1);
  const record = f.c.GovernanceDemo.records.get('products:product'), entry = record.items.find(item => item.id === 'field:code');
  assert.equal(f.c.GovernanceDemo.act(record, entry, 'save', f.actor, { ...entry.content, usage: '不同系统编码可能重复，保留前导零。' }), undefined);
  assert.equal(f.w.needsUpdate(doc), true); assert.equal(f.w.active.get(doc.id).snapshot.note, prior);
  assert.equal(f.c.GovernanceDemo.act(record, entry, 'confirm', f.actor), undefined);
  const preview = f.w.updatePreview(doc); assert.match(preview.generated, /不同系统/); assert.ok(preview.changed.some(entry => entry.field === 'code'));
});

test('all source-generated documents retain matching case coverage and publish one coherent batch', () => {
  const f = fixture(false); f.w.seedPrototype(f.actor);
  const docs = f.w.suggested('products').map(spec => f.w.createDocument(spec, f.actor).document);
  for (const doc of docs) { f.w.confirmDocument(doc, f.actor); f.w.suggestCases(doc, f.actor).tests.forEach(test => f.w.reviewCase(test, f.actor)); }
  assert.equal(f.w.cases.size, 5); assert.equal(f.w.suggestCases(docs[0], f.actor).tests.length, 1); assert.equal(f.w.cases.size, 5);
  const task = f.w.createTask('products', docs.map(doc => doc.id), f.actor).task; f.build(task); f.run(task); assert.equal(task.stage, '待裁定');
  f.w.candidate(task).runs.at(-1).results.filter(result => result.result === '待裁定').forEach(result => f.w.adjudicate(task, result.test, '通过', '已逐项对照本批候选及原始业务说明', f.actor));
  assert.equal(task.stage, '待发布'); assert.equal(f.w.publish(task, f.actor), undefined); assert.equal(f.w.finishPublish(task, f.actor), undefined);
  assert.equal(f.w.active.size, 4); assert.equal(f.c.DataSourceDemo.find('products').knowledge, 4);
  for (const doc of docs) { assert.equal(f.w.active.get(doc.id).snapshot.basis, doc.draft.basis); assert.equal(f.w.active.get(doc.id).release, task.id); }
});

test('editing a candidate question beyond the fixed sample produces an execution error', () => {
  const f = fixture(false); f.w.seedPrototype(f.actor);
  const doc = f.w.createDocument(f.w.suggested('products').find(spec => spec.kind === '指标说明'), f.actor).document; f.w.confirmDocument(doc, f.actor);
  const tests = f.w.suggestCases(doc, f.actor).tests;
  f.w.saveCase(tests[0], { ...tests[0], question: '查询所有门店的实时库存', expected: '12' }, f.actor); tests.forEach(test => f.w.reviewCase(test, f.actor));
  const task = f.w.createTask('products', [doc.id], f.actor).task; f.build(task); f.run(task);
  assert.equal(task.stage, '执行异常'); assert.match(f.w.candidate(task).runs.at(-1).results[0].error, /超出固定样例/); assert.match(f.w.publish(task, f.actor), /阶段/);
});
