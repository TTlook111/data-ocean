import { RecordTable } from '../../shared/RecordTable';
import { ActionMenuButton } from '../../shared/ActionMenuButton';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Tabs,
  Tag,
} from 'antd';
import { PlusOutlined, SafetyCertificateOutlined, SearchOutlined } from '@ant-design/icons';
import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useBlocker, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { useSession, allowed } from '../../shared/session';
import { displayTime, readErrorMessage, ReadState, Status } from '../../shared/Records';
import { ReviewDialog } from '../../shared/ReviewDialog';
import { useFormClose } from '../../shared/useFormClose';
import { useReturnFocus } from '../../shared/useReturnFocus';
import { applyFormError } from '../../shared/formErrors';
import { OperationContext } from '../../shared/OperationContext';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import type { CapabilityPage, Page, Person, Preview, Role, RoleForm } from './types';

/**
 * 从角色成员打开人员详情时才加载详情及关联的数据访问模块。
 */
const PersonDetailsDrawer = lazy(() =>
  import('./UsersPage').then(({ PersonDetailsDrawer }) => ({ default: PersonDetailsDrawer })),
);

/**
 * 普通和受保护配置共用显式目录选择，动作自动补齐所属页面权。
 */
function PermissionPicker({
  pages,
  value = [],
  onChange,
  protectedMode = false,
  disabled = false,
  grantLimit = false,
  readOnly = false,
  footer,
}: {
  pages: CapabilityPage[];
  value?: string[];
  onChange?: (value: string[]) => void;
  protectedMode?: boolean;
  disabled?: boolean;
  grantLimit?: boolean;
  readOnly?: boolean;
  /**
   * 独立角色编辑的操作区，固定在右侧用途清单下方。
   */
  footer?: ReactNode;
}) {
  const [keyword, setKeyword] = useState('');

  const [activeId, setActiveId] = useState(
    pages.find((page) => page.operations.some((operation) => value.includes(operation.code)))
      ?.pageCode,
  );

  const filtered = pages.filter(
    (page) =>
      (!readOnly || page.operations.some((operation) => value.includes(operation.code))) &&
      `${page.name} ${page.operations.map((operation) => `${operation.name} ${operation.purpose}`).join(' ')}`.includes(
        keyword.trim(),
      ),
  );

  const active =
    filtered.find((page) => page.pageCode === activeId) ??
    filtered.find((page) => page.operations.some((operation) => value.includes(operation.code))) ??
    filtered[0];

  /**
   * 原型中的页面分组只影响浏览，不参与服务端授权判断。
   */
  function group(page: CapabilityPage) {
    const code = page.pageCode.replace(/\.view$/, '');
    if (
      [
        'users',
        'departments',
        'roles',
        'output-rules',
        'access-requests',
        'grants',
        'audit',
      ].includes(code)
    )
      return '人员与权限';
    if (['sources-dashboard', 'sources', 'collections', 'catalog', 'business-notes'].includes(code))
      return '数据源接入';
    if (['governance-dashboard', 'governance'].includes(code)) return '数据治理';
    if (['knowledge-dashboard', 'knowledge', 'test-cases', 'releases', 'versions'].includes(code))
      return '知识库与发布';
    if (['feedback-dashboard', 'feedback'].includes(code)) return '反馈处理';
    if (['models', 'diagnostics'].includes(code)) return '系统配置';
    if (['account-info', 'my-requests', 'my-access'].includes(code)) return '个人管理';
    return '总览';
  }
  /**
   * 维护完整最终集合，取消页面权时同时取消所属动作。
   */
  function toggle(code: string, checked: boolean) {
    const selected = new Set(value);

    const permission = pages.flatMap((page) => page.operations).find((item) => item.code === code)!;
    if (checked) {
      selected.add(code);
      if (permission.pagePermissionCode && !grantLimit) {
        selected.add(permission.pagePermissionCode);
      }
    } else {
      selected.delete(code);
      if (!permission.pagePermissionCode && !grantLimit) {
        pages
          .flatMap((page) => page.operations)
          .filter((item) => item.pagePermissionCode === code)
          .forEach((item) => selected.delete(item.code));
      }
    }
    onChange?.([...selected].sort());
  }

  // 原型功能浏览器保持左侧页面目录和右侧用途清单，不改为整页复选框网格。
  return (
    <div className="permission-browser">
      <div className="permission-pages-column">
        <div className="permission-search">
          <Input
            aria-label="搜索页面或操作"
            placeholder="搜索页面或操作"
            prefix={<SearchOutlined />}
            allowClear
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
        </div>
        <nav className="permission-pages" aria-label="功能页面">
          {[...new Set(filtered.map(group))].map((name) => (
            <section key={name}>
              <h3>{name}</h3>
              {filtered
                .filter((page) => group(page) === name)
                .map((page) => (
                  <button
                    type="button"
                    key={page.pageCode}
                    className={`permission-page${active?.pageCode === page.pageCode ? ' is-current' : ''}`}
                    aria-pressed={active?.pageCode === page.pageCode}
                    onClick={() => setActiveId(page.pageCode)}
                  >
                    <span>{page.name}</span>
                    <small>
                      {page.operations.filter((operation) => value.includes(operation.code)).length}{' '}
                      / {page.operations.length}
                    </small>
                  </button>
                ))}
            </section>
          ))}
        </nav>
      </div>
      {/* 右栏将功能正文滚动与角色级操作分开，左栏目录保留完整高度。 */}
      <div className="permission-detail-column">
        <section
          className="permission-content"
          aria-label={active ? `${active.name}的功能` : '功能选择结果'}
        >
          {active ? (
            <>
              <div className="permission-page-heading">
                <h3>{active.name}</h3>
                <span>
                  {active.operations.filter((operation) => value.includes(operation.code)).length} /{' '}
                  {active.operations.length} 项
                </span>
              </div>
              <ul className="permission-operations">
                {active.operations
                  .filter((operation) => !readOnly || value.includes(operation.code))
                  .map((operation) => (
                    <li key={operation.code}>
                      {readOnly ? (
                        <strong>{operation.name}</strong>
                      ) : (
                        <Checkbox
                          checked={value.includes(operation.code)}
                          disabled={
                            disabled ||
                            (!protectedMode &&
                              (operation.protected ||
                                (!operation.pagePermissionCode &&
                                  active.operations.some(
                                    (item) => item.protected && value.includes(item.code),
                                  ))))
                          }
                          onChange={(event) => toggle(operation.code, event.target.checked)}
                        >
                          {operation.name}
                        </Checkbox>
                      )}
                      {operation.protected && <Tag className="role-protected">受保护</Tag>}
                      <p>{operation.purpose}</p>
                    </li>
                  ))}
              </ul>
              {!protectedMode && active.operations.some((operation) => operation.protected) && (
                <p className="permission-hint">受保护管理能力需单独核验。</p>
              )}
            </>
          ) : (
            <div className="permission-empty">
              {readOnly && !keyword ? '暂无已配置功能' : '没有匹配的页面或操作'}
            </div>
          )}
        </section>
        {footer}
      </div>
    </div>
  );
}

