/* 可交互页面原型：只维护示例行、表单和显示状态，不计算或执行业务权限。 */
(() => {
  'use strict';
  const { users, departments, roles, roleTemplates, dataSources, extraRequests } = PeopleDemo;
  const currentUser = users.find(user => user.id === 'u01');
  const main = document.getElementById('admin-main');
  const pageLocation = Vue.reactive({ title: '总览' });
  PrototypeUI.mount(document.getElementById('admin-breadcrumb'), {
    setup() {
      const { h } = Vue;
      return () => h(ElementPlus.ElBreadcrumb, { separator: '/' }, { default: () => [
        h(ElementPlus.ElBreadcrumbItem, null, { default: () => h('a', { href: '#dashboard' }, '后台管理') }),
        h(ElementPlus.ElBreadcrumbItem, { 'aria-current': 'page' }, { default: () => pageLocation.title })
      ] });
    }
  });
  const groups = [
    ['数据源接入', 'database', [['sources', '数据源管理'], ['catalog', '元数据目录'], ['collections', '采集记录与结构变化'], ['business-notes', '企业业务说明']]],
    ['数据治理', 'layers', [['governance', '治理工作台'], ['governance-review', '治理审核与复核'], ['test-cases', '标准题单与验证']]],
    ['知识库与发布', 'file-text', [['knowledge', '知识文档'], ['releases', '发布管理'], ['versions', '版本与回退']]],
    ['反馈处理', 'inbox', [['feedback', '问题队列与处理']]],
    ['人员与权限', 'user-round', [['users', '人员管理'], ['departments', '部门管理'], ['roles', '角色与功能权限'], ['grant-management', '授权执行与撤销'], ['access-requests', '访问申请'], ['grants', '授权记录'], ['audit', '权限审计']]],
    ['系统配置', 'layers', [['models', '模型配置'], ['model-uses', '用途设置'], ['retrieval', '知识检索配置'], ['diagnostics', '运行诊断']]],
    ['个人管理', 'user-round', [['account-info', '账号信息'], ['my-requests', '我的申请'], ['my-access', '我的访问范围']]]
  ];
  const titles = { dashboard: '总览', ...Object.fromEntries(groups.flatMap(group => group[2])) };
  const queries = { users: { keyword: '', status: 'all', page: 1, pageSize: 10 }, members: { keyword: '', status: 'all', page: 1, pageSize: 10 }, children: { page: 1, pageSize: 10 }, requests: { keyword: '', status: 'all', page: 1, pageSize: 10 } };
  let section = '', selectedDepartment = 'd01', treeKeyword = '', feedbackTimer, departmentTab = 'members', includeChildren = true;
  const deptName = id => departments.find(d => d.id === id)?.name || '—';
  const members = id => users.filter(u => u.status !== '已归档' && (u.department === id || u.extra.includes(id)));
  const childDepartments = id => departments.filter(d => d.parent === id);
  const branch = id => [id, ...childDepartments(id).flatMap(d => branch(d.id))];
  // 只做组织列表的范围筛选与去重，不从上下级关系推导业务权限。
  const scopedMembers = id => { const ids = includeChildren ? branch(id) : [id]; return users.filter(u => u.status !== '已归档' && (ids.includes(u.department) || u.extra.some(d => ids.includes(d)))); };
  const allMembers = id => { const ids = branch(id); return users.filter(u => u.status !== '已归档' && (ids.includes(u.department) || u.extra.some(d => ids.includes(d)))); };
  const options = values => values.map(value => ({ value, label: value }));
  const roleRows = selected => selected.flatMap(role => roleTemplates[role].map(item => ({ role, ...item })));
  const roleFunctionText = selected => roleRows(selected).map(row => row.role + '：' + row.page + ' / ' + row.operation).join('\n') || '无';
  // 只将预置范围转换为页面文案；不解析条件、合并范围或计算有效权限。
  const accessRows = (access, department) => access.flatMap(item => item.scopes.map(id => {
    const source = dataSources.find(s => s.id === item.source), scope = source.scopes.find(s => s.id === id);
    return { source: source.name, scope: scope.name, limits: '表：' + scope.tables + '\n行：' + scope.rows.replace('{department}', deptName(department)) + '\n字段：' + scope.fields };
  }));
  const accessText = (access, department) => accessRows(access, department).map(row => row.source + ' / ' + row.scope + '\n' + row.limits).join('\n') || '未配置';
  const RolePermissions = {
    props: ['selected'],
    computed: { rows() { return roleRows(this.selected); } },
    template: `<el-table :data="rows" class="role-permissions" aria-label="角色对应页面和功能" empty-text="选择角色后展示对应页面和功能">
      <el-table-column prop="role" label="角色" min-width="110"></el-table-column>
      <el-table-column prop="page" label="页面" min-width="180"></el-table-column>
      <el-table-column prop="operation" label="按钮与功能" min-width="240"></el-table-column>
    </el-table>`
  };
  const DataAccessTable = {
    props: ['access', 'department'],
    computed: { rows() { return accessRows(this.access, this.department); } },
    template: `<el-table :data="rows" class="role-permissions data-access-table" aria-label="数据源和访问范围" empty-text="暂无数据访问配置">
      <el-table-column prop="source" label="数据源" min-width="120"></el-table-column>
      <el-table-column prop="scope" label="访问范围" min-width="170"></el-table-column>
      <el-table-column prop="limits" label="表 行 字段限制" min-width="260"></el-table-column>
    </el-table>`
  };
  const DataAccessEditor = {
    components: { DataAccessTable }, props: ['modelValue', 'popupTarget', 'department'], emits: ['update:modelValue'],
    data: () => ({ dataSources }),
    methods: {
      update(index, patch) { this.$emit('update:modelValue', this.modelValue.map((item, i) => i === index ? { ...item, ...patch } : item)); },
      remove(index) { this.$emit('update:modelValue', this.modelValue.filter((_, i) => i !== index)); },
      add() { this.$emit('update:modelValue', [...this.modelValue, { source: '', scopes: [] }]); },
      scopes(item) { return dataSources.find(s => s.id === item.source)?.scopes || []; },
      selectedAccess() { return this.modelValue.filter(item => item.source && item.scopes.length); }
    },
    template: `<div class="data-access-editor">
      <div v-for="(item,index) in modelValue" :key="index" class="data-access-row">
        <el-select :model-value="item.source" @update:model-value="update(index,{source:$event,scopes:[]})" :aria-label="'数据源 '+(index+1)" :append-to="popupTarget" placeholder="选择数据源">
          <el-option v-for="source in dataSources" :key="source.id" :label="source.name" :value="source.id" :disabled="modelValue.some((value,i)=>i!==index && value.source===source.id)"></el-option>
        </el-select>
        <el-select :model-value="item.scopes" @update:model-value="update(index,{scopes:$event})" :aria-label="'访问范围 '+(index+1)" multiple :disabled="!item.source" :append-to="popupTarget" placeholder="选择访问范围">
          <el-option v-for="scope in scopes(item)" :key="scope.id" :label="scope.name" :value="scope.id"></el-option>
        </el-select>
        <el-button link type="danger" @click="remove(index)" :aria-label="'移除数据源 '+(index+1)">移除</el-button>
      </div>
      <el-button v-if="modelValue.length < dataSources.length" @click="add">添加数据源</el-button>
      <data-access-table :access="selectedAccess()" :department="department"></data-access-table>
    </div>`
  };
  const validateAccess = access => access.some(item => !item.source || !item.scopes.length) ? '请为每项选择数据源和访问范围，或移除未填写的项。' : undefined;
  const timestamp = () => new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16);
  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function button(text, action, className = 'admin-button', focusKey) {
    const node = el('button', className, text); node.type = 'button';
    if (focusKey) node.dataset.focus = focusKey;
    node.addEventListener('click', () => action(node)); return node;
  }
  function textCell(text) { const node = el('span', 'cell-text', text || '—'); node.title = text || ''; return node; }
  function status(value) { return el('span', 'status-tag' + (['停用', '已归档', '来源停用', '已驳回', '已撤回'].includes(value) ? ' is-disabled' : ['待首次登录', '待复核', '待审批'].includes(value) ? ' is-pending' : ''), value); }
  function moreActions(items, key, label, popupTarget = document.body) {
    const wrap = el('span', 'row-more prototype-ui');
    PrototypeUI.mount(wrap, {
      setup() {
        const { h, onMounted, ref } = Vue, dropdown = ref();
        // 关闭浮层后回到触发按钮；阻止 Escape 同时关闭外层详情抽屉。
        const dismiss = event => {
          if (event.key !== 'Escape') return;
          event.preventDefault(); event.stopPropagation();
          dropdown.value.handleClose(); wrap.querySelector('button').focus();
        };
        onMounted(() => wrap.querySelector('.more-chevron').append(WorkbenchIcon('chevron-down', 14)));
        return () => h(ElementPlus.ElDropdown, {
          ref: dropdown, trigger: 'click', placement: 'bottom-end', appendTo: popupTarget, onKeydownCapture: dismiss,
          popperClass: 'prototype-ui-popper people-dropdown',
          onCommand: index => items[Number(index)].action(wrap.querySelector('button'))
        }, {
          default: () => h(ElementPlus.ElButton, {
            link: !label, nativeType: 'button', class: label ? 'department-action-trigger' : 'more-trigger',
            'data-focus': key, 'aria-label': label || '更多操作'
          }, { default: () => [h('span', null, label || '更多'), h('span', { class: 'more-chevron', 'aria-hidden': 'true' })] }),
          dropdown: () => h(ElementPlus.ElDropdownMenu, { onKeydownCapture: dismiss }, { default: () => items.map((item, index) =>
            h(ElementPlus.ElDropdownItem, { command: index, divided: Boolean(item.separator), class: item.danger ? 'is-danger' : '' }, { default: () => item.label })) })
        });
      }
    });
    return wrap;
  }
  function pair(label, value) { const item = el('div'); item.append(el('dt', '', label), el('dd', '', value || '—')); return item; }
  function notify(text) {
    const node = document.getElementById('admin-feedback'); clearTimeout(feedbackTimer);
    node.textContent = text; node.hidden = false;
    feedbackTimer = setTimeout(() => { node.hidden = true; }, 3500);
  }
  function restoreFocus(trigger) {
    const key = trigger?.dataset.focus;
    const target = trigger?.isConnected ? trigger : key ? [...main.querySelectorAll('[data-focus]')].find(n => n.dataset.focus === key) : null;
    (target || main.querySelector('h1'))?.focus({ preventScroll: true });
  }
  function modal(title, trigger, detail = false) {
    const node = el('dialog', 'admin-dialog prototype-ui' + (detail ? ' detail-dialog' : ''));
    const heading = el('header', 'dialog-heading'); const label = el('h2', '', title); label.id = 'active-dialog-title';
    node.setAttribute('aria-labelledby', label.id);
    const close = button('', () => node.close(), 'icon-close'); close.setAttribute('aria-label', '关闭' + title); close.append(WorkbenchIcon('x'));
    heading.append(label, close); node.append(heading); document.body.append(node);
    node.addEventListener('close', () => { node.remove(); restoreFocus(trigger); }, { once: true });
    node.addEventListener('click', event => {
      const rect = node.getBoundingClientRect();
      if (event.target === node && (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom)) node.close();
    });
    return node;
  }
  const display = value => Array.isArray(value) ? value.join('、') || '无' : value || '无';
  const change = (label, before, after) => ({ label, before: display(before), after: display(after) });
  // 预览只比较表单与示例行；确认前不写入，返回修改和取消均保留原状态。
  function openForm({ title, trigger, fields, value, note = '', saveLabel = '保存', save, preview, validate, danger = false, success }) {
    const node = modal(title, trigger), host = el('div'); node.append(host);
    PrototypeUI.mount(host, {
      components: { RolePermissions, DataAccessEditor },
      data: () => ({ model: structuredClone(value), fields, note, error: '', saveLabel, popupTarget: node, reviewing: false, reviewRows: [], danger,
        rules: Object.fromEntries(fields.map(f => [f.key, [
          ...(f.type === 'checkbox' ? [{ required: true, validator: (_, v, done) => done(v ? undefined : new Error('请先完成复核确认')), trigger: 'change' }] : f.required ? [{ required: true, message: f.options ? (f.label.startsWith('选择') ? '请' : '请选择') + f.label : '请填写' + f.label, trigger: f.options ? 'change' : 'blur' }] : []),
          ...(f.key === 'email' ? [{ type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }] : [])
        ]])) }),
      watch: { model: { deep: true, handler() { this.error = ''; } } },
      methods: {
        cancel() { node.close(); },
        commit() {
          const message = save(structuredClone(Vue.toRaw(this.model)));
          if (message) { this.error = message; this.reviewing = false; return; }
          node.close(); render(); notify(success || title + '成功');
        },
        submit() { if (this.reviewing) { this.commit(); return; } this.$refs.form.validate(valid => {
          if (!valid) return;
          const data = structuredClone(Vue.toRaw(this.model)), message = validate?.(data);
          if (message) { this.error = message; return; }
          if (preview) { this.reviewRows = preview(data); this.reviewing = true; this.$nextTick(() => node.querySelector('.review-heading')?.focus()); }
          else this.commit();
        }); }
      },
      template: `<el-form ref="form" :model="model" :rules="rules" label-position="top" scroll-to-error @submit.prevent="submit">
        <div class="dialog-body"><p v-if="note" class="form-note">{{note}}</p><p v-if="error" class="form-error" role="alert">{{error}}</p>
        <div v-show="!reviewing" class="form-grid"><el-form-item v-for="f in fields" :key="f.key" :label="f.label" :prop="f.key" :class="{'full-width': f.full}">
          <el-select v-if="f.options" v-model="model[f.key]" :aria-label="f.label" :multiple="f.multiple" :disabled="f.disabled" :clearable="!f.required" :append-to="popupTarget" filterable :placeholder="f.label.startsWith('选择') ? '请'+f.label : '请选择'+f.label">
            <el-option v-for="o in f.options" :key="o.value" :value="o.value" :label="o.label" :disabled="o.disabled"></el-option>
          </el-select>
          <data-access-editor v-else-if="f.type === 'data-access'" v-model="model[f.key]" :popup-target="popupTarget" :department="f.department"></data-access-editor>
          <el-checkbox v-else-if="f.type === 'checkbox'" v-model="model[f.key]">{{f.checkLabel}}</el-checkbox>
          <el-date-picker v-else-if="f.type === 'date'" :model-value="model[f.key]" @update:model-value="model[f.key]=$event || ''" @keydown.enter.prevent type="date" format="YYYY-MM-DD" value-format="YYYY-MM-DD" clearable :aria-label="f.label" :append-to="popupTarget" popper-class="prototype-ui-popper people-date-popper" placeholder="请选择日期" style="width:100%"></el-date-picker>
          <el-input v-else v-model="model[f.key]" :aria-label="f.label" :type="f.type || 'text'" :show-password="f.type === 'password'" :autocomplete="f.type === 'password' ? 'new-password' : 'off'" :disabled="f.disabled" :maxlength="f.type === 'textarea' ? 300 : 80" :rows="3" :show-word-limit="f.type === 'textarea'" :placeholder="f.placeholder || ''"></el-input>
          <span v-if="f.help" class="field-help">{{f.help}}</span>
          <role-permissions v-if="f.rolePreview" class="selected-role-preview" :selected="model[f.key]"></role-permissions>
        </el-form-item></div>
        <section v-if="reviewing" class="change-preview"><h3 class="review-heading" tabindex="-1">核对变更与影响</h3>
          <table><thead><tr><th>内容</th><th>当前</th><th>变更后</th></tr></thead><tbody><tr v-for="row in reviewRows" :key="row.label"><th>{{row.label}}</th><td>{{row.before}}</td><td>{{row.after}}</td></tr></tbody></table>
        </section></div>
        <footer class="dialog-footer"><el-button @click="cancel">取消</el-button><el-button v-if="reviewing" @click="reviewing=false">返回修改</el-button><el-button :type="danger ? 'danger' : 'primary'" native-type="submit">{{reviewing ? '确认'+saveLabel : ${Boolean(preview)} ? '预览变更' : saveLabel}}</el-button></footer>
      </el-form>`
    });
    node.showModal();
  }
  function confirmChange({ title, trigger, description, save, preview, danger = false }) {
    openForm({ title, trigger, note: description, fields: [{ key: 'reason', label: '操作原因', type: 'textarea', required: true, full: true }],
      value: { reason: '' }, saveLabel: title.slice(0, 2), save, preview, danger });
  }
  function departmentOptions(current) {
    return departments.filter(d => d.status === '正常' || d.id === current).map(d => ({ value: d.id, label: d.name + (d.status !== '正常' ? '（' + d.status + '）' : '') }));
  }
  function userForm(user, trigger) {
    const initialDepartment = departments.find(d => d.id === selectedDepartment)?.status === '正常' ? selectedDepartment : '';
    const value = user || { name: '', login: '', department: initialDepartment, extra: [], roles: ['普通用户'], roleUntil: '', phone: '', email: '', note: '' };
    openForm({ title: user ? '编辑人员' : '新增人员', trigger, value: { ...value, initialPassword: '' },
      fields: [
        { key: 'name', label: '姓名', required: true }, { key: 'login', label: '登录名', required: true, disabled: Boolean(user) },
        ...(!user ? [{ key: 'initialPassword', label: '初始密码', type: 'password', required: true, full: true, help: '首次登录需修改初始密码；登录账号为登录名。' }] : []),
        { key: 'department', label: '主部门', options: departmentOptions(value.department), required: true },
        { key: 'extra', label: '兼任部门', options: departmentOptions(), multiple: true },
        ...(!user ? [{ key: 'roles', label: '初始个人角色', options: options(roles), multiple: true, full: true }] : []),
        { key: 'phone', label: '手机号' }, { key: 'email', label: '邮箱' },
        { key: 'note', label: '备注', type: 'textarea', full: true }
      ],
      validate: data => {
        data.name = data.name.trim(); data.login = data.login.trim();
        if (!data.name || !data.login) return '姓名和登录名不能为空。';
        if (users.some(u => u.id !== user?.id && u.login === data.login)) return '登录名已存在，请更换。';
        if (!user && !/^(?=.*[A-Za-z])(?=.*\d).{8,32}$/.test(data.initialPassword)) return '初始密码需为 8–32 位，包含字母和数字。';
      },
      preview: data => [change('姓名', user?.name, data.name), change('主部门', user ? deptName(user.department) : '', deptName(data.department)),
        change('兼任部门', user?.extra.map(deptName), data.extra.filter(id => id !== data.department).map(deptName)),
        change('个人角色', user?.roles, data.roles), change('手机号', user?.phone, data.phone), change('邮箱', user?.email, data.email), change('备注', user?.note, data.note),
        ...(!user ? [change('初始密码', '', '已设置，不回显')] : []), change('账号状态', user?.status, user?.status || '待首次登录')],
      success: user ? '人员资料已保存' : '人员已创建，可使用登录名和初始密码登录',
      save: data => {
        data.name = data.name.trim(); data.login = data.login.trim();
        data.extra = data.extra.filter(id => id !== data.department);
        // 密码只参与表单展示，不写入示例数据、预览或本地存储。
        const { initialPassword, ...profile } = data;
        if (user) Object.assign(user, profile, { updated: timestamp() });
        else { users.unshift({ ...profile, id: 'u' + Date.now(), status: '待首次登录', mustChangePassword: true, updated: timestamp() }); queries.users.page = 1; }
      }
    });
  }
  function assignRoles(user, trigger) {
    openForm({ title: '分配角色', trigger, value: { roles: [...user.roles], roleUntil: user.roleUntil || '', reason: '' },
      note: user.name + ' · 角色控制页面、按钮和功能。部门初始数据访问及已生效申请保持不变。',
      fields: [{ key: 'roles', label: '个人角色', options: options(roles), multiple: true, full: true, rolePreview: true },
        { key: 'roleUntil', label: '角色有效至', type: 'date', full: true, help: '留空为长期' }, { key: 'reason', label: '分配原因', type: 'textarea', required: true, full: true }],
      preview: data => [change('个人角色', user.roles, data.roles), change('角色期限', user.roleUntil || '长期', data.roleUntil || '长期'),
        change('页面、按钮与功能', roleFunctionText(user.roles), roleFunctionText(data.roles)), change('初始与申请数据访问', '按独立配置', '保持不变')],
      save: data => { user.roles = data.roles; user.roleUntil = data.roleUntil; user.updated = timestamp(); } });
  }
  function toggleUser(user, trigger) {
    const disable = user.status !== '停用';
    if (!disable) {
      openForm({ title: '复核并恢复人员', trigger,
        note: '核对当前归属、角色与个人临时授权。过期来源和旧任务不随账号恢复。',
        value: { department: departments.find(d => d.id === user.department)?.status === '正常' ? user.department : '', roles: [...user.roles], roleUntil: user.roleUntil, reviewed: false, reason: '' },
        fields: [{ key: 'department', label: '当前主部门', options: departmentOptions(), required: true, full: true },
          { key: 'roles', label: '复核个人角色', options: options(roles), multiple: true, full: true },
          { key: 'roleUntil', label: '角色有效至', type: 'date', full: true },
          { key: 'reviewed', label: '恢复前复核', type: 'checkbox', checkLabel: '已核对部门、角色和临时授权', full: true },
          { key: 'reason', label: '恢复原因', type: 'textarea', required: true, full: true }],
        preview: data => [change('账号状态', '停用', '正常'), change('主部门', deptName(user.department), deptName(data.department)),
          change('个人角色', user.roles, data.roles), change('来源期限', user.roleUntil || '长期', data.roleUntil || '长期'), change('旧任务与过期来源', '已结束', '不自动恢复')],
        saveLabel: '恢复', save: data => { Object.assign(user, { department: data.department, roles: data.roles, roleUntil: data.roleUntil, status: '正常', updated: timestamp() }); user.extra = user.extra.filter(id => id !== data.department); } });
      return;
    }
    confirmChange({ title: disable ? '停用人员' : '恢复人员', trigger,
      description: '将停用“' + user.name + '”。该人员将无法继续使用系统，人员资料、旧配置和历史记录保留。', danger: true,
      preview: () => [change('账号状态', user.status, '停用'), change('继续访问', '按现有配置', '停止'), change('人员资料与历史', '保留', '保留')],
      save: () => { user.status = disable ? '停用' : '正常'; user.updated = timestamp(); } });
  }
  function firstLogin(user, trigger) {
    const node = modal('首次登录说明', trigger), body = el('div', 'dialog-body');
    body.append(el('h3', '', user.name + ' · ' + user.login));
    const steps = el('ol', 'first-login-steps');
    for (const [title, text] of [['账号已创建', '管理员已设置登录名和初始密码'], ['使用账号密码登录', '在登录页输入登录名“' + user.login + '”和初始密码'], ['修改初始密码', '设置新密码后重新登录，即可使用获准功能']]) {
      const step = el('li'); step.append(el('strong', '', title), el('p', '', text)); steps.append(step);
    }
    body.append(steps); const footer = el('footer', 'dialog-footer');
    const preview = el('a', 'admin-button is-primary', '查看首次登录页面'); preview.href = '../app/login.html?first=1';
    footer.append(button('关闭', () => node.close()), preview);
    node.append(body, footer); node.showModal();
  }
  function resetPassword(user, trigger) {
    openForm({ title: '重置登录密码', trigger, value: { initialPassword: '', reason: '' },
      note: '为“' + user.name + '”设置新的初始密码。重置不改变部门、角色和数据范围，下次登录必须修改密码。',
      fields: [{ key: 'initialPassword', label: '新初始密码', type: 'password', required: true, full: true }, { key: 'reason', label: '重置原因', type: 'textarea', required: true, full: true }],
      validate: data => /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/.test(data.initialPassword) ? undefined : '初始密码需为 8–32 位，包含字母和数字。',
      preview: () => [change('登录名', user.login, user.login), change('密码', '不回显', '设为新初始密码'), change('下次登录', '原设置', '必须修改密码'), change('部门、角色与范围', '保留', '保持不变')],
      saveLabel: '重置', save: () => { user.mustChangePassword = true; user.updated = timestamp(); } });
  }
  function transferUser(user, trigger) {
    openForm({ title: '调整人员归属', trigger, value: { department: user.department, extra: [...user.extra], reason: '' },
      note: user.name + ' · 调岗调整部门初始数据访问，个人角色、申请授权与其他部门数据访问保持不变。',
      fields: [{ key: 'department', label: '主部门', options: departmentOptions(user.department), required: true, full: true },
        { key: 'extra', label: '兼任部门', options: departmentOptions(), multiple: true, full: true },
        { key: 'reason', label: '调整原因', type: 'textarea', required: true, full: true }],
      preview: data => [change('主部门', deptName(user.department), deptName(data.department)), change('兼任部门', user.extra.map(deptName), data.extra.filter(id => id !== data.department).map(deptName)),
        change('个人角色与申请授权', '保留', '保持不变'), change('部门数据访问', '原所属部门配置', '结束移出来源，关联新部门初始数据访问')],
      save: data => { user.department = data.department; user.extra = data.extra.filter(id => id !== data.department); user.updated = timestamp(); } });
  }
  function archiveUser(user, trigger) {
    if (user.status !== '停用') { notify('请先停用人员，再核对归档'); return; }
    confirmChange({ title: '归档人员', trigger, danger: true, description: '归档“' + user.name + '”后，仅保留资料和历史回看，不再提供日常维护操作，登录名不复用。',
      preview: () => [change('账号状态', '停用', '已归档'), change('登录名', user.login, '保留，不复用'), change('历史记录', '保留', '保留')],
      save: () => { user.status = '已归档'; user.updated = timestamp(); } });
  }
  function userActions(user) {
    if (user.status === '已归档') return [];
    return [...(user.status === '待首次登录' ? [{ label: '首次登录说明', action: trigger => firstLogin(user, trigger) }] : []),
      ...(user.status !== '停用' ? [{ label: '重置密码', action: trigger => resetPassword(user, trigger) }] : []),
      { label: '调整归属', action: trigger => transferUser(user, trigger) },
      { label: user.status === '停用' ? '复核并恢复' : '停用人员', separator: true, danger: user.status !== '停用', action: trigger => toggleUser(user, trigger) },
      ...(user.status === '停用' ? [{ label: '归档人员', danger: true, action: trigger => archiveUser(user, trigger) }] : [])];
  }
  function userDetail(user, trigger) {
    const node = modal('人员详情', trigger, true), body = el('div', 'dialog-body');
    const overview = el('div', 'person-overview'), name = el('div'); name.append(el('h2', '', user.name), status(user.status));
    overview.append(el('span', 'person-avatar', user.name.slice(0, 1)), name);
    const fields = el('dl', 'detail-fields'); fields.append(pair('登录名', user.login), pair('主部门', deptName(user.department)),
      pair('兼任部门', user.extra.map(deptName).join('、')), pair('手机号', user.phone), pair('邮箱', user.email), pair('更新时间', user.updated), pair('登录要求', user.mustChangePassword ? '下次登录须修改初始密码' : '使用登录名和密码'));
    const functions = el('section', 'detail-section'); functions.append(el('h2', '', '个人角色'), el('p', 'form-note', '角色有效至：' + (user.roleUntil || '长期')));
    const roleHost = el('div', 'prototype-ui');
    PrototypeUI.mount(roleHost, { render: () => Vue.h(RolePermissions, { selected: user.roles }) }); functions.append(roleHost);
    const sources = el('section', 'detail-section'); sources.append(el('h2', '', '数据访问来源'), dataAccessSources(user));
    body.append(overview, fields, functions, sources);
    if (user.note) { const note = el('section', 'detail-section'); note.append(el('h2', '', '备注'), el('p', '', user.note)); body.append(note); }
    const actions = el('footer', 'dialog-footer');
    if (user.status !== '已归档') {
      if (user.status === '待首次登录') actions.append(button('首次登录说明', () => { node.close(); firstLogin(user, trigger); }));
      actions.append(button('分配角色', () => { node.close(); assignRoles(user, trigger); }),
        button('编辑人员', () => { node.close(); userForm(user, trigger); }, 'admin-button is-primary'),
        moreActions(userActions(user).map(item => ({ ...item, action: () => { node.close(); item.action(trigger); } })), 'detail-more', undefined, node));
    } else actions.append(button('关闭', () => node.close()));
    node.append(body, actions); node.showModal();
  }
  function dataAccessSources(user) {
    const host = el('div', 'data-access-sources');
    const sources = [...new Set([user.department, ...user.extra])].map(id => departments.find(d => d.id === id))
      .filter(d => d?.access.length).map(d => ({ label: '部门初始访问 · ' + d.name, access: d.access, department: d.id, until: d.until,
        state: d.status !== '正常' ? '来源' + d.status : d.requiresReview ? '待复核' : '' }));
    for (const request of extraRequests.filter(r => r.user === user.id && r.status === '已生效')) sources.push({ label: '申请授权 · ' + request.id, access: request.access, department: request.department, until: request.until });
    for (const source of sources) {
      const section = el('section', 'data-access-source'), heading = el('div', 'section-heading');
      heading.append(el('h3', '', source.label), el('span', 'cell-sub', source.until || '长期'));
      if (source.state) heading.append(status(source.state));
      const table = el('div', 'prototype-ui');
      PrototypeUI.mount(table, { render: () => Vue.h(DataAccessTable, { access: source.access, department: source.department }) });
      section.append(heading, table); host.append(section);
    }
    if (!sources.length) host.append(el('p', 'form-note', '暂无数据访问来源'));
    return host;
  }
  function filterControls(kind, target) {
    return RecordControls.filters({ value: queries[kind], includeDates: false,
      keywordLabel: '搜索人员', keywordPlaceholder: kind === 'users' ? '姓名、登录名、部门或角色' : '姓名或登录名',
      statuses: [{ value: 'all', label: '全部状态' }, ...['正常', '待首次登录', '停用', ...(kind === 'users' ? ['已归档'] : [])].map(value => ({ value, label: value }))],
      onApply: next => { Object.assign(queries[kind], next); target(); main.querySelector('input[name="keyword"]')?.focus({ preventScroll: true }); },
      onReset: () => { Object.assign(queries[kind], { keyword: '', status: 'all', page: 1 }); target(); main.querySelector('input[name="keyword"]')?.focus({ preventScroll: true }); }
    });
  }
  function applyExtraAccess(trigger) {
    openForm({ title: '申请额外数据访问', trigger, value: { access: [{ source: '', scopes: [] }], until: '', reason: '' },
      note: currentUser.name + ' · 审批并生效后才可访问，个人角色和部门初始访问保持不变。',
      fields: [{ key: 'access', label: '申请的数据源与范围', type: 'data-access', full: true, department: currentUser.department },
        { key: 'until', label: '申请有效至', type: 'date', required: true, full: true },
        { key: 'reason', label: '业务用途', type: 'textarea', required: true, full: true }],
      validate: data => !data.access.length ? '请至少选择一个数据源与访问范围。' : validateAccess(data.access),
      preview: data => [change('申请数据源与范围', '', accessText(data.access, currentUser.department)), change('申请有效至', '', data.until), change('业务用途', '', data.reason), change('提交后的状态', '', '待审批，尚不可访问'), change('角色与部门初始数据访问', '保留', '保持不变')],
      saveLabel: '提交申请', success: '申请已提交，等待审批',
      save: data => { extraRequests.unshift({ ...data, id: 'SQ-' + Date.now(), user: currentUser.id, department: currentUser.department, status: '待审批', submitted: timestamp() }); } });
  }
  function requestDetail(request, trigger) {
    const node = modal('数据访问申请详情', trigger), body = el('div', 'dialog-body'), fields = el('dl', 'detail-fields');
    fields.append(pair('申请编号', request.id), pair('状态', request.status), pair('提交时间', request.submitted), pair('申请有效至', request.until));
    const table = el('div', 'prototype-ui');
    PrototypeUI.mount(table, { render: () => Vue.h(DataAccessTable, { access: request.access, department: request.department }) });
    body.append(fields, el('h3', '', '业务用途'), el('p', '', request.reason), table);
    if (request.result) body.append(el('p', 'form-note', request.result));
    const footer = el('footer', 'dialog-footer'); footer.append(button('关闭', () => node.close()));
    if (request.status === '待审批') footer.append(button('撤回申请', () => { node.close(); confirmChange({ title: '撤回申请', trigger, danger: true,
      description: '撤回“' + request.id + '”，当前数据访问保持不变。', preview: () => [change('申请状态', '待审批', '已撤回')], save: () => { request.status = '已撤回'; } }); }, 'admin-button is-danger'));
    node.append(body, footer); node.showModal();
  }
  function renderMyAccess() {
    const card = el('section', 'personal-access-card'), heading = el('div', 'section-heading');
    heading.append(el('h2', '', '当前数据访问来源'), button('申请额外数据访问', applyExtraAccess, 'admin-button is-primary', 'apply-data-access'));
    const history = el('a', 'access-request-link', '查看我的申请'); history.href = '#my-requests';
    card.append(heading, dataAccessSources(currentUser), history);
    main.replaceChildren(pageHeading('我的访问范围'), card);
  }
  function renderMyRequests() {
    const q = queries.requests, tools = el('section', 'people-query-card');
    tools.append(RecordControls.filters({ value: q, includeDates: false, keywordLabel: '搜索申请', keywordPlaceholder: '申请编号、用途或数据源',
      statuses: [{ value: 'all', label: '全部状态' }, ...['待审批', '已生效', '已驳回', '已撤回'].map(value => ({ value, label: value }))],
      onApply: next => { Object.assign(q, next); renderMyRequests(); }, onReset: () => { Object.assign(q, { keyword: '', status: 'all', page: 1 }); renderMyRequests(); }
    }), button('申请额外数据访问', applyExtraAccess, 'admin-button is-primary', 'apply-data-access'));
    const records = extraRequests.filter(r => r.user === currentUser.id && (q.status === 'all' || q.status === r.status) &&
      [r.id, r.reason, ...r.access.map(a => dataSources.find(s => s.id === a.source).name)].join(' ').toLowerCase().includes(q.keyword.toLowerCase()));
    q.page = Math.max(1, Math.min(q.page, Math.ceil(records.length / q.pageSize)));
    const panel = el('section', 'record-panel'), empty = el('div', 'empty-state'); empty.append(el('p', '', '暂无匹配的申请'));
    panel.append(RecordTable.render({ caption: '我的数据访问申请', minWidth: 800,
      columns: [{ key: 'request', label: '申请事项' }, { key: 'submitted', label: '提交时间', width: 166 }, { key: 'status', label: '状态', width: 104 }, { key: 'actions', label: '操作', width: 144 }],
      items: records.slice((q.page - 1) * q.pageSize, q.page * q.pageSize), empty,
      row: request => { const title = el('div'); title.append(button(request.reason, trigger => requestDetail(request, trigger), 'cell-title'), el('span', 'cell-sub', request.id));
        return [title, textCell(request.submitted), status(request.status), button('查看详情', trigger => requestDetail(request, trigger), 'do-action is-primary request-detail-action')]; }
    }), RecordControls.pager({ total: records.length, ...q }, page => { q.page = page; renderMyRequests(); }, size => { q.pageSize = size; q.page = 1; renderMyRequests(); }));
    main.replaceChildren(pageHeading('我的申请'), tools, panel);
  }
  function userTable(records, kind, target) {
    const q = queries[kind], keyword = q.keyword.toLowerCase();
    const found = records.filter(u => (q.status === 'all' || u.status === q.status) &&
      [u.name, u.login, deptName(u.department), ...u.extra.map(deptName), ...u.roles].join(' ').toLowerCase().includes(keyword));
    q.page = Math.max(1, Math.min(q.page, Math.ceil(found.length / q.pageSize)));
    const empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('search'), el('p', '', keyword || q.status !== 'all' ? '没有匹配的人员' : '该部门暂无成员'));
    if (keyword || q.status !== 'all') empty.append(button('清空筛选', () => { Object.assign(q, { keyword: '', status: 'all', page: 1 }); target(); }));
    const columns = [{ key: 'person', label: '人员' },
      ...(kind === 'users' ? [{ key: 'department', label: '部门', width: 160 }, { key: 'roles', label: '个人角色', width: 200 }] : [{ key: 'department', label: '所属部门', width: 200 }, { key: 'membership', label: '成员关系', width: 112 }]),
      { key: 'status', label: '状态', width: 104 }, ...(kind === 'users' ? [{ key: 'updated', label: '更新时间', width: 166 }] : []), { key: 'actions', label: '操作', width: kind === 'users' ? 256 : 200 }];
    const table = RecordTable.render({ caption: kind === 'users' ? '人员列表' : '部门成员', minWidth: kind === 'users' ? 1112 : 840, columns,
      items: found.slice((q.page - 1) * q.pageSize, q.page * q.pageSize), empty,
      row(user) {
        const person = el('div'), title = button(user.name, trigger => userDetail(user, trigger), 'cell-title', 'person-' + user.id);
        title.title = user.name; person.append(title, el('span', 'cell-sub', user.login));
        const department = el('div'); department.append(textCell(deptName(user.department)));
        if (user.extra.length) department.append(el('span', 'cell-sub', '兼任：' + user.extra.map(deptName).join('、')));
        const actions = el('div', 'do-actions');
        if (user.status !== '已归档') {
          actions.append(button(kind === 'users' ? '编辑' : '调岗', trigger => kind === 'users' ? userForm(user, trigger) : transferUser(user, trigger), 'do-action is-primary', 'edit-' + user.id));
          if (kind === 'users') actions.append(button('分配角色', trigger => assignRoles(user, trigger), 'do-action', 'roles-' + user.id), moreActions(userActions(user), 'more-' + user.id));
          else if (user.extra.includes(selectedDepartment)) actions.append(button('移出本部门', trigger => removeMember(user, departments.find(d => d.id === selectedDepartment), trigger), 'do-action', 'remove-' + user.id));
        } else actions.append(el('span', 'cell-sub', '仅供回看'));
        const ids = includeChildren ? branch(selectedDepartment) : [selectedDepartment];
        const stateCell = el('div'); stateCell.append(status(user.status));
        if (user.mustChangePassword && user.status === '正常') stateCell.append(el('span', 'cell-sub', '下次登录改密'));
        return [person, ...(kind === 'users' ? [department, textCell(user.roles.join('、'))] : [department, textCell(ids.includes(user.department) ? (user.extra.some(id => ids.includes(id)) ? '主部门及兼任' : '主部门') : '兼任')]),
          stateCell, ...(kind === 'users' ? [textCell(user.updated)] : []), actions];
      }
    });
    return [table, RecordControls.pager({ total: found.length, page: q.page, pageSize: q.pageSize },
      page => { q.page = page; target(); }, size => { q.pageSize = size; q.page = 1; target(); })];
  }
  function pageHeading(title) { const label = el('h1', 'sr-only', title); label.tabIndex = -1; return label; }
  function renderUsers() {
    const tools = el('section', 'people-query-card');
    tools.append(filterControls('users', renderUsers), button('新增人员', trigger => userForm(null, trigger), 'admin-button is-primary', 'new-user'));
    main.replaceChildren(pageHeading('人员管理'), tools);
    const panel = el('section', 'record-panel'); panel.append(...userTable(users, 'users', renderUsers)); main.append(panel);
  }
  function departmentForm(department, trigger, parent = selectedDepartment) {
    // 这里只排除会让组织树无法显示的循环父节点，不处理授权继承。
    const below = id => id === department?.id || Boolean(departments.find(d => d.id === id)?.parent && below(departments.find(d => d.id === id).parent));
    const options = departments.filter(d => (d.status === '正常' || d.id === department?.parent) && !below(d.id)).map(d => ({ value: d.id, label: d.name + (d.status === '正常' ? '' : '（' + d.status + '）') }));
    openForm({ title: department ? '编辑部门' : parent ? '新增子部门' : '新增部门', trigger,
      value: department || { name: '', code: '', parent, manager: '', note: '' },
      fields: [{ key: 'name', label: '部门名称', required: true }, { key: 'code', label: '部门编号', required: true },
        { key: 'parent', label: '上级部门', options: [{ value: '', label: '无（顶级部门）' }, ...options], full: true },
        { key: 'manager', label: '负责人', options: users.filter(u => u.status !== '已归档').map(u => ({ value: u.name, label: u.name })) },
        { key: 'note', label: '部门说明', type: 'textarea', full: true }],
      validate: data => {
        data.name = data.name.trim(); data.code = data.code.trim();
        if (!data.name || !data.code) return '部门名称和编号不能为空。';
        if (departments.some(d => d.id !== department?.id && d.code === data.code)) return '部门编号已存在，请更换。';
      },
      preview: data => [change('部门名称', department?.name, data.name), change('部门编号', department?.code, data.code), change('上级部门', department ? deptName(department.parent) : '', deptName(data.parent)),
        change('负责人', department?.manager, data.manager), change('部门说明', department?.note, data.note), change('业务权限', '按明确来源配置', '不随组织层级自动继承')],
      save: data => {
        data.name = data.name.trim(); data.code = data.code.trim();
        if (department) Object.assign(department, data);
        else { const item = { ...data, id: 'd' + Date.now(), status: '正常', access: [], until: '', created: timestamp() }; departments.push(item); selectedDepartment = item.id; treeKeyword = ''; departmentTab = 'members'; }
      }
    });
  }
  function joinMembers(d, trigger) {
    const candidates = users.filter(u => ['正常', '待首次登录'].includes(u.status) && !members(d.id).includes(u));
    if (!candidates.length) { notify('当前没有可加入的人员'); return; }
    openForm({ title: '加入部门成员', trigger, note: '加入“' + d.name + '”后为兼任成员，原主部门保持不变；更换主部门请使用调岗。',
      value: { ids: [], reason: '' },
      fields: [{ key: 'ids', label: '选择人员', full: true, multiple: true, required: true, options: candidates.map(u => ({ value: u.id, label: u.name + ' · ' + deptName(u.department) })) },
        { key: 'reason', label: '加入原因', type: 'textarea', required: true, full: true }],
      preview: data => [change('所选人员', '', data.ids.map(id => users.find(u => u.id === id).name)), change('兼任部门', '原配置保留', '新增：' + d.name),
        change('主部门', '原主部门', '保持不变'), change('部门初始数据访问', '', accessText(d.access, d.id)), change('个人角色与申请授权', '保留', '保持不变')],
      save: data => { for (const id of data.ids) { const user = users.find(u => u.id === id); user.extra.push(d.id); user.updated = timestamp(); } }
    });
  }
  function removeMember(user, d, trigger) {
    if (user.department === d.id) { transferUser(user, trigger); return; }
    confirmChange({ title: '移出兼任成员', trigger, danger: true, description: '仅将“' + user.name + '”移出“' + d.name + '”，主部门及其他来源保留。',
      preview: () => [change('兼任部门', user.extra.map(deptName), user.extra.filter(id => id !== d.id).map(deptName)), change('本部门初始数据访问', '关联', '结束'), change('个人角色、申请及其他部门访问', '保留', '保持不变')],
      save: () => { user.extra = user.extra.filter(id => id !== d.id); user.updated = timestamp(); } });
  }
  function moveDepartmentMembers(d, trigger) {
    const affected = members(d.id), destinations = departmentOptions().filter(o => !branch(d.id).includes(o.value));
    openForm({ title: '迁移直属成员', trigger, value: { destination: '', reason: '' }, note: '仅迁移“' + d.name + '”直属的主部门与兼任关系，不处理下级部门成员。',
      fields: [{ key: 'destination', label: '迁入部门', options: destinations, required: true, full: true }, { key: 'reason', label: '迁移原因', type: 'textarea', required: true, full: true }],
      preview: data => [change('受影响人员', affected.map(u => u.name), affected.length + ' 人'), change('部门数据来源', d.name, deptName(data.destination)), change('个人角色、申请及其他部门访问', '保留', '保持不变')],
      save: data => { for (const user of affected) { if (user.department === d.id) user.department = data.destination; user.extra = user.extra.filter(id => id !== d.id && id !== user.department); if (user.department !== data.destination && !user.extra.includes(data.destination)) user.extra.push(data.destination); user.updated = timestamp(); } } });
  }
  function moveChildDepartments(d, trigger) {
    const children = childDepartments(d.id).filter(item => item.status !== '已归档');
    openForm({ title: '迁移下级部门', trigger, value: { parent: '', reason: '' },
      fields: [{ key: 'parent', label: '新的上级部门', required: true, options: departmentOptions().filter(o => !branch(d.id).includes(o.value)), full: true },
        { key: 'reason', label: '迁移原因', type: 'textarea', required: true, full: true }],
      preview: data => [change('下级部门', children.map(item => item.name), '保持部门身份与成员'), change('上级部门', d.name, deptName(data.parent)), change('授权来源', '原配置', '不因组织迁移自动扩大')],
      save: data => { for (const item of children) item.parent = data.parent; } });
  }
  function departmentAccess(d, trigger) {
    openForm({ title: '配置初始数据访问', trigger, value: { access: structuredClone(d.access), until: d.until, reason: '' },
      note: '适用于“' + d.name + '”直属成员。个人角色、申请授权及下级配置保持不变。',
      fields: [{ key: 'access', label: '数据源与访问范围', type: 'data-access', full: true, department: d.id },
        { key: 'until', label: '来源有效至', type: 'date', full: true, help: '留空为长期' }, { key: 'reason', label: '配置原因', type: 'textarea', required: true, full: true }],
      validate: data => validateAccess(data.access),
      preview: data => [change('初始数据源与范围', accessText(d.access, d.id), accessText(data.access, d.id)),
        change('来源期限', d.until || '长期', data.until || '长期'), change('影响对象', members(d.id).map(u => u.name), '本部门直属成员'), change('个人角色、申请与其他部门访问', '保留', '保持不变')],
      save: data => { Object.assign(d, { access: data.access, until: data.until, requiresReview: false }); } });
  }
  function endDepartmentAccess(d, trigger) {
    confirmChange({ title: '结束初始数据访问', trigger, danger: true, description: '结束“' + d.name + '”的初始数据访问。个人角色、已生效申请及其他部门数据访问保留。',
      preview: () => [change('部门初始数据访问', accessText(d.access, d.id), '结束本部门来源'), change('个人角色、申请与其他部门访问', '保留', '保持不变')],
      save: () => { Object.assign(d, { access: [], until: '', requiresReview: false }); } });
  }
  function toggleDepartment(d, trigger) {
    if (d.status === '停用') {
      openForm({ title: '复核并恢复部门', trigger, note: '恢复组织状态后，保留的初始数据访问需再次复核；个人角色保持不变。',
        value: { reviewed: false, reason: '' }, fields: [{ key: 'reviewed', label: '恢复前复核', type: 'checkbox', checkLabel: '已核对成员、下级部门及初始数据访问', full: true }, { key: 'reason', label: '恢复原因', type: 'textarea', required: true, full: true }],
        preview: () => [change('部门状态', '停用', '正常'), change('直属成员', members(d.id).length + ' 人', '保持组织归属'), change('初始数据访问', accessText(d.access, d.id), '待复核')],
        saveLabel: '恢复', save: () => { d.status = '正常'; d.requiresReview = Boolean(d.access.length); } });
      return;
    }
    confirmChange({ title: '停用部门', trigger, danger: true, description: '仅停用当前部门“' + d.name + '”，成员资料保留；下级部门保持各自状态。',
      preview: () => [change('部门状态', '正常', '停用'), change('本部门直属成员', members(d.id).map(u => u.name), '保留归属，结束本部门派生来源'),
        change('下级部门', childDepartments(d.id).length + ' 个', '保持原状态'), change('个人角色、申请及其他部门访问', '保留', '保持不变')],
      save: () => { d.status = '停用'; } });
  }
  function archiveDepartment(d, trigger) {
    // 只核对示例关系并展示处理入口，不执行数据库清理或真实撤权。
    const node = modal('归档部门前核对', trigger), body = el('div', 'dialog-body'), checks = el('div', 'archive-checks');
    const items = [
      ['部门状态', d.status === '停用', d.status, '停用部门', () => toggleDepartment(d, trigger)],
      ['直属成员', members(d.id).length === 0, members(d.id).length + ' 人', '迁移成员', () => moveDepartmentMembers(d, trigger)],
      ['下级部门', childDepartments(d.id).every(item => item.status === '已归档'), childDepartments(d.id).filter(item => item.status !== '已归档').length + ' 个', '迁移下级', () => moveChildDepartments(d, trigger)],
      ['初始数据访问', !d.access.length, d.access.length + ' 个数据源', '结束数据访问', () => endDepartmentAccess(d, trigger)]
    ];
    for (const [label, done, current, action, callback] of items) { const row = el('div', 'archive-check'); const text = el('div'); text.append(el('strong', '', label), el('span', 'cell-sub', current)); row.append(text); if (done) row.append(status('已核对')); else row.append(button(action, () => { node.close(); callback(); })); checks.append(row); }
    body.append(el('p', 'form-note', '关联事项处理完成后可归档，历史身份和记录继续保留。'), checks);
    const footer = el('footer', 'dialog-footer'); footer.append(button('关闭', () => node.close()));
    if (items.every(item => item[1])) footer.append(button('继续归档', () => { node.close(); confirmChange({ title: '归档部门', trigger, danger: true, description: '归档后只保留部门及其历史回看，不再用于人员归属或初始数据访问。',
      preview: () => [change('部门状态', '停用', '已归档'), change('直属成员、下级及初始数据访问', '已处理', '无待处理关联'), change('历史记录', '保留', '保留')], save: () => { d.status = '已归档'; } }); }, 'admin-button is-danger'));
    node.append(body, footer); node.showModal();
  }
  function renderDepartmentContent() {
    const content = main.querySelector('.department-content'); if (!content) return;
    const d = departments.find(item => item.id === selectedDepartment);
    const summary = el('section', 'department-summary'), heading = el('div', 'section-heading'), label = el('div', 'heading-label');
    label.append(el('h2', '', d.name), status(d.status)); const actions = el('div', 'do-actions');
    if (d.status !== '已归档') actions.append(moreActions([
      ...(d.status === '正常' ? [{ label: '新增子部门', action: trigger => departmentForm(null, trigger, d.id) }] : []),
      { label: '编辑部门', action: trigger => departmentForm(d, trigger) },
      { label: d.status === '停用' ? '复核并恢复部门' : '停用部门', separator: true, danger: d.status !== '停用', action: trigger => toggleDepartment(d, trigger) },
      { label: '归档部门', danger: true, action: trigger => archiveDepartment(d, trigger) }
    ], 'department-more', '部门操作'));
    heading.append(label, actions); const meta = el('dl', 'summary-meta');
    meta.append(pair('部门编号', d.code), pair('上级部门', deptName(d.parent)), pair('负责人', d.manager),
      pair('成员范围', '直属 ' + members(d.id).length + ' 人 / 含下级 ' + allMembers(d.id).length + ' 人'));
    summary.append(heading, meta);
    if (d.note) summary.append(el('p', 'form-note', d.note));
    const panel = el('section', 'record-panel department-panel'), tabs = el('div', 'department-tabs'); tabs.setAttribute('role', 'tablist'); tabs.setAttribute('aria-label', '部门内容');
    for (const [key, name] of [['members', '成员'], ['children', '下级部门'], ['template', '初始数据访问']]) {
      const tab = button(name, () => { departmentTab = key; renderDepartmentContent(); content.querySelector('[aria-selected="true"]')?.focus({ preventScroll: true }); }, 'department-tab');
      tab.id = 'department-tab-' + key; tab.setAttribute('role', 'tab'); tab.setAttribute('aria-selected', String(departmentTab === key)); tab.tabIndex = departmentTab === key ? 0 : -1; tab.setAttribute('aria-controls', 'department-tab-panel');
      tab.addEventListener('keydown', event => { if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return; event.preventDefault(); const keys = ['members', 'children', 'template']; departmentTab = keys[(keys.indexOf(key) + (event.key === 'ArrowRight' ? 1 : 2)) % 3]; renderDepartmentContent(); content.querySelector('[aria-selected="true"]').focus(); });
      tabs.append(tab);
    }
    if (departmentTab === 'members' && d.status === '正常') tabs.append(button('加入成员', trigger => joinMembers(d, trigger), 'admin-button tab-action', 'join-members'));
    const tabBody = el('div', 'department-tab-body'); tabBody.id = 'department-tab-panel'; tabBody.setAttribute('role', 'tabpanel'); tabBody.setAttribute('aria-labelledby', 'department-tab-' + departmentTab);
    panel.append(tabs, tabBody);
    if (departmentTab === 'members') {
      const toolbar = el('div', 'member-tools'), scope = el('div', 'scope-switch'); scope.setAttribute('role', 'group'); scope.setAttribute('aria-label', '成员查看范围');
      for (const [name, value] of [['含下级部门', true], ['仅直属', false]]) {
        const control = button(name, () => { includeChildren = value; queries.members.page = 1; renderDepartmentContent(); content.querySelector('[aria-pressed="true"]')?.focus({ preventScroll: true }); }, 'scope-button');
        control.setAttribute('aria-pressed', String(includeChildren === value)); scope.append(control);
      }
      toolbar.append(scope, filterControls('members', renderDepartmentContent));
      tabBody.append(toolbar, ...userTable(scopedMembers(d.id), 'members', renderDepartmentContent));
    } else if (departmentTab === 'children') {
      const toolbar = el('header', 'member-heading'); toolbar.append(el('h2', '', '直属下级部门'));
      if (d.status === '正常') toolbar.append(button('新增子部门', trigger => departmentForm(null, trigger, d.id), 'admin-button', 'add-child-tab'));
      const empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('layers'), el('p', '', '暂无下级部门'));
      const children = childDepartments(d.id);
      const query = queries.children; query.page = Math.max(1, Math.min(query.page, Math.ceil(children.length / query.pageSize)));
      tabBody.append(toolbar, RecordTable.render({ caption: '下级部门', minWidth: 760,
        columns: [{ key: 'name', label: '部门' }, { key: 'manager', label: '负责人', width: 112 }, { key: 'count', label: '含下级人数', width: 112 }, { key: 'status', label: '状态', width: 104 }, { key: 'actions', label: '操作', width: 160 }], items: children.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), empty,
        row: child => [button(child.name, () => selectDepartment(child.id), 'cell-title'), textCell(child.manager), textCell(allMembers(child.id).length + ' 人'), status(child.status),
          child.status === '已归档' ? el('span', 'cell-sub', '仅供回看') : button('编辑部门', trigger => departmentForm(child, trigger), 'do-action is-primary')]
      }), RecordControls.pager({ total: children.length, ...query }, page => { query.page = page; renderDepartmentContent(); }, size => { query.pageSize = size; query.page = 1; renderDepartmentContent(); }));
    } else {
      const config = el('section', 'template-content'), title = el('div', 'section-heading'), buttons = el('div', 'do-actions');
      title.append(el('h2', '', '本部门初始数据访问'));
      if (d.status !== '已归档') { buttons.append(button('配置数据访问', trigger => departmentAccess(d, trigger), 'admin-button', 'edit-access')); if (d.access.length) buttons.append(button('结束数据访问', trigger => endDepartmentAccess(d, trigger), 'admin-button is-danger', 'end-access')); }
      title.append(buttons); const configFields = el('dl', 'detail-fields');
      configFields.append(pair('来源期限', d.access.length ? d.until || '长期' : '—'), pair('影响成员', '本部门直属 ' + members(d.id).length + ' 人'), pair('配置状态', d.status !== '正常' ? d.status : d.requiresReview ? '待复核' : d.access.length ? '已配置' : '未配置'));
      const permissions = el('div', 'department-access-preview prototype-ui');
      PrototypeUI.mount(permissions, { render: () => Vue.h(DataAccessTable, { access: d.access, department: d.id }) });
      config.append(title, configFields, permissions); tabBody.append(config);
    }
    content.replaceChildren(summary, panel);
  }
  function selectDepartment(id) {
    selectedDepartment = id; departmentTab = 'members'; queries.children.page = 1; Object.assign(queries.members, { keyword: '', status: 'all', page: 1 }); renderDepartments();
  }
  function renderDepartments() {
    main.replaceChildren(pageHeading('部门管理'));
    const layout = el('div', 'department-layout'), tree = el('aside', 'department-tree'); tree.setAttribute('aria-label', '部门组织');
    const title = el('header', 'tree-title'), treeLabel = el('div'); treeLabel.append(el('h2', '', '组织架构'), el('small', '', departments.length + ' 个部门'));
    title.append(treeLabel, button('新增部门', trigger => departmentForm(null, trigger, ''), 'admin-button is-primary tree-add', 'new-department'));
    const treeHost = el('div', 'tree-content prototype-ui'); tree.append(title, treeHost);
    function treeData(parent = '') { return departments.filter(d => d.parent === parent).map(d => ({ id: d.id, label: d.name, status: d.status, count: allMembers(d.id).length, children: treeData(d.id) })); }
    PrototypeUI.mount(treeHost, {
      data: () => ({ keyword: treeKeyword, nodes: treeData(), selected: selectedDepartment }),
      watch: { keyword(value) { treeKeyword = value; this.$refs.tree.filter(value); } },
      mounted() { this.$refs.tree.filter(this.keyword); },
      methods: { filter(value, data) { return data.label.includes(value); }, select(data) { selectedDepartment = data.id; departmentTab = 'members'; Object.assign(queries.members, { keyword: '', status: 'all', page: 1 }); renderDepartmentContent(); } },
      template: `<div class="tree-filter"><el-input v-model="keyword" clearable aria-label="搜索部门" placeholder="搜索部门"></el-input></div>
        <el-tree ref="tree" :data="nodes" node-key="id" default-expand-all highlight-current :current-node-key="selected" :expand-on-click-node="false" :filter-node-method="filter" @node-click="select" aria-label="部门组织树">
          <template #default="{data}"><span class="tree-label" :title="data.label+' · 本部门及下级共 '+data.count+' 人'" :class="{'tree-disabled':data.status !== '正常'}">{{data.label}}<small>{{data.status !== '正常' ? data.status : data.count}}</small></span></template>
        </el-tree>`
    });
    layout.append(tree, el('div', 'department-content')); main.append(layout); renderDepartmentContent();
  }
  function buildNav() {
    const nav = document.getElementById('admin-nav');
    const overview = el('a', 'nav-link nav-overview', '总览'); overview.href = '#dashboard'; overview.prepend(WorkbenchIcon('layers')); nav.append(overview);
    for (const [name, icon, pages] of groups) {
      const group = el('details', 'nav-group'); group.open = name === '人员与权限' || name === '个人管理';
      const summary = el('summary'); summary.append(WorkbenchIcon(icon), el('span', '', name), WorkbenchIcon('chevron-down', 14)); group.append(summary);
      for (const [key, title] of pages) { const link = el('a', 'nav-link', title); link.href = '#' + key; group.append(link); }
      nav.append(group);
    }
  }
  function render() {
    document.querySelector('dialog[open]')?.close();
    const parts = location.hash.slice(1).split('/'); const key = parts[0] === 'admin' ? parts[1] : parts[0];
    const changed = section !== key; section = key || 'dashboard';
    pageLocation.title = titles[section] || '页面不存在';
    document.title = (titles[section] || '页面不存在') + ' · DataOcean 后台管理';
    for (const link of document.querySelectorAll('.nav-link')) {
      if (link.hash === '#' + section) { link.setAttribute('aria-current', 'page'); const group = link.closest('details'); if (group) group.open = true; }
      else link.removeAttribute('aria-current');
    }
    if (section === 'dashboard') renderDashboard();
    else if (section === 'users') renderUsers();
    else if (section === 'departments') renderDepartments();
    else if (section === 'my-access') renderMyAccess();
    else if (section === 'my-requests') renderMyRequests();
    else { const panel = el('section', 'planned-page'); panel.append(WorkbenchIcon('file-text'), el('h2', '', titles[section] ? '页面待制作' : '页面不存在'), el('p', '', titles[section] ? '本轮先完成人员管理与部门管理。' : '请从左侧菜单选择页面。')); main.replaceChildren(pageHeading(titles[section] || '页面不存在'), panel); }
    if (changed) { main.scrollTop = 0; main.querySelector('h1')?.focus({ preventScroll: true }); }
  }
  function renderDashboard() {
    main.replaceChildren(pageHeading('总览'));
    const panel = el('section', 'dashboard-placeholder'), heading = el('div', 'section-heading');
    heading.append(el('h2', '', '治理与问数运营'), el('span', 'status-tag is-disabled', '待制作'));
    const grid = el('div', 'dashboard-areas');
    for (const [title, description, key, label] of [
      ['可用能力', '数据源连接、治理确认与知识发布', 'sources', '数据源管理'],
      ['待处理工作', '待治理、待复核与待验证事项', 'governance', '治理工作台'],
      ['问数与反馈', '问数结果、用户反馈与业务正确性', 'feedback', '问题队列'],
      ['运行状态', '运行异常、耗时与调用成本', 'diagnostics', '运行诊断']
    ]) { const area = el('div', 'dashboard-area'); area.append(el('h3', '', title), el('p', '', description)); const link = el('a', '', label); link.href = '#' + key; area.append(link); grid.append(area); }
    panel.append(heading, grid); main.append(panel);
  }
  buildNav(); window.addEventListener('hashchange', render); render();
})();
