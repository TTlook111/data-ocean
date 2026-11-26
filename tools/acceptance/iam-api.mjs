import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { IdentityClient } from "./iam-client.mjs";

/**
 * 每次验收使用独立名称，保留真实审计，不重置已有开发库。
 */
const run = `a${Date.now().toString(36)}`;

/**
 * 报告只记录用例和结果，不记录 Cookie、令牌、密码或散列。
 */
const results = [];

/**
 * 用户明确指定的开发密码，与项目开发初始化资源一致。
 */
const password = (
  await fs.readFile(
    new URL(
      "../../backend/src/main/resources/development/initial-password.txt",
      import.meta.url,
    ),
    "utf8",
  )
).trimEnd();

/**
 * 当前真实应用的开发管理员会话。
 */
const admin = new IdentityClient(process.env.DATAOCEAN_ACCEPTANCE_URL);

/**
 * 以真实请求核对允许和拒绝场景，失败立即保留报告。
 */
async function test(name, action) {
  const started = Date.now();
  try {
    await action();
    results.push({ name, status: "PASS", elapsedMs: Date.now() - started });
    console.log(`PASS ${name}`);
  } catch (error) {
    results.push({
      name,
      status: "FAIL",
      message: error.message,
      elapsedMs: Date.now() - started,
    });
    console.error(`FAIL ${name}: ${error.message}`);
    throw error;
  }
}

/**
 * 检查明确业务错误，不打印完整请求或返回中的核对凭据。
 */
function rejected(response, code) {
  assert.equal(
    response.code,
    code,
    `expectedCode=${code}, actualCode=${response.code}, status=${response.status}`,
  );
}

/**
 * 创建一个正常部门，核对与提交使用同一份资料。
 */
async function createDepartment(name, code, parentId) {
  const proposal = {
    name,
    code,
    parentId,
    managerUserId: null,
    note: null,
    sortOrder: 0,
  };
  const result = await admin.reviewed("/iam/departments", "CREATE", proposal);
  return admin.ok(`/iam/departments/${result.targetId}`);
}

/**
 * 创建一个普通角色，授予上限始终保持为空。
 */
async function createRole(name, permissionCodes) {
  const result = await admin.reviewed("/iam/roles", "CREATE", {
    name,
    description: "首批真实接口验收",
    permissionCodes,
  });
  return admin.ok(`/iam/roles/${result.targetId}`);
}

/**
 * 真实创建人员；初始密码只出现在正式凭据请求。
 */
async function createUser(suffix, department, roles = []) {
  const profile = {
    username: `${run}-${suffix}`,
    displayName: `${run} ${suffix}`,
    membership: {
      primaryDepartmentId: department.id,
      secondaryDepartmentIds: [],
    },
    roles: roles.map((role) => ({ roleId: role.id, version: role.version })),
    phone: null,
    email: null,
    managementNote: null,
  };
  const result = await admin.reviewed("/iam/users", "CREATE", profile, {
    body: { ...profile, initialPassword: password },
  });
  return admin.ok(`/iam/users/${result.targetId}`);
}

/**
 * 走真实首次改密再恢复统一开发密码，不修改待改密规则。
 */
async function activate(person) {
  const client = new IdentityClient(admin.base);
  const login = await client.login(person.username, password);
  assert.equal(login.mustChangePassword, true);
  const before = await client.ok("/auth/me");
  const temporary = `Verify${Date.now()}!`;
  await client.ok("/auth/password/initial", {
    method: "POST",
    body: {
      expectedVersion: before.version,
      newPassword: temporary,
      confirmPassword: temporary,
    },
  });
  rejected(await client.request("/auth/me"), 401001);
  await client.login(person.username, temporary);
  const current = await client.ok("/auth/me");
  await client.ok("/account/password", {
    method: "POST",
    body: {
      expectedVersion: current.version,
      currentPassword: temporary,
      newPassword: password,
      confirmPassword: password,
    },
  });
  await client.login(person.username, password);
  return client;
}

/**
 * 获得当前人员版本，防止测试自身复用过期列表快照。
 */
async function person(id) {
  return admin.ok(`/iam/users/${id}`);
}

