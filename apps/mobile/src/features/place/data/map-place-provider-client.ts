import { publicRequest } from '@/shared/http/public-api-request'
import { createMapPlaceProvider } from './map-place-api'

export const mapPlaceProvider = createMapPlaceProvider({ request: publicRequest })
