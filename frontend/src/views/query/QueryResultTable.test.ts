// @vitest-environment happy-dom
import {mount,flushPromises} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import {it,expect} from 'vitest'
import Table from './QueryResultTable.vue'
it('unmapped technical aliases have explicit neutral labels while data keys stay exact',async()=>{
 const result={taskId:'same',status:'COMPLETED',columns:[{name:'s1_c1',type:'INT'},{name:'region',type:'VARCHAR',comment:'地区'}],data:[{s1_c1:2,region:'east'}],sourceTrace:{sqlHash:'EXACT_HASH'}}
 const before=JSON.stringify(result),w=mount(Table,{props:{result,rows:result.data,page:1,pageSize:50},global:{plugins:[ElementPlus],stubs:{ 'el-table':{name:'ElTable',props:['data'],template:'<div><slot/></div>'},'el-table-column':{name:'ElTableColumn',props:['prop','label'],template:'<div><slot name="header">{{label}}</slot></div>'},'el-pagination':true}}});await flushPromises()
 expect(w.text()).toContain('结果列 1（未提供业务名称）');expect(w.text()).toContain('原始列名：s1_c1');expect(w.text()).toContain('地区')
 expect(w.findAllComponents({name:'ElTableColumn'}).map(c=>c.props('prop'))).toEqual(['s1_c1','region']);expect(JSON.stringify(result)).toBe(before);w.unmount()
})
