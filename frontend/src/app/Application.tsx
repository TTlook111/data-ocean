import { useEffect, useRef, useState } from 'react';
import { App, Avatar, Breadcrumb, Button, Dropdown, Layout, Menu, Modal, Result, Spin } from 'antd';
import {
  ApartmentOutlined,
  AuditOutlined,
  BookOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  DownOutlined,
  InboxOutlined,
  ReadOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { api, ApiError, resetCsrf } from '../shared/api';
import { SessionContext } from '../shared/session';
import { readErrorMessage } from '../shared/Records';
import type { Person, Session } from '../modules/iam/types';
import { InitialPasswordPage, LoginPage } from '../modules/iam/LoginPage';
import { AccountPage } from '../modules/iam/AccountPage';
import { UsersPage } from '../modules/iam/UsersPage';
import { DepartmentsPage } from '../modules/iam/DepartmentsPage';
import { RolesPage, RoleEditor } from '../modules/iam/RolesPage';
import { AuditPage } from '../modules/iam/AuditPage';

/**
 * 已实现页面与真实页面权限的对应关系。
 */
const navigation = [
  { key: '/iam/users', label: '人员管理', permission: 'users.view', icon: <TeamOutlined /> },
  {
    key: '/iam/departments',
    label: '部门管理',
    permission: 'departments.view',
    icon: <ApartmentOutlined />,
  },
  { key: '/iam/roles', label: '角色与功能权限', permission: 'roles.view', icon: <ReadOutlined /> },
  { key: '/iam/audit', label: '权限审计', permission: 'audit.view', icon: <AuditOutlined /> },
];

/**
 * 登录边界控制缓存和页面生命周期，失效后立即撤下业务内容。
 */
export function Application() {
  const cache = useQueryClient();

  const { message } = App.useApp();

  const [loggingOut, setLoggingOut] = useState(false);

  const navigate = useNavigate();

  const location = useLocation();

  const pageContexts = useRef<Record<string, string>>({});

  useEffect(() => {
    // 各管理列表独立保留已应用条件与页码，编辑子页不覆盖原列表上下文。
    if (navigation.some((item) => item.key === location.pathname))
      pageContexts.current[location.pathname] = location.search;
    document
      .querySelector('.admin-sidebar .ant-menu-item-selected')
      ?.scrollIntoView({ block: 'nearest' });
  }, [location.pathname, location.search]);

  /**
   * 菜单与上级面包屑复用列表上下文，不重新提交业务操作。
   */
  function openPage(path: string) {
    const returnSearch =
      path === '/iam/roles'
        ? (location.state as { returnSearch?: string } | null)?.returnSearch
        : null;
    navigate(`${path}${pageContexts.current[path] ?? (returnSearch ? `?${returnSearch}` : '')}`);
  }

  const identity = useQuery<Session | null>({
    queryKey: ['session'],
    queryFn: async () => {
      // 未登录是一种正常导航状态，依赖故障仍保留独立错误反馈。
      try {
        return await api<Session>('/auth/me');
      } catch (error) {
        if (error instanceof ApiError && [401001, 401002].includes(error.code)) return null;
        throw error;
      }
    },
  });

  /**
   * 原型顶栏显示本人主部门，来自同一条真实本人资料。
   */
  const account = useQuery({
    queryKey: ['account'],
    queryFn: () => api<Person>('/account'),
    enabled: Boolean(identity.data && !identity.data.mustChangePassword),
  });

  /**
   * 清除旧会话所有业务缓存，随后重新核实服务端身份。
   */
  async function refreshIdentity() {
    pageContexts.current = {};
    cache.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
    const result = await identity.refetch();
    navigate(
      result.data?.mustChangePassword
        ? '/password/initial'
        : result.data?.permissionCodes.includes('users.view')
          ? '/iam/users'
          : result.data
            ? '/account'
            : '/login',
      { replace: true },
    );
  }

  /**
   * 普通退出只注销当前 Cookie 会话。
   */
  async function logout() {
    // 退出尚未完成时不重复发送，失败保留当前身份并提供明确反馈。
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await api('/auth/logout', { method: 'POST' });
      resetCsrf();
      await refreshIdentity();
    } catch (failure) {
      // 服务端未确认注销时不能伪装成功或让菜单操作没有响应。
      message.error(readErrorMessage(failure instanceof Error ? failure : new Error('退出未完成')));
    } finally {
      setLoggingOut(false);
    }
  }

  useEffect(() => {
    /**
     * 请求失效时同步清理显示与缓存，不重放原写操作。
     */
    function expire() {
      pageContexts.current = {};
      resetCsrf();
      cache.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
      cache.setQueryData(['session'], null);
      navigate('/login', { replace: true });
    }
    window.addEventListener('auth-expired', expire);
    return () => window.removeEventListener('auth-expired', expire);
  }, [cache, navigate]);

  if (identity.isPending) {
    return (
      <div className="page-loading">
        <Spin size="large" />
      </div>
    );
  }
  if (identity.error && !identity.data) {
    return (
      <Result
        status="error"
        title="暂时无法核验登录"
        subTitle={readErrorMessage(identity.error)}
        extra={<Button onClick={() => identity.refetch()}>重新连接</Button>}
      />
    );
  }

  const session = identity.data;
  if (!session) {
    return <LoginPage done={refreshIdentity} />;
  }
  if (session.mustChangePassword) {
    return <InitialPasswordPage session={session} done={refreshIdentity} logout={logout} />;
  }

  const selected = navigation.find((item) => location.pathname.startsWith(item.key));

  const visible = navigation.filter((item) => session.permissionCodes.includes(item.permission));

  const granted = !selected || session.permissionCodes.includes(selected.permission);

  /**
   * 后续模块保留原型导航位置，只给已落地页面提供实际入口。
   */
  function futureGroup(
    key: string,
    label: string,
    icon: React.ReactNode,
    pages: [string, string][],
  ) {
    const permitted = pages.filter(([code]) => session!.permissionCodes.includes(`${code}.view`));
    return permitted.length
      ? [
          {
            key,
            label,
            icon,
            children: permitted.map(([code, name]) => ({
              key: `pending-${code}`,
              label: name,
              disabled: true,
            })),
          },
        ]
      : [];
  }

  const menus = [
    ...(session.permissionCodes.includes('dashboard.view')
      ? [{ key: 'pending-dashboard', label: '总览', icon: <DashboardOutlined />, disabled: true }]
      : []),
    ...futureGroup('sources-group', '数据源接入', <DatabaseOutlined />, [
      ['sources-dashboard', '接入概览'],
      ['sources', '数据源管理'],
      ['collections', '采集记录'],
      ['catalog', '元数据目录'],
      ['business-notes', '企业业务说明'],
    ]),
    ...futureGroup('governance-group', '数据治理', <DeploymentUnitOutlined />, [
      ['governance-dashboard', '治理概览'],
      ['governance', '治理工作台'],
    ]),
    ...futureGroup('knowledge-group', '知识库与发布', <BookOutlined />, [
      ['knowledge-dashboard', '知识概览'],
      ['knowledge', '知识文档'],
      ['test-cases', '标准题单'],
      ['releases', '发布与版本'],
    ]),
    ...futureGroup('feedback-group', '反馈处理', <InboxOutlined />, [
      ['feedback-dashboard', '反馈概览'],
      ['feedback', '问题队列与处理'],
    ]),
    ...(visible.length
      ? [
          {
            key: 'people-group',
            label: '人员与权限',
            icon: <TeamOutlined />,
            children: [
              ...visible
                .filter((item) => item.key !== '/iam/audit')
                .map(({ key, label }) => ({ key, label })),
              ...[
                ['output-rules', '输出限制规则'],
                ['access-requests', '访问申请'],
                ['grants', '授权管理'],
              ]
                .filter(([code]) => session.permissionCodes.includes(`${code}.view`))
                .map(([code, label]) => ({ key: `pending-${code}`, label, disabled: true })),
              ...visible
                .filter((item) => item.key === '/iam/audit')
                .map(({ key, label }) => ({ key, label })),
            ],
          },
        ]
      : []),
    ...futureGroup('system-group', '系统配置', <SettingOutlined />, [
      ['models', '模型配置'],
      ['diagnostics', '调用记录与故障'],
    ]),
    {
      key: 'personal-group',
      label: '个人管理',
      icon: <UserOutlined />,
      children: [
        { key: '/account', label: '账号信息' },
        { key: 'pending-my-requests', label: '我的申请', disabled: true },
        { key: 'pending-my-access', label: '我的访问范围', disabled: true },
      ],
    },
  ];

  // 顶栏贯穿整个窗口，侧栏从顶栏下方开始，沿用原型的导航层级。
  return (
    <SessionContext.Provider value={session}>
      <Layout className="admin-shell">
        <Layout.Header className="topbar">
          <a
            className="admin-brand"
            href="/iam/users"
            onClick={(event) => {
              event.preventDefault();
              navigate(visible[0]?.key ?? '/account');
            }}
          >
            <img src="/dataocean-mark.png" alt="" />
            <strong>DataOcean</strong>
          </a>
          <Breadcrumb
            className="admin-breadcrumb"
            items={[
              { title: '后台管理' },
              {
                title:
                  selected?.key === '/iam/roles' && location.pathname !== '/iam/roles' ? (
                    <a
                      href={`/iam/roles${pageContexts.current['/iam/roles'] ?? ''}`}
                      onClick={(event) => {
                        event.preventDefault();
                        openPage('/iam/roles');
                      }}
                    >
                      角色与功能权限
                    </a>
                  ) : (
                    (selected?.label ?? '账号信息')
                  ),
              },
              ...(location.pathname.includes('/edit') || location.pathname.endsWith('/new')
                ? [{ title: location.pathname.endsWith('/new') ? '新增角色' : '编辑角色' }]
                : []),
            ]}
          />
          <Button type="text" className="workbench-link" disabled>
            问数工作台
          </Button>
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                { key: 'account', label: '账号信息' },
                {
                  key: 'logout',
                  label: loggingOut ? '正在退出…' : '退出登录',
                  disabled: loggingOut,
                },
              ],
              onClick: ({ key }) => {
                if (key === 'logout') void logout();
                else navigate('/account');
              },
            }}
          >
            <Button
              type="text"
              className="admin-identity"
              aria-label={`${session.displayName}，${account.data?.primaryDepartment?.name ?? ''}，用户菜单`}
            >
              <Avatar size={32}>{session.displayName.slice(0, 1)}</Avatar>
              <span className="identity-text">
                {session.displayName}
                <small>{account.data?.primaryDepartment?.name ?? '—'}</small>
              </span>
              <DownOutlined />
            </Button>
          </Dropdown>
        </Layout.Header>
        <Layout className="admin-body">
          <Layout.Sider width={216} theme="light" className="admin-sidebar">
            <Menu
              mode="inline"
              inlineIndent={12}
              aria-label="后台管理菜单"
              defaultOpenKeys={['people-group', 'personal-group']}
              selectedKeys={[location.pathname === '/account' ? '/account' : (selected?.key ?? '')]}
              onClick={({ key }) => openPage(key)}
              items={menus}
            />
          </Layout.Sider>
          <Layout.Content
            className="workspace"
            aria-label={
              location.pathname.endsWith('/new')
                ? '新增角色'
                : location.pathname.includes('/edit')
                  ? '角色功能配置'
                  : (selected?.label ?? '账号信息')
            }
          >
            {!granted ? (
              <Result
                status="403"
                title="当前无权查看此页面"
                extra={<Button onClick={() => navigate('/account')}>返回账号信息</Button>}
              />
            ) : (
              <Routes>
                <Route path="/account" element={<AccountPage expired={refreshIdentity} />} />
                <Route path="/iam/users" element={<UsersPage />} />
                <Route path="/iam/departments" element={<DepartmentsPage />} />
                <Route path="/iam/roles" element={<RolesPage />} />
                <Route path="/iam/roles/new" element={<RoleEditor />} />
                <Route path="/iam/roles/:id/edit" element={<RoleEditor />} />
                <Route path="/iam/audit" element={<AuditPage />} />
                <Route path="*" element={<Navigate to={visible[0]?.key ?? '/account'} replace />} />
              </Routes>
            )}
          </Layout.Content>
        </Layout>
      </Layout>
      {/* 临时故障遮挡全部旧展示并阻止操作，保留挂载的表单；明确失效仍由上方登录边界卸载。 */}
      <Modal
        open={Boolean(identity.error)}
        title="暂时无法核验登录"
        centered
        closable={false}
        keyboard={false}
        mask={{ closable: false }}
        zIndex={3000}
        styles={{ mask: { background: '#fff' } }}
        footer={
          <Button type="primary" loading={identity.isFetching} onClick={() => identity.refetch()}>
            重新连接
          </Button>
        }
      >
        {identity.error && readErrorMessage(identity.error)}
      </Modal>
    </SessionContext.Provider>
  );
}
