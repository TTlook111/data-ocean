import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * 验收结果按证据类别展示，不混加单元测试、接口场景与浏览器任务。
 */
const root = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);

/**
 * 所有生成物位于项目输出目录，跨电脑可重新运行同一入口。
 */
const directory = path.join(root, "output/acceptance/iam-first-stage");

/**
 * 读取当前实际证据，缺失或失败即中止，不能把未执行写成通过。
 */
async function json(name) {
  return JSON.parse(
    (await fs.readFile(path.join(directory, name), "utf8")).replace(
      /^\uFEFF/u,
      "",
    ),
  );
}

/**
 * JUnit 只提取必要汇总，不复制测试日志或任何请求输入。
 */
async function junit(file) {
  const source = await fs.readFile(file, "utf8");
  const suites = [...source.matchAll(/<testsuite\b[^>]*>/gu)].map(([tag]) => {
    /**
     * 属性值只来自测试工具生成的 XML，不执行其中的任何内容。
     */
    const value = (key) => tag.match(new RegExp(`${key}="([^"]+)"`, "u"))?.[1];
    return {
      name: value("name"),
      tests: Number(value("tests")),
      failures: Number(value("failures")) + Number(value("errors")),
      skipped: Number(value("skipped")),
    };
  });
  return suites;
}

/**
 * 所有文本先转义，报告不得把测试名称解释为 HTML。
 */
