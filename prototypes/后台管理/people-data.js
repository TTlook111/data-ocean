/* 仅供页面评审的少量示例；修改只保留在当前页面内存，刷新恢复。 */
(() => {
  'use strict';
  // 角色仅展示页面、按钮和功能；数据访问单独用部门配置与申请示例表达。
  const roleTemplates = {
    '超级管理员': [{ page: '全部后台页面、问数工作台', operation: '人员、部门、角色及系统管理功能' }],
    '管理员': [{ page: '人员管理、部门管理、角色与功能权限', operation: '新增、编辑、调岗、停用与分配角色' }],
    '数据分析师': [{ page: '问数工作台、元数据目录、个人管理', operation: '问数、查看元数据、反馈与申请数据访问' }],
    '普通用户': [{ page: '问数工作台、个人管理', operation: '问数、反馈、申请数据访问与查看本人进度' }]
  };
  const dataSources = [
    { id: 'retail', name: '经营业务库', scopes: [
      { id: 'stock', name: '本部门商品与库存', description: '查看本部门商品与可用库存，不含采购成本', tables: '商品、库存', rows: '门店 / 部门 = {department}', fields: '商品名称、规格、可用库存，不含采购成本' },
      { id: 'sales', name: '本部门销售汇总', description: '查看本部门销售数量与销售净额', tables: '销售汇总', rows: '门店 / 部门 = {department}', fields: '业务日期、销售数量、销售净额' }
    ] },
    { id: 'finance', name: '财务分析库', scopes: [
      { id: 'receivables', name: '本部门应收与回款', description: '查看本部门应收与回款汇总，不含账户信息', tables: '应收回款汇总', rows: '归属部门 = {department}', fields: '业务日期、应收金额、回款金额，不含账户信息' },
      { id: 'expenses', name: '本部门费用汇总', description: '查看本部门费用类别与月度费用汇总', tables: '费用汇总', rows: '归属部门 = {department}', fields: '月份、费用类别、费用金额' }
    ] },
    { id: 'products', name: '商品中心库', scopes: [
      { id: 'catalog', name: '商品基础目录', description: '查看已上架商品基础信息，不含采购成本', tables: '商品主数据', rows: '已上架商品', fields: '商品编号、名称、分类、规格，不含采购成本' }
    ] },
    { id: 'aftersales', name: '售后业务库', scopes: [] }
  ];
  // 既有范围也保留明确的确认事实；没有状态的条目不自动视为可选范围。
  for (const source of dataSources) for (const scope of source.scopes) Object.assign(scope, {
    state: '已确认', revision: 'GOV-' + source.id.toUpperCase() + '-01', confirmedBy: ({ retail: '陈主管', finance: '刘芳', products: '赵敏' })[source.id], confirmedAt: '2026-11-08 10:00'
  });
  const departments = [
    ['d01', '总部', '', '林管理员', '', '正常'],
    ['d02', '运营中心', 'd01', '陈主管', 'retail', '正常'],
    ['d03', '城南片区', 'd02', '陈主管', 'retail', '正常'],
    ['d04', '城南店', 'd03', '周店长', 'retail', '正常'],
    ['d05', '滨江店', 'd03', '许店长', 'retail', '正常'],
    ['d06', '城北片区', 'd02', '李明', 'retail', '正常'],
    ['d07', '城北店', 'd06', '李明', 'retail', '正常'],
    ['d08', '新城店', 'd06', '孙悦', '', '停用'],
    ['d09', '商品中心', 'd01', '赵敏', 'products', '正常'],
    ['d10', '采购部', 'd09', '赵敏', 'products', '正常'],
    ['d11', '仓储物流部', 'd09', '王磊', 'retail', '正常'],
    ['d12', '数据与信息部', 'd01', '杨分析师', 'retail', '正常'],
    ['d13', '财务部', 'd01', '刘芳', 'finance', '正常'],
    ['d14', '人事行政部', 'd01', '何静', '', '正常'],
    ['d15', '旧城店', 'd03', '唐宁', 'retail', '停用']
  ].map(([id, name, parent, manager, source, status], index) => ({
    id, name, parent, manager, status, access: source ? [{ source, scopes: [dataSources.find(s => s.id === source).scopes[0].id] }] : [],
    until: '', code: 'DEP-' + String(index + 1).padStart(3, '0'),
    note: name === '数据与信息部' ? '负责经营数据分析、数据治理与系统日常维护。' : '', created: '2026-10-20 09:00'
  }));
  const users = [
    ['林管理员', 'lin.admin', 'd01', ['超级管理员'], '正常'],
    ['陈主管', 'chen.zhuguan', 'd02', ['管理员'], '正常'],
    ['周店长', 'zhou.dianzhang', 'd04', ['管理员', '普通用户'], '正常'],
    ['林晓雨', 'lin.xiaoyu', 'd04', ['普通用户'], '正常'],
    ['吴同事', 'wu.tongshi', 'd04', ['普通用户'], '待首次登录'],
    ['许店长', 'xu.dianzhang', 'd05', ['管理员'], '正常'],
    ['李明', 'li.ming', 'd07', ['管理员'], '正常'],
    ['孙悦', 'sun.yue', 'd07', ['普通用户'], '正常'],
    ['赵敏', 'zhao.min', 'd10', ['数据分析师'], '正常'],
    ['王磊', 'wang.lei', 'd11', ['普通用户'], '正常'],
    ['杨分析师', 'yang.fenxi', 'd12', ['数据分析师'], '正常'],
    ['郑文清', 'zheng.wenqing', 'd12', ['数据分析师', '普通用户'], '正常'],
    ['刘芳', 'liu.fang', 'd13', ['数据分析师'], '正常'],
    ['何静', 'he.jing', 'd14', ['普通用户'], '待首次登录'],
    ['唐宁', 'tang.ning', 'd15', ['普通用户'], '停用']
  ].map(([name, login, department, roles, status], index) => ({
    id: 'u' + String(index + 1).padStart(2, '0'), name, login, department, roles, status,
    extra: index === 10 ? ['d13'] : [], roleUntil: '', mustChangePassword: status === '待首次登录', phone: index === 4 ? '' : '1380000' + String(1000 + index),
    email: login + '@example.com', note: index === 11 ? '兼顾商品与库存分析，参与经营口径梳理。' : '',
    updated: '2026-11-0' + (8 - index % 5) + ' ' + (index % 2 ? '10:35' : '09:20')
  }));
  const extraRequests = [
    { id: 'SQ-DEMO-011', user: 'u01', department: 'd01', access: [{ source: 'finance', scopes: ['receivables'] }], reason: '核对总部应收与回款汇总', until: '2026-12-31', status: '已生效', submitted: '2026-11-08 09:20' },
    { id: 'SQ-DEMO-012', user: 'u04', department: 'd04', access: [{ source: 'finance', scopes: ['receivables'] }], reason: '核对城南店应收与回款汇总', until: '2026-12-31', status: '已生效', submitted: '2026-11-08 09:10' },
    { id: 'SQ-DEMO-013', user: 'u01', department: 'd01', access: [{ source: 'retail', scopes: ['sales'] }], reason: '补充销售汇总分析', until: '2026-12-15', status: '待审批', submitted: '2026-11-09 08:30' },
    { id: 'SQ-DEMO-014', user: 'u01', department: 'd01', access: [{ source: 'products', scopes: ['catalog'] }], reason: '查看商品基础目录', until: '2026-12-15', status: '已驳回', submitted: '2026-11-07 08:30', result: '请补充具体业务用途后重新申请。' },
    // 补足本人 15 项范围明细，覆盖一笔申请包含多范围、多数据源及跨页回看。
    { id: 'SQ-DEMO-015', user: 'u01', department: 'd01', access: [{ source: 'retail', scopes: ['stock', 'sales'] }], reason: '核对总部商品库存与销售', until: '2026-12-31', status: '已生效', submitted: '2026-11-08 10:00' },
    { id: 'SQ-DEMO-016', user: 'u01', department: 'd01', access: [{ source: 'finance', scopes: ['expenses'] }], reason: '核对总部月度费用', until: '2026-12-31', status: '已生效', submitted: '2026-11-08 10:10' },
    { id: 'SQ-DEMO-017', user: 'u01', department: 'd01', access: [{ source: 'products', scopes: ['catalog'] }], reason: '核对已上架商品资料', until: '2026-12-15', status: '已生效', submitted: '2026-11-08 10:20' },
    { id: 'SQ-DEMO-018', user: 'u01', department: 'd04', access: [{ source: 'retail', scopes: ['stock', 'sales'] }, { source: 'finance', scopes: ['receivables'] }], reason: '核对城南店库存销售与回款', until: '2026-11-30', status: '已生效', submitted: '2026-11-08 10:30' },
    { id: 'SQ-DEMO-019', user: 'u01', department: 'd05', access: [{ source: 'retail', scopes: ['stock', 'sales'] }], reason: '核对滨江店库存与销售', until: '2026-11-30', status: '已生效', submitted: '2026-11-08 10:40' },
    { id: 'SQ-DEMO-020', user: 'u01', department: 'd07', access: [{ source: 'retail', scopes: ['stock', 'sales'] }], reason: '核对城北店库存与销售', until: '2026-11-30', status: '已生效', submitted: '2026-11-08 10:50' },
    { id: 'SQ-DEMO-021', user: 'u01', department: 'd09', access: [{ source: 'products', scopes: ['catalog'] }], reason: '核对商品中心上架目录', until: '2026-12-20', status: '已生效', submitted: '2026-11-08 11:00' },
    { id: 'SQ-DEMO-022', user: 'u01', department: 'd13', access: [{ source: 'finance', scopes: ['receivables', 'expenses'] }], reason: '核对财务部应收与费用', until: '2026-12-31', status: '已生效', submitted: '2026-11-08 11:10' }
  ];
  window.PeopleDemo = { departments, users, roles: Object.keys(roleTemplates), roleTemplates, dataSources, extraRequests };
})();
