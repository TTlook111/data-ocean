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

test('table progress covers only overview and fields while change impact is independent', () => {
  const { gov, record, actor } = fixture();
  for (const entry of record.items.filter(entry => ['overview', 'fields'].includes(entry.kind))) {
    if (entry.gap) gov.act(record, entry, 'save', actor, { ...entry.content, meaning: entry.name + '的业务含义', basis: '已核对业务制度', gap: '' });
    assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  }
  const complete = gov.summary(record);
  assert.equal(complete.state, '已治理');
  assert.equal(complete.confirmed, complete.total);
  assert.equal(complete.affected, 0);
  assert.ok(record.items.some(entry => entry.kind === 'relations' && entry.review === '待确认'));
  const entry = record.items.find(entry => entry.kind === 'fields');
  gov.act(record, entry, 'save', actor, { ...entry.content, meaning: '修订后的业务含义', basis: '新版制度', gap: '' });
  assert.equal(gov.summary(record).state, '治理中');
  assert.equal(gov.summary(record).affected, 0);
});

test('relation-only changes do not reset completed overview and field progress', () => {
  const { data, gov, record, actor, impacts } = fixture();
  for (const entry of record.items.filter(entry => ['overview', 'fields'].includes(entry.kind))) {
    if (entry.gap) gov.act(record, entry, 'save', actor, { ...entry.content, meaning: entry.name + '的业务含义', basis: '业务制度', gap: '' });
    gov.act(record, entry, 'confirm', actor);
  }
  const note = data.notes[0], relation = record.items.find(entry => entry.kind === 'relations');
  const impact = impacts.saveNote(note, { ...note, content: '关联条件需要核对。' }, actor, data.noteToken(note)).impact;
  assert.equal(gov.associate(impact, [{ table: record.table.id, entry: relation.id }], actor, impacts.token(impact)), undefined);
  assert.equal(gov.summary(record).state, '已治理');
  assert.equal(gov.summary(record).affected, 1);
  assert.equal(record.table.governance, '已治理');
  assert.equal(record.table.governanceAffected, true);
  const counts = data.governanceSummary(record.table.source);
  assert.equal(counts.total, counts.confirmed + counts.partial + counts.pending);
  assert.ok(counts.affected > 0);
});

test('verified source changes need business review without becoming a progress status', () => {
  const { data, gov, actor, impacts } = fixture(), change = data.changes.find(change => change.id === 'CH-015');
  const impact = impacts.forChange(change), record = gov.get(gov.impactTarget(impact).table);
  const before = JSON.stringify([change.fact, change.factSource, change.verifications]);
  assert.equal(change.fact, '已确认');
  assert.equal(gov.summary(record).state, '治理中');
  assert.ok(gov.summary(record).affected > 0);
  const entry = record.items.find(entry => entry.id === 'field:quantity');
  gov.act(record, entry, 'save', actor, { meaning: '销售数量', values: '可为小数', usage: '按商品单位汇总', basis: '数量制度', gap: '' });
  gov.act(record, entry, 'confirm', actor);
  assert.equal(gov.summary(record).affected, 0);
  assert.equal(impact.state, '知识处理中');
  assert.equal(JSON.stringify([change.fact, change.factSource, change.verifications]), before);
});

test('generation automatically records same-source effective revisions without confirming the suggested content', () => {
  const { data, gov, record, actor } = fixture(), before = data.noteUses.length;
  data.notes.push({ ...structuredClone(data.notes[0]), id: 'OTHER-SOURCE', source: 'finance' });
  const expected = data.notes.filter(note => note.source === record.table.source && note.status === '有效');
  assert.equal(gov.generate(record, ['fields'], actor, 'field:code'), undefined);
  const task = gov.tasksFor(record)[0], uses = data.noteUses.filter(use => use.taskId === task.id);
  assert.equal(task.notes.length, expected.length);
  assert.equal(uses.length, expected.length);
  assert.equal(data.noteUses.length - before, expected.length);
  assert.ok(!task.notes.some(note => note.id === 'OTHER-SOURCE' || note.id === 'ZL-001'));
  assert.ok(uses.every(use => use.kind === '治理任务' && use.actor === actor.id));
  assert.equal(task.entries.length, 1);
  assert.equal(task.outputs[0].id, 'field:code');
  assert.equal(record.items.find(entry => entry.id === 'field:code').review, '待确认');
  assert.ok(data.noteDeleteBlock(expected[3]));
});

