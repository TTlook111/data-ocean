const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  for (const file of ['record-query.js', 'people-data.js', 'role-data.js', 'source-approval-data.js', 'datasource-data.js', 'governance-impact-data.js', 'governance-data.js', 'governance-prototype.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理', file), 'utf8'), context); Object.assign(context, context.window);
  }
  const { DataSourceDemo: data, GovernanceDemo: gov, GovernancePrototype: prototype, PeopleDemo: people } = context;
  const actor = people.users[0], table = data.objects('products').find(row => row.name === 'sale_order'), record = gov.get(table);
  return { data, gov, prototype, actor, table, record };
}
test('prototype click fills only the requested field as an explicitly simulated pending draft', () => {
  const { gov, prototype, record, actor } = fixture(), entry = record.items.find(row => row.id === 'field:id');
  assert.equal(entry.content.meaning, '');
  assert.equal(prototype.generate(record, ['fields'], actor, entry.id, entry.revision), undefined);
  assert.ok(entry.content.meaning); assert.equal(entry.review, '待确认'); assert.equal(entry.reviewer, '');
  assert.equal(entry.ai.simulated, true); assert.match(entry.ai.sampling.sql, /SELECT `id` FROM `sale_order` LIMIT 5/);
  assert.equal(entry.ai.sampling.count, 5); assert.equal(gov.tasksFor(record)[0].simulated, true);
  assert.equal(gov.summary(record).state, '治理中'); assert.equal(gov.summary(record).confirmed, 0);
  assert.ok(record.items.filter(row => row !== entry && !row.retired).every(row => row.content.meaning === ''));
});
test('fields can be edited and confirmed before table description without an adoption or document-selection step', () => {
  const { gov, prototype, record, actor } = fixture(), entry = record.items.find(row => row.id === 'field:id');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.equal(gov.act(record, entry, 'save', actor, { ...entry.content, meaning: '本次验收修改后的订单编号', gap: '', basis: '' }), undefined);
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  assert.equal(entry.review, '已确认'); assert.equal(record.items[0].review, '待确认'); assert.equal(record.items[0].content.meaning, '');
  assert.ok(entry.history.some(event => event.action === '演示建议写入待审核草稿'));
});
test('uncertain codes stay pending until their concrete gap is resolved', () => {
  const { gov, prototype, record, actor } = fixture(), entry = record.items.find(row => row.id === 'field:status');
  prototype.generate(record, ['fields'], actor, entry.id);
  assert.match(entry.gap, /状态/); assert.match(gov.confirmIssue(record, entry), /回答 AI/);
  const model = { ...entry.content, checkContext: structuredClone(entry.checkContext), gap: entry.gap, basis: '' };
  for (const question of model.checkContext.questions) {
    question.response = question.key === 'values' ? '验收示例：0 为新建，1 为有效，2 为完成。' : '验收示例：有效记录选 1、2。';
    assert.equal(gov.confirmAnswer(model, question.key), undefined);
  }
  assert.equal(gov.act(record, entry, 'save', actor, model), undefined);
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
});

test('table suggestions sample complete rows while field suggestions retain their single column', () => {
  const { gov, prototype, record, actor } = fixture();
  prototype.generate(record, ['overview'], actor, 'overview');
  const sample = gov.tasksFor(record)[0].outputs[0].sampling;
  assert.deepEqual(Array.from(sample.columns), Array.from(record.table.fields, field => field.name));
  assert.equal(sample.count, 5); assert.equal(sample.rows.length, 5);
  assert.equal(sample.rows[0].id, 1001); assert.equal(sample.rows[0].store_id, 1);
  assert.ok(sample.rows.every(row => sample.columns.every(column => Object.hasOwn(row, column))));
  assert.match(sample.sql, /`id`, `store_id`, `code`, `status`, `updated_at`/);
  prototype.generate(record, ['fields'], actor, 'field:store_id');
  const fieldSample = gov.tasksFor(record)[0].outputs[0].sampling;
  assert.equal(fieldSample.column, 'store_id'); assert.equal(fieldSample.columns, undefined);
  assert.match(fieldSample.sql, /SELECT `store_id` FROM/);
});

