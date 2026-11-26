const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function page(storage,query=false,referrer='',type='navigate'){
  const events={},clicks={},href='http://demo/prototypes/'+(query?'app':'%E5%90%8E%E5%8F%B0%E7%AE%A1%E7%90%86')+'/index.html';
  const c=vm.createContext({window:{addEventListener:(name,fn)=>events[name]=fn},structuredClone,URL,
    location:{href,origin:'http://demo'},document:{referrer,addEventListener:(name,fn)=>clicks[name]=fn},performance:{getEntriesByType:()=>[{type}]},
    sessionStorage:{getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)}});
  const files=['record-query.js','people-data.js','role-data.js','source-approval-data.js','access-request-data.js',...query?[]:['grant-data.js','audit-data.js']];
  for(const file of files){vm.runInContext(fs.readFileSync(path.join(__dirname,'../../后台管理',file),'utf8'),c);Object.assign(c,c.window);}
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../../shared/query-knowledge-bridge.js'),'utf8'),c);Object.assign(c,c.window);
  return{c,href,leave(target){clicks.click({button:0,target:{closest:()=>({href:target,target:''})}});events.pagehide();}};
}
test('approval, latest rules and audit survive admin-query round trips; backend reload resets the demo',()=>{
  const storage=new Map(),admin=page(storage),c=admin.c,actor=c.PeopleDemo.users[0];
  const request=c.AccessRequestDemo.records().find(row=>row.user!==actor.id&&row.approvalItems.some(part=>part.status==='待审批'&&part.reviewer===actor.id));
  const part=request.approvalItems.find(part=>part.status==='待审批'&&part.reviewer===actor.id),rule=c.PeopleDemo.outputRules[0];
  assert.equal(c.AccessRequestDemo.decide(request,part.id,'approve',{reason:'核实用途',access:[{source:part.source,rules:[rule]}]},actor,request.revision),undefined);
  const original=part.rules[0].content;rule.content='不返回电话和身份证及联系地址';rule.revision=2;
  const audits=c.AuditDemo.records(actor).length;
  admin.leave('http://demo/prototypes/app/index.html');
  const query=page(storage,true,admin.href),applicant=query.c.PeopleDemo.users.find(row=>row.id===request.user);
  assert.ok(query.c.PeopleDemo.accessFor(applicant).some(access=>access.source===part.source));
  assert.ok(query.c.PeopleDemo.rulesFor(applicant,part.source).some(row=>row.content===rule.content));
  const copied=query.c.PeopleDemo.extraRequests.find(row=>row.id===request.id);assert.equal(copied.approvalItems.find(row=>row.id===part.id).rules[0].content,original);
  query.leave(admin.href);const back=page(storage,false,query.href);
  assert.equal(back.c.AuditDemo.records(back.c.PeopleDemo.users[0]).length,audits);
  assert.equal(back.c.PeopleDemo.extraRequests.find(row=>row.id===request.id).approvalItems.find(row=>row.id===part.id).status,'已生效');
  assert.equal(page(storage,false,query.href,'reload').c.PeopleDemo.outputRules[0].content,original);
});
test('an inactive or unreviewed department and an unapproved joint item do not grant query access',()=>{
  const p=page(new Map()),people=p.c.PeopleDemo,user=people.users.find(row=>row.id==='u04'),department=people.departments.find(row=>row.id===user.department);
  people.extraRequests.splice(0);assert.ok(people.accessFor(user).length);
  department.requiresReview=true;assert.equal(people.accessFor(user).length,0);
  department.requiresReview=false;department.status='停用';assert.equal(people.accessFor(user).length,0);
  people.extraRequests.push({user:user.id,status:'待审批',approvalItems:[{source:'products',status:'已批准',rules:people.outputRules}]});
  assert.equal(people.accessFor(user).length,0);
});
