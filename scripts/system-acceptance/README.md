# 当前版本系统联调入口

本入口针对隔离的合成销售数据集，支持现状盘点、只读预检、独立 SQL 预期、正式 API 登录、治理/发布/build 和固定题回放。任何局部批次通过都不代表整个 DataOcean 验收完成。正确性与覆盖边界见 [系统联调验收](../../docs/development/系统联调验收.md)。

## 运行条件

- 使用项目 `python-service/.venv` 的 Python 3.13 和已有依赖，不自动安装。
- 现有服务应运行当前工作树代码；入口核对迁移版本，但不能仅凭 HTTP 健康证明进程代码版本。
- 私有 dotenv 文件包含隔离应用库、合成业务库和已有测试登录账号的连接配置。字段名沿用本机私有验收配置；不在仓库放凭据。
- 默认仅允许回环 HTTP，固定应用库 `dataocean_e_acceptance_20260927`，业务库 `langgraph_fixture_e_20260927`；发现其他业务源时拒绝实际操作。
- 显式执行 sources 流程后，可额外验证第二合成库 `langgraph_fixture_switch_20261028`；必须有同一运行目录旁的私有来源配置，并核对其专用账号仅有 products 的 SELECT。其他库仍拒绝。
- 合成库的查询账号必须只拥有 products/sales_orders 两表 SELECT。入口只读预检与独立 SQL 都不会创建、重置或播种数据库。

## 命令

在仓库根目录执行，`<运行目录>` 使用当前机器已核实的仓库外目录，`<私有配置>` 指向被忽略的 dotenv 文件：

```powershell
python-service/.venv/Scripts/python.exe scripts/system-acceptance/acceptance.py inventory --output <运行目录>
python-service/.venv/Scripts/python.exe scripts/system-acceptance/acceptance.py preflight --private-env <私有配置> --output <运行目录>
python-service/.venv/Scripts/python.exe scripts/system-acceptance/acceptance.py oracle --private-env <私有配置> --output <运行目录>
python-service/.venv/Scripts/python.exe scripts/system-acceptance/acceptance.py session --private-env <私有配置> --output <运行目录>
```

session 输出当前验证码图片路径，读取图片后输入 JSON 行完成普通登录；不查询验证码存储、不生成 JWT、不跳过登录。令牌只保存在进程内，结束时登出，不写报告：

登录后先将 API 的账号、数据源目标和活动 build 与隔离库只读结果比对，成功后才允许业务写操作。INCOMPLETE、任意失败动作或命令异常使会话非零退出；只有显式 exit 且本会话动作全部成功才退出 0，这也不表示全项目通过。

```json
{"loginCaptcha":"当前图片中的字符"}
{"flow":"fields"}
{"flow":"business"}
{"flow":"publish"}
{"flow":"build"}
{"flow":"questions"}
{"exit":true}
```

fields/business 会按明确的合成口径创建修订、走提交/审核/复查，记录操作身份和版本。这些程序化测试审核不构成真实业务人员确认。publish 验证未审文档拒绝发布及发布不触发 build；build 是显式动作，会保留旧文档版本，把被本轮文档组替代的旧当前文档留为新草稿，再确认新 build。输出会记录被替代文档身份，不能将旧集合删除当成验收准备。

questions 按 questionIds 保存批次预期和完成情况；中断始终保留 INCOMPLETE/false。每题比较独立结果、同任务 PROTECTED 回执、attemptId/hash、safeSql、列、数据与来源，并读取助手历史元数据。SQL 字面值由产品隐藏，不能从 safeSql 重算原 SQL hash；原摘要通过保护回执关联。固定题之外的多角色、撤回、恢复与浏览器范围必须单独记录。

每道成功结果还会核对同任务提交后的审计、实际表使用和列使用/输出来源投影；返回数组长度、API rowCount、PROTECTED 数量/数组和审计数量必须一致，不能把相互一致的错误数量当作证明。COUNT(*) 可无列来源，但必须有表使用及完整保护凭据。不能用缺失数据充当合法空集。目标身份缺少 host/port/id/库名同样拒绝。

## 扩展流程

- `{"flow":"smoke"}`：20 个模块读取入口冒烟。
- `{"flow":"setup-actors"}`、`actors-captcha`、`actor-login:<reader或no_data>:<当前验证码>`、`actors-check`：在隔离应用库新建合成查询角色和两个账号，按正常首次登录改密及重新登录后核对跨角色/任务归属。凭据只保存在运行目录旁的私有文件；结束用 actors-logout。
- `{"flow":"sources"}`：显式在已核实且正在运行的 MySQL 内创建固定第二合成库和专用只读账号，通过正式 API 登记、采集草稿快照并检查同名表读取隔离。不创建容器/服务，不改已有业务数据，不自动发布第二源或授予其问数权限。
- `feedback-start` → `feedback-revise` → business/publish/build → 原 Q03 重放 → `feedback-close`：合成反馈修订闭环。修订前固定在途旧来源，核对明确知识失效原因和尝试记录；新修订有业务分类及精确复查后才进入待验证，实际重放结果与原预期一致后执行测试确认。
- `{"flow":"resume-completed"}`：完成任务的恢复请求应被拒绝，执行尝试和审计计数不增加。

扩展流程会新增隔离测试数据和正常审核记录，必须与只读 preflight/oracle 区分。程序化合成“审核/确认”不是企业真实业务人员验收。

## 数据和离线自检

`synthetic-data.json` 为三件商品、六笔订单的可分享合成数据说明，不含客户、工资或真实个人资料；`questions.json` 固定 12 题及独立 SQL/预期。真实 oracle 会使用只读账号核对现有 MySQL 数据，不把模拟库结果冒充真实服务结果。

```powershell
cd python-service
.venv/Scripts/python.exe -m pytest tests/test_langgraph_acceptance_fixture.py tests/test_reviewed_predicate_adversarial.py tests/test_build_document_parts.py -q
```

这些测试覆盖结果比较、只读授权守卫、不完整报告、保护凭据与分篇切片合同，不调用模型。真实调用的费用、服务异常和未完成题目必须保留在当轮报告；不自动把失败题剔除后报告全通过。
