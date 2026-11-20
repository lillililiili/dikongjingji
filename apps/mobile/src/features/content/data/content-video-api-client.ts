import { publicRequest } from '@/shared/http/public-api-request'

import { createContentVideoApi } from './content-video-api'

export const contentVideoApi = createContentVideoApi(publicRequest)
