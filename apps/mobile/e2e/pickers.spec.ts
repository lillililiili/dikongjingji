import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { BUYER, apiLogin, clearAddresses, type Session } from './support/trade-fixtures'

// 公共页面（计划 08 S5-10，画板 MapPick、RegionPick、PhotoViewer、Scan、CityClosed），开发版（预览构建）。
// uni-button 的禁用在 H5 上只看 disabled 属性（toBeDisabled 读不出来）。
// 地图选点走本机模拟地图（浏览器里没有定位，落在“定位被拒”帧、从城市中心开始）；城市未开通用接口桩让东营“没有本地资源”。
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/s5-pickers')
const SERVICE_ID = '33333333-3333-3333-3333-333333333333'
const BOOKING_URL = `/#/pages/service/inspection/booking?serviceId=${SERVICE_ID}&cityCode=370500`
const KIT_ID = 'c2000000-0000-4000-8000-000000000101'
const ARTICLE_ID = 'f3000000-0000-4000-8000-000000000001'

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

async function shot(page: Page, name: string): Promise<void> {
  if (!process.env.PICKERS_VISUAL) return
  mkdirSync(compareOutput, { recursive: true })
  await page.screenshot({ path: resolve(compareOutput, `${name}-390.png`) })
}

async function dragMap(page: Page, dx: number, dy: number): Promise<void> {
  const box = await page.getByTestId('map-pick-map').boundingBox()
  if (!box) throw new Error('map is not on the page')
  const x = box.x + box.width / 2
  const y = box.y + box.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + dx / 2, y + dy / 2, { steps: 4 })
  await page.mouse.move(x + dx, y + dy, { steps: 4 })
  await page.mouse.up()
}

test.describe('地图选点（MapPick）', () => {
  test('PICK-001 巡检预约的“巡检地点”进地图选点：选示例地点、拖图钉手填地址、搜不到时用输入的文字', async ({ page }) => {
    await page.goto(BOOKING_URL)
    await expect(page.locator('.inspection-booking__content')).toBeVisible()
    await page.getByTestId('booking-location').click()
    await expect(page).toHaveURL(/pages\/place\/map-pick\?purpose=INSPECTION&requestId=.+&cityCode=370500/)
    await expect(page.getByTestId('map-pick')).toContainText('选择巡检地点')
    // 浏览器里没有定位：说明并从城市中心开始。
    await expect(page.getByTestId('map-pick-denied')).toContainText('未开启定位，可手动搜索')
    await expect(page.getByTestId('map-pick-chosen')).toContainText('在地图上拖动选点，或搜索地址')
    await expect(page.getByTestId('map-pick-chosen')).toContainText('选中的位置作为巡检起点')
    await expect(page.getByTestId('map-pick-confirm')).toHaveAttribute('disabled', 'true')
    await shot(page, 'map-pick-denied')

    // 选一个示例地点并确认，回到预约页。
    await page.getByTestId('map-pick-place-road-88').click()
    await expect(page.getByTestId('map-pick-pin')).toContainText('示例路 88 号')
    await expect(page.getByTestId('map-pick-confirm')).not.toHaveAttribute('disabled', 'true')
    await page.getByTestId('map-pick-confirm').click()
    await expect(page.getByTestId('booking-location')).toContainText('东营市示例路 88 号（示例）')

    // 再进一次：拖动地图，图钉位置要手填详细地址才能确认。
    await page.getByTestId('booking-location').click()
    await dragMap(page, 60, 40)
    await expect(page.getByTestId('map-pick-pin')).toContainText('图钉位置')
    await expect(page.getByTestId('map-pick-confirm')).toHaveAttribute('disabled', 'true')
    await page.getByTestId('map-pick-manual').locator('input').fill('东营区西二路 100 号院内管廊')
    await expect(page.getByTestId('map-pick-confirm')).not.toHaveAttribute('disabled', 'true')
    await shot(page, 'map-pick-pin')
    await page.getByTestId('map-pick-confirm').click()
    await expect(page.getByTestId('booking-location')).toContainText('东营区西二路 100 号院内管廊')

    // 搜索：命中示例地点；搜不到时就用输入的文字作为图钉位置的地址。
    await page.getByTestId('booking-location').click()
    const search = page.getByTestId('map-pick-search').locator('input')
    await search.fill('产业园')
    await expect(page.getByTestId('map-pick-place-park-gate-3')).toBeVisible()
    await search.fill('黄河路 66 号')
    await expect(page.getByTestId('map-pick-none')).toContainText('没有找到“黄河路 66 号”相关的地点')
    await page.getByTestId('map-pick-use-typed').click()
    await expect(page.getByTestId('map-pick-manual').locator('input')).toHaveValue('黄河路 66 号')
    await page.getByTestId('map-pick-back').click()
    // 返回不改预约页上的地点。
    await expect(page.getByTestId('booking-location')).toContainText('东营区西二路 100 号院内管廊')
  })

  test('PICK-002 需求发布的地图选点把地址填进“服务地点”', async ({ page, request }) => {
    const buyer = await apiLogin(request, BUYER)
    test.skip(!buyer, '本地后端没有开发账号（不是 dev 配置）')
    await signIn(page, buyer as Session)
    await page.goto('/#/pages/demand/publish?type=INSPECTION')
    await expect(page.getByTestId('demand-city')).toContainText('东营')
    await page.getByTestId('demand-map-pick').click()
    await expect(page.getByTestId('map-pick')).toContainText('选择服务地点')
    await page.getByTestId('map-pick-place-park-gate-3').click()
    await page.getByTestId('map-pick-confirm').click()
    await expect(page.getByTestId('demand-address').locator('input')).toHaveValue('东营市示例路 96 号（示例）')
  })
})

