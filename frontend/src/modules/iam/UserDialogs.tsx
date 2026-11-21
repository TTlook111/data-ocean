import { Alert, App, Button, Checkbox, Empty, Form, Input, Modal, Select, Spin } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { UserOutlined } from '@ant-design/icons';
import { api, mutation, queryString } from '../../shared/api';
import { useSession, allowed } from '../../shared/session';
import { ReviewDialog } from '../../shared/ReviewDialog';
import { useFormClose } from '../../shared/useFormClose';
import { useReturnFocus } from '../../shared/useReturnFocus';
import { applyFormError } from '../../shared/formErrors';
import { OperationContext } from '../../shared/OperationContext';
import { Status } from '../../shared/Records';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import type { Department, Person, PersonForm, Preview, Role, Page } from './types';

/**
 * 人员资料表单只传稳定 ID，主兼任在同一次保存中完成。
 */
export function PersonEditor({
  person,
  close,
  created,
}: {
  /** 编辑时使用当前人员详情，新增时为空。 */
  person: Person | null;
  /** 退出当前资料表单。 */
  close: () => void;
  /** 新增成功后由列表展示真实创建对象。 */
  created?: (id: string) => void;
}) {
  useReturnFocus();

  const session = useSession();

  const [form] = Form.useForm<PersonForm>();

  const [error, setError] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);

  const requestClose = useFormClose(form, close, busy);

  const [review, setReview] = useState<ReviewSubmission | null>(null);

  const departmentQuery = useQuery({
    queryKey: ['department-options', person?.id ?? 'create'],
    queryFn: () =>
      api<Department[]>(
        `/iam/department-options?${queryString({ purpose: person ? 'USER_TRANSFER' : 'USER_CREATE' })}${
          person
            ? [person.primaryDepartment, ...person.secondaryDepartments]
                .filter(Boolean)
                .map((item) => `&selectedIds=${item!.id}`)
                .join('')
            : ''
        }`,
      ),
    enabled: !person || allowed(session, 'users.transfer'),
  });

  const roleQuery = useQuery({
    queryKey: ['role-options', 'USER_CREATE'],
    queryFn: () => api<Role[]>('/iam/role-options?purpose=USER_CREATE'),
    enabled: !person && allowed(session, 'users.assign'),
  });

  const options =
    departmentQuery.data ??
    [person?.primaryDepartment, ...(person?.secondaryDepartments ?? [])].filter(
      (item): item is Department => Boolean(item),
    );

  /**
   * 密码不进入创建预览，正式提交仍由同一核对凭据绑定非秘密字段。
   */
  async function preview(values: PersonForm) {
    setBusy(true);
    setError(null);
    try {
      const membership = {
        primaryDepartmentId: values.primaryDepartmentId,
        secondaryDepartmentIds: values.secondaryDepartmentIds ?? [],
      };

      const contact = {
        displayName: values.displayName.trim(),
        membership,
        phone: values.phone?.trim() || null,
        email: values.email?.trim() || null,
        managementNote: values.managementNote?.trim() || null,
      };

      const roles = (values.roleIds ?? []).map((id) => ({
        roleId: id,
        version: roleQuery.data!.find((role) => role.id === id)!.version,
      }));

      const proposal = person ? contact : { ...contact, username: values.username.trim(), roles };

      const result = await api<Preview>(
        person ? `/iam/users/${person.id}/impact-preview` : '/iam/users/impact-preview',
        {
          method: 'POST',
          body: person
            ? { action: 'UPDATE', expectedVersion: person.version, proposal }
            : { proposal },
        },
      );
      setReview({
        title: person ? `核对修改：${person.displayName}` : '核对新增人员',
        preview: result,
        path: person ? `/iam/users/${person.id}` : '/iam/users',
        method: person ? 'PUT' : 'POST',
        operation: person ? 'users.edit' : 'users.create',
        body: person
          ? { ...proposal, expectedVersion: person.version }
          : { ...proposal, initialPassword: values.initialPassword },
        done: (id) => {
          // 新增成功才交回对象标识，取消、预览或返回修改不改变列表上下文。
          close();
          if (!person) created?.(id);
        },
      });
    } catch (failure) {
      // 登录名冲突与接口字段校验定位到输入；其余失败保留整体反馈。
      setError(applyFormError(form, failure, { 409101: 'username' }));
    } finally {
      setBusy(false);
    }
  }

  // 编辑弹窗保留原身份，归属字段按独立的调整能力只读。
  return (
    <>
      <Modal
        centered
        open={!review}
        title={person ? '编辑人员' : '新增人员'}
        onCancel={requestClose}
        width={680}
        onOk={() => form.submit()}
        okText="核对并保存"
        confirmLoading={busy}
        closable={!busy}
        cancelButtonProps={{ disabled: busy }}
        mask={{ closable: false }}
      >
        {(error || departmentQuery.error || roleQuery.error) && (
          <Alert
            type="error"
            title={error ?? departmentQuery.error?.message ?? roleQuery.error?.message}
            showIcon
            className="form-alert"
          />
        )}
        <Form
          name="person-editor"
          form={form}
          layout="vertical"
          onFinish={preview}
          disabled={busy}
          onValuesChange={() => setError(null)}
          scrollToFirstError={{ focus: true }}
          initialValues={{
            ...person,
            primaryDepartmentId: person?.primaryDepartment?.id,
            secondaryDepartmentIds: person?.secondaryDepartments.map((item) => item.id) ?? [],
            roleIds: [],
            initialPassword: import.meta.env.VITE_DEV_INITIAL_PASSWORD ?? '',
          }}
        >
          <div className="form-grid">
            <Form.Item
              name="displayName"
              label="姓名"
              rules={[{ required: true, whitespace: true, message: '请输入姓名' }, { max: 80 }]}
            >
              <Input autoFocus />
            </Form.Item>
            <Form.Item
              name="username"
              label="登录名"
              rules={[{ required: true, whitespace: true, message: '请输入登录名' }, { max: 80 }]}
            >
              <Input disabled={Boolean(person)} autoComplete="off" />
            </Form.Item>
            {!person && (
              <Form.Item
                className="form-field-wide"
                name="initialPassword"
                label="初始密码"
                extra="首次登录需修改初始密码。"
                rules={[
                  { required: true, message: '请输入初始密码' },
                  {
                    pattern: /^(?=.*[A-Za-z])(?=.*[0-9]).{8,32}$/u,
                    message: '密码需为 8–32 位，包含字母和数字',
                  },
                ]}
              >
                <Input.Password autoComplete="new-password" />
              </Form.Item>
            )}
            <Form.Item
              name="primaryDepartmentId"
              label="主部门"
              rules={[{ required: true, message: '请选择主部门' }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                disabled={Boolean(person) && !allowed(session, 'users.transfer')}
                options={options.map((item) => ({
                  value: item.id,
                  label: item.name,
                  disabled: item.selectable === false,
                }))}
              />
            </Form.Item>
            <Form.Item name="secondaryDepartmentIds" label="兼任部门">
              <Select
                mode="multiple"
                optionFilterProp="label"
                disabled={Boolean(person) && !allowed(session, 'users.transfer')}
                options={options.map((item) => ({
                  value: item.id,
                  label: item.name,
                  disabled: item.selectable === false,
                }))}
              />
            </Form.Item>
            {!person && allowed(session, 'users.assign') && (
              <Form.Item className="form-field-wide" name="roleIds" label="初始个人角色">
                <Select
                  mode="multiple"
                  optionFilterProp="label"
                  options={roleQuery.data?.map((item) => ({
                    value: item.id,
                    label: item.name,
                    disabled: !item.selectable,
                  }))}
                />
              </Form.Item>
            )}
            <Form.Item name="phone" label="手机号" rules={[{ max: 32 }]}>
              <Input autoComplete="tel" />
            </Form.Item>
            <Form.Item
              name="email"
              label="邮箱"
              rules={[{ type: 'email', message: '邮箱格式不正确' }, { max: 254 }]}
            >
              <Input autoComplete="email" />
            </Form.Item>
          </div>
          <Form.Item name="managementNote" label="备注" rules={[{ max: 300 }]}>
            <Input.TextArea rows={3} showCount maxLength={300} />
          </Form.Item>
        </Form>
      </Modal>
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </>
  );
}

/**
 * 普通角色直接勾选保存，受保护分配保留只读，已有停用普通角色可以移出。
 */
export function RoleAssignmentDialog({ person, close }: { person: Person; close: () => void }) {
  useReturnFocus();

  const [search, setSearch] = useState('');

  const roles = useQuery({
    queryKey: ['role-options', person.id, search],
    queryFn: () =>
      api<Role[]>(
        `/iam/role-options?${queryString({ purpose: 'USER_ASSIGN', userId: person.id, q: search })}`,
      ),
  });

  const [selected, setSelected] = useState(
    person.roles.map((role) => ({ roleId: role.id, version: role.version })),
  );

  const [busy, setBusy] = useState(false);

  const [error, setError] = useState<string | null>(null);

  const [command] = useState(() => crypto.randomUUID());

  const cache = useQueryClient();

  const { message } = App.useApp();

  /**
   * 搜索只改变可见选项，保留未显示的勾选及其版本，避免搜索后无意取消其他角色。
   */
  function selectRoles(values: string[]) {
    const visible = new Set(roles.data?.map((role) => role.id));
    const retained = selected.filter((role) => !visible.has(role.roleId));
    const checked = values.map((id) => ({
      roleId: id,
      version: roles.data!.find((role) => role.id === id)!.version,
    }));
    setSelected([...retained, ...checked]);
  }

  /**
   * 完整最终分配携带每个角色版本，回包后刷新原人员。
   */
  async function save() {
    // 当前选项仍在核验或读取失败时不提交旧版本，保留勾选供重试。
    if (busy || roles.isFetching || roles.error || !roles.data) return;
    setBusy(true);
    try {
      const refs = selected.map((ref) => ({
        roleId: ref.roleId,
        version: roles.data?.find((role) => role.id === ref.roleId)?.version ?? ref.version,
      }));
      await mutation(
        `/iam/users/${person.id}/roles`,
        'PUT',
        { expectedVersion: person.version, roles: refs },
        'users.assign',
        command,
      );
      await cache.invalidateQueries();
      message.success('角色分配已保存');
      close();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '保存未完成');
    } finally {
      setBusy(false);
    }
  }

  // 角色选择不展开无关功能明细，不增加额外原因或预览步骤。
  return (
    <Modal
      centered
      open
      title="分配角色"
      className="operation-dialog"
      width={520}
      onCancel={() => {
        if (!busy) close();
      }}
      onOk={save}
      okText="保存"
      confirmLoading={busy}
      closable={!busy}
      cancelButtonProps={{ disabled: busy }}
      okButtonProps={{ disabled: !roles.data || roles.isFetching || Boolean(roles.error) }}
    >
      {/* 与其他短任务共用对象摘要，角色选择仍直接保存，不增加核对步骤。 */}
      <OperationContext icon={<UserOutlined />} title={person.displayName}>
        <Status value={person.displayStatus} />
        <span>{person.username}</span>
      </OperationContext>
      {(error || roles.error) && (
        <Alert
          title={error ?? roles.error?.message}
          type="error"
          showIcon
          className="form-alert"
          action={roles.error && <Button onClick={() => roles.refetch()}>重试</Button>}
        />
      )}
      <Input.Search
        disabled={busy}
        aria-label="搜索个人角色"
        placeholder="搜索角色"
        allowClear
        onSearch={setSearch}
        className="form-alert"
      />
      {search && (
        <p className="role-selection-count" aria-live="polite">
          已选 {selected.length} 项
        </p>
      )}
      {/* 角色选项直接可见，多选或清空后直接保存，不增加功能明细和核对步骤。 */}
      <Spin spinning={roles.isFetching}>
        <div className="role-assignment-body">
          {roles.data?.length === 0 && !roles.error ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={search ? '没有匹配的角色' : '暂无可分配角色'}
            />
          ) : (
            <Checkbox.Group
              aria-label="个人角色"
              disabled={busy || roles.isFetching || Boolean(roles.error)}
              className="role-assignment-options"
              value={selected.map((role) => role.roleId)}
              onChange={(values) => selectRoles(values as string[])}
              options={roles.data?.map((role) => ({
                value: role.id,
                label: role.name + (role.lifecycle === 'ENABLED' ? '' : '（停用）'),
                disabled:
                  role.containsProtected ||
                  (!role.selectable && !(role.lifecycle === 'DISABLED' && role.selected)),
              }))}
            />
          )}
        </div>
      </Spin>
    </Modal>
  );
}

