/* 仅供原型点击演示：建议、样本及 SQL 均为模拟，不调用模型或数据库，也不预填业务标签。 */
(() => {
  'use strict';
  const copy = value => structuredClone(value);
  // 仅演示按字段语义线索检查关键缺口，不把某几个固定字段名作为提问名单。
  const fieldProfile = field => field ? GovernanceDemo.fieldProfile(field) : '';
  const unitFrom = value => GovernanceDemo.unitFromText(value);
  const normalizedText = text => (text || '').trim().replace(/[。；;]+$/, '').replace(/\s+/g, '');
  // 同一语义维度才比较冲突；单位、空值、编码和筛选等互补信息可以合并。
  function evidenceFacts(text, kind, key) {
    if (key === 'meaning' || kind === 'overview' && key === 'values') return new Map([['definition', normalizedText(text)]]);
    const facts = new Map(), other = [];
    const clauses = text.split(/[，,。；;\n]/).map(value => value.trim()).filter(Boolean);
    for (const clause of clauses) {
      let recognized = false;
      const add = (name, value) => { facts.set(name, normalizedText(value)); recognized = true; };
      const unit = unitFrom(clause);
      if (unit) add('unit', GovernanceDemo.normalizedUnit(unit));
      const nullValue = clause.match(/空值\s*(?:表示|代表|为|是|[:：])\s*(.+)/); if (nullValue) add('null', nullValue[1]);
      const emptyValue = clause.match(/空字符串\s*(?:表示|代表|为|是|[:：])\s*(.+)/); if (emptyValue) add('empty', emptyValue[1]);
      const code = clause.match(/^(?:(?:状态|编码)\s*)?['"]?([A-Za-z0-9_-]+)['"]?\s*(?:=|＝|表示|代表|为|[:：])\s*(.+)$/); if (code) add('code:' + code[1], code[2]);
      if (/编码(?:使用|格式)|格式|前导零/.test(clause)) add('format', clause);
      if (/唯一/.test(clause)) add('uniqueness', clause);
      const zone = clause.match(/UTC(?:[+-]\d{1,2}(?::\d{2})?)?|GMT(?:[+-]\d{1,2})?|Asia\/[A-Za-z_]+|北京时间|东八区/); if (zone) add('timezone', /北京时间|东八区|Asia\/Shanghai/.test(zone[0]) ? 'UTC+08:00' : zone[0]);
      if (/自然日|营业日|统计边界|起点|终点/.test(clause)) add('boundary', clause);
      if (/退款|退货|退回/.test(clause)) add(/回写|回填/.test(clause) ? 'refund-storage' : 'refund-calculation', clause);
      if (/取消/.test(clause)) add('filter-cancelled', clause);
      if (/测试/.test(clause)) add('filter-test', clause);
      if (/有效|作废|状态/.test(clause) && !code && !nullValue) add('filter-state', clause);
      if (kind === 'relations' && key === 'values' && /一对一|一对多|多对一|多对多/.test(clause)) add('cardinality', clause);
      if (kind === 'metrics' && key === 'values' && /\b(?:SUM|COUNT|AVG|MIN|MAX)\s*\(/i.test(clause)) add('formula', clause);
      if (!recognized) other.push(clause);
    }
    if (other.length) facts.set('definition', normalizedText(other.join('；')));
    return facts;
  }
  function compareEvidence(candidates, kind, key) {
    const merged = new Map(); let conflict = false;
    for (const candidate of candidates) for (const [facet, value] of evidenceFacts(candidate.value, kind, key)) {
      if (merged.has(facet) && merged.get(facet) !== value) conflict = true;
      merged.set(facet, value);
    }
    const complete = candidates.find(candidate => candidates.every(other => candidate.value.includes(normalizedText(other.value)) || normalizedText(candidate.value).includes(normalizedText(other.value))));
    const value = complete?.value || [...new Set(candidates.flatMap(candidate => candidate.value.split(/[。；;\n]/).map(part => part.trim()).filter(Boolean)))].join('；') + '。';
    return { conflict, value };
  }
  // 表说明使用同批次的完整记录；字段说明只展示当前列，全部样本均为明确的模拟数据。
  function fieldSamples(field) {
    const numeric = /INT|DECIMAL|NUMERIC/i.test(field.type);
    if (['id', 'sale_item_id'].includes(field.name)) return numeric ? [1001, 1002, 1003, 1004, 1005] : ['ID-1001', 'ID-1002', 'ID-1003', 'ID-1004', 'ID-1005'];
    if (field.name === 'store_id') return [1, 1, 2, 2, 3];
    if (fieldProfile(field) === 'enum') return [0, 1, 1, 2, 1];
    if (field.name === 'paid_at') return ['2026-11-10 09:21:00', '2026-11-10 10:05:12', null, '2026-11-11 08:42:03', null];
    if (field.name === 'updated_at') return ['2026-11-10 09:22:00', '2026-11-10 10:06:00', '2026-11-10 11:30:00', null, '2026-11-11 08:45:00'];
    if (fieldProfile(field) === 'quantity') return [1.25, 2.5, 1, 3.75, 2];
    if (fieldProfile(field) === 'amount') return [128.5, 64, 215.25, 0, 86];
    if (/DATE|TIME/i.test(field.type)) return ['2026-11-10 09:00:00', '2026-11-10 10:00:00', '2026-11-10 11:00:00', '2026-11-11 09:00:00', '2026-11-11 10:00:00'];
    return numeric ? [101, 102, 103, 104, 105] : ['DEMO-001', 'DEMO-002', 'DEMO-003', 'DEMO-004', 'DEMO-005'];
  }
  function proposal(record, entry, task) {
    const table = record.table, field = table.fields.find(field => field.name === entry.field);
    const labels = table.label, name = field?.name, profile = fieldProfile(field);
    const unitPrice = profile === 'amount' && /price|单价|标价|售价/i.test((name || '') + ' ' + (field.comment || '')) && !/total|amount|总价|总额|金额/i.test((name || '') + ' ' + (field.comment || ''));
    let content, reasoning;
    if (['relations', 'metrics'].includes(entry.kind)) {
      const discoveryCurrent = entry.discovery?.snapshot === table.version && (entry.discovery.origin !== '业务资料' || entry.discovery.notesSignature === noteSignature(record));
      content = { meaning: '', values: '', usage: '', ...(discoveryCurrent ? copy(entry.discovery.content) : {}) };
      reasoning = '仅整理明确指向当前' + (entry.kind === 'relations' ? '关联' : '指标') + '的业务资料；字段名称或样本匹配不足以确认完整业务规则。';
    } else if (entry.kind === 'overview') {
      content = { meaning: '记录' + labels + '的业务信息，可结合本表字段分析对应业务。', values: table.name === 'sale_order' ? '一行对应一笔订单，商品明细在订单明细表中记录。' : table.name === 'sale_item' ? '一行对应订单中的一条商品明细，同一订单可以有多条明细。' : '' };
      reasoning = '演示结合表名称、原注释、全部字段与完整记录样本判断记录对象和粒度；样本不能证明完整业务口径。';
    } else if (['id', 'sale_item_id'].includes(name)) {
      const numeric = /INT|DECIMAL|NUMERIC/i.test(field.type);
      content = { meaning: labels + '记录的编号。', values: (numeric ? '整数编号' : '当前类型为 ' + field.type + ' 的编号') + '，用于识别记录；编号大小不表示业务发生顺序。', rules: '关联明细后统计记录数时，需要核对是否产生重复行。', usage: '' };
      reasoning = '演示结合当前字段名称、类型、已采集注释及编号样本提出标识候选；少量样本本身不能证明全表唯一。';
    } else if (name === 'store_id') {
      content = { meaning: '这条业务记录所属门店的编号。', values: '同一门店编号可以出现在多条记录中。', rules: '用于按门店归属分析。', usage: '与门店档案的关联键需要单独核对。' };
      reasoning = '演示结合“门店编号”注释和重复出现的编号样本，提出归属门店的解释，不据此确认关联关系。';
    } else if (profile === 'enum') {
      content = { meaning: field.comment ? field.comment + '。' : labels + '记录的业务状态或分类。', values: '演示样本出现 0、1、2；这些代码的含义和完整范围尚未确定。', rules: '编码含义核清前，不能据代码直接筛选有效或已完成记录。', usage: '' };
      reasoning = '字段名和整数取值只能支持“状态编码”的候选解释，无法仅凭数值判断具体业务含义。';
    } else if (profile === 'time') {
      content = { meaning: (field.comment || name) + '。', values: '日期时间；具体时区' + (field.nullable === '是' ? '和空值的业务含义' : '') + '尚未确定。', rules: '', usage: '不能用来源更新时间代替业务事件时间。' };
      reasoning = '演示依据当前时间字段的名称、注释和格式提出候选；样本不能证明时区、空值含义或业务日期边界。';
    } else if (profile === 'updated') {
      content = { meaning: '来源记录的最后更新时间。', values: '日期时间；时区和空值原因仍需核对。', usage: '不能直接用于解释订单发生、支付或结算的业务时间。' };
      reasoning = '演示结合更新时间的名称、注释和时间样本提出解释，并保留与业务事件时间的区别。';
    } else if (profile === 'quantity') {
      content = { meaning: field.comment ? field.comment + '。' : '本条业务记录的数量。', values: '具体计量单位尚需结合业务资料核对。', rules: '不同计量单位不能直接相加；净数量需要另行确认退回或调整口径。', usage: '' };
      reasoning = '演示结合数量字段的名称、注释、数值精度和样本提出解释，不把可计算等同于业务口径正确。';
    } else if (profile === 'amount') {
      content = { meaning: field.comment ? field.comment + '。' : unitPrice ? '本条业务记录中的单价。' : '本条业务记录中的金额。', values: '', rules: unitPrice ? '单价用于比较或作为计算输入，不能按行求和作为总金额。' : '', usage: unitPrice ? '' : '核清支付和退款口径前，不能直接解释为净收入。' };
      reasoning = '结合字段名称与注释起草金额含义，单位和退款口径按实际可用依据整理；数值样本不能独自证明业务规则。';
    } else if (profile === 'code') {
      content = { meaning: labels + '的业务编码。', values: '编码样本可用于识别业务记录，完整编码规则尚未确定。', usage: '不能仅凭编码样本认定它是唯一键或推断编码中的日期含义。' };
      reasoning = '演示结合字段名与字符串样本提出编码解释，将尚无业务依据的编码规则保留待核对。';
    } else {
      content = { meaning: field?.comment?.trim() ? field.comment.trim() + '。' : '', values: '', usage: '' }; reasoning = field?.comment?.trim() ? '根据原注释起草业务含义，未从少量样本推断其他规则。' : '当前结构没有足够信息说明具体业务含义，不填入猜定的解释。';
    }
    const sections = GovernanceDemo.contentSections(entry.kind), references = [], supplied = new Set(), evidence = new Map(), knownFromComment = new Set();
    content = { ...Object.fromEntries(sections.map(section => [section.key, ''])), ...content };
    const commentUnit = ['amount', 'quantity'].includes(profile) ? unitFrom(field.comment) : '';
    if (commentUnit) { content.values = '单位：' + commentUnit + '。'; knownFromComment.add('values'); }
    // 演示只直取明确指向本对象的资料原文，不用样本或采集时间编造范围及更新规律。
    const peers = DataSourceDemo.objects(table.source), tableIdentities = [GovernanceDemo.objectName(table)];
    if (peers.filter(other => other.name === table.name).length === 1) tableIdentities.push(table.name);
    if (peers.filter(other => other.label === table.label).length === 1) tableIdentities.push(table.label);
    const identities = entry.kind === 'overview' ? [...new Set(tableIdentities)] : [...new Set([...tableIdentities.map(identity => identity + '.' + (name || entry.name)), ...(entry.discovery?.identities || [])])];
    const aliases = { values: entry.kind === 'fields' ? ['取值说明'] : entry.kind === 'relations' ? ['关系类型与完整键'] : entry.kind === 'metrics' ? ['计算方法与条件'] : [], rules: ['使用规则'], usage: entry.kind === 'fields' ? ['使用注意'] : entry.kind === 'relations' ? ['适用业务与限制'] : entry.kind === 'metrics' ? ['单位、时间与使用限制'] : [] };
    for (const note of task.notes) for (const line of note.content.split(/\r?\n/).map(text => text.trim()).filter(Boolean)) {
      for (const section of sections) {
        const prefixes = identities.flatMap(identity => [section.label, ...(aliases[section.key] || [])].flatMap(label => [identity + '的' + label, identity + ' ' + label, identity + '.' + label]));
        const prefix = prefixes.find(prefix => line.startsWith(prefix) && /^\s*[:：]/.test(line.slice(prefix.length)));
        let value = prefix ? line.slice(prefix.length).replace(/^\s*[:：]\s*/, '').trim() : '';
        if (!value && ['relations', 'metrics'].includes(entry.kind) && section.key === (entry.kind === 'relations' ? 'meaning' : 'values')) {
          const declaration = line.match(entry.kind === 'relations' ? /^关联\s+([^:：]{1,80})[:：]\s*(.+)$/ : /^指标\s+([^:：]{1,80})[:：]\s*(.+)$/);
          if (declaration && [entry.name, entry.discovery?.sourceName].includes(declaration[1].trim()) && tableIdentities.some(identity => declaration[2].includes(identity + '.'))) value = declaration[2];
        }
        if (!value) continue;
        const candidates = evidence.get(section.key) || [];
        candidates.push({ value, reference: { ...copy(note), excerpt: line, sections: [section.key] } }); evidence.set(section.key, candidates);
      }
    }
    // 同一项的多份依据先比较；冲突和明确未定的说明不能靠覆盖顺序变成结论。
    const conflicts = new Map(), uncertain = new Set();
    const undetermined = GovernanceDemo.unresolvedText;
    for (const section of sections) {
      const candidates = evidence.get(section.key) || [];
      const compared = compareEvidence(candidates, entry.kind, section.key);
      if (compared.conflict) { conflicts.set(section.key, candidates); content[section.key] = ''; }
      else if (candidates.length && undetermined(candidates[0].value)) { uncertain.add(section.key); content[section.key] = ''; }
      else if (candidates.length) { content[section.key] = compared.value; supplied.add(section.key); }
    }
    if (commentUnit && supplied.has('values') && unitFrom(content.values) && GovernanceDemo.normalizedUnit(unitFrom(content.values)) !== GovernanceDemo.normalizedUnit(commentUnit)) { conflicts.set('values', evidence.get('values')); supplied.delete('values'); content.values = ''; }
    const questions = {
      meaning: '该内容具体表示什么业务对象、属性或事件？', values: entry.kind === 'overview' ? '一行对应什么业务对象，同一对象是否保留多条记录？' : '编码、单位、格式和空值分别表示什么？',
      scope: '包含哪些业务数据，是否排除测试、取消或历史记录？', updates: '数据覆盖何时起，按什么规律更新，当前可确认的数据截至何时？',
      rules: '筛选、计算或统计时需要遵守哪些业务规则？', usage: '有哪些格式、标识、时间或关联方面的特殊限制？'
    };
    if (profile === 'enum') { questions.values = '观察到的编码 0、1、2 分别表示什么？' + (field.nullable === '是' ? '空值表示什么？' : '') + '实际字典还包含哪些值？'; questions.rules = '统计有效或已完成记录时，应选择哪些状态或分类？'; }
    if (profile === 'time') { questions.values = '该业务时间采用什么时区？' + (field.nullable === '是' ? '空值表示什么？' : ''); questions.rules = '按该时间统计时，日期或营业日的边界是什么？'; }
    if (profile === 'updated') questions.values = '更新时间采用什么时区？' + (field.nullable === '是' ? '空值表示什么？' : '');
    if (profile === 'quantity') { questions.values = '数量使用什么单位，是否随业务对象变化？'; questions.rules = '该数量可按哪些维度汇总，是否需要去重或调整？'; }
    if (profile === 'amount') { questions.values = '该金额使用元、分或其他什么单位？'; questions.rules = '统计该金额时采用什么范围，退款或调整是否回写？'; }
    if (profile === 'code') { questions.values = '业务编码遵循什么规则，在什么范围内唯一？'; questions.usage = '编码是否需要保留前导零，作为标识或关联键时有什么限制？'; }
    if (entry.kind === 'fields' && !content.meaning) questions.meaning = '字段 ' + name + ' 具体表示什么业务内容？当前类型和样本无法说明其含义。';
    if (entry.kind === 'relations') { questions.meaning = '关联“' + entry.name + '”通过哪些双方字段和附加条件连接？'; questions.values = '这条关联是一对一、一对多还是多对多，如何避免漏数或重复？'; }
    if (entry.kind === 'metrics') { questions.meaning = '指标“' + entry.name + '”衡量什么业务内容？'; questions.values = '该指标的完整公式、筛选条件及去重或调整规则是什么？'; }
    const missingReasons = {
      scope: '现有结构和少量样本不能证明完整业务范围及排除条件。',
      updates: '尚无业务数据保留、同步和截至时间的说明；元数据采集时间不能代替业务数据更新时间。',
      meaning: '结构、注释和现有资料不足以说明业务含义。', values: '现有输入不足以确定完整取值、单位或空值含义。',
      rules: '现有资料没有明确筛选或计算规则。', usage: '现有输入未明确特殊使用限制，不能据此断言没有限制。'
    };
    const required = entry.kind === 'overview' ? ['meaning', 'values'] : entry.kind === 'fields' ? ['meaning'] : ['meaning', 'values'];
    const critical = entry.kind === 'fields' ? unitPrice ? ['values'] : ['enum', 'time', 'quantity', 'amount'].includes(profile) ? ['values', 'rules'] : profile === 'updated' ? ['values'] : profile === 'code' ? ['values', 'usage'] : [] : [];
    const impactReason = { amount: '单位和退款口径会改变金额汇总结果。', quantity: '单位及退回调整会改变数量汇总结果。', enum: '编码及筛选口径会改变纳入统计的数据。', time: '业务时区和日期边界会改变统计期间。', updated: '更新时间的时区或空值解释会影响筛选和数据时点判断。', code: '唯一性和格式会影响去重与关联。' };
    const validation = GovernanceDemo.definitionIssues({ ...entry, content, ...(['amount', 'quantity'].includes(profile) ? { valueDetails: { unit: unitFrom(content.values), mappings: [] } } : {}) });
    const needsHuman = section => {
      if (conflicts.has(section.key)) return true;
      if (entry.discovery?.unresolved && entry.discovery.pending?.[section.key]) return true;
      if (validation.some(issue => issue.key === section.key)) return true;
      if (supplied.has(section.key)) return false;
      if (entry.discovery?.pending?.[section.key]) return true;
      if (required.includes(section.key) && !content[section.key].trim()) return true;
      return critical.includes(section.key) && (!knownFromComment.has(section.key) || uncertain.has(section.key));
    };
    const followups = sections.filter(needsHuman).map(section => {
      const candidates = evidence.get(section.key) || [], conflicting = conflicts.has(section.key);
      const reason = conflicting ? '资料存在不同说法：' + (section.key === 'values' && commentUnit ? '原注释单位为' + commentUnit + '；' : '') + candidates.map(candidate => candidate.reference.title + '（第 ' + candidate.reference.revision + ' 版）：' + candidate.value).join('；') : (uncertain.has(section.key) ? '当前资料明确保留此项待确认。' : '') + (impactReason[profile] || missingReasons[section.key]);
      return { key: section.key, label: section.label, origin: 'AI', question: conflicting ? '关于“' + section.label + '”有多种说法，请核实应采用哪个定义。' : entry.discovery?.pending?.[section.key] || questions[section.key], reason: conflicting ? reason : entry.discovery?.reason || reason, conflict: conflicting, preserveExisting: entry.kind === 'metrics' && section.key === 'values' && Boolean(entry.discovery?.pending?.values) && !entry.discovery.unresolved && !conflicting && Boolean(content.values.trim()), response: '', answer: '', resolved: false, deferred: false };
    });
    for (const section of sections) if (supplied.has(section.key) || followups.some(question => question.key === section.key)) for (const candidate of evidence.get(section.key) || []) {
      const ref = references.find(ref => ref.id === candidate.reference.id && ref.revision === candidate.reference.revision && ref.excerpt === candidate.reference.excerpt);
      if (ref) { if (!ref.sections.includes(section.key)) ref.sections.push(section.key); } else references.push(candidate.reference);
    }
    for (const ref of entry.discovery?.references || []) if (task.notes.some(note => note.id === ref.id && note.revision === ref.revision && note.content.includes(ref.excerpt)) && !references.some(old => old.id === ref.id && old.revision === ref.revision && old.excerpt === ref.excerpt)) references.push(copy(ref));
    if (entry.discovery?.reason) reasoning = entry.discovery.reason + ' ' + reasoning;
    if (supplied.size) reasoning += ' 已整理明确对应当前对象的' + sections.filter(section => supplied.has(section.key)).map(section => section.label).join('、') + '，原文及修订随引用保留。';
    if (conflicts.size) reasoning += ' 不同资料的冲突已保留，未自行选择其中一份。';
    const coverage = sections.map(section => ({ ...section, attempted: true, state: content[section.key].trim() ? '已有建议' : '缺少依据', needsSupplement: followups.some(question => question.key === section.key), conflict: conflicts.has(section.key), reason: followups.find(question => question.key === section.key)?.reason || (supplied.has(section.key) ? '整理本次有效业务说明中的明确原文（演示）。' : content[section.key].trim() ? reasoning : missingReasons[section.key]), question: followups.find(question => question.key === section.key)?.question || '', supplied: supplied.has(section.key) }));
    const gap = followups.map(question => question.question).join('\n');
    const checkContext = followups.length ? { origin: 'AI', question: gap, reason: [...new Set(followups.map(question => question.reason))].join('\n'), targets: followups.map(question => question.key), questions: followups } : null;
    const quote = DataSourceDemo.find(table.source).config.type === 'PostgreSQL' ? '"' : '`';
    const identifier = value => quote + value.replaceAll(quote, quote + quote) + quote;
    const fields = entry.kind === 'overview' ? table.fields : field ? [field] : [];
    const columns = fields.map(field => field.name), samples = fields.map(fieldSamples);
    const sql = 'SELECT ' + (columns.length ? columns.map(identifier).join(', ') : '1') + ' FROM ' + [table.schema, table.name].filter(Boolean).map(identifier).join('.') + ' LIMIT 5;';
    const sampling = entry.kind === 'overview'
      ? { simulated: true, sql, columns, rows: fields.length ? Array.from({ length: 5 }, (_, index) => Object.fromEntries(columns.map((column, i) => [column, samples[i][index]]))) : [], count: fields.length ? 5 : 0 }
      : { simulated: true, sql, column: name, values: samples[0] || [], count: samples[0]?.length || 0 };
    const unit = !conflicts.has('values') && !uncertain.has('values') && ['amount', 'quantity'].includes(profile) ? unitFrom(content.values) : '';
    return { content, gap, reasoning, sampling, coverage, checkContext, references, ...(['amount', 'quantity'].includes(profile) ? { valueDetails: { unit, mappings: [] } } : {}) };
  }
  const noteSignature = record => JSON.stringify(GovernanceDemo.availableNotes(record.table.source).map(note => [note.id, note.revision, note.content]));
  // 预览不写入治理内容、任务或资料取用；明确选择替换范围后才提交本次草稿。
  function preview(record, kinds, actor, entryId, entryIds) {
    if (!SourceApprovalDemo.hasAbility(actor, 'governance.view') || !SourceApprovalDemo.hasAbility(actor, 'governance.suggest')) return { error: '当前账号没有生成 AI 建议的权限。' };
    const entries = record.items.filter(entry => kinds.includes(entry.kind) && !entry.retired && !GovernanceDemo.isDismissed(entry) && (!entryId || entry.id === entryId) && (!entryIds || entryIds.includes(entry.id)));
    if (!entries.length) return { error: '请选择需要生成建议的条目。' };
    const notes = copy(GovernanceDemo.availableNotes(record.table.source));
    return { snapshot: record.table.version, notesSignature: noteSignature(record), outputs: entries.map(entry => ({ id: entry.id, revision: entry.revision, ...proposal(record, entry, { notes }) })) };
  }
  function generate(record, kinds, actor, entryId, expectedRevision, options = {}) {
    const selected = record.items.find(entry => entry.id === entryId);
    if (selected && expectedRevision !== undefined && selected.revision !== expectedRevision) return '当前条目已修改，请重新核对后生成。';
    const prepared = options.preview || preview(record, kinds, actor, entryId, options.entryIds);
    if (prepared.error) return prepared.error;
    if (prepared.snapshot !== record.table.version || prepared.notesSignature !== noteSignature(record)) return '来源结构或业务资料已变化，请重新生成并核对差异。';
    if (prepared.outputs.some(output => record.items.find(entry => entry.id === output.id)?.revision !== output.revision)) return '治理条目已修改，请重新核对后生成。';
    const replacements = Object.fromEntries(prepared.outputs.map(output => {
      const entry = record.items.find(entry => entry.id === output.id);
      // 批量默认只补充空白或纯 AI 草稿，人工内容及确认内容必须明确勾选替换。
      const protectedEntry = entry.review === '已确认' || Boolean(entry.editedBy) || entry.definitionSource?.kind === '人工填写';
      return [entry.id, options.replacements ? options.replacements[entry.id] || [] : protectedEntry ? [] : GovernanceDemo.contentSections(entry.kind).map(section => section.key)];
    }));
    const entryIds = prepared.outputs.filter(output => replacements[output.id]?.length).map(output => output.id);
    if (!entryIds.length) return '现有人工或已确认内容受到保护，请先选择需要替换的内容。';
    const error = GovernanceDemo.generate(record, kinds, actor, entryId, entryIds); if (error) return error;
    const task = GovernanceDemo.tasksFor(record)[0];
    task.simulated = true; task.failure = ''; task.state = '已生成（演示）'; task.method = '原型交互演示'; task.outputs = [];
    for (const target of task.entries) {
      const entry = record.items.find(entry => entry.id === target.id), output = prepared.outputs.find(output => output.id === entry.id), keys = replacements[entry.id];
      const base = options.baseModels?.[entry.id] || { ...copy(entry.content), gap: entry.gap, checkContext: copy(entry.checkContext || null), basis: entry.basis };
      entry.history.unshift({ action: '演示生成前内容', actor: actor.name, at: DataSourceDemo.now(), revision: entry.revision, review: entry.review, reviewer: entry.reviewer, reviewedAt: entry.reviewedAt, content: copy(entry.content), valueDetails: copy(entry.valueDetails || null), dependencies: copy(entry.dependencies || []), basis: entry.basis, checkContext: copy(entry.checkContext || null), references: copy(entry.references || []), definitionSource: copy(entry.definitionSource || null) });
      entry.ai = { ...entry.ai, ...copy(output), simulated: true };
      entry.content = Object.fromEntries(GovernanceDemo.contentSections(entry.kind).map(({ key }) => [key, keys.includes(key) ? output.content[key] : base[key] || '']));
      const questions = [...(base.checkContext?.questions || []).filter(question => !keys.includes(question.key)), ...(output.checkContext?.questions || []).filter(question => keys.includes(question.key))];
      entry.checkContext = questions.length ? { origin: 'AI', question: questions.map(question => question.question).join('\n'), reason: [...new Set(questions.map(question => question.reason))].join('\n'), targets: questions.map(question => question.key), questions: copy(questions) } : null;
      entry.gap = questions.length ? questions.filter(question => !question.resolved).map(question => question.question).join('\n') : !base.checkContext && keys.length < GovernanceDemo.contentSections(entry.kind).length ? base.gap || '' : '';
      const replacedAll = keys.length === GovernanceDemo.contentSections(entry.kind).length;
      entry.basis = base.basis || ''; entry.references = [...(replacedAll ? [] : entry.references || []), ...copy(output.references.filter(ref => !ref.sections || ref.sections.some(key => keys.includes(key))))].filter((ref, index, all) => all.findIndex(other => other.id === ref.id && other.revision === ref.revision && other.excerpt === ref.excerpt) === index); entry.sourceTaskId = task.id;
      if (replacedAll) entry.manualEvidence = null;
      if (base.valueDetails) entry.valueDetails = copy(base.valueDetails);
      if (keys.includes('values') && output.valueDetails) entry.valueDetails = { ...copy(entry.valueDetails || { unit: '', mappings: [] }), unit: output.valueDetails.unit };
      if (base.dependencies) entry.dependencies = copy(base.dependencies);
      if (base.name?.trim()) entry.name = base.name.trim();
      GovernanceDemo.invalidateDependents(record, entry, actor, '依赖定义重新生成');
      entry.review = '待确认'; entry.reviewer = ''; entry.reviewedAt = ''; entry.revision++;
      entry.definitionSource = { kind: 'AI 建议（原型演示）', simulated: true, taskId: task.id, at: DataSourceDemo.now(), structure: copy(entry.ai.structure) };
      entry.history.unshift({ action: '演示建议写入待审核草稿', actor: '原型演示', at: DataSourceDemo.now(), revision: entry.revision, review: entry.review, content: copy(entry.content), checkContext: copy(entry.checkContext), definitionSource: copy(entry.definitionSource) });
      task.outputs.push({ ...copy(output), id: entry.id, name: entry.name, kind: entry.kind, replacedSections: copy(keys) });
    }
    GovernanceDemo.refreshProgress(record);
    task.events = [
      { action: '整理结构上下文（演示）', at: task.at, detail: record.table.path + ' · ' + record.table.version },
      { action: '纳入业务说明输入', at: task.at, detail: task.notes.length + ' 份当前源有效资料，保留原文及修订；演示未执行真实模型分析。' },
      { action: '核对代表性样本（演示）', at: DataSourceDemo.now(), detail: 'SQL 和样本为模拟，未访问业务数据库。' },
      { action: '建议写入待审核草稿（演示）', at: DataSourceDemo.now(), detail: '演示结果未自动确认，缺口保留待核对。' }
    ];
    DataSourceDemo.noteUses.filter(use => use.taskId === task.id).forEach(use => { use.state = '演示建议待审核'; use.method = '原型交互演示'; use.result = '模拟建议用于体验审核流程，未执行真实模型和 SQL。'; });
    return undefined;
  }
  // 发现只创建有明确技术线索或资料命名的候选；初始目录不预填业务关系和指标。
  function discoverySignature(record) {
    return JSON.stringify([DataSourceDemo.objects(record.table.source).map(table => [table.id, table.identity, table.generation, table.version, table.label, table.comment, table.readState, table.fields, table.primaryKey, table.foreignKeys, DataSourceDemo.pendingVerification(table).map(change => [change.id, change.fact])]), noteSignature(record)]);
  }
  function discover(record, kinds, actor, expectedSignature) {
    if (!SourceApprovalDemo.hasAbility(actor, 'governance.view') || !SourceApprovalDemo.hasAbility(actor, 'governance.suggest')) return { error: '当前账号没有发现 AI 候选的权限。' };
    if (!DataSourceDemo.canGovern(record.table) || DataSourceDemo.pendingVerification(record.table).length) return { error: '请先完成当前表的采集及来源事实核实。' };
    if (expectedSignature && discoverySignature(record) !== expectedSignature) return { error: '结构或业务资料已变化，请重新发现候选。' };
    if (!kinds.length || kinds.some(kind => !['relations', 'metrics'].includes(kind))) return { error: '请选择关联或指标。' };
    const tables = DataSourceDemo.objects(record.table.source).filter(table => DataSourceDemo.canGovern(table) && !DataSourceDemo.pendingVerification(table).length), notes = copy(GovernanceDemo.availableNotes(record.table.source));
    const resolve = name => {
      const matches = tables.filter(table => GovernanceDemo.objectName(table) === name || table.path === name || table.name === name);
      return matches.length === 1 ? matches[0] : undefined;
    };
    const dependencies = text => GovernanceDemo.fieldReferences(record.table.source, text, tables);
    const seeds = new Map(), rawRelations = [];
    // 业务规则的方向、完整条件和适用范围共同标识候选，字段集合相同不代表同一条规则。
    const conditionKey = text => (text || '').replace(/'(?:''|[^'])*'|[^']+/g, value => value.startsWith("'") ? value : value.replace(/\s+|[`\"]/g, ''));
    const relationKey = (owner, name, content) => 'relations:' + JSON.stringify([owner.id, name, conditionKey(content.meaning), normalizedText(content.values), normalizedText(content.usage)]);
    const put = seed => {
      if (seed.kind === 'relations' && seed.origin === '业务资料') {
        const technical = rawRelations.find(raw => raw.dependencies.every(ref => seed.dependencies.some(dep => dep.table === ref.table && dep.entry === ref.entry)) && conditionKey(seed.content.meaning).includes(conditionKey(raw.content.meaning)));
        if (technical) { seed.owner = technical.owner; seeds.delete(technical.key); seed.reason = technical.reason + ' ' + seed.reason; }
      }
      seed.key = seed.kind === 'relations' ? relationKey(seed.owner, seed.name, seed.content) : seed.kind + ':' + seed.owner.id + ':' + seed.name;
      const old = seeds.get(seed.key);
      if (!old) { seeds.set(seed.key, seed); if (seed.origin === '采集外键') rawRelations.push(seed); return seed; }
      if (seed.origin === '业务资料') {
        if (old.origin === '采集外键') { old.name = seed.name; old.origin = seed.origin; }
        Object.assign(old.content, seed.content); Object.assign(old.pending, seed.pending);
        old.unresolved ||= seed.unresolved;
      }
      old.identities.push(...seed.identities); old.references.push(...seed.references); return old;
    };
    if (kinds.includes('relations')) for (const owner of tables) for (const fk of owner.foreignKeys || []) {
      const target = resolve(fk.target);
      if (!target || owner !== record.table && target !== record.table || !fk.fields?.length || fk.fields.length !== fk.targetFields?.length) continue;
      if (!fk.fields.every(field => owner.fields.some(column => column.name === field)) || !fk.targetFields.every(field => target.fields.some(column => column.name === field))) continue;
      put({ kind: 'relations', owner, name: owner.label + '与' + target.label, origin: '采集外键', content: { meaning: fk.fields.map((field, index) => GovernanceDemo.objectName(owner) + '.' + field + ' = ' + GovernanceDemo.objectName(target) + '.' + fk.targetFields[index]).join(' AND '), values: '', usage: '' }, dependencies: [...fk.fields.map(field => ({ table: owner.id, entry: 'field:' + field })), ...fk.targetFields.map(field => ({ table: target.id, entry: 'field:' + field }))], pending: {}, references: [], identities: [], reason: '采集外键 ' + fk.name + ' 提供字段对应线索，数量关系、附加业务条件及统计风险仍须核实。' });
    }
    const identities = [GovernanceDemo.objectName(record.table), record.table.path, ...(tables.filter(table => table.name === record.table.name).length === 1 ? [record.table.name] : [])];
    const labels = { relations: { '关联条件': 'meaning', '关系类型与完整键': 'values', '数量关系与完整键': 'values', '适用业务与限制': 'usage', '适用条件与限制': 'usage' }, metrics: { '指标含义': 'meaning', '计算方法与条件': 'values', '公式与筛选条件': 'values', '单位、时间与使用限制': 'usage', '单位与统计边界': 'usage' } };
    // 明确对象和名称的资料片段先成组，复用逐项起草的来源比较与冲突处理。
    const named = new Map();
    for (const note of notes) for (const line of note.content.split(/\r?\n/).map(value => value.trim()).filter(Boolean)) for (const kind of kinds) {
      let name, section, value, identity;
      for (const tableName of [...new Set(identities)].sort((a, b) => b.length - a.length)) for (const [label, key] of Object.entries(labels[kind])) {
        const prefix = tableName + '.', end = line.indexOf(' ' + label);
        if (line.startsWith(prefix) && end > prefix.length && /^\s*[:：]/.test(line.slice(end + label.length + 1))) { name = line.slice(prefix.length, end).trim(); section = key; value = line.slice(end + label.length + 1).replace(/^\s*[:：]\s*/, ''); identity = tableName + '.' + name; }
      }
      const declaration = line.match(kind === 'relations' ? /^关联\s+([^:：]{1,80})[:：]\s*(.+)$/ : /^指标\s+([^:：]{1,80})[:：]\s*(.+)$/);
      if (!name && declaration && (dependencies(declaration[2]).found.some(ref => ref.table === record.table.id) || identities.some(identity => declaration[2].includes(identity + '.')))) { name = declaration[1].trim(); section = kind === 'relations' ? 'meaning' : 'values'; value = declaration[2]; }
      if (!name || !value) continue;
      const key = kind + ':' + name, current = named.get(key) || { kind, owner: record.table, name, origin: '业务资料', content: {}, dependencies: [], dependencyText: [], pending: {}, references: [], identities: [], reason: '业务资料明确提出当前表相关的' + (kind === 'relations' ? '关联' : '指标') + '，按本次资料修订起草，尚未人工确认。' };
      current.content[section] ||= value; if (identity) current.identities.push(identity);
      current.dependencyText.push(value);
      current.references.push({ ...copy(note), excerpt: line, sections: [section] }); named.set(key, current);
    }
    for (const seed of named.values()) {
      const refs = dependencies(seed.dependencyText.join('\n'));
      seed.dependencies = refs.found;
      if (seed.kind === 'metrics') for (const tableId of new Set(refs.found.map(ref => ref.table))) seed.dependencies.push({ table: tableId, entry: 'overview' });
      if (seed.kind === 'relations' && seed.dependencies.length && !seed.dependencies.some(ref => ref.table === record.table.id)) continue;
      if (refs.missing.length || seed.dependencies.length < (seed.kind === 'relations' ? 2 : 1)) { seed.unresolved = true; seed.pending[seed.kind === 'relations' ? 'meaning' : 'values'] = '请核实并选择本条定义实际使用的表字段' + (refs.missing.length ? '，当前未能定位：' + refs.missing.join('、') : '') + '。'; }
      put(seed);
    }
    if (kinds.includes('metrics')) {
      const table = record.table, qualified = GovernanceDemo.objectName(table);
      const primary = (table.primaryKey || []).filter(name => table.fields.some(field => field.name === name));
      if (primary.length === 1 && ![...seeds.values()].some(seed => seed.kind === 'metrics' && /COUNT\s*\(/i.test(seed.content.values || ''))) put({ kind: 'metrics', owner: table, name: table.label + '记录数', origin: '采集结构', content: { meaning: '统计当前表中记录的数量。', values: 'COUNT(DISTINCT ' + qualified + '.' + primary[0] + ')', usage: '统计表内记录，不自动等同于有效业务笔数；业务范围引用已确认的表说明。' }, dependencies: [{ table: table.id, entry: 'overview' }, { table: table.id, entry: 'field:' + primary[0] }], pending: {}, references: [], identities: [], reason: '采集主键提供记录标识，表粒度与标识定义须先确认；记录数不自动解释为业务完成笔数。' });
      for (const field of table.fields.filter(field => ['amount', 'quantity'].includes(fieldProfile(field)) && !/price|单价|标价|售价/i.test(field.name + ' ' + field.comment))) {
        if ([...seeds.values()].some(seed => seed.kind === 'metrics' && seed.dependencies.some(ref => ref.table === table.id && ref.entry === 'field:' + field.name) && /SUM\s*\(/i.test(seed.content.values || ''))) continue;
        put({ kind: 'metrics', owner: table, name: (field.comment || field.name) + '合计', origin: '采集结构', content: { meaning: '汇总本表中的' + (field.comment || field.name) + '。', values: 'SUM(' + qualified + '.' + field.name + ')', usage: '单位及业务时间引用依赖字段，不能混合不同计量单位。' }, dependencies: [{ table: table.id, entry: 'overview' }, { table: table.id, entry: 'field:' + field.name }], pending: { values: '本次合计采用哪些有效记录、去重或调整规则？请补全计算范围。' }, references: [], identities: [], reason: '数值字段及注释提供汇总线索，SUM 仅为候选；统计范围和必要调整尚无明确依据。' });
      }
    }
    const created = [], skipped = [], groups = new Map();
    for (const seed of seeds.values()) {
      const owner = GovernanceDemo.get(seed.owner);
      const existing = [...GovernanceDemo.records.values()].filter(row => row.table.source === record.table.source).flatMap(row => row.items.map(entry => ({ record: row, entry }))).find(({ record: row, entry }) => entry.kind === seed.kind && !entry.retired && (entry.discovery?.key === seed.key || row === owner && (entry.discovery?.sourceName === seed.name || entry.name === seed.name) && (entry.discovery || conditionKey(entry.content.meaning) === conditionKey(seed.content.meaning) && normalizedText(entry.content.usage) === normalizedText(seed.content.usage))));
      if (existing) { skipped.push(existing); continue; }
      const entry = { id: (seed.kind === 'relations' ? 'relation:' : 'metric:') + DataSourceDemo.nextId('AI-'), kind: seed.kind, name: seed.name, content: { meaning: '', values: '', usage: '' }, dependencies: copy(seed.dependencies), gap: '', revision: 1, review: '待确认', validity: '适用', publication: '未发布', reviewer: '', reviewedAt: '', basis: '', manualEvidence: null, references: [], ai: null, history: [], discovery: { key: seed.key, sourceName: seed.name, origin: seed.origin, snapshot: seed.owner.version, notesSignature: noteSignature(record), content: copy(seed.content), pending: copy(seed.pending), unresolved: Boolean(seed.unresolved), identities: [...new Set(seed.identities)], references: copy(seed.references), reason: seed.reason } };
      owner.items.push(entry); created.push({ record: owner, entry, references: seed.references });
      if (!groups.has(owner)) groups.set(owner, []); groups.get(owner).push(entry.id);
    }
    const beforeTasks = GovernanceDemo.tasks.length, beforeUses = DataSourceDemo.noteUses.length;
    for (const [owner, ids] of groups) {
      const error = generate(owner, kinds, actor, undefined, undefined, { entryIds: ids });
      if (error) { created.forEach(({ record: row, entry }) => row.items.splice(row.items.indexOf(entry), 1)); GovernanceDemo.tasks.splice(0, GovernanceDemo.tasks.length - beforeTasks); DataSourceDemo.noteUses.splice(0, DataSourceDemo.noteUses.length - beforeUses); return { error }; }
      const task = GovernanceDemo.tasksFor(owner)[0]; task.discovery = true; task.method = '原型候选发现'; task.discoveryStructure = copy(tables.map(table => ({ id: table.id, object: GovernanceDemo.objectName(table), snapshot: table.version, primaryKey: table.primaryKey || [], foreignKeys: table.foreignKeys || [] })));
      task.events.unshift({ action: '发现关联与指标候选（演示）', at: task.at, detail: ids.length + ' 个新候选，结构与有效业务资料共同提供线索。' });
    }
    if (!created.length) {
      // 没有新增候选也保留本次实际检查的资料修订，资料取用不依赖是否产生业务结论。
      const at = DataSourceDemo.now(), task = { id: DataSourceDemo.nextId('RW-'), source: record.table.source, table: record.table.id, object: GovernanceDemo.objectName(record.table), name: record.table.label + ' · 候选发现', actor: actor.id, at, ended: at, state: '无新候选（演示）', method: '原型候选发现', discovery: true, simulated: true, entries: [], outputs: [], notes: copy(notes), tableContext: copy(record.table.fields), sampling: { state: '未执行', reads: [] }, events: [{ action: '检查候选范围（演示）', at, detail: seeds.size + ' 个线索；已有 ' + skipped.length + ' 个条目保留原内容。' }] };
      GovernanceDemo.tasks.unshift(task);
      notes.forEach(note => DataSourceDemo.noteUses.unshift({ id: task.id, taskId: task.id, noteId: note.id, revision: note.revision, snapshot: copy(note), kind: '治理任务', name: task.name, state: task.state, actor: actor.id, at, ended: at, method: task.method, purpose: '检查当前表的关联与指标候选。', result: '本次没有新增候选，已有定义及确认记录保留。' }));
    }
    return { created, skipped, checked: seeds.size };
  }
  window.GovernancePrototype = { generate, preview, discover, discoverySignature };
})();
