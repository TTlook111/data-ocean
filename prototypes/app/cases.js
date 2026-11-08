/* 店长原型只演示城南店的两项业务；原问题、办理事项和重新发起的查询各自留痕。 */
(() => {
  'use strict';

  const scope = '城南店经营复盘';
  const terms = Object.freeze(['7 天', '14 天', '30 天']);
  const resources = Object.freeze({ profit: '仅毛利额', profitWithBasis: '毛利额及计算依据' });
  const reviewer = '经营业务负责人';
  const period = '2026.10.26 — 11.01';
  const numbers = new Intl.NumberFormat('zh-CN');
  const publicStatus = {
    draft: ['待申请', '当前没有权限回答这个问题。可按业务用途申请访问。'],
    pending: ['待审批', '业务负责人将根据用途匹配所需权限。'],
    approved: ['待执行', '业务负责人已批准申请，授权生效后会通知你。'],
    effective: ['已生效', '本店毛利汇总的查询权限已生效。'],
    rejected: ['已驳回', '本次用途与申请范围不符。补充具体的经营复盘任务后可重新申请。'],
    executionFailed: ['执行未生效', '审批已通过，执行时未通过授权范围复核。本次申请未生效。'],
    expired: ['已到期', '本店毛利汇总的查询权限已到期，需要时可重新申请。'],
    processing: ['处理中', '当前缺少适用于本店牛奶退货率的已确认口径，本次问数已结束。'],
    published: ['待复验', '退货率口径已发布，正在用生效版本复验原问题。'],
    resolved: ['已复验', '本店牛奶退货率口径已生效，原问题已通过复验。'],
    explained: ['核查结案', '经核查，现行经营报表只分别统计售出量和退货量。该指标暂不适用于本店，未变更口径。']
  };

  function scenario(text) {
    const hasProfit = /毛利额|毛利润/.test(text);
    const hasRate = /退货率|退货比例/.test(text);
    if (!hasProfit && !hasRate) return null;
    const type = hasProfit ? 'access' : 'knowledge';
    // 未覆盖的门店、商品、时段或分组不能借用当前示例的结果。
    const remainder = text.replace(/毛利额|毛利润|退货率|退货比例|城南店|本门店|我们店|本店|店里|上个星期|上星期|上一周|上周/g, '')
      .replace(/请问|麻烦|帮我|查一下|看一下|我想知道|想知道|查询|查看|看看|是多少|有多少|为多少|多少|一共|合计|总共|上一个星期/g, '')
      .replace(/[\s的呢吗呀啊请查问看？?。，,！!]/g, '');
    const supported = /上个星期|上星期|上一周|上周/.test(text) && !(hasProfit && hasRate)
      && (hasProfit ? remainder === '' : /^(?:盒装)?牛奶$/.test(remainder));
    return { type, supported };
  }

  function dateTime(ms) {
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
    }).format(new Date(ms));
  }

  function rate(sold, returned) {
    return sold ? (100 * returned / sold).toFixed(2) + '%' : '—';
  }

  function answer(type, now, resource) {
    const result = { type: 'business', period, stamp: dateTime(now), group: 'total' };
    if (type === 'access') {
      // 两个范围均为门店汇总；只有获准计算依据的结果才含销售净额和成本。
      const sales = 18460;
      const cost = 13840;
      const value = sales - cost;
      Object.assign(result, {
        resource, metric: 'grossProfit', label: '毛利额', value, unit: '元', scope: '城南店 · 经营汇总',
        title: '上周城南店毛利额为 ' + numbers.format(value) + ' 元。',
        basis: '采用经营报表的业务周及门店汇总口径。',
        headings: ['门店', '毛利额（元）'], rows: [['城南店', value]], totals: ['合计', value]
      });
      if (resource === 'profitWithBasis') Object.assign(result, {
        basis: '按业务周汇总城南店的毛利额，采用经营报表的销售净额减销售成本口径。',
        headings: ['门店', '销售净额（元）', '销售成本（元）', '毛利额（元）'], rows: [['城南店', sales, cost, value]],
        totals: ['合计', sales, cost, value]
      });
    } else {
      // 退货率的分子与分母取自工作台同一份日流水，周合计按总量相除。
      const sales = window.QueryDemo.next(null, '本店上周牛奶净销量，按天列', now).reply.result;
      const sold = sales.rows.reduce((n, row) => n + row.sold, 0);
      const returned = sales.rows.reduce((n, row) => n + row.returned, 0);
      Object.assign(result, {
        metric: 'returnRate', label: '退货率', value: 100 * returned / sold, unit: '%', scope: '城南店 · 盒装牛奶',
        title: '上周城南店牛奶退货率为 ' + rate(sold, returned) + '。',
        basis: '按本店已发布的经营口径，以退货发生日归属退货盒数，再除以同期售出盒数。',
        headings: ['日期', '售出（盒）', '退货（盒）', '退货率'],
        rows: sales.rows.map(row => [row.date.slice(5).replace('-', '/'), String(row.sold), String(row.returned), rate(row.sold, row.returned)]),
        totals: ['合计', numbers.format(sold), numbers.format(returned), rate(sold, returned)]
      });
    }
    return { kind: 'answer', result, text: type === 'access' ? '统计范围为城南店已获准查看的经营汇总。'
      : '上周退货 ' + result.totals[2] + ' 盒，同期售出 ' + result.totals[1] + ' 盒。' };
  }

  function create(saved, now = Date.now) {
    const records = saved?.version === 1 && Array.isArray(saved.records) ? structuredClone(saved.records) : [];

    function status(item) {
      return item.status === 'effective' && now() >= item.expiresAt ? 'expired' : item.status;
    }

    function decisionFor(item) {
      if (item.decision) return item.decision;
      // 旧演示固定按原申请期限批准毛利和计算依据，保留已作出的决定和原回告。
      if (['approved', 'effective', 'executionFailed'].includes(item.status)) {
        return { resource: 'profitWithBasis', term: item.term, actor: reviewer, at: null };
      }
      if (item.status === 'rejected') return { reason: publicStatus.rejected[1], actor: reviewer, at: null };
      return null;
    }

    function read(item) {
      const state = status(item);
      const [statusLabel, summary] = publicStatus[state];
      const view = { ...structuredClone(item), status: state, statusLabel, summary };
      if (item.type === 'access') {
        view.applicant = item.applicant || '周店长';
        view.decision = structuredClone(decisionFor(item));
        if (state === 'rejected') view.summary = view.decision.reason;
      }
      if (state === 'approved') view.owner = '授权执行人';
      if (state === 'processing' && item.events.at(-1)?.action === 'fail') view.summary = item.events.at(-1).text;
      // 期限届满只改变当前可用性，已办结回告仍是同一份历史记录。
      if (state === 'expired' && view.receipt) view.receipt.canRetry = false;
      return view;
    }

    const find = id => records.find(item => item.id === id);
    const fail = message => ({ ok: false, message });
    const success = item => ({ ok: true, record: read(item) });
    const rateReady = () => records.some(item => item.type === 'knowledge' && status(item) === 'resolved');

    function profitAccess() {
      let resource = null;
      for (const item of records) {
        if (item.type !== 'access' || status(item) !== 'effective') continue;
        const grant = decisionFor(item).resource;
        if (grant === 'profitWithBasis') return grant;
        if (grant === 'profit') resource = grant;
      }
      return resource;
    }

    function ask(input, { threadId, turnId }) {
      const text = String(input || '').trim();
      const match = scenario(text);
      if (!match) return null;
      if (!match.supported) {
        return { kind: 'notice', text: match.type === 'access'
          ? '毛利额示例覆盖城南店上周的门店汇总。可以问“本店上周的毛利额是多少？”'
          : '退货率示例覆盖城南店上周的盒装牛奶。可以问“本店上周牛奶的退货率是多少？”' };
      }
      // 重绘原消息仍展示它当时的阻断记录；新的提问需使用新的 turnId 并重核。
      const old = records.find(item => item.links.some(link => link.threadId === threadId && link.turnId === turnId));
      if (old) return { kind: 'case', caseId: old.id };
      if (match.type === 'access') {
        const grant = profitAccess();
        if (grant) return answer(match.type, now(), grant);
      } else if (rateReady()) return answer(match.type, now());

      let item = records.find(record => record.type === match.type && (match.type === 'knowledge'
        || ['draft', 'pending', 'approved'].includes(status(record))));
      if (!item) {
        const access = match.type === 'access';
        item = {
          id: (access ? 'SQ-' : 'ZL-') + String(records.length + 1).padStart(3, '0'),
          type: match.type, status: access ? 'draft' : 'processing', title: access ? '城南店毛利汇总访问申请' : '城南店牛奶退货率口径',
          owner: access ? '经营业务负责人' : '经营口径负责人', purpose: '', scope, term: '14 天',
          links: [], notes: [], events: [], receipt: null
        };
        if (!access) item.events.push({ text: '已自动登记经营口径事项，并关联原问题。' });
        records.push(item);
      }
      item.links.push({ threadId, turnId, text });
      return { kind: 'case', caseId: item.id };
    }

    function apply(id, fields = {}) {
      const item = find(id);
      if (!item || item.status !== 'draft') return fail('当前状态不能提交申请。');
      const purpose = String(fields.purpose || '').trim();
      if (!purpose) return fail('请填写要完成的业务工作。');
      if (fields.scope !== scope || !terms.includes(fields.term)) return fail('请选择公开业务范围和使用期限。');
      Object.assign(item, { status: 'pending', applicant: '周店长', submittedAt: now(), purpose, scope, term: fields.term });
      item.events.push({ actor: item.applicant, at: item.submittedAt, text: '已按业务用途提交申请，等待业务负责人匹配授权范围。' });
      return success(item);
    }

    function supplement(id, content) {
      const item = find(id);
      if (!item || !['processing', 'published'].includes(item.status)) return fail('当前事项已结束或不接受补充。');
      const text = String(content || '').trim();
      if (!text) return fail('请填写要补充的业务情况。');
      item.notes.push({ text });
      return success(item);
    }

    const knowledgeTransitions = {
      processing: {
        publish: ['published', '业务负责人已确认退货率口径，候选回归通过后已发布，等待生效后复验。'],
        explain: ['explained', '已核对当前经营报表的适用指标，按核查结论结案。']
      },
      published: {
        verify: ['resolved', '已按生效版本复验原问题，统计范围和计算结果通过核对。'],
        fail: ['processing', '生效版本复验未通过，已退回负责人继续处理。']
      }
    };

    const receipts = {
      effective: '访问权限已生效', rejected: '访问申请已驳回', executionFailed: '访问申请未生效',
      resolved: '退货率口径已通过复验', explained: '退货率核查已结案'
    };

    function advance(item, action, next, event, at, message = publicStatus[next][1]) {
      item.status = next;
      item.events.push({ action, at, actor: item.type === 'access' ? reviewer : item.owner, text: event });
      if (receipts[next]) item.receipt = {
        key: item.id + ':' + next, completedAt: at, title: receipts[next], text: message, canRetry: next === 'effective' || next === 'resolved'
      };
      return success(item);
    }

    function validGrant(item, grant) {
      return item.scope === scope && grant != null && Object.hasOwn(resources, grant.resource) &&
        terms.includes(item.term) && terms.includes(grant.term) && Number.parseInt(grant.term, 10) <= Number.parseInt(item.term, 10);
    }

    // 本例企业将本店这两种资源的审批和执行均分配给经营业务负责人。
    function decide(id, action, fields = {}) {
      const item = find(id);
      if (item?.type !== 'access') return fail('请选择需要办理的访问申请。');
      const at = now();
      if (item.status === 'pending' && action === 'reject') {
        const reason = typeof fields?.reason === 'string' ? fields.reason.trim() : '';
        if (!reason || reason.length > 500) return fail('请填写不超过 500 字的公开驳回原因。');
        item.decision = { reason, actor: reviewer, at };
        return advance(item, action, 'rejected', '已驳回申请。' + reason, at,
          '申请已驳回。' + reason);
      }
      if (item.status === 'pending' && action === 'approve') {
        if (!validGrant(item, fields)) return fail('请选择本店可授予的资源，授权期限须在申请期限内。');
        item.decision = { resource: fields.resource, term: fields.term, actor: reviewer, at };
        return advance(item, action, 'approved', '业务负责人已批准申请，等待执行授权。', at);
      }
      if (item.status === 'approved' && ['activate', 'activate-fail'].includes(action)) {
        const grant = decisionFor(item);
        // 执行只兑现批准的范围和期限，重新核对授予上限，不能用执行参数扩大授权。
        if (action === 'activate-fail' || !validGrant(item, grant)) {
          return advance(item, action, 'executionFailed', '执行时未通过授权范围复核，申请未生效。', at);
        }
        item.expiresAt = at + Number.parseInt(grant.term, 10) * 86400000;
        const granted = grant.resource === 'profit' ? '城南店毛利额汇总' : '城南店毛利汇总及其计算依据';
        return advance(item, action, 'effective', '执行时复核通过，批准的范围已生效。', at,
          granted + '的查询权限已生效，有效至 ' + dateTime(item.expiresAt) + '。可重新查询原问题。');
      }
      return fail('当前申请状态不能执行此操作，请查看最新进度。');
    }

    function simulate(id, action) {
      const item = find(id);
      const transition = item?.type === 'knowledge' && knowledgeTransitions[status(item)]?.[action];
      if (!Array.isArray(transition)) return fail('当前状态无法模拟该操作。');
      const [next, event] = transition;
      const message = next === 'resolved' ? '已确认城南店牛奶退货率按退货发生日归入当周，用当周退货盒数除以同期售出盒数。生效版本已通过复验，可重新提问查看结果。' : publicStatus[next][1];
      return advance(item, action, next, event, now(), message);
    }

    function canView(result) {
      if (result?.type !== 'business') return true;
      if (result.metric === 'returnRate') return rateReady();
      if (result.metric !== 'grossProfit') return false;
      const grant = profitAccess();
      // 缺少范围标记的旧答案含计算依据，查看和导出均需完整授权。
      return grant === 'profitWithBasis' || (grant === 'profit' && result.resource === 'profit');
    }

    return { ask, apply, supplement, simulate, decide, canView,
      get: id => find(id) ? read(find(id)) : null,
      list: () => records.map(read),
      snapshot: () => ({ version: 1, records: structuredClone(records) })
    };
  }

  window.QueryCases = { create, resources, terms };
})();
