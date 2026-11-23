import { publicRequest } from '@/shared/http/public-api-request'

import { createIdentityAccountApi } from './identity-account-api'

export const identityAccountApi = createIdentityAccountApi(publicRequest)
