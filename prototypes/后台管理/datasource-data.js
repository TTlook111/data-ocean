/* 数据源原型只维护页面内的连接摘要、结构和任务；不联网、不保存凭据、不执行 SQL。 */
(() => {
  'use strict';
  const sources = PeopleDemo.dataSources;
  const now = () => new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 19);
  let sequence = 100, selected = 'retail';
  // 联系人只供查看后自行联系；新增时不预选，不作为连接或采集前提。
  const configDefaults = { type: 'MySQL', version: '', purpose: '', technical: '', business: '', host: '', port: 3306, database: '', schemas: [], account: '', connections: 4, concurrency: 2, retries: 2, taskTimeout: 600, queryTimeout: 30, rowLimit: 1000, syncHours: 24, window: '02:00–05:00' };
  // 连接保护归平台策略；只表达原型默认约束，不提供逐数据源关闭校验的入口。
  const connectionPolicy = Object.freeze({ verifyServerCertificate: true, connectTimeoutSeconds: 15 });
  const initial = [
    { state: '已启用', health: '正常', host: 'retail-db.example', database: 'retail', collection: '部分成功', lastFull: '2026-11-10 14:03:21', knowledge: 8, forbidden: 2, governed: 16, technical: 'u11', business: 'u02' },
    { state: '已启用', health: '网络异常', host: 'finance-db.example', database: 'finance', collection: '失败', lastFull: '2026-11-08 02:04:20', knowledge: 3, forbidden: 1, governed: 6, technical: 'u11', business: 'u13' },
    { state: '已启用', health: '正常', host: 'products-db.example', database: 'products', collection: '完整成功', lastFull: '2026-11-11 03:01:10', knowledge: 4, forbidden: 0, governed: 9, technical: 'u11', business: 'u09' },
    { state: '草稿', health: '未测试', host: '', database: '', collection: '未采集', lastFull: '', knowledge: 0, forbidden: 0, governed: 0 }
  ];
  sources.forEach((source, index) => {
    const value = initial[index];
    Object.assign(source, { connectionState: value.state, health: value.health, collection: value.collection, lastFull: value.lastFull, lastCollected: index === 0 ? '2026-11-11 02:03:21' : value.lastFull, revision: 1, credentialSet: index < 3, paused: false, knowledge: value.knowledge, forbidden: value.forbidden, governed: value.governed, updated: '2026-11-11 08:30:00', history: [] });
    source.config = { ...configDefaults, schemas: [], account: index < 3 ? 'dataocean_reader' : '', version: index < 3 ? '8.0.46' : '', ...Object.fromEntries(Object.entries(value).filter(([key]) => key in configDefaults)) };
  });
  const names = [
    ['sale_order', '销售订单'], ['sale_item', '销售明细'], ['product', '商品档案'], ['store', '门店档案'], ['return_order', '退货单'], ['return_item', '退货明细'], ['inventory', '库存余额'], ['inventory_movement', '库存流水'], ['supplier', '供应商'], ['purchase_order', '采购单'], ['purchase_item', '采购明细'], ['category', '商品分类'], ['brand', '品牌'], ['promotion', '促销活动'], ['payment', '收款记录'], ['member', '会员资料'], ['warehouse', '仓库'], ['transfer_order', '调拨单'], ['stocktake', '盘点单'], ['daily_sales_view', '每日销售汇总'], ['sale_item_202611', '销售明细十一月分区'], ['sys_config', '系统配置'], ['legacy_price', '历史价格']
  ];
  const tables = [];
  function object(source, name, label, index, schema = '') {
    const retail = source.id === 'retail';
    return { id: source.id + ':' + (schema ? schema + ':' : '') + name, source: source.id, schema, name, label, path: [source.config.database, schema, name].filter(Boolean).join('.'), type: name.endsWith('_view') ? '视图' : name.includes('_202611') ? '分区表' : name.startsWith('sys_') ? '系统表' : '普通表', parent: name.includes('_202611') ? 'sale_item' : '', collection: retail && [8, 22].includes(index) ? '属性缺失' : '已采集', governance: index < source.governed ? '已确认' : '待治理', version: 'S-003', collected: source.lastCollected, indexState: retail && [8, 22].includes(index) ? '无权限' : '已读取', commentState: name === 'sys_config' ? '不支持' : '已读取', identity: 'OBJ-' + source.id + '-' + (schema ? schema + '-' : '') + (index + 1), generation: 1, forbidden: name === 'legacy_price', readState: source.id === 'finance' ? '来源不可用' : '未验证', comment: label, fields: [
      { name: name === 'sale_item' ? 'sale_item_id' : 'id', type: 'BIGINT', nullable: '否', default: '由数据库生成', comment: '主键', meaning: '记录编号' },
      { name: 'store_id', type: 'BIGINT', nullable: '否', default: '无默认值', comment: '门店编号', meaning: '业务发生门店' },
      { name: name === 'sale_item' ? 'product_id' : 'code', type: name === 'sale_item' ? 'BIGINT' : 'VARCHAR(64)', nullable: '否', default: '无默认值', comment: name === 'sale_item' ? '商品编号' : '业务编码', meaning: name === 'sale_item' ? '对应商品档案' : '待确认' },
      { name: name === 'sale_item' ? 'quantity' : 'status', type: name === 'sale_item' ? 'DECIMAL(12,3)' : 'SMALLINT', nullable: '否', default: name === 'sale_item' ? '0.000' : '1', comment: name === 'sale_item' ? '销售数量' : '', meaning: name === 'sale_item' ? '销售数量；退货口径需结合已确认定义' : '取值含义待确认' },
      { name: 'updated_at', type: 'DATETIME', nullable: '是', default: '无默认值', comment: '更新时间', meaning: '来源记录更新时间' }
    ] };
  }
  sources.forEach((source, index) => names.slice(0, [23, 8, 12, 0][index]).forEach(([name, label], i) => tables.push(object(source, name, label, i))));
  // 本次结构变化与治理复核分别显示；来源同步不覆盖已经确认的业务含义。
  tables.filter(table => table.source === 'retail' && ['sale_order', 'sale_item', 'legacy_price'].includes(table.name)).forEach(table => { table.collection = '结构变化'; table.governance = '待复核'; });
  sources.forEach(source => { source.governed = tables.filter(table => table.source === source.id && table.governance === '已确认').length; });
  const changes = [
    { id: 'CH-015', source: 'retail', object: 'sale_item', field: 'quantity', kind: '类型变化', before: 'INT', after: 'DECIMAL(12,3)', compatible: false, impact: '净销量、商品销售排行', affected: 2, control: '已禁止使用', state: '待复核', at: '2026-11-11 02:03:21', evidence: '相同来源对象标识；同范围枚举完整，字段类型已读取。', result: '' },
    { id: 'CH-014', source: 'retail', object: 'sale_order', field: 'paid_at', kind: '新增字段', before: '不存在', after: 'DATETIME，可空', compatible: true, impact: '销售时间口径待确认', affected: 1, control: '待治理', state: '待复核', at: '2026-11-11 02:03:21', evidence: '相同范围完整枚举，字段为本次新发现。', result: '' },
    { id: 'CH-013', source: 'retail', object: 'legacy_price', field: 'cost_price', kind: '删除字段', before: 'DECIMAL(12,2)', after: '已确认移除', compatible: false, impact: '历史价格分析', affected: 1, control: '已禁止使用', state: '待复核', at: '2026-11-10 02:03:21', evidence: '同范围枚举完整；来源负责人确认字段已删除。', result: '' },
    { id: 'CH-012', source: 'retail', object: 'supplier', field: '目录可见性', kind: '访问丢失', before: '目录可见', after: '来源待核实', compatible: null, impact: '供应商资料治理', affected: 1, control: '待核实', state: '待复核', at: '2026-11-09 02:03:21', evidence: '账号权限有变化，不能据此认定对象已删除。', result: '' },
    ...Array.from({ length: 11 }, (_, i) => ({ id: 'CH-' + String(11 - i).padStart(3, '0'), source: 'retail', object: names[i + 2][0], field: '来源注释', kind: '修改注释', before: names[i + 2][1], after: names[i + 2][1] + '（业务系统）', compatible: true, impact: '对应对象说明需复核', affected: 1, control: '待复核', state: i < 2 ? '待复核' : '已复核', at: '2026-11-' + String(8 - Math.floor(i / 3)).padStart(2, '0') + ' 02:03:21', evidence: '来源注释改变，结构未发现不兼容；人工定义保留。', result: i < 2 ? '' : '已核对来源，交治理更新说明。' }))
  ];
  const tasks = Array.from({ length: 15 }, (_, i) => ({ id: 'CJ-' + String(30 - i).padStart(4, '0'), source: 'retail', trigger: i % 3 ? '定时' : '手动', actor: i % 3 ? '系统调度' : '林管理员', at: '2026-11-' + String(11 - Math.ceil(i / 2)).padStart(2, '0') + (i % 2 ? ' 14:00:00' : ' 02:00:00'), ended: '2026-11-' + String(11 - Math.ceil(i / 2)).padStart(2, '0') + (i % 2 ? ' 14:03:21' : ' 02:03:21'), state: i === 0 ? '部分成功' : i === 2 ? '失败' : i === 4 ? '已取消' : '完整成功', enumeration: i !== 2 && i !== 4, total: i === 2 || i === 4 ? null : 23, discovered: i === 2 ? 12 : 23, processed: i === 2 ? 10 : i === 4 ? 8 : 23, failed: i === 0 ? 2 : i === 2 ? 2 : 0, version: 'S-' + String(30 - i).padStart(3, '0'), scope: 'retail · 全部可见对象', account: 'dataocean_reader', reason: i === 0 ? 'supplier、legacy_price 的索引目录无权限；已保留字段等可靠属性。' : i === 2 ? '枚举中途超时，全部数量未知；未据此判断对象删除。' : i === 4 ? '发起人取消，保留已完成范围。' : '', databaseState: '已确认结束', failures: i === 0 ? ['supplier', 'legacy_price'] : i === 2 ? ['purchase_order', 'purchase_item'] : [], parent: '' }));
  tasks.push({ ...tasks[0], id: 'CJ-F001', source: 'finance', at: '2026-11-11 02:00:00', state: '失败', enumeration: false, discovered: 0, total: null, processed: 0, failed: 0, scope: 'finance · 全部可见对象', reason: '网络连接超时。请检查连接路径，再重新测试。', failures: [] });
  tasks.push({ ...tasks[1], id: 'CJ-P001', source: 'products', at: '2026-11-11 03:00:00', ended: '2026-11-11 03:01:10', state: '完整成功', enumeration: true, discovered: 12, total: 12, processed: 12, failed: 0, scope: 'products · 全部可见对象', reason: '', failures: [] });
  tables.filter(table => table.source === 'retail').forEach(table => { table.version = tasks[0].version; });
  const noteTitles = ['销售与退货口径', '库存流转与盘点说明', '促销折扣计算规则', '门店与仓库的业务关系', '商品分类维护约定', '采购入库与验收说明', '销售时间与营业日', '会员订单统计口径', '作废单据的处理方式', '线上订单与门店归属', '调拨出入库业务流程', '价格调整说明', '支付渠道与对账规则', '供应商结算说明', '历史系统迁移注意事项'];
  const notes = noteTitles.map((title, i) => ({ id: 'ZL-' + String(15 - i).padStart(3, '0'), source: 'retail', title, content: i === 0 ? '本系统管理超市商品、库存和销售。销售额按实际收款计算，退货需要扣除。作废单据不计入销售。不同门店的营业日以各自关账时间为准，未关账的当天数据可能继续变化。' : title + '。请结合当前有效业务制度核对，尚不明确的技术映射留待治理确认。', files: i % 4 === 1 ? [{ name: title + '.pdf', size: 245760 }] : [], author: i % 3 ? 'u02' : 'u01', revision: i === 0 ? 3 : 1, status: i === 14 ? '已撤下' : '有效', updated: '2026-11-' + String(11 - Math.floor(i / 2)).padStart(2, '0') + ' 10:00:00', viewers: [], history: [] }));
  const find = id => sources.find(source => source.id === id);
  const objects = id => tables.filter(table => table.source === id && (find(id).config.type !== 'PostgreSQL' || find(id).config.schemas.includes(table.schema)));
  const activeTask = id => tasks.find(task => task.source === id && ['排队', '进行中'].includes(task.state));
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
  function saveConfig(data, source, enabled, credentialChanged) {
    const safe = Object.fromEntries(Object.keys(configDefaults).map(key => [key, data[key] ?? configDefaults[key]]));
    safe.schemas = safe.type === 'PostgreSQL' ? [...safe.schemas] : [];
    const scopeChanged = source && JSON.stringify([...source.config.schemas].sort()) !== JSON.stringify([...safe.schemas].sort());
    if (!source) {
      source = { id: 'source-' + (++sequence), name: data.name.trim(), config: safe, connectionState: '草稿', health: '未测试', collection: '未采集', lastFull: '', lastCollected: '', revision: 0, credentialSet: false, paused: false, knowledge: 0, forbidden: 0, governed: 0, history: [], approval: { primary: '', alternate: '', revision: 1, updated: '' } };
      sources.unshift(source);
    }
    source.history.unshift({ revision: source.revision + 1, at: now(), action: enabled ? '测试通过并启用配置' : source.connectionState === '草稿' ? '保存连接草稿' : '测试通过并保存配置', name: data.name.trim(), account: safe.account });
    source.name = data.name.trim(); source.config = safe; source.revision++; source.updated = now();
    source.credentialSet = source.credentialSet || credentialChanged;
    if (enabled) { source.connectionState = '已启用'; source.health = '正常'; }
    // 旧的完整采集只证明旧范围，范围改变后等待重新采集，历史任务仍保留原结论。
    if (scopeChanged && source.lastCollected) { source.collection = '待重新采集'; source.lastFull = ''; }
    return source;
  }
  function startTask(source, parent) {
    if (source.connectionState !== '已启用') return { error: '数据源尚未启用，不能采集。' };
    if (source.config.type === 'PostgreSQL' && !source.config.schemas.length) return { error: '请先核定 Schema 范围。' };
    if (activeTask(source.id)) return { error: '该数据源已有进行中的采集，请先查看当前任务。' };
    const retry = parent && parent.enumeration && parent.failures.length;
    const task = { id: 'CJ-' + (++sequence), source: source.id, trigger: parent ? '重试' : '手动', actor: '林管理员', at: now(), ended: '', state: '排队', enumeration: false, total: null, discovered: 0, processed: 0, failed: 0, version: 'S-' + (++sequence), scope: scopeLabel(source) + (retry ? ' · 失败对象：' + parent.failures.join('、') : ' · 全部可见对象'), schemas: [...source.config.schemas], account: source.config.account, reason: '', databaseState: '尚未开始', failures: [], parent: parent?.id || '', retryOnly: Boolean(retry), retryObjects: retry ? [...parent.failures] : [] };
    tasks.unshift(task); return { task };
  }
  // 简单任务分段仅用于评审进度与取消；不拼接新旧结果，不改写历史任务结论。
  function advanceTask(task) {
    if (!['排队', '进行中'].includes(task.state)) return;
    const source = find(task.source), count = task.retryOnly ? task.retryObjects.length : source.config.type === 'PostgreSQL' ? task.schemas.length * 6 : objects(task.source).length || 6;
    if (task.state === '排队') { task.state = '进行中'; task.databaseState = '采集中'; return; }
    if (source.health !== '正常') { Object.assign(task, { state: '失败', reason: '网络连接不可用，请先测试并修复连接。', ended: now(), databaseState: '已确认结束' }); source.collection = '失败'; return; }
    if (!task.enumeration) { task.enumeration = true; task.total = count; task.discovered = count; task.processed = Math.ceil(count / 2); return; }
    const failures = task.retryOnly ? task.retryObjects.filter(name => objects(source.id).find(table => table.name === name)?.indexState === '无权限') : objects(source.id).filter(table => table.indexState === '无权限').map(table => table.name);
    Object.assign(task, { processed: count, failed: failures.length, failures, state: failures.length ? '部分成功' : '完整成功', ended: now(), databaseState: '已确认结束', reason: failures.length ? '索引目录无权限。请数据库负责人核对读取权限，成功属性已保留。' : '' });
    // 相同表名按 Schema 区分；变更范围后保留旧记录，当前目录只呈现选定范围。
    if (source.config.type === 'PostgreSQL') task.schemas.forEach(schema => names.slice(0, 6).forEach(([name, label], index) => { const row = object(source, name, label, index, schema); if (!tables.some(table => table.id === row.id)) tables.push(row); }));
    else if (!objects(source.id).length) names.slice(0, 6).forEach(([name, label], index) => tables.push(object(source, name, label, index)));
    source.lastCollected = task.ended;
    // 子范围重试不能把整源目录标为完整；每个对象保留本次结构来源。
    if (!task.retryOnly) { source.collection = task.state; if (!failures.length) source.lastFull = task.ended; }
    objects(source.id).filter(table => !task.retryOnly || task.retryObjects.includes(table.name)).forEach(table => { table.collected = task.ended; table.version = task.version; });
  }
  function cancelTask(task) {
    if (!['排队', '进行中'].includes(task.state)) return false;
    Object.assign(task, { state: '已取消', ended: now(), databaseState: '已确认结束', reason: '已停止采集；保留已完成范围，未完成部分未判定为移除。' }); return true;
  }
  function lifecycle(source, action, reason) {
    if (action === '暂停定时采集') source.paused = true;
    if (action === '恢复定时采集') source.paused = false;
    if (action === '停用数据源') { source.connectionState = '已停用'; const task = activeTask(source.id); if (task) cancelTask(task); }
    if (action === '恢复数据源') { source.connectionState = '已启用'; source.health = '正常'; }
    if (action === '退役数据源') { if (source.connectionState !== '已停用') return '请先停用数据源，再核对退役。'; source.connectionState = '已退役'; }
    source.updated = now(); source.history.unshift({ revision: source.revision, at: now(), action, reason });
  }
  window.DataSourceDemo = { sources, tables, tasks, notes, changes, now, find, objects, activeTask, configuration, configDefaults, connectionPolicy, scopeLabel, scopeError, inspectConnection, nameError, error, testResults, canEnable, saveConfig, startTask, advanceTask, cancelTask, lifecycle,
    get selected() { return selected; }, set selected(value) { if (find(value)) selected = value; }, nextId: prefix => prefix + (++sequence) };
})();
