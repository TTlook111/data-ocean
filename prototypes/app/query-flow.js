/* 离线问数的交互状态。只编排固定样例，不执行 SQL、不解析任意授权规则。 */
(() => {
  'use strict';
  const clone = value => value == null ? value : structuredClone(value);
  const stages = ['理解问题', '核对已发布口径', '生成查询', '核对查询与输出', '执行查询', '核对结果'];
  const stamp = at => new Date(at).toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' });
  const sales = (period, group = 'day') => QueryDemo.next(null, '城南店' + period + '牛奶净销量，' + (group === 'day' ? '按天列' : '只看合计')).reply.result;

  function extraExample(previous, text, now) {
    if (/同比|环比|本周.*上周|上周.*本周|为什么.*下降|下降.*为什么/.test(text)) {
      const normalized = text.replace(/[\s，,。？！?!]/g, '').replace(/按整周比较|按相同进度比较/g, '');
      const supported = ['本周和上周牛奶净销量比较', '本周牛奶净销量除以上周牛奶净销量', '本周净销量除以上周净销量', '为什么本周比上周净销量下降'];
      if (!supported.includes(normalized)) return { state: previous, reply: { kind: 'notice', text: '这条比较样例尚未覆盖当前指标或新增条件。可试“本周和上周牛奶净销量比较”。' } };
      const comparison = /为什么|原因/.test(text) ? 'cause' : /除以|比值/.test(text) ? 'formula' : 'compare';
      const selected = text.includes('相同进度') ? '按相同进度比较' : text.includes('整周') ? '按整周比较' : '';
      if (selected) return extraExample({ topic: 'comparison', comparison }, selected, now);
      return { state: { topic: 'comparison', comparison, waitingUntil: now + 1800000 }, reply: {
        kind: 'clarify', text: '本周和上周按整周比较，还是只比较相同进度？', choices: ['按整周比较', '按相同进度比较'] } };
    }
    if (previous?.topic !== 'comparison' || !/整周|相同进度/.test(text)) return null;
    // 组合答复不能只消费一个选项，未覆盖的新条件保留给用户，不暗中执行旧问题。
    if (text.replace(/按整周比较|按相同进度比较|整周|相同进度|[。！!，,\s]/g, '')) return { state: previous,
      reply: { kind: 'notice', text: '这条比较样例尚未覆盖新增条件。请单独说明要调整的指标或范围。' } };
    const a = sales('上周'), b = sales('本周');
    const delta = b.value - a.value, percent = (100 * delta / a.value).toFixed(2);
    const formula = previous.comparison === 'formula', cause = previous.comparison === 'cause';
    return { state: { ...previous, progress: text, waitingUntil: null }, reply: { kind: 'answer', text: cause
      ? '减少 110 盒。周日减少 90 盒，占净减少量的 81.82%；现有销量记录不能证明客流、价格或促销是原因。'
      : formula ? '本次计算：本周净销量 ÷ 上周净销量；两期单位均为盒、粒度均为门店周合计。分母为零或缺失时不计算比值。' : '两期均按城南店盒装牛奶净销量比较，已扣退货。', result: {
      type: 'business', example: 'comparison', metric: 'comparison', label: formula ? '本次比值' : '两期比较', group: 'total', scope: a.scope,
      period: '2026.10.26 — 11.08', title: formula ? '本周净销量是上周的 ' + (b.value / a.value).toFixed(4) + ' 倍。'
        : '本周比上周净销量减少 ' + Math.abs(delta) + ' 盒（' + Math.abs(Number(percent)) + '%）。',
      basis: '本样例业务时间为 2026-11-08（周日），整周与相同进度均覆盖周一至周日。采用用户明确选择的“' + (text.includes('整周') ? '整周' : '相同进度') + '”；临时计算仅用于本题。',
      headings: ['期间', '净销量（盒）'], rows: [['上周', a.value], ['本周', b.value]], totals: ['差额（本周－上周）', delta], unit: '盒'
    } } };
  }

  function productExample(text, context) {
    if (!context?.documents?.length || context.error) return { kind: 'notice', text: context?.error || '当前数据源尚无可用的已发布知识。请先完成知识整理、验证和发布。', knowledgeGap: true };
    if (/库存|净销量|按天|按月|今天|上周/.test(text)) return { kind: 'notice', text: '当前正式口径只支持商品原销售数量，未确认净销量、实时库存或按时间统计的定义。', knowledgeGap: true };
    const normalized = text.replace(/[\s，,。？！?!]/g, '');
    if (!/^商品101的?(?:原销售数量|原销量|销售数量)(?:是多少(?:盒)?|有多少(?:盒)?|多少(?:盒)?)?$/.test(normalized)) return { kind: 'notice', text: '当前商品样例支持“商品101的原销售数量是多少？”，其他问题尚未提供数据样例。' };
    const metric = context.documents.flatMap(doc => doc.entries).find(entry => entry.id === 'metric:sales-quantity');
    if (!metric) return { kind: 'notice', text: '当前正式知识缺少商品原销售数量指标，请先治理并发布该口径。', knowledgeGap: true };
    const rows = context.sample?.source === 'products' && context.sample.rows?.filter(row => row.product === 101 && row.unit === '盒');
    if (!rows?.length) return { kind: 'notice', text: '正式口径可用，但本批没有可供问数演示的固定验证数据。' };
    const value = rows.reduce((sum, row) => sum + row.quantity, 0);
    return { kind: 'answer', text: '按相同单位汇总本批已发布候选的 ' + rows.length + ' 条固定销售明细；不扣退货，不按商品当前在售状态过滤。', result: {
      type: 'business', example: 'published-products', label: '原销售数量', metric: 'quantity', group: 'total', title: '商品 101 的原销售数量为 ' + value + ' 盒。',
      scope: '商品101 · 业务编码0001', period: '获准样例明细', unit: '盒', basis: metric.content.meaning + '；' + metric.content.usage,
      headings: ['商品内部编号', '业务编码', '原销售数量（盒）'], rows: [['101', rows[0].code, value]], totals: ['合计', '', value],
      knowledge: context.documents.map(doc => ({ id: doc.id, title: doc.title, revision: doc.revision })), governance: metric.revision
    } };
  }

  function open({ id, sourceId, text, previous, caseReply, context, scenario = 'normal', now = Date.now(), related = '' }) {
    const outcome = sourceId === 'products' ? { state: null, reply: productExample(text, context) }
      : sourceId !== 'retail' ? { state: null, reply: { kind: 'notice', text: '当前数据源暂无问数数据样例。' } }
      : caseReply ? { state: null, reply: clone(caseReply) } : extraExample(previous, text, now) || QueryDemo.next(previous, text, now);
    const request = { id, sourceId, text, related, scenario, startedAt: now, stage: 0, state: clone(outcome.state), planned: clone(outcome.reply),
      status: '运行中', database: '未提交', explanation: '未开始', executions: 0, explanationAttempts: 0, remainingExplanation: 2,
      anchor: '2026-11-08 Asia/Shanghai', evidence: { release: 'SAMPLE-RETAIL-r1', revision: 1 }, events: [], reply: null };
    if (previous?.waitingUntil && now >= previous.waitingUntil) request.resume = '等待已到期，已关联原问题建立新请求';
    else if (previous?.waitingUntil && outcome.reply.kind === 'answer') request.resume = /按天|按日|改|换|顺便/.test(text) ? '条件已改变，重新理解问题' : '缺项已补齐，继续原任务';
    if (outcome.reply.kind !== 'answer') {
      request.reply = clone(outcome.reply);
      request.status = outcome.reply.kind === 'clarify' || outcome.state?.waitingUntil ? '等待补充' : outcome.reply.kind === 'case' || outcome.reply.knowledgeGap ? '知识不足' : '示例未覆盖';
      if (outcome.reply.kind === 'case' && outcome.reply.caseId === 'XZ-COST') request.status = '权限不允许';
      if (/天气|写入|删除数据|预测/.test(text)) request.status = '超出问数范围';
      if (/^(取消|不查了|停止查询|算了)[吧啦了。！!\s]*$/.test(text)) request.status = '用户取消';
      request.events.push(request.status);
    }
    return request;
  }

  function finish(request, terminal, message) {
    request.status = terminal; request.reply = { kind: 'notice', text: message };
    request.events.push(terminal); return request;
  }
  function advance(request, now = Date.now()) {
    if (request.status !== '运行中') return request;
    request.events.push(stages[request.stage]);
    // 仅演示毛利样例的有上限修复回路；当前候选未通过时不交付，修复后重新检查。
    if (request.stage === 3 && request.scenario === 'output-repair' && request.planned.result?.example === 'profit-summary' && !request.repairs) {
      request.repairs = 1; request.events.push('样例输出命中成本限制，保持毛利汇总目的重新生成'); request.stage = 2; return request;
    }
    if (request.stage === 3 && request.scenario === 'permission') return finish(request, '权限不允许', '当前数据源访问已失效，本次查询已停止。可申请访问后重新提问。');
    if (request.stage === 3 && request.scenario === 'budget') return finish(request, '资源或预算超限', '本次问数已达到资源上限。请缩小查询范围后重新提问。');
    if (request.stage === 4) {
      if (request.scenario === 'connection') { request.database = '提交失败'; return finish(request, '技术失败', '数据源连接失败，尚未取得结果。连接恢复后可重新查询。'); }
      request.executions++; request.database = '执行中';
      if (request.scenario === 'unknown') { request.database = '状态未知'; return finish(request, '技术失败', '查询连接中断，原任务状态未知。请先核查任务状态。'); }
    }
    request.stage++;
    if (request.stage < stages.length) return request;
    request.database = '已完成'; request.status = '已交付结果'; request.deliveredAt = now;
    request.reply = clone(request.planned); delete request.planned;
    const result = request.reply.result;
    result.stamp = stamp(now); result.sample = true;
    result.queryId = request.id; result.queriedAt = now; result.retainedUntil = now + 90 * 86400000;
    result.knowledge ||= [{ id: 'SAMPLE-RETAIL', title: '城南店经营口径（固定样例）', revision: 1 }];
    result.sql = result.type === 'sales' ? 'SELECT business_day, SUM(sold) AS sold, SUM(returned) AS returned,\n       SUM(sold) - SUM(returned) AS net\nFROM sample_daily_sales\nWHERE store_name = :store AND product_name = :product\n  AND business_day IN (:fixed_dates)\nGROUP BY business_day;\n-- 参数按本题已确认的日期、商品、门店绑定；仅为离线样例，不可执行。'
      : result.example === 'published-products' ? 'SELECT product_id, SUM(quantity) AS quantity\nFROM sample_sale_item\nWHERE product_id = :product_id\nGROUP BY product_id;\n-- 固定样例 product_id = 101；不按当前商品状态过滤，不扣退货。' : 'SELECT /* 本题获准输出 */ * FROM sample_result;\n-- 离线结果结构示意，不连接业务数据库。';
    result.completeness = '完整样例';
    // 空记录、零和 NULL 各自交付，不能用合计零替代未查到记录或未知值。
    if (['empty', 'zero', 'null', 'truncated'].includes(request.scenario)) {
      const sample = request.scenario;
      Object.assign(result, { type: 'business', example: 'boundary', label: '样例结果', headings: ['范围', '数量（盒）'],
        rows: sample === 'empty' ? [] : [['城南店', sample === 'null' ? 'NULL（未知）' : sample === 'zero' ? 0 : 8]],
        totals: sample === 'empty' || sample === 'null' ? [] : ['已返回行合计', sample === 'zero' ? 0 : 8],
        title: sample === 'empty' ? '在这些条件下未查到记录。' : sample === 'null' ? '数量为 NULL，当前值未知。' : sample === 'zero' ? '查询数量为 0 盒。' : '仅返回前 1 行，结果已截断。',
        completeness: sample === 'truncated' ? '已截断 · 返回1行，不代表完整总量' : '完整样例' });
      request.reply.text = sample === 'truncated' ? '导出与表格均仅包含本次已返回的行。' : '';
    }
    request.explanation = request.scenario === 'explanation' ? '失败' : '完成';
    request.reply.explanationStatus = request.explanation;
    request.events.push('已交付结果'); return request;
  }
  function cancel(request) {
    if (request.status === '等待补充') { request.state = null; return finish(request, '用户取消', '这次问数已取消。'); }
    if (request.status !== '运行中') return request;
    request.database = request.executions ? '取消已确认' : '未提交';
    return finish(request, '用户取消', request.executions ? '查询已停止，未交付结果。' : '这次问数已取消，尚未执行查询。');
  }
  function retryExplanation(request, allowed, now = Date.now()) {
    if (!allowed || request.reply?.result?.retainedUntil <= now) return '结果已不可用，请重新核对访问范围或重新查询。';
    if (request.status !== '已交付结果' || !['失败', '已停止'].includes(request.explanation)) return '当前解释无需重试。';
    if (!request.remainingExplanation) return '解释重试预算已用尽，已交付结果仍可使用。';
    request.remainingExplanation--; request.explanationAttempts++; request.explanation = '生成中'; request.reply.explanationStatus = '生成中'; return '';
  }
  function expire(request, now = Date.now()) {
    if (request.status === '等待补充' && request.state?.waitingUntil <= now) finish(request, '等待到期未继续', '等待已到期。继续答复将建立关联的新请求。');
    return request;
  }
  window.QueryFlow = { stages, open, advance, cancel, retryExplanation, expire, productExample };
})();
