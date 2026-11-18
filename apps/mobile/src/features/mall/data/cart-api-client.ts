import { publicRequest } from '@/shared/http/public-api-request'

import { createCartApi } from './cart-api'

export const cartApi = createCartApi(publicRequest)
