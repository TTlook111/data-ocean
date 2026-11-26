'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
function fixture(seed=false){
  const c=vm.createContext({window:{},structuredClone});
  for(const file of ['people-data.js','role-data.js','source-approval-data.js','datasource-data.js','governance-impact-data.js','governance-data.js','knowledge-update-data.js','knowledge-workspace-data.js','feedback-data.js']){
    vm.runInContext(fs.readFileSync(path.join(__dirname,'../../后台管理',file),'utf8'),c);Object.assign(c,c.window);
  }
  const actor=c.PeopleDemo.users[0],demo=c.FeedbackDemo.create({seed,now:()=>Date.UTC(2026,10,15,4)});
  const receive=(answerId='A1',sourceId='retail',reaction={type:'down',reason:'时间归属需核对'},user=actor)=>demo.intake({type:'用户点踩',sourceId,answerId,userId:user.id,question:'上周牛奶净销量',threadId:'thread1',turnId:answerId,reaction,evidence:{knowledge:[{id:'SAMPLE',revision:1}]}},user);
  const row=()=>receive().item;
  const assign=row=>{assert.equal(demo.assign(row,{owner:actor.id,direction:'技术问题',reason:'核对查询及展示'},actor,row.revision),undefined);assert.equal(demo.investigate(row,{rootCause:'格式处理错误',basis:'核对原问题及原回答'},actor,row.revision),undefined);};
  const add=row=>{const r=demo.addTask(row,{kind:'技术修复',title:'修复格式',owner:actor.id},actor,row.revision);assert.ok(r.task);return r.task;};
  const complete=(changes={})=>({stage:'ready',summary:'修正格式',check:'按固定样例0001复查，文本显示与预期一致；已核对本次生效修复。',reference:'FIX-1',effectiveConfirmed:true,regression:'原问题及相关受限场景测试通过',regressionPassed:true,...changes});
  const handle=(row,task)=>assert.equal(demo.handling(row,task.id,complete({performer:task.kind==='源数据修正'?'源系统负责团队':''}),c.PeopleDemo.users.find(user=>user.id===task.owner),row.revision),undefined);
  const verify=row=>assert.equal(demo.verify(row,{decision:'通过',actual:'0001',basis:'生效版本固定样例核对'},actor,row.revision),undefined);
  const close=row=>assert.equal(demo.close(row,{status:'已解决',conclusion:'已修复并复验，请重新查询。',basis:'复验记录核对'},actor,row.revision),undefined);
  return {c,demo,actor,receive,row,assign,add,handle,complete,verify,close};
}
test('只有本人有源访问可评价；点赞不建事项，空原因拒绝，修改撤销保留追加事件',()=>{
  const f=fixture();assert.equal(f.receive('A1','retail',{type:'up'}).item,undefined);assert.equal(f.demo.items.length,0);
  assert.match(f.receive('A1','retail',{type:'down',reason:' '}).error,/原因/);
  const row=f.row();assert.equal(f.demo.items.length,1);assert.equal(f.receive().duplicate,true);
  f.receive('A1','retail',{type:'up'});f.receive('A1','retail',null);
  assert.equal(f.demo.items.length,1);assert.equal(row.status,'待分派');assert.equal(f.demo.evaluations.length,4);
  assert.match(f.demo.intake({userId:'u04',answerId:'A1',question:'x',sourceId:'retail',type:'用户点踩',reaction:{type:'down',reason:'x'}},f.actor).error,/本人/);
});
test('分派不扩权，角色功能及版本冲突独立检查',()=>{
  const f=fixture(),row=f.row(),stale=row.revision;
  assert.match(f.demo.assign(row,{owner:'u04',direction:'技术',reason:'x'},f.actor,row.revision),/有效的反馈处理功能/);
  f.assign(row);assert.match(f.demo.investigate(row,{basis:'旧页面'},f.actor,stale),/已变化/);
  f.actor.roles=[];assert.equal(f.demo.list(f.actor).length,0);assert.match(f.demo.investigate(row,{basis:'x'},f.actor,row.revision),/权限/);
});
test('处理功能不代替事项归属；获派技术子任务不要求兼任主负责人',()=>{
  const f=fixture(),row=f.row(),worker=f.c.PeopleDemo.users[1];f.assign(row);
  f.c.RoleDemo.records.find(role=>role.name==='管理员').permissions.push('feedback.view','feedback.process');
  assert.match(f.demo.investigate(row,{basis:'未获派'},worker,row.revision),/其他负责人/);
  const task=f.demo.addTask(row,{kind:'技术修复',title:'验证显示',owner:worker.id},f.actor,row.revision).task;
  assert.equal(f.demo.handling(row,task.id,f.complete(),worker,row.revision),undefined);
  assert.match(f.demo.addTask(row,{kind:'业务知识',title:'无治理权',owner:worker.id,binding:'retail:sale_item/field:quantity'},f.actor,row.revision),/对应处理功能/);
});
test('待补充指定原提问人，其他用户不能补充，不改变核实口径',()=>{
  const f=fixture(),row=f.row();f.assign(row);
  assert.equal(f.demo.requestSupplement(row,{userId:f.actor.id,question:'说明预期按哪个业务日归属'},f.actor,row.revision),undefined);
  assert.equal(row.status,'待补充');assert.match(f.demo.supplement(row.id,'其他人',f.c.PeopleDemo.users[3]),/不能/);
  assert.equal(f.demo.supplement(row.id,'按退货发生日',f.actor),undefined);assert.equal(row.status,'处理中');assert.equal(row.notes.length,1);assert.equal(row.rootCause,'格式处理错误');
});
test('所有处理任务完成且生效后复验通过才能已解决；失败退回处理',()=>{
  const f=fixture(),row=f.row();f.assign(row);const a=f.add(row),b=f.add(row);f.handle(row,a);
  assert.match(f.demo.gate(row).error,/尚未提交/);f.handle(row,b);
  assert.match(f.demo.close(row,{status:'已解决',conclusion:'x',basis:'x'},f.actor,row.revision),/尚未通过/);
  assert.equal(f.demo.verify(row,{decision:'不通过',actual:'丢失前导零',basis:'仍为旧版本'},f.actor,row.revision),undefined);assert.equal(row.status,'处理中');
  assert.match(f.demo.gate(row).error,/修订处理结果/);f.handle(row,b);f.verify(row);f.close(row);assert.equal(row.status,'已解决');assert.equal(row.notifications.length,1);
});
test('处理结果或版本改变后旧复验失效；负责人撤权须协调重新分派',()=>{
  const f=fixture(),row=f.row();f.assign(row);const task=f.add(row);f.handle(row,task);f.verify(row);f.handle(row,task);
  assert.match(f.demo.close(row,{status:'已解决',conclusion:'x',basis:'x'},f.actor,row.revision),/尚未通过/);
  f.actor.status='停用';assert.match(f.demo.gate(row).error,/权限已变化/);
});
test('合并不是解决，范围变更使复验失效，纠正合并保留原证据且不允许环',()=>{
  const f=fixture(),a=f.row(),b=f.receive('A2').item;f.assign(a);const task=f.add(a);f.handle(a,task);f.verify(a);
  assert.equal(f.demo.merge(b,a,{reason:'同一根因与范围',targetRevision:a.revision},f.actor,b.revision),undefined);
  assert.equal(b.status,'已合并');assert.equal(f.demo.origins(a).length,2);assert.equal(a.verification,null);
  assert.match(f.demo.merge(a,b,{reason:'错误环',targetRevision:b.revision},f.actor,a.revision),/主事项/);
  assert.equal(f.demo.unmerge(b,'核对后为不同原因',f.actor,b.revision),undefined);assert.equal(b.status,'待分派');assert.equal(f.demo.origins(a).length,1);
});
test('合并保留各事项必要补充，用户分别补齐前主事项不能验收',()=>{
 const f=fixture(),main=f.row(),other=f.receive('SUP-2','retail',{type:'down',reason:'另一个缺项'},f.c.PeopleDemo.users[3]).item;
 f.assign(main);f.assign(other);
 f.demo.requestSupplement(main,{userId:f.actor.id,question:'确认计量单位'},f.actor,main.revision);
 f.demo.requestSupplement(other,{userId:f.c.PeopleDemo.users[3].id,question:'确认下单日还是完成日'},f.actor,other.revision);
 assert.equal(f.demo.merge(other,main,{reason:'同一统计事项',targetRevision:main.revision},f.actor,other.revision),undefined);
 assert.match(f.demo.publicItem(other.id,f.c.PeopleDemo.users[3]).summary,/下单日还是完成日/);
 f.demo.supplement(main.id,'单位是盒',f.actor);
 assert.equal(main.status,'待补充');assert.equal(f.demo.nextAction(main,f.actor).key,'waiting');assert.match(f.demo.gate(main).error,/补齐/);
 f.demo.supplement(other.id,'按完成日',f.c.PeopleDemo.users[3]);assert.equal(main.status,'处理中');
 assert.equal(other.supplement,null);assert.equal(f.demo.publicItem(other.id,f.c.PeopleDemo.users[3]).notes.length,1);
 assert.equal(f.demo.unmerge(other,'分开核查',f.actor,other.revision),undefined);assert.notEqual(other.status,'待补充');
 assert.equal(f.demo.publicItem(other.id,f.c.PeopleDemo.users[3]).notes[0].text,'按完成日');
});
test('浏览器缓存页面恢复时同步新反馈与已读状态，不覆盖较新的本页记录',()=>{
 const f=fixture(),values=new Map(),storage={getItem:key=>values.get(key),setItem:(key,value)=>values.set(key,value)};
 const first=f.c.FeedbackDemo.create({seed:false,storage}),cached=f.c.FeedbackDemo.create({seed:false,storage}),oldArray=cached.items;
 const row=first.intake({type:'用户点踩',sourceId:'retail',answerId:'CACHE',userId:f.actor.id,question:'原问题',reaction:{type:'down',reason:'需核查'}},f.actor).item;
 first.close(row,{status:'其他结案',reason:'未复现',basis:'核对未复现',conclusion:'内部结论'},f.actor,row.revision);first.deliver(row,row.notifications[0].key,f.actor);
 assert.equal(cached.refreshSnapshot(),true);assert.equal(cached.items,oldArray);assert.equal(cached.get(row.id).status,'其他结案');
 cached.markRead(row.id,row.notifications[0].key,f.actor);assert.equal(first.refreshSnapshot(),true);assert.equal(first.get(row.id).notifications[0].read,true);
 assert.equal(first.refreshSnapshot(),false);assert.equal(cached.refreshSnapshot(),false);
});
test('其他结案保留实际原因，重开形成新轮次且保留旧结案与回告',()=>{
  const f=fixture(),row=f.row();f.assign(row);
  assert.equal(f.demo.close(row,{status:'其他结案',reason:'未复现',basis:'现有数据无法重现',conclusion:'本次未复现，未修改定义。'},f.actor,row.revision),undefined);
  assert.equal(row.status,'其他结案');assert.equal(row.closeReason,'未复现');
  assert.equal(f.demo.reopen(row,'新增截图及时间条件',f.actor,row.revision),undefined);assert.equal(row.cycle,2);assert.equal(row.status,'处理中');assert.equal(row.notifications[0].cycle,1);
  assert.match(f.demo.deliver(row,row.notifications[0].key,f.actor),/历史轮次/);
});
test('回告失败与未知独立重试，不能重复修复或重复投递，同人多反馈按轮次去重',()=>{
  const f=fixture(),a=f.row(),b=f.receive('A2').item;f.assign(a);f.demo.merge(b,a,{reason:'同根因',targetRevision:a.revision},f.actor,b.revision);const task=f.add(a);f.handle(a,task);f.verify(a);f.close(a);
  const key=a.notifications[0].key;assert.equal(a.notifications.length,1);
  f.demo.deliver(a,key,f.actor,'fail');assert.equal(a.status,'已解决');assert.equal(a.notifications[0].status,'提示失败');
  f.demo.deliver(a,key,f.actor,'unknown');assert.match(f.demo.deliver(a,key,f.actor),/不能直接/);
  f.demo.reconcile(a,key,f.actor,false);f.demo.deliver(a,key,f.actor);assert.equal(a.notifications[0].status,'已投递');assert.match(f.demo.deliver(a,key,f.actor),/重复/);
});
test('接收人失权后投递及历史回看只提供受限提示，停用账号不发送',()=>{
  const f=fixture(),user=f.c.PeopleDemo.users[3],row=f.receive('A1','retail',{type:'down',reason:'x'},user).item;f.assign(row);const task=f.add(row);f.handle(row,task);f.verify(row);f.close(row);
  f.c.PeopleDemo.departments.find(d=>d.id===user.department).access=[];
  const key=row.notifications[0].key;f.demo.deliver(row,key,f.actor);const pub=f.demo.publicItem(row.id,user);
  assert.equal(pub.origins.length,0);assert.match(pub.receipts[0].text,/权限限制/);assert.equal(pub.title,'反馈处理进度');
  assert.equal('conclusion' in pub.receipts[0],false);assert.equal(JSON.stringify(pub).includes('已修复并复验'),false);
  user.status='停用';assert.equal(f.demo.publicItem(row.id,user),null);
});
test('初始样例覆盖分页与统一状态；组合查询只作用于对应字段',()=>{
  const f=fixture(true);assert.equal(f.demo.items.length,15);assert.ok(f.demo.items.every(row=>f.c.FeedbackDemo.statuses.includes(row.status)));
  const rows=f.demo.list(f.actor,{sourceId:'products',status:'处理中',keyword:'关联',owner:'lin.admin'});assert.equal(rows.length,1);assert.equal(rows[0].id,'WT-102');
  assert.ok(f.demo.list(f.actor,{bucket:'mine'}).every(row=>!f.c.FeedbackDemo.closed(row)));
});
test('问数事项卡读取后台统一状态，补充回写原事项且不能再走旧模拟办理',()=>{
  const f=fixture();f.c.FeedbackDemo=f.demo;f.c.window.FeedbackDemo=f.demo;
  for(const file of ['dialogue.js','cases.js']){vm.runInContext(fs.readFileSync(path.join(__dirname,'..',file),'utf8'),f.c);Object.assign(f.c,f.c.window);}
  const row=f.row(),desk=f.c.QueryCases.create(null,Date.now,{id:f.actor.id});
  const local=desk.register('retail','回答核查',{threadId:'thread1',turnId:'A1',text:'原问题'},'feedback');desk.link(local.caseId,row.id);
  assert.equal(desk.get(local.caseId).statusLabel,'待分派');assert.equal(desk.simulate(local.caseId,'publish').ok,false);
  f.assign(row);assert.equal(desk.get(local.caseId).statusLabel,'处理中');
  assert.equal(desk.supplement(local.caseId,'补充预期时间').ok,true);assert.equal(row.notes.length,1);
  const task=f.add(row);f.handle(row,task);f.verify(row);f.close(row);f.demo.deliver(row,row.notifications[0].key,f.actor);
  assert.equal(desk.get(local.caseId).statusLabel,'已解决');assert.equal(desk.get(local.caseId).receipts.length,1);
  const question='城南店上周牛奶退货率是多少？',first=desk.ask(question,{threadId:'g',turnId:'G1'});
  const gap=f.demo.intake({type:'问数知识缺口',sourceId:'retail',userId:f.actor.id,answerId:'G1',question,gapKey:'rate'},f.actor).item;desk.link(first.caseId,gap.id);
  f.demo.close(gap,{status:'其他结案',reason:'未复现',basis:'保留现有定义',conclusion:'本次未复现'},f.actor,gap.revision);
  const again=desk.ask(question,{threadId:'g',turnId:'G2'});assert.notEqual(again.caseId,first.caseId);assert.equal(desk.get(first.caseId).backendId,gap.id);
});

