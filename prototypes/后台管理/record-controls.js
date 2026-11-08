/* 共用查询和分页直接使用 Element Plus，业务页只提供条件与回调。 */
(() => {
  'use strict';
  const { h, reactive, ref, onMounted } = Vue;
  const { ElInput, ElSelect, ElOption, ElDatePicker, ElButton, ElSpace, ElPagination } = ElementPlus;
  let sequence = 0;

  function filters({ value, statuses, keywordLabel, keywordPlaceholder, dateLabel = '日期范围', includeDates = true, onApply, onReset }) {
    const form = document.createElement('form');
    form.className = 'rl-filters prototype-ui';
    const uid = 'list-filter-' + (++sequence);
    let submit;
    form.addEventListener('submit', event => { event.preventDefault(); submit?.(); });
    return PrototypeUI.mount(form, {
      setup() {
        const state = reactive({ keyword: value.keyword || '', status: value.status || 'all',
          dates: value.fromDate && value.toDate ? [value.fromDate, value.toDate] : null, error: '' });
        const keywordRef = ref();
        const current = () => form.isConnected;
        submit = () => {
          if (!current()) return;
          const dates = includeDates ? [...form.querySelectorAll('.rl-dates input')].map(node => node.value.trim()) : [];
          const [fromDate = '', toDate = ''] = dates;
          if (includeDates && Boolean(fromDate) !== Boolean(toDate)) { state.error = '请选择完整的开始和结束日期。'; return; }
          const isDay = text => /^\d{4}-\d{2}-\d{2}$/.test(text) && Number.isFinite(Date.parse(text)) && new Date(text).toISOString().slice(0, 10) === text;
          if (includeDates && dates.some(text => text && !isDay(text))) { state.error = '请输入有效日期，格式为 YYYY-MM-DD。'; return; }
          if (fromDate && toDate && fromDate > toDate) { state.error = '结束日期须晚于或等于开始日期。'; return; }
          onApply({ ...value, keyword: state.keyword.trim(), status: state.status, page: 1,
            ...(includeDates ? { fromDate, toDate } : {}) });
        };
        onMounted(() => {
          const input = keywordRef.value?.input;
          if (input) input.dataset.listFocus = 'keyword';
          form.querySelector('.rl-status input')?.setAttribute('data-list-focus', 'status');
          form.querySelectorAll('.rl-dates input').forEach((node, index) => {
            node.name = index ? 'toDate' : 'fromDate';
            node.dataset.listFocus = index ? 'toDate' : 'fromDate';
            node.setAttribute('aria-label', dateLabel + (index ? '结束日期' : '开始日期'));
            node.setAttribute('aria-describedby', uid + '-error');
          });
        });
        return () => h('div', { class: 'rl-filter-row' }, [
          h('div', { class: 'rl-keyword' }, [
            h('label', { for: uid + '-keyword', class: 'sr-only' }, keywordLabel),
            h(ElInput, { ref: keywordRef, id: uid + '-keyword', name: 'keyword', modelValue: state.keyword,
              'onUpdate:modelValue': text => { state.keyword = text; }, placeholder: keywordPlaceholder,
              clearable: true, maxlength: 160, 'aria-label': keywordLabel,
              onKeydown: event => { if (event.key === 'Enter' && !event.isComposing) { event.preventDefault(); submit(); } } })
          ]),
          h('div', { class: 'rl-status' }, [
            h(ElSelect, { modelValue: state.status, 'onUpdate:modelValue': next => { state.status = next; },
              ariaLabel: '状态', placeholder: '全部状态', popperClass: 'prototype-ui-popper',
              style: { width: '100%' } }, { default: () => statuses.map(item => h(ElOption, { key: item.value, value: item.value, label: item.label })) })
          ]),
          includeDates ? h('div', { class: 'rl-dates', role: 'group', 'aria-label': dateLabel,
            'aria-describedby': uid + '-error', 'aria-invalid': String(Boolean(state.error)) }, [
            h(ElDatePicker, { modelValue: state.dates,
              'onUpdate:modelValue': next => { state.dates = next; state.error = ''; },
              type: 'daterange', name: ['fromDate', 'toDate'], format: 'YYYY-MM-DD', valueFormat: 'YYYY-MM-DD',
              startPlaceholder: '开始日期', endPlaceholder: '结束日期', rangeSeparator: '至',
              clearable: true, unlinkPanels: true, popperClass: 'prototype-ui-popper',
              style: { width: '100%' } })
          ]) : null,
          h(ElSpace, { class: 'rl-filter-actions', size: 8 }, { default: () => [
            h(ElButton, { type: 'primary', nativeType: 'submit' }, { default: () => '查询' }),
            h(ElButton, { nativeType: 'button', onClick: () => { if (current()) onReset(); } }, { default: () => '重置' })
          ] }),
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
