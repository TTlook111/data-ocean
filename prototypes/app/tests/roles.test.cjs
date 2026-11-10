const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
function fixture() {
  const context = vm.createContext({ window: {}, structuredClone });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理/people-data.js'), 'utf8'), context);
  context.PeopleDemo = context.window.PeopleDemo;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../后台管理/role-data.js'), 'utf8'), context);
  return { demo: context.window.RoleDemo, people: context.PeopleDemo };
}
const plain = value => JSON.parse(JSON.stringify(value));
const draft = role => ({ name: role.name, description: role.description, permissions: [...role.permissions] });
test('duplicate and empty role changes never partially rename people or mutate records', () => {
  const { demo, people } = fixture(), role = demo.records.find(role => role.id === 'role-admin');
  const before = JSON.stringify({ people, records: demo.records });
  assert.match(demo.save(role, { ...draft(role), name: ' 普通用户 ' }), /名称已存在/);
  assert.match(demo.save(role, { ...draft(role), name: '  ' }), /填写角色名称/);
  assert.equal(JSON.stringify({ people, records: demo.records }), before);
});
test('renaming a role retains assigned people and independent organization and source access', () => {
  const { demo, people } = fixture(), role = demo.records.find(role => role.id === 'role-user');
  const ids = plain(demo.members(role).map(user => user.id));
  const access = JSON.stringify({ departments: people.departments, requests: people.extraRequests, users: people.users.map(({ department, extra }) => ({ department, extra })) });
  demo.save(role, { ...draft(role), name: '业务用户' });
  assert.deepEqual(plain(demo.members(role).map(user => user.id)), ids);
  assert.deepEqual(plain(demo.initialRoles()), ['业务用户']);
  assert(!people.users.some(user => user.roles.includes('普通用户')));
  assert.equal(JSON.stringify({ departments: people.departments, requests: people.extraRequests, users: people.users.map(({ department, extra }) => ({ department, extra })) }), access);
});
test('protected grants cannot be added, removed, or bypassed through a role rename', () => {
  const { demo } = fixture(), root = demo.records.find(role => role.protectedRole);
  const before = JSON.stringify(root);
  assert.match(demo.save(root, { ...draft(root), name: '另一个名字', permissions: root.permissions.filter(key => key !== 'roles.protected') }), /需单独核验/);
  assert.equal(JSON.stringify(root), before);
  assert.match(demo.save(null, { name: '受保护测试', description: '', permissions: ['roles.view', 'roles.protected'] }), /需单独核验/);
  assert.match(demo.statusBlock(root, '停用'), /需单独核验/);
});
test('stopped role assignments remain readable while new assignment options exclude them', () => {
  const { demo } = fixture(), role = demo.records.find(role => role.id === 'role-user');
  const assigned = demo.members(role).length;
  role.status = '停用'; demo.syncNames();
  assert(!demo.options().some(item => item.value === role.name));
  assert(demo.options([role.name]).some(item => item.value === role.name && item.label.includes('停用')));
  assert.equal(demo.members(role).length, assigned);
  assert.deepEqual(plain(demo.initialRoles()), []);
});
test('archiving requires a stopped and unassigned role, and archived roles are read only', () => {
  const { demo } = fixture(), assigned = demo.records.find(role => role.id === 'role-admin');
  assert(demo.statusBlock(assigned, '已归档'));
  assigned.status = '停用'; assert(demo.statusBlock(assigned, '已归档'));
  const unused = demo.records.find(role => role.id === 'role-observer');
  assert.equal(demo.statusBlock(unused, '已归档'), undefined);
  unused.status = '已归档';
  assert.match(demo.save(unused, draft(unused)), /仅供回看/);
  assert.match(demo.statusBlock(unused, '启用'), /仅供回看/);
});
test('actions require page access and unusable or empty configurations cannot save', () => {
  const { demo } = fixture(), count = demo.records.length;
  for (const permissions of [[], ['not-a-permission'], ['query.ask']]) assert.equal(typeof demo.save(null, { name: '测试角色', description: '', permissions }), 'string');
  assert.equal(demo.records.length, count);
  const created = demo.save(null, { name: '仅问数角色', description: '', permissions: ['query.view', 'query.ask'] });
  assert.equal(created.status, '启用');
  assert(demo.options().some(item => item.value === created.name));
  assert(!created.permissions.some(key => key.includes('export')));
});
