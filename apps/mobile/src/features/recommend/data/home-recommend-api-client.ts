import { publicRequest } from '@/shared/http/public-api-request'

import { createHomeRecommendApi } from './home-recommend-api'

export const homeRecommendApi = createHomeRecommendApi({ request: publicRequest })
