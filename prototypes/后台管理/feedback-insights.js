/* 改进配置沿用问题模块、共用表格和表单。验证、职责确认与启停各自留痕。 */
(() => {
  'use strict';
  const model=FeedbackInsights,labels={policies:'重点检查策略',notes:'历史注意事项',preferences:'排序偏好'},kinds={policies:'policy',notes:'note',preferences:'preference'};
  const queries=new Map();let main,ui,actor,tab;
  const choices=values=>values.map(value=>({value,label:value}));
  const name=id=>PeopleDemo.users.find(user=>user.id===id)?.name||'未记录';
  const date=value=>new Date(value).toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'}).slice(0,16);
  const source=id=>FeedbackDemo.sourceName(id);
  const allowed=ability=>model.permitted(actor,ability);
  const el=(...args)=>ui.el(...args);
  const action=(label,fn,primary=false)=>ui.button(label,fn,primary?'admin-button is-primary':'admin-button');
  function navigation(controls,active='queue'){
    const node=controls.el('div','prototype-ui fb-module-tabs');
    PrototypeUI.mount(node,{data:()=>({active}),methods:{change(value){location.hash='feedback'+(value==='queue'?'':'/'+value);}},template:'<el-tabs v-model="active" @tab-change="change"><el-tab-pane label="问题队列" name="queue"></el-tab-pane><el-tab-pane label="重点检查" name="policies"></el-tab-pane><el-tab-pane label="历史注意" name="notes"></el-tab-pane><el-tab-pane label="排序偏好" name="preferences"></el-tab-pane></el-tabs>'});return node;
  }
  function form(config){const initial=JSON.stringify(config.value);return ui.openForm({protectChanges:true,hasChanges:data=>JSON.stringify(data)!==initial,...config});}
  const field=(key,label,other={})=>({key,label,required:true,...other});
  const prose=(key,label,other={})=>field(key,label,{type:'textarea',full:true,...other});
  const number=(key,label,min,max,step=1)=>field(key,label,{type:'number',min,max,step,precision:step<1?2:0});
  function snapshotFields(content){
    const list=el('dl','fb-fields'),entries=[['名称',content.title],['数据源',source(content.sourceId)]];
    if(content.window)entries.push(['窗口 / 提醒间隔',content.window+'天 / '+content.interval+'小时'],['最低反馈 / 独立用户 / 重复密度',content.minimum+' / '+content.users+' / '+content.density],['接收人',content.recipients.map(name).join('、')],['不同人员复核',content.independentReview?'已配置':'未要求']);
    else entries.push(['问题场景',content.scenario],['适用条件',content.conditions],['知识 / 对象版本',content.documentId||content.object+' · '+content.version],['核实依据',content.basis],...(content.description?[['历史问题与处理结果',content.description],['修正状态',content.correction],['本次检查',content.check]]:[['相关性门槛 / 调整上限',content.threshold+' / '+content.limit]]));
    entries.forEach(([label,value])=>list.append(ui.pair(label,String(value||'待绑定'))));return list;
  }
  function edit(row,trigger){
    const kind=kinds[tab],sourceId=row?.sourceId||queries.get(tab).sourceId||'products';
    const value=row?structuredClone(row.draft.content):kind==='policy'?{title:'',sourceId,window:7,minimum:5,users:3,density:0.2,interval:24,recipients:[actor.id],independentReview:false}:{title:'',sourceId,issueId:'',scenario:'',conditions:'',basis:'',documentId:'',version:'',kind:'业务',object:'',description:'',check:'',correction:'待核实',threshold:0.65,limit:0.05};
    delete value.refToken;
    // 源切换会影响知识选项，因此新记录先选数据源，再进入正文表单。
    const open=(initial)=>{
      const docs=model.knowledge(initial.sourceId),revision=row?.revision;
      const fields=[field('title','名称'),{key:'sourceLabel',label:'数据源',readonly:true}];
      initial.sourceLabel=source(initial.sourceId);
      if(kind==='policy')fields.push(number('window','观察窗口（天）',1,365),number('minimum','最低有效反馈量',1,10000),number('users','最低独立用户量',1,10000),number('density','最低重复密度',0.01,1,0.01),number('interval','提醒间隔（小时）',1,8760),field('recipients','接收人',{multiple:true,full:true,options:PeopleDemo.users.filter(user=>user.status==='正常'&&model.permitted(user,'view')).map(user=>({value:user.id,label:user.name+'（'+user.login+'）'}))}),{key:'independentReview',label:'本数据源的确认要求',options:[{value:false,label:'允许同一有权人员验证、确认及启停'},{value:true,label:'历史注意和排序偏好须由不同人员复核'}],full:true});
      else{
        fields.push({key:'issueId',label:'关联已核查事项',options:FeedbackDemo.items.filter(item=>item.sourceId===initial.sourceId&&item.basis&&FeedbackDemo.evidenceAccess(actor,item)).map(item=>({value:item.id,label:item.id+' · '+item.title})),full:true},prose('scenario','问题场景'),prose('conditions','适用条件与时间口径'));
        if(kind==='note')fields.push(field('kind','注意事项类型',{options:choices(['业务','技术','涉及业务口径的技术'])}),field('correction','当前修正状态',{options:choices(['未修正','已修正','待核实'])}),field('object','适用对象',{when:data=>data.kind==='技术'}),field('version','技术版本',{when:data=>data.kind==='技术'}));
        fields.push(field('documentId','确切知识版本',{required:docs.some(doc=>doc.available),options:docs.filter(doc=>doc.available).map(doc=>({value:doc.value,label:doc.label})),full:true,when:data=>kind==='preference'||data.kind!=='技术',help:docs.some(doc=>doc.available)?'':'当前源没有正式可用知识，可先保存候选；绑定正式知识后才能验证和启用。'}));
        if(kind==='note')fields.push(prose('description','历史问题与处理结果'),prose('check','本次应检查什么'));
        else fields.push(number('threshold','最低语义相关性',0,1,0.01),number('limit','有界调整上限',0.01,1,0.01));
        fields.push(prose('basis','核实依据与预期作用'));
      }
      form({title:(row?'编辑':'新增')+labels[tab],trigger,value:initial,fields,saveLabel:'保存草稿',note:kind==='preference'?'默认调整量为零，完成对照、确认和显式启用后才生效。':'',
        save:data=>{delete data.sourceLabel;if(kind!=='policy'&&data.kind==='技术')data.documentId='';else data.version='';const result=model.save(kind,row,data,actor,revision);return result.error;},success:'草稿已保存'});
    };
    if(row)open(value);else form({title:'新增'+labels[tab],trigger,value:{sourceId},fields:[field('sourceId','数据源',{options:PeopleDemo.dataSources.filter(item=>kind==='policy'||model.access(actor,item.id)).map(item=>({value:item.id,label:item.name})),full:true})],saveLabel:'继续',hasChanges:()=>true,protectChanges:false,save:()=>{},onSaved:data=>open({...value,sourceId:data.sourceId})});
  }
  function verification(row,trigger){const policy=row.kind==='policy',preference=row.kind==='preference',rev=row.revision;
    form({title:policy?'登记历史样本校准':preference?'登记同题单对照':'登记适用性核验',trigger,value:{basis:'',result:'',decision:'',falsePositive:'',falseNegative:'',positive:false,counter:false,restricted:false,regression:false},
      note:'在对应环境完成检查后登记实际结果；当前为离线原型。',saveLabel:'保存验证',
      fields:[prose('basis',policy?'代表性历史样本与版本':preference?'审核题单、无偏好基线及对照版本':'核对的对象、版本及证据'),prose('result','实际结果与观察指标'),...(policy?[prose('falsePositive','误报检查'),prose('falseNegative','漏报检查')]:[]),...(preference?[['positive','正例效果已核对'],['counter','近似但不同口径的反例已核对'],['restricted','无权限、暂停和知识不足场景均未放行'],['regression','无关问题无退步']].map(([key,label])=>field(key,label,{type:'checkbox',checkLabel:label,full:true})):[]),field('decision','验证结论',{options:choices(['通过','不通过']),full:true})],save:data=>model.verify(row,data,actor,rev)});
  }
  function confirmation(row,trigger){const rev=row.revision;form({title:'确认当前修订适用性',trigger,value:{duty:'',basis:''},saveLabel:'确认适用性',fields:[field('duty','确认职责',{options:choices(model.requiredReviews(row.draft.content,row.kind))}),prose('basis','确认依据')],save:data=>model.confirm(row,data,actor,rev)});}
  function activation(row,trigger,enable){const rev=row.revision;form({title:enable?'启用当前修订':'停用生效修订',trigger,value:{reason:''},fields:[prose('reason',enable?'启用依据与观察指标':'停用原因')],summary:true,previewLabel:'核对生效范围',saveLabel:enable?'确认启用':'确认停用',danger:!enable,
    preview:data=>[{label:'数据源',after:source(row.sourceId)},{label:'修订',after:'r'+(enable?row.draft.revision:row.active.revision)},{label:'依据',after:data.reason},{label:'确认后',after:enable?row.kind==='preference'?'仅在合法有效候选内按本修订进行有界调整':'本修订在所限定场景内可用':row.kind==='preference'?'调整量归零':'停止提供本修订'}],save:data=>model.toggle(row,enable,data.reason,actor,rev)});}
  function rollback(row,trigger){const rev=row.revision;form({title:'回退历史生效修订',trigger,value:{revision:'',reason:''},fields:[field('revision','历史修订',{options:row.versions.filter(item=>item.revision!==row.active?.revision).map(item=>({value:item.revision,label:'r'+item.revision+' · '+date(item.at)}))}),prose('reason','回退依据')],summary:true,previewLabel:'核对回退',preview:data=>[{label:'回退目标',after:'r'+data.revision},{label:'依据',after:data.reason},{label:'生效前检查',after:'重新核对当前权限、确认与适用版本'}],saveLabel:'确认回退',save:data=>model.rollback(row,data.revision,data.reason,actor,rev)});}
  function preview(row,trigger){
    const value=structuredClone(row.active?.content||row.draft.content),kind=row.kind;
    Object.assign(value,{eligible:false,score:0.7});
    form({title:kind==='note'?'核对本次注意事项适用性':'核对本次排序调整',trigger,value,hasChanges:()=>true,protectChanges:false,fields:[prose('scenario','本次问题场景'),prose('conditions','本次条件与时间口径'),{key:'documentId',label:'本次正式知识',options:model.knowledge(row.sourceId).filter(doc=>doc.available).map(doc=>({value:doc.value,label:doc.label})),required:!!value.documentId,full:true,when:()=>!!value.documentId},{key:'version',label:'本次技术版本',required:true,when:()=>!value.documentId},...(kind==='note'?[field('correction','本次修正状态',{options:choices(['未修正','已修正','待核实'])})]:[number('score','本次语义相关性',0,1,0.01),field('eligible','候选约束',{type:'checkbox',checkLabel:'已核对权限、选源、正式发布、暂停与兼容性',full:true})])],
      saveLabel:'核对本次结果',save:data=>{const result=model.recordPreview(row,data,actor);showPreview(row,result,trigger);},success:'本次核对已记录'});
  }
  function showPreview(row,result,trigger){
    // 等当前编辑弹窗关闭后再开结果，避免嵌套遮罩和焦点竞争。
    queueMicrotask(()=>{const node=ui.modal('本次核对结果',trigger),body=el('div','dialog-body');body.append(el('p','fb-prose',result.error||result.context||result.message));if(result.adjustment!==undefined)body.append(el('p','fb-prose','实际调整量：'+result.adjustment.toFixed(2)));if(result.revision)body.append(el('p','fb-muted','采用 '+row.id+' · r'+result.revision));const footer=el('footer','dialog-footer');footer.append(action('关闭',()=>node.close()));node.append(body,footer);node.showModal();});
  }
  function details(row,trigger){
    const node=ui.modal(labels[tab]+'详情',trigger,true),body=el('div','dialog-body'),fields=el('dl','fb-fields');
    const data=row.draft.content;
    const entries=[['数据源',source(row.sourceId)],['当前草稿','r'+row.draft.revision],['实际生效',model.current(row)?'r'+row.active.revision:row.active?.enabled?'需复核，当前不采用':'未启用'],['验证',row.draft.validation?.decision||'待验证'],['确认职责',row.draft.reviews.map(review=>review.duty+' · '+name(review.actor)).join('、')||'待确认']];
    if(row.kind==='policy'){
      const metric=model.observation(row,actor);entries.push(['观察窗口',metric.window+'天'],['有效反馈 / 回答 / 独立用户',metric.feedback+' / '+metric.answers+' / '+metric.users],['检查状态',metric.state+' · '+metric.reason],['重复密度',metric.density.toFixed(2)],['提醒间隔',metric.interval+'小时'],['接收人',data.recipients.map(name).join('、')],['不同人员复核',data.independentReview?'已配置':'未要求']);
    }else entries.push(['问题场景',data.scenario],['条件与时间口径',data.conditions],['适用知识 / 版本',data.documentId?model.knowledge(row.sourceId).find(doc=>doc.value===data.documentId)?.label||data.documentId:data.kind==='技术'?data.object+' · '+data.version:'待绑定正式可用知识'],['核实依据',data.basis],...(row.kind==='note'?[['历史问题与处理结果',data.description],['当前修正状态',data.correction],['本次应检查',data.check]]:[['相关性门槛',data.threshold],['调整上限',data.limit]]));
    entries.forEach(([label,value])=>fields.append(ui.pair(label,String(value))));body.append(fields);
    if(row.active&&row.active.revision!==row.draft.revision){const current=el('details','fb-disclosure');current.append(el('summary','','回看生效修订 r'+row.active.revision),snapshotFields(row.active.content));body.append(current);}
    if(row.draft.validation)body.append(el('h3','','当前验证'),el('p','fb-prose',row.draft.validation.basis+'\n'+row.draft.validation.result));
    const history=el('ol','fb-timeline');row.history.slice().reverse().forEach(item=>{const entry=el('li');entry.append(el('strong','',item.action),el('small','fb-muted',item.actor+' · '+date(item.at)));if(item.basis)entry.append(el('p','fb-prose',item.basis));if(item.result)entry.append(el('p','fb-prose',item.result));if(item.content&&JSON.stringify(item.content)!==JSON.stringify(data)){const snapshot=el('details','fb-disclosure');snapshot.append(el('summary','','查看当次内容'),snapshotFields(item.content));entry.append(snapshot);}history.append(entry);});body.append(el('h3','','修订与办理记录'),history);
    const adopted=model.adoptions.filter(item=>item.id===row.id);if(adopted.length){body.append(el('h3','','适用性核对记录'));adopted.slice().reverse().forEach(item=>body.append(el('p','fb-prose',date(item.at)+' · '+name(item.actor)+' · '+(item.accepted?'采用 r'+item.revision:'未采用：'+item.error))));}
    if(row.kind==='policy'){
      const reminders=model.reminders.filter(item=>item.policy===row.id);body.append(el('h3','','重点检查提示'));
      if(!reminders.length)body.append(el('p','fb-muted','尚无达到有效策略门槛的提示'));
      reminders.slice().reverse().forEach(item=>{const link=el('a','do-action is-primary',item.group+' · '+item.metrics.feedback+'条反馈 / '+item.metrics.answers+'个回答 / '+item.metrics.users+'位用户');link.href='#feedback/'+item.group;link.addEventListener('click',()=>node.close());body.append(link,el('p','fb-muted','策略 r'+item.revision+' · '+date(item.at)+' · 接收人：'+item.recipients.map(name).join('、')));});
    }
    const footer=el('footer','dialog-footer');footer.append(action('关闭',()=>node.close()));const run=(fn)=>()=>{node.close();fn();};
    if(allowed(row.kind==='policy'?'policy':'insight-edit'))footer.append(action('编辑草稿',run(()=>edit(row,trigger))));
    let enableControl;const more=[];if(allowed('insight-verify'))more.push({label:'登记验证',action:run(()=>verification(row,trigger))});if(allowed('insight-confirm'))more.push({label:'确认适用性',action:run(()=>confirmation(row,trigger))});
    if(row.kind==='policy')more.push({label:'检查当前样本',action:run(()=>{const result=model.inspect(row,actor);ui.notify(result.error||result.state+'：'+result.reason);details(row,trigger);})});
    if(allowed('insight-toggle')){
      if(row.active?.enabled)more.push({label:'停用',danger:true,action:run(()=>activation(row,trigger,false))});
      if(row.versions.some(item=>item.revision!==row.active?.revision))more.push({label:'回退历史修订',action:run(()=>rollback(row,trigger))});
      if(!row.active?.enabled||row.active.revision!==row.draft.revision){enableControl=action('启用当前修订',run(()=>activation(row,trigger,true)),true);const reason=model.readiness(row);enableControl.disabled=!!reason;if(reason)enableControl.title=reason;}
    }
    if(row.kind!=='policy')more.push({label:'本次适用性核对',action:run(()=>preview(row,trigger))});
    if(more.length)footer.append(ui.moreActions(more,'insight-more','更多',node,'top-start'));
    if(enableControl)footer.append(enableControl);
    const blocker=model.readiness(row);if(blocker)body.append(el('p','fb-notice',blocker));
    if(row.kind!=='policy'&&data.kind!=='技术'&&!data.refToken&&FeedbackDemo.has(actor,'knowledge.view')){const knowledge=el('a','do-action is-primary','前往知识文档');knowledge.href='#knowledge/'+row.sourceId;knowledge.addEventListener('click',()=>node.close());body.append(knowledge);}
    node.append(body,footer);node.showModal();
  }
  function render(target,controls,selected){
    main=target;ui=controls;actor=controls.currentUser;tab=selected;
    ui.setTitle(labels[tab],'问题队列与处理','#feedback');
    if(!queries.has(tab))queries.set(tab,{keyword:'',sourceId:'',status:'all',page:1,pageSize:10});const query=queries.get(tab),kind=kinds[tab];
    const status=row=>model.current(row)?'已启用':row.active?.enabled?'需复核':row.active?'已停用':'未启用';
    const filter=RecordControls.filters({value:query,includeDates:false,fields:[{key:'keyword',label:'名称或编号'},{key:'sourceId',label:'数据源',options:PeopleDemo.dataSources.map(item=>({value:item.id,label:item.name}))}],statuses:[{value:'all',label:'全部状态'},...choices(['未启用','已启用','已停用','需复核'])],onApply:data=>{Object.assign(query,data);render(main,ui,tab);},onReset:()=>{Object.assign(query,{keyword:'',sourceId:'',status:'all',page:1});render(main,ui,tab);}});
    const tools=el('div','fb-insight-tools');tools.append(filter);if(allowed(kind==='policy'?'policy':'insight-edit'))tools.append(action('新增'+labels[tab],trigger=>edit(null,trigger),true));
    const rows=model.rows.filter(row=>row.kind===kind&&model.visible(row,actor)&&(!query.sourceId||row.sourceId===query.sourceId)&&(query.status==='all'||status(row)===query.status)&&AdminQuery.matches([row.id,row.draft.content.title],query.keyword));
    query.page=Math.max(1,Math.min(query.page,Math.ceil(rows.length/query.pageSize)));
    const panel=el('section','record-panel'),empty=el('div','empty-state');empty.append(WorkbenchIcon('file-text'),el('p','','暂无匹配的'+labels[tab]));
    panel.append(RecordTable.render({caption:labels[tab],minWidth:1000,columns:[{key:'title',label:'名称 / 编号',width:240,grow:3},{key:'source',label:'数据源',width:140},{key:'revision',label:'草稿 / 生效修订',width:160},{key:'status',label:'状态',width:104},{key:'next',label:'下一步',width:220,grow:2},{key:'actions',label:'操作',width:120,fixed:'right'}],items:rows.slice((query.page-1)*query.pageSize,query.page*query.pageSize),empty,row:row=>{
      const title=el('div');title.append(el('span','',row.draft.content.title),el('small','cell-sub',row.id));const view=ui.button('查看与处理',trigger=>details(row,trigger),'do-action is-primary');view.setAttribute('aria-label','查看 '+row.id);
      return [title,ui.textCell(source(row.sourceId)),ui.textCell('r'+row.draft.revision+' / '+(row.active?'r'+row.active.revision:'未启用')),el('span','status-tag'+(status(row)==='已启用'?'':status(row)==='需复核'?' is-pending':' is-disabled'),status(row)),ui.textCell(model.readiness(row)|| (model.current(row)?'运行观察，可独立停用':'可启用当前修订')),view];
    }}),RecordControls.pager({total:rows.length,...query},page=>{query.page=page;render(main,ui,tab);},size=>{query.pageSize=size;query.page=1;render(main,ui,tab);}));
    main.replaceChildren(ui.pageHeading(labels[tab]),navigation(ui,tab),tools,panel);
  }
  window.FeedbackInsightsPage={render,navigation,labels};
})();
