'use strict';
const assert=require('node:assert/strict'),test=require('node:test');
global.window={};require('../../后台管理/record-query.js');const{matches}=window.AdminQuery;
test('跨字段多词匹配，数据源与规则名称都能检索',()=>{
  assert.equal(matches(['经营业务库','产品成本限制','部门初始访问'],'经营 成本'),true);
  assert.equal(matches(['经营业务库','用户信息限制'],'经营 成本'),false);
});
test('全角字母编号与大小写按相同输入处理',()=>assert.equal(matches(['SQ-DEMO-022'],'ｓｑ－ｄｅｍｏ－０２２'),true));
test('正则符号按原文检索，不将用户输入当表达式',()=>{
  assert.equal(matches(['比较 A+B (C) 与 [D]'],'a+b (c)'),true);assert.equal(matches(['经营业务库'],'.*'),false);
});

test('分开的查询条件分别命中对应字段，不能从其他列凑词',()=>{
  const {matchesFields}=window.AdminQuery, values={source:'经营业务库',primary:['林管理员','lin.admin'],alternate:['陈主管','chen.zhuguan']};
  assert.equal(matchesFields(values,{source:'经营',primary:'ＬＩＮ'}),true);
  assert.equal(matchesFields(values,{source:'林管理员'}),false);
  assert.equal(matchesFields(values,{primary:'陈主管'}),false);
  assert.equal(matchesFields(values,{primary:'林',alternate:'陈'}),true);
  assert.equal(matchesFields(values,{source:'不存在'}),false);
});
