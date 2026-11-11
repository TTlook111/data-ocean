/* 授权管理在同一任务页查看来源与撤销，不按动作拆分页面。 */
(() => {
  'use strict';
  const { users, dataSources } = PeopleDemo, demo = GrantDemo;
  const queryFor = () => ({ person: '', approver: '', source: '', origin: '', status: 'all', fromDate: '', toDate: '', page: 1, pageSize: 10 });
  const query = queryFor();
  function render(main, ui) {
    const { el, button, status, textCell, pair, pageHeading, modal, openForm, currentUser, DataAccessTable, viewRequest, openDepartment } = ui;
    const title = '授权管理';
    if (!demo.canView(currentUser)) { const denied = el('section','planned-page'); denied.append(el('p','','当前账号没有查看此页面的权限。')); main.replaceChildren(pageHeading(title),denied); return; }
    const rows = demo.records(currentUser,query), filtered = Boolean(query.person || query.approver || query.source || query.origin || query.status !== 'all' || query.fromDate || query.toDate);
    query.page = Math.max(1,Math.min(query.page,Math.ceil(rows.length/query.pageSize)));
    const reset = () => { Object.assign(query,queryFor(),{pageSize:query.pageSize}); render(main,ui); main.querySelector('input[name="person"]')?.focus(); };
    const filters = RecordControls.filters({ value:query, fields:[{key:'person',label:'被授权人',placeholder:'被授权人姓名或登录名'},{key:'source',label:'数据源',options:dataSources.map(source=>({value:source.id,label:source.name}))},{key:'origin',label:'来源名称或编号'},{key:'approver',label:'批准人',placeholder:'批准人姓名或登录名'}],
      dateLabel:'生效日期', statuses:[{value:'all',label:'全部状态'},...['已生效','已撤销','来源停用','待复核','来源已结束'].map(value=>({value,label:value}))],
      onApply:next=>{Object.assign(query,next);render(main,ui);main.querySelector('input[name="person"]')?.focus();},onReset:reset });
    filters.classList.add('grant-filters');
    const panel=el('section','record-panel grant-panel'),empty=el('div','empty-state');
    empty.append(WorkbenchIcon('file-text'),el('p','',filtered?'没有匹配的授权记录':'暂无授权记录'));
    if(filtered)empty.append(button('清空筛选',reset));
    panel.append(RecordTable.render({caption:title,minWidth:1120,columns:[{key:'person',label:'被授权人',width:160,grow:2},{key:'scope',label:'数据源与规则',width:220,grow:4},{key:'origin',label:'授权来源',width:172,grow:2},{key:'approver',label:'批准人',width:144,grow:2},{key:'state',label:'状态',width:104,grow:1},{key:'time',label:'生效时间',width:166,grow:2},{key:'actions',label:'操作',width:144,fixed:'right'}],
      items:rows.slice((query.page-1)*query.pageSize,query.page*query.pageSize),empty,
      row:record=>{
        const user=users.find(user=>user.id===record.user),person=el('div'),scope=el('div'),origin=el('div'),approver=el('div'),actions=el('div','row-actions');
        person.append(textCell(user.name),el('small','cell-sub',user.login)); if(user.status!=='正常')person.append(status(user.status));
        const name=button(demo.sourceName(record.source),trigger=>detail(record.id,trigger),'cell-title','grant-'+record.id); name.title=demo.sourceName(record.source);
        scope.append(name,textCell(record.rules.map(rule=>rule.name).join('、'))); scope.lastChild.classList.add('grant-rule-names');
        origin.append(textCell(record.typeLabel),el('small','cell-sub',record.origin));
        approver.append(textCell(record.request ? record.part.reviewedBy || '未记录' : '—'));
        if(record.part?.reviewedLogin)approver.append(el('small','cell-sub',record.part.reviewedLogin));
        const view=button('详情',trigger=>detail(record.id,trigger),'do-action','view-'+record.id);view.setAttribute('aria-label','查看'+user.name+'的'+demo.sourceName(record.source)+'授权详情，'+record.origin);actions.append(view);
        if(!demo.blocked(record,currentUser)){const action=button('撤销',trigger=>revoke(record.id,trigger,false),'do-action is-danger','revoke-'+record.id);action.setAttribute('aria-label','撤销'+user.name+'的'+demo.sourceName(record.source)+'授权，'+record.origin);actions.append(action);}
        return[person,scope,origin,approver,status(record.state),textCell(record.activatedAt),actions];
      }
    }),RecordControls.pager({total:rows.length,...query},page=>{query.page=page;render(main,ui);},size=>{query.pageSize=size;query.page=1;render(main,ui);}));
    main.replaceChildren(pageHeading(title),filters,panel);
    function freshTrigger(id) { return main.querySelector('[data-focus="view-'+id+'"]')||main.querySelector('input[name="person"]'); }
    function detail(id, trigger, result = false) {
      const record=demo.get(id,currentUser); if(!record)return;
      const node=modal(result?'撤销结果':'授权详情',trigger,true),body=el('div','dialog-body'),fields=el('dl','detail-fields'),user=users.find(user=>user.id===record.user);
      const joint=record.request?.mode==='joint',scope=joint?record.request.approvalItems.filter(part=>['已生效','已撤销'].includes(part.status)):[{source:record.source,rules:record.rules}];
      const heading=el('div','grant-detail-heading');heading.append(el('h2','',joint?'联合授权':demo.sourceName(record.source)),status(record.state));
      fields.append(pair(record.request?'申请人（被授权人）':'被授权人',demo.identity(record.user)),pair('账号状态',user.status),pair('来源类型',record.typeLabel),pair('生效时间',record.activatedAt),pair(record.type==='department'?'来源部门':'申请编号',record.origin));
      if(record.type==='department')fields.append(pair('部门编号',record.originCode));
      if(record.request){fields.append(pair('提交时间',record.request.submitted));if(record.request.approvalItems.length>1)fields.append(pair('办理方式',record.request.mode==='joint'?'联合办理':'独立办理'));}
      const table=el('div','prototype-ui');PrototypeUI.mount(table,{render:()=>Vue.h(DataAccessTable,{access:scope})});
      body.append(heading,fields,el('h3','','数据源与输出限制规则'),table);
      if(record.request){
        const approvals=el('dl','detail-fields');
        const parts=joint?scope:[record.part];
        for(const part of parts)approvals.append(pair(joint?demo.sourceName(part.source)+'批准人':'批准人',AccessRequestDemo.reviewIdentity(part)),pair('批准时间',part.reviewedAt||'未记录'));
        body.append(el('h3','','批准记录'),approvals);
      }
      if(record.part?.revokedAt){const revoked=el('dl','detail-fields');revoked.append(pair('撤销人',record.part.revokedBy),pair('撤销时间',record.part.revokedAt));body.append(el('h3','','撤销记录'),revoked,el('p','grant-reason',record.part.revokedReason));}
      if(record.endedAt)body.append(el('h3','','来源结束时间'),el('p','',record.endedAt));
      const footer=el('footer','dialog-footer');footer.append(button('关闭',()=>node.close()));
      if(record.request)footer.append(button('查看申请',()=>{node.close();const requestDialog=viewRequest(record.request,freshTrigger(id));requestDialog?.addEventListener('close',()=>detail(id,freshTrigger(id)),{once:true});}));
      else if(SourceApprovalDemo.hasAbility(currentUser,'departments.view'))footer.append(button('查看部门配置',()=>{node.close();openDepartment(record.department);}));
      if(!demo.blocked(record,currentUser))footer.append(button('撤销授权',()=>{node.close();revoke(id,trigger,true);},'admin-button is-danger'));
      node.append(body,footer);node.showModal();
    }
    function revoke(id,trigger,returnToDetail) {
      const impact=demo.impact(id,currentUser);if(!impact)return;
      const record=impact.row,revision=record.request.revision;let saved=false;
      const dialog=openForm({title:record.request.mode==='joint'?'撤销联合授权':'撤销授权',trigger,danger:true,
        value:{person:demo.identity(record.user),origin:record.origin,scope:impact.targets.map(part=>({source:part.source,rules:structuredClone(part.rules)})),reason:''},
        fields:[{key:'person',label:'授权对象',readonly:true},{key:'origin',label:'授权来源',readonly:true},{key:'scope',label:'本次撤销范围',type:'access-summary',full:true},{key:'reason',label:'撤销原因',type:'textarea',required:true,full:true}],
        previewTitle:'核对撤销影响',previewLabel:'核对撤销',saveLabel:'确认撤销',summary:true,previewContentLabel:'核对内容',
        preview:data=>[{label:'授权对象',after:demo.identity(record.user)},{label:'授权来源',after:record.origin},...impact.sources.flatMap(source=>[
          {label:source.name,after:'结束本笔来源；其余已生效来源 '+source.remaining+' 笔'},
          ...(source.remaining&&source.beforeRules.some(rule=>!source.afterRules.some(after=>after.displayKey===rule.displayKey))?[{label:source.name+'限制变化',after:'以下限制将不再适用：'+source.beforeRules.filter(rule=>!source.afterRules.some(after=>after.displayKey===rule.displayKey)).map(rule=>rule.name).join('、')}]:[]),
          ...(JSON.stringify(source.beforeRules.map(rule=>rule.displayKey).sort())!==JSON.stringify(source.afterRules.map(rule=>rule.displayKey).sort())?[{label:source.name+'撤销后限制',after:source.afterRules.map(rule=>rule.name+'：'+rule.content).join('\n')||'无其他已生效来源'}]:[])
        ]),{label:'撤销原因',after:data.reason.trim()},{label:'确认后状态',after:'已撤销'}],
        save:data=>demo.revoke(id,data.reason,currentUser,revision),success:'授权已撤销',onSaved:()=>{saved=true;}
      });
      dialog.addEventListener('close',()=>{if(saved||returnToDetail)detail(id,freshTrigger(id),saved);else freshTrigger(id)?.focus({preventScroll:true});},{once:true});
    }
  }
  function openOrigin(id){Object.assign(query,queryFor(),{origin:id,pageSize:query.pageSize});location.hash='grants';}
  window.GrantsPage={render,openOrigin};
})();
