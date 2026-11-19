import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { IdentityClient } from "./iam-client.mjs";

/**
 * 所有实际服务用例复用同一开发凭据来源，不把秘密响应保存到报告。
 */
export const developmentPassword = (
  await fs.readFile(
    new URL(
      "../../backend/src/main/resources/development/initial-password.txt",
      import.meta.url,
    ),
    "utf8",
  )
).trimEnd();

/**
 * 合成记录使用当前运行独立前缀，不重置既有开发数据和审计历史。
 */
export class IdentityFixture {
  /**
   * 同一次验收的稳定合成命名空间。
   */
  prefix = `b${Date.now().toString(36)}${process.pid.toString(36)}`;

  /**
   * 当前开发管理员的真实 Cookie 会话。
   */
  admin = new IdentityClient(process.env.DATAOCEAN_ACCEPTANCE_URL);

  /**
   * 实际初始化的根部门引用。
   */
  root;

  /**
   * 实际管理员标识，只从当前身份读取，不依赖自增起点。
   */
  actorId;

  /**
   * 登录并读取当前根组织，不依赖固定数据库 ID。
   */
  async start(password = developmentPassword) {
    // 隔离环境可传入已按首登要求完成变更的管理员密码。
    await this.admin.login("admin", password);
    this.actorId = (await this.admin.ok("/auth/me")).userId;
    this.root = (await this.admin.ok("/iam/departments/tree"))[0];
    assert.ok(this.root?.id);
    return this;
  }

  /**
   * 创建明确资料的普通角色，授予上限不会隐式跟随使用权。
   */
  async role(name, permissionCodes) {
    const created = await this.admin.reviewed("/iam/roles", "CREATE", {
      name: `${this.prefix} ${name}`,
      description: "首批真实边界验收",
      permissionCodes,
    });
    return this.admin.ok(`/iam/roles/${created.targetId}`);
  }

  /**
   * 创建当前根组织下的真实部门。
   */
  async department(name, parentId = this.root.id) {
    const created = await this.admin.reviewed("/iam/departments", "CREATE", {
      name: `${this.prefix} ${name}`,
      code: `${this.prefix}-${name}`,
      parentId,
      managerUserId: null,
      note: null,
      sortOrder: 0,
    });
    return this.admin.ok(`/iam/departments/${created.targetId}`);
  }

  /**
   * 创建人员时只在正式凭据请求携带初始密码。
   */
  async user(name, department = this.root, roles = []) {
    const profile = {
      username: `${this.prefix}-${name}`,
      displayName: `${this.prefix} ${name}`,
      membership: {
        primaryDepartmentId: department.id,
        secondaryDepartmentIds: [],
      },
      roles: roles.map((role) => ({ roleId: role.id, version: role.version })),
      phone: null,
      email: null,
      managementNote: null,
    };
    const created = await this.admin.reviewed("/iam/users", "CREATE", profile, {
      body: { ...profile, initialPassword: developmentPassword },
    });
    return this.admin.ok(`/iam/users/${created.targetId}`);
  }

  /**
   * 通过实际必要改密和正常改密，将保留的开发账号最终统一为用户指定密码。
   */
  async activate(person) {
    const client = new IdentityClient(this.admin.base);
    const login = await client.login(person.username, developmentPassword);
    assert.equal(login.mustChangePassword, true);
    const before = await client.ok("/auth/me");
    const temporary = `Temporary${Date.now()}!`;
    await client.ok("/auth/password/initial", {
      method: "POST",
      body: {
        expectedVersion: before.version,
        newPassword: temporary,
        confirmPassword: temporary,
      },
    });
    await client.login(person.username, temporary);
    const current = await client.ok("/auth/me");
    await client.ok("/account/password", {
      method: "POST",
      body: {
        expectedVersion: current.version,
        currentPassword: temporary,
        newPassword: developmentPassword,
        confirmPassword: developmentPassword,
      },
    });
    await client.login(person.username, developmentPassword);
    return client;
  }

