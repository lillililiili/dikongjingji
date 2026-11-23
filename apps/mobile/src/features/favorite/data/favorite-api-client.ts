import { publicRequest } from '@/shared/http/public-api-request'

import { createFavoriteApi } from './favorite-api'

export const favoriteApi = createFavoriteApi(publicRequest)
