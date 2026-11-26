// 仅供历史审核、发布等单元场景使用：显式建立测试快照和模型替身，不进入原型运行资源。
module.exports = function reviewedScenario(context) {
  const { DataSourceDemo: data, GovernanceDemo: gov, GovernanceImpactDemo: impacts } = context;
  require('./note-scenarios.cjs')(data);
  data.notes.forEach(note => { note.origin = '测试资料'; });
  context.KnowledgeUpdateDemo?.testCases.push(
    { id: 'TC-001', source: 'retail', dependency: 'sale_item.quantity', revision: 1, review: '已确认', question: '同单位销售数量 1.250 与 2.500 汇总', expected: '3.750', actual: '3.750', origin: '测试资料' },
    { id: 'TC-002', source: 'retail', dependency: 'sale_order.paid_at', revision: 1, review: '已确认', question: '支付时间为空的订单不计入已支付订单', expected: '排除空支付时间', actual: '排除空支付时间', origin: '测试资料' },
    { id: 'TC-003', source: 'retail', dependency: 'legacy_price.cost_price', revision: 1, review: '已确认', question: '停用知识不再引用已删除的成本价字段', expected: '无旧成本价依赖', actual: '无旧成本价依赖', origin: '测试资料' },
    { id: 'TC-004', source: 'retail', dependency: 'supplier', revision: 1, review: '已确认', question: '停用知识不再引用已确认删除的供应商表', expected: '无已删除供应商表依赖', actual: '无已删除供应商表依赖', origin: '测试资料' }
  );
  const sourceOrder = ['retail', 'finance', 'products'], reviewedCounts = [16, 6, 9];
  const overview = {
    sale_order: ['订单主记录', '一行一张订单，以 id 标识', '订单与明细分别统计'],
    product: ['维护商品档案', '一行一条商品档案，以 id 标识', '历史交易按当时取值'],
    sale_item: ['销售商品明细', '一行一条商品行，以 sale_item_id 标识', '按同单位汇总数量']
  };
  for (const record of gov.records.values()) {
    const table = record.table, begun = data.objects(table.source).indexOf(table) < reviewedCounts[sourceOrder.indexOf(table.source)];
    const related = data.objects(table.source).find(other => other.name === (table.name === 'sale_item' ? 'product' : 'store') && other.schema === table.schema);
    const pending = (id, kind, name, gap, dependencies) => ({ id, kind, name, content: { meaning: '', values: '', usage: '' }, gap, dependencies, revision: 1, review: '待确认', validity: '适用', publication: '未发布', reviewer: '', reviewedAt: '', basis: '', references: [], ai: null, history: [] });
    if (related && related.id !== table.id) record.items.push(pending('relation:main', 'relations', table.name === 'sale_item' ? '销售明细与商品' : table.label + '与门店', '测试关联需核对', [{ table: table.id, entry: 'field:' + (table.name === 'sale_item' ? 'product_id' : 'store_id') }, { table: related.id, entry: 'field:id' }]));
    if (table.name === 'sale_item') record.items.push(pending('metric:quantity', 'metrics', '销售数量（未扣退货）', '测试指标需核对', ['field:quantity']));
    const note = data.notes.find(note => note.source === table.source && note.status === '有效');
    for (const entry of record.items) {
      if (entry.retired) continue;
      if (['field:code', 'field:status'].includes(entry.id)) entry.gap = '测试取值或编码定义尚未核清';
      if (note) entry.manualEvidence = structuredClone({ ...note, excerpt: note.content.split('。')[0] + '。', recordedBy: 'u01', recordedAt: '2026-11-10 09:00:00' });
      let content;
      if (entry.kind === 'overview' && overview[table.name]) {
        const [meaning, values, usage] = overview[table.name]; content = { meaning, values, usage };
      } else if (entry.kind === 'fields' && !['code', 'status', 'paid_at'].includes(entry.field)) {
        content = { meaning: entry.field === 'quantity' ? '销售数量；退货口径另行确认' : entry.field === 'updated_at' ? '来源记录更新时间' : entry.field === 'store_id' ? '业务发生门店' : '记录编号', values: '', usage: entry.field === 'updated_at' ? '不直接等于业务发生时间' : '' };
      }
      if (entry.kind === 'fields') {
        if (entry.field === 'quantity') { content.values = '单位：件；按商品计量单位分组。'; content.rules = '按相同单位汇总原销售数量，退货用独立净销量口径。'; entry.valueDetails = { unit: '件', mappings: [] }; }
        if (entry.field === 'updated_at') content.values = '北京时间，空值表示尚未写回更新时间。';
        if (entry.field === 'code') content = { meaning: '业务编码', values: '按原始格式保留，可能重复，唯一标识使用主键。', rules: '', usage: '保留前导零，不直接作为唯一关联键。' };
        if (entry.field === 'status') content = { meaning: '测试业务状态', values: '0=新建，1=有效，2=完成；空值表示未知。', rules: '测试口径下有效记录使用 1、2。', usage: '' };
        if (entry.field === 'paid_at') content = { meaning: '支付时间', values: '北京时间，空值表示尚未支付。', rules: '按支付自然日统计。', usage: '不使用记录更新时间替代支付事件。' };
      }
      if (content) {
        entry.content = content; if (!['code', 'status'].includes(entry.field)) entry.gap = '';
        if (begun && entry.validity !== '待复核' && !['code', 'status', 'paid_at'].includes(entry.field)) Object.assign(entry, { review: '已确认', reviewer: '测试审核人', reviewedAt: '2026-11-10 09:00:00', basis: '测试场景预先核对记录' });
      }
      if (entry.kind === 'relations') entry.proposal = { meaning: '测试关联条件', values: '测试多对一完整键', usage: '测试已声明范围' };
      if (entry.kind === 'metrics') entry.proposal = { meaning: '测试销售数量', values: 'SUM(sale_item.quantity)', usage: '测试同单位汇总' };
    }
    for (const field of table.fields) {
      const entry = record.items.find(entry => entry.field === field.name);
      if (entry?.content.meaning) field.meaning = entry.content.meaning + (entry.review === '已确认' ? '' : '（待确认）');
    }
    table.governance = gov.summary(record).state;
    table.governanceAffected = gov.summary(record).affected > 0;
  }
  for (const record of gov.records.values()) for (const entry of record.items.filter(entry => entry.review === '已确认')) entry.dependencyRevisions = gov.bindDependencyRevisions(record, entry);
  for (const impact of impacts.records) for (const binding of impact.bindings) {
    const entry = gov.records.get(binding.table)?.items.find(entry => entry.id === binding.entry);
    if (entry) binding.before = structuredClone(entry.content);
  }
  for (const source of data.sources) {
    const rows = data.objects(source.id); source.governed = rows.filter(row => row.governance === '已治理').length; source.partialGoverned = rows.filter(row => row.governance === '治理中').length;
  }
  const prepare = gov.generate;
  gov.generate = (record, kinds, actor, entryId) => {
    const error = prepare(record, kinds, actor, entryId); if (error) return error;
    const task = gov.tasksFor(record)[0]; task.state = '已完成'; task.outputs = [];
    for (const entry of record.items.filter(entry => entry.ai?.taskId === task.id)) {
      const content = entry.proposal || (['field:code', 'field:status'].includes(entry.id) ? { meaning: '测试待核对解释', values: '测试取值说明', usage: '测试使用条件' } : entry.content);
      Object.assign(entry.ai, { content: structuredClone(content), gap: entry.gap, references: task.notes.map(note => ({ ...structuredClone(note), excerpt: note.content.split('。')[0] + '。' })), basis: ['测试模型输出依据'] });
      task.outputs.push(structuredClone({ id: entry.id, name: entry.name, kind: entry.kind, content, gap: entry.gap }));
    }
  };
};
