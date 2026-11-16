/* 模型接入与逐节点分配的离线样例。凭据只记录设置状态，不发出真实模型请求。 */
(() => {
  'use strict';
  const protocols={openai:{label:'OpenAI Chat Completions',kind:'generation'},anthropic:{label:'Anthropic Messages',kind:'generation'},embedding:{label:'OpenAI Embeddings',kind:'embedding'},rerank:{label:'Cohere Rerank',kind:'rerank'}};
  const kinds={generation:'生成模型',embedding:'Embedding 向量模型',rerank:'Rerank 重排模型'};
  const protocolOptions=kind=>Object.entries(protocols).filter(([,item])=>!kind||item.kind===kind).map(([value,item])=>({value,label:item.label}));
  const definitions=[
    ['table-description','数据治理','表说明生成','结合结构、资料及必要的渐进读取，判断缺口并整理表的用途、粒度、范围和更新规则。','generation'],
    ['field-description','数据治理','字段解释生成','结合整表上下文及必要读取，判断缺口并整理字段含义、编码、单位、取值和使用注意。','generation'],
    ['relation-discovery','数据治理','关联候选发现','根据结构及业务资料提出关联候选，交人确认。','generation'],
    ['metric-discovery','数据治理','指标候选发现','提出指标公式、过滤、单位和时间口径候选。','generation'],
    ['document-plan','知识库与发布','知识文档规划','按业务职责建议文档清单、用途、问题及必要依赖。','generation'],
    ['document-write','知识库与发布','知识正文生成与更新','依据已确认治理内容生成正文及更新差异建议，保留人工修改。','generation'],
    ['question-suggestion','知识库与发布','标准题建议','建议标准问题、验证条件及边界案例，交人审核。','generation'],
    ['intent','智能问数','问题理解','结合有效对话理解原问题与后续补充，形成规范问题，识别补条件、改意图或换题。','generation'],
    ['clarification','智能问数','追问生成','针对仍影响结果的缺项或歧义生成具体追问。','generation'],
    ['history-summary','智能问数','历史对话摘要','将较早对话整理为本次仍可使用的摘要。','generation'],
    ['sql-generate','智能问数','SQL 生成','根据问题和RAG取得的正式依据生成SQL候选。','generation'],
    ['sql-semantic-check','智能问数','SQL 意图语义核对','结合原问题、同版依据与实际SQL解析结果，辅助核对条件、粒度、单位和需求覆盖；不替代系统检查。','generation'],
    ['output-meaning','智能问数','输出含义识别','识别别名与计算列的业务含义，提供与中文输出规则匹配的依据；授权及执行资格由系统校验。','generation'],
    ['sql-repair','智能问数','SQL 修正','根据具体差异在原问题及权限范围内修正SQL。','generation'],
    ['result-explanation','智能问数','结果解释','依据已核对且获准的实际结果生成业务说明和图表展示建议，失败时只重试解释。','generation'],
    ['rag-embedding','RAG','知识与问题向量化','知识索引与问题向量化共用匹配的Embedding配置；更换后待新索引就绪，再切换生效配置。','embedding'],
    ['rag-rerank','RAG','候选知识重排','在已获准且有效的候选知识中进一步比较相关性。','rerank',true],
    ['feedback-similarity','反馈处理','反馈问题向量化','用匹配的Embedding配置向量化反馈及问题库，供系统召回相似候选；不自动归组、合并或认定根因。','embedding']
  ];
  const permitted=(actor,key)=>window.SourceApprovalDemo?.hasAbility(actor,key)||false;
  function create(options={}){
    const clock=options.now||Date.now,rows=[],calls=[],changes=[],tests=[];
    const nodes=definitions.map(([id,module,name,description,kind,optional=false])=>({id,module,name,description,kind,optional,model:'',active:null,previousIndex:null,indexTarget:null,indexTask:null,indexState:'未配置',indexError:''}));let sequence=0,assignmentRevision=1;
    const find=id=>rows.find(row=>row.id===id),clone=value=>structuredClone(value),text=value=>String(value??'').trim();
    const log=(actor,action,object,before,after,extra={})=>changes.unshift({id:'BG-'+(++sequence),actor:actor.name,action,object,before,after,at:clock(),...extra});
    const snapshot=row=>row?clone({modelId:row.id,name:row.name,revision:row.revision,kind:row.kind,content:row.content,at:clock()}):null;
    const key=value=>value?value.modelId+'/r'+value.revision:'';
    const vectorIdentity=value=>value?JSON.stringify([value.content.protocol,value.content.address,value.content.model]):'';
    const reference=value=>value?value.name+' · r'+value.revision:'未分配';
    function revisionSnapshot(id,revision){const row=find(id);return clone(row?.revision===revision?snapshot(row):row?.history.find(value=>value.revision===revision)||null);}
    const recordSnapshot=record=>clone(record.snapshot||revisionSnapshot(record.model,record.revision));
    function configureIndex(node,target){
      node.indexError='';
      if(!target){if(node.active)node.previousIndex=clone(node.active);node.active=null;node.indexTarget=null;node.indexTask=null;node.indexState='未配置';return;}
      // 清空分配不删除已存在的索引；重新选择时，只恢复仍可用且配套的已知路径。
      if(!node.active&&node.previousIndex&&find(node.previousIndex.modelId)?.state==='已启用')node.active=clone(node.previousIndex);
      if(node.active?.modelId===target.modelId&&vectorIdentity(node.active)===vectorIdentity(target)){
        node.active=clone(target);node.indexTarget=null;node.indexTask=null;node.indexState='已就绪';
      }else{
        // 两类向量索引各自更新，任务绑定确切目标，旧结果不能覆盖后来的选择。
        if(key(node.indexTarget)!==key(target))node.indexTask={id:'SY-'+(++sequence),scope:node.module==='RAG'?'已发布知识索引':'反馈与问题库索引',targetKey:key(target),at:clock(),attempts:0};
        node.indexTarget=clone(target);node.indexState='待索引更新';
      }
    }
    function indexInfo(node){
      const active=node.active,available=Boolean(node.model&&active&&find(active.modelId)?.state==='已启用');
      return {active:clone(active),requested:snapshot(find(node.model)),target:clone(node.indexTarget),task:clone(node.indexTask),targetKey:key(node.indexTarget),state:node.indexState,error:node.indexError,available};
    }
    function resolveIndex(nodeId,result,targetKey,actor){
      if(!permitted(actor,'models.assign'))return '当前账号没有节点模型分配权限。';
      const node=nodes.find(value=>value.id===nodeId),target=node?.indexTarget,current=node&&find(node.model);
      if(!node||node.kind!=='embedding'||!target||key(target)!==targetKey||key(snapshot(current))!==targetKey)return '索引目标已变化，不能切换旧构建结果。';
      if(!['ready','failed'].includes(result))return '请选择有效的索引演示结果。';
      if(current.state!=='已启用')return '待应用模型已停用，不能切换索引。';
      node.indexTask.attempts++;node.indexTask.finishedAt=clock();node.indexTask.state=result==='failed'?'更新失败':'已完成';
      // 离线原型使用固定构建结果演示办理，真实构建仍须校验匹配后才能切换。
      if(result==='failed'){node.indexState='索引更新失败';node.indexError='演示：索引校验未通过。';}
      else{node.active=clone(target);node.indexTarget=null;node.indexState='已就绪';node.indexError='';}
      log(actor,result==='ready'?'索引切换（演示）':'索引失败（演示）',node.name,reference(target),node.indexState,{taskId:node.indexTask.id});return '';
    }
    function validate(data){
      if(!kinds[data.kind])return '请选择模型类型。';
      if(!protocols[data.protocol]||protocols[data.protocol].kind!==data.kind)return '请选择与模型类型匹配的接口格式。';
      if(!text(data.name)||!text(data.address)||!text(data.model))return '请填写配置名称、服务URL和模型名称。';
      try{const url=new URL(data.address);if(!['https:','http:'].includes(url.protocol)||!url.hostname||url.username||url.password||url.search||url.hash)throw new Error();}catch{return '请填写有效服务URL，地址不能包含账号、凭据、查询参数或片段。';}
      if(!Number.isInteger(data.timeout)||data.timeout<1||data.timeout>120||!Number.isInteger(data.retries)||data.retries<0||data.retries>2)return '请核对调用超时和重试次数。';
      return '';
    }
    function save(row,data,actor,revision){
      if(!permitted(actor,row?'models.edit':'models.create'))return {error:'当前账号没有模型维护权限。'};
      if(row&&(!rows.includes(row)||row.revision!==revision))return {error:'模型配置已变化，请重新打开后修改。'};
      if(rows.some(item=>item!==row&&item.name===text(data.name)))return {error:'配置名称已存在。'};
      const error=validate(data);if(error)return {error};
      if(!row&&!text(data.credential))return {error:'请填写演示凭据。'};
      if(row&&text(data.credential)&&!permitted(actor,'models.credential'))return {error:'当前账号没有替换凭据权限。'};
      if(row&&nodes.some(node=>(node.model===row.id||node.active?.modelId===row.id)&&node.kind!==protocols[data.protocol].kind))return {error:'该模型仍被节点选择或生效配置引用，请先调整节点分配。'};
      // 仅白名单字段进入记录；原始输入凭据不进入模型列表、历史、诊断或浏览器存储。
      const content={kind:data.kind,protocol:data.protocol,address:text(data.address),model:text(data.model),timeout:data.timeout,retries:data.retries,credentialSet:!!(text(data.credential)||row?.content.credentialSet),credentialRevision:(row?.content.credentialRevision||0)+(text(data.credential)?1:0)};
      if(row&&row.name===text(data.name)&&JSON.stringify(row.content)===JSON.stringify(content))return {error:'模型配置没有变化。'};
      const before=snapshot(row);
      if(!row){row={id:'MX-'+String(++sequence).padStart(3,'0'),name:'',revision:0,stateRevision:1,state:'已启用',history:[],test:null};rows.unshift(row);}
      if(row.revision)row.history.push(snapshot(row));
      row.name=text(data.name);row.kind=data.kind;row.content=content;row.revision++;row.test=null;
      const after=snapshot(row);
      for(const node of nodes){
        if(node.kind==='embedding'){
          if(node.active?.modelId===row.id&&vectorIdentity(node.active)===vectorIdentity(after))node.active=clone(after);
          if(node.model===row.id)configureIndex(node,after);
        }else if(node.model===row.id)node.active=clone(after);
      }
      log(actor,'保存模型',row.id,reference(before),reference(after),{beforeSnapshot:before,afterSnapshot:after});return {row};
    }
    function testConnection(row,actor,revision){
      if(!permitted(actor,'models.test'))return '当前账号没有连接测试权限。';
      if(!row||row.revision!==revision)return '配置已变化，请重新测试当前版本。';
      const failed=row.scenario==='鉴权失败',result={id:'CS-'+(++sequence),model:row.id,revision:row.revision,snapshot:snapshot(row),protocol:row.content.protocol,at:clock(),status:failed?'失败':'通过',reason:failed?'演示：凭据失效或调用权限不足。':'演示：合成请求的连接和响应格式通过。',actor:actor.name};
      row.test=clone(result);tests.unshift(result);return '';
    }
    function assign(mapping,actor,revision,options={}){
      if(!permitted(actor,'models.assign'))return '当前账号没有节点模型分配权限。';
      if(revision!==assignmentRevision)return '节点分配已变化，请重新查看后保存。';
      const changed=nodes.filter(node=>Object.hasOwn(mapping,node.id)&&mapping[node.id]!==node.model);if(!changed.length)return '节点分配没有变化。';
      for(const node of changed){const value=mapping[node.id];if(!value){if(node.model&&!node.optional&&!options.confirmedClear)return '清空'+node.name+'将使该节点不可用，请确认影响。';continue;}const row=find(value);if(!row||row.state!=='已启用')return node.name+'所选模型不可用。';if(row.kind!==node.kind)return node.name+'需要'+kinds[node.kind]+'，请重新选择。';}
      // 全部改动核对后一起写入，避免一项失败时其他节点已被部分覆盖。
      for(const node of changed){const before=snapshot(find(node.model)),after=snapshot(find(mapping[node.id]));log(actor,'分配节点模型',node.name,reference(before),after?reference(after):node.optional?'不使用':'未分配',{beforeSnapshot:before,afterSnapshot:after});node.model=mapping[node.id]||'';if(node.kind==='embedding')configureIndex(node,after);else node.active=after;}assignmentRevision++;return '';
    }
    const usages=row=>nodes.filter(node=>node.model===row.id||node.active?.modelId===row.id);
    function stop(row,actor,revision,stateRevision=row?.stateRevision){
      if(!permitted(actor,'models.disable'))return '当前账号没有模型停用权限。';if(!row||row.revision!==revision||row.stateRevision!==stateRevision)return '模型配置或状态已变化，请重新核对。';if(row.state==='已停用')return '该模型已停用。';row.state='已停用';row.stateRevision++;log(actor,'停用模型',row.id,'已启用','已停用');return '';
    }
    function enable(row,actor,revision,stateRevision=row?.stateRevision){if(!permitted(actor,'models.edit'))return '当前账号没有模型维护权限。';if(!row||row.revision!==revision||row.stateRevision!==stateRevision)return '模型配置或状态已变化。';if(row.state!=='已停用')return '模型已恢复。';row.state='已启用';row.stateRevision++;log(actor,'恢复模型',row.id,'已停用','已启用');return '';}
    function stateOf(node){
      if(!node.model)return node.optional?'不使用':'未分配';
      if(node.kind==='embedding'){
        const info=indexInfo(node);
        if(node.indexTarget)return node.indexState+(info.available?'':'，当前不可用');
        return info.available?'已生效':'模型已停用';
      }
      return find(node.model)?.state==='已启用'?'已分配':'模型已停用';
    }
    function callContext(node,index){
      const source='products',base={taskId:'RW-'+String(index+1).padStart(3,'0'),module:node.module,source,sourceName:'商品中心库',operation:node.name};
      if(node.module==='数据治理'){
        const tab={'table-description':'overview','field-description':'fields','relation-discovery':'relations','metric-discovery':'metrics'}[node.id];
        return {...base,object:'products.product',ability:'governance.view',href:'#governance/products/product?tab='+tab};
      }
      if(node.module==='知识库与发布'){
        const page=node.id==='question-suggestion'?'test-cases':'knowledge';
        const entries=page==='knowledge'?window.KnowledgeWorkspace?.documents:window.KnowledgeWorkspace?.cases;
        const target=entries&&[...entries.values()].find(value=>value.source===source);
        return {...base,object:target?.title||target?.question||'商品知识规划',ability:page+'.view',href:target?'#'+page+'/'+source+'/'+encodeURIComponent(target.id):''};
      }
      return {...base,object:node.module==='RAG'?'商品知识索引':node.module==='反馈处理'?'商品问题库':'商品101销量问数',ability:node.module==='反馈处理'?'feedback.view':'query.view',href:''};
    }
    function seed(){
      const actor=PeopleDemo.users[0];
      for(let i=0;i<15;i++){
        const protocol=i<8?i%3===1?'anthropic':'openai':i<12?'embedding':'rerank',name=i===0?'通用生成模型':i===1?'Anthropic 生成模型':protocol==='embedding'?'Embedding 向量模型 '+(i-7):protocol==='rerank'?'Rerank 重排模型 '+(i-11):'生成备用模型 '+(i-1);
        const row=save(null,{kind:protocols[protocol].kind,protocol,name,address:'https://models.example.invalid/'+protocol,model:protocol+'-demo-'+(i+1),credential:'fixed-demo-value',timeout:120,retries:2},actor).row;
        row.scenario=i===4?'鉴权失败':'正常';if(i===7)row.state='已停用';
      }
      rows.reverse();
      // 初始示例交错展示三类能力，避免向量模型与重排模型埋在后续分页。
      const leading=[rows[0],rows.find(row=>row.kind==='embedding'),rows.find(row=>row.kind==='rerank'),rows.find(row=>row.content.protocol==='anthropic')];
      rows.splice(0,rows.length,...leading,...rows.filter(row=>!leading.includes(row)));
      const generation=rows.filter(row=>row.kind==='generation'&&row.state==='已启用'),vector=rows.find(row=>row.kind==='embedding'),rank=rows.find(row=>row.kind==='rerank');
      nodes.forEach(node=>{node.model=node.kind==='generation'?generation[0].id:node.kind==='embedding'?vector.id:rank.id;node.active=snapshot(find(node.model));node.indexState='已就绪';});
      const callNodes=['table-description','field-description','relation-discovery','metric-discovery','document-plan','document-write','question-suggestion','intent','clarification','history-summary','sql-generate','sql-semantic-check','result-explanation','rag-embedding','feedback-similarity'];
      for(let i=0;i<15;i++){const node=nodes.find(value=>value.id===callNodes[i]),row=find(node.model),failed=i%3===0;calls.push({id:'DY-'+String(i+1).padStart(3,'0'),node:node.id,nodeName:node.name,module:node.module,model:row.id,revision:row.revision,snapshot:snapshot(row),status:failed?i===9?'结果待核查':'失败':'成功',reason:failed?['服务限流','凭据失效','响应中断'][Math.floor(i/3)%3]:'',at:clock()-i*600000,elapsed:i===9?null:280+i*27,tokens:i%4===0?null:1000+i*13,delivered:node.id==='result-explanation'&&failed});}
      calls.forEach((call,index)=>{call.context=clone(callContext(nodes.find(node=>node.id===call.node),index));});
      changes.length=0;
    }
    if(options.seed!==false)seed();
    return {rows,nodes,calls,changes,tests,find,save,validate,testConnection,assign,usages,stop,enable,stateOf,permitted,clock,snapshot,revisionSnapshot,recordSnapshot,indexInfo,resolveIndex,get assignmentRevision(){return assignmentRevision;}};
  }
  window.SystemModelsDemo=Object.assign(create(),{create,protocols,kinds,protocolOptions});
})();