test('角色授予反馈功能后可获派任务，无需配置源范围，也不增加业务查询访问',()=>{
 const f=fixture(),worker=f.c.PeopleDemo.users[10],row=f.receive('P1','products').item;f.assign(row);
 worker.roles=['反馈处理员'];assert.equal(f.c.SourceApprovalDemo.hasAbility(worker,'query.view'),false);
 assert.equal(f.c.FeedbackDemo.sourceAccess(worker,'products'),false);assert.equal(f.c.FeedbackDemo.can(worker,row,'process'),true);assert.equal(f.c.FeedbackDemo.evidenceAccess(worker,row),false);
 const task=f.demo.addTask(row,{kind:'技术修复',impact:'展示表达',title:'显示修复',owner:worker.id},f.actor,row.revision).task;
 assert.ok(task);assert.equal(f.demo.handling(row,task.id,f.complete(),worker,row.revision),undefined);
 assert.equal(f.c.FeedbackDemo.sourceAccess(worker,'products'),false);
 const retail=f.row();f.assign(retail);assert.ok(f.demo.addTask(retail,{kind:'技术修复',title:'不同源的获派任务',owner:worker.id},f.actor,retail.revision).task);
 f.c.RoleDemo.records.find(role=>role.name==='反馈处理员').permissions=f.c.RoleDemo.records.find(role=>role.name==='反馈处理员').permissions.filter(key=>key!=='feedback.process');
 assert.match(f.demo.handling(row,task.id,{summary:'失权提交'},worker,row.revision),/权限/);assert.equal(f.c.FeedbackDemo.sourceAccess(worker,'retail'),true);
});
test('任务凭依据退出与恢复，范围变更使验收失效；全部退出只能说明结案',()=>{
 const f=fixture(),row=f.row();f.assign(row);const a=f.add(row),b=f.add(row);f.handle(row,a);
 assert.match(f.demo.disposition(row,b.id,{action:'无需变更',reason:'误建',covered:false},f.actor,row.revision),/未覆盖/);
 assert.equal(f.demo.disposition(row,b.id,{action:'无需变更',reason:'已由任务A覆盖',covered:true},f.actor,row.revision),undefined);f.verify(row);
 assert.equal(f.demo.disposition(row,b.id,{action:'恢复',reason:'发现新影响'},f.actor,row.revision),undefined);assert.equal(row.verification,null);assert.match(f.demo.gate(row).error,/尚未提交/);
 for(const task of [a,b])assert.equal(f.demo.disposition(row,task.id,{action:'已取消',reason:'确认无需修复',covered:true},f.actor,row.revision),undefined);
 assert.match(f.demo.gate(row).error,/没有仍需修复/);assert.match(f.demo.close(row,{status:'已解决',conclusion:'x',basis:'x'},f.actor,row.revision),/没有仍需修复/);
 assert.equal(f.demo.close(row,{status:'其他结案',reason:'取消',conclusion:'内部依据',basis:'核查确认'},f.actor,row.revision),undefined);
 assert.equal(row.tasks.length,2);assert.equal(row.tasks[1].dispositionHistory.length,3);
});
test('展示、连接和源数据按实际生效对象验收；查询安全变更保留候选回归门槛',()=>{
 for(const [kind,impact] of [['技术修复','展示表达'],['技术修复','连接恢复'],['源数据修正','']]){
  const f=fixture(),row=f.row();f.assign(row);const task=f.demo.addTask(row,{kind,impact,title:'修正',owner:f.actor.id},f.actor,row.revision).task;
  assert.equal(f.demo.handling(row,task.id,f.complete({performer:kind==='源数据修正'?'源系统负责团队':''}),f.actor,row.revision),undefined);assert.equal(f.demo.gate(row).error,undefined);
 }
 const f=fixture(),row=f.row();f.assign(row);const task=f.add(row);assert.match(f.demo.handling(row,task.id,f.complete({regression:'',regressionPassed:false}),f.actor,row.revision),/相关场景/);
});
test('内部结论不进入回告，输出受限人只收受控模板；失去问数功能只显示受限提示',()=>{
 const f=fixture(),user=f.c.PeopleDemo.users[3],row=f.receive('U1','retail',{type:'down',reason:'x'},user).item;
 assert.equal(f.demo.close(row,{status:'其他结案',reason:'其他',basis:'内部记录',conclusion:'产品成本123秘密'},f.actor,row.revision),undefined);
 f.demo.deliver(row,row.notifications[0].key,f.actor);let pub=f.demo.publicItem(row.id,user);
 assert.equal(JSON.stringify(pub).includes('123秘密'),false);assert.equal(pub.owner,f.actor.name);
 f.c.RoleDemo.records.find(r=>r.name==='普通用户').permissions=[];pub=f.demo.publicItem(row.id,user);assert.match(pub.receipts[0].text,/权限限制/);assert.equal(pub.origins.length,0);
});

