import { publicRequest } from '@/shared/http/public-api-request'

import { createDemandApi } from './demand-api'

export const demandApi = createDemandApi(publicRequest)
