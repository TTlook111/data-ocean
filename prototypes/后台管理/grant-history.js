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

  // 列表只做摘要，完整值保留在 title，来源入口回到同一条申请详情。
  function summary(tag, value) {
    const text = String(value ?? '');
    const node = element(tag, 'gh-truncate', text);
    node.title = text;
    return node;
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
      dateLabel: '生效日期',
      onApply: next => onQuery({ ...query, ...next, page: 1 }, 'keyword'),
      onReset: resetQuery
    }));
    page.append(controls);
    const result = AdminQuery.run(records, query, 'grants');
    query.page = result.page;
    query.pageSize = result.pageSize;
    page.dataset.resultTotal = String(result.total);
    const resultsPanel = element('section', 'gh-results rl-result-panel');
    resultsPanel.setAttribute('aria-label', '授权查询结果');
    page.append(resultsPanel);
    const hasFilters = Boolean(String(query.keyword || '').trim() || (query.status && query.status !== 'all') ||
      query.fromDate || query.toDate);
    let empty;
    if (!result.total) {
      empty = element('div', 'ap-empty gh-empty');
      const icon = element('div', 'ap-empty-icon');
      icon.append(WorkbenchIcon('layers', 28));
      empty.append(icon, element('h2', '', hasFilters ? '没有符合条件的授权记录' : '暂无授权记录'),
        element('p', '', hasFilters ? '调整关键词、状态或生效日期后再查询。' : '访问申请执行生效后，可在这里核对授权范围和期限。'));
      if (hasFilters) {
        const clear = element('button', 'gh-empty-link', '清空筛选');
        clear.type = 'button';
        clear.addEventListener('click', resetQuery);
        empty.append(clear);
      } else if (view.canViewRequests) {
        const requests = element('a', 'gh-empty-link', '查看访问申请');
        requests.href = '#admin/access-requests';
        requests.append(WorkbenchIcon('chevron-right', 14));
        empty.append(requests);
      }
    }

    const table = RecordTable.render({
      caption: '授权记录', minWidth: 776,
      columns: [
        { key: 'resource', label: '获准范围' },
        { key: 'applicant', label: '申请人', width: 128 },
        { key: 'expiresAt', label: '有效至', width: 176 },
        { key: 'status', label: '状态', width: 112 },
        { key: 'source', label: '来源申请', width: 160 }
      ],
      items: result.items, empty,
      row(item) {
        const resource = QueryCases.resources[item.decision?.resource] || '范围未记录';
        const end = Number.isFinite(item.expiresAt) ? element('time') : element('span', '', '未记录');
        if (end.tagName === 'TIME') {
          end.dateTime = new Date(item.expiresAt).toISOString();
          end.textContent = dateFormat.format(item.expiresAt);
        }
        end.classList.add('gh-truncate');
        end.title = end.textContent;
        const status = element('span', 'ap-status', item.statusLabel);
        status.dataset.status = item.status;
        const source = element(typeof onOpen === 'function' ? 'button' : 'span', typeof onOpen === 'function' ? 'gh-source do-action is-primary' : 'gh-truncate');
        if (source.tagName === 'BUTTON') source.type = 'button';
        source.dataset.caseId = item.id;
        if (source.tagName === 'BUTTON') source.setAttribute('aria-label', '查看来源申请 ' + item.id);
        source.title = item.id;
        source.append(summary('span', item.id));
        if (typeof onOpen === 'function') {
          source.append(WorkbenchIcon('chevron-right', 13));
          source.addEventListener('click', () => onOpen(item.id));
        }
        return [
          [summary('strong', resource), summary('small', item.scope)],
          summary('span', item.applicant || '未记录'),
          [end, summary('small', item.decision?.term)], status, source
        ];
      }
    });
    resultsPanel.append(table);
    resultsPanel.append(RecordControls.pager(result,
      next => onQuery({ ...query, page: next }, 'page'),
      size => onQuery({ ...query, page: 1, pageSize: size }, 'pageSize')));
    return page;
  }

  window.GrantHistory = { render };
})();
