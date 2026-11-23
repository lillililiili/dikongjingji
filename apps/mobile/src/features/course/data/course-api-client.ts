import { publicRequest } from '@/shared/http/public-api-request'

import { createCourseApi } from './course-api'

export const courseApi = createCourseApi(publicRequest)