try {
  await test("A01/A03/A08 首管理员直接登录、匿名拒绝与登录 CSRF", async () => {
    const anonymous = new IdentityClient(admin.base);
    rejected(await anonymous.request("/iam/users"), 401001);
    rejected(
      await anonymous.request("/auth/login", {
        method: "POST",
        body: { username: "admin", password },
        csrf: false,
      }),
      403001,
    );
    const state = await admin.login("admin", password);
    assert.equal(
      state.mustChangePassword,
      false,
      "首管理员应直接取得正常会话",
    );
    const oldCsrf = admin.token;
    assert.equal(oldCsrf, null);
  });

  const root = (await admin.ok("/iam/departments/tree"))[0];
  const ordinary = await createRole(`${run} 只读角色`, ["users.view"]);
  const alpha = await createDepartment(
    `${run} 甲部门`,
    `${run}-alpha`,
    root.id,
  );
  const beta = await createDepartment(`${run} 乙部门`, `${run}-beta`, root.id);
  let alice;
  let bob;
  let limited;

  await test("A02 新增人员、空初始角色与重复登录名回滚", async () => {
    alice = await createUser("alice", alpha);
    bob = await createUser("bob", alpha, [ordinary]);
    assert.equal(alice.roles.length, 0);
    const initialCount = (
      await admin.ok(`/iam/users?username=${alice.username}`)
    ).total;
    const proposal = {
      username: alice.username,
      displayName: "重复创建",
      membership: { primaryDepartmentId: alpha.id, secondaryDepartmentIds: [] },
      roles: [],
      phone: null,
      email: null,
      managementNote: null,
    };
    rejected(
      await admin.request("/iam/users/impact-preview", {
        method: "POST",
        body: { proposal },
      }),
      409101,
    );
    assert.equal(
      (await admin.ok(`/iam/users?username=${alice.username}`)).total,
      initialCount,
    );
  });

  await test("A04/A05 首次改密受限及同密码、确认不一致拒绝", async () => {
    const client = new IdentityClient(admin.base);
    await client.login(alice.username, password);
    const current = await client.ok("/auth/me");
    assert.deepEqual(current.permissionCodes, []);
    rejected(await client.request("/iam/users"), 403002);
    rejected(
      await client.request("/auth/password/initial", {
        method: "POST",
        body: {
          expectedVersion: current.version,
          newPassword: password,
          confirmPassword: password,
        },
      }),
      409106,
    );
    rejected(
      await client.request("/auth/password/initial", {
        method: "POST",
        body: {
          expectedVersion: current.version,
          newPassword: "Different123",
          confirmPassword: "Different124",
        },
      }),
      400001,
    );
  });

  await test("A06/A07 本人资料、完整改密及无管理权访问边界", async () => {
    limited = await activate(alice);
    const current = await limited.ok("/account");
    const command = crypto.randomUUID();
    await limited.ok("/account/contact", {
      method: "PUT",
      command,
      body: {
        expectedVersion: current.version,
        phone: "13800138000",
        email: "dev@example.test",
      },
    });
    const changed = await limited.ok("/account");
    assert.equal(changed.phone, "13800138000");
    rejected(
      await limited.request("/account/contact", {
        method: "PUT",
        command: crypto.randomUUID(),
        body: {
          expectedVersion: changed.version,
          phone: null,
          email: null,
          userId: bob.id,
        },
      }),
      400001,
    );
    rejected(await limited.request("/iam/users"), 403003);
    rejected(await limited.request("/iam/audit-records"), 403003);
    rejected(
      await limited.request("/account/password", {
        method: "POST",
        body: {
          expectedVersion: changed.version,
          currentPassword: "Wrong123",
          newPassword: "NextPassword123",
          confirmPassword: "NextPassword123",
        },
      }),
      400101,
    );
    await limited.ok("/account/contact", {
      method: "PUT",
      command: crypto.randomUUID(),
      body: { expectedVersion: changed.version, phone: null, email: null },
    });
    assert.equal((await limited.ok("/account")).email, null);
  });

  await test("A14/A20 角色实际生效与服务端组合查询、越界页码", async () => {
    const bobClient = await activate(bob);
    assert.equal((await bobClient.ok("/iam/users")).total >= 3, true);
    rejected(await bobClient.request("/iam/roles"), 403003);
    rejected(
      await bobClient.request("/iam/users", {
        method: "POST",
        command: crypto.randomUUID(),
        body: {},
      }),
      400001,
    );
    const exact = await admin.ok(
      `/iam/users?${new URLSearchParams({ displayName: `${run} bob`, username: bob.username, departmentId: alpha.id, displayStatus: "ENABLED", pageNum: "999", pageSize: "1" })}`,
    );
    assert.equal(exact.total, 1);
    assert.equal(exact.pageNum, 1);
    assert.equal(exact.items[0].id, bob.id);
    assert.equal((await admin.ok("/iam/users?username=%25_%25")).total, 0);
    rejected(await admin.request("/iam/users?sortBy=password_hash"), 400001);
  });

  await test("A21/A22 部门兼任与后代范围按人员去重、拒绝直接移除主部门", async () => {
    const fresh = await person(bob.id);
    const source = await admin.ok(`/iam/departments/${beta.id}`);
    await admin.reviewed(
      "/iam/departments",
      "ADD_SECONDARY",
      {
        members: [{ userId: bob.id, version: fresh.version }],
        reason: "验证兼任",
      },
      {
        id: beta.id,
        version: source.version,
        method: "POST",
        path: "/secondary-members",
      },
    );
    const combined = await admin.ok(
      `/iam/departments/${root.id}/members?includeDescendants=true`,
    );
    assert.equal(combined.items.filter((item) => item.id === bob.id).length, 1);
    const inAlpha = await admin.ok(
      `/iam/departments/${alpha.id}/members?includeDescendants=false`,
    );
    const row = inAlpha.items.find((item) => item.id === bob.id);
    const membership = row.matchingMemberships[0];
    const department = await admin.ok(`/iam/departments/${alpha.id}`);
    rejected(
      await admin.request(`/iam/departments/${alpha.id}/impact-preview`, {
        method: "POST",
        body: {
          action: "END_MEMBERSHIP",
          expectedVersion: department.version,
          proposal: {
            membershipId: membership.id,
            userExpectedVersion: row.version,
            reason: "不得移除主部门",
          },
        },
      }),
      409005,
    );
  });

  await test("A23 并发互设上级不成环", async () => {
    const left = await admin.ok(`/iam/departments/${alpha.id}`);
    const right = await admin.ok(`/iam/departments/${beta.id}`);
    const proposal = (current, parent) => ({
      name: current.name,
      code: current.code,
      parentId: parent.id,
      managerUserId: null,
      note: null,
      sortOrder: 0,
    });
    const lp = proposal(left, right);
    const rp = proposal(right, left);
    const previews = await Promise.all([
      admin.ok(`/iam/departments/${left.id}/impact-preview`, {
        method: "POST",
        body: { action: "UPDATE", expectedVersion: left.version, proposal: lp },
      }),
      admin.ok(`/iam/departments/${right.id}/impact-preview`, {
        method: "POST",
        body: {
          action: "UPDATE",
          expectedVersion: right.version,
          proposal: rp,
        },
      }),
    ]);
    const submitted = await Promise.all([
      admin.request(`/iam/departments/${left.id}`, {
        method: "PUT",
        command: crypto.randomUUID(),
        body: {
          ...lp,
          expectedVersion: left.version,
          reviewToken: previews[0].reviewToken,
        },
      }),
      admin.request(`/iam/departments/${right.id}`, {
        method: "PUT",
        command: crypto.randomUUID(),
        body: {
          ...rp,
          expectedVersion: right.version,
          reviewToken: previews[1].reviewToken,
        },
      }),
    ]);
    assert.equal(submitted.filter((result) => result.code === 200).length, 1);
    assert.equal(
      submitted.filter((result) =>
        [409001, 409002, 409105].includes(result.code),
      ).length,
      1,
    );
  });

  await test("A27/A28 并发重复命令、内容冲突及本人命令核实", async () => {
    const fresh = await person(bob.id);
    const body = { expectedVersion: fresh.version, roles: [] };
    const command = crypto.randomUUID();
    const submitted = await Promise.all([
      admin.request(`/iam/users/${bob.id}/roles`, {
        method: "PUT",
        command,
        body,
      }),
      admin.request(`/iam/users/${bob.id}/roles`, {
        method: "PUT",
        command,
        body,
      }),
    ]);
    assert.equal(
      submitted.every((result) => result.code === 200),
      true,
    );
    const status = await admin.ok(
      `/commands/${command}?operationCode=users.assign`,
    );
    assert.equal(status.state, "COMMITTED");
    rejected(
      await admin.request(`/iam/users/${bob.id}/roles`, {
        method: "PUT",
        command,
        body: {
          ...body,
          roles: [{ roleId: ordinary.id, version: ordinary.version }],
        },
      }),
      409003,
    );
    rejected(
      await limited.request(`/commands/${command}?operationCode=users.assign`),
      404001,
    );
    rejected(
      await admin.request(`/iam/users/${bob.id}/roles`, {
        method: "PUT",
        command: crypto.randomUUID(),
        body: { expectedVersion: fresh.version, roles: [] },
      }),
      409001,
    );
    const audits = await admin.ok(
      `/iam/audit-records?target=${bob.id}&actionCode=users.assign&pageSize=100`,
    );
    assert.equal(
      audits.items.filter((item) => item.commandId === command).length <= 1,
      true,
    );
  });

  await test("A13/A15/A16 角色规范化防重、动作所属页面和受保护项", async () => {
    rejected(
      await admin.request("/iam/roles/impact-preview", {
        method: "POST",
        body: {
          proposal: {
            name: ordinary.name.toUpperCase(),
            description: null,
            permissionCodes: ["users.view"],
          },
        },
      }),
      409102,
    );
    rejected(
      await admin.request("/iam/roles/impact-preview", {
        method: "POST",
        body: {
          proposal: {
            name: `${run} 无页面`,
            description: null,
            permissionCodes: ["users.create"],
          },
        },
      }),
      400001,
    );
    rejected(
      await admin.request("/iam/roles/impact-preview", {
        method: "POST",
        body: {
          proposal: {
            name: `${run} 越过保护`,
            description: null,
            permissionCodes: ["users.view", "users.recover"],
          },
        },
      }),
      403005,
    );
    const latest = await person(alice.id);
    const superRole = (await admin.ok("/iam/roles?pageSize=100")).items.find(
      (role) => role.code === "role-root",
    );
    rejected(
      await admin.request(`/iam/users/${alice.id}/roles`, {
        method: "PUT",
        command: crypto.randomUUID(),
        body: {
          expectedVersion: latest.version,
          roles: [{ roleId: superRole.id, version: superRole.version }],
        },
      }),
      403005,
    );
  });

  await test("A25 部门停用和恢复保持成员账号与角色", async () => {
    const department = await admin.ok(`/iam/departments/${beta.id}`);
    const before = await person(bob.id);
    await admin.reviewed(
      "/iam/departments",
      "DISABLE",
      { reason: "验证部门停用" },
      {
        id: beta.id,
        version: department.version,
        method: "POST",
        path: "/disable",
      },
    );
    assert.equal((await person(bob.id)).displayStatus, before.displayStatus);
    const disabled = await admin.ok(`/iam/departments/${beta.id}`);
    await admin.reviewed(
      "/iam/departments",
      "ENABLE",
      { reason: "验证组织恢复", reviewedMembers: true },
      {
        id: beta.id,
        version: disabled.version,
        method: "POST",
        path: "/enable",
      },
    );
    assert.equal(
      (await admin.ok(`/iam/departments/${beta.id}`)).accessReviewRequired,
      true,
    );
  });

  await test("A10 两个会话在密码更新后按权威版本立即失效", async () => {
    const another = new IdentityClient(admin.base);
    await another.login(alice.username, password);
    const old = await limited.ok("/auth/me");
    const temporary = `Revision${Date.now()}!`;
    await limited.ok("/account/password", {
      method: "POST",
      body: {
        expectedVersion: old.version,
        currentPassword: password,
        newPassword: temporary,
        confirmPassword: temporary,
      },
    });
    assert.equal((await another.request("/account")).status, 401);
    await limited.login(alice.username, temporary);
    const fresh = await limited.ok("/auth/me");
    await limited.ok("/account/password", {
      method: "POST",
      body: {
        expectedVersion: fresh.version,
        currentPassword: temporary,
        newPassword: password,
        confirmPassword: password,
      },
    });
    await limited.login(alice.username, password);
  });

  await test("A31 只读审计按历史身份返回，不开放编辑或删除", async () => {
    const audit = await admin.ok("/iam/audit-records?pageSize=100");
    assert.ok(audit.total > 3);
    const detail = await admin.ok(`/iam/audit-records/${audit.items[0].id}`);
    assert.ok(detail.actor.kind);
    assert.equal(
      (
        await admin.request(`/iam/audit-records/${detail.id}`, {
          method: "DELETE",
        })
      ).status,
      405,
    );
  });
} finally {
  // 无论成功失败都保留分类证据，报告不代替后续数据库和浏览器验收。
  const destination = path.resolve(
    "output/acceptance/iam-first-stage/api-report.json",
  );
  await fs.mkdir(path.dirname(destination), { recursive: true });
  await fs.writeFile(
    destination,
    JSON.stringify(
      {
        run,
        baseUrl: admin.base,
        checkedAt: new Date().toISOString(),
        results,
      },
      null,
      2,
    ),
  );
  console.log({
    passed: results.filter((item) => item.status === "PASS").length,
    failed: results.filter((item) => item.status === "FAIL").length,
    report: destination,
  });
}
