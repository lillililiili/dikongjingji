import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test } from '@playwright/test'

// S3-1：城市面板照原型 CitySheet 分“已开通城市 / 筹备中”两组，开通状态来自平台城市目录（openapi-platform-city.yaml 1.1.0，
// 运营端维护）。依赖开发种子 R__dev_platform_city_service_status.sql：已开通东营；筹备中济南、青岛、潍坊；其余未开通。
// 后端要换成带 S3-1 的 jar 并重启（种子随启动执行），否则目录里没有这两组。

const visualOutput = resolve(__dirname, '../../../.planning/style-compare')

test.describe('城市面板（CitySheet）', () => {
  test('CITY-301 分已开通、筹备中两组，未开通的只能搜索到', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.getByTestId('home-city').click()
    const sheet = page.locator('.city-sheet')
    await expect(sheet).toBeVisible()

    await expect(sheet.locator('.city-sheet__current-name')).toContainText('东营市 · 已开通')
    await expect(sheet).toContainText('已开通城市')
    await expect(sheet.locator('.city-sheet__open .city-sheet__city')).toHaveText(['东营市 ✓'])
    await expect(sheet).toContainText('筹备中（可先浏览全国商品与线上体验）')
    const preparing = sheet.locator('.city-sheet__preparing .city-sheet__city')
    await expect(preparing).toHaveText(['济南市筹备', '青岛市筹备', '潍坊市筹备'])
    // 画板没有的分组不再出现。
    await expect(sheet).not.toContainText('热门城市')
    await expect(sheet).not.toContainText('更多城市')
    await expect(sheet).not.toContainText('最近使用')
    await expect(sheet).toContainText('账号、订单、参与人和收藏不变，跨城通用')

    mkdirSync(visualOutput, { recursive: true })
    await page.screenshot({ path: resolve(visualOutput, 's3-city-sheet-390.png') })

    // 未开通的城市（烟台）只在搜索结果里，名字后面标“未开通”。
    await expect(sheet.locator('.city-sheet__city', { hasText: '烟台市' })).toHaveCount(0)
    await sheet.getByLabel('搜索城市').fill('烟台')
    await expect(sheet.locator('.city-sheet__search-results .city-sheet__city')).toHaveText(['烟台市未开通'])
  })

  test('CITY-302 选筹备中的城市也会切换过去', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.getByTestId('home-city').click()
    await expect(page.locator('.city-sheet')).toBeVisible()

    await page.locator('.city-sheet__preparing .city-sheet__city', { hasText: '潍坊市' }).click()

    await expect(page.locator('.city-sheet')).toHaveCount(0)
    await expect(page.getByTestId('home-city')).toContainText('潍坊')
    await page.getByTestId('home-city').click()
    await expect(page.locator('.city-sheet__current-name')).toContainText('潍坊市 · 筹备中')
    await expect(page.locator('.city-sheet__preparing .city-sheet__city', { hasText: '潍坊市' }))
      .toHaveText('潍坊市 ✓筹备')
  })
})
