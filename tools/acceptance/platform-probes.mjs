import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

/**
 * 读取本机编译好的探针与依赖引用，实际连接秘密仍由受控配置文件提供。
 */
const root = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);

/**
 * 当前机器的应用配置路径，跨机器由调用者明确提供。
 */
const configuration = process.env.DATAOCEAN_LOCAL_CONFIG;

/**
 * 用标准输入调用 Java 探针，SQL 或会话凭据不进入进程命令行。
 */
function probe(name, input, mode) {
  if (!configuration)
    throw new Error("请用 DATAOCEAN_LOCAL_CONFIG 指定已核实的新平台本机配置");
  const dependencies = fs
    .readFileSync(
      path.join(root, "backend/target/acceptance/backend-classpath.txt"),
      "utf8",
    )
    .trim();

  const java = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, "bin/java.exe")
    : "java";

  const result = spawnSync(
    java,
    [
      "-Xms16m",
      "-Xmx128m",
      "-Dfile.encoding=UTF-8",
      "-cp",
      `${path.join(root, "backend/target/acceptance/probes")}${path.delimiter}${path.join(root, "backend/target/classes")}${path.delimiter}${dependencies}`,
      name,
      configuration,
      ...(mode ? [mode] : []),
    ],
    { input, encoding: "utf8", timeout: 60000, windowsHide: true },
  );
  if (result.status !== 0)
    throw new Error(
      `平台验收探针未完成：${name}, exit=${result.status}, signal=${result.signal ?? "none"}, error=${result.error?.code ?? "none"}`,
    );
  return JSON.parse(result.stdout.trim());
}

/**
 * 数据库调用只传入验收脚本自己的合成记录和命令专属故障约束，不查询凭据原值。
 */
export function sql(statement) {
  return probe("DatabaseProbe", statement);
}

/**
 * 读取实际生产 Mapper SQL 验证租约确认，不另写一套可绕过生产回归的测试条件。
 */
export function acknowledgeOutbox(id, lease) {
  return probe(
    "DatabaseProbe",
    JSON.stringify({ id, lease }),
    "outbox-delivered",
  );
}

/**
 * 根据当前客户端自身 Cookie 定位实际会话，验证时不输出或持久化该标识。
 */
export function sessionClock(client, field, epoch) {
  const cookie = client.cookies.get("DATAOCEAN_SESSION");
  if (!cookie) throw new Error("当前验收客户端没有会话");
  const sessionId = Buffer.from(decodeURIComponent(cookie), "base64").toString(
    "utf8",
  );
  if (!/^[0-9a-fA-F-]{36}$/.test(sessionId))
    throw new Error("实际会话编码与验收配置不一致");
  return probe(
    "SessionClockProbe",
    JSON.stringify({
      sessionId,
      field,
      ...(epoch === undefined ? {} : { epoch }),
    }),
  );
}
