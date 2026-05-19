# 页面-接口布线图

每个页面：长什么样、展示哪些功能、调哪些接口、什么时机触发、响应怎么渲染。

---

## 全局：AppShell 布局框架

**组件**: `components/AppShell.vue`  
**结构**: 左侧可折叠侧边栏 + 顶栏 + 右侧内容区

```
┌─────────────────────────────────────────────────┐
│ 顶栏：面包屑标题 | 用户名(角色) | [退出登录]    │
├────────┬────────────────────────────────────────┤
│ 侧边栏 │                                        │
│        │         <router-view />                 │
│ 工作台  │         (页面内容区)                    │
│ 智能查询│                                        │
│ ──────  │                                        │
│ 治理管理│                                        │
│  用户   │                                        │
│  角色   │                                        │
│  部门   │                                        │
│  数据源 │                                        │
│ ──────  │                                        │
│ 个人    │                                        │
│  资料   │                                        │
└────────┴────────────────────────────────────────┘
```

**接口调用**:
| 时机 | 接口 | 用途 |
|------|------|------|
| 组件挂载 | `GET /api/auth/me` (via authStore.fetchUserInfo) | 获取用户信息渲染顶栏 |
| 点击"退出登录" | `POST /api/auth/logout` | 注销 JWT，清除本地 token，跳转登录页 |

**菜单权限控制**:
- `user:manage` → 显示"用户管理"
- `role:view` → 显示"角色管理"
- `department:manage` → 显示"部门管理"
- `datasource:manage` → 显示"数据源管理"
- 无权限的菜单项直接隐藏（不是灰色）

---

## 页面 1：登录页 `/login`

**组件**: `views/login/LoginPage.vue` → `views/LoginView.vue`  
**布局**: 独立全屏页（无 AppShell）

```
┌──────────────────────┬─────────────────────┐
│                      │                     │
│   DataOcean          │   用户名 [______]   │
│   智能数据查询平台    │   密码   [______]   │
│                      │   □ 记住我          │
│   · NL2SQL 查询      │                     │
│   · 元数据治理       │   [    登录    ]    │
│   · 数据可视化       │                     │
│                      │                     │
└──────────────────────┴─────────────────────┘
```

**功能清单**:
- 用户名+密码登录
- "记住我"记住用户名
- 登录失败错误提示
- 账号锁定提示

**接口调用**:
| 触发动作 | 接口 | 请求体 | 成功处理 | 失败处理 |
|----------|------|--------|----------|----------|
| 点击登录/Enter | `POST /api/auth/login` | `{username, password}` | 存 token → 判断 passwordChanged → 跳转 | 显示错误 alert |

**跳转逻辑（登录成功后）**:
```
if (user.passwordChanged === false) → /change-password?forced=1
else if (url有redirect参数) → redirect目标
else if (角色是ADMIN/SUPER_ADMIN) → /admin
else → /query
```

---

## 页面 2：强制改密 `/change-password`

**组件**: `views/profile/ChangePassword.vue`  
**布局**: 独立居中卡片（强制模式无 AppShell）/ AppShell 内（自愿模式）

```
┌─────────────────────────────────┐
│  ⚠️ 首次登录，请修改初始密码     │  ← 仅强制模式显示
├─────────────────────────────────┤
│  当前密码  [______________]     │
│  新密码    [______________]     │
│  确认密码  [______________]     │
│                                 │
│         [  确认修改  ]          │
└─────────────────────────────────┘
```

**功能清单**:
- 输入旧密码 + 新密码 + 确认
- 密码复杂度校验（8-32字符，含字母+数字）
- 强制模式 vs 自愿模式区分

**接口调用**:
| 触发动作 | 接口 | 请求体 | 成功处理 | 失败处理 |
|----------|------|--------|----------|----------|
| 点击确认修改 | `PUT /api/auth/password` | `{oldPassword, newPassword}` | 提示成功 → logout → 跳转登录页 | 字段下方显示错误 |

---

## 页面 3：管理首页 `/admin`

**组件**: `views/AdminHomeView.vue`  
**布局**: AppShell 内，欢迎区 + 模块卡片 + 快捷入口

```
┌─────────────────────────────────────────────┐
│  👋 下午好，张三                             │
├─────────────────────────────────────────────┤
│  ┌─────────┐ ┌─────────┐ ┌─────────┐       │
│  │ 用户模块 │ │ 数据源  │ │ 元数据  │ ...   │
│  │ ✓ 已联调│ │ ✓ 已联调│ │ 开发中  │       │
│  └─────────┘ └─────────┘ └─────────┘       │
├─────────────────────────────────────────────┤
│  快捷入口：用户管理 | 数据源管理 | ...       │
└─────────────────────────────────────────────┘
```

