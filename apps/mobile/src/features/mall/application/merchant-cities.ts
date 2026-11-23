import { regionDirectoryApi } from '../../account/data/region-api-client'
import type { CityDirectory } from '../../../shared/city/domain/city-directory'

import { cityLookupFrom, type CityLookup } from './merchant-view'

// 商家主页的可售城市只有编码，名称从平台城市目录查。一次会话读一次就够：目录随版本变，不随商家变。
let directory: Promise<CityDirectory> | null = null

// 读不到目录时返回一个都查不到的查找：页面只写“全国发货”这类说明，不写编码。
export async function loadMerchantCityLookup(): Promise<CityLookup> {
  directory ??= regionDirectoryApi.load()
  try {
    return cityLookupFrom(await directory)
  } catch {
    directory = null
    return cityLookupFrom(null)
  }
}