test('single-item governance includes current business-note revisions before knowledge generation', () => {
  const { data, gov, prototype, record, actor } = fixture();
  data.notes.unshift({ id: 'QA-NOTE', source: record.table.source, title: '验收业务说明', content: '订单按门店归属记录。', origin: '用户录入', revision: 2, status: '有效', author: actor.id, files: [], updated: '2026-11-14 12:00:00' });
  prototype.generate(record, ['overview'], actor, 'overview');
  const task = gov.tasksFor(record)[0];
  assert.equal(task.notes.length, 1); assert.equal(task.notes[0].revision, 2);
  assert.equal(task.notes[0].content, '订单按门店归属记录。');
  assert.ok(task.events.some(event => event.action === '纳入业务说明输入'));
  assert.ok(data.noteUses.some(use => use.taskId === task.id && use.noteId === 'QA-NOTE'));
  data.notes[0].content = '第三版正文'; data.notes[0].revision = 3;
  assert.equal(task.notes[0].content, '订单按门店归属记录。'); assert.equal(task.notes[0].revision, 2);
});
test('a stale generation cannot replace a newer saved revision', () => {
  const { gov, prototype, record, actor } = fixture(), entry = record.items.find(row => row.id === 'field:id'), revision = entry.revision;
  gov.act(record, entry, 'save', actor, { meaning: '人工先保存的编号解释', values: '', usage: '', gap: '', basis: '' });
  assert.match(prototype.generate(record, ['fields'], actor, entry.id, revision), /已修改/);
  assert.equal(entry.content.meaning, '人工先保存的编号解释');
});
test('a fields-only batch updates all requested fields and leaves table description unchanged', () => {
  const { gov, prototype, record, actor } = fixture();
  assert.equal(prototype.generate(record, ['fields'], actor), undefined);
  assert.equal(gov.tasksFor(record)[0].outputs.length, record.items.filter(entry => entry.kind === 'fields' && !entry.retired).length);
  assert.ok(record.items.filter(entry => entry.kind === 'fields').every(entry => entry.ai?.simulated));
  assert.equal(record.items[0].content.meaning, '');
});
test('metadata changes preserve unaffected confirmations and leave a new field empty', () => {
  const { data, gov, prototype, record, actor, table } = fixture();
  prototype.generate(record, ['fields'], actor, 'field:id');
  const originalTask = gov.tasksFor(record)[0], originalContext = JSON.stringify(originalTask.tableContext);
  const reviewed = [record.items[0], record.items.find(row => row.id === 'field:id'), record.items.find(row => row.id === 'field:store_id')];
  for (const entry of reviewed) {
    gov.act(record, entry, 'save', actor, { meaning: '验收定义 ' + entry.id, values: '验收记录粒度或取值', usage: '', gap: '', basis: '' });
    assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  }
  const untouched = JSON.stringify([reviewed[0], reviewed[2]]), previousID = reviewed[1].content.meaning;
  const { task } = data.startTask(data.find(table.source));
  const result = task.resultObjects.find(row => row.id === table.id);
  result.fields.find(row => row.name === 'id').type = 'VARCHAR(64)';
  result.fields.push({ name: 'new_flag', type: 'SMALLINT', nullable: '是', default: '', comment: '新增标记' });
  for (let i = 0; i < 3; i++) data.advanceTask(task);
  assert.equal(reviewed[1].review, '待确认'); assert.equal(reviewed[1].content.meaning, previousID);
  assert.equal(JSON.stringify([reviewed[0], reviewed[2]]), untouched);
  assert.equal(JSON.stringify(originalTask.tableContext), originalContext);
  assert.equal(originalTask.entries[0].structure.type, 'BIGINT');
  const added = record.items.find(row => row.id === 'field:new_flag'); assert.ok(added); assert.equal(added.content.meaning, ''); assert.equal(added.review, '待确认');
});

