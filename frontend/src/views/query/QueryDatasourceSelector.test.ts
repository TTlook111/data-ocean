// @vitest-environment happy-dom
import {it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import Selector from './QueryDatasourceSelector.vue'
it('selector distinguishes unknown, failed, forbidden and ready states',async()=>{
 const w=mount(Selector,{props:{datasources:[{id:1,name:'Synthetic',enabled:true}],readinessMap:{},selectedId:1,loading:false,readinessLoading:false,errorMessage:''},global:{plugins:[ElementPlus]}})
 expect(w.get('.datasource-trigger').text()).toContain('状态未确认')
 await w.setProps({readinessErrors:{1:'读取失败'}});expect(w.get('.datasource-trigger').text()).toContain('状态读取失败')
 await w.setProps({readinessErrors:{1:'当前账号无权读取'}});expect(w.get('.datasource-trigger').text()).toContain('无权读取状态')
 await w.setProps({readinessLoading:true});expect(w.get('.datasource-trigger').text()).toContain('状态确认中')
 await w.setProps({readinessLoading:false,readinessErrors:{},readinessMap:{1:{askable:true} as never}})
 expect(w.get('.datasource-trigger').text()).toContain('已就绪');w.unmount()
})
