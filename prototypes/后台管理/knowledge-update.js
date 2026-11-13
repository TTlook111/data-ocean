/* 复用后台外壳与治理阅读尺度，承接变更知识更新，不搭建真实发布服务。 */
(() => {
  'use strict';
  const demo = KnowledgeUpdateDemo, queries = new Map(), origins = new Map();
  let main, ui;
  const el = (tag, cls, text) => ui.el(tag, cls, text);
  const allowed = ability => SourceApprovalDemo.hasAbility(ui.currentUser, ability);
  const refresh = () => window.dispatchEvent(new Event('hashchange'));
  const button = (text, action, primary = false, key) => ui.button(text, action, 'admin-button' + (primary ? ' is-primary' : ''), key);
  const badge = text => el('span', 'status-tag' + (text === '验证未通过' ? ' ds-status-danger' : text !== '已完成' ? ' is-pending' : ''), text);
  const path = impact => '#knowledge/' + encodeURIComponent(impact.source) + '/' + encodeURIComponent(impact.id);
  function open(impact) {
    const host=document.getElementById('admin-main'),scroller=host?.querySelector('.rt-scroll,.gov-content');
    origins.set(impact.id, { hash: location.hash, focus: document.activeElement?.dataset.focus, top: host?.scrollTop || 0, contentTop:scroller?.scrollTop||0, left:scroller?.scrollLeft||0 });
    DataSourceDemo.selected = impact.source; location.hash = path(impact);
  }
  function run(record, action, data) {
    const error = demo.act(record, action, ui.currentUser, data); if (error) return ui.notify(error);
    refresh(); ui.notify(action === 'allow' ? '已解除禁止并完成本次变更处理' : '处理结果已更新');
  }
  function generate(impact) {
    const result = demo.start(impact, ui.currentUser); if (result.error) return ui.notify(result.error);
    refresh(); ui.notify('已生成知识更新草稿');
  }
  function edit(record, trigger) {
    const revision = record.revision;
    ui.openForm({ title: '编辑知识草稿 · ' + record.title, trigger, value: { note: record.note }, fields: [
      { key: 'note', label: '知识正文', type: 'textarea', required: true, full: true }
    ], note: '业务含义或计算口径需要变动时，返回治理修改并审核。', saveLabel: '保存草稿', save: data => demo.act(record, 'save', ui.currentUser, data, revision), onSaved: refresh, success: '草稿已保存，等待确认' });
  }
  function publish(record, trigger) {
    const candidate = demo.current(record), revision = record.revision;
    ui.openForm({ title: '确认发布 · ' + record.title, trigger, value: { source: DataSourceDemo.find(record.source).name, scope: record.mode + ' · ' + record.entries.map(entry=>entry.object+' / '+entry.name).join('、'), candidate: candidate.id, result: candidate.attempts.at(-1)?.result }, fields: [
      { key: 'source', label: '数据源', readonly: true }, { key: 'candidate', label: '候选', readonly: true },
      { key: 'scope', label: '本次发布范围', readonly: true, full: true }, { key: 'result', label: '验证结果', readonly: true }
    ], note: record.entries.some(entry=>entry.blocked&&!entry.retired) ? '发布后相关内容仍保持禁止使用；需有权人员核对并显式解除禁止。' : record.mode==='停用依赖知识' ? '发布后停用本次范围内的依赖知识；已删除字段保持禁止使用。' : '', saveLabel: '确认发布', save: () => demo.act(record,'publish',ui.currentUser,{candidateId:candidate.id},revision), onSaved: refresh, success: '本次范围已发布' });
  }
  function allow(record, trigger) {
    const revision=record.revision;
    ui.openForm({ title:'解除禁止 · '+record.title, trigger, value:{release:record.release.id,scope:record.entries.map(entry=>entry.object+' / '+entry.name).join('、')}, fields:[
      {key:'release',label:'已验证发布',readonly:true}, {key:'scope',label:'解除范围',readonly:true,full:true}
    ], note:'确认后允许本次范围内的对象再次参与查询；提交时重新核对来源可用性、治理修订和其他未完成变更。', saveLabel:'确认解除禁止', danger:true, save:()=>demo.act(record,'allow',ui.currentUser,{},revision), onSaved:refresh, success:'已显式解除禁止，变更处理完成' });
  }
  function list(source, section) {
    if(!queries.has(source.id)) queries.set(source.id,{keyword:'',status:'all',page:1,pageSize:10});
    const query=queries.get(source.id), all=demo.list(source.id), state=impact=>demo.records.get(impact.id)?.state||'待生成草稿';
    const rows=all.filter(impact=>AdminQuery.matches([impact.title,impact.targets],query.keyword)&&(query.status==='all'||query.status===state(impact))).sort((a,b)=>b.at.localeCompare(a.at)||b.id.localeCompare(a.id));
    const context=el('section','gov-context prototype-ui');
    PrototypeUI.mount(context,{data:()=>({selected:source.id,sources:DataSourceDemo.sources}),methods:{change(id){DataSourceDemo.selected=id;location.hash='#'+section+'/'+encodeURIComponent(id);}},template:'<label id="ku-source-label">当前数据源</label><el-select v-model="selected" aria-labelledby="ku-source-label" @change="change"><el-option v-for="source in sources" :key="source.id" :value="source.id" :label="source.name"></el-option></el-select>'});
    const tools=RecordControls.filters({value:query,includeDates:false,fields:[{key:'keyword',label:'变更对象或影响内容'}],statuses:['全部更新状态','待生成草稿','草稿','待构建','待验证','验证未通过','被阻断','待发布','已发布待解除禁止','已完成'].map((label,index)=>({label,value:index?label:'all'})),statusLabel:'知识更新状态',onApply:value=>{Object.assign(query,value);refresh();},onReset:()=>{Object.assign(query,{keyword:'',status:'all',page:1});refresh();}});
    query.page=Math.max(1,Math.min(query.page,Math.ceil(rows.length/query.pageSize)));
    const panel=el('section','record-panel'),empty=el('div','empty-state');empty.append(WorkbenchIcon('file-text'),el('p','',all.length?'没有匹配的知识更新':'暂无待更新知识'));
    if(!all.length&&allowed('governance.view')) empty.append(button('前往治理工作台',()=>{location.hash='#governance/'+source.id;}));
    panel.append(RecordTable.render({caption:'变更知识更新',columns:[{key:'title',label:'变更对象 / 影响内容',width:250},{key:'state',label:'知识更新状态',width:160},{key:'candidate',label:'当前候选',width:150},{key:'at',label:'发现时间',width:170},{key:'actions',label:'操作',width:100,fixed:'right'}],items:rows.slice((query.page-1)*query.pageSize,query.page*query.pageSize),empty,row:impact=>{
      const record=demo.records.get(impact.id),name=el('div');name.append(el('strong','',impact.title),el('small','cell-sub',impact.targets));
      return [name,badge(state(impact)),ui.textCell(record?.currentId||'尚未构建'),ui.textCell(impact.at),ui.button('查看处理',()=>open(impact),'do-action is-primary','ku-open-'+impact.id)];
    }}),RecordControls.pager({...query,total:rows.length},page=>{query.page=page;refresh();},pageSize=>{query.pageSize=pageSize;query.page=1;refresh();}));
    main.replaceChildren(ui.pageHeading(section==='knowledge'?'知识文档':'发布管理'),context,tools,panel);
  }
  function detail(impact) {
    const record=demo.records.get(impact.id),origin=origins.get(impact.id),back=origin?.hash||'#releases/'+impact.source;
    const parent=back.startsWith('#governance/')?'治理详情':back.startsWith('#collections')?'采集记录':back.startsWith('#business-notes')?'企业业务说明':back.startsWith('#catalog')?(back.split('/')[2]==='changes'?'结构变化':'元数据目录'):back.startsWith('#knowledge')?'知识文档':'发布管理';
    ui.setTitle('知识更新 · '+impact.title,parent,back);
    const shell=el('section','gov-shell'),header=el('header','gov-toolbar'),identity=el('div','gov-identity');
    identity.append(el('strong','',impact.title),el('small','',DataSourceDemo.find(impact.source).name+(record?.release?' · '+record.release.id:'')));
    header.append(identity,badge(record?.state||'待生成草稿'),button('返回'+parent,()=>{location.hash=back;requestAnimationFrame(()=>requestAnimationFrame(()=>{const host=document.getElementById('admin-main'),scroller=host?.querySelector('.rt-scroll,.gov-content');if(host)host.scrollTop=origin?.top||0;if(scroller){scroller.scrollTop=origin?.contentTop||0;scroller.scrollLeft=origin?.left||0;}const node=(origin?.focus&&host?.querySelector('[data-focus="'+CSS.escape(origin.focus)+'"]'))||host?.querySelector('[data-entry-title],button');node?.focus({preventScroll:true});}));}));shell.append(header);
    const content=el('div','gov-content'),body=el('article','gov-draft');
    const change=DataSourceDemo.changes.find(change=>impact.kind==='结构变化'&&change.source===impact.source&&change.id===impact.originId);
    const section=(title,...nodes)=>{const host=el('section');host.append(el('h2','',title),...nodes);body.append(host);return host;};
    if(record?.release)section(impact.completion?'完成结果':'发布后续',el('p','ds-prose',impact.completion||'本次范围已发布；相关对象仍禁止使用，等待有权人员显式解除。'));
    if(change) section('本次变更',el('p','ds-prose',change.before+' → '+change.after),...(change.resolution?[el('p','ds-prose',change.resolution)]:[]),...(change.reason?[el('p','ds-prose','来源说明：'+change.reason)]:[]));
    const ready=demo.readiness(impact);if(ready)body.append(el('p','ds-notice',ready));
    const stale=record&&record.signature!==demo.signature(impact);if(stale)body.append(el('p','ds-notice','治理修订已变化，需重新生成草稿、构建并验证候选。'));
    if(record){
      const scope=section('知识更新范围',el('p','ds-prose',record.mode+' · '+(record.dependencies.map(item=>item.name).join('、')||record.entries.map(entry=>entry.name).join('、'))));
      record.entries.forEach(entry=>{const item=el('details','gov-basis');item.append(el('summary','',entry.object+' / '+entry.name+' · 治理修订 '+entry.revision),el('p','',Object.values(entry.content).filter(Boolean).join('\n')));scope.append(item);});
      section('知识正文',el('p','ds-prose',record.note));
      const tests=demo.casesFor(impact),testSection=section('相关已审核题单');
      tests.forEach(test=>testSection.append(el('p','ds-prose',test.id+' · 修订 '+test.revision+' · '+test.question+'\n预期：'+test.expected)));
      if(!tests.length){testSection.append(el('p','ds-prose','暂无相关已审核题，补题并审核后才能验证发布。'));if(allowed('test-cases.view'))testSection.append(button('前往标准题单',()=>{location.hash='#test-cases';}));}
      const candidate=demo.current(record);
      if(candidate){
        const host=section('当前候选',el('p','ds-prose',candidate.id+' · 草稿修订 '+candidate.revision+' · '+candidate.at));
        candidate.attempts.forEach(attempt=>host.append(el('p','ds-prose',attempt.result+' · '+attempt.actor+' · '+attempt.at+'\n'+attempt.evidence)));
        if(!candidate.attempts.length)host.append(el('p','ds-prose','尚未运行验证'));
      }
      if(record.release)section('发布记录',el('p','ds-prose',record.release.id+' · '+record.release.actor+' · '+record.release.at));
      if(record.releases.length)section('以往发布',...record.releases.map(release=>el('p','ds-prose',release.id+' · '+release.candidate+' · '+release.actor+' · '+release.at)));
      const history=section('处理记录');record.events.forEach(event=>history.append(el('p','ds-prose',event.action+' · '+event.actor+' · '+event.at+'\n'+event.reason)));
    }else section('待生成知识更新草稿',el('p','ds-prose','受影响业务定义已审核，可生成对应范围的知识更新草稿。'));
    content.append(body);shell.append(content);
    const bar=el('footer','gov-page-footer');let count=0;
    const add=(label,fn,ability,primary=false,key)=>{if(allowed(ability)){bar.append(button(label,fn,primary,key));count++;}};
    if(!record||stale){if(!ready)add(record?'重新生成草稿':'生成更新草稿',()=>generate(impact),'knowledge.generate',true,'ku-generate');}
    else if(record.state==='已发布待解除禁止')add('解除禁止',trigger=>allow(record,trigger),'governance.allow',true,'ku-allow');
    else if(record.state!=='已完成'){
      add('编辑知识草稿',trigger=>edit(record,trigger),'knowledge.edit',false,'ku-edit');
      if(!record.confirmed)add('确认草稿',()=>run(record,'confirm'),'knowledge.confirm',true,'ku-confirm');
      else if(record.state==='待构建')add('构建候选',()=>run(record,'build'),'releases.build',true,'ku-build');
      else if(['待验证','验证未通过','被阻断'].includes(record.state))add('运行候选验证',()=>run(record,'test',{candidateId:demo.current(record).id}),'releases.test',true,'ku-test');
      else if(record.state==='待发布')add('确认发布',trigger=>publish(record,trigger),'releases.publish',true,'ku-publish');
    }
    if(allowed('governance.view'))bar.prepend(button('回看治理依据',()=>GovernancePage.openImpact(impact,{from:'knowledge-update'})));
    if(!count&&record?.state!=='已完成')bar.append(el('span','gov-current-item','当前账号可回看；下一步由具备对应操作权限的人员处理。'));
    shell.append(bar);main.replaceChildren(ui.pageHeading('知识更新'),shell);
  }
  function render(target,controls,section,sourceId,impactId){
    main=target;ui=controls;
    if(!allowed('knowledge.view')||!allowed(section==='knowledge'?'knowledge.view':'releases.view')){main.replaceChildren(ui.pageHeading('不可访问'),el('p','ds-notice is-danger','当前账号没有此页面或知识内容的查看权限。'));return;}
    const source=DataSourceDemo.find(sourceId||DataSourceDemo.selected);
    if(!source){main.replaceChildren(ui.pageHeading('不可访问'),el('p','ds-notice','找不到当前数据源。'));return;}
    DataSourceDemo.selected=source.id;
    if(impactId){const impact=GovernanceImpactDemo.records.find(item=>item.id===impactId&&item.source===source.id);if(!impact||(!demo.records.has(impactId)&&impact.state!=='知识处理中')){main.replaceChildren(ui.pageHeading('知识更新不可访问'),el('p','ds-notice','该变化尚未进入知识更新阶段。'));return;}detail(impact);}
    else list(source,section);
  }
  window.KnowledgeUpdatePage={render,open};
})();
