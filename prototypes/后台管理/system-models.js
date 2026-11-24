/* 模型列表维护接入；节点列表说明用途并直接选择模型，不增加验收或发布审批。 */
(() => {
  'use strict';
  const model=SystemModelsDemo,queries=new Map();let main,ui,actor,section='models',tab='list',sourceOrigin,reopen;
  // 本次定位来自概览入口，不覆盖模型与诊断页面单独保存的查询。
  let overviewParams = new URLSearchParams();
  const el=(...args)=>ui.el(...args),has=key=>model.permitted(actor,key),button=(label,fn,primary=false)=>ui.button(label,fn,primary?'admin-button is-primary':'admin-button');
  const time=at=>new Date(at).toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'}).slice(0,19);
  const field=(key,label,extra={})=>({key,label,...extra}),choices=values=>values.map(value=>({value,label:value}));
  const status=value=>el('span','status-tag'+(/未分配|不使用|已停用/.test(value)?' is-disabled':/失败|待|不可用/.test(value)?' is-pending':''),value);
  function focusKey(key){requestAnimationFrame(()=>{if(document.querySelector('dialog[open]'))return;const target=[...main.querySelectorAll('[data-focus]')].find(node=>node.dataset.focus===key)||main.querySelector('.sm-record-panel .do-action')||main.querySelector('input');target?.focus({preventScroll:true});});}
  function refresh(){render(main,ui,section,tab,overviewParams);}
  function facts(entries){const list=el('dl','sm-facts');entries.forEach(([label,value])=>list.append(ui.pair(label,String(value??'未知'))));return list;}
  function form(config){const initial=JSON.stringify(config.value);return ui.openForm({protectChanges:true,hasChanges:value=>JSON.stringify(value)!==initial,...config});}
  function tabs(items,active,change,action){const host=el('div','prototype-ui sm-tabs'),nav=el('div','sm-tabs-nav');PrototypeUI.mount(nav,{data:()=>({active}),methods:{change},template:'<el-tabs v-model="active" @tab-change="change">'+items.map(([name,label])=>'<el-tab-pane name="'+name+'" label="'+label+'"></el-tab-pane>').join('')+'</el-tabs>'});host.append(nav);if(action)host.append(action);return host;}
  function table(caption,rows,columns,row,q){q.page=Math.max(1,Math.min(q.page,Math.ceil(rows.length/q.pageSize)));const panel=el('section','record-panel sm-record-panel'),empty=el('div','empty-state');empty.append(WorkbenchIcon('search'),el('p','','没有匹配的记录'));panel.append(RecordTable.render({caption,columns,minWidth:columns.reduce((sum,col)=>sum+col.width,0),items:rows.slice((q.page-1)*q.pageSize,q.page*q.pageSize),row,empty}),RecordControls.pager({total:rows.length,...q},page=>{q.page=page;refresh();},size=>{q.pageSize=size;q.page=1;refresh();}));return panel;}
  function edit(row,trigger){
    const initial=row?{name:row.name,...structuredClone(row.content),credential:''}:{name:'',kind:'generation',protocol:'openai',address:'',model:'',credential:'',timeout:120,retries:2},revision=row?.revision;
    let savedRow;
    form({title:row?'编辑模型':'新增模型',trigger,value:initial,note:row?'':'完成节点任务所需的获准信息会交给所选模型处理，请按企业要求选择服务。',fields:[field('kind','模型类型',{required:true,options:Object.entries(model.kinds).map(([value,label])=>({value,label})),onChange:data=>{data.protocol=model.protocolOptions(data.kind)[0].value;}}),field('protocol','接口格式',{required:true,options:data=>model.protocolOptions(data.kind)}),field('name','配置名称',{required:true,full:true}),field('address','服务URL',{required:true,full:true,placeholder:'https://models.example.invalid/api'}),field('model','模型名称',{required:true,full:true}),field('credential','访问凭据',{type:'password',required:!row,disabled:!!row&&!has('models.credential'),full:true,placeholder:row?'留空保留已设置凭据；替换时填写演示值':'请填写演示值'}),field('timeout','调用超时（秒）',{type:'number',min:1,max:120,precision:0,required:true}),field('retries','最多重试次数',{type:'number',min:0,max:2,precision:0,required:true})],saveLabel:'保存模型',
      save:data=>{const result=model.save(row,data,actor,revision);savedRow=result.row;return result.error;},success:'模型已保存，可在节点列表中选择',onSaved:()=>{if(!row){queries.delete('list');refresh();}focusKey('sm-model-'+savedRow.id);}});
  }
  function test(row,trigger){const error=model.testConnection(row,actor,row.revision);ui.notify(error||'固定样例连接测试'+row.test.status);refresh();detail(row,trigger);}
  function stop(row,trigger){const revision=row.revision,stateRevision=row.stateRevision,used=model.usages(row);form({title:'停用模型',trigger,value:{impact:used.length?used.map(node=>node.name).join('、'):'未分配给节点'},fields:[field('impact','受影响节点',{readonly:true,full:true})],hasChanges:()=>true,protectChanges:false,danger:true,note:used.length?'停用后，依赖该模型的节点或原生效路径将不可用。':'',saveLabel:'确认停用',save:()=>model.stop(row,actor,revision,stateRevision),onSaved:()=>focusKey(trigger?.dataset.focus)});}
  function snapshotFacts(value){return facts([['配置名称',value.name],['配置修订','r'+value.revision],['模型类型',model.kinds[value.kind]],['接口格式',model.protocols[value.content.protocol]?.label||'未知'],['实际模型标识',value.content.model],['服务URL',value.content.address],['调用超时 / 最多重试',value.content.timeout+'秒 / '+value.content.retries+'次']]);}
  function detail(row,trigger){
    const node=ui.modal('模型详情 · '+row.name,trigger,true),body=el('div','dialog-body');body.append(facts([['配置名称',row.name],['模型类型',model.kinds[row.kind]],['接口格式',model.protocols[row.content.protocol].label],['服务URL',row.content.address],['模型名称',row.content.model],['凭据状态',row.content.credentialSet?'已设置':'未设置'],['配置修订','r'+row.revision],['启停状态',row.state],['最近连接测试',row.test?row.test.status+' · '+time(row.test.at):'未测试'],['已分配节点',model.usages(row).map(item=>item.name).join('、')||'无'],['调用超时 / 最多重试',row.content.timeout+'秒 / '+row.content.retries+'次']]));
    if(row.test?.reason)body.append(el('p','sm-prose',row.test.reason));
    if(row.history.length){const history=el('details','sm-disclosure');history.append(el('summary','','查看配置修改记录'));row.history.slice().reverse().forEach(value=>history.append(snapshotFacts(value)));body.append(history);}
    const footer=el('footer','dialog-footer'),run=fn=>()=>{node.close();fn();},more=[];footer.append(button('关闭',()=>node.close()));
    if(has('models.test'))more.push({label:'测试连接',action:run(()=>test(row,trigger))});
    if(has('models.disable')&&row.state!=='已停用')more.push({label:'停用模型',danger:true,separator:true,action:run(()=>stop(row,trigger))});
    if(has('models.edit')&&row.state==='已停用')more.push({label:'恢复模型',action:run(()=>{const error=model.enable(row,actor,row.revision);ui.notify(error||'模型已恢复');refresh();})});
    if(more.length)footer.append(ui.moreActions(more,'model-more','更多',node,'top-start'));
    if(has('models.edit'))footer.append(button('编辑模型',run(()=>edit(row,trigger)),true));node.append(body,footer);node.showModal();
  }
  function list(){
    if(!queries.has('list'))queries.set('list',{keyword:'',kind:'',model:'',protocol:'',status:'all',page:1,pageSize:10});const q=queries.get('list'),tools=el('div','sm-list-tools');
    tools.append(RecordControls.filters({value:q,includeDates:false,fields:[field('keyword','配置名称'),field('kind','模型类型',{options:Object.entries(model.kinds).map(([value,label])=>({value,label}))}),field('protocol','接口格式',{options:model.protocolOptions()}),field('model','模型名称')],statuses:[{value:'all',label:'全部启停状态'},...choices(['已启用','已停用'])],onApply:data=>{Object.assign(q,data);refresh();},onReset:()=>{queries.delete('list');refresh();}}));main.append(tools);
    const rows=model.rows.filter(row=>(!q.kind||q.kind===row.kind)&&(!q.protocol||q.protocol===row.content.protocol)&&(q.status==='all'||q.status===row.state)&&AdminQuery.matches([row.name],q.keyword)&&AdminQuery.matches([row.content.model],q.model));
    main.append(table('模型列表',rows,[{key:'name',label:'配置名称',width:185,grow:2},{key:'kind',label:'模型类型',width:165},{key:'protocol',label:'接口格式',width:215},{key:'model',label:'模型名称',width:165,grow:1},{key:'state',label:'启停状态',width:100},{key:'test',label:'最近连接测试',width:125},{key:'nodes',label:'引用节点',width:90},{key:'actions',label:'操作',width:100,fixed:'right'}],row=>[ui.textCell(row.name),ui.textCell(model.kinds[row.kind]),ui.textCell(model.protocols[row.content.protocol].label),ui.textCell(row.content.model),status(row.state),row.test?status(row.test.status):ui.textCell('未测试'),ui.textCell(model.usages(row).length+' 个'),ui.button('查看详情',trigger=>detail(row,trigger),'do-action is-primary','sm-model-'+row.id)],q));
  }
  function indexDetail(item,trigger){
    const info=model.indexInfo(item),node=ui.modal('索引状态 · '+item.name,trigger,true),body=el('div','dialog-body');
    const describe=value=>value?value.name+' · r'+value.revision:'未配置';
    body.append(facts([['节点',item.name],['已选择配置',describe(info.requested)],['当前生效配置',describe(info.active)],['索引状态',item.indexState],['当前节点可用性',info.available?info.target?'使用匹配的原有效路径':'使用当前生效配置':'当前不可用']]));
    if(info.task)body.append(facts([['更新任务',info.task.id],['更新范围',info.task.scope],['任务创建时间',time(info.task.at)],['更新次数',info.task.attempts],...(info.task.finishedAt?[['最近完成时间',time(info.task.finishedAt)]]:[])]));
    if(info.target)body.append(el('p','sm-notice',info.available?'新索引就绪前继续使用当前生效配置。':'目前没有可用的匹配索引，请完成索引更新。'));
    if(info.error)body.append(el('p','sm-notice',info.error));
    function update(result){const error=model.resolveIndex(item.id,result,info.targetKey,actor);if(error){ui.notify(error);return;}node.close();refresh();indexDetail(item,trigger);ui.notify(result==='ready'?'原型索引更新完成，已切换匹配配置':'原型索引更新失败，保留原生效配置');}
    if(info.target)body.append(el('p','sm-prototype-hint','离线原型使用固定更新结果，不执行真实索引构建。'));
    if(new URLSearchParams(location.search).get('review')==='models'&&info.target&&has('models.assign')){
      const review=el('details','sm-disclosure'),actions=el('div','do-actions sm-review-actions');review.append(el('summary','','原型索引状态演示'),el('p','sm-prototype-hint','固定状态样例，不执行真实索引构建或知识发布。'));
      for(const [result,label] of [['failed','演示更新失败'],['ready','演示校验通过并切换']])actions.append(button(label,()=>update(result)));
      review.append(actions);body.append(review);
    }
    const footer=el('footer','dialog-footer');footer.append(button('关闭',()=>node.close()));
    if(info.target&&has('models.assign')){const start=button(info.error?'重试索引更新':'更新索引',()=>update('ready'),true);start.disabled=model.find(item.model)?.state!=='已启用';footer.append(start);}
    node.append(body,footer);node.showModal();
  }
  function clearNode(item,trigger){
    const revision=model.assignmentRevision;
    form({title:'清空节点模型',trigger,value:{node:item.name,purpose:item.description},fields:[field('node','节点',{readonly:true,full:true}),field('purpose','功能影响',{readonly:true,full:true})],hasChanges:()=>true,protectChanges:false,danger:true,note:'清空后，该节点无法发起新的AI调用；重新选择可用模型后可恢复。',saveLabel:'确认清空',save:()=>model.assign({[item.id]:''},actor,revision,{confirmedClear:true}),success:'节点模型已清空，该节点当前不可用',onSaved:()=>focusKey('sm-node-'+item.id)});
  }
  function allocation(node){
    const host=el('div','prototype-ui sm-assignment');
    // 选择即保存本节点；不要求额外登记验收、核对审批或整组切换。
    PrototypeUI.mount(host,{
      data:()=>({selected:node.model,state:model.stateOf(node)}),
      mounted(){host.querySelector('input')?.setAttribute('data-focus','sm-node-'+node.id);},
      methods:{change(value){
        if(!value&&node.model&&!node.optional){this.selected=node.model;clearNode(node,host.querySelector('input'));return;}
        const error=model.assign({[node.id]:value||''},actor,model.assignmentRevision);
        if(error){this.selected=node.model;ui.notify(error);return;}
        this.state=model.stateOf(node);
        ui.notify(node.kind==='embedding'&&node.indexTarget?'配置已保存，匹配索引就绪后切换生效':node.name+'的模型分配已保存');
      }},
      render(){
        const {h}=Vue,choices=model.rows.filter(row=>row.kind===node.kind&&(row.state==='已启用'||row.id===node.model)),info=node.kind==='embedding'?model.indexInfo(node):null;
        return h('div',{},[
          h(ElementPlus.ElSelect,{modelValue:this.selected,'onUpdate:modelValue':value=>{this.selected=value;},onChange:this.change,filterable:true,clearable:true,disabled:!has('models.assign'),ariaLabel:'为'+node.name+'分配模型',placeholder:node.optional?'不使用':'未分配'},()=>choices.map(row=>h(ElementPlus.ElOption,{key:row.id,value:row.id,label:row.name+(row.state==='已停用'?'（已停用）':''),disabled:row.state!=='已启用'}))),
          !node.model&&!node.optional?h('small',{class:'sm-node-warning'},'未配置，当前不可用'):this.state==='模型已停用'?h('small',{class:'sm-node-warning'},this.state):null,
          info&&node.model?h('div',{class:'sm-index-state'},[
            h('span',{class:info.target||!info.available?'sm-node-warning':'cell-sub'},info.target?this.state:info.available?'已生效 · r'+info.active.revision:'当前不可用'),
            info.target?h('small',{class:'cell-sub'},'当前生效：'+(info.active?info.active.name+' · r'+info.active.revision:'无')):null,
            h(ElementPlus.ElButton,{link:true,type:'primary','data-focus':'sm-index-'+node.id,onClick:event=>indexDetail(node,event.currentTarget)},()=> '索引状态')
          ]):null
        ]);
      }
    });return host;
  }
  function nodes(){
    if(!queries.has('nodes'))queries.set('nodes',{keyword:'',module:'',status:'all',page:1,pageSize:20});const q=queries.get('nodes');
    main.append(RecordControls.filters({value:q,includeDates:false,includeStatus:false,fields:[field('keyword','节点名称或说明'),field('module','所属模块',{options:choices([...new Set(model.nodes.map(node=>node.module))])})],onApply:data=>{Object.assign(q,data);refresh();},onReset:()=>{queries.delete('nodes');refresh();}}));
    const rows=model.nodes.filter(node=>(!q.module||node.module===q.module)&&AdminQuery.matches([node.name,node.description],q.keyword));
    const panel=table('节点列表',rows,[{key:'node',label:'节点',width:190,grow:1},{key:'description',label:'这个节点做什么',width:400,grow:3},{key:'model',label:'分配模型',width:290,grow:0,fixed:'right'}],node=>{const title=el('div');title.append(el('span','sm-node-name',node.name),el('small','cell-sub',node.module+' · '+(node.kind==='embedding'?'Embedding':node.kind==='rerank'?'Rerank':'生成模型')));return [title,el('p','sm-node-description',node.description),allocation(node)];},q);panel.classList.add('sm-nodes-panel');main.append(panel);
  }
  const canViewSource=record=>record.context&&has(record.context.ability);
  function sourceDetail(record,trigger,type){
    if(!canViewSource(record)){ui.notify('当前账号无权查看来源任务。');return;}
    const context=record.context,node=ui.modal('来源任务 · '+context.taskId,trigger,true),body=el('div','dialog-body');
    body.append(facts([['来源任务',context.taskId],['所属模块',context.module],['数据源',context.sourceName],['业务对象（当次）',context.object],['任务操作',context.operation],['本次调用',record.id],['调用时间',time(record.at)],['调用状态',record.status]]));
    const footer=el('footer','dialog-footer');footer.append(button('返回调用详情',()=>{node.close();recordDetail(record,trigger,type);}));
    if(context.href)footer.append(button('前往业务对象',()=>{
      if(!canViewSource(record)){ui.notify('当前账号无权查看来源任务。');return;}
      // 保留调用列表的条件和入口；业务页仍按自身权限读取当前对象。
      sourceOrigin={record,type,hash:location.hash,target:context.href};node.close();location.hash=context.href;
    },true));
    else body.append(el('p','sm-prototype-hint','当前样例保留当次任务摘要，未提供可定位的业务记录。'));
    node.append(body,footer);node.showModal();
  }
  function context(){
    if(!sourceOrigin)return null;
    if(location.hash!==sourceOrigin.target){if(location.hash!==sourceOrigin.hash)sourceOrigin=null;return null;}
    if(!canViewSource(sourceOrigin.record))return null;
    const bar=el('div','sm-source-return'),source=sourceOrigin.record.context;
    bar.append(el('span','',source.taskId+' · '+source.object),button('返回调用记录',()=>{reopen=sourceOrigin;location.hash=sourceOrigin.hash;}));return bar;
  }
  function recordDetail(record,trigger,type){
    const node=ui.modal(type==='changes'?'配置变更详情':type==='tests'?'连接测试详情':'模型调用详情',trigger,true),body=el('div','dialog-body');
    const saved=type==='changes'?null:model.recordSnapshot(record);
    // 调用详情沿用概览的输入输出口径，缺失用量时显示未知。
    const usage = record.tokens == null ? '未知' : '输入 ' + record.inputTokens + ' · 输出 ' + record.outputTokens + ' Token';
    body.append(facts(type==='changes'?[['操作',record.action],['对象',record.object],['变更前',record.before||'无'],['变更后',record.after||'无'],['操作者',record.actor],['时间',time(record.at)]]:[['记录编号',record.id],['节点',record.nodeName||'连接测试'],['配置名称（当次）',saved?.name||'当次配置不可用'],['实际模型标识',saved?.content.model||'未知'],['当次配置修订','r'+record.revision],['状态',record.status],['时间',time(record.at)],['原因',record.reason||'固定样例成功'],['耗时',record.elapsed==null?'未知':record.elapsed+' 毫秒'],['用量',usage],['费用','未知']]));
    if(type==='calls'&&canViewSource(record))body.append(facts([['来源任务',record.context.taskId],['数据源',record.context.sourceName],['业务对象（当次）',record.context.object]]));
    else if(type==='calls'&&record.context)body.append(el('p','sm-notice','当前账号无权查看来源任务。'));
    if(saved&&has('models.view')){const evidence=el('details','sm-disclosure');evidence.append(el('summary','','查看当次配置快照'),snapshotFacts(saved));body.append(evidence);}
    if(type==='changes'&&has('models.view'))for(const [key,label] of [['beforeSnapshot','变更前配置快照'],['afterSnapshot','变更后配置快照']])if(record[key]){const evidence=el('details','sm-disclosure');evidence.append(el('summary','',label),snapshotFacts(record[key]));body.append(evidence);}
    if(record.delivered)body.append(el('p','sm-notice','查询结果已交付，解释未完成；此处不会重新执行SQL。'));
    if(record.status==='结果待核查')body.append(el('p','sm-notice','提供方是否处理及是否计费尚未核实，此处不会重放业务请求。'));
    const footer=el('footer','dialog-footer');footer.append(button('关闭',()=>node.close()));
    if(type==='calls'&&canViewSource(record))footer.append(button('查看来源任务',()=>{node.close();sourceDetail(record,trigger,type);},true));
    const row=model.find(record.model);if(row&&has('diagnostics.test')&&has('models.test'))footer.append(button('测试当前模型连接',()=>{node.close();test(row,trigger);}));node.append(body,footer);node.showModal();
  }
  function records(){
    const type = ['calls', 'tests', 'changes'].includes(tab) ? tab : 'calls';
    // 图表下钻保留到毫秒的区间，日历过滤仍用于诊断页自己的查询。
    const from = Number(overviewParams.get('from')), to = Number(overviewParams.get('to'));
    const scoped = type === 'calls' && Number.isFinite(from) && Number.isFinite(to) && from > 0 && to > from && to <= 8640000000000000;
    const state = overviewParams.get('state');
    const key = 'records-' + type + (scoped ? '/' + overviewParams : '');
    if (!queries.has(key)) {
      // 状态通过现有查询控件表达；管理员可以继续核对同一时段内的其他状态。
      queries.set(key, { keyword: '', node: '', status: scoped && ['成功', '失败', '结果待核查'].includes(state) ? state : 'all', fromDate: '', toDate: '', page: 1, pageSize: 10 });
    }
    const q = queries.get(key);
    main.append(tabs([['calls','调用记录'],['tests','连接测试'],['changes','配置变更']],type,value=>{location.hash='diagnostics/'+value;}));
    if (scoped) {
      // 定位保留图上的精确时段，可返回图表或清除条件回到诊断页。
      const bar = el('div', 'sm-source-return model-overview-scope');
      const actions = el('div', 'do-actions');
      const clear = el('a', 'do-action', '清除定位');
      const back = el('a', 'do-action is-primary', '返回模型运行概览');
      clear.href = '#diagnostics/calls';
      back.href = '#model-overview';
      actions.append(clear, back);
      bar.append(el('span', '', '调用时段 · ' + time(from).slice(0, 16) + ' 至 ' + time(to).slice(0, 16)), actions);
      main.append(bar);
    }
    main.append(RecordControls.filters({value:q,includeStatus:type!=='changes',dateLabel:'发生日期',fields:[field('keyword',type==='changes'?'配置对象或操作者':'模型名称或记录编号'),...(type==='calls'?[field('node','节点名称')]:[])],statuses:[{value:'all',label:'全部状态'},...choices(type==='tests'?['通过','失败']:['成功','失败','结果待核查'])],onApply:data=>{Object.assign(q,data);refresh();},onReset:()=>{queries.delete(key);if(scoped)location.hash='diagnostics/calls';else refresh();}}));
    const rows=(type==='calls'?model.calls:type==='tests'?model.tests:model.changes).filter(row=>{const saved=type==='changes'?null:model.recordSnapshot(row);return (!scoped||row.at>=from&&row.at<to)&&AdminQuery.matches(type==='changes'?[row.object,row.actor]:[row.id,row.model,saved?.name,saved?.content.model,model.find(row.model)?.name],q.keyword)&&(!q.node||AdminQuery.matches([row.nodeName],q.node))&&(q.status==='all'||row.status===q.status)&&(!q.fromDate||time(row.at).slice(0,10)>=q.fromDate)&&(!q.toDate||time(row.at).slice(0,10)<=q.toDate);}).slice().sort((a,b)=>b.at-a.at);
    main.append(table('模型调用与配置记录',rows,[{key:'model',label:type==='changes'?'操作者 / 对象':'当次模型 / 修订',width:220,grow:2},{key:'node',label:type==='changes'?'操作':'节点',width:190},{key:'state',label:type==='changes'?'变更结果':'状态',width:160},{key:'summary',label:type==='changes'?'配置变化':'原因',width:240,grow:2},{key:'at',label:'发生时间',width:185},{key:'actions',label:'操作',width:100,fixed:'right'}],row=>[ui.textCell(type==='changes'?row.actor+' / '+row.object:(model.recordSnapshot(row)?.name||'当次配置不可用')+' · r'+row.revision),ui.textCell(type==='changes'?row.action:row.nodeName||'连接测试'),type==='changes'?ui.textCell('已保存'):status(row.status),ui.textCell(type==='changes'?(row.before||'无')+' → '+(row.after||'无'):row.reason||'固定样例成功'),ui.textCell(time(row.at)),ui.button('查看详情',trigger=>recordDetail(row,trigger,type),'do-action is-primary','sm-record-'+type+'-'+row.id)],q));
  }
  function render(target,controls,current='models',selected='list',scope=new URLSearchParams()){
    // 每次从概览发起定位都应用当次条件，列表内部刷新继续保留已修改的查询。
    if (section === 'model-overview' && current === 'diagnostics' && scope.size) {
      queries.delete('records-calls/' + scope);
    }
    main=target;ui=controls;actor=controls.currentUser;section=current;tab=selected||'list';overviewParams=scope;
    const title = { 'model-overview': '模型运行概览', models: '模型配置', diagnostics: '调用记录与故障' }[current];
    ui.setTitle(title);main.replaceChildren(ui.pageHeading(title));
    // 三张图来自调用记录，沿用诊断查看权限。
    if (current === 'model-overview') {
      if (has('diagnostics.view')) ModelOverviewPage.render(main, ui);
      else main.append(el('p', 'sm-notice', '当前账号没有此页面的查看权限。'));
      return;
    }
    if(!has(current+'.view')){main.append(el('p','sm-notice','当前账号没有此页面的查看权限。'));return;}
    if(current==='models'){const add=tab!=='nodes'&&has('models.create')?ui.button('新增模型',trigger=>edit(null,trigger),'admin-button is-primary','sm-create'):null;main.append(tabs([['list','模型列表'],['nodes','节点列表']],tab==='nodes'?'nodes':'list',value=>{location.hash='models'+(value==='nodes'?'/nodes':'');},add));if(tab==='nodes')nodes();else list();}else records();
    main.append(el('p','sm-prototype-hint','离线原型，请填写演示凭据；连接测试与调用记录使用固定样例。'));
    if(reopen&&reopen.hash===location.hash){const saved=reopen;reopen=null;sourceOrigin=null;requestAnimationFrame(()=>{const trigger=main.querySelector('[data-focus="sm-record-'+saved.type+'-'+saved.record.id+'"]');recordDetail(saved.record,trigger,saved.type);});}
  }
  window.SystemModelsPage={render,context};
})();
