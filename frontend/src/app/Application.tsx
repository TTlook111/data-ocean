import { workspaceParent } from './workspaceParent';
import { lazy, Suspense, useEffect, useRef, useState, type ComponentType } from 'react';
import { App, Avatar, Breadcrumb, Button, Layout, Menu, Spin } from 'antd';
import {
  ApartmentOutlined,
  AuditOutlined,
  BookOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  InboxOutlined,
  ReadOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  matchPath,
  matchRoutes,
  Navigate,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from 'react-router';
import { api, ApiError, queryString, resetCsrf } from '../shared/api';
import { SessionContext } from '../shared/session';
import { ReadAccessDenied, readErrorMessage } from '../shared/Records';
import { ActionMenuButton } from '../shared/ActionMenuButton';
import { LoadingStatus } from '../shared/LoadingIndicator';
import type { Person, Session } from '../modules/iam/types';
import { ConnectionFailure, NotFoundPage } from './StatusPage';
import '../modules/datasource/datasource.css';
import '../modules/access/access.css';

/**
 * 已实现页面与真实页面权限的对应关系。
 */
const navigation = [
  { key: '/dashboard', label: '总览', permission: 'dashboard.view', icon: <DashboardOutlined /> },
  { key: '/iam/users', label: '人员管理', permission: 'users.view', icon: <TeamOutlined /> },
  {
    key: '/iam/departments',
    label: '部门管理',
    permission: 'departments.view',
    icon: <ApartmentOutlined />,
  },
  { key: '/iam/roles', label: '角色与功能权限', permission: 'roles.view', icon: <ReadOutlined /> },
  { key: '/iam/output-rules', label: '输出限制规则', permission: 'output-rules.view' },
  { key: '/iam/access-requests', label: '访问申请', permission: 'access-requests.view' },
  { key: '/iam/grants', label: '授权管理', permission: 'grants.view' },
  { key: '/iam/audit', label: '权限审计', permission: 'audit.view', icon: <AuditOutlined /> },
  { key: '/sources-dashboard', label: '接入概览', permission: 'sources-dashboard.view' },
  {
    key: '/datasources',
    label: '数据源管理',
    permission: 'sources.view',
    icon: <DatabaseOutlined />,
  },
  {
    key: '/collections',
    label: '采集记录',
    permission: 'collections.view',
    icon: <DatabaseOutlined />,
  },
  { key: '/catalog', label: '元数据目录', permission: 'catalog.view', icon: <DatabaseOutlined /> },
  { key: '/business-notes', label: '企业业务说明', permission: 'business-notes.view' },
  { key: '/governance-dashboard', label: '治理概览', permission: 'governance-dashboard.view' },
  { key: '/governance', label: '治理工作台', permission: 'governance.view' },
  { key: '/knowledge-dashboard', label: '知识概览', permission: 'knowledge-dashboard.view' },
  { key: '/knowledge', label: '知识文档', permission: 'knowledge.view' },
  { key: '/test-cases', label: '标准题单', permission: 'test-cases.view' },
  { key: '/releases', label: '发布与版本', permission: 'releases.view' },
  { key: '/feedback-dashboard', label: '反馈概览', permission: 'feedback-dashboard.view' },
  { key: '/feedback', label: '问题队列与处理', permission: 'feedback.view' },
  { key: '/model-overview', label: '模型运行概览', permission: 'diagnostics.view' },
  { key: '/models', label: '模型配置', permission: 'models.view' },
  { key: '/diagnostics', label: '调用记录与故障', permission: 'diagnostics.view' },
  { key: '/my-requests', label: '我的申请', permission: 'my-requests.view' },
  { key: '/my-access', label: '我的访问范围', permission: 'my-access.view' },
];

/**
 * 路由真正渲染时才加载命名导出的工作区页面，组件身份在模块内保持稳定。
 */
function lazyPage<Props extends object>(load: () => Promise<ComponentType<Props>>) {
  return lazy(async () => ({ default: await load() }));
}

// 登录与首次改密共用表单模块，异常状态页不需要预载表单。
const LoginPage = lazyPage(() => import('../modules/iam/LoginPage').then((m) => m.LoginPage));
const InitialPasswordPage = lazyPage(() =>
  import('../modules/iam/LoginPage').then((m) => m.InitialPasswordPage),
);

// 身份和组织页面按各自入口加载；角色列表和编辑继续共用同一模块。
const AccountPage = lazyPage(() => import('../modules/iam/AccountPage').then((m) => m.AccountPage));
const QueryWorkbench = lazyPage(() =>
  import('../modules/query/QueryWorkbench').then((m) => m.QueryWorkbench),
);
const UsersPage = lazyPage(() => import('../modules/iam/UsersPage').then((m) => m.UsersPage));
const DepartmentsPage = lazyPage(() =>
  import('../modules/iam/DepartmentsPage').then((m) => m.DepartmentsPage),
);
const RolesPage = lazyPage(() => import('../modules/iam/RolesPage').then((m) => m.RolesPage));
const RoleEditor = lazyPage(() => import('../modules/iam/RolesPage').then((m) => m.RoleEditor));
const AuditPage = lazyPage(() => import('../modules/iam/AuditPage').then((m) => m.AuditPage));
// 统计与办理分开加载，并分别检查已有页面权限。
const FeedbackOverview = lazyPage(() =>
  import('../modules/feedback-overview/FeedbackOverview').then((m) => m.FeedbackOverview),
);
// 已可靠入队的事项通过反馈处理菜单进入。
const FeedbackQueue = lazyPage(() =>
  import('../modules/feedback/FeedbackQueue').then((m) => m.FeedbackQueue),
);
// 正式事项的核查和分派在同一独立详情办理。
const FeedbackIssuePage = lazyPage(() =>
  import('../modules/feedback/FeedbackIssuePage').then((m) => m.FeedbackIssuePage),
);

// 数据源和数据访问页面保留现有具名入口及跨页复用。
const DatasourceManagementPage = lazyPage(() =>
  import('../modules/access/ConfigurationPages').then((m) => m.DatasourceManagementPage),
);
const OutputRulesPage = lazyPage(() =>
  import('../modules/access/ConfigurationPages').then((m) => m.OutputRulesPage),
);
const RequestsPage = lazyPage(() =>
  import('../modules/access/RequestsPage').then((m) => m.RequestsPage),
);
const GrantsPage = lazyPage(() => import('../modules/access/ScopePages').then((m) => m.GrantsPage));
const MyAccessPage = lazyPage(() =>
  import('../modules/access/ScopePages').then((m) => m.MyAccessPage),
);
// 接入概览单独按获准权限加载。
// 总览只在实际进入后台首页时加载图表。
const DashboardOverview = lazyPage(() =>
  import('../modules/dashboard/DashboardOverview').then((m) => m.DashboardOverview),
);
const SourceOverview = lazyPage(() =>
  import('../modules/datasource/SourceOverview').then((m) => m.SourceOverview),
);
const CollectionPage = lazyPage(() =>
  import('../modules/datasource/CollectionPage').then((m) => m.CollectionPage),
);
const CatalogPage = lazyPage(() =>
  import('../modules/datasource/CatalogPage').then((m) => m.CatalogPage),
);
const CatalogObjectPage = lazyPage(() =>
  import('../modules/datasource/CatalogPage').then((m) => m.CatalogObjectPage),
);
const StandaloneStructureChangePage = lazyPage(() =>
  import('../modules/datasource/StructureChangePage').then((m) => m.StandaloneStructureChangePage),
);
const BusinessNotesPage = lazyPage(() =>
  import('../modules/datasource/BusinessNotesPage').then((m) => m.BusinessNotesPage),
);
// 工作台和条目详情按需加载，保留同一治理菜单归属。
// 治理看板与工作台按各自页面权限加载。
const GovernanceOverview = lazyPage(() =>
  import('../modules/governance/GovernanceOverview').then((m) => m.GovernanceOverview),
);
const GovernancePage = lazyPage(() =>
  import('../modules/governance/GovernancePage').then((m) => m.GovernancePage),
);
const GovernanceDetailPage = lazyPage(() =>
  import('../modules/governance/GovernanceDetailPage').then((m) => m.GovernanceDetailPage),
);
// 知识列表和正文属于同一源内的文档流程。
// 知识概览单独加载，不依赖原文档或任务的页面权限。
const KnowledgeOverview = lazyPage(() =>
  import('../modules/knowledge/KnowledgeOverview').then((m) => m.KnowledgeOverview),
);
const KnowledgePage = lazyPage(() =>
  import('../modules/knowledge/KnowledgePage').then((m) => m.KnowledgePage),
);
const KnowledgeDocumentPage = lazyPage(() =>
  import('../modules/knowledge/KnowledgeDocumentPage').then((m) => m.KnowledgeDocumentPage),
);
// 标准题与文档页面分别授权，继续共用当前来源。
const KnowledgeTestPage = lazyPage(() =>
  import('../modules/knowledge/KnowledgeTestPage').then((m) => m.KnowledgeTestPage),
);
// 发布入口读取同源文档的实际构建前条件。
const KnowledgeReleasePage = lazyPage(() =>
  import('../modules/knowledge/KnowledgeReleasePage').then((m) => m.KnowledgeReleasePage),
);
// 发布任务有独立详情，保持原型的列表与办理页分工。
const KnowledgeReleaseBuildPage = lazyPage(() =>
  import('../modules/knowledge/KnowledgeReleaseBuildPage').then((m) => m.KnowledgeReleaseBuildPage),
);
const KnowledgeEffectiveVersionPage = lazyPage(() =>
  import('../modules/knowledge/KnowledgeEffectiveVersionPage').then(
    (m) => m.KnowledgeEffectiveVersionPage,
  ),
);
// 模型配置与调用记录独立授权，使用各自的后台入口。
const ModelOverview = lazyPage(() =>
  import('../modules/models/ModelOverview').then((m) => m.ModelOverview),
);
const ModelPage = lazyPage(() => import('../modules/models/ModelPage').then((m) => m.ModelPage));
const ModelCallsPage = lazyPage(() =>
  import('../modules/models/ModelCallsPage').then((m) => m.ModelCallsPage),
);

/**
 * 工作区路由同时用于内容渲染与权限、菜单和面包屑定位。
 */
const workspaceRoutes = [
  { path: '/dashboard', element: <DashboardOverview /> },
  // 问数使用独立工作台，管理页面继续走原有外壳。
  { path: '/query' },
  // 人员、组织与功能权限管理。
  { path: '/iam/users', element: <UsersPage /> },
  { path: '/iam/departments', element: <DepartmentsPage /> },
  { path: '/iam/roles', element: <RolesPage /> },
  { path: '/iam/roles/new', element: <RoleEditor /> },
  { path: '/iam/roles/:id/edit', element: <RoleEditor /> },
  { path: '/iam/audit', element: <AuditPage /> },
  // 数据源接入及目录详情。
  { path: '/datasources', element: <DatasourceManagementPage /> },
  { path: '/sources-dashboard', element: <SourceOverview /> },
  { path: '/collections', element: <CollectionPage /> },
  { path: '/collections/changes', element: <StandaloneStructureChangePage /> },
  { path: '/catalog', element: <CatalogPage /> },
  { path: '/catalog/:id', element: <CatalogObjectPage /> },
  { path: '/business-notes', element: <BusinessNotesPage /> },
  // 表说明和字段治理的工作台与办理页。
  { path: '/governance-dashboard', element: <GovernanceOverview /> },
  { path: '/governance', element: <GovernancePage /> },
  { path: '/governance/:id', element: <GovernanceDetailPage /> },
  // 知识文档列表及其草稿、当前确认和历史修订。
  { path: '/knowledge-dashboard', element: <KnowledgeOverview /> },
  { path: '/knowledge', element: <KnowledgePage /> },
  { path: '/knowledge/:id', element: <KnowledgeDocumentPage /> },
  { path: '/test-cases', element: <KnowledgeTestPage /> },
  { path: '/releases', element: <KnowledgeReleasePage /> },
  { path: '/releases/:id', element: <KnowledgeReleaseBuildPage /> },
  { path: '/releases/versions/:id', element: <KnowledgeEffectiveVersionPage /> },
  // 反馈概览和基础队列分别使用各自的页面权限。
  { path: '/feedback-dashboard', element: <FeedbackOverview /> },
  { path: '/feedback', element: <FeedbackQueue /> },
  { path: '/feedback/:id', element: <FeedbackIssuePage /> },
  // 系统配置的真实模型与诊断入口。
  { path: '/model-overview', element: <ModelOverview /> },
  { path: '/models', element: <ModelPage /> },
  { path: '/diagnostics', element: <ModelCallsPage /> },
  // 数据访问审批及个人范围。
  { path: '/iam/output-rules', element: <OutputRulesPage /> },
  { path: '/iam/access-requests', element: <RequestsPage key="manage" /> },
  { path: '/iam/grants', element: <GrantsPage /> },
  { path: '/my-requests', element: <RequestsPage key="mine" mine /> },
  { path: '/my-access', element: <MyAccessPage /> },
];

/**
 * 已登录用户打开这些入口时仍回到其可访问的首页。
 */
const entryPaths = ['/', '/login', '/password/initial'];

/**
 * 身份核验和首次加载登录表单使用相同的全屏加载反馈。
 */
const sessionLoading = (
  <div className="page-loading" role="status" aria-label="正在加载">
    <Spin size="large" />
  </div>
);

/**
 * 登录边界控制缓存和页面生命周期，失效后立即撤下业务内容。
 */
export function Application() {
  const cache = useQueryClient();

  const { message } = App.useApp();

  const [loggingOut, setLoggingOut] = useState(false);
  const [menuExpansion, setMenuExpansion] = useState<{ path: string; keys: string[] }>({
    path: '',
    keys: [],
  });

  const navigate = useNavigate();

  const location = useLocation();

  // 只根据真实注册路由识别未知地址，独立 404 无需等待或发起身份核验。
  const activePath = matchRoutes(workspaceRoutes, location)?.at(-1)?.route.path;
  const missing =
    !activePath &&
    !matchPath('/account', location.pathname) &&
    !entryPaths.some((path) => matchPath(path, location.pathname));

  const pageContexts = useRef<Record<string, string>>({});

  // 显式刷新概览恢复默认期间，应用内来回导航仍使用页面上下文。
  const resetOverview = useRef(
    [
      '/dashboard',
      '/feedback-dashboard',
      '/sources-dashboard',
      '/governance-dashboard',
      '/knowledge-dashboard',
    ].includes(location.pathname) &&
      Boolean(location.search) &&
      (performance.getEntriesByType?.('navigation')[0] as PerformanceNavigationTiming | undefined)
        ?.type === 'reload'
      ? location.pathname
      : null,
  );
  useEffect(() => {
    // 只在本次应用首次挂载处理浏览器刷新。
    const path = resetOverview.current;
    if (!path) return;
    resetOverview.current = null;
    void navigate(path, { replace: true });
  }, [navigate]);

  useEffect(() => {
    // 各管理列表独立保留已应用条件与页码，编辑子页不覆盖原列表上下文。
    if (navigation.some((item) => item.key === location.pathname)) {
      if (location.pathname === '/releases' || location.pathname === '/test-cases') {
        // 同一路径下的旧绑定和标准题详情不覆盖侧栏的列表入口。
        const search = new URLSearchParams(location.search);
        search.delete('bindingId');
        search.delete('testId');
        search.delete('queuePageNum');
        pageContexts.current[location.pathname] = search.size ? '?' + search.toString() : '';
      } else {
        pageContexts.current[location.pathname] = location.search;
      }
    }
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
    enabled: !missing,
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
    enabled: Boolean(!missing && identity.data && !identity.data.mustChangePassword),
  });

  /**
   * 清除旧会话所有业务缓存，随后重新核实服务端身份。
   */
  async function refreshIdentity() {
    pageContexts.current = {};
    cache.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
    const result = await identity.refetch();
    navigate(
      result.data?.mustChangePassword ? '/password/initial' : result.data ? '/query' : '/login',
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

  /**
   * 只重查身份，同一次重连过程复用正在发送的请求。
   */
  function retryConnection() {
    void identity.refetch({ cancelRefetch: false });
  }

  if (missing) {
    // 独立状态页直接回到正式入口，由入口继续检查登录和首页权限。
    return <NotFoundPage onHome={() => navigate('/', { replace: true })} />;
  }

  if (identity.isPending && !identity.isFetched) {
    // 初次核验保持加载，失败后的重试则留在原故障场景。
    return sessionLoading;
  }
  const connectionDetail = identity.error
    ? readErrorMessage(identity.error)
    : '服务连接暂不可用，请检查连接后重试';
  if (!identity.data && (identity.error || identity.isPending)) {
    // 身份尚未确认时只显示故障页，不构建受限工作区。
    return (
      <ConnectionFailure
        detail={connectionDetail}
        retrying={identity.isFetching}
        onRetry={retryConnection}
      />
    );
  }

  const session = identity.data;
  if (!session || session.mustChangePassword) {
    // 保持身份入口原有分流，表单模块按当前身份状态加载。
    return (
      <Suspense fallback={sessionLoading}>
        {session ? (
          <InitialPasswordPage session={session} done={refreshIdentity} logout={logout} />
        ) : (
          <LoginPage done={refreshIdentity} />
        )}
      </Suspense>
    );
  }

  const visible = navigation.filter((item) => session.permissionCodes.includes(item.permission));
  // 概览沿用调用诊断权限，模型配置继续独立授权。
  const systemPages = visible.filter(({ key }) =>
    ['/model-overview', '/models', '/diagnostics'].includes(key),
  );
  const home = visible[0]?.key ?? '/account';

  if (entryPaths.some((path) => matchPath(path, location.pathname))) {
    // 有效会话从正式入口进入问数，首次改密仍由前面的认证分流处理。
    return <Navigate to="/query" replace />;
  }

  if (activePath === '/query') {
    return (
      <Suspense fallback={sessionLoading}>
        <QueryWorkbench
          session={session}
          department={account.data?.primaryDepartment?.name}
          onBack={() => openPage(home)}
          onLogout={() => void logout()}
          loggingOut={loggingOut}
        />
      </Suspense>
    );
  }

  // 已注册的子页继承所属模块的菜单位置和页面权限。
  const selected = navigation.find(
    (item) => activePath && matchPath({ path: item.key, end: false }, activePath),
  );
  const pageLabel = selected?.label ?? '账号信息';
  const creatingRole = activePath === '/iam/roles/new';
  const editingRole = activePath === '/iam/roles/:id/edit';
  const collectionOrigin = location.state as { collectionSearch?: string } | null;
  const readingCollectionChanges =
    activePath === '/collections/changes' ||
    (activePath === '/catalog' && Boolean(collectionOrigin?.collectionSearch));
  const changeSearch = new URLSearchParams(location.search);
  // 由采集任务进入时，上级仍定位到发现变化的同一任务。
  const collectionsSearch = queryString({
    source: changeSearch.get('source'),
    task: changeSearch.get('taskId'),
  });
  const collectionsUrl =
    '/collections' +
    (collectionOrigin?.collectionSearch ?? (collectionsSearch ? '?' + collectionsSearch : ''));
  const noteMode =
    activePath === '/business-notes' ? new URLSearchParams(location.search).get('edit') : null;
  const noteSource = (location.state as { noteSource?: string } | null)?.noteSource;

  const granted = !selected || session.permissionCodes.includes(selected.permission);
  const detailBreadcrumb = workspaceParent(activePath, location, session.permissionCodes);
  const breadcrumb = granted ? detailBreadcrumb : undefined;

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
            children: permitted.map(([code, name]) => {
              // 已接入的页面沿用原分组和顺序。
              const path = '/' + code;
              const ready = visible.some((item) => item.key === path);
              return { key: ready ? path : 'pending-' + code, label: name, disabled: !ready };
            }),
          },
        ]
      : [];
  }

  const menus = [
    ...(session.permissionCodes.includes('dashboard.view')
      ? [{ key: '/dashboard', label: '总览', icon: <DashboardOutlined /> }]
      : []),
    ...([
      'sources-dashboard.view',
      'sources.view',
      'collections.view',
      'catalog.view',
      'business-notes.view',
    ].some((code) => session.permissionCodes.includes(code))
      ? [
          {
            key: 'sources-group',
            label: '数据源接入',
            icon: <DatabaseOutlined />,
            children: [
              ...visible
                .filter((item) =>
                  [
                    '/sources-dashboard',
                    '/datasources',
                    '/collections',
                    '/catalog',
                    '/business-notes',
                  ].includes(item.key),
                )
                .map(({ key, label }) => ({ key, label })),
            ],
          },
        ]
      : []),
    // 治理看板与办理工作台分别出现在授权可见的菜单中。
    ...(['governance-dashboard.view', 'governance.view'].some((code) =>
      session.permissionCodes.includes(code),
    )
      ? [
          {
            key: 'governance-group',
            label: '数据治理',
            icon: <DeploymentUnitOutlined />,
            children: [
              ...visible
                .filter(({ key }) => ['/governance-dashboard', '/governance'].includes(key))
                .map(({ key, label }) => ({ key, label })),
            ],
          },
        ]
      : []),
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
    ...(visible.some((item) => item.key.startsWith('/iam/'))
      ? [
          {
            key: 'people-group',
            label: '人员与权限',
            icon: <TeamOutlined />,
            children: [
              ...visible
                .filter((item) => item.key.startsWith('/iam/') && item.key !== '/iam/audit')
                .map(({ key, label }) => ({ key, label })),
              ...visible
                .filter((item) => item.key === '/iam/audit')
                .map(({ key, label }) => ({ key, label })),
            ],
          },
        ]
      : []),
    ...(systemPages.length
      ? [
          {
            key: 'system-group',
            label: '系统配置',
            icon: <SettingOutlined />,
            children: systemPages.map(({ key, label }) => ({ key, label })),
          },
        ]
      : []),
    {
      key: 'personal-group',
      label: '个人管理',
      icon: <UserOutlined />,
      children: [
        { key: '/account', label: '账号信息' },
        ...visible
          .filter((item) => item.key.startsWith('/my-'))
          .map(({ key, label }) => ({ key, label })),
      ],
    },
  ];

  // 直接进入深链时展开当前模块，保证侧栏能显示实际页面位置。
  const activeMenuKey = location.pathname === '/account' ? '/account' : selected?.key;
  const activeMenuGroup = menus.find(
    (item) => 'children' in item && item.children?.some((child) => child.key === activeMenuKey),
  );
  // 跨页跳转展开当前分组，保留用户手动展开的其他分组。
  if (menuExpansion.path !== location.pathname) {
    const group = activeMenuGroup?.key;
    const keys =
      group && !menuExpansion.keys.includes(group)
        ? [...menuExpansion.keys, group]
        : menuExpansion.keys;
    setMenuExpansion({ path: location.pathname, keys });
  }

  // 顶栏贯穿整个窗口，侧栏从顶栏下方开始，沿用原型的导航层级。
  return (
    <SessionContext.Provider value={session}>
      <Layout className="admin-shell">
        <Layout.Header className="topbar">
          <a
            className="admin-brand"
            href={home}
            onClick={(event) => {
              event.preventDefault();
              navigate(home);
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
                title: breadcrumb ? (
                  <a
                    href={breadcrumb.url}
                    onClick={(event) => {
                      // 保留原列表和触发位置，并由路由统一保护未保存表单。
                      if (
                        event.button !== 0 ||
                        event.metaKey ||
                        event.ctrlKey ||
                        event.shiftKey ||
                        event.altKey
                      )
                        return;
                      event.preventDefault();
                      navigate(breadcrumb.url, { state: breadcrumb.state });
                    }}
                  >
                    {breadcrumb.label}
                  </a>
                ) : creatingRole || editingRole ? (
                  <a
                    href={`/iam/roles${pageContexts.current['/iam/roles'] ?? ''}`}
                    onClick={(event) => {
                      event.preventDefault();
                      openPage('/iam/roles');
                    }}
                  >
                    角色与功能权限
                  </a>
                ) : readingCollectionChanges ? (
                  <a
                    href={collectionsUrl}
                    onClick={(event) => {
                      event.preventDefault();
                      navigate(collectionsUrl);
                    }}
                  >
                    采集记录
                  </a>
                ) : (
                  pageLabel
                ),
              },
              ...(creatingRole || editingRole
                ? [{ title: creatingRole ? '新增角色' : '编辑角色' }]
                : []),
              ...(readingCollectionChanges ? [{ title: '结构变化' }] : []),
              ...(breadcrumb ? [{ title: breadcrumb.current }] : []),
              ...(noteMode
                ? [
                    {
                      title: `${noteMode === 'new' ? '新增' : '编辑'}业务说明${noteSource ? ' · ' + noteSource : ''}`,
                    },
                  ]
                : []),
            ]}
          />
          <Button type="text" className="workbench-link" onClick={() => navigate('/query')}>
            问数工作台
          </Button>
          <ActionMenuButton
            placement="bottomRight"
            type="text"
            className="admin-identity"
            label={`${session.displayName}，${account.data?.primaryDepartment?.name ?? ''}，用户菜单`}
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
            {/* 头像、姓名和部门共同形成账号菜单的触发区。 */}
            <Avatar size={32}>{session.displayName.slice(0, 1)}</Avatar>
            <span className="identity-text">
              {session.displayName}
              <small>{account.data?.primaryDepartment?.name ?? '—'}</small>
            </span>
          </ActionMenuButton>
        </Layout.Header>
        <Layout className="admin-body">
          <Layout.Sider width={216} theme="light" className="admin-sidebar">
            <Menu
              mode="inline"
              inlineIndent={12}
              aria-label="后台管理菜单"
              openKeys={menuExpansion.keys}
              onOpenChange={(keys) => setMenuExpansion({ path: location.pathname, keys })}
              selectedKeys={activeMenuKey ? [activeMenuKey] : []}
              onClick={({ key }) => openPage(key)}
              items={menus}
            />
          </Layout.Sider>
          <Layout.Content
            className="workspace"
            aria-label={creatingRole ? '新增角色' : editingRole ? '角色功能配置' : pageLabel}
          >
            {!granted ? (
              <ReadAccessDenied title="当前无权查看此页面">
                <Button onClick={() => navigate('/account')}>返回账号信息</Button>
              </ReadAccessDenied>
            ) : (
              <Suspense
                fallback={
                  <div className="read-loading muted">
                    <LoadingStatus>正在加载页面</LoadingStatus>
                  </div>
                }
              >
                {/* 路由模块加载只暂缓工作区正文，导航和登录核验不受影响。 */}
                <Routes>
                  <Route path="/account" element={<AccountPage expired={refreshIdentity} />} />
                  {workspaceRoutes.map(({ path, element }) => (
                    <Route key={path} path={path} element={element} />
                  ))}
                </Routes>
              </Suspense>
            )}
          </Layout.Content>
        </Layout>
      </Layout>
      {/* 登录核验出错时挂出顶层故障页，原表单仍挂载以便重连后继续。 */}
      {identity.error && (
        <ConnectionFailure
          overlay
          detail={connectionDetail}
          retrying={identity.isFetching}
          onRetry={retryConnection}
        />
      )}
    </SessionContext.Provider>
  );
}
