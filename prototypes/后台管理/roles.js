/* 角色清单、配置与回看共用现有列表和弹窗；所有变更仅留在当前页面。 */
(() => {
  'use strict';
  const { catalog, records, members } = RoleDemo;
  const query = { keyword: '', status: 'all', page: 1, pageSize: 10 };
  const drafts = new Map();
  let focusRole;
  const PermissionBrowser = {
    props: ['modelValue', 'readonly'], emits: ['update:modelValue'],
    data() { return { keyword: '', activeId: catalog.find(page => page.operations.some(op => this.modelValue.includes(op.id)))?.id || catalog[0].id }; },
    mounted() { this.showActive(); },
    watch: { keyword() {
      const exact = this.pages.find(page => page.name === this.keyword.trim());
      if (exact) this.activeId = exact.id;
      else if (this.pages.length && !this.pages.some(page => page.id === this.activeId)) this.activeId = this.pages[0].id;
    }, activeId() { this.showActive(); } },
    computed: {
      pages() { return catalog.filter(page => (!this.readonly || this.count(page)) && AdminQuery.matches([page.group, page.name, ...page.operations.flatMap(op => [op.name, op.purpose])], this.keyword)); },
      groups() { return [...new Set(this.pages.map(page => page.group))]; },
      active() { return this.pages.find(page => page.id === this.activeId) || this.pages[0]; },
      operations() { return this.active?.operations.filter(op => !this.readonly || this.modelValue.includes(op.id)) || []; }
    },
    methods: {
      showActive() { this.$nextTick(() => this.$el.querySelector('.permission-page.is-current')?.scrollIntoView({ block: 'nearest', inline: 'nearest' })); },
      count(page) { return page.operations.filter(op => this.modelValue.includes(op.id)).length; },
      inGroup(group) { return this.pages.filter(page => page.group === group); },
      locked(op) { return op.protectedAbility || op.id === this.active.id + '.view' && this.active.operations.some(item => item.protectedAbility && this.modelValue.includes(item.id)); },
      toggle(op, checked) {
        if (this.locked(op)) return;
        const selected = new Set(this.modelValue), view = this.active.id + '.view';
        // 操作必须有页面访问；清除页面访问时一并移除该页普通操作，受保护项始终保留。
        if (checked) { selected.add(view); selected.add(op.id); }
        else if (op.id === view) for (const item of this.active.operations) { if (!item.protectedAbility) selected.delete(item.id); }
        else selected.delete(op.id);
        this.$emit('update:modelValue', [...selected]);
      }
    },
    template: `<div class="permission-browser">
      <div class="permission-pages-column"><div class="permission-search"><el-input v-model="keyword" clearable aria-label="搜索页面或操作" placeholder="搜索页面或操作"></el-input></div>
        <nav class="permission-pages" aria-label="功能页面"><section v-for="group in groups" :key="group"><h3>{{group}}</h3>
          <button v-for="page in inGroup(group)" :key="page.id" type="button" class="permission-page" :class="{'is-current':active?.id===page.id}" :aria-pressed="active?.id===page.id" @click="activeId=page.id"><span>{{page.name}}</span><small>{{count(page)}} / {{page.operations.length}}</small></button>
        </section><p v-if="!pages.length" class="permission-no-results">没有匹配的页面或操作</p></nav>
      </div>
      <section class="permission-content" :aria-label="active ? active.name+'的功能' : '功能选择结果'">
        <template v-if="active"><div class="permission-page-heading"><h3>{{active.name}}</h3><span>{{count(active)}} / {{active.operations.length}} 项</span></div>
        <ul class="permission-operations"><li v-for="op in operations" :key="op.id">
          <strong v-if="readonly">{{op.name}}</strong><el-checkbox v-else :model-value="modelValue.includes(op.id)" :disabled="locked(op)" @update:model-value="toggle(op,$event)">{{op.name}}</el-checkbox>
          <span v-if="op.protectedAbility" class="role-protected">受保护</span><p>{{op.purpose}}</p>
        </li></ul><p v-if="active.operations.some(op=>op.protectedAbility)" class="permission-hint">受保护管理能力需单独核验。</p></template>
        <div v-else class="permission-empty"><p>{{readonly && !keyword ? '暂无已配置功能' : '没有匹配的页面或操作'}}</p></div>
      </section>
    </div>`
  };
  function render(main, ui) {
    const { el, button, textCell, status, modal, pair, pageHeading, openForm, moreActions, notify } = ui;
    const findRole = id => records.find(role => role.id === id);
    const goList = role => { focusRole = role?.id; location.hash = '#roles'; };
    const moreItems = (role, trigger, returnToDetail = false) => {
      const next = role.status === '启用' ? '停用' : '启用';
      return [{ label: '查看已分配人员', action: source => detail(role, trigger || source, 'members') },
        ...(role.status === '已归档' ? [] : [{ label: next + '角色', danger: next === '停用', separator: true,
          disabled: Boolean(RoleDemo.statusBlock(role, next)), reason: RoleDemo.statusBlock(role, next), action: source => changeState(role, next, trigger || source, returnToDetail) },
          { label: '归档角色', danger: true, disabled: Boolean(RoleDemo.statusBlock(role, '已归档')), reason: RoleDemo.statusBlock(role, '已归档'), action: source => changeState(role, '已归档', trigger || source, returnToDetail) }])];
    };
    function changeState(role, next, trigger, returnToDetail) {
      const blocked = RoleDemo.statusBlock(role, next); if (blocked) { notify(blocked); return; }
      document.querySelector('dialog[open]')?.close();
      const affected = members(role), action = next === '已归档' ? '归档' : next;
      const node = openForm({ title: action + '角色', trigger, value: { reason: '' }, note: role.name,
        fields: [{ key: 'reason', label: '操作原因', type: 'textarea', required: true, full: true }], danger: next !== '启用', saveLabel: action,
        preview: () => [{ label: '角色状态', before: role.status, after: next }, { label: '已分配人员', before: affected.length + ' 人', after: next === '已归档' ? '无当前分配人员，配置仅供回看' : '保留分配关系，' + next + '该角色功能' }],
        save: () => { const error = RoleDemo.statusBlock(role, next); if (error) return error; role.status = next; role.updated = RoleDemo.timestamp(); RoleDemo.syncNames(); },
        success: '角色已' + action });
      if (returnToDetail) node.addEventListener('close', () => detail(role, trigger), { once: true });
    }
    function memberPanel(role) {
      const host = el('div', 'role-members'), state = { keyword: '', login: '', status: 'all', page: 1, pageSize: 10 };
      const draw = () => {
        const tools = RecordControls.filters({ value: state, includeDates: false, fields: [{ key: 'keyword', label: '姓名' }, { key: 'login', label: '登录名' }],
          statuses: [{ value: 'all', label: '全部状态' }, ...['正常', '待首次登录', '停用'].map(value => ({ value, label: value }))],
          onApply: next => { Object.assign(state, next); draw(); }, onReset: () => { Object.assign(state, { keyword: '', login: '', status: 'all', page: 1 }); draw(); } });
        const rows = members(role).filter(user => (state.status === 'all' || user.status === state.status) && AdminQuery.matchesFields({ keyword: user.name, login: user.login }, state));
        state.page = Math.max(1, Math.min(state.page, Math.ceil(rows.length / state.pageSize)));
        const empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('user-round'), el('p', '', state.keyword || state.login || state.status !== 'all' ? '没有匹配的已分配人员' : '暂无已分配人员'));
        const panel = el('section', 'record-panel');
        panel.append(RecordTable.render({ caption: '已分配人员', minWidth: 580, columns: [{ key: 'name', label: '人员', width: 190 }, { key: 'department', label: '部门' }, { key: 'status', label: '账号状态', width: 110 }],
          items: rows.slice((state.page - 1) * state.pageSize, state.page * state.pageSize), empty,
          row: user => { const identity = el('div'); identity.append(textCell(user.name), el('small', 'cell-sub', user.login)); return [identity,
            textCell(PeopleDemo.departments.find(d => d.id === user.department)?.name), status(user.status)]; }
        }), RecordControls.pager({ total: rows.length, ...state }, page => { state.page = page; draw(); }, size => { state.pageSize = size; state.page = 1; draw(); }));
        host.replaceChildren(tools, panel);
      }; draw(); return host;
    }
    function detail(role, trigger, initialTab = 'permissions') {
      document.querySelector('dialog[open]')?.close();
      const node = modal('角色详情', trigger, true); node.classList.add('role-detail-dialog');
      const body = el('div', 'dialog-body'), overview = el('div', 'role-overview'), heading = el('div');
      heading.append(el('h2', '', role.name), status(role.status)); if (role.protectedRole) heading.append(el('span', 'role-protected', '受保护'));
      overview.append(heading, el('p', 'role-description', role.description || '暂无角色说明'));
      const fields = el('dl', 'detail-fields'); fields.append(pair('已分配人员', members(role).length + ' 人'), pair('更新时间', role.updated));
      const tabs = el('div', 'prototype-ui role-detail-tabs'), content = el('div', 'role-detail-content');
      const showTab = key => {
        if (key === 'members') content.replaceChildren(memberPanel(role));
        else { const host = el('div', 'prototype-ui role-detail-permissions'); content.replaceChildren(host); PrototypeUI.mount(host, { components: { PermissionBrowser }, data: () => ({ permissions: role.permissions }), template: '<permission-browser :model-value="permissions" :readonly="true"></permission-browser>' }); }
      };
      PrototypeUI.mount(tabs, { data: () => ({ active: initialTab }), methods: { changed(key) { showTab(key); } }, template: `<el-tabs v-model="active" @tab-change="changed"><el-tab-pane label="功能权限" name="permissions"></el-tab-pane><el-tab-pane :label="'已分配人员（${members(role).length}）'" name="members"></el-tab-pane></el-tabs>` });
      body.append(overview, fields, tabs, content); showTab(initialTab);
      const footer = el('footer', 'dialog-footer role-detail-actions'), actions = el('div', 'role-detail-main-actions');
      if (role.status !== '已归档') {
        footer.append(moreActions(moreItems(role, trigger, true).filter(item => item.label !== '查看已分配人员').map(item => ({ ...item, action: () => { node.close(); item.action(); } })), 'role-detail-more', '更多', node, 'top-start'));
        actions.append(button('关闭', () => node.close()), button('编辑角色', () => { node.close(); location.hash = '#roles/' + role.id + '/edit'; }, 'admin-button is-primary'));
      } else actions.append(button('关闭', () => node.close()));
      footer.append(actions); node.append(body, footer); node.showModal();
    }
    function editor(role) {
      ui.setTitle(role ? '编辑角色' : '新增角色', true);
      const wrapper = el('section', 'role-editor prototype-ui'), draftKey = role?.id || 'new';
      PrototypeUI.mount(wrapper, {
        components: { PermissionBrowser },
        data: () => ({ model: structuredClone(drafts.get(draftKey) || { name: role?.name || '', description: role?.description || '', permissions: [...(role?.permissions || [])] }), error: '', nameError: false, discarded: false }),
        computed: { changed() { return !role || this.model.name !== role.name || this.model.description !== role.description || [...this.model.permissions].sort().join('|') !== [...role.permissions].sort().join('|'); } },
        // 临时切换页面或浏览器返回时保留本页草稿；明确取消或保存才清除，不写入角色记录。
        watch: { model: { deep: true, handler() { this.error = ''; this.nameError = false; if (!this.discarded) drafts.set(draftKey, JSON.parse(JSON.stringify(this.model))); } } },
        beforeUnmount() { if (!this.discarded && this.changed) drafts.set(draftKey, JSON.parse(JSON.stringify(this.model))); },
        methods: {
          cancel() { this.discarded = true; drafts.delete(draftKey); goList(role); },
          review() {
            const data = JSON.parse(JSON.stringify(this.model)), message = RoleDemo.validate(role, data);
            // 名称错误回到实际输入；功能整体约束仍由错误区说明，避免随意定位某个权限。
            if (message) { this.error = message; this.nameError = message.includes('角色名称'); this.$nextTick(() => wrapper.querySelector(this.nameError ? 'input[aria-label="角色名称"]' : '.role-editor-error')?.focus()); return; }
            const selected = new Set(data.permissions), previous = new Set(role?.permissions || []);
            const added = catalog.flatMap(page => page.operations.filter(op => selected.has(op.id) && !previous.has(op.id)).map(op => page.name + ' / ' + op.name));
            const removed = catalog.flatMap(page => page.operations.filter(op => previous.has(op.id) && !selected.has(op.id)).map(op => page.name + ' / ' + op.name));
            const trigger = wrapper.querySelector('[data-focus="review-role"]'), node = modal('核对角色变更', trigger), body = el('div', 'dialog-body');
            const fields = el('dl', 'detail-fields'); fields.append(pair('角色名称', data.name.trim()), pair('已分配人员', role ? members(role).length + ' 人' : '0 人'));
            body.append(fields);
            if (role && role.name !== data.name.trim()) body.append(el('p', 'role-review-name', '名称：' + role.name + ' → ' + data.name.trim()));
            if (data.description.trim() || role?.description) body.append(el('p', 'role-description', data.description.trim() || '保存后无角色说明'));
            for (const [label, items] of [['新增功能', added], ['移除功能', removed]]) if (items.length) { const section = el('section', 'role-diff'); section.append(el('h3', '', label + '（' + items.length + '）')); const list = el('ul'); for (const item of items) list.append(el('li', '', item)); section.append(list); body.append(section); }
            if (role && members(role).length) { const affected = el('section', 'role-diff'); affected.append(el('h3', '', '已分配人员'), el('p', '', members(role).map(user => user.name).join('、'))); body.append(affected); }
            const footer = el('footer', 'dialog-footer'); footer.append(button('返回修改', () => node.close()), button('确认保存', () => {
              const saved = RoleDemo.save(role, data); if (typeof saved === 'string') { node.close(); this.error = saved; return; }
              this.discarded = true; drafts.delete(draftKey);
              node.close(); Object.assign(query, { keyword: '', status: 'all', page: 1 }); goList(saved); notify('角色已保存');
            }, 'admin-button is-primary'));
            node.append(body, footer); node.showModal();
          }
        },
        template: `<el-form label-position="top" @submit.prevent="review">
          <section class="role-editor-info"><el-form-item label="角色名称" required><el-input v-model="model.name" maxlength="60" aria-label="角色名称" :aria-invalid="nameError" :aria-describedby="nameError ? 'role-editor-error' : undefined" placeholder="填写角色名称"></el-input></el-form-item>
            <el-form-item label="角色说明"><el-input v-model="model.description" maxlength="200" aria-label="角色说明" placeholder="说明角色的使用职责"></el-input></el-form-item></section>
          <p v-if="error" id="role-editor-error" class="form-error role-editor-error" role="alert" tabindex="-1">{{error}}</p>
          <section class="role-permission-card"><header><h2>功能权限</h2><span><small v-if="changed" class="role-unsaved">未保存</small>已选择 {{model.permissions.length}} 项</span></header><permission-browser v-model="model.permissions"></permission-browser></section>
          <footer class="role-editor-footer"><el-button @click="cancel">取消</el-button><el-button type="primary" native-type="submit" data-focus="review-role" :disabled="!changed">核对变更</el-button></footer>
        </el-form>`
      });
      main.replaceChildren(pageHeading(role ? '编辑角色' : '新增角色'), wrapper);
      requestAnimationFrame(() => wrapper.querySelector('input[aria-label="角色名称"]')?.focus({ preventScroll: true }));
    }
    const routeParts = location.hash.slice(1).split('/'), parts = routeParts[0] === 'admin' ? routeParts.slice(1) : routeParts;
    if (parts[1]) {
      const role = findRole(parts[1]);
      if (parts[1] === 'new') { editor(null); return; }
      if (role && role.status !== '已归档' && parts[2] === 'edit') { editor(role); return; }
      const empty = el('section', 'planned-page'); empty.append(el('h2', '', role?.status === '已归档' ? '已归档角色仅供回看' : '角色不存在'), button('返回角色列表', () => goList())); main.replaceChildren(pageHeading('角色与功能权限'), empty); return;
    }
    const tools = el('section', 'people-query-card'); tools.append(RecordControls.filters({ value: query, includeDates: false,
      keywordLabel: '角色名称', keywordPlaceholder: '角色名称', statuses: [{ value: 'all', label: '全部状态' }, ...['启用', '停用', '已归档'].map(value => ({ value, label: value }))],
      onApply: next => { Object.assign(query, next); render(main, ui); }, onReset: () => { Object.assign(query, { keyword: '', status: 'all', page: 1 }); render(main, ui); }
    }), button('新增角色', () => { location.hash = '#roles/new'; }, 'admin-button is-primary', 'new-role'));
    const filtered = records.filter(role => (query.status === 'all' || query.status === role.status) && AdminQuery.matches([role.name], query.keyword))
      .sort((a, b) => b.updated.localeCompare(a.updated) || a.name.localeCompare(b.name, 'zh-CN'));
    query.page = Math.max(1, Math.min(query.page, Math.ceil(filtered.length / query.pageSize)));
    const empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('search'), el('p', '', query.keyword || query.status !== 'all' ? '没有匹配的角色' : '暂无角色'));
    if (query.keyword || query.status !== 'all') empty.append(button('清空筛选', () => { Object.assign(query, { keyword: '', status: 'all', page: 1 }); render(main, ui); }));
    const panel = el('section', 'record-panel'); panel.append(RecordTable.render({ caption: '角色与功能权限', minWidth: 980,
      columns: [{ key: 'name', label: '角色名称', width: 212 }, { key: 'description', label: '角色说明',width:360,grow:4 }, { key: 'status', label: '状态', width: 82 }, { key: 'members', label: '已分配人员', width: 104 }, { key: 'updated', label: '更新时间', width: 166 }, { key: 'actions', label: '操作', width: 160 }],
      items: filtered.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), empty,
      row: role => {
        const identity = el('div', 'role-cell-identity'), name = button(role.name, trigger => detail(role, trigger), 'cell-title', 'role-' + role.id); name.title = role.name; identity.append(name); if (role.protectedRole) identity.append(el('span', 'role-protected', '受保护'));
        const actions = el('div', 'row-actions'); if (role.status !== '已归档') actions.append(button('编辑', () => { location.hash = '#roles/' + role.id + '/edit'; }, 'do-action is-primary', 'edit-' + role.id));
        if (role.status !== '已归档') actions.append(moreActions(moreItems(role, main.querySelector('[data-focus="role-' + role.id + '"]')), 'more-' + role.id));
        else actions.append(el('span', 'cell-sub', '仅供回看'));
        return [identity, textCell(role.description), status(role.status), button(members(role).length + ' 人', trigger => detail(role, trigger, 'members'), 'do-action', 'members-' + role.id), textCell(role.updated), actions];
      }
    }), RecordControls.pager({ total: filtered.length, ...query }, page => { query.page = page; render(main, ui); }, size => { query.pageSize = size; query.page = 1; render(main, ui); }));
    main.replaceChildren(pageHeading('角色与功能权限'), tools, panel);
    if (focusRole) { const id = focusRole; focusRole = null; requestAnimationFrame(() => main.querySelector('[data-focus="role-' + id + '"]')?.focus({ preventScroll: true })); }
  }
  window.RolesPage = { render };
})();
