import { createRouter, createWebHistory } from 'vue-router'
import Machine from '../views/Machine.vue'
import Order from '../views/Order.vue'
import Signoff from '../views/Signoff.vue'
import Paper from '../views/Paper.vue'
import Product from '../views/Product.vue'

const routes = [
  { path: '/', redirect: '/machines' },
  { path: '/machines', name: '装订机', component: Machine },
  { path: '/orders', name: '工单', component: Order },
  { path: '/signoffs', name: '签样', component: Signoff },
  { path: '/papers', name: '纸张', component: Paper },
  { path: '/products', name: '成品', component: Product }
]

export default createRouter({ history: createWebHistory(), routes })