test('迟到反馈复用任务创建前的已发布修复，当前对象变化仍阻止旧通过套用',()=>{
 const c=fixture().c,actor=c.PeopleDemo.users[0],w=c.KnowledgeWorkspace;w.seedPrototype(actor);
 const plan=w.createPlan('products',actor).plan;w.checkPlan(plan);w.submitPlan(plan,actor);const doc=w.documents.get(plan.rows[1].result.document);
 const release=()=>{if(!doc.draft.confirmed)assert.equal(w.confirmDocument(doc,actor,true),undefined);w.suggestCases(doc,actor);[...w.cases.values()].filter(row=>row.documents.includes(doc.id)).forEach(row=>assert.equal(w.reviewCase(row,actor),undefined));const task=w.createTask('products',[doc.id],actor).task;for(const action of ['build','finishBuild','run','finishRun'])assert.equal(w[action](task,actor),undefined);w.candidate(task).runs.at(-1).results.filter(row=>row.result==='待裁定').forEach(row=>assert.equal(w.adjudicate(task,row.test,'通过','独立审查：对照固定候选正文与样例',actor),undefined));assert.equal(w.publish(task,actor),undefined);assert.equal(w.finishPublish(task,actor),undefined);return task;};
 const before=release(),oldRevision=doc.draft.revision,table=c.DataSourceDemo.tables.find(row=>row.id==='products:sale_item'),record=c.GovernanceDemo.get(table),entry=record.items.find(row=>row.id==='metric:sales-quantity');
 assert.equal(c.GovernanceDemo.act(record,entry,'save',actor,{...entry.content,usage:entry.content.usage+' 同一明细不得重复累计。'}),undefined);assert.equal(c.GovernanceDemo.act(record,entry,'confirm',actor),undefined);
 const update=w.updatePreview(doc);assert.equal(w.applyUpdate(doc,update.generated,actor,w.stamp(update.entries),doc.draft.revision),undefined);const repaired=release();
 const demo=c.FeedbackDemo.create({seed:false}),row=demo.intake({type:'用户点踩',sourceId:'products',userId:actor.id,answerId:'LATE',question:'原销量',reaction:{type:'down',reason:'晚到反馈'},evidence:{knowledge:[{id:doc.id,revision:oldRevision}],release:before.id}},actor).item;assert.equal(demo.assign(row,{owner:actor.id,direction:'业务知识',reason:'核对'},actor,row.revision),undefined);assert.equal(demo.investigate(row,{basis:'核对原引用'},actor,row.revision),undefined);
 const binding=c.FeedbackDemo.bindingOptions('products').find(row=>row.value.endsWith('/metric:sales-quantity'));
 const task=demo.addTask(row,{kind:'业务知识',title:'迟到反馈复用已发布修复',owner:actor.id,binding:binding.value},actor,row.revision).task;
 assert.equal(demo.handling(row,task.id,{summary:'修复已由另一事项完成，本事项验收现有有效修订',expected:'原销售数量12盒且不重复',reproduction:'查询原问题',dataBasis:'固定明细10盒与2盒',releaseId:repaired.id,coverage:'原回答r1，修复r2已覆盖重复累计与查询边界'},actor,row.revision),undefined);
 assert.equal(demo.gate(row).error,undefined);
 const duplicate=demo.intake({type:'用户点踩',sourceId:'products',userId:actor.id,answerId:'CURRENT-REV',question:'同版原销量',reaction:{type:'down',reason:'现有版本也存在疑点'},evidence:{knowledge:[{id:doc.id,revision:doc.draft.revision}]}},actor).item;
 assert.equal(demo.merge(duplicate,row,{reason:'同一统计范围',targetRevision:row.revision},actor,duplicate.revision),undefined);
 assert.match(demo.gate(row).error,/版本相同/);
 assert.equal(demo.unmerge(duplicate,'原引用不同，分开核查',actor,duplicate.revision),undefined);assert.equal(demo.gate(row).error,undefined);
 const originalRevision=entry.revision;assert.equal(originalRevision,2);
 demo.verify(row,{decision:'通过',actual:'12盒',basis:'固定身份与数据、原问边界核对'},actor,row.revision);
 assert.equal(demo.close(row,{status:'已解决',conclusion:'修复已复用',basis:'现有修复覆盖通过'},actor,row.revision),undefined);
 entry.revision++;assert.ok(demo.gate(row).error);
});

