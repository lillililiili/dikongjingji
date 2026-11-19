import { regionDirectoryApi } from '../../account/data/region-api-client'
import type { CityDirectory } from '../../../shared/city/domain/city-directory'

import { cityNameIn } from './inquiry-view'

// An inquiry carries the delivery city as its code; the pages show its name from the platform city
// directory. One read per session is enough: the directory changes with releases, not with orders.
let directory: Promise<CityDirectory> | null = null

// The city's name, or null when the directory cannot be read: the page then leaves it out.
export async function loadInquiryCityName(cityCode: string): Promise<string | null> {
  if (!cityCode) return null
  directory ??= regionDirectoryApi.load()
  try {
    return cityNameIn(await directory, cityCode)
  } catch {
    directory = null
    return null
  }
}
