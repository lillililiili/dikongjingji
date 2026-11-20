import { publicRequest } from '@/shared/http/public-api-request'

import { createLiveApi } from './live-api'

export const liveApi = createLiveApi(publicRequest)
