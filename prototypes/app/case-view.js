/* 每张事项卡跟随原问题。店长的操作和原型用的负责人演示各有入口。 */
(() => {
  'use strict';

  let sequence = 0;
  const simulation = {
    pending: [['approve', '匹配范围并通过审批'], ['reject', '驳回申请']],
    approved: [['activate', '执行授权并生效'], ['activate-fail', '执行校验失败']],
    processing: [['publish', '候选回归通过并发布'], ['explain', '无需修改，回告核查结论']],
    published: [['verify', '复验通过并回告'], ['fail', '复验未过，退回处理']]
  };

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
  }

  function button(label, key, primary = false) {
    const node = el('button', 'case-action' + (primary ? ' case-primary' : ''), label);
    node.type = 'button';
    node.dataset.caseControl = key;
    return node;
  }

  function disclosure(className, label, previous) {
    const details = el('details', className);
    const summary = el('summary', 'case-disclosure');
    summary.dataset.caseControl = className;
    summary.append(WorkbenchIcon('chevron-right', 14), document.createTextNode(label));
    details.append(summary);
    details.open = Boolean(previous?.querySelector('.' + className)?.open);
    return details;
  }

  function field(label, control) {
    const wrapper = el('label', 'case-field', label);
    wrapper.append(control);
    return wrapper;
  }

  function entryForm(item, apply, onAction, previous) {
    const action = apply ? 'apply' : 'supplement';
    const wrap = el('div', 'case-entry');
    const toggle = button(apply ? '申请访问' : '补充情况', 'open-' + action, apply);
    const form = el('form', 'case-form');
    const previousForm = previous?.querySelector('form[data-case-form="' + action + '"]');
    form.dataset.caseForm = action;
    form.id = 'case-form-' + (++sequence);
    toggle.setAttribute('aria-controls', form.id);
    const text = el('textarea');
    text.name = apply ? 'purpose' : 'note';
    text.dataset.caseControl = text.name;
    text.rows = 2;
    text.maxLength = 500;
    text.required = true;
    text.value = previousForm?.querySelector('textarea')?.value || '';
    text.placeholder = apply ? '例如，用于核对上周门店经营周报' : '例如，希望明确跨周退货计入哪一周';
    form.append(field(apply ? '业务用途' : '补充给处理人的情况', text));

    let scope, term;
    if (apply) {
      const pair = el('div', 'case-field-pair');
      scope = el('input');
      scope.value = item.scope || '城南店经营复盘';
      scope.readOnly = true;
      term = el('select');
      term.name = 'term';
      term.dataset.caseControl = 'term';
      for (const days of ['7 天', '14 天', '30 天']) term.add(new Option(days, days));
      term.value = previousForm?.querySelector('select')?.value || item.term || '7 天';
      pair.append(field('申请范围', scope), field('期望使用期限', term));
      form.append(pair);
    }

    const error = el('span', 'case-error');
    error.id = form.id + '-error';
    error.setAttribute('aria-live', 'polite');
    text.setAttribute('aria-describedby', error.id);
    function validate() {
      const valid = Boolean(text.value.trim());
      text.setAttribute('aria-invalid', String(!valid));
      error.textContent = valid ? '' : (apply ? '请填写这次查数的业务用途。' : '请填写要补充的情况。');
      return valid;
    }
    text.addEventListener('input', () => {
      if (text.getAttribute('aria-invalid') === 'true') validate();
    });
    text.addEventListener('blur', () => { if (text.value) validate(); });
    form.noValidate = true;
    const controls = el('div', 'case-form-buttons');
    const cancel = button('取消', 'cancel-' + action);
    const submit = button(apply ? '提交申请' : '提交补充', 'send-' + action, true);
    submit.type = 'submit';
    controls.append(cancel, submit);
    form.append(error, controls);
    function expand(open) {
      form.hidden = !open;
      toggle.hidden = open;
      toggle.setAttribute('aria-expanded', String(open));
    }
    toggle.addEventListener('click', () => { expand(true); text.focus(); });
    cancel.addEventListener('click', () => { expand(false); toggle.focus(); });
    form.addEventListener('submit', event => {
      event.preventDefault();
      if (!validate()) return text.focus();
      const value = text.value.trim();
      text.value = '';
      expand(false);
      const result = onAction(action, apply ? { purpose: value, scope: scope.value, term: term.value } : value);
      if (result?.ok === false) {
        expand(true);
        text.value = value;
        text.focus();
      }
    });
    expand(Boolean(previousForm && !previousForm.hidden));
    wrap.append(toggle);
    if (!apply && item.notes?.length) wrap.append(el('span', 'case-note-count', '已补充 ' + item.notes.length + ' 条'));
    wrap.append(form);
    return wrap;
  }

  function records(item, previous) {
    const details = disclosure('case-records', '查看处理记录', previous);
    if (item.purpose) {
      const description = el('dl', 'case-description');
      for (const [label, value] of [['业务用途', item.purpose], ['申请范围', item.scope], ['期望使用期限', item.term]]) {
        description.append(el('dt', '', label), el('dd', '', value));
      }
      details.append(description);
    }
    if (item.events?.length) {
      const events = el('ol', 'case-events');
      events.setAttribute('aria-label', '处理进度');
      for (const event of item.events) events.append(el('li', '', event.text));
      details.append(events);
    }
    if (item.notes?.length) {
      const notes = el('div', 'case-notes');
      notes.append(el('strong', '', '我的补充'));
      for (const note of item.notes) notes.append(el('p', '', note.text));
      details.append(notes);
    }
    return details;
  }

  function render(item, dispatch, previous) {
    const isAccess = item.type === 'access';
    const draft = item.status === 'draft';
    const card = el('section', 'case-card');
    card.dataset.caseId = item.id;
    card.dataset.caseStatus = item.status;
    card.setAttribute('aria-label', isAccess ? '访问申请' : '口径治理事项');
    const main = el('div', 'case-body');
    const actionError = el('p', 'case-error');
    actionError.setAttribute('aria-live', 'polite');
    const onAction = (key, value) => {
      const result = dispatch(key, value);
      actionError.textContent = result?.ok === false ? result.message || '操作未完成，请查看最新状态。' : '';
      return result;
    };
    const header = el('div', 'case-heading');
    const symbol = el('span', 'case-symbol');
    symbol.append(WorkbenchIcon(isAccess ? 'store' : 'layers', 18));
    const title = el('h2', 'case-title', item.title);
    title.tabIndex = -1;
    header.append(symbol, title, el('span', 'case-status', item.statusLabel));
    const summary = item.status === 'expired' ? item.summary : item.receipt?.text || item.summary;
    main.append(header, el('p', 'case-summary', summary));

    if (!draft) {
      const linked = item.links?.length || 1;
      const reference = isAccess ? '申请 ' + item.id :
        '已自动关联事项 ' + item.id + (linked > 1 ? '（' + linked + ' 次提问）' : '');
      main.append(el('p', 'case-owner', reference + ' · ' + item.owner));
    }
    if (draft || (!isAccess && ['processing', 'published'].includes(item.status))) {
      main.append(entryForm(item, draft, onAction, previous));
    } else if (item.receipt?.canRetry) {
      const retry = button('重新查询原问题', 'retry', true);
      retry.addEventListener('click', () => onAction('retry'));
      main.append(retry);
    } else if (isAccess && ['rejected', 'executionFailed', 'expired'].includes(item.status)) {
      const reapply = button('重新申请', 'reapply');
      reapply.addEventListener('click', () => onAction('reapply'));
      main.append(reapply);
    }
    main.append(actionError);
    card.append(main);

    if (!draft && (item.purpose || item.notes?.length || item.events?.length)) {
      card.append(records(item, previous));
    }
    const transitions = simulation[item.status];
    if (transitions) {
      const sim = disclosure('case-sim', '模拟负责人处理', previous);
      const buttons = el('div', 'case-sim-buttons');
      for (const [key, label] of transitions) {
        const action = button(label, key);
        action.addEventListener('click', () => onAction('simulate', key));
        buttons.append(action);
      }
      sim.append(buttons);
      card.append(sim);
    }

    // 跨会话的同一事项会同步刷新，仅在用户正操作本卡时恢复焦点。
    const focused = previous?.contains(document.activeElement) && document.activeElement.dataset.caseControl;
    if (focused) queueMicrotask(() => {
      if (!card.isConnected) return;
      const next = Array.from(card.querySelectorAll('[data-case-control]')).find(node =>
        node.dataset.caseControl === focused && node.getClientRects().length);
      (next || title).focus({ preventScroll: true });
    });
    return card;
  }

  window.CaseView = { render };
})();
