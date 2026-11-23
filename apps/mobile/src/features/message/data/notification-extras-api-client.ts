import { publicRequest } from '@/shared/http/public-api-request'

import { createNotificationExtrasApi } from './notification-extras-api'

export const notificationExtrasApi = createNotificationExtrasApi(publicRequest)