/**
 * 列表入口保留筛选与页码，配置在既定独立页面完成。
 */
export function RolesPage() {
  const session = useSession();

  const { message } = App.useApp();

  const [params, setParams] = useSearchParams();

  const [form] = Form.useForm();

  const name = params.get('name') ?? undefined;
  const lifecycle = params.get('lifecycle') ?? undefined;

  useEffect(() => {
    // 地址恢复筛选时同步可见输入，分页变化继续保留未提交的输入。
    form.setFieldsValue({ name, lifecycle });
  }, [form, name, lifecycle]);

  const navigate = useNavigate();

  const [members, setMembers] = useState<Role | null>(null);

  const [detailTab, setDetailTab] = useState('permissions');

  const [action, setAction] = useState<{ role: Role; name: string } | null>(null);

  const records = useQuery({
    queryKey: ['roles', params.toString()],
    queryFn: () => api<Page<Role>>(`/iam/roles?${params}`),
  });

  /**
   * 清空角色条件并保留已选择的每页条数。
   */
  function resetRoles() {
    form.setFieldsValue({ name: undefined, lifecycle: undefined });
    setParams({ pageNum: '1', pageSize: params.get('pageSize') ?? '10' });
    form.focusField('name');
  }

  /**
   * 编辑或复制前取得完整角色，列表返回时恢复已应用条件。
   */
  async function configure(role?: Role, copy = false, protectedMode = false) {
    try {
      const detail = role ? await api<Role>(`/iam/roles/${role.id}`) : null;
      navigate(
        copy || !role
          ? '/iam/roles/new'
          : `/iam/roles/${role.id}/edit${protectedMode ? '?mode=protected' : allowed(session, 'roles.edit') ? '' : '?mode=view'}`,
        { state: { returnSearch: params.toString(), copy: copy ? detail : null } },
      );
    } catch (failure) {
      // 读取失败留在原查询，不创建缺少配置的编辑页。
      message.error(
        readErrorMessage(failure instanceof Error ? failure : new Error('读取角色失败')),
      );
    }
  }

  /**
   * 根据生命周期和真实显示权限提供角色操作。
   */
  function actions(role: Role, inDetail = false) {
    const extras = [
      { key: 'copy', label: '复制为新角色', code: 'roles.create', visible: true },
      {
        key: 'disable',
        label: '停用角色',
        code: 'roles.disable',
        visible: role.lifecycle === 'ENABLED',
        danger: true,
      },
      {
        key: 'enable',
        label: '启用角色',
        code: 'roles.enable',
        visible: role.lifecycle === 'DISABLED',
      },
      {
        key: 'archive',
        label: '归档角色',
        code: 'roles.archive',
        visible: role.lifecycle === 'DISABLED',
        danger: true,
      },
      {
        key: 'grant-limit',
        label: '调整授予上限',
        code: 'roles.protected',
        visible: role.lifecycle !== 'ARCHIVED',
      },
      {
        key: 'protected',
        label: '受保护功能配置',
        code: 'roles.protected',
        visible: role.lifecycle !== 'ARCHIVED',
      },
    ].filter((item) => item.visible && allowed(session, item.code));

    /**
     * 配置走独立页面，状态和上限操作在当前列表上下文核对。
     */
    function choose(key: string) {
      if (key === 'copy') void configure(role, true);
      else if (key === 'protected') void configure(role, false, true);
      else {
        // 详情底栏切换办理时先退出回看，避免两个对象浮层相互遮挡。
        if (inDetail) setMembers(null);
        setAction({ role, name: key });
      }
    }
    return (
      <Space className={inDetail ? undefined : 'row-actions'} size={8}>
        {!inDetail && role.lifecycle !== 'ARCHIVED' && allowed(session, 'roles.edit') && (
          <Button type="link" onClick={() => configure(role)}>
            编辑
          </Button>
        )}
        {extras.length === 1 ? (
          <Button
            type={inDetail ? 'default' : 'link'}
            danger={extras[0].danger}
            onClick={() => choose(extras[0].key)}
          >
            {extras[0].label}
          </Button>
        ) : (
          extras.length > 1 && (
            <ActionMenuButton
              type={inDetail ? 'default' : 'link'}
              placement={inDetail ? 'topLeft' : 'bottomLeft'}
              menu={{
                items: extras,
                onClick: ({ key }) => choose(key),
              }}
            >
              更多
            </ActionMenuButton>
          )
        )}
      </Space>
    );
  }

  // 名称和状态过滤顺序与结果表头保持一致。
  return (
    <>
      <section className="panel query-panel">
        <Form
          name="roles-query"
          form={form}
          layout="inline"
          initialValues={Object.fromEntries(params)}
          onFinish={(values) => {
            // 查询与 Enter 共用提交入口，完成后仍可继续在原字段输入。
            setParams(
              queryString({
                name: values.name,
                lifecycle: values.lifecycle,
                pageNum: 1,
                pageSize: params.get('pageSize') ?? 10,
              }),
            );
            form.focusField('name');
          }}
        >
          <Form.Item name="name">
            <Input
              aria-label="角色名称"
              placeholder="角色名称"
              prefix={<SearchOutlined />}
              allowClear
            />
          </Form.Item>
          <div className="query-tail status-tail">
            <Form.Item name="lifecycle" className="status-query">
              <Select
                aria-label="状态"
                placeholder="全部状态"
                allowClear
                options={[
                  { value: 'ENABLED', label: '启用' },
                  { value: 'DISABLED', label: '停用' },
                  { value: 'ARCHIVED', label: '已归档' },
                ]}
              />
            </Form.Item>
            <Space>
              <Button htmlType="submit">查询</Button>
              <Button onClick={resetRoles}>重置</Button>
            </Space>
          </div>
        </Form>
        {allowed(session, 'roles.create') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => configure()}>
            新增角色
          </Button>
        )}
      </section>
      <section className="panel records-panel" aria-label="角色列表">
        <RecordTable<Role>
          error={records.error}
          retry={records.refetch}
          filtered={params.has('name') || params.has('lifecycle')}
          onReset={resetRoles}
          rowKey="id"
          loading={records.isFetching}
          dataSource={records.data?.items}
          scroll={{ x: 980, y: '100%' }}
          pagination={{
            current: records.data?.pageNum ?? 1,
            pageSize: records.data?.pageSize ?? 10,
            total: records.data?.total,
            showTotal: (total) => `共 ${total} 条`,
            showSizeChanger: true,
            onChange: (pageNum, pageSize) =>
              setParams((previous) => {
                const next = new URLSearchParams(previous);
                next.set('pageNum', String(pageNum));
                next.set('pageSize', String(pageSize));
                return next;
              }),
          }}
          columns={[
            {
              title: '角色名称',
              width: 212,
              render: (_, role) => (
                <Space>
                  <Button
                    type="link"
                    className="cell-title"
                    title={role.name}
                    onClick={() => {
                      setDetailTab('permissions');
                      setMembers(role);
                    }}
                  >
                    {role.name}
                  </Button>
                  {role.containsProtected && <Tag className="role-protected">受保护</Tag>}
                </Space>
              ),
            },
            {
              title: '角色说明',
              dataIndex: 'description',
              width: 360,
              ellipsis: true,
              render: (value) => value || '—',
            },
            {
              title: '状态',
              dataIndex: 'lifecycle',
              width: 82,
              render: (value) => (
                <Status value={value} label={value === 'ENABLED' ? '启用' : undefined} />
              ),
            },
            {
              title: '已分配人员',
              className: 'record-actions-cell',
              width: 104,
              render: (_, role) => (
                <Button
                  type="link"
                  aria-label={`查看 ${role.name} 的已分配人员（${role.memberCount} 人）`}
                  onClick={() => {
                    // 人数已经是完整入口，行操作不重复提供查看成员。
                    setDetailTab('members');
                    setMembers(role);
                  }}
                >
                  {role.memberCount} 人
                </Button>
              ),
            },
            { title: '更新时间', dataIndex: 'updatedAt', width: 166, render: displayTime },
            {
              title: '操作',
              className: 'record-actions-cell',
              width: 160,
              fixed: 'right',
              render: (_, role) => actions(role),
            },
          ]}
        />
      </section>
      {members && (
        <RoleMembers
          role={members}
          actions={(role) => actions(role, true)}
          initialTab={detailTab}
          edit={() => {
            setMembers(null);
            void configure(members);
          }}
          close={() => setMembers(null)}
        />
      )}
      {action && (
        <RoleAction
          key={`${action.role.id}:${action.name}:${action.role.version}`}
          role={action.role}
          action={action.name}
          close={() => setAction(null)}
        />
      )}
    </>
  );
}

