import { publicRequest } from '@/shared/http/public-api-request'

import { createExperienceRecordApi } from './experience-record-api'

export const experienceRecordApi = createExperienceRecordApi(publicRequest)
