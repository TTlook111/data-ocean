const fs = require('fs');
const { chromium } = require('D:/tool/dataocean-playwright-runtime/node_modules/playwright');

const BASE = 'http://127.0.0.1:5173';
const API = 'http://127.0.0.1:8080';
const OUT = 'D:/projects/DataOcean/output/playwright';
const browserPath = 'D:/tool/dataocean-playwright-browsers/chromium-1243/chrome-win64/chrome.exe';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
const consoleErrors = [];
const expectedConsoleErrors = [];
const pageErrors = [];
const requestFailures = [];

function screenshotName(name) {
  return `${OUT}/${name}.png`;
}

async function waitForUi(page, ms = 1200) {
  await page.waitForLoadState('domcontentloaded').catch(() => {});
  await page.waitForTimeout(ms);
}

async function capture(page, name, stage, requestedPath, options = {}) {
  const before = page.url();
  let navigationError = null;
  let httpStatus = null;
  try {
    const response = await page.goto(`${BASE}${requestedPath}`, { waitUntil: 'domcontentloaded', timeout: 15000 });
    httpStatus = response?.status() ?? null;
    await waitForUi(page, options.waitMs || 1200);
  } catch (error) {
    navigationError = error.message;
  }
  const finalUrl = page.url();
  const body = await page.locator('body').innerText().catch(() => '');
  const tabs = await page.locator('.el-tabs__item, [role="tab"]').allTextContents().catch(() => []);
  const buttons = await page.locator('button:visible').allTextContents().catch(() => []);
  const pagePass = !navigationError && !body.includes('页面不存在') && !body.includes('请先登录');
  const finalName = name.replace(/-(pass|fail)$/, `-${pagePass ? 'pass' : 'fail'}`);
  const screenshot = screenshotName(finalName);
  await page.screenshot({ path: screenshot, fullPage: true }).catch(() => {});
  results.push({
    type: 'page',
    stage,
    name,
    requestedPath,
    before,
    finalUrl,
    httpStatus,
    navigationError,
    body: body.slice(0, 5000),
    tabs: tabs.map(x => x.trim()).filter(Boolean),
    buttons: buttons.map(x => x.trim()).filter(Boolean),
    screenshot,
    conclusion: pagePass ? '通过' : '不通过',
  });
  return { finalUrl, body, tabs, buttons, httpStatus, pagePass, screenshot };
}

async function login(page) {
  const username = process.env.DATAOCEAN_ACCEPT_USERNAME;
  const password = process.env.DATAOCEAN_ACCEPT_PASSWORD;
  if (!username || !password) {
    throw new Error('缺少 DATAOCEAN_ACCEPT_USERNAME 或 DATAOCEAN_ACCEPT_PASSWORD；验收脚本不内置口令');
  }
  let captchaCode;
  let resolveCaptcha;
  const captchaReady = new Promise(resolve => { resolveCaptcha = resolve; });
  const handler = async response => {
    if (!response.url().includes('/api/auth/captcha')) return;
    try {
      const data = await response.json();
      const key = data?.data?.captchaKey;
      if (!key) return;
      const result = require('child_process').execFileSync(
        'docker', ['exec', 'redis', 'redis-cli', 'GET', `captcha:${key}`], { encoding: 'utf8' },
      ).trim();
      captchaCode = result;
      resolveCaptcha(result);
    } catch (_) {}
  };
  page.on('response', handler);
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle', timeout: 15000 });
  const code = await Promise.race([
    captchaReady,
    new Promise((_, reject) => setTimeout(() => reject(new Error('captcha timeout')), 5000)),
  ]);
  await page.locator('input[placeholder="请输入账号"]').fill(username);
  await page.locator('input[placeholder="请输入密码"]').fill(password);
  await page.locator('input[placeholder="请输入验证码"]').fill(code || captchaCode);
  await page.getByRole('button', { name: '登录' }).click();
  await page.waitForURL('**/query', { timeout: 12000 });
  await page.screenshot({ path: screenshotName('admin-redesign-p0-auth-login-success-pass'), fullPage: true });
  results.push({ type: 'auth', name: 'login', requestedPath: '/login', finalUrl: page.url(), conclusion: '通过', body: (await page.locator('body').innerText()).slice(0, 1200) });
  page.off('response', handler);
}

