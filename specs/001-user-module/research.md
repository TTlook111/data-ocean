# Research: 用户模块

## JWT 方案选型

**Decision**: 使用 jjwt (io.jsonwebtoken) 库生成和验证 JWT

**Rationale**: jjwt 是 Java 生态最成熟的 JWT 库，API 简洁，支持 HS256/RS256，与 Spring Security 集成良好。

**Alternatives considered**:
- Spring Security OAuth2 Resource Server: 过重，适合 OAuth2 场景，本项目只需简单 JWT
- Nimbus JOSE+JWT: 功能全面但 API 复杂，学习成本高
- Auth0 java-jwt: 轻量但社区活跃度不如 jjwt

## JWT 黑名单方案

**Decision**: 退出登录时将 JWT ID (jti) 写入 Redis，设置 TTL 等于 Token 剩余有效期

**Rationale**: 比维护白名单更节省内存（只存已退出的 Token），Redis TTL 自动清理过期记录。

**Alternatives considered**:
- Token 白名单（每次请求查 Redis）: 每次请求都要查 Redis，性能开销大
- 短有效期 + Refresh Token: 增加复杂度，MVP 阶段不需要
- 数据库存黑名单: 查询性能不如 Redis

## 密码加密方案

**Decision**: BCrypt (strength=10)

**Rationale**: Spring Security 内置支持，业界标准，自带盐值，strength=10 在安全性和性能间平衡。

**Alternatives considered**:
- Argon2: 更安全但 Java 生态支持不如 BCrypt 成熟
- PBKDF2: 安全但性能略差
- SHA-256 + salt: 不推荐，容易实现错误

## 账号锁定策略

**Decision**: 连续 5 次登录失败自动锁定，使用 Redis 计数器（key: `login:fail:{username}`, TTL: 30 分钟）

**Rationale**: Redis 计数器天然支持 TTL 自动重置，不需要定时任务清理。30 分钟窗口内 5 次失败触发锁定。

**Alternatives considered**:
- 数据库记录失败次数: 需要额外清理逻辑
- Spring Security 内置 LockoutPolicy: 需要自定义 AuthenticationProvider，不如直接用 Redis 简单

## 分页方案

**Decision**: MyBatis-Plus 内置分页插件 + PageHelper

**Rationale**: MyBatis-Plus 自带分页拦截器，零配置，返回 IPage 对象包含 total/pages/records。

## 初始数据方案

**Decision**: Flyway V2 迁移脚本插入预定义角色和超级管理员账号

**Rationale**: 保证每个环境初始化一致，超级管理员密码通过环境变量注入（BCrypt 预计算）。
