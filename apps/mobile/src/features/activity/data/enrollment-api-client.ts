import { publicRequest } from '@/shared/http/public-api-request'

import { createEnrollmentApi } from './enrollment-api'

export const enrollmentApi = createEnrollmentApi(publicRequest)