test.describe('省市选择（RegionPick）', () => {
  test('PICK-003 收货地址的“所在地区”：当前城市一键使用，省份换了城市清空，确认后回到表单', async ({ page, request }) => {
    const signedIn = await apiLogin(request, BUYER)
    test.skip(!signedIn, '本地后端没有开发账号（不是 dev 配置）')
    const buyer = signedIn as Session
    await clearAddresses(request, buyer)
    await signIn(page, buyer)

    await page.goto('/#/pages/mine/addresses')
    await page.getByTestId('address-add').click()
    await page.getByTestId('address-region').click()
    const picker = page.getByTestId('address-region-picker')
    await expect(picker).toContainText('选择所在地区')
    await expect(page.getByTestId('address-region-confirm')).toHaveAttribute('disabled', 'true')
    await expect(page.getByTestId('address-region-picked')).toHaveText('已选：还没有选择')
    await expect(picker).toContainText('地区选到市；区县、街道和门牌请写在详细地址里')
    await shot(page, 'region-pick')

    await page.getByTestId('address-region-use-located').click()
    await expect(page.getByTestId('address-region-picked')).toHaveText('已选：山东省 东营市')

    // 回到省份换一个：城市清空，确认不可用。
    await picker.getByRole('tab').first().click()
    await page.getByTestId('address-province-110000').click()
    await expect(page.getByTestId('address-region-picked')).toHaveText('已选：北京市')
    await page.getByTestId('address-province-370000').click()
    await expect(page.getByTestId('address-region-picked')).toHaveText('已选：山东省 …')
    await expect(page.getByTestId('address-region-confirm')).toHaveAttribute('disabled', 'true')
    await page.getByTestId('address-city-370500').click()
    await page.getByTestId('address-region-confirm').click()
    await expect(picker).toHaveCount(0)
    await expect(page.getByTestId('address-region')).toContainText('山东省 东营市')
    await clearAddresses(request, buyer)
  })
})

