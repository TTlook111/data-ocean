(() => {
  'use strict';

  const transcript = document.getElementById('transcript');
  const input = document.getElementById('message');
  const composer = document.getElementById('composer');
  const sendButton = composer.querySelector('.send');
  const history = document.getElementById('history');
  const filter = document.getElementById('history-filter');
  const findButton = document.getElementById('find-chat');
  const sidebar = document.getElementById('sidebar');
  const workspace = document.getElementById('workspace');
  const menuButton = document.querySelector('.menu-toggle');
  const scrim = document.querySelector('.scrim');
  const mobile = matchMedia('(max-width: 720px)');
  const receiptDialog = document.getElementById('receipts');
  const receiptButton = document.getElementById('open-receipts');
  const managerPage = document.getElementById('manager-page');
  const approvalsPage = document.getElementById('approvals-page');
  // 仅保存当前标签页的离线演示，刷新时恢复快照，不重放提问、审批或发布。
  const storageKey = 'dataocean.workbench.cases.v1';
  let saved;
  try { saved = JSON.parse(sessionStorage.getItem(storageKey)); } catch { /* 浏览器可禁用本地存储。 */ }
  const desk = QueryCases.create(saved?.cases);
  const readReceipts = new Set(saved?.readReceipts);
  const threads = [];
  let active;
  let serial = saved?.serial || 0;
  let expiryTimer;
  let returnAction;
  const profitExample = '本店上周的毛利额是多少？';

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
  }

  function mark() {
    const img = element('img');
    img.src = 'assets/dataocean-mark.png';
    img.alt = '';
    return img;
  }

  for (const slot of document.querySelectorAll('[data-icon]')) {
    slot.replaceWith(WorkbenchIcon(slot.dataset.icon));
  }
  const status = element('div', 'sr-only');
  status.setAttribute('role', 'status');
  document.body.append(status);

  // 每段对话独立保存意图、输入草稿和回答节点，回看时保留该条明细的展开状态。
  function createThread(title = '新问数', group = '今天', id = 'chat-' + ++serial) {
    const thread = { id, title, group, turns: [], state: null, flow: element('div', 'flow'), draft: '', choices: null, position: 0 };
    threads.unshift(thread);
    return thread;
  }

  function save() {
    const snapshot = {
      serial, activeId: active?.id, readReceipts: [...readReceipts], cases: desk.snapshot(),
      threads: threads.map(({ id, title, group, turns, draft }) => ({ id, title, group, turns, draft }))
    };
    try { sessionStorage.setItem(storageKey, JSON.stringify(snapshot)); } catch { /* 当前页仍可完整试走。 */ }
  }

  function syncInput() {
    sendButton.disabled = !input.value.trim();
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 110) + 'px';
    if (active) active.draft = input.value;
  }

  function setMenu(open) {
    const isOpen = mobile.matches && open;
    document.body.classList.toggle('sidebar-open', isOpen);
    scrim.hidden = !isOpen;
    sidebar.inert = mobile.matches && !isOpen;
    workspace.inert = isOpen;
    menuButton.setAttribute('aria-expanded', String(isOpen));
    if (isOpen) sidebar.querySelector('.sidebar-close').focus();
  }

  function drawHistory() {
    history.replaceChildren();
    const needle = filter.hidden ? '' : filter.value.trim().toLowerCase();
    let lastGroup;
    for (const thread of threads.filter(item => item.title.toLowerCase().includes(needle))) {
      if (thread.group !== lastGroup) {
        history.append(element('p', 'section-label', thread.group));
        lastGroup = thread.group;
      }
      const button = element('button', 'history', thread.title);
      button.type = 'button';
      if (thread === active) button.setAttribute('aria-current', 'page');
      button.addEventListener('click', () => { selectThread(thread); save(); });
      history.append(button);
    }
    if (!history.childElementCount) history.append(element('p', 'history-empty', '没有匹配的对话'));
  }

  function selectThread(thread, focusInput = false) {
    if (active) {
      active.draft = input.value;
      active.position = transcript.scrollTop;
    }
    active = thread;
    refreshAccess(thread);
    setMenu(false);
    transcript.replaceChildren(thread.flow);
    transcript.scrollTop = thread.position;
    document.getElementById('thread-title').textContent = thread.title;
    document.title = thread.title + ' · DataOcean 问数工作台';
    input.value = thread.draft;
    syncInput();
    drawHistory();
    if (focusInput) input.focus();
    else if (mobile.matches) menuButton.focus();
  }

  function drawEmpty(thread) {
    const empty = element('div', 'empty');
    empty.append(mark(), element('h1', '', '想了解店里的什么数据？'));
    const prompts = element('div', 'prompts');
    for (const [name, text] of [
      ['milk', '本店上周牛奶卖了多少盒？'],
      ['warehouse', '今天还有多少牛奶库存？'],
      ['store', profitExample],
      ['layers', '本店上周牛奶的退货率是多少？'],
    ]) {
      const prompt = element('button', 'prompt');
      prompt.type = 'button';
      prompt.append(WorkbenchIcon(name, 16), document.createTextNode(text));
      prompt.addEventListener('click', () => submitMessage(text));
      prompts.append(prompt);
    }
    empty.append(prompts);
    thread.flow.append(empty);
  }

  function hideRestricted(turn) {
    turn.querySelector('.qa-answer')?.remove();
    if (!turn.querySelector('.access-ended')) turn.append(element('p', 'access-ended', '当前权限不足以查看这条历史回答。可重新提问，按现有权限查询。'));
  }

  function refreshAccess(thread) {
    for (const entry of thread.turns) {
      const { id, reply } = entry;
      if (reply.kind !== 'case' && reply.result?.type !== 'business') continue;
      const turn = thread.flow.querySelector('#' + id);
      if (reply.kind === 'case') {
        if (turn.querySelector('.case-card')?.dataset.caseStatus !== desk.get(reply.caseId).status) renderCase(thread, entry, turn);
      } else if (!desk.canView(reply.result)) {
        hideRestricted(turn);
      } else {
        const message = turn.querySelector('.access-ended');
        if (message) message.replaceWith(QueryAnswers.render(reply));
      }
    }
  }

  function renderCase(thread, entry, turn) {
    const record = desk.get(entry.reply.caseId);
    const previousCard = turn.querySelector('.case-card');
    const card = CaseView.render(record, (action, value) => {
      if (action === 'retry' || action === 'reapply') {
        // 从卡片所关联的原问创建新一轮，重新核验此刻的访问权限和生效口径。
        if (active !== thread) selectThread(thread);
        const draft = input.value;
        submitMessage(entry.text);
        if (draft) { input.value = draft; syncInput(); save(); }
        return { ok: true };
      }
      const result = action === 'apply' ? desk.apply(record.id, value)
        : action === 'supplement' ? desk.supplement(record.id, value) : desk.simulate(record.id, value);
      if (result.ok) {
        syncCase(record.id);
        refreshAccess(active);
        drawReceipts();
        save();
      }
      status.textContent = !result.ok ? result.message : action === 'supplement' ? '补充已提交' : result.record.receipt?.title || result.record.statusLabel;
      return result;
    }, previousCard);
    if (previousCard) previousCard.replaceWith(card);
    else turn.append(card);
  }

  function syncCase(id) {
    for (const owner of threads) {
      for (const entry of owner.turns) {
        if (entry.reply.caseId === id) renderCase(owner, entry, owner.flow.querySelector('#' + entry.id));
      }
    }
  }

  function decideAccess(id, action, fields) {
    if (approvalsPage.hidden) return { ok: false, message: '请切换到负责人视角处理申请。' };
    const result = desk.decide(id, action, fields);
    if (result.ok) {
      syncCase(id);
      refreshAccess(active);
      drawReceipts();
      save();
    }
    status.textContent = result.ok ? result.record.statusLabel : result.message;
    return result;
  }

  // 两个视角共用一份申请和回告。切换与刷新只还原页面，审批和重查必须由本人点击。
  function showPerspective() {
    const [view, selectedId] = location.hash.slice(1).split('/');
    const reviewing = view === 'approvals';
    const changed = managerPage.hidden !== reviewing;
    if (changed) {
      setMenu(false);
      if (receiptDialog.open) receiptDialog.close();
    }
    managerPage.hidden = reviewing;
    approvalsPage.hidden = !reviewing;
    if (reviewing) {
      document.title = '访问申请审批 · DataOcean';
      // 负责人的队列只取已经提交的访问申请，店长草稿和口径治理事项有各自的入口。
      ApprovalPage.render(approvalsPage, {
        desk: { list: () => desk.list().filter(item => item.type === 'access' && item.status !== 'draft') },
        selectedId: selectedId || null,
        onSelect: id => { location.hash = id ? 'approvals/' + encodeURIComponent(id) : 'approvals'; },
        onReturn: (openReceipts = false) => {
          returnAction = openReceipts ? 'receipts' : null;
          location.hash = 'workbench';
        },
        onStartExample: () => {
          returnAction = 'profit';
          location.hash = 'workbench';
        },
        onDecision: decideAccess
      });
      if (changed) approvalsPage.focus({ preventScroll: true });
    } else if (changed) {
      approvalsPage.replaceChildren();
      refreshAccess(active);
      drawReceipts();
      document.title = active.title + ' · DataOcean 问数工作台';
      receiptButton.focus({ preventScroll: true });
      if (returnAction === 'receipts') receiptDialog.showModal();
      if (returnAction === 'profit') {
        newChat();
        submitMessage(profitExample);
      }
      returnAction = null;
    }
  }

  function drawReceipts() {
    // 同一事项的一次完成只通知店长一次，记录内的每个原问都可以单独回看。
    const items = desk.list();
    const records = items.filter(item => item.receipt).sort((a, b) => b.receipt.completedAt - a.receipt.completedAt);
    clearTimeout(expiryTimer);
    const expiries = items.filter(item => item.status === 'effective').map(item => item.expiresAt);
    if (expiries.length) {
      const delay = Math.min(...expiries) - Date.now();
      expiryTimer = setTimeout(() => {
        refreshAccess(active);
        drawReceipts();
        if (!approvalsPage.hidden) showPerspective();
      }, Math.min(2147483647, Math.max(1, delay)));
    }
    const unread = records.filter(item => !readReceipts.has(item.receipt.key)).length;
    const count = document.getElementById('receipt-count');
    count.textContent = String(unread);
    count.hidden = unread === 0;
    receiptButton.setAttribute('aria-label', unread ? '处理回告，' + unread + ' 条未读' : '处理回告');

    const list = document.getElementById('receipt-list');
    list.replaceChildren();
    if (!records.length) list.append(element('p', 'receipt-empty', '还没有处理回告。申请或口径事项处理完成后，可以在这里查看。'));
    for (const item of records) {
      const row = element('article', 'receipt-item');
      if (!readReceipts.has(item.receipt.key)) row.classList.add('unread');
      const meta = element('div', 'receipt-topic', item.title);
      const completed = new Date(item.receipt.completedAt);
      const time = element('time', '', completed.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }));
      time.dateTime = completed.toISOString();
      meta.append(time);
      row.append(meta, element('h3', '', item.receipt.title), element('p', '', item.receipt.text));
      item.links.forEach((link, i) => {
        const button = element('button', 'receipt-link', item.links.length === 1 ? '查看原问题' : '查看提问 ' + (i + 1) + ' · ' + link.text);
        button.type = 'button';
        button.append(WorkbenchIcon('chevron-right', 13));
        button.addEventListener('click', () => {
          const thread = threads.find(t => t.id === link.threadId);
          const turn = thread?.flow.querySelector('#' + link.turnId);
          if (!turn) return;
          readReceipts.add(item.receipt.key);
          receiptDialog.close();
          selectThread(thread);
          turn.tabIndex = -1;
          turn.focus({ preventScroll: true });
          turn.scrollIntoView({ block: 'center' });
          drawReceipts();
          save();
        });
        row.append(button);
      });
      list.append(row);
    }
  }

  receiptButton.addEventListener('click', () => { drawReceipts(); receiptDialog.showModal(); });
  receiptDialog.querySelector('.receipt-close').addEventListener('click', () => receiptDialog.close());
  document.getElementById('reset-demo').addEventListener('click', () => {
    if (!window.confirm('清除这个标签页的示例对话、申请和回告，重新开始演示？')) return;
    window.removeEventListener('pagehide', save);
    try { sessionStorage.removeItem(storageKey); } catch { /* 未启用存储时，刷新即重置。 */ }
    location.reload();
  });
  // 查看、展开或导出已交付的受限结果之前再次检查，过期时立即收起这条回答。
  transcript.addEventListener('click', event => {
    const turn = event.target.closest('.assistant-turn');
    const reply = turn && active?.turns.find(entry => entry.id === turn.id)?.reply;
    if (reply?.result?.type !== 'business' || desk.canView(reply.result)) return;
    event.preventDefault();
    event.stopPropagation();
    hideRestricted(turn);
  }, true);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && active) { refreshAccess(active); drawReceipts(); }
  });

  function appendReply(thread, entry) {
    const { reply } = entry;
    const turn = element('article', 'assistant-turn');
    turn.id = entry.id;
    turn.setAttribute('aria-label', 'DataOcean 回答');
    if (reply.kind === 'case' || (reply.kind === 'answer' && reply.result)) {
      const author = element('div', 'who');
      author.append(mark(), document.createTextNode('DataOcean'));
      turn.append(author);
      if (reply.kind === 'case') renderCase(thread, entry, turn);
      else if (reply.result.type !== 'business' || desk.canView(reply.result)) turn.append(QueryAnswers.render(reply));
      else hideRestricted(turn);
    } else {
      turn.classList.add('compact');
      const line = element('div', 'prompt-line');
      line.append(mark(), element('span', '', reply.text));
      turn.append(line);
    }
    // 插问解释或依据时，当前请求仍可继续；把仍有效的选项放到最新回复下方。
    if (reply.choices?.length || !thread.state?.waitingUntil) {
      thread.choices?.remove();
      thread.choices = null;
    }
    if (reply.choices?.length) {
      const choices = element('div', 'choices');
      for (const label of reply.choices) {
        const button = element('button', 'choice', label);
        button.type = 'button';
        button.addEventListener('click', () => submitMessage(label));
        choices.append(button);
      }
      thread.choices = choices;
    }
    if (thread.choices) turn.append(thread.choices);
    thread.flow.append(turn);
  }

  function addTurn(thread, text) {
    const id = 'turn-' + ++serial;
    const caseReply = desk.ask(text, { threadId: thread.id, turnId: id });
    if (caseReply?.caseId) syncCase(caseReply.caseId);
    const { state, reply } = caseReply ? { state: null, reply: caseReply } : QueryDemo.next(thread.state, text);
    const entry = { id, text, state, reply };
    thread.flow.querySelector('.empty')?.remove();
    thread.flow.append(element('div', 'bubble', text));
    thread.state = state;
    thread.turns.push(entry);
    appendReply(thread, entry);
    return reply;
  }

  function submitMessage(raw) {
    const text = raw.trim();
    if (!text || !active) return;
    if (!active.turns.length && active.title === '新问数') {
      active.title = text.length > 20 ? text.slice(0, 20) + '…' : text;
    }
    const reply = addTurn(active, text);
    document.getElementById('thread-title').textContent = active.title;
    document.title = active.title + ' · DataOcean 问数工作台';
    input.value = '';
    syncInput();
    drawHistory();
    transcript.scrollTop = transcript.scrollHeight;
    input.focus({preventScroll: true});
    status.textContent = reply.result?.title || reply.text || desk.get(reply.caseId)?.summary || '';
    drawReceipts();
    save();
  }

  composer.addEventListener('submit', event => {
    event.preventDefault();
    submitMessage(input.value);
  });
  input.addEventListener('input', syncInput);
  // 拼音输入法用 Enter 选字时不发送，Shift+Enter 保留换行。
  input.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
      event.preventDefault();
      composer.requestSubmit();
    }
  });
  function newChat() {
    filter.value = '';
    filter.hidden = true;
    findButton.setAttribute('aria-expanded', 'false');
    let thread = threads.find(item => !item.turns.length && item.title === '新问数');
    if (!thread) {
      thread = createThread();
      drawEmpty(thread);
    }
    selectThread(thread, true);
    save();
  }
  document.getElementById('new-chat').addEventListener('click', newChat);
  findButton.addEventListener('click', () => {
    filter.hidden = !filter.hidden;
    findButton.setAttribute('aria-expanded', String(!filter.hidden));
    if (!filter.hidden) filter.focus();
    drawHistory();
  });
  filter.addEventListener('input', drawHistory);
  menuButton.addEventListener('click', () => setMenu(true));
  function closeDrawer() {
    setMenu(false);
    menuButton.focus();
  }
  sidebar.querySelector('.sidebar-close').addEventListener('click', closeDrawer);
  scrim.addEventListener('click', closeDrawer);
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && document.body.classList.contains('sidebar-open')) closeDrawer();
  });
  mobile.addEventListener('change', () => setMenu(false));

  // 用同一套对话规则构造选稿中的示例；新建问数可从头体验同一个追问。
  if (saved?.threads?.length) {
    for (const data of [...saved.threads].reverse()) {
      const thread = createThread(data.title, data.group, data.id);
      thread.turns = data.turns;
      thread.draft = data.draft || '';
      if (!thread.turns.length) drawEmpty(thread);
      for (const entry of thread.turns) {
        thread.flow.append(element('div', 'bubble', entry.text));
        thread.state = entry.state;
        appendReply(thread, entry);
      }
    }
    selectThread(threads.find(t => t.id === saved.activeId) || threads[0]);
  } else {
    const stock = createThread('今日牛奶库存', '示例对话');
    addTurn(stock, '今天还有多少牛奶库存？');
    const returns = createThread('上周牛奶退货', '示例对话');
    addTurn(returns, '上周牛奶一共退了多少盒？');
    const first = createThread('牛奶净销量');
    addTurn(first, '本店上周牛奶卖了多少盒？');
    addTurn(first, '扣掉，顺便按天列。');
    selectThread(first);
  }
  drawReceipts();
  showPerspective();
  window.addEventListener('hashchange', showPerspective);
  window.addEventListener('pagehide', save);
})();