test('人工验收绑定测试身份及授权，输出规则变化后不能沿用旧通过结案',()=>{
 const f=fixture(),row=f.row();f.assign(row);const task=f.add(row);f.handle(row,task);f.verify(row);
 f.c.PeopleDemo.extraRequests.find(item=>item.user===f.actor.id&&item.status==='已生效'&&item.access.some(access=>access.source==='retail')).access[0].rules.push({id:'new-rule',content:'新输出限制'});
 assert.match(f.demo.close(row,{status:'已解决',basis:'旧验收',conclusion:'内部'},f.actor,row.revision),/输出规则已变化/);
 f.verify(row);f.close(row);assert.equal(row.status,'已解决');
});

test('反馈只向无业务证据权的处理者提供统一受限视图，撤权后不保留旧明细',()=>{
 const f=fixture(),worker=f.c.PeopleDemo.users[10],row=f.receive('PRIVATE','products').item;f.assign(row);
 const secret='受限占位文本_9381';row.title=secret;row.basis=secret;row.rootCause=secret;row.conclusion=secret;row.closeBasis=secret;
 row.origins[0].question=secret;row.origins[0].evidence.sql=secret;row.currentReaction.reason=secret;
 row.notes.push({userId:f.actor.id,text:secret});row.supplement={userId:f.actor.id,question:secret};
 row.verifications.push({decision:'不通过',actual:secret,basis:secret,actor:f.actor.name,at:1});row.events.push({action:'记录核查结论',reason:secret,actor:f.actor.name,at:1});
 const task=f.demo.addTask(row,{kind:'技术修复',title:secret,owner:worker.id},f.actor,row.revision).task;
 task.result={summary:secret,reproduction:secret,expected:secret,dataBasis:secret,reference:secret};task.history=[{result:{summary:secret}}];task.dispositionHistory=[{reason:secret}];
 const view=f.demo.present(row,worker);assert.ok(view.restricted);assert.equal(view.id,row.id);assert.equal(view.tasks[0].id,task.id);assert.equal(view.tasks[0].result.restricted,true);
 assert.equal(JSON.stringify(view).includes(secret),false);assert.equal(f.demo.present(row,f.actor).basis,secret);
 f.c.RoleDemo.records.find(role=>role.name==='超级管理员').permissions=f.c.RoleDemo.records.find(role=>role.name==='超级管理员').permissions.filter(key=>key!=='feedback.evidence');
 assert.equal(JSON.stringify(f.demo.present(row,f.actor)).includes(secret),false);assert.equal(row.basis,secret);
});
test('SQL查看与业务证据分别核权，合并事项的成员也经过同一展示规则',()=>{
 const f=fixture(),a=f.row(),b=f.receive('MERGE-B').item;f.assign(a);
 a.origins[0].evidence.sql='SQL_ONLY_SENTINEL';b.origins[0].question='成员原问题';
 assert.equal(f.demo.merge(b,a,{reason:'同根因',targetRevision:a.revision},f.actor,b.revision),undefined);
 const role=f.c.RoleDemo.records.find(role=>role.name==='超级管理员');role.permissions=role.permissions.filter(key=>key!=='query.sql');
 let view=f.demo.present(a,f.actor);assert.equal(view.restricted,false);assert.equal(view.members.length,2);assert.equal(JSON.stringify(view).includes('SQL_ONLY_SENTINEL'),false);assert.ok(JSON.stringify(view).includes('成员原问题'));
 role.permissions=role.permissions.filter(key=>key!=='feedback.evidence');view=f.demo.present(a,f.actor);assert.equal(JSON.stringify(view).includes('成员原问题'),false);
});
test('待我处理只保留实际动作，我负责的保留等待他人和已提交的任务',()=>{
 const f=fixture(),row=f.row();f.assign(row);
 f.demo.requestSupplement(row,{userId:f.actor.id,question:'补充时间'},f.actor,row.revision);
 assert.equal(f.demo.list(f.actor,{bucket:'mine'}).length,0);assert.equal(f.demo.list(f.actor,{bucket:'owned'}).length,1);assert.equal(f.demo.nextAction(row,f.actor).label,'等待用户补充');
 f.demo.supplement(row.id,'已补充',f.actor);const worker=f.c.PeopleDemo.users[1];f.c.RoleDemo.records.find(role=>role.name==='管理员').permissions.push('feedback.view','feedback.process');
 const task=f.demo.addTask(row,{kind:'技术修复',title:'技术修复',owner:worker.id},f.actor,row.revision).task;
 assert.equal(f.demo.list(f.actor,{bucket:'mine'}).length,0);assert.equal(f.demo.list(worker,{bucket:'mine'}).length,1);assert.equal(f.demo.nextAction(row,f.actor).label,'等待任务负责人提交');
 f.handle(row,task);assert.equal(f.demo.list(worker,{bucket:'mine'}).length,0);assert.equal(f.demo.list(worker,{bucket:'owned'}).length,1);
 assert.equal(f.demo.nextAction(row,f.actor).label,'登记人工验收');f.verify(row);assert.equal(f.demo.nextAction(row,f.actor).label,'验证结案');
});
test('全部任务退出和验收条件变化会更新真实下一步，计时明确表示最近更新',()=>{
 const f=fixture(),row=f.row();f.assign(row);const task=f.add(row);f.handle(row,task);f.verify(row);
 f.c.PeopleDemo.extraRequests.find(item=>item.user===f.actor.id&&item.status==='已生效'&&item.access.some(access=>access.source==='retail')).access[0].rules.push({id:'new',content:'新限制'});
 assert.equal(f.demo.nextAction(row,f.actor).label,'重新登记人工验收');
 f.demo.disposition(row,task.id,{action:'无需变更',reason:'核实无需修改',covered:true},f.actor,row.revision);
 assert.equal(f.demo.nextAction(row,f.actor).label,'说明并结案');assert.equal(f.c.FeedbackDemo.updatedLabel(row,row.updatedAt+6*3600000),'6小时前');
 f.receive('A1','retail',{type:'up'});assert.equal(f.c.FeedbackDemo.updatedLabel(row,row.updatedAt),'刚刚');assert.equal(f.demo.nextAction(row,f.actor).label,'说明并结案');
});

