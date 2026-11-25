import { Document, Picture } from '@element-plus/icons-vue'
import type { AdminModule } from '@/core/navigation/module'

const roles=['PLATFORM_OPERATOR'] as const
export const pageContentModule: AdminModule = {
  key:'home-carousel',
  menu:[
    {path:'/home-carousel',title:'首页轮播',icon:Picture,group:'PLATFORM',roles,order:5},
    {path:'/content/agreements',title:'平台协议',icon:Document,group:'PLATFORM',roles,order:48},
  ],
  routes:[
    {path:'/home-carousel',name:'home-carousel',component:()=>import('./HomeCarouselPage.vue'),meta:{title:'首页轮播',roles}},
    {path:'/content/agreements',name:'platform-agreements',component:()=>import('./AgreementsPage.vue'),meta:{title:'平台协议',roles}},
  ],
}
