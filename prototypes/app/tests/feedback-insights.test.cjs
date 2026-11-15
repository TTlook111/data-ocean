const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
function fixture(){
  const c=vm.createContext({window:{},structuredClone});
  for(const file of ['people-data.js','role-data.js','source-approval-data.js','feedback-data.js','feedback-insights-data.js']){vm.runInContext(fs.readFileSync(path.join(__dirname,'../../后台管理',file),'utf8'),c);Object.assign(c,c.window);}
  const actor=c.PeopleDemo.users[0];let now=Date.UTC(2026,10,15,4);
  const feedback=c.FeedbackDemo.create({seed:false,now:()=>now}),model=c.FeedbackInsights.create({seed:false,feedback,now:()=>now});
  let revision=1,available=true;
  const doc=()=>({id:'DOC-1',title:'原销量口径',source:'products',revision,entries:[{objectId:'PRODUCT',revision}]});
  c.window.KnowledgeWorkspace={documents:new Map([['DOC-1',doc()]]),publishedContext:()=>available?{documents:[doc()]}:{error:'暂停'}};
  c.KnowledgeWorkspace=c.window.KnowledgeWorkspace;
  const policy={title:'商品重点检查',sourceId:'products',window:7,minimum:2,users:2,density:0.2,interval:24,recipients:[actor.id],independentReview:false};
  const note={title:'跨日时间核对',sourceId:'products',kind:'业务',scenario:'原销售数量',conditions:'自然日',documentId:'DOC-1',version:'',correction:'已修正',description:'历史日期边界已修正',check:'核对业务日期',basis:'已核实的业务说明'};
  const pref={...note,title:'原销量检索候选',threshold:0.65,limit:0.05};
  const save=(kind,data)=>{const result=model.save(kind,null,data,actor);assert.ok(!result.error,result.error);return result.row;};
  const verify=row=>assert.equal(model.verify(row,{basis:'题单r1：固定离线样例',result:'各案例已核对',decision:'通过',falsePositive:'误报0',falseNegative:'漏报0',positive:true,counter:true,restricted:true,regression:true},actor,row.revision),undefined);
  const confirm=row=>model.requiredReviews(row.draft.content,row.kind).forEach(duty=>assert.equal(model.confirm(row,{duty,basis:'核对业务条件及实际版本'},actor,row.revision),undefined));
  const enable=row=>{verify(row);confirm(row);assert.equal(model.toggle(row,true,'样例验证及适用性已确认',actor,row.revision),undefined);};
  return {c,actor,model,feedback,policy,note,pref,save,verify,confirm,enable,advance:ms=>now+=ms,change:()=>revision++,pause:()=>available=false};
}

test('候选默认不采用；验证、适用性确认和显式启用分别办理',()=>{
 const f=fixture(),row=f.save('preference',f.pref),input={...f.pref,eligible:true,score:0.7};
 assert.match(f.model.preview(row,input,f.actor).error,/未启用/);
 assert.match(f.model.toggle(row,true,'未验证',f.actor,row.revision),/验证/);
 f.verify(row);assert.match(f.model.toggle(row,true,'缺确认',f.actor,row.revision),/确认/);
 f.confirm(row);assert.equal(f.model.toggle(row,true,'启用',f.actor,row.revision),undefined);
 const result=f.model.preview(row,input,f.actor);assert.equal(result.adjustment,0.05);assert.equal(result.revision,1);
 assert.ok(f.model.preview(row,{...input,score:0.2},f.actor).error);assert.ok(f.model.preview(row,{...input,eligible:false},f.actor).error);
 assert.ok(f.model.preview(row,{...input,scenario:'净销量'},f.actor).error);
});

test('知识暂停或版本变化立即停止采用，旧验证和回退不能绕过',()=>{
 const f=fixture(),row=f.save('note',f.note);f.enable(row);
 assert.match(f.model.preview(row,f.note,f.actor).context,/历史问题已修正/);
 f.change();assert.equal(f.model.current(row),null);assert.match(f.model.preview(row,f.note,f.actor).error,/版本/);
 assert.match(f.model.verify(row,{decision:'通过',basis:'旧依据',result:'通过'},f.actor,row.revision),/版本/);
 const g=fixture(),other=g.save('preference',g.pref);g.enable(other);g.pause();assert.equal(g.model.current(other),null);
});

test('新草稿保留有效旧修订，新验证和确认不能复用；停用归零并可回退有效历史',()=>{
 const f=fixture(),row=f.save('preference',f.pref);f.enable(row);
 assert.equal(f.model.save('preference',row,{...f.pref,limit:0.1},f.actor,row.revision).error,undefined);
 assert.equal(f.model.current(row).revision,1);assert.equal(row.draft.validation,null);assert.equal(row.draft.reviews.length,0);
 f.enable(row);assert.equal(f.model.current(row).revision,2);
 assert.equal(f.model.rollback(row,1,'回退观察',f.actor,row.revision),undefined);assert.equal(f.model.current(row).revision,1);
 assert.equal(f.model.toggle(row,false,'停止观察',f.actor,row.revision),undefined);assert.match(f.model.preview(row,{...f.pref,score:0.7,eligible:true},f.actor).error,/调整量为零/);
});

