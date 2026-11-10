/* 共用字面多词匹配，不承载授权范围、审批或期限的业务模拟。 */
(() => {
  'use strict';
  const normalized = value => String(value ?? '').normalize('NFKC').trim().toLocaleLowerCase('zh-CN');
  function matches(values, keyword) {
    const text = normalized(values.join(' '));
    return normalized(keyword).split(/\s+/).filter(Boolean).every(word => text.includes(word));
  }
  window.AdminQuery = { matches };
})();