test('task snapshots survive edits and withdrawal while new tasks take the latest effective revisions', () => {
  const { data, gov, record, actor, impacts } = fixture(), note = data.notes[0];
  gov.generate(record, ['overview'], actor);
  const first = gov.tasksFor(record)[0], frozen = JSON.stringify(first.notes), original = note.content;
  impacts.saveNote(note, { ...note, content: original + ' 新补充规则。' }, actor, data.noteToken(note));
  gov.generate(record, ['overview'], actor);
  const second = gov.tasksFor(record)[0];
  assert.notEqual(first.id, second.id);
  assert.equal(first.notes.find(item => item.id === note.id).revision, 1);
  assert.equal(second.notes.find(item => item.id === note.id).revision, 2);
  assert.equal(JSON.stringify(first.notes), frozen);
  data.changeNote(note, '撤下', { reason: '暂停用于后续治理' }, actor, data.noteToken(note));
  gov.generate(record, ['overview'], actor);
  assert.ok(!gov.tasksFor(record)[0].notes.some(item => item.id === note.id));
  assert.equal(data.noteUsesFor(note).filter(use => use.taskId).length, 2);
  assert.equal(JSON.stringify(first.notes), frozen);
});

test('confirmation inherits adopted sources rather than subsequently regenerated suggestions', () => {
  const { data, gov, record, actor } = fixture(), entry = record.items.find(entry => entry.id === 'field:code');
  gov.generate(record, ['fields'], actor, entry.id);
  const adoptedTask = gov.tasksFor(record)[0];
  gov.act(record, entry, 'adopt', actor);
  data.notes[0].revision++;
  gov.generate(record, ['fields'], actor, entry.id);
  gov.act(record, entry, 'save', actor, { meaning: '商品业务编码', values: '来源系统分配', usage: '按确认编码规则使用', basis: '已核对业务资料', gap: '' });
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  const uses = data.noteUses.filter(use => use.kind === '治理成果' && use.table === record.table.id && use.entryId === entry.id);
  assert.equal(uses.length, adoptedTask.notes.length);
  assert.ok(uses.every(use => use.taskId === adoptedTask.id));
  assert.ok(adoptedTask.events.some(event => event.action === '采用建议，保留待确认' && event.actor === actor.name));
  assert.ok(adoptedTask.events.some(event => event.action === '人工确认治理内容' && event.entryId === entry.id));
  assert.equal(uses.find(use => use.noteId === data.notes[0].id).revision, 1);
  const count = data.noteUses.length;
  gov.act(record, entry, 'confirm', actor);
  assert.equal(data.noteUses.length, count);
});

test('denied and empty generations create no references; missing source notes do not prevent unresolved suggestions', () => {
  const { data, gov, record, actor } = fixture(), before = data.noteUses.length;
  assert.ok(gov.generate(record, ['fields'], { ...actor, roles: ['普通用户'] }));
  assert.ok(gov.generate(record, [], actor));
  assert.ok(gov.generate(record, ['fields'], actor, 'unknown'));
  assert.equal(gov.tasks.length, 0);
  assert.equal(data.noteUses.length, before);
  const other = gov.get(data.objects('products')[0]);
  assert.equal(gov.generate(other, ['fields'], actor), undefined);
  assert.equal(gov.tasksFor(other)[0].notes.length, 0);
  assert.equal(data.noteUses.length, before);
  assert.ok(other.items.some(entry => entry.gap));
});