  /**
   * 经真实受保护核对调整个人完整分配，目标和角色版本都使用当前事实。
   */
  async protectedRoles(personId, roles, actor = this.admin) {
    const person = await actor.ok(`/iam/users/${personId}`);
    const references = [];
    for (const role of roles) {
      const current = await actor.ok(`/iam/roles/${role.id}`);
      references.push({ roleId: current.id, version: current.version });
    }
    return actor.reviewed(
      "/iam/users",
      "PROTECTED_ROLES",
      { roles: references, reason: "核对实际受保护分配" },
      {
        id: personId,
        version: person.version,
        path: "/protected-roles",
        method: "PUT",
      },
    );
  }

  /**
   * 修改完整受保护功能集合，不借提案新授予的能力证明当前有权。
   */
  async protectedPermissions(roleId, permissionCodes) {
    const role = await this.admin.ok(`/iam/roles/${roleId}`);
    await this.admin.reviewed(
      "/iam/roles",
      "PROTECTED_PERMISSIONS",
      {
        name: role.name,
        description: role.description,
        permissionCodes,
        reason: "核对真实完整功能",
      },
      {
        id: role.id,
        version: role.version,
        path: "/protected-permissions",
        method: "PUT",
      },
    );
    return this.admin.ok(`/iam/roles/${roleId}`);
  }

  /**
   * 授予上限独立保存，不将角色使用能力自动复制成上限。
   */
  async grants(roleId, grantablePermissionCodes) {
    const role = await this.admin.ok(`/iam/roles/${roleId}`);
    await this.admin.reviewed(
      "/iam/roles",
      "GRANT_LIMIT",
      { grantablePermissionCodes, reason: "核对明确授予上限" },
      {
        id: role.id,
        version: role.version,
        path: "/grant-limit",
        method: "PUT",
      },
    );
    return this.admin.ok(`/iam/roles/${roleId}`);
  }

  /**
   * 定位本次命令实际产生的成功审计，列表和详情均走真实权限接口。
   */
  async auditFor(personId, commandId, actor = this.admin) {
    const records = await actor.ok(
      `/iam/audit-records?target=${personId}&pageSize=100`,
    );
    for (const record of records.items.filter(
      (item) => item.targetId === personId && item.outcome === "SUCCESS",
    )) {
      const detail = await actor.ok(`/iam/audit-records/${record.id}`);
      if (detail.commandId === commandId) return detail;
    }
    throw new Error("未找到本次命令对应的成功审计");
  }
}

/**
 * 用例只报告受控状态和时间，不把原始 HTTP 响应或秘密输入写入文件。
 */
export class AcceptanceReport {
  /**
   * 本次分类验收的最小结果集合。
   */
  results = [];

  /**
   * 每个实际场景失败即保留明确问题，不静默跳过。
   */
  async test(name, action) {
    const started = Date.now();
    try {
      await action();
      this.results.push({
        name,
        status: "PASS",
        elapsedMs: Date.now() - started,
      });
      console.log(`PASS ${name}`);
    } catch (error) {
      this.results.push({
        name,
        status: "FAIL",
        message: error.message,
        elapsedMs: Date.now() - started,
      });
      throw error;
    }
  }

  /**
   * 分类证据分别保存，不能用合成或单元结果冒充真实接口完成。
   */
  async save(name, prefix) {
    const destination = path.resolve(
      `output/acceptance/iam-first-stage/${name}.json`,
    );
    await fs.mkdir(path.dirname(destination), { recursive: true });
    await fs.writeFile(
      destination,
      JSON.stringify(
        { prefix, checkedAt: new Date().toISOString(), results: this.results },
        null,
        2,
      ),
    );
    console.log({
      passed: this.results.filter((item) => item.status === "PASS").length,
      failed: this.results.filter((item) => item.status === "FAIL").length,
      report: destination,
    });
  }
}

/**
 * 错误断言只比较业务码，异常信息不打印令牌或 Cookie。
 */
export function reject(response, code) {
  assert.equal(
    response.code,
    code,
    `expectedCode=${code}, actualCode=${response.code}, status=${response.status}`,
  );
}
