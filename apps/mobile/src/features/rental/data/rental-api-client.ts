import { publicRequest } from '@/shared/http/public-api-request'

import { createRentalApi } from './rental-api'

export const rentalApi = createRentalApi(publicRequest)
