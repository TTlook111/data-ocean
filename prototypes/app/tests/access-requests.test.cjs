const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'access-request-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context);
    Object.assign(context, context.window);
  }
  const people = context.window.PeopleDemo, demo = context.window.AccessRequestDemo;
  return { people, demo, routing: context.SourceApprovalDemo, rows: demo.records(), actor: people.users[0] };
}
const approval = part => ({ access: [{ source: part.source, rules: structuredClone(part.rules) }], reason: '按业务用途核定' });

test('pending responsibility is not displayed as a historical approval identity', () => {
  const { demo, rows, actor }=fixture(), own=rows.find(row=>row.id==='SQ-DEMO-013'), waiting=own.approvalItems[0];
  const info=demo.reviewInfo(waiting,own);
  assert.equal(info.label,'审批负责人');assert.match(info.identity,/陈主管（chen.zhuguan）/);assert.equal(info.timeLabel,'');
  assert.notEqual(waiting.reviewer,actor.id);assert.equal(waiting.reviewedById,undefined);
  const routing=rows.find(row=>row.id==='SQ-REVIEW-007');
  assert.equal(demo.reviewInfo(routing.approvalItems[0],routing).identity,'待分流');
});

test('approved access retains approver labels while rejections do not', () => {
  const { demo, rows }=fixture();
  for(const id of ['SQ-DEMO-018','SQ-REVIEW-012']){
    const request=rows.find(row=>row.id===id);
    for(const part of request.approvalItems){
      const info=demo.reviewInfo(part,request);
      assert.equal(info.label,'批准人');assert.equal(info.timeLabel,'批准时间');assert.equal(info.time,part.reviewedAt);
    }
  }
  for(const id of ['SQ-REVIEW-011','SQ-REVIEW-013']){
    const request=rows.find(row=>row.id===id),info=demo.reviewInfo(request.approvalItems[0],request);
    assert.equal(info.label,'办理人');assert.equal(info.timeLabel,'办理时间');
  }
  assert.equal(demo.reviewInfo({status:'已生效',reviewer:'u01'},{}).identity,'未记录');
});

