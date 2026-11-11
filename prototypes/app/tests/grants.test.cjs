const {test}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function fixture(loadGrants=true){
  const context=vm.createContext({window:{},structuredClone});
  for(const file of ['record-query.js','people-data.js','role-data.js','source-approval-data.js','access-request-data.js','grant-data.js']){vm.runInContext(fs.readFileSync(path.join(__dirname,'../../后台管理',file),'utf8'),context);Object.assign(context,context.window);}
  const people=context.PeopleDemo,demo=context.GrantDemo,requests=context.AccessRequestDemo,actor=people.users[0];
  if(loadGrants)demo.records(actor);
  return{people,demo,requests,actor,routing:context.SourceApprovalDemo};
}
const find=(demo,actor,request,source)=>demo.records(actor).find(row=>row.request?.id===request&&(!source||row.source===source));
const approval=part=>({access:[{source:part.source,rules:structuredClone(part.rules)}],reason:''});

test('an approved source belongs to the applicant and never to the approving operator',()=>{
  const{people,demo,requests,actor}=fixture(),request=requests.records().find(row=>row.id==='SQ-REVIEW-003'),part=request.approvalItems[0];
  const before=JSON.stringify(demo.accessGroups(actor));
  requests.decide(request,part.id,'approve',approval(part),actor,request.revision);
  const row=find(demo,actor,request.id),applicant=people.users.find(user=>user.id===request.user);
  assert.equal(row.user,applicant.id);assert.equal(row.part.reviewedById,actor.id);assert.notEqual(row.user,actor.id);
  assert.ok(demo.accessGroups(applicant).some(group=>group.origins.some(origin=>origin.id===row.id)));
  assert.equal(JSON.stringify(demo.accessGroups(actor)),before);
  demo.revoke(row.id,'结束用途',actor,request.revision);
  assert.equal(requests.reviewInfo(part,request).label,'批准人');assert.equal(requests.reviewInfo(part,request).identity,'林管理员（lin.admin）');
});

