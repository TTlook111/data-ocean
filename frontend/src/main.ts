import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

// 全局错误处理，防止未捕获异常导致白屏
app.config.errorHandler = (err, instance, info) => {
  console.error('[全局错误]', info, err)
}

app.use(createPinia()).use(router).use(ElementPlus).mount('#app')
