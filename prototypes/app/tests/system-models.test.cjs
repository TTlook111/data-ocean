const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
function fixture(){const c=vm.createContext({window:{},structuredClone,URL});for(const file of ['record-query.js','people-data.js','role-data.js','source-approval-data.js','system-model-data.js']){vm.runInContext(fs.readFileSync(path.join(__dirname,'../../后台管理',file),'utf8'),c);Object.assign(c,c.window);}const actor=c.PeopleDemo.users[0],model=c.SystemModelsDemo;return {c,actor,model};}
test('模型列表支持OpenAI和Anthropic协议，保存后即可分配，没有登记验收或启用关卡',()=>{const {model,actor}=fixture(),result=model.save(null,{name:'新Anthropic模型',protocol:'anthropic',address:'https://gateway.example.invalid',model:'demo-model',credential:'DEMO-SECRET-839',timeout:120,retries:2},actor);assert.ok(result.row);assert.equal(result.row.state,'可用');assert.equal(model.assign({'sql-generate':result.row.id},actor,model.assignmentRevision),'');assert.equal(model.nodes.find(n=>n.id==='sql-generate').model,result.row.id);assert.equal(JSON.stringify({rows:model.rows,changes:model.changes}).includes('DEMO-SECRET-839'),false);});
test('每个节点独立选择，修改SQL生成不改变SQL修正、解释或其他节点',()=>{const {model,actor}=fixture(),before=new Map(model.nodes.map(n=>[n.id,n.model])),target=model.rows.find(row=>row.content.protocol==='anthropic');assert.equal(model.assign({'sql-generate':target.id},actor,model.assignmentRevision),'');for(const node of model.nodes)assert.equal(node.model,node.id==='sql-generate'?target.id:before.get(node.id));});
test('节点分配检查能力和配置状态，一项错误不会部分覆盖其他节点',()=>{const {model,actor}=fixture(),gen=model.rows.find(r=>r.kind==='generation'),before=JSON.stringify(model.nodes);assert.match(model.assign({'sql-generate':gen.id,'rag-embedding':gen.id},actor,model.assignmentRevision),/向量模型/);assert.equal(JSON.stringify(model.nodes),before);const stopped=model.rows.find(r=>r.state==='已停用');assert.match(model.assign({'sql-generate':stopped.id},actor,model.assignmentRevision),/不可用/);});
test('字段校验和旧修订冲突不会部分修改配置，凭据替换不回显旧值',()=>{const {model,actor}=fixture(),row=model.rows[0],before=JSON.stringify(row),data={name:row.name,...row.content,credential:''};assert.match(model.save(row,{...data,address:'https://user:secret@example.invalid'},actor,row.revision).error,/URL/);assert.equal(JSON.stringify(row),before);assert.match(model.save(row,{...data,model:'other'},actor,0).error,/变化/);assert.equal(model.save(row,{...data,model:'other'},actor,row.revision).error,undefined);assert.equal(row.content.credentialSet,true);assert.equal(row.history[0].content.model,data.model);});
test('调用实际修订保持历史，编辑配置不改写旧记录，连接测试不是节点分配前提',()=>{const {model,actor}=fixture(),row=model.rows[0],calls=JSON.stringify(model.calls);assert.equal(row.test,null);assert.equal(model.testConnection(row,actor,row.revision),'');assert.equal(model.save(row,{name:row.name,...row.content,model:'new-model'},actor,row.revision).error,undefined);assert.equal(row.test,null);assert.equal(JSON.stringify(model.calls),calls);});
test('权限与并发分配独立检查，停用模型保留节点归属并提示不可用',()=>{const {model,actor}=fixture(),row=model.rows[0],revision=model.assignmentRevision;assert.equal(model.assign({'rag-rerank':''},actor,revision),'');assert.match(model.assign({'sql-generate':row.id},actor,revision),/变化/);assert.equal(model.stop(row,actor,row.revision),'');assert.equal(model.stateOf(model.nodes.find(n=>n.model===row.id)),'模型已停用');actor.roles=[];assert.match(model.assign({'sql-generate':''},actor,model.assignmentRevision),/权限/);});
test('RAG模型和反馈语义匹配属于节点分配，不另建检索配置能力或访问页面',()=>{const {c,model}=fixture();assert.ok(model.nodes.some(n=>n.id==='rag-embedding'));assert.ok(model.nodes.some(n=>n.id==='rag-rerank'));assert.ok(model.nodes.some(n=>n.id==='feedback-similarity'));assert.equal(c.RoleDemo.catalog.some(page=>page.id==='retrieval'||page.id==='model-uses'),false);assert.equal(c.RoleDemo.catalog.flatMap(page=>page.operations).some(op=>/acceptance|activate|bind-governance|bind-query/.test(op.id)&&op.id.startsWith('models.')),false);});

test('清空必需节点先确认且不部分写入，可选重排可以直接不使用',()=>{
  const {model,actor}=fixture(),node=model.nodes.find(n=>n.id==='rag-embedding'),before=JSON.stringify(model.nodes),revision=model.assignmentRevision;
  assert.match(model.assign({[node.id]:''},actor,revision),/确认影响/);assert.equal(JSON.stringify(model.nodes),before);assert.equal(model.assignmentRevision,revision);
  assert.equal(model.assign({[node.id]:''},actor,revision,{confirmedClear:true}),'');assert.equal(node.active,null);assert.equal(model.stateOf(node),'未分配');
  assert.equal(model.assign({'rag-rerank':''},actor,model.assignmentRevision),'');assert.equal(model.stateOf(model.nodes.find(n=>n.id==='rag-rerank')),'不使用');
  const original=model.rows.find(row=>row.kind==='embedding');
  assert.equal(model.assign({[node.id]:original.id},actor,model.assignmentRevision),'');assert.equal(model.stateOf(node),'已生效');assert.equal(node.active.modelId,original.id);
});