test('unverified changes block governance of that table until every fact is resolved', () => {
  const { data, gov, actor, impacts, record: other } = fixture();
  const table = data.objects('retail').find(table => table.name === 'supplier'), record = gov.get(table);
  const first = data.changesFor(table).find(change => change.fact === '待核实');
  const second = { ...structuredClone(first), id: 'CH-TEST', field: 'code' }; data.changes.push(second);
  const entry = record.items[0], before = JSON.stringify(record);
  assert.match(gov.generate(record, ['overview'], actor), /待核实/);
  assert.match(gov.act(record, entry, 'save', actor, entry.content), /待核实/);
  assert.match(gov.act(record, entry, 'confirm', actor), /待核实/);
  assert.match(gov.generateDraft(record, actor).error, /待核实/);
  assert.equal(JSON.stringify(record), before);
  assert.equal(gov.generate(other, ['overview'], actor), undefined);
  assert.equal(impacts.verify(first, { decision: '对象仍存在', reason: '源端对象仍存在' }, actor, 0), undefined);
  assert.match(gov.generate(record, ['overview'], actor), /待核实/);
  assert.equal(impacts.verify(second, { decision: '对象仍存在', reason: '字段存在，目录账号权限异常' }, actor, 0), undefined);
  assert.equal(gov.generate(record, ['overview'], actor), undefined);
});
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
  assert.equal(gov.summary(record).state, '治理中');
});

test('confirmation availability uses the same missing-content and dependency checks as submission', () => {
  const { gov, record, actor } = fixture(), entry = record.items.find(entry => entry.id === 'field:code');
  entry.content.meaning = '   '; entry.gap = '';
  assert.match(gov.confirmIssue(record, entry), /业务含义/);
  assert.equal(gov.act(record, entry, 'confirm', actor), gov.confirmIssue(record, entry));
  entry.content.meaning = '商品业务编码'; entry.gap = '编码规则尚未核清';
  assert.match(gov.confirmIssue(record, entry), /待确认问题/);
  entry.gap = ''; entry.dependencies = ['field:status'];
  assert.match(gov.confirmIssue(record, entry), /依赖字段/);
  assert.equal(gov.act(record, entry, 'confirm', actor), gov.confirmIssue(record, entry));
  const dependency = record.items.find(item => item.id === 'field:status'); dependency.review = '已确认'; dependency.blocked = true;
  assert.match(gov.confirmIssue(record, entry), /禁止使用/);
  dependency.blocked = false;
  assert.equal(gov.confirmIssue(record, entry), '');
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
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
  const sourceFacts = JSON.stringify([change.fact, change.factSource, change.verifications]);
  gov.act(record, entry, 'save', actor, { meaning: '销售数量', values: '允许小数，计量单位随商品核对', usage: '不可混合不同单位', basis: '来源类型和数量制度', gap: '' });
  assert.equal(impact.state,'治理中');
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined); assert.equal(gov.impactReady(impact), true);
  assert.equal(impact.state, '知识处理中'); assert.equal(entry.blocked, true); assert.equal(change.control, '已禁止使用');
  assert.equal(JSON.stringify([change.fact, change.factSource, change.verifications]), sourceFacts);
  assert.ok(!gov.generateDraft(record, actor).draft.items.some(item => item.id === entry.id));
});

