/* 原型按同一组查询条件过滤、排序再分页，页面只接收当前页的记录。 */
(() => {
  'use strict';

  const requestStates = ['pending', 'approved', 'effective', 'rejected', 'executionFailed', 'expired'];
  const grantStates = ['effective', 'expired'];
  const dayFormat = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'
  });

  function normalized(value) {
    return String(value ?? '').normalize('NFKC').trim().toLocaleLowerCase('zh-CN');
  }

  function validDate(value) {
    const text = String(value || '').trim();
    if (!text) return '';
    if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return null;
    const parsed = new Date(text + 'T00:00:00Z');
    return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === text ? text : null;
  }

  function timestamp(record, kind) {
    const value = kind === 'grants' ? record.receipt?.completedAt : record.submittedAt;
    return Number.isFinite(value) && Number.isFinite(new Date(value).getTime()) ? value : null;
  }

  function businessDate(value) {
    const parts = dayFormat.formatToParts(value);
    const get = type => parts.find(part => part.type === type).value;
    return get('year') + '-' + get('month') + '-' + get('day');
  }

  function searchable(record) {
    const resource = record.decision?.resource;
    const resourceName = window.QueryCases?.resources?.[resource] || resource;
    return normalized([
      record.id, record.applicant, record.purpose, record.scope, record.title, resourceName,
      ...(record.links || []).map(link => link.text)
    ].filter(value => value != null).join(' '));
  }

  function run(records, query = {}, kind = 'requests') {
    const keywords = normalized(query.keyword).split(/\s+/).filter(Boolean);
    const fromDate = validDate(query.fromDate);
    const toDate = validDate(query.toDate);
    const invalidDates = fromDate === null || toDate === null || (fromDate && toDate && fromDate > toDate);
    const requestedSize = Number(query.pageSize);
    const pageSize = [10, 20, 50].includes(requestedSize) ? requestedSize : 10;
    const source = Array.isArray(records) ? records : [];
    const states = kind === 'grants' ? grantStates : requestStates;

    const filtered = source.filter(record => {
      if (!record || record.type !== 'access' || !states.includes(record.status) || invalidDates) return false;
      if (kind !== 'grants' && query.group) {
        const group = ['pending', 'approved'].includes(record.status) ? 'todo' : 'done';
        if (group !== query.group) return false;
      }
      if (query.status && query.status !== 'all' && record.status !== query.status) return false;
      if (fromDate || toDate) {
        const at = timestamp(record, kind);
        if (at === null) return false;
        const date = businessDate(at);
        if ((fromDate && date < fromDate) || (toDate && date > toDate)) return false;
      }
      const text = searchable(record);
      return keywords.every(word => text.includes(word));
    });
    filtered.sort((a, b) => {
      const timeA = timestamp(a, kind) ?? -Infinity;
      const timeB = timestamp(b, kind) ?? -Infinity;
      return (timeB - timeA) || String(b.id || '').localeCompare(String(a.id || ''));
    });

    const total = filtered.length;
    const pageCount = Math.ceil(total / pageSize);
    const requestedPage = Number(query.page);
    const positivePage = Number.isFinite(requestedPage) ? Math.max(1, Math.floor(requestedPage)) : 1;
    const page = Math.min(positivePage, Math.max(1, pageCount));
    const start = (page - 1) * pageSize;
    return {
      items: filtered.slice(start, start + pageSize), total, page, pageSize, pageCount,
      from: total ? start + 1 : 0, to: Math.min(start + pageSize, total)
    };
  }

  window.AdminQuery = { run };
})();
