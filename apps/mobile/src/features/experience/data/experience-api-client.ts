import { publicRequest } from '@/shared/http/public-api-request'

import { createExperienceApi } from './experience-api'

export const experienceApi = createExperienceApi({ request: publicRequest })