test('withdrawn and abandoned joint items do not invent completed approvers or current assignments', () => {
  const { demo, rows, actor }=fixture(), withdrawn=rows.find(row=>row.id==='SQ-REVIEW-014');
  assert.equal(demo.reviewInfo(withdrawn.approvalItems[0],withdrawn),null);
  const joint=rows.find(row=>row.id==='SQ-REVIEW-004'),[first,second]=joint.approvalItems;
  assert.equal(demo.decide(joint,first.id,'reject',{reason:'用途未通过'},actor,joint.revision),undefined);
  assert.equal(demo.reviewInfo(first,joint).label,'办理人');assert.equal(demo.reviewInfo(second,joint),null);
});
test('approval automatically activates only its selected source without changing organization', () => {
  const { people, demo, rows, actor } = fixture(), request = rows.find(value=>value.id==='SQ-REVIEW-003'), part = request.approvalItems[0];
  const before = JSON.stringify({ users: people.users, departments: people.departments });
  assert.equal(demo.decide(request, part.id, 'approve', approval(part), actor, 1), undefined);
  assert.equal(request.status, '已生效');
  assert.equal(JSON.stringify({ users: people.users, departments: people.departments }), before);
  assert.equal(request.access.length, 1); assert.equal(request.access[0].source, part.source);
  assert.equal(request.history.length, 1);
  assert.equal(request.history[0].actor, actor.name); assert.match(request.history[0].content, /访问已生效/);
});
test('self approval, stopped accounts, and another reviewer cannot change a request', () => {
  const { demo, rows, actor } = fixture();
  for (const request of [rows.find(value=>value.id==='SQ-DEMO-013'), rows.find(value=>value.id==='SQ-REVIEW-010'), rows.find(value=>value.mode==='joint')]) {
    const part = request.mode === 'joint' ? request.approvalItems[1] : request.approvalItems[0], before = JSON.stringify(request);
    assert.equal(typeof demo.decide(request, part.id, 'approve', approval(part), actor, 1), 'string');
    assert.equal(JSON.stringify(request), before);
  }
});
test('stale revision and missing rules never partially update the request', () => {
  const { demo, rows, actor } = fixture(), request = rows.find(value=>value.id==='SQ-REVIEW-003'), part = request.approvalItems[0], before = JSON.stringify(request);
  assert.match(demo.decide(request, part.id, 'approve', approval(part), actor, 0), /修订/);
  assert.match(demo.decide(request, part.id, 'approve', { access: [{ source: part.source, rules: [] }], reason: '' }, actor, 1), /规则/);
  assert.equal(JSON.stringify(request), before);
});
test('independent items retain separate outcomes, and supplement and rejection need a reason', () => {
  const { demo, rows, actor, people } = fixture(), request = rows.find(value=>value.id==='SQ-REVIEW-006');
  const [first, second] = request.approvalItems;
  assert.match(demo.decide(request, second.id, 'reject', { reason: ' ' }, actor, 1), /说明/);
  demo.decide(request, first.id, 'approve', approval(first), people.users.find(user=>user.id===first.reviewer), 1);
  assert.equal(request.status, '部分已生效');
  demo.decide(request, second.id, 'reject', { reason: '第二项用途不足' }, actor, 2);
  assert.equal(request.status, '部分已生效');
  assert.equal(first.status, '已生效');
  assert.equal(second.status, '已驳回');
});
test('joint approvals wait for all reviewers, and a rejected required item blocks the combination', () => {
  const { demo, rows, actor, people } = fixture(), request = rows.find(value=>value.mode==='joint'), [first, second] = request.approvalItems;
  demo.decide(request, first.id, 'approve', approval(first), actor, 1);
  assert.equal(request.status, '待审批');
  demo.decide(request, second.id, 'reject', { reason: '联合核对依据不足' }, people.users.find(value=>value.id===second.reviewer), 2);
  assert.equal(request.status, '已驳回');
  assert.equal(request.access.length, 0);
});
test('datasource responsibility resolves primary, self-substitute and missing assignments', () => {
  const { rows } = fixture();
  const part = id => rows.find(request=>request.id===id).approvalItems[0];
  assert.equal(part('SQ-REVIEW-001').reviewer, 'u13');
  assert.equal(part('SQ-REVIEW-002').reviewer, 'u01');
  assert.equal(part('SQ-REVIEW-002').routeKind, 'substitute');
  assert.equal(part('SQ-DEMO-013').reviewer, 'u02');
  for (const id of ['SQ-REVIEW-007','SQ-REVIEW-009']) { assert.equal(part(id).status, '待分流'); assert.equal(part(id).reviewer, ''); }
});
test('responsibility configuration requires two distinct eligible accounts and its own capability', () => {
  const { people, routing, actor } = fixture(), source = people.dataSources[0], before = JSON.stringify(source);
  assert.match(routing.save(source,{primary:'u01',alternate:'u01'},actor,1),/同一人/);
  assert.match(routing.save(source,{primary:'u04',alternate:'u01'},actor,1),/审批功能/);
  const technical = people.users.find(user=>user.id==='u08'); technical.roles=['数据源维护员'];
  assert.equal(routing.canReview(technical), false);
  assert.match(routing.save(source,{primary:'u01',alternate:'u02'},technical,1),/没有配置/);
  assert.equal(JSON.stringify(source),before);
});
test('configuring an unassigned source routes its waiting items without granting access', () => {
  const { people, rows, routing, actor } = fixture(), source = people.dataSources.find(source=>source.id==='aftersales'), request = rows.find(row=>row.id==='SQ-REVIEW-009');
  const identities = JSON.stringify({users:people.users,departments:people.departments});
  assert.equal(routing.save(source,{primary:'u01',alternate:'u02'},actor,1),undefined);
  assert.equal(request.status,'待审批'); assert.equal(request.approvalItems[0].reviewer,'u01');
  assert.equal(request.access.length,0); assert.equal(JSON.stringify({users:people.users,departments:people.departments}),identities);
});
test('triage resolves an unknown source and sends a primary applicant to the substitute', () => {
  const { rows, demo, actor } = fixture(), request = rows.find(row=>row.id==='SQ-REVIEW-007'), part=request.approvalItems[0];
  assert.equal(demo.reassign(request,part.id,{source:'finance'},actor,1),undefined);
  assert.equal(request.status,'待审批'); assert.equal(part.reviewer,'u01'); assert.equal(part.routeKind,'substitute');
  assert.equal(part.rules.length,0); assert.equal(request.access.length,0);
});
test('changing datasource cannot reuse the old reviewer or an old approval revision', () => {
  const { people, rows, demo, actor } = fixture(), request = rows.find(row=>row.id==='SQ-REVIEW-003'), part=request.approvalItems[0];
  const data={access:[{source:'finance',rules:people.outputRules.slice(0,1)}],reason:''};
  assert.match(demo.decide(request,part.id,'approve',data,actor,1),/重新核定/);
  assert.equal(part.source,'retail');
  demo.reassign(request,part.id,{source:'finance'},actor,1);
  assert.equal(part.reviewer,'u13'); assert.equal(part.rules.length,0);
  assert.match(demo.decide(request,part.id,'approve',data,actor,1),/修订/);
  assert.match(demo.decide(request,part.id,'approve',data,actor,2),/刘芳/);
  assert.equal(demo.decide(request,part.id,'approve',data,people.users.find(user=>user.id==='u13'),2),undefined);
  assert.equal(request.status,'已生效'); assert.equal(request.access.length,1);
});
test('responsibility changes update open routing while preserving completed facts', () => {
  const { people, rows, routing, actor } = fixture(), source=people.dataSources[0], open=rows.find(row=>row.id==='SQ-REVIEW-003'), done=rows.find(row=>row.id==='SQ-REVIEW-013');
  const snapshot=JSON.stringify(done);
  routing.save(source,{primary:'u02',alternate:'u13'},actor,1);
  assert.equal(open.approvalItems[0].reviewer,'u02'); assert.equal(open.revision,2);
  assert.equal(JSON.stringify(done),snapshot);
  assert.match(routing.save(source,{primary:'u01',alternate:'u02'},actor,1),/重新核对/);
});
test('loss of approval capability moves pending items back to triage', () => {
  const { people, rows, demo } = fixture(), request=rows.find(row=>row.id==='SQ-REVIEW-001');
  people.users.find(user=>user.id==='u13').roles=[];
  demo.records();
  assert.equal(request.status,'待分流'); assert.equal(request.approvalItems[0].reviewer,'');
  assert.equal(request.access.length,0);
});
test('triage requires its capability, rejects duplicate sources and cannot rewrite processed items', () => {
  const { people, rows, demo, actor } = fixture(), unknown=rows.find(row=>row.id==='SQ-REVIEW-007'), joint=rows.find(row=>row.mode==='joint'), done=rows.find(row=>row.id==='SQ-REVIEW-012');
  assert.match(demo.reassign(unknown,unknown.approvalItems[0].id,{source:'retail'},people.users[3],1),/没有核定/);
  assert.match(demo.reassign(joint,joint.approvalItems[0].id,{source:'finance'},actor,1),/已有审批子项/);
  const snapshot=JSON.stringify(done);
  assert.match(demo.reassign(done,done.approvalItems[0].id,{source:'retail'},actor,1),/已处理/);
  assert.equal(JSON.stringify(done),snapshot);
});
test('closed joint requests retain unfinished facts and reject further routing or decisions', () => {
  const { people, rows, demo, routing, actor } = fixture(), request=rows.find(row=>row.mode==='joint'), [first,second]=request.approvalItems;
  demo.decide(request,first.id,'reject',{reason:'联合用途未通过'},actor,1);
  assert.equal(request.status,'已驳回');
  const snapshot=JSON.stringify(request), source=people.dataSources.find(source=>source.id==='finance');
  routing.save(source,{primary:'u01',alternate:'u13'},actor,1);
  assert.equal(JSON.stringify(request),snapshot);
  assert.match(demo.decide(request,second.id,'reject',{reason:'不能重放'},people.users.find(user=>user.id==='u13'),request.revision),/已处理/);
  assert.equal(JSON.stringify(request),snapshot);
});

