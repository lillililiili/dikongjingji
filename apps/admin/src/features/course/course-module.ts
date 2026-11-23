import { Notebook } from '@element-plus/icons-vue'

import type { AdminModule } from '@/core/navigation/module'

const ROLES = ['PLATFORM_OPERATOR'] as const

/** 课程（契约 openapi-ops-course.yaml）：线上录播课和线下班，只给平台运营。 */
export const courseModule: AdminModule = {
  key: 'course',
  menu: [
    { path: '/courses', title: '课程', icon: Notebook, group: 'PLATFORM', roles: ROLES, order: 45 },
  ],
  routes: [
    {
      path: '/courses',
      name: 'courses',
      component: () => import('./CourseListPage.vue'),
      meta: { title: '课程', roles: ROLES },
    },
    {
      path: '/courses/:courseId',
      name: 'course-detail',
      component: () => import('./CourseEditPage.vue'),
      meta: { title: '课程详情', roles: ROLES },
    },
  ],
}
