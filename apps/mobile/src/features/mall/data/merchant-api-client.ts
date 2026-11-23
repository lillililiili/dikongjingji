import { publicRequest } from '@/shared/http/public-api-request'

import { createMallMerchantApi } from './merchant-api'

export const mallMerchantApi = createMallMerchantApi({ request: publicRequest })
