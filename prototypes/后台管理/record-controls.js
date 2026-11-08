/* 查询表单与翻页共用原生控件，提交条件后由页面更新结果。 */
(() => {
  'use strict';

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function field(text, control) {
    const label = element('label', 'rl-field', text);
    label.append(control);
    return label;
  }

  function button(text, className = '') {
    const node = element('button', 'rl-button ' + className, text);
    node.type = 'button';
    return node;
  }

  function filters({ value, statuses, keywordLabel, keywordPlaceholder, dateLabel, onApply, onReset }) {
    const form = element('form', 'rl-filters');
    const keyword = element('input');
    keyword.type = 'search';
    keyword.name = 'keyword';
    keyword.maxLength = 160;
    keyword.placeholder = keywordPlaceholder;
    keyword.value = value.keyword || '';
    keyword.dataset.listFocus = 'keyword';
    const keywordField = field(keywordLabel, keyword);
    keywordField.classList.add('rl-keyword');
    const status = element('select');
    status.name = 'status';
    status.dataset.listFocus = 'status';
    for (const item of statuses) status.add(new Option(item.label, item.value));
    status.value = value.status || 'all';

    const from = element('input'), to = element('input');
    for (const [control, key, label] of [[from, 'fromDate', '开始日期'], [to, 'toDate', '结束日期']]) {
      control.type = 'date';
      control.name = key;
      control.value = value[key] || '';
      control.setAttribute('aria-label', dateLabel + label);
      control.dataset.listFocus = key;
    }
    const dates = element('fieldset', 'rl-dates');
    dates.append(element('legend', '', dateLabel));
    const range = element('div', 'rl-date-range');
    range.append(from, element('span', '', '至'), to);
    dates.append(range);
    const error = element('p', 'rl-error');
    error.id = 'record-date-error';
    error.setAttribute('role', 'alert');
    from.setAttribute('aria-describedby', error.id);
    to.setAttribute('aria-describedby', error.id);
    function validate() {
      const valid = !from.value || !to.value || from.value <= to.value;
      to.setCustomValidity(valid ? '' : '结束日期须晚于或等于开始日期。');
      error.textContent = valid ? '' : '结束日期须晚于或等于开始日期。';
      to.setAttribute('aria-invalid', String(!valid));
      return valid;
    }
    from.addEventListener('input', validate);
    to.addEventListener('input', validate);
    const actions = element('div', 'rl-filter-actions');
    const search = button('查询', 'rl-primary');
    search.type = 'submit';
    const reset = button('重置');
    reset.addEventListener('click', () => onReset());
    actions.append(search, reset);
    form.append(keywordField, field('状态', status), dates, actions, error);
    form.addEventListener('submit', event => {
      event.preventDefault();
      if (!validate()) { to.focus(); return; }
      onApply({ ...value, keyword: keyword.value.trim(), status: status.value,
        fromDate: from.value, toDate: to.value, page: 1 });
    });
    return form;
  }

  function pager(result, onPage, onPageSize) {
    const nav = element('nav', 'rl-pager');
    nav.setAttribute('aria-label', '结果分页');
    const summary = element('span', 'rl-result-count', result.total
      ? '共 ' + result.total + ' 条，当前 ' + result.from + '–' + result.to + ' 条' : '共 0 条');
    summary.setAttribute('role', 'status');
    const size = element('select');
    size.setAttribute('aria-label', '每页条数');
    size.dataset.listFocus = 'pageSize';
    for (const number of [10, 20, 50]) size.add(new Option(number + ' 条 / 页', String(number)));
    size.value = String(result.pageSize);
    size.addEventListener('change', () => onPageSize(Number(size.value)));
    const controls = element('div', 'rl-page-actions');
    const previous = button('上一页');
    previous.disabled = result.page <= 1 || !result.total;
    previous.dataset.listFocus = 'previous';
    previous.addEventListener('click', () => onPage(result.page - 1));
    const next = button('下一页');
    next.disabled = !result.total || result.page >= result.pageCount;
    next.dataset.listFocus = 'next';
    next.addEventListener('click', () => onPage(result.page + 1));
    const jump = element('input');
    jump.type = 'number';
    jump.min = '1';
    jump.max = String(Math.max(1, result.pageCount));
    jump.step = '1';
    jump.value = String(result.page);
    jump.disabled = !result.total;
    jump.setAttribute('aria-label', '跳转页码');
    jump.dataset.listFocus = 'page';
    function go() {
      if (!jump.checkValidity()) { jump.reportValidity(); return; }
      const page = Number(jump.value);
      if (page !== result.page) onPage(page);
    }
    jump.addEventListener('keydown', event => {
      if (event.key === 'Enter') { event.preventDefault(); go(); }
    });
    const pages = element('label', 'rl-page-number', '第');
    pages.append(jump, element('span', '', '/ ' + result.pageCount + ' 页'));
    pages.hidden = result.total === 0;
    const goButton = button('跳转');
    goButton.disabled = !result.total;
    goButton.hidden = result.total === 0;
    goButton.addEventListener('click', go);
    controls.append(previous, pages, goButton, next);
    nav.append(summary, size, controls);
    return nav;
  }

  window.RecordControls = { filters, pager };
})();