/**
 * 角色功能的独立编辑页，未保存变化离开前保留确认。
 */
export function RoleEditor() {
  const { id } = useParams();

  const [params] = useSearchParams();

  const location = useLocation();

  const navigate = useNavigate();

  const session = useSession();

  const [form] = Form.useForm<RoleForm>();

  const [review, setReview] = useState<ReviewSubmission | null>(null);

  const [error, setError] = useState<string | null>(null);

  const [dirty, setDirty] = useState(false);

  const [busy, setBusy] = useState(false);

  const initialized = useRef(false);

  const initial = useRef('');

  const dirtyRef = useRef(false);

  const selectedCodes = Form.useWatch('permissionCodes', form) ?? [];

  const state = location.state as { copy?: Role; returnSearch?: string } | null;

  const catalog = useQuery({
    queryKey: ['permissions'],
    queryFn: () => api<CapabilityPage[]>('/iam/permissions'),
  });

  const detail = useQuery({
    queryKey: ['role', id],
    queryFn: () => api<Role>(`/iam/roles/${id}`),
    enabled: Boolean(id),
  });

  const protectedMode = params.get('mode') === 'protected';

  const readOnly =
    params.get('mode') === 'view' ||
    detail.data?.lifecycle === 'ARCHIVED' ||
    !allowed(session, protectedMode ? 'roles.protected' : id ? 'roles.edit' : 'roles.create');

  const blocker = useBlocker(() => dirtyRef.current);

  const { modal } = App.useApp();

  useEffect(() => {
    // 后台自动刷新不得覆盖正在编辑的内容，只在首次取得详情时填充。
    if (initialized.current || (id && !detail.data) || !catalog.data) return;
    const source = detail.data ?? state?.copy;

    const protectedCodes = catalog.data
      .flatMap((page) => page.operations)
      .filter((item) => item.protected)
      .map((item) => item.code);
    form.setFieldsValue({
      name: state?.copy ? `${source!.name}（副本）` : (source?.name ?? ''),
      description: source?.description ?? null,
      permissionCodes:
        source?.permissionCodes.filter((code) => !state?.copy || !protectedCodes.includes(code)) ??
        [],
    });
    initial.current = JSON.stringify(form.getFieldsValue());
    initialized.current = true;
  }, [catalog.data, detail.data, form, id, state?.copy]);

  useEffect(() => {
    // 菜单、返回和浏览器历史导航都走同一个未保存确认。
    if (blocker.state !== 'blocked') return;
    const dialog = modal.confirm({
      centered: true,
      title: '有未保存的修改',
      content: '离开会丢弃本次修改。',
      okText: '放弃修改并离开',
      cancelText: '继续编辑',
      focusable: { autoFocusButton: 'cancel' },
      keyboard: true,
      onOk: () => blocker.proceed(),
      onCancel: () => blocker.reset(),
    });
    return () => dialog.destroy();
  }, [blocker, modal]);

  useEffect(() => {
    /**
     * 原生刷新和关闭窗口同样保护未提交的编辑内容。
     */
    function beforeUnload(event: BeforeUnloadEvent) {
      if (dirty) event.preventDefault();
    }
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty]);

  /**
   * 完整功能集合交给服务端核对全部成员影响。
   */
  async function prepare(values: RoleForm) {
    setBusy(true);
    setError(null);
    try {
      const proposal = {
        name: values.name.trim(),
        description: values.description?.trim() || null,
        permissionCodes: values.permissionCodes,
        ...(protectedMode ? { reason: values.reason } : {}),
      };

      const preview = await api<Preview>(
        id ? `/iam/roles/${id}/impact-preview` : '/iam/roles/impact-preview',
        {
          method: 'POST',
          body: id
            ? {
                action: protectedMode ? 'PROTECTED_PERMISSIONS' : 'UPDATE',
                expectedVersion: detail.data!.version,
                proposal,
              }
            : { proposal },
        },
      );
      setReview({
        title: id ? `核对角色：${values.name}` : '核对新增角色',
        preview,
        path: id
          ? `/iam/roles/${id}${protectedMode ? '/protected-permissions' : ''}`
          : '/iam/roles',
        method: id ? 'PUT' : 'POST',
        operation: protectedMode ? 'roles.protected' : id ? 'roles.edit' : 'roles.create',
        body: id ? { ...proposal, expectedVersion: detail.data!.version } : proposal,
        done: () => {
          dirtyRef.current = false;
          setDirty(false);
          setReview(null);
          // 新增或复制后清除旧名称和状态筛选，编辑则恢复原查询上下文。
          const context = new URLSearchParams(state?.returnSearch ?? '');
          const returnSearch = id
            ? context.toString()
            : queryString({ pageNum: 1, pageSize: context.get('pageSize') ?? 10 });
          navigate(`/iam/roles${returnSearch ? `?${returnSearch}` : ''}`);
        },
      });
    } catch (failure) {
      // 重名定位到角色名称，其他失败保留功能选择供修改或重试。
      setError(applyFormError(form, failure, { 409102: 'name' }));
    } finally {
      setBusy(false);
    }
  }

  // 功能编辑保留后台外壳，操作区位于功能权限卡片右栏底部。
  return (
    <ReadState
      error={catalog.error ?? detail.error}
      retry={() => {
        void catalog.refetch();
        if (id) void detail.refetch();
      }}
      loading={catalog.isPending || (Boolean(id) && detail.isPending)}
    >
      <section className="role-editor" aria-label="角色功能配置">
        {error && <Alert type="error" title={error} showIcon className="form-alert" />}
        <Form
          name="role-editor"
          form={form}
          layout="vertical"
          disabled={readOnly || busy}
          onFinish={prepare}
          onValuesChange={() => {
            // 修正字段后撤下上一次整体失败反馈，字段错误由 Form 自身更新。
            setError(null);
            dirtyRef.current = initial.current !== JSON.stringify(form.getFieldsValue());
            setDirty(dirtyRef.current);
          }}
          scrollToFirstError={{ focus: true }}
        >
          <section className="panel role-editor-info">
            <Form.Item
              name="name"
              label="角色名称"
              rules={[{ required: true, whitespace: true, message: '请输入角色名称' }, { max: 80 }]}
            >
              <Input aria-label="角色名称" placeholder="填写角色名称" autoFocus />
            </Form.Item>
            <Form.Item name="description" label="角色说明" rules={[{ max: 300 }]}>
              <Input aria-label="角色说明" placeholder="说明角色的使用职责" maxLength={300} />
            </Form.Item>
          </section>
          <section className="role-permission-card">
            <header>
              <h2>功能权限{protectedMode && <Tag>受保护配置</Tag>}</h2>
              <span>
                {dirty && <small className="role-unsaved">未保存</small>}已选择{' '}
                {selectedCodes.length} 项
              </span>
            </header>
            <Form.Item
              className="permission-form-item"
              name="permissionCodes"
              rules={[
                { type: 'array', min: 1, required: true, message: '请至少选择一个页面或功能' },
              ]}
            >
              <PermissionPicker
                pages={catalog.data ?? []}
                protectedMode={protectedMode}
                disabled={readOnly}
                readOnly={readOnly}
                footer={
                  // 角色级操作固定在右侧清单下方，不占用页面外层底栏。
                  <div className="permission-editor-footer">
                    {/* 受保护原因紧邻核对操作，继续参与同一角色表单的校验。 */}
                    {protectedMode && (
                      <Form.Item
                        name="reason"
                        label="操作原因"
                        rules={[
                          { required: true, whitespace: true, message: '请填写受保护调整原因' },
                          { max: 500 },
                        ]}
                      >
                        <Input.TextArea rows={2} />
                      </Form.Item>
                    )}
                    {/* 只读或提交期间仍可取消，避免继承表单禁用状态而无法返回。 */}
                    <div className="permission-editor-actions">
                      <Button
                        disabled={false}
                        onClick={() =>
                          navigate(
                            `/iam/roles${state?.returnSearch ? `?${state.returnSearch}` : ''}`,
                          )
                        }
                      >
                        取消
                      </Button>
                      {!readOnly && (
                        <Button
                          type="primary"
                          loading={busy}
                          disabled={Boolean(id) && !dirty}
                          onClick={() => form.submit()}
                        >
                          核对变更
                        </Button>
                      )}
                    </div>
                  </div>
                }
              />
            </Form.Item>
          </section>
        </Form>
      </section>
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </ReadState>
  );
}

