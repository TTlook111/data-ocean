/* 本人资料沿用共用人员示例，只读展示，不提供管理他人或修改权限的入口。 */
(() => {
  'use strict';
  function render(main, ui) {
    const { el, pair, status, pageHeading, currentUser } = ui;
    if (!SourceApprovalDemo.hasAbility(currentUser, 'account-info.view')) {
      const denied = el('section', 'planned-page');
      denied.append(el('p', '', '当前账号没有查看账号信息的权限。'));
      main.replaceChildren(pageHeading('账号信息'), denied); return;
    }
    const panel = el('section', 'account-panel');
    const basic = el('section', 'account-section'), heading = el('header', 'account-section-heading');
    heading.append(el('h2', '', '基本信息'), status(currentUser.status));
    const fields = el('dl', 'detail-fields');
    fields.append(pair('姓名', currentUser.name), pair('登录名', currentUser.login), pair('手机号', currentUser.phone || '未填写'), pair('邮箱', currentUser.email || '未填写'));
    basic.append(heading, fields);
    const organization = el('section', 'account-section'), memberships = el('dl', 'detail-fields');
    const departmentName = id => PeopleDemo.departments.find(row => row.id === id)?.name || '部门不可用';
    memberships.append(pair('主部门', departmentName(currentUser.department)), pair('兼任部门', currentUser.extra.map(departmentName).join('、') || '无兼任部门'));
    organization.append(el('h2', '', '所属部门'), memberships);
    const roles = el('section', 'account-section'), names = el('div', 'account-roles');
    for (const name of currentUser.roles) names.append(el('span', 'account-role', name));
    if (!currentUser.roles.length) names.append(el('p', 'account-unassigned', '未分配角色'));
    roles.append(el('h2', '', '个人角色'), names);
    panel.append(basic, organization, roles);
    main.replaceChildren(pageHeading('账号信息'), panel);
  }
  window.AccountInfoPage = { render };
})();
