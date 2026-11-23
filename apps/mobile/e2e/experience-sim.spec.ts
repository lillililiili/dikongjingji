import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import { API, BUYER, CITY, apiLogin, databaseAvailable, psql, type Session } from './support/trade-fixtures'

// 线上体验（S5-4，计划 08 §5 D7）：两个 App 内仿真模板（机器人避障、无人机起降与悬停）、体验结果、我的体验、
// 软件试用和课程试听、商品详情的“选择体验方式”。真实后端（契约 openapi-experience 1.3.0，迁移 experience V004）。
// 用 dev 种子里的四个线上体验项目；每条用例前后把 dev-customer 的体验记录从数据库删掉。
// 游客玩的成绩先存在本机，登录后用同一个幂等键补交，只记一次。
const ROBOT = 'b1000000-0000-4000-8000-000000000001'
const DRONE = 'b1000000-0000-4000-8000-000000000002'
const SOFTWARE = 'b1000000-0000-4000-8000-000000000004'
const COURSE = 'b1000000-0000-4000-8000-000000000005'
const KIT = 'c2000000-0000-4000-8000-000000000101'
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/experience-sim')
const STEP_WAIT = 15_000

// Solutions of the three levels, as the palette test ids (simulation-add-<block>).
const ROBOT_SOLUTIONS = [
  ['forward', 'turn_left', 'forward', 'turn_right', 'forward', 'forward', 'turn_right', 'forward'],
  ['forward', 'turn_left', 'forward', 'turn_right', 'forward', 'forward', 'forward'],
  ['forward', 'forward', 'turn_right', 'forward', 'forward', 'forward', 'forward', 'turn_left', 'forward', 'forward'],
]
const DRONE_SOLUTIONS = [
  ['takeoff', 'throttle', 'hover', 'land'],
  ['takeoff', 'throttle', 'throttle', 'throttle', 'hover', 'land'],
  ['takeoff', 'throttle', 'throttle', 'hover', 'throttle', 'throttle', 'hover', 'land'],
]

const buyerId = () => `(SELECT id FROM identity_user WHERE login_name = '${BUYER}')`

function cleanup(): void {
  psql(`DELETE FROM experience_record WHERE account_id = ${buyerId()};`)
}

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

