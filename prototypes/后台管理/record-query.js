/* 共用字面多词匹配，不承载授权范围、审批或期限的业务模拟。 */
(() => {
  'use strict';
  const normalized = value => String(value ?? '').normalize('NFKC').trim().toLocaleLowerCase('zh-CN');
  function matches(values, keyword) {
    const text = normalized(values.join(' '));
    return normalized(keyword).split(/\s+/).filter(Boolean).every(word => text.includes(word));
  }
  // 各业务条件只匹配自己的字段，条件之间取交集，避免跨列凑词命中。
  const matchesFields = (values, query) => Object.entries(values).every(([key, value]) => matches(Array.isArray(value) ? value : [value], query[key]));
  window.AdminQuery = { matches, matchesFields };
})();
