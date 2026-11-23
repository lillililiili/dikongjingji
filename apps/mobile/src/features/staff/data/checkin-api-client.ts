import { publicRequest } from '@/shared/http/public-api-request'

import { createCheckinApi } from './checkin-api'

export const checkinApi = createCheckinApi(publicRequest)