async function api(page, path, options = {}) {
  const token = await page.evaluate(() => localStorage.getItem('dataocean_token'));
  // Java API 与 Vite 开发源不同，Node 客户端避免跨源 fetch 影响真实页面验收。
  const response = await fetch(`${API}${path}`, {
    method: options.method || 'GET',
    headers: { Authorization: `Bearer ${token}`, ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const text = await response.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: response.status, body };
}

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: browserPath });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  let expectedErrorInjectionActive = false;
  page.on('console', message => {
    if (message.type() !== 'error') return;
    const target = expectedErrorInjectionActive ? expectedConsoleErrors : consoleErrors;
    target.push({ url: page.url(), text: message.text() });
  });
  page.on('pageerror', error => pageErrors.push({ url: page.url(), text: error.message }));
  page.on('requestfailed', request => requestFailures.push({ url: request.url(), failure: request.failure()?.errorText }));

  await login(page);
  const me = await api(page, '/api/auth/me');
  const internalBadResponse = await fetch('http://127.0.0.1:8000/internal/sql/pools/dashboard', { headers: { 'X-Internal-Token': 'invalid-track-a-token' } });
  const internalToken = process.env.DATAOCEAN_INTERNAL_TOKEN;
  const internalGoodResponse = internalToken
    ? await fetch('http://127.0.0.1:8000/internal/sql/pools/dashboard', { headers: { 'X-Internal-Token': internalToken } })
    : null;
  results.push({ type: 'api', name: 'python-internal-token', badStatus: internalBadResponse.status, goodStatus: internalGoodResponse?.status || null, conclusion: internalBadResponse.status === 403 && internalGoodResponse?.status === 200 ? '通过' : '不通过' });
  const datasourceList = await api(page, '/api/admin/datasources/simple');
  const datasources = Array.isArray(datasourceList.body?.data) ? datasourceList.body.data : [];
  const primaryDatasourceId = datasources.find(item => item.id === 1)?.id || datasources[0]?.id || 1;
  const secondaryDatasourceId = datasources.find(item => item.id !== primaryDatasourceId)?.id;
  results.push({ type: 'assertion', name: 'super-admin-wildcard', expected: '*', observed: me.body?.data?.permissions || [], conclusion: me.status === 200 && me.body?.data?.permissions?.includes('*') ? '通过' : '不通过' });
  // 真实业务库闭环：重采集当前验收数据库，执行质量检查并发布最新快照。
  const sync = await api(page, '/api/admin/metadata/sync', { method: 'POST', body: { datasourceId: primaryDatasourceId, includeStatistics: false } });
  results.push({ type: 'api', name: 'metadata-sync-trigger', status: sync.status, taskId: sync.body?.data?.taskId, conclusion: sync.status === 200 ? '通过' : '不通过' });
  await new Promise(resolve => setTimeout(resolve, 1500));
  const snapshotsAfterSync = await api(page, `/api/admin/metadata/snapshots?datasourceId=${primaryDatasourceId}&page=1&size=20`);
  const primarySnapshotId = snapshotsAfterSync.body?.data?.records?.[0]?.id || 1;
  const quality = await api(page, `/api/admin/snapshots/${primarySnapshotId}/quality-check`, { method: 'POST', body: {} });
  results.push({ type: 'api', name: 'quality-check', status: quality.status, snapshotId: primarySnapshotId, issueCount: quality.body?.data?.totalIssues, conclusion: quality.status === 200 ? '通过' : '不通过' });
  const issuePage = await api(page, `/api/admin/snapshots/${primarySnapshotId}/quality-issues?page=1&size=100`);
  const issueRecords = issuePage.body?.data?.records || [];
  const currentUserId = me.body?.data?.id || 1;
  if (issueRecords[0]?.id) {
    const issueId = issueRecords[0].id;
    const assign = await api(page, `/api/admin/quality-issues/${issueId}/assign`, { method: 'POST', body: { assigneeId: currentUserId } });
    const confirm = await api(page, `/api/admin/quality-issues/${issueId}/status`, { method: 'PATCH', body: { status: 'CONFIRMED', resolutionNote: '轨道 A 真实验收确认' } });
    const resolve = await api(page, `/api/admin/quality-issues/${issueId}/status`, { method: 'PATCH', body: { status: 'RESOLVED', resolutionNote: '轨道 A 真实验收处理完成' } });
    const batchIssueId = issueRecords.find(item => item.id !== issueId && item.status === 'OPEN')?.id;
    const partial = await api(page, '/api/admin/quality-issues/batch-status', { method: 'PATCH', body: { issueIds: [batchIssueId || issueId, -1], status: 'CONFIRMED' } });
    const batchResolve = batchIssueId
      ? await api(page, `/api/admin/quality-issues/${batchIssueId}/status`, { method: 'PATCH', body: { status: 'RESOLVED', resolutionNote: '批量处理后复查完成' } })
      : { status: 200 };
    const partialSuccess = partial.body?.data?.updated === 1 && partial.body?.data?.skipped === 1;
    results.push({ type: 'api', name: 'quality-issue-assign-handle-batch', issueId, batchIssueId: batchIssueId || null, assignStatus: assign.status, confirmStatus: confirm.status, resolveStatus: resolve.status, batchStatus: partial.status, batchResolveStatus: batchResolve.status, batchData: partial.body?.data, conclusion: [assign, confirm, resolve, partial, batchResolve].every(item => item.status === 200) && partialSuccess ? '通过' : '不通过' });
  } else {
    results.push({ type: 'assertion', name: 'quality-issue-assign-handle-batch', conclusion: '无法构造', reason: '真实质量检查未生成可处理问题' });
  }
  const approveSnapshot = await api(page, `/api/admin/snapshots/${primarySnapshotId}/status`, { method: 'PATCH', body: { targetStatus: 'APPROVED', reason: '轨道 A 真实验收' } });
  const publishSnapshot = await api(page, `/api/admin/snapshots/${primarySnapshotId}/publish`, { method: 'POST' });
  results.push({ type: 'api', name: 'snapshot-review-publish', snapshotId: primarySnapshotId, approveStatus: approveSnapshot.status, publishStatus: publishSnapshot.status, conclusion: approveSnapshot.status === 200 && publishSnapshot.status === 200 ? '通过' : '不通过' });

  // Prompt 启停真实链路；只对真实 APPROVED 模板做停用/重新启用。
  const promptList = await api(page, '/api/admin/prompt-templates?page=1&pageSize=50');
  const prompt = (promptList.body?.data?.records || promptList.body?.data || []).find(item => item.status === 'APPROVED');
  if (prompt?.templateCode) {
    const disablePrompt = await api(page, `/api/admin/prompt-templates/${encodeURIComponent(prompt.templateCode)}/enabled`, { method: 'PATCH', body: { enabled: false } });
    const enablePrompt = await api(page, `/api/admin/prompt-templates/${encodeURIComponent(prompt.templateCode)}/enabled`, { method: 'PATCH', body: { enabled: true } });
    results.push({ type: 'api', name: 'prompt-disable-enable', code: prompt.templateCode, disableStatus: disablePrompt.status, enableStatus: enablePrompt.status, conclusion: disablePrompt.status === 200 && enablePrompt.status === 200 ? '通过' : '不通过' });
  } else {
    results.push({ type: 'assertion', name: 'prompt-disable-enable', conclusion: '无法构造', reason: '当前没有 APPROVED Prompt 模板' });
  }
  const entities = await api(page, `/api/admin/catalog/entities?datasourceId=${primaryDatasourceId}`);
  const entityId = entities.body?.data?.[0]?.id || 1;

  const formalRoutes = [
    '/admin/workbench', '/admin/data-sources', '/admin/collections', '/admin/assets', '/admin/releases',
    '/admin/governance', '/admin/governance/issues', '/admin/governance/rules', '/admin/governance/fields',
    '/admin/semantics/glossaries', '/admin/semantics/knowledge', '/admin/semantics/prompts',
    '/admin/access', '/admin/access/approvals', '/admin/access/organization',
    '/admin/operations/queries', '/admin/operations/lineage', '/admin/platform/runtime',
    '/admin/platform/operation-logs', '/admin/platform/ai',
  ];
  for (const [index, path] of formalRoutes.entries()) {
    const routePage = await capture(page, `admin-redesign-p${index < 2 ? 1 : 2}-formal-route-${index + 1}-pass`, index < 2 ? 1 : 2, path);
    const sidebar = await page.locator('.admin-domain-nav').count();
    const workspaceNav = await page.locator('.admin-workspace-nav').count();
    results.push({ type: 'assertion', name: `formal-route-${path}`, requestedPath: path, observedUrl: routePage.finalUrl, sidebar, contentWorkspaceNav: workspaceNav, conclusion: routePage.pagePass && sidebar === 1 && workspaceNav === 0 ? '通过' : '不通过' });
  }

  await page.goto(`${BASE}/admin/workbench`, { waitUntil: 'domcontentloaded', timeout: 15000 });
  await waitForUi(page, 700);
  const domainCount = await page.locator('.admin-domain-nav__item').count();
  const expandedDomain = await page.locator('.admin-domain-nav__item.active').count();
  const collapseButton = page.locator('.admin-shell__collapse');
  await collapseButton.click();
  const collapsed = await page.locator('.admin-shell.is-collapsed').count();
  await page.locator('.admin-domain-nav__item[title="运营与平台"]').click();
  await waitForUi(page, 700);
  const expandedAfterCollapsedClick = await page.locator('.admin-shell.is-collapsed').count() === 0;
  const operationLinkVisible = await page.locator('.admin-domain-nav__workspace-item').filter({ hasText: '操作日志' }).isVisible().catch(() => false);
  const keyboardLink = page.locator('.admin-domain-nav__workspace-item').filter({ hasText: '运行监控' }).first();
  await keyboardLink.focus();
  const focusInSidebar = await page.evaluate(() => Boolean(document.activeElement?.closest('.admin-domain-nav')));
  await page.keyboard.press('Enter');
  await waitForUi(page, 500);
  results.push({ type: 'assertion', name: 'desktop-sidebar-expand-collapse-keyboard', domainCount, expandedDomain, collapsed, expandedAfterCollapsedClick, operationLinkVisible, focusInSidebar, finalUrl: page.url(), conclusion: domainCount === 7 && collapsed === 1 && expandedAfterCollapsedClick && operationLinkVisible && focusInSidebar && page.url().includes('/admin/platform/runtime') ? '通过' : '不通过' });

  // §16.1：数据源上线与驾驶舱；新增数据源表单和连接测试已单独执行。
  await capture(page, 'admin-redesign-p3-data-entry-detail-pass', 3, `/admin/data-sources/${primaryDatasourceId}`);
  await capture(page, 'admin-redesign-p3-assets-pass', 3, `/admin/assets?datasourceId=${primaryDatasourceId}`);
  await capture(page, 'admin-redesign-p3-asset-detail-pass', 3, `/admin/assets/entities/${entityId}`);
  await capture(page, 'admin-redesign-p3-releases-pass', 3, `/admin/releases?datasourceId=${primaryDatasourceId}`);
  await capture(page, 'admin-redesign-p4-governance-pass', 4, `/admin/governance?datasourceId=${primaryDatasourceId}&snapshotId=${primarySnapshotId}`);
  await capture(page, 'admin-redesign-p4-issues-assigned-pass', 4, `/admin/governance/issues?datasourceId=${primaryDatasourceId}&snapshotId=${primarySnapshotId}`);
  await capture(page, 'admin-redesign-p4-rules-pass', 4, `/admin/governance/rules?datasourceId=${primaryDatasourceId}&snapshotId=${primarySnapshotId}`);
  await capture(page, 'admin-redesign-p4-fields-pass', 4, `/admin/governance/fields?datasourceId=${primaryDatasourceId}&snapshotId=${primarySnapshotId}`);

  // F1：真实禁用数据源状态应突出“启用数据源”，而不是“测试连接”。
  const disable = await api(page, '/api/admin/datasources/3/status', { method: 'PATCH', body: { status: 0 } });
  const disabledPage = await capture(page, 'admin-redesign-p1-readiness-disabled-pass', 1, '/admin/data-sources/3');
  results.push({ type: 'assertion', name: 'F1-disabled-readiness', expected: '启用数据源', observed: disabledPage.body.includes('启用数据源'), api: disable, conclusion: disabledPage.body.includes('启用数据源') ? '通过' : '不通过' });
  await api(page, '/api/admin/datasources/3/status', { method: 'PATCH', body: { status: 1 } });

  // §16.2 / F6：跨工作区点击应继承 datasourceId + snapshotId。
  await page.goto(`${BASE}/admin/governance/issues?datasourceId=${primaryDatasourceId}&snapshotId=${primarySnapshotId}`, { waitUntil: 'networkidle', timeout: 15000 });
  await page.waitForTimeout(1000);
  const workspaceLink = page.locator('a').filter({ hasText: '治理总览' }).first();
  const workspaceLinkCount = await workspaceLink.count();
  if (workspaceLinkCount) {
    await workspaceLink.click();
    await page.waitForTimeout(800);
  }
  const inheritedUrl = page.url();
  await page.screenshot({ path: screenshotName('admin-redesign-p1-context-workspace-inherit-pass'), fullPage: true });
  results.push({ type: 'assertion', name: 'F6-workspace-context-inheritance', clickPath: '治理问题中心 -> 治理总览', observedUrl: inheritedUrl, linkFound: Boolean(workspaceLinkCount), conclusion: inheritedUrl.includes(`datasourceId=${primaryDatasourceId}`) && inheritedUrl.includes(`snapshotId=${primarySnapshotId}`) ? '通过' : '不通过' });

  // §16.3 / 阶段 5：知识 6 Tab、术语和 Prompt URL 状态恢复。
  const knowledgeDetail = await capture(page, 'admin-redesign-p5-knowledge-detail-tabs-pass', 5, '/admin/semantics/knowledge/2?tab=content');
  results.push({ type: 'assertion', name: 'knowledge-detail-tabs', expected: ['内容', '来源与覆盖', '审核记录', '版本', '切分预览', '索引状态'], observedTabs: knowledgeDetail.tabs, conclusion: knowledgeDetail.tabs.length === 6 ? '通过' : '不通过' });
  await capture(page, 'admin-redesign-p5-knowledge-review-url-pass', 5, '/admin/semantics/knowledge?tab=review');
  await page.reload({ waitUntil: 'networkidle' });
  results.push({ type: 'assertion', name: 'knowledge-review-refresh', observedUrl: page.url(), activeTabs: await page.locator('.el-tabs__item.is-active, [role="tab"][aria-selected="true"]').allTextContents().catch(() => []), conclusion: page.url().includes('tab=review') ? '通过' : '不通过' });
  await capture(page, 'admin-redesign-p5-glossary-url-state-pass', 5, '/admin/semantics/glossaries?glossaryId=1&termId=1');
  await page.reload({ waitUntil: 'networkidle' });
  results.push({ type: 'assertion', name: 'glossary-refresh', observedUrl: page.url(), conclusion: page.url().includes('glossaryId=1') && page.url().includes('termId=1') ? '通过' : '不通过' });
  await capture(page, 'admin-redesign-p5-prompts-url-state-pass', 5, '/admin/semantics/prompts?code=sql_generation&tab=versions');
  await page.reload({ waitUntil: 'networkidle' });
  results.push({ type: 'assertion', name: 'prompt-refresh', observedUrl: page.url(), activeTabs: await page.locator('.el-tabs__item.is-active, [role="tab"][aria-selected="true"]').allTextContents().catch(() => []), conclusion: page.url().includes('code=sql_generation') && page.url().includes('tab=versions') ? '通过' : '不通过' });

  // §16.4：权限与组织、最终权限预览、访问审批；检查页面是否重复创建数据源选择器。
  await capture(page, 'admin-redesign-p6-access-grants-pass', 6, `/admin/access?datasourceId=${primaryDatasourceId}&tab=grants`);
  const accessPage = await capture(page, 'admin-redesign-p6-access-policies-pass', 6, `/admin/access?datasourceId=${primaryDatasourceId}&tab=policies`);
  const visibleScopeSelectorCount = await page.locator('.scope-bar .el-select:visible').count();
  results.push({ type: 'assertion', name: 'single-datasource-context-selector', visibleScopeSelectorCount, bodyHasDuplicateWarning: accessPage.body.includes('第二个数据源'), conclusion: visibleScopeSelectorCount === 1 && !accessPage.body.includes('第二个数据源') ? '通过' : '不通过' });
  await capture(page, 'admin-redesign-p6-access-approvals-pass', 6, '/admin/access/approvals');
  await capture(page, 'admin-redesign-p6-organization-users-pass', 6, '/admin/access/organization?tab=users');
  await capture(page, 'admin-redesign-p6-organization-roles-pass', 6, '/admin/access/organization?tab=roles');

  // §16.5：异常体验。拦截一个真实接口为 500，观察页面是否显示错误而非假空状态。
  expectedErrorInjectionActive = true;
  await page.route('**/api/admin/dashboard/stats', route => route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 500, message: '验收注入的接口错误', data: null }) }));
  const errorPage = await capture(page, 'admin-redesign-p1-workbench-injected-error-pass', 1, '/admin/workbench', { waitMs: 1600 });
  await page.unroute('**/api/admin/dashboard/stats');
  expectedErrorInjectionActive = false;
  results.push({ type: 'assertion', name: 'error-state-not-empty', observed: errorPage.body.slice(0, 1200), conclusion: /失败|错误|重试|异常/.test(errorPage.body) ? '通过' : '不通过' });

  await capture(page, 'admin-redesign-p7-query-analysis-pass', 7, '/admin/operations/queries?tab=audit');
  await capture(page, 'admin-redesign-p7-lineage-pass', 7, `/admin/operations/lineage?datasourceId=${primaryDatasourceId}`);
  await capture(page, 'admin-redesign-p7-runtime-pass', 7, '/admin/platform/runtime?tab=health');
  await capture(page, 'admin-redesign-p7-operation-logs-pass', 7, '/admin/platform/operation-logs');
  await capture(page, 'admin-redesign-p7-ai-config-pass', 7, '/admin/platform/ai');

  // 知识主流程：使用真实已审核文档执行发布，等待 Java 向量任务完成后再进入问数。
  const generation = await api(page, `/api/admin/knowledge-docs/generate-from-snapshot?datasourceId=${primaryDatasourceId}`, { method: 'POST', body: { snapshotId: primarySnapshotId } });
  const generatedRecords = Array.isArray(generation.body?.data) ? generation.body.data : [];
  results.push({ type: 'api', name: 'knowledge-generate-draft', status: generation.status, docIds: generatedRecords.map(item => item.id).filter(Boolean), conclusion: generation.status === 200 && generatedRecords.some(item => item.id) ? '通过' : '不通过' });
  const knowledgeList = await api(page, `/api/admin/knowledge-docs?datasourceId=${primaryDatasourceId}&page=1&pageSize=50`);
  const generatedIds = generatedRecords.map(item => item.id).filter(Boolean);
  const selectedKnowledgeRecords = generatedIds.length
    ? (knowledgeList.body?.data?.records || []).filter(item => generatedIds.includes(item.id))
    : (knowledgeList.body?.data?.records || []).filter(item => item.status === 'APPROVED').slice(0, 1);
  const knowledgeWork = [];
  for (const selectedKnowledge of selectedKnowledgeRecords) {
    let reviewStatus = null;
    let reviewApproval = null;
    if (selectedKnowledge.status === 'DRAFT') {
      reviewStatus = await api(page, `/api/admin/knowledge-docs/${selectedKnowledge.id}/submit-review`, { method: 'POST' });
      reviewApproval = await api(page, `/api/admin/knowledge-docs/${selectedKnowledge.id}/approve`, { method: 'POST', body: { comment: '轨道 A 真实验收通过' } });
    }
    const publishKnowledge = selectedKnowledge.status === 'PUBLISHED'
      ? { status: 200 }
      : await api(page, `/api/admin/knowledge-docs/${selectedKnowledge.id}/publish`, { method: 'POST' });
    knowledgeWork.push({ selectedKnowledge, reviewStatus, reviewApproval, publishKnowledge, vectorTask: null });
  }
  if (knowledgeWork.length) {
    // 调度器固定 5 分钟轮询，真实验收最多等待 6 分钟而不是把 PENDING 误判为通过。
    for (let i = 0; i < 240; i++) {
      await new Promise(resolve => setTimeout(resolve, 1500));
      let complete = true;
      for (const work of knowledgeWork) {
        const vectorTasks = await api(page, `/api/admin/knowledge-docs/${work.selectedKnowledge.id}/vector-tasks`);
        work.vectorTask = (vectorTasks.body?.data || [])[0];
        if (!work.vectorTask || !['COMPLETED', 'FAILED', 'CLEANUP_PENDING'].includes(work.vectorTask.status)) complete = false;
      }
      if (complete) break;
    }
    for (const work of knowledgeWork) {
      results.push({ type: 'api', name: 'knowledge-review-index-publish', docId: work.selectedKnowledge.id, submitReviewStatus: work.reviewStatus?.status || null, approveStatus: work.reviewApproval?.status || null, publishStatus: work.publishKnowledge.status, vectorStatus: work.vectorTask?.status || null, conclusion: work.publishKnowledge.status === 200 && work.vectorTask?.status === 'COMPLETED' ? '通过' : '不通过' });
    }
  } else {
    results.push({ type: 'assertion', name: 'knowledge-review-index-publish', conclusion: '不通过', reason: '真实知识草稿生成或查询未得到可审核文档，无法绕过审核状态伪造发布' });
  }

  // 真实问数与“猜你想问”：任务、VO、会话 metadata、点击回填和刷新恢复。
  const userReadiness = await api(page, `/api/datasources/${primaryDatasourceId}/readiness`);
  const primaryConversations = await api(page, `/api/query/conversations?datasourceId=${primaryDatasourceId}`);
  results.push({ type: 'api', name: 'conversation-isolation-primary', datasourceId: primaryDatasourceId, status: primaryConversations.status, count: primaryConversations.body?.data?.length ?? null, conclusion: primaryConversations.status === 200 ? '通过' : '不通过' });
  if (secondaryDatasourceId) {
    const secondaryConversations = await api(page, `/api/query/conversations?datasourceId=${secondaryDatasourceId}`);
    const noPrimaryLeak = !(secondaryConversations.body?.data || []).some(item => item.datasourceId === primaryDatasourceId);
    results.push({ type: 'api', name: 'conversation-isolation-secondary', datasourceId: secondaryDatasourceId, status: secondaryConversations.status, count: secondaryConversations.body?.data?.length ?? null, noPrimaryLeak, conclusion: secondaryConversations.status === 200 && noPrimaryLeak ? '通过' : '不通过' });
  }
  results.push({ type: 'api', name: 'datasource-readiness-primary', datasourceId: primaryDatasourceId, status: userReadiness.status, stage: userReadiness.body?.data?.stage, askable: userReadiness.body?.data?.askable, conclusion: userReadiness.status === 200 && userReadiness.body?.data?.askable ? '通过' : '不通过' });
  if (userReadiness.body?.data?.askable) {
    const ask = await api(page, '/api/query/ask', { method: 'POST', body: { datasourceId: primaryDatasourceId, question: '按客户区域汇总订单金额' } });
    const taskId = ask.body?.data?.taskId;
    results.push({ type: 'api', name: 'real-nl2sql-submit', status: ask.status, taskId, conversationId: ask.body?.data?.conversationId, conclusion: ask.status === 200 ? '通过' : '不通过' });
    let task;
    if (taskId) {
      for (let i = 0; i < 60; i++) {
        await new Promise(resolve => setTimeout(resolve, 1500));
        const taskResponse = await api(page, `/api/query/tasks/${taskId}`);
        task = taskResponse.body?.data;
        if (task && ['SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED'].includes(task.status)) break;
      }
      const suggestions = task?.suggestedQuestions || [];
      results.push({ type: 'api', name: 'real-nl2sql-result', status: task?.status, taskId, suggestedQuestions: suggestions, hasError: Boolean(task?.errorMessage), conclusion: task?.status === 'COMPLETED' && Array.isArray(suggestions) ? '通过' : '不通过' });
      const history = await api(page, `/api/query/history?datasourceId=${primaryDatasourceId}&page=1&pageSize=20`);
      results.push({ type: 'api', name: 'query-history-datasource-filter', status: history.status, taskId, requestedDatasourceId: primaryDatasourceId, conclusion: history.status === 200 ? '通过' : '不通过' });
      await capture(page, 'admin-redesign-p7-query-result-pass', 7, `/query?datasourceId=${primaryDatasourceId}`, { waitMs: 1600 });
      const suggestion = suggestions[0];
      if (suggestion) {
        await page.locator('.message-suggestions button').first().click().catch(() => {});
        const inputValue = await page.locator('textarea, input[placeholder*="问题"], input[placeholder*="提问"]').first().inputValue().catch(() => '');
        results.push({ type: 'assertion', name: 'suggested-question-click-fill', expected: suggestion, observed: inputValue, conclusion: inputValue === suggestion ? '通过' : '不通过' });
      }
      await page.reload({ waitUntil: 'domcontentloaded', timeout: 15000 });
      await waitForUi(page, 1600);
      const restoredSuggestionCount = await page.locator('.message-suggestions button').count();
      results.push({ type: 'assertion', name: 'suggested-question-history-restore', expectedAtLeast: suggestion ? 1 : 0, observed: restoredSuggestionCount, conclusion: restoredSuggestionCount >= (suggestion ? 1 : 0) ? '通过' : '不通过' });
      if (secondaryDatasourceId) {
        await page.goto(`${BASE}/query?datasourceId=${secondaryDatasourceId}`, { waitUntil: 'domcontentloaded', timeout: 15000 });
        await waitForUi(page, 1000);
        const secondaryName = datasources.find(item => item.id === secondaryDatasourceId)?.name || '';
        const secondarySelectedName = await page.locator('.datasource-trigger .source-copy strong').innerText().catch(() => '');
        await page.goBack({ waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
        await waitForUi(page, 1000);
        const primarySelectedName = await page.locator('.datasource-trigger .source-copy strong').innerText().catch(() => '');
        const primaryName = datasources.find(item => item.id === primaryDatasourceId)?.name || '';
        results.push({ type: 'assertion', name: 'query-datasource-url-back-forward', secondaryExpected: secondaryName, secondaryObserved: secondarySelectedName, primaryExpected: primaryName, primaryObserved: primarySelectedName, conclusion: secondarySelectedName === secondaryName && primarySelectedName === primaryName ? '通过' : '不通过' });
      }
      await page.goto(`${BASE}/admin/platform/runtime?tab=pools`, { waitUntil: 'domcontentloaded', timeout: 15000 });
      await waitForUi(page, 1200);
      const resetButton = page.getByRole('button', { name: '重置连接池' }).first();
      if (await resetButton.count()) {
        await resetButton.click();
        const confirmation = page.getByRole('dialog').last();
        const confirmationText = await confirmation.innerText().catch(() => '');
        const cancelButton = confirmation.getByRole('button', { name: '取消' });
        if (await cancelButton.count()) await cancelButton.click();
        results.push({ type: 'assertion', name: 'pool-reset-secondary-confirmation', confirmationText, conclusion: confirmationText.includes('正在执行的查询') && confirmationText.includes('确认重置') ? '通过' : '不通过' });
      } else {
        results.push({ type: 'assertion', name: 'pool-reset-secondary-confirmation', conclusion: '无法构造', reason: '查询结束后没有活跃连接池，页面没有展示重置操作' });
      }
    }
  } else {
    results.push({ type: 'assertion', name: 'real-nl2sql-submit', conclusion: '不通过', reason: '真实主数据源 readiness 未达到 askable，无法绕过上游治理发布流程提交查询' });
  }

  // 旧后台 URL 不再兼容：逐个打开并确认没有重定向到正式页面，只允许 NotFound/404。
  const legacy = [
    '/admin/datasources', '/admin/datasources/1/lifecycle', '/admin/metadata/sync', '/admin/metadata/schedule',
    '/admin/metadata/catalog', '/admin/metadata/tables', '/admin/metadata/lifecycle', '/admin/metadata/snapshots',
    '/admin/metadata/version-history', '/admin/metadata/diff?oldId=1&newId=1', '/admin/governance/quality',
    '/admin/governance/status', '/admin/field/tags', '/admin/field/confidence', '/admin/field/feedback-review',
    '/admin/glossary/list', '/admin/knowledge', '/admin/knowledge/editor/2', '/admin/knowledge/versions/2',
    '/admin/knowledge/review', '/admin/prompts', '/admin/permission/access', '/admin/permission/policies',
    '/admin/users', '/admin/roles', '/admin/departments', '/admin/audit/logs', '/admin/audit/slow-queries',
    '/admin/audit/data-lineage', '/admin/system/health', '/admin/system/operation-logs', '/admin/system/ai-config',
  ];
  for (const [index, path] of legacy.entries()) {
    const before = new URL(`${BASE}${path}`);
    const response = await page.goto(before.toString(), { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => null);
    await waitForUi(page, 500);
    const finalUrl = new URL(page.url());
    const body = await page.locator('body').innerText().catch(() => '');
    const notFound = body.includes('页面不存在') || response?.status() === 404;
    results.push({ type: 'legacy-route', index: index + 1, path, httpStatus: response?.status() ?? null, finalUrl: page.url(), notFound, conclusion: notFound && finalUrl.pathname === before.pathname ? '通过' : '不通过' });
  }

  // 桌面端键盘可达性：只记录浏览器实测，不用静态 CSS 结论替代。
  await page.keyboard.press('Tab');
  const focus = await page.evaluate(() => ({ tag: document.activeElement?.tagName, aria: document.activeElement?.getAttribute('aria-label'), text: document.activeElement?.textContent?.trim().slice(0,120) }));
  results.push({ type: 'assertion', name: 'desktop-keyboard-access', focus, conclusion: Boolean(focus.tag) ? '通过' : '不通过' });

  const gitCommit = require('child_process').execFileSync('git', ['rev-parse', 'HEAD'], { cwd: 'D:/projects/DataOcean', encoding: 'utf8' }).trim();
  const evidence = {
    generatedAt: new Date().toISOString(),
    gitCommit,
    browser: 'Chromium via Playwright',
    viewport: { width: 1440, height: 1000 },
    formalRoutes,
    primaryDatasourceId,
    secondaryDatasourceId: secondaryDatasourceId || null,
    primarySnapshotId,
    results,
    consoleErrors,
    expectedConsoleErrors,
    pageErrors,
    requestFailures,
  };
  fs.writeFileSync(`${OUT}/轨道A浏览器验收证据.json`, JSON.stringify(evidence, null, 2), 'utf8');
  console.log(JSON.stringify({ pages: results.filter(x => x.type === 'page').length, assertions: results.filter(x => x.type === 'assertion').length, legacyRoutes: results.filter(x => x.type === 'legacy-route').length, consoleErrors, pageErrors, requestFailures, evidence: `${OUT}/轨道A浏览器验收证据.json` }, null, 2));
  await browser.close();
}

main().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
