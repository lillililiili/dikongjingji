import { publicRequest } from '@/shared/http/public-api-request'

import { createServiceOrderApi } from './service-order-api'

export const serviceOrderApi = createServiceOrderApi(publicRequest)