/**
 * 角色详情保留原列表上下文，成员按姓名、登录名和状态查询，并按权限回看人员。
 */
function RoleMembers({
  role,
  initialTab,
  actions,
  edit,
  close,
}: {
  role: Role;
  initialTab: string;
  /**
   * 复用列表动作资格，在详情底栏承接当前角色的低频办理。
   */
  actions: (role: Role) => React.ReactNode;
  edit: () => void;
  close: () => void;
}) {
  useReturnFocus();

  const [query, setQuery] = useState({
    displayName: '',
    username: '',
    displayStatus: '',
    pageNum: 1,
    pageSize: 10,
  });

  const [form] = Form.useForm();

  const [personId, setPersonId] = useState<string | null>(null);

  /**
   * 角色成员按姓名、登录名和状态查询，重置保留当前每页条数。
   */
  function resetMembers() {
    form.setFieldsValue({ displayName: undefined, username: undefined, displayStatus: undefined });
    setQuery((previous) => ({
      displayName: '',
      username: '',
      displayStatus: '',
      pageNum: 1,
      pageSize: previous.pageSize,
    }));
    form.focusField('displayName');
  }

  const records = useQuery({
    queryKey: ['role-members', role.id, query],
    queryFn: () => api<Page<Person>>(`/iam/roles/${role.id}/members?${queryString(query)}`),
  });

  const detail = useQuery({
    queryKey: ['role', role.id],
    queryFn: () => api<Role>(`/iam/roles/${role.id}`),
  });

  const catalog = useQuery({
    queryKey: ['permissions'],
    queryFn: () => api<CapabilityPage[]>('/iam/permissions'),
  });

  const session = useSession();

  const currentRole = detail.data ?? role;

  // 角色详情沿用原型右侧抽屉，管理动作集中在底栏，正文负责资料与页签回看。
  return (
    <>
      <Drawer
        open
        title="角色详情"
        onClose={close}
        size="min(920px, calc(100vw - 80px))"
        closable={{ placement: 'end' }}
        className="role-details-drawer"
        footer={
          <div className="detail-footer">
            <span>{!detail.error && detail.data && actions(currentRole)}</span>
            <Space>
              <Button onClick={close}>关闭</Button>
              {!detail.error &&
                detail.data &&
                currentRole.lifecycle !== 'ARCHIVED' &&
                allowed(session, 'roles.edit') && (
                  <Button type="primary" onClick={edit}>
                    编辑角色
                  </Button>
                )}
            </Space>
          </div>
        }
      >
        <ReadState error={detail.error} retry={detail.refetch} loading={detail.isPending}>
          <div className="detail-heading">
            <h2>{currentRole.name}</h2>
            <Status
              value={currentRole.lifecycle}
              label={currentRole.lifecycle === 'ENABLED' ? '启用' : undefined}
            />
          </div>
          <p className="role-description">{currentRole.description || '无角色说明'}</p>
          <Descriptions
            className="role-detail-meta"
            layout="vertical"
            colon={false}
            column={2}
            items={[
              { key: 'members', label: '已分配人员', children: `${currentRole.memberCount} 人` },
              { key: 'updated', label: '更新时间', children: displayTime(currentRole.updatedAt) },
            ]}
          />
          <Tabs
            defaultActiveKey={initialTab}
            items={[
              {
                key: 'permissions',
                label: '功能权限',
                children: (
                  <ReadState
                    error={catalog.error ?? detail.error}
                    retry={() => {
                      void catalog.refetch();
                      void detail.refetch();
                    }}
                    loading={catalog.isPending || detail.isPending}
                  >
                    <div className="role-detail-permissions">
                      <PermissionPicker
                        pages={catalog.data ?? []}
                        value={detail.data?.permissionCodes ?? []}
                        readOnly
                      />
                    </div>
                  </ReadState>
                ),
              },
              {
                key: 'members',
                label: '已分配人员',
                children: (
                  <div className="records-panel role-member-results">
                    <Form
                      name="role-members-query"
                      form={form}
                      layout="inline"
                      className="member-query"
                      onFinish={(values) => {
                        setQuery((previous) => ({ ...previous, ...values, pageNum: 1 }));
                        form.focusField('displayName');
                      }}
                    >
                      <Form.Item name="displayName">
                        <Input aria-label="姓名" placeholder="姓名" allowClear />
                      </Form.Item>
                      <Form.Item name="username">
                        <Input aria-label="登录名" placeholder="登录名" allowClear />
                      </Form.Item>
                      {/* 与部门成员共用状态及查询的成组换行，避免抽屉内按钮孤立。 */}
                      <div className="member-query-tail">
                        <Form.Item name="displayStatus">
                          <Select
                            aria-label="账号状态"
                            placeholder="全部状态"
                            allowClear
                            options={[
                              { value: 'ENABLED', label: '正常' },
                              { value: 'DISABLED', label: '停用' },
                              { value: 'ARCHIVED', label: '已归档' },
                              { value: 'PENDING_FIRST_LOGIN', label: '待首次登录' },
                              { value: 'PASSWORD_CHANGE_REQUIRED', label: '待改密' },
                            ]}
                          />
                        </Form.Item>
                        <Space>
                          <Button htmlType="submit">查询</Button>
                          <Button onClick={resetMembers}>重置</Button>
                        </Space>
                      </div>
                    </Form>
                    <RecordTable<Person>
                      error={records.error}
                      retry={records.refetch}
                      filtered={Boolean(query.displayName || query.username || query.displayStatus)}
                      onReset={resetMembers}
                      rowKey="id"
                      loading={records.isFetching}
                      dataSource={records.data?.items}
                      scroll={{ x: 600, y: 240 }}
                      pagination={{
                        current: records.data?.pageNum,
                        total: records.data?.total,
                        pageSize: query.pageSize,
                        showTotal: (total) => `共 ${total} 条`,
                        onChange: (pageNum, pageSize) =>
                          setQuery((previous) => ({ ...previous, pageNum, pageSize })),
                      }}
                      columns={[
                        {
                          title: '人员',
                          ellipsis: true,
                          render: (_, person) => (
                            <div title={person.displayName}>
                              {allowed(session, 'users.view') ? (
                                <Button
                                  type="link"
                                  className="cell-title"
                                  onClick={() => setPersonId(person.id)}
                                >
                                  {person.displayName}
                                </Button>
                              ) : (
                                person.displayName
                              )}
                              <span className="table-secondary" title={person.username}>
                                {person.username}
                              </span>
                            </div>
                          ),
                        },
                        {
                          title: '部门',
                          ellipsis: true,
                          render: (_, person) => person.primaryDepartment?.name ?? '—',
                        },
                        {
                          title: '账号状态',
                          width: 140,
                          dataIndex: 'displayStatus',
                          render: (value) => <Status value={value} />,
                        },
                      ]}
                    />
                  </div>
                ),
              },
            ]}
          />
        </ReadState>
      </Drawer>
      {/* 详情首次加载时保留角色抽屉及成员列表的位置。 */}
      <Suspense fallback={null}>
        {personId && <PersonDetailsDrawer id={personId} close={() => setPersonId(null)} />}
      </Suspense>
    </>
  );
}

