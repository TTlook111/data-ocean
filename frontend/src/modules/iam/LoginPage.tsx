import { EyeInvisibleOutlined, EyeOutlined, KeyOutlined } from '@ant-design/icons';
import { Alert, App, Button, Form, Input, Modal, type InputRef } from 'antd';
import { useRef, useState } from 'react';
import { api, resetCsrf } from '../../shared/api';
import styles from './LoginPage.module.css';
import { LoginSchool } from './LoginSchool';
import type { Session } from './types';

/** 登录接口使用的凭据字段。 */
type Credentials = {
  /** 输入的登录名。 */
  username: string;
  /** 只提交给认证接口的密码。 */
  password: string;
};

/**
 * 登录只收集凭据，不读取或选择角色来模拟身份。
 */
export function LoginPage({ done }: { done: () => Promise<unknown> }) {
  const [form] = Form.useForm<Credentials>();
  const usernameRef = useRef<InputRef>(null);
  const passwordRef = useRef<InputRef>(null);
  const submitting = useRef(false);

  const [busy, setBusy] = useState(false);

  const [error, setError] = useState<string | null>(null);

  const [passwordVisible, setPasswordVisible] = useState(false);

  const [recoveryOpen, setRecoveryOpen] = useState(false);

  const hasPassword = Boolean(Form.useWatch('password', form));
  const showPassword = hasPassword && passwordVisible;

  /**
   * 成功后轮换 CSRF 并读取服务端当前身份，失败保留登录名。
   */
  async function login(values: Credentials) {
    // 在按钮加载状态提交到 DOM 前，也阻止重复认证请求。
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setError(null);
    try {
      await api('/auth/login', { method: 'POST', body: values });
      resetCsrf();
      await done();
    } catch (failure) {
      // 界面只显示认证接口提供的错误信息。
      setError(failure instanceof Error ? failure.message : '登录未完成');
    } finally {
      // 登录接口失败后可以继续修正并重试。
      submitting.current = false;
      setBusy(false);
    }
  }

  /**
   * 项目开发配置提供跨电脑一致的示例账号，实际认证仍调用真实服务。
   */
  function fillExample() {
    form.setFieldsValue({
      username: import.meta.env.VITE_DEV_LOGIN_USERNAME,
      password: import.meta.env.VITE_DEV_INITIAL_PASSWORD,
    });
    setPasswordVisible(false);
    setError(null);
    form.focusField('username');

    // 在允许动态效果时短暂提示两个已填字段。
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    for (const input of [usernameRef.current, passwordRef.current]) {
      input?.nativeElement?.animate?.(
        [{ backgroundColor: '#eaf0fc' }, { backgroundColor: '#ffffff' }],
        {
          duration: 340,
        },
      );
    }
  }

  /**
   * 用户修正凭据后收起接口错误，清空密码时恢复掩码状态。
   */
  function updateCredentials(changed: Partial<Credentials>) {
    setError(null);
    if (changed.password === '') setPasswordVisible(false);
  }

  return (
    <main className={styles.layout}>
      {/* 表单在键盘顺序中优先，待核验前只提交凭据。 */}
      <section className={styles.panel} aria-labelledby="login-title">
        <Form
          className={styles.form}
          name="login"
          form={form}
          layout="vertical"
          requiredMark={false}
          onFinish={login}
          onValuesChange={updateCredentials}
          scrollToFirstError={{ focus: true }}
        >
          <h1 id="login-title">登录</h1>
          {/* 保留登录名、密码校验与浏览器自动填充。 */}
          <Form.Item
            className={styles.field}
            name="username"
            label="登录名"
            rules={[{ required: true, message: '请输入登录名' }]}
          >
            <Input
              ref={usernameRef}
              placeholder="请输入登录名"
              autoComplete="username"
              autoFocus
              maxLength={80}
              readOnly={busy}
            />
          </Form.Item>
          <Form.Item
            className={styles.field}
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input
              ref={passwordRef}
              classNames={{ root: styles.passwordControl }}
              type={showPassword ? 'text' : 'password'}
              placeholder="请输入密码"
              autoComplete="current-password"
              maxLength={32}
              readOnly={busy}
              suffix={
                <Button
                  type="text"
                  size="small"
                  htmlType="button"
                  className={styles.reveal}
                  aria-label={showPassword ? '隐藏密码' : '显示密码'}
                  icon={
                    showPassword ? (
                      <EyeInvisibleOutlined aria-hidden />
                    ) : (
                      <EyeOutlined aria-hidden />
                    )
                  }
                  disabled={!hasPassword}
                  onClick={() => setPasswordVisible(!showPassword)}
                />
              }
            />
          </Form.Item>
          {/* 保留开发示例和正式的管理员重置入口。 */}
          <div className={styles.actions}>
            {import.meta.env.DEV && (
              <Button type="link" className={styles.example} onClick={fillExample} disabled={busy}>
                填入示例账号
              </Button>
            )}
            <Button type="link" className={styles.forgot} onClick={() => setRecoveryOpen(true)}>
              忘记密码
            </Button>
          </div>
          <Button className={styles.submit} htmlType="submit" type="primary" block loading={busy}>
            {busy ? '正在登录…' : '登录'}
          </Button>
          <div className={styles.feedback}>
            {error && <Alert type="error" title={error} showIcon />}
          </div>
        </Form>
      </section>

      {/* 左侧水面不参与凭据提交。 */}
      <LoginSchool />

      {/* 复用组件的键盘关闭、焦点约束和返回，再使用确认版式。 */}
      <Modal
        centered
        width={376}
        open={recoveryOpen}
        mask={{ closable: false }}
        rootClassName={styles.recovery}
        styles={{
          // 由弹窗统一承接内距，分区不重复叠加后台表单的留白。
          container: { padding: 24 },
          header: { padding: 0, marginBottom: 14, border: 0, background: 'transparent' },
          body: { padding: 0 },
          footer: { padding: 0, marginTop: 18, border: 0, background: 'transparent' },
          close: { top: 24, insetInlineEnd: 24 },
          // 浅色遮罩弱化背景，同时保留水面轮廓。
          mask: { background: '#243b552e', backdropFilter: 'blur(2px)' },
        }}
        onCancel={() => setRecoveryOpen(false)}
        onOk={() => setRecoveryOpen(false)}
        okText="知道了"
        okButtonProps={{ style: { minWidth: 82, height: 36 } }}
        footer={(_, { OkBtn }) => <OkBtn />}
        title={
          <span className={styles.recoveryHeading}>
            <span className={styles.recoveryIcon}>
              <KeyOutlined aria-hidden />
            </span>
            忘记密码
          </span>
        }
      >
        <p className={styles.recoveryCopy}>
          请联系管理员核实身份并重置密码。
          <br />
          重置后首次登录需设置新密码。
        </p>
      </Modal>
    </main>
  );
}

