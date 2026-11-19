import { publicRequest } from '@/shared/http/public-api-request'

import { createMallInquiryApi } from './inquiry-api'

export const mallInquiryApi = createMallInquiryApi(publicRequest)