**功能清单**:
- 欢迎语（含用户姓名 + 时间段问候）
- 模块开发状态总览
- 快捷跳转入口

**接口调用**: 无额外接口（用户信息从 authStore 读取）

---

## 页面 4：用户管理 `/admin/users`

**组件**: `views/admin/user/UserList.vue`  
**权限**: `user:manage`  
**布局**: AppShell 内，筛选栏 + 表格 + 分页

```
┌─────────────────────────────────────────────────────────────┐
│ 用户名[___] 姓名[___] 部门[▼树选择] 状态[▼] [重置] [+新建] │
├─────────────────────────────────────────────────────────────┤
│ 用户名 │ 姓名 │ 部门    │ 角色        │ 状态 │ 操作        │
│ admin  │ 管理员│ 技术部  │ ADMIN       │ 正常 │ 编辑|禁用|… │
│ zhang  │ 张三  │ 业务部  │ ANALYST,BIZ │ 正常 │ 编辑|禁用|… │
│ locked │ 李四  │ 技术部  │ BIZ_USER    │ 锁定 │ 编辑|解锁|… │
├─────────────────────────────────────────────────────────────┤
│ 共 25 条  < 1 2 3 >  每页 [20▼]                            │
└─────────────────────────────────────────────────────────────┘
```

**功能清单**:
- 用户列表分页展示
- 多条件筛选（用户名/姓名/部门/状态）
- 新建用户（弹窗表单）
- 编辑用户（弹窗表单）
- 启用/禁用/解锁用户
- 重置密码
- 删除用户

**接口调用**:
| 触发动作 | 接口 | 参数/请求体 | 响应处理 |
|----------|------|-------------|----------|
| 页面挂载 + 筛选变化 + 翻页 | `GET /api/admin/users` | `{page, size, username?, realName?, departmentId?, status?}` | 渲染表格 + 更新分页 |
| 页面挂载（加载筛选选项） | `GET /api/admin/departments/tree` | — | 填充部门树选择器 |
| 页面挂载（加载筛选选项） | `GET /api/admin/roles` | — | 填充角色多选框（新建/编辑弹窗用） |
| 点击"新建" → 填写 → 确认 | `POST /api/admin/users` | `{username, password, realName, email?, phone?, departmentId, roleIds[]}` | 关闭弹窗 → 刷新列表第一页 |
| 点击"编辑" → 修改 → 确认 | `PUT /api/admin/users/{id}` | `{realName, email?, phone?, departmentId, roleIds[]}` | 关闭弹窗 → 当前行数据更新 |
| 点击"禁用" → 确认弹窗 | `PATCH /api/admin/users/{id}/status` | `{status: "DISABLED"}` | 行内状态 Tag 变灰 |
| 点击"启用" | `PATCH /api/admin/users/{id}/status` | `{status: "NORMAL"}` | 行内状态 Tag 变绿 |
| 点击"解锁" → 确认弹窗 | `PATCH /api/admin/users/{id}/status` | `{status: "NORMAL"}` | 行内状态 Tag 变绿 |
| 点击"重置密码" → 确认弹窗 | `POST /api/admin/users/{id}/reset-password` | — | 弹窗显示临时密码（可复制） |
| 点击"删除" → 确认弹窗 | `DELETE /api/admin/users/{id}` | — | 行消失，刷新当前页 |

---

## 页面 5：角色列表 `/admin/roles`

**组件**: `views/admin/user/RoleList.vue`  
**权限**: `role:view`  
**布局**: AppShell 内，纯表格（无筛选、无操作）

```
┌──────────────────────────────────────────────────────┐
│ 角色编码      │ 角色名称   │ 描述         │ 状态    │
│ SUPER_ADMIN  │ 超级管理员 │ 系统最高权限  │ 启用    │
│ ADMIN        │ 管理员     │ 日常管理     │ 启用    │
│ DATA_ANALYST │ 数据分析师 │ 查询+分析    │ 启用    │
│ BUSINESS_USER│ 业务用户   │ 基础查询     │ 启用    │
└──────────────────────────────────────────────────────┘
```

**功能清单**:
- 只读展示预置角色

**接口调用**:
| 触发动作 | 接口 | 响应处理 |
|----------|------|----------|
| 页面挂载 | `GET /api/admin/roles` | 渲染表格 |

---

## 页面 6：部门树 `/admin/departments`

**组件**: `views/admin/user/DepartmentTree.vue`  
**权限**: `department:manage`  
**布局**: AppShell 内，树形结构 + 操作按钮