test('table scope and update rules survive saving, review and later edits with frozen history', () => {
  const { gov, record, actor } = fixture(), entry = record.items[0];
  const content = { meaning: '客户订单', values: '一行一笔订单', scope: '包含取消订单，排除测试订单', updates: '每天同步前一天的数据' };
  assert.equal(gov.act(record, entry, 'save', actor, content), undefined);
  assert.equal(entry.content.scope, content.scope); assert.equal(entry.content.updates, content.updates);
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  const revision = entry.revision;
  assert.equal(gov.act(record, entry, 'save', actor, { ...entry.content, updates: '每小时同步' }), undefined);
  assert.equal(entry.review, '待确认'); assert.equal(entry.reviewer, ''); assert.ok(entry.revision > revision);
  assert.ok(entry.history.some(event => event.action === '修改前内容' && event.content.updates === content.updates));
});

test('optional table and field sections can stay blank while essential definitions and actual gaps still gate review', () => {
  const { gov, record, actor } = fixture(), overview = record.items[0], field = record.items.find(entry => entry.id === 'field:id');
  gov.act(record, overview, 'save', actor, { meaning: '订单', values: '' });
  assert.match(gov.confirmIssue(record, overview), /一行|粒度/);
  gov.act(record, overview, 'save', actor, { meaning: '订单', values: '一行一笔订单', scope: '', updates: '' });
  assert.equal(gov.act(record, overview, 'confirm', actor), undefined);
  assert.equal(gov.act(record, field, 'save', actor, { meaning: '订单标识', values: '', rules: '', usage: '' }), undefined);
  assert.equal(gov.act(record, field, 'confirm', actor), undefined);
  gov.act(record, field, 'save', actor, { ...field.content, gap: '编号唯一性尚需核实' });
  assert.match(gov.confirmIssue(record, field), /待核对/);
});

test('field calculation rules remain separate from cautions and survive prototype generation and editing', () => {
  const { gov, prototype, record, actor } = fixture(), field = record.items.find(entry => entry.id === 'field:id');
  prototype.generate(record, ['fields'], actor, field.id);
  assert.ok(field.content.rules); assert.equal(field.review, '待确认');
  assert.equal(field.checkContext, null);
  const content = { ...field.content, rules: '按订单编号去重计数', usage: '编号不表示时间顺序' };
  assert.equal(gov.act(record, field, 'save', actor, content), undefined);
  assert.equal(field.content.rules, content.rules); assert.equal(field.content.usage, content.usage);
  assert.equal(gov.act(record, field, 'confirm', actor), undefined);
});

test('one relation is referenced from both tables and exact participating fields without copying its definition', () => {
  const { data, gov, record, actor, table } = fixture(), target = data.objects(table.source).find(row => row.name === 'store' && row.schema === table.schema), other = gov.get(target);
  const before = gov.summary(record), result = gov.addRelation(record, actor, { name: '订单与门店', meaning: 'sale_order.store_id = store.id', values: '多个订单对应一家门店', usage: '按完整门店标识关联', dependencies: [{ table: table.id, entry: 'field:store_id' }, { table: target.id, entry: 'field:id' }] });
  assert.equal(result.error, undefined); assert.equal(result.entry.review, '待确认');
  assert.equal(gov.relationsFor(record)[0].entry, result.entry); assert.equal(gov.relationsFor(other)[0].entry, result.entry);
  assert.equal(gov.relationsFor(record, 'store_id')[0].entry, result.entry); assert.equal(gov.relationsFor(other, 'id')[0].entry, result.entry);
  assert.equal(gov.relationsFor(record, 'id').length, 0); assert.ok(!other.items.includes(result.entry));
  assert.equal(gov.summary(record).total, before.total); assert.equal(gov.summary(record).confirmed, before.confirmed);
  assert.equal(gov.act(record, result.entry, 'save', actor, { ...result.entry.content, usage: '门店标识需包含租户条件', dependencies: result.entry.dependencies }), undefined);
  assert.equal(gov.relationsFor(other, 'id')[0].entry.content.usage, '门店标识需包含租户条件');
  const foreign = data.objects('finance')[0]; assert.equal(gov.relationsFor(gov.get(foreign)).length, 0);
});

