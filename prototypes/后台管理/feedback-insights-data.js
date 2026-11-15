/* 反馈改进的离线修订与核对记录。历史提醒和排序独立办理，不改写知识或执行检索。 */
(() => {
  'use strict';
  const clone=value=>structuredClone(value),text=value=>String(value??'').trim();
  const capabilities={policy:'policy',note:'insight-edit',preference:'insight-edit'};
  const permitted=(actor,ability)=>FeedbackDemo.has(actor,'feedback.view')&&FeedbackDemo.has(actor,'feedback.'+ability);
  function create(options={}){
    const feedback=options.feedback||FeedbackDemo,clock=options.now||Date.now;
    const rows=[],adoptions=[],reminders=[];let serial=0;
    const find=id=>rows.find(row=>row.id===id);
    const knowledge=source=>window.KnowledgeWorkspace?[...KnowledgeWorkspace.documents.values()].filter(doc=>doc.source===source).map(doc=>{
      const published=KnowledgeWorkspace.publishedContext([doc.id]);const live=published.documents?.find(item=>item.id===doc.id);
      return {value:doc.id,label:doc.title+(live?' · r'+live.revision:' · 未正式可用'),revision:live?.revision,token:live?JSON.stringify(live):'',available:!!live};
    }):[];
    function access(actor,source){return permitted(actor,'evidence')&&FeedbackDemo.has(actor,'query.view')&&FeedbackDemo.sourceAccess(actor,source);}
    function scopeToken(content){
      if(content.documentId){const doc=knowledge(content.sourceId).find(doc=>doc.value===content.documentId);return doc?.available?doc.token:'';}
      return content.kind==='技术'&&text(content.version)?JSON.stringify([content.sourceId,content.object,content.version]):'';
    }
    const reviewPolicy=source=>rows.find(row=>row.kind==='policy'&&row.sourceId===source)?.active?.content.independentReview===true;
    function visible(row,actor){return permitted(actor,'view')&&(row.kind==='policy'||access(actor,row.sourceId));}
    function validate(kind,data){
      if(!PeopleDemo.dataSources.some(source=>source.id===data.sourceId)||!text(data.title))return '请填写名称并选择有效数据源。';
      if(kind==='policy'){
        if(!['window','minimum','users','density','interval'].every(key=>Number.isFinite(data[key])))return '请填写完整的数值配置。';
        if(![data.window,data.minimum,data.users,data.interval].every(value=>Number.isInteger(value)&&value>0)||data.window>365||data.minimum>10000||data.interval>8760||data.users>data.minimum||data.density<=0||data.density>1)return '窗口为1–365天，反馈量不超过10000，独立人数不超过反馈量，重复密度大于0且不超过1，提醒间隔不超过8760小时。';
        if(!Array.isArray(data.recipients)||!data.recipients.length||data.recipients.some(id=>!PeopleDemo.users.some(user=>user.id===id&&user.status==='正常'&&permitted(user,'view'))))return '请选择具有反馈查看功能的正常接收人。';
      }else{
        if(!text(data.scenario)||!text(data.conditions)||!text(data.basis))return '请填写问题场景、适用条件和核实依据。';
        if(data.issueId&&!feedback.get(data.issueId)?.basis)return '关联事项尚无核查依据，不能直接作为已核实事实。';
        if(data.kind==='技术'&&!text(data.version))return '技术事项须填写确切技术版本。';
        if(kind==='note'&&(!['业务','技术','涉及业务口径的技术'].includes(data.kind)||!['未修正','已修正','待核实'].includes(data.correction)||!text(data.check)||!text(data.description)||data.kind==='技术'&&!text(data.object)))return '请填写注意事项、当前修正状态、本次检查内容和适用对象。';
        if(kind==='preference'&&(!Number.isFinite(data.threshold)||data.threshold<0||data.threshold>1||!Number.isFinite(data.limit)||data.limit<=0||data.limit>1))return '相关性门槛为0–1，调整上限须大于0且不超过1。';
      }
      return '';
    }
    function save(kind,row,data,actor,revision){
      if(!permitted(actor,capabilities[kind])||kind!=='policy'&&!access(actor,data.sourceId))return {error:'当前账号没有维护或业务证据权限。'};
      if(row&&(!rows.includes(row)||row.revision!==revision||row.kind!==kind))return {error:'记录已变化，请重新核对修订。'};
      if(row&&row.sourceId!==data.sourceId)return {error:'已有记录不能更换数据源，请另建记录。'};
      const error=validate(kind,data);if(error)return {error};
      if(kind==='policy'&&rows.some(item=>item!==row&&item.kind==='policy'&&item.sourceId===data.sourceId))return {error:'该数据源已有重点检查策略，请编辑原策略。'};
      const content=clone(data);delete content.refToken;content.refToken=kind==='policy'?'':scopeToken(content);
      if(row&&JSON.stringify(row.draft.content)===JSON.stringify(content))return {error:'内容没有变化。'};
      if(!row){row={id:'GJ-'+(++serial),kind,sourceId:data.sourceId,revision:0,versions:[],active:null,history:[]};rows.unshift(row);}
      if(kind==='note'&&row.active&&row.active.content.correction!==content.correction)row.active.suspendedReason='当前修正状态已变化，请重新核实后启用。';
      const contentRevision=(row.draft?.revision||0)+1;row.revision++;row.draft={revision:contentRevision,content,editor:actor.id,at:clock(),validation:null,reviews:[]};
      row.history.push({action:'保存草稿 r'+contentRevision,actor:actor.name,at:clock(),content:clone(content)});return {row};
    }
    function guard(row,actor,ability,revision){
      if(!row||!rows.includes(row)||!visible(row,actor)||!permitted(actor,ability))return '当前账号没有此操作权限。';
      if(row.revision!==revision)return '记录已变化，请重新核对当前修订。';
      return '';
    }
    // 校准和对照结果由人登记；通过只表示已核对所填证据，不声称原型执行了真实评测。
    function verify(row,data,actor,revision){
      const error=guard(row,actor,'insight-verify',revision);if(error)return error;
      if(!text(data.basis)||!text(data.result)||!['通过','不通过'].includes(data.decision))return '请填写样本或题单版本、实际结果及验证结论。';
      if(row.kind!=='policy'&&(!row.draft.content.refToken||scopeToken(row.draft.content)!==row.draft.content.refToken))return '缺少正式可用知识，或适用版本已变化，请修订后重新验证。';
      if(row.kind==='policy'&&(!data.falsePositive||!data.falseNegative))return '请记录误报和漏报检查结果。';
      if(row.kind==='preference'&&[data.positive,data.counter,data.restricted,data.regression].some(value=>value!==true))return '请核对正例、不同口径反例、受限及不可用知识、无关问题回归。';
      row.draft.validation={...clone(data),actor:actor.id,at:clock()};row.draft.reviews=[];
      row.revision++;row.history.push({action:'验证'+data.decision+' · r'+row.draft.revision,actor:actor.name,at:clock(),basis:data.basis,result:data.result});
    }
    function requiredReviews(content,kind){return kind==='policy'?['配置']:kind==='preference'||content.kind==='业务'?['业务']:content.kind==='技术'?['技术']:['业务','技术'];}
    function confirm(row,data,actor,revision){
      const error=guard(row,actor,'insight-confirm',revision);if(error)return error;
      if(row.draft.validation?.decision!=='通过')return '请先完成当前修订的验证并核对通过结果。';
      if(!requiredReviews(row.draft.content,row.kind).includes(data.duty)||!text(data.basis))return '请选择所需确认职责并填写依据。';
      if(data.duty==='业务'&&!FeedbackDemo.has(actor,'governance.confirm'))return '业务适用性须由具备治理确认权限的人员确认。';
      if(row.kind!=='policy'&&reviewPolicy(row.sourceId)&&[row.draft.editor,row.draft.validation.actor].includes(actor.id))return '当前范围已启用不同人员复核，请由另一位具备权限的人员确认。';
      row.draft.reviews=row.draft.reviews.filter(review=>review.duty!==data.duty);
      row.draft.reviews.push({...clone(data),actor:actor.id,at:clock()});row.revision++;row.history.push({action:data.duty+'确认 · r'+row.draft.revision,actor:actor.name,at:clock(),basis:data.basis});
    }
    function readiness(row,version=row?.draft){
      if(version?.suspendedReason)return version.suspendedReason;
      if(row?.kind==='note'&&version?.content.correction!==row.draft.content.correction)return '当前修正状态已变化，历史修订须重新核实。';
      if(row?.kind!=='policy'&&version&&(!version.content.refToken||scopeToken(version.content)!==version.content.refToken))return '缺少正式可用知识，或适用版本已变化；请绑定并重新验证。';
      if(!version||version.validation?.decision!=='通过')return '本修订尚未验证通过。';
      if(!PeopleDemo.users.some(user=>user.id===version.validation.actor&&permitted(user,'insight-verify')))return '验证人权限已变化，请重新验证。';
      if(requiredReviews(version.content,row.kind).some(duty=>!version.reviews.some(review=>review.duty===duty&&PeopleDemo.users.some(user=>user.id===review.actor&&permitted(user,'insight-confirm')&&(duty!=='业务'||FeedbackDemo.has(user,'governance.confirm'))))))return '所需适用性确认尚未完成或确认人权限已变化。';
      if(row.kind!=='policy'&&reviewPolicy(row.sourceId)&&version.reviews.some(review=>[version.editor,version.validation.actor].includes(review.actor)))return '当前范围需要不同人员复核。';
      return '';
    }
    function toggle(row,enable,reason,actor,revision){
      const error=guard(row,actor,'insight-toggle',revision);if(error)return error;
      if(!text(reason))return '请填写启停依据。';
      if(enable){const issue=readiness(row);if(issue)return issue;
        if(row.active?.enabled&&row.active.revision===row.draft.revision)return '此修订已经启用。';
        const version={...clone(row.draft),enabled:true,at:clock()};row.active=version;
        if(!row.versions.some(item=>item.revision===version.revision))row.versions.push(clone(version));
      }else{if(!row.active?.enabled)return '当前没有已启用修订。';row.active.enabled=false;}
      row.revision++;row.history.push({action:(enable?'启用':'停用')+' r'+row.active.revision,actor:actor.name,at:clock(),basis:reason});
    }
    function rollback(row,target,reason,actor,revision){
      const error=guard(row,actor,'insight-toggle',revision);if(error)return error;
      const version=row.versions.find(item=>item.revision===Number(target));
      if(!version||row.active?.revision===version.revision||!text(reason))return '请选择其他历史生效修订并填写回退依据。';
      const issue=readiness(row,version);if(issue)return issue;
      row.active={...clone(version),enabled:true,at:clock()};row.revision++;row.history.push({action:'回退到 r'+version.revision,actor:actor.name,at:clock(),basis:reason});
    }
    const current=row=>row.active?.enabled&&!readiness(row,row.active)?row.active:null;
    function counts(row){
      const source=row.sourceId,content=row.draft.content,since=clock()-content.window*86400000;
      // 同用户同回答只取最后有效评价，撤销和改赞不计点踩；同回答不随关联对象重复计数。
      const latest=new Map();feedback.evaluations.filter(event=>feedback.items.some(item=>item.sourceId===source&&item.origins.some(origin=>origin.userId===event.userId&&origin.answerId===event.answerId))&&event.at>=since&&event.at<=clock()).forEach(event=>{const key=event.userId+'/'+event.answerId;if(!latest.has(key)||latest.get(key).at<=event.at)latest.set(key,event);});
      const valid=[...latest.values()].filter(event=>event.reaction?.type==='down'),answers=new Set(valid.map(event=>event.answerId)),users=new Set(valid.map(event=>event.userId));
      const grouped=new Map();for(const event of valid){const item=feedback.items.find(item=>item.origins.some(origin=>origin.userId===event.userId&&origin.answerId===event.answerId)),root=item&&feedback.root(item);if(!root)continue;if(!grouped.has(root.id))grouped.set(root.id,[]);grouped.get(root.id).push(event);}
      const groups=[...grouped].map(([id,events])=>{const item=feedback.get(id),count=new Set(events.map(event=>event.answerId)).size;return {id,feedback:events.length,answers:count,users:new Set(events.map(event=>event.userId)).size,density:count>1?(count-1)/count:0,version:JSON.stringify(feedback.family(item).map(member=>[member.id,member.mergedInto||''])),closed:FeedbackDemo.closed(item)};});
      return {feedback:valid.length,answers:answers.size,users:users.size,density:groups.length?Math.max(...groups.map(group=>group.density)):0,groups};
    }
    function observation(row,actor){
      if(!visible(row,actor))return null;
      const active=current(row),content=active?.content||row.draft.content;
      const metrics=counts({...row,draft:{content}}),matches=active?metrics.groups.filter(group=>!group.closed&&group.feedback>=content.minimum&&group.users>=content.users&&group.density>=content.density):[];
      return {...metrics,matches,state:matches.length?'达到重点检查条件':'观察线索',reason:!active?'没有有效启用配置':!matches.length?'同组样本或重复密度未达到策略条件':'同组未结事项在提醒间隔内合并展示',window:content.window,interval:content.interval,version:active?.revision||null};
    }
    function inspect(row,actor){
      if(!visible(row,actor)||row.kind!=='policy')return {error:'当前账号没有查看策略权限。'};
      const result=observation(row,actor),active=current(row);if(!active)return result;
      const recipients=active.content.recipients.filter(id=>PeopleDemo.users.some(user=>user.id===id&&user.status==='正常'&&permitted(user,'view')));if(!recipients.length)return {...result,error:'接收人已不可用，请更新策略。'};
      for(const group of result.matches){
        const previous=[...reminders].reverse().find(item=>item.policy===row.id&&item.group===group.id);
        if(previous&&clock()-previous.at<active.content.interval*3600000){Object.assign(previous,{metrics:clone(group),checkedAt:clock()});continue;}
        reminders.push({id:'TX-'+(++serial),policy:row.id,revision:active.revision,group:group.id,groupVersion:group.version,metrics:clone(group),recipients:clone(recipients),at:clock()});
      }
      return result;
    }
    // 显式评审输入匹配确切版本和场景，不把字符串相似度伪装成真实语义检索。
    function preview(row,data,actor){
      if(!visible(row,actor))return {error:'当前账号没有业务证据权限。'};
      const active=current(row);if(!active)return {error:row.active?.enabled?readiness(row,row.active):'当前未启用，调整量为零，不补入上下文。'};
      const content=active.content;
      if(data.scenario!==content.scenario||data.conditions!==content.conditions)return {error:'业务场景或条件不匹配。'};
      if(content.documentId!==data.documentId||content.version!==data.version)return {error:'使用版本不匹配。'};
      if(row.kind==='preference'){
        if(data.eligible!==true||!Number.isFinite(data.score)||data.score<content.threshold||data.score>1)return {error:'未通过权限、发布、暂停或相关性门槛，调整量为零。'};
        return {revision:active.revision,adjustment:Math.min(content.limit,1-data.score),message:'仅在合法有效候选内按本修订上限调整；不改变知识正文。'};
      }
      if(data.correction!==content.correction)return {error:'当前修正状态已变化，须重新核实注意事项。'};
      return {revision:active.revision,context:(content.correction==='已修正'?'历史问题已修正。':'')+content.description+'；适用条件：'+content.conditions+'；本次检查：'+content.check};
    }
    function recordPreview(row,data,actor){const result=preview(row,data,actor);adoptions.push({id:row.id,revision:result.revision||null,actor:actor.id,at:clock(),accepted:!result.error,...clone(result)});return result;}
    if(options.seed!==false){
      const actor=PeopleDemo.users[0];
      for(const source of PeopleDemo.dataSources.slice(0,3))save('policy',null,{sourceId:source.id,title:source.name+'重点检查',window:7,minimum:5,users:3,density:0.2,interval:24,recipients:[actor.id],independentReview:false},actor);
      save('note',null,{sourceId:'products',title:'商品编码前导零展示核对',issueId:'',kind:'技术',object:'商品编码展示',version:'DEMO-UI-r1',scenario:'商品编码展示',conditions:'商品编码为带前导零的文本',correction:'已修正',description:'曾出现文本编码按数值展示而丢失前导零；固定样例已修正展示。',check:'核对0001等编码仍按文本显示，不改变正式口径。',basis:'离线商品编码展示样例与人工复查记录。'},actor);
      save('preference',null,{sourceId:'products',title:'商品原销售数量口径优先候选',issueId:'',kind:'业务',scenario:'查询商品原销售数量',conditions:'原销售数量不扣除退货；不适用于净销量问题',documentId:'',version:'',basis:'依据已确认的原销售数量与净销量边界提出候选，目标知识发布后另行绑定验证。',threshold:0.65,limit:0.05},actor);
    }
    return {rows,adoptions,reminders,find,knowledge,save,validate,verify,confirm,toggle,rollback,readiness,current,visible,access,permitted,observation,inspect,preview,recordPreview,requiredReviews};
  }
  window.FeedbackInsights=Object.assign(create(),{create});
})();
