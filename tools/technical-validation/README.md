# 最小技术链路验证

此目录是独立验证程序，不是新版业务工程。Java 不扫描旧应用、不加载旧本机配置或迁移；Python 不加载旧 `.env`。接口、任务表和生效指针只用于合成测试，正式模型与契约仍须单独设计。

## 运行

先完整读取本机 `.dataocean/local-environment.md`，核对实际主机、工具、端口、容器标签、挂载和 Compose 归属。日常验证由用户先启动 MySQL、Redis、独立检查点 Redis、Milvus 及其 etcd、MinIO。

```powershell
./tools/technical-validation/run.ps1 -RuntimeDirectory '<本机私有运行目录>'
```

负责人明确授权故障注入后，增加 `-StorageFaults` 执行 Redis / Milvus 强制终止、恢复和检查点备份还原；此模式会实际中断服务，使用独立合成任务。

需要 JDK 17、Maven、uv 与 Python 3.13 / 3.14，入口默认使用已安装的 Python 3.13，验证端口为回环 18081 / 18082，临时还原检查点实例使用回环 16380。Java 版本引用 `java/pom.xml`，Python 核心版本引用 `python/pyproject.toml`，精确解析结果固定在同目录 `uv.lock`。

共享基础设施保持原先的独立 Docker 分组：`redis` 项目包含 `redis` / `redis-checkpoint`，`milvus` 项目包含 `milvus` / `etcd` / `minio`，现有 MySQL 单独管理。根 Compose 模板显式指定项目名及所属服务后使用，不放入应用项目；私有环境文件与容器名覆盖配置留在仓库外的本机运行目录，准确路径见本机环境说明。

Redis 会话与队列访问 6379，Checkpointer 单独访问 6380 / DB 0；两实例使用独立卷，AOF `appendfsync always`、`noeviction`、`aof-load-truncated no`。这些参数是当前本地可靠性验证配置，容量和性能尚未验收。

当前 fixture 保护固定检查本机 `mysql:8.0` 容器及 `dataocean-shared-mysql-data` 卷；其他机器应先核对实际拓扑再适配保护条件，不能直接删除检查。临时账号仅有本轮唯一 schema 的权限；现有 root 密码始终在容器内部使用，不导出至验证程序。

输出为 `output/acceptance/technical-chain-*/verification.json`，运行日志复制至指定运行目录。Windows 子进程使用本轮输出目录作为临时运行区，避免默认临时目录及 Docker 管道访问限制；容器 fixture 准备与清理由 PowerShell 执行。测试账号密码、签名密钥只供本轮使用，完成后逐项清理，不进入报告或 Git。

## 验证范围

- JDK 17 真实构建及 Spring Boot 启动，Spring Security / Spring Session Redis 登录、会话 ID 轮换、CSRF 拒绝、退出失效，MyBatis-Plus 分页与 Hutool 调用。
- API / Worker 为不同真实进程，Java / Python 分别用一次性 RSA 密钥签发 RS256 服务 JWT，拒绝缺失、错误签名、错误签发者或接收方、过期令牌。
- Redis Streams 命令去重与入队，MySQL 保存任务事实，真实 HTTP SSE 首次订阅、断线后按游标重放；重复命令不重复执行，同编号不同内容拒绝。
- 长时间合成节点的协作取消、Java 权威取消状态及迟到完成拒绝。
- LangGraph 真实 Redis Checkpointer 暂停后，终止旧 Worker 进程树并核对退出；重启 Java 与 Worker 后恢复，保留原任务、会话与已完成节点，递增执行代次并拒绝旧代次回写。
- 故障模式对两个 Redis 使用 SIGKILL，核对退出码 137、进程身份变化、相同检查点与登录态保留、故障期间拒绝交付以及 MySQL 任务事实仍可核实；随后恢复原任务，已完成节点不重复执行。
- 两个 Redis 停机冷备完整数据目录并生成 SHA-256 清单；检查点备份还原到隔离卷与临时实例，核对原检查点。备份中未完成而 MySQL 已完成的任务仍拒绝重跑。
- `python/verify_aof.py` 可另验证 AOF 增量：在空验证实例先保存 RDB，再写入 String / JSON / Stream；由外层故障工具实际 SIGKILL 后，核对全部新值保留并清理。此项与恢复已有快照分开记录。
- 合成四维向量在 Milvus 两个独立 Collection 中 Strong 检索；MySQL 最小生效指针并发 CAS 只有一次提交成功；固定旧 Collection 的读取保持旧结果，并验证回退。维度错误写入失败后原指针保持不变。
- 故障模式对 Milvus 使用 SIGKILL 后重启，核对 MySQL 指针、新旧检索结果与回退仍正确；没有以重建索引代替数据恢复。
- 清理仅处理本轮登记的 schema、账号、Collection、命名空间、索引和进程，不扫描或清空业务库。

## 实际限制

这是最小技术可行性验证，不能当作正式业务验收：未验证真实模型 / Embedding、真实业务 SQL 取消、浏览器与反向代理、TLS、完整认证及权限生命周期、运行中任意节点崩溃后的自动租约接管、整机断电与磁盘损坏、事件过期与慢客户端、大规模索引和完整知识发布门槛。普通模式不注入存储故障，其结果不能替代 `-StorageFaults` 的实际证据。

暂停后重启续作与运行中任意节点崩溃恢复分别验收。最小生效指针的事务竞争不能代替多范围发布、模型兼容门禁、回包丢失及回退依赖验证。既有依赖版本与文档基线不同时，结果只覆盖实测版本，不自动更改已定选型。

框架用法核对来源：[Spring Boot](https://github.com/spring-projects/spring-boot/tree/v4.1.0)、[Redis Checkpointer](https://github.com/redis-developer/langgraph-redis)、[MyBatis-Plus](https://github.com/baomidou/mybatis-plus)、[Redis 持久化](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)、[Redis 连接恢复](https://redis.io/docs/latest/develop/clients/redis-py/produsage/)。Milvus 2.6.26 镜像的 SDK 版本字符串为构建号，其 `b8d2ca4` 与[官方发布](https://github.com/milvus-io/milvus/releases/tag/v2.6.26)一致，验证记录同时保存镜像摘要及完整构建号。
