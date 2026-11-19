import { publicRequest } from '@/shared/http/public-api-request'

import { createContentApi } from './content-api'

export const contentApi = createContentApi({ request: publicRequest })
