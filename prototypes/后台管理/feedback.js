/* 问题队列和单事项办理复用现有表格、查询、核对表单及后台外壳。 */
(() => {
  'use strict';
  const model=FeedbackDemo, tabs=new Map(), positions=new Map(),parentPositions=new Map();
  const defaults=()=>({keyword:'',originType:'',sourceId:'',owner:'',status:'all',fromDate:'',toDate:'',page:1,pageSize:10,bucket:'all'});
  const query=defaults();let main,ui,actor,current='',deliveryMode='success',lastIssue='',reviewOpen=false;
  const choices=values=>values.map(value=>({value,label:value}));
  const name=id=>PeopleDemo.users.find(user=>user.id===id)?.name||'待分派';
  const time=value=>new Date(value).toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'}).slice(0,16);
  const allowed=(row,ability)=>model.can(actor,row,ability);
  const canWork=row=>allowed(row,'process')&&row.owner===actor.id;
  const el=(...args)=>ui.el(...args);
  const button=(label,action,primary=false,key)=>ui.button(label,action,primary?'admin-button is-primary':'admin-button',key);
  const tag=status=>el('span','status-tag'+(status==='待分派'||status==='待补充'||status==='待验证'?' is-pending':status==='其他结案'||status==='已合并'?' is-disabled':''),status);
  const result=message=>typeof message==='string'?message:undefined;
  function remember(){if(main&&current){positions.set(current,main.querySelector('.fb-body')?.scrollTop||0);parentPositions.set(current,main.scrollTop);}}
  function navigate(id=''){if(main){positions.set(current,main.querySelector('.fb-body')?.scrollTop||0);parentPositions.set(current,main.scrollTop);}location.hash='feedback'+(id?'/'+encodeURIComponent(id):'');}
  function refresh(){remember();render(main,ui,current);}
  function form(row,config){
    const revision=row.revision,save=config.save,initial=JSON.stringify(config.value);
    remember();
    return ui.openForm({previewTitle:'核对本次处理',previewContentLabel:'核对结果',...config,protectChanges:true,hasChanges:config.hasChanges||(value=>JSON.stringify(value)!==initial),
      save:data=>result(save(data,revision))});
  }
  function assign(row,trigger){form(row,{title:'分派事项 · '+row.id,trigger,value:{direction:model.present(row,actor).direction,owner:row.owner,reason:''},saveLabel:'分派',
    fields:[{key:'direction',label:'初步方向',required:true,options:choices(['待核实','业务知识','技术问题','源数据','口径解释','权限限制'])},
      {key:'owner',label:'负责人',required:true,options:model.eligible().map(user=>({value:user.id,label:user.name+'（'+user.login+'）'}))},
      {key:'reason',label:'分派依据',type:'textarea',required:true,full:true}],
    preview:data=>[{label:'事项',after:model.displayTitle(actor,row)},{label:'负责人',after:name(data.owner)},{label:'初步方向',after:data.direction},{label:'分派依据',after:data.reason}],summary:true,previewLabel:'核对分派',save:(data,rev)=>model.assign(row,data,actor,rev)});}
  function investigate(row,trigger){const view=model.present(row,actor);form(row,{title:'记录核查 · '+row.id,trigger,value:{rootCause:view.rootCause,basis:view.basis},note:view.restricted?'已有业务明细受限，请记录本次获准的核查依据。':'',saveLabel:'保存核查',
    fields:[{key:'rootCause',label:'核实根因',type:'textarea',placeholder:'原因未明时可留空',full:true},{key:'basis',label:'核查依据',type:'textarea',required:true,full:true}],
    save:(data,rev)=>model.investigate(row,data,actor,rev)});}
  function supplement(row,trigger){form(row,{title:'请求用户补充 · '+row.id,trigger,value:{userId:model.origins(row)[0]?.userId||'',question:''},saveLabel:'请求补充',
    fields:[{key:'userId',label:'原提问人',required:true,options:[...new Set(model.origins(row).map(origin=>origin.userId))].map(id=>({value:id,label:name(id)}))},
      {key:'question',label:'需要补充的业务信息',type:'textarea',required:true,full:true}],save:(data,rev)=>model.requestSupplement(row,data,actor,rev)});}
  function addTask(row,trigger,kind){
    const business=kind==='业务知识';
    const value={kind,title:'',owner:row.owner||actor.id,binding:'',impact:'查询与安全'};
    form(row,{title:business?'新增治理任务':kind==='技术修复'?'安排技术处理':'跟进数据修正',trigger,value,saveLabel:'新增任务',note:business?'':kind==='技术修复'?'选择本次任务负责人，实际修复在对应研发或运维流程完成。':'选择跟进人员，联系源系统负责人核实并修正数据。',
      fields:[
        {key:'title',label:'处理任务',required:true},{key:'owner',label:'任务负责人',required:true,options:model.eligible(business?'业务知识':undefined).map(user=>({value:user.id,label:user.name}))},
        ...(kind==='技术修复'?[{key:'impact',label:'技术影响',required:true,options:choices(['展示表达','连接恢复','查询与安全'])}]:[]),
        ...(business?[{key:'binding',label:'受影响治理条目',required:true,full:true,options:model.bindingOptions(row.sourceId)}]:[])],save:(data,rev)=>model.addTask(row,data,actor,rev)});
  }
  function progressForm(row,task,trigger){
    const sourceData=task.kind==='源数据修正',safety=!sourceData&&(task.impact||'查询与安全')==='查询与安全';
    const view=model.present(row,actor),record=task.result&&!task.result.restricted?task.result:null,follow=task.followup&&!task.followup.restricted?task.followup:null;
    const completed=value=>value.stage==='ready',following=value=>value.stage!=='ready';
    const value={context:view.restricted?'原问题内容受限 · '+row.id:view.origins[0]?.question||view.title,
      stage:task.result?'ready':task.followup?.stage||'working',note:follow?.note||'',summary:record?.summary||'',check:record?.check||'',performer:record?.performer||follow?.performer||'',reference:record?.reference||'',
      effectiveConfirmed:record?.format===2&&record.effectiveConfirmed===true,regression:record?.regression||'',regressionPassed:record?.format===2&&record.regressionPassed===true};
    // 只比较当前阶段的输入，隐藏字段不触发空保存或重复修订。
    const comparable=value=>JSON.stringify(value.stage!=='ready'?{stage:value.stage,note:value.note.trim()}:{stage:value.stage,summary:value.summary.trim(),check:value.check.trim(),reference:value.reference.trim(),effectiveConfirmed:value.effectiveConfirmed,
      ...(sourceData?{performer:value.performer.trim()}:{}),...(safety?{regression:value.regression.trim(),regressionPassed:value.regressionPassed}:{})});
    const original=comparable(value);
    form(row,{title:(sourceData?'记录数据修正':'记录技术处理')+' · '+task.id,trigger,value,hasChanges:data=>comparable(data)!==original,saveLabel:'保存处理记录',success:'处理记录已保存',
      note:(sourceData?'在源业务系统完成修正，此处记录跟进和复查结果。':'在研发或运维流程完成修复，此处记录进展和复查结果。')+(task.result?'改为继续跟进会撤回待验证结果，历史记录保留。':''),
      fields:[{key:'context',label:'关联问题',readonly:true,full:true},
        {key:'stage',label:'目前进展',required:true,full:true,options:[{value:'working',label:sourceData?'正在核对、联系负责人':'仍在排查或修复'},{value:'waiting',label:sourceData?'等待对方修正或数据更新':'等待修复或配置生效'},{value:'ready',label:'已处理好，提交验收'}]},
        {key:'note',label:'已做的工作和下一步',type:'textarea',required:true,full:true,when:following,placeholder:sourceData?'例如：已联系退货记录负责人核对日期，下一步等待修正后重新查询。':'例如：已核对查询日志，正在排查跨周日期条件，下一步修正并测试。'},
        {key:'performer',label:'实际修正人或负责团队',required:true,full:true,when:completedOnlySource,placeholder:'例如：售后系统维护团队'},
        {key:'summary',label:sourceData?'改正了哪些记录、做了什么修改':'做了什么修复',type:'textarea',required:true,full:true,when:completed,placeholder:sourceData?'说明单据、时间或筛选范围，以及实际修改内容。':'例如：按已确认口径调整退货查询日期条件，避免跨周记录归属错误。'},
        {key:'check',label:'怎样复查、实际结果如何',type:'textarea',required:true,full:true,when:completed,placeholder:sourceData?'例如：用已核实的退货记录重新查询，与源系统修正结果逐项核对。':'说明检查位置、问题条件和实际结果；写清如何核对本次已生效修复。'},
        {key:'reference',label:'相关处理记录',full:true,when:completed,placeholder:'已有工单、发布记录、批次或链接时填写'},
        {key:'effectiveConfirmed',label:'生效确认',type:'checkbox',required:true,full:true,when:completed,validationMessage:'请确认修正已生效；尚未生效可先保存跟进进展。',checkLabel:sourceData?'已确认源系统修正及必要同步已生效':'已确认本次修复已发布，或恢复配置已生效'},
        {key:'regression',label:'相关场景测试结果',type:'textarea',required:true,full:true,when:completedWithSafety,help:'此任务影响查询、检索或权限，需核对原问题及本次变更涉及的受限场景。',placeholder:'记录测试条件、实际结果及可回看的测试记录。'},
        {key:'regressionPassed',label:'测试确认',type:'checkbox',required:true,full:true,when:completedWithSafety,validationMessage:'请确认本次相关场景测试已通过。',checkLabel:'已核对本次变更涉及的原问题和受限场景，测试均通过'}],
      save:(data,rev)=>model.handling(row,task.id,data,actor,rev)});
    function completedOnlySource(value){return sourceData&&completed(value);}
    function completedWithSafety(value){return safety&&completed(value);}
  }
  function resultFields(task,value){
    if(value.format===2)return fields([['修正说明',value.summary],...(task.kind==='源数据修正'?[['实际修正人或团队',value.performer]]:[]),['复查方式与结果',value.check],['生效确认',value.effectiveConfirmed?'已确认':'未确认'],...(value.reference?[['相关处理记录',value.reference]]:[]),...(value.regression?[['相关场景测试结果',value.regression],['测试确认',value.regressionPassed?'已通过':'未通过']]:[])]);
    return fields([['处理结果',value.summary],['复现方式',value.reproduction],['期望行为',value.expected],['数据依据',value.dataBasis],['修复 / 发布引用',value.releaseId||value.reference],['生效对象 / 版本',value.effectiveVersion||'见关联发布']]);
  }
  function handling(row,task,trigger){
    if(task.kind!=='业务知识')return progressForm(row,task,trigger);
    const origin=model.present(row,actor).origins[0],known=origin?.evidence||{};
    const value={summary:'',reproduction:'',expected:known.expected||'',dataBasis:'',releaseId:'',coverage:'',...(!task.result?.restricted?task.result:{})};
    const releases=window.KnowledgeWorkspace?[...KnowledgeWorkspace.tasks.values()].filter(item=>item.source===row.sourceId&&item.kind!=='恢复验证'):[];
    form(row,{title:'处理结果 · '+task.id,trigger,value,note:task.result?.restricted?'已有结果明细受限，请记录本次获准的处理依据。':'',saveLabel:'提交处理结果',fields:[
      {key:'summary',label:'处理结果',type:'textarea',required:true,full:true},
      {key:'reproduction',label:'复现方式',type:'textarea',required:true,full:true},
      {key:'expected',label:'期望行为',type:'textarea',required:true,full:true},{key:'dataBasis',label:'验证数据依据',type:'textarea',required:true,full:true},
      {key:'releaseId',label:'复用或新建的实际发布任务',required:true,options:releases.map(item=>({value:item.id,label:item.id+' · '+item.stage})),full:true},{key:'coverage',label:'原问题与修复覆盖依据',type:'textarea',required:true,full:true}],
      save:(data,rev)=>model.handling(row,task.id,data,actor,rev)});
  }
  function reassignTask(row,task,trigger){form(row,{title:'重新分派 · '+task.id,trigger,value:{owner:task.owner,reason:''},saveLabel:'分派',
    fields:[{key:'owner',label:'任务负责人',required:true,options:model.eligible(task.kind).map(user=>({value:user.id,label:user.name}))},{key:'reason',label:'调整依据',type:'textarea',required:true,full:true}],
    save:(data,rev)=>model.reassignTask(row,task.id,data,actor,rev)});}
  function verify(row,trigger){form(row,{title:'登记人工验收结果 · '+row.id,trigger,value:{decision:'',actual:'',basis:'',testUser:model.origins(row)[0]?.userId||actor.id},saveLabel:'记录复验',success:'复验结论已记录',note:'按所选身份核对源授权及输出规则，实际数据与边界范围写入验收依据。',fields:[
    {key:'testUser',label:'测试身份',required:true,options:PeopleDemo.users.filter(user=>user.status==='正常').map(user=>({value:user.id,label:user.name+' · '+user.login})),full:true},
    {key:'decision',label:'人工验收结论',required:true,options:choices(['通过','不通过'])},{key:'actual',label:'实际结果',type:'textarea',required:true,full:true},
    {key:'basis',label:'复验依据及范围',type:'textarea',required:true,full:true}],save:(data,rev)=>model.verify(row,data,actor,rev)});}
  function close(row,trigger,solved){form(row,{title:solved?'验证结案并确认回告':'说明并结案',trigger,
    value:{status:solved?'已解决':'其他结案',reason:'',conclusion:'',basis:''},saveLabel:'结案并确认回告',summary:true,previewLabel:'核对结案与回告',
    note:!solved&&model.tasksFor(row).some(task=>!task.result)?'未完成的处理任务将随本事项停止，不计为已解决。':'',
    fields:[...(!solved?[{key:'reason',label:'结案原因',required:true,options:choices(['未复现','按现有口径确认无误','取消','其他'])}]:[]),
      {key:'basis',label:'结案依据',type:'textarea',required:true,full:true},{key:'conclusion',label:'内部处理结论',type:'textarea',required:true,full:true}],
    preview:data=>[{label:'事项状态',after:data.status},{label:'结案依据',after:data.basis},{label:'回告模板',after:model.receiptText(data.status,data.reason)},{label:'接收人',after:[...new Set(model.origins(row).map(origin=>name(origin.userId)))].join('、')||'无原提问人'}],
    success:'结案与回告状态已更新',save:(data,rev)=>{
      const error=model.close(row,data,actor,rev);if(error)return error;
      // 负责人确认结论后演示产品内投递；失败或未知结果仍独立保留，不回退结案。
      row.notifications.filter(item=>item.cycle===row.cycle&&item.status==='待提示').forEach(item=>model.deliver(row,item.key,actor,deliveryMode));tabs.set(row.id,'history');
    }});}
  function disposition(row,task,trigger){
    const restore=!model.activeTask(task);
    form(row,{title:restore?'恢复任务 · '+task.id:'退出任务 · '+task.id,trigger,value:{action:restore?'恢复':'无需变更',reason:'',covered:false},saveLabel:restore?'恢复办理':'确认退出',
      note:restore?'恢复后重新核对任务范围与验收。':'退出后保留任务与依据。仍有未覆盖影响时须保留必要任务跟进。',
      fields:[...(!restore?[{key:'action',label:'退出类型',required:true,options:choices(['无需变更','已取消'])}]:[]),{key:'reason',label:'依据与影响处理',type:'textarea',required:true,full:true},...(!restore?[{key:'covered',label:'范围确认',type:'checkbox',required:true,checkLabel:'已核对无需修复，或剩余必要任务可覆盖全部影响',full:true}]:[])],
      save:(data,rev)=>model.disposition(row,task.id,data,actor,rev)});
  }
  function reopen(row,trigger){form(row,{title:'凭新证据重开 · '+row.id,trigger,value:{reason:''},saveLabel:'重开',fields:[{key:'reason',label:'新证据或复发依据',type:'textarea',required:true,full:true}],save:(data,rev)=>model.reopen(row,data.reason,actor,rev)});}
  function merge(row,trigger){
    const targets=model.list(actor).filter(item=>item.id!==row.id&&item.sourceId===row.sourceId&&!model.closed(item));
    const versions=Object.fromEntries(targets.map(item=>[item.id,item.revision]));
    form(row,{title:'合并重复事项 · '+row.id,trigger,value:{target:'',reason:''},saveLabel:'合并',summary:true,previewLabel:'核对合并范围',
      note:'原反馈与回答引用保留在主事项，合并不代表解决。',fields:[{key:'target',label:'主事项',required:true,full:true,options:targets.map(item=>({value:item.id,label:item.id+' · '+model.displayTitle(actor,item)}))},
        {key:'reason',label:'相同根因与处理范围的依据',type:'textarea',required:true,full:true}],
      preview:data=>[{label:'合并事项',after:row.id+' · '+model.displayTitle(actor,row)},{label:'主事项',after:data.target+' · '+model.displayTitle(actor,model.get(data.target))},{label:'合并依据',after:data.reason}],
      save:(data,rev)=>model.merge(row,model.get(data.target),{reason:data.reason,targetRevision:versions[data.target]},actor,rev)});
  }
  function unmerge(row,trigger){form(row,{title:'纠正合并 · '+row.id,trigger,value:{reason:''},saveLabel:'解除合并',fields:[{key:'reason',label:'纠正依据',type:'textarea',required:true,full:true}],save:(data,rev)=>model.unmerge(row,data.reason,actor,rev)});}
  function fields(entries){const node=el('dl','fb-fields');entries.forEach(([label,value])=>node.append(ui.pair(label,value)));return node;}
  function link(label,href){const node=el('a','do-action is-primary',label);node.href=href;return node;}
  function section(title){const node=el('section','fb-section');node.append(el('h2','',title));return node;}
  function list(){
    ui.setTitle('问题队列与处理');
    const statusOptions=query.bucket==='closed'?model.statuses.filter(status=>model.closed({status})):['mine','owned'].includes(query.bucket)?model.statuses.filter(status=>!model.closed({status})&&(query.bucket!=='mine'||status!=='待补充')):model.statuses;
    const filter=RecordControls.filters({value:query,includeStatus:false,advancedKeys:['originType','owner','dates'],fields:[
      {key:'keyword',label:'问题摘要或编号'},
      {key:'sourceId',label:'数据源',options:PeopleDemo.dataSources.map(source=>({value:source.id,label:source.name}))},
      {key:'status',label:'事项状态',options:[{value:'all',label:'全部状态'},...choices(statusOptions)]},{key:'originType',label:'来源',options:choices(['用户点踩','问数知识缺口','技术失败','人工巡检'])},{key:'owner',label:'负责人姓名或登录名'}],dateLabel:'提出日期',
      onApply:next=>{Object.assign(query,next);parentPositions.delete('');refresh();},onReset:()=>{Object.assign(query,defaults(),{pageSize:query.pageSize});parentPositions.delete('');refresh();}});
    let rows=model.list(actor,query);if(query.bucket==='open')rows=rows.filter(row=>!model.closed(row));if(query.bucket==='closed')rows=rows.filter(row=>model.closed(row));
    query.page=Math.max(1,Math.min(query.page,Math.ceil(rows.length/query.pageSize)));
    const panel=el('section','record-panel fb-list'),toolbar=el('div','fb-list-toolbar'),tabs=el('div','prototype-ui');
    PrototypeUI.mount(tabs,{data:()=>({active:query.bucket}),methods:{change(value){query.bucket=value;query.page=1;if(query.status!=='all'&&((value==='closed'&&!model.closed({status:query.status}))||(['mine','owned'].includes(value)&&model.closed({status:query.status}))||(value==='mine'&&query.status==='待补充')))query.status='all';refresh();}},template:'<el-tabs v-model="active" @tab-change="change"><el-tab-pane label="待我处理" name="mine"></el-tab-pane><el-tab-pane label="我负责的" name="owned"></el-tab-pane><el-tab-pane label="全部事项" name="all"></el-tab-pane><el-tab-pane label="结案回看" name="closed"></el-tab-pane></el-tabs>'});toolbar.append(tabs);panel.append(toolbar);
    const empty=el('div','empty-state');empty.append(WorkbenchIcon('search'),el('p','','没有匹配的问题事项'));
    panel.append(RecordTable.render({caption:'问题队列',minWidth:1120,columns:[{key:'title',label:'问题 / 编号',width:230,grow:3},{key:'origin',label:'来源',width:104},{key:'source',label:'数据源',width:128},{key:'status',label:'状态',width:96},{key:'owner',label:'负责人',width:104},{key:'next',label:'下一步 / 最近更新',width:168},{key:'at',label:'提出时间',width:148},{key:'actions',label:'操作',width:100,fixed:'right'}],
      items:rows.slice((query.page-1)*query.pageSize,query.page*query.pageSize),empty,row:row=>{
        const title=el('div');title.append(ui.textCell(model.displayTitle(actor,row)),el('small','cell-sub',row.id+' · '+model.origins(model.root(row)).length+' 个关联回答'));
        const pending=model.nextAction(row,actor),action=ui.button(row.status==='已合并'?'查看合并':model.closed(row)?'查看结果':pending.actionable?'进入办理':'查看进度',()=>navigate(row.id),'do-action is-primary','feedback-'+row.id);
        action.setAttribute('aria-label','打开事项 '+row.id);
        const next=el('div');next.append(ui.textCell(pending.label));const updated=el('small','cell-sub','最近更新：'+model.updatedLabel(row));updated.title=time(row.updatedAt);next.append(updated);
        return [title,ui.textCell(row.originType),ui.textCell(model.sourceName(row.sourceId)),tag(row.status),ui.textCell(row.owner?name(row.owner):model.closed(row)?row.closedBy?.name||'历史办理人':'待分派'),next,ui.textCell(time(row.createdAt)),action];
      }}),RecordControls.pager({total:rows.length,...query},page=>{query.page=page;refresh();},size=>{query.pageSize=size;query.page=1;refresh();}));
    main.replaceChildren(ui.pageHeading('问题队列与处理'),FeedbackInsightsPage.navigation(ui),filter,panel);
    const key=sessionStorage.getItem('dataocean.feedback.return');if(key){sessionStorage.removeItem('dataocean.feedback.return');requestAnimationFrame(()=>{const target=main.querySelector('[data-focus="feedback-'+key+'"]')||main.querySelector('[data-focus^="feedback-"]')||main.querySelector('input[name="keyword"]');target?.focus({preventScroll:true});target?.scrollIntoView({block:'nearest',inline:'nearest'});});}
  }
  function evidence(row,body){
    const view=model.present(row,actor);
    if(view.restricted){body.append(el('p','fb-notice','当前可办理获派任务；原问题与业务证据需另行核权，请由有权人员协助核实。'));return;}
    const sources=section('原问题与反馈线索');
    for(const member of view.members)for(const origin of member.origins){
      const article=el('article','fb-origin');article.append(fields([['原提问人',name(origin.userId)],['回答引用',origin.answerId],['原问题',origin.question],['接收时间',time(origin.at)]]));
      const current=[...model.evaluations].reverse().find(event=>event.userId===origin.userId&&event.answerId===origin.answerId);
      if(current)article.append(fields([['当前评价',current.reaction?.type==='down'?'点踩':current.reaction?.type==='up'?'点赞':'已撤销'],['用户原因',current.reaction?.reason||'无']]));
      const details=el('details','fb-disclosure');details.append(el('summary','','当次使用依据'));
      const known=origin.evidence||{};details.append(fields([['意图 / 回答版本',known.revision||'未取得'],['查询终态',known.status||'未取得'],['查询时间',known.queriedAt?time(known.queriedAt):'未取得']]));
      if(model.has(actor,'knowledge.view'))details.append(el('p','fb-prose',(known.knowledge||[]).map(ref=>ref.title+' · '+ref.id+' r'+ref.revision).join('\n')||'未取得确切知识引用，不能据此认定根因。'));
      if(known.sql&&model.has(actor,'query.sql')){const sql=el('details');sql.append(el('summary','','查看当次 SQL（样例）'),el('pre','fb-prose',known.sql));details.append(sql);}
      article.append(details);sources.append(article);
      if(origin.userId===actor.id&&origin.threadId&&origin.turnId)article.append(link('回到原问','../app/index.html?feedbackThread='+encodeURIComponent(origin.threadId)+'&feedbackTurn='+encodeURIComponent(origin.turnId)+'&feedbackAnswer='+encodeURIComponent(origin.answerId)));
    }
    for(const member of view.members)if(member.currentReaction?.type==='down')sources.append(el('p','fb-prose','用户原因：'+member.currentReaction.reason));
    if(!model.origins(row).length)sources.append(el('p','fb-prose','人工巡检线索，无原提问人。'));
    body.append(sources);
    const notes=section('用户补充');const ownNotes=[...new Map(view.members.flatMap(item=>item.notes).map(note=>[JSON.stringify(note),note])).values()];
    if(!ownNotes.length)notes.append(el('p','fb-muted','暂无补充'));
    ownNotes.forEach(note=>notes.append(el('p','fb-prose',name(note.userId)+' · '+time(note.at)+'\n'+note.text)));body.append(notes);
  }
  function work(row,body){
    const view=model.present(row,actor),origin=view.origins[0],intro=section('问题线索');
    intro.append(el('p','fb-prose',model.evidenceAccess(actor,row)?origin?.question||view.title:'当前仅展示受控办理信息，业务证据由有权人员核实。'));
    const cause=model.evidenceAccess(actor,row)&&view.currentReaction?.type==='down'?view.currentReaction.reason:'';if(cause)intro.append(el('p','fb-prose','用户原因：'+cause));
    body.append(intro);
    const check=section('核查结论');check.append(fields([['初步方向',view.direction],['核实根因',model.evidenceAccess(actor,row)?view.rootCause||'待核实':'受限'],['核查依据',model.evidenceAccess(actor,row)?view.basis||'尚未记录':'由有权人员核实']]));
    const actions=el('div','fb-inline-actions');
    if(!model.closed(row)&&row.owner&&canWork(row)){actions.append(button('记录核查',trigger=>investigate(row,trigger)));if(model.evidenceAccess(actor,row))actions.append(button('请求用户补充',trigger=>supplement(row,trigger)));}
    check.append(actions);if(view.status==='待补充')view.pendingSupplements.forEach(request=>check.append(el('p','fb-notice','等待 '+name(request.userId)+(view.restricted?' 补充业务信息，具体内容受限。':' 补充：'+request.question))));body.append(check);
    const tasks=section('处理任务'),tools=el('div','fb-inline-actions');
    if(!model.closed(row)&&canWork(row)&&allowed(row,'assign')&&row.owner&&row.basis)tools.append(button('新增治理任务',trigger=>addTask(row,trigger,'业务知识')),button('安排技术处理',trigger=>addTask(row,trigger,'技术修复')),button('跟进数据修正',trigger=>addTask(row,trigger,'源数据修正')));
    tasks.append(tools);
    const all=view.tasks;if(!all.length)tasks.append(el('p','fb-muted',row.basis?'尚无处理任务':'记录核查依据后可添加必要任务。'));
    all.forEach(task=>{
      const ownerRow=model.get(task.issueId),article=el('article','fb-task');article.id='task-'+task.id;article.tabIndex=-1;article.append(el('h3','',model.evidenceAccess(actor,row)?task.title:task.kind+' · '+task.id),fields([['任务编号',task.id],['处理路径',task.kind+(task.kind==='技术修复'?' · '+(task.impact||'查询与安全'):'')],['负责人',name(task.owner)],['办理结果',!model.activeTask(task)?task.disposition:row.status==='已解决'?'已复验':row.status==='其他结案'?'随事项结案，未计为解决':model.taskState(task)]]));
      if(task.followup){
        if(task.followup.restricted)article.append(el('p','fb-muted','已记录跟进，具体说明受当前权限限制。'));
        else article.append(fields([['跟进说明',task.followup.note],['记录人 / 时间',task.followup.actor+' · '+time(task.followup.at)]]));
      }
      if(task.result&&!task.result.restricted){const details=el('details','fb-disclosure');details.append(el('summary','','处理结果与复查记录'),resultFields(task,task.result));article.append(details);}
      if(task.result?.restricted)article.append(el('p','fb-muted','已提交处理结果，明细受当前权限限制。'));
      if(task.history?.length&&model.evidenceAccess(actor,row)){const history=el('details','fb-disclosure');history.append(el('summary','','历史处理记录 · '+task.history.length));
        task.history.forEach(version=>{history.append(fields([['记录轮次','第'+version.cycle+'轮 · 任务r'+version.revision],['记录人 / 时间',version.actor+' · '+time(version.at)]]));
          if(version.result)history.append(resultFields(task,version.result));if(version.followup)history.append(fields([['当时进展',model.taskState({kind:task.kind,followup:version.followup})],['跟进说明',version.followup.note]]));});article.append(history);}
      const actions=el('div','fb-inline-actions');
      if(task.binding&&model.evidenceAccess(actor,row)){const table=DataSourceDemo.tables.find(item=>item.id===task.binding.table);if(table&&model.has(actor,'governance.view'))actions.append(link('进入对应治理','#governance/'+row.sourceId+'/'+encodeURIComponent(GovernanceDemo.objectName(table))+'?tab='+({overview:'overview',fields:'fields',relations:'relations',metrics:'metrics'}[GovernanceDemo.get(table).items.find(entry=>entry.id===task.binding.entry)?.kind]||'metrics')+'&field='+encodeURIComponent(task.binding.entry)));
        if(!table)article.append(el('p','fb-notice','原治理对象当前不可用，请核对对象身份及历史依据。'));
        if(model.has(actor,'knowledge.view'))actions.append(link('知识文档','#knowledge/'+row.sourceId));
        if(model.has(actor,'releases.view'))actions.append(link('发布与版本',task.result?.releaseId?'#releases/'+row.sourceId+'/'+task.result.releaseId:'#releases/'+row.sourceId));
      }
      if(!model.closed(row)&&model.activeTask(task)&&allowed(ownerRow,'process')&&task.owner===actor.id)actions.append(button(task.kind==='业务知识'?(task.result?'修订处理结果':'记录处理结果'):(task.result?'更新处理记录':'更新处理进展'),trigger=>handling(ownerRow,task,trigger),false,'handle-'+task.id));
      if(!model.closed(row)&&model.activeTask(task)&&allowed(ownerRow,'assign'))actions.append(button('重新分派',trigger=>reassignTask(ownerRow,task,trigger)));
      if(!model.closed(row)&&(canWork(row)||allowed(row,'process')&&allowed(row,'assign')))actions.append(button(model.activeTask(task)?'退出任务':'恢复办理',trigger=>disposition(ownerRow,task,trigger)));
      if(task.dispositionHistory?.length){const history=el('details','fb-disclosure');history.append(el('summary','','任务范围记录 · '+task.dispositionHistory.length));task.dispositionHistory.forEach(record=>history.append(el('p','fb-prose',record.action+' · '+record.actor+' · '+time(record.at)+(model.evidenceAccess(actor,row)?'\n'+record.reason:''))));article.append(history);}
      article.append(actions);tasks.append(article);
    });body.append(tasks);
    if(all.length){const validation=section('生效后复验');const gate=model.gate(row);
      if(gate.error&&!model.closed(row))validation.append(el('p','fb-notice',(view.restricted?'验收条件尚未齐备，请核对任务状态或联系有权人员。':gate.error)));
      for(const record of view.verifications)validation.append(fields(view.restricted?[['复验结论',record.decision+' · 第'+record.cycle+'轮'],['验证人 / 时间',record.actor+' · '+time(record.at)]]:[['测试身份',name(record.testUser)],['复验结论',record.decision+' · 第'+record.cycle+'轮'],['实际结果',record.actual],['复验依据',record.basis],['验证人 / 时间',record.actor+' · '+time(record.at)]]));
      if(!view.verifications.length)validation.append(el('p','fb-muted','尚无复验记录'));body.append(validation);
    }
  }
  function history(row,body){
    const view=model.present(row,actor);
    const notices=section('回告记录');
    if(!view.notifications.length)notices.append(el('p','fb-muted','结案负责人确认结论后形成回告。'));
    view.notifications.forEach(notification=>{
      const card=el('article','fb-origin');card.append(fields([['接收人',name(notification.userId)],['完成轮次','第'+notification.cycle+'轮'],['投递状态',notification.status+(notification.restricted?' · 详细内容受限':'')],['阅读状态',notification.read?'已读':'未读'],['确认结论',notification.template||model.receiptText('其他结案','其他')]]));
      if(allowed(row,'close')&&['待提示','提示失败'].includes(notification.status)&&notification.cycle===row.cycle&&model.closed(row))card.append(button(notification.status==='提示失败'?'重试回告':'发送回告',()=>{const error=model.deliver(row,notification.key,actor,deliveryMode);if(error)ui.notify(error);refresh();},true));
      if(allowed(row,'close')&&notification.status==='结果待核查')card.append(button('核查回告投递',trigger=>form(row,{title:'核查回告投递',trigger,value:{result:''},saveLabel:'记录核查',fields:[{key:'result',label:'核查结果',required:true,options:choices(['已投递','未投递'])}],save:data=>model.reconcile(row,notification.key,actor,data.result==='已投递')})));
      notices.append(card);
    });body.append(notices);
    const records=section('操作记录');const list=el('ol','fb-timeline');
    view.members.flatMap(member=>member.events.map(event=>({...event,issue:member.id}))).sort((a,b)=>b.at-a.at).forEach(event=>{
      const item=el('li');item.append(el('strong','',event.action),el('small','fb-muted',event.issue+' · '+event.actor+' · '+time(event.at)+' · 第'+event.cycle+'轮'),el('p','fb-prose',model.evidenceAccess(actor,row)?event.reason:''));list.append(item);
    });records.append(list);body.append(records);
  }
  function detail(original){
    const row=model.root(original);ui.setTitle(model.displayTitle(actor,original),'问题队列与处理','#feedback');
    const shell=el('section','fb-shell'),header=el('header','fb-header'),heading=el('div','fb-heading');heading.append(tag(original.status),el('span','fb-muted',original.id+' · 修订 '+original.revision));
    header.append(heading,fields([['数据源',model.sourceName(row.sourceId)],[!row.owner&&model.closed(row)?'结案负责人':'主负责人',!row.owner&&model.closed(row)?row.closedBy?.name||'历史记录未取得':name(row.owner)],['提出时间',time(original.createdAt)],['来源',original.originType]]));
    if(original.mergedInto){header.append(el('p','fb-notice','已关联主事项 '+row.id+'，原反馈与回答引用保留。'),button('进入主事项',()=>navigate(row.id)));if(allowed(original,'assign'))header.append(button('纠正合并',trigger=>unmerge(original,trigger)));const footer=el('footer','fb-footer');footer.append(button('返回队列',()=>{sessionStorage.setItem('dataocean.feedback.return',original.id);navigate();}));shell.append(header,footer);main.replaceChildren(ui.pageHeading(model.displayTitle(actor,original)),shell);return;}
    const nav=el('div','prototype-ui fb-tabs');
    const selected=tabs.get(row.id)||'work';
    PrototypeUI.mount(nav,{data:()=>({tab:selected}),methods:{change(value){tabs.set(row.id,value);refresh();main.querySelector('.fb-tabs .el-tabs__item.is-active')?.focus({preventScroll:true});}},template:'<el-tabs v-model="tab" @tab-change="change"><el-tab-pane label="核查与处理" name="work"></el-tab-pane><el-tab-pane label="原问题与证据" name="evidence"></el-tab-pane><el-tab-pane label="记录与回告" name="history"></el-tab-pane></el-tabs>'});
    const body=el('div','fb-body');body.tabIndex=0;
    if(selected==='evidence')evidence(row,body);else if(selected==='history')history(row,body);else work(row,body);
    const footer=el('footer','fb-footer');footer.append(button('返回队列',()=>{sessionStorage.setItem('dataocean.feedback.return',row.id);navigate();}));
    const actions=el('div','fb-footer-actions'),more=[];
    if(!model.closed(row)&&allowed(row,'assign')){actions.append(button(row.owner?'重新分派':'分派事项',trigger=>assign(row,trigger),!row.owner));if(!row.tasks.length)more.push({label:'合并重复事项',action:trigger=>merge(row,trigger)});}
    if(!model.closed(row)&&allowed(row,'close'))actions.append(button('说明并结案',trigger=>close(row,trigger,false)));
    if(model.closed(row)&&allowed(row,'process')&&(row.owner===actor.id||allowed(row,'assign')))actions.append(button('凭新证据重开',trigger=>reopen(row,trigger)));
    if(more.length)actions.append(ui.moreActions(more,'feedback-more'));
    if(!model.closed(row)&&allowed(row,'verify')&&model.tasksFor(row).length){
      const gate=model.gate(row),verified=model.nextAction(row,actor).key==='close';
      if(verified&&allowed(row,'close'))actions.append(button('验证结案并回告',trigger=>close(row,trigger,true),true));
      else{if(gate.error){const notice=el('span','fb-footer-blocker',model.evidenceAccess(actor,row)?gate.error:'验收条件尚未齐备，请核对任务状态或联系有权人员。');notice.setAttribute('role','status');actions.append(notice);if(gate.taskId)actions.append(button('定位待办',()=>{tabs.set(row.id,'work');refresh();requestAnimationFrame(()=>{const target=main.querySelector(gate.taskId?'#task-'+gate.taskId:'.fb-body .fb-section');target?.scrollIntoView({block:'center'});target?.focus({preventScroll:true});});}));}
      else actions.append(button('登记人工验收结果',trigger=>verify(row,trigger),true));}
    }
    footer.append(actions);shell.append(header,nav,body,footer);main.replaceChildren(ui.pageHeading(model.displayTitle(actor,row)),shell);
    shell.addEventListener('click',event=>{const target=event.target.closest('a[href^="#"]');if(target&&!target.hash.startsWith('#feedback')){lastIssue=row.id;positions.set(row.id,body.scrollTop);parentPositions.set(row.id,main.scrollTop);}});
    requestAnimationFrame(()=>{body.scrollTop=positions.get(row.id)||0;});
  }
  function reviewTools(){
    if(new URLSearchParams(location.search).get('review')!=='1')return;
    const tools=el('details','fb-review');tools.append(el('summary','','原型评审'));
    tools.open=reviewOpen;tools.addEventListener('toggle',()=>{if(tools.isConnected)reviewOpen=tools.open;});
    const panel=el('div','prototype-ui');PrototypeUI.mount(panel,{data:()=>({mode:deliveryMode}),methods:{change(value){deliveryMode=value;}},template:'<el-select v-model="mode" aria-label="回告投递样例" @change="change"><el-option label="正常投递" value="success"></el-option><el-option label="投递失败" value="fail"></el-option><el-option label="投递结果未知" value="unknown"></el-option></el-select>'});tools.append(panel);main.append(tools);
  }
  function render(target,controls,id=''){
    main=target;ui=controls;actor=ui.currentUser;current=id;
    if(!model.has(actor,'feedback.view')){main.replaceChildren(ui.pageHeading('问题队列与处理'),el('p','fb-notice','当前账号没有反馈查看权限。'));return;}
    if(Object.hasOwn(FeedbackInsightsPage.labels,id)){FeedbackInsightsPage.render(main,ui,id);return;}
    if(id){const row=model.get(id);if(!row||!allowed(row,'view')){main.replaceChildren(ui.pageHeading('事项不可访问'),el('p','fb-notice','事项不存在或当前没有访问权限。'));return;}detail(row);}else list();reviewTools();
    main.scrollTop=parentPositions.get(id)||0;
  }
  function context(section){
    if(!lastIssue||!['governance','knowledge','test-cases','releases'].includes(section))return null;
    const row=model.get(lastIssue);if(!row||!allowed(row,'view'))return null;
    const bar=el('div','fb-return-context');bar.append(el('span','',row.id+' · '+model.displayTitle(actor,row)),link('返回问题处理','#feedback/'+row.id));return bar;
  }
  window.FeedbackPage={render,context};
})();