test('同人可兼任，只有显式不同人员策略才限制复核；业务与技术职责独立确认',()=>{
 const f=fixture(),policy=f.save('policy',{...f.policy,independentReview:true});f.enable(policy);
 const row=f.save('note',f.note);f.verify(row);assert.match(f.model.confirm(row,{duty:'业务',basis:'本人'},f.actor,row.revision),/不同人员/);
 const g=fixture(),mixed=g.save('note',{...g.note,kind:'涉及业务口径的技术'});g.verify(mixed);
 assert.equal(g.model.confirm(mixed,{duty:'技术',basis:'技术核对'},g.actor,mixed.revision),undefined);assert.match(g.model.readiness(mixed),/确认/);
 assert.equal(g.model.confirm(mixed,{duty:'业务',basis:'业务核对'},g.actor,mixed.revision),undefined);assert.equal(g.model.readiness(mixed),'');
});

test('缺少正式知识仍可保存候选，但不能验证或启用',()=>{
 const f=fixture(),row=f.save('preference',{...f.pref,documentId:''});assert.equal(row.draft.content.refToken,'');
 assert.match(f.model.verify(row,{decision:'通过',basis:'样本',result:'结果'},f.actor,row.revision),/缺少正式/);
 assert.match(f.model.toggle(row,true,'尝试启用',f.actor,row.revision),/验证/);
});

test('验证与确认的修改拒绝旧表单；撤权后不展示业务内容或沿用确认',()=>{
 const f=fixture(),row=f.save('note',f.note),old=row.revision;f.verify(row);
 assert.match(f.model.confirm(row,{duty:'业务',basis:'旧表单'},f.actor,old),/变化/);f.confirm(row);
 f.actor.roles=[];assert.equal(f.model.visible(row,f.actor),false);assert.match(f.model.toggle(row,true,'撤权',f.actor,row.revision),/权限/);
});

test('样本按最后评价去重；提醒按同组用户和回答门槛生成并在间隔内合并',()=>{
 const f=fixture(),row=f.save('policy',f.policy);f.enable(row);
 const user=f.c.PeopleDemo.users.find(user=>user.id==='u09');
 const receive=(actor,answerId)=>f.feedback.intake({type:'用户点踩',sourceId:'products',userId:actor.id,answerId,question:'原销售数量',reaction:{type:'down',reason:'日期边界'}},actor).item;
 const first=receive(f.actor,'A1'),second=receive(user,'A2');
 assert.equal(f.feedback.merge(second,first,{reason:'相同场景及根因',targetRevision:first.revision},f.actor,second.revision),undefined);
 const observed=f.model.inspect(row,f.actor);assert.equal(observed.feedback,2);assert.equal(observed.users,2);assert.equal(f.model.reminders.length,1);
 f.model.inspect(row,f.actor);assert.equal(f.model.reminders.length,1);
 f.advance(25*3600000);f.model.inspect(row,f.actor);assert.equal(f.model.reminders.length,2);
 f.feedback.intake({type:'评价变更',sourceId:'products',userId:user.id,answerId:'A2',question:'原销售数量',reaction:null},user);
 const after=f.model.observation(row,f.actor);assert.equal(after.feedback,1);assert.equal(after.state,'观察线索');
});

test('单人重复评价不会满足独立人数，未启用或无效配置只显示线索',()=>{
 const f=fixture(),row=f.save('policy',f.policy);
 for(let i=0;i<4;i++)f.feedback.intake({type:'用户点踩',sourceId:'products',userId:f.actor.id,answerId:'SAME',question:'原销售数量',reaction:{type:'down',reason:'重复'+i}},f.actor);
 assert.equal(f.model.observation(row,f.actor).feedback,1);assert.equal(f.model.inspect(row,f.actor).state,'观察线索');assert.equal(f.model.reminders.length,0);
 assert.ok(f.model.save('policy',null,{...f.policy,users:6},f.actor).error);
});

test('修正状态变化立即停止旧注意事项，历史回退不能重新描述为未修正',()=>{
 const f=fixture(),row=f.save('note',{...f.note,correction:'未修正'});f.enable(row);
 assert.equal(f.model.save('note',row,{...f.note,correction:'已修正'},f.actor,row.revision).error,undefined);
 assert.equal(f.model.current(row),null);f.enable(row);
 assert.match(f.model.rollback(row,1,'不能复用旧修正状态',f.actor,row.revision),/修正状态/);
 assert.match(f.model.preview(row,{...f.note,correction:'未修正'},f.actor).error,/修正状态/);
});
