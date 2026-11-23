import { publicRequest } from '@/shared/http/public-api-request'

import { createSearchApi } from './search-api'

export const searchApi = createSearchApi({ request: publicRequest })
