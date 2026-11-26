/* 本人资料沿用共用人员示例；联系方式和密码由本人维护，组织与角色只读。 */
(() => {
  'use strict';
  function render(main, ui) {
    const { el, button, pair, status, pageHeading, currentUser, openForm } = ui;
    if (!SourceApprovalDemo.hasAbility(currentUser, 'account-info.view')) {
      const denied = el('section', 'planned-page');
      denied.append(el('p', '', '当前账号没有查看账号信息的权限。'));
      main.replaceChildren(pageHeading('账号信息'), denied); return;
    }
    // 使用一个连续面板，分组标题、资料及操作各自沿固定列对齐。
    const panel = el('section', 'account-panel'), basic = el('section', 'account-section');
    const fields = el('dl', 'account-fields'), name = pair('姓名', currentUser.name);
    name.querySelector('dd').append(status(currentUser.status)); name.classList.add('account-name-field');
    const departmentName = id => PeopleDemo.departments.find(row => row.id === id)?.name || '部门不可用';
    fields.append(name, pair('登录名', currentUser.login), pair('主部门', departmentName(currentUser.department)), pair('兼任部门', currentUser.extra.map(departmentName).join('、') || '无兼任部门'));
    const names = el('div', 'account-roles');
    for (const role of currentUser.roles) names.append(el('span', 'account-role', role));
    if (!currentUser.roles.length) names.append(el('span', 'account-unassigned', '未分配角色'));
    const roles = pair('个人角色', ''); roles.classList.add('account-role-field'); roles.querySelector('dd').replaceChildren(names); fields.append(roles);
    basic.append(el('h2', '', '基本信息'), fields);
    const contact = el('section', 'account-section account-contact-section'), contactActions = el('div', 'account-section-actions');
    contactActions.append(button('编辑联系方式', trigger => {
      const initial = { phone: currentUser.phone || '', email: currentUser.email || '' };
      openForm({ title: '编辑联系方式', trigger, value: initial, protectChanges: true,
        fields: [{ key: 'phone', label: '手机号', full: true }, { key: 'email', label: '邮箱', full: true }],
        hasChanges: data => data.phone.trim() !== initial.phone || data.email.trim() !== initial.email,
        save: data => {
          // 只回写本人联系方式，沿用人员管理的同一条示例，不接受身份或权限字段。
          currentUser.phone = data.phone.trim(); currentUser.email = data.email.trim();
          currentUser.updated = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16);
        }, success: '联系方式已保存'
      }).classList.add('account-edit-dialog');
    }));
    contactActions.querySelector('button').dataset.focus = 'edit-own-contacts';
    const contacts = el('dl', 'account-fields');
    contacts.append(pair('手机号', currentUser.phone || '未填写'), pair('邮箱', currentUser.email || '未填写'));
    contact.append(el('h2', '', '联系方式'), contacts, contactActions);
    const password = el('section', 'account-section account-password-section'), passwordActions = el('div', 'account-section-actions');
    passwordActions.append(button('修改密码', trigger => changePassword(trigger, ui)));
    passwordActions.querySelector('button').dataset.focus = 'change-own-password';
    password.append(el('h2', '', '登录密码'), el('p', 'account-password-value', '已设置'), passwordActions);
    panel.append(basic, contact, password);
    main.replaceChildren(pageHeading('账号信息'), panel);
  }
  function changePassword(trigger, { el, modal }) {
    const node = modal('修改密码', trigger), host = el('div');
    node.classList.add('account-edit-dialog'); node.append(host);
    PrototypeUI.mount(host, {
      data() {
        return { model: { current: '', next: '', confirm: '' }, currentError: '', busy: false,
          fields: [{ key: 'current', label: '当前密码' }, { key: 'next', label: '新密码' }, { key: 'confirm', label: '确认新密码' }],
          rules: {
            current: [{ required: true, message: '请输入当前密码', trigger: 'blur' }, { validator: (_, value, done) => done(this.currentError ? new Error(this.currentError) : undefined), trigger: 'blur' }],
            next: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/, message: '密码需为 8–32 位，包含字母和数字', trigger: 'blur' }, { validator: (_, value, done) => done(value && value === this.model.current ? new Error('新密码不能与当前密码相同') : undefined), trigger: 'blur' }],
            confirm: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }, { validator: (_, value, done) => done(value === this.model.next ? undefined : new Error('两次输入的密码不一致')), trigger: 'blur' }]
          }
        };
      },
      mounted() { node.closeGuard = () => { if (!this.busy) node.close(); }; },
      beforeUnmount() { this.model.current = ''; this.model.next = ''; this.model.confirm = ''; },
      methods: {
        cancel() { if (!this.busy) node.close(); },
        validated(prop, valid) {
          this.$nextTick(() => {
            const input = this.$refs.form.getField(prop)?.$el.querySelector('input'), id = 'account-password-' + prop + '-error';
            if (!input) return;
            input.setAttribute('aria-invalid', String(!valid));
            if (valid) input.removeAttribute('aria-describedby'); else input.setAttribute('aria-describedby', id);
          });
        },
        edited(key) {
          if (key === 'current') this.currentError = '';
          // 修正输入后立即清除已解决的错误，联动重查已报错的确认项。
          const keys = [key, ...(key === 'current' ? ['next'] : []), ...(key === 'next' ? ['confirm'] : [])];
          this.$nextTick(() => {
            for (const prop of keys) if (this.$refs.form.getField(prop)?.validateState === 'error') this.$refs.form.validateField(prop, () => {});
          });
        },
        focusError() { this.$nextTick(() => node.querySelector('.el-form-item.is-error input')?.focus()); },
        submit() {
          if (this.busy) return;
          this.busy = true;
          this.$refs.form.validate(async valid => {
            if (!valid) { this.busy = false; this.focusError(); return; }
            if (!await PrototypeLogin.matches(this.model.current)) {
              this.busy = false; this.currentError = '当前密码不正确';
              this.$refs.form.validateField('current', () => this.focusError()); return;
            }
            await PrototypeLogin.change(this.model.next);
            this.model.current = ''; this.model.next = ''; this.model.confirm = '';
            // 只跳转状态标记，密码不进入 URL、人员示例或业务审计。
            location.href = '../app/login.html?passwordChanged=1';
          });
        }
      },
      template: `<el-form ref="form" :model="model" :rules="rules" label-position="top" @validate="validated" @submit.prevent="submit">
        <div class="dialog-body"><el-form-item v-for="field in fields" :key="field.key" :label="field.label" :prop="field.key">
          <el-input v-model="model[field.key]" :aria-label="field.label" type="password" show-password :autocomplete="field.key==='current'?'current-password':'new-password'" :placeholder="field.key==='next'?'8–32 位，包含字母和数字':''" :disabled="busy" @input="edited(field.key)"></el-input>
          <template #error="{error}"><div :id="'account-password-'+field.key+'-error'" class="el-form-item__error" role="alert">{{error}}</div></template>
        </el-form-item><p class="field-help">修改成功后需重新登录。</p></div>
        <footer class="dialog-footer"><el-button :disabled="busy" @click="cancel">取消</el-button><el-button type="primary" native-type="submit" :loading="busy">确认修改</el-button></footer>
      </el-form>`
    });
    node.showModal(); node.querySelector('input')?.focus();
  }
  window.AccountInfoPage = { render };
})();
