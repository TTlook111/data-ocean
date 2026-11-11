/* 共用查询和分页直接使用 Element Plus，业务页只提供条件与回调。 */
(() => {
  'use strict';
  const { h, reactive, onMounted } = Vue;
  const { ElInput, ElSelect, ElOption, ElDatePicker, ElButton, ElSpace, ElPagination } = ElementPlus;
  let sequence = 0;

  // 来源类型等枚举筛选复用同一控件，并提供与实际用途一致的可访问名称。
  function filters({ value, statuses, fields, keywordLabel, keywordPlaceholder, statusLabel = '状态', dateLabel = '日期范围', includeDates = true, includeStatus = true, onApply, onReset }) {
    fields = fields || [{ key: 'keyword', label: keywordLabel, placeholder: keywordPlaceholder }];
    const form = document.createElement('form');
    form.className = 'rl-filters prototype-ui' + (fields.length > 1 ? ' has-multiple-fields' : '');
    const uid = 'list-filter-' + (++sequence);
    let submit;
    form.addEventListener('submit', event => { event.preventDefault(); submit?.(); });
    return PrototypeUI.mount(form, {
      setup() {
        const state = reactive({ ...Object.fromEntries(fields.map(field => [field.key, value[field.key] || ''])), status: value.status || 'all',
          dates: value.fromDate && value.toDate ? [value.fromDate, value.toDate] : null, error: '' });
        const current = () => form.isConnected;
        submit = () => {
          if (!current()) return;
          const dates = includeDates ? [...form.querySelectorAll('.rl-dates input')].map(node => node.value.trim()) : [];
          const [fromDate = '', toDate = ''] = dates;
          if (includeDates && Boolean(fromDate) !== Boolean(toDate)) { state.error = '请选择完整的开始和结束日期。'; return; }
          const isDay = text => /^\d{4}-\d{2}-\d{2}$/.test(text) && Number.isFinite(Date.parse(text)) && new Date(text).toISOString().slice(0, 10) === text;
          if (includeDates && dates.some(text => text && !isDay(text))) { state.error = '请输入有效日期，格式为 YYYY-MM-DD。'; return; }
          if (fromDate && toDate && fromDate > toDate) { state.error = '结束日期须晚于或等于开始日期。'; return; }
          onApply({ ...value, ...Object.fromEntries(fields.map(field => [field.key, state[field.key].trim()])), status: state.status, page: 1,
            ...(includeDates ? { fromDate, toDate } : {}) });
        };
        onMounted(() => {
          form.querySelectorAll('.rl-keyword input').forEach(input => { input.dataset.listFocus = input.name; });
          form.querySelector('.rl-status input')?.setAttribute('data-list-focus', 'status');
          form.querySelectorAll('.rl-dates input').forEach((node, index) => {
            node.name = index ? 'toDate' : 'fromDate';
            node.dataset.listFocus = index ? 'toDate' : 'fromDate';
            node.setAttribute('aria-label', dateLabel + (index ? '结束日期' : '开始日期'));
            node.setAttribute('aria-describedby', uid + '-error');
          });
        });
        return () => h('div', { class: 'rl-filter-row' }, [
          ...fields.map(field => h('div', { class: 'rl-keyword' + (field.options ? ' rl-field-select' : ''), key: field.key }, [
            h('label', { for: uid + '-' + field.key, class: 'sr-only' }, field.label),
            field.options ? h(ElSelect, { id: uid + '-' + field.key, name: field.key, modelValue: state[field.key],
              'onUpdate:modelValue': next => { state[field.key] = next || ''; }, ariaLabel: field.label,
              placeholder: field.placeholder || field.label, clearable: true, popperClass: 'prototype-ui-popper', style: { width: '100%' } },
              { default: () => field.options.map(option => h(ElOption, { key: option.value, ...option })) }) :
            h(ElInput, { id: uid + '-' + field.key, name: field.key, modelValue: state[field.key],
              'onUpdate:modelValue': text => { state[field.key] = text; }, placeholder: field.placeholder || field.label,
              clearable: true, maxlength: 160, 'aria-label': field.label,
              onKeydown: event => { if (event.key === 'Enter' && !event.isComposing) { event.preventDefault(); submit(); } } })
          ])),
          includeStatus ? h('div', { class: 'rl-status' }, [
            h(ElSelect, { modelValue: state.status, 'onUpdate:modelValue': next => { state.status = next; },
              ariaLabel: statusLabel, placeholder: statuses[0]?.label, popperClass: 'prototype-ui-popper',
              style: { width: '100%' } }, { default: () => statuses.map(item => h(ElOption, { key: item.value, value: item.value, label: item.label })) })
          ]) : null,
          h('div', { class: 'rl-filter-tail' }, [includeDates ? h('div', { class: 'rl-dates', role: 'group', 'aria-label': dateLabel,
            'aria-describedby': uid + '-error', 'aria-invalid': String(Boolean(state.error)) }, [
            h(ElDatePicker, { modelValue: state.dates,
              'onUpdate:modelValue': next => { state.dates = next; state.error = ''; },
              type: 'daterange', name: ['fromDate', 'toDate'], format: 'YYYY-MM-DD', valueFormat: 'YYYY-MM-DD',
              startPlaceholder: '开始日期', endPlaceholder: '结束日期', rangeSeparator: '至',
              clearable: true, unlinkPanels: true, popperClass: 'prototype-ui-popper',
              style: { width: '100%' } })
          ]) : null,
          h(ElSpace, { class: 'rl-filter-actions', size: 8 }, { default: () => [
            h(ElButton, { nativeType: 'submit' }, { default: () => '查询' }),
            h(ElButton, { nativeType: 'button', onClick: () => { if (current()) onReset(); } }, { default: () => '重置' })
          ] })]),
          h('p', { id: uid + '-error', class: 'rl-error', role: 'alert' }, state.error)
        ]);
      }
    });
  }

  function pager(result, onPage, onPageSize) {
    const nav = document.createElement('nav');
    nav.className = 'rl-pager prototype-ui';
    nav.setAttribute('aria-label', '结果分页');
    return PrototypeUI.mount(nav, {
      setup() {
        onMounted(() => {
          for (const [selector, key] of [['.el-pagination__editor input', 'page'], ['.el-pagination__sizes input', 'pageSize'], ['.btn-prev', 'previous'], ['.btn-next', 'next']]) {
            nav.querySelector(selector)?.setAttribute('data-list-focus', key);
          }
        });
        return () => h('div', { class: 'rl-pagination-content' }, [
          h('span', { class: 'sr-only', role: 'status', 'aria-live': 'polite' }, '共 ' + result.total + ' 条'),
          h(ElPagination, {
          total: result.total, currentPage: Math.max(1, result.page), pageSize: result.pageSize,
          pageSizes: [10, 20, 50], pagerCount: 5, size: 'large',
          layout: result.total ? 'total, ->, sizes, prev, pager, next, jumper' : 'total, ->, sizes, prev, next',
          popperClass: 'prototype-ui-popper',
          'onUpdate:currentPage': page => { if (nav.isConnected && page !== result.page) onPage(page); },
          'onUpdate:pageSize': size => { if (nav.isConnected && size !== result.pageSize) onPageSize(size); }
          })
        ]);
      }
    });
  }
  window.RecordControls = { filters, pager };
})();
