import { Alert, App, Button, Form, Input, Modal, Space, Tag } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { api, ApiError, mutation, resetCsrf } from '../../shared/api';
import { useFormClose } from '../../shared/useFormClose';
import { ReadState, Status } from '../../shared/Records';
import type { Person } from './types';

/**
 * 本人资料使用一块连续面板，身份由管理员维护，联系方式由本人更新。
 */
export function AccountPage({ expired }: { expired: () => Promise<unknown> }) {
  const record = useQuery({ queryKey: ['account'], queryFn: () => api<Person>('/account') });

  const [mode, setMode] = useState<'contact' | 'password' | null>(null);

  const [form] = Form.useForm();

  const [busy, setBusy] = useState(false);

  const [error, setError] = useState<string | null>(null);

  const [command, setCommand] = useState('');

  const { message } = App.useApp();

  const cache = useQueryClient();

  const values = Form.useWatch((value) => value, { form, preserve: true });

  const initialContact = useRef({ phone: '', email: '' });

  /**
   * 打开时固定资料修订，后台查询刷新不能替旧草稿接受新的并发版本。
   */
  const editorVersion = useRef(0);

  const contactChanged =
    (values?.phone?.trim() ?? '') !== initialContact.current.phone ||
    (values?.email?.trim() ?? '') !== initialContact.current.email;

  /**
   * 密码关闭即清空，联系方式存在实际改动时先确认放弃。
   */
  function closeEditor() {
    form.resetFields();
    setMode(null);
    setError(null);
  }

  const requestClose = useFormClose(form, closeEditor, busy, mode === 'contact', () => {
    // 关闭时读取即时输入，避免表单观察更新尚未渲染时遗漏或误判改动。
    const current = form.getFieldsValue();
    return (
      (current.phone?.trim() ?? '') !== initialContact.current.phone ||
      (current.email?.trim() ?? '') !== initialContact.current.email
    );
  });

  /**
   * 每次进入编辑都从当前事实填充，取消不落库。
   */
  function open(next: 'contact' | 'password') {
    setMode(next);
    setError(null);
    setCommand(crypto.randomUUID());
    editorVersion.current = record.data!.version;
    form.resetFields();
    if (next === 'contact') {
      initialContact.current = {
        phone: record.data?.phone?.trim() ?? '',
        email: record.data?.email?.trim() ?? '',
      };
    }
  }

  /**
   * 本人联系方式与改密分别调用专属契约，未知管理字段不会被提交。
   */
  async function save(values: Record<string, string>) {
    if (busy) return;
    setBusy(true);
    try {
      if (mode === 'contact') {
        await mutation(
          '/account/contact',
          'PUT',
          {
            expectedVersion: editorVersion.current,
            phone: values.phone?.trim() || null,
            email: values.email?.trim() || null,
          },
          'account.contact',
          command,
        );
        await cache.invalidateQueries();
        closeEditor();
        message.success('联系方式已保存');
      } else {
        await api('/account/password', {
          method: 'POST',
          body: { ...values, expectedVersion: editorVersion.current },
        });
        resetCsrf();
        closeEditor();
        message.success('密码已更新，请重新登录');
        await expired();
      }
    } catch (failure) {
      // 服务端字段错误与密码拒绝回到对应输入，不用顶部提示替代就近校验。
      const fields = failure instanceof ApiError ? failure.fields : [];
      const passwordField =
        failure instanceof Error && /原密码/.test(failure.message)
          ? 'currentPassword'
          : failure instanceof Error && /新密码/.test(failure.message)
            ? 'newPassword'
            : null;
      if (fields.length || passwordField) {
        const errors = fields.length
          ? fields
          : [{ field: passwordField!, message: (failure as Error).message }];
        form.setFields(errors.map((item) => ({ name: item.field, errors: [item.message] })));
        form.scrollToField(errors[0].field, { focus: true });
      } else setError(failure instanceof Error ? failure.message : '操作未完成');
    } finally {
      setBusy(false);
    }
  }

  const person = record.data;

  // 身份、组织与联系资料保持连续的阅读轴，编辑入口就近提供。
  return (
    <ReadState error={record.error} retry={record.refetch} loading={record.isPending}>
      <section className="panel account-panel" aria-label="账号信息">
        <section className="account-section">
          <h2>基本信息</h2>
          <dl className="account-fields">
            <div className="account-name-field">
              <dt>姓名</dt>
              <dd>
                {person?.displayName}
                {person && <Status value={person.displayStatus} />}
              </dd>
            </div>
            <div>
              <dt>登录名</dt>
              <dd>{person?.username}</dd>
            </div>
            <div>
              <dt>主部门</dt>
              <dd>{person?.primaryDepartment?.name ?? '—'}</dd>
            </div>
            <div>
              <dt>兼任部门</dt>
              <dd>
                {person?.secondaryDepartments.map((item) => item.name).join('、') || '无兼任部门'}
              </dd>
            </div>
            <div className="account-role-field">
              <dt>个人角色</dt>
              <dd>
                {person?.roles.length
                  ? person.roles.map((role) => (
                      <Tag className="account-role" key={role.id}>
                        {role.name}
                      </Tag>
                    ))
                  : '未分配角色'}
              </dd>
            </div>
          </dl>
          <span />
        </section>
        <section className="account-section account-contact-section">
          <h2>联系方式</h2>
          <dl className="account-fields">
            <div>
              <dt>手机号</dt>
              <dd>{person?.phone || '未填写'}</dd>
            </div>
            <div>
              <dt>邮箱</dt>
              <dd>{person?.email || '未填写'}</dd>
            </div>
          </dl>
          <Button onClick={() => open('contact')}>编辑联系方式</Button>
        </section>
        <section className="account-section">
          <h2>登录密码</h2>
          <span className="muted">已设置</span>
          <Button onClick={() => open('password')}>修改密码</Button>
        </section>
      </section>
      <Modal
        centered
        width={440}
        open={mode !== null}
        title={mode === 'contact' ? '编辑联系方式' : '修改密码'}
        onCancel={requestClose}
        closable={!busy}
        destroyOnHidden
        footer={
          <Space>
            <Button disabled={busy} onClick={requestClose}>
              取消
            </Button>
            <Button
              type="primary"
              loading={busy}
              disabled={mode === 'contact' && !contactChanged}
              onClick={() => form.submit()}
            >
              {mode === 'contact' ? '保存' : '确认修改'}
            </Button>
          </Space>
        }
      >
        {error && <Alert type="error" title={error} showIcon className="form-alert" />}
        <Form
          name="account-editor"
          key={command}
          form={form}
          layout="vertical"
          initialValues={mode === 'contact' ? initialContact.current : {}}
          preserve={false}
          onFinish={save}
          onValuesChange={() => setError(null)}
          scrollToFirstError={{ focus: true }}
        >
          {mode === 'contact' ? (
            <>
              <Form.Item
                name="phone"
                label="手机号"
                rules={[{ max: 32, message: '手机号最多 32 字符' }]}
              >
                <Input autoComplete="tel" />
              </Form.Item>
              <Form.Item
                name="email"
                label="邮箱"
                rules={[{ type: 'email', message: '邮箱格式不正确' }, { max: 254 }]}
              >
                <Input autoComplete="email" />
              </Form.Item>
            </>
          ) : mode === 'password' ? (
            <>
              <Form.Item
                name="currentPassword"
                label="原密码"
                rules={[{ required: true, message: '请输入原密码' }]}
              >
                <Input.Password autoComplete="current-password" />
              </Form.Item>
              <Form.Item
                name="newPassword"
                label="新密码"
                dependencies={['currentPassword']}
                rules={[
                  { required: true, message: '请输入新密码' },
                  {
                    pattern: /^(?=.*[A-Za-z])(?=.*[0-9]).{8,32}$/u,
                    message: '密码需为 8–32 位，包含字母和数字',
                  },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      return !value || value !== getFieldValue('currentPassword')
                        ? Promise.resolve()
                        : Promise.reject(new Error('新密码不能与原密码相同'));
                    },
                  }),
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
            </>
          ) : null}
        </Form>
      </Modal>
    </ReadState>
  );
}