function escape(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

/**
 * 当前 Java 结果直接来自 Surefire，避免手写测试数量。
 */
const backend = [];
for (const file of await fs.readdir(
  path.join(root, "backend/target/surefire-reports"),
)) {
  if (file.startsWith("TEST-") && file.endsWith(".xml"))
    backend.push(
      ...(await junit(
        path.join(root, "backend/target/surefire-reports", file),
      )),
    );
}
assert.ok(backend.length);
assert.ok(backend.every((item) => item.failures === 0 && item.skipped === 0));
await fs.writeFile(
  path.join(directory, "backend-tests.json"),
  JSON.stringify(
    {
      checkedAt: new Date().toISOString(),
      tests: backend.reduce((sum, item) => sum + item.tests, 0),
      failures: 0,
      suites: backend,
    },
    null,
    2,
  ),
);

/**
 * 三端测试各自保留原始报告与清楚的覆盖边界。
 */
const frontend = await json("frontend-tests.json");
const frontendChecks = await json("frontend-checks.json");
const python = await junit(path.join(directory, "python-tests.xml"));
const browser = JSON.parse(
  await fs.readFile(
    path.join(root, "output/playwright/iam-first-stage/browser-flows.json"),
    "utf8",
  ),
);
assert.equal(frontend.numPassedTests, frontend.numTotalTests);
assert.deepEqual(
  [frontendChecks.build, frontendChecks.lint, frontendChecks.format],
  [0, 0, 0],
);
assert.ok(
  python.length &&
    python.every((item) => item.failures === 0 && item.skipped === 0),
);
assert.ok(browser.every((item) => item.status === "PASS"));

/**
 * 真实接口按普通业务、权限、事务与外部依赖分别核对，保留细分证据。
 */
const groups = [];
for (const [name, file] of [
  ["普通真实 API", "api-report.json"],
  ["真实权限与并发", "security-boundaries.json"],
  ["MySQL 事务及可靠事件", "transactions.json"],
  ["真实依赖故障", "dependency-faults.json"],
]) {
  const report = await json(file);
  assert.ok(
    report.results.length &&
      report.results.every((item) => item.status === "PASS"),
  );
  groups.push({ name, count: report.results.length, file });
}

/**
 * 静态检查和构建必须具有当前成功输出，不能仅凭源码存在判定。
 */
assert.match(
  await fs.readFile(path.join(directory, "backend-build.log"), "utf8"),
  /BUILD SUCCESS/u,
);
assert.match(
  await fs.readFile(path.join(directory, "python-build.log"), "utf8"),
  /Successfully built/u,
);
assert.match(
  await fs.readFile(path.join(directory, "python-lint.log"), "utf8"),
  /All checks passed/u,
);
assert.match(
  await fs.readFile(path.join(directory, "python-format-check.log"), "utf8"),
  /already formatted/u,
);
const health = await json("python-http.json");
assert.deepEqual(
  health.results.map((item) => item.code),
  [200, 405001, 404001],
);

/**
 * 开发密码独立核查只保留统计；缺少该可选证据时不声称已经执行。
 */
let developmentCredentials = null;
try {
  developmentCredentials = await json("development-password-check.json");
  assert.equal(developmentCredentials.differentCount, 0);
} catch (error) {
  if (error.code !== "ENOENT") throw error;
}

/**
 * 分页只记录浏览器实际测量，原生缩放继续明确留作未验证。
 */
const pagination = JSON.parse(
  await fs.readFile(
    path.join(root, "output/playwright/iam-first-stage/final-pagination.json"),
    "utf8",
  ),
);
assert.ok(pagination.gap >= 0 && pagination.gap <= 2);
const summary = {
  checkedAt: new Date().toISOString(),
  backendTests: backend.reduce((sum, item) => sum + item.tests, 0),
  frontendTests: frontend.numTotalTests,
  pythonTests: python.reduce((sum, item) => sum + item.tests, 0),
  groups,
  browserTasks: browser.length,
  developmentCredentials,
  pagination,
  unverified: [
    "原生浏览器缩放",
    "提交后立即结束生产进程再补投",
    "批量迁移及受保护恢复的逐项浏览器全流程",
  ],
};
await fs.writeFile(
  path.join(directory, "summary.json"),
  JSON.stringify(summary, null, 2),
);

/**
 * 报告只展示当前结论、验证范围与必要入口，不写排查流水。
 */
const rows = [
  ["Java 服务边界测试", summary.backendTests, "backend-tests.json"],
  ["React 组件回归", summary.frontendTests, "frontend-tests.json"],
  ["Python 公共配置及 HTTP 契约", summary.pythonTests, "python-tests.xml"],
  ...groups.map((item) => [item.name, item.count, item.file]),
  [
    "浏览器实际任务",
    browser.length,
    "../../playwright/iam-first-stage/browser-flows.json",
  ],
]
  .map(
    ([name, count, file]) =>
      `<tr><td>${escape(name)}</td><td>${count}</td><td class="pass">通过</td><td><a href="${file}">证据</a></td></tr>`,
  )
  .join("");
const flows = browser.map((item) => `<li>${escape(item.name)}</li>`).join("");
const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>DataOcean 首批实现与验证</title><style>
/* 结论和边界采用连续文档结构，证据可直接打开。 */
body{max-width:1240px;margin:40px auto;padding:0 24px 48px;font:15px/1.7 "Segoe UI","Microsoft YaHei",sans-serif;color:#243047;background:#f6f8fb}h1{font-size:28px;margin-bottom:8px}h2{font-size:20px;margin-top:30px}a{color:#245dcc}main{background:white;border:1px solid #dce2eb;border-radius:8px;padding:28px}.pass{color:#17664c}
/* 测试种类分开计数，避免不同粒度混成一个总数。 */
table{border-collapse:collapse;width:100%}th,td{text-align:left;border-bottom:1px solid #e4e8ef;padding:9px 12px}th{background:#f7f9fc}
/* 原型对照截图保持原始比例，点击可看完整分辨率。 */
img{max-width:100%;height:auto;border:1px solid #e0e5ee}li{margin:5px 0}.limits{border-left:3px solid #b88219;padding-left:16px}
</style><main><h1>首批功能与真实验证</h1><p>公共底座、认证、人员、部门和角色已实现；已测场景通过，未验证项单列。AI 工作流、数据访问来源与跨服务身份按对应批次接入。</p><h2>构建与验证</h2><p>Java Wrapper 与 Spotless、前端生产构建／ESLint／Prettier、Python 锁定包构建与 Ruff 均通过。各类别数量独立展示。</p><table><thead><tr><th>类别</th><th>已测数量</th><th>结果</th><th>明细</th></tr></thead><tbody>${rows}</tbody></table><h2>浏览器真实闭环</h2><ul>${flows}</ul><h2>分页与原型</h2><p>最终生产产物在 ${pagination.width} × ${pagination.height} 渲染视口、仅一条记录时，分页与面板底边相差 ${pagination.gap}px。空结果及末页证据保留在同一浏览器输出目录；这是渲染视口检查，不代表原生缩放通过。</p><a href="../../playwright/iam-first-stage/users-footer-production.png"><img alt="最终生产产物分页固定在结果面板底部" src="../../playwright/iam-first-stage/users-footer-production.png"></a><h2>仍未验证</h2><ul class="limits">${summary.unverified.map((item) => `<li>${escape(item)}</li>`).join("")}</ul><p>租约隔离、重复投递、延迟认证失效和接口批量回滚已有真实证据；上面的未验项不据此计为通过。Python 当前健康检查只证明进程可响应。</p><h2>复验入口</h2><p><a href="../../../tools/acceptance/README.md">真实验收工具</a> · <a href="../../../docs/backend/运行与验收.md">运行与配置</a> · <a href="../../playwright/2026-11-17-react-prototype-fixes/report.html">页面规则及 PC 视口证据</a></p></main></html>`;
await fs.writeFile(path.join(directory, "report.html"), html);
console.log({ report: path.join(directory, "report.html"), ...summary });
