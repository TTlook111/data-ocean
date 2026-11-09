/* 业务范围离线原型：复用治理示例生成候选，人工确认后才进入可选范围，不调用模型或执行授权。 */
(() => {
  'use strict';
  const objectMappings = {
    stock: [
      { label: '商品', object: 'products', meaning: '商品编号关联库存，一行对应一种商品及规格。', fields: 'product_id（关联商品编号）、product_name（商品名称）、specification（规格）' },
      { label: '库存', object: 'inventory', meaning: '按部门和商品保存可用库存，记录按获准部门限定。', fields: 'product_id（关联商品编号）、department_id（部门编号）、available_qty（可用库存）' }
    ],
    sales: [{ label: '销售汇总', object: 'sales_summary', meaning: '按业务日期和部门汇总销售数量、销售净额。', fields: 'department_id（部门编号）、business_date（业务日期）、sold_qty（销售数量）、net_sales（销售净额）' }],
    receivables: [{ label: '应收与回款', object: 'receivables_summary', meaning: '按归属部门汇总应收及回款金额。', fields: 'department_id（部门编号）、business_date（业务日期）、receivable_amount（应收金额）、received_amount（回款金额）' }],
    expenses: [{ label: '费用汇总', object: 'expense_summary', meaning: '按部门、月份和类别汇总费用。', fields: 'department_id（部门编号）、business_month（月份）、expense_category（费用类别）、expense_amount（费用金额）' }],
    catalog: [{ label: '商品目录', object: 'product_master', meaning: '依据已确认的上架状态限定商品基础目录。', fields: 'listing_status（上架状态）、product_code（商品编号）、product_name（名称）、category（分类）、specification（规格）' }]
  };
  const plans = {
    retail: [
      { key: 'daily-sales', base: 'sales', name: '门店销售汇总数据', description: '本部门业务日期、销售数量和销售净额，不含成本数据。' },
      { key: 'store-stock', base: 'stock', name: '门店商品与库存数据', description: '本部门商品、规格及可用库存，不含采购成本。' },
      { key: 'sales-quantity', base: 'sales', name: '门店销售数量数据', description: '本部门业务日期与销售数量，不开放销售净额及成本数据。', fields: '业务日期、销售数量，不含销售净额与成本数据' },
      { key: 'returns', base: 'returns', name: '门店退货数据', description: '退货对象、字段含义及统计口径尚未确认。' }
    ],
    finance: [
      { key: 'receipt-check', base: 'receivables', name: '门店应收与回款数据', description: '本部门业务日期、应收与回款金额，不含账户信息。' },
      { key: 'monthly-expenses', base: 'expenses', name: '门店费用汇总数据', description: '本部门月份、费用类别与费用金额。' }
    ],
    products: [{ key: 'product-specs', base: 'catalog', name: '商品基础信息', description: '已上架商品的编号、名称、分类与规格，不含采购成本。' }],
    aftersales: [{ key: 'returns', base: 'returns', name: '售后退货数据', description: '退货记录与退款金额的含义尚未确认。' }]
  };
  // 候选的治理输入独立于已有授权定义：没有预设范围的已治理源也能从零生成候选。
  const governedObjects = {
    retail: {
      stock: { tables: '商品、库存', rows: '门店 / 部门 = {department}', fields: '商品名称、规格、可用库存，不含采购成本' },
      sales: { tables: '销售汇总', rows: '门店 / 部门 = {department}', fields: '业务日期、销售数量、销售净额，不含成本数据' }
    },
    finance: {
      receivables: { tables: '应收回款汇总', rows: '归属部门 = {department}', fields: '业务日期、应收金额、回款金额，不含账户信息' },
      expenses: { tables: '费用汇总', rows: '归属部门 = {department}', fields: '月份、费用类别、费用金额' }
    },
    products: { catalog: { tables: '商品主数据', rows: '已上架商品', fields: '商品编号、名称、分类、规格，不含采购成本' } }
  };

  const checkLabels = ['业务含义与粒度', '字段含义与开放限制', '记录限制', '对象关联'];
  const visibleFields = scope => (scope.fields || '').split('不含')[0].replace(/[，,、\s]+$/, '');
  // 比较数据边界而非名称，避免给同一范围换个名字后重复登记。
  const boundaryKey = scope => JSON.stringify([scope.tables, scope.rows, visibleFields(scope).split('、').sort()]);
  const selectableScopes = source => (source?.scopes || []).filter(scope => scope.state === '已确认');
  function validateAccess(access, sources, required = false) {
    if (!Array.isArray(access) || (required && !access.length)) return '请至少选择一个数据源与业务范围。';
    const used = new Set();
    for (const item of access) {
      const source = sources.find(source => source.id === item.source);
      if (!source || !Array.isArray(item.scopes) || !item.scopes.length) return '请为每项选择数据源和业务范围，或移除未填写的项。';
      if (used.has(item.source) || new Set(item.scopes).size !== item.scopes.length) return '同一数据源或业务范围不能重复选择。';
      used.add(item.source);
      if (item.scopes.some(id => !selectableScopes(source).some(scope => scope.id === id))) return '所选业务范围已不可选，请重新核对。';
    }
    return undefined;
  }
  const today = () => new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' });
  function validateUntil(value, required = false, date = today()) {
    if (!value) return required ? '请选择有效日期。' : undefined;
    const parsed = new Date(value + 'T00:00:00Z');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) return '请输入有效日期。';
    return value < date ? '有效日期不能早于今天。' : undefined;
  }
  function createStore(sources, now = () => new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).slice(0, 16)) {
    // 这是离线治理样例。每个可用对象必须有确认事实、证据引用及四项核对结果，不能由表名推断可信。
    const snapshots = sources.map(source => ({ source: source.id, revision: 'GOV-' + source.id.toUpperCase() + '-01', objects: Object.entries(governedObjects[source.id] || {}).map(([base, definition]) => ({
      base, definition: structuredClone(definition), actor: ({ retail: '陈主管', finance: '刘芳', products: '赵敏' })[source.id], confirmed: '2026-11-08 09:30', evidence: '治理核对记录 / ' + source.id + '-' + base,
      checks: Object.fromEntries(checkLabels.map(label => [label, '已确认']))
    })) }));
    for (const id of ['retail', 'aftersales']) snapshots.find(item => item.source === id)?.objects.push({ base: 'returns', definition: null, actor: '', confirmed: '', evidence: '', checks: Object.fromEntries(checkLabels.map(label => [label, '待确认'])) });
    const records = sources.flatMap(source => selectableScopes(source).map(scope => ({ id: source.id + '-' + scope.id, source: source.id, scope, base: scope.id, state: '已确认', gaps: [], updated: scope.confirmedAt || '', revision: scope.revision, actor: scope.confirmedBy || '', reason: '' })));
    const snapshot = source => snapshots.find(item => item.source === source);
    const issuesFor = object => !object ? ['没有对应治理对象'] : [
      ...checkLabels.filter(label => object.checks?.[label] !== '已确认').map(label => label + '待确认'),
      ...(!object.definition?.tables || !object.definition?.rows || !object.definition?.fields ? ['数据边界未完整确定'] : []),
      ...(!object.actor || !object.confirmed || !object.evidence ? ['缺少治理确认记录'] : [])
    ];
    const assessment = sourceId => {
      const input = snapshot(sourceId);
      return { source: sourceId, revision: input?.revision || '', items: (plans[sourceId] || []).map(plan => {
        const object = input?.objects.find(item => item.base === plan.base), issues = issuesFor(object);
        const scope = object?.definition ? { ...structuredClone(object.definition), ...(plan.fields ? { fields: plan.fields } : {}) } : {};
        const existing = issues.length ? undefined : records.find(record => record.source === sourceId && record.state === '已确认' && boundaryKey(record.scope) === boundaryKey(scope));
        return { ...plan, issues, object, scope, existing };
      }) };
    };
    const recordIssues = record => record.revision !== snapshot(record.source)?.revision ? ['治理版本已变化，请重新生成并核对候选'] : issuesFor(snapshot(record.source)?.objects.find(item => item.base === record.base));
    return {
      records: () => records.map(record => {
        if (record.state === '已确认') return record;
        const gaps = recordIssues(record);
        return gaps.length ? { ...record, gaps, state: '需补充' } : record;
      }),
      snapshot, assessment, recordIssues,
      generate(sourceId) {
        const input = snapshot(sourceId);
        if (!input) return { added: 0, error: '请选择一个数据源。' };
        const assessed = assessment(sourceId), usable = assessed.items.filter(item => !item.issues.length);
        if (!usable.length) return { added: 0, error: '当前没有可用的已确认治理依据，请先完成治理核对。' };
        let added = 0, reused = 0;
        for (const plan of usable) {
          if (plan.existing) { reused++; continue; }
          const id = sourceId + '-' + plan.key + '-' + input.revision;
          if (records.some(record => record.id === id)) continue;
          records.push({ id, source: sourceId, base: plan.base, revision: input.revision, updated: now(), actor: '', reason: '',
            scope: { ...plan.scope, id: 'scope-' + id, name: plan.name, description: plan.description },
            gaps: [], state: '待确认' });
          added++;
        }
        return { added, reused, blocked: assessed.items.filter(item => item.issues.length).length };
      },
      confirm(id, value, actor) {
        const record = records.find(item => item.id === id);
        if (!record || record.state !== '待确认') return '当前范围不能确认，请刷新核对状态。';
        const issues = recordIssues(record);
        if (issues.length) return issues.join('；') + '，不能确认。';
        if (!value?.reviewed || ![value.name, value.description, value.reason, actor].every(item => typeof item === 'string' && item.trim())) return '请完成范围、依据和确认说明的核对。';
        const source = sources.find(item => item.id === record.source);
        const scope = { ...record.scope, name: value.name.trim(), description: value.description.trim(), state: '已确认' };
        if (selectableScopes(source).some(item => boundaryKey(item) === boundaryKey(scope))) return '已存在相同数据边界的范围，请选用已有范围。';
        // 确认只新增可复用的范围定义，不修改部门、人员或任何申请的访问来源。
        Object.assign(scope, { revision: record.revision, confirmedBy: actor, confirmedAt: now() });
        source.scopes.push(scope);
        Object.assign(record, { scope, state: '已确认', updated: now(), actor, reason: value.reason.trim() });
        return undefined;
      }
    };
  }
  const sources = window.PeopleDemo.dataSources;
  const store = createStore(sources);
  const query = { keyword: '', status: 'all', page: 1, pageSize: 10 };
  let selectedSource = 'retail', activeGroup = 'review';
  const sourceName = id => sources.find(source => source.id === id)?.name || '—';

  // 先呈现可核对的数据边界；缺口候选不把已有销售线索误报为完整退货范围。
  function boundaryFor(record) {
    if (record.gaps.length) return { content: '对象范围待补全', visible: '尚未完整确定', records: '拟限定获准部门，仍需核对', excluded: '待核对' };
    const scope = record.scope, marker = scope.fields.indexOf('不含');
    const records = scope.rows.includes('{department}') ? '仅获准部门的记录' : scope.rows;
    return {
      content: scope.tables,
      visible: (marker < 0 ? scope.fields : scope.fields.slice(0, marker)).replace(/[，,、\s]+$/, ''),
      records,
      excluded: marker < 0 ? '未单独标注' : scope.fields.slice(marker + 2).replace(/[。；;]+$/, '')
    };
  }
  const Boundary = {
    props: ['record'],
    computed: { parts() { return boundaryFor(this.record); } },
    template: `<dl class="scope-boundary"><div><dt>数据内容</dt><dd>{{parts.content}}</dd></div><div><dt>可见数据项</dt><dd>{{parts.visible}}</dd></div><div><dt>记录限制</dt><dd>{{parts.records}}</dd></div><div><dt>排除项</dt><dd>{{parts.excluded}}</dd></div></dl>`
  };
  const Evidence = {
    props: ['record'],
    computed: {
      sourceLabel() { return sourceName(this.record.source); },
      basis() { return store.snapshot(this.record.source)?.objects.find(object => object.base === this.record.base); },
      objects() {
        return (objectMappings[this.record.base] || []).map(object => ({ ...object, fieldRows: object.fields.split('、').map(field => {
          const match = field.match(/^(.*?)（(.*?)）$/);
          const technical = match?.[1] || field, meaning = match?.[2] || field;
          const purpose = ['department_id', 'listing_status'].includes(technical) ? '记录限制' : technical === 'product_id' ? '对象关联' : visibleFields(this.record.scope).includes(meaning) ? '开放数据' : '不开放';
          return { technical, meaning, purpose };
        }) }));
      }
    },
    template: `<div class="scope-evidence">
      <dl class="scope-evidence-meta"><div><dt>数据源</dt><dd>{{sourceLabel}}</dd></div><div><dt>已确认治理版本</dt><dd><code>{{record.revision}}</code></dd></div><div v-if="basis?.actor"><dt>治理确认人</dt><dd>{{basis.actor}}</dd></div><div v-if="basis?.confirmed"><dt>治理确认时间</dt><dd>{{basis.confirmed}}</dd></div></dl>
      <p v-if="basis?.evidence" class="scope-evidence-ref">{{basis.evidence}}</p>
      <section v-for="object in objects" :key="object.object" class="scope-object">
        <div class="scope-object-title"><h4>{{object.label}}</h4><code>{{object.object}}</code></div>
        <p class="scope-object-meaning">{{object.meaning}}</p>
        <el-collapse class="scope-object-fields"><el-collapse-item :title="object.label+'字段 · '+object.fieldRows.length+' 项'" :name="object.object">
          <dl class="scope-field-mapping"><div v-for="field in object.fieldRows" :key="field.technical"><dt>{{field.meaning}}<span class="scope-field-purpose">{{field.purpose}}</span></dt><dd><code>{{field.technical}}</code></dd></div></dl>
        </el-collapse-item></el-collapse>
      </section>
    </div>`
  };

  function openReview(record, trigger, ui, onConfirmed) {
    const node = ui.modal('核对业务范围', trigger);
    node.classList.add('scope-review-dialog');
    const title = node.querySelector('.dialog-heading h2'), titleGroup = ui.el('div', 'scope-review-heading');
    title.replaceWith(titleGroup); titleGroup.append(title, ui.status(record.state));
    const host = ui.el('div', 'scope-review-host prototype-ui'); node.append(host);
    const required = message => ({ required: true, validator: (_, value, done) => done(String(value || '').trim() ? undefined : new Error(message)), trigger: 'blur' });
    // 定义与依据可同时核对，底部一次确认；保留说明和人工勾选，不重复抄写预览表格。
    PrototypeUI.mount(host, {
      components: { ScopeBoundary: Boundary, ScopeEvidence: Evidence },
      data: () => ({ record, blocked: record.gaps.length > 0, error: '', model: { name: record.scope.name, description: record.scope.description, reason: '', reviewed: false },
        rules: { name: [required('请输入范围名称')], description: [required('请输入数据内容说明')], reason: [required('请填写核对说明')] } }),
      methods: {
        close() { node.close(); },
        submit() {
          if (this.blocked) return;
          if (!this.model.reviewed) { this.error = '请先完成数据边界与治理依据的核对。'; return; }
          this.$refs.form.validate(valid => {
            if (!valid) return;
            const error = store.confirm(record.id, structuredClone(Vue.toRaw(this.model)), ui.actor);
            if (error) { this.error = error; return; }
            node.close(); onConfirmed(); ui.notify('范围已确认，可在部门配置或申请中选用');
          });
        }
      },
      template: `<el-form ref="form" class="scope-review-shell" :model="model" :rules="rules" label-position="top" scroll-to-error @submit.prevent="submit">
        <div class="scope-review-body">
          <section class="scope-review-main" aria-label="范围定义与数据边界">
            <p v-if="error" class="form-error" role="alert">{{error}}</p>
            <template v-if="!blocked">
              <el-form-item label="范围名称" prop="name"><el-input v-model="model.name" aria-label="范围名称" maxlength="80"></el-input></el-form-item>
              <el-form-item label="数据内容说明" prop="description"><el-input v-model="model.description" type="textarea" :rows="2" resize="none" aria-label="数据内容说明" maxlength="300"></el-input></el-form-item>
            </template>
            <dl v-else class="scope-readonly-definition"><div><dt>范围名称</dt><dd>{{record.scope.name}}</dd></div><div><dt>拟定说明</dt><dd>{{record.scope.description}}</dd></div></dl>
            <section class="scope-boundary-section"><h3>数据边界</h3><p v-if="blocked" class="scope-evidence-gap" role="alert">{{record.gaps.join('；')}}</p><scope-boundary :record="record"></scope-boundary></section>
            <el-form-item v-if="!blocked" label="核对说明" prop="reason" class="scope-review-reason"><el-input v-model="model.reason" type="textarea" :rows="2" resize="none" aria-label="核对说明" placeholder="记录本次核对的依据或调整" maxlength="300"></el-input></el-form-item>
          </section>
          <aside class="scope-review-reference" aria-label="治理依据"><h3>{{blocked ? '现有治理依据' : '治理依据'}}</h3><scope-evidence :record="record"></scope-evidence></aside>
        </div>
        <footer class="dialog-footer scope-review-footer">
          <div class="scope-review-ack"><el-checkbox v-if="!blocked" v-model="model.reviewed" size="default">已核对数据边界与治理依据</el-checkbox><span class="scope-confirm-impact">仅确认范围定义，不授予人员访问</span></div>
          <div class="scope-review-actions"><el-button @click="close">{{blocked ? '关闭' : '取消'}}</el-button><el-button type="primary" native-type="submit" :disabled="blocked || !model.reviewed">{{blocked ? '等待补充依据' : '确认范围'}}</el-button></div>
        </footer>
      </el-form>`
    });
    node.showModal();
  }

  function openRecord(record, trigger, ui, onConfirmed) {
    if (record.state !== '已确认') { openReview({ ...record, gaps: store.recordIssues(record) }, trigger, ui, onConfirmed); return; }
    const node = ui.modal('业务范围详情', trigger, true), body = ui.el('div', 'dialog-body'), fields = ui.el('dl', 'detail-fields');
    fields.append(ui.pair('数据源', sourceName(record.source)), ui.pair('业务范围', record.scope.name), ui.pair('确认人', record.actor || '—'), ui.pair('确认时间', record.updated || '—'));
    const definition = ui.el('div', 'prototype-ui');
    PrototypeUI.mount(definition, { render: () => Vue.h('div', null, [
      Vue.h('h3', { class: 'scope-detail-section-title' }, '数据边界'), Vue.h(Boundary, { record }),
      Vue.h('h3', { class: 'scope-detail-section-title' }, '治理依据'), Vue.h(Evidence, { record })
    ]) });
    body.append(fields, ui.el('p', 'scope-detail-description', record.scope.description), definition);
    if (record.reason) { const audit = ui.el('section', 'detail-section'); audit.append(ui.el('h3', 'scope-detail-section-title', '核对说明'), ui.el('p', '', record.reason)); body.append(audit); }
    const footer = ui.el('footer', 'dialog-footer'); footer.append(ui.button('关闭', () => node.close()));
    node.append(body, footer); node.showModal();
  }

  function openGenerate(trigger, ui, onGenerated) {
    const node = ui.modal('生成业务范围候选', trigger), host = ui.el('div', 'scope-generation-host'); node.append(host);
    PrototypeUI.mount(host, {
      data: () => ({ source: selectedSource === 'all' ? '' : selectedSource, sources, popupTarget: node, error: '' }),
      computed: {
        assessment() { return store.assessment(this.source); },
        ready() { return this.assessment.items.filter(item => !item.issues.length); },
        fresh() { return this.ready.filter(item => !item.existing); },
        basisItems() { return [...new Map(this.assessment.items.map(item => [item.base, item])).values()]; }
      },
      methods: {
        close() { node.close(); },
        generate() {
          const result = store.generate(this.source);
          if (result.error) { this.error = result.error; return; }
          selectedSource = this.source; activeGroup = result.added || this.fresh.length ? 'review' : 'confirmed';
          Object.assign(query, { keyword: '', status: 'all', page: 1 }); node.close(); onGenerated();
          ui.notify(result.added ? '已生成 ' + result.added + ' 项候选；已有范围直接复用，治理缺口未纳入' : result.reused ? '已有范围可直接选用，本次没有新增候选' : '当前治理版本的候选已生成，保留原核对结果');
        }
      },
      template: `<div><div class="dialog-body scope-generation-body">
        <label for="generation-source">数据源</label><el-select id="generation-source" v-model="source" aria-label="生成候选的数据源" :append-to="popupTarget" placeholder="选择数据源" @change="error=''"><el-option v-for="item in sources" :key="item.id" :value="item.id" :label="item.name"></el-option></el-select>
        <p v-if="!source" class="scope-generation-empty">选择数据源后核对治理依据。</p>
        <template v-else><div class="scope-generation-summary"><span>治理版本 <code>{{assessment.revision}}</code></span><span>{{ready.length ? '仅使用已确认的治理结果' : '暂无可用治理依据'}}</span></div>
          <ul class="scope-generation-items"><li v-for="item in basisItems" :key="item.base"><div class="scope-generation-item-title"><strong>{{item.object?.definition?.tables || item.name}}</strong><span class="status-tag" :class="{'is-pending':item.issues.length}">{{item.issues.length ? '治理未完成' : '已确认，可使用'}}</span></div><p v-if="item.issues.length">{{item.issues.join('；')}}</p><p v-else>{{item.object.actor}} · {{item.object.confirmed}}<br>{{item.object.evidence}}</p></li></ul>
          <p class="scope-generation-rule">仅使用已确认业务含义、字段、记录限制和关联的对象。治理缺口不纳入候选，已有相同边界的范围直接复用。</p>
        </template><p v-if="error" class="form-error" role="alert">{{error}}</p>
        </div><footer class="dialog-footer"><el-button @click="close">取消</el-button><el-button type="primary" :disabled="!source || !ready.length" @click="generate">{{!source ? '生成候选' : !ready.length ? '请先完成治理核对' : !fresh.length ? '查看已有范围' : '生成候选'}}</el-button></footer></div>`
    });
    // 弹层位于原生 dialog 内，Select 的浮层也挂入同一顶层，保证可点击与键盘访问。
    node.showModal();
  }

  function render(main, ui) {
    const rerender = () => render(main, ui), tools = ui.el('section', 'people-query-card scope-query-card');
    const reset = () => { Object.assign(query, { keyword: '', status: 'all', page: 1 }); rerender(); main.querySelector('input[name="keyword"]')?.focus({ preventScroll: true }); };
    const select = ui.el('div', 'scope-source-select prototype-ui');
    PrototypeUI.mount(select, {
      setup: () => () => Vue.h(ElementPlus.ElSelect, { modelValue: selectedSource, 'onUpdate:modelValue': value => { selectedSource = value; query.page = 1; rerender(); }, ariaLabel: '业务范围数据源', style: { width: '100%' } },
        { default: () => [{ id: 'all', name: '全部数据源' }, ...sources].map(source => Vue.h(ElementPlus.ElOption, { value: source.id, label: source.name })) })
    });
    tools.append(select, RecordControls.filters({ value: query, includeDates: false, includeStatus: activeGroup === 'review', keywordLabel: '搜索业务范围', keywordPlaceholder: '范围名称或数据内容', statusLabel: '候选状态',
      statuses: [{ value: 'all', label: '全部状态' }, ...['待确认', '需补充'].map(value => ({ value, label: value }))],
      onApply: value => { Object.assign(query, value); rerender(); main.querySelector('input[name="keyword"]')?.focus({ preventScroll: true }); }, onReset: reset
    }));
    const records = store.records().filter(record => (selectedSource === 'all' || record.source === selectedSource) && (activeGroup === 'confirmed' ? record.state === '已确认' : record.state !== '已确认') && (query.status === 'all' || record.state === query.status) && AdminQuery.matches([record.scope.name, record.scope.description, sourceName(record.source)], query.keyword))
      .sort((a, b) => Number(a.state === '需补充') - Number(b.state === '需补充') || b.updated.localeCompare(a.updated) || b.id.localeCompare(a.id));
    query.page = Math.max(1, Math.min(query.page, Math.ceil(records.length / query.pageSize)));
    const panel = ui.el('section', 'record-panel scope-result-panel'), empty = ui.el('div', 'empty-state');
    empty.append(ui.el('p', '', query.keyword || query.status !== 'all' ? '没有匹配的业务范围' : activeGroup === 'review' ? '暂无待核对候选' : '暂无已确认范围'));
    if (query.keyword || query.status !== 'all') empty.append(ui.button('清空筛选', reset));
    const table = RecordTable.render({ caption: activeGroup === 'review' ? '待核对业务范围' : '已确认业务范围', minWidth: 920,
      columns: [{ key: 'scope', label: '业务范围' }, { key: 'source', label: '数据源', width: 144 }, { key: 'state', label: activeGroup === 'review' ? '状态' : '确认人', width: 112 }, { key: 'updated', label: activeGroup === 'review' ? '生成时间' : '确认时间', width: 166 }, { key: 'actions', label: '操作', width: 128 }],
      items: records.slice((query.page - 1) * query.pageSize, query.page * query.pageSize), empty,
      row: record => {
        const afterConfirmed = () => { activeGroup = 'confirmed'; Object.assign(query, { keyword: '', status: 'all', page: 1 }); rerender(); Vue.nextTick(() => main.querySelector('[data-focus="scope-' + record.id + '"]')?.focus({ preventScroll: true })); };
        const name = ui.el('div'), title = ui.button(record.scope.name, trigger => openRecord(record, trigger, ui, afterConfirmed), 'cell-title', 'scope-' + record.id); title.title = record.scope.name;
        const description = ui.el('span', 'cell-sub', record.scope.description); description.title = record.scope.description; name.append(title, description);
        return [name, ui.textCell(sourceName(record.source)), activeGroup === 'review' ? ui.status(record.state) : ui.textCell(record.actor || '—'), ui.textCell(record.updated || '—'), ui.button(record.state === '已确认' ? '查看' : record.gaps.length ? '查看缺口' : '核对', trigger => openRecord(record, trigger, ui, afterConfirmed), 'do-action is-primary', 'scope-review-' + record.id)];
      }
    });
    const pager = RecordControls.pager({ total: records.length, ...query }, page => { query.page = page; rerender(); }, size => { query.pageSize = size; query.page = 1; rerender(); });
    const tabs = ui.el('div', 'scope-list-tabs prototype-ui');
    // 真实结果挂到当前页签的公开内容插槽，保留表格、分页与 aria-tabpanel 的对应关系。
    const tabsComponent = {
      setup() {
        const content = Vue.ref();
        Vue.onMounted(() => content.value.append(table, pager));
        return () => Vue.h(ElementPlus.ElTabs, { class: 'scope-tabs', modelValue: activeGroup, 'onUpdate:modelValue': value => {
          if (value === activeGroup) return;
          activeGroup = value; Object.assign(query, { status: 'all', page: 1 }); rerender();
          Vue.nextTick(() => main.querySelector('.scope-tabs [role="tab"][aria-selected="true"]')?.focus({ preventScroll: true }));
        } }, { default: () => [['review', '待核对'], ['confirmed', '已确认']].map(([name, label]) => Vue.h(ElementPlus.ElTabPane, { name, label }, { default: () => name === activeGroup ? Vue.h('div', { class: 'scope-result-content', ref: content }) : null })) });
      }
    };
    const actions = ui.el('div', 'scope-result-actions');
    const generate = ui.button('生成候选', trigger => openGenerate(trigger, ui, rerender), 'admin-button is-primary', 'generate-scopes');
    actions.append(generate); panel.append(tabs, actions);
    main.replaceChildren(ui.pageHeading('业务范围'), tools, panel);
    // Tabs 在插入页面后挂载，避免离线节点宽度为零时选中下划线停在第一个页签。
    PrototypeUI.mount(tabs, tabsComponent);
  }
  window.BusinessScopePage = { render, Evidence, createStore, boundaryFor, selectableScopes, validateAccess, validateUntil };
})();
