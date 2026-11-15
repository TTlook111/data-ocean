/* 同一标签页从知识后台进入问数时，仅携带已发布且可用的正式内容，不传草稿或原始附件。 */
(() => {
  'use strict';
  const key = 'dataocean.query.published-context.v1';
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
  window.addEventListener('pagehide', capture);
})();
