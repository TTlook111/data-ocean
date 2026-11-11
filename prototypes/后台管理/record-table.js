/* PC 列表共用同一表头、列预算和空态骨架，空数据仅替换表体内容。 */
(() => {
  'use strict';

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);
    return node;
  }

  function appendContent(target, content) {
    if (Array.isArray(content)) {
      for (const part of content) appendContent(target, part);
    } else if (content !== undefined && content !== null) {
      if (content instanceof Node) target.append(content);
      else target.append(document.createTextNode(String(content)));
    }
  }

  function render({ columns, items = [], row, empty, caption = '查询结果', minWidth, bodyId, headId, tableId }) {
    if (!Array.isArray(columns) || !columns.length) throw new TypeError('表格必须定义列。');
    const scroll = element('div', 'rt-scroll');
    const table = element('table', 'rt-table');
    if (tableId) table.id = tableId;
    if (minWidth) table.style.minWidth = typeof minWidth === 'number' ? minWidth + 'px' : minWidth;
    table.append(element('caption', 'rt-caption', caption));
    const colgroup = element('colgroup');
    const head = element('thead');
    const headings = element('tr');
    const numeric = columns.every(column => !column.width || typeof column.width === 'number');
    const baseTotal = columns.reduce((sum,column)=>sum+(typeof column.width==='number'?column.width:280),0);
    const weight = column => column.key==='actions'?0:(column.grow ?? (['state','status','count','members'].includes(column.key)?1:2));
    const weightTotal = columns.reduce((sum,column)=>sum+weight(column),0);
    const columnNodes = [];
    if(numeric&&typeof minWidth!=='string')table.style.minWidth=Math.max(minWidth||0,baseTotal)+'px';
    if (headId) headings.id = headId;
    for (const column of columns) {
      const col = element('col');
      const width = column.width || 280;
      col.style.width = typeof width === 'number' ? width + 'px' : width;
      columnNodes.push(col);
      col.dataset.column = column.key;
      colgroup.append(col);
      const th = element('th', '', column.label);
      th.scope = 'col';
      th.dataset.column = column.key;
      th.title = column.label;
      if (column.fixed === 'right' || column.key === 'actions') th.classList.add('rt-fixed-right');
      headings.append(th);
    }
    head.append(headings);
    const body = element('tbody');
    if (bodyId) body.id = bodyId;
    if (items.length) {
      for (const item of items) {
        const record = element('tr');
        if (item.id !== undefined && item.id !== null) {
          record.dataset.recordId = String(item.id);
          record.dataset.caseId = String(item.id);
        }
        const values = row(item);
        for (const [index, column] of columns.entries()) {
          const td = element('td');
          td.dataset.column = column.key;
          if (column.fixed === 'right' || column.key === 'actions') td.classList.add('rt-fixed-right');
          appendContent(td, values[index]);
          record.append(td);
        }
        body.append(record);
      }
    } else {
      table.classList.add('is-empty');
      const emptyRow = element('tr', 'rt-empty-row');
      const cell = element('td', 'rt-empty-cell');
      cell.colSpan = columns.length;
      const content = element('div', 'rt-empty');
      appendContent(content, empty || element('p', '', '暂无记录'));
      // 空态按可见表体居中，横向查看列时不随整张表偏到一侧。
      scroll.addEventListener('scroll', () => {
        content.style.left = scroll.scrollLeft + 'px';
        content.style.right = -scroll.scrollLeft + 'px';
      }, { passive: true });
      cell.append(content);
      emptyRow.append(cell);
      body.append(emptyRow);
    }
    table.append(colgroup, head, body);
    scroll.append(table);
    if(numeric&&weightTotal){
      // 按实际可用宽度分配信息列，扣除滚动条；操作列宽度始终不参与拉伸。
      const observer=new ResizeObserver(()=>{
        if(!scroll.isConnected){observer.disconnect();return;}
        const available=Math.max(scroll.clientWidth,baseTotal,typeof minWidth==='number'?minWidth:0),extra=available-baseTotal;
        columns.forEach((column,index)=>{columnNodes[index].style.width=((column.width||280)+extra*weight(column)/weightTotal)+'px';});
      });
      observer.observe(scroll);
    }
    return scroll;
  }

  window.RecordTable = { render };
})();
