import { createCityDirectoryApi } from '@/shared/city/data/city-directory-api'
import { publicRequest } from '@/shared/http/public-api-request'

// Provinces and prefecture-level cities for the address form, from the platform city directory.
export const regionDirectoryApi = createCityDirectoryApi({ request: publicRequest })
