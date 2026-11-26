import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { AcceptanceReport, IdentityFixture, reject } from "./iam-fixtures.mjs";
import { lockFixtureGrant, sql } from "./platform-probes.mjs";

/**
 * 读取当前平台库的受控验收结果，查询失败时只报告错误分类。
 */
function rows(statement) {
  const result = sql(statement);
  assert.ok(result.rows, `数据库观察失败：${result.sqlState}/${result.vendorCode}`);
  return result.rows;
}

/**
 * 只返回正在执行验收语句的连接编号，避免读取其它请求的 SQL 文本。
 */
function activeConnections(pattern) {
  return rows(`SELECT ID FROM information_schema.PROCESSLIST
    WHERE DB=DATABASE() AND COMMAND IN ('Query','Execute')
      AND REGEXP_LIKE(INFO, '${pattern}', 'i')`).map(([id]) => id);
}

/**
 * 等到 MySQL 实际观察到唯一请求，再推进锁顺序。
 */
async function waitForQuery(pattern, description) {
  const deadline = Date.now() + 5000;
  do {
    // 只以真实数据库观察作为同步条件。
    const connections = activeConnections(pattern);
    if (connections.length === 1) {
      return connections[0];
    }
    assert.equal(connections.length, 0, `无法唯一定位：${description}`);
    await delay(100);
  } while (Date.now() < deadline);
  throw new Error(`未观察到：${description}`);
}

/**
 * 释放锁前两个 HTTP 请求会保持进行中，提前登记拒绝处理防止未处理的 Promise。
 */
function queue(pending, request) {
  pending.push(request);
  void request.catch(() => {
    // 释放后由 Promise.all 统一验证请求结果。
  });
}

/**
 * 通过真实生命周期接口归档本次合成记录，保留审计。
 */
async function archive(admin, resource, id) {
  for (const [action, lifecycle] of [["DISABLE", "DISABLED"], ["ARCHIVE", "ARCHIVED"]]) {
    // 按当前聚合版本核对并提交生命周期操作。
    const current = await admin.ok(`${resource}/${id}`);
    await admin.reviewed(resource, action, { reason: "角色编辑并发验收结束" }, {
      id,
      version: current.version,
      path: `/${action.toLowerCase()}`,
      method: "POST",
    });
    assert.equal((await admin.ok(`${resource}/${id}`)).lifecycle, lifecycle);
  }
}

// 提前检查当前应用账号可观察自己的 MySQL 连接，避免环境不符时创建测试数据。
assert.equal(rows("SELECT ID FROM information_schema.PROCESSLIST WHERE ID=CONNECTION_ID()").length, 1);
const fixture = await new IdentityFixture().start();
const report = new AcceptanceReport();
const created = [];
let person;

