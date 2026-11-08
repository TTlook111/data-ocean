/* 负责人在后台权限管理中办理已提交的访问申请，申请人在问数工作台接收回告。 */
(() => {
  'use strict';

  const dateFormat = new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hourCycle: 'h23', timeZone: 'Asia/Shanghai'
  });
  const decisionTitles = {
    approved: '审批已通过，等待执行授权',
    effective: '访问权限已生效',
    rejected: '这份申请已驳回',
    executionFailed: '本次授权未生效',
    expired: '访问权限已到期'
  };

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function action(label, name, className) {
    const button = el('button', className, label);
    button.type = 'button';
    button.dataset.apAction = name;
    return button;
  }

  function groupFor(item) {
    return ['pending', 'approved'].includes(item.status) ? 'todo' : 'done';
  }

  function badge(item) {
    const label = el('span', 'ap-status', item.statusLabel);
    label.dataset.status = item.status;
    return label;
  }

  function field(list, label, value) {
    const pair = el('div', 'ap-field-pair');
    pair.append(el('dt', '', label), el('dd', '', value));
    list.append(pair);
  }

  function identityMenu(onReturn) {
    const menu = el('details', 'ap-identity');
    const toggle = el('summary', '', '演示身份');
    toggle.append(el('span', 'ap-identity-current', '经营业务负责人'), WorkbenchIcon('chevron-down', 14));
    const panel = el('div', 'ap-identity-panel');
    const switcher = action('以店长身份打开工作台', 'return', 'ap-identity-switch');
    switcher.addEventListener('click', () => onReturn());
    panel.append(el('p', '', '当前身份 · 经营业务负责人'), switcher);
    menu.append(toggle, panel);
    menu.addEventListener('keydown', event => {
      if (event.key === 'Escape') {
        menu.open = false;
        toggle.focus();
      }
    });
    menu.addEventListener('focusout', event => {
      if (!menu.contains(event.relatedTarget)) menu.open = false;
    });
    return menu;
  }

  function historyOf(item) {
    const history = el('details', 'ap-log');
    const summary = el('summary', '', '处理记录');
    summary.prepend(WorkbenchIcon('chevron-right', 14));
    history.append(summary);
    const list = el('ol');
    for (const event of item.events) {
      const row = el('li');
      const meta = el('div', 'ap-log-meta');
      if (event.at) {
        const time = el('time', '', dateFormat.format(event.at));
        time.dateTime = new Date(event.at).toISOString();
        meta.append(time);
      }
      if (event.actor) meta.append(el('span', 'ap-log-actor', event.actor));
      if (meta.childElementCount) row.append(meta);
      row.append(el('p', 'ap-log-text', event.text));
      list.append(row);
    }
    history.append(list);
    return history;
  }

  function applicationFacts(item) {
    const facts = el('section', 'ap-facts');
    const purpose = el('div', 'ap-purpose-group');
    purpose.append(el('h3', '', '业务用途'), el('p', 'ap-purpose', item.purpose));
    const info = el('dl', 'ap-info-grid');
    field(info, '申请人', item.applicant || '周店长');
    field(info, '业务范围', item.scope);
    field(info, '申请期限', item.term);
    facts.append(purpose, info);
    return facts;
  }

  function reviewForm(item, decide) {
    const form = el('form', 'ap-review');
    const heading = el('div', 'ap-block-heading');
    heading.append(el('h3', '', '授权范围'), el('span', 'ap-mode', '城南店 · 只读'));
    const choices = el('fieldset', 'ap-resources');
    choices.append(el('legend', 'sr-only', '可授予的查询范围'));
    for (const [value, name] of Object.entries(QueryCases.resources)) {
      const label = el('label', 'ap-choice');
      const radio = el('input');
      radio.type = 'radio';
      radio.name = 'resource';
      radio.value = value;
      radio.checked = value === 'profit';
      const words = el('span');
      const detail = value === 'profit' ? '可查看门店及毛利汇总值' : '额外可见销售净额和销售成本';
      words.append(el('strong', '', name), el('small', '', detail));
      label.append(radio, words);
      choices.append(label);
    }
    const termLabel = el('label', 'ap-term', '批准期限');
    const term = el('select');
    term.name = 'term';
    for (const value of QueryCases.terms.filter(value => parseInt(value, 10) <= parseInt(item.term, 10))) {
      term.add(new Option(value, value));
    }
    term.value = item.term;
    termLabel.append(term, el('span', 'ap-hint', '自授权生效之日起'));
    const bar = el('div', 'ap-actions');
    const approve = action('批准申请', 'approve', 'ap-btn ap-primary');
    approve.type = 'submit';
    const reject = action('驳回申请', 'reject', 'ap-btn ap-secondary');
    bar.append(approve, reject);
    const normal = el('div', 'ap-approval-controls');
    normal.append(heading, choices, termLabel, bar);

    const rejection = el('div', 'ap-rejection');
    rejection.hidden = true;
    const reasonLabel = el('label', 'ap-reason', '驳回原因');
    const reason = el('textarea');
    reason.rows = 3;
    reason.maxLength = 500;
    reason.name = 'reason';
    reason.placeholder = '说明需要补充什么，或本次用途为何无法获批';
    reasonLabel.append(reason, el('span', 'ap-hint', '店长会在处理回告中看到此说明'));
    const feedback = el('p', 'ap-error');
    feedback.id = 'ap-reason-error';
    feedback.setAttribute('role', 'alert');
    reason.setAttribute('aria-describedby', feedback.id);
    const rejectActions = el('div', 'ap-actions');
    const confirm = action('确认驳回', 'confirm-reject', 'ap-btn ap-danger');
    confirm.type = 'submit';
    const cancel = action('取消', 'cancel-reject', 'ap-btn ap-secondary');
    rejectActions.append(confirm, cancel);
    rejection.append(reasonLabel, feedback, rejectActions);

    function rejecting(open) {
      normal.hidden = open;
      rejection.hidden = !open;
      approve.disabled = open;
      confirm.disabled = !open;
      feedback.textContent = '';
      reason.removeAttribute('aria-invalid');
      if (open) reason.focus();
      else reject.focus();
    }
    confirm.disabled = true;
    reject.addEventListener('click', () => rejecting(true));
    cancel.addEventListener('click', () => rejecting(false));
    reason.addEventListener('input', () => {
      if (reason.value.trim()) { reason.removeAttribute('aria-invalid'); feedback.textContent = ''; }
    });
    form.addEventListener('submit', event => {
      event.preventDefault();
      if (rejection.hidden) {
        decide('approve', { resource: form.elements.resource.value, term: term.value });
      } else if (reason.value.trim()) {
        decide('reject', { reason: reason.value.trim() });
      } else {
        feedback.textContent = '请填写给店长的驳回原因。';
        reason.setAttribute('aria-invalid', 'true');
        reason.focus();
      }
    });
    form.append(normal, rejection);
    return form;
  }

  function outcome(item, decide) {
    const section = el('section', 'ap-outcome');
    section.append(el('h3', '', item.status === 'rejected' ? '驳回原因' : '本次授权'));
    if (item.decision?.resource) {
      const info = el('dl', 'ap-info-grid ap-grant');
      field(info, '批准范围', '城南店 · ' + QueryCases.resources[item.decision.resource]);
      field(info, '批准期限', item.decision.term);
      if (item.expiresAt) field(info, '有效至', new Date(item.expiresAt).toLocaleString('zh-CN', {
        dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai'
      }));
      section.append(info);
    }
    if (item.status === 'rejected' || item.status === 'executionFailed') {
      section.append(el('p', 'ap-result-text', item.summary));
    }
    if (item.status === 'approved') {
      const buttons = el('div', 'ap-actions');
      const activate = action('执行授权', 'activate', 'ap-btn ap-primary');
      activate.addEventListener('click', () => decide('activate'));
      const fail = action('模拟执行校验失败', 'activate-fail', 'ap-btn ap-quiet');
      fail.addEventListener('click', () => decide('activate-fail'));
      buttons.append(activate, fail);
      section.append(buttons);
    }
    return section;
  }

  const menuItems = [
    { route: 'access-requests', label: '访问申请', icon: 'inbox' },
    { route: 'grants', label: '授权记录', icon: 'layers' }
  ];

  function navigation(section) {
    const nav = el('nav', 'ap-admin-nav');
    nav.setAttribute('aria-label', '后台管理菜单');
    nav.append(el('h2', 'ap-nav-group', '权限管理'));
    for (const item of menuItems) {
      const link = el('a', 'ap-admin-link', item.label);
      link.href = '#admin/' + item.route;
      link.prepend(WorkbenchIcon(item.icon, 17));
      if (section === item.route) link.setAttribute('aria-current', 'page');
      nav.append(link);
    }
    return nav;
  }

  function menuDrawer(section, toggle) {
    const dialog = el('dialog', 'ap-menu-drawer');
    dialog.id = 'admin-menu-drawer';
    dialog.setAttribute('aria-label', '后台菜单');
    const bar = el('div', 'ap-drawer-head');
    const close = action('', 'close-menu', 'ap-menu-close');
    close.setAttribute('aria-label', '关闭后台菜单');
    close.append(WorkbenchIcon('x', 19));
    close.addEventListener('click', () => dialog.close());
    bar.append(el('strong', '', '后台菜单'), close);
    const nav = navigation(section);
    nav.addEventListener('click', event => {
      if (event.target.closest('a')) dialog.close();
    });
    dialog.append(bar, nav);
    dialog.addEventListener('click', event => {
      if (event.target !== dialog) return;
      const bounds = dialog.getBoundingClientRect();
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dialog.close();
    });
    toggle.setAttribute('aria-controls', dialog.id);
    toggle.setAttribute('aria-haspopup', 'dialog');
    toggle.setAttribute('aria-expanded', 'false');
    toggle.addEventListener('click', () => {
      dialog.showModal();
      toggle.setAttribute('aria-expanded', 'true');
      nav.querySelector('[aria-current="page"]').focus();
    });
    dialog.addEventListener('close', () => toggle.setAttribute('aria-expanded', 'false'));
    return dialog;
  }

  function requestInbox(root, context, records, selected) {
    const { query, onQuery } = context.views.requests;
    const group = query.group || 'todo';
    const result = AdminQuery.run(records, query, 'requests');
    query.page = result.page;
    query.pageSize = result.pageSize;
    const queue = el('section', 'ap-queue');
    queue.setAttribute('aria-label', '访问申请');
    queue.dataset.resultTotal = result.total;
    const pagebar = el('header', 'ap-pagebar');
    pagebar.append(el('h1', 'ap-queue-title', '访问申请'));
    queue.append(pagebar);
    const clearQuery = () => onQuery({ ...query, keyword: '', status: 'all', fromDate: '', toDate: '', page: 1 }, 'keyword');
    const statuses = group === 'todo'
      ? [{ value: 'pending', label: '待审批' }, { value: 'approved', label: '待执行' }]
      : [{ value: 'effective', label: '已生效' }, { value: 'rejected', label: '已驳回' },
        { value: 'executionFailed', label: '执行未生效' }, { value: 'expired', label: '已到期' }];
    queue.append(RecordControls.filters({
      value: query, statuses: [{ value: 'all', label: '全部状态' }, ...statuses],
      keywordLabel: '关键词', keywordPlaceholder: '编号、申请人、用途或业务范围',
      dateLabel: '提交日期',
      onApply: value => onQuery({ ...value, page: 1 }, 'keyword'),
      onReset: clearQuery
    }));
    const inbox = el('div', 'ap-inbox');
    inbox.classList.toggle('ap-has-selection', Boolean(selected));
    const mailbox = el('section', 'ap-mailbox');
    mailbox.setAttribute('aria-label', '申请列表');
    const filters = el('div', 'ap-filters');
    function setGroup(key) {
      onQuery({ ...query, group: key, status: 'all', page: 1 }, 'keyword');
    }
    for (const [key, title] of [['todo', '待处理'], ['done', '已办']]) {
      const button = action(title, 'filter-' + key, 'ap-filter');
      const total = AdminQuery.run(records, { ...query, group: key, status: 'all', page: 1 }, 'requests').total;
      button.append(el('span', 'ap-counter', total));
      button.setAttribute('aria-pressed', String(key === group));
      button.addEventListener('click', () => { if (key !== group) setGroup(key); });
      filters.append(button);
    }
    mailbox.append(filters);
    const items = result.items;
    const current = selected || items[0];
    inbox.classList.toggle('ap-no-results', !current);
    const list = el('div', 'ap-items');
    for (const item of items) {
      const link = el('a', 'ap-mail-item');
      link.href = '#admin/access-requests/' + encodeURIComponent(item.id);
      link.dataset.caseId = item.id;
      if (item.id === current?.id) link.dataset.preview = 'true';
      if (item.id === selected?.id) link.setAttribute('aria-current', 'true');
      const meta = el('span', 'ap-mail-meta');
      meta.append(el('span', 'ap-mail-applicant', item.applicant || '周店长'), badge(item));
      const stamp = el('time', 'ap-mail-time', item.submittedAt ? dateFormat.format(item.submittedAt) : item.id);
      if (item.submittedAt) stamp.dateTime = new Date(item.submittedAt).toISOString();
      link.append(meta, el('strong', '', item.title), el('span', 'ap-mail-purpose', item.purpose), stamp);
      link.title = item.id;
      list.append(link);
    }
    if (!items.length && current) list.append(el('p', 'ap-mail-empty', '没有符合当前条件的申请'));
    mailbox.append(list);
    const preview = el('div', 'ap-preview');
    if (current) {
      if (selected && !items.some(item => item.id === selected.id)) {
        const note = el('div', 'ap-selection-note', '当前查看的申请不在本页查询结果中。');
        const backToResults = action('返回查询结果', 'back-to-results', 'ap-btn ap-secondary');
        backToResults.addEventListener('click', () => context.onSelect(null));
        note.append(backToResults);
        preview.append(note);
      }
      preview.append(requestDetail(root, context, current));
    } else {
      const empty = el('div', 'ap-empty');
      const symbol = el('div', 'ap-empty-icon');
      symbol.append(WorkbenchIcon('inbox', 30));
      const hasFilters = Boolean(query.keyword || query.status !== 'all' || query.fromDate || query.toDate);
      const title = hasFilters ? '没有符合条件的申请' : group === 'todo' ? (records.length ? '待办已处理完' : '暂无访问申请') : '还没有已办申请';
      empty.append(symbol, el('h2', '', title),
        el('p', '', hasFilters ? '可以修改关键词或日期，重新查询。' : group === 'todo' ? '员工提交访问申请后，可以在这里办理。' : '申请处理后，可以在这里回看办理结论。'));
      if (hasFilters) {
        const reset = action('清空筛选', 'clear-filters', 'ap-btn ap-secondary');
        reset.addEventListener('click', clearQuery);
        empty.append(reset);
      } else if (records.length) {
        const other = group === 'todo' ? 'done' : 'todo';
        const jump = action(other === 'done' ? '查看已办申请' : '查看待处理申请', 'other-group', 'ap-btn ap-secondary');
        jump.addEventListener('click', () => setGroup(other));
        empty.append(jump);
      }
      preview.append(empty);
    }
    inbox.append(mailbox, preview);
    const resultsPanel = el('section', 'ap-results rl-result-panel');
    resultsPanel.setAttribute('aria-label', '访问申请查询结果');
    resultsPanel.append(inbox, RecordControls.pager(result,
      page => onQuery({ ...query, page }, 'page'),
      pageSize => onQuery({ ...query, pageSize, page: 1 }, 'pageSize')));
    queue.append(resultsPanel);
    return queue;
  }

  function requestDetail(root, context, current) {
    const { onSelect, onDecision } = context;
    const detail = el('article', 'ap-detail');
    detail.dataset.caseId = current.id;
    detail.dataset.status = current.status;
    const back = action('返回申请列表', 'back-to-list', 'ap-back');
    back.prepend(WorkbenchIcon('chevron-right', 14));
    back.addEventListener('click', () => {
      onSelect(null);
      requestAnimationFrame(() => root.querySelector('.ap-mail-item[data-case-id="' + current.id + '"]')?.focus({ preventScroll: true }));
    });
    const idLine = el('div', 'ap-detail-id');
    const pending = current.status === 'pending';
    const question = current.links[0]?.text || current.title;
    if (pending) idLine.append(badge(current));
    idLine.append(el('span', 'ap-id', '申请单 ' + current.id));
    const title = el('h2', 'ap-detail-title', decisionTitles[current.status] || question);
    title.tabIndex = -1;
    const sheetHead = el('header', 'ap-sheet-head');
    sheetHead.append(idLine, title);
    if (!pending) sheetHead.append(el('p', 'ap-source-question', question));
    const error = el('p', 'ap-error');
    error.setAttribute('role', 'alert');
    function decide(key, fields) {
      const response = onDecision(current.id, key, fields);
      if (!response.ok) { error.textContent = response.message; return; }
      // 从待办转到已办时继续展示刚处理的申请，让负责人能核对实际结果。
      if (context.selectedId !== current.id) onSelect(current.id);
      else render(root, context);
      requestAnimationFrame(() => root.querySelector('.ap-detail-title')?.focus({ preventScroll: true }));
    }
    detail.append(sheetHead);
    const facts = applicationFacts(current);
    if (pending) {
      detail.append(facts, reviewForm(current, decide), error);
    } else {
      const contextDetails = el('details', 'ap-log ap-request-context');
      const summary = el('summary', '', '申请资料');
      summary.prepend(WorkbenchIcon('chevron-right', 14));
      contextDetails.append(summary, facts);
      detail.append(outcome(current, decide), error, contextDetails);
    }
    if (current.events.length) detail.append(historyOf(current));
    const wrap = el('div', 'ap-detail-wrap');
    wrap.append(back, detail);
    return wrap;
  }

  function render(root, context) {
    const { desk, section, selectedId, onSelect, onReturn, views } = context;
    const records = desk.list().sort((a, b) => (b.submittedAt || 0) - (a.submittedAt || 0) || b.id.localeCompare(a.id));
    const selected = records.find(item => item.id === selectedId);
    root.querySelector('dialog[open]')?.close();
    const header = el('header', 'ap-top');
    const toggle = action('', 'open-menu', 'ap-menu-toggle');
    toggle.setAttribute('aria-label', '打开后台菜单');
    toggle.append(WorkbenchIcon('menu', 20));
    const brand = el('div', 'ap-brand');
    const logo = el('img');
    logo.src = 'assets/dataocean-mark.png';
    logo.alt = '';
    brand.append(logo, el('strong', '', 'DataOcean'), el('span', 'ap-area', '后台管理'));
    header.append(toggle, brand, identityMenu(onReturn));
    const layout = el('div', 'ap-admin-layout');
    const sidebar = el('aside', 'ap-sidebar');
    sidebar.append(navigation(section));
    const body = el('div', 'ap-body');
    if (section === 'grants') {
      body.append(GrantHistory.render(records, onSelect, views.grants));
    } else {
      if (selected && groupFor(selected) !== views.requests.query.group) {
        Object.assign(views.requests.query, { group: groupFor(selected), status: 'all', page: 1 });
      }
      body.append(requestInbox(root, context, records, selected));
    }
    layout.append(sidebar, body);
    root.classList.add('ap-page');
    root.replaceChildren(header, layout, menuDrawer(section, toggle));
  }

  const compact = window.matchMedia('(max-width: 760px)');
  compact.addEventListener('change', event => {
    if (!event.matches) document.querySelector('.ap-menu-drawer[open]')?.close();
  });
  window.ApprovalPage = { render };
})();