test('authorization management contains actual sources rather than pending or failed approvals',()=>{
  const{demo,actor}=fixture(),rows=demo.records(actor);
  assert.ok(rows.some(row=>row.type==='department'));assert.ok(!rows.some(row=>row.state==='生效失败'));
  assert.ok(!rows.some(row=>row.request?.id==='SQ-DEMO-013'));
  assert.ok(rows.filter(row=>row.type==='request').length>10);
});
test('view and revoke capabilities are checked independently without returning denied record counts',()=>{
  const{people,demo,actor}=fixture(),row=find(demo,actor,'SQ-DEMO-022'),before=JSON.stringify(row.request);
  const viewer=people.users.find(user=>user.id==='u06');viewer.roles=['权限审计员'];
  assert.ok(demo.records(viewer).length);assert.equal(demo.canRevoke(viewer),false);
  assert.match(demo.revoke(row.id,'核对',viewer,row.request.revision),/没有撤销/);
  viewer.roles=[];assert.equal(demo.records(viewer).length,0);
  actor.status='停用';assert.equal(demo.records(actor).length,0);
  assert.equal(JSON.stringify(row.request),before);
});
test('revoking one application source retains other sources, rules and identity configuration',()=>{
  const{people,demo,requests,actor}=fixture(),row=find(demo,actor,'SQ-DEMO-022'),rules=JSON.stringify(row.rules),others=people.extraRequests.filter(request=>request!==row.request);
  const before=JSON.stringify({users:people.users,departments:people.departments,others});
  assert.ok(demo.impact(row.id,actor).sources[0].remaining>0);
  assert.equal(demo.revoke(row.id,'用途结束',actor,row.request.revision),undefined);
  const ended=demo.get(row.id,actor);assert.equal(ended.state,'已撤销');assert.equal(JSON.stringify(ended.rules),rules);
  assert.equal(requests.effectiveAccess(row.request).length,0);assert.equal(row.request.status,'已撤销');
  assert.equal(JSON.stringify({users:people.users,departments:people.departments,others}),before);
  assert.equal(ended.part.revokedReason,'用途结束');assert.equal(ended.part.revokedBy,actor.name);
});
test('empty reasons, stale revisions and duplicate revocations cannot partially mutate a source',()=>{
  const{demo,actor}=fixture(),row=find(demo,actor,'SQ-DEMO-022'),before=JSON.stringify(row.request),revision=row.request.revision;
  assert.match(demo.revoke(row.id,' ',actor,revision),/原因/);assert.match(demo.revoke(row.id,'测试',actor,revision-1),/已变化/);
  assert.equal(JSON.stringify(row.request),before);
  demo.revoke(row.id,'用途结束',actor,revision);const after=JSON.stringify(row.request);
  assert.match(demo.revoke(row.id,'重复',actor,row.request.revision),/已撤销/);assert.equal(JSON.stringify(row.request),after);
});
test('independent sources in the same application can be revoked separately',()=>{
  const{demo,requests,actor}=fixture(),finance=find(demo,actor,'SQ-DEMO-018','finance');
  demo.revoke(finance.id,'不再核对回款',actor,finance.request.revision);
  assert.equal(finance.request.status,'部分已生效');assert.equal(requests.effectiveAccess(finance.request).length,1);
  assert.equal(requests.effectiveAccess(finance.request)[0].source,'retail');
  assert.equal(find(demo,actor,'SQ-DEMO-018','retail').state,'已生效');
});
test('revoking a required joint source ends that application combination together',()=>{
  const{people,demo,requests,actor}=fixture(),request=requests.records().find(row=>row.mode==='joint'),[first,second]=request.approvalItems;
  requests.decide(request,first.id,'approve',approval(first),actor,request.revision);
  requests.decide(request,second.id,'approve',approval(second),people.users.find(user=>user.id===second.reviewer),request.revision);
  const record=find(demo,actor,request.id),rules=JSON.stringify(request.approvalItems.map(part=>part.rules));
  assert.equal(demo.impact(record.id,actor).targets.length,2);
  demo.revoke(record.id,'联合用途结束',actor,request.revision);
  assert.ok(request.approvalItems.every(part=>part.status==='已撤销'));
  assert.equal(request.status,'已撤销');assert.equal(requests.effectiveAccess(request).length,0);
  assert.equal(JSON.stringify(request.approvalItems.map(part=>part.rules)),rules);
  assert.equal(request.history.filter(event=>event.action==='授权撤销').length,1);
});
test('department sources are managed through department configuration and retain ended records',()=>{
  const{people,demo,actor}=fixture(),row=demo.records(actor).find(row=>row.department==='d02'),department=people.departments.find(item=>item.id==='d02'),before=JSON.stringify(department),access=structuredClone(department.access);
  assert.match(demo.revoke(row.id,'测试',actor,1),/部门配置/);assert.equal(JSON.stringify(department),before);
  department.access=[];assert.equal(demo.get(row.id,actor).state,'来源已结束');assert.ok(demo.get(row.id,actor).rules.length);
  department.access=access;const added=demo.records(actor).find(item=>item.department==='d02'&&item.user===row.user&&item.state==='已生效');
  assert.notEqual(added.id,row.id);assert.equal(demo.get(row.id,actor).state,'来源已结束');
});
test('authorization queries intersect their own fields before returning records',()=>{
  const{demo,actor}=fixture();
  assert.equal(demo.records(actor,{person:'刘芳',approver:'lin.admin',source:'finance',origin:'022'}).length,1);
  assert.equal(demo.records(actor,{person:'林管理员',source:'finance',origin:'022'}).length,0);
  assert.equal(demo.records(actor,{person:'刘芳',approver:'liu.fang',origin:'022'}).length,0);
  assert.equal(demo.records(actor,{origin:'林管理员'}).length,0);
  assert.equal(demo.records(actor,{person:'林管理员',source:'products',origin:'022'}).length,0);
  assert.equal(demo.records(actor,{fromDate:'2099-01-01',toDate:'2099-01-02'}).length,0);
});
test('new automatic approvals appear immediately and revoked grants cannot be reactivated by reading',()=>{
  const{demo,requests,actor,routing,people}=fixture(),request=requests.records().find(row=>row.id==='SQ-REVIEW-003'),part=request.approvalItems[0];
  requests.decide(request,part.id,'approve',approval(part),actor,request.revision);
  const record=find(demo,actor,request.id);assert.equal(record.state,'已生效');
  demo.revoke(record.id,'结束本次用途',actor,request.revision);
  routing.save(people.dataSources[0],{primary:'u02',alternate:'u01'},actor,people.dataSources[0].approval.revision);
  requests.records();assert.equal(demo.get(record.id,actor).state,'已撤销');assert.equal(requests.effectiveAccess(request).length,0);
  assert.equal(typeof requests.decide(request,part.id,'approve',approval(part),actor,request.revision),'string');
});

