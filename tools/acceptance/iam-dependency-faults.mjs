import assert from "node:assert/strict";
import fs from "node:fs";
import fsp from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawn } from "node:child_process";
import {
  AcceptanceReport,
  developmentPassword,
  reject,
} from "./iam-fixtures.mjs";
import { IdentityClient } from "./iam-client.mjs";

/**
 * 通过独立正式应用进程及连接代理模拟故障，不停止或修改共享 MySQL、Redis。
 */
const root = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);

/**
 * 机器连接配置只通过现有受控文件引用读取。
 */
const configuration = process.env.DATAOCEAN_LOCAL_CONFIG;
if (!configuration || !process.env.JAVA_HOME)
  throw new Error("请先选择项目 JDK 并指定 DATAOCEAN_LOCAL_CONFIG");

/**
 * 分类结果与当前业务接口、数据库和浏览器证据分别保存。
 */
const report = new AcceptanceReport();

/**
 * 本次独立会话命名空间，不替换浏览器当前开发会话。
 */
const run = `fault-${Date.now().toString(36)}`;

/**
 * 为正式驱动建立只转发字节的本机连接，不记录协议内容或凭据。
 */
async function connectionProxy(port, targetPort) {
  let blocked = false;
  const sockets = new Set();
  const server = net.createServer((socket) => {
    if (blocked) {
      socket.destroy();
      return;
    }
    const destination = net.connect({ host: "127.0.0.1", port: targetPort });
    sockets.add(socket);
    sockets.add(destination);
    /**
     * 两端一起释放，故障时正式驱动会收到真实连接关闭。
     */
    function close() {
      sockets.delete(socket);
      sockets.delete(destination);
      socket.destroy();
      destination.destroy();
    }
    socket.on("error", close);
    destination.on("error", close);
    socket.on("close", close);
    destination.on("close", close);
    socket.pipe(destination);
    destination.pipe(socket);
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", resolve);
  });
  return {
    /**
     * 仅切断本次应用自己的代理连接，共享服务保持原状。
     */
    fail() {
      blocked = true;
      for (const socket of sockets) socket.destroy();
      sockets.clear();
    },
    /**
     * 允许真实驱动重连原来的既有服务。
     */
    recover() {
      blocked = false;
    },
    /**
     * 验收结束关闭全部自建连接与监听端口。
     */
    async close() {
      for (const socket of sockets) socket.destroy();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

/**
 * 启动前实际端口冲突会直接失败，不接管现有进程。
 */
const mysql = await connectionProxy(
  13307,
  Number(process.env.DATAOCEAN_MYSQL_PORT ?? 3307),
);

/**
 * 独立 Redis 连接故障入口，原 6379 及其所有用户不受影响。
 */
const redis = await connectionProxy(
  16379,
  Number(process.env.DATAOCEAN_REDIS_PORT ?? 6379),
);

/**
 * 验收应用使用正式编译产物与同一 BOM 依赖。
 */
const dependencies = fs
  .readFileSync(
    path.join(root, "backend/target/acceptance/backend-classpath.txt"),
    "utf8",
  )
  .trim();

/**
 * 本次应用的日志不包含命令行秘密，退出后作为依赖故障证据保留。
 */
fs.mkdirSync(path.join(root, "output/acceptance/iam-first-stage"), {
  recursive: true,
});
const log = fs.createWriteStream(
  path.join(root, `output/acceptance/iam-first-stage/${run}.log`),
);

/**
 * 启动参数只包含端口和文件引用，连接密码仍从本机配置载入。
 */
const application = spawn(
  path.join(process.env.JAVA_HOME, "bin/java.exe"),
  [
    "-Dfile.encoding=UTF-8",
    "-cp",
    `${path.join(root, "backend/target/classes")}${path.delimiter}${dependencies}`,
    "com.dataocean.DataOceanApplication",
    "--spring.profiles.active=local,dev",
    `--spring.config.additional-location=optional:${pathToFileURL(path.dirname(configuration) + path.sep).href}`,
    `--dataocean.secret-key-file=${path.join(root, ".dataocean/hmac-key.txt")}`,
    "--server.port=18082",
    "--spring.datasource.url=jdbc:mysql://127.0.0.1:13307/dataocean?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8",
    "--spring.data.redis.host=127.0.0.1",
    "--spring.data.redis.port=16379",
    `--spring.session.redis.namespace=dataocean:session:${run}`,
    "--dataocean.outbox-enabled=false",
  ],
  { cwd: root, windowsHide: true, stdio: ["ignore", "pipe", "pipe"] },
);
application.stdout.pipe(log, { end: false });
application.stderr.pipe(log, { end: false });

/**
 * 独立应用的 Cookie 只存在于本进程，不修改用户浏览器登录。
 */
const client = new IdentityClient("http://127.0.0.1:18082");

/**
 * 按实际健康结果等待启动或驱动重连，不用固定长延时冒充恢复。
 */
async function ready() {
  const until = Date.now() + 45000;
  while (Date.now() < until) {
    if (application.exitCode !== null)
      throw new Error("正式故障验收应用启动或运行失败，请核对本次应用日志");
    try {
      const response = await fetch(`${client.base}/api/v1/health`, {
        signal: AbortSignal.timeout(3000),
      });
      if (response.ok && (await response.json()).data?.status === "UP") return;
    } catch {
      /* 驱动仍在恢复时继续等待明确健康结果。 */
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error("正式应用未在允许时间内恢复实际依赖连接");
}

try {
  await ready();
  await client.login("admin", developmentPassword);
  assert.equal(
    (await client.ok("/auth/me")).authenticationState,
    "AUTHENTICATED",
  );
  await report.test(
    "A11 真实 MySQL 连接失败时已有 Cookie 不能复用旧允许结果",
    async () => {
      mysql.fail();
      reject(await client.request("/account"), 503001);
      mysql.recover();
      await ready();
      assert.equal(
        (await client.ok("/auth/me")).authenticationState,
        "AUTHENTICATED",
      );
    },
  );
  await report.test(
    "A11 真实 Redis 会话连接失败返回统一 503，不降级内存或旧身份",
    async () => {
      redis.fail();
      reject(await client.request("/account"), 503001);
      redis.recover();
      await ready();
      assert.equal(
        (await client.ok("/auth/me")).authenticationState,
        "AUTHENTICATED",
      );
    },
  );
} finally {
  mysql.recover();
  redis.recover();
  try {
    await client.request("/auth/logout", { method: "POST" });
  } catch {
    /* 独立验收会话未建立或应用已退出时不影响其他会话。 */
  }
  application.kill();
  await Promise.all([mysql.close(), redis.close()]);
  log.end();
  await report.save("dependency-faults", run);
}
