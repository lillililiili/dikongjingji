import { publicRequest } from '@/shared/http/public-api-request'

import { createTradeApi } from './trade-api'

export const tradeApi = createTradeApi(publicRequest)