test('personal access contains one row per datasource and deduplicates the applicable restrictions',()=>{
  const{demo,actor}=fixture(),groups=demo.accessGroups(actor),finance=groups.find(group=>group.id==='finance');
  assert.equal(groups.length,3);assert.equal(finance.activeCount,3);assert.equal(finance.rules.length,2);
  assert.equal(new Set(finance.rules.map(rule=>rule.displayKey)).size,finance.rules.length);
  assert.equal(demo.accessGroups(actor,{keyword:'财务',rule:'收款'}).length,1);
});
test('restrictions from different active grants all apply and update when one grant is revoked',()=>{
  const{people,demo,requests,actor}=fixture(),request=requests.records().find(row=>row.id==='SQ-DEMO-013'),part=request.approvalItems[0];
  requests.reassign(request,part.id,{source:'finance'},actor,request.revision);
  requests.decide(request,part.id,'approve',{access:[{source:'finance',rules:[people.outputRules.find(rule=>rule.id==='cost')]}],reason:''},people.users.find(user=>user.id===part.reviewer),request.revision);
  let finance=demo.accessGroups(actor).find(group=>group.id==='finance');
  assert.equal(finance.activeCount,4);assert.equal(finance.rules.length,3);
  const row=find(demo,actor,request.id),impact=demo.impact(row.id,actor).sources[0];
  assert.equal(impact.beforeRules.length,3);assert.equal(impact.afterRules.length,2);
  demo.revoke(row.id,'用途结束',actor,request.revision);
  finance=demo.accessGroups(actor).find(group=>group.id==='finance');assert.equal(finance.activeCount,3);assert.equal(finance.rules.length,2);
});
test('inactive and pending-review sources retain their provenance but do not add applicable restrictions',()=>{
  const{people,demo,actor}=fixture(),department=people.departments.find(row=>row.id==='d01');
  department.access=[{source:'finance',rules:[people.outputRules.find(rule=>rule.id==='cost')]}];department.requiresReview=true;
  const finance=demo.accessGroups(actor).find(group=>group.id==='finance');
  assert.equal(finance.activeCount,3);assert.equal(finance.origins.length,4);assert.ok(!finance.rules.some(rule=>rule.id==='cost'));
  const stopped=demo.accessGroups(people.users.find(user=>user.id==='u15'))[0];assert.equal(stopped.state,'来源停用');assert.equal(stopped.rules.length,0);assert.ok(stopped.origins[0].rules.length);
});
test('different snapshots of one rule never overwrite or silently remove another restriction',()=>{
  const{demo}=fixture(),input=[{rules:[{id:'privacy',name:'信息限制',content:'不返回电话'}]},{rules:[{id:'privacy',name:'信息限制',content:'不返回身份证'}]}];
  assert.equal(demo.mergeRules(input).length,2);assert.equal(input[0].rules[0].content,'不返回电话');
});
test('identical restriction text is displayed once even when source rule names differ',()=>{
  const{demo}=fixture(),input=[{rules:[{id:'a',name:'客户信息限制',content:'不返回电话'}]},{rules:[{id:'b',name:'联系方式限制',content:'不返回电话'}]}],rules=demo.mergeRules(input);
  assert.equal(rules.length,1);assert.ok(rules[0].name.includes('客户信息限制'));assert.ok(rules[0].name.includes('联系方式限制'));
});
test('one authorization page keeps read-only auditing separate from revoke capability',()=>{
  const{people,demo,actor}=fixture(),operator=people.users.find(user=>user.id==='u06');operator.roles=['授权管理员'];
  assert.ok(demo.canView(operator));assert.ok(demo.canRevoke(operator));
  assert.equal(demo.canView(actor),true);assert.ok(!actor.roles.includes('授权执行员'));
});
test('approval decisions stay approved after revocation while the authorization status changes',()=>{
  const{demo,requests,actor}=fixture(),request=requests.records().find(row=>row.id==='SQ-REVIEW-003'),part=request.approvalItems[0];
  requests.decide(request,part.id,'approve',approval(part),actor,request.revision);
  const row=find(demo,actor,request.id);demo.revoke(row.id,'用途结束',actor,request.revision);
  assert.equal(part.status,'已撤销');assert.equal(requests.decisionOf(part),'已批准');
  assert.ok(requests.inbox(actor,'done',{status:'已批准'}).includes(request));
  assert.equal(requests.inbox(actor,'done',{status:'已撤销'}).length,0);
});

