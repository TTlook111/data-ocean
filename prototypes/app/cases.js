/* 仅提供固定问数与治理事项示例，不模拟授权裁决、审批执行或授权期限。 */
(() => {
  'use strict';
  const period = '2026.10.26 — 11.01';
  const numbers = new Intl.NumberFormat('zh-CN');
  const publicStatus = {
    blocked: ['输出受限', '当前数据源选用的产品成本限制不允许返回成本信息。'],
    processing: ['处理中', '当前缺少城南店牛奶退货率的已确认口径，本次问数已结束。'],
    published: ['待复验', '退货率口径已发布，正在用生效版本复验原问题。'],
    resolved: ['已复验', '城南店牛奶退货率口径已生效，原问题已通过复验。'],
    explained: ['核查结案', '经核查，现行经营报表只分别统计售出量和退货量。该指标暂不适用，未变更口径。']
  };
  function scenario(input) {
    const profit = /毛利额|毛利润/.test(input), rate = /退货率|退货比例/.test(input);
    if (!profit && !rate) return null;
    const remainder = input.replace(/毛利额|毛利润|退货率|退货比例|城南店|本门店|我们店|本店|店里|上个星期|上星期|上一周|上周/g, '')
      .replace(/请问|麻烦|帮我|查一下|看一下|我想知道|想知道|查询|查看|看看|是多少|有多少|为多少|多少|一共|合计|总共|上一个星期/g, '')
      .replace(/[\s的呢吗呀啊请查问看？?。，,！!]/g, '');
    return { type: profit ? 'profit' : 'knowledge', supported: /上个星期|上星期|上一周|上周/.test(input)
      && !(profit && rate) && (profit ? remainder === '' : /^(?:盒装)?牛奶$/.test(remainder)) };
  }
  const stamp = now => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(now));
  const rate = (sold, returned) => sold ? (100 * returned / sold).toFixed(2) + '%' : '—';
  function answer(type, now) {
    const result = { type: 'business', period, stamp: stamp(now), group: 'total' };
    if (type === 'profit') Object.assign(result, {
      example: 'profit-summary', metric: 'grossProfit', label: '毛利额', value: 4620, unit: '元', scope: '城南店 · 经营汇总',
      title: '上周城南店毛利额为 4,620 元。', basis: '采用已确认的业务周及门店汇总口径。',
      headings: ['门店', '毛利额（元）'], rows: [['城南店', 4620]], totals: ['合计', 4620]
    });
    else {
      const sales = window.QueryDemo.next(null, '城南店上周牛奶净销量，按天列', now).reply.result;
      const sold = sales.rows.reduce((n, row) => n + row.sold, 0), returned = sales.rows.reduce((n, row) => n + row.returned, 0);
      Object.assign(result, {
        example: 'return-rate', metric: 'returnRate', label: '退货率', value: 100 * returned / sold, unit: '%', scope: '城南店 · 盒装牛奶',
        title: '上周城南店牛奶退货率为 ' + rate(sold, returned) + '。',
        basis: '按已发布的经营口径，以退货发生日归属退货盒数，再除以同期售出盒数。',
        headings: ['日期', '售出（盒）', '退货（盒）', '退货率'],
        rows: sales.rows.map(row => [row.date.slice(5).replace('-', '/'), String(row.sold), String(row.returned), rate(row.sold, row.returned)]),
        totals: ['合计', numbers.format(sold), numbers.format(returned), rate(sold, returned)]
      });
    }
    return { kind: 'answer', result, text: type === 'profit' ? '统计范围来自本题指定的城南店。' : '上周退货 ' + result.totals[2] + ' 盒，同期售出 ' + result.totals[1] + ' 盒。' };
  }
  function create(saved, now = Date.now) {
    // 只恢复本版事项；旧版带门店授权和期限的模拟记录不进入当前页面。
    const records = saved?.version === 2 && Array.isArray(saved.records) ? structuredClone(saved.records).filter(item => publicStatus[item.status]) : [];
    const find = id => records.find(item => item.id === id);
    const read = item => ({ ...structuredClone(item), statusLabel: publicStatus[item.status][0], summary: item.status === 'processing' && item.events.at(-1)?.action === 'fail' ? item.events.at(-1).text : publicStatus[item.status][1] });
    const fail = message => ({ ok: false, message }), success = item => ({ ok: true, record: read(item) });
    const rateReady = () => records.some(item => item.type === 'knowledge' && item.status === 'resolved');
    function ask(input, { threadId, turnId }) {
      const text = String(input || '').trim();
      // 固定阻断示例只展示所选规则的含义，不解析任意中文规则或假装已执行 SQL。
      const costQuestion = text.replace(/[\s？?。，,]/g, '');
      if (/^(?:城南店|城北店|本店)?(?:上周)?的?(?:产品成本|销售成本|采购成本)(?:是多少|有多少|多少)$/.test(costQuestion)) {
        let item = records.find(record => record.type === 'access');
        if (!item) {
          item = { id: 'XZ-COST', sourceId: 'retail', type: 'access', status: 'blocked', title: '产品成本信息受限', owner: '当前输出限制规则', links: [], notes: [], events: [], receipt: null };
          records.push(item);
        }
        item.sourceId = 'retail';
        if (!item.links.some(link => link.threadId === threadId && link.turnId === turnId)) item.links.push({ threadId, turnId, text });
        return { kind: 'case', caseId: item.id };
      }
      const match = scenario(text);
      if (!match) return null;
      if (!match.supported) return { kind: 'notice', text: match.type === 'profit' ? '当前毛利额示例为城南店上周汇总，其他条件尚未提供示例。' : '当前退货率示例为城南店上周盒装牛奶，其他条件尚未提供示例。' };
      if (match.type === 'profit') return answer('profit', now());
      const old = records.find(item => item.type === 'knowledge' && item.links.some(link => link.threadId === threadId && link.turnId === turnId));
      if (old) return { kind: 'case', caseId: old.id };
      if (rateReady()) return answer('knowledge', now());
      let item = records.find(record => record.type === 'knowledge');
      if (!item) {
        item = { id: 'ZL-001', type: 'knowledge', status: 'processing', title: '城南店牛奶退货率口径', owner: '经营口径负责人', links: [], notes: [], events: [{ text: '已自动登记经营口径事项，并关联原问题。' }], receipt: null };
        records.push(item);
      }
      item.links.push({ threadId, turnId, text });return { kind: 'case', caseId: item.id };
    }
    function supplement(id, content) {
      const item = find(id), text = String(content || '').trim();
      if (!item || !['processing', 'published'].includes(item.status)) return fail('当前事项已结束或不接受补充。');
      if (!text) return fail('请填写要补充的业务情况。');
      item.notes.push({ text });return success(item);
    }
    const transitions = {
      processing: { publish: ['published', '候选回归通过后已发布，等待生效后复验。'], explain: ['explained', '已核对经营报表适用指标，按核查结论结案。'] },
      published: { verify: ['resolved', '已按生效版本复验原问题，统计范围与计算通过核对。'], fail: ['processing', '生效版本复验未通过，已退回负责人继续处理。'] }
    };
    function simulate(id, action) {
      const item = find(id), transition = item?.type === 'knowledge' && transitions[item.status]?.[action];
      if (!transition) return fail('当前状态无法模拟该操作。');
      const [next, message] = transition;item.status = next;item.events.push({ action, at: now(), actor: item.owner, text: message });
      if (next === 'resolved' || next === 'explained') item.receipt = {
        key: item.id + ':' + next, completedAt: now(), title: next === 'resolved' ? '退货率口径已通过复验' : '退货率核查已结案',
        text: next === 'resolved' ? '城南店牛奶退货率口径已发布并通过复验，可重新查询原问题。' : publicStatus[next][1], canRetry: next === 'resolved'
      };
      return success(item);
    }
    function canView(result) {
      if (result?.type !== 'business') return true;
      if (result.example === 'return-rate') return rateReady();
      return result.example === 'profit-summary' && JSON.stringify(result.headings) === JSON.stringify(['门店', '毛利额（元）']);
    }
    return { ask, supplement, simulate, canView, get: id => find(id) ? read(find(id)) : null, list: () => records.map(read), snapshot: () => ({ version: 2, records: structuredClone(records) }) };
  }
  window.QueryCases = { create };
})();
