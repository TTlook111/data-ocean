import { Alert, App, Button, Form, Input } from 'antd';
import { useState } from 'react';
import { api, resetCsrf } from '../../shared/api';
import type { Session } from './types';

/**
 * 登录只收集凭据，不读取或选择角色来模拟身份。
 */
export function LoginPage({ done }: { done: () => Promise<unknown> }) {
  const [form] = Form.useForm<{ username: string; password: string }>();

  const [busy, setBusy] = useState(false);

  const [error, setError] = useState<string | null>(null);

  const { modal } = App.useApp();

  /**
   * 成功后轮换 CSRF 并读取服务端当前身份，失败保留登录名。
   */
  async function login(values: { username: string; password: string }) {
    setBusy(true);
    setError(null);
    try {
      await api('/auth/login', { method: 'POST', body: values });
      resetCsrf();
      await done();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '登录未完成');
    } finally {
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
    form.focusField('username');
  }

  // 按原型保留左上品牌和居中的单个登录卡片。
  return (
    <div className="login-shell">
      <header className="login-brand">
        <img src="/dataocean-mark.png" alt="" />
        <strong>DataOcean</strong>
      </header>
      <main className="login-page">
        <section className="login-card" aria-label="登录">
          <h1>登录</h1>
          {error && <Alert type="error" title={error} showIcon className="form-alert" />}
          <Form
            name="login"
            form={form}
            layout="vertical"
            onFinish={login}
            onValuesChange={() => setError(null)}
            scrollToFirstError={{ focus: true }}
          >
            <Form.Item
              name="username"
              label="登录名"
              rules={[{ required: true, message: '请输入登录名' }]}
            >
              <Input
                aria-label="登录名"
                placeholder="请输入登录名"
                autoComplete="username"
                autoFocus
                maxLength={80}
              />
            </Form.Item>
            <Form.Item
              name="password"
              label="密码"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                aria-label="密码"
                placeholder="请输入密码"
                autoComplete="current-password"
                maxLength={32}
              />
            </Form.Item>
            <div className="login-help">
              <Button
                type="link"
                onClick={() =>
                  modal.info({
                    title: '忘记密码',
                    content: '请联系管理员核实身份并重置密码。重置后首次登录需设置新密码。',
                    okText: '知道了',
                  })
                }
              >
                忘记密码
              </Button>
            </div>
            <Button htmlType="submit" type="primary" block loading={busy}>
              登录
            </Button>
          </Form>
          {import.meta.env.DEV && (
            <div className="demo-account">
              <Button type="link" onClick={fillExample}>
                填入示例账号
              </Button>
            </div>
          )}
        </section>
      </main>
    </div>
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