test('failed or unauthorized relation creation leaves no partial record', () => {
  const { gov, record, actor } = fixture(), count = record.items.length;
  assert.match(gov.addRelation(record, { ...actor, roles: [] }, { name: '不应新增' }).error, /权限/);
  assert.equal(record.items.length, count);
  assert.match(gov.addRelation(record, actor, { name: '', meaning: '无名称的关联' }).error, /名称/);
  assert.equal(record.items.length, count);
});

test('every table and field section is included in generation and missing suggestions carry a concrete reason', () => {
  const { gov, prototype, record, actor } = fixture();
  prototype.generate(record, ['overview'], actor, 'overview');
  const task = gov.tasksFor(record)[0], output = task.outputs[0];
  assert.deepEqual(Array.from(task.entries[0].sections, section => section.key), ['meaning', 'values', 'scope', 'updates']);
  assert.deepEqual(Array.from(output.coverage, section => section.key), ['meaning', 'values', 'scope', 'updates']);
  assert.equal(output.coverage.find(section => section.key === 'scope').state, '缺少依据');
  assert.match(output.coverage.find(section => section.key === 'scope').reason, /范围|排除/);
  assert.match(output.coverage.find(section => section.key === 'updates').reason, /采集时间不能代替/);
  prototype.generate(record, ['fields'], actor, 'field:updated_at');
  const field = gov.tasksFor(record)[0].outputs[0];
  assert.deepEqual(Array.from(field.coverage, section => section.key), ['meaning', 'values', 'rules', 'usage']);
  assert.ok(field.checkContext.question); assert.ok(field.checkContext.reason); assert.deepEqual(Array.from(field.checkContext.targets), ['values']);
});

test('explicit same-object source text can fill optional scope and update sections without inventing evidence', () => {
  const { data, gov, prototype, record, actor } = fixture();
  const content = 'sale_order 数据范围：包含取消订单，不含测试订单。\nsale_order 时间与更新：每天凌晨同步前一天数据。';
  data.notes.unshift({ id: 'QA-ALL-SECTIONS', source: record.table.source, title: '订单业务定义', content, origin: '用户录入', revision: 3, status: '有效', author: actor.id, files: [], updated: '2026-11-14 12:00:00' });
  prototype.generate(record, ['overview'], actor, 'overview');
  const entry = record.items[0], output = gov.tasksFor(record)[0].outputs[0];
  assert.equal(entry.content.scope, '包含取消订单，不含测试订单。'); assert.equal(entry.content.updates, '每天凌晨同步前一天数据。');
  assert.ok(output.coverage.every(section => section.state === '已有建议')); assert.equal(entry.review, '待确认');
  assert.equal(entry.checkContext, null); assert.equal(entry.gap, '');
  assert.equal(entry.basis, ''); assert.ok(entry.references.every(ref => ref.revision === 3 && content.includes(ref.excerpt)));
  const frozen = JSON.stringify(output);
  data.notes[0].content = 'sale_order 时间与更新：每小时同步。'; data.notes[0].revision = 4;
  assert.equal(JSON.stringify(output), frozen);
});

test('review questions, reasons and target sections survive resolution and revision history', () => {
  const { gov, record, actor } = fixture(), entry = record.items[0];
  const checkContext = { question: '是否排除测试订单？', reason: '业务资料与样本无法证明排除规则。', targets: ['scope'] };
  assert.equal(gov.act(record, entry, 'save', actor, { meaning: '订单', values: '一行一笔订单', gap: checkContext.question, checkContext }), undefined);
  assert.match(gov.confirmIssue(record, entry), /待核对/);
  assert.equal(gov.act(record, entry, 'save', actor, { ...entry.content, scope: '不包含测试订单', gap: '', basis: '订单接口说明明确排除测试单。', checkContext }), undefined);
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  assert.equal(entry.checkContext.question, checkContext.question);
  const event = entry.history.find(event => event.action === '人工确认');
  assert.equal(event.checkContext.reason, checkContext.reason); assert.deepEqual(Array.from(event.checkContext.targets), ['scope']);
  assert.equal(event.basis, '订单接口说明明确排除测试单。');
});

