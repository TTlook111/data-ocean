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
  const receiptDialog = document.getElementById('receipts');
  const receiptButton = document.getElementById('open-receipts');
  // 与后台共用同一份人员及数据源示例，身份不再绑定门店记录范围。
  const person = PeopleDemo.users.find(user => user.id === 'u01');
  const account = { id: person.id, name: person.name, active: person.status === '正常',
    departments: [person.department, ...person.extra].map(id => PeopleDemo.departments.find(department => department.id === id).name) };
  const assigned = [...PeopleDemo.departments.filter(department => [person.department, ...person.extra].includes(department.id)).flatMap(department => department.access),
    ...PeopleDemo.extraRequests.filter(request => request.user === person.id && request.status === '已生效').flatMap(request => request.access)];
  const sourceOptions = PeopleDemo.dataSources.filter(source => assigned.some(item => item.source === source.id));
  const defaultSource = sourceOptions.some(source => source.id === 'retail') ? 'retail' : sourceOptions[0]?.id;
  const sourceName = id => sourceOptions.find(source => source.id === id)?.name || '数据源不可用';
  const sourceState = Vue.reactive({ value: defaultSource });
  PrototypeUI.mount(document.getElementById('data-source-picker'), {
    setup: () => () => Vue.h(ElementPlus.ElSelect, {
      modelValue: sourceState.value, 'aria-label': '本次问数数据源',
      'onUpdate:modelValue': value => switchSource(value), popperClass: 'prototype-ui-popper',
    }, { default: () => sourceOptions.map(source => Vue.h(ElementPlus.ElOption, { key: source.id, value: source.id, label: source.name })) })
  });
  const profileMenu = sidebar.querySelector('.profile-more');
  const profileToggle = profileMenu.querySelector('summary');
  // 评审操作单独启用，默认产品视图只呈现当前业务流程。
  const prototypeReview = new URLSearchParams(location.search).get('review') === '1';
  document.documentElement.dataset.prototypeReview = String(prototypeReview);
  document.getElementById('prototype-tools').hidden = !prototypeReview;
  document.querySelector('.sample').hidden = false;
  const reviewState = Vue.reactive({ scenario: 'normal' });
  const revokedSources = new Set();
  let canSql = true;
  const timers = new Map();
  const allowedSource = source => account.active && sourceOptions.some(item => item.id === source) && !revokedSources.has(source);
  const scenarios = [['normal', '正常查询'], ['output-repair', '毛利输出校验后重新生成'], ['explanation', '解释生成失败'], ['connection', '连接失败'], ['unknown', '执行状态未知'], ['permission', '执行前撤权'], ['budget', '资源或预算超限'], ['empty', '空记录'], ['zero', '零值'], ['null', 'NULL 未知值'], ['truncated', '结果截断']];
  PrototypeUI.mount(document.getElementById('query-review-controls'), {
    setup: () => () => Vue.h(ElementPlus.ElSelect, { modelValue: reviewState.scenario, 'aria-label': '下一次查询样例',
      'onUpdate:modelValue': value => { reviewState.scenario = value; } }, { default: () => scenarios.map(([value, label]) => Vue.h(ElementPlus.ElOption, { value, label, key: value })) })
  });
  function updateAccountMenu() {
    profileToggle.setAttribute('aria-label', account.name + '，' + account.departments.join('、') + '，用户菜单');
    profileMenu.querySelector('.face').textContent = account.name.slice(0, 1);
    profileMenu.querySelector('strong').textContent = account.name;
    profileMenu.querySelector('small').textContent = account.departments.join('、');
    const admin = document.getElementById('enter-admin');
    admin.hidden = !account.active;
    admin.href = '../后台管理/index.html#dashboard';
  }
  updateAccountMenu();

  // 原生 summary 随 details 暴露展开状态，避免额外 aria 状态滞后。
  profileMenu.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      profileMenu.open = false;
      profileToggle.focus({ preventScroll: true });
    }
  });
  profileMenu.addEventListener('focusout', event => {
    if (event.relatedTarget && !profileMenu.contains(event.relatedTarget)) profileMenu.open = false;
  });
  profileMenu.querySelectorAll('a').forEach(link => link.addEventListener('click', () => {
    profileMenu.open = false;
    if (link.getAttribute('aria-current') === 'page') profileToggle.focus({ preventScroll: true });
  }));
  document.addEventListener('pointerdown', event => {
    if (!profileMenu.contains(event.target)) profileMenu.open = false;
  });
  // 仅保存当前标签页的离线演示，刷新时恢复快照，不重放提问、审批或发布。
  const storageKey = 'dataocean.workbench.' + account.id + '.v4';
  const casesKey = 'dataocean.prototype.cases.v3';
  let saved;
  try { saved = JSON.parse(sessionStorage.getItem(storageKey)); } catch { /* 浏览器可禁用本地存储。 */ }
  let sharedCases;
  try { sharedCases = JSON.parse(sessionStorage.getItem(casesKey)); } catch { /* 无共享记录时从当前会话开始。 */ }
  const desk = QueryCases.create(sharedCases || saved?.cases, Date.now, account);
  const readReceipts = new Set(saved?.readReceipts);
  const threads = [];
  let active;
  let serial = saved?.serial || 0;
  let receiptVersion;

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
  function createThread(title = '新问数', group = '今天', id = 'chat-' + ++serial, sourceId = defaultSource) {
    const thread = { id, title, group, sourceId, turns: [], state: null, flow: element('div', 'flow'), draft: '', choices: null, position: 0 };
    threads.unshift(thread);
    return thread;
  }

  function save() {
    const snapshot = {
      serial, activeId: active?.id, readReceipts: [...readReceipts], cases: desk.snapshot(),
      threads: threads.map(({ id, title, group, sourceId, turns, draft }) => ({ id, title, group, sourceId, turns, draft }))
    };
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(snapshot));
      sessionStorage.setItem(casesKey, JSON.stringify(desk.snapshot()));
    } catch { /* 当前页仍可完整试走。 */ }
  }

  function syncInput() {
    sendButton.disabled = !input.value.trim() || active?.turns.at(-1)?.request?.status === '运行中';
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 110) + 'px';
    if (active) active.draft = input.value;
  }

  function drawHistory() {
    history.replaceChildren();
    const needle = filter.hidden ? '' : filter.value.trim().toLowerCase();
    let lastGroup;
    for (const thread of threads.filter(item => item.title.toLowerCase().includes(needle))) {
      if (thread.group !== lastGroup) {
        history.append(element('p', 'section-label', !prototypeReview && thread.group === '示例对话' ? '最近' : thread.group));
        lastGroup = thread.group;
      }
      const button = element('button', 'history', thread.title);
      button.type = 'button';
      button.title = thread.title;
      button.append(element('small', 'history-source', sourceName(thread.sourceId)));
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
    if (active && active !== thread) markHistorical(active);
    active = thread;
    sourceState.value = thread.sourceId;
    input.placeholder = "向" + sourceName(thread.sourceId) + "提问，也可以补充或修改条件…";
    refreshAccess(thread);
    transcript.replaceChildren(thread.flow);
    transcript.scrollTop = thread.position;
    document.getElementById('thread-title').textContent = thread.title;
    document.title = thread.title + ' · DataOcean 问数工作台';
    input.value = thread.draft;
    syncInput();
    drawHistory();
    drawActivity();
    if (focusInput) input.focus();
  }

  function markHistorical(thread) {
    thread.historical = true;
    for (const entry of thread.turns) {
      entry.historical = true;
      const answer = thread.flow.querySelector('#' + entry.id + ' .qa-answer');
      if (answer && !answer.querySelector('.qa-history')) answer.prepend(element('p', 'qa-history', '历史结果 · ' + (entry.reply.result.queryId || '固定样例')));
    }
  }

  function drawEmpty(thread) {
    const empty = element('div', 'empty');
    empty.append(mark(), element('h1', '', '想了解哪些数据？'));
    const prompts = element('div', 'prompts');
    for (const [name, text] of thread.sourceId === 'retail' ? [
      ['milk', '城南店上周牛奶卖了多少盒？'],
      ['warehouse', '今天还有多少牛奶库存？'],
      ['store', '城南店上周的毛利额是多少？'],
      ['layers', '城南店上周牛奶的退货率是多少？'],
    ] : thread.sourceId === 'products' ? [['store', '商品101的原销售数量是多少？']] : []) {
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
    turn.querySelector('.feedback-case')?.remove();
    if (!turn.querySelector('.access-ended')) turn.append(element('p', 'access-ended', '这条回答当前不可用，请核对访问范围、知识状态或结果保留期限。'));
  }

  function refreshAccess(thread) {
    for (const entry of thread.turns) {
      const { id, reply } = entry;
      if (reply.kind !== 'case' && !reply.result) continue;
      const turn = thread.flow.querySelector('#' + id);
      if (!turn) continue;
      if (!allowedSource(thread.sourceId)) { hideRestricted(turn); turn.querySelector('.case-card')?.remove(); continue; }
      if (reply.kind === 'case') {
        if (turn.querySelector('.case-card')?.dataset.caseStatus !== desk.get(reply.caseId).status) renderCase(thread, entry, turn);
      } else if (!canView(thread, entry)) {
        hideRestricted(turn);
      } else {
        const message = turn.querySelector('.access-ended');
        if (message) message.replaceWith(renderAnswer(thread, entry));
      }
    }
  }

  function canView(thread, entry) {
    if (!allowedSource(thread.sourceId) || !desk.canView(entry.reply.result)) return false;
    if (entry.reply.result?.retainedUntil <= Date.now()) return false;
    if (entry.reply.result?.example === 'published-products') {
      const current = QueryKnowledgeBridge.read(thread.sourceId);
      return Boolean(current?.documents?.length && !current.error && entry.reply.result.knowledge.every(ref => current.documents.some(doc => doc.id === ref.id)));
    }
    return true;
  }

  function requery(thread, entry) {
    if (active !== thread) selectThread(thread);
    const draft = input.value;
    const state = entry.state;
    let question = entry.queryText || entry.text;
    if (state?.topic === 'sales') question = '城南店' + state.period + (state.weekdays?.length > 1 ? state.weekdays.map(day => '周' + '日一二三四五六'[day]).join('和') : '') + state.products.join('和')
      + ({ net: '净销量', gross: '售出盒数', returns: '退货盒数' }[state.metric] || '销量') + (state.group === 'day' ? '，按天列' : '，只看合计');
    if (state?.topic === 'inventory') question = '城南店今天' + state.products.join('和') + '库存';
    if (state?.topic === 'comparison') question = (state.comparison === 'cause' ? '为什么本周比上周净销量下降' : state.comparison === 'formula' ? '本周牛奶净销量除以上周牛奶净销量' : '本周和上周牛奶净销量比较') + '，' + (state.progress || '按整周比较');
    submitMessage(question, entry.request?.id || entry.id, null);
    if (draft) { input.value = draft; syncInput(); save(); }
  }

  function renderAnswer(thread, entry) {
    return QueryAnswers.render(entry.reply, { question: entry.queryText || entry.text, history: entry.historical,
      related: entry.request?.related, canSql, onRequery: () => requery(thread, entry),
      onReaction: reaction => {
        if (reaction?.type === 'down') {
          const record = desk.register(thread.sourceId, '回答反馈 · ' + (entry.request?.id || entry.id), { threadId: thread.id, turnId: entry.id, text: entry.queryText || entry.text }, 'feedback');
          entry.feedbackCaseId = record.caseId; desk.supplement(record.caseId, reaction.reason);
        }
        if (entry.feedbackCaseId) desk.reaction(entry.feedbackCaseId, reaction);
        drawFeedback(thread, entry); save();
      },
      onExplanation: action => {
        const request = entry.request;
        if (!request) return;
        if (action === 'stop') { request.explanation = '已停止'; entry.reply.explanationStatus = '已停止'; updateEntry(thread, entry); return; }
        const error = QueryFlow.retryExplanation(request, canView(thread, entry));
        if (error) {
          status.textContent = error;
          const answer = thread.flow.querySelector('#' + entry.id + ' .qa-answer');
          answer?.querySelector('.qa-explanation-error')?.remove();
          const message = element('p', 'qa-explanation-error', error); message.setAttribute('role', 'alert'); answer?.append(message); return;
        }
        entry.reply = request.reply; updateEntry(thread, entry);
        setTimeout(() => {
          if (request.explanation !== '生成中') return;
          if (!canView(thread, entry)) { request.explanation = '已停止'; entry.reply.explanationStatus = '已停止'; refreshAccess(thread); }
          else { request.explanation = '完成'; entry.reply.explanationStatus = '完成'; updateEntry(thread, entry); }
          save();
        }, 700);
      }
    });
  }

  function drawFeedback(thread, entry) {
    const turn = thread.flow.querySelector('#' + entry.id);
    if (!turn || !entry.feedbackCaseId || !canView(thread, entry)) return;
    const previous = turn.querySelector('.feedback-case .case-card');
    turn.querySelector('.feedback-case')?.remove();
    const card = element('div', 'feedback-case');
    const record = desk.get(entry.feedbackCaseId);
    card.append(CaseView.render(record, (action, value) => {
      if (action === 'retry') { requery(thread, entry); return { ok: true }; }
      const result = action === 'supplement' ? desk.supplement(record.id, value) : desk.simulate(record.id, value);
      if (result.ok) { drawFeedback(thread, entry); drawReceipts(); save(); }
      return result;
    }, previous));
    turn.append(card);
  }

  function renderCase(thread, entry, turn) {
    const record = { ...desk.get(entry.reply.caseId), originalQuestion: entry.text };
    if (!allowedSource(thread.sourceId)) { hideRestricted(turn); return; }
    const previousCard = turn.querySelector('.case-card');
    turn.querySelector('.access-ended')?.remove();
    const card = CaseView.render(record, (action, value) => {
      if (action === 'retry' || action === 'reapply') {
        // 从卡片所关联的原问创建新一轮，重新核验此刻的访问权限和生效口径。
        if (active !== thread) selectThread(thread);
        const draft = input.value;
        submitMessage(entry.text);
        if (draft) { input.value = draft; syncInput(); save(); }
        return { ok: true };
      }
      const result = action === 'supplement' ? desk.supplement(record.id, value) : desk.simulate(record.id, value);
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

  // 个人管理和后台只保留统一入口，旧的嵌入式审批页面已移除。
  function showPage() {
    const [view, section] = location.hash.slice(1).split('/');
    if (view === 'account') { location.replace('../后台管理/index.html#account-info'); return; }
    if (view === 'admin') { location.replace('../后台管理/index.html#' + (section || 'dashboard')); return; }
    updateAccountMenu();
  }

  function drawReceipts() {
    // 同一事项的一次完成只通知店长一次，记录内的每个原问都可以单独回看。
    const items = desk.list().filter(item => allowedSource(item.sourceId || 'retail'));
    const records = items.filter(item => item.receipt)
      .sort((a, b) => b.receipt.completedAt - a.receipt.completedAt);
    const unread = records.filter(item => !readReceipts.has(item.receipt.key)).length;
    const count = document.getElementById('receipt-count');
    count.textContent = String(unread);
    count.hidden = unread === 0;
    receiptButton.setAttribute('aria-label', unread ? '处理回告，' + unread + ' 条未读' : '处理回告');

    const list = document.getElementById('receipt-list');
    // 页面恢复可见时保留未变化的回告节点，避免用户点击过程中列表被重复替换。
    const version = JSON.stringify(records.map(item => [item.receipt, item.links, readReceipts.has(item.receipt.key)]));
    if (version === receiptVersion) return;
    receiptVersion = version;
    list.replaceChildren();
    if (!records.length) list.append(element('p', 'receipt-empty', '暂无处理回告。'));
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
    if (!window.confirm('清除当前标签页的示例对话、事项和回告，重新开始演示？')) return;
    window.removeEventListener('pagehide', save);
    try {
      for (const key of [storageKey, casesKey]) sessionStorage.removeItem(key);
    } catch { /* 未启用存储时，刷新即重置。 */ }
    location.reload();
  });
  document.getElementById('review-access').addEventListener('click', event => {
    if (revokedSources.has(active.sourceId)) revokedSources.delete(active.sourceId); else revokedSources.add(active.sourceId);
    event.currentTarget.textContent = revokedSources.has(active.sourceId) ? '恢复当前源访问' : '模拟撤回当前源访问';
    threads.forEach(refreshAccess); drawActivity(); drawReceipts();
  });
  document.getElementById('review-sql').addEventListener('click', event => {
    canSql = !canSql; event.currentTarget.textContent = canSql ? '收起 SQL 查看权限' : '恢复 SQL 查看权限';
    threads.forEach(thread => thread.turns.filter(entry => entry.reply.result).forEach(entry => updateEntry(thread, entry)));
  });
  document.getElementById('review-expire').addEventListener('click', () => {
    const entry = active.turns.at(-1);
    if (entry?.request?.status !== '等待补充') { status.textContent = '当前没有等待补充的问数。'; return; }
    entry.state.waitingUntil = Date.now() - 1; entry.request.state.waitingUntil = entry.state.waitingUntil;
    active.state = entry.state; QueryFlow.expire(entry.request); entry.reply = entry.request.reply; updateEntry(active, entry); save();
  });
  document.getElementById('review-retention').addEventListener('click', () => {
    const entry = active.turns.findLast(item => item.reply.result);
    if (!entry) { status.textContent = '当前没有已交付结果。'; return; }
    entry.reply.result.retainedUntil = Date.now() - 1; refreshAccess(active); save();
  });
  // 查看、展开或导出已交付的受限结果之前再次检查，过期时立即收起这条回答。
  transcript.addEventListener('click', event => {
    const turn = event.target.closest('.assistant-turn');
    const entry = turn && active?.turns.find(entry => entry.id === turn.id);
    if (!entry?.reply.result || canView(active, entry)) return;
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
    if (entry.request?.status === '运行中') {
      turn.classList.add('compact'); turn.setAttribute('aria-busy', 'true');
      const line = element('div', 'prompt-line'); line.append(mark(), element('span', 'running-label', QueryFlow.stages[entry.request.stage] + '…'));
      turn.append(line); thread.flow.append(turn); return;
    }
    if (reply.kind === 'case' || (reply.kind === 'answer' && reply.result)) {
      const author = element('div', 'who');
      author.append(mark(), document.createTextNode('DataOcean'));
      turn.append(author);
      if (reply.kind === 'case') renderCase(thread, entry, turn);
      else if (canView(thread, entry)) turn.append(renderAnswer(thread, entry));
      else hideRestricted(turn);
    } else {
      turn.classList.add('compact');
      const line = element('div', 'prompt-line');
      line.append(mark(), element('span', '', reply.text));
      turn.append(line);
      if (entry.request?.status === '技术失败' && entry.request.database !== '状态未知') {
        const retry = element('button', 'choice', '重新查询'); retry.type = 'button'; retry.addEventListener('click', () => requery(thread, entry)); turn.append(retry);
      }
      if (entry.request?.status === '权限不允许') {
        const apply = element('a', 'choice', '申请访问');
        apply.href = '../后台管理/index.html?requestData=' + encodeURIComponent(entry.queryText || entry.text) + '&requestSource=' + thread.sourceId + '#my-requests'; turn.append(apply);
      }
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
    drawFeedback(thread, entry);
  }

  function updateEntry(thread, entry) {
    const followBottom = active === thread && transcript.scrollTop + transcript.clientHeight >= transcript.scrollHeight - 60;
    const old = thread.flow.querySelector('#' + entry.id);
    const nextSibling = old?.nextSibling;
    const focused = old?.contains(document.activeElement);
    old?.remove(); appendReply(thread, entry);
    const node = thread.flow.querySelector('#' + entry.id);
    if (nextSibling) thread.flow.insertBefore(node, nextSibling);
    if (focused) { node.tabIndex = -1; node.focus({ preventScroll: true }); }
    if (thread === active) { drawActivity(); syncInput(); }
    if (followBottom) transcript.scrollTop = transcript.scrollHeight;
  }

  function addTurn(thread, text, { immediate = true, related = '', previous = thread.state } = {}) {
    const id = 'turn-' + ++serial;
    const caseReply = thread.sourceId === 'retail' ? desk.ask(text, { threadId: thread.id, turnId: id })
      : null;
    if (caseReply?.caseId) syncCase(caseReply.caseId);
    const last = thread.turns.at(-1);
    const continuing = last?.request?.status === '等待补充' && previous?.waitingUntil > Date.now() && !/另问|换个问题|算了|库存/.test(text);
    if (last?.request?.status === '等待补充') {
      QueryFlow.expire(last.request);
      if (last.request.status === '等待补充') { last.request.status = '已继续'; last.reply.choices = []; }
      updateEntry(thread, last);
    }
    const request = QueryFlow.open({ id: continuing ? last.request.id : 'QS-' + serial, sourceId: thread.sourceId, text, previous, caseReply,
      context: QueryKnowledgeBridge.read(thread.sourceId), scenario: prototypeReview && !immediate ? reviewState.scenario : 'normal', related: related || (previous?.waitingUntil <= Date.now() ? last?.request?.id || '' : '') });
    if (prototypeReview && !immediate) reviewState.scenario = 'normal';
    if (immediate) while (request.status === '运行中') QueryFlow.advance(request);
    if (request.reply?.knowledgeGap) {
      const record = desk.register(thread.sourceId, '知识缺口 · ' + request.reply.text, { threadId: thread.id, turnId: id, text });
      request.reply = record;
    }
    const state = request.state, reply = request.reply || { kind: 'notice', text: '' };
    const inheritedQuestion = previous?.waitingUntil && last ? (last.queryText || last.text) + '；补充：' + text : text;
    const entry = { id, text, queryText: inheritedQuestion, state, reply, request };
    thread.flow.querySelector('.empty')?.remove();
    thread.flow.append(element('div', 'bubble', text));
    thread.state = state;
    thread.turns.push(entry);
    appendReply(thread, entry);
    if (request.status === '运行中') schedule(thread, entry);
    return reply;
  }

  function schedule(thread, entry) {
    timers.set(entry.id, setTimeout(() => {
      timers.delete(entry.id);
      if (entry.request.status !== '运行中') return;
      if (!allowedSource(thread.sourceId)) {
        entry.request.database = entry.request.executions ? '取消已请求，状态待核查' : '未提交';
        entry.request.status = '权限不允许'; entry.request.reply = { kind: 'notice', text: '当前数据源访问已失效，本次查询已停止交付。' };
      } else QueryFlow.advance(entry.request);
      entry.reply = entry.request.reply || { kind: 'notice', text: '' };
      entry.state = entry.request.state;
      updateEntry(thread, entry);
      if (entry.request.status === '运行中') schedule(thread, entry);
      else {
        if (entry.request.scenario === 'permission' && entry.request.status === '权限不允许') { revokedSources.add(thread.sourceId); threads.forEach(refreshAccess); drawReceipts(); drawActivity(); }
        status.textContent = entry.reply.result?.title || entry.reply.text || ''; save();
      }
    }, 330));
  }

  function drawActivity() {
    const host = document.getElementById('query-activity'); host.replaceChildren();
    if (active && !allowedSource(active.sourceId)) {
      host.hidden = false; host.append(element('span', '', '当前数据源访问已失效。'));
      const apply = element('a', 'choice', '申请访问');
      apply.href = '../后台管理/index.html?requestData=' + encodeURIComponent(input.value || active.turns.at(-1)?.queryText || '') + '&requestSource=' + active.sourceId + '#my-requests';
      host.append(apply); return;
    }
    const entry = active?.turns.at(-1), request = entry?.request;
    const unknown = threads.flatMap(thread => thread.turns.map(entry => ({ thread, entry }))).find(({entry}) => entry.request?.sourceId === active?.sourceId && ['状态未知', '取消已请求，状态待核查'].includes(entry.request?.database));
    host.hidden = !unknown && !['运行中', '等待补充', '等待到期未继续'].includes(request?.status);
    if (host.hidden) return;
    const action = element('button', 'choice'); action.type = 'button';
    if (unknown) {
      host.append(element('span', '', '原查询状态待核查，暂不能重发。')); action.textContent = '核查任务状态';
      action.addEventListener('click', () => { unknown.entry.request.database = '取消已确认'; unknown.entry.reply.text = '原任务已确认取消，未交付结果。现在可以重新查询。'; updateEntry(unknown.thread, unknown.entry); drawActivity(); save(); });
    } else if (request.status === '运行中' || request.status === '等待补充') {
      host.append(element('span', '', request.status === '运行中' ? QueryFlow.stages[request.stage] + '…' : '等待补充' + (request.resume ? ' · ' + request.resume : '')));
      action.textContent = request.status === '运行中' ? '停止查询' : '取消本次问数';
      action.addEventListener('click', () => { clearTimeout(timers.get(entry.id)); timers.delete(entry.id); QueryFlow.cancel(request); entry.reply = request.reply; entry.state = null; request.state = null; active.state = null; updateEntry(active, entry); save(); input.focus(); });
    } else { host.append(element('span', '', '等待已到期，继续答复会建立关联的新请求。')); return; }
    host.append(action);
  }

  function submitMessage(raw, related = '', previous = active?.state) {
    const text = raw.trim();
    if (!text || !active) return;
    if (active.turns.at(-1)?.request?.status === '运行中') { status.textContent = '当前问数仍在运行，可先停止查询。'; return; }
    const unknown = threads.some(thread => thread.sourceId === active.sourceId && thread.turns.some(entry => ['状态未知', '取消已请求，状态待核查'].includes(entry.request?.database)));
    if (unknown) { drawActivity(); status.textContent = '请先核查原任务状态。'; return; }
    if (!allowedSource(active.sourceId)) { status.textContent = '当前数据源访问已失效，请申请访问。'; drawActivity(); return; }
    markHistorical(active);
    if (!active.turns.length && active.title === '新问数') {
      active.title = text.length > 20 ? text.slice(0, 20) + '…' : text;
    }
    const reply = addTurn(active, text, { immediate: false, related, previous });
    document.getElementById('thread-title').textContent = active.title;
    document.title = active.title + ' · DataOcean 问数工作台';
    input.value = '';
    syncInput();
    drawHistory();
    transcript.scrollTop = transcript.scrollHeight;
    input.focus({preventScroll: true});
    status.textContent = reply.result?.title || reply.text || desk.get(reply.caseId)?.summary || '';
    drawReceipts();
    drawActivity();
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
  function switchSource(sourceId) {
    if (!sourceOptions.some(source => source.id === sourceId) || active?.sourceId === sourceId) return;
    // 切源恢复该源最近会话与草稿；首次进入才新建，不拼接其他数据源上下文。
    let thread = threads.find(item => item.sourceId === sourceId);
    if (!thread) { thread = createThread('新问数', '今天', undefined, sourceId); drawEmpty(thread); }
    selectThread(thread, true); save();
  }

  function newChat() {
    filter.value = '';
    filter.hidden = true;
    findButton.setAttribute('aria-expanded', 'false');
    let thread = threads.find(item => !item.turns.length && item.title === '新问数' && item.sourceId === sourceState.value);
    if (!thread) {
      thread = createThread('新问数', '今天', undefined, sourceState.value);
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

  // 用同一套对话规则构造选稿中的示例；新建问数可从头体验同一个追问。
  if (saved?.threads?.length) {
    for (const data of [...saved.threads].reverse()) {
      const thread = createThread(data.title, data.group, data.id, data.sourceId || defaultSource);
      thread.turns = data.turns;
      thread.historical = true;
      thread.draft = data.draft || '';
      if (!thread.turns.length) drawEmpty(thread);
      for (const entry of thread.turns) {
        // 页面恢复不重放尚未确认的执行；已有数据库任务先核查，未提交的请求停止。
        if (entry.request?.status === '运行中') {
          entry.request.status = '技术失败'; entry.request.database = entry.request.executions ? '状态未知' : '未提交';
          entry.reply = { kind: 'notice', text: entry.request.executions ? '页面恢复后需先核查原任务状态。' : '页面恢复，未提交的查询已停止。可重新查询。' };
        }
        if (entry.request?.explanation === '生成中') { entry.request.explanation = '已停止'; entry.reply.explanationStatus = '已停止'; }
        if (entry.request) { entry.request.reply = entry.reply; QueryFlow.expire(entry.request); entry.reply = entry.request.reply; }
        entry.historical = true;
        thread.flow.append(element('div', 'bubble', entry.text));
        thread.state = entry.state;
        appendReply(thread, entry);
      }
    }
    selectThread(threads.find(t => t.id === saved.activeId) || threads[0]);
  } else {
    const stock = createThread('今日牛奶库存', '示例对话');
    addTurn(stock, '城南店今天还有多少牛奶库存？');
    const returns = createThread('上周牛奶退货', '示例对话');
    addTurn(returns, '上周牛奶一共退了多少盒？');
    const first = createThread('牛奶净销量');
    addTurn(first, '城南店上周牛奶卖了多少盒？');
    addTurn(first, '扣掉，顺便按天列。');
    selectThread(first);
  }
  drawReceipts();
  drawActivity();
  setInterval(() => {
    for (const thread of threads) {
      const entry = thread.turns.at(-1);
      if (entry?.request?.status !== '等待补充') continue;
      QueryFlow.expire(entry.request);
      if (entry.request.status === '等待到期未继续') { entry.reply = entry.request.reply; updateEntry(thread, entry); save(); }
    }
  }, 1000);
  showPage();
  window.addEventListener('hashchange', showPage);
  window.addEventListener('pagehide', save);
})();