/**
 * 首次或重置后仅开放必要改密，完成后必须重新登录。
 */
export function InitialPasswordPage({
  session,
  done,
  logout,
}: {
  session: Session;
  done: () => Promise<unknown>;
  logout: () => Promise<unknown>;
}) {
  const [busy, setBusy] = useState(false);

  const [error, setError] = useState<string | null>(null);

  const { message } = App.useApp();

  /**
   * 受限会话带当前人员版本提交，成功后清除旧认证显示。
   */
  async function submit(values: { newPassword: string; confirmPassword: string }) {
    setBusy(true);
    try {
      await api('/auth/password/initial', {
        method: 'POST',
        body: { ...values, expectedVersion: session.version },
      });
      resetCsrf();
      message.success('密码已更新，请重新登录');
      await done();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '改密未完成');
    } finally {
      setBusy(false);
    }
  }

  // 必要改密界面不渲染业务菜单或旧权限缓存。
  return (
    <div className="login-shell">
      <header className="login-brand">
        <img src="/dataocean-mark.png" alt="" />
        <strong>DataOcean</strong>
      </header>
      <main className="login-page">
        <section className="login-card">
          <h1>设置新密码</h1>
          <p className="login-note">首次登录或密码重置后，请修改初始密码。</p>
          <p className="login-name">
            登录名 <strong>{session.username}</strong>
          </p>
          {error && <Alert type="error" title={error} showIcon className="form-alert" />}
          <Form
            name="initial-password"
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => setError(null)}
            scrollToFirstError={{ focus: true }}
          >
            <Form.Item
              name="newPassword"
              label="新密码"
              rules={[
                { required: true, message: '请输入新密码' },
                {
                  pattern: /^(?=.*[A-Za-z])(?=.*[0-9]).{8,32}$/u,
                  message: '密码需为 8–32 位，包含字母和数字',
                },
              ]}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
            <Form.Item
              name="confirmPassword"
              label="确认新密码"
              dependencies={['newPassword']}
              rules={[
                { required: true, message: '请再次输入新密码' },
                ({ getFieldValue }) => ({
                  validator(_, value) {
                    return value === getFieldValue('newPassword')
                      ? Promise.resolve()
                      : Promise.reject(new Error('两次新密码不一致'));
                  },
                }),
              ]}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
            <Button htmlType="submit" type="primary" loading={busy} block>
              确认修改
            </Button>
            <Button type="link" className="return-login" onClick={logout}>
              返回登录
            </Button>
          </Form>
        </section>
      </main>
    </div>
  );
}