test('受限内容在办理、历史和证据页签及结果表单中均不可见，技术任务仍可办理',()=>{
 const f=fixture(),row=f.receive('PRIVATE-UI','products').item,worker=f.c.PeopleDemo.users[10],secret='受限界面占位_6248';f.assign(row);
 row.title=secret;row.direction=secret;row.rootCause=secret;row.basis=secret;row.status='待补充';row.supplement={userId:f.actor.id,question:secret};
 row.origins[0].question=secret;row.events.push({action:'记录核查结论',reason:secret,actor:f.actor.name,at:1,cycle:1});
 row.verifications.push({decision:'不通过',actual:secret,basis:secret,actor:f.actor.name,at:1,cycle:1});
 const task=f.demo.addTask(row,{kind:'技术修复',title:secret,owner:worker.id},f.actor,row.revision).task;row.status='待补充';task.result={summary:secret,reproduction:secret,expected:secret,dataBasis:secret,reference:secret};
 class Node{constructor(tag='',cls='',value=''){this.value=String(value||'');this.children=[];this.scrollTop=0;this.classList={add(){}};}append(...nodes){this.children.push(...nodes.filter(n=>n!=null));}replaceChildren(...nodes){this.children=nodes;}setAttribute(){}addEventListener(){}querySelector(){return null;}get textContent(){return this.value+this.children.map(n=>n instanceof Node?n.textContent:String(n)).join('');}}
 const host=new Node(),forms=[],buttons=[];let tabChange;
 const ui={currentUser:worker,el:(...args)=>new Node(...args),button:(label,action)=>{buttons.push({label,action});return new Node('button','',label);},pair:(label,value)=>new Node('div','',label+':'+value),pageHeading:label=>new Node('h1','',label),setTitle(){},moreActions:()=>new Node(),openForm:config=>{forms.push(config);}};
 Object.assign(f.c,{URLSearchParams,location:{search:''},requestAnimationFrame:fn=>fn(),PrototypeUI:{mount:(node,config)=>{tabChange=config.methods.change;return node;}}});
 f.c.FeedbackDemo=Object.assign(f.demo,Object.fromEntries(Object.entries(f.c.FeedbackDemo).filter(([key])=>!(key in f.demo))));f.c.window.FeedbackDemo=f.c.FeedbackDemo;
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../../后台管理/feedback.js'),'utf8'),f.c);Object.assign(f.c,f.c.window);
 f.c.FeedbackPage.render(host,ui,row.id);assert.equal(host.textContent.includes(secret),false);assert.ok(host.textContent.includes('具体内容受限'));
 buttons.find(button=>button.label==='更新处理记录').action();assert.equal(JSON.stringify(forms[0].value).includes(secret),false);
 buttons.find(button=>button.label==='重新分派').action();assert.equal(JSON.stringify(forms[1].value).includes(secret),false);
 tabChange('history');assert.equal(host.textContent.includes(secret),false);tabChange('evidence');assert.equal(host.textContent.includes(secret),false);
});

