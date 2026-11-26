import assert from "node:assert/strict";
import { IdentityFixture, AcceptanceReport, reject } from "./iam-fixtures.mjs";
import { sql, acknowledgeOutbox } from "./platform-probes.mjs";

/**
 * 本组同时核对真实 HTTP 结果和 MySQL 事实，不重置既有开发库。
 */
const fixture = await new IdentityFixture().start();

/**
 * 事务、物理约束与可靠事件单独保存证据。
 */
const report = new AcceptanceReport();

/**
 * 查询只保留计数或状态，不读取受控凭据原文。
 */
function rows(statement) {
  const result = sql(statement);
  assert.equal(
    result.sqlState,
    undefined,
    `databaseState=${result.sqlState}, vendorCode=${result.vendorCode}`,
  );
  return result.rows;
}

/**
 * 普通资料修订不改变组织安全版本，用于隔离验证成员版本检查。
 */
async function renamePerson(id, suffix) {
  const person = await fixture.admin.ok(`/iam/users/${id}`);
  const proposal = {
    displayName: `${fixture.prefix} ${suffix}`,
    membership: {
      primaryDepartmentId: person.primaryDepartment.id,
      secondaryDepartmentIds: person.secondaryDepartments.map(
        (item) => item.id,
      ),
    },
    phone: person.phone,
    email: person.email,
    managementNote: person.managementNote,
  };
  await fixture.admin.reviewed("/iam/users", "UPDATE", proposal, {
    id,
    version: person.version,
  });
  return fixture.admin.ok(`/iam/users/${id}`);
}

/**
 * 读取当前部门聚合版本，不重用列表里的过期摘要。
 */
async function department(id) {
  return fixture.admin.ok(`/iam/departments/${id}`);
}

/**
 * 只修改本次合成对象的显示名，用于验证归档快照不随当前名称变化。
 */
async function renameDepartment(id) {
  const current = await department(id);
  const proposal = {
    name: `${fixture.prefix} 改名后部门`,
    code: current.code,
    parentId: current.parent?.id ?? null,
    managerUserId: current.manager?.id ?? null,
    note: current.note,
    sortOrder: current.sortOrder,
  };
  await fixture.admin.reviewed("/iam/departments", "UPDATE", proposal, {
    id,
    version: current.version,
  });
}

/**
 * 当前脚本的两个部门与人员完全独立于其他开发操作。
 */
const alpha = await fixture.department("alpha");
const beta = await fixture.department("beta");
let first = await fixture.user("first");
let second = await fixture.user("second");

/**
 * 唯一关系与角色生命周期共用本次独立普通角色。
 */
const ordinary = await fixture.role("普通关系角色", ["users.view"]);

/**
 * 当前普通人员会话在后续事件与角色生效验证中复用。
 */
let secondClient;

