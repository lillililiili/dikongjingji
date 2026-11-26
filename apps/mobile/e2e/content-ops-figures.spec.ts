import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page } from '@playwright/test'

// S3-1 运营端 / S4-4 补字段（契约 openapi-content.yaml 1.3.0）：图文的正文配图块和专题的商家内容。
// 真实后端和开发种子（R__dev_content.sql：机器狗夜巡案例“怎么巡”后面的配图；黄河口低空体验季的编程机器人探索套装）。
// 开发种子记录了受控媒体路径。390 宽截图放在 .planning/style-compare/content，
// 和画板 ContentArticle、Topic 并排对照由 content.spec.ts 的 CNT-201 生成。
const CITY = '370500'
const CASE_DOG = 'f3000000-0000-4000-8000-000000000001'
const TOPIC = 'f3100000-0000-4000-8000-000000000001'
const ROBOT_KIT = 'c2000000-0000-4000-8000-000000000101'
const compareOutput = resolve(__dirname, '../../../.planning/style-compare/content')

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

test.describe('发现内容：正文配图和专题商家内容', () => {
  test('CNT-301 案例详情：“怎么巡”步骤后面有配图和图注', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`/?n=${Date.now()}#/pages/content/article?articleId=${CASE_DOG}&cityCode=${CITY}`)
    const detail = page.getByTestId('content-article')
    await expect(detail).toHaveAttribute('data-state', 'ready')

    const figure = page.getByTestId('content-article-figure')
    await expect(figure).toHaveCount(1)
    await expect(page.getByTestId('content-article-figure-caption'))
      .toHaveText('围墙外侧由无人机补充巡查，机器狗负责地面路线')
    // 正文使用开发库中的实际媒体。
    await expect(figure.locator('img')).toHaveAttribute('src', /content-patrol-drone\.jpg$/)
    // 原来这里的提示块换成了配图：同一句话只出现在图注里。
    await expect(detail.locator('.article__tip', { hasText: '围墙外侧由无人机补充巡查' })).toHaveCount(0)

    // 配图在“白天踏勘，定路线”那组步骤后面、“交付了什么”前面；宽 358（两边各 16）、高 190。
    const steps = await detail.getByText('白天踏勘，定路线').boundingBox()
    const delivered = await detail.getByText('交付了什么').boundingBox()
    const box = await figure.locator('.article__figure-image').boundingBox()
    expect(steps && delivered && box).toBeTruthy()
    expect(box!.y).toBeGreaterThan(steps!.y)
    expect(box!.y + box!.height).toBeLessThan(delivered!.y)
    expect(Math.round(box!.width)).toBe(358)
    expect(Math.round(box!.height)).toBe(190)
    await assertNoHorizontalOverflow(page)

    mkdirSync(compareOutput, { recursive: true })
    await figure.screenshot({ path: resolve(compareOutput, 'ContentArticle-figure-app.png') })
  })

  test('CNT-302 专题页：商家内容读商城的真实商品，标广告，点进商品详情', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`/?n=${Date.now()}#/pages/content/topic?topicId=${TOPIC}&cityCode=${CITY}`)
    await expect(page.getByTestId('content-topic')).toHaveAttribute('data-state', 'ready')

    const block = page.getByTestId('content-topic-merchants')
    await expect(block).toContainText('商家内容')
    const card = page.getByTestId(`content-topic-merchant-${ROBOT_KIT}`)
    await expect(block.locator('[data-testid^="content-topic-merchant-"]')).toHaveCount(1)
    await expect(card.getByTestId('content-sponsored')).toHaveText('广告')
    await expect(card).toContainText('示例机器人商家发布')
    await expect(card).toContainText('编程机器人探索套装')
    // 开发种子：东营三家在售，最低 ¥1,299（画板“¥1,299 起 · 3 家在售”）。
    await expect(card).toContainText('¥1,299')
    await expect(card).toContainText('起 · 3 家在售')
    await expect(card.locator('img')).toHaveAttribute('src', /\/api\/v1\/dev-media\/images\/preview\/robot-rover\.jpg$/)
    await assertNoHorizontalOverflow(page)

    mkdirSync(compareOutput, { recursive: true })
    await block.screenshot({ path: resolve(compareOutput, 'Topic-merchants-app.png') })

    await card.click()
    await expect(page).toHaveURL(new RegExp(`mall/product\\?productId=${ROBOT_KIT}&cityCode=${CITY}`))
    await expect(page.getByTestId('mall-product')).toBeVisible()
  })

  test('CNT-303 商城读不到的商品不显示，整块没有就不显示', async ({ page }) => {
    await page.route('**/api/v1/mall/products/**', (route) => route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'COMMON_NOT_FOUND', message: '资源不存在', traceId: 'e2e' }),
    }))
    await page.goto(`/?n=${Date.now()}#/pages/content/topic?topicId=${TOPIC}&cityCode=${CITY}`)
    await expect(page.getByTestId('content-topic')).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('content-topic-activities')).toBeVisible()
    await expect(page.getByTestId('content-topic-merchants')).toHaveCount(0)
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  })
})
