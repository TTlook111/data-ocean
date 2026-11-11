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
test('approval records a pending execution result without adding data access or changing organization', () => {
  const { people, demo, rows, actor } = fixture(), request = rows.find(value=>value.id==='SQ-REVIEW-003'), part = request.approvalItems[0];
  const before = JSON.stringify({ users: people.users, departments: people.departments, access: request.access });
  assert.equal(demo.decide(request, part.id, 'approve', approval(part), actor, 1), undefined);
  assert.equal(request.status, '已批准待执行');
  assert.equal(JSON.stringify({ users: people.users, departments: people.departments, access: request.access }), before);
  assert.equal(request.history.length, 1);
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
  assert.equal(request.status, '待审批');
  demo.decide(request, second.id, 'reject', { reason: '第二项用途不足' }, actor, 2);
  assert.equal(request.status, '部分批准待执行');
  assert.equal(first.status, '已批准待执行');
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
  assert.equal(request.status,'已批准待执行'); assert.equal(request.access.length,0);
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
  assert.equal(request.status,'待审批');
  assert.ok(demo.inbox(actor,'done').includes(request));
  assert.ok(!demo.inbox(actor,'pending').includes(request));
  assert.equal(demo.partsFor(request,actor,'done').length,1);
  assert.ok(demo.inbox(people.users.find(user=>user.id==='u09'),'pending').includes(request));
  assert.equal(request.access.length,0);
});

test('unchanged responsibility never increments revisions or appends routing history', () => {
  const { people, routing, rows, actor }=fixture(), source=people.dataSources[0];
  const before=JSON.stringify({source,rows});
  assert.match(routing.save(source,{primary:source.approval.primary,alternate:source.approval.alternate},actor,source.approval.revision),/未变化/);
  assert.equal(JSON.stringify({source,rows}),before);
});