try {
  await report.test(
    "A01/数据库 新平台表、初始化标记、MySQL 强制 CHECK 与外键",
    async () => {
      assert.equal(
        rows(
          "SELECT CAST(bootstrap_completed AS UNSIGNED) FROM iam_security_guard WHERE id=1",
        )[0][0],
        1,
      );
      assert.equal(
        rows("SELECT COUNT(*) FROM iam_user WHERE username='admin'")[0][0],
        1,
      );
      const invalid = sql(
        `INSERT INTO iam_role(code,name,name_key,lifecycle,version,created_at,updated_at) VALUES('${fixture.prefix}-invalid','constraint','${fixture.prefix}-invalid','INVALID',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))`,
      );
      assert.equal(invalid.vendorCode, 3819);
      const zeroVersion = sql(
        `INSERT INTO iam_role(code,name,name_key,lifecycle,version,created_at,updated_at) VALUES('${fixture.prefix}-zero','constraint','${fixture.prefix}-zero','ENABLED',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))`,
      );
      assert.equal(zeroVersion.vendorCode, 3819);
      const missing = sql(
        "INSERT INTO iam_user_credential(user_id,password_hash,changed_at) VALUES(9223372036854775806,'synthetic-not-a-real-credential',UTC_TIMESTAMP(3))",
      );
      assert.equal(missing.vendorCode, 1452);
      const duplicatePrimary = sql(
        `INSERT INTO iam_user_department(user_id,department_id,is_primary,joined_by,joined_at) VALUES(${first.id},${alpha.id},1,${fixture.actorId},UTC_TIMESTAMP(3))`,
      );
      assert.equal(duplicatePrimary.vendorCode, 1062);
      assert.equal(
        rows(
          `SELECT COUNT(*) FROM iam_user_department WHERE user_id=${first.id} AND is_primary=1 AND left_at IS NULL`,
        )[0][0],
        1,
      );
      await fixture.admin.ok(`/iam/users/${first.id}/roles`, {
        method: "PUT",
        command: crypto.randomUUID(),
        body: {
          expectedVersion: first.version,
          roles: [{ roleId: ordinary.id, version: ordinary.version }],
        },
      });
      first = await fixture.admin.ok(`/iam/users/${first.id}`);
      const duplicateRole = sql(
        `INSERT INTO iam_user_role(user_id,role_id,assigned_role_version,assigned_by,assigned_at) VALUES(${first.id},${ordinary.id},${ordinary.version},${fixture.actorId},UTC_TIMESTAMP(3))`,
      );
      assert.equal(duplicateRole.vendorCode, 1062);
    },
  );

  await report.test(
    "A34 无变化资料与角色保存不增加内容修订或成功审计",
    async () => {
      const current = await fixture.admin.ok(`/iam/users/${first.id}`);
      const proposal = {
        displayName: current.displayName,
        membership: {
          primaryDepartmentId: current.primaryDepartment.id,
          secondaryDepartmentIds: current.secondaryDepartments.map(
            (item) => item.id,
          ),
        },
        phone: current.phone,
        email: current.email,
        managementNote: current.managementNote,
      };
      const profileCommand = crypto.randomUUID();
      const unchanged = await fixture.admin.reviewed(
        "/iam/users",
        "UPDATE",
        proposal,
        { id: current.id, version: current.version, command: profileCommand },
      );
      assert.equal(unchanged.version, current.version);
      const rolesCommand = crypto.randomUUID();
      const assignment = await fixture.admin.ok(
        `/iam/users/${current.id}/roles`,
        {
          method: "PUT",
          command: rolesCommand,
          body: {
            expectedVersion: current.version,
            roles: current.roles.map((role) => ({
              roleId: role.id,
              version: role.version,
            })),
          },
        },
      );
      assert.equal(assignment.version, current.version);
      assert.equal(
        rows(
          `SELECT COUNT(*) FROM audit_record WHERE command_id IN ('${profileCommand}','${rolesCommand}') AND outcome='SUCCESS'`,
        )[0][0],
        0,
      );
    },
  );

  await report.test(
    "A24 加入兼任的后一个人员版本变化时整批不产生关系",
    async () => {
      const current = await department(alpha.id);
      const proposal = {
        members: [
          { userId: second.id, version: second.version },
          { userId: first.id, version: first.version },
        ],
        reason: "整批成员版本核对",
      };
      const preview = await fixture.admin.ok(
        `/iam/departments/${alpha.id}/impact-preview`,
        {
          method: "POST",
          body: {
            action: "ADD_SECONDARY",
            expectedVersion: current.version,
            proposal,
          },
        },
      );
      first = await renamePerson(first.id, "first-revised");
      reject(
        await fixture.admin.request(
          `/iam/departments/${alpha.id}/secondary-members`,
          {
            method: "POST",
            command: crypto.randomUUID(),
            body: {
              ...proposal,
              expectedVersion: current.version,
              reviewToken: preview.reviewToken,
            },
          },
        ),
        409001,
      );
      assert.equal(
        (
          await fixture.admin.ok(
            `/iam/departments/${alpha.id}/members?includeDescendants=false`,
          )
        ).total,
        0,
      );
      const references = await Promise.all(
        [first.id, second.id].map((id) => fixture.admin.ok(`/iam/users/${id}`)),
      );
      await fixture.admin.reviewed(
        "/iam/departments",
        "ADD_SECONDARY",
        {
          members: references.map((person) => ({
            userId: person.id,
            version: person.version,
          })),
          reason: "全部重新核对后加入",
        },
        {
          id: alpha.id,
          version: (await department(alpha.id)).version,
          method: "POST",
          path: "/secondary-members",
        },
      );
    },
  );

  await report.test(
    "A24 迁移完整成员集合时任一人员版本变化均整批回滚",
    async () => {
      const current = await department(alpha.id);
      const target = await department(beta.id);
      const members = await fixture.admin.ok(
        `/iam/departments/${alpha.id}/members?includeDescendants=false`,
      );
      const proposal = {
        destination: { id: beta.id, version: target.version },
        members: members.items.map((person) => ({
          userId: person.id,
          version: person.version,
        })),
        reason: "验证迁移整体回滚",
      };
      const preview = await fixture.admin.ok(
        `/iam/departments/${alpha.id}/impact-preview`,
        {
          method: "POST",
          body: {
            action: "MOVE_MEMBERS",
            expectedVersion: current.version,
            proposal,
          },
        },
      );
      first = await renamePerson(first.id, "first-move-revised");
      reject(
        await fixture.admin.request(
          `/iam/departments/${alpha.id}/member-transfers`,
          {
            method: "POST",
            command: crypto.randomUUID(),
            body: {
              ...proposal,
              expectedVersion: current.version,
              reviewToken: preview.reviewToken,
            },
          },
        ),
        409001,
      );
      assert.equal(
        (
          await fixture.admin.ok(
            `/iam/departments/${alpha.id}/members?includeDescendants=false`,
          )
        ).total,
        2,
      );
      assert.equal(
        (
          await fixture.admin.ok(
            `/iam/departments/${beta.id}/members?includeDescendants=false`,
          )
        ).total,
        0,
      );
    },
  );

  await report.test(
    "A24 迁移完整子部门集合时后一个版本变化均整批回滚",
    async () => {
      const one = await fixture.department("child-one", alpha.id);
      const two = await fixture.department("child-two", alpha.id);
      const current = await department(alpha.id);
      const destination = await department(beta.id);
      const proposal = {
        destination: { id: destination.id, version: destination.version },
        children: [one, two].map((child) => ({
          id: child.id,
          version: child.version,
        })),
        reason: "验证子部门完整集合回滚",
      };
      const preview = await fixture.admin.ok(
        `/iam/departments/${alpha.id}/impact-preview`,
        {
          method: "POST",
          body: {
            action: "MOVE_CHILDREN",
            expectedVersion: current.version,
            proposal,
          },
        },
      );
      await renameDepartment(two.id);
      reject(
        await fixture.admin.request(
          `/iam/departments/${alpha.id}/child-transfers`,
          {
            method: "POST",
            command: crypto.randomUUID(),
            body: {
              ...proposal,
              expectedVersion: current.version,
              reviewToken: preview.reviewToken,
            },
          },
        ),
        409001,
      );
      assert.equal((await department(one.id)).parent.id, alpha.id);
      assert.equal((await department(two.id)).parent.id, alpha.id);
    },
  );

  await report.test(
    "A29 成功审计或 Outbox 插入失败时人员、命令和成功审计共同回滚",
    async () => {
      for (const table of ["audit_record", "sys_outbox"]) {
        const command = crypto.randomUUID();
        const constraint = `${fixture.prefix}_${table}`;
        const profile = {
          username: `${fixture.prefix}-${table}`,
          displayName: `${fixture.prefix} rollback`,
          membership: {
            primaryDepartmentId: fixture.root.id,
            secondaryDepartmentIds: [],
          },
          roles: [],
          phone: null,
          email: null,
          managementNote: null,
        };
        const preview = await fixture.admin.ok("/iam/users/impact-preview", {
          method: "POST",
          body: { proposal: profile },
        });
        const body = {
          ...profile,
          initialPassword: "admin123",
          reviewToken: preview.reviewToken,
        };
        const installed = sql(
          `ALTER TABLE ${table} ADD CONSTRAINT ${constraint} CHECK(command_id IS NULL OR command_id<>'${command}')`,
        );
        assert.equal(
          installed.sqlState,
          undefined,
          `故障约束未安装：${installed.sqlState}/${installed.vendorCode}`,
        );
        try {
          reject(
            await fixture.admin.request("/iam/users", {
              method: "POST",
              command,
              body,
            }),
            503001,
          );
          assert.equal(
            rows(
              `SELECT COUNT(*) FROM iam_user WHERE username='${profile.username}'`,
            )[0][0],
            0,
          );
          assert.equal(
            rows(
              `SELECT COUNT(*) FROM sys_command WHERE command_id='${command}'`,
            )[0][0],
            0,
          );
          assert.equal(
            rows(
              `SELECT COUNT(*) FROM audit_record WHERE command_id='${command}' AND outcome='SUCCESS'`,
            )[0][0],
            0,
          );
        } finally {
          // 只移除本次命令专属的 CHECK，不依赖 SUPER 或改变数据库全局开关。
          const removed = sql(`ALTER TABLE ${table} DROP CHECK ${constraint}`);
          assert.equal(removed.sqlState, undefined);
        }
        const saved = await fixture.admin.ok(
          "/iam/users",
          { method: "POST", command, body },
          201,
        );
        assert.ok(saved.targetId);
        assert.equal(
          rows(
            `SELECT COUNT(*) FROM audit_record WHERE command_id='${command}' AND outcome='SUCCESS'`,
          )[0][0],
          1,
        );
      }
    },
  );

  await report.test(
    "A30 真实 Mapper 旧租约不能确认新领取，只有当前租约确认一次",
    async () => {
      const event = crypto.randomUUID();
      const oldLease = crypto.randomUUID();
      const newLease = crypto.randomUUID();
      const payload = JSON.stringify({
        securityVersion: String(
          rows(
            "SELECT security_version FROM iam_security_guard WHERE id=1",
          )[0][0],
        ),
        changeKind: "acceptance",
        subjectRefs: [],
        sourceIds: [],
        impactMode: "ALL_OF_SUBJECTS",
      });
      const inserted = sql(
        `INSERT INTO sys_outbox(event_id,event_type,aggregate_type,aggregate_id,aggregate_version,payload,available_at,occurred_at) VALUES('${event}','iam.authorization.changed','department','${alpha.id}',1,'${payload}',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY),UTC_TIMESTAMP(3))`,
      );
      assert.equal(inserted.sqlState, undefined);
      sql(
        `UPDATE sys_outbox SET delivery_state='CLAIMED',lease_token='${newLease}',leased_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY),attempts=2 WHERE event_id='${event}'`,
      );
      assert.equal(acknowledgeOutbox(event, oldLease).updated, 0);
      assert.equal(acknowledgeOutbox(event, newLease).updated, 1);
      assert.equal(acknowledgeOutbox(event, newLease).updated, 0);
    },
  );

  await report.test(
    "A30 延迟认证事件及重复投递不删除改密后的新会话",
    async () => {
      const client = await fixture.activate(second);
      secondClient = client;
      const current = await fixture.admin.ok(`/iam/users/${second.id}`);
      const authenticationVersion = Number(
        rows(
          `SELECT authentication_version FROM iam_user WHERE id=${second.id}`,
        )[0][0],
      );
      const event = crypto.randomUUID();
      const payload = JSON.stringify({
        userId: second.id,
        authenticationVersion: authenticationVersion - 1,
        reason: "PASSWORD_CHANGED",
      });
      assert.equal(
        sql(
          `INSERT INTO sys_outbox(event_id,event_type,aggregate_type,aggregate_id,aggregate_version,payload,available_at,occurred_at) VALUES('${event}','iam.authentication.invalidated','user','${second.id}',${current.version},'${payload}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))`,
        ).sqlState,
        undefined,
      );
      const until = Date.now() + 30000;
      while (
        Date.now() < until &&
        rows(
          `SELECT delivery_state FROM sys_outbox WHERE event_id='${event}'`,
        )[0][0] !== "DELIVERED"
      )
        await new Promise((resolve) => setTimeout(resolve, 300));
      assert.equal(
        rows(
          `SELECT delivery_state FROM sys_outbox WHERE event_id='${event}'`,
        )[0][0],
        "DELIVERED",
      );
      assert.equal((await client.ok("/account")).id, second.id);
      assert.equal(
        rows(
          `SELECT COUNT(*) FROM sys_event_receipt WHERE event_id='${event}' AND consumer_code='SESSION_INVALIDATION'`,
        )[0][0],
        1,
      );
      sql(
        `UPDATE sys_outbox SET delivery_state='PENDING',delivered_at=NULL,available_at=UTC_TIMESTAMP(3) WHERE event_id='${event}'`,
      );
      const repeatedUntil = Date.now() + 30000;
      while (
        Date.now() < repeatedUntil &&
        rows(
          `SELECT delivery_state FROM sys_outbox WHERE event_id='${event}'`,
        )[0][0] !== "DELIVERED"
      )
        await new Promise((resolve) => setTimeout(resolve, 300));
      assert.equal(
        rows(
          `SELECT delivery_state FROM sys_outbox WHERE event_id='${event}'`,
        )[0][0],
        "DELIVERED",
      );
      assert.ok(
        rows(
          `SELECT attempts FROM sys_outbox WHERE event_id='${event}'`,
        )[0][0] >= 2,
      );
      assert.equal(
        rows(
          `SELECT COUNT(*) FROM sys_event_receipt WHERE event_id='${event}' AND consumer_code='SESSION_INVALIDATION'`,
        )[0][0],
        1,
      );
      assert.equal((await client.ok("/account")).id, second.id);
    },
  );

  await report.test(
    "A13/A14 角色停用保留分配并即时失效，恢复重算能力，归档先移出成员",
    async () => {
      let current = await fixture.admin.ok(`/iam/users/${second.id}`);
      await fixture.admin.ok(`/iam/users/${second.id}/roles`, {
        method: "PUT",
        command: crypto.randomUUID(),
        body: {
          expectedVersion: current.version,
          roles: [{ roleId: ordinary.id, version: ordinary.version }],
        },
      });
      assert.equal(
        (await secondClient.ok("/auth/me")).permissionCodes.includes(
          "users.view",
        ),
        true,
      );
      let role = await fixture.admin.ok(`/iam/roles/${ordinary.id}`);
      await fixture.admin.reviewed(
        "/iam/roles",
        "DISABLE",
        { reason: "停用保留当前分配" },
        {
          id: role.id,
          version: role.version,
          method: "POST",
          path: "/disable",
        },
      );
      assert.equal(
        (await secondClient.ok("/auth/me")).permissionCodes.includes(
          "users.view",
        ),
        false,
      );
      role = await fixture.admin.ok(`/iam/roles/${ordinary.id}`);
      assert.equal(role.memberCount, 2);
      reject(
        await fixture.admin.request(`/iam/roles/${role.id}/impact-preview`, {
          method: "POST",
          body: {
            action: "ARCHIVE",
            expectedVersion: role.version,
            proposal: { reason: "仍有当前成员不可归档" },
          },
        }),
        409006,
      );
      await fixture.admin.reviewed(
        "/iam/roles",
        "ENABLE",
        { reason: "重新核对后启用" },
        { id: role.id, version: role.version, method: "POST", path: "/enable" },
      );
      assert.equal(
        (await secondClient.ok("/auth/me")).permissionCodes.includes(
          "users.view",
        ),
        true,
      );
      role = await fixture.admin.ok(`/iam/roles/${ordinary.id}`);
      await fixture.admin.reviewed(
        "/iam/roles",
        "DISABLE",
        { reason: "移出成员前停用" },
        {
          id: role.id,
          version: role.version,
          method: "POST",
          path: "/disable",
        },
      );
      for (const id of [first.id, second.id]) {
        current = await fixture.admin.ok(`/iam/users/${id}`);
        await fixture.admin.ok(`/iam/users/${id}/roles`, {
          method: "PUT",
          command: crypto.randomUUID(),
          body: { expectedVersion: current.version, roles: [] },
        });
      }
      role = await fixture.admin.ok(`/iam/roles/${ordinary.id}`);
      await fixture.admin.reviewed(
        "/iam/roles",
        "ARCHIVE",
        { reason: "无当前成员后归档" },
        {
          id: role.id,
          version: role.version,
          method: "POST",
          path: "/archive",
        },
      );
      assert.equal(
        (await fixture.admin.ok(`/iam/roles/${ordinary.id}`)).lifecycle,
        "ARCHIVED",
      );
    },
  );

  await report.test(
    "A35 归档结束当前主兼任和角色，后续部门与角色改名不改写快照",
    async () => {
      let current = await fixture.admin.ok(`/iam/users/${first.id}`);
      const snapshotRole = await fixture.role("归档角色", ["users.view"]);
      await fixture.admin.ok(`/iam/users/${first.id}/roles`, {
        method: "PUT",
        command: crypto.randomUUID(),
        body: {
          expectedVersion: current.version,
          roles: [{ roleId: snapshotRole.id, version: snapshotRole.version }],
        },
      });
      current = await fixture.admin.ok(`/iam/users/${first.id}`);
      await fixture.admin.reviewed(
        "/iam/users",
        "TRANSFER",
        {
          membership: {
            primaryDepartmentId: alpha.id,
            secondaryDepartmentIds: [beta.id],
          },
          reason: "建立归档前完整归属",
        },
        {
          id: first.id,
          version: current.version,
          path: "/memberships",
          method: "PUT",
        },
      );
      current = await fixture.admin.ok(`/iam/users/${first.id}`);
      const originalName = current.primaryDepartment.name;
      await fixture.admin.reviewed(
        "/iam/users",
        "DISABLE",
        { reason: "归档前停用" },
        {
          id: first.id,
          version: current.version,
          path: "/disable",
          method: "POST",
        },
      );
      current = await fixture.admin.ok(`/iam/users/${first.id}`);
      await fixture.admin.reviewed(
        "/iam/users",
        "ARCHIVE",
        { reason: "保留历史并结束当前关系" },
        {
          id: first.id,
          version: current.version,
          path: "/archive",
          method: "POST",
        },
      );
      assert.equal(
        rows(
          `SELECT COUNT(*) FROM iam_user_department WHERE user_id=${first.id} AND left_at IS NULL`,
        )[0][0],
        0,
      );
      assert.equal(
        rows(
          `SELECT COUNT(*) FROM iam_user_role WHERE user_id=${first.id} AND ended_at IS NULL`,
        )[0][0],
        0,
      );
      await renameDepartment(alpha.id);
      await fixture.admin.reviewed(
        "/iam/roles",
        "UPDATE",
        {
          name: `${fixture.prefix} 新角色名称`,
          description: snapshotRole.description,
          permissionCodes: ["users.view"],
        },
        { id: snapshotRole.id, version: snapshotRole.version },
      );
      const archived = await fixture.admin.ok(`/iam/users/${first.id}`);
      assert.equal(archived.displayStatus, "ARCHIVED");
      assert.equal(archived.primaryDepartment.name, originalName);
      assert.equal(archived.roles[0].name, snapshotRole.name);
    },
  );

  await report.test(
    "A31/A32 拒绝与失败有最小审计，非凭据存储无明文密码或散列",
    async () => {
      const before = rows(
        `SELECT COUNT(*) FROM audit_record WHERE target_type='user' AND target_id='${first.id}' AND action_code='users.delete' AND outcome='REJECTED'`,
      )[0][0];
      const denied = await fixture.admin.request(`/iam/users/${first.id}`, {
        method: "DELETE",
        command: crypto.randomUUID(),
      });
      assert.equal(denied.status, 405);
      assert.equal(
        rows(
          `SELECT COUNT(*) FROM audit_record WHERE target_type='user' AND target_id='${first.id}' AND action_code='users.delete' AND outcome='REJECTED'`,
        )[0][0],
        before + 1,
      );
      assert.ok(
        rows(
          "SELECT COUNT(*) FROM audit_record WHERE outcome='REJECTED'",
        )[0][0] > 0,
      );
      assert.ok(
        rows("SELECT COUNT(*) FROM audit_record WHERE outcome='FAILED'")[0][0] >
          0,
      );
      const hits = rows(
        "SELECT COUNT(*) FROM audit_record WHERE CAST(actor_snapshot AS CHAR) LIKE '%admin123%' OR COALESCE(CAST(before_summary AS CHAR),'') LIKE '%admin123%' OR COALESCE(CAST(after_summary AS CHAR),'') LIKE '%admin123%' OR COALESCE(CAST(after_summary AS CHAR),'') LIKE '%pbkdf2%' OR COALESCE(CAST(before_summary AS CHAR),'') LIKE '%pbkdf2%'",
      )[0][0];
      assert.equal(hits, 0);
      const commandHits = rows(
        "SELECT COUNT(*) FROM sys_command WHERE COALESCE(CAST(result_ref AS CHAR),'') LIKE '%admin123%' OR COALESCE(CAST(result_ref AS CHAR),'') LIKE '%pbkdf2%'",
      )[0][0];
      assert.equal(commandHits, 0);
    },
  );
} finally {
  await report.save("transactions", fixture.prefix);
}
