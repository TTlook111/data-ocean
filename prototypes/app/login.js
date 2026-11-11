/* 仅演示登录与首次改密界面；示例比较不接认证服务，不写本地存储。 */
(() => {
  'use strict';
  let examplePassword = 'Demo2026';
  let needsChange = new URLSearchParams(location.search).get('first') === '1';
  Vue.createApp({
    data() {
      // 原型首页直接提供示例填入，便于继续登录演示；改密后使用新密码重新登录。
      return { mode: 'login', help: false, error: '', changed: false, form: { login: '', password: '' }, passwords: { next: '', confirm: '' },
        loginRules: { login: [{ required: true, message: '请输入登录名', trigger: 'blur' }], password: [{ required: true, message: '请输入密码', trigger: 'blur' }] },
        passwordRules: { next: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/, message: '密码需为 8–32 位，包含字母和数字', trigger: 'blur' }],
          confirm: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }, { validator: (_, value, done) => done(value === this.passwords.next ? undefined : new Error('两次输入的密码不一致')), trigger: 'blur' }] }
      };
    },
    methods: {
      // 登录与改密沿用后台表单的错误聚焦和就近说明关联。
      validated(formName, prop, valid) {
        this.$nextTick(() => {
          const item = this.$refs[formName]?.getField(prop), id = formName + '-' + prop + '-error';
          for (const input of item?.$el.querySelectorAll('input') || []) {
            input.setAttribute('aria-invalid', String(!valid));
            if (valid) input.removeAttribute('aria-describedby');
            else input.setAttribute('aria-describedby', id);
          }
        });
      },
      focusError(formName) { this.$nextTick(() => this.$refs[formName]?.$el.querySelector('.el-form-item.is-error input')?.focus()); },
      fillExample() { this.form.login = 'lin.admin'; this.form.password = examplePassword; this.error = ''; this.$refs.loginForm.clearValidate(); this.validated('loginForm', 'login', true); this.validated('loginForm', 'password', true); },
      submit() { this.$refs.loginForm.validate(valid => {
        if (!valid) { this.focusError('loginForm'); return; }
        if (this.form.login.trim() !== 'lin.admin' || this.form.password !== examplePassword) { this.error = '登录名或密码不正确'; this.form.password = ''; this.$nextTick(() => this.$refs.loginForm.$el.querySelector('input[aria-label="密码"]')?.focus()); return; }
        this.form.password = ''; this.error = '';
        if (needsChange) { this.mode = 'change'; document.title = '首次登录设置密码 · DataOcean'; this.$nextTick(() => this.$refs.changeForm.$el.querySelector('input')?.focus()); }
        else location.href = '../后台管理/index.html#dashboard';
      }); },
      changePassword() { this.$refs.changeForm.validate(valid => {
        if (!valid) { this.focusError('changeForm'); return; }
        examplePassword = this.passwords.next; this.passwords.next = ''; this.passwords.confirm = ''; needsChange = false; this.changed = true; this.mode = 'complete';
      }); },
      returnToLogin() { this.mode = 'login'; this.error = ''; this.form.password = ''; this.passwords.next = ''; this.passwords.confirm = ''; document.title = '登录 · DataOcean'; this.$nextTick(() => this.$refs.loginForm.$el.querySelector('input')?.focus()); }
    }
  }).use(ElementPlus, { locale: ElementPlusLocaleZhCn, size: 'large' }).mount('#login-app');
})();
