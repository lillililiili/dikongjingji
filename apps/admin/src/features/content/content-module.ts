import { Reading } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 发现内容（契约 openapi-ops-content.yaml）：图文、视频、专题，只给平台运营。 */
export const contentModule: AdminModule = {
  key: 'content',
  menu: [
    { path: '/content', title: '发现内容', icon: Reading, group: 'PLATFORM', roles: ROLES, order: 60 },
  ],
  routes: [
    {
      path: '/content',
      name: 'content',
      component: () => import('./ContentListPage.vue'),
      meta: { title: '发现内容', roles: ROLES },
    },
    {
      path: '/content/articles/new',
      name: 'content-article-new',
      component: () => import('./ArticleEditPage.vue'),
      meta: { title: '新建图文', roles: ROLES },
    },
    {
      path: '/content/articles/:articleId',
      name: 'content-article',
      component: () => import('./ArticleEditPage.vue'),
      meta: { title: '图文', roles: ROLES },
    },
    {
      path: '/content/videos/new',
      name: 'content-video-new',
      component: () => import('./VideoEditPage.vue'),
      meta: { title: '新建视频', roles: ROLES },
    },
    {
      path: '/content/videos/:videoId',
      name: 'content-video',
      component: () => import('./VideoEditPage.vue'),
      meta: { title: '视频', roles: ROLES },
    },
    {
      path: '/content/topics/new',
      name: 'content-topic-new',
      component: () => import('./TopicEditPage.vue'),
      meta: { title: '新建专题', roles: ROLES },
    },
    {
      path: '/content/topics/:topicId',
      name: 'content-topic',
      component: () => import('./TopicEditPage.vue'),
      meta: { title: '专题', roles: ROLES },
    },
  ],
}
