# 后端设计

正式后端采用 Java 与 Python，基础技术栈、[应用职责](../architecture/服务职责与数据流.md)、[存储分工与身份层级](../architecture/存储与会话状态.md)、[调用与任务协议](../architecture/调用与任务协议.md)及[工程组织](../engineering/工程组织与版本基线.md)已确定。当前采用[本地运行方式](../engineering/工程组织与版本基线.md#本地运行与依赖环境)，基础依赖和独立验证程序已可运行；物理模型、逐接口结构、正式业务配置、容量与保留参数仍待落实。整体阶段见[文档导航](../README.md#当前阶段)。

## 已确认的技术栈

| 范围 | 已确认选择 | 用途与边界 |
| --- | --- | --- |
| Java 后端 | JDK 17、Spring Boot、MyBatis-Plus、Spring Security、Hutool | 业务管理、身份与权限、元数据采集、受控 SQL 执行、业务记录及对前端统一提供 API；跨端边界见应用职责 |
| 登录态 | Spring Security、Spring Session Redis | Cookie 携带服务端登录会话，Java 通过 UserContext 读取请求用户；退出失效、内部服务 JWT 和凭据注入统一见[认证方案](../architecture/认证与身份上下文.md) |
| Python AI 后端 | LangGraph、FastAPI | Python 主要负责 Agent 调用与流程编排；LangGraph 承担 Agent 编排，FastAPI 承担 HTTP API 服务 |
| 存储组件 | MySQL 8 系列、Redis、Milvus | 平台 MySQL 保存业务数据和会话历史，Redis 保存 LangGraph 检查点并承担缓存与临时协调，Milvus 保存向量；细则见存储分工 |

Java 基线已确定为 JDK 17，平台数据库使用 MySQL 8 系列，不预先锁定 8.4；MySQL 的具体小版本与补丁在部署基线中固定。接入业务源的数据库类型及版本按适配器能力单独核验，不由平台库版本推定。

前端选择维护在[前端技术选型](../frontend/README.md#前端技术选型)，各端不默认沿用现有业务代码中的依赖。

Hutool 是 Java 通用工具库，包含字符串、日期、集合、文件与 IO 等工具。优先复用 JDK、现有框架及 Hutool 已有的适用能力，不为已有能力重复封装。它不替代业务服务、安全框架或持久化分层。用途参考 [Hutool 官方仓库](https://github.com/chinabugotech/hutool)；框架职责参考 [LangGraph 官方概述](https://docs.langchain.com/oss/python/langgraph/overview)及 [FastAPI 官方文档](https://fastapi.tiangolo.com/)。

## 版本基线与验证边界

| 内容 | 已认可的工程基线 | 约束 |
| --- | --- | --- |
| Java 运行与构建 | JDK 17、Maven 3.9.16 | Maven Wrapper 固定版本，构建目标及应用运行遵循 JDK 17 |
| Java 框架 | Spring Boot 4.1.2 | Spring Security、Spring Session、Jackson 等配套依赖跟随 Boot BOM，不从旧示例单独钉版本 |
| 持久化与工具 | MyBatis-Plus 3.5.18、Hutool 5.8.48 | 使用 Boot 4 对应 starter，分页与 JSqlParser 配套依赖通过 MP BOM 对齐并验证 |
| Python 运行与 API | Python 3.14、FastAPI 0.163.1 | 使用 uv 和同一 `uv.lock`；Uvicorn、Pydantic、HTTP 客户端及模型 SDK 配套求解并锁定 |
| 图与检查点 | LangGraph 1.2.22、langgraph-checkpoint 4.3.2、langgraph-checkpoint-redis 0.6.0 | API/Worker 同包同锁，Redis 适配器与图版本共同验证；Python redis 包版本不等同 Redis 服务端版本 |
| 平台数据库 | MySQL 8 系列、Redis 8.10.3 | MySQL 小版本与补丁仍待部署确认；Redis 持久化与检查点隔离按存储方案落实 |
| 向量服务与 SDK | Milvus 2.6.26、PyMilvus 2.6.17 | 按官方对应组合验证，不机械要求服务端和 SDK 补丁号相同 |

基线依据参考 [Spring Boot 要求](https://docs.spring.io/spring-boot/system-requirements.html)、[MyBatis-Plus 安装](https://baomidou.com/en/getting-started/install/)、[Redis Checkpointer 依赖](https://github.com/redis-developer/langgraph-redis/blob/main/pyproject.toml)及 [Milvus 对应表](https://milvus.io/docs/v2.6.x/release_notes.md)。具体版本只在本节维护，共用工程文档链接引用。

独立合成程序已验证 Windows 下的 Java 编译启动、Python 依赖安装与 API / Worker、会话及服务认证、分页、SSE、幂等、协作取消、检查点续作和最小索引切换。Redis / Milvus 采用上述基线，检查点使用独立 Redis 与数据卷，进程强制中断、隔离备份还原和索引恢复已验证。持久化设计见[存储边界](../architecture/存储与会话状态.md#redis-检查点的持久化与恢复边界)，具体场景见[验证说明](../../tools/technical-validation/README.md)，本机运行依据见[环境说明](../../.dataocean/local-environment.md#验证入口与依据)。

这些结果只覆盖独立合成验证程序，正式业务工程尚未重构。Linux 完整安装、真实模型与业务 SQL 取消、浏览器及代理、运行中任意节点崩溃后的自动接管、整机断电或磁盘损坏、跨机备份恢复及完整知识发布门槛仍待验证；准确版本、配置及实际测试边界保留在验证证据中。

Java 格式检查采用 Spotless，命名等非格式检查保留 Checkstyle 的兼容评估。插件、格式引擎和静态检查器分别锁定并验证 JDK 17 兼容性，不为检查工具改变运行基线；具体组合仍待验证，参考 [Checkstyle JRE 要求](https://checkstyle.org/)和 [格式引擎要求](https://github.com/google/google-java-format#using-the-formatter)。Java 测试采用 Spring Boot 测试体系，JUnit 配套版本由 Boot 管理。

Python 使用 Ruff 和 pytest，精确工具版本随开发依赖锁定。测试命名、模块对应及构建入口见[工程基线](../engineering/工程组织与版本基线.md#构建检查与测试定位)，不在本页重复目录规则。

## 编码约定

Java 与 Python 的通用命名、目录、复用、依赖、配置、注释及测试原则统一见[三端共用总则](../engineering/代码风格与项目组织规范.md)。Java 分层、`entity` 分类、类与文件名后缀、参数和显式导入继续维护在[Java 编码细则](编码规范.md)；各端细则不复制总则正文。

跨 Java 与 Python 的职责见[服务职责与数据流](../architecture/服务职责与数据流.md)，用户、会话、请求归属及检查点要求见[存储与会话状态](../architecture/存储与会话状态.md)，命令、流式输出、取消、超时及恢复见[调用与任务协议](../architecture/调用与任务协议.md)，接口和部署细化见[架构待定事项](../architecture/README.md#需要继续讨论的架构问题)。

登录会话、退出与失效、Java／Python 服务 JWT 及凭据文件注入已按[认证方案](../architecture/认证与身份上下文.md)确定；`UserContext` 的请求线程生命周期、清理与异步传递遵守[Java 当前用户规范](编码规范.md#当前用户与线程上下文)。具体兼容库、过滤链、序列化与运行配置仍须实现验证，不将方案确认视为已接入认证。

知识内容、索引代次、Embedding 生效配置、暂停禁止及回退的跨端约束统一见[知识版本与索引一致性](../architecture/知识版本与索引一致性.md)，不在 Java 与 Python 各维护一套生效判断。

SQL 只读校验、输出限制、实时撤权、秘密保护及关键审计提交统一见[安全执行与审计](../architecture/安全执行与审计.md)，具体解析器、方言与保护实现按该边界验收。

## 负责的内容

后端设计负责业务规则的服务端执行、身份认证、操作与数据权限、持久化、任务状态、接口实现和运行配置。前端负责交互与呈现，跨端协议、错误语义和系统边界统一维护在[系统架构](../architecture/README.md)。

## 设计输入

- [产品模块解说](../product/README.md)定位所需业务章，详细规则读方案原文。
- [人员权限](../product/modules/02-people-access.md)明确后台页面和操作分离、办理归属，以及问数数据源访问与输出限制来源；不能用隐藏按钮代替服务端检查。
- [数据源接入](../product/modules/03-datasources.md)、[知识发布](../product/modules/05-knowledge-release.md)和[问数](../product/modules/06-query.md)提供只读、版本和执行边界。
- [现有源码定位](../source-map/README.md)仅供理解仍保留的代码与评估复用，不定义新版架构。

## 后端技术选型与细化

以已确认技术栈、应用职责和原型需求为依据，继续确定兼容版本、接口、物理数据模型及运行方式。获得明确结论后按实际复杂度增加必要专题，记录选型、用途、理由及约束，不预建空文件、不写候选尝试经过。开始真实实现须有明确实施任务。
