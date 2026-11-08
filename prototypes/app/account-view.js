/* 个人管理仅展示当前账号及其本人申请，不切换人员或改变授权。 */
(() => {
  'use strict';

  const dateFormat = new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hourCycle: 'h23', timeZone: 'Asia/Shanghai'
  });
  const statusNames = {
    draft: '草稿', pending: '待审批', needsSupplement: '需补充', withdrawn: '已撤回',
    approved: '待执行', effective: '已生效', partiallyEffective: '部分生效',
    rejected: '已驳回', executionFailed: '执行未生效', expired: '已到期'
  };
  const pageStates = new WeakMap();

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);
    return node;
  }

  function button(text, className = 'rl-button') {
    const node = el('button', className, text);
    node.type = 'button';
    return node;
  }

  function clipped(tag, value, className = '') {
    const node = el(tag, 'pm-ellipsis ' + className, value);
    node.title = String(value);
    return node;
  }

  function field(label, content) {
    const pair = el('div', 'pm-field');
    const value = el('dd');
    if (Array.isArray(content)) {
      for (const word of content.length ? content : ['未分配']) value.append(el('span', 'pm-tag', word));
    } else value.textContent = content || '未分配';
    pair.append(el('dt', '', label), value);
    return pair;
  }

  function normalize(value) {
    return String(value || '').normalize('NFKC').toLocaleLowerCase().trim();
  }

  function render(root, { account, requests = [], onReturn, onOpenRequest, adminRoute }) {
    const records = requests.slice().sort((a, b) =>
      (b.submittedAt || 0) - (a.submittedAt || 0) || String(b.id).localeCompare(String(a.id)));
    // 从原问题返回时保留本账号的申请筛选和页码，换账号则重新开始。
    const previous = pageStates.get(root);
    const state = previous?.accountId === account.id ? previous : {
      accountId: account.id, selected: 'info', query: { keyword: '', status: 'all', page: 1, pageSize: 10 }
    };
    pageStates.set(root, state);
    let selected = state.selected;
    let query = state.query;
    const header = el('header', 'pm-topbar');
    const brand = el('div', 'pm-brand');
    const mark = el('img');
    mark.src = 'assets/dataocean-mark.png';
    mark.alt = '';
    brand.append(mark, el('strong', '', 'DataOcean'), el('span', 'pm-area', '个人管理'));
    const topActions = el('div', 'pm-top-actions');
    if (adminRoute) {
      const admin = el('a', 'pm-top-link', '后台管理');
      admin.href = adminRoute;
      topActions.append(admin);
    }
    const back = button('问数工作台', 'pm-top-link');
    back.addEventListener('click', () => onReturn());
    const currentAccount = clipped('span', account.name, 'pm-current-account');
    topActions.append(back, currentAccount);
    header.append(brand, topActions);

    const layout = el('div', 'pm-layout');
    const side = el('aside', 'pm-sidebar');
    const nav = el('nav', 'pm-nav');
    nav.setAttribute('aria-label', '个人管理菜单');
    nav.append(el('h2', 'pm-nav-group', '个人管理'));
    const navButtons = [];
    for (const [key, text, icon] of [['info', '账号信息', 'user-round'], ['requests', '我的申请', 'inbox']]) {
      const control = button(text, 'pm-nav-item');
      control.prepend(WorkbenchIcon(icon, 16));
      control.dataset.personalTab = key;
      control.setAttribute('aria-controls', 'personal-content');
      control.addEventListener('click', () => { selected = key; draw(); });
      navButtons.push(control);
      nav.append(control);
    }
    side.append(nav);
    const workspace = el('section', 'pm-workspace');
    workspace.id = 'personal-content';
    layout.append(side, workspace);
    root.classList.add('pm-page');
    root.replaceChildren(header, layout);

    function draw(focusKey) {
      state.selected = selected;
      state.query = query;
      for (const control of navButtons) {
        if (control.dataset.personalTab === selected) control.setAttribute('aria-current', 'page');
        else control.removeAttribute('aria-current');
      }
      workspace.replaceChildren(el('h1', 'pm-title', selected === 'info' ? '账号信息' : '我的申请'));
      if (selected === 'info') {
        const panel = el('div', 'pm-info-panel');
        const identity = el('div', 'pm-account');
        const face = el('span', 'pm-avatar', Array.from(account.name || '我')[0]);
        face.setAttribute('aria-hidden', 'true');
        identity.append(face, el('h2', '', account.name));
        const facts = el('dl', 'pm-facts');
        facts.append(field('所属部门', account.departments || []), field('角色', account.roles || []), field('所属业务范围', account.scope));
        panel.append(identity, facts);
        workspace.append(panel);
      } else drawRequests();
      if (focusKey) {
        const target = workspace.querySelector('[data-list-focus="' + focusKey + '"]');
        const control = target?.matches('input,select,button,textarea') ? target : target?.querySelector('input,button,[tabindex]');
        control?.focus({ preventScroll: true });
      }
    }

    function drawRequests() {
      // 查询复用组件库控件，状态枚举不随当前记录数量或状态变化而消失。
      workspace.append(RecordControls.filters({
        includeDates: false, value: query,
        statuses: [{ value: 'all', label: '全部状态' }, ...Object.entries(statusNames).map(([value, label]) => ({ value, label }))],
        keywordLabel: '搜索我的申请', keywordPlaceholder: '申请编号、业务用途或范围',
        onApply: next => { query = next; draw('keyword'); },
        onReset: () => {
          query = { ...query, keyword: '', status: 'all', page: 1 };
          draw('keyword');
        }
      }));

      const words = normalize(query.keyword).split(/\s+/).filter(Boolean);
      const matches = records.filter(item => {
        const text = normalize([item.id, item.title, item.purpose, item.scope].join(' '));
        return words.every(word => text.includes(word)) && (query.status === 'all' || (item.status || item.statusLabel) === query.status);
      });
      const pageCount = matches.length ? Math.ceil(matches.length / query.pageSize) : 0;
      query.page = Math.min(Math.max(1, query.page), Math.max(1, pageCount));
      const from = (query.page - 1) * query.pageSize;
      const panel = el('section', 'pm-results rl-result-panel');
      panel.setAttribute('aria-label', '我的访问申请查询结果');
      panel.dataset.resultTotal = matches.length;
      const feedback = el('p', 'pm-request-feedback');
      feedback.setAttribute('role', 'status');
      feedback.hidden = true;
      panel.append(feedback);
      const empty = el('div', 'pm-empty');
      empty.append(WorkbenchIcon('inbox', 32), el('h2', '', records.length ? '没有符合条件的申请' : '还没有访问申请'));
      empty.append(el('p', '', records.length ? '调整条件后重新查询。' : '问数时申请数据访问后，可在这里查看进度。'));
      // 表头、空态与数据行始终使用同一基础表格，查询无匹配时也保留列结构。
      panel.append(RecordTable.render({
        caption: '我的访问申请', minWidth: '756px',
        columns: [
          { key: 'subject', label: '申请事项' },
          { key: 'scope', label: '业务范围', width: '176px' },
          { key: 'term', label: '申请期限', width: '96px' },
          { key: 'status', label: '状态', width: '112px' },
          { key: 'action', label: '操作', width: '160px' }
        ],
        items: matches.slice(from, from + query.pageSize), empty,
        row(item) {
          const badge = el('span', 'pm-status', item.statusLabel || statusNames[item.status] || '状态未提供');
          badge.dataset.status = item.status;
          if (Number.isFinite(item.submittedAt)) badge.title = '提交于 ' + dateFormat.format(item.submittedAt);
          let action = '';
          if (item.canOpen === false) {
            action = el('span', 'pm-unavailable', '原问题不可回看');
          } else if (typeof onOpenRequest === 'function') {
            const open = button('回到原问题', 'do-action is-primary');
            open.setAttribute('aria-label', '回到申请 ' + item.id + ' 的原问题');
            open.append(WorkbenchIcon('chevron-right', 16));
            open.addEventListener('click', async () => {
              feedback.hidden = true;
              try {
                const response = await onOpenRequest(item);
                if (response?.ok === false) {
                  feedback.textContent = response.message || '原问题暂时无法打开，请稍后重试。';
                  feedback.hidden = false;
                }
              } catch {
                feedback.textContent = '原问题暂时无法打开，请稍后重试。';
                feedback.hidden = false;
              }
            });
            action = open;
          }
          return [
            [clipped('strong', item.title || '访问申请', 'pm-request-title'), clipped('small', item.id + ' · ' + (item.purpose || '尚未提交业务用途'), 'pm-request-description')],
            clipped('span', item.scope || '未指定'), clipped('span', item.term || '未指定'), badge, action
          ];
        }
      }));
      panel.append(RecordControls.pager({ total: matches.length, page: query.page, pageCount, pageSize: query.pageSize },
        page => { query.page = page; draw('page'); },
        size => { query.pageSize = size; query.page = 1; draw('pageSize'); }));
      workspace.append(panel);
    }

    draw();
  }

  window.AccountPage = { render };
})();
