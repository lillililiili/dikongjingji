import { publicRequest } from '@/shared/http/public-api-request'

import { createActivityApi } from './activity-api'

export const activityApi = createActivityApi({ request: publicRequest })
