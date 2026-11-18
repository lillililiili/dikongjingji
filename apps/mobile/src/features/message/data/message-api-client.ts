import { publicRequest } from '@/shared/http/public-api-request'

import { createMessageApi } from './message-api'

export const messageApi = createMessageApi(publicRequest)
