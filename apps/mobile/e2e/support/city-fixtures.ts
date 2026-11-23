import type { Page } from '@playwright/test'

/**
 * Reports one city as 已开通 in the platform city directory (openapi-platform-city 1.1.0) for this
 * page only, leaving the rest of the real response as it is. The development seed marks 济南 筹备中
 * after board CitySheet, and the home page then keeps only 推荐、体验、商城 (PICK-007); specs that
 * use 济南 as a second inspection city and come back to the home page's 专业服务 mark it open.
 */
export async function markCityOpen(page: Page, cityCode: string): Promise<void> {
  await page.route('**/api/v1/platform/cities', async (route) => {
    const response = await route.fetch()
    const body = await response.json()
    const mark = (city: { code: string; serviceStatus?: string }) => {
      if (city.code === cityCode) city.serviceStatus = 'OPEN'
    }
    body.hotCities.forEach(mark)
    body.provinces.forEach((province: { cities: { code: string }[] }) => province.cities.forEach(mark))
    const preparing: { code: string; serviceStatus?: string }[] = body.preparingCities ?? []
    preparing.forEach(mark)
    body.openCities = [...(body.openCities ?? []), ...preparing.filter((city) => city.code === cityCode)]
    body.preparingCities = preparing.filter((city) => city.code !== cityCode)
    await route.fulfill({ response, json: body })
  })
}
