/* 仅供页面评审的少量示例；修改只保留在当前页面内存，刷新恢复。 */
(() => {
  'use strict';
  // 人员分配只使用角色名称，不在此模拟角色与页面、按钮权限的对应关系。
  const roles = ['超级管理员', '管理员', '数据分析师', '普通用户'];
  const dataSources = [
    { id: 'retail', name: '经营业务库' }, { id: 'finance', name: '财务分析库' },
    { id: 'products', name: '商品中心库' }, { id: 'aftersales', name: '售后业务库' }
  ];
  const outputRules = [
    { id: 'privacy', name: '用户信息限制', content: '不允许返回用户的身份证号码、电话号码。', updated: '2026-11-10 09:00' },
    { id: 'cost', name: '产品成本限制', content: '不允许返回产品成本信息。', updated: '2026-11-10 09:10' },
    { id: 'account', name: '收款账户限制', content: '不允许返回收款银行账号信息。', updated: '2026-11-10 09:20' }
  ];
  const accessExample = source => ({ source, rules: structuredClone(outputRules.filter(rule => source === 'finance' ? ['privacy', 'account'].includes(rule.id) : ['privacy', 'cost'].includes(rule.id))) });
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
    id, name, parent, manager, status, access: source ? [accessExample(source)] : [],
    code: 'DEP-' + String(index + 1).padStart(3, '0'),
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
    extra: index === 10 ? ['d13'] : [], mustChangePassword: status === '待首次登录', phone: index === 4 ? '' : '1380000' + String(1000 + index),
    email: login + '@example.com', note: index === 11 ? '兼顾商品与库存分析，参与经营口径梳理。' : '',
    updated: '2026-11-0' + (8 - index % 5) + ' ' + (index % 2 ? '10:35' : '09:20')
  }));
  const extraRequests = [
    { id: 'SQ-DEMO-011', user: 'u01', department: 'd01', access: [accessExample('finance')], reason: '核对总部应收与回款汇总', status: '已生效', submitted: '2026-11-08 09:20' },
    { id: 'SQ-DEMO-012', user: 'u04', department: 'd04', access: [accessExample('finance')], reason: '核对城南店应收与回款汇总', status: '已生效', submitted: '2026-11-08 09:10' },
    { id: 'SQ-DEMO-013', user: 'u01', department: 'd01', access: [accessExample('retail')], reason: '补充销售汇总分析', status: '待审批', submitted: '2026-11-09 08:30' },
    { id: 'SQ-DEMO-014', user: 'u01', department: 'd01', access: [accessExample('products')], reason: '查看商品基础目录', status: '已驳回', submitted: '2026-11-07 08:30', result: '请补充具体业务用途后重新申请。' },
    // 每笔申请分别保留来源；多数据源及不同状态供列表和详情评审。
    { id: 'SQ-DEMO-015', user: 'u01', department: 'd01', access: [accessExample('retail')], reason: '核对总部商品库存与销售', status: '已生效', submitted: '2026-11-08 10:00' },
    { id: 'SQ-DEMO-016', user: 'u01', department: 'd01', access: [accessExample('finance')], reason: '核对总部月度费用', status: '已生效', submitted: '2026-11-08 10:10' },
    { id: 'SQ-DEMO-017', user: 'u01', department: 'd01', access: [accessExample('products')], reason: '核对已上架商品资料', status: '已生效', submitted: '2026-11-08 10:20' },
    { id: 'SQ-DEMO-018', user: 'u01', department: 'd01', access: [accessExample('retail'), accessExample('finance')], reason: '核对城南店库存销售与回款', status: '已生效', submitted: '2026-11-08 10:30' },
    { id: 'SQ-DEMO-019', user: 'u01', department: 'd01', access: [accessExample('retail')], reason: '核对滨江店库存与销售', status: '已生效', submitted: '2026-11-08 10:40' },
    { id: 'SQ-DEMO-020', user: 'u01', department: 'd01', access: [accessExample('retail')], reason: '核对城北店库存与销售', status: '已生效', submitted: '2026-11-08 10:50' },
    { id: 'SQ-DEMO-021', user: 'u01', department: 'd01', access: [accessExample('products')], reason: '核对商品中心上架目录', status: '已生效', submitted: '2026-11-08 11:00' },
    { id: 'SQ-DEMO-022', user: 'u01', department: 'd01', access: [accessExample('finance')], reason: '核对财务部应收与费用', status: '已生效', submitted: '2026-11-08 11:10' }
  ];
  window.PeopleDemo = { departments, users, roles, dataSources, outputRules, extraRequests };
})();
