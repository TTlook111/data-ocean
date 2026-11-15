/* 同标签页问数往返保留后台已保存状态；问数检索只读取正式内容，后台交接快照不作为回答依据。 */
(() => {
  'use strict';
  const key = 'dataocean.query.published-context.v1';
  const handoffKey='dataocean.query.workspace-handoff.v1';
  const isQuery=url=>/\/prototypes\/app\/index\.html$/.test(url.pathname);
  let leavingForQuery=false;
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
    restoreHandoff();
    document.addEventListener('click',event=>{
      const link=event.target.closest?.('a[href]');if(!link||event.defaultPrevented||event.button!==0||event.ctrlKey||event.metaKey||event.shiftKey||event.altKey||link.target&&link.target!=='_self')return;
      const target=new URL(link.href,location.href);leavingForQuery=target.origin===location.origin&&isQuery(target);
    });
  }
  window.addEventListener('pagehide',()=>{capture();saveHandoff();});
  window.addEventListener('pageshow',event=>{
    if(!event.persisted)return;
    if(window.KnowledgeWorkspace)try{sessionStorage.removeItem(handoffKey);}catch{/* 无交接存储时继续使用当前页面。 */}
    if(window.FeedbackDemo?.refreshSnapshot())window.dispatchEvent(new Event('feedback-prototype-refresh'));
  });
})();
