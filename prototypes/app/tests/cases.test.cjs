'use strict';
const assert=require('node:assert/strict'),test=require('node:test');
global.window={};require('../dialogue.js');require('../cases.js');
const origin=(turnId,threadId='business')=>({threadId,turnId});
const create=saved=>window.QueryCases.create(saved,()=>Date.UTC(2026,10,8,9));
test('毛利汇总不再被门店授权模拟拦截，输出不含成本或销售净额',()=>{
  const desk=create(),reply=desk.ask('城南店上周毛利额是多少？',origin(1));
  assert.equal(reply.kind,'answer');assert.equal(reply.result.value,4620);
  assert.equal(desk.canView(reply.result),true);assert.equal(desk.list().length,0);
  assert.doesNotMatch(JSON.stringify(reply.result.rows),/18460|13840/);
  assert.deepEqual(reply.result.headings,['门店','毛利额（元）']);
});
test('成本问题展示规则阻断，不能通过模拟审批或期限解除',()=>{
  const desk=create(),reply=desk.ask('城南店上周的产品成本是多少？',origin(1)),record=desk.get(reply.caseId);
  assert.equal(record.status,'blocked');assert.match(record.summary,/产品成本限制/);
  assert.equal(desk.apply,undefined);assert.equal(desk.decide,undefined);
  assert.equal(desk.simulate(record.id,'activate').ok,false);
  assert.equal(record.term,undefined);assert.equal(record.expiresAt,undefined);
});
test('成本仅用于条件或询问定义时，不因关键词出现就判定输出受限',()=>{
  const desk=create();
  assert.equal(desk.ask('按产品成本大于10元筛选商品，只返回商品名称',origin(1)),null);
  assert.equal(desk.ask('产品成本是什么意思？',origin(2)),null);
  assert.equal(desk.list().length,0);
});
test('口径缺口合并原问，复验通过前不交付，回告仅一条',()=>{
  const desk=create(),question='城南店上周牛奶的退货率是多少？',first=desk.ask(question,origin(1));
  assert.equal(desk.ask(question,origin(2,'other')).caseId,first.caseId);assert.equal(desk.get(first.caseId).links.length,2);
  assert.equal(desk.supplement(first.caseId,' ').ok,false);assert.equal(desk.supplement(first.caseId,'核对跨日退货').record.notes.length,1);
  assert.equal(desk.simulate(first.caseId,'verify').ok,false);desk.simulate(first.caseId,'publish');desk.simulate(first.caseId,'fail');
  assert.equal(desk.get(first.caseId).status,'processing');desk.simulate(first.caseId,'publish');desk.simulate(first.caseId,'verify');
  assert.equal(desk.list().filter(item=>item.receipt).length,1);assert.deepEqual(desk.ask(question,origin(1)),first);
  const reply=desk.ask(question,origin(3));assert.equal(reply.kind,'answer');assert.deepEqual(reply.result.totals,['合计','1,200','80','6.67%']);
});
test('当前快照可恢复，旧门店授权快照不进入当前模型',()=>{
  const desk=create(),first=desk.ask('城南店上周牛奶退货率是多少？',origin(1));
  assert.equal(create(desk.snapshot()).get(first.caseId).status,'processing');
  assert.equal(create({version:1,records:[{type:'access',status:'effective'}]}).list().length,0);
});
test('不替其他门店、时段或分组编造示例结果',()=>{
  const desk=create();for(const question of ['城北店上周毛利额','城南店本周毛利额','城南店上周毛利额按天列','上周酸奶退货率']){
    const reply=desk.ask(question,origin(1));assert.equal(reply.kind,'notice');assert.equal(reply.result,undefined);
  }assert.equal(desk.list().length,0);
});
