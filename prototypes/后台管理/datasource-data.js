/* 数据源原型只维护页面内的连接摘要、结构和任务；不联网、不保存凭据、不执行 SQL。 */
(() => {
  'use strict';
  const sources = PeopleDemo.dataSources;
  const now = () => new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 19);
  let sequence = 100, snapshotSequence = 30, selected = 'retail';
  // 联系人只供查看后自行联系；新增时不预选，不作为连接或采集前提。
  const configDefaults = { type: 'MySQL', version: '', purpose: '', technical: '', business: '', host: '', port: 3306, database: '', schemas: [], account: '', connections: 4, concurrency: 2, retries: 2, taskTimeout: 600, queryTimeout: 30, rowLimit: 1000, syncHours: 24, window: '02:00–05:00' };
  // 连接保护归平台策略；只表达原型默认约束，不提供逐数据源关闭校验的入口。
  const connectionPolicy = Object.freeze({ verifyServerCertificate: true, connectTimeoutSeconds: 15 });
  const initial = [
    { state: '已启用', health: '正常', host: 'retail-db.example', database: 'retail', collection: '部分成功', lastFull: '2026-11-10 14:03:21', knowledge: 8, governed: 16, technical: 'u11', business: 'u02' },
    { state: '已启用', health: '网络异常', host: 'finance-db.example', database: 'finance', collection: '失败', lastFull: '2026-11-08 02:04:20', knowledge: 3, governed: 6, technical: 'u11', business: 'u13' },
    { state: '已启用', health: '正常', host: 'products-db.example', database: 'products', collection: '完整成功', lastFull: '2026-11-11 03:01:10', knowledge: 4, governed: 9, technical: 'u11', business: 'u09' },
    { state: '草稿', health: '未测试', host: '', database: '', collection: '未采集', lastFull: '', knowledge: 0, governed: 0 }
  ];
  // 禁止数量与可回看的知识明细使用同一份示例，来源恢复不解除禁止。
  const forbiddenKnowledge = [
    { source: 'retail', name: '净销量与商品销售排行', content: '净销量为销售数量扣除退货数量；商品销售排行按净销量汇总排序。', dependency: 'sale_item.quantity', reason: '销售数量由 INT 改为 DECIMAL(12,3)，原有整数计算规则需修正并验证。' },
    { source: 'retail', name: '历史价格分析', content: '按商品和生效时间读取历史成本价，用于历史价格及毛利分析。', dependency: 'legacy_price.cost_price', reason: '依赖的成本价字段已删除，原有取值规则无法继续使用。' },
    { source: 'finance', name: '退货成本冲回口径', content: '退货发生时，按原销售成本冲回对应成本，并计入所属期间。', dependency: '销售与退货成本规则', reason: '原有规则未覆盖跨月退货，成本归属期间需重新确认。' }
  ];
  sources.forEach((source, index) => {
    const value = initial[index];
    Object.assign(source, { connectionState: value.state, health: value.health, collection: value.collection, lastFull: value.lastFull, lastCollected: index === 0 ? '2026-11-11 02:03:21' : value.lastFull, revision: 1, credentialSet: index < 3, paused: false, knowledge: value.knowledge, governed: value.governed, updated: '2026-11-11 08:30:00', history: [] });
    source.config = { ...configDefaults, schemas: [], account: index < 3 ? 'dataocean_reader' : '', version: index < 3 ? '8.0.46' : '', ...Object.fromEntries(Object.entries(value).filter(([key]) => key in configDefaults)) };
    source.forbiddenKnowledge = forbiddenKnowledge.filter(item => item.source === source.id);
    source.forbidden = source.forbiddenKnowledge.length;
  });
  const names = [
    ['sale_order', '销售订单'], ['sale_item', '销售明细'], ['product', '商品档案'], ['store', '门店档案'], ['return_order', '退货单'], ['return_item', '退货明细'], ['inventory', '库存余额'], ['inventory_movement', '库存流水'], ['supplier', '供应商'], ['purchase_order', '采购单'], ['purchase_item', '采购明细'], ['category', '商品分类'], ['brand', '品牌'], ['promotion', '促销活动'], ['payment', '收款记录'], ['member', '会员资料'], ['warehouse', '仓库'], ['transfer_order', '调拨单'], ['stocktake', '盘点单'], ['daily_sales_view', '每日销售汇总'], ['sale_item_202611', '销售明细十一月分区'], ['sys_config', '系统配置'], ['legacy_price', '历史价格']
  ];
  const tables = [];
  function object(source, name, label, index, schema = '') {
    const retail = source.id === 'retail', view = name.endsWith('_view');
    const row = { id: source.id + ':' + (schema ? schema + ':' : '') + name, source: source.id, schema, name, label, path: [source.config.database, schema, name].filter(Boolean).join('.'), type: view ? '视图' : name.includes('_202611') ? '分区表' : name.startsWith('sys_') ? '系统表' : '普通表', parent: name.includes('_202611') ? 'sale_item' : '', collection: retail && [8, 22].includes(index) ? '属性缺失' : '已采集', governance: index < source.governed ? '部分确认' : '待治理', version: 'S-003', collected: source.lastCollected, indexState: retail && [8, 22].includes(index) ? '无权限' : '已读取', commentState: name === 'sys_config' ? '不支持' : '已读取', identity: 'OBJ-' + source.id + '-' + (schema ? schema + '-' : '') + (index + 1), generation: 1, readState: source.id === 'finance' ? '来源不可用' : '未验证', comment: label, fields: [
      { name: name === 'sale_item' ? 'sale_item_id' : 'id', type: 'BIGINT', nullable: '否', default: '由数据库生成', comment: '主键', meaning: '记录编号' },
      { name: 'store_id', type: 'BIGINT', nullable: '否', default: '无默认值', comment: '门店编号', meaning: '业务发生门店' },
      { name: name === 'sale_item' ? 'product_id' : 'code', type: name === 'sale_item' ? 'BIGINT' : 'VARCHAR(64)', nullable: '否', default: '无默认值', comment: name === 'sale_item' ? '商品编号' : '业务编码', meaning: name === 'sale_item' ? '对应商品档案' : '待确认' },
      { name: name === 'sale_item' ? 'quantity' : 'status', type: name === 'sale_item' ? 'DECIMAL(12,3)' : 'SMALLINT', nullable: '否', default: name === 'sale_item' ? '0.000' : '1', comment: name === 'sale_item' ? '销售数量' : '', meaning: name === 'sale_item' ? '销售数量；退货口径需结合已确认定义' : '取值含义待确认' },
      { name: 'updated_at', type: 'DATETIME', nullable: '是', default: '无默认值', comment: '更新时间', meaning: '来源记录更新时间' }
    ] };
    // 普通视图按视图属性展示，不继承普通表的物理索引和生成默认值。
    if (view) { row.indexState = '不适用'; row.fields.forEach(field => { field.default = '—'; }); }
    if (row.commentState !== '已读取') row.comment = '';
    return row;
  }
  sources.forEach((source, index) => names.slice(0, [23, 8, 12, 0][index]).forEach(([name, label], i) => tables.push(object(source, name, label, i))));
  // 本次结构变化与治理复核分别显示；来源同步不覆盖已经确认的业务含义。
  tables.filter(table => table.source === 'retail' && ['sale_order', 'sale_item', 'legacy_price'].includes(table.name)).forEach(table => { table.collection = '结构变化'; table.governance = '待复核'; });
  sources.forEach(source => { source.governed = tables.filter(table => table.source === source.id && table.governance === '已确认').length; source.partialGoverned = tables.filter(table => table.source === source.id && table.governance === '部分确认').length; });
  const changes = [
    { id: 'CH-015', source: 'retail', taskId: 'CJ-0030', beforeSnapshot: 'S-029', afterSnapshot: 'S-030', object: 'sale_item', field: 'quantity', attribute: '字段类型', kind: '类型变化', before: 'INT', after: 'DECIMAL(12,3)', compatible: false, impact: '净销量、商品销售排行', affected: 2, control: '已禁止使用', state: '待复核', at: '2026-11-11 02:03:21', evidence: '相同来源对象标识；同范围枚举完整，字段类型已读取。', result: '' },
    { id: 'CH-014', source: 'retail', taskId: 'CJ-0030', beforeSnapshot: 'S-029', afterSnapshot: 'S-030', object: 'sale_order', field: 'paid_at', attribute: '字段定义', kind: '新增字段', before: '不存在', after: 'DATETIME，可空', compatible: true, impact: '销售时间口径待确认', affected: 1, control: '待治理', state: '待复核', at: '2026-11-11 02:03:21', evidence: '相同范围完整枚举，字段为本次新发现。', result: '' },
    { id: 'CH-013', source: 'retail', taskId: 'CJ-0029', beforeSnapshot: 'S-027', afterSnapshot: 'S-029', object: 'legacy_price', field: 'cost_price', attribute: '字段定义', kind: '删除字段', before: 'DECIMAL(12,2)', after: '已确认移除', compatible: false, impact: '历史价格分析', affected: 1, control: '已禁止使用', state: '待复核', at: '2026-11-10 14:03:21', evidence: '同范围枚举完整；来源负责人确认字段已删除。', result: '' },
    { id: 'CH-012', source: 'retail', taskId: 'CJ-0027', beforeSnapshot: 'S-025', afterSnapshot: 'S-027', object: 'supplier', field: '', attribute: '目录可见性', kind: '访问丢失', before: '目录可见', after: '来源待核实', compatible: null, impact: '供应商资料治理', affected: 1, control: '待核实', state: '待复核', at: '2026-11-09 14:03:21', evidence: '账号权限有变化，不能据此认定对象已删除。', result: '' },
    ...Array.from({ length: 11 }, (_, i) => ({ id: 'CH-' + String(11 - i).padStart(3, '0'), source: 'retail', taskId: 'CJ-' + String(24 - 2 * Math.floor(i / 3)).padStart(4, '0'), beforeSnapshot: 'S-' + String(23 - 2 * Math.floor(i / 3)).padStart(3, '0'), afterSnapshot: 'S-' + String(24 - 2 * Math.floor(i / 3)).padStart(3, '0'), object: names[i + 2][0], field: '', attribute: '表注释', kind: '修改注释', before: names[i + 2][1], after: names[i + 2][1] + '（业务系统）', compatible: true, impact: '对应对象说明需复核', affected: 1, control: '未禁止', state: i < 2 ? '待复核' : '已复核', at: '2026-11-' + String(8 - Math.floor(i / 3)).padStart(2, '0') + ' 02:03:21', evidence: '来源注释改变，结构未发现不兼容；人工定义保留。', result: i < 2 ? '' : '已核对来源，交治理更新说明。', reviewer: i < 2 ? '' : '杨分析师', reviewed: i < 2 ? '' : '2026-11-' + String(8 - Math.floor(i / 3)).padStart(2, '0') + ' 09:00:00' }))
  ];
  // 预置变化与当前目录保持一致；这里只对齐示例，不实现真实差异生成。
  tables.find(table => table.source === 'retail' && table.name === 'sale_order').fields.push({ name: 'paid_at', type: 'DATETIME', nullable: '是', default: '无默认值', comment: '支付时间', meaning: '待确认' });
  changes.filter(change => change.kind === '修改注释').forEach(change => { tables.find(table => table.source === change.source && table.name === change.object).comment = change.after; });
  // 只有实际字段名进入限定路径；注释、可见性等属性单独作为变化项。
  const changeTarget = change => change.field ? change.object + '.' + change.field : change.object;
  // 以同源完整对象名关联，避免 sale_order 与其分区、同名其他源混在一起。
  const changesFor = table => changes.filter(change => change.source === table.source && change.object === (table.schema ? table.schema + '.' : '') + table.name);
  const pendingVerification = table => changesFor(table).filter(change => change.fact === '待核实');
  // 目录关注项可同时存在，结构变化不能掩盖同一对象的属性读取缺项。
  const catalogStates = table => {
    const missing = [table.indexState, table.commentState].some(state => ['无权限', '读取失败', '未知', '未读取'].includes(state));
    const related = changesFor(table), progress = window.GovernanceImpactDemo?.progress;
    const active = related.some(change => !progress || progress(change) !== '已完成');
    const accessPending = pendingVerification(table).some(change => change.kind === '访问丢失');
    let collection = table.collection;
    if (active) collection = accessPending ? '访问丢失待核实' : '结构变化';
    else if (related.length && collection === '结构变化') collection = '已采集';
    const states = [missing && collection === '已采集' ? '属性缺失' : collection];
    if (missing && !states.includes('属性缺失')) states.push('属性缺失');
    return states;
  };
  // 已处理包含三类结果；读取了可靠属性但有缺项的对象不计作读取失败。
  const tasks = Array.from({ length: 15 }, (_, i) => {
    const partialObjects = i === 0 ? ['supplier', 'legacy_price'] : [], failures = i === 2 ? ['purchase_order', 'purchase_item'] : [];
    const processed = i === 2 ? 10 : i === 4 ? 8 : 23;
    return { id: 'CJ-' + String(30 - i).padStart(4, '0'), source: 'retail', configRevision: 1, trigger: i % 3 ? '定时' : '手动', actor: i % 3 ? '系统调度' : '林管理员', at: '2026-11-' + String(11 - Math.ceil(i / 2)).padStart(2, '0') + (i % 2 ? ' 14:00:00' : ' 02:00:00'), ended: '2026-11-' + String(11 - Math.ceil(i / 2)).padStart(2, '0') + (i % 2 ? ' 14:03:21' : ' 02:03:21'), state: i === 0 ? '部分成功' : i === 2 ? '失败' : i === 4 ? '已取消' : '完整成功', enumeration: i !== 2 && i !== 4, total: i === 2 || i === 4 ? null : 23, discovered: i === 2 ? 12 : 23, processed, complete: processed - partialObjects.length - failures.length, partial: partialObjects.length, failed: failures.length, partialObjects, failures, version: 'S-' + String(30 - i).padStart(3, '0'), scope: 'retail · 全部可见对象', account: 'dataocean_reader', reason: i === 0 ? 'supplier、legacy_price 的索引目录无权限；已保留字段等可靠属性。' : i === 2 ? '枚举中途超时，全部数量未知；未据此判断对象删除。' : i === 4 ? '发起人取消，保留已完成范围。' : '', databaseState: '已确认结束', parent: '' };
  });
  tasks.push({ ...tasks[0], id: 'CJ-F001', source: 'finance', at: '2026-11-11 02:00:00', state: '失败', enumeration: false, discovered: 0, total: null, processed: 0, complete: 0, partial: 0, failed: 0, partialObjects: [], scope: 'finance · 全部可见对象', reason: '网络连接超时。请检查连接路径，再重新测试。', failures: [] });
  tasks.push({ ...tasks[1], id: 'CJ-P001', source: 'products', version: 'S-003', at: '2026-11-11 03:00:00', ended: '2026-11-11 03:01:10', state: '完整成功', enumeration: true, discovered: 12, total: 12, processed: 12, complete: 12, failed: 0, scope: 'products · 全部可见对象', reason: '', failures: [] });
  // 最近采集包含失败和取消的尝试；对象本身保留可靠属性的采集时间。
  sources.forEach(source => { const latest = tasks.filter(task => task.source === source.id).sort((a, b) => b.at.localeCompare(a.at))[0]; if (latest) source.lastCollected = latest.ended || latest.at; });
  tables.filter(table => table.source === 'retail').forEach(table => { table.version = tasks[0].version; });
  const noteTitles = ['销售与退货口径', '库存流转与盘点说明', '促销折扣计算规则', '门店与仓库的业务关系', '商品分类维护约定', '采购入库与验收说明', '销售时间与营业日', '会员订单统计口径', '作废单据的处理方式', '线上订单与门店归属', '调拨出入库业务流程', '价格调整说明', '支付渠道与对账规则', '供应商结算说明', '历史系统迁移注意事项'];
  const notes = noteTitles.map((title, i) => ({ id: 'ZL-' + String(15 - i).padStart(3, '0'), source: 'retail', title, content: i === 0 ? '本系统管理超市商品、库存和销售。销售额按实际收款计算，退货需要扣除。作废单据不计入销售。不同门店的营业日以各自关账时间为准，未关账的当天数据可能继续变化。' : title + '。请结合当前有效业务制度核对，尚不明确的技术映射留待治理确认。', files: i % 4 === 1 ? [{ name: title + '.pdf', size: 245760 }] : [], author: i % 3 ? 'u02' : 'u01', revision: 1, status: i === 14 ? '已撤下' : '有效', updated: '2026-11-' + String(11 - Math.floor(i / 2)).padStart(2, '0') + ' 10:00:00', history: [] }));
  // 引用绑定实际修订；历史任务和历史成果仍需保留来源，不能只检查当前版本。
  const noteUses = [
    { id: 'CG-021', noteId: 'ZL-015', revision: 1, kind: '治理成果', name: '销售额与退货扣减口径', state: '已确认', actor: 'u12', at: '2026-11-11 11:20:00', method: '人工治理依据', purpose: '确认销售额、退货扣减和作废单据的统计口径。', excerpt: '销售额按实际收款计算，退货需要扣除。作废单据不计入销售。', result: '销售额按实际收款统计，扣除退货，排除作废单据。', reviewer: 'u11', reviewedAt: '2026-11-11 14:10:00' },
    { id: 'RW-008', noteId: 'ZL-014', revision: 1, kind: '治理任务', name: '库存与盘点规则核对', state: '进行中', actor: 'u11', at: '2026-11-11 11:30:00', method: 'AI 治理输入', purpose: '将该版正文及附件作为输入，辅助整理库存流转、盘点差异和待确认问题。', result: '正在整理业务规则，尚未形成已确认成果。' },
    { id: 'RW-006', noteId: 'ZL-013', revision: 1, kind: '治理任务', name: '促销折扣规则识别', state: '已完成', actor: 'u12', at: '2026-11-09 11:00:00', method: 'AI 治理输入', purpose: '依据第 1 版正文识别促销折扣规则和信息缺口。', result: '已生成待人工确认的建议；优惠叠加规则仍需补充。', ended: '2026-11-09 11:08:00' },
    { id: 'CG-009', noteId: 'ZL-001', revision: 1, kind: '治理成果', name: '历史订单归属说明', state: '已确认', actor: 'u11', at: '2026-11-04 09:00:00', method: '人工治理依据', purpose: '核对历史订单归属说明，保留原业务资料作为确认依据。', result: '技术映射仍按已确认的对象定义核对，不从迁移说明推断关联键。', reviewer: 'u11', reviewedAt: '2026-11-04 09:30:00' }
  ];
  const revisedNote = notes.find(item => item.id === 'ZL-013');
  revisedNote.history.push({ revision: 1, updated: '2026-11-09 10:00:00', title: revisedNote.title, content: '促销折扣按订单记录核对；叠加优惠规则待补充。', files: [] });
  revisedNote.revision = 2;
  notes.find(item => item.id === 'ZL-001').reason = '历史系统已退出日常使用，原有治理依据保留。';
  const noteEvents = [{ noteId: 'ZL-001', revision: 1, action: '撤下', actor: '林管理员', at: '2026-11-04 10:00:00', reason: notes.find(item => item.id === 'ZL-001').reason }];
  const noteUsesFor = item => noteUses.filter(use => use.noteId === item.id);
  const noteToken = item => item.revision + ':' + item.status + ':' + noteEvents.filter(event => event.noteId === item.id).length;
  function noteDeleteBlock(item) {
    const uses = noteUsesFor(item);
    if (!uses.length) return;
    const active = uses.filter(use => use.kind === '治理任务' && ['排队', '进行中'].includes(use.state));
    const example = (active[0] || uses[0]), target = example.kind + '「' + (example.name || example.id || '未命名记录') + '」';
    const reason = active.length ? target + '正在取用第 ' + example.revision + ' 版，不能删除。' : target + '已引用第 ' + example.revision + ' 版，需保留来源，不能删除。';
    return reason + (uses.length > 1 ? '共 ' + uses.length + ' 条引用。' : '') + '可在资料详情的“引用记录”查看。' + (item.status === '有效' ? '撤下可停止后续取用，已有引用保留。' : '撤下后已有引用仍保留。');
  }
  function changeNote(item, action, data, actor, token) {
    const ability = { '撤下': 'withdraw', '恢复': 'restore', '删除': 'delete' }[action];
    if (!ability || !SourceApprovalDemo.hasAbility(actor, 'business-notes.' + ability)) return '当前账号没有' + action + '资料的权限。';
    if (!notes.includes(item) || noteToken(item) !== token) return '资料已变化，请关闭后重新核对。';
    if (action === '删除') { const blocked = noteDeleteBlock(item); if (blocked) return blocked; }
    else if (item.status !== (action === '撤下' ? '有效' : '已撤下')) return '资料状态已变化，请重新核对。';
    const reason = (data.reason || '').trim();
    if (action === '撤下' && !reason) return '请填写撤下原因。';
    const at = now();
    noteEvents.unshift({ noteId: item.id, source: item.source, title: item.title, revision: item.revision, action, actor: actor.name, at, reason });
    if (action === '删除') notes.splice(notes.indexOf(item), 1);
    else Object.assign(item, { status: action === '撤下' ? '已撤下' : '有效', reason: action === '撤下' ? reason : '', updated: at });
    if (action === '撤下') window.GovernanceImpactDemo?.onWithdraw(item);
  }
  const find = id => sources.find(source => source.id === id);
  const objects = id => tables.filter(table => table.source === id && (find(id).config.type !== 'PostgreSQL' || find(id).config.schemas.includes(table.schema)));
  const activeTask = id => tasks.find(task => task.source === id && ['排队', '进行中'].includes(task.state));
  const latestTask = id => tasks.filter(task => task.source === id).sort((a, b) => b.at.localeCompare(a.at))[0];
  // 治理分类和目录数量使用同一组对象，避免把字段数、任务范围或旧汇总混入分母。
  function governanceSummary(id) {
    const rows = objects(id);
    return { total: rows.length, confirmed: rows.filter(row => row.governance === '已确认').length, partial: rows.filter(row => row.governance === '部分确认').length, pending: rows.filter(row => row.governance === '待治理').length, review: rows.filter(row => row.governance === '待复核').length };
  }
  const configuration = source => ({ ...source.config, schemas: [...source.config.schemas], name: source.name });
  const scopeLabel = source => source.config.database + (source.config.type === 'PostgreSQL' ? ' / ' + (source.config.schemas.join('、') || '尚未选择 Schema') : '');
  // 服务端识别用固定响应表达；版本不取自用户填写值，失败不伪造识别结果。
  const serverInfo = type => ({ version: type === 'PostgreSQL' ? '16.4' : '8.0.46', schemas: type === 'PostgreSQL' ? ['public', 'sales', 'finance'] : [] });
  function scopeError(config, available) {
    if (config.type !== 'PostgreSQL') return;
    if (!config.schemas.length) return '请选择至少一个 Schema。';
    if (config.schemas.some(name => !available.includes(name))) return '所选 Schema 当前不可见，请重新选择。';
  }
  function nameError(name, exceptId) {
    if (!name?.trim()) return '请填写数据源名称。';
    const normalized = value => value.normalize('NFKC').trim().toLocaleLowerCase('zh-CN');
    if (sources.some(source => source.id !== exceptId && normalized(source.name) === normalized(name))) return '数据源名称已存在。';
  }
  function error(config, exceptId) {
    const invalidName = nameError(config.name, exceptId); if (invalidName) return invalidName;
    if (!config.host?.trim() || !config.database?.trim() || !config.account?.trim()) return '请补齐地址、数据库和只读账号。';
    if (!Number.isInteger(Number(config.port)) || config.port < 1 || config.port > 65535) return '端口应为 1–65535 的整数。';
  }
  function testResults(config, source) {
    const fault = source?.id === 'finance' && config.host === source.config.host;
    const steps = ['配置有效性', '网络与加密', '账户认证', '目录枚举', '结构读取', '业务只读能力', '限制与兼容性'];
    const details = ['必填及参数格式已核对', 'TLS 握手及服务器证书校验通过', '只读账号认证通过', '仅枚举配置范围内、账号可见对象', '字段、键、索引和注释分别记录读取状态', '权限探测不返回业务行，不进行试写', '已识别 ' + config.type + ' ' + serverInfo(config.type).version + ' · 适配能力已核对'];
    return steps.map((label, index) => ({ label, state: fault && index === 1 ? '失败' : fault && index > 1 ? '未验证' : '通过', detail: fault && index === 1 ? '连接超时，请检查网络路径或修改连接地址。' : fault && index > 1 ? '前序步骤失败，尚未执行' : details[index] }));
  }
  const canEnable = results => results?.length === 7 && results.every(item => item.state === '通过');
  function inspectConnection(config, source) {
    const results = testResults(config, source);
    return { results, ...(canEnable(results) ? serverInfo(config.type) : { version: '', schemas: [] }) };
  }
  // 原型仅保存凭据是否已配置；原始输入不进入配置、任务、修订或本地存储。
  function configChanged(data, source, credentialChanged = false) {
    if (!source || credentialChanged || data.name.trim() !== source.name) return true;
    return Object.keys(configDefaults).filter(key => key !== 'version').some(key => {
      if (key === 'schemas') return JSON.stringify([...(data.type === 'PostgreSQL' ? data.schemas || [] : [])].sort()) !== JSON.stringify([...source.config.schemas].sort());
      return (data[key] ?? configDefaults[key]) !== source.config[key];
    });
  }
  function saveConfig(data, source, enabled, credentialChanged) {
    // 启用已有草稿是状态变化；无内容修改不制造新的配置修订。
    if (source && !configChanged(data, source, credentialChanged)) {
      if (enabled && source.connectionState === '草稿') {
        source.connectionState = '已启用'; source.health = '正常'; source.updated = now();
        source.history.unshift({ revision: source.revision, at: source.updated, action: '启用已保存配置', name: source.name, account: source.config.account });
      }
      return source;
    }
    const safe = Object.fromEntries(Object.keys(configDefaults).map(key => [key, data[key] ?? configDefaults[key]]));
    safe.schemas = safe.type === 'PostgreSQL' ? [...safe.schemas] : [];
    const scopeChanged = source && JSON.stringify([...source.config.schemas].sort()) !== JSON.stringify([...safe.schemas].sort());
    if (!source) {
      source = { id: 'source-' + (++sequence), name: data.name.trim(), config: safe, connectionState: '草稿', health: '未测试', collection: '未采集', lastFull: '', lastCollected: '', revision: 0, credentialSet: false, paused: false, knowledge: 0, forbidden: 0, forbiddenKnowledge: [], governed: 0, partialGoverned: 0, history: [], approval: { primary: '', alternate: '', revision: 1, updated: '' } };
      sources.unshift(source);
    }
    source.history.unshift({ revision: source.revision + 1, at: now(), action: enabled ? '测试通过并启用配置' : source.connectionState === '草稿' ? '保存连接草稿' : '测试通过并保存配置', name: data.name.trim(), account: safe.account });
    source.name = data.name.trim(); source.config = safe; source.revision++; source.updated = now();
    source.credentialSet = source.credentialSet || credentialChanged;
    if (enabled && source.connectionState === '草稿') { source.connectionState = '已启用'; source.health = '正常'; }
    // 旧的完整采集只证明旧范围，范围改变后等待重新采集，历史任务仍保留原结论。
    if (scopeChanged && source.lastCollected) { source.collection = '待重新采集'; source.lastFull = ''; }
    return source;
  }
  const retryTargets = task => [...new Set([...(task.partialObjects || []), ...task.failures])];
  function startTask(source, parent) {
    if (source.connectionState !== '已启用') return { error: '数据源尚未启用，不能采集。' };
    if (source.config.type === 'PostgreSQL' && !source.config.schemas.length) return { error: '请先核定 Schema 范围。' };
    if (activeTask(source.id)) return { error: '该数据源已有进行中的采集，请先查看当前任务。' };
    const targets = parent ? retryTargets(parent) : [], retry = parent && parent.enumeration && targets.length;
    const task = { id: 'CJ-' + (++sequence), source: source.id, configRevision: source.revision, trigger: parent ? '重试' : '手动', actor: '林管理员', at: now(), ended: '', state: '排队', enumeration: false, total: null, discovered: 0, processed: 0, complete: 0, partial: 0, failed: 0, partialObjects: [], version: 'S-' + String(++snapshotSequence).padStart(3, '0'), scope: scopeLabel(source) + (retry ? ' · 未完整对象：' + targets.join('、') : ' · 全部可见对象'), schemas: [...source.config.schemas], account: source.config.account, reason: '', databaseState: '尚未开始', failures: [], parent: parent?.id || '', retryOnly: Boolean(retry), retryObjects: retry ? targets : [] };
    tasks.unshift(task); return { task };
  }
  // 简单任务分段仅用于评审进度与取消；不拼接新旧结果，不改写历史任务结论。
  function advanceTask(task) {
    if (!['排队', '进行中'].includes(task.state)) return;
    const source = find(task.source), count = task.retryOnly ? task.retryObjects.length : source.config.type === 'PostgreSQL' ? task.schemas.length * 6 : objects(task.source).length || 6;
    if (task.state === '排队') { task.state = '进行中'; task.databaseState = '采集中'; return; }
    if (source.health !== '正常') { Object.assign(task, { state: '失败', reason: '网络连接不可用，请先测试并修复连接。', ended: now(), databaseState: '已确认结束' }); source.lastCollected = task.ended; if (!task.retryOnly) source.collection = '失败'; return; }
    const objectNames = task.retryOnly ? task.retryObjects : objects(source.id).map(table => table.name);
    const partialFor = names => names.filter(name => objects(source.id).find(table => table.name === name)?.indexState === '无权限');
    if (!task.enumeration) {
      const processed = Math.ceil(count / 2), partialObjects = partialFor(objectNames.slice(0, processed));
      Object.assign(task, { enumeration: true, total: count, discovered: count, processed, complete: processed - partialObjects.length, partial: partialObjects.length, partialObjects }); return;
    }
    const partialObjects = partialFor(objectNames);
    Object.assign(task, { processed: count, complete: count - partialObjects.length, partial: partialObjects.length, partialObjects, failed: 0, failures: [], state: partialObjects.length ? '部分成功' : '完整成功', ended: now(), databaseState: '已确认结束', reason: partialObjects.length ? '索引目录无权限。请数据库负责人核对读取权限，成功属性已保留。' : '' });
    // 相同表名按 Schema 区分；变更范围后保留旧记录，当前目录只呈现选定范围。
    if (source.config.type === 'PostgreSQL') task.schemas.forEach(schema => names.slice(0, 6).forEach(([name, label], index) => { const row = object(source, name, label, index, schema); if (!tables.some(table => table.id === row.id)) tables.push(row); }));
    else if (!objects(source.id).length) names.slice(0, 6).forEach(([name, label], index) => tables.push(object(source, name, label, index)));
    source.lastCollected = task.ended;
    // 子范围重试不能把整源目录标为完整；每个对象保留本次结构来源。
    if (!task.retryOnly) { source.collection = task.state; if (!partialObjects.length) source.lastFull = task.ended; }
    objects(source.id).filter(table => !task.retryOnly || task.retryObjects.includes(table.name)).forEach(table => { table.collected = task.ended; table.version = task.version; });
  }
  function cancelTask(task, reason = '发起人取消采集') {
    if (!['排队', '进行中'].includes(task.state)) return false;
    Object.assign(task, { state: '已取消', ended: now(), databaseState: '已确认结束', reason: reason.trim() || '发起人取消采集' });
    const source = find(task.source); source.lastCollected = task.ended;
    if (!task.retryOnly) source.collection = '已取消';
    return true;
  }
  function lifecycle(source, action, reason) {
    const expected = { '暂停定时采集': '已启用', '恢复定时采集': '已启用', '停用数据源': '已启用', '恢复数据源': '已停用', '退役数据源': '已停用' }[action];
    if (source.connectionState !== expected) return '数据源状态已变化，请重新核对。';
    if (action === '暂停定时采集' && source.paused || action === '恢复定时采集' && !source.paused) return '定时采集状态未变化。';
    if (action === '暂停定时采集') source.paused = true;
    if (action === '恢复定时采集') source.paused = false;
    if (action === '停用数据源') { source.connectionState = '已停用'; const task = activeTask(source.id); if (task) cancelTask(task, '停用数据源：' + reason.trim()); }
    if (action === '恢复数据源') { source.connectionState = '已启用'; source.health = '正常'; }
    if (action === '退役数据源') source.connectionState = '已退役';
    source.updated = now(); source.history.unshift({ revision: source.revision, at: now(), action, reason });
  }
  window.DataSourceDemo = { sources, tables, tasks, notes, noteUses, noteEvents, noteUsesFor, noteToken, noteDeleteBlock, changeNote, changes, changeTarget, changesFor, pendingVerification, catalogStates, now, find, objects, activeTask, latestTask, governanceSummary, configuration, configDefaults, connectionPolicy, scopeLabel, scopeError, inspectConnection, nameError, error, testResults, canEnable, configChanged, saveConfig, startTask, retryTargets, advanceTask, cancelTask, lifecycle,
    get selected() { return selected; }, set selected(value) { if (find(value)) selected = value; }, nextId: prefix => prefix + (++sequence) };
})();
