const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context); Object.assign(context, context.window);
  }
  const data = context.DataSourceDemo, gov = context.GovernanceDemo, actor = context.PeopleDemo.users[0];
  return { data, gov, actor, impacts: context.GovernanceImpactDemo, record: gov.get(data.objects('retail').find(table => table.name === 'product')) };
}
test('AI regeneration keeps confirmed definitions and reviewer facts separate', () => {
  const { gov, record, actor } = fixture(), entry = record.items.find(entry=>entry.id==='field:id'), before = JSON.stringify(entry.content), reviewer = entry.reviewer;
  gov.generate(record, ['fields'], actor,entry.id); gov.generate(record, ['fields'], actor,entry.id);
  assert.equal(JSON.stringify(entry.content), before); assert.equal(entry.reviewer, reviewer);
  assert.match(gov.act(record, entry, 'adopt', actor), /已确认/);
});
test('adoption and saving never silently confirm an unknown field', () => {
  const { gov, record, actor } = fixture(), entry = record.items.find(entry => entry.id === 'field:code');
  gov.generate(record, ['fields'], actor); gov.act(record, entry, 'adopt', actor);
  assert.equal(entry.review, '待确认'); assert.ok(entry.gap); assert.match(gov.act(record, entry, 'confirm', actor), /补齐/);
  const original = JSON.stringify(entry.ai.content);
  gov.act(record, entry, 'save', actor, { meaning: '商品业务编码', values: '业务系统分配', usage: '不等同数据库主键', basis: '业务编码制度第 2 条', gap: '' });
  assert.equal(entry.review, '待确认'); assert.equal(JSON.stringify(entry.ai.content), original);
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined); assert.equal(entry.review, '已确认');
  assert.equal(entry.reviewer, actor.name); assert.ok(entry.history.some(event => event.action === '修改前内容'));
  assert.equal(record.table.fields.find(field => field.name === 'code').meaning, '商品业务编码');
  assert.equal(gov.summary(record).state, '待复核');
});
test('editing confirmed content removes its old confirmation and preserves the prior definition', () => {
  const { gov, record, actor } = fixture(), entry = record.items[0], before = entry.content.meaning;
  gov.act(record, entry, 'save', actor, { meaning: '商品当前档案', values: '每条为一个商品', usage: '不用于历史回溯', basis: '商品制度', gap: '' });
  assert.equal(entry.review, '待确认'); assert.equal(entry.reviewer, '');
  assert.ok(entry.history.some(event => event.content.meaning === before));
});
test('single-entry generation and source identity do not spill into adjacent objects', () => {
  const { data, gov, actor, record } = fixture(), other = gov.get(data.objects('products').find(table => table.name === 'product'));
  gov.generate(record, ['fields'], actor, 'field:code');
  assert.ok(record.items.find(entry => entry.id === 'field:code').ai);
  assert.equal(record.items.find(entry => entry.id === 'field:status').ai, null);
  assert.ok(other.items.every(entry => !entry.ai));
});
test('item review directly advances a change without a second result confirmation and retains prohibition', () => {
  const { data, gov, actor, impacts } = fixture(), change = data.changes.find(change => change.id === 'CH-015'), impact = impacts.forChange(change);
  assert.equal(impact.state,'待治理');
  const record = gov.get(gov.impactTarget(impact).table), entry = record.items.find(entry => entry.id === 'field:quantity');
  impacts.reviewed(impact,actor); assert.equal(impact.state,'待治理');
  assert.match(impacts.dismiss(impact,'跳过不兼容变化',actor,impacts.token(impact)),/不兼容/);
  gov.act(record, entry, 'save', actor, { meaning: '销售数量', values: '允许小数，计量单位随商品核对', usage: '不可混合不同单位', basis: '来源类型和数量制度', gap: '' });
  assert.equal(impact.state,'处理中');
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined); assert.equal(gov.impactReady(impact), true);
  assert.equal(impact.state, '待知识更新'); assert.equal(entry.blocked, true); assert.equal(change.control, '已禁止使用');
  assert.ok(!gov.generateDraft(record, actor).draft.items.some(item => item.id === entry.id));
});

test('no business change is decided in the table detail and leaves unrelated unknown fields pending',()=>{
  const {data,gov,actor,impacts,record}=fixture(),impact=impacts.forChange(data.changes.find(change=>change.id==='CH-011'));
  const before=JSON.stringify(record.items[0].content),code=record.items.find(entry=>entry.id==='field:code');
  assert.equal(record.items[0].review,'待确认');
  assert.equal(impacts.dismiss(impact,'仅补充来源系统名称，业务含义未变',actor,impacts.token(impact)),undefined);
  assert.equal(impact.state,'已完成');assert.equal(impact.knowledge,'无需更新');
  assert.equal(JSON.stringify(record.items[0].content),before);assert.equal(record.items[0].review,'已确认');
  assert.equal(code.review,'待确认');assert.equal(gov.summary(record).state,'部分确认');
});

