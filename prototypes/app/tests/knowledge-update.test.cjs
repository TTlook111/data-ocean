const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture(id = 'CH-015') {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js','people-data.js','role-data.js','source-approval-data.js','datasource-data.js','governance-impact-data.js','governance-data.js','knowledge-update-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname,'../../后台管理',file),'utf8'),context);Object.assign(context,context.window);
  }
  const data=context.DataSourceDemo, impacts=context.GovernanceImpactDemo, gov=context.GovernanceDemo, updates=context.KnowledgeUpdateDemo, actor=context.PeopleDemo.users[0];
  const change=data.changes.find(change=>change.id===id),impact=impacts.forChange(change),record=impact&&gov.get(gov.impactTarget(impact).table),entry=record?.items.find(entry=>entry.id===(change.field?'field:'+change.field:'overview'));
  const review=()=>{if(!entry.retired)assert.equal(gov.act(record,entry,'save',actor,{meaning:entry.content.meaning||'业务含义已核对',values:'允许小数，单位随商品核对',usage:'同单位汇总',basis:'已核对来源制度',gap:''}),undefined);assert.equal(gov.act(record,entry,'confirm',actor),undefined);};
  const draft=()=>{review();const result=updates.start(impact,actor);assert.equal(result.error,undefined);return result.record;};
  const build=record=>{assert.ok(record.note);assert.equal(record.state,'草稿');assert.equal(updates.act(record,'confirm',actor),undefined);assert.equal(updates.act(record,'build',actor),undefined);};
  return {data,impacts,gov,updates,actor,change,impact,record,entry,review,draft,build};
}
test('confirmed object deletion completes knowledge retirement without restoring the removed object',()=>{
  const {data,impacts,gov,updates,actor}=fixture(),change=data.changes.find(item=>item.id==='CH-012');
  assert.equal(impacts.verify(change,{decision:'对象已删除',reason:'来源管理员确认对象已删除'},actor,0),undefined);
  const impact=impacts.forChange(change),table=gov.impactTarget(impact).table,governance=gov.get(table);
  for(const entry of governance.items) assert.equal(gov.act(governance,entry,'confirm',actor),undefined);
  const {record,error}=updates.start(impact,actor);assert.equal(error,undefined);assert.equal(record.mode,'停用依赖知识');
  for(const action of ['confirm','build','test','publish']) assert.equal(updates.act(record,action,actor),undefined,action);
  assert.equal(impacts.progress(change),'已完成');assert.match(impacts.completion(change),/删除对象保持禁止/);
  assert.ok(governance.items.every(entry=>entry.blocked&&entry.retired));assert.ok(data.catalogStates(table).includes('已确认移除'));
  assert.ok(updates.act(record,'allow',actor));
});
test('usage control, completion and unknown reasons remain distinct from processing stages',()=>{
  const {data,impacts,actor,change,updates,impact}=fixture();
  assert.equal(impacts.progress(change),'待治理');assert.equal(impacts.completion(change),'—');assert.equal(change.reason,'');
  assert.ok(updates.start(impact,actor).error);
  assert.equal(impacts.progress(change),'待治理');assert.equal(change.control,'已禁止使用');assert.equal(change.reason,'');
  const visibility=data.changes.find(change=>change.id==='CH-012');
  assert.equal(impacts.verify(visibility,{decision:'对象仍存在',reason:'访问尚未恢复，仅确认权限变化'},actor,0),undefined);
  assert.equal(impacts.progress(visibility),'已完成');assert.equal(visibility.control,'未禁止');assert.match(impacts.completion(visibility),/对象仍存在.*读取异常/);
});
test('static object bans name only knowledge with the same source and exact dependency',()=>{
  const f=fixture();
  f.data.find('finance').forbiddenKnowledge.push({name:'其他源的同名字段知识',dependency:'sale_item.quantity'});
  f.data.find('retail').forbiddenKnowledge.push({name:'其他字段的知识',dependency:'sale_item.product_id'});
  assert.equal(f.impacts.blockedUse(f.change),'sale_item.quantity 已禁止使用；已暂停知识：净销量与商品销售排行');
  assert.equal(f.impacts.blockedUse({...f.change,field:'unmapped_field'}),'sale_item.unmapped_field 已禁止使用');
  assert.equal(f.impacts.blockedUse(f.data.changes.find(change=>change.id==='CH-014')),'');
});
test('failed and untested candidates cannot publish; explicit release and allowance complete the change',()=>{
  const f=fixture(),record=f.draft();f.build(record);
  assert.match(f.updates.act(record,'publish',f.actor),/验证通过/);
  f.updates.current(record).tests[0].actual='3';
  assert.equal(f.updates.act(record,'test',f.actor),undefined);
  assert.equal(record.state,'验证未通过');assert.match(f.updates.act(record,'publish',f.actor),/验证通过/);
  f.updates.current(record).tests[0].actual='3.750';
  assert.equal(f.updates.act(record,'test',f.actor),undefined);
  assert.equal(f.updates.act(record,'publish',f.actor),undefined);
  assert.equal(record.state,'已发布待解除禁止');assert.equal(f.entry.blocked,true);assert.equal(f.impact.state,'知识处理中');
  assert.equal(f.impacts.blockedUse(f.change),'sale_item.quantity 已禁止使用；已暂停知识：净销量与商品销售排行');
  assert.match(f.updates.act(record,'publish',f.actor),/重复/);
  const older={...structuredClone(f.change),id:'OLDER-SAME-FIELD'};f.data.changes.push(older);
  assert.equal(f.updates.act(record,'allow',f.actor),undefined);
  assert.equal(older.control,'未禁止');
  assert.equal(record.state,'已完成');assert.equal(f.change.control,'未禁止');assert.equal(f.entry.blocked,false);assert.match(f.impact.completion,/显式解除/);
  assert.equal(f.impacts.blockedUse(f.change),'');
  assert.equal(f.data.find('retail').forbiddenKnowledge.some(item=>item.dependency==='legacy_price.cost_price'),true);
});
test('editing a draft invalidates its candidate and freezes the previous snapshot',()=>{
  const f=fixture(),record=f.draft();f.build(record);const old=f.updates.current(record),snapshot=JSON.stringify(old.entries);
  assert.equal(f.updates.act(record,'save',f.actor,{note:'补充使用边界'}),undefined);
  assert.equal(record.currentId,'');assert.equal(record.confirmed,false);assert.match(f.updates.act(record,'publish',f.actor),/验证通过/);
  assert.equal(JSON.stringify(old.entries),snapshot);
});
test('new governance revisions and unavailable sources block publication and allowance',()=>{
  const f=fixture(),record=f.draft();f.build(record);
  f.updates.act(record,'test',f.actor);
  f.data.find('retail').health='网络异常';assert.match(f.updates.act(record,'publish',f.actor),/来源当前不可用/);
  f.data.find('retail').health='正常';
  f.gov.act(f.record,f.entry,'save',f.actor,{meaning:'修订后的销售数量',values:'新规则',usage:'新边界',basis:'新制度',gap:''});f.gov.act(f.record,f.entry,'confirm',f.actor);
  assert.match(f.updates.act(record,'publish',f.actor),/治理修订已变化/);
});
test('deleted fields retire dependent knowledge and never regain use',()=>{
  const f=fixture('CH-013'),record=f.draft();f.build(record);
  f.updates.act(record,'test',f.actor);
  assert.equal(f.updates.act(record,'publish',f.actor),undefined);assert.equal(record.state,'已完成');assert.equal(f.entry.blocked,true);assert.equal(f.change.control,'已禁止使用');
  assert.match(f.impact.completion,/停用.*删除字段保持禁止/);assert.ok(f.updates.act(record,'allow',f.actor));
  assert.equal(f.impacts.blockedUse(f.change),'legacy_price.cost_price 已禁止使用');
});
test('unblocked new fields finish on publication without an extra closure',()=>{
  const f=fixture('CH-014'),record=f.draft();f.build(record);f.updates.act(record,'test',f.actor);
  assert.equal(f.updates.act(record,'publish',f.actor),undefined);assert.equal(f.impact.state,'已完成');assert.match(f.impact.completion,/验证并发布/);
});
test('roles and concurrent revisions cannot skip draft, test or allowance permissions',()=>{
  const f=fixture(),record=f.draft(),editor={...f.actor,roles:['数据治理员']},publisher={...f.actor,roles:['发布管理员']};
  assert.match(f.updates.act(record,'publish',editor),/权限/);assert.match(f.updates.act(record,'allow',publisher),/权限/);
  const old=record.revision;f.updates.act(record,'save',f.actor,{note:'当前草稿的表达修订'});f.build(record);assert.match(f.updates.act(record,'save',f.actor,{note:'并行旧表单'},old),/已变化/);
});
test('a replaced candidate cannot receive results from an older open form',()=>{
  const f=fixture(),record=f.draft();f.build(record);const old=f.updates.current(record).id;
  assert.equal(f.updates.act(record,'build',f.actor),undefined);
  assert.match(f.updates.act(record,'test',f.actor,{candidateId:old,result:'通过',evidence:'旧候选结果'}),/候选已变化/);
  assert.equal(f.updates.current(record).attempts.length,0);
});
test('allowance checks current source health and overlapping unfinished changes',()=>{
  const f=fixture(),record=f.draft();f.build(record);f.updates.act(record,'test',f.actor);f.updates.act(record,'publish',f.actor);
  f.data.find('retail').health='网络异常';assert.match(f.updates.act(record,'allow',f.actor),/来源当前不可用/);f.data.find('retail').health='正常';
  const note=f.data.notes[0],other=f.impacts.saveNote(note,{...note,content:'新的数量说明待核对'},f.actor,f.data.noteToken(note)).impact;
  assert.equal(f.gov.associate(other,[{table:f.record.table.id,entry:f.entry.id}],f.actor,f.impacts.token(other)),undefined);
  f.gov.act(f.record,f.entry,'confirm',f.actor);
  assert.match(f.updates.act(record,'allow',f.actor),/其他变更未完成/);assert.equal(f.entry.blocked,true);
});
test('a repair revised after publication preserves the old release and requires a fresh candidate',()=>{
  const f=fixture(),record=f.draft();f.build(record);f.updates.act(record,'test',f.actor);f.updates.act(record,'publish',f.actor);
  const release=record.release.id,old=f.updates.current(record);
  f.gov.act(f.record,f.entry,'save',f.actor,{meaning:'新的数量口径',values:'新修订',usage:'新边界',basis:'更新依据',gap:''});f.gov.act(f.record,f.entry,'confirm',f.actor);
  assert.match(f.updates.act(record,'allow',f.actor),/治理修订已变化/);
  const refreshed=f.updates.start(f.impact,f.actor);assert.equal(refreshed.error,undefined);
  assert.equal(record.releases[0].id,release);assert.equal(record.release,undefined);assert.equal(record.currentId,'');assert.equal(record.confirmed,false);assert.ok(old.published);
});
test('note-driven knowledge updates follow mapped technical dependencies without matching business names',()=>{
  const f=fixture();f.review();const note=f.data.notes[0];
  const impact=f.impacts.saveNote(note,{...note,content:'数量说明更新，需要核对已引用定义'},f.actor,f.data.noteToken(note)).impact;
  assert.equal(f.gov.associate(impact,[{table:f.record.table.id,entry:f.entry.id}],f.actor,f.impacts.token(impact)),undefined);
  f.gov.act(f.record,f.entry,'confirm',f.actor);
  const {record,error}=f.updates.start(impact,f.actor);assert.equal(error,undefined);
  assert.equal(record.dependencies.length,1);assert.equal(record.dependencies[0].dependency,'sale_item.quantity');
});