test('inboxes contain only the current reviewer items and require functional permission', () => {
  const { people, demo, actor } = fixture(), finance = people.users.find(user=>user.id==='u13');
  const own = demo.inbox(actor,'pending');
  assert.ok(own.length);
  assert.ok(own.every(request=>demo.partsFor(request,actor,'pending').every(part=>part.reviewer===actor.id)));
  assert.ok(!own.some(request=>['SQ-REVIEW-001','SQ-DEMO-013'].includes(request.id)));
  assert.ok(own.some(request=>request.id==='SQ-REVIEW-002'));
  assert.ok(demo.inbox(finance,'pending').some(request=>request.id==='SQ-REVIEW-001'));
  const viewer=people.users.find(user=>user.id==='u06');
  for(const tab of ['pending','routing','done']) assert.equal(demo.inbox(viewer,tab).length,0);
  assert.ok(!demo.inbox(actor,'done').some(request=>request.id==='SQ-REVIEW-014'));
});

test('a mixed request remains in its reviewer inbox while its other item awaits triage', () => {
  const { people, demo, rows, actor }=fixture(), request=rows.find(row=>row.mode==='joint');
  people.dataSources.find(source=>source.id==='finance').approval={primary:'',alternate:'',revision:2,updated:''};
  demo.records();
  assert.equal(request.status,'待分流');
  assert.ok(demo.inbox(actor,'pending').includes(request));
  assert.ok(demo.inbox(actor,'routing').includes(request));
  assert.equal(demo.partsFor(request,actor,'pending')[0].source,'retail');
  assert.equal(demo.partsFor(request,actor,'routing')[0].source,'finance');
});