test('an incomplete review context cannot replace an existing draft', () => {
  const { gov, record, actor } = fixture(), entry = record.items[0], before = JSON.stringify(entry);
  assert.match(gov.act(record, entry, 'save', actor, { meaning: '订单', values: '一行一笔订单', gap: '待核实范围', checkContext: { question: '待核实范围', reason: '缺少范围资料', targets: [] } }), /问题、原因和涉及内容/);
  assert.equal(JSON.stringify(entry), before);
});

test('same-name tables in separate schemas require explicitly qualified source text', () => {
  const { data, gov, prototype, record, actor } = fixture();
  record.table.schema = 'current';
  data.tables.push({ ...structuredClone(record.table), id: 'qa-archive-order', schema: 'archive', identity: 'qa-archive-order' });
  const note = { id: 'QA-QUALIFIED', source: record.table.source, title: '表范围说明', content: 'sale_order 数据范围：只包含当前订单。', origin: '用户录入', revision: 1, status: '有效', author: actor.id, files: [], updated: '2026-11-14 12:00:00' };
  data.notes.unshift(note); prototype.generate(record, ['overview'], actor, 'overview');
  assert.equal(record.items[0].content.scope, ''); assert.equal(record.items[0].references.length, 0);
  note.content = 'current.sale_order 数据范围：只包含当前订单。'; note.revision++;
  prototype.generate(record, ['overview'], actor, 'overview');
  assert.equal(record.items[0].content.scope, '只包含当前订单。'); assert.ok(record.items[0].references.every(ref => ref.revision === 2));
});

test('ordinary blank table sections do not become blocking review tasks', () => {
  const { gov, prototype, record, actor } = fixture();
  prototype.generate(record, ['overview'], actor, 'overview');
  const entry = record.items[0];
  assert.equal(entry.content.scope, ''); assert.equal(entry.content.updates, '');
  assert.equal(entry.checkContext, null); assert.equal(entry.gap, '');
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
});

test('actual code questions are answered separately and copied into the corresponding definition', () => {
  const { gov, prototype, record, actor } = fixture();
  prototype.generate(record, ['fields'], actor, 'field:status');
  const entry = record.items.find(item => item.id === 'field:status'), original = JSON.stringify(gov.tasksFor(record)[0].outputs[0].checkContext);
  const model = { ...entry.content, gap: entry.gap, basis: '', checkContext: structuredClone(entry.checkContext) };
  assert.match(gov.confirmAnswer(model, 'values'), /回答 AI/);
  const values = model.checkContext.questions.find(question => question.key === 'values');
  values.response = '0 为新建，1 为有效，2 为完成；空值尚未分配状态。';
  assert.equal(gov.confirmAnswer(model, 'values'), undefined);
  assert.equal(model.values, values.response); assert.equal(gov.unansweredQuestions(model).length, 1);
  assert.equal(gov.act(record, entry, 'save', actor, model), undefined); assert.match(gov.confirmIssue(record, entry), /使用口径/);
  model.checkContext.questions.find(question => question.key === 'rules').response = '有效记录选 1、2。';
  assert.equal(gov.confirmAnswer(model, 'rules'), undefined); assert.equal(model.gap, '');
  assert.equal(gov.act(record, entry, 'save', actor, model), undefined); assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  assert.equal(entry.basis, ''); assert.equal(JSON.stringify(gov.tasksFor(record)[0].outputs[0].checkContext), original);
});