try {
  // 两个角色分别作为编辑目标和低权限账号的授权来源，收权不改变编辑目标版本。
  const target = await fixture.role("角色编辑并发目标", ["users.view"]);
  created.push(target.id);
  const manager = await fixture.role("角色编辑并发授权角色", ["roles.view", "roles.edit"]);
  created.push(manager.id);
  const permission = "users.view";
  const granted = await fixture.grants(manager.id, [permission]);
  person = await fixture.user("role-edit-race", fixture.root, [granted]);
  const editor = await fixture.activate(person);
  assert.deepEqual((await editor.ok("/auth/me")).permissionCodes.sort(), ["roles.edit", "roles.view"]);
  assert.match(manager.id, /^[1-9][0-9]{0,18}$/);
  assert.match(target.id, /^[1-9][0-9]{0,18}$/);

  const admin = fixture.admin;
  const editPath = `/iam/roles/${target.id}`;
  const grantPath = `/iam/roles/${manager.id}`;
  const before = await admin.ok(editPath);
  const edit = { name: before.name, description: "等待授权锁时收紧上限", permissionCodes: before.permissionCodes };
  const reduction = { grantablePermissionCodes: [], reason: "并发收紧验收角色的授予上限" };
  const editCommand = crypto.randomUUID();
  const grantCommand = crypto.randomUUID();

  // 在持锁前签发双方的真实预览，并准备各自的有效会话。
  const editPreview = await editor.ok(`${editPath}/impact-preview`, {
    method: "POST",
    body: { action: "UPDATE", expectedVersion: before.version, proposal: edit },
  });
  const grantPreview = await admin.ok(`${grantPath}/impact-preview`, {
    method: "POST",
    body: { action: "GRANT_LIMIT", expectedVersion: granted.version, proposal: reduction },
  });
  const guardQuery = "^SELECT [*] FROM iam_security_guard WHERE id *= *1 FOR UPDATE$";
  const grantQuery = `^DELETE FROM iam_role_grant_permission WHERE role_id *= *${manager.id}$`;
  const pending = [];
  try {
    // 管理员先取得授权锁，然后必然阻塞在探针持有的授予记录上。
    const release = await lockFixtureGrant(manager, permission);
    try {
      // 观察管理员进入真实的收权 SQL，再发起编辑，保证它排在管理员之后。
      queue(pending, admin.request(`${grantPath}/grant-limit`, {
        method: "PUT",
        command: grantCommand,
        body: { ...reduction, expectedVersion: granted.version, reviewToken: grantPreview.reviewToken },
      }));
      await report.test("管理员持有授权锁并等待收回本轮授予记录", async () => {
        // 精确匹配当前合成角色的 DELETE，确认它尚未完成，并排除已有授权锁等待者。
        await waitForQuery(grantQuery, "管理员删除本轮授予记录");
        assert.deepEqual(activeConnections(guardQuery), []);
      });

      queue(pending, editor.request(editPath, {
        method: "PUT",
        command: editCommand,
        body: { ...edit, expectedVersion: before.version, reviewToken: editPreview.reviewToken },
      }));
      await report.test("编辑通过 Controller 核权并到达 MySQL 授权锁等待", async () => {
        // 授权锁是 Controller 拦截器通过后，写事务才会发出的查询。
        const editorConnection = await waitForQuery(guardQuery, "编辑事务的授权锁查询");
        const [adminConnection] = activeConnections(grantQuery);
        assert.ok(adminConnection, "观察到编辑等待时管理员尚未能完成收权");
        assert.notEqual(editorConnection, adminConnection);
      });
    } finally {
      // 同时观察到两个事务后再放行管理员，编辑只能在收权事务提交后取得授权锁。
      await release();
    }

    const [reduced, edited] = await Promise.all(pending);
    await report.test("收权事务提交，编辑再次核权返回 HTTP 403 / 403004", async () => {
      // 编辑权保留，只有最终可授予范围缩小，因此拒绝必须来自完整授予策略。
      assert.equal(reduced.status, 200);
      assert.equal(reduced.code, 200);
      await fixture.auditFor(manager.id, grantCommand);
      assert.deepEqual((await admin.ok(`${grantPath}/grant-limit`)).grantablePermissionCodes, []);
      assert.ok((await editor.ok("/auth/me")).permissionCodes.includes("roles.edit"));
      assert.equal(edited.status, 403);
      reject(edited, 403004);
    });
    await report.test("目标角色内容与版本不变，编辑命令无成功审计", async () => {
      // 比较完整详情，并按目标和命令号独立查询成功审计。
      assert.deepEqual(await admin.ok(editPath), before);
      assert.deepEqual(rows(`SELECT COUNT(*) FROM audit_record WHERE outcome='SUCCESS'
        AND (command_id='${editCommand}' OR (action_code='roles.edit' AND target_type='role' AND target_id='${target.id}'))`), [[0]]);
      assert.deepEqual(rows(`SELECT COUNT(*) FROM sys_command WHERE command_id='${editCommand}'`), [[0]]);
    });
  } finally {
    // 如观察或断言失败，先等所有已发请求结束，再归档其涉及的数据。
    await Promise.allSettled(pending);
  }
} finally {
  try {
    // 先归档人员，释放对本轮角色的当前分配，再归档角色。
    await report.test("归档本轮合成账号和角色", async () => {
      if (person) {
        await archive(fixture.admin, "/iam/users", person.id);
      }
      for (const id of created) {
        await archive(fixture.admin, "/iam/roles", id);
      }
    });
  } finally {
    // 无论哪项断言失败都保留最小结果。
    await report.save("role-edit-race", fixture.prefix);
  }
}