```
┌─────────────────────────────────────────────┐
│ [+ 新建根部门]                               │
├─────────────────────────────────────────────┤
│ 📁 总公司                    [添加子部门][删除]│
│   📁 技术部                  [添加子部门][删除]│
│     📁 后端组                [添加子部门][删除]│
│     📁 前端组                [添加子部门][删除]│
│   📁 业务部                  [添加子部门][删除]│
└─────────────────────────────────────────────┘
```

**功能清单**:
- 树形展示部门层级
- 新建根部门 / 添加子部门
- 删除部门

**接口调用**:
| 触发动作 | 接口 | 参数/请求体 | 响应处理 |
|----------|------|-------------|----------|
| 页面挂载 | `GET /api/admin/departments/tree` | — | 渲染树 |
| 点击"新建"/"添加子部门" → 填写 → 确认 | `POST /api/admin/departments` | `{parentId?, deptName, deptCode, sortOrder}` | 刷新树 |
| 点击"删除" → 确认弹窗 | `DELETE /api/admin/departments/{id}` | — | 刷新树 |

---

## 页面 7：数据源管理 `/admin/datasources`

**组件**: `views/admin/datasource/DatasourceList.vue`  
**权限**: `datasource:manage`  
**布局**: AppShell 内，筛选栏 + 表格 + 分页

```
┌──────────────────────────────────────────────────────────────────────┐
│ 名称[_______] 状态[▼] 健康[▼]                    [重置]  [+新建]    │
├──────────────────────────────────────────────────────────────────────┤
│ 名称      │ 连接信息          │ 状态 │ 健康 │ 授权数│ 操作          │
│ 生产库    │ 10.0.1.5:3306/prod│ 启用 │ ✓健康│ 5    │ 编辑|测试|授权│
│ 测试库    │ 10.0.1.6:3306/test│ 禁用 │ —    │ 2    │ 编辑|启用|删除│
├──────────────────────────────────────────────────────────────────────┤
│ 共 8 条  < 1 >  每页 [20▼]                                          │
└──────────────────────────────────────────────────────────────────────┘
```

**功能清单**:
- 数据源列表分页展示
- 筛选（名称/状态/健康状态）
- 新建数据源（弹窗，含连接测试）
- 编辑数据源
- 测试已保存数据源的连接
- 启用/禁用数据源
- 删除数据源（仅禁用状态可删）
- 授权管理（弹窗：添加/移除用户授权）

**接口调用**:
| 触发动作 | 接口 | 参数/请求体 | 响应处理 |
|----------|------|-------------|----------|
| 页面挂载 + 筛选 + 翻页 | `GET /api/admin/datasources` | `{page, size, name?, status?, healthStatus?}` | 渲染表格 |
| 新建弹窗 → 点击"测试连接" | `POST /api/admin/datasources/test-connection` | `{host, port, databaseName, charset, username, password}` | 成功=绿色提示+解锁保存按钮，失败=红色提示+错误详情 |
| 新建弹窗 → 点击"保存" | `POST /api/admin/datasources` | `{name, description?, host, port, databaseName, charset, username, password}` | 关闭弹窗 → 刷新列表 |
| 编辑弹窗 → 点击"保存" | `PUT /api/admin/datasources/{id}` | 同上（password 可选） | 关闭弹窗 → 当前行更新 |
| 表格行 → 点击"测试连接" | `POST /api/admin/datasources/{id}/test-connection` | — | Message 提示结果 |
| 点击"禁用" → 确认弹窗 | `PATCH /api/admin/datasources/{id}/status` | `{status: "DISABLED"}` | 行内状态更新 |
| 点击"启用" | `PATCH /api/admin/datasources/{id}/status` | `{status: "ENABLED"}` | 行内状态更新 |
| 点击"删除" → 确认弹窗 | `DELETE /api/admin/datasources/{id}` | — | 行消失 |
| 点击"授权" → 打开弹窗 | `GET /api/admin/datasources/{id}/access` | — | 渲染已授权用户列表 |
| 授权弹窗 → 搜索用户 | `GET /api/admin/users` | `{username或realName模糊}` | 填充用户下拉选项 |
| 授权弹窗 → 点击"添加" | `POST /api/admin/datasources/{id}/access` | `{userIds: [...]}` | 刷新授权列表 |
| 授权弹窗 → 点击"取消授权" | `DELETE /api/admin/datasources/{id}/access/{userId}` | — | 行消失 |

---

## 页面 8：问答端数据源选择 `/query`

**组件**: `views/query/QueryDatasourceView.vue`  
**权限**: 任意已登录用户  
**布局**: AppShell 内（侧边栏精简版，仅显示"智能查询"和"个人"）