test('deferring or changing a code answer keeps its real question open', () => {
  const { gov, prototype, record, actor } = fixture(); prototype.generate(record, ['fields'], actor, 'field:status');
  const entry = record.items.find(item => item.id === 'field:status'), model = { ...entry.content, gap: entry.gap, basis: '', checkContext: structuredClone(entry.checkContext) };
  const question = model.checkContext.questions[0]; question.deferred = true; gov.refreshAnswers(model);
  assert.equal(gov.unansweredQuestions(model).length, 2);
  question.response = '0 为新建，1 为有效，2 为完成'; gov.confirmAnswer(model, question.key);
  question.response = '修改后的编码解释'; gov.refreshAnswers(model);
  assert.equal(question.resolved, false); assert.equal(gov.unansweredQuestions(model).length, 2);
  model.gap = ''; model.basis = '不能清空字符串绕过实际核对';
  assert.equal(gov.act(record, entry, 'save', actor, model), undefined); assert.match(gov.confirmIssue(record, entry), /取值含义/);
});

test('confirmed answers update the definition once and remain historical records when prose is edited', () => {
  const { gov, prototype, record, actor } = fixture(); prototype.generate(record, ['fields'], actor, 'field:status');
  const entry = record.items.find(item => item.id === 'field:status'), model = { ...entry.content, gap: entry.gap, basis: '', checkContext: structuredClone(entry.checkContext) };
  for (const question of model.checkContext.questions) {
    question.response = question.key === 'values' ? '0 为新建，1 为有效，2 为完成。' : '有效订单使用 1、2。';
    assert.equal(gov.confirmAnswer(model, question.key, actor), undefined);
  }
  const accepted = structuredClone(model.checkContext.questions[0]);
  assert.equal(model.values, accepted.answer); assert.equal(accepted.answeredBy, actor.name); assert.ok(accepted.answeredAt);
  model.values += '状态仅反映当前处理阶段。'; gov.refreshAnswers(model);
  assert.equal(gov.unansweredQuestions(model).length, 0); assert.equal(model.gap, '');
  assert.equal(model.checkContext.questions[0].answer, accepted.answer);
  assert.equal(gov.act(record, entry, 'save', actor, model), undefined);
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  assert.equal(entry.checkContext.questions[0].answer, accepted.answer);
  assert.notEqual(entry.content.values, accepted.answer);
});

test('partial drafts save without meaning while confirmation still checks essentials', () => {
  const { gov, record, actor } = fixture(), entry = record.items[0];
  assert.equal(gov.act(record, entry, 'save', actor, { scope: '排除测试订单' }), undefined);
  assert.equal(entry.content.meaning, ''); assert.equal(entry.content.scope, '排除测试订单');
  assert.equal(gov.summary(record).confirmed, 0); assert.match(gov.confirmIssue(record, entry), /记录什么业务/);
  const metric = gov.addRelation(record, actor, { name: '净收入' }, 'metrics');
  assert.equal(metric.error, undefined); assert.equal(metric.entry.review, '待确认');
  assert.equal(gov.act(record, metric.entry, 'save', actor, { meaning: '扣除退款后的收入' }), undefined);
  assert.match(gov.confirmIssue(record, metric.entry), /公式/);
});

test('generation previews are read-only and selected replacements preserve manual and confirmed neighbors', () => {
  const { data, gov, prototype, record, actor } = fixture(), overview = record.items[0], field = record.items.find(item => item.id === 'field:id');
  gov.act(record, overview, 'save', actor, { meaning: '人工订单用途', values: '一行一笔订单', scope: '正式订单' });
  gov.act(record, overview, 'confirm', actor);
  gov.act(record, field, 'save', actor, { meaning: '人工编号', values: '保留前导零' });
  const before = JSON.stringify(record.items), tasks = gov.tasks.length, uses = data.noteUses.length;
  const preview = prototype.preview(record, ['overview', 'fields'], actor, undefined, ['overview', field.id]);
  assert.equal(JSON.stringify(record.items), before); assert.equal(gov.tasks.length, tasks); assert.equal(data.noteUses.length, uses);
  assert.equal(prototype.generate(record, ['overview', 'fields'], actor, undefined, undefined, { preview, replacements: { [field.id]: ['meaning'] } }), undefined);
  assert.equal(overview.review, '已确认'); assert.equal(overview.content.meaning, '人工订单用途');
  assert.notEqual(field.content.meaning, '人工编号'); assert.equal(field.content.values, '保留前导零');
  assert.deepEqual(Array.from(gov.tasksFor(record)[0].entries, item => item.id), [field.id]);
  assert.ok(field.history.some(event => event.content.meaning === '人工编号'));
});

