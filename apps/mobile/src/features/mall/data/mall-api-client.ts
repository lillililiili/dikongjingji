import { publicRequest } from '@/shared/http/public-api-request'

import { createMallApi } from './mall-api'

export const mallApi = createMallApi({ request: publicRequest })
