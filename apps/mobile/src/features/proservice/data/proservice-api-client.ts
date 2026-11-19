import { publicRequest } from '@/shared/http/public-api-request'

import { createProServiceApi } from './proservice-api'

export const proServiceApi = createProServiceApi({ request: publicRequest })
