const { test } = require('node:test');
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'access-request-data.js', 'audit-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context);
    Object.assign(context, context.window);
  }
  return { people: context.PeopleDemo, audit: context.AuditDemo, requests: context.AccessRequestDemo, actor: context.PeopleDemo.users[0] };
}
test('audit filters intersect actor, target, action, result and inclusive business dates', () => {
  const { audit, actor } = fixture();
  const query = { actor: 'lin.admin', target: 'chen.zhuguan', action: '批准申请', status: '成功', fromDate: '2026-11-10', toDate: '2026-11-10' };
  const rows = audit.records(actor, query);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].requestId, 'SQ-REVIEW-012');
  assert.equal(audit.records(actor, { ...query, actor: 'chen.zhuguan' }).length, 0);
  assert.equal(audit.records(actor, { ...query, target: '系统' }).length, 0);
  assert.equal(audit.records(actor, { ...query, toDate: '2026-11-09' }).length, 0);
});
test('unavailable accounts and accounts without audit visibility cannot read records or details', () => {
  const { audit, actor, people } = fixture(), id = audit.records(actor)[0].id;
  for (const user of [people.users[3], { ...actor, status: '停用' }]) {
    assert.equal(audit.canView(user), false);
    assert.equal(audit.records(user).length, 0);
    assert.equal(audit.get(id, user), undefined);
  }
});
test('historical identities and changes do not follow later personnel edits', () => {
  const { audit, actor, people } = fixture();
  const original = audit.records(actor, { action: '创建账号' })[0];
  actor.name = '改名后的管理员'; actor.login = 'changed.admin'; people.users[4].name = '改名后的成员';
  const row = audit.get(original.id, actor);
  assert.equal(row.actor.name, '林管理员'); assert.equal(row.actor.login, 'lin.admin');
  assert.equal(row.target.name, '吴同事');
  assert.equal(audit.records(actor, { actor: 'changed.admin' }).length, 0);
});
test('one approval audit contains both the decision and effective access under the actual approver', () => {
  const { audit, actor } = fixture();
  for (const requestId of ['SQ-REVIEW-012','SQ-DEMO-022']) {
    const rows = audit.records(actor).filter(row => row.requestId === requestId);
    assert.equal(rows.length,1);
    const approval=rows[0];
    assert.equal(approval.action,'批准申请'); assert.equal(approval.actor.name,'林管理员');
    assert.equal(approval.result,'成功'); assert.equal(approval.effect,'已生效');
    assert.ok(approval.changes.some(row=>row.after==='已批准'));
    assert.ok(approval.changes.some(row=>row.after==='已生效'));
  }
  assert.ok(!audit.records(actor).some(row=>row.actor.name==='系统'||row.action==='自动生效'));
  const denied = audit.records(actor, { status: '已拒绝' })[0];
  assert.equal(denied.changes.length, 0);
});

test('approving from the application adds exactly one effective audit and repeated approval adds nothing',()=>{
  const { audit, requests, actor }=fixture(), request=requests.records().find(row=>row.id==='SQ-REVIEW-003'), part=request.approvalItems[0];
  const data={access:[{source:part.source,rules:structuredClone(part.rules)}],reason:'核定经营汇总'};
  const before = audit.records(actor).length;
  assert.equal(typeof requests.decide(request,part.id,'approve',{access:[{source:part.source,rules:[]}],reason:''},actor,request.revision),'string');
  assert.equal(request.status,'待审批'); assert.equal(audit.records(actor).length,before);
  assert.equal(requests.decide(request,part.id,'approve',data,actor,request.revision),undefined);
  assert.equal(request.status,'已生效');
  const logs=audit.records(actor).filter(row=>row.requestId===request.id);
  assert.equal(logs.length,1); assert.equal(logs[0].effect,'已生效'); assert.equal(logs[0].actor.login,actor.login);
  assert.equal(typeof requests.decide(request,part.id,'approve',data,actor,request.revision),'string');
  assert.equal(audit.records(actor).filter(row=>row.requestId===request.id).length,1);
});

test('joint item decisions remain approvals until the final required approval makes the group effective',()=>{
  const { audit, requests, actor, people }=fixture(), request=requests.records().find(row=>row.id==='SQ-REVIEW-004'), [first,second]=request.approvalItems;
  const data = part => ({access:[{source:part.source,rules:structuredClone(part.rules)}],reason:''});
  requests.decide(request,first.id,'approve',data(first),actor,request.revision);
  let logs=audit.records(actor).filter(row=>row.requestId===request.id);
  assert.equal(logs.length,1); assert.equal(logs[0].action,'通过子项审批'); assert.equal(logs[0].effect,'申请待其余审批');
  assert.equal(requests.effectiveAccess(request).length,0);
  requests.decide(request,second.id,'approve',data(second),people.users.find(user=>user.id===second.reviewer),request.revision);
  logs=audit.records(actor).filter(row=>row.requestId===request.id);
  assert.equal(logs.length,2); assert.equal(logs.filter(row=>row.effect==='已生效').length,1);
  assert.equal(logs.find(row=>row.effect==='已生效').changes.filter(row=>row.after==='已生效').length,2);
  assert.equal(requests.effectiveAccess(request).length,2);
  assert.ok(request.approvalItems.every(part=>part.activatedAt===second.reviewedAt));
});