test('approved examples distinguish applicants from historical approvers for every source',()=>{
  const{demo,requests,actor}=fixture(),rows=demo.records(actor).filter(row=>row.type==='request');
  assert.ok(new Set(rows.map(row=>row.user)).size>1);
  for(const row of rows){
    assert.equal(row.user,row.request.user);assert.notEqual(row.part.reviewedById,row.user);
    assert.equal(row.part.reviewer,row.part.reviewedById);
    assert.ok(row.part.reviewedAt);assert.ok(row.part.reviewedLogin);assert.ok(row.part.reviewedBy);
    assert.ok(row.request.history.some(event=>event.action==='已批准'));
    assert.notEqual(requests.reviewIdentity(row.part),'未记录');
  }
  assert.equal(find(demo,actor,'SQ-DEMO-018','retail').part.reviewedById,'u02');
  assert.equal(find(demo,actor,'SQ-DEMO-018','finance').part.reviewedById,'u13');
});

test('approval identity is frozen at the decision and survives responsibility changes and revocation',()=>{
  const{people,demo,requests,routing,actor}=fixture(),request=requests.records().find(row=>row.id==='SQ-REVIEW-003'),part=request.approvalItems[0];
  requests.decide(request,part.id,'approve',approval(part),actor,request.revision);
  const record=find(demo,actor,request.id),identity=requests.reviewIdentity(part),time=part.reviewedAt;
  assert.equal(part.reviewedById,actor.id);assert.equal(part.reviewedLogin,actor.login);
  routing.save(people.dataSources[0],{primary:'u02',alternate:'u01'},actor,people.dataSources[0].approval.revision);
  actor.name='改名后的管理员';actor.login='renamed.admin';
  demo.revoke(record.id,'用途结束',actor,request.revision);
  assert.equal(requests.reviewIdentity(demo.get(record.id,actor).part),identity);assert.equal(part.reviewedAt,time);
  assert.equal(demo.records(actor,{approver:'lin.admin',origin:request.id}).length,1);
  assert.equal(demo.records(actor,{approver:'renamed.admin',origin:request.id}).length,0);
  assert.equal(requests.reviewIdentity({reviewer:'u01'}),'未记录');
});

test('completed examples are visible in the approver inbox before visiting authorization management',()=>{
  const{people,requests,actor}=fixture(false),request=people.extraRequests.find(row=>row.id==='SQ-DEMO-022');
  assert.equal(request.approvalItems,undefined);
  assert.ok(requests.inbox(actor,'done').includes(request));
  assert.equal(requests.inbox(people.users.find(user=>user.id===request.user),'done',{requestId:request.id}).length,0);
});