test('inbox conditions intersect within visible parts rather than unrelated source or free text', () => {
  const { demo, actor }=fixture();
  assert.equal(demo.inbox(actor,'pending',{requestId:'review-003',applicant:'许'}).length,1);
  assert.equal(demo.inbox(actor,'pending',{requestId:'许'}).length,0);
  assert.equal(demo.inbox(actor,'pending',{applicant:'滨江'}).length,0);
  assert.equal(demo.inbox(actor,'pending',{requestId:'review-004',sourceId:'finance'}).length,0);
  assert.equal(demo.inbox(actor,'pending',{requestId:'review-004',sourceId:'retail'}).length,1);
  assert.equal(demo.inbox(actor,'pending',{fromDate:'2026-11-11'}).length,0);
});

test('my processed items include my completed part while another reviewer still has work', () => {
  const { people, demo, rows, actor }=fixture(), request=rows.find(row=>row.id==='SQ-REVIEW-006'), own=request.approvalItems[1];
  assert.equal(demo.decide(request,own.id,'approve',approval(own),actor,request.revision),undefined);
  assert.equal(request.status,'部分已生效');
  assert.ok(demo.inbox(actor,'done').includes(request));
  assert.ok(!demo.inbox(actor,'pending').includes(request));
  assert.equal(demo.partsFor(request,actor,'done').length,1);
  assert.ok(demo.inbox(people.users.find(user=>user.id==='u09'),'pending').includes(request));
  assert.equal(request.access.length,1);
});

test('unchanged responsibility never increments revisions or appends routing history', () => {
  const { people, routing, rows, actor }=fixture(), source=people.dataSources[0];
  const before=JSON.stringify({source,rows});
  assert.match(routing.save(source,{primary:source.approval.primary,alternate:source.approval.alternate},actor,source.approval.revision),/未变化/);
  assert.equal(JSON.stringify({source,rows}),before);
});

test('the final joint approval activates every required item together and cannot duplicate access', () => {
  const { people, demo, rows, actor }=fixture(), request=rows.find(row=>row.mode==='joint'), [first,second]=request.approvalItems;
  demo.decide(request,first.id,'approve',approval(first),actor,request.revision);
  assert.equal(first.status,'已批准'); assert.equal(demo.effectiveAccess(request).length,0);
  assert.equal(demo.decide(request,second.id,'approve',approval(second),people.users.find(user=>user.id===second.reviewer),request.revision),undefined);
  assert.equal(request.status,'已生效'); assert.ok(request.approvalItems.every(part=>part.status==='已生效'));
  assert.equal(request.access.length,2); assert.equal(request.history.filter(entry=>entry.action==='已批准').length,2);
  assert.ok(!request.history.some(entry=>entry.actor==='系统'));
  assert.match(request.history.at(-1).content,/访问已生效/);
  const before=JSON.stringify(request);
  assert.equal(typeof demo.decide(request,second.id,'approve',approval(second),people.users.find(user=>user.id===second.reviewer),request.revision),'string');
  assert.equal(JSON.stringify(request),before);
});

