import { publicRequest } from '@/shared/http/public-api-request'

import { createSupportApi } from './support-api'

export const supportApi = createSupportApi(publicRequest)
