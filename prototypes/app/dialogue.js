/* 问数原型只使用本页示例。日期固定，方便核对追问前后的统计范围与数字。 */
(() => {
  'use strict';

  const TODAY = '2026-11-08';
  const WAIT_MS = 30 * 60 * 1000;
  const STORE = '城南店';
  const numbers = new Intl.NumberFormat('zh-CN');
  const weekdays = '日一二三四五六';
  const stock = [
    { name: '盒装牛奶', stock: 248 },
    { name: '盒装酸奶', stock: 96 },
    { name: '盒装豆奶', stock: 72 }
  ];
  const periods = {
    '上周': ['2026-10-26', '2026-11-01'],
    '本周': ['2026-11-02', TODAY],
    '今天': [TODAY, TODAY],
    '昨天': ['2026-11-07', '2026-11-07'],
    '前天': ['2026-11-06', '2026-11-06']
  };

  // 同一批日记录驱动回答、趋势图与核账明细；本周是另一组示例数据。
  const netByDay = [128, 146, 132, 158, 174, 196, 186, 119, 142, 137, 162, 173, 181, 96];
  const returnsByDay = [8, 9, 7, 12, 13, 18, 13, 6, 8, 9, 12, 11, 14, 5];
  const ledger = netByDay.map((net, i) => ({
    date: new Date(Date.UTC(2026, 9, 26 + i)).toISOString().slice(0, 10),
    sold: net + returnsByDay[i],
    returned: returnsByDay[i],
    net
  }));

  function choosePeriod(text, previousPeriod) {
    // 先识别演示外的范围，避免把“上上周”或“上个月”误当成已有周记录。
    if (/上上周|下周|下星期|上个?月|下个?月|本月|这个月|今年|去年|明年|半年|季度|明天|后天|最近\s*[\d一二三四五六七八九十]+\s*[天周个月]/.test(text)) {
      return { unavailable: true };
    }
    const date = text.match(/(?:(20\d{2})[-/.年])?(\d{1,2})[-/.月](\d{1,2})(?:日|号)?/);
    if (date) {
      const iso = (date[1] || '2026') + '-' + date[2].padStart(2, '0') + '-' + date[3].padStart(2, '0');
      if (text.includes('至') || /到\s*(?:20\d{2}[-/.年])?\d{1,2}[-/.月]/.test(text)) return { unavailable: true };
      return { period: iso };
    }
    const week = /上(?:个)?(?:周|星期|礼拜)/.test(text) ? '上周'
      : /(?:本|这)(?:个)?(?:周|星期|礼拜)/.test(text) ? '本周' : null;
    const weekday = text.match(/(?:周|星期|礼拜)([一二三四五六日天])/);
    if (weekday) {
      const previousWasLastWeek = previousPeriod === '上周' || (previousPeriod >= periods['上周'][0] && previousPeriod <= periods['上周'][1]);
      const base = week || (previousWasLastWeek ? '上周' : '本周');
      const offset = '一二三四五六日'.indexOf(weekday[1].replace('天', '日'));
      const start = Date.parse(periods[base][0] + 'T00:00:00Z');
      return { period: new Date(start + offset * 86400000).toISOString().slice(0, 10) };
    }
    if (week) return { period: week };
    if (/前天/.test(text)) return { period: '前天' };
    if (/昨天|昨日/.test(text)) return { period: '昨天' };
    if (/今天|今日|当前|现在/.test(text)) return { period: '今天' };
    return {};
  }

  function chooseMetric(text) {
    // 否定与纠正优先，例如“不扣退货”必须走售出数量。
    if (/(?:不(?:要|用|必|再|需)?|别|无需|不需要)\s*(?:再)?(?:扣|减)|不计退货|不考虑退货|售出盒数|毛销量|原始销量/.test(text)) return 'gross';
    if (/净销量|净售出|净销售量|扣(?:除|减|掉|退货)|减(?:掉|去)退货|不含退货|^扣[。，！!\s]*$/.test(text)) return 'net';
    if (/退货(?:量|数量|盒数|明细)|退了多少|退回.*多少|只看退货/.test(text)) return 'returns';
    return null;
  }

  function unsupportedSubject(text) {
    // 只在常见销售/库存句式中抽取商品名。无法匹配的商品不套用牛奶样例。
    const afterUnit = text.match(/(?:多少|几)盒(?:的)?([\u4e00-\u9fff]+?)(?:[，。？！,?!\s]|$)/);
    if (afterUnit) {
      const name = afterUnit[1].replace(/[呢呀啊吗吧啦了]+$/, '');
      if (name && !stock.some(item => item.name === name || item.name.slice(2) === name)) return true;
    }
    const head = text.match(/^(.*?)(?:卖了|卖出|售出|(?:毛|净)?销量|销售数量|销售量|库存)/);
    if (!head) return false;
    const subject = head[1]
      .replace(/^(?:不|不用|是的|对)[，,]\s*|另问(?:一题)?|换个问题|我想问(?:一下)?|你好|您好|可以|能否|它的|它|这种/g, '')
      .replace(/(?:20\d{2}[-/.年])?\d{1,2}[-/.月]\d{1,2}[日号]?/g, '')
      .replace(/(?:上|本|这)(?:个)?(?:周|星期|礼拜)[一二三四五六日天]?|[昨今]天|今日|昨日|前天|现在|(?:周|星期|礼拜)[一二三四五六日天]/g, '')
      .replace(/本店|城南店|我们店|我店|这家店|本门店|店里/g, '')
      .replace(/请问|能不能|麻烦|帮我|给我|我想|想要|知道|统计|查询|怎么|改为|按天|按日|顺便|算了|一下|每日|每天|看看|查看|了解|还有多少|有多少|还剩|一共|到底|合计|只看|盒装牛奶|牛奶|盒装酸奶|酸奶|盒装豆奶|豆奶/g, '')
      .replace(/[，,。！？!\s的把先再看查那呢换成改为扣掉按毛净算请]/g, '');
    return subject.length > 0;
  }

  function next(previous, input, now = Date.now()) {
    // “改成本周”里的“成本”是动词和时间词的交界，先归一换词表达再判断指标。
    const text = String(input || '').trim().replace(/改成|换成/g, '改为');
    const notice = (message, state = previous) => ({ state, reply: { kind: 'notice', text: message } });
    if (!text) return notice('请输入想查的问题。');
    if (/谁提供|数据来源|数据.*(?:哪来|哪里来|从哪)|查询依据|怎么算的|怎么计算的/.test(text)) {
      return notice('这里使用城南店经营数据的离线示例。每条回答的“查看依据”可核对统计范围和采用的口径。');
    }
    if (/^(?:算了|不查了|取消(?:这次|本次|查询)?|停止查询)[吧啦了。！!\s]*$/.test(text)) {
      return notice('这次问数已结束。', null);
    }
    const namedStores = text.match(/[\u4e00-\u9fffA-Za-z0-9]{1,8}店/g) || [];
    const otherStore = namedStores.some(store => !/(城南|本|本门|我|我们|我们门|这|这家|现有门|当前门)店$/.test(store));
    if (otherStore || /全市|所有门店|全部门店|其他门店|别的店/.test(text)) {
      return notice('当前示例可查城南店的数据。');
    }
    if (/销售额|营业额|金额|多少钱|利润|成本|同比|环比|预测|天气/.test(text)) {
      return notice('这组示例提供牛奶的销售盒数、退货盒数和今天的库存。可以换一个指标继续问。');
    }
    // 优先理解员工明确改口后的表达，同时保留同一句中另外补充的分组条件。
    const corrected = text.replace(/(?:不是|不要查|别查|不查|不看)[^，,。]+[，,]\s*(?:是|要查|改查|看)?/g, '');
    const topic = /库存|存货|还剩多少/.test(corrected) ? 'inventory'
      : /销量|销售数量|销售量|销售情况|卖了|卖出|售出|退货(?:量|数量|盒数|明细)|退了多少/.test(corrected) ? 'sales' : null;
    let metric = chooseMetric(corrected);
    if (!metric && previous?.topic === 'sales' && previous.period && !previous.metric) {
      const firstClause = corrected.split(/[，,。！!\s]/)[0];
      if (/^(?:扣|要扣|要|是|是的|对|要扣除|扣吧)[吧了]*$/.test(firstClause)) metric = 'net';
      if (/^(?:不用|不要|不|不用扣|否|不扣|不必)[了]*$/.test(firstClause)) metric = 'gross';
    }
    const periodChange = choosePeriod(corrected, previous?.period);
    if (periodChange.unavailable) return notice('销售示例覆盖 10 月 26 日至 11 月 8 日，库存示例为 11 月 8 日快照。');
    const daily = /按天|每天|每日|逐日|分天|按日|分日|趋势/.test(text);
    const totalOnly = /只(?:看|要|算|查)(?:合计|总数|总量)|不(?:用|要|必)?(?:再)?按[天日]|合计就行/.test(text);
    const peak = /(?:哪|几)[天日].*(?:最[多少高低])|(?:最[多少高低]).*(?:哪|几)[天日]/.test(text);
    const product = stock.find(item => corrected.includes(item.name.slice(2)));
    if (unsupportedSubject(corrected) || /咖啡|面包|饮料|水果|啤酒|鸡蛋|苹果|饼干|矿泉水|巧克力|饺子|大米/.test(text)) {
      return notice('当前示例包含盒装牛奶的销售数据，以及牛奶、酸奶和豆奶的今日库存。');
    }
    const wantedTopic = topic || (metric ? 'sales' : previous?.topic);
    if (!wantedTopic || !(topic || metric || periodChange.period || daily || totalOnly || peak || product)) {
      return notice('可以试试“本店上周牛奶卖了多少盒？”或“今天库存还有多少？”');
    }
    if (wantedTopic === 'sales' && product && product.name !== '盒装牛奶') {
      return notice('销售示例仅覆盖城南店盒装牛奶；今天的库存也包含酸奶和豆奶。');
    }

    const switched = wantedTopic !== previous?.topic;
    const expired = previous?.waitingUntil != null && now >= previous.waitingUntil;
    const explicitRestart = /另问|换个问题|重新查|算了[，, ]*先/.test(text);
    const independent = switched || expired || explicitRestart || previous.waitingUntil == null;
    const linked = !switched && !explicitRestart;
    const state = {
      request: independent ? (previous?.request || 0) + 1 : previous.request,
      topic: wantedTopic,
      period: periodChange.period || (linked ? previous?.period : null) || (wantedTopic === 'inventory' ? '今天' : null),
      metric: wantedTopic === 'inventory' ? 'stock' : metric || (linked ? previous?.metric : null),
      group: daily && !totalOnly ? 'day' : totalOnly ? 'total' : (linked ? previous?.group : null) || 'total',
      product: wantedTopic === 'sales' ? '盒装牛奶' : product?.name || ((linked || /它|这种/.test(corrected)) ? previous?.product : null),
      waitingUntil: null
    };
    // 过期答复可带入本次仍明确适用的业务条件，但使用新的请求编号。
    if (expired && linked) state.relatedRequest = previous.request;
    else if (!independent && previous.relatedRequest) state.relatedRequest = previous.relatedRequest;
    const clarify = (question, choices) => {
      state.waitingUntil = now + WAIT_MS;
      return { state, reply: { kind: 'clarify', text: question, choices } };
    };

    if (wantedTopic === 'inventory') {
      if (!['今天', TODAY].includes(state.period)) return notice('当前库存示例只有 11 月 8 日的快照。可以直接说“改看今天”。', state);
      if (daily && !totalOnly) return notice('当前示例只提供今天的库存快照，无法按日列出库存趋势。', state);
      const rows = stock.filter(item => !state.product || item.name === state.product).map(item => ({ ...item }));
      const value = rows.reduce((sum, row) => sum + row.stock, 0);
      const scope = STORE + ' · ' + (state.product || '示例商品');
      return { state, reply: { kind: 'answer', text: '统计城南店当前可见的示例商品，按盒计量。', result: {
        type: 'inventory', metric: 'stock', value, unit: '盒', rows, scope, period: '2026.11.08',
        group: 'total', title: '今天库存共 ' + numbers.format(value) + ' 盒。',
        basis: '按 11 月 8 日的示例库存快照汇总各商品实物数量。', stamp: '11/08 09:42'
      } } };
    }

    if (!state.period) return clarify('你想查哪段时间的牛奶销量？', ['上周', '本周', '今天']);
    const [start, end] = periods[state.period] || [state.period, state.period];
    const rows = ledger.filter(row => row.date >= start && row.date <= end).map(row => ({ ...row }));
    if (!rows.length) return notice('这段时间超出了当前示例范围。可以改查上周或本周。', state);
    if (!state.metric) return clarify('退货的要扣掉吗？', ['扣掉退货', '按售出盒数算']);

    const columns = { net: 'net', gross: 'sold', returns: 'returned' };
    const labels = { net: '净售出', gross: '售出', returns: '退货' };
    const sold = rows.reduce((sum, row) => sum + row.sold, 0);
    const returned = rows.reduce((sum, row) => sum + row.returned, 0);
    const key = columns[state.metric];
    const value = rows.reduce((sum, row) => sum + row[key], 0);
    const period = start === end ? start.replaceAll('-', '.') : start.replaceAll('-', '.') + ' — ' + end.slice(5).replace('-', '.');
    const periodLabel = state.period.includes('-') ? start.slice(5).replace('-', '/') : state.period;
    const basis = {
      net: '按业务日统计售出盒数，扣除同期间退货盒数。',
      gross: '按业务日统计售出盒数，未扣除退货。',
      returns: '按退货发生日统计实际退回的盒数。'
    }[state.metric];
    let description = '城南店共售出 ' + numbers.format(sold) + ' 盒，退货 ' + numbers.format(returned) + ' 盒。';
    if (rows.length > 1 && (state.group === 'day' || peak)) {
      const lowest = /最少|最低/.test(text);
      const extreme = rows.reduce((best, row) => (lowest ? row[key] < best[key] : row[key] > best[key]) ? row : best);
      const weekday = weekdays[new Date(extreme.date + 'T00:00:00Z').getUTCDay()];
      description += '周' + weekday + (lowest ? '最低' : '最高') + '，为 ' + numbers.format(extreme[key]) + ' 盒。';
    }
    const title = periodLabel + labels[state.metric] + ' ' + numbers.format(value) + ' 盒牛奶。';
    return { state, reply: { kind: 'answer', text: description, result: {
      type: 'sales', metric: state.metric, value, unit: '盒', rows, scope: STORE + ' · 盒装牛奶',
      period, group: state.group, title, basis, stamp: '11/08 09:42'
    } } };
  }

  window.QueryDemo = { next };
})();