test('editing and AI generation do not grant review rights after the page merge',()=>{
  const {gov,record,actor}=fixture(),editor={...actor,roles:['数据治理员']},reviewer={...actor,roles:['知识审核员']};
  const entry=record.items.find(entry=>entry.id==='field:code');
  assert.equal(gov.generate(record,['fields'],editor,entry.id),undefined);
  assert.equal(gov.act(record,entry,'save',editor,{meaning:'商品编码',values:'编码制度分配',usage:'限定当前源',basis:'商品编码制度',gap:''}),undefined);
  assert.match(gov.act(record,entry,'confirm',editor),/权限/);
  assert.match(gov.act(record,entry,'save',reviewer,{}),/权限/);
  assert.equal(gov.act(record,entry,'confirm',reviewer),undefined);
});

test('unknown definitions and modified content cannot be closed as requiring no changes',()=>{
  const {data,gov,actor,impacts,record}=fixture(),impact=impacts.forChange(data.changes.find(change=>change.id==='CH-011'));
  const revision=impacts.token(impact);
  gov.act(record,record.items[0],'save',actor,{meaning:'新的商品业务用途',values:'新粒度',usage:'新条件',basis:'新版制度',gap:''});
  assert.match(impacts.dismiss(impact,'旧窗口提交',actor,revision),/已更新/);
  assert.match(impacts.dismiss(impact,'不再处理',actor,impacts.token(impact)),/已修改/);
  assert.equal(impact.state,'处理中');
  gov.act(record,record.items[0],'confirm',actor); assert.equal(impact.state,'待知识更新');
});

test('business note changes keep source snapshots and finish only after all associated entries are reviewed',()=>{
  const {data,gov,actor,impacts,record}=fixture(),note=data.notes[0];
  const impact=impacts.saveNote(note,{...note,content:'销售与商品编码规则需要重新核对。'},actor,data.noteToken(note)).impact;
  const targets=[{table:record.table.id,entry:'field:code'},{table:record.table.id,entry:'field:status'}];
  assert.equal(gov.associate(impact,targets,actor,impacts.token(impact)),undefined);
  assert.equal(impact.bindings.length,2);
  const code=record.items.find(entry=>entry.id==='field:code'),status=record.items.find(entry=>entry.id==='field:status');
  gov.act(record,code,'save',actor,{meaning:'商品业务编码',values:'由业务系统分配',usage:'限当前源',basis:'补充资料',gap:''});
  gov.act(record,code,'confirm',actor);assert.equal(impact.state,'处理中');
  gov.act(record,status,'save',actor,{meaning:'商品当前状态',values:'由已核定制度解释',usage:'历史订单另行定义',basis:'补充状态制度',gap:''});
  gov.act(record,status,'confirm',actor);assert.equal(impact.state,'待知识更新');
  assert.ok(impact.after.includes('商品编码'));assert.equal(impact.noteRevision,note.revision);
});

test('read-only and cross-source selections cannot bind a note change',()=>{
  const {data,gov,actor,impacts,record}=fixture(),note=data.notes[0];
  const impact=impacts.saveNote(note,{...note,content:'新增说明'},actor,data.noteToken(note)).impact;
  const reader={...actor,roles:['普通用户']};
  assert.match(gov.associate(impact,[{table:record.table.id,entry:'overview'}],reader,impacts.token(impact)),/权限/);
  const other=data.objects('products')[0];
  assert.match(gov.associate(impact,[{table:other.id,entry:'overview'}],actor,impacts.token(impact)),/当前数据源/);
  assert.equal(impact.bindings.length,0);
  assert.match(impacts.dismiss(impact,'无影响',reader,impacts.token(impact)),/权限/);
});

test('one current-definition review updates overlapping changes to the same entry',()=>{
  const {data,gov,actor,impacts}=fixture(),change=data.changes.find(change=>change.id==='CH-012');
  impacts.verify(change,{decision:'确认变化',reason:'确认目录权限变化'},actor,0);
  const impact=impacts.forChange(change),record=gov.get(gov.impactTarget(impact).table);
  const related=impacts.records.filter(item=>item.bindings.some(binding=>binding.table===record.table.id&&binding.entry==='overview'));
  assert.ok(related.length>1);
  assert.equal(impacts.dismiss(impact,'核对当前用途与相关来源变化，业务含义无需修改',actor,impacts.token(impact)),undefined);
  assert.ok(related.every(item=>item.state==='已完成'));
});
test('knowledge draft freezes only confirmed usable revisions without publishing', () => {
  const { gov, record, actor } = fixture(), { draft } = gov.generateDraft(record, actor), before = draft.items[0].content.meaning;
  assert.ok(draft.items.length); assert.ok(!draft.items.some(entry => entry.id === 'field:status'));
  record.items[0].content.meaning = '后续修改'; assert.equal(draft.items[0].content.meaning, before);
  assert.ok(record.items.every(entry => entry.publication === '未发布'));
});
test('no-edit and no-review roles cannot mutate labels or generate knowledge', () => {
  const { gov, record, actor } = fixture(), reader = { ...actor, roles: ['普通用户'] }, before = JSON.stringify(record);
  assert.match(gov.generate(record, ['fields'], reader), /权限/);
  assert.match(gov.act(record, record.items[0], 'confirm', reader), /权限/);
  assert.match(gov.act(record, record.items[0], 'save', reader, {}), /权限/);
  assert.match(gov.generateDraft(record, reader).error, /权限/); assert.equal(JSON.stringify(record), before);
});
