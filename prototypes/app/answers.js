/* 每个回答使用当次查询的结果快照，展开明细或切换话题都以该快照为准。 */
(() => {
  'use strict';

  const formatter = new Intl.NumberFormat('zh-CN');
  const weekday = new Intl.DateTimeFormat('zh-CN', { weekday: 'short', timeZone: 'UTC' });
  const salesFields = { net: 'net', gross: 'sold', returns: 'returned' };
  const metricLabels = { net: '净销量', gross: '售出量', returns: '退货量' };

  function element(tag, className, content) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content !== undefined) node.textContent = content;
    return node;
  }

  function action(label, iconName, iconOnly = false) {
    const button = element('button', 'qa-action');
    button.type = 'button';
    button.setAttribute('aria-label', label);
    button.title = label;
    button.append(window.WorkbenchIcon(iconName, 15));
    if (!iconOnly) button.append(document.createTextNode(label));
    return button;
  }

  function sum(rows, key) {
    return rows.reduce((total, row) => total + row[key], 0);
  }

  function displayValue(value) {
    return typeof value === 'number' ? formatter.format(value) : value;
  }

  function shortDate(date) {
    return date.slice(5).replace('-', '/');
  }

  function dateHeading(date) {
    const heading = element('th');
    heading.scope = 'row';
    heading.append(shortDate(date), element('small', 'qa-weekday', weekday.format(new Date(date + 'T12:00:00Z'))));
    return heading;
  }

  function valuesFor(result) {
    if (result.type === 'business') return result;
    if (result.type === 'inventory') {
      return {
        headings: ['商品', '库存（' + result.unit + '）'],
        rows: result.rows.map(row => [row.name, row.stock]),
        totals: ['合计', sum(result.rows, 'stock')]
      };
    }
    return {
      headings: ['日期', '售出（' + result.unit + '）', '退货（' + result.unit + '）', '净售出（' + result.unit + '）'],
      rows: result.rows.map(row => [row.date, row.sold, row.returned, row.net]),
      totals: ['合计', sum(result.rows, 'sold'), sum(result.rows, 'returned'), sum(result.rows, 'net')]
    };
  }

  function tableFor(result) {
    const { headings, rows, totals } = valuesFor(result);
    const table = element('table', 'qa-table');
    const caption = element('caption', 'qa-sr', result.scope + ' ' + result.period + ' 明细');
    table.append(caption);
    const header = table.createTHead().insertRow();
    for (const text of headings) {
      const th = element('th', '', text);
      th.scope = 'col';
      header.append(th);
    }
    const body = table.createTBody();
    const isSales = result.type === 'sales';
    const metricColumn = isSales ? ['date', 'sold', 'returned', 'net'].indexOf(salesFields[result.metric]) : -1;
    const peak = isSales ? Math.max(0, ...rows.map(row => row[metricColumn])) : 0;
    for (const row of rows) {
      const tr = body.insertRow();
      if (isSales && peak > 0 && row[metricColumn] === peak) tr.className = 'qa-peak';
      const heading = isSales ? dateHeading(row[0]) : element('th', '', row[0]);
      heading.scope = 'row';
      tr.append(heading);
      for (let i = 1; i < row.length; i += 1) {
        const cell = tr.insertCell();
        if (i === metricColumn) {
          const content = element('span', 'qa-bar-value');
          const track = element('span', 'qa-track');
          track.setAttribute('aria-hidden', 'true');
          const bar = element('i');
          bar.style.width = (peak > 0 ? Math.max(0, row[i]) * 100 / peak : 0) + '%';
          track.append(bar);
          content.append(track, element('b', '', formatter.format(row[i])));
          cell.append(content);
        } else {
          cell.textContent = displayValue(row[i]);
        }
      }
    }
    const foot = table.createTFoot().insertRow();
    const totalHeading = element('th', '', totals[0]);
    totalHeading.scope = 'row';
    foot.append(totalHeading);
    for (const value of totals.slice(1)) foot.insertCell().textContent = displayValue(value);
    return table;
  }

  // 坐标轴随实际数据取整；点、数值和日期用同一套坐标，窄屏下仍保留可读字号。
  function scale(values) {
    const min = Math.min(...values);
    const max = Math.max(...values);
    const rawStep = (max - min || Math.max(2, Math.abs(max) / 2)) / 2;
    const power = 10 ** Math.floor(Math.log10(rawStep));
    const step = Math.max(1, ([1, 2, 5, 10].find(x => x * power >= rawStep) || 10) * power);
    const bottom = Math.floor(min / step) * step;
    const top = Math.max(bottom + step, Math.ceil(max / step) * step);
    return { top, bottom, step };
  }

  function svgNode(tag, attributes) {
    const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [name, value] of Object.entries(attributes)) node.setAttribute(name, value);
    return node;
  }

  function trend(result) {
    const key = salesFields[result.metric];
    const values = result.rows.map(row => row[key]);
    const { top, bottom, step } = scale(values);
    const frame = element('div', 'qa-chart');
    frame.setAttribute('role', 'img');
    frame.setAttribute('aria-label', '每日' + metricLabels[result.metric] + '（' + result.unit + '）：' +
      result.rows.map(row => shortDate(row.date) + ' ' + formatter.format(row[key])).join('，'));
    const plot = element('div', 'qa-plot');
    for (let tick = bottom; tick <= top; tick += step) {
      const grid = element('div', 'qa-gridline');
      grid.style.bottom = (tick - bottom) * 100 / (top - bottom) + '%';
      grid.append(element('span', '', formatter.format(tick)));
      plot.append(grid);
    }
    const coordinates = values.map((value, i) => [
      100 * i / (values.length - 1), 100 * (top - value) / (top - bottom)
    ]);
    const points = coordinates.map(pair => pair.join(',')).join(' ');
    const svg = svgNode('svg', { viewBox: '0 0 100 100', preserveAspectRatio: 'none', 'aria-hidden': 'true' });
    svg.append(
      svgNode('polygon', { points: '0,100 ' + points + ' 100,100', fill: '#eff4fc' }),
      svgNode('polyline', { points, fill: 'none', stroke: '#3b72d6', 'stroke-width': 2.2,
        'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'vector-effect': 'non-scaling-stroke' })
    );
    plot.append(svg);
    const highest = Math.max(...values);
    coordinates.forEach(([x, y], i) => {
      const point = element('span', 'qa-dot' + (values[i] === highest ? ' qa-high' : ''));
      point.style.left = x + '%';
      point.style.top = y + '%';
      point.append(element('b', '', formatter.format(values[i])));
      const label = element('span', 'qa-x-label', shortDate(result.rows[i].date));
      label.style.left = x + '%';
      plot.append(point, label);
    });
    frame.append(plot);
    return frame;
  }

  function resultCard(result) {
    const card = element('section', 'qa-card');
    const sales = result.type === 'sales';
    // 只看合计时保留结论和可展开明细，逐日趋势只在实际请求了按天分组时出现。
    const showTrend = sales && result.group === 'day' && result.rows.length > 1;
    if (showTrend) {
      const top = element('div', 'qa-card-head');
      top.append(element('h3', '', '每日' + metricLabels[result.metric]),
        element('span', 'qa-period', result.period + ' · ' + result.unit));
      card.append(top, trend(result));
    }
    const details = element('details', 'qa-details');
    const summary = element('summary', 'qa-summary');
    let detailName = sales ? '每日明细' : '库存明细';
    if (result.type === 'business') detailName = result.resource === 'profit' ? '门店汇总' : '计算明细';
    summary.append(window.WorkbenchIcon('chevron-down', 14),
      element('span', 'qa-when-closed', '展开' + detailName),
      element('span', 'qa-when-open', '收起' + detailName));
    const wrap = element('div', 'qa-table-wrap');
    wrap.append(tableFor(result));
    details.append(summary, wrap);
    // 展开较靠下的回答时，把整张核账表带入对话视口，底部输入框仍可续问。
    details.addEventListener('toggle', () => {
      const viewport = details.closest('.transcript');
      if (!details.open || !viewport) return;
      const bounds = details.getBoundingClientRect();
      if (bounds.bottom > viewport.getBoundingClientRect().bottom) {
        details.scrollIntoView({ block: bounds.height > viewport.clientHeight ? 'start' : 'nearest' });
      }
    });
    const footer = element('div', 'qa-meta');
    const origin = element('span', 'qa-scope');
    origin.append(window.WorkbenchIcon('store', 13), result.scope);
    if (!showTrend) origin.append(' · ' + result.period);
    footer.append(origin, element('span', '', '查询于 ' + result.stamp + ' · 数据更新时间未知'));
    card.append(details, footer);
    return card;
  }

  // 导出只读取当前答案的明细，和同一条消息的表格使用相同列与合计。
  function exportCsv(result) {
    const { headings, rows, totals } = valuesFor(result);
    const esc = value => '"' + String(value).replace(/"/g, '""') + '"';
    const csv = [headings, ...rows, totals].map(row => row.map(esc).join(',')).join('\r\n');
    const url = URL.createObjectURL(new Blob(['\ufeff', csv], { type: 'text/csv;charset=utf-8' }));
    const link = element('a');
    link.href = url;
    link.download = result.scope.replace(/[\\/:*?"<>|]/g, '-') +
      '-' + (result.label || (result.type === 'inventory' ? '库存' : metricLabels[result.metric])) +
      '-' + result.period.replace(/[\\/:*?"<>|]/g, '-') + '.csv';
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function answerActions(reply, panel) {
    const result = reply.result;
    const bar = element('div', 'qa-actions');
    const basisButton = action('查看依据', 'layers');
    const csvButton = action('导出', 'download');
    const like = action('赞同这条回答', 'thumbs-up', true);
    const dislike = action('反馈这条回答的问题', 'thumbs-down', true);
    const basis = element('div', 'qa-basis');
    basis.hidden = true;
    basis.append(element('strong', '', '查询依据'), element('p', '', result.basis),
      element('small', '', '经营数据 · ' + result.scope + ' · ' + result.period));
    basisButton.setAttribute('aria-expanded', 'false');
    basisButton.addEventListener('click', () => {
      basis.hidden = !basis.hidden;
      basisButton.setAttribute('aria-expanded', String(!basis.hidden));
    });
    csvButton.addEventListener('click', () => exportCsv(result));

    // 评价只附属于本条回答。点踩需有非空原因，放弃填写不产生评价。
    const feedback = element('form', 'qa-feedback');
    feedback.hidden = true;
    const prompt = element('label', '', '哪里和预期不一致？');
    const reason = element('textarea');
    reason.required = true;
    reason.rows = 2;
    reason.maxLength = 500;
    reason.placeholder = '说说原因';
    prompt.append(reason);
    const commands = element('div', 'qa-feedback-buttons');
    const cancel = element('button', '', '取消');
    cancel.type = 'button';
    const commit = element('button', 'qa-primary', '提交反馈');
    commit.type = 'submit';
    commands.append(cancel, commit);
    feedback.append(prompt, commands);
    const status = element('span', 'qa-feedback-status');
    status.setAttribute('role', 'status');

    function reflect() {
      like.setAttribute('aria-pressed', String(reply.reaction?.type === 'up'));
      dislike.setAttribute('aria-pressed', String(reply.reaction?.type === 'down'));
      dislike.title = reply.reaction?.type === 'down' ? '修改反馈或撤销点踩' : '反馈这条回答的问题';
      cancel.textContent = '取消';
    }
    function closeForm() {
      feedback.hidden = true;
      dislike.setAttribute('aria-expanded', 'false');
      dislike.focus({ preventScroll: true });
    }
    like.addEventListener('click', () => {
      reply.reaction = reply.reaction?.type === 'up' ? null : { type: 'up' };
      feedback.hidden = true;
      dislike.setAttribute('aria-expanded', 'false');
      status.textContent = reply.reaction ? '已点赞' : '已撤销点赞';
      reflect();
    });
    const undo = element('button', 'qa-undo', '撤销点踩');
    undo.type = 'button';
    undo.addEventListener('click', () => {
      reply.reaction = null;
      status.textContent = '已撤销点踩';
      closeForm();
      reflect();
    });
    commands.prepend(undo);
    dislike.setAttribute('aria-expanded', 'false');
    dislike.addEventListener('click', () => {
      if (!feedback.hidden) return closeForm();
      feedback.hidden = false;
      dislike.setAttribute('aria-expanded', 'true');
      reason.value = reply.reaction?.reason || '';
      reason.setCustomValidity('');
      undo.hidden = reply.reaction?.type !== 'down';
      reason.focus();
    });
    cancel.addEventListener('click', closeForm);
    reason.addEventListener('input', () => reason.setCustomValidity(''));
    feedback.addEventListener('submit', event => {
      event.preventDefault();
      const value = reason.value.trim();
      if (!value) {
        reason.setCustomValidity('请填写点踩原因。');
        reason.reportValidity();
        return;
      }
      reply.reaction = { type: 'down', reason: value };
      status.textContent = '反馈已提交';
      closeForm();
      reflect();
    });
    reflect();
    bar.append(basisButton, csvButton, status, like, dislike);
    panel.append(bar, basis, feedback);
  }

  function render(reply) {
    const { result } = reply;
    const card = element('article', 'qa-answer');
    card.append(element('h2', 'qa-title', result.title));
    if (reply.text && reply.text !== result.title) card.append(element('p', 'qa-description', reply.text));
    card.append(resultCard(result));
    answerActions(reply, card);
    return card;
  }

  window.QueryAnswers = { render };
})();
