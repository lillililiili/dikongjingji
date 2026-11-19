import { publicRequest } from '@/shared/http/public-api-request'

import { createVenueBookingApi } from './venue-booking-api'

export const venueBookingApi = createVenueBookingApi(publicRequest)
