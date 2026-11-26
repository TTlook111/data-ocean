import assert from "node:assert/strict";
import {
  IdentityFixture,
  AcceptanceReport,
  developmentPassword,
  reject,
} from "./iam-fixtures.mjs";
import { IdentityClient } from "./iam-client.mjs";
import { sessionClock } from "./platform-probes.mjs";

/**
 * 本组使用真实平台 API、会话 Redis 和完整授权图，不修改共享基础服务状态。
 */
const fixture = await new IdentityFixture().start();

/**
 * 与其他分类证据分别保存。
 */
const report = new AcceptanceReport();

/**
 * 显式定位初始化模板仅用于构造验收数据，所有实际资格仍由服务端验证。
 */
const superRole = (
  await fixture.admin.ok("/iam/roles?pageSize=100")
).items.find((role) => role.code === "role-root");

/**
 * 第二条独立恢复途径用于验证并发保护，结束后保持开发账号可继续使用。
 */
let backup;
let backupClient;

try {
  // 初始化也纳入清理边界，准备阶段遇到依赖失败不能遗留额外管理途径。
  backup = await fixture.user("backup");
  await fixture.protectedRoles(backup.id, [superRole]);
  backupClient = await fixture.activate(backup);
  await report.test(
    "A09 真实 Redis 空闲与最长时限，身份和 CSRF 轮询均不续期",
    async () => {
      const user = await fixture.user("clock");
      const client = await fixture.activate(user);
      const before = sessionClock(client, "dataocean.lastUserActivityAt").epoch;
      await client.ok("/auth/me");
      await client.ok("/auth/csrf");
      assert.equal(
        sessionClock(client, "dataocean.lastUserActivityAt").epoch,
        before,
      );
      sessionClock(client, "dataocean.lastUserActivityAt", 1);
      reject(await client.request("/account"), 401002);
      await client.login(user.username, developmentPassword);
      sessionClock(client, "dataocean.authenticatedAt", 1);
      reject(await client.request("/account"), 401002);
    },
  );

  await report.test(
    "A11 多路真实验密与停用并发，任何旧 Cookie 均不能形成可用资格",
    async () => {
      const user = await fixture.user("loginrace");
      const existing = await fixture.activate(user);
      const current = await fixture.admin.ok(`/iam/users/${user.id}`);
      const clients = Array.from(
        { length: 8 },
        () => new IdentityClient(fixture.admin.base),
      );
      await Promise.all(
        clients.map(async (client) => {
          client.token = await client.ok("/auth/csrf");
        }),
      );
      const pending = clients.map((client) =>
        client.request("/auth/login", {
          method: "POST",
          body: { username: user.username, password: developmentPassword },
        }),
      );
      await fixture.admin.reviewed(
        "/iam/users",
        "DISABLE",
        { reason: "与验密过程并发停用" },
        {
          id: current.id,
          version: current.version,
          path: "/disable",
          method: "POST",
        },
      );
      const responses = await Promise.all(pending);
      for (let index = 0; index < responses.length; index++) {
        // 登录先完成或后完成都不能绕过最终权威认证版本；限流拒绝同样不产生资格。
        assert.equal(
          [200, 401003, 401002, 429001].includes(responses[index].code),
          true,
        );
        if (responses[index].code === 200)
          assert.equal((await clients[index].request("/account")).status, 401);
      }
      assert.equal((await existing.request("/account")).status, 401);
    },
  );

  await report.test(
    "A12/A33 两个真实会话交错请求与拒绝后身份不串用",
    async () => {
      const first = await fixture.user("identity-one");
      const second = await fixture.user("identity-two");
      const firstClient = await fixture.activate(first);
      const secondClient = await fixture.activate(second);
      for (let turn = 0; turn < 8; turn++) {
        const responses = await Promise.all([
          firstClient.ok("/account"),
          secondClient.ok("/account"),
        ]);
        assert.equal(responses[0].id, first.id);
        assert.equal(responses[1].id, second.id);
        reject(await firstClient.request("/iam/users"), 403003);
        assert.equal((await secondClient.ok("/account")).id, second.id);
      }
    },
  );

  await report.test(
    "A17 两个管理动作的上限不能借受保护配置提高本人分配上限",
    async () => {
      let protectedRole = await fixture.role("配置职责", ["roles.view"]);
      protectedRole = await fixture.protectedPermissions(protectedRole.id, [
        "roles.view",
        "roles.protected",
      ]);
      const directory = await fixture.admin.ok("/iam/permissions");
      const all = directory.flatMap((page) =>
        page.operations.map((operation) => operation.code),
      );
      await fixture.grants(protectedRole.id, all);
      let assigningRole = await fixture.role("分配职责", [
        "users.view",
        "users.assign",
      ]);
      assigningRole = await fixture.grants(assigningRole.id, ["users.view"]);
      const actor = await fixture.user("selflimit");
      await fixture.protectedRoles(actor.id, [protectedRole, assigningRole]);
      const actorClient = await fixture.activate(actor);
      reject(
        await actorClient.request(
          `/iam/roles/${assigningRole.id}/impact-preview`,
          {
            method: "POST",
            body: {
              action: "GRANT_LIMIT",
              expectedVersion: assigningRole.version,
              proposal: {
                grantablePermissionCodes: ["users.view", "users.create"],
                reason: "不得提高本人其他动作的上限",
              },
            },
          },
        ),
        403004,
      );
      assert.deepEqual(
        (await fixture.admin.ok(`/iam/roles/${assigningRole.id}/grant-limit`))
          .grantablePermissionCodes,
        ["users.view"],
      );
    },
  );

  await report.test(
    "A18 保留人数但清空完整可恢复图仍必须整笔回滚",
    async () => {
      const role = await fixture.admin.ok(`/iam/roles/${superRole.id}`);
      const proposal = {
        grantablePermissionCodes: ["users.recover"],
        reason: "验证完整恢复图而非仅人数",
      };
      const preview = await fixture.admin.ok(
        `/iam/roles/${role.id}/impact-preview`,
        {
          method: "POST",
          body: {
            action: "GRANT_LIMIT",
            expectedVersion: role.version,
            proposal,
          },
        },
      );
      reject(
        await fixture.admin.request(`/iam/roles/${role.id}/grant-limit`, {
          method: "PUT",
          command: crypto.randomUUID(),
          body: {
            ...proposal,
            expectedVersion: role.version,
            reviewToken: preview.reviewToken,
          },
        }),
        409104,
      );
      assert.equal(
        (await fixture.admin.ok(`/iam/roles/${role.id}`)).version,
        role.version,
      );
      assert.ok(
        (await fixture.admin.ok(`/iam/roles/${role.id}/grant-limit`))
          .grantablePermissionCodes.length > 2,
      );
    },
  );

  await report.test(
    "A18 两笔并发停用仅允许一笔成功，实际可用恢复主体始终保留",
    async () => {
      const me = await fixture.admin.ok("/auth/me");
      const other = await backupClient.ok("/auth/me");
      const inputs = [
        { actor: fixture.admin, id: me.userId, version: me.version },
        { actor: backupClient, id: other.userId, version: other.version },
      ];
      for (const item of inputs)
        item.preview = await item.actor.ok(
          `/iam/users/${item.id}/impact-preview`,
          {
            method: "POST",
            body: {
              action: "DISABLE",
              expectedVersion: item.version,
              proposal: { reason: "并发停用恢复人员" },
            },
          },
        );
      const submitted = await Promise.all(
        inputs.map((item) =>
          item.actor.request(`/iam/users/${item.id}/disable`, {
            method: "POST",
            command: crypto.randomUUID(),
            body: {
              expectedVersion: item.version,
              reason: "并发停用恢复人员",
              reviewToken: item.preview.reviewToken,
            },
          }),
        ),
      );
      assert.equal(submitted.filter((result) => result.code === 200).length, 1);
      assert.equal(
        submitted.filter((result) => [409002, 409104].includes(result.code))
          .length,
        1,
      );
      const winner = submitted[0].code === 200 ? 0 : 1;
      const survivor = inputs[1 - winner].actor;
      const target = await survivor.ok(`/iam/users/${inputs[winner].id}`);
      const currentRoles = await Promise.all(
        target.roles.map((role) => survivor.ok(`/iam/roles/${role.id}`)),
      );
      await survivor.reviewed(
        "/iam/users",
        "ENABLE",
        {
          membership: {
            primaryDepartmentId: target.primaryDepartment.id,
            secondaryDepartmentIds: target.secondaryDepartments.map(
              (department) => department.id,
            ),
          },
          roles: currentRoles.map((role) => ({
            roleId: role.id,
            version: role.version,
          })),
          reviewedAccess: true,
          reason: "验收后恢复开发主体",
        },
        {
          id: target.id,
          version: target.version,
          method: "POST",
          path: "/enable",
        },
      );
      await fixture.admin.login("admin", developmentPassword);
      backupClient = new IdentityClient(fixture.admin.base);
      await backupClient.login(backup.username, developmentPassword);
    },
  );

  await report.test(
    "A19 从真实审计恢复已知分配，错误主体与扩权后的共享角色均拒绝",
    async () => {
      const target = await fixture.user("recovery");
      let manage = await fixture.role("历史管理角色", ["users.view"]);
      manage = await fixture.protectedPermissions(manage.id, [
        "users.view",
        "users.recover",
      ]);
      const initial = await fixture.protectedRoles(target.id, [manage]);
      const history = await fixture.auditFor(target.id, initial.commandId);
      await fixture.protectedRoles(target.id, []);
      let current = await fixture.admin.ok(`/iam/users/${target.id}`);
      const proposal = {
        expectedVersion: current.version,
        sourceAuditId: history.id,
        reason: "恢复真实历史分配",
      };
      const preview = await fixture.admin.ok(
        `/iam/users/${target.id}/recovery-preview`,
        { method: "POST", body: proposal },
      );
      await fixture.admin.ok(`/iam/users/${target.id}/recover`, {
        method: "POST",
        command: crypto.randomUUID(),
        body: { ...proposal, reviewToken: preview.reviewToken },
      });
      assert.equal(
        (await fixture.admin.ok(`/iam/users/${target.id}`)).roles.some(
          (role) => role.id === manage.id,
        ),
        true,
      );
      const wrong = await fixture.admin.ok(`/iam/users/${backup.id}`);
      reject(
        await fixture.admin.request(
          `/iam/users/${backup.id}/recovery-preview`,
          {
            method: "POST",
            body: {
              expectedVersion: wrong.version,
              sourceAuditId: history.id,
              reason: "不能借他人历史恢复",
            },
          },
        ),
        404001,
      );
      await fixture.protectedRoles(target.id, []);
      await fixture.protectedPermissions(manage.id, [
        "users.view",
        "users.recover",
        "users.assign",
      ]);
      current = await fixture.admin.ok(`/iam/users/${target.id}`);
      reject(
        await fixture.admin.request(
          `/iam/users/${target.id}/recovery-preview`,
          {
            method: "POST",
            body: {
              expectedVersion: current.version,
              sourceAuditId: history.id,
              reason: "共享角色扩权后不能直接恢复",
            },
          },
        ),
        409301,
      );
    },
  );

  await report.test(
    "A26 仅资料编辑权不能让组合表单中的归属变化半成功",
    async () => {
      const editRole = await fixture.role("仅资料编辑", [
        "users.view",
        "users.edit",
      ]);
      const actor = await fixture.user("editor", fixture.root, [editRole]);
      const actorClient = await fixture.activate(actor);
      const target = await fixture.user("mixed");
      const destination = await fixture.department("mixed-target");
      const proposal = {
        displayName: "不能半成功的新姓名",
        membership: {
          primaryDepartmentId: destination.id,
          secondaryDepartmentIds: [],
        },
        phone: null,
        email: null,
        managementNote: null,
      };
      reject(
        await actorClient.request(`/iam/users/${target.id}/impact-preview`, {
          method: "POST",
          body: { action: "UPDATE", expectedVersion: target.version, proposal },
        }),
        403003,
      );
      const valid = {
        ...proposal,
        membership: {
          primaryDepartmentId: fixture.root.id,
          secondaryDepartmentIds: [],
        },
      };
      const preview = await actorClient.ok(
        `/iam/users/${target.id}/impact-preview`,
        {
          method: "POST",
          body: {
            action: "UPDATE",
            expectedVersion: target.version,
            proposal: valid,
          },
        },
      );
      reject(
        await actorClient.request(`/iam/users/${target.id}`, {
          method: "PUT",
          command: crypto.randomUUID(),
          body: {
            ...proposal,
            expectedVersion: target.version,
            reviewToken: preview.reviewToken,
          },
        }),
        403003,
      );
      const unchanged = await fixture.admin.ok(`/iam/users/${target.id}`);
      assert.equal(unchanged.displayName, target.displayName);
      assert.equal(unchanged.primaryDepartment.id, target.primaryDepartment.id);
      reject(
        await actorClient.request(
          `/iam/role-options?purpose=USER_ASSIGN&userId=${target.id}`,
        ),
        403003,
      );
      reject(
        await actorClient.request(
          "/iam/department-options?purpose=USER_TRANSFER",
        ),
        403003,
      );
    },
  );
} finally {
  try {
    // 独立备用主体只用于本组并发用例，结束后移出管理角色，避免下次验收多出恢复途径。
    if (backup) {
      await fixture.admin.login("admin", developmentPassword);
      await fixture.protectedRoles(backup.id, []);
    }
  } finally {
    // 清理失败也保留已经观察到的真实用例结果，不用成功数量掩盖错误。
    await report.save("security-boundaries", fixture.prefix);
  }
}