test('withdrawing unprocessed independent items retains the access already activated', () => {
  const { people, demo, rows, actor }=fixture(), request=rows.find(row=>row.id==='SQ-REVIEW-006');
  demo.decide(request,request.approvalItems[1].id,'approve',approval(request.approvalItems[1]),actor,request.revision);
  const access=JSON.stringify(request.access), applicant=people.users.find(user=>user.id===request.user);
  assert.match(demo.withdraw(request,actor,request.revision),/本人/);
  assert.equal(demo.withdraw(request,applicant,request.revision),undefined);
  assert.equal(request.status,'部分已生效'); assert.equal(request.approvalItems[0].status,'已撤回');
  assert.equal(JSON.stringify(request.access),access); assert.equal(demo.effectiveAccess(request).length,1);
  assert.equal(demo.inProgress(request),false);
});

test('personal pending source intentions are unavailable until the substitute approves', () => {
  const { people, demo, rows }=fixture(), request=rows.find(row=>row.id==='SQ-DEMO-013');
  assert.equal(request.access.length,0); assert.equal(demo.effectiveAccess(request).length,0);
  assert.equal(request.sourceIds[0],'retail');
  const part=request.approvalItems[0], substitute=people.users.find(user=>user.id===part.reviewer);
  const data={access:[{source:part.source,rules:structuredClone(people.outputRules.slice(0,1))}],reason:''};
  assert.equal(demo.decide(request,part.id,'approve',data,substitute,request.revision),undefined);
  assert.equal(request.status,'已生效'); assert.equal(demo.effectiveAccess(request).length,1);
  const historical=people.extraRequests.find(row=>row.id==='SQ-DEMO-011');
  demo.ensure(historical); assert.equal(historical.approvalItems[0].status,'已生效'); assert.equal(demo.effectiveAccess(historical).length,1);
});

test('approved examples are effective directly and have no separate system activation records', () => {
  const { demo, rows, routing }=fixture(), approved=rows.find(row=>row.id==='SQ-REVIEW-012');
  assert.equal(approved.status,'已生效'); assert.equal(approved.access.length,1); assert.equal(demo.effectiveAccess(approved).length,1);
  for (const row of rows) {
    assert.ok(!row.approvalItems.some(part=>part.status==='生效失败'||part.activationError));
    assert.ok(!row.history.some(entry=>entry.actor==='系统'||entry.action==='自动生效'));
    if (row.mode !== 'joint') for (const part of row.approvalItems.filter(part=>part.status==='已生效')) assert.equal(part.activatedAt,part.reviewedAt);
  }
  demo.records(); assert.equal(approved.status,'已生效');
  assert.equal(routing.hasAbility(fixture().actor,'grant-management.execute'),false);
});

test('invalid approval leaves the pending request unchanged without a successful decision', () => {
  const { demo, rows, actor }=fixture(), request=rows.find(row=>row.id==='SQ-REVIEW-003'), part=request.approvalItems[0];
  const before=JSON.stringify(request);
  assert.equal(typeof demo.decide(request,part.id,'approve',{access:[{source:part.source,rules:[]}],reason:''},actor,request.revision),'string');
  assert.equal(JSON.stringify(request),before);
});

test('supplement resubmission is applicant-only, revision checked and preserves the previous decision',()=>{
  const {demo,rows,people,actor}=fixture(),request=rows.find(row=>row.id==='SQ-REVIEW-015'),data={reason:'核对十月费用',requestedScope:'2026年10月各费用科目汇总',supplement:'已明确月份及汇总指标'};
  assert.match(demo.resubmit(request,data,people.users[1],request.revision),/申请人/);
  assert.match(demo.resubmit(request,data,actor,0),/修订/);
  const previous=JSON.stringify(request.approvalItems);
  assert.equal(demo.resubmit(request,data,actor,request.revision),undefined);
  assert.equal(request.status,'待审批');assert.equal(request.access.length,0);assert.equal(JSON.stringify(request.submissions[0].items),previous);
  assert.equal(request.approvalItems[0].reviewedById,undefined);assert.equal(request.approvalItems[0].rules.length,0);
  assert.equal(request.approvalItems[0].reviewer,'u13');assert.equal(request.approvalItems[0].application.supplement,data.supplement);
  assert.match(demo.resubmit(request,data,actor,request.revision),/需补充/);
});