test('missing and unreviewed related cases cannot be replaced by a manually selected pass',()=>{
  for(const reviewed of [false,true]){
    const f=fixture();
    if(reviewed)f.updates.testCases.splice(0);
    else f.updates.testCases.forEach(test=>{test.review='待确认';});
    const record=f.draft();f.build(record);
    assert.match(f.updates.act(record,'test',f.actor,{result:'通过',evidence:'手动填写不能通过'}),/没有相关已审核题/);
    assert.match(f.updates.act(record,'publish',f.actor),/验证通过/);
  }
});

test('undecided results block publication and retain the actual candidate run evidence',()=>{
  const f=fixture(),record=f.draft();f.build(record);
  delete f.updates.current(record).tests[0].actual;
  assert.equal(f.updates.act(record,'test',f.actor,{result:'通过'}),undefined);
  assert.equal(record.state,'被阻断');
  assert.equal(f.updates.current(record).attempts[0].result,'待裁定');
  assert.match(f.updates.act(record,'publish',f.actor),/验证通过/);
});

test('a revised approved test set invalidates a previously passed candidate',()=>{
  const f=fixture(),record=f.draft();f.build(record);
  assert.equal(f.updates.act(record,'test',f.actor),undefined);
  f.updates.testCases[0].revision++;
  assert.match(f.updates.act(record,'publish',f.actor),/题单已变化/);
  assert.match(f.updates.act(record,'test',f.actor),/题单已变化/);
  assert.equal(f.updates.act(record,'build',f.actor),undefined);
  assert.equal(f.updates.current(record).tests[0].revision,2);
  assert.equal(f.updates.current(record).attempts.length,0);
});

test('confirmed facts cannot authorize publication or allowance while object access is still lost',()=>{
  const f=fixture(),record=f.draft();f.build(record);f.updates.act(record,'test',f.actor);
  f.record.table.readState='访问丢失';
  assert.match(f.updates.act(record,'publish',f.actor),/仍无法读取/);
  f.record.table.readState='已读取';assert.equal(f.updates.act(record,'publish',f.actor),undefined);
  f.record.table.readState='访问丢失';
  assert.match(f.updates.act(record,'allow',f.actor),/仍无法读取/);
  assert.equal(f.entry.blocked,true);
});

test('a new collection snapshot without definition changes does not invalidate the approved candidate',()=>{
  const f=fixture(),record=f.draft();f.build(record);f.updates.act(record,'test',f.actor);
  const old=f.record.table.version,candidate=f.updates.current(record).id;
  const {task}=f.data.startTask(f.data.find('retail'));
  f.data.advanceTask(task);f.data.advanceTask(task);f.data.advanceTask(task);
  assert.notEqual(f.record.table.version,old);
  assert.equal(f.updates.act(record,'publish',f.actor),undefined);
  assert.equal(f.updates.current(record).id,candidate);
});
