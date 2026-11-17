# 首批身份真实验收

本目录验证已运行的正式 Java 应用、平台 MySQL 和会话 Redis。它不会创建容器或重置数据库。先阅读[本地运行](../../docs/backend/运行与验收.md)及 `.dataocean/local-environment.md`（若存在），确认新平台开发库可以保留合成验收数据。

## 普通接口

应用启动后从仓库根目录执行 `node tools/acceptance/iam-api.mjs`。默认地址为 `http://127.0.0.1:18080`，可用 `DATAOCEAN_ACCEPTANCE_URL` 覆盖。脚本使用项目内开发账号约定，每次创建独立前缀的数据，保留真实审计，不清空已有人员、部门和角色。

## 平台探针

深层场景需要当前机器的真实连接配置。将 `DATAOCEAN_LOCAL_CONFIG` 指向 `application-local.yml` 的绝对路径，配置须包含可直接读取的本机 MySQL/Redis 连接值，不能提交或打印文件内容。`JAVA_HOME` 选择项目 JDK；Maven Wrapper、Node 与版本要求见各端工程入口。

```powershell
# 准备运行期依赖列表和探针，编译中间文件统一进入 target。
New-Item -ItemType Directory -Force backend/target/acceptance/probes | Out-Null
./backend/mvnw.cmd -f backend/pom.xml -s backend/.mvn/settings.xml dependency:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=target/acceptance/backend-classpath.txt'
$dependencies = [System.IO.File]::ReadAllText((Join-Path $PWD 'backend/target/acceptance/backend-classpath.txt')).Trim()
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp $dependencies -d backend/target/acceptance/probes tools/acceptance/java/VerificationEnvironment.java tools/acceptance/java/DatabaseProbe.java tools/acceptance/java/SessionClockProbe.java

# 指向已核实的新平台配置，不在命令行写连接密码。
$env:DATAOCEAN_LOCAL_CONFIG = (Resolve-Path backend/src/main/resources/application-local.yml).Path
node tools/acceptance/iam-security-boundaries.mjs
node tools/acceptance/iam-transactions.mjs
node tools/acceptance/iam-dependency-faults.mjs
```

三组按顺序运行，避免相互改变安全版本。安全边界组验证真实会话时限、并发认证、最后恢复途径、历史恢复及组合权限；其中管理员停用／恢复会使已有浏览器会话失效。事务组只为自己的命令临时创建具名失败 CHECK，`finally` 移除同名约束，验证审计和 Outbox 失败时整笔回滚；不删除业务表、不修改已有约束或数据库全局开关。租约测试直接读取生产 Mapper 的更新条件，不复制另一套 SQL。

依赖故障组在 `18082` 启动独立正式应用，以 `13307`、`16379` 本地代理连接当前配置中的 MySQL、Redis，使用独立会话命名空间。仅切断本次代理连接，不停止共享服务；结束时关闭自己的进程和代理。端口占用时先核对归属，不终止未知进程。

会话标识、核对凭据和密码只保留在当前进程中；数据库探针只输出计数、状态和错误分类。报告分别保存在 `output/acceptance/iam-first-stage`。浏览器通过 ego-lite 单独操作，证据放 `output/playwright`；API 和数据库通过不代表界面、原生缩放或后续 AI/SSE 已验收。

各类原始证据齐备后运行 `node tools/acceptance/report.mjs` 汇总，缺少必要证据或存在失败会中止生成，不把未执行场景写成通过。