test('independent resubmission leaves effective grants and unrelated pending items unchanged',()=>{
  const {demo,rows,people,actor}=fixture(),request=rows.find(row=>row.id==='SQ-REVIEW-006'),[first,second]=request.approvalItems;
  demo.decide(request,second.id,'approve',approval(second),actor,request.revision);
  demo.decide(request,first.id,'supplement',{reason:'请明确商品状态'},people.users.find(user=>user.id===first.reviewer),request.revision);
  const effective=JSON.stringify(second),access=JSON.stringify(request.access),applicant=people.users.find(user=>user.id===request.user);
  assert.equal(demo.resubmit(request,{reason:'核对状态',requestedScope:'在售商品目录',supplement:'只需要当前在售商品'},applicant,request.revision),undefined);
  assert.equal(JSON.stringify(second),effective);assert.equal(JSON.stringify(request.access),access);assert.equal(first.status,'待审批');
  assert.equal(request.status,'部分已生效');
});

test('joint supplement resets the complete unactivated group and never reuses a previous approval',()=>{
  const {demo,rows,people,actor}=fixture(),request=rows.find(row=>row.mode==='joint'),[first,second]=request.approvalItems;
  demo.decide(request,first.id,'approve',approval(first),actor,request.revision);
  demo.decide(request,second.id,'supplement',{reason:'补充对账日期'},people.users.find(user=>user.id===second.reviewer),request.revision);
  assert.equal(first.status,'已批准');
  assert.equal(demo.resubmit(request,{reason:'联合对账',requestedScope:'十月销售及回款',supplement:'两源均使用十月日期'},people.users.find(user=>user.id===request.user),request.revision),undefined);
  assert.ok(request.approvalItems.every(part=>part.status==='待审批'&&!part.decision));assert.equal(request.access.length,0);
});

test('manual split and joint membership changes reroute without granting and freeze after any decision',()=>{
  const {demo,rows,actor}=fixture(),request=rows.find(row=>row.id==='SQ-REVIEW-007'),initial=request.approvalItems[0].id;
  const data={sourceIds:['retail','finance'],mode:'joint',basis:'同一业务对账必须联合使用'};
  assert.equal(demo.restructure(request,data,actor,request.revision),undefined);
  assert.equal(request.mode,'joint');assert.equal(request.approvalItems.length,2);assert.equal(request.access.length,0);
  assert.notEqual(request.approvalItems[0].id,initial);assert.notEqual(request.approvalItems[0].id,request.approvalItems[1].id);
  const first=request.approvalItems[0];first.rules=fixture().people.outputRules.slice(0,1);
  demo.decide(request,first.id,'approve',approval(first),actor,request.revision);
  assert.match(demo.restructure(request,{...data,mode:'independent'},actor,request.revision),/办理结论/);
  assert.equal(request.access.length,0);assert.equal(first.status,'已批准');
});

test('structure rejects unauthorized, duplicate, stale, unchanged and single-item joint edits atomically',()=>{
  const {demo,rows,people,actor}=fixture(),request=rows.find(row=>row.id==='SQ-REVIEW-003'),before=JSON.stringify(request),base={sourceIds:['retail','products'],mode:'independent',basis:'两个独立工作'};
  assert.match(demo.restructure(request,base,people.users[3],request.revision),/权限/);
  assert.match(demo.restructure(request,base,actor,0),/修订/);
  assert.match(demo.restructure(request,{...base,sourceIds:['retail','retail']},actor,request.revision),/不重复/);
  assert.match(demo.restructure(request,{...base,sourceIds:['retail'],mode:'joint'},actor,request.revision),/至少/);
  assert.match(demo.restructure(request,{...base,sourceIds:['retail']},actor,request.revision),/没有变化/);
  assert.equal(JSON.stringify(request),before);
});
