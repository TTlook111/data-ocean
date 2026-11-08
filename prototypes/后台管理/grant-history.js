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

  function render(records, onOpen) {
    const page = element('section', 'gh-page');
    const title = element('h1', 'gh-title', '授权记录');
    title.tabIndex = -1;
    page.append(title);
    const grants = records.filter(item => item.type === 'access' && ['effective', 'expired'].includes(item.status))
      .sort((a, b) => (b.receipt?.completedAt || 0) - (a.receipt?.completedAt || 0) || b.id.localeCompare(a.id));
    if (!grants.length) {
      const empty = element('div', 'ap-empty gh-empty');
      const icon = element('div', 'ap-empty-icon');
      icon.append(WorkbenchIcon('layers', 28));
      empty.append(icon, element('h2', '', '暂无授权记录'), element('p', '', '访问申请执行生效后，可在这里核对授权范围和期限。'));
      const requests = element('a', 'gh-empty-link', '查看访问申请');
      requests.href = '#admin/access-requests';
      requests.append(WorkbenchIcon('chevron-right', 14));
      empty.append(requests);
      page.append(empty);
      return page;
    }

    const list = element('ul', 'gh-grants');
    const header = element('div', 'gh-labels');
    header.setAttribute('aria-hidden', 'true');
    const labels = ['获准范围', '申请人', '有效至', '状态', '来源申请'];
    for (const label of labels) header.append(element('span', '', label));
    for (const item of grants) {
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
    return page;
  }

  window.GrantHistory = { render };
})();
