# Quickstart: 用户模块

## Prerequisites

- JDK 17+
- Maven 3.8+
- MySQL 8 (或 Docker)
- Redis 7+ (或 Docker)

## Quick Setup

```bash
# 1. 启动依赖服务
docker compose up -d mysql redis

# 2. 进入后端目录
cd backend

# 3. 配置环境变量（或修改 application-dev.yml）
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=dataocean
export DB_USERNAME=root
export DB_PASSWORD=root123
export REDIS_HOST=localhost
export REDIS_PORT=6379
export JWT_SECRET=your-256-bit-secret-key-here-must-be-long-enough
export ADMIN_PASSWORD=Admin123!

# 4. 启动应用（Flyway 自动建表和初始化数据）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 5. 验证
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin123!"}'
```

## Default Accounts

| Username | Password | Role |
|----------|----------|------|
| admin | (环境变量 ADMIN_PASSWORD) | 超级管理员 |

## Key Configuration

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&serverTimezone=Asia/Shanghai
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}

jwt:
  secret: ${JWT_SECRET}
  expiration: 86400  # 24 hours in seconds

security:
  login:
    max-attempts: 5
    lock-duration: 1800  # 30 minutes in seconds
```

## Testing

```bash
# 运行单元测试
mvn test

# 运行集成测试（需要 Docker）
mvn verify -P integration-test
```
