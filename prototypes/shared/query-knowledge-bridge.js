/* 同标签页问数往返保留后台已保存状态；问数检索只读取正式内容，后台交接快照不作为回答依据。 */
(() => {
  'use strict';
  const key = 'dataocean.query.published-context.v1';
  const handoffKey='dataocean.query.workspace-handoff.v1';
  const accessKey='dataocean.query.access-handoff.v1';
  const isQuery=url=>/\/prototypes\/app\/index\.html$/.test(url.pathname);
  const isAdmin=url=>/\/prototypes\/后台管理\/index\.html$/.test(decodeURIComponent(url.pathname));
  let leavingForQuery=false;
  let crossingWorkspace=false;
  // 仅交接同标签页已保存的示例授权；刷新后台仍恢复初始演示，不建设真实持久化。
  function saveAccess() {
    if(!crossingWorkspace)return;
    if(window.DataSourceDemo)for(const source of PeopleDemo.dataSources){const current=DataSourceDemo.sources.find(row=>row.id===source.id);if(current)source.connectionState=current.connectionState;}
    try { const previous=JSON.parse(sessionStorage.getItem(accessKey));sessionStorage.setItem(accessKey,JSON.stringify({version:1,
      people:Object.fromEntries(['users','departments','outputRules','extraRequests','dataSources'].map(key=>[key,PeopleDemo[key]])),
      roles:window.RoleDemo?.records,grants:window.GrantDemo?.navigationSnapshot()||previous?.grants,audit:window.AuditDemo?.navigationSnapshot()||previous?.audit})); } catch { /* 不把未交接的状态当成生效结果。 */ }
  }
  function restoreAccess(cached=false) {
    if(typeof location==='undefined'||typeof document==='undefined')return;
    try {
      const navigation=performance.getEntriesByType('navigation')[0]?.type,current=new URL(location.href);
      if(isAdmin(current)&&navigation==='reload'&&!cached){sessionStorage.removeItem(accessKey);return;}
      const previous=document.referrer&&new URL(document.referrer);
      if(!cached&&navigation!=='back_forward'&&(!previous||previous.origin!==current.origin||!(isQuery(previous)&&isAdmin(current)||isAdmin(previous)&&isQuery(current))))return;
      const saved=JSON.parse(sessionStorage.getItem(accessKey));if(saved?.version!==1)return;
      const restore=(rows,next)=>{const values=next.map(value=>{const old=rows.find(row=>row.id===value.id);if(!old)return value;Object.keys(old).forEach(key=>delete old[key]);return Object.assign(old,value);});rows.splice(0,rows.length,...values);};
      for(const key of ['users','departments','outputRules','extraRequests','dataSources'])restore(PeopleDemo[key],saved.people[key]);
      if(saved.roles&&window.RoleDemo)restore(RoleDemo.records,saved.roles);
      if(saved.grants&&window.GrantDemo)GrantDemo.restoreNavigation(saved.grants);
      if(saved.audit&&window.AuditDemo)AuditDemo.restoreNavigation(saved.audit);
    } catch { /* 损坏交接沿用当前页面，不把部分授权当成有效授权。 */ }
  }
  function saveHandoff(){
    if(!window.KnowledgeWorkspace||!leavingForQuery)return;
    try{sessionStorage.removeItem(handoffKey);sessionStorage.setItem(handoffKey,JSON.stringify({version:1,source:DataSourceDemo.navigationSnapshot(),governance:GovernanceDemo.navigationSnapshot(),
      impacts:GovernanceImpactDemo.records,updates:KnowledgeUpdateDemo.navigationSnapshot(),workspace:KnowledgeWorkspace.navigationSnapshot()}));}catch{/* 无法交接时不冒充已保存。 */}
  }
  function restoreHandoff(){
    if(!window.KnowledgeWorkspace)return;
    try{
      const saved=JSON.parse(sessionStorage.getItem(handoffKey));sessionStorage.removeItem(handoffKey);
      // 交接只消费一次，且只接受从问数页面返回；刷新仍从初始示例开始。
      const previous=document.referrer&&new URL(document.referrer);
      const navigation=performance.getEntriesByType('navigation')[0]?.type;
      if(!saved||saved.version!==1||navigation==='reload'||navigation!=='back_forward'&&(!previous||previous.origin!==location.origin||!isQuery(previous)))return;
      DataSourceDemo.restoreNavigation(saved.source);GovernanceDemo.restoreNavigation(saved.governance);
      GovernanceImpactDemo.records.splice(0,GovernanceImpactDemo.records.length,...saved.impacts);
      KnowledgeUpdateDemo.restoreNavigation(saved.updates);KnowledgeWorkspace.restoreNavigation(saved.workspace);
    }catch{/* 交接损坏时沿用初始原型，不把不完整状态作为有效发布。 */}
  }
  function capture() {
    if (!window.KnowledgeWorkspace) return;
    const workspace = window.KnowledgeWorkspace, sources = {};
    for (const source of PeopleDemo.dataSources) {
      const ids = [...workspace.active.keys()].filter(id => workspace.documents.get(id)?.source === source.id);
      const usable = ids.filter(id => !workspace.publishedContext([id]).error);
      sources[source.id] = usable.length ? workspace.publishedContext(usable) : { error: '当前数据源尚无可用的已发布知识。' };
      // 复用发布候选冻结的验证数据，不从后来修改的整理稿或治理草稿取数。
      const samples = usable.flatMap(id => {
        const task = workspace.tasks.get(workspace.active.get(id).release), sample = task && workspace.candidate(task)?.sample;
        return sample?.rows ? [sample] : [];
      });
      if (samples.length) sources[source.id].sample = structuredClone(samples[0]);
    }
    try { sessionStorage.setItem(key, JSON.stringify({ sources, at: Date.now() })); } catch { /* 禁用存储时不冒充已连接发布上下文。 */ }
  }
  function read(source) {
    try { return JSON.parse(sessionStorage.getItem(key))?.sources?.[source]; } catch { return null; }
  }
  window.QueryKnowledgeBridge = { capture, read };
  if(typeof document!=='undefined'){
    restoreAccess();
    restoreHandoff();
    document.addEventListener('click',event=>{
      const link=event.target.closest?.('a[href]');if(!link||event.defaultPrevented||event.button!==0||event.ctrlKey||event.metaKey||event.shiftKey||event.altKey||link.target&&link.target!=='_self')return;
      const target=new URL(link.href,location.href),current=new URL(location.href);leavingForQuery=target.origin===location.origin&&isQuery(target);
      crossingWorkspace=target.origin===current.origin&&(isAdmin(current)&&isQuery(target)||isQuery(current)&&isAdmin(target));
    });
  }
  window.addEventListener('pagehide',()=>{capture();saveHandoff();saveAccess();});
  window.addEventListener('pageshow',event=>{
    if(!event.persisted)return;
    restoreAccess(true);
    window.dispatchEvent(new Event('access-prototype-refresh'));
    if(window.KnowledgeWorkspace)try{sessionStorage.removeItem(handoffKey);}catch{/* 无交接存储时继续使用当前页面。 */}
    if(window.FeedbackDemo?.refreshSnapshot())window.dispatchEvent(new Event('feedback-prototype-refresh'));
  });
})();