test.describe('图片查看器（PhotoViewer）', () => {
  test('PICK-004 图文的头图点开进图片查看器，× 返回', async ({ page }) => {
    await page.goto(`/#/pages/content/article?articleId=${ARTICLE_ID}&cityCode=370500`)
    await page.getByTestId('content-article-hero-image').click()
    await expect(page).toHaveURL(/pages\/common\/photo-viewer$/)
    await expect(page.getByTestId('photo-viewer-page')).toHaveText('1 / 1')
    await expect(page.getByTestId('photo-viewer')).toContainText('图片仅供查看')
    await expect(page.getByTestId('photo-viewer-prev')).toHaveAttribute('disabled', 'true')
    await shot(page, 'photo-viewer')
    await page.getByTestId('photo-viewer-close').click()
    await expect(page).toHaveURL(/pages\/content\/article\?articleId=/)
  })

  test('PICK-005 刷新后查看器说图片已关闭', async ({ page }) => {
    await page.goto('/#/pages/common/photo-viewer')
    await expect(page.getByTestId('photo-viewer-empty')).toContainText('图片已关闭')
  })
})

test.describe('扫一扫（Scan）', () => {
  test('PICK-006 浏览器里手动输入：平台链接打开对象页，核销码和外部码说明不打开', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')
    await page.getByTestId('home-scan').click()
    await expect(page.getByTestId('scan-page')).toContainText('将二维码放入框内，自动识别')
    await expect(page.getByTestId('scan-manual')).toContainText('输入码内容或链接')
    await shot(page, 'scan')

    const input = page.getByTestId('scan-input').locator('input')
    await input.fill('ACT-1234-5678-9ABC')
    await page.getByTestId('scan-submit').click()
    await expect(page.getByTestId('scan-sheet-check_in_code')).toContainText('这是核销码')
    await expect(page.getByTestId('scan-sheet-check_in_code')).toContainText('停在这里，不显示码内信息')
    await page.getByTestId('scan-ok').click()

    await input.fill('https://evil.example.com/#/pages/mall/product?productId=' + KIT_ID)
    await page.getByTestId('scan-submit').click()
    await expect(page.getByTestId('scan-sheet-unknown')).toContainText('为了安全不会打开外部链接')
    await page.getByTestId('scan-again').click()

    await input.fill(`/pages/mall/product?productId=${KIT_ID}&cityCode=370500`)
    await page.getByTestId('scan-submit').click()
    await expect(page).toHaveURL(new RegExp(`pages/mall/product\\?productId=${KIT_ID}&cityCode=370500`))
  })
})

test.describe('城市未开通（CityClosed）', () => {
  test('PICK-007 本城没有本地资源时首页只留推荐、体验、商城，显示筹备中和全国可用', async ({ page }) => {
    await page.route('**/api/v1/home/recommend?**', (route) => route.fulfill({
      json: { today: null, activities: [], products: [], venues: [] },
    }))
    await page.route('**/api/v1/inspection/services?**', (route) => route.fulfill({
      json: { page: 1, size: 1, total: 0, hasMore: false, items: [] },
    }))
    await page.goto('/#/pages/home/index')
    const closed = page.getByTestId('city-closed')
    await expect(closed).toBeVisible()
    await expect(page.getByTestId('city-closed-title')).toHaveText('东营城市站筹备中')
    await expect(closed).toContainText('全国发货商品和线上体验可以正常使用。')
    await expect(page.locator('.home-header__channel')).toHaveText(['推荐', '体验', '商城'])
    await expect(page.getByTestId('home-search-input')).toContainText('搜索全国发货商品与线上体验')
    await shot(page, 'city-closed')

    await page.getByTestId('city-closed-notify').click()
    await expect(page.locator('.uni-sample-toast')).toContainText('城市开通提醒尚未上线')

    await page.getByTestId('city-closed-course').click()
    await expect(page.getByTestId('mall-channel')).toBeVisible()
    await expect(page.getByTestId('mall-category-course')).toHaveAttribute('aria-pressed', 'true')
  })
})
