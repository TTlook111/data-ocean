/* 由实际执行结果投影授权清单，打开来源可回到对应申请的办理详情。 */
(() => {
  'use strict';

  const dateFormat = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: 'Asia/Shanghai'
  });

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function cell(label, ...children) {
    const field = element('div', 'gh-cell');
    const value = element('dd');
    value.append(...children);
    field.append(element('dt', '', label), value);
    return field;
  }

  function render(records, onOpen, view) {
    const { query, onQuery } = view;
    const page = element('section', 'gh-page');
    const title = element('h1', 'gh-title', '授权记录');
    title.tabIndex = -1;
    page.append(title);
    const resetQuery = () => onQuery({
      ...query, keyword: '', status: 'all', fromDate: '', toDate: '', page: 1
    }, 'keyword');
    const controls = element('div', 'gh-controls');
    controls.append(RecordControls.filters({
      value: query,
      statuses: [
        { value: 'all', label: '全部状态' },
        { value: 'effective', label: '已生效' },
        { value: 'expired', label: '已到期' }
      ],
      keywordLabel: '关键词',
      keywordPlaceholder: '申请编号、申请人或授权范围',
      dateLabel: '授权生效日期',
      onApply: next => onQuery({ ...query, ...next, page: 1 }, 'keyword'),
      onReset: resetQuery
    }));
    page.append(controls);
    const result = AdminQuery.run(records, query, 'grants');
    query.page = result.page;
    query.pageSize = result.pageSize;
    page.dataset.resultTotal = String(result.total);
    const hasFilters = Boolean(String(query.keyword || '').trim() || (query.status && query.status !== 'all') ||
      query.fromDate || query.toDate);
    if (!result.total) {
      const empty = element('div', 'ap-empty gh-empty');
      const icon = element('div', 'ap-empty-icon');
      icon.append(WorkbenchIcon('layers', 28));
      empty.append(icon, element('h2', '', hasFilters ? '没有符合条件的授权记录' : '暂无授权记录'),
        element('p', '', hasFilters ? '调整关键词、状态或生效日期后再查询。' : '访问申请执行生效后，可在这里核对授权范围和期限。'));
      if (hasFilters) {
        const clear = element('button', 'gh-empty-link', '清空筛选');
        clear.type = 'button';
        clear.addEventListener('click', resetQuery);
        empty.append(clear);
      } else {
        const requests = element('a', 'gh-empty-link', '查看访问申请');
        requests.href = '#admin/access-requests';
        requests.append(WorkbenchIcon('chevron-right', 14));
        empty.append(requests);
      }
      page.append(empty);
      page.append(RecordControls.pager(result,
        next => onQuery({ ...query, page: next }, 'page'),
        size => onQuery({ ...query, page: 1, pageSize: size }, 'pageSize')));
      return page;
    }

    const list = element('ul', 'gh-grants');
    const header = element('div', 'gh-labels');
    header.setAttribute('aria-hidden', 'true');
    const labels = ['获准范围', '申请人', '有效至', '状态', '来源申请'];
    for (const label of labels) header.append(element('span', '', label));
    for (const item of result.items) {
      const row = element('li', 'gh-row');
      const fields = element('dl', 'gh-fields');
      const resource = QueryCases.resources[item.decision?.resource] || '范围未记录';
      const end = Number.isFinite(item.expiresAt) ? element('time') : element('span', '', '未记录');
      if (end.tagName === 'TIME') {
        end.dateTime = new Date(item.expiresAt).toISOString();
        end.textContent = dateFormat.format(item.expiresAt);
      }
      const status = element('span', 'ap-status', item.statusLabel);
      status.dataset.status = item.status;
      const source = element('button', 'gh-source', item.id);
      source.type = 'button';
      source.dataset.caseId = item.id;
      source.setAttribute('aria-label', '查看来源申请 ' + item.id);
      source.append(WorkbenchIcon('chevron-right', 13));
      source.addEventListener('click', () => onOpen(item.id));
      fields.append(
        cell(labels[0], element('strong', '', resource), element('small', '', item.scope)),
        cell(labels[1], item.applicant || '未记录'),
        cell(labels[2], end, element('small', '', item.decision?.term)),
        cell(labels[3], status), cell(labels[4], source)
      );
      row.append(fields);
      list.append(row);
    }
    const panel = element('div', 'gh-panel');
    panel.append(header, list);
    page.append(panel);
    page.append(RecordControls.pager(result,
      next => onQuery({ ...query, page: next }, 'page'),
      size => onQuery({ ...query, page: 1, pageSize: size }, 'pageSize')));
    return page;
  }

  window.GrantHistory = { render };
})();
