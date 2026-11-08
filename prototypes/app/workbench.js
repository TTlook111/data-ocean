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
  const threads = [];
  let active;

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
  function createThread(title = '新问数', group = '今天') {
    const thread = { title, group, state: null, flow: element('div', 'flow'), draft: '', choices: null, position: 0 };
    threads.unshift(thread);
    return thread;
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
      button.addEventListener('click', () => selectThread(thread));
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

  function appendReply(thread, reply) {
    const turn = element('article', 'assistant-turn');
    turn.setAttribute('aria-label', 'DataOcean 回答');
    if (reply.kind === 'answer' && reply.result) {
      const author = element('div', 'who');
      author.append(mark(), document.createTextNode('DataOcean'));
      turn.append(author, QueryAnswers.render(reply));
    } else {
      turn.classList.add('compact');
      const line = element('div', 'prompt-line');
      line.append(mark(), element('span', '', reply.text));
      turn.append(line);
    }
    if (reply.choices?.length) {
      const choices = element('div', 'choices');
      for (const label of reply.choices) {
        const button = element('button', 'choice', label);
        button.type = 'button';
        button.addEventListener('click', () => submitMessage(label));
        choices.append(button);
      }
      turn.append(choices);
      thread.choices = choices;
    }
    thread.flow.append(turn);
  }

  function addTurn(thread, text) {
    const {state, reply} = QueryDemo.next(thread.state, text);
    thread.flow.querySelector('.empty')?.remove();
    // 旧追问保留原文。选项只针对当前这一步，避免点击旧答案污染当前意图。
    thread.choices?.remove();
    thread.choices = null;
    thread.flow.append(element('div', 'bubble', text));
    thread.state = state;
    appendReply(thread, reply);
    return reply;
  }

  function submitMessage(raw) {
    const text = raw.trim();
    if (!text || !active) return;
    if (!active.state && active.title === '新问数') {
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
    status.textContent = reply.result?.title || reply.text;
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
  document.getElementById('new-chat').addEventListener('click', () => {
    filter.value = '';
    filter.hidden = true;
    findButton.setAttribute('aria-expanded', 'false');
    let thread = threads.find(item => !item.state && item.title === '新问数');
    if (!thread) {
      thread = createThread();
      drawEmpty(thread);
    }
    selectThread(thread, true);
  });
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
  const stock = createThread('今日牛奶库存', '示例对话');
  addTurn(stock, '今天还有多少牛奶库存？');
  const returns = createThread('上周牛奶退货', '示例对话');
  addTurn(returns, '上周牛奶一共退了多少盒？');
  const first = createThread('牛奶净销量');
  addTurn(first, '本店上周牛奶卖了多少盒？');
  addTurn(first, '扣掉，顺便按天列。');
  selectThread(first);
})();
