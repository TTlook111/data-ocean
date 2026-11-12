/* 角色页使用少量离线记录和操作目录，不据此判断真实账号权限。 */
(() => {
  'use strict';
  const page = (id, group, name, operations = []) => ({ id, group, name,
    operations: [{ id: id + '.view', name: '访问页面', purpose: '进入' + name + '，查看获准内容。' },
      ...operations.map(([key, label, purpose, protectedAbility = false]) => ({ id: id + '.' + key, name: label, purpose, protectedAbility }))] });
  const catalog = [
    page('dashboard', '总览', '总览'),
    page('sources', '数据源接入', '数据源管理', [
      ['approval-config', '配置审批责任', '为数据源指定具备审批功能的主审批人和替代负责人，不改变查询授权。'],
      ['create', '新增数据源', '创建连接配置，不自动获得业务查询权。'], ['edit', '编辑连接', '修改连接信息或轮换凭据，凭据不回显。'],
      ['test', '测试连接', '核对连接、读取与采集条件。'], ['enable', '启用数据源', '核对连接和采集条件后启用。'],
      ['pause', '暂停采集', '暂停该源后续采集，保留已有结果。'], ['disable', '停用数据源', '核对使用与依赖影响后停用。'], ['retire', '退役数据源', '核对依赖和历史保留后退役。']]),
    page('catalog', '数据源接入', '元数据目录'),
    page('collections', '数据源接入', '采集记录与结构变化', [
      ['collect', '发起采集', '从获准数据源只读采集结构信息。'], ['retry', '重试失败对象', '只重试失败部分，保留已有结果。'],
      ['cancel', '取消采集', '停止当前有权取消的采集任务。'], ['review', '核实变化', '核对结构、注释或可见性变化的证据并记录结论，不替代业务治理确认。']]),
    page('business-notes', '数据源接入', '企业业务说明', [
      ['submit', '提交业务资料', '编写说明或上传业务资料，保存修订。'], ['replace', '替换业务资料', '保存替换修订，已引用正文或附件变更进入影响评估。'], ['withdraw', '撤下业务资料', '核对影响后撤下有权管理的资料。'],
      ['restore', '恢复业务资料', '恢复已撤下资料供后续治理取用，保留原修订和操作记录。'], ['delete', '删除未使用资料', '仅删除所有修订均未被任务取用或成果引用的资料。']]),
    page('governance', '数据治理', '治理工作台', [
      ['suggest', '生成 AI 建议', '基于获准资料生成待确认建议。'], ['edit', '编辑治理内容', '维护表字段、关系和指标的业务说明。'],
      ['classify', '维护主题归类', '确认或调整主题归类，不替代关系确认。']]),
    page('governance-review', '数据治理', '治理审核与复核', [
      ['confirm', '确认治理内容', '评估变化影响、交接治理并确认处理结果，不自动发布或授权。'], ['supplement', '要求补充', '记录评估缺口并保留待处理状态。'],
      ['forbid', '禁止对象使用', '核对影响后禁止对象参与查询。'], ['allow', '解除对象禁止', '明确确认后解除禁止，仍须核验当前权限和资源状态。']]),
    page('test-cases', '数据治理', '标准题单与验证', [
      ['edit', '维护标准题单', '新增或修订标准问题及预期结果。'], ['run', '运行验证', '执行获准的相关测试并查看结果。']]),
    page('knowledge', '知识库与发布', '知识文档', [
      ['generate', '生成知识草稿', '使用已确认治理成果生成文档。'], ['edit', '编辑知识草稿', '修改草稿并核对来源。'], ['confirm', '确认知识文档', '确认文档内容，供后续候选构建使用。']]),
    page('releases', '知识库与发布', '发布管理', [
      ['build', '构建候选', '确认范围后构建隔离候选，不直接切换线上知识。'], ['test', '运行发布测试', '核对题单、结果和发布门槛。'], ['publish', '确认发布', '复核通过后整批切换实际发布范围。']]),
    page('versions', '知识库与发布', '版本与回退', [
      ['pause', '暂停知识', '说明原因并暂停受影响知识。'], ['rollback', '回退上一版', '整批预检通过后回退上一版本。']]),
    page('feedback', '反馈处理', '问题队列与处理', [
      ['assign', '分派问题', '核对归属后分派，不扩大处理者权限。'], ['process', '记录处理', '记录核查、修订或技术修复依据。'],
      ['verify', '复验', '核对实际修复结果。'], ['close', '结案并回告', '按实际处理结果结案，回告可公开结论。']]),
    page('users', '人员与权限', '人员管理', [
      ['create', '新增人员', '创建登录名、初始密码及人员资料。'], ['edit', '编辑人员', '维护人员资料，不直接改变数据访问。'],
      ['assign', '分配角色', '向个人选择已有角色，不设置时长。'], ['transfer', '调整归属', '调整主部门或兼任关系，核对初始访问来源变化。'],
      ['password', '重置密码', '重置初始凭据，不改变已有业务权限。'], ['disable', '停用人员', '核对影响后停止该账号访问，保留资料及历史。'],
      ['enable', '复核并恢复人员', '核对归属、角色及访问来源后恢复账号。'], ['archive', '归档人员', '先停用并核对历史保留，仅供回看。'],
      ['recover', '受保护权限恢复', '核验后恢复受保护管理能力。', true]]),
    page('departments', '人员与权限', '部门管理', [
      ['create', '新增部门', '创建组织及上下级关系。'], ['edit', '编辑部门', '维护部门资料与负责人。'], ['add-member', '加入成员', '维护直属或兼任成员关系。'],
      ['remove-member', '移出成员', '核对其他归属与来源后移出相应成员。'], ['transfer-members', '迁移成员或子部门', '核对目标部门和组织关系后迁移。'],
      ['access', '配置初始数据访问', '选择数据源和输出限制规则，不形成部门记录过滤。'], ['end-access', '结束初始数据访问', '结束本部门初始来源，其他合法来源保留。'],
      ['disable', '停用部门', '核对成员和来源影响后停用。'], ['enable', '复核并恢复部门', '恢复组织状态并复核保留配置。'], ['archive', '归档部门', '处理成员、子部门及来源后归档。']]),
    page('roles', '人员与权限', '角色与功能权限', [
      ['create', '新增角色', '创建角色并配置页面及操作。'], ['edit', '编辑角色', '修改名称、说明及功能，核对受影响人员。'],
      ['disable', '停用角色', '停用该角色功能，保留分配关系和配置。'], ['enable', '启用角色', '核对影响后启用该角色功能。'],
      ['archive', '归档角色', '先停用并移出当前分配人员，配置仅供回看。'], ['protected', '受保护能力授予', '核验授予上限及受保护管理能力。', true]]),
    page('output-rules', '人员与权限', '输出限制规则', [
      ['create', '新增规则', '维护可复用规则名称及返回限制内容。'], ['edit', '编辑规则', '维护已有规则名称与返回限制内容。']]),
    page('access-requests', '人员与权限', '访问申请', [
      ['route', '核定与分派申请', '核定待处理子项的数据源，按该源审批责任分派，不直接批准或授权。'],
      ['review', '核定与审批', '根据用途核定数据源和输出规则，分项批准。'], ['supplement', '要求补充', '说明缺少的业务信息并交回申请人。'], ['reject', '驳回申请', '说明理由并驳回相应申请子项。']]),
    page('grants', '人员与权限', '授权管理', [['revoke', '撤销授权', '核对来源和影响后结束指定授权来源。']]),
    page('audit', '人员与权限', '权限审计'),
    page('models', '系统配置', '模型配置', [
      ['create', '新增模型配置', '创建配置草稿，凭据保持遮蔽。'], ['edit', '编辑配置草稿', '维护草稿参数和服务信息。'], ['credential', '替换模型凭据', '替换凭据，不回显旧凭据。'],
      ['test', '测试配置', '验证对应模型服务与用途条件。'], ['activate', '使配置生效', '核对用途影响后使修订生效。'], ['disable', '停用模型配置', '核对绑定用途和依赖后停用。']]),
    page('model-uses', '系统配置', '用途设置', [['governance', '切换治理用途配置', '选择可用配置，核对治理调用影响后切换。'], ['query', '切换问数用途配置', '选择可用配置，核对问数调用影响后切换。']]),
    page('retrieval', '系统配置', '知识检索配置', [['edit', '维护检索配置', '配置向量及重排用途；需要重建时进入候选发布流程。'], ['test', '验证检索', '验证当前检索配置与索引的匹配条件。']]),
    page('diagnostics', '系统配置', '运行诊断', [['test', '重新测试', '对有权诊断的失败项重新测试。']]),
    page('account-info', '个人管理', '账号信息'),
    page('my-requests', '个人管理', '我的申请', [['apply', '申请额外访问', '填写业务用途和需要的数据，已知源可选填。'], ['withdraw', '撤回本人申请', '撤回本人尚未生效的申请子项。']]),
    page('my-access', '个人管理', '我的访问范围'),
    page('query', '问数工作台', '问数工作台', [['ask', '发起问数', '对单一获准源发起或重新查询业务问题。'], ['sql', '查看 SQL', '按对应权限查看查询依据中的 SQL。']])
  ];
  const all = catalog.flatMap(item => item.operations);
  const keysFor = ids => catalog.filter(item => ids.includes(item.id)).flatMap(item => item.operations.filter(op => !op.protectedAbility).map(op => op.id));
  const personal = [...keysFor(['account-info', 'my-requests', 'my-access']), 'query.view', 'query.ask'];
  const presets = [
    ['role-root', '超级管理员', '维护平台功能配置，受保护管理能力需单独核验。', all.map(item => item.id), true],
    ['role-admin', '管理员', '维护人员、部门及常用管理功能。', keysFor(['users', 'departments', 'roles', 'output-rules', 'account-info', 'my-requests', 'my-access'])],
    ['role-analyst', '数据分析师', '使用问数、元数据目录和本人管理功能。', [...personal, 'catalog.view']],
    ['role-user', '普通用户', '使用本人问数、申请及访问回看功能。', personal],
    ['role-source', '数据源维护员', '维护连接、结构目录与采集记录。', keysFor(['sources', 'catalog', 'collections', 'business-notes']).filter(key => key !== 'sources.approval-config')],
    ['role-governance', '数据治理员', '整理业务知识并维护治理内容。', keysFor(['governance', 'catalog', 'test-cases'])],
    ['role-review', '知识审核员', '核对治理依据和知识文档。', keysFor(['governance-review', 'knowledge'])],
    ['role-release', '发布管理员', '管理候选构建、发布测试及版本回退。', keysFor(['knowledge', 'releases', 'versions', 'test-cases'])],
    ['role-feedback', '反馈处理员', '核查、分派与处理有权问题。', keysFor(['feedback'])],
    ['role-approval', '访问审批员', '核定业务用途、数据源及输出限制。', keysFor(['access-requests', 'output-rules'])],
    ['role-executor', '授权管理员', '回看授权来源并按权限撤销访问。', keysFor(['grants'])],
    ['role-audit', '权限审计员', '回看有权审计的授权及操作记录。', [...keysFor(['audit']), 'grants.view']],
    ['role-model', '模型配置员', '维护模型配置及用途，核对检索条件。', keysFor(['models', 'model-uses', 'retrieval', 'diagnostics'])],
    ['role-observer', '业务观察员', '仅回看获准概览与本人访问来源。', ['dashboard.view', 'my-access.view']],
    ['role-archive', '历史资料查阅协作角色', '结束使用后仅保留配置回看。', ['catalog.view']]
  ];
  const records = presets.map(([id, name, description, permissions, protectedRole], index) => ({ id, name, description, permissions: [...new Set(permissions)],
    protectedRole: Boolean(protectedRole), status: index === 14 ? '已归档' : index === 13 ? '停用' : '启用', updated: '2026-11-10 ' + String(20 - Math.floor(index / 3)).padStart(2, '0') + ':' + String(50 - index).padStart(2, '0') }));
  const members = role => PeopleDemo.users.filter(user => user.status !== '已归档' && user.roles.includes(role.name));
  const options = (selected = []) => records.filter(role => role.status === '启用' || selected.includes(role.name)).map(role => ({ value: role.name,
    label: role.name + (role.status === '启用' ? '' : '（' + role.status + '）'), disabled: role.status !== '启用' && !selected.includes(role.name) }));
  const initialRoles = () => { const role = records.find(role => role.id === 'role-user'); return role?.status === '启用' ? [role.name] : []; };
  const syncNames = () => PeopleDemo.roles.splice(0, PeopleDemo.roles.length, ...records.filter(role => role.status !== '已归档').map(role => role.name));
  const timestamp = () => new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16);
  const canonicalName = name => name.trim().normalize('NFKC').toLocaleLowerCase();
  function validate(role, draft) {
    if (role?.status === '已归档') return '已归档角色仅供回看。';
    if (!draft.name.trim()) return '请填写角色名称。';
    if (records.some(item => item.id !== role?.id && canonicalName(item.name) === canonicalName(draft.name))) return '角色名称已存在。';
    if (!draft.permissions.length) return '请至少选择一个页面或功能。';
    if (draft.permissions.some(key => !all.some(item => item.id === key))) return '存在不可用的功能，请重新核对。';
    const protectedKeys = all.filter(item => item.protectedAbility).map(item => item.id);
    if (protectedKeys.some(key => draft.permissions.includes(key) !== Boolean(role?.permissions.includes(key)))) return '受保护管理能力需单独核验。';
    for (const item of catalog) if (draft.permissions.some(key => item.operations.slice(1).some(op => op.id === key)) && !draft.permissions.includes(item.id + '.view')) return '操作所属页面访问尚未选中。';
  }
  function save(role, draft) {
    const error = validate(role, draft); if (error) return error;
    const name = draft.name.trim(), value = { name, description: draft.description.trim(), permissions: [...new Set(draft.permissions)], updated: timestamp() };
    // 重命名同步当前人员的名称引用，不改变分配对象、部门或任何数据访问来源。
    if (role) { const oldName = role.name; for (const user of PeopleDemo.users) user.roles = user.roles.map(value => value === oldName ? name : value); Object.assign(role, value); }
    else { role = { id: 'role-' + Date.now(), ...value, status: '启用', protectedRole: false }; records.unshift(role); }
    syncNames(); return role;
  }
  function statusBlock(role, next) {
    if (role.status === '已归档') return '已归档角色仅供回看。';
    if (role.protectedRole && next !== '启用') return '受保护管理角色需单独核验，不能直接停用或归档。';
    if (next === '已归档' && role.status !== '停用') return '请先停用角色，再核对归档条件。';
    if (next === '已归档' && members(role).length) return '请先移出仍分配该角色的 ' + members(role).length + ' 名人员。';
  }
  syncNames();
  window.RoleDemo = { catalog, records, members, options, initialRoles, validate, save, statusBlock, timestamp, syncNames };
})();