test('reviewing existing business content completes the source progress without a separate closure',()=>{
  const {data,gov,actor,impacts,record}=fixture(),impact=impacts.forChange(data.changes.find(change=>change.id==='CH-011'));
  const before=JSON.stringify(record.items[0].content),code=record.items.find(entry=>entry.id==='field:code');
  assert.equal(record.items[0].review,'待确认');
  assert.equal(gov.act(record,record.items[0],'confirm',actor),undefined);
  assert.equal(impact.state,'已完成');assert.equal(impact.knowledge,'无需更新');
  assert.equal(JSON.stringify(record.items[0].content),before);assert.equal(record.items[0].review,'已确认');
  assert.equal(code.review,'待确认');assert.equal(gov.summary(record).state,'治理中');
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

test('changed business content remains pending until its own review and then waits for knowledge',()=>{
  const {data,gov,actor,impacts,record}=fixture(),impact=impacts.forChange(data.changes.find(change=>change.id==='CH-011'));
  gov.act(record,record.items[0],'save',actor,{meaning:'新的商品业务用途',values:'新粒度',usage:'新条件',basis:'新版制度',gap:''});
  assert.equal(impact.state,'治理中');
  gov.act(record,record.items[0],'confirm',actor); assert.equal(impact.state,'知识处理中');
});

test('business note changes keep source snapshots and finish only after all associated entries are reviewed',()=>{
  const {data,gov,actor,impacts,record}=fixture(),note=data.notes[0];
  const impact=impacts.saveNote(note,{...note,content:'销售与商品编码规则需要重新核对。'},actor,data.noteToken(note)).impact;
  const targets=[{table:record.table.id,entry:'field:code'},{table:record.table.id,entry:'field:status'}];
  assert.equal(gov.associate(impact,targets,actor,impacts.token(impact)),undefined);
  assert.equal(impact.bindings.length,2);
  const code=record.items.find(entry=>entry.id==='field:code'),status=record.items.find(entry=>entry.id==='field:status');
  gov.act(record,code,'save',actor,{meaning:'商品业务编码',values:'由业务系统分配',usage:'限当前源',basis:'补充资料',gap:''});
  gov.act(record,code,'confirm',actor);assert.equal(impact.state,'治理中');
  gov.act(record,status,'save',actor,{meaning:'商品当前状态',values:'由已核定制度解释',usage:'历史订单另行定义',basis:'补充状态制度',gap:''});
  gov.act(record,status,'confirm',actor);assert.equal(impact.state,'知识处理中');
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
});

test('one current-definition review updates overlapping changes to the same entry',()=>{
  const {data,gov,actor,impacts}=fixture(),source=data.changes.find(change=>change.id==='CH-012');
  const change={...structuredClone(source),id:'CH-COMMENT',kind:'修改注释',attribute:'表注释',before:'供应商',after:'供应商档案',fact:'待核实'};
  impacts.verify(source,{decision:'对象仍存在',reason:'原目录访问问题已恢复'},actor,0);
  data.changes.push(change);
  impacts.verify(change,{decision:'确认变化',reason:'来源注释变化已核实'},actor,0);
  const impact=impacts.forChange(change),record=gov.get(gov.impactTarget(impact).table);
  const related=impacts.records.filter(item=>item.bindings.some(binding=>binding.table===record.table.id&&binding.entry==='overview'));
  assert.ok(related.length>1);
  assert.equal(gov.act(record,record.items[0],'confirm',actor),undefined);
  assert.ok(related.every(item=>item.state==='已完成'));
});

test('confirmed visibility changes preserve business definitions and source restrictions',()=>{
  const {data,gov,actor,impacts}=fixture(),change=data.changes.find(change=>change.id==='CH-012');
  const record=gov.get(data.objects('retail').find(table=>table.name==='supplier'));
  const before=JSON.stringify(record.items),control=change.control;
  assert.equal(impacts.verify(change,{decision:'对象仍存在',reason:'目录读取权限变化，表定义未变'},actor,0),undefined);
  assert.equal(JSON.stringify(record.items),before);
  assert.equal(impacts.progress(change),'已完成');
  assert.equal(change.control,control);
  assert.equal(change.verifications.length,1);
});
test('an unchanged definition can be reviewed directly without manufacturing a missing-information task',()=>{
  const {data,gov,actor,impacts}=fixture(),change=data.changes.find(change=>change.id==='CH-015');
  const impact=impacts.forChange(change),record=gov.get(gov.impactTarget(impact).table),entry=record.items.find(entry=>entry.id==='field:quantity');
  const before=JSON.stringify(entry.content);
  assert.equal(entry.gap,'');assert.equal(gov.confirmIssue(record,entry),'');
  assert.equal(gov.act(record,entry,'confirm',actor),undefined);
  assert.equal(JSON.stringify(entry.content),before);assert.equal(impact.state,'知识处理中');assert.equal(change.control,'已禁止使用');
});
test('confirmed object deletion retains collection evidence and reviews explicit removal work',()=>{
  const {data,gov,actor,impacts}=fixture(),change=data.changes.find(change=>change.id==='CH-012');
  const table=data.objects('retail').find(table=>table.name==='supplier'),before=[change.before,change.after,change.evidence,change.beforeSnapshot,change.afterSnapshot];
  assert.match(impacts.verify(change,{decision:'确认变化',reason:'含糊结论'},actor,0),/请选择核实结论/);
  assert.equal(change.fact,'待核实');assert.equal(change.verifications.length,0);
  assert.equal(impacts.verify(change,{decision:'对象已删除',reason:'来源管理员确认已删除，原权限与采集范围未变'},actor,0),undefined);
  assert.equal(change.kind,'删除对象');assert.equal(change.control,'已禁止使用');assert.equal(impacts.progress(change),'待治理');
  assert.deepEqual([change.before,change.after,change.evidence,change.beforeSnapshot,change.afterSnapshot],before);
  assert.ok(data.catalogStates(table).includes('已确认移除'));
  const impact=impacts.forChange(change),record=gov.get(table);
  assert.equal(impact.bindings.length,record.items.length);
  assert.ok(record.items.every(entry=>entry.retired&&entry.blocked&&entry.review==='待确认'));
  assert.equal(gov.act(record,record.items[0],'confirm',actor),undefined);assert.equal(impact.state,'治理中');
  for(const entry of record.items.slice(1)) assert.equal(gov.act(record,entry,'confirm',actor),undefined);
  assert.equal(impact.state,'知识处理中');assert.ok(record.items.every(entry=>entry.blocked));
  assert.ok(impacts.verify(change,{decision:'对象仍存在',reason:'过期窗口'},actor,0));
});
test('knowledge draft freezes only confirmed usable revisions without publishing', () => {
  const { gov, record, actor } = fixture(), { draft } = gov.generateDraft(record, actor), before = draft.items[0].content.meaning;
  assert.ok(draft.items.length); assert.ok(!draft.items.some(entry => entry.id === 'field:status'));
  record.items[0].content.meaning = '后续修改'; assert.equal(draft.items[0].content.meaning, before);
  assert.ok(record.items.every(entry => entry.publication === '未发布'));
});

test('changed cited notes directly mark their mapped business entries while preserving adjacent confirmations',()=>{
  const {data,gov,actor,impacts,record}=fixture(),note=data.notes[0],entry=record.items.find(entry=>entry.id==='field:code');
  gov.generate(record,['fields'],actor,entry.id); gov.act(record,entry,'adopt',actor);
  gov.act(record,entry,'save',actor,{meaning:'商品业务编码',values:'由业务制度分配',usage:'限定当前数据源',basis:'已采用资料及业务制度',gap:''});
  gov.act(record,entry,'confirm',actor);
  const stable=JSON.stringify(record.items.find(entry=>entry.id==='field:id'));
  const impact=impacts.saveNote(note,{...note,content:'商品编码制度已调整。'},actor,data.noteToken(note)).impact;
  assert.ok(impact.bindings.some(binding=>binding.table===record.table.id&&binding.entry===entry.id));
  assert.ok(gov.attention(record).affected.includes(entry));
  assert.equal(entry.review,'待确认');
  assert.equal(JSON.stringify(record.items.find(entry=>entry.id==='field:id')),stable);
});
test('no-edit and no-review roles cannot mutate labels or generate knowledge', () => {
  const { gov, record, actor } = fixture(), reader = { ...actor, roles: ['普通用户'] }, before = JSON.stringify(record);
  assert.match(gov.generate(record, ['fields'], reader), /权限/);
  assert.match(gov.act(record, record.items[0], 'confirm', reader), /权限/);
  assert.match(gov.act(record, record.items[0], 'save', reader, {}), /权限/);
  assert.match(gov.generateDraft(record, reader).error, /权限/); assert.equal(JSON.stringify(record), before);
});