```
┌─────────────────────────────────────────────────┐
│  选择数据源                                      │
├─────────────────────────────────────────────────┤
│  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │ 🗄️ 生产库 │  │ 🗄️ 分析库 │  │ 🗄️ 报表库 │      │
│  │ prod_db  │  │ anal_db  │  │ report   │      │
│  │ 核心业务  │  │ 数据分析  │  │ 报表数据  │      │
│  │ [选择]   │  │ [选择]   │  │ [选择]   │      │
│  └──────────┘  └──────────┘  └──────────┘      │
├─────────────────────────────────────────────────┤
│  💬 请先选择一个数据源            [开始查询]     │
└─────────────────────────────────────────────────┘
```

**功能清单**:
- 展示当前用户被授权的、已启用的数据源
- 卡片选择交互
- 底部查询输入框（当前为占位，后续模块实现）
- 空状态引导

**接口调用**:
| 触发动作 | 接口 | 响应处理 |
|----------|------|----------|
| 页面挂载 | `GET /api/datasources` | 渲染数据源卡片网格；空数组则显示空状态 |

**注意**: 此接口只返回 `status=ENABLED` 且当前用户有授权的数据源，后端已做过滤。

---

## 页面 9：个人资料 `/profile`

**组件**: `views/profile/ProfileView.vue`  
**权限**: 任意已登录用户  
**布局**: AppShell 内，居中卡片表单

```
┌─────────────────────────────────┐
│  个人资料                        │
├─────────────────────────────────┤
│  用户名   admin (不可修改)       │
│  姓名     [__管理员______]       │
│  邮箱     [__admin@xx.com_]      │
│  手机     [__13800000000_]       │
│                                  │
│  [保存修改]    修改密码 →        │
└─────────────────────────────────┘
```

**功能清单**:
- 展示并编辑个人信息（姓名/邮箱/手机）
- 跳转修改密码

**接口调用**:
| 触发动作 | 接口 | 请求体 | 响应处理 |
|----------|------|--------|----------|
| 页面挂载 | `GET /api/auth/me` | — | 填充表单 |
| 点击"保存修改" | `PUT /api/auth/profile` | `{realName, email?, phone?}` | Message 提示成功 + 更新 authStore |

---

## 接口总览：按调用频率排序

| 接口 | 被哪些页面调用 | 调用频率 |
|------|---------------|----------|
| `GET /api/auth/me` | AppShell, ProfileView | 每次页面刷新 |
| `POST /api/auth/login` | LoginView | 登录时 |
| `POST /api/auth/logout` | AppShell, ChangePassword | 退出时 |
| `PUT /api/auth/password` | ChangePassword | 改密时 |
| `PUT /api/auth/profile` | ProfileView | 保存资料时 |
| `GET /api/admin/users` | UserList, DatasourceList(授权弹窗) | 进入页面+筛选+翻页 |
| `POST /api/admin/users` | UserList | 新建用户 |
| `PUT /api/admin/users/{id}` | UserList | 编辑用户 |
| `PATCH /api/admin/users/{id}/status` | UserList | 启用/禁用/解锁 |
| `DELETE /api/admin/users/{id}` | UserList | 删除用户 |
| `POST /api/admin/users/{id}/reset-password` | UserList | 重置密码 |
| `GET /api/admin/roles` | UserList, RoleList | 加载角色选项 |
| `GET /api/admin/departments/tree` | UserList, DepartmentTree | 加载部门树 |
| `POST /api/admin/departments` | DepartmentTree | 新建部门 |
| `DELETE /api/admin/departments/{id}` | DepartmentTree | 删除部门 |
| `GET /api/admin/datasources` | DatasourceList | 进入页面+筛选+翻页 |
| `POST /api/admin/datasources` | DatasourceList | 新建数据源 |
| `PUT /api/admin/datasources/{id}` | DatasourceList | 编辑数据源 |
| `DELETE /api/admin/datasources/{id}` | DatasourceList | 删除数据源 |
| `PATCH /api/admin/datasources/{id}/status` | DatasourceList | 启用/禁用 |
| `POST /api/admin/datasources/test-connection` | DatasourceList(新建弹窗) | 测试新连接 |
| `POST /api/admin/datasources/{id}/test-connection` | DatasourceList(表格行) | 测试已有连接 |
| `GET /api/admin/datasources/{id}/access` | DatasourceList(授权弹窗) | 查看授权 |
| `POST /api/admin/datasources/{id}/access` | DatasourceList(授权弹窗) | 添加授权 |
| `DELETE /api/admin/datasources/{id}/access/{userId}` | DatasourceList(授权弹窗) | 取消授权 |
| `GET /api/datasources` | QueryDatasourceView | 加载用户可用数据源 |

---

## 未使用的接口

| 接口 | 说明 |
|------|------|
| `GET /api/admin/users/{id}` | 后端存在但前端未调用（编辑时直接用列表数据填充弹窗） |