/**
 * 角色状态和上限调整留在列表上下文，经真实影响核对后提交。
 */
function RoleAction({ role, action, close }: { role: Role; action: string; close: () => void }) {
  useReturnFocus();

  const [form] = Form.useForm();

  const [review, setReview] = useState<ReviewSubmission | null>(null);

  const [error, setError] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);

  const requestClose = useFormClose(form, close, busy);

  const grant = useQuery({
    queryKey: ['grant-limit', role.id],
    queryFn: () =>
      api<{ version: number; grantablePermissionCodes: string[] }>(
        `/iam/roles/${role.id}/grant-limit`,
      ),
    enabled: action === 'grant-limit',
  });

  const catalog = useQuery({
    queryKey: ['permissions'],
    queryFn: () => api<CapabilityPage[]>('/iam/permissions'),
    enabled: action === 'grant-limit',
  });

  const initialized = useRef(false);

  // 上限尚未完整读取时不能把空白表单解释为清空已有配置。
  const grantReady =
    action !== 'grant-limit' ||
    Boolean(
      grant.data &&
      catalog.data &&
      !grant.isFetching &&
      !catalog.isFetching &&
      !grant.error &&
      !catalog.error,
    );

  useEffect(() => {
    // 只在首次取得上限时填充，刷新不覆盖未保存选择。
    if (grant.data && !initialized.current) {
      form.setFieldsValue({ grantablePermissionCodes: grant.data.grantablePermissionCodes });
      form.setFields([{ name: 'grantablePermissionCodes', touched: false }]);
      initialized.current = true;
    }
  }, [form, grant.data]);

  const title =
    action === 'grant-limit'
      ? '调整授予上限'
      : action === 'disable'
        ? '停用角色'
        : action === 'enable'
          ? '启用角色'
          : '归档角色';

  // 标题表达当前任务，对象名称与成员事实在独立摘要中完整展示。
  const targetContext = (
    <OperationContext
      icon={<SafetyCertificateOutlined />}
      title={role.name}
      warning={
        action === 'disable'
          ? '停用后暂停该角色的功能权限，保留人员分配。'
          : action === 'archive'
            ? '归档后仅可回看，不能再分配或编辑。'
            : undefined
      }
    >
      <Status value={role.lifecycle} label={role.lifecycle === 'ENABLED' ? '启用' : undefined} />
      <span>已分配 {role.memberCount} 人</span>
    </OperationContext>
  );

  const reasonLabel =
    action === 'disable'
      ? '停用原因'
      : action === 'enable'
        ? '启用原因'
        : action === 'archive'
          ? '归档原因'
          : '变更原因';

  /**
   * 状态和上限使用各自 DTO，核对后保留同一完整提案。
   */
  async function prepare(values: { reason: string; grantablePermissionCodes?: string[] }) {
    // 键盘提交与按钮遵循同一就绪边界，避免预览尚未回填的选择。
    if (!grantReady || busy) return;
    setBusy(true);
    try {
      const proposal = {
        reason: values.reason,
        ...(action === 'grant-limit'
          ? { grantablePermissionCodes: values.grantablePermissionCodes ?? [] }
          : {}),
      };

      const version = grant.data?.version ?? role.version;

      const preview = await api<Preview>(`/iam/roles/${role.id}/impact-preview`, {
        method: 'POST',
        body: {
          action: action === 'grant-limit' ? 'GRANT_LIMIT' : action.toUpperCase(),
          expectedVersion: version,
          proposal,
        },
      });
      setReview({
        title: `${title} · 核对影响`,
        context: targetContext,
        confirmText: `确认${title}`,
        danger: ['disable', 'archive'].includes(action),
        preview,
        path: `/iam/roles/${role.id}/${action}`,
        method: action === 'grant-limit' ? 'PUT' : 'POST',
        operation: action === 'grant-limit' ? 'roles.protected' : `roles.${action}`,
        body: { ...proposal, expectedVersion: version },
        done: close,
      });
    } catch (failure) {
      // 角色仍有成员等阻断原因在本次操作中反馈，不丢弃已填原因。
      setError(applyFormError(form, failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Modal
        centered
        open={!review}
        title={title}
        className="operation-dialog"
        width={action === 'grant-limit' ? 900 : 560}
        onCancel={requestClose}
        onOk={() => form.submit()}
        okText="核对影响"
        confirmLoading={busy}
        okButtonProps={{ disabled: !grantReady }}
        closable={!busy}
        cancelButtonProps={{ disabled: busy }}
      >
        {error && <Alert type="error" title={error} showIcon className="form-alert" />}
        <ReadState
          error={action === 'grant-limit' ? (grant.error ?? catalog.error) : null}
          retry={() => {
            // 读取重试不提交权限变更。
            void grant.refetch();
            void catalog.refetch();
          }}
          loading={action === 'grant-limit' && (grant.isPending || catalog.isPending)}
        >
          {targetContext}
          <Form
            name="role-action"
            form={form}
            layout="vertical"
            onFinish={prepare}
            disabled={busy}
            onValuesChange={() => setError(null)}
            scrollToFirstError={{ focus: true }}
          >
            {action === 'grant-limit' && (
              <Form.Item name="grantablePermissionCodes" label="可授予操作">
                <PermissionPicker pages={catalog.data ?? []} protectedMode grantLimit />
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
                autoFocus={action !== 'grant-limit'}
                autoSize={{ minRows: 3, maxRows: 5 }}
                maxLength={500}
              />
            </Form.Item>
          </Form>
        </ReadState>
      </Modal>
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </>
  );
}