/**
 * 人员危险操作、调岗和受保护分配都通过服务端影响核对提交。
 */
export function UserActionDialog({
  person,
  action,
  close,
}: {
  person: Person;
  action: string;
  close: () => void;
}) {
  useReturnFocus();

  const session = useSession();

  const [form] = Form.useForm();

  const [review, setReview] = useState<ReviewSubmission | null>(null);

  const [error, setError] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);

  const requestClose = useFormClose(form, close, busy);

  const needsMembership = action === 'transfer' || action === 'enable';

  const departments = useQuery({
    queryKey: ['department-options', action, person.id],
    queryFn: () =>
      api<Department[]>(
        `/iam/department-options?purpose=${action === 'enable' ? 'USER_ENABLE' : 'USER_TRANSFER'}`,
      ),
    enabled: needsMembership,
  });

  const roles = useQuery({
    queryKey: ['protected-role-options', person.id, action],
    queryFn: () =>
      action === 'protected-roles'
        ? api<Page<Role>>('/iam/roles?pageSize=100').then((page) =>
            page.items.filter((role) => role.lifecycle !== 'ARCHIVED'),
          )
        : api<Role[]>(`/iam/role-options?purpose=USER_ENABLE&userId=${person.id}`),
    enabled: action === 'enable' || action === 'protected-roles',
  });

  const actionNames: Record<string, string> = {
    transfer: '调整归属',
    disable: '停用人员',
    enable: '复核并恢复人员',
    archive: '归档人员',
    'password-reset': '重置密码',
    'protected-roles': '受保护角色分配',
    recover: '受保护权限恢复',
  };

  // 归属或角色仍在读取时暂停预览，防止使用旧版本或未回填的选择。
  const optionsUnavailable =
    departments.isFetching || roles.isFetching || Boolean(departments.error || roles.error);

  // 标题表达任务，对象名称、状态和必要后果在摘要中分层呈现。
  const targetContext = (
    <OperationContext
      icon={<UserOutlined />}
      title={person.displayName}
      warning={
        action === 'disable'
          ? '停用后无法登录，已有会话将失效。'
          : action === 'archive'
            ? '归档后仅可回看，账号无法恢复使用。'
            : action === 'password-reset'
              ? '重置后现有会话失效，下次登录须先修改密码。'
              : undefined
      }
    >
      <Status value={person.displayStatus} />
      <span>{person.username}</span>
    </OperationContext>
  );
  const reasonLabel =
    action === 'disable'
      ? '停用原因'
      : action === 'archive'
        ? '归档原因'
        : action === 'enable'
          ? '恢复原因'
          : action === 'password-reset'
            ? '重置原因'
            : '操作原因';

  /**
   * 为每个动作构造明确提案，密码仅加入正式写入请求。
   */
  async function prepare(values: {
    reason: string;
    initialPassword?: string;
    primaryDepartmentId: string;
    secondaryDepartmentIds: string[];
    roleIds: string[];
    reviewedAccess: boolean;
    sourceAuditId: string;
  }) {
    // 查询失败或仍在读取时，Enter 也不能绕过禁用的预览按钮。
    if (busy || optionsUnavailable) return;
    setBusy(true);
    setError(null);
    try {
      const base = { expectedVersion: person.version };

      const reason = values.reason.trim();

      const membership = {
        primaryDepartmentId: values.primaryDepartmentId,
        secondaryDepartmentIds: values.secondaryDepartmentIds ?? [],
      };

      const refs = (values.roleIds ?? []).map((id) => ({
        roleId: id,
        version: roles.data!.find((role) => role.id === id)!.version,
      }));

      const actionCode =
        action === 'password-reset'
          ? 'RESET_PASSWORD'
          : action === 'protected-roles'
            ? 'PROTECTED_ROLES'
            : action.toUpperCase();

      const proposal =
        action === 'transfer'
          ? { membership, reason }
          : action === 'enable'
            ? { membership, roles: refs, reviewedAccess: values.reviewedAccess, reason }
            : action === 'password-reset'
              ? { reset: true, reason }
              : action === 'protected-roles'
                ? { roles: refs, reason }
                : { reason };

      const result = await api<Preview>(
        `/iam/users/${person.id}/${action === 'recover' ? 'recovery-preview' : 'impact-preview'}`,
        {
          method: 'POST',
          body:
            action === 'recover'
              ? { ...base, sourceAuditId: values.sourceAuditId, reason }
              : { ...base, action: actionCode, proposal },
        },
      );

      const body =
        action === 'password-reset'
          ? { ...base, reason, initialPassword: values.initialPassword }
          : action === 'recover'
            ? { ...base, sourceAuditId: values.sourceAuditId, reason }
            : { ...base, ...proposal };

      const operation =
        action === 'transfer'
          ? 'users.transfer'
          : action === 'protected-roles'
            ? 'users.assign'
            : action === 'password-reset'
              ? 'users.password'
              : `users.${action}`;
      setReview({
        title: `${actionNames[action]} · 核对影响`,
        context: targetContext,
        confirmText: `确认${actionNames[action]}`,
        danger: ['disable', 'archive', 'password-reset'].includes(action),
        preview: result,
        path: `/iam/users/${person.id}/${action === 'transfer' ? 'memberships' : action}`,
        method: action === 'transfer' || action === 'protected-roles' ? 'PUT' : 'POST',
        operation,
        body,
        done: close,
      });
    } catch (failure) {
      // 保留本次动作的输入并关联可识别字段，不将拒绝误当成提交成功。
      setError(applyFormError(form, failure));
    } finally {
      setBusy(false);
    }
  }

  // 只展示本次动作必需字段，危险后果由服务端核对结果确认。
  return (
    <>
      <Modal
        centered
        open={!review}
        title={actionNames[action]}
        className="operation-dialog"
        width={560}
        onCancel={requestClose}
        onOk={() => form.submit()}
        okText="核对影响"
        confirmLoading={busy}
        okButtonProps={{ disabled: optionsUnavailable }}
        closable={!busy}
        cancelButtonProps={{ disabled: busy }}
        mask={{ closable: false }}
      >
        {(error || departments.error || roles.error) && (
          <Alert
            type="error"
            title={error ?? departments.error?.message ?? roles.error?.message}
            action={
              (departments.error || roles.error) && (
                <Button
                  onClick={() => {
                    // 只重试失败的选项读取，保留当前人员和已填原因。
                    if (departments.error) void departments.refetch();
                    if (roles.error) void roles.refetch();
                  }}
                >
                  重试
                </Button>
              )
            }
            showIcon
            className="form-alert"
          />
        )}
        {targetContext}
        <Form
          name="person-action"
          form={form}
          layout="vertical"
          onFinish={prepare}
          disabled={busy || optionsUnavailable}
          onValuesChange={() => setError(null)}
          scrollToFirstError={{ focus: true }}
          initialValues={{
            primaryDepartmentId: person.primaryDepartment?.id,
            secondaryDepartmentIds: person.secondaryDepartments.map((item) => item.id),
            roleIds: person.roles.map((role) => role.id),
            initialPassword: import.meta.env.VITE_DEV_INITIAL_PASSWORD ?? '',
          }}
        >
          {needsMembership && (
            <>
              <Form.Item
                name="primaryDepartmentId"
                label="主部门"
                rules={[{ required: true, message: '请选择主部门' }]}
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  disabled={action === 'enable' && !allowed(session, 'users.transfer')}
                  options={departments.data?.map((item) => ({
                    value: item.id,
                    label: item.name,
                    disabled: !item.selectable,
                  }))}
                />
              </Form.Item>
              <Form.Item name="secondaryDepartmentIds" label="兼任部门">
                <Select
                  mode="multiple"
                  optionFilterProp="label"
                  disabled={action === 'enable' && !allowed(session, 'users.transfer')}
                  options={departments.data?.map((item) => ({
                    value: item.id,
                    label: item.name,
                    disabled: !item.selectable,
                  }))}
                />
              </Form.Item>
            </>
          )}
          {(action === 'enable' || action === 'protected-roles') && (
            <Form.Item name="roleIds" label="个人角色">
              <Select
                mode="multiple"
                optionFilterProp="label"
                disabled={action === 'enable' && !allowed(session, 'users.assign')}
                options={roles.data?.map((role) => ({
                  value: role.id,
                  label: role.name,
                  disabled:
                    action === 'protected-roles'
                      ? role.lifecycle !== 'ENABLED' &&
                        !person.roles.some((current) => current.id === role.id)
                      : !role.selectable,
                }))}
              />
            </Form.Item>
          )}
          {action === 'enable' && (
            <Form.Item
              name="reviewedAccess"
              valuePropName="checked"
              rules={[
                {
                  validator: (_, value) =>
                    value ? Promise.resolve() : Promise.reject(new Error('请完成恢复前复核')),
                },
              ]}
            >
              <Checkbox>已复核归属、角色及保留配置，旧任务和已结束来源不恢复</Checkbox>
            </Form.Item>
          )}
          {action === 'password-reset' && (
            <Form.Item
              name="initialPassword"
              label="新初始密码"
              rules={[
                { required: true, message: '请输入新初始密码' },
                {
                  pattern: /^(?=.*[A-Za-z])(?=.*[0-9]).{8,32}$/u,
                  message: '密码需为 8–32 位，包含字母和数字',
                },
              ]}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
          )}
          {action === 'recover' && (
            <Form.Item
              name="sourceAuditId"
              label="原管理分配的审计编号"
              rules={[
                { required: true, message: '请选择真实关联该人员的历史依据' },
                { pattern: /^[1-9][0-9]*$/, message: '请输入有效审计编号' },
              ]}
            >
              <Input />
            </Form.Item>
          )}
          <Form.Item
            name="reason"
            label={reasonLabel}
            rules={[
              { required: true, whitespace: true, message: `请填写${reasonLabel}` },
              { max: 500 },
            ]}
          >
            <Input.TextArea
              autoFocus={['disable', 'archive'].includes(action)}
              autoSize={{ minRows: 3, maxRows: 5 }}
              maxLength={500}
            />
          </Form.Item>
        </Form>
      </Modal>
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </>
  );
}
