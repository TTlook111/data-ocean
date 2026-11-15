'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture() {
  const storage = new Map(), c = vm.createContext({ window: { addEventListener() {} }, structuredClone,
    sessionStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) } });
  for (const file of ['people-data.js','role-data.js','source-approval-data.js','datasource-data.js','governance-impact-data.js','governance-data.js','knowledge-update-data.js','knowledge-workspace-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname,'../../后台管理',file),'utf8'),c); Object.assign(c,c.window);
  }
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../../shared/query-knowledge-bridge.js'),'utf8'),c); Object.assign(c,c.window);
  const w=c.KnowledgeWorkspace, actor=c.PeopleDemo.users[0]; w.seedPrototype(actor);
  const plan=w.createPlan('products',actor).plan; w.checkPlan(plan); w.submitPlan(plan,actor);
  const doc=w.documents.get(plan.rows[1].result.document);
  const publish=()=>{
    assert.equal(w.confirmDocument(doc,actor,true),undefined);
    w.suggestCases(doc,actor).tests.forEach(row=>assert.equal(w.reviewCase(row,actor),undefined));
    const task=w.createTask('products',[doc.id],actor).task;
    for(const action of ['build','finishBuild','run','finishRun']) assert.equal(w[action](task,actor),undefined);
    w.candidate(task).runs.at(-1).results.filter(row=>row.result==='待裁定').forEach(row=>assert.equal(w.adjudicate(task,row.test,'通过','核对固定候选与明确边界',actor),undefined));
    assert.equal(w.publish(task,actor),undefined); assert.equal(w.finishPublish(task,actor),undefined); return task;
  };
  return {c,w,actor,doc,publish,bridge:c.QueryKnowledgeBridge};
}
test('未发布草稿不进入问数，正式内容携带同版治理与冻结验证数据',()=>{
  const f=fixture(); f.bridge.capture(); assert.match(f.bridge.read('products').error,/尚无/);
  const task=f.publish(); f.bridge.capture(); const context=f.bridge.read('products');
  assert.equal(context.documents.length,1); assert.equal(context.documents[0].id,f.doc.id);
  assert.equal(context.documents[0].revision,1); assert.equal(context.sample.rows.reduce((n,row)=>n+row.quantity,0),12);
  assert.ok(context.documents[0].entries.every(row=>row.review==='已确认'));
  assert.equal(JSON.stringify(context).includes('EX-NOTE-PRODUCT'),true);
  assert.equal(context.documents[0].entries.flatMap(row=>row.references).some(ref=>'content' in ref||'files' in ref),false);
  f.w.saveDocument(f.doc,f.doc.draft.note+'\n尚未发布的修改',f.actor,f.doc.draft.revision);
  f.bridge.capture(); assert.equal(f.bridge.read('products').documents[0].revision,1);
  assert.equal(f.bridge.read('products').documents[0].note.includes('尚未发布的修改'),false);
  assert.equal(task.stage,'已发布');
});
test('暂停或撤下后不沿用历史允许快照，也不保留冻结样例作为可用结果',()=>{
  const f=fixture(); f.publish(); const row=f.w.active.get(f.doc.id);
  assert.equal(f.w.pause(row,'核对依赖',f.actor),undefined); f.bridge.capture();
  assert.match(f.bridge.read('products').error,/尚无/); assert.equal(f.bridge.read('products').sample,undefined);
  row.pauses=[]; assert.equal(f.w.withdraw(row,f.actor,'停止供给'),undefined); f.bridge.capture();
  assert.match(f.bridge.read('products').error,/尚无/);
});