test('default batches protect manual drafts and confirmed definitions', () => {
  const { gov, prototype, record, actor } = fixture(), entry = record.items.find(item => item.id === 'field:id');
  gov.act(record, entry, 'save', actor, { meaning: '人工编号' }); gov.act(record, entry, 'confirm', actor);
  const frozen = JSON.stringify(entry);
  assert.equal(prototype.generate(record, ['fields'], actor), undefined);
  assert.equal(JSON.stringify(entry), frozen);
  assert.ok(!gov.tasksFor(record)[0].entries.some(item => item.id === entry.id));
});

test('stale source notes prevent applying a preview without changing current input or recording a task', () => {
  const { data, gov, prototype, record, actor } = fixture();
  const preview = prototype.preview(record, ['overview'], actor, 'overview'), before = JSON.stringify(record.items), count = gov.tasks.length;
  data.notes.unshift({ id: 'changed-note', source: record.table.source, revision: 1, content: '新资料', status: '有效', origin: '用户录入' });
  assert.match(prototype.generate(record, ['overview'], actor, 'overview', undefined, { preview }), /资料已变化/);
  assert.equal(JSON.stringify(record.items), before); assert.equal(gov.tasks.length, count);
});

test('units and code mappings save as field semantics and remain in confirmation history', () => {
  const { gov, record, actor } = fixture(), entry = record.items.find(item => item.id === 'field:status');
  const valueDetails = { unit: '', mappings: [{ code: '01', meaning: '' }] };
  assert.equal(gov.act(record, entry, 'save', actor, { meaning: '业务状态', rules: '当前仅用于展示，不用于筛选或统计。', valueDetails }), undefined);
  assert.match(gov.confirmIssue(record, entry), /编码及含义/);
  valueDetails.mappings[0].meaning = '有效';
  assert.equal(gov.act(record, entry, 'save', actor, { ...entry.content, valueDetails }), undefined);
  assert.equal(gov.act(record, entry, 'confirm', actor), undefined);
  const history = entry.history.find(event => event.action === '人工确认');
  assert.equal(history.valueDetails.mappings[0].code, '01');
  valueDetails.mappings[0].meaning = '后续修改';
  assert.equal(history.valueDetails.mappings[0].meaning, '有效');
});

test('a relation requires both participating field definitions rather than a single dependency', () => {
  const { gov, record, actor } = fixture(), field = record.items.find(item => item.id === 'field:id');
  gov.act(record, field, 'save', actor, { meaning: '订单编号' }); gov.act(record, field, 'confirm', actor);
  const { entry } = gov.addRelation(record, actor, { name: '未完整选择双方', meaning: '订单与门店关联', values: '多对一', dependencies: [{ table: record.table.id, entry: field.id }] });
  assert.match(gov.confirmIssue(record, entry), /双方/);
});

test('changes to a field unit require knowledge updates even when its prose stays identical', () => {
  const { gov, record, actor } = fixture(), entry = record.items.find(item => item.id === 'field:store_id');
  const impact = { kind: '业务说明', bindings: [{ table: record.table.id, entry: entry.id, before: structuredClone(entry.content), beforeValueDetails: null, beforeDependencies: [] }] };
  assert.equal(gov.impactNeedsKnowledge(impact), false);
  assert.equal(gov.act(record, entry, 'save', actor, { valueDetails: { unit: '个', mappings: [] } }), undefined);
  assert.equal(gov.impactNeedsKnowledge(impact), true);
});
