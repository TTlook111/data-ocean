import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './styles/variables.css'
import './style.css'
import './styles/governance.css'
import './styles/product-workspace.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

// 兜底记录 Vue 异常发生的生命周期。错误对象可能携带请求配置、令牌或业务正文，
// 控制台只保留受控异常类别；页面仍负责自身的错误态与重试入口。
app.config.errorHandler = (err, _instance, info) => {
  const category = err instanceof TypeError ? 'TYPE_ERROR' : err instanceof RangeError ? 'RANGE_ERROR' : 'UNEXPECTED_ERROR'
  console.error('[界面异常]', { stage: info, category })
}

// 内置分页、日期选择及辅助技术提示统一使用中文，复用组件库的现有翻译。
app.use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