test('待补充期间新增或提交任务不解除等待，必要信息补齐前不能验收结案',()=>{
 const f=fixture(),row=f.row();f.assign(row);
 f.demo.requestSupplement(row,{userId:f.actor.id,question:'补齐业务条件'},f.actor,row.revision);
 const task=f.add(row);f.handle(row,task);assert.equal(row.status,'待补充');assert.equal(f.demo.nextAction(row,f.actor).actionable,false);
 assert.match(f.demo.verify(row,{decision:'通过',actual:'x',basis:'x'},f.actor,row.revision),/补齐用户信息/);
 assert.match(f.demo.close(row,{status:'已解决',conclusion:'x',basis:'x'},f.actor,row.revision),/补齐用户信息/);
 f.demo.supplement(row.id,'已补齐条件',f.actor);assert.equal(row.supplement,null);assert.equal(f.demo.nextAction(row,f.actor).key,'verify');f.verify(row);f.close(row);
});

test('分派权限不替代处理归属，任务转派后原负责人不能继续提交',()=>{
 const f=fixture(),row=f.row(),worker=f.c.PeopleDemo.users[10];f.assign(row);
 const task=f.demo.addTask(row,{kind:'技术修复',title:'分派给技术人员',owner:worker.id},f.actor,row.revision).task;
 const data=f.complete();
 assert.match(f.demo.handling(row,task.id,data,f.actor,row.revision),/其他负责人/);
 assert.match(f.demo.investigate(row,{basis:'未获派者填写'},worker,row.revision),/其他负责人/);
 assert.equal(f.demo.handling(row,task.id,data,worker,row.revision),undefined);
 assert.equal(f.demo.reassignTask(row,task.id,{owner:f.actor.id,reason:'职责调整'},f.actor,row.revision),undefined);
 assert.match(f.demo.handling(row,task.id,data,worker,row.revision),/其他负责人/);assert.equal(f.demo.handling(row,task.id,data,f.actor,row.revision),undefined);
});
test('旧范围快照不再参与权限计算，模型和角色目录不保留范围配置能力',()=>{
 const f=fixture(),row=f.row(),snapshot=f.demo.snapshot();snapshot.scopes=[{userId:f.actor.id,sources:[]}];
 const restored=f.c.FeedbackDemo.create({seed:false,snapshot});assert.equal(restored.list(f.actor).length,1);assert.equal(restored.snapshot().scopes,undefined);
 assert.equal(f.c.FeedbackDemo.configureScope,undefined);assert.equal(f.c.FeedbackDemo.manages,undefined);assert.equal(f.c.FeedbackDemo.scopes,undefined);
 assert.equal(f.c.RoleDemo.records.some(role=>role.permissions.includes('feedback.scope')),false);
 const user=f.c.PeopleDemo.users[3];assert.equal(f.c.FeedbackDemo.sourceAccess(user,row.sourceId),true);assert.equal(restored.list(user).length,0);
});

