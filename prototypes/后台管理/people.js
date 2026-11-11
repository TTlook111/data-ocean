/* 可交互页面原型：只维护示例行、表单和显示状态，不计算或执行业务权限。 */
(() => {
  'use strict';
  const { users, departments, dataSources, outputRules, extraRequests } = PeopleDemo;
  const currentUser = users.find(user => user.id === 'u01');
  const main = document.getElementById('admin-main');
  const pageLocation = Vue.reactive({ title: '总览', parent: '' });
  PrototypeUI.mount(document.getElementById('admin-breadcrumb'), {
    setup() {
      const { h } = Vue;
      return () => h(ElementPlus.ElBreadcrumb, { separator: '/' }, { default: () => [
        h(ElementPlus.ElBreadcrumbItem, null, { default: () => h('a', { href: '#dashboard' }, '后台管理') }),
        ...(pageLocation.parent ? [h(ElementPlus.ElBreadcrumbItem, null, { default: () => h('a', { href: '#roles' }, pageLocation.parent) })] : []),
        h(ElementPlus.ElBreadcrumbItem, { 'aria-current': 'page' }, { default: () => pageLocation.title })
      ] });
    }
  });
  const groups = [
    ['数据源接入', 'database', [['sources', '数据源管理'], ['catalog', '元数据目录'], ['collections', '采集记录与结构变化'], ['business-notes', '企业业务说明']]],
    ['数据治理', 'layers', [['governance', '治理工作台'], ['governance-review', '治理审核与复核'], ['test-cases', '标准题单与验证']]],
    ['知识库与发布', 'file-text', [['knowledge', '知识文档'], ['releases', '发布管理'], ['versions', '版本与回退']]],
    ['反馈处理', 'inbox', [['feedback', '问题队列与处理']]],
    ['人员与权限', 'user-round', [['users', '人员管理'], ['departments', '部门管理'], ['roles', '角色与功能权限'], ['output-rules', '输出限制规则'], ['access-requests', '访问申请'], ['grants', '授权管理'], ['audit', '权限审计']]],
    ['系统配置', 'layers', [['models', '模型配置'], ['model-uses', '用途设置'], ['retrieval', '知识检索配置'], ['diagnostics', '运行诊断']]],
    ['个人管理', 'user-round', [['account-info', '账号信息'], ['my-requests', '我的申请'], ['my-access', '我的访问范围']]]
  ];
  const titles = { dashboard: '总览', ...Object.fromEntries(groups.flatMap(group => group[2])) };
  const queries = { users: { keyword: '', login: '', departmentId: '', status: 'all', page: 1, pageSize: 10 }, members: { keyword: '', login: '', status: 'all', page: 1, pageSize: 10 }, children: { page: 1, pageSize: 10 }, requests: { keyword: '', requestId: '', status: 'all', page: 1, pageSize: 10 }, access: { keyword: '', rule: '', origin: '', status: 'all', page: 1, pageSize: 10 } };
  let section = '', selectedDepartment = 'd01', treeKeyword = '', feedbackTimer, departmentTab = 'members', includeChildren = true, formSequence = 0;
  const deptName = id => departments.find(d => d.id === id)?.name || '—';
  // 账号区整块可操作；个人入口沿用现有页面，Escape 返回账号按钮。
  PrototypeUI.mount(document.getElementById('admin-account'), {
    setup() {
      const { h, ref, nextTick } = Vue, dropdown = ref(), trigger = ref();
      const close = event => {
        if (event.key !== 'Escape') return;
        event.preventDefault(); event.stopPropagation(); dropdown.value?.handleClose();
        nextTick(() => trigger.value?.focus({ preventScroll: true }));
      };
      return () => h(ElementPlus.ElDropdown, {
        ref: dropdown, trigger: 'click', placement: 'bottom-end', popperClass: 'prototype-ui-popper people-dropdown',
        onKeydownCapture: close, onCommand: key => { location.hash = key; }
      }, {
        default: () => h('button', { ref: trigger, type: 'button', class: 'admin-identity', 'aria-label': currentUser.name + '，' + deptName(currentUser.department) + '，用户菜单' }, [
          h('span', { class: 'identity-avatar', 'aria-hidden': 'true' }, currentUser.name.slice(0,1)),
          h('span', null, [currentUser.name, h('small', null, deptName(currentUser.department))]),
          h('span', { class: 'identity-chevron', 'aria-hidden': 'true', ref: node => { if (node && !node.firstChild) node.append(WorkbenchIcon('chevron-down',14)); } })
        ]),
        dropdown: () => h(ElementPlus.ElDropdownMenu, { onKeydownCapture: close }, { default: () => [
          ['account-info', '账号信息'], ['my-requests', '我的申请'], ['my-access', '我的访问范围']
        ].map(([command,label]) => h(ElementPlus.ElDropdownItem, { command }, { default: () => label })) })
      });
    }
  });
  const members = id => users.filter(u => u.status !== '已归档' && (u.department === id || u.extra.includes(id)));
  const childDepartments = id => departments.filter(d => d.parent === id);
  const branch = id => [id, ...childDepartments(id).flatMap(d => branch(d.id))];
  // 只做组织列表的范围筛选与去重，不从上下级关系推导业务权限。
  const scopedMembers = id => { const ids = includeChildren ? branch(id) : [id]; return users.filter(u => u.status !== '已归档' && (ids.includes(u.department) || u.extra.some(d => ids.includes(d)))); };
  const allMembers = id => { const ids = branch(id); return users.filter(u => u.status !== '已归档' && (ids.includes(u.department) || u.extra.some(d => ids.includes(d)))); };
  const options = values => values.map(value => ({ value, label: value }));
  // 规则全文按每笔来源展示，不解释中文规则，也不合并多来源权限。
  const accessRows = access => access.map(item => ({
    source: dataSources.find(source => source.id === item.source)?.name || '数据源不可用',
    rules: item.rules, ruleNames: item.rules.map(rule => rule.name).join('、'),
    limits: item.rules.map(rule => rule.name + '：' + rule.content).join('\n')
  }));
  const accessText = access => accessRows(access).map(row => row.source + '\n' + row.limits).join('\n') || '未配置';
  // 阅读摘要只占一行，配置核定保留全文；补充信息不增加展开操作。
  const OutputRuleList = {
    props: { rules: Array, preview: { type: Boolean, default: true } },
    template: `<dl class="output-rule-list"><div v-for="rule in rules" :key="rule.displayKey || rule.id"><dt :title="rule.name">{{rule.name}}</dt><dd><span class="output-rule-text" :class="{'rule-content-preview':preview}" :title="preview ? rule.content : null">{{rule.content}}</span></dd></div></dl>`
  };
  const DataAccessTable = {
    props: ['access'],
    computed: { rows() { return accessRows(this.access); } },
    template: `<el-table :data="rows" class="data-access-table" aria-label="数据源与输出限制规则" empty-text="暂无数据访问配置">
      <el-table-column prop="source" label="数据源" width="160"></el-table-column>
      <el-table-column label="输出限制规则" min-width="260"><template #default="{row}"><span v-if="row.rules.length" class="cell-text" :title="row.ruleNames">{{row.ruleNames}}</span><span v-else class="cell-sub">待核定</span></template></el-table-column>
    </el-table>`
  };
  const DataAccessEditor = {
    components: { OutputRuleList }, props: ['modelValue', 'popupTarget', 'singleSource', 'sourceReadonly'], emits: ['update:modelValue'],
    data: () => ({ dataSources, outputRules, ruleKeywords: {} }),
    methods: {
      update(index, patch) { this.$emit('update:modelValue', this.modelValue.map((item, i) => i === index ? { ...item, ...patch } : item)); },
      remove(index) { this.$emit('update:modelValue', this.modelValue.filter((_, i) => i !== index)); },
      add() { this.$emit('update:modelValue', [...this.modelValue, { source: '', rules: [] }]); },
      selectRules(index, ids) { this.update(index, { rules: structuredClone(ids.map(id => outputRules.find(rule => rule.id === id))) }); },
      matchingRules(index) { return outputRules.filter(rule => AdminQuery.matches([rule.name, rule.content], this.ruleKeywords[index] || '')); }
    },
    template: `<div class="data-access-editor">
      <div v-for="(item,index) in modelValue" :key="index" class="data-access-item"><div class="data-access-row">
        <div v-if="sourceReadonly" class="data-access-source-readonly" role="group" aria-label="已核定数据源">{{dataSources.find(source=>source.id===item.source)?.name || '数据源不可用'}}</div>
        <el-select v-else :model-value="item.source" @update:model-value="update(index,{source:$event})" :aria-label="'数据源 '+(index+1)" :append-to="popupTarget" :validate-event="false" filterable placeholder="选择数据源">
          <el-option v-for="source in dataSources" :key="source.id" :label="source.name" :value="source.id" :disabled="modelValue.some((value,i)=>i!==index && value.source===source.id)"></el-option>
        </el-select>
        <el-select :model-value="item.rules.map(rule=>rule.id)" @update:model-value="selectRules(index,$event)" :aria-label="'输出限制规则 '+(index+1)" :validate-event="false" multiple collapse-tags collapse-tags-tooltip :max-collapse-tags="2" filterable :filter-method="keyword=>ruleKeywords[index]=keyword" :reserve-keyword="false" :disabled="!item.source" :append-to="popupTarget" popper-class="prototype-ui-popper output-rule-options" placeholder="选择输出限制规则" no-match-text="没有匹配的规则">
          <el-option v-for="rule in matchingRules(index)" :key="rule.id" :label="rule.name" :value="rule.id"><span class="rule-option-title">{{rule.name}}</span><span class="rule-option-description">{{rule.content}}</span></el-option>
        </el-select>
        <el-button v-if="!singleSource" link type="danger" @click="remove(index)" :aria-label="'移除数据源 '+(index+1)">移除</el-button>
      </div><output-rule-list v-if="item.rules.length" :rules="item.rules" :preview="false"></output-rule-list></div>
      <el-button v-if="!singleSource && modelValue.length < dataSources.length" @click="add">添加数据源</el-button>
    </div>`
  };
  const validateAccess = (access, required = false) => {
    if (required && !access.length) return '请至少选择一个数据源和输出限制规则。';
    if (access.some(item => !item.source || !item.rules.length)) return '请为每项选择数据源和输出限制规则，或移除未填写的项。';
    if (new Set(access.map(item => item.source)).size !== access.length) return '同一数据源不能重复选择。';
  };
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
  function status(value) { return el('span', 'status-tag' + (['停用', '已停用', '已归档', '来源停用', '来源已结束', '已驳回', '已撤回', '已撤销'].includes(value) ? ' is-disabled' : ['待首次登录', '待复核', '待审批', '待分流', '待核定数据源', '缺审批责任', '审批责任需复核', '待确认', '需补充', '未配置', '需复核', '已批准', '部分已生效'].includes(value) ? ' is-pending' : ''), value); }
  // 列表默认向下展开，固定底栏可指定向上展开，定位和键盘行为仍由组件处理。
  function moreActions(items, key, label, popupTarget = document.body, placement = 'bottom-end') {
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
          ref: dropdown, trigger: 'click', placement, appendTo: popupTarget, onKeydownCapture: dismiss,
          popperClass: 'prototype-ui-popper people-dropdown',
          onCommand: index => { const item = items[Number(index)]; if (!item.disabled) item.action(wrap.querySelector('button')); }
        }, {
          default: () => h(ElementPlus.ElButton, {
            link: !label, nativeType: 'button', class: label ? 'department-action-trigger' : 'more-trigger',
            'data-focus': key, 'aria-label': label || '更多操作'
          }, { default: () => [h('span', null, label || '更多'), h('span', { class: 'more-chevron', 'aria-hidden': 'true' })] }),
          dropdown: () => h(ElementPlus.ElDropdownMenu, { onKeydownCapture: dismiss }, { default: () => items.map((item, index) =>
            h(ElementPlus.ElDropdownItem, { command: index, divided: Boolean(item.separator), disabled: Boolean(item.disabled), title: item.reason || undefined, class: item.danger ? 'is-danger' : '' }, { default: () => item.label })) })
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
  function openForm({ title, trigger, fields, value, note = '', saveLabel = '保存', save, preview, validate, hasChanges = () => true, danger = false, success, previewTitle = '核对变更与影响', previewLabel = '预览变更', previewContentLabel, summary = false, onSaved }) {
    const node = modal(title, trigger), host = el('div'); node.append(host);
    const errorPrefix = 'admin-form-' + (++formSequence) + '-';
    if (fields.some(field => field.type === 'data-access')) node.classList.add('data-access-dialog');
    PrototypeUI.mount(host, {
      components: { DataAccessEditor, DataAccessTable, AssignmentPreview: SourceApprovalsPage.AssignmentPreview },
      data: () => ({ model: structuredClone(value), fields, note, error: '', errorPrefix, choiceKeywords: {}, saveLabel, popupTarget: node, reviewing: false, reviewRows: [], danger, previewTitle, previewLabel, previewContentLabel, summary,
        rules: Object.fromEntries(fields.map(f => [f.key, [
          ...(f.type === 'checkbox' ? [{ required: true, validator: (_, v, done) => done(v ? undefined : new Error('请先完成复核确认')), trigger: 'change' }] : f.required ? [{ required: true, message: f.options ? (f.label.startsWith('选择') ? '请' : '请选择') + f.label : '请填写' + f.label, trigger: f.options ? 'change' : 'blur' }] : []),
          ...(f.key === 'email' ? [{ type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }] : []),
          ...(f.required && !f.options && (!f.type || f.type === 'textarea' || f.type === 'text') ? [{ pattern: /\S/, message: '请填写' + f.label, trigger: 'blur' }] : []),
          ...(f.type === 'data-access' ? [{ validator: (_, v, done) => { const error = f.sourceReadonly && !v[0]?.rules.length ? '请选择至少一条输出限制规则。' : f.singleSource && (v.length !== 1 || !v[0]?.source || !v[0]?.rules.length) ? '请核定数据源并选择至少一条输出限制规则。' : validateAccess(v, Boolean(f.required)); done(error ? new Error(error) : undefined); }, trigger: 'change' }] : [])
        ]])) }),
      watch: { model: { deep: true, handler() { this.error = ''; } } },
      computed: { unchanged() { return !hasChanges(this.model); } },
      methods: {
        cancel() { node.close(); },
        validated(prop, valid) {
          // 将组件的就近报错关联到实际输入，清除时只移除本表单添加的描述。
          this.$nextTick(() => {
            const item = this.$refs.form.getField(prop), errorId = errorPrefix + prop;
            for (const input of item?.$el.querySelectorAll('input:not([disabled]),textarea:not([disabled]),button[role="combobox"]:not([disabled])') || []) {
              const descriptions = (input.getAttribute('aria-describedby') || '').split(' ').filter(id => id && id !== errorId);
              if (!valid) descriptions.push(errorId);
              input.setAttribute('aria-invalid', String(!valid));
              if (descriptions.length) input.setAttribute('aria-describedby', descriptions.join(' '));
              else input.removeAttribute('aria-describedby');
            }
          });
        },
        clearAccessError(key) { this.$nextTick(() => { this.$refs.form.clearValidate(key); this.validated(key, true); }); },
        matchingChoices(field) { return field.options.filter(option => AdminQuery.matches([option.label], this.choiceKeywords[field.key] || '')); },
        selectedChoices(field) { return this.model[field.key].map(value => field.options.find(option => option.value === value)?.label || value).join('、'); },
        back() { this.reviewing = false; this.$nextTick(() => node.querySelector('.form-grid input,.form-grid textarea')?.focus()); },
        commit() {
          if (this.unchanged) return;
          // 原型表单只有普通数据，序列化同时展开多选组件带来的嵌套响应式引用。
          const data = JSON.parse(JSON.stringify(this.model)), invalid = validate?.(data);
          if (invalid) { this.error = invalid; this.reviewing = false; this.$nextTick(() => node.querySelector('.form-error')?.focus()); return; }
          const message = save(data);
          if (message) { this.error = message; this.reviewing = false; this.$nextTick(() => node.querySelector('.form-error')?.focus()); return; }
          node.close(); render(); notify(success || title + '成功');
          onSaved?.(data);
        },
        submit() { if (this.unchanged) return; this.$refs.form.validate(valid => {
          if (!valid) { this.reviewing = false; this.$nextTick(() => node.querySelector('.el-form-item.is-error input:not([disabled]),.el-form-item.is-error textarea,.el-form-item.is-error button')?.focus()); return; }
          if (this.reviewing) { this.commit(); return; }
          const data = JSON.parse(JSON.stringify(this.model)), message = validate?.(data);
          if (message) { this.error = message; this.$nextTick(() => node.querySelector('.form-error')?.focus()); return; }
          if (preview) { this.reviewRows = preview(data); this.reviewing = true; this.$nextTick(() => node.querySelector('.review-heading')?.focus()); }
          else this.commit();
        }); }
      },
      template: `<el-form ref="form" :model="model" :rules="rules" label-position="top" scroll-to-error @validate="validated" @submit.prevent="submit">
        <div class="dialog-body"><p v-if="note" class="form-note">{{note}}</p><p v-if="error" class="form-error" role="alert" tabindex="-1">{{error}}</p>
        <div v-show="!reviewing" class="form-grid"><el-form-item v-for="f in fields" :key="f.key" :label="f.label" :prop="f.key" :class="{'full-width': f.full}">
          <div v-if="f.readonly" class="readonly-value">{{model[f.key]}}</div>
          <assignment-preview v-else-if="f.type === 'approval-assignment'" :source-id="model.source" :applicant-id="f.applicantId"></assignment-preview>
          <data-access-table v-else-if="f.type === 'access-summary'" :access="model[f.key]"></data-access-table>
          <div v-else-if="f.type === 'checkbox-group'" class="role-choice-field"><el-input v-if="f.options.length>8" v-model="choiceKeywords[f.key]" clearable aria-label="搜索可分配角色" placeholder="搜索角色" class="role-choice-search"></el-input>
            <p v-if="choiceKeywords[f.key] && model[f.key].length" class="role-choice-selected" aria-label="已选角色">已选：{{selectedChoices(f)}}</p>
            <div class="role-choice-scroll"><el-checkbox-group v-model="model[f.key]" :aria-label="f.label" class="form-choice-group">
              <el-checkbox v-for="o in matchingChoices(f)" :key="o.value" :value="o.value" :disabled="o.disabled">{{o.label}}</el-checkbox>
            </el-checkbox-group><p v-if="!matchingChoices(f).length" class="form-note">没有匹配的角色</p></div></div>
          <el-select v-else-if="f.options" v-model="model[f.key]" :aria-label="f.label" :multiple="f.multiple" :disabled="f.disabled" :clearable="!f.required" :append-to="popupTarget" filterable :placeholder="f.placeholder || (f.label.startsWith('选择') ? '请'+f.label : '请选择'+f.label)">
            <el-option v-for="o in f.options" :key="o.value" :value="o.value" :label="o.label" :disabled="o.disabled"></el-option>
          </el-select>
          <data-access-editor v-else-if="f.type === 'data-access'" v-model="model[f.key]" @update:model-value="clearAccessError(f.key)" :popup-target="popupTarget" :single-source="f.singleSource" :source-readonly="f.sourceReadonly"></data-access-editor>
          <el-checkbox v-else-if="f.type === 'checkbox'" v-model="model[f.key]">{{f.checkLabel}}</el-checkbox>
          <el-input v-else v-model="model[f.key]" :aria-label="f.label" :type="f.type || 'text'" :show-password="f.type === 'password'" :autocomplete="f.type === 'password' ? 'new-password' : 'off'" :disabled="f.disabled" :maxlength="f.type === 'textarea' ? 300 : 80" :rows="3" :show-word-limit="f.type === 'textarea'" :placeholder="f.placeholder || ''"></el-input>
          <span v-if="f.help" class="field-help">{{f.help}}</span>
          <template #error="{error:message}"><div class="el-form-item__error" :id="errorPrefix+f.key" role="alert">{{message}}</div></template>
        </el-form-item></div>
        <section v-if="reviewing" class="change-preview" :class="{'is-summary':summary}"><h3 class="review-heading" tabindex="-1">{{previewTitle}}</h3>
          <table><thead><tr><th>内容</th><th v-if="!summary">当前</th><th>{{previewContentLabel || (summary ? '申请内容' : '变更后')}}</th></tr></thead><tbody><tr v-for="row in reviewRows" :key="row.label"><th>{{row.label}}</th><td v-if="!summary">{{row.before}}</td><td>{{row.after}}</td></tr></tbody></table>
        </section></div>
        <footer class="dialog-footer"><el-button @click="cancel">取消</el-button><el-button v-if="reviewing" @click="back">返回修改</el-button><el-button :disabled="unchanged" :type="danger ? 'danger' : 'primary'" native-type="submit">{{reviewing ? (summary ? saveLabel : '确认'+saveLabel) : ${Boolean(preview)} ? previewLabel : saveLabel}}</el-button></footer>
      </el-form>`
    });
    node.showModal();
    return node;
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
    const value = user || { name: '', login: '', department: initialDepartment, extra: [], roles: RoleDemo.initialRoles(), phone: '', email: '', note: '' };
    openForm({ title: user ? '编辑人员' : '新增人员', trigger, value: { ...value, initialPassword: '' },
      fields: [
        { key: 'name', label: '姓名', required: true }, { key: 'login', label: '登录名', required: true, disabled: Boolean(user) },
        ...(!user ? [{ key: 'initialPassword', label: '初始密码', type: 'password', required: true, full: true, help: '首次登录需修改初始密码。' }] : []),
        { key: 'department', label: '主部门', options: departmentOptions(value.department), required: true },
        { key: 'extra', label: '兼任部门', options: departmentOptions(), multiple: true },
        ...(!user ? [{ key: 'roles', label: '初始个人角色', options: RoleDemo.options(), multiple: true, full: true }] : []),
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
  function assignRoles(user, trigger, returnToDetail = false) {
    // 角色只按名称选择，较多时支持搜索；保存前仅修改副本，不改变数据访问来源。
    const node = openForm({ title: '分配角色 · ' + user.name, trigger, value: { roles: [...user.roles] },
      fields: [{ key: 'roles', label: '个人角色', type: 'checkbox-group', options: RoleDemo.options(user.roles), full: true }],
      success: '角色已保存',
      save: data => { user.roles = data.roles; user.updated = timestamp(); } });
    node.classList.add('role-assignment-dialog');
    // 从详情进入时，保存或取消都回到原人员，避免丢失查看上下文。
    if (returnToDetail) node.addEventListener('close', () => userDetail(user, trigger), { once: true });
    (node.querySelector('input:checked') || node.querySelector('input'))?.focus({ preventScroll: true });
  }
  function toggleUser(user, trigger) {
    const disable = user.status !== '停用';
    if (!disable) {
      openForm({ title: '复核并恢复人员', trigger,
        note: '核对当前归属、角色与数据访问来源。失效来源和旧任务不随账号恢复。',
        value: { department: departments.find(d => d.id === user.department)?.status === '正常' ? user.department : '', roles: [...user.roles], reviewed: false, reason: '' },
        fields: [{ key: 'department', label: '当前主部门', options: departmentOptions(), required: true, full: true },
          { key: 'roles', label: '复核个人角色', options: RoleDemo.options(user.roles), multiple: true, full: true },
          { key: 'reviewed', label: '恢复前复核', type: 'checkbox', checkLabel: '已核对部门、角色和数据访问来源', full: true },
          { key: 'reason', label: '恢复原因', type: 'textarea', required: true, full: true }],
        preview: data => [change('账号状态', '停用', '正常'), change('主部门', deptName(user.department), deptName(data.department)),
          change('个人角色', user.roles, data.roles), change('旧任务与失效来源', '已结束', '不自动恢复')],
        saveLabel: '恢复', save: data => { Object.assign(user, { department: data.department, roles: data.roles, status: '正常', updated: timestamp() }); user.extra = user.extra.filter(id => id !== data.department); } });
      return;
    }
    confirmChange({ title: disable ? '停用人员' : '恢复人员', trigger,
      description: '停用“' + user.name + '”后，该人员将无法继续使用系统。', danger: true,
      preview: () => [change('账号状态', user.status, '停用'), change('继续访问', '按现有配置', '停止')],
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
    openForm({ title: '重置登录密码 · ' + user.name, trigger, value: { initialPassword: '', reason: '' },
      note: '下次登录须修改初始密码。',
      fields: [{ key: 'initialPassword', label: '新初始密码', type: 'password', required: true, full: true }, { key: 'reason', label: '重置原因', type: 'textarea', required: true, full: true }],
      validate: data => /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/.test(data.initialPassword) ? undefined : '初始密码需为 8–32 位，包含字母和数字。',
      preview: () => [change('登录名', user.login, user.login), change('密码', '不回显', '设为新初始密码'), change('下次登录', '原设置', '必须修改密码')],
      saveLabel: '重置', save: () => { user.mustChangePassword = true; user.updated = timestamp(); } });
  }
  function transferUser(user, trigger) {
    openForm({ title: '调整人员归属 · ' + user.name, trigger, value: { department: user.department, extra: [...user.extra], reason: '' },
      fields: [{ key: 'department', label: '主部门', options: departmentOptions(user.department), required: true, full: true },
        { key: 'extra', label: '兼任部门', options: departmentOptions(), multiple: true, full: true },
        { key: 'reason', label: '调整原因', type: 'textarea', required: true, full: true }],
      preview: data => [change('主部门', deptName(user.department), deptName(data.department)), change('兼任部门', user.extra.map(deptName), data.extra.filter(id => id !== data.department).map(deptName)),
        change('部门数据访问', '原所属部门配置', '结束移出来源，关联新部门初始数据访问')],
      save: data => { user.department = data.department; user.extra = data.extra.filter(id => id !== data.department); user.updated = timestamp(); } });
  }
  function archiveUser(user, trigger) {
    if (user.status !== '停用') { notify('请先停用人员，再核对归档'); return; }
    confirmChange({ title: '归档人员', trigger, danger: true, description: '归档“' + user.name + '”后，仅保留资料和历史回看，不再提供日常维护操作，登录名不复用。',
      preview: () => [change('账号状态', '停用', '已归档'), change('登录名', user.login, '保留，不复用')],
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
      pair('兼任部门', user.extra.map(deptName).join('、')), pair('手机号', user.phone), pair('邮箱', user.email), pair('更新时间', user.updated));
    // 人员详情只回看已分配的角色名称，不展开功能明细和有效时长。
    const roleSection = el('section', 'detail-section');
    roleSection.append(el('h2', '', '个人角色'), el('p', '', user.roles.join('、') || '未分配角色'));
    const sources = el('section', 'detail-section'); sources.append(el('h2', '', '数据访问来源'), dataAccessSources(user));
    body.append(overview, fields, roleSection, sources);
    if (user.note) { const note = el('section', 'detail-section'); note.append(el('h2', '', '备注'), el('p', '', user.note)); body.append(note); }
    // 查看型抽屉集中管理动作：低频菜单靠左，常用动作靠右，正文保持纯展示。
    const actions = el('footer', 'dialog-footer person-detail-actions'), mainActions = el('div', 'person-detail-main-actions');
    if (user.status !== '已归档') {
      actions.append(moreActions(userActions(user).map(item => ({ ...item, action: () => { node.close(); item.action(trigger); } })), 'detail-more', '更多', node, 'top-start'));
      mainActions.append(button('分配角色', () => { node.close(); assignRoles(user, trigger, true); }),
        button('编辑人员', () => { node.close(); userForm(user, trigger); }, 'admin-button is-primary'));
    } else mainActions.append(button('关闭', () => node.close()));
    actions.append(mainActions);
    node.append(body, actions); node.showModal();
  }
  // 人员详情与本人范围列表共用来源集合；待审批或驳回的申请不会成为访问来源。
  function accessOrigins(user) {
    const sources = [...new Set([user.department, ...user.extra])].map(id => departments.find(d => d.id === id))
      .filter(d => d?.access.length).map(d => ({ id: d.id, type: 'department', label: '部门初始访问 · ' + d.name, access: d.access, submitted: d.created,
        state: d.status !== '正常' ? '来源' + d.status : d.requiresReview ? '待复核' : '' }));
    for (const request of extraRequests.filter(r => r.user === user.id && AccessRequestDemo.effectiveAccess(r).length)) sources.push({ id: request.id, type: 'request', label: '申请授权 · ' + request.id, access: AccessRequestDemo.effectiveAccess(request), submitted: request.submitted, request });
    return sources;
  }
  function dataAccessSources(user) {
    // 来源按数据源分页，规则正文作为单行补充摘要。
    const host = el('div', 'detail-access-list'), query = { page: 1, pageSize: 10 };
    const records = accessRecords(user).sort((a, b) => b.origin.submitted.localeCompare(a.origin.submitted) || b.id.localeCompare(a.id));
    const draw = () => {
      query.page = Math.max(1, Math.min(query.page, Math.ceil(records.length / query.pageSize)));
      const list = el('div', 'prototype-ui');
      if (records.length) PrototypeUI.mount(list, {
        components: { OutputRuleList },
        data: () => ({ records: records.slice((query.page - 1) * query.pageSize, query.page * query.pageSize) }),
        template: `<el-collapse accordion>
          <el-collapse-item v-for="row in records" :key="row.id" :name="row.id">
            <template #title><span class="detail-access-title"><span class="detail-access-heading"><span class="detail-access-name"><span class="cell-text" :title="row.source">{{row.source}}</span><span v-if="row.origin.state" class="status-tag" :class="row.origin.state==='待复核' ? 'is-pending' : 'is-disabled'">{{row.origin.state}}</span></span><span class="cell-sub" :title="row.origin.label">{{row.origin.label}}</span></span></span></template>
            <div class="detail-access-body"><output-rule-list :rules="row.rules"></output-rule-list></div>
          </el-collapse-item>
        </el-collapse>`
      });
      else list.append(el('p', 'detail-access-empty', '暂无数据访问来源'));
      host.replaceChildren(list, RecordControls.pager({ total: records.length, ...query }, page => { query.page = page; draw(); }, size => { query.pageSize = size; query.page = 1; draw(); }));
    };
    draw();
    return host;
  }
  function filterControls(kind, target) {
    return RecordControls.filters({ value: queries[kind], includeDates: false,
      fields: [{ key: 'keyword', label: '姓名' }, { key: 'login', label: '登录名' }, ...(kind === 'users' ? [{ key: 'departmentId', label: '所属部门', options: departments.map(department => ({ value: department.id, label: department.name })) }] : [])],
      statuses: [{ value: 'all', label: '全部状态' }, ...['正常', '待首次登录', '停用', ...(kind === 'users' ? ['已归档'] : [])].map(value => ({ value, label: value }))],
      onApply: next => { Object.assign(queries[kind], next); target(); main.querySelector('input[name="keyword"]')?.focus({ preventScroll: true }); },
      onReset: () => { Object.assign(queries[kind], { keyword: '', login: '', departmentId: '', status: 'all', page: 1 }); target(); main.querySelector('input[name="keyword"]')?.focus({ preventScroll: true }); }
    });
  }
  function applyExtraAccess(trigger) {
    let submittedRequest;
    const query = new URLSearchParams(location.search), requestedSource = query.get('requestSource');
    openForm({ title: '申请额外数据访问', trigger, value: { sourceIds: dataSources.some(source => source.id === requestedSource) ? [requestedSource] : [], access: [], requestedScope: query.get('requestData') || '', reason: '' },
      fields: [{ key: 'requestedScope', label: '需要的数据', type: 'textarea', required: true, full: true, placeholder: '例如：近两周的销售数量与退货情况' },
        { key: 'reason', label: '业务用途', type: 'textarea', required: true, full: true, placeholder: '例如：核对经营周报' },
        { key: 'sourceIds', label: '已知数据源（选填）', options: dataSources.map(source => ({ value: source.id, label: source.name })), placeholder: '选择已知数据源', multiple: true, full: true }],
      previewTitle: '核对申请内容', previewLabel: '核对申请', summary: true,
      preview: data => [change('需要的数据', '', data.requestedScope.trim()), change('业务用途', '', data.reason.trim()),
        change('已知数据源', '', data.sourceIds.map(id => dataSources.find(source => source.id === id).name).join('、') || '交由负责人核定'),
        change('提交后的状态', '', data.sourceIds.length && data.sourceIds.every(id=>SourceApprovalDemo.resolve(id,currentUser.id).reviewer) ? '待审批' : '待分流')],
      saveLabel: '提交申请', success: '申请已提交',
      // 仅记录申请并展示待审批，不把申请内容转换为实际授权。
      save: data => { submittedRequest = { ...data, reason: data.reason.trim(), requestedScope: data.requestedScope.trim(), id: 'SQ-' + Date.now(), user: currentUser.id, department: currentUser.department, status: '待审批', submitted: timestamp() }; AccessRequestDemo.ensure(submittedRequest); extraRequests.unshift(submittedRequest); },
      onSaved: () => {
        // 新申请直接回看其待审批详情，不让它混入“当前可访问”的清单。
        const reveal = () => requestDetail(submittedRequest, main.querySelector('[data-focus="apply-data-access"]'));
        Object.assign(queries.requests, { keyword: '', requestId: '', status: 'all', page: 1 });
        if (section === 'my-requests') { renderMyRequests(); reveal(); }
        else { window.addEventListener('hashchange', reveal, { once: true }); location.hash = 'my-requests'; }
      } });
  }
  function requestDetail(request, trigger, adminResult = false) {
    AccessRequestDemo.ensure(request);
    const node = modal(adminResult && section !== 'my-access' ? '申请详情' : '我的申请详情', trigger, true), body = el('div', 'dialog-body'), fields = el('dl', 'detail-fields');
    node.classList.add('access-detail-dialog');
    const state = el('div', 'request-state'); state.append(el('span','cell-sub','申请状态'),status(request.status), el('span', 'request-id', request.id));
    const applicant = users.find(user => user.id === request.user); fields.append(pair('申请人', applicant.name + '（' + applicant.login + '）'));
    fields.append(pair('提交时间', request.submitted));
    body.append(state, fields, el('h3', '', '业务用途'), el('p', '', request.reason));
    if (request.requestedScope) body.append(el('h3', '', '需要的数据'), el('p', '', request.requestedScope));
    // 子项已包含数据源与办理结果，不再另放一张相同范围的规则明细表。
    if (!request.approvalItems?.length) {
      const sourceIds = request.sourceIds || request.access.map(item => item.source);
      body.append(el('h3', '', '已知数据源'), el('p', '', sourceIds.map(id => dataSources.find(source => source.id === id).name).join('、') || '交由负责人核定'));
    }
    if (request.approvalItems?.length) {
      const reviewed = el('div', 'request-reviewed-items');
      for (const item of request.approvalItems) {
        const section = el('section'); section.append(el('h3', '', dataSources.find(source => source.id === item.source)?.name || '待核定数据源'), status(AccessRequestDemo.open(item)?item.status:AccessRequestDemo.decisionOf(item)));
        if(AccessRequestDemo.decisionOf(item)==='已批准'&&item.status!=='已批准')section.append(el('p','', '当前授权：'+item.status));
        const review = AccessRequestDemo.reviewInfo(item, request);
        if(review){section.append(el('p','',review.label+'：'+review.identity));if(review.timeLabel)section.append(el('p','cell-sub',review.timeLabel+'：'+review.time));}
        if (AccessRequestDemo.open(item) && AccessRequestDemo.inProgress(request) && item.routeReason) section.append(el('p', 'form-note', item.routeReason));
        if (item.reason) section.append(el('p', '', item.reason));
        if (AccessRequestDemo.decisionOf(item) === '已批准' && item.rules.length) { const names = el('p', 'access-rule-names', '输出限制规则：' + item.rules.map(rule => rule.name).join('、')); names.title = names.textContent; section.append(names); }
        if (item.revokedAt) section.append(el('p', 'cell-sub', item.revokedAt + ' · ' + item.revokedBy), el('p', '', item.revokedReason));
        reviewed.append(section);
      }
      body.append(el('h3', '', '办理情况'), reviewed);
      if (request.mode === 'joint' && request.approvalItems.some(item => item.status === '已批准')) body.append(el('p', 'form-note', AccessRequestDemo.inProgress(request) ? '等待其他必要子项审批。' : '本组合未通过，当前未生效。'));
    }
    if (adminResult && request.history.length) {
      const history = el('ol', 'approval-history');
      for (const entry of request.history) { const item = el('li'); item.append(el('small', '', entry.time + ' · ' + entry.actor), el('strong', '', entry.action), el('p', '', entry.content)); history.append(item); }
      body.append(el('h3', '', '处理记录'), history);
    }
    if (request.result) body.append(el('h3', '', '处理说明'), el('p', '', request.result));
    const footer = el('footer', 'dialog-footer'); footer.append(button('关闭', () => node.close()));
    if(adminResult&&section==='access-requests'&&SourceApprovalDemo.hasAbility(currentUser,'grants.view')&&GrantDemo.records(currentUser,{origin:request.id}).some(record=>record.request?.id===request.id))footer.append(button('查看授权',()=>{node.close();GrantsPage.openOrigin(request.id);}));
    if (!adminResult && AccessRequestDemo.inProgress(request)) footer.append(button(request.access.length ? '撤回未处理子项' : '撤回申请', () => { const revision = request.revision; node.close(); confirmChange({ title: request.access.length ? '撤回未处理子项' : '撤回申请', trigger, danger: true,
      description: '撤回“' + request.id + '”的未处理子项。', preview: () => [change('申请状态', request.status, request.access.length ? '部分已生效' : '已撤回')], save: () => AccessRequestDemo.withdraw(request, currentUser, revision) }); }, 'admin-button is-danger'));
    node.append(body, footer); node.showModal(); return node;
  }
  // 管理回看保留逐笔来源；本人访问范围另按数据源合并当前有效限制。
  function accessRecords(user) {
    return accessOrigins(user).flatMap(origin => origin.access.map(item => ({
      ...accessRows([item])[0], id: origin.id + '-' + item.source, access: [item], origin
    })));
  }
  function groupedAccessDetail(sourceId, trigger, activeOrigin = '') {
    const group = GrantDemo.accessGroups(currentUser).find(group=>group.id===sourceId);if(!group)return;
    const node=modal('访问范围详情',trigger,true),body=el('div','dialog-body'),heading=el('div','grant-detail-heading');node.classList.add('access-detail-dialog');
    heading.append(el('h2','',group.source),status(group.state));body.append(heading);
    const identity=el('dl','detail-fields');identity.append(pair('被授权人',GrantDemo.identity(currentUser.id)));body.append(identity);
    if(group.activeCount){const rules=el('div','prototype-ui');PrototypeUI.mount(rules,{render:()=>Vue.h(OutputRuleList,{rules:group.rules})});body.append(el('h3','','当前输出限制'),rules);}
    body.append(el('h3','','授权来源（'+group.activeCount+' 笔生效）'));
    const sources=el('div','prototype-ui detail-access-list');
    PrototypeUI.mount(sources,{data:()=>({origins:group.origins,active:activeOrigin}),methods:{
      reviewIdentity:AccessRequestDemo.reviewIdentity,
      viewRequest(id){const row=group.origins.find(row=>row.id===id);if(!row?.request)return;node.close();const target=main.querySelector('[data-focus="access-detail-'+sourceId+'"]')||main.querySelector('input[name="keyword"]');const dialog=requestDetail(row.request,target,true);dialog.addEventListener('close',()=>groupedAccessDetail(sourceId,target,id),{once:true});}
    },template:`<el-collapse v-model="active" accordion><el-collapse-item v-for="origin in origins" :key="origin.id" :name="origin.id"><template #title><span class="detail-access-title"><span class="detail-access-heading"><span class="detail-access-name">{{origin.typeLabel}} · {{origin.origin}}</span><span class="cell-sub">{{origin.state}} · {{origin.activatedAt}}</span></span></span></template><div class="detail-access-body"><dl v-if="origin.request" class="detail-fields"><div><dt>批准人</dt><dd>{{reviewIdentity(origin.part)}}</dd></div><div><dt>批准时间</dt><dd>{{origin.part.reviewedAt || '未记录'}}</dd></div></dl><p class="access-rule-names" :title="origin.rules.map(rule=>rule.name).join('、')">{{origin.rules.map(rule=>rule.name).join('、')}}</p><div v-if="origin.request" class="detail-access-actions"><el-button link type="primary" @click="viewRequest(origin.id)" :aria-label="'查看申请 '+origin.origin">查看申请</el-button></div></div></el-collapse-item></el-collapse>`});
    body.append(sources);const footer=el('footer','dialog-footer');footer.append(button('关闭',()=>node.close()));node.append(body,footer);node.showModal();
  }
  function renderMyAccess() {
    const q = queries.access, tools = el('section', 'people-query-card personal-query');
    const reset = () => { Object.assign(q, { keyword: '', rule: '', page: 1 }); renderMyAccess(); main.querySelector('input[name="keyword"]')?.focus({ preventScroll: true }); };
    tools.append(RecordControls.filters({ value: q, includeDates: false, includeStatus:false, fields: [{ key: 'keyword', label: '数据源名称' }, { key: 'rule', label: '输出限制规则名称' }],
      onApply: next => { Object.assign(q, next); renderMyAccess(); main.querySelector('input[name="keyword"]')?.focus({ preventScroll: true }); }, onReset: reset
    }), button('申请额外数据访问', applyExtraAccess, 'admin-button is-primary', 'apply-data-access'));
    const records = GrantDemo.accessGroups(currentUser,q);
    q.page = Math.max(1, Math.min(q.page, Math.ceil(records.length / q.pageSize)));
    const panel = el('section', 'record-panel personal-records-panel'), empty = el('div', 'empty-state'), filtered = Boolean(q.keyword || q.rule);
    empty.append(WorkbenchIcon('search'), el('p', '', filtered ? '没有匹配的访问范围' : '暂无数据访问范围'));
    if (filtered) empty.append(button('清空筛选', reset));
    panel.append(RecordTable.render({ caption: '我的访问范围', minWidth: 800,
      columns: [{ key: 'source', label: '数据源', width: 180,grow:2 }, { key: 'rules', label: '当前输出限制',width:360,grow:4 }, { key: 'origin', label: '有效来源', width: 112,grow:1 }, { key: 'state', label: '状态', width:104,grow:1 }, { key: 'actions', label: '操作', width: 128, fixed:'right' }],
      items: records.slice((q.page - 1) * q.pageSize, q.page * q.pageSize), empty,
      row: record => {
        const view = button('查看详情', trigger => groupedAccessDetail(record.id, trigger), 'do-action is-primary access-detail-action', 'access-detail-' + record.id); view.setAttribute('aria-label', '查看' + record.source + '的合并限制与授权来源');
        return [textCell(record.source), textCell(record.ruleNames), textCell(record.activeCount+' 笔'),status(record.state), view];
      }
    }), RecordControls.pager({ total: records.length, ...q }, page => { q.page = page; renderMyAccess(); }, size => { q.pageSize = size; q.page = 1; renderMyAccess(); }));
    main.replaceChildren(pageHeading('我的访问范围'), tools, panel);
  }
  function renderMyRequests() {
    AccessRequestDemo.records();
    const q = queries.requests, tools = el('section', 'people-query-card personal-query');
    const reset = () => { Object.assign(q, { keyword: '', requestId: '', status: 'all', page: 1 }); renderMyRequests(); };
    tools.append(RecordControls.filters({ value: q, includeDates: false, fields: [{ key: 'keyword', label: '申请事项' }, { key: 'requestId', label: '申请编号' }],
      statuses: [{ value: 'all', label: '全部状态' }, ...['待分流', '待审批', '需补充', '部分已生效', '已生效', '已撤销', '已驳回', '已撤回'].map(value => ({ value, label: value }))],
      onApply: next => { Object.assign(q, next); renderMyRequests(); }, onReset: reset
    }), button('申请额外数据访问', applyExtraAccess, 'admin-button is-primary', 'apply-data-access'));
    const records = extraRequests.filter(r => r.user === currentUser.id && (q.status === 'all' || q.status === r.status) &&
      AdminQuery.matchesFields({ keyword: r.reason, requestId: r.id }, q))
      .sort((a, b) => b.submitted.localeCompare(a.submitted) || b.id.localeCompare(a.id));
    q.page = Math.max(1, Math.min(q.page, Math.ceil(records.length / q.pageSize)));
    const panel = el('section', 'record-panel personal-records-panel'), empty = el('div', 'empty-state'), filtered = Boolean(q.keyword || q.requestId || q.status !== 'all');
    empty.append(WorkbenchIcon('search'), el('p', '', filtered ? '没有匹配的申请' : '暂无数据访问申请'));
    if (filtered) empty.append(button('清空筛选', reset));
    panel.append(RecordTable.render({ caption: '我的数据访问申请', minWidth: 980,
      columns: [{ key: 'request', label: '申请事项',width:360,grow:4 }, {key:'sources',label:'申请数据源',width:240,grow:3}, { key: 'submitted', label: '提交时间', width: 166,grow:2 }, { key: 'status', label: '状态', width: 104,grow:1 }, { key: 'actions', label: '操作', width: 128,fixed:'right' }],
      items: records.slice((q.page - 1) * q.pageSize, q.page * q.pageSize), empty,
      row: request => { const title = el('div'); title.append(button(request.reason, trigger => requestDetail(request, trigger), 'cell-title'), el('span', 'cell-sub', request.id));
        const sources=(request.sourceIds||request.access.map(item=>item.source)).map(id=>dataSources.find(source=>source.id===id)?.name||'数据源不可用').join('、')||'待核定';
        return [title, textCell(sources), textCell(request.submitted), status(request.status), button('查看详情', trigger => requestDetail(request, trigger), 'do-action is-primary request-detail-action')]; }
    }), RecordControls.pager({ total: records.length, ...q }, page => { q.page = page; renderMyRequests(); }, size => { q.pageSize = size; q.page = 1; renderMyRequests(); }));
    main.replaceChildren(pageHeading('我的申请'), tools, panel);
  }
  function userTable(records, kind, target) {
    const q = queries[kind], filtered = Boolean(q.keyword || q.login || q.departmentId || q.status !== 'all');
    const found = records.filter(u => (q.status === 'all' || u.status === q.status) &&
      (!q.departmentId || u.department === q.departmentId || u.extra.includes(q.departmentId)) && AdminQuery.matchesFields({ keyword: u.name, login: u.login }, q))
      .sort((a, b) => b.updated.localeCompare(a.updated) || b.id.localeCompare(a.id));
    q.page = Math.max(1, Math.min(q.page, Math.ceil(found.length / q.pageSize)));
    const empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('search'), el('p', '', filtered ? '没有匹配的人员' : '该部门暂无成员'));
    if (filtered) empty.append(button('清空筛选', () => { Object.assign(q, { keyword: '', login: '', departmentId: '', status: 'all', page: 1 }); target(); }));
    const columns = [{ key: 'person', label: '人员', width:kind === 'users'?224:240 },
      ...(kind === 'users' ? [{ key: 'department', label: '部门', width: 160 }, { key: 'roles', label: '个人角色', width: 200 }] : [{ key: 'department', label: '所属部门',width:160 }, { key: 'membership', label: '成员关系', width: 144 }]),
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
        change('负责人', department?.manager, data.manager), change('部门说明', department?.note, data.note)],
      save: data => {
        data.name = data.name.trim(); data.code = data.code.trim();
        if (department) Object.assign(department, data);
        else { const item = { ...data, id: 'd' + Date.now(), status: '正常', access: [], created: timestamp() }; departments.push(item); selectedDepartment = item.id; treeKeyword = ''; departmentTab = 'members'; }
      }
    });
  }
  function joinMembers(d, trigger) {
    const candidates = users.filter(u => ['正常', '待首次登录'].includes(u.status) && !members(d.id).includes(u));
    if (!candidates.length) { notify('当前没有可加入的人员'); return; }
    openForm({ title: '添加兼任成员 · ' + d.name, trigger,
      value: { ids: [], reason: '' },
      fields: [{ key: 'ids', label: '选择人员', full: true, multiple: true, required: true, options: candidates.map(u => ({ value: u.id, label: u.name + ' · ' + deptName(u.department) })) },
        { key: 'reason', label: '加入原因', type: 'textarea', required: true, full: true }],
      preview: data => [change('所选人员', '', data.ids.map(id => users.find(u => u.id === id).name)), change('兼任部门', '原配置保留', '新增：' + d.name),
        change('部门初始数据访问', '', accessText(d.access))],
      save: data => { for (const id of data.ids) { const user = users.find(u => u.id === id); user.extra.push(d.id); user.updated = timestamp(); } }
    });
  }
  function removeMember(user, d, trigger) {
    if (user.department === d.id) { transferUser(user, trigger); return; }
    confirmChange({ title: '移出兼任成员', trigger, danger: true, description: '将“' + user.name + '”移出“' + d.name + '”，并结束其在本部门的初始数据访问。',
      preview: () => [change('兼任部门', user.extra.map(deptName), user.extra.filter(id => id !== d.id).map(deptName)), change('本部门初始数据访问', '关联', '结束')],
      save: () => { user.extra = user.extra.filter(id => id !== d.id); user.updated = timestamp(); } });
  }
  function moveDepartmentMembers(d, trigger) {
    const affected = members(d.id), destinations = departmentOptions().filter(o => !branch(d.id).includes(o.value));
    openForm({ title: '迁移直属成员 · ' + d.name, trigger, value: { destination: '', reason: '' },
      fields: [{ key: 'destination', label: '迁入部门', options: destinations, required: true, full: true }, { key: 'reason', label: '迁移原因', type: 'textarea', required: true, full: true }],
      preview: data => [change('受影响人员', affected.map(u => u.name), affected.length + ' 人'), change('部门数据来源', d.name, deptName(data.destination))],
      save: data => { for (const user of affected) { if (user.department === d.id) user.department = data.destination; user.extra = user.extra.filter(id => id !== d.id && id !== user.department); if (user.department !== data.destination && !user.extra.includes(data.destination)) user.extra.push(data.destination); user.updated = timestamp(); } } });
  }
  function moveChildDepartments(d, trigger) {
    const children = childDepartments(d.id).filter(item => item.status !== '已归档');
    openForm({ title: '迁移下级部门', trigger, value: { parent: '', reason: '' },
      fields: [{ key: 'parent', label: '新的上级部门', required: true, options: departmentOptions().filter(o => !branch(d.id).includes(o.value)), full: true },
        { key: 'reason', label: '迁移原因', type: 'textarea', required: true, full: true }],
      preview: data => [change('下级部门', children.map(item => item.name), '迁入：' + deptName(data.parent))],
      save: data => { for (const item of children) item.parent = data.parent; } });
  }
  function departmentAccess(d, trigger) {
    openForm({ title: '配置初始数据访问 · ' + d.name, trigger, value: { access: structuredClone(d.access), members: '直属 ' + members(d.id).length + ' 人，含直属兼任成员', reason: '' },
      fields: [{ key: 'members', label: '影响成员', readonly: true, full: true },
        { key: 'access', label: '数据源与输出限制规则', type: 'data-access', full: true },
        { key: 'reason', label: '配置原因', type: 'textarea', required: true, full: true }],
      validate: data => validateAccess(data.access),
      previewTitle: '核对“' + d.name + '”的数据访问',
      preview: data => [change('数据源与输出限制规则', accessText(d.access), accessText(data.access)),
        change('影响对象', members(d.id).map(u => u.name), '本部门直属 ' + members(d.id).length + ' 人，含直属兼任成员；不含下级'),
        change('配置原因', '', data.reason.trim())],
      save: data => { Object.assign(d, { access: data.access, requiresReview: false }); } });
  }
  function endDepartmentAccess(d, trigger) {
    confirmChange({ title: '结束初始数据访问', trigger, danger: true, description: '结束“' + d.name + '”的初始数据访问，影响本部门直属及直属兼任成员。',
      preview: () => [change('部门初始数据访问', accessText(d.access), '结束本部门来源')],
      save: () => { Object.assign(d, { access: [], requiresReview: false }); } });
  }
  function toggleDepartment(d, trigger) {
    if (d.status === '停用') {
      openForm({ title: '复核并恢复部门', trigger, note: '恢复后，保留的初始数据访问需再次复核。',
        value: { reviewed: false, reason: '' }, fields: [{ key: 'reviewed', label: '恢复前复核', type: 'checkbox', checkLabel: '已核对成员、下级部门及初始数据访问', full: true }, { key: 'reason', label: '恢复原因', type: 'textarea', required: true, full: true }],
        preview: () => [change('部门状态', '停用', '正常'), change('直属成员', members(d.id).length + ' 人', '保持组织归属'), change('初始数据访问', accessText(d.access), '待复核')],
        saveLabel: '恢复', save: () => { d.status = '正常'; d.requiresReview = Boolean(d.access.length); } });
      return;
    }
    confirmChange({ title: '停用部门', trigger, danger: true, description: '仅停用当前部门“' + d.name + '”，成员资料保留；下级部门保持各自状态。',
      preview: () => [change('部门状态', '正常', '停用'), change('本部门直属成员', members(d.id).map(u => u.name), '保留归属，结束本部门派生来源'),
        change('下级部门', childDepartments(d.id).length + ' 个', '保持原状态')],
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
    if (!items.every(item => item[1])) body.append(el('p', 'form-note', '请先处理未完成的关联事项。'));
    body.append(checks);
    const footer = el('footer', 'dialog-footer'); footer.append(button('关闭', () => node.close()));
    if (items.every(item => item[1])) footer.append(button('继续归档', () => { node.close(); confirmChange({ title: '归档部门', trigger, danger: true, description: '归档后只保留部门及其历史回看，不再用于人员归属或初始数据访问。',
      preview: () => [change('部门状态', '停用', '已归档'), change('直属成员、下级及初始数据访问', '已处理', '无待处理关联')], save: () => { d.status = '已归档'; } }); }, 'admin-button is-danger'));
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
      ...(d.access.length ? [{ label: '结束初始数据访问', separator: true, danger: true, action: trigger => endDepartmentAccess(d, trigger) }] : []),
      { label: d.status === '停用' ? '复核并恢复部门' : '停用部门', separator: true, danger: d.status !== '停用', action: trigger => toggleDepartment(d, trigger) },
      { label: '归档部门', danger: true, action: trigger => archiveDepartment(d, trigger) }
    ], 'department-more', '部门操作'));
    heading.append(label, actions); const meta = el('dl', 'summary-meta');
    meta.append(pair('部门编号', d.code), pair('上级部门', deptName(d.parent)), pair('负责人', d.manager),
      pair('成员范围', '直属 ' + members(d.id).length + ' 人 / 含下级 ' + allMembers(d.id).length + ' 人'));
    summary.append(heading, meta);
    if (d.note) summary.append(el('p', 'form-note', d.note));
    // 页签负责内容定位，整体操作固定在右侧；操作按钮不进入页签的键盘导航组。
    const panel = el('section', 'record-panel department-panel'), tabs = el('div', 'department-tabs');
    const tabList = el('div', 'department-tab-list'), tabActions = el('div', 'do-actions tab-action');
    tabList.setAttribute('role', 'tablist'); tabList.setAttribute('aria-label', '部门内容');
    for (const [key, name] of [['members', '成员'], ['children', '子部门'], ['template', '初始数据访问']]) {
      const tab = button(name, () => { departmentTab = key; renderDepartmentContent(); content.querySelector('[aria-selected="true"]')?.focus({ preventScroll: true }); }, 'department-tab');
      tab.id = 'department-tab-' + key; tab.setAttribute('role', 'tab'); tab.setAttribute('aria-selected', String(departmentTab === key)); tab.tabIndex = departmentTab === key ? 0 : -1; tab.setAttribute('aria-controls', 'department-tab-panel');
      tab.addEventListener('keydown', event => { if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return; event.preventDefault(); const keys = ['members', 'children', 'template']; departmentTab = keys[(keys.indexOf(key) + (event.key === 'ArrowRight' ? 1 : 2)) % 3]; renderDepartmentContent(); content.querySelector('[aria-selected="true"]').focus(); });
      tabList.append(tab);
    }
    if (departmentTab === 'members' && d.status === '正常') tabActions.append(button('加入成员', trigger => joinMembers(d, trigger), 'admin-button', 'join-members'));
    if (departmentTab === 'children' && d.status === '正常') tabActions.append(button('新增子部门', trigger => departmentForm(null, trigger, d.id), 'admin-button', 'add-child-tab'));
    if (departmentTab === 'template' && d.status !== '已归档') {
      tabActions.append(button('配置数据访问', trigger => departmentAccess(d, trigger), 'admin-button', 'edit-access'));
    }
    tabs.append(tabList);
    if (tabActions.childElementCount) tabs.append(tabActions);
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
      const empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('layers'), el('p', '', '暂无子部门'));
      const children = childDepartments(d.id);
      const query = queries.children; query.page = Math.max(1, Math.min(query.page, Math.ceil(children.length / query.pageSize)));
      tabBody.append(RecordTable.render({ caption: '子部门', minWidth: 760,
        columns: [{ key: 'name', label: '部门' }, { key: 'manager', label: '负责人', width: 112 }, { key: 'count', label: '含下级人数', width: 112 }, { key: 'status', label: '状态', width: 104 }, { key: 'actions', label: '操作', width: 160 }], items: children.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), empty,
        row: child => [button(child.name, () => selectDepartment(child.id), 'cell-title'), textCell(child.manager), textCell(allMembers(child.id).length + ' 人'), status(child.status),
          child.status === '已归档' ? el('span', 'cell-sub', '仅供回看') : button('编辑部门', trigger => departmentForm(child, trigger), 'do-action is-primary')]
      }), RecordControls.pager({ total: children.length, ...query }, page => { query.page = page; renderDepartmentContent(); }, size => { query.pageSize = size; query.page = 1; renderDepartmentContent(); }));
    } else {
      const config = el('section', 'template-content'), configFields = el('dl', 'detail-fields');
      configFields.append(pair('影响成员', '本部门直属 ' + members(d.id).length + ' 人'), pair('配置状态', d.status !== '正常' ? d.status : d.requiresReview ? '待复核' : d.access.length ? '已配置' : '未配置'));
      const permissions = el('div', 'department-access-preview prototype-ui');
      PrototypeUI.mount(permissions, { render: () => Vue.h(DataAccessTable, { access: d.access }) });
      config.append(configFields, permissions); tabBody.append(config);
    }
    content.replaceChildren(summary, panel);
  }
  function selectDepartment(id) {
    selectedDepartment = id; departmentTab = 'members'; queries.children.page = 1; Object.assign(queries.members, { keyword: '', login: '', status: 'all', page: 1 }); renderDepartments();
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
      methods: { filter(value, data) { return data.label.includes(value); }, select(data) { selectedDepartment = data.id; queries.children.page = 1; Object.assign(queries.members, { keyword: '', login: '', status: 'all', page: 1 }); renderDepartmentContent(); } },
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
    if(key==='grant-management'){location.replace('#grants');return;}
    const changed = section !== key; section = key || 'dashboard';
    pageLocation.title = titles[section] || '页面不存在';
    pageLocation.parent = '';
    document.title = (titles[section] || '页面不存在') + ' · DataOcean 后台管理';
    for (const link of document.querySelectorAll('.nav-link')) {
      if (link.hash === '#' + section) { link.setAttribute('aria-current', 'page'); const group = link.closest('details'); if (group) group.open = true; }
      else link.removeAttribute('aria-current');
    }
    if (section === 'dashboard') renderDashboard();
    else if (section === 'users') renderUsers();
    else if (section === 'departments') renderDepartments();
    else if (section === 'sources') SourceApprovalsPage.render(main, { el, button, textCell, status, pair, pageHeading, openForm, modal, currentUser });
    else if (section === 'my-access') renderMyAccess();
    else if (section === 'my-requests') renderMyRequests();
    else if (section === 'account-info') AccountInfoPage.render(main, { el, pair, status, pageHeading, currentUser });
    else if (section === 'audit') AuditPage.render(main, { el, button, pair, textCell, pageHeading, modal, currentUser, viewRequest: (request, trigger) => requestDetail(request, trigger, true) });
    else if (section === 'output-rules') OutputRulesPage.render(main, { el, button, textCell, status, modal, pair, pageHeading, notify, openForm, actor: currentUser.name });
    else if (section === 'roles') RolesPage.render(main, { el, button, textCell, status, modal, pair, pageHeading, notify, openForm, moreActions,
      setTitle(title, editor) { pageLocation.title = title; pageLocation.parent = editor ? '角色与功能权限' : ''; document.title = title + ' · DataOcean 后台管理'; } });
    else if (section === 'access-requests') AccessRequestsPage.render(main, { el, button, textCell, status, pair, pageHeading, notify, openForm, currentUser, DataAccessTable, viewResult: (request, trigger) => requestDetail(request, trigger, true) });
    else if (section==='grants') GrantsPage.render(main,{ el,button,textCell,status,pair,pageHeading,modal,openForm,currentUser,DataAccessTable,
      viewRequest:(request,trigger)=>requestDetail(request,trigger,true),openDepartment:id=>{selectedDepartment=id;departmentTab='template';location.hash='departments';}
    });
    else {
      const panel = el('section', 'planned-page'); panel.append(WorkbenchIcon('file-text'), el('h2', '', titles[section] ? '待制作' : '页面不存在'));
      if (!titles[section]) { const back = el('a', 'admin-button', '返回总览'); back.href = '#dashboard'; panel.append(back); }
      main.replaceChildren(pageHeading(titles[section] || '页面不存在'), panel);
    }
    if (changed) {
      main.scrollTop = 0; main.querySelector('h1')?.focus({ preventScroll: true });
      // 菜单独立滚动时，当前页也必须留在可见区域。
      requestAnimationFrame(() => document.querySelector('.nav-link[aria-current="page"]')?.scrollIntoView({ block: 'nearest', inline: 'nearest' }));
    }
  }
  function renderDashboard() {
    // 尚未制作的看板沿用清楚的占位，不用重复菜单的卡片模拟已有内容。
    const panel = el('section', 'planned-page'); panel.append(WorkbenchIcon('layers'), el('h2', '', '待制作'));
    main.replaceChildren(pageHeading('总览'), panel);
  }
  buildNav(); window.addEventListener('hashchange', render); render();
  if (section === 'my-requests' && new URLSearchParams(location.search).has('requestData')) {
    applyExtraAccess(main.querySelector('[data-focus="apply-data-access"]'));
    const url = new URL(location.href); url.searchParams.delete('requestData'); url.searchParams.delete('requestSource'); window.history.replaceState(null, '', url);
  }
})();
