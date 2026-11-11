/* 中文输出限制规则原型：只维护页面示例，不解析规则或执行授权。 */
(() => {
  'use strict';
  const { outputRules } = PeopleDemo;
  const query = { keyword: '', content: '', page: 1, pageSize: 10 };
  function render(main, ui) {
    const { el, button, textCell, modal, pair, pageHeading, openForm } = ui;
    function edit(rule, trigger) {
      openForm({ title: rule ? '编辑输出限制规则' : '新增输出限制规则', trigger,
        value: rule ? { ...rule } : { name: '', content: '' },
        fields: [{ key: 'name', label: '规则名称', required: true, full: true },
          { key: 'content', label: '返回限制内容', type: 'textarea', required: true, full: true, placeholder: '例如：不允许返回用户的身份证号码、电话号码。' }],
        validate: data => outputRules.some(item => item.id !== rule?.id && item.name === data.name.trim()) ? '规则名称已存在。' : undefined,
        // 这里只更新规则列表，不模拟共享规则修改对已有授权的生效策略。
        save: data => {
          const value = { name: data.name.trim(), content: data.content.trim(), updated: new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16) };
          if (rule) Object.assign(rule, value);
          else { outputRules.unshift({ id: 'rule-' + Date.now(), ...value }); query.keyword = ''; query.content = ''; query.page = 1; }
        }
      });
    }
    function detail(rule, trigger) {
      const node = modal('输出限制规则详情', trigger, true), body = el('div', 'dialog-body'), fields = el('dl', 'detail-fields');
      node.classList.add('access-detail-dialog');
      fields.append(pair('规则名称', rule.name), pair('更新时间', rule.updated));
      body.append(fields, el('h3', '', '返回限制内容'), el('p', 'rule-content', rule.content));
      const footer = el('footer', 'dialog-footer');
      footer.append(button('关闭', () => node.close()), button('编辑规则', () => { node.close(); edit(rule, trigger); }, 'admin-button is-primary'));
      node.append(body, footer); node.showModal();
    }
    const tools = el('section', 'people-query-card');
    tools.append(RecordControls.filters({ value: query, includeDates: false, includeStatus: false,
      fields: [{ key: 'keyword', label: '规则名称' }, { key: 'content', label: '返回限制内容' }],
      onApply: next => { Object.assign(query, next); render(main, ui); },
      onReset: () => { Object.assign(query, { keyword: '', content: '', page: 1 }); render(main, ui); }
    }), button('新增规则', trigger => edit(null, trigger), 'admin-button is-primary', 'new-output-rule'));
    const records = outputRules.filter(rule => AdminQuery.matchesFields({ keyword: rule.name, content: rule.content }, query));
    query.page = Math.max(1, Math.min(query.page, Math.ceil(records.length / query.pageSize)));
    const empty = el('div', 'empty-state'); empty.append(WorkbenchIcon('search'), el('p', '', query.keyword || query.content ? '没有匹配的输出限制规则' : '暂无输出限制规则'));
    if (query.keyword || query.content) empty.append(button('清空筛选', () => { query.keyword = ''; query.content = ''; query.page = 1; render(main, ui); }));
    const panel = el('section', 'record-panel');
    panel.append(RecordTable.render({ caption: '输出限制规则', minWidth: 800,
      columns: [{ key: 'name', label: '规则名称', width: 200 }, { key: 'content', label: '返回限制内容',width:520,grow:4 }, { key: 'updated', label: '更新时间', width: 166 }, { key: 'actions', label: '操作', width: 112 }],
      items: records.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), empty,
      row: rule => {
        const content = el('span', 'rule-content-preview', rule.content); content.title = rule.content;
        const editButton = button('编辑', trigger => edit(rule, trigger), 'do-action is-primary', 'edit-rule-' + rule.id); editButton.setAttribute('aria-label', '编辑' + rule.name);
        return [button(rule.name, trigger => detail(rule, trigger), 'cell-title', 'rule-' + rule.id), content, textCell(rule.updated), editButton];
      }
    }), RecordControls.pager({ total: records.length, ...query }, page => { query.page = page; render(main, ui); }, size => { query.pageSize = size; query.page = 1; render(main, ui); }));
    main.replaceChildren(pageHeading('输出限制规则'), tools, panel);
  }
  window.OutputRulesPage = { render };
})();