async function records(request: APIRequestContext, session: Session, experienceId?: string) {
  const query = experienceId ? `?experienceId=${experienceId}` : ''
  const response = await request.get(`${API}/api/v1/experience/records${query}`, { headers: session.headers })
  expect(response.ok()).toBe(true)
  return (await response.json()) as {
    total: number
    items: Array<{ recordId: string; status: string; result: { score: number; levelsCompleted: number; mistakes: number } | null }>
  }
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

async function build(page: Page, blocks: string[]): Promise<void> {
  for (const block of blocks) await page.getByTestId(`simulation-add-${block}`).click()
}

// Runs the program and waits for the run to finish playing (the button says 运行程序 / 下一关 / 查看结果 again).
async function run(page: Page): Promise<void> {
  await page.getByTestId('simulation-run').click()
  await expect(page.getByTestId('simulation-run')).not.toHaveText('运行中…', { timeout: STEP_WAIT })
}

async function solveAll(page: Page, solutions: string[][]): Promise<void> {
  for (const [index, solution] of solutions.entries()) {
    await build(page, solution)
    await run(page)
    await expect(page.getByTestId('simulation-status')).toHaveText(new RegExp(`^第 ${index + 1} 关完成`))
    await expect(page.getByTestId('simulation-run')).toHaveText(index === solutions.length - 1 ? '查看结果' : '下一关')
    await page.getByTestId('simulation-run').click()
  }
}

test.describe('线上体验：仿真、结果与记录', () => {
  let session: Session

  test.beforeAll(async ({ request }) => {
    test.skip(!databaseAvailable(), '需要开发数据库（清理测试账号的体验记录）')
    const signedIn = await apiLogin(request, BUYER)
    test.skip(!signedIn, '开发账号不可用')
    session = signedIn as Session
  })

  test.beforeEach(() => cleanup())
  test.afterAll(() => {
    if (databaseAvailable()) cleanup()
  })

  test('SIM-001 体验频道 → 机器人避障：失败重试、三关通关，游客结果存在本机，登录后补交只记一次', async ({ page, request }) => {
    await page.goto('/#/pages/home/index')
    await page.getByTestId('home-channel-experience').click()
    await page.getByTestId(`experience-item-${ROBOT}`).click()

    await expect(page.getByTestId('simulation-page')).toBeVisible()
    await expect(page.getByTestId('simulation-level')).toHaveText('关卡 1 / 3')
    await expect(page.getByTestId('simulation-status')).toHaveText('目标：绕过障碍，走到绿色终点')
    await expect(page.getByTestId('simulation-clock')).toHaveText(/^\d\d:\d\d$/)

    // 失败：撞上障碍，留在原地，计一次碰撞；程序保留，可以改了再跑。
    await build(page, ['forward', 'forward'])
    await run(page)
    await expect(page.getByTestId('simulation-status')).toHaveText('撞到障碍 1 次，没到终点。改一改程序再运行')
    await expect(page.getByTestId('simulation-stat-2')).toHaveText('碰撞 1 次')
    await page.getByTestId('simulation-program-1').click()
    await expect(page.locator('[data-testid^="simulation-program-"]')).toHaveCount(1)
    await page.getByTestId('simulation-reset').click()
    await expect(page.locator('[data-testid^="simulation-program-"]')).toHaveCount(0)

    await solveAll(page, ROBOT_SOLUTIONS)

    await expect(page.getByTestId('simulation-result')).toBeVisible()
    await expect(page).toHaveURL(/pages\/experience\/result\?experienceId=.*&localId=/)
    await expect(page.getByTestId('simulation-result-title')).toHaveText('挑战完成')
    await expect(page.getByTestId('simulation-result-sub')).toHaveText('记录只保存在本机，登录后可归档到账户')
    await expect(page.getByTestId('simulation-result-stat-1')).toContainText('3/3')
    await expect(page.getByTestId('simulation-result-stat-2')).toContainText('1 次')
    await expect(page.getByTestId('simulation-result-guest')).toBeVisible()
    expect((await records(request, session)).total).toBe(0)

    // 登录后回到结果页：本机的成绩补交到账户，页面换成记录。
    await page.getByTestId('simulation-result-login').click()
    await expect(page).toHaveURL(/login/)
    await page.evaluate((token) => localStorage.setItem('inspection.accessToken', token), session.token)
    const localUrl = decodeURIComponent(decodeURIComponent(new URL(page.url()).hash.split('returnUrl=')[1] ?? ''))
    await page.goto(`/#${localUrl}`)
    await expect(page).toHaveURL(/recordId=/)
    await expect(page.getByTestId('simulation-result-sub')).toHaveText('得分 95 分')
    await expect(page.getByTestId('simulation-result-guest')).toHaveCount(0)

    const saved = await records(request, session, ROBOT)
    expect(saved.total).toBe(1)
    expect(saved.items[0]).toMatchObject({ status: 'COMPLETED', result: { score: 95, levelsCompleted: 3, mistakes: 1 } })

    // 再打开同一个本机结果也不会再记一次。
    await page.goto(`/#${localUrl}`)
    await expect(page).toHaveURL(/recordId=/)
    expect((await records(request, session, ROBOT)).total).toBe(1)
  })

  test('SIM-002 无人机起降：失误计数，第 1 关后退出记为未完成（登录时直接记到账户）', async ({ page, request }) => {
    await signIn(page, session)
    await page.goto(`/#/pages/experience/simulation?experienceId=${DRONE}`)
    await expect(page.getByTestId('simulation-status')).toHaveText('目标：升到 1.5 m 悬停，再降落')

    await build(page, ['throttle', 'takeoff', 'throttle'])
    await run(page)
    await expect(page.getByTestId('simulation-status')).toHaveText('程序结束时还在空中，最后要降落，失误 1 次')
    await expect(page.getByTestId('simulation-drone')).toHaveAttribute('data-height', '15')
    await page.getByTestId('simulation-reset').click()
    await build(page, DRONE_SOLUTIONS[0])
    await run(page)
    await page.getByTestId('simulation-run').click()
    await expect(page.getByTestId('simulation-level')).toHaveText('关卡 2 / 3')
    await expect(page.getByTestId('simulation-status')).toHaveText('目标：升到 2.5 m 悬停，再降落')

    await page.getByTestId('simulation-exit').click()
    await expect(page.locator('.uni-modal')).toContainText('退出后记为未完成，停在第 2 关')
    await page.locator('.uni-modal__btn_primary').click()

    await expect(page).toHaveURL(/recordId=/)
    await expect(page.getByTestId('simulation-result-title')).toHaveText('还差一点')
    await expect(page.getByTestId('simulation-result-sub')).toHaveText('得分 25 分 · 停在第 2 关，可以再试一次')
    await expect(page.getByTestId('simulation-result-main')).toHaveText('再试一次')
    const saved = await records(request, session, DRONE)
    expect(saved.items[0]).toMatchObject({ status: 'UNFINISHED', result: { levelsCompleted: 1, mistakes: 1 } })

    await page.getByTestId('simulation-result-main').click()
    await expect(page.getByTestId('simulation-page')).toBeVisible()
    await expect(page.getByTestId('simulation-level')).toHaveText('关卡 1 / 3')

    // 三关都过：查看结果。
    await solveAll(page, DRONE_SOLUTIONS)
    await expect(page.getByTestId('simulation-result-title')).toHaveText('挑战完成')
    expect((await records(request, session, DRONE)).total).toBe(2)
  })

  test('SIM-003 商品详情：先体验 → 选择体验方式；体验过的显示上一次成绩', async ({ page, request }) => {
    await signIn(page, session)
    const key = '0b6f1f2a-6f0e-4c6b-9a77-1d1e1c1b1a19'
    const now = Date.now()
    const created = await request.post(`${API}/api/v1/experience/records`, {
      headers: { ...session.headers, 'Idempotency-Key': key },
      data: {
        experienceId: ROBOT,
        startedAt: new Date(now - 200_000).toISOString(),
        endedAt: new Date(now - 40_000).toISOString(),
        durationSeconds: 156,
        levelsCompleted: 3,
        levelsTotal: 3,
        mistakes: 1,
      },
    })
    expect(created.status()).toBe(201)

    await page.goto(`/#/pages/mall/product?productId=${KIT}&cityCode=${CITY}`)
    await expect(page.getByTestId('product-try-first-last')).toContainText('体验过“机器人避障模拟”，用时 2′36″')
    await page.getByTestId('mall-product-try-first').click()
    await expect(page.getByTestId('experience-pick')).toBeVisible()
    await expect(page.getByTestId('experience-pick')).toContainText('编程机器人探索套装 · 体验结束不会自动下单')
    await expect(page.getByTestId('experience-pick-SIMULATION')).toContainText('免费')
    await expect(page.getByTestId('experience-pick-VENUE')).toContainText('真机 · 工作人员陪同')
    await page.getByTestId('experience-pick-RECORDED_DEMO').click()
    await expect(page.locator('.uni-sample-toast')).toContainText('录播演示即将开放')
    await page.getByTestId('experience-pick-SIMULATION').click()
    await expect(page.getByTestId('simulation-page')).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`experienceId=${ROBOT}`))
  })

  test('SIM-004 软件试用：启动、试用中倒计时、提前结束；记录为已结束', async ({ page, request }) => {
    await page.goto(`/#/pages/experience/software-trial?experienceId=${SOFTWARE}`)
    await expect(page.getByTestId('trial-guest')).toContainText('登录后开始试用')

    await signIn(page, session)
    await page.goto(`/#/pages/experience/software-trial?experienceId=${SOFTWARE}`)
    await expect(page.getByTestId('trial-booting')).toBeVisible()
    await expect(page.getByTestId('trial-remaining')).toHaveText(/^剩余 2[45]:\d\d$/, { timeout: STEP_WAIT })
    await expect(page.getByTestId('trial-screen')).toContainText('软件画面 · 示例')

    // 再打开回到同一次试用，不另开。
    await page.reload()
    await expect(page.getByTestId('trial-remaining')).toBeVisible({ timeout: STEP_WAIT })
    expect((await records(request, session, SOFTWARE)).total).toBe(1)

    await page.getByTestId('trial-end').click()
    await expect(page.getByTestId('trial-end-sheet')).toContainText('结束这次试用？')
    await page.getByTestId('trial-end-confirm').click()
    await expect(page.getByTestId('trial-over-title')).toHaveText('试用已结束')
    const saved = await records(request, session, SOFTWARE)
    expect(saved.items[0].status).toBe('ENDED')
  })

  test('SIM-005 课程试听：开始即记，到期时间来自服务端', async ({ page, request }) => {
    await signIn(page, session)
    await page.goto(`/#/pages/experience/course-trial?experienceId=${COURSE}`)
    await expect(page.getByTestId('trial-course-running')).toContainText('试听中 · 剩余 7 天')
    await expect(page.getByTestId('trial-remaining')).toHaveText('剩余 7 天')
    const saved = await records(request, session, COURSE)
    expect(saved.items[0].status).toBe('ACTIVE')
  })

  test('SIM-006 我的 → 体验记录：线上体验列表和到馆预约页签', async ({ page, request }) => {
    await signIn(page, session)
    await request.post(`${API}/api/v1/experience/trials`, { headers: session.headers, data: { experienceId: SOFTWARE } })

    await page.goto('/#/pages/mine/index')
    await page.getByTestId('mine-link-experience_records').click()
    await expect(page.getByTestId('experience-records')).toBeVisible()
    await expect(page.getByTestId('experience-record-line').first()).toHaveText(/^试用中 · 剩余 2[45]:\d\d$/)
    await page.getByTestId('experience-records-tab-venue').click()
    await expect(page.getByTestId('experience-records-all-bookings')).toBeVisible()
    await page.getByTestId('experience-records-tab-online').click()
    await expect(page.locator('[data-testid^="experience-record-"]').filter({ hasText: '图形化编程软件试用' })).toHaveCount(1)
    await assertNoHorizontalOverflow(page)
  })

  test('SIM-007 体验频道的“我的体验记录 ›”', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await page.getByTestId('home-channel-experience').click()
    await page.getByTestId('experience-records-link').click()
    await expect(page.getByTestId('experience-records')).toBeVisible()
    await expect(page.getByTestId('experience-records-guest')).toBeVisible()
  })

  test('SIM-VIS 390 宽截图（仿真、结果、记录、试用）', async ({ page }) => {
    mkdirSync(compareOutput, { recursive: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await signIn(page, session)
    await page.goto(`/#/pages/experience/simulation?experienceId=${ROBOT}`)
    await build(page, ['forward', 'turn_left', 'forward', 'if_blocked'])
    await assertNoHorizontalOverflow(page)
    await page.screenshot({ path: `${compareOutput}/e2e-ExpSim-390.png` })
    await page.goto(`/#/pages/mine/experience-records?tab=ALL`)
    await expect(page.getByTestId('experience-records')).toBeVisible()
    await page.screenshot({ path: `${compareOutput}/e2e-ExpRecords-390.png` })
  })
})
