import { publicRequest } from '@/shared/http/public-api-request'

import { createAddressApi } from './address-api'

export const addressApi = createAddressApi(publicRequest)
