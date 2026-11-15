/* 离线问题事项：只记录线索、人工办理和验证依据；不执行用户文本，不修改业务定义或授权。 */
(() => {
  'use strict';
  const storageKey = 'dataocean.feedback.prototype.v1';
  const statuses = ['待分派', '待补充', '处理中', '待验证', '已解决', '其他结案', '已合并'];
  const closed = row => ['已解决', '其他结案', '已合并'].includes(row.status);
  const clone = value => structuredClone(value), text = value => String(value || '').trim();
  const has = (actor, ability) => window.SourceApprovalDemo?.hasAbility(actor, ability) || false;
  const person = id => PeopleDemo.users.find(user => user.id === id);
  const sourceName = id => PeopleDemo.dataSources.find(source => source.id === id)?.name || '来源不可用';
  // 共用既有示例访问来源，不从部门层级推导记录过滤，也不因分派授予访问。
  function sourceAccess(actor, source) {
    if (!actor || actor.status !== '正常') return false;
    const departments = [actor.department, ...actor.extra];
    return PeopleDemo.departments.some(row => departments.includes(row.id) && row.status === '正常' && row.access.some(access => access.source === source))
      || PeopleDemo.extraRequests.some(row => row.user === actor.id && row.status === '已生效' && row.access.some(access => access.source === source));
  }
  // 反馈功能统一取个人角色；具体处理核对分派，业务证据仍沿用原有数据访问。
  const can = (actor,row,ability='view') => Boolean(row) && has(actor,'feedback.view') && has(actor,'feedback.'+ability);
  const evidenceAccess = (actor,row) => can(actor,row) && has(actor,'feedback.evidence') && has(actor,'query.view') && sourceAccess(actor,row.sourceId);
  const displayTitle = (actor,row) => evidenceAccess(actor,row)?row.title:'反馈事项 · '+row.id;
  const activeTask = task => !['无需变更','已取消'].includes(task.disposition);
  const taskState = task => !activeTask(task)?task.disposition:task.result?'已提交，待验证':task.followup?.stage==='waiting'?(task.kind==='源数据修正'?'等待对方修正或数据更新':'等待修复生效'):task.followup?'跟进中':'尚未开始';
  const receiptText = (status,reason) => status==='已解决'?'你提交的反馈已完成处理并通过验证，可重新查询原问题。':reason==='未复现'?'本次核查未能复现问题，已保留反馈，可在原问中补充新的线索。':reason==='按现有口径确认无误'?'已核查现有口径并说明结案，未修改业务定义。':reason==='取消'?'本次反馈已按核查依据取消处理。':'本次反馈已完成核查并说明结案，未计为修复解决。';
  const publicAccess = (actor,source) => has(actor,'query.view')&&sourceAccess(actor,source);
  const limitedReceipt = '该反馈已完成处理，详细结果受当前权限限制。';
  const publicActions = new Set(['收到点踩原因','用户已改为点赞','用户已撤销评价','接收问数知识缺口','接收技术失败','分派事项','记录核查结论','请求补充','收到用户补充','新增处理任务','任务无需变更','任务已取消','任务恢复','重新分派处理任务','提交处理结果','记录处理进展','生效后复验通过','生效后复验不通过','已解决','其他结案','重开事项','纠正合并','回告已投递','回告待提示','回告提示失败','回告结果待核查','回告未发送','核查回告投递','接收示例线索']);
  const publicAction = value => publicActions.has(value)||/^(合并到|关联重复事项|解除重复关联)WT-\d+$/.test(value)?value:'办理记录';
  function updatedLabel(row,now=Date.now()){
    const minutes=Math.max(0,Math.floor((now-(row.updatedAt||row.createdAt))/60000));
    return minutes<1?'刚刚':minutes<60?minutes+'分钟前':minutes<1440?Math.floor(minutes/60)+'小时前':Math.floor(minutes/1440)+'天前';
  }
  // 人工验收也固定测试身份及当时授权；结案前重新比较，避免撤权或规则变化后套用旧通过。
  function validationContext(user,row){
    if(!user)return '';
    const departments=[user.department,...user.extra];
    const grants=PeopleDemo.departments.filter(item=>departments.includes(item.id)&&item.status==='正常').map(item=>({id:item.id,access:item.access.filter(access=>access.source===row.sourceId)}));
    const extra=PeopleDemo.extraRequests.filter(item=>item.user===user.id&&item.status==='已生效').map(item=>({id:item.id,access:item.access.filter(access=>access.source===row.sourceId)})).filter(item=>item.access.length);
    return JSON.stringify([user.id,user.status,['query.view','query.ask','query.sql'].map(ability=>has(user,ability)),grants,extra]);
  }
  const eligible = kind => PeopleDemo.users.filter(actor => has(actor,'feedback.view')&&has(actor,'feedback.process')&&(kind!=='业务知识'||has(actor,'governance.view')&&(has(actor,'governance.edit')||has(actor,'governance.supplement'))));

  function create(options = {}) {
    const clock = options.now || Date.now, store = options.storage;
    let saved = options.snapshot;
    if (!saved && store) try { saved = JSON.parse(store.getItem(storageKey)); } catch { /* 本地评审快照不可用时使用初始示例。 */ }
    let items = saved?.version === 1 ? clone(saved.items) : [], evaluations = saved?.version === 1 ? clone(saved.evaluations) : [];
    let sequence = saved?.sequence || 100, epoch = saved?.epoch || 0;
    const get = id => items.find(row => row.id === id);
    const root = row => { const seen = new Set(); while (row?.mergedInto && !seen.has(row.id)) { seen.add(row.id); row = get(row.mergedInto); } return row; };
    const family = row => items.filter(item => root(item)?.id === row.id);
    // 合并只改变办理入口，原事项未补齐的必要信息仍属于主事项验收范围。
    const pendingSupplements = row => family(root(row)).filter(item=>item.supplement);
    const origins = row => [...new Map(family(row).flatMap(item => item.origins).map(origin => [origin.userId + '/' + origin.answerId, origin])).values()];
    const tasksFor = row => family(row).flatMap(item => item.tasks.map(task => ({...task, issueId:item.id})));
    // 各页签、历史和表单共用同一展示投影；任务归属不能替代业务证据权限。
    function present(row,actor){
      if(typeof row==='string')row=get(row);
      if(!row||!can(actor,row)||!can(actor,root(row)))return null;
      const restricted=!evidenceAccess(actor,root(row)),sqlAllowed=has(actor,'query.sql');
      const originView=origin=>{
        if(restricted)return {userId:origin.userId,answerId:origin.answerId,at:origin.at};
        const value=clone(origin);if(!sqlAllowed&&value.evidence)delete value.evidence.sql;return value;
      };
      const taskView=task=>restricted?{
        id:task.id,issueId:task.issueId,kind:task.kind,impact:task.impact,owner:task.owner,revision:task.revision,disposition:task.disposition,
        title:task.kind+' · '+task.id,result:task.result?{restricted:true}:null,followup:task.followup?{stage:task.followup.stage,at:task.followup.at,actor:task.followup.actor,restricted:true}:null,history:[],
        dispositionHistory:(task.dispositionHistory||[]).map(({reason,...record})=>record)
      }:clone(task);
      const memberView=member=>{
        if(!restricted){const value=clone(member);value.origins=value.origins.map(originView);return value;}
        return {id:member.id,sourceId:member.sourceId,title:displayTitle(actor,member),originType:member.originType,status:member.status,owner:member.owner,revision:member.revision,cycle:member.cycle,
          createdAt:member.createdAt,updatedAt:member.updatedAt,mergedInto:member.mergedInto,closedBy:clone(member.closedBy),
          direction:['待核实','业务知识','技术问题','源数据','口径解释','权限限制'].includes(member.direction)?member.direction:'待核实',
          rootCause:'',basis:'',conclusion:'',closeBasis:'',notes:[],origins:member.origins.map(originView),tasks:member.tasks.map(taskView),
          supplement:member.supplement?{userId:member.supplement.userId,at:member.supplement.at}:null,
          events:member.events.map(event=>({action:publicAction(event.action),actor:event.actor,at:event.at,cycle:event.cycle})),
          verifications:member.verifications.map(({decision,actor,at,cycle})=>({decision,actor,at,cycle})),
          notifications:member.notifications.map(({key,userId,cycle,status,at,read,restricted})=>({key,userId,cycle,status,at,read,restricted}))};
      };
      const main=root(row),view=memberView(row);view.restricted=restricted;
      view.members=family(main).map(memberView);view.origins=origins(main).map(originView);view.tasks=tasksFor(main).map(taskView);
      view.pendingSupplements=pendingSupplements(main).map(item=>({issueId:item.id,...(restricted?{userId:item.supplement.userId}:clone(item.supplement))}));return view;
    }
    // 待办按当前可执行动作计算；负责人等待他人时仍在“我负责的”，不冒充本人待办。
    function nextAction(row,actor){
      const action=(key,label,taskId,actionable=true)=>({key,label,taskId,actionable});
      if(!row||!can(actor,row))return action('unavailable','不可访问',undefined,false);
      if(closed(row))return action('view',row.mergedInto?'查看主事项':'查看结果',undefined,false);
      const coordinator=can(actor,row,'assign'),owner=row.owner===actor.id,process=can(actor,row,'process');
      if(!eligible().some(user=>user.id===row.owner))return action('assign',row.owner?'重新分派事项':'分派负责人',undefined,coordinator);
      if(row.status==='待补充'||pendingSupplements(row).length)return action('waiting','等待用户补充',undefined,false);
      const tasks=tasksFor(row).filter(activeTask);
      const invalid=tasks.find(task=>!eligible(task.kind).some(user=>user.id===task.owner));
      if(invalid)return action('reassign-task','重新分派任务',invalid.id,coordinator);
      if(!row.basis)return action('investigate','记录核查',undefined,owner&&process);
      const ownPending=tasks.find(task=>task.owner===actor.id&&!task.result&&task.followup?.stage!=='waiting');
      if(ownPending&&process)return action('handle',ownPending.kind==='业务知识'?'提交处理结果':'更新处理进展',ownPending.id);
      const ownWaiting=tasks.find(task=>task.owner===actor.id&&!task.result&&task.followup?.stage==='waiting');
      if(ownWaiting)return action('waiting',taskState(ownWaiting),ownWaiting.id,false);
      if(tasks.some(task=>!task.result))return action('waiting','等待任务负责人提交',undefined,false);
      if(!tasks.length){
        if(tasksFor(row).length)return action('explain','说明并结案',undefined,can(actor,row,'close'));
        return action(coordinator?'plan':'waiting',coordinator?'确定处理路径':'等待分派处理任务',undefined,owner&&process&&coordinator);
      }
      const ready=gate(row);
      if(ready.error){
        const task=tasks.find(task=>task.id===ready.taskId);
        if(task?.owner===actor.id&&process)return action('repair','补齐验收条件',task.id);
        return action('reassign-task','协调处理任务',task?.id,coordinator&&Boolean(task));
      }
      const verified=row.verification?.decision==='通过'&&row.verification.token===ready.token;
      const current=verified&&row.verification.contextToken===validationContext(person(row.verification.testUser),row);
      if(current)return action('close','验证结案',undefined,can(actor,row,'close'));
      return action('verify',verified?'重新登记人工验收':'登记人工验收',undefined,can(actor,row,'verify'));
    }
    const snapshot = () => ({version:1, items:clone(items), evaluations:clone(evaluations), sequence, epoch});
    // 浏览器后退可直接复用旧页面；只同步本标签页较新的反馈快照，保留后台治理与发布内存。
    function refreshSnapshot(){
      if(!store)return false;
      try{const saved=JSON.parse(store.getItem(storageKey));if(saved?.version!==1||saved.epoch<=epoch)return false;
        items.splice(0,items.length,...clone(saved.items));evaluations.splice(0,evaluations.length,...clone(saved.evaluations));sequence=saved.sequence;epoch=saved.epoch;return true;
      }catch{return false;}
    }
    function persist() { epoch++; if (store) try { store.setItem(storageKey, JSON.stringify(snapshot())); } catch { /* 页面内仍可办理，不冒充服务端保存。 */ } }
    function log(row, actor, action, reason = '') { row.events.push({action, reason, actor:actor?.name || '系统', at:clock(), cycle:row.cycle}); row.revision++; row.updatedAt=clock(); persist(); }
    function check(row, actor, ability, revision) {
      if (!row || !can(actor, row, ability)) return '当前账号没有该事项的操作权限。';
      if (revision !== undefined && row.revision !== revision) return '事项已变化，请重新读取后核对。';
      if (row.mergedInto) return '该事项已合并，请进入主事项办理。';
      return '';
    }
    const newRow = (data = {}) => ({id:'WT-' + (++sequence), sourceId:'retail', title:'', originType:'用户点踩', status:'待分派', direction:'待核实', owner:'', rootCause:'', basis:'', revision:1, cycle:1,
      createdAt:clock(), updatedAt:clock(), origins:[], notes:[], events:[], tasks:[], verifications:[], notifications:[], ...data});
    function intake(data, actor) {
      if (!actor || actor.id !== data.userId || !sourceAccess(actor, data.sourceId) || !has(actor,'query.view')) return {error:'只能为当前有权查看的本人问数提交线索。'};
      if (!data.answerId || !text(data.question)) return {error:'原问题或回答引用不完整。'};
      const origin = {userId:actor.id, threadId:data.threadId, turnId:data.turnId, answerId:data.answerId, question:text(data.question), at:clock(), evidence:clone(data.evidence || {})};
      let row;
      if (data.type === '用户点踩' || data.type === '评价变更') {
        const last = [...evaluations].reverse().find(event => event.answerId === data.answerId && event.userId === actor.id);
        const reaction = data.reaction || null;
        if (reaction?.type === 'down' && !text(reaction.reason)) return {error:'请填写点踩原因。'};
        if (reaction && !['up','down'].includes(reaction.type)) return {error:'评价类型不正确。'};
        row = items.find(item => item.origins.some(ref => ref.userId === actor.id && ref.answerId === data.answerId) && item.originType === '用户点踩');
        if (last && JSON.stringify(last.reaction) === JSON.stringify(reaction)) return {item:row, duplicate:true};
        evaluations.push({id:'PJ-' + (++sequence),userId:actor.id,answerId:data.answerId,reaction:clone(reaction),at:clock(),origin});
        if (!row && reaction?.type === 'down') { row=newRow({title:text(data.title) || '回答与预期不符',sourceId:data.sourceId,origins:[origin]}); items.unshift(row); }
        if (row) { row.currentReaction=clone(reaction); log(row,actor,reaction?.type === 'down' ? '收到点踩原因' : reaction?.type === 'up' ? '用户已改为点赞' : '用户已撤销评价',reaction?.reason || '已有核查事项保留'); }
        else persist();
        return {item:row};
      }
      if (!['问数知识缺口','技术失败'].includes(data.type)) return {error:'不支持的自动线索来源。'};
      row=items.find(item=>item.sourceId===data.sourceId && item.originType===data.type && item.gapKey===data.gapKey && !closed(item));
      if (!row) { row=newRow({sourceId:data.sourceId,title:text(data.title) || text(data.question),originType:data.type,gapKey:data.gapKey});items.unshift(row); }
      if (row.origins.some(ref=>ref.userId===origin.userId && ref.answerId===origin.answerId)) return {item:row,duplicate:true};
      row.origins.push(origin);log(row,actor,'接收' + data.type,text(data.description));return {item:row};
    }
    function list(actor, query = {}) {
      const match = (values, needle) => !needle || values.some(value=>String(value||'').toLowerCase().includes(needle.toLowerCase()));
      return items.filter(row=>can(actor,row) && (query.bucket !== 'mine' || nextAction(row,actor).actionable)
        && (query.bucket !== 'owned' || !closed(row)&&(row.owner===actor.id||tasksFor(root(row)).some(task=>task.owner===actor.id&&activeTask(task))))
        && (!query.status || query.status==='all' || row.status===query.status) && (!query.sourceId || row.sourceId===query.sourceId)
        && (!query.originType || row.originType===query.originType) && match([row.id,displayTitle(actor,row)],query.keyword)
        && match([person(row.owner)?.name,person(row.owner)?.login],query.owner)
        && (!query.fromDate || new Date(row.createdAt+8*3600000).toISOString().slice(0,10)>=query.fromDate)
        && (!query.toDate || new Date(row.createdAt+8*3600000).toISOString().slice(0,10)<=query.toDate));
    }
    function assign(row, data, actor, revision) {
      const error=check(row,actor,'assign',revision);if(error)return error;
      if(closed(row))return '结案事项请先重开。';
      if(!eligible().some(user=>user.id===data.owner))return '负责人须具有有效的反馈处理功能。';
      if(!text(data.reason)||!text(data.direction))return '请选择初步方向并填写分派依据。';
      row.owner=data.owner;row.direction=data.direction;row.status=pendingSupplements(row).length?'待补充':'处理中';row.verification=null;
      log(row,actor,'分派事项',data.reason);
    }
    function investigate(row, data, actor, revision) {
      const error=check(row,actor,'process',revision);if(error)return error;
      if(closed(row)||!row.owner||!eligible().some(user=>user.id===row.owner))return '请先核对有效负责人，结案事项须先重开。';
      if(actor.id!==row.owner)return '当前事项由其他负责人办理，请先协调分派。';
      if(!text(data.basis))return '请填写核查依据；原因未明可保留待核实。';
      row.rootCause=text(data.rootCause);row.basis=text(data.basis);row.verification=null;
      if(row.status==='待验证')row.status='处理中';log(row,actor,'记录核查结论',row.basis);
    }
    function requestSupplement(row,data,actor,revision){
      const error=check(row,actor,'process',revision);if(error)return error;
      if(closed(row)||!text(data.question)||!origins(row).some(origin=>origin.userId===data.userId))return '请选择原提问人并明确需要其补充的业务信息。';
      if(!row.owner)return '请先分派跟进负责人。';
      if(actor.id!==row.owner)return '当前事项由其他负责人办理，请先协调分派。';
      row.supplement={userId:data.userId,question:text(data.question),at:clock()};row.status='待补充';row.verification=null;log(row,actor,'请求补充',data.question);
    }
    function supplement(id, content, actor) {
      const original=get(id), row=root(original), value=text(content);
      if(!row || !publicAccess(actor,row.sourceId)||!origins(row).some(origin=>origin.userId===actor.id))return '当前账号不能补充此事项。';
      if(closed(row)||!value)return '事项已结案或补充内容为空。';
      const requests=pendingSupplements(row).filter(item=>item.supplement.userId===actor.id),note={userId:actor.id,text:value,at:clock()};
      // 补充归回提出请求的原事项，纠正合并后仍能回看；未被请求的补充归回原回答。
      (requests.length?requests:[original.origins.some(origin=>origin.userId===actor.id)?original:family(row).find(item=>item.origins.some(origin=>origin.userId===actor.id))]).forEach(item=>item.notes.push(clone(note)));
      row.verification=null;if(row.status==='待验证')row.status='处理中';
      requests.forEach(item=>{
        item.supplement=null;
        if(item!==row){if(item.beforeMerge?.status==='待补充')item.beforeMerge.status='处理中';log(item,actor,'收到用户补充',value);}
      });
      row.status=pendingSupplements(row).length?'待补充':'处理中';
      log(row,actor,'收到用户补充',value);
    }
    function addTask(row,data,actor,revision){
      const error=check(row,actor,'process',revision)||check(row,actor,'assign',revision);if(error)return error;
      if(!['处理中','待补充','待验证'].includes(row.status)||!text(row.basis))return '请先分派并记录核查依据。';
      if(actor.id!==row.owner)return '当前事项由其他负责人办理，请先协调分派。';
      if(!['业务知识','技术修复','源数据修正'].includes(data.kind)||!text(data.title)||!eligible(data.kind).some(user=>user.id===data.owner))return '请填写任务并选择具备对应处理功能的负责人。';
      const task={id:'CL-' + (++sequence),kind:data.kind,title:text(data.title),owner:data.owner,revision:1,result:null,binding:null,disposition:'在办',impact:data.impact||'查询与安全'};
      if(data.kind==='业务知识'){
        const binding=bindingOptions(row.sourceId).find(option=>option.value===data.binding);if(!binding)return '请选择当前数据源的治理条目。';
        task.binding=binding.binding;
      }
      row.tasks.push(task);row.status=pendingSupplements(row).length?'待补充':'处理中';row.verification=null;log(row,actor,'新增处理任务',task.title);return {task};
    }
    // 退出只调整必要验收范围，历史结果与恢复入口保留；范围变化使原验收失效。
    function disposition(row,taskId,data,actor,revision){
      const error=check(row,actor,'process',revision);if(error)return error;
      const task=row.tasks.find(task=>task.id===taskId);
      if(closed(row)||!task||(actor.id!==row.owner&&!has(actor,'feedback.assign')))return '须由主负责人或分派协调者确认任务范围。';
      if(!text(data.reason)||!['无需变更','已取消','恢复'].includes(data.action))return '请选择退出或恢复动作并填写依据。';
      if(data.action!=='恢复'&&!data.covered)return '仍有未覆盖影响，请保留必要任务并明确跟进，不可直接退出。';
      if(data.action==='恢复'&&activeTask(task))return '任务已在办理中。';
      (task.dispositionHistory||=[]).push({from:taskState(task),action:data.action,reason:text(data.reason),covered:Boolean(data.covered),actor:actor.name,at:clock(),cycle:row.cycle});
      task.disposition=data.action==='恢复'?'在办':data.action;
      if(data.action==='恢复'){archiveTask(row,task,actor);task.result=null;task.followup=null;}
      task.revision++;row.scopeRevision=(row.scopeRevision||0)+1;row.verification=null;row.status=pendingSupplements(row).length?'待补充':'处理中';log(row,actor,'任务'+data.action,task.id+' · '+data.reason);
    }
    function reassignTask(row,taskId,data,actor,revision){
      const error=check(row,actor,'assign',revision);if(error)return error;
      const task=row.tasks.find(task=>task.id===taskId);
      if(closed(row)||!task||!activeTask(task)||!eligible(task.kind).some(user=>user.id===data.owner)||!text(data.reason))return '请核对未结任务、有效负责人和重新分派依据。';
      archiveTask(row,task,actor);
      task.owner=data.owner;task.result=null;task.followup=null;task.revision++;row.verification=null;row.status=pendingSupplements(row).length?'待补充':'处理中';log(row,actor,'重新分派处理任务',data.reason);
    }
    function archiveTask(row,task,actor,cycle=row.cycle){
      if(task.result||task.followup)(task.history||=[]).push({result:clone(task.result),followup:clone(task.followup),revision:task.revision,cycle,at:clock(),actor:actor.name});
    }
    function resultIssue(task,data){
      if(!text(data.summary)||!text(data.check))return '请说明做了什么修正，以及怎样复查、实际结果如何。';
      if(data.effectiveConfirmed!==true)return '修正尚未确认生效，请先保存跟进进展。';
      if(task.kind==='源数据修正'&&!text(data.performer))return '请填写实际完成修正的人或负责团队。';
      if(task.kind==='技术修复'&&(task.impact||'查询与安全')==='查询与安全'&&(!text(data.regression)||data.regressionPassed!==true))return '请记录相关场景测试结果，并确认测试通过后再提交。';
      return '';
    }
    function handling(row,taskId,data,actor,revision){
      const error=check(row,actor,'process',revision);if(error)return error;
      const task=row.tasks.find(task=>task.id===taskId);
      if(closed(row)||!task||!activeTask(task)||!eligible(task.kind).some(user=>user.id===task.owner))return '任务不可办理或负责人权限已变化。';
      if(actor.id!==task.owner)return '当前处理任务由其他负责人办理，请先重新分派。';
      if(task.kind==='业务知识'){
        if(!['summary','expected','reproduction','dataBasis'].every(key=>text(data[key])))return '请填写处理结果、复现方式、期望行为和验证数据依据。';
        if(!text(data.releaseId)||!text(data.coverage))return '请关联实际发布任务，并说明对原问题及受影响范围的覆盖。';
        archiveTask(row,task,actor);task.result=clone(data);task.followup=null;
      }else{
        if(!['working','waiting','ready'].includes(data.stage))return '请选择目前进展。';
        if(data.stage!=='ready'&&!text(data.note))return '请记录已做的工作和下一步。';
        if(data.stage==='ready'){const issue=resultIssue(task,data);if(issue)return issue;}
        archiveTask(row,task,actor);
        // 跟进记录不充当修复结果；已生效并有复查说明后才能进入待验证。
        task.result=data.stage==='ready'?{format:2,summary:text(data.summary),check:text(data.check),performer:text(data.performer),reference:text(data.reference),effectiveConfirmed:true,
          ...(task.kind==='技术修复'&&(task.impact||'查询与安全')==='查询与安全'?{regression:text(data.regression),regressionPassed:true}:{})}:null;
        task.followup=data.stage==='ready'?null:{stage:data.stage,note:text(data.note),actor:actor.name,at:clock()};
      }
      task.revision++;row.verification=null;
      row.status=pendingSupplements(row).length?'待补充':tasksFor(row).filter(activeTask).every(task=>task.result)?'待验证':'处理中';log(row,actor,task.result?'提交处理结果':'记录处理进展',task.title);
    }
    function gate(row){
      // 未补齐的必要信息仍阻止验收；新增任务或修订结果不能隐式解除待补充。
      if(pendingSupplements(row).length||row.status==='待补充')return {error:'请先补齐用户信息并核实，再登记验收。'};
      if(!eligible().some(user=>user.id===row.owner))return {error:'主负责人权限已变化，请先重新分派事项。'};
      const tasks=tasksFor(row).filter(activeTask);if(!tasks.length)return {error:'没有仍需修复的任务；请按实际依据说明并结案。'};
      const tokens=[];
      for(const task of tasks){
        if(!task.result)return {error:task.id+' 尚未提交处理结果。',taskId:task.id};
        if(!eligible(task.kind).some(user=>user.id===task.owner))return {error:task.id+' 的负责人权限已变化，请重新分派。',taskId:task.id};
        if(task.kind==='业务知识'){
          const state=businessEvidence(task,row.sourceId,{origins:origins(row)});if(state.error)return {...state,taskId:task.id};tokens.push([task.id,task.revision,state.token]);
        }else{
          if(task.result.format!==2)return {error:task.id+' 请补充本次生效确认和复查结果。',taskId:task.id};
          const issue=resultIssue(task,task.result);if(issue)return {error:task.id+' '+issue,taskId:task.id};
          tokens.push([task.id,task.revision,task.result]);
        }
      }
      const token=JSON.stringify([row.cycle,row.scopeRevision||0,origins(row).map(origin=>[origin.userId,origin.answerId,origin.evidence]),tasksFor(row).map(task=>[task.id,task.disposition,task.revision]),tokens]);
      if(row.failedToken===token)return {error:'本轮复验未通过，请先补充或修订处理结果后重新复验。',taskId:tasks[0]?.id};
      return {token,tasks};
    }
    function verify(row,data,actor,revision){
      const error=check(row,actor,'verify',revision);if(error)return error;
      if(closed(row)||!text(data.actual)||!text(data.basis)||!['通过','不通过'].includes(data.decision))return '请填写实际结果、复验依据和明确结论。';
      const ready=gate(row);if(ready.error)return ready.error;
      const tester=person(data.testUser||actor.id);if(!tester||tester.status!=='正常')return '请核对测试身份，账号不可用时不能登记本轮验收。';
      const record={id:'FY-'+(++sequence),decision:data.decision,actual:text(data.actual),basis:text(data.basis),testUser:tester.id,contextToken:validationContext(tester,row),token:ready.token,actor:actor.name,at:clock(),cycle:row.cycle};
      row.verifications.push(record);row.verification=record;row.status=data.decision==='通过'?'待验证':'处理中';
      if(data.decision==='不通过')row.failedToken=ready.token;
      log(row,actor,'生效后复验'+data.decision,record.basis);
    }
    function close(row,data,actor,revision){
      const error=check(row,actor,'close',revision);if(error)return error;
      if(closed(row)||!text(data.conclusion)||!text(data.basis))return '请填写真实结论和结案依据。';
      if(!['已解决','其他结案'].includes(data.status))return '请选择有效结案类型。';
      if(data.status==='已解决'){
        const ready=gate(row);if(ready.error)return ready.error;
        if(row.verification?.decision!=='通过'||row.verification.token!==ready.token)return '当前范围和版本尚未通过生效后复验，不能记为已解决。';
        if(row.verification.contextToken!==validationContext(person(row.verification.testUser),row))return '测试身份、授权或输出规则已变化，请重新登记验收。';
      }else if(!['未复现','按现有口径确认无误','取消','其他'].includes(data.reason))return '请选择其他结案的真实原因。';
      row.status=data.status;row.conclusion=text(data.conclusion);row.closeBasis=text(data.basis);row.closeReason=data.status==='已解决'?'验证解决':data.reason;
      row.closedAt=clock();row.closedBy={id:actor.id,name:actor.name};
      for(const userId of new Set(origins(row).map(origin=>origin.userId)))row.notifications.push({key:row.id+'/'+row.cycle+'/'+userId,userId,cycle:row.cycle,status:'待提示',template:receiptText(row.status,row.closeReason),conclusion:receiptText(row.status,row.closeReason),at:clock(),attempts:0,read:false});
      log(row,actor,data.status,row.closeBasis);
    }
    function reopen(row,reason,actor,revision){
      const error=check(row,actor,'process',revision);if(error)return error;
      if(actor.id!==row.owner&&!has(actor,'feedback.assign'))return '请由原负责人或分派协调者凭依据重开。';
      if(!['已解决','其他结案'].includes(row.status)||!text(reason))return '只有已结案事项可凭新证据重开。';
      row.cycle++;row.status=eligible().some(user=>user.id===row.owner)?'处理中':'待分派';row.verification=null;
      family(row).forEach(item=>{item.supplement=null;if(item.beforeMerge?.status==='待补充')item.beforeMerge.status='处理中';});
      row.tasks.forEach(task=>{archiveTask(row,task,actor,row.cycle-1);task.result=null;task.followup=null;task.revision++;});log(row,actor,'重开事项',reason);
    }
    function merge(row,target,data,actor,revision){
      const error=check(row,actor,'assign',revision)||check(target,actor,'assign',data.targetRevision);if(error)return error;
      if(row.id===target.id||root(target)?.id===row.id||closed(row)||closed(target)||row.sourceId!==target.sourceId||row.tasks.length||!text(data.reason))return '只能合并同数据源、无独立处理任务且未结案的重复事项，请核对相同根因与处理范围。';
      row.beforeMerge={status:row.status,owner:row.owner};row.mergedInto=target.id;row.status='已合并';target.verification=null;
      if(pendingSupplements(target).length)target.status='待补充';else if(target.status==='待验证')target.status='处理中';log(row,actor,'合并到'+target.id,data.reason);log(target,actor,'关联重复事项'+row.id,data.reason);
    }
    function unmerge(row,reason,actor,revision){
      if(!row||!can(actor,row,'assign')||row.revision!==revision)return '事项已变化或没有纠正合并的权限。';
      const target=root(row);if(!row.mergedInto||closed(target)||!text(reason))return '请填写纠正依据；主事项已结案时须先重开。';
      Object.assign(row,row.beforeMerge);delete row.mergedInto;delete row.beforeMerge;target.verification=null;
      if(pendingSupplements(row).length)row.status='待补充';
      if(['待验证','待补充'].includes(target.status))target.status=pendingSupplements(target).length?'待补充':'处理中';log(row,actor,'纠正合并',reason);log(target,actor,'解除重复关联'+row.id,reason);
    }
    function deliver(row,key,actor,mode='success'){
      const error=check(row,actor,'close');if(error)return error;
      const notification=row.notifications.find(item=>item.key===key);
      if(!notification||!['待提示','提示失败'].includes(notification.status))return '已投递或结果待核查，不能直接重复发送。';
      if(notification.cycle!==row.cycle||!closed(row))return '事项已重开，历史轮次的结案回告不能直接重新发送。';
      const user=person(notification.userId);notification.attempts++;
      if(!user||user.status!=='正常'){notification.status='未发送';notification.reason='接收账号不可用';}
      else if(mode==='unknown'){notification.status='结果待核查';}
      else if(mode==='fail'){notification.status='提示失败';}
      else{notification.status='已投递';notification.restricted=!publicAccess(user,row.sourceId);notification.text=notification.restricted?limitedReceipt:notification.template||receiptText(row.status,row.closeReason);}
      log(row,actor,'回告'+notification.status);return '';
    }
    function reconcile(row,key,actor,delivered){
      const error=check(row,actor,'close');if(error)return error;
      const notification=row.notifications.find(item=>item.key===key);if(notification?.status!=='结果待核查')return '仅核查结果未知的投递。';
      notification.status=delivered?'已投递':'待提示';
      if(delivered){notification.restricted=!publicAccess(person(notification.userId),row.sourceId);notification.text=notification.restricted?limitedReceipt:notification.template||receiptText(row.status,row.closeReason);}
      log(row,actor,'核查回告投递',delivered?'已确认投递':'未投递，可重新发送');
    }
    function publicItem(id,actor){
      const original=get(id),row=root(original);if(!row)return null;
      const own=origins(row).filter(origin=>origin.userId===actor?.id);if(!own.length||actor.status!=='正常')return null;
      const visible=publicAccess(actor,row.sourceId);
      const receipts=row.notifications.filter(item=>item.userId===actor.id&&item.status==='已投递').map(item=>({key:item.key,userId:item.userId,cycle:item.cycle,status:item.status,at:item.at,read:item.read,restricted:!visible||item.restricted,text:visible&&!item.restricted?(item.template||receiptText('其他结案','其他')):limitedReceipt}));
      const requests=pendingSupplements(row).filter(item=>item.supplement.userId===actor.id);
      return {id:row.id,sourceId:row.sourceId,status:row.status,title:visible?row.title:'反馈处理进度',owner:visible?person(row.owner)?.name||(closed(row)?row.closedBy?.name||'历史办理人':'待分派'):'处理负责人',
        summary:!visible?'详细内容受当前权限限制。':row.status==='待补充'&&requests.length?requests.map(item=>item.supplement.question).join('\n'):row.status==='已解决'?'处理已通过验证，可重新查询原问题。':row.status==='其他结案'?receiptText(row.status,row.closeReason):'已收到，正在按当前进度处理。',
        origins:visible?own.map(({evidence,...origin})=>clone(origin)):[],notes:visible?clone([...new Map(family(row).flatMap(item=>item.notes).filter(note=>note.userId===actor.id).map(note=>[JSON.stringify(note),note])).values()]):[],receipts,canSupplement:visible&&!closed(row),canRetry:visible&&row.status==='已解决'};
    }
    function markRead(id,key,actor){const row=root(get(id)),n=row?.notifications.find(item=>item.key===key&&item.userId===actor?.id);if(!n||n.status!=='已投递')return;n.read=true;persist();}
    function seed(){
      if(items.length||saved?.version===1)return;
      const titles=['跨周退货的时间归属需要核对','商品原销售数量的关联范围待确认','原查询连接中断，任务状态未知','原销量与净销量预期不同','商品编码展示丢失前导零','退货查询存在重复明细线索','销售时间定义缺失','库存更新时间缺少依据','查询范围与用户原意不符','同一笔销售多次退货的口径核对','对比周期进度需要明确','历史答案展示异常','权限正常阻止成本输出','显示顺序不符合业务习惯','巡检发现未知商品状态编码'];
      titles.forEach((title,index)=>{
        const sourceId=index===1||index===4||index===6||index===14?'products':index===12?'finance':'retail';
        const status=statuses[[0,2,0,5,3,2,1,0,3,2,5,4,5,6,0][index]];
        const row=newRow({title,sourceId,status,owner:['待分派','已合并'].includes(status)?'':'u01',originType:['用户点踩','问数知识缺口','技术失败','用户点踩','用户点踩','用户点踩','问数知识缺口','人工巡检'][index%8],
          createdAt:Date.UTC(2026,10,15-index%5,1,index),updatedAt:Date.UTC(2026,10,15,1,index),fixture:true,
          origins:[{userId:index%3===0?'u04':'u01',answerId:'SAMPLE-ANSWER-'+index,question:title,at:Date.UTC(2026,10,15,1,index),evidence:{label:'固定问题线索，尚未核实',knowledge:[],revision:1}}],
          events:[{action:'接收示例线索',actor:'系统',at:Date.UTC(2026,10,15,1,index),cycle:1}],basis:index===3?'已核对当次口径说明，原销售数量不扣退货。':''});
        if(status==='其他结案'){row.conclusion='按现有口径核查结案，未修改业务定义。';row.closeReason='按现有口径确认无误';row.closeBasis='固定示例核查记录';}
        if(status==='待补充')row.supplement={userId:row.origins[0].userId,question:'请明确本次统计应使用的业务日期及时间范围。',at:row.updatedAt};
        if(status==='待验证'){
          row.basis='固定样例：已核对问题及展示记录。';row.rootCause=index===4?'展示层未保留文本编码':'界面显示与查询结果未保持一致';
          row.tasks.push({id:'CL-SAMPLE-'+index,kind:'技术修复',impact:'展示表达',title:index===4?'保留商品编码的前导零':'核对展示与原结果',owner:'u01',revision:1,result:{format:2,summary:'修正展示格式',check:'人工对照固定编码样例0001，展示保留前导零；仅离线样例。',effectiveConfirmed:true,reference:'DEMO-FIX-'+index}});
        }
        if(status==='已解决'){row.conclusion='示例展示问题已完成修复及复验。';row.closeBasis='历史固定复验记录';row.verifications=[{decision:'通过',actor:'林管理员',basis:'固定示例历史记录',at:row.updatedAt,cycle:1}];}
        items.push(row);
      });
      const merged=items.find(row=>row.status==='已合并');merged.mergedInto=items[5].id;merged.beforeMerge={status:'待分派',owner:''};
      persist();
    }
    if(options.seed!==false)seed();
    items.filter(row=>!closed(row)&&pendingSupplements(row).length).forEach(row=>{row.status='待补充';});
    return {get,root,family,origins,tasksFor,present,nextAction,list,intake,disposition,assign,investigate,requestSupplement,supplement,addTask,reassignTask,handling,gate,verify,close,reopen,merge,unmerge,deliver,reconcile,publicItem,markRead,snapshot,persist,refreshSnapshot,
      get items(){return items;},get evaluations(){return evaluations;},get epoch(){return epoch;}};
  }
  function bindingOptions(source){
    if(!window.GovernanceDemo||!window.DataSourceDemo)return [];
    return DataSourceDemo.objects(source).flatMap(table=>GovernanceDemo.get(table).items.filter(entry=>!entry.retired).map(entry=>({value:table.id+'/'+entry.id,label:(entry.content?.meaning||entry.content?.name||entry.name)+' · '+GovernanceDemo.objectName(table)+' / '+entry.name+' · r'+entry.revision+' · '+entry.review,binding:{table:table.id,entry:entry.id}})));
  }
  function definitionToken(binding){
    const table=window.DataSourceDemo?.tables.find(table=>table.id===binding?.table),entry=table&&GovernanceDemo.get(table).items.find(entry=>entry.id===binding.entry);
    return entry?JSON.stringify([table.identity,table.generation,entry.revision,entry.review,entry.validity,entry.content]):'';
  }
  function businessEvidence(task,source,row){
    const w=window.KnowledgeWorkspace,table=window.DataSourceDemo?.tables.find(table=>table.id===task.binding?.table),entry=table&&GovernanceDemo.get(table).items.find(entry=>entry.id===task.binding.entry);
    if(!w||!entry||entry.review!=='已确认'||entry.validity==='待复核'||entry.blocked)return {error:'请先确认受影响的治理条目并核对当前禁止状态。'};
    // 用原回答引用判断是否已有实际修复，不能把任务创建时点当作修复基线。
    if(!text(task.result?.coverage))return {error:'请补充已有发布对原问题、条件和受影响范围的覆盖依据。'};
    const originalRefs=(row?.origins||[]).flatMap(origin=>origin.evidence?.knowledge||[]);
    const release=w.tasks.get(task.result?.releaseId),candidate=release&&w.candidate(release);
    if(!release||release.source!==source||!candidate)return {error:'请关联本数据源的固定发布任务。'};
    const docs=candidate.documents.filter(doc=>doc.entries.some(item=>item.table===task.binding.table&&item.id===task.binding.entry&&item.revision===entry.revision&&item.objectId===table.identity&&item.generation===table.generation));
    if(!docs.length||candidate.runs.at(-1)?.result!=='通过')return {error:'受影响修订尚未通过关联发布任务的候选回归。'};
    if(release.stage!=='已发布'||docs.some(doc=>w.active.get(doc.id)?.release!==release.id))return {error:'候选回归通过后仍须完成发布切换，再做生效后复验。'};
    if(originalRefs.some(ref=>docs.some(doc=>ref.id===doc.id&&ref.revision===doc.revision)))return {error:'目标与原回答使用的版本相同，请核实实际修复或按解释结案。'};
    const context=w.publishedContext(docs.map(doc=>doc.id));if(context.error)return {error:context.error};
    return {token:JSON.stringify([release.id,candidate.runs.at(-1).id,docs.map(doc=>[doc.id,doc.revision]),definitionToken(task.binding)]),release,docs};
  }
  let storage;try{storage=sessionStorage;}catch{/* 无本地存储时仅在当前页运行。 */}
  window.FeedbackDemo=Object.assign(create({storage}),{create,statuses,closed,can,has,eligible,evidenceAccess,displayTitle,activeTask,taskState,receiptText,updatedLabel,sourceAccess,sourceName,bindingOptions,definitionToken,businessEvidence,storageKey});
})();