test('无业务查询权的事项负责人可记录获准核查，建立分派任务仍核对分派功能',()=>{
 const f=fixture(),row=f.receive('P-OWNER','products').item,worker=f.c.PeopleDemo.users[10];
 f.c.RoleDemo.records.find(role=>role.name==='反馈处理员').permissions=f.c.RoleDemo.records.find(role=>role.name==='反馈处理员').permissions.filter(key=>key!=='feedback.assign');
 assert.equal(f.demo.assign(row,{owner:worker.id,direction:'技术问题',reason:'排查连接诊断'},f.actor,row.revision),undefined);
 assert.equal(f.demo.nextAction(row,worker).key,'investigate');assert.equal(f.demo.investigate(row,{rootCause:'连接诊断异常',basis:'已核对获准诊断引用'},worker,row.revision),undefined);
 assert.match(f.demo.addTask(row,{kind:'技术修复',title:'擅自分派',owner:worker.id},worker,row.revision),/权限/);
 assert.equal(f.c.FeedbackDemo.sourceAccess(worker,'products'),false);
});

test('技术和数据跟进只需当前工作及下一步，等待外部处理不进入本人待办或验收',()=>{
 for(const kind of ['技术修复','源数据修正']){
  const f=fixture(),row=f.row();f.assign(row);const task=f.demo.addTask(row,{kind,title:'跟进修正',owner:f.actor.id},f.actor,row.revision).task;
  assert.equal(f.demo.handling(row,task.id,{stage:'working',note:'已核对问题，下一步联系实际处理人员'},f.actor,row.revision),undefined);
  assert.equal(task.result,null);assert.equal(row.status,'处理中');assert.match(f.demo.gate(row).error,/尚未提交/);
  assert.equal(f.demo.handling(row,task.id,{stage:'waiting',note:'已联系负责人，等待修正或生效后复查'},f.actor,row.revision),undefined);
  assert.equal(f.demo.list(f.actor,{bucket:'mine'}).length,0);assert.equal(f.demo.list(f.actor,{bucket:'owned'}).length,1);assert.match(f.demo.nextAction(row,f.actor).label,/等待/);
  assert.equal(task.history[0].followup.stage,'working');
 }
});
test('无工单或版本号也能登记可核对的修正，源数据要求实际修正团队和生效确认',()=>{
 const f=fixture(),row=f.row();f.assign(row);const task=f.demo.addTask(row,{kind:'源数据修正',title:'修正退货日期',owner:f.actor.id},f.actor,row.revision).task;
 assert.match(f.demo.handling(row,task.id,f.complete({reference:'',performer:''}),f.actor,row.revision),/实际完成修正/);
 assert.match(f.demo.handling(row,task.id,f.complete({performer:'售后团队',effectiveConfirmed:false}),f.actor,row.revision),/尚未确认生效/);
 assert.equal(f.demo.handling(row,task.id,f.complete({reference:'',performer:'售后团队'}),f.actor,row.revision),undefined);assert.equal(row.status,'待验证');
 assert.equal(f.demo.gate(row).error,undefined);assert.equal(task.result.reference,'');assert.equal(task.result.format,2);f.verify(row);f.close(row);
});
test('结果退回跟进会使旧验收失效，跟进历史在转派和重开后保留',()=>{
 const f=fixture(),row=f.row();f.assign(row);const task=f.add(row);f.handle(row,task);f.verify(row);
 assert.equal(f.demo.handling(row,task.id,{stage:'waiting',note:'发现尚未生效，继续等待'},f.actor,row.revision),undefined);assert.equal(row.verification,null);assert.equal(task.result,null);assert.equal(task.history[0].result.format,2);
 f.demo.reassignTask(row,task.id,{owner:f.actor.id,reason:'重新接手核对'},f.actor,row.revision);assert.equal(task.followup,null);assert.equal(task.history[1].followup.note,'发现尚未生效，继续等待');
 f.handle(row,task);f.verify(row);f.close(row);f.demo.reopen(row,'出现新线索',f.actor,row.revision);assert.equal(task.result,null);assert.ok(task.history.some(h=>h.result?.format===2));
});
test('查询安全变更的相关场景测试仍是门槛，隐藏字段不充当完成结果',()=>{
 const f=fixture(),row=f.row();f.assign(row);const task=f.add(row);
 assert.match(f.demo.handling(row,task.id,f.complete({regressionPassed:false}),f.actor,row.revision),/相关场景/);
 assert.equal(f.demo.handling(row,task.id,{...f.complete(),stage:'working',note:'排查中'},f.actor,row.revision),undefined);assert.equal(task.result,null);assert.match(f.demo.gate(row).error,/尚未提交/);
 f.handle(row,task);task.result.regressionPassed=false;assert.match(f.demo.gate(row).error,/相关场景/);
});
test('跟进说明和外部处理人同样受证据权限保护',()=>{
 const f=fixture(),row=f.receive('F-PRIVATE','products').item,worker=f.c.PeopleDemo.users[10];f.assign(row);
 const task=f.demo.addTask(row,{kind:'源数据修正',title:'核对修正',owner:worker.id},f.actor,row.revision).task;
 f.demo.handling(row,task.id,{stage:'waiting',note:'业务秘密_NOTE_591',performer:'业务秘密_PERSON_591'},worker,row.revision);
 const view=f.demo.present(row,worker);assert.equal(view.tasks[0].followup.stage,'waiting');assert.equal(JSON.stringify(view).includes('业务秘密'),false);
 assert.equal(task.followup.performer,undefined);
 f.demo.handling(row,task.id,f.complete({performer:'业务秘密_PERSON_591',summary:'业务秘密_RESULT_591'}),worker,row.revision);
 assert.equal(JSON.stringify(f.demo.present(row,worker)).includes('业务秘密'),false);
});
