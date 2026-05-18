import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import AdminHomeView from '../views/AdminHomeView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/login',
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView,
    },
    {
      path: '/admin',
      name: 'admin',
      component: AdminHomeView,
    },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem('dataocean_token')
  if (to.path.startsWith('/admin') && !token) {
    return '/login'
  }
  if (to.path === '/login' && token) {
    return '/admin'
  }
  return true
})

export default router