test('Embedding改配保留原生效快照，失败保留旧路径，校验就绪才切换',()=>{
  const {model,actor}=fixture(),node=model.nodes.find(n=>n.id==='rag-embedding'),old=JSON.stringify(node.active),target=model.rows.filter(r=>r.kind==='embedding')[1];
  assert.equal(model.assign({[node.id]:target.id},actor,model.assignmentRevision),'');assert.equal(node.model,target.id);assert.equal(JSON.stringify(node.active),old);assert.equal(model.stateOf(node),'待索引更新');
  const key=model.indexInfo(node).targetKey;
  assert.equal(model.resolveIndex(node.id,'failed',key,actor),'');assert.equal(JSON.stringify(node.active),old);assert.equal(model.indexInfo(node).available,true);
  // 无关节点的分配不使同一有效索引目标失效。
  assert.equal(model.assign({'rag-rerank':''},actor,model.assignmentRevision),'');
  assert.equal(model.resolveIndex(node.id,'ready',key,actor),'');assert.equal(node.active.modelId,target.id);assert.equal(node.indexTarget,null);assert.equal(model.stateOf(node),'已生效');
});

test('旧索引结果不能覆盖新目标，停用原生效模型会阻断旧路径',()=>{
  const {model,actor}=fixture(),node=model.nodes.find(n=>n.id==='rag-embedding'),vectors=model.rows.filter(r=>r.kind==='embedding'),original=model.find(node.model);
  model.assign({[node.id]:vectors[1].id},actor,model.assignmentRevision);const stale=model.indexInfo(node).targetKey;
  model.assign({[node.id]:vectors[2].id},actor,model.assignmentRevision);assert.match(model.resolveIndex(node.id,'ready',stale,actor),/目标已变化/);assert.equal(node.active.modelId,original.id);
  assert.ok(model.usages(original).includes(node));assert.equal(model.stop(original,actor,original.revision),'');assert.equal(model.indexInfo(node).available,false);assert.match(model.stateOf(node),/当前不可用/);
  assert.equal(model.enable(original,actor,original.revision),'');assert.equal(model.indexInfo(node).available,true);
  const target=model.find(node.model),key=model.indexInfo(node).targetKey;model.stop(target,actor,target.revision);assert.match(model.resolveIndex(node.id,'ready',key,actor),/已停用/);
});

test('向量模型标识变化进入索引待更新，改名不要求重建同一向量身份',()=>{
  const {model,actor}=fixture(),node=model.nodes.find(n=>n.id==='rag-embedding'),row=model.find(node.model),original=node.active.content.model;
  assert.equal(model.save(row,{name:'Embedding 改名',...row.content,credential:''},actor,row.revision).error,undefined);
  assert.equal(node.indexTarget,null);assert.equal(node.active.revision,2);
  assert.equal(model.save(row,{name:row.name,...row.content,model:'new-vector-identity',credential:''},actor,row.revision).error,undefined);
  assert.equal(node.active.content.model,original);assert.equal(node.indexTarget.content.model,'new-vector-identity');
  const old=model.indexInfo(node).targetKey;
  model.save(row,{name:row.name,...row.content,model:'newer-vector-identity',credential:''},actor,row.revision);
  assert.match(model.resolveIndex(node.id,'ready',old,actor),/目标已变化/);
});

test('停用恢复不制造内容修订空洞，旧调用及连接测试保留当次配置快照',()=>{
  const {model,actor}=fixture(),row=model.rows[0],call=model.calls[0],name=row.name,remote=row.content.model;
  model.testConnection(row,actor,row.revision);const testRecord=model.tests[0],oldState=row.stateRevision;
  assert.equal(model.stop(row,actor,row.revision,oldState),'');assert.equal(row.revision,1);
  assert.match(model.enable(row,actor,row.revision,oldState),/状态已变化/);assert.equal(model.enable(row,actor,row.revision,row.stateRevision),'');assert.equal(row.revision,1);
  assert.equal(model.save(row,{name:'改名后配置',...row.content,model:'changed-model',credential:''},actor,row.revision).error,undefined);assert.equal(row.revision,2);assert.equal(row.history[0].revision,1);
  for(const record of [call,testRecord]){const saved=model.recordSnapshot(record);assert.equal(saved.name,name);assert.equal(saved.content.model,remote);assert.equal(saved.revision,1);saved.name='外部改动';assert.equal(model.recordSnapshot(record).name,name);}
  assert.equal(model.revisionSnapshot(row.id,1).content.model,remote);
  assert.equal(model.recordSnapshot({model:row.id,revision:99}),null);
});

test('节点覆盖当前方案AI职责，Embedding和Rerank明确区分，系统执行不伪装成AI调用',()=>{
  const {model}=fixture(),byId=id=>model.nodes.find(n=>n.id===id);
  assert.equal(model.nodes.length,18);
  for(const id of ['table-description','field-description','relation-discovery','metric-discovery','document-plan','document-write','question-suggestion','intent','clarification','history-summary','sql-generate','sql-semantic-check','output-meaning','sql-repair','result-explanation'])assert.equal(byId(id)?.kind,'generation',id);
  assert.equal(byId('rag-embedding').kind,'embedding');assert.equal(byId('feedback-similarity').kind,'embedding');assert.equal(byId('rag-rerank').kind,'rerank');assert.equal(byId('rag-rerank').optional,true);
  assert.ok(!byId('execute')&&!byId('authorization')&&!byId('publish-confirmation'));
  assert.match(model.kinds.embedding,/Embedding/);assert.ok(model.rows.filter(r=>r.kind==='embedding').every(r=>r.name.includes('Embedding')));
});
