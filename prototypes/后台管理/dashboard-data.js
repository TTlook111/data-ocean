/* 总览评审的固定统计样例；不读取业务库，也不冒充其他模块的实时统计。 */
(() => {
  'use strict';
  const DAY = 86400000;
  const asOf = '2026-11-15';
  const startsAt = '2026-09-17';
  const sources = [
    { id: 'retail', name: '经营业务库', weight: 1 },
    { id: 'finance', name: '财务分析库', weight: 0.58 },
    { id: 'products', name: '商品中心库', weight: 0.76 },
    { id: 'aftersales', name: '售后业务库', weight: 0.34 }
  ];
  const fields = ['requests', 'delivered', 'collectionSuccess', 'collectionFailure', 'collectionCancelled', 'collectionSeconds', 'pendingChanges',
    'tableConfirmed', 'fieldConfirmed', 'relationConfirmed', 'indicatorConfirmed', 'governanceTodo', 'governanceDoing', 'governanceDone', 'governanceReview',
    'availableDocuments', 'publishBatches', 'validationFailed', 'validationErrors', 'knowledgeUpdates', 'knowledgePaused', 'knowledgeForbidden',
    'feedbackNew', 'feedbackSolved', 'feedbackOtherClosed', 'feedbackMerged', 'feedbackOpen', 'feedbackAwaitingVerification', 'feedbackResolutionHours'];
  const stocks = new Set(['pendingChanges', 'governanceTodo', 'governanceDoing', 'governanceDone', 'governanceReview',
    'availableDocuments', 'knowledgeUpdates', 'knowledgePaused', 'knowledgeForbidden', 'feedbackOpen', 'feedbackAwaitingVerification']);
  const date = value => new Date(value + 'T00:00:00Z');
  const iso = value => value.toISOString().slice(0, 10);
  const shift = (value, amount) => iso(new Date(date(value).getTime() + amount * DAY));
  const validDay = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(date(value).getTime()) && iso(date(value)) === value;
  const wave = [0, 26, 14, -18, 32, -48, -68, 5, 39, -8, 22, 47, -41, -53];
  const outstanding = sources.map(source => Math.round(120 * source.weight));
  // 同一日期、数据源始终返回同一份样例，时间切换只筛选，不重新随机造数。
  const rows = Array.from({ length: 60 }, (_, index) => sources.map((source, sourceIndex) => {
    const factor = source.weight;
    const requests = Math.round((130 + index * 2.4 + wave[(index + sourceIndex * 2) % wave.length]) * factor);
    const failure = [12, 26, 43, 50].includes(index) ? (sourceIndex === 0 ? 3 : 1) : (index + sourceIndex) % 11 === 0 ? 1 : 0;
    const collectionSuccess = 3 + ((index + sourceIndex) % 3);
    const feedbackNew = Math.max(0, Math.round((4 + ((index + sourceIndex * 3) % 8)) * factor));
    const feedbackSolved = Math.max(0, Math.round((3 + ((index * 3 + sourceIndex) % 9) + (index >= 35 ? 2 : 0)) * factor));
    const feedbackOtherClosed = (index + sourceIndex) % 5 === 0 ? 1 : 0;
    const feedbackMerged = (index + sourceIndex * 2) % 7 === 0 ? 1 : 0;
    outstanding[sourceIndex] = Math.max(0, outstanding[sourceIndex] + feedbackNew - feedbackSolved - feedbackOtherClosed - feedbackMerged);
    return {
      date: shift(startsAt, index), source: source.id,
      requests, delivered: requests - Math.round(requests * (0.055 + ((index + sourceIndex) % 7) * 0.007)),
      collectionSuccess, collectionFailure: failure, collectionCancelled: (index + sourceIndex) % 13 === 0 ? 1 : 0,
      collectionSeconds: collectionSuccess * (82 + sourceIndex * 14 - index * 0.3 + wave[index % wave.length] * 0.35 + failure * 14),
      pendingChanges: Math.max(0, Math.round((20 - index * 0.19 + wave[index % wave.length] * 0.045 + failure * 2) * factor)),
      tableConfirmed: Math.max(0, Math.round((3 + ((index * 3 + sourceIndex) % 5)) * factor)),
      fieldConfirmed: Math.round((22 + index * 0.55 + wave[(index + 3) % wave.length] * 0.3) * factor),
      relationConfirmed: Math.max(0, Math.round((2 + ((index + sourceIndex) % 6)) * factor)),
      indicatorConfirmed: Math.max(0, Math.round((1 + ((index * 2 + sourceIndex) % 5)) * factor)),
      governanceTodo: Math.max(0, Math.round((42 - index * 0.4) * factor)),
      governanceDoing: Math.round((18 + Math.sin(index / 6) * 4) * factor),
      governanceDone: Math.round((11 + index * 0.47) * factor),
      governanceReview: Math.max(0, Math.round((28 - index * 0.24 + wave[index % wave.length] * 0.1 + failure * 2) * factor)),
      availableDocuments: Math.round((12 + Math.floor(index / 5) * 2 - (index >= 43 && index < 48 ? 3 : 0)) * factor),
      publishBatches: (index + sourceIndex * 2) % 7 === 0 ? 1 + (index % 11 === 0 ? 1 : 0) : 0,
      validationFailed: (index + sourceIndex) % 5 === 0 ? 2 : (index + sourceIndex) % 3 === 0 ? 1 : 0,
      validationErrors: (index + sourceIndex) % 17 === 0 ? 1 : 0,
      knowledgeUpdates: Math.round((5 + (index % 8) * 0.6) * factor),
      knowledgePaused: Math.round((2 + (index >= 43 && index < 48 ? 2 : 0)) * factor),
      knowledgeForbidden: Math.round((1 + (index % 11 < 3 ? 2 : 0)) * factor),
      feedbackNew, feedbackSolved, feedbackOtherClosed, feedbackMerged,
      feedbackOpen: outstanding[sourceIndex],
      feedbackAwaitingVerification: Math.min(outstanding[sourceIndex], Math.round((10 + ((index + sourceIndex) % 7)) * factor)),
      feedbackResolutionHours: feedbackSolved * (24 - index * 0.17 + sourceIndex * 1.5 + wave[(index + 2) % wave.length] * 0.045)
    };
  })).flat();
  const presetRange = days => [shift(asOf, 1 - days), asOf];
  function derived(values) {
    // 平均耗时按实际完成次数加权，不能把每日或各源平均值直接相加。
    values.collectionDuration = values.collectionSuccess ? Number((values.collectionSeconds / values.collectionSuccess).toFixed(1)) : null;
    values.feedbackHours = values.feedbackSolved ? Number((values.feedbackResolutionHours / values.feedbackSolved).toFixed(1)) : null;
    values.completedTasks = values.collectionSuccess === null ? null : values.collectionSuccess + values.collectionFailure + values.collectionCancelled;
    values.governanceObjects = values.governanceTodo === null ? null : values.governanceTodo + values.governanceDoing + values.governanceDone;
    values.closedIssues = values.feedbackSolved === null ? null : values.feedbackSolved + values.feedbackOtherClosed + values.feedbackMerged;
    return values;
  }
  function validateRange(range) {
    if (!Array.isArray(range) || range.length !== 2 || !range.every(validDay)) return '请选择有效的开始和结束日期。';
    if (range[0] > range[1]) return '开始日期不能晚于结束日期。';
    if (range[1] > asOf) return '示例统计截至 2026-11-15，请选择该日期及以前的时段。';
    return '';
  }
  function view({ range = presetRange(30), source = 'all' } = {}) {
    const error = validateRange(range);
    if (error) return { error, points: [], totals: {}, count: 0 };
    if (source !== 'all' && !sources.some(item => item.id === source)) return { error: '数据源不可用。', points: [], totals: {}, count: 0 };
    const [from, to] = range;
    const days = Math.round((date(to) - date(from)) / DAY) + 1;
    const granularity = days <= 60 ? 'day' : days <= 180 ? 'week' : 'month';
    const applicable = rows.filter(row => (source === 'all' || row.source === source) && row.date >= from && row.date <= to);
    const points = [];
    for (let cursor = from; cursor <= to;) {
      let next;
      if (granularity === 'month') {
        const current = date(cursor);
        next = iso(new Date(Date.UTC(current.getUTCFullYear(), current.getUTCMonth() + 1, 1)));
      } else next = shift(cursor, granularity === 'week' ? 7 : 1);
      const end = next > to ? to : shift(next, -1);
      const bucket = applicable.filter(row => row.date >= cursor && row.date <= end);
      const values = Object.fromEntries(fields.map(key => [key, bucket.length ? 0 : null]));
      for (const row of bucket) for (const key of fields) if (!stocks.has(key)) values[key] += row[key];
      // 所有存量统一取桶内最后一个有记录的日期，不能累加每日状态。
      if (bucket.length) {
        const last = bucket.reduce((latest, row) => row.date > latest ? row.date : latest, '');
        for (const key of stocks) values[key] = bucket.filter(row => row.date === last).reduce((sum, row) => sum + row[key], 0);
      }
      points.push({ from: cursor, to: end, label: cursor.slice(5).replace('-', '/'), ...derived(values) });
      cursor = next;
    }
    const totals = Object.fromEntries(fields.map(key => [key, applicable.reduce((sum, row) => sum + row[key], 0)]));
    // 期末值没有快照时保持未知，不借用更早日期的状态填补。
    const endRows = applicable.filter(row => row.date === to);
    for (const key of stocks) totals[key] = endRows.length ? endRows.reduce((sum, row) => sum + row[key], 0) : null;
    derived(totals);
    return {
      range: [...range], source, days, granularity, points, totals, count: applicable.length,
      partial: Boolean(applicable.length) && (from < startsAt || to > asOf),
      sourceName: source === 'all' ? '全部数据源' : sources.find(item => item.id === source).name
    };
  }
  window.DashboardDemo = { asOf, startsAt, sources, presetRange, validateRange, view };
})();
