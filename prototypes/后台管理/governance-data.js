/* 治理原型：建议、人工定义和审核记录分存，仅维护页面内示例，不调用模型或数据库。 */
(() => {
  'use strict';
  const records = new Map(), drafts = new Map();
  const has = (actor, ability) => SourceApprovalDemo.hasAbility(actor, ability);
  const objectName = table => (table.schema ? table.schema + '.' : '') + table.name;
  const copy = value => structuredClone(value);
  function get(table) {
    if (records.has(table.id)) return records.get(table.id);
    const started = table.governance !== '待治理';
    const item = (id, kind, name, content, confirmed = false, gap = '') => ({ id, kind, name, content, gap, revision: 1, review: confirmed ? '已确认' : '待确认', validity: '适用', publication: '未发布', reviewer: confirmed ? '杨分析师' : '', reviewedAt: confirmed ? '2026-11-10 09:00:00' : '', basis: confirmed ? '已核对业务说明及对象定义。' : '', ai: null, history: [] });
    const descriptions = {
      product: ['维护商品档案及业务编码。', '一行代表一条商品档案；不表示销售或库存数量。', '用于识别商品；历史交易以交易时的记录为准。'],
      sale_item: ['记录销售单中的商品明细。', '一行代表销售单中的一条商品明细。', '明细行数不等于交易笔数；数量汇总需核对单位和退货口径。'],
      inventory: ['记录门店与商品的当前库存余额。', '同一门店、同一商品的当前库存记录。', '不能直接回答历史时点库存；可售与实物库存需另行核对。']
    };
    const overview = descriptions[table.name] || [table.label + '的业务记录。', '', '按已确认的字段含义使用。'];
    const items = [item('overview', 'overview', '表用途与粒度', { meaning: overview[0], values: overview[1], usage: overview[2] }, started)];
    table.fields.forEach(field => {
      const gap = /待确认/.test(field.meaning) ? field.name === 'status' ? '缺少状态代码的业务定义，不能仅凭默认值判断。' : '尚缺业务编码及适用范围说明。' : '';
      const entry = item('field:' + field.name, 'fields', field.name, { meaning: gap ? '' : field.meaning, values: '', usage: field.name === 'updated_at' ? '来源更新时间不直接等于业务发生时间。' : '' }, started && !gap, gap);
      entry.field = field.name; entry.type = field.type; entry.comment = field.comment;
      const change = DataSourceDemo.changes.find(change => change.source === table.source && change.object === objectName(table) && change.field === field.name);
      if (change) { entry.validity = '待复核'; entry.review = '待确认'; entry.reviewer = ''; entry.reviewedAt = ''; entry.gap = change.kind === '新增字段' ? '需确认新增字段的业务含义与使用时间。' : '结构已变化，需核对新类型、单位与原业务口径。'; entry.blocked = change.control === '已禁止使用'; }
      items.push(entry);
    });
    DataSourceDemo.changes.filter(change => change.source === table.source && change.object === objectName(table) && change.kind === '删除字段' && !table.fields.some(field => field.name === change.field)).forEach(change => {
      const entry = item('field:' + change.field, 'fields', change.field, { meaning: '来源字段已删除。', values: '', usage: '停止依赖该字段，相关知识需修订。' });
      Object.assign(entry, { field: change.field, type: '已移除', comment: '', retired: true, blocked: true, validity: '失效', basis: change.evidence }); items.push(entry);
    });
    const related = DataSourceDemo.objects(table.source).find(other => other.name === (table.name === 'sale_item' ? 'product' : 'store') && other.schema === table.schema);
    if (related && related.id !== table.id) {
      const entry = item('relation:main', 'relations', table.name === 'sale_item' ? '销售明细与商品' : table.label + '与门店', { meaning: '', values: '', usage: '' }, false, '需核对完整关联键、基数和重复汇总风险。');
      entry.proposal = { meaning: objectName(table) + '.' + (table.name === 'sale_item' ? 'product_id' : 'store_id') + ' = ' + objectName(related) + '.id', values: '多对一，待业务确认', usage: '只用于已确认的同源业务关联。' }; items.push(entry);
    }
    if (table.name === 'sale_item') {
      const entry = item('metric:quantity', 'metrics', '销售数量（未扣退货）', { meaning: '', values: '', usage: '' }, false, '需确认有效单据条件、数量单位和时间归属。');
      entry.proposal = { meaning: '按销售明细汇总销售数量，不含退货扣减。', values: 'SUM(sale_item.quantity)', usage: '不得将不同计量单位直接相加；不能称为净销量。' }; entry.dependencies = ['field:quantity']; items.push(entry);
    }
    const themes = table.name === 'product' ? ['销售分析', '库存管理'] : table.name.startsWith('inventory') ? ['库存管理'] : /^(purchase|supplier)/.test(table.name) ? ['采购管理'] : ['销售分析'];
    const record = { table, items, themes: started ? themes : [], themeReview: started ? '已确认' : '待归类', themeSuggestion: null };
    records.set(table.id, record);
    GovernanceImpactDemo.records.filter(impact => impact.kind === '结构变化' && impact.source === table.source && ['待治理', '处理中'].includes(impact.state)).forEach(impact => {
      const change = DataSourceDemo.changes.find(change => change.id === impact.originId && change.object === objectName(table));
      if (change) bindImpact(record, impact, change.field ? 'field:' + change.field : 'overview');
    });
    return record;
  }
  function log(entry, action, actor, reason = '') {
    entry.history.unshift({ action, actor: actor.name, at: DataSourceDemo.now(), reason, content: copy(entry.content), revision: entry.revision, review: entry.review });
  }
  function summary(record) {
    const necessary = record.items.filter(entry => ['overview', 'fields'].includes(entry.kind));
    const confirmed = necessary.filter(entry => entry.review === '已确认' && entry.validity !== '待复核').length;
    return { total: necessary.length, confirmed, pending: necessary.length - confirmed, state: necessary.some(entry => entry.validity === '待复核') ? '待复核' : confirmed === necessary.length ? '已确认' : confirmed ? '部分确认' : '待治理' };
  }
  function sync(record) {
    record.table.governance = summary(record).state;
    record.table.fields.forEach(field => { const entry = record.items.find(entry => entry.id === 'field:' + field.name); if (entry) field.meaning = entry.content.meaning ? entry.content.meaning + (entry.review === '已确认' ? '' : '（待确认）') : '待确认'; });
    const source = DataSourceDemo.find(record.table.source), tables = DataSourceDemo.objects(source.id);
    source.governed = tables.filter(table => table.governance === '已确认').length;
    source.partialGoverned = tables.filter(table => table.governance === '部分确认').length;
  }
  function generate(record, kinds, actor, entryId) {
    if (!has(actor, 'governance.view') || !has(actor, 'governance.suggest')) return '当前账号没有生成 AI 建议的权限。';
    if (!kinds.length) return '请选择需要生成建议的内容。';
    for (const entry of record.items.filter(entry => kinds.includes(entry.kind) && !entry.retired && (!entryId || entry.id === entryId))) {
      const field = record.table.fields.find(field => field.name === entry.field);
      const content = entry.proposal || (entry.id === 'field:code' ? { meaning: '用于标识' + record.table.label + '的业务编码。', values: '编码规则与唯一性需结合业务制度核对。', usage: '不能仅凭字段名假定其为主键。' } : entry.id === 'field:status' ? { meaning: '记录当前业务状态，具体代码含义待补充。', values: '默认值为 ' + field.default + '；不能据此推断完整枚举。', usage: '状态定义确认前不用于业务过滤。' } : copy(entry.content));
      entry.ai = { content: copy(content), gap: entry.gap, at: DataSourceDemo.now(), basis: [record.table.path + (field ? '.' + field.name : '') + ' · ' + record.table.version, field ? '来源注释：' + (field.comment || '无注释') : '来源注释：' + (record.table.comment || '无注释'), entry.proposal ? '依据结构提出的关联或计算建议，仍需核对业务条件。' : '结合已有定义起草；来源注释不等于业务确认。'] };
      log(entry, '生成 AI 建议', actor);
      linked(record, entry).filter(impact => impact.state !== '待知识更新').forEach(impact => GovernanceImpactDemo.touch(impact, actor));
    }
    record.themeSuggestion = record.table.name.includes('inventory') ? ['库存管理'] : ['销售分析'];
  }
  function act(record, entry, action, actor, data = {}) {
    const permitted = action === 'confirm' ? has(actor, 'governance.confirm') : action === 'pending' ? has(actor, 'governance.edit') || has(actor, 'governance.supplement') : has(actor, 'governance.edit');
    if (!has(actor, 'governance.view') || !permitted) return '当前账号没有此项治理操作权限。';
    if (!record.items.includes(entry)) return '治理条目已变化，请重新打开。';
    if (action === 'confirm') {
      if (!entry.content.meaning.trim() || entry.gap) return '请先补齐业务内容与待确认问题。';
      if (entry.dependencies?.some(id => { const dependency = record.items.find(item => item.id === id); return !dependency || dependency.review !== '已确认' || dependency.blocked; })) return '依赖字段尚未确认或仍禁止使用，请先处理依赖。';
      log(entry, '确认前内容', actor); entry.review = '已确认'; entry.reviewer = actor.name; entry.reviewedAt = DataSourceDemo.now();
      if (!entry.retired) entry.validity = '适用';
      log(entry, entry.retired ? '确认移除处理' : '人工确认', actor);
    } else if (action === 'adopt') {
      if (!entry.ai) return '请先生成 AI 建议。';
      if (entry.review === '已确认') return '已确认内容请通过编辑修改，AI 建议不会覆盖人工定义。';
      log(entry, '采用建议前内容', actor); entry.content = copy(entry.ai.content); entry.gap = entry.ai.gap; entry.basis = entry.ai.basis.join('\n'); entry.revision++; log(entry, '采用为待确认内容', actor);
    } else if (action === 'save') {
      if (!data.meaning?.trim() || !data.basis?.trim()) return '请填写业务含义和核对依据。';
      if (JSON.stringify([entry.content, entry.gap, entry.basis]) === JSON.stringify([{ meaning: data.meaning.trim(), values: data.values.trim(), usage: data.usage.trim() }, data.gap.trim(), data.basis.trim()])) return '内容未变化。';
      log(entry, '修改前内容', actor); entry.content = { meaning: data.meaning.trim(), values: data.values.trim(), usage: data.usage.trim() }; entry.gap = data.gap.trim(); entry.basis = data.basis.trim(); entry.review = '待确认'; entry.reviewer = ''; entry.reviewedAt = ''; entry.revision++; log(entry, '保存待确认内容', actor);
    } else if (action === 'pending') {
      if (!data.reason?.trim()) return '请填写尚缺的业务信息。';
      log(entry, '待确认前内容', actor); entry.review = '待确认'; entry.gap = data.reason.trim(); entry.reviewer = ''; entry.reviewedAt = ''; log(entry, '保留待确认', actor, data.reason.trim());
    } else if (action === 'reject') {
      if (!entry.ai || !data.reason?.trim()) return '请填写不采用该建议的依据。';
      entry.ai.rejected = data.reason.trim(); log(entry, '不采用 AI 建议', actor, data.reason.trim());
    }
    sync(record);
    linked(record, entry).forEach(impact => action === 'confirm' ? GovernanceImpactDemo.reviewed(impact, actor) : action !== 'reject' && GovernanceImpactDemo.touch(impact, actor));
  }
  const linked = (record, entry) => GovernanceImpactDemo.records.filter(impact => impact.bindings.some(binding => binding.table === record.table.id && binding.entry === entry.id) && impact.state !== '已完成');
  function bindImpact(record, impact, entryId) {
    const entry = record.items.find(item => item.id === entryId);
    if (!entry || impact.bindings.some(binding => binding.table === record.table.id && binding.entry === entry.id)) return;
    impact.bindings.push({ table: record.table.id, entry: entry.id, before: copy(entry.content), previous: { review: entry.review, validity: entry.validity, gap: entry.gap } });
    entry.review = '待确认'; entry.reviewer = ''; entry.reviewedAt = '';
    if (!entry.retired) entry.validity = '待复核';
    entry.gap ||= '请核对本次变化对业务定义的影响。'; sync(record);
  }
  function impactTarget(impact) {
    const change = DataSourceDemo.changes.find(change => impact.kind === '结构变化' && change.id === impact.originId && change.source === impact.source);
    const binding = impact.bindings[0];
    const table = DataSourceDemo.objects(impact.source).find(table => change ? objectName(table) === change.object : table.id === binding?.table);
    const entryId = change ? change.field ? 'field:' + change.field : 'overview' : binding?.entry;
    return { table, field: change?.field || (entryId?.startsWith('field:') ? entryId.slice(6) : ''), entryId };
  }
  function impactReady(impact) {
    return impact.bindings.length > 0 && impact.bindings.every(binding => {
      const table = DataSourceDemo.objects(impact.source).find(table => table.id === binding.table), entry = table && get(table).items.find(entry => entry.id === binding.entry);
      return Boolean(entry && entry.review === '已确认' && entry.validity !== '待复核' && !entry.gap);
    });
  }
  function prepareImpact(impact) {
    const { table, entryId } = impactTarget(impact); if (table && entryId) bindImpact(get(table), impact, entryId);
  }
  function associate(impact, targets, actor, revision) {
    if (!has(actor, 'governance.view') || !has(actor, 'governance.edit')) return '当前账号没有关联治理对象的权限。';
    if (!GovernanceImpactDemo.records.includes(impact) || impact.kind !== '业务说明' || impact.bindings.length || impact.revision !== revision) return '变化事项已更新，请重新核对。';
    const selected = targets.map(target => { const table = DataSourceDemo.objects(impact.source).find(table => table.id === target.table); return { table, entry: table && get(table).items.find(entry => entry.id === target.entry) }; });
    if (!selected.length || selected.some(target => !target.table || !target.entry)) return '请选择当前数据源中可访问的治理条目。';
    selected.forEach(target => bindImpact(get(target.table), impact, target.entry.id));
    GovernanceImpactDemo.touch(impact, actor);
  }
  function impactNeedsKnowledge(impact) {
    const change = DataSourceDemo.changes.find(change => impact.kind === '结构变化' && change.id === impact.originId);
    return ['新增字段', '删除字段'].includes(change?.kind) || impact.bindings.some(binding => { const entry = records.get(binding.table)?.items.find(entry => entry.id === binding.entry); return entry && (entry.blocked || entry.retired || JSON.stringify(entry.content) !== JSON.stringify(binding.before)); });
  }
  function dismissError(impact) {
    const change = DataSourceDemo.changes.find(change => impact.kind === '结构变化' && change.id === impact.originId);
    if (change && (change.compatible === false || ['新增字段', '删除字段'].includes(change.kind))) return '新增、删除或不兼容变化须在对应治理条目中人工审核。';
    if (impact.bindings.some(binding => { const entry = records.get(binding.table)?.items.find(entry => entry.id === binding.entry); return !entry || entry.blocked || entry.retired || binding.previous.review !== '已确认' || JSON.stringify(entry.content) !== JSON.stringify(binding.before); })) return '治理内容已修改或仍缺少确认，请完成对应条目的人工审核。';
    if (impact.kind === '结构变化' && !impact.bindings.length) return '尚未定位到对应治理对象，请先核对来源。';
  }
  function restoreImpact(impact, actor, reason) {
    impact.bindings.forEach(binding => { const record = records.get(binding.table), entry = record.items.find(entry => entry.id === binding.entry); entry.review = '已确认'; entry.validity = binding.previous.validity; entry.gap = binding.previous.gap; entry.reviewer = actor.name; entry.reviewedAt = DataSourceDemo.now(); log(entry, '确认无需修改', actor, reason); sync(record); linked(record,entry).filter(other=>other!==impact).forEach(other=>GovernanceImpactDemo.reviewed(other,actor)); });
  }
  function generateDraft(record, actor) {
    if (!has(actor, 'knowledge.generate')) return { error: '当前账号没有生成知识草稿的权限。' };
    // 已确认只表示可进入草稿；禁止、失效、依赖不完整的条目仍排除，不改变已发布知识。
    const included = record.items.filter(entry => entry.review === '已确认' && entry.validity === '适用' && !entry.blocked && !entry.retired && !entry.dependencies?.some(id => { const dependency = record.items.find(item => item.id === id); return !dependency || dependency.review !== '已确认' || dependency.blocked; }));
    if (!included.length) return { error: '暂无可纳入草稿的已确认条目。' };
    const draft = { id: 'GOV-' + record.table.id, source: record.table.source, table: record.table.id, title: record.table.label + '业务知识', items: copy(included), excluded: record.items.filter(entry => !included.includes(entry)).map(entry => entry.name), at: DataSourceDemo.now() };
    drafts.set(draft.id, draft); return { draft };
  }
  window.GovernanceDemo = { get, records, drafts, objectName, summary, generate, act, impactTarget, impactReady, prepareImpact, associate, impactNeedsKnowledge, dismissError, restoreImpact, generateDraft };
})();
