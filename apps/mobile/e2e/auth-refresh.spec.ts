import { expect, test, type Page, type Route } from '@playwright/test'

import { API, devPassword } from './support/trade-fixtures'

// 登录续期（2026-11-22）：访问令牌 900 秒；过期后公共请求层用刷新令牌静默续期一次再重试，续期失败才回登录页。
// AR-001–AR-003 用桩数据；AR-004–AR-005 对着本地后端（需要带 V008 和 /api/v1/auth/refresh 的后端）。

const PROFILE = {
  displayName: '王女士',
  organizationName: '东营港务物流',
  organizationType: 'CUSTOMER',
  roles: ['CUSTOMER'],
}

/** An access token that ran out a minute ago, and the refresh token of the same login (H5: sessionStorage). */
async function expiredLogin(page: Page, refreshToken: string | null = 'refresh-1'): Promise<void> {
  await page.addInitScript((refresh) => {
    if (sessionStorage.getItem('auth-refresh-spec.seeded')) return
    sessionStorage.setItem('auth-refresh-spec.seeded', '1')
    localStorage.setItem('inspection.accessToken', 'access-1')
    localStorage.setItem('inspection.accessTokenExpiresAt', String(Date.now() - 60_000))
    if (refresh) sessionStorage.setItem('inspection.refreshToken', refresh)
  }, refreshToken)
}

/**
 * Every API call made with the old token gets a 401, with the renewed one a 503 (this spec only
 * looks at the account name); /auth/me answers with the profile once the token is the renewed one.
 */
async function stubApi(page: Page, refresh: (route: Route) => Promise<void>): Promise<void> {
  await page.route('**/api/v1/**', (route) => {
    const authorization = route.request().headers().authorization
    if (!authorization) return route.fallback()
    return authorization === 'Bearer access-2'
      ? route.fulfill({ status: 503, json: { code: 'COMMON_UNAVAILABLE', message: '桩', traceId: 't' } })
      : route.fulfill({ status: 401, json: { code: 'COMMON_UNAUTHORIZED', message: '请先登录', traceId: 't' } })
  })
  await page.route('**/api/v1/auth/me', (route) =>
    route.request().headers().authorization === 'Bearer access-2'
      ? route.fulfill({ json: PROFILE })
      : route.fulfill({ status: 401, json: { code: 'COMMON_UNAUTHORIZED', message: '请先登录', traceId: 't' } }))
  await page.route('**/api/v1/auth/refresh', refresh)
}

test.describe('登录续期', () => {
  test('AR-001 访问令牌过期后静默续期：只续一次，不带旧令牌，“我的”照常显示账号', async ({ page }) => {
    await expiredLogin(page)
    const refreshCalls: Array<{ body: unknown; authorization: string | undefined }> = []
    await stubApi(page, async (route) => {
      refreshCalls.push({ body: route.request().postDataJSON(), authorization: route.request().headers().authorization })
      await route.fulfill({
        json: {
          accessToken: 'access-2',
          tokenType: 'Bearer',
          expiresInSeconds: 900,
          refreshToken: 'refresh-2',
          refreshExpiresInSeconds: 2_592_000,
        },
      })
    })

    await page.goto('/#/pages/mine/index')

    await expect(page.locator('.platform-mine__name')).toHaveText('王女士')
    await expect(page.getByTestId('mine-login')).toHaveCount(0)
    // 页面同时发出的几个请求都 401，只用刷新令牌续了一次。
    expect(refreshCalls).toEqual([{ body: { refreshToken: 'refresh-1' }, authorization: undefined }])
    const stored = await page.evaluate(() => ({
      session: sessionStorage.getItem('inspection.refreshToken'),
      local: Object.keys(localStorage).filter((key) => (localStorage.getItem(key) ?? '').includes('refresh-')),
      access: localStorage.getItem('inspection.accessToken') ?? '',
    }))
    expect(stored.session).toBe('refresh-2')
    expect(stored.local).toEqual([])
    expect(stored.access).toContain('access-2')
  })

  test('AR-002 刷新令牌被拒（过期、撤销或重放）：清掉登录，“我的”回到未登录', async ({ page }) => {
    await expiredLogin(page)
    let refreshCount = 0
    await stubApi(page, async (route) => {
      refreshCount += 1
      await route.fulfill({
        status: 401,
        json: { code: 'COMMON_UNAUTHORIZED', message: '登录已过期，请重新登录', traceId: 't' },
      })
    })

    await page.goto('/#/pages/mine/index')

    // 续期失败才回登录页（见文件头）：“我的”的订单请求被拒，转到登录页，登录后回“我的”。
    await expect(page).toHaveURL(/inspection\/login\?returnUrl=[^&]*pages[^&]*mine[^&]*index/)
    expect(refreshCount).toBe(1)
    const left = await page.evaluate(() => ({
      session: sessionStorage.getItem('inspection.refreshToken'),
      access: localStorage.getItem('inspection.accessToken'),
    }))
    expect(left).toEqual({ session: null, access: null })

    // 不登录直接回“我的”：已是未登录，也不再续期。
    await page.goto('/#/pages/mine/index')
    await expect(page.getByTestId('mine-login')).toBeVisible()
    expect(refreshCount).toBe(1)
  })

  test('AR-003 没有刷新令牌的旧登录过期后直接按未登录处理，不发续期请求', async ({ page }) => {
    await expiredLogin(page, null)
    let refreshCount = 0
    await stubApi(page, async (route) => {
      refreshCount += 1
      await route.fulfill({ status: 401, json: { code: 'COMMON_UNAUTHORIZED', message: 'x', traceId: 't' } })
    })

    await page.goto('/#/pages/mine/index')

    await expect(page.getByTestId('mine-login')).toBeVisible()
    expect(refreshCount).toBe(0)
  })

  test('AR-004 真实后端：续期轮换、旧令牌重放撤销整次登录、退出登录撤销', async ({ request }) => {
    const login = await request.post(`${API}/api/v1/auth/login`, {
      data: { loginName: 'dev-customer', password: devPassword() },
    })
    test.skip(!login.ok(), '本地后端没有开发账号（不是 dev 配置）')
    const first = await login.json()
    test.skip(typeof first.refreshToken !== 'string', '本地后端还没有登录续期（V008 之前的版本）')
    expect(first.refreshExpiresInSeconds).toBeGreaterThan(0)

    const renewed = await request.post(`${API}/api/v1/auth/refresh`, { data: { refreshToken: first.refreshToken } })
    expect(renewed.status()).toBe(200)
    const second = await renewed.json()
    expect(second.refreshToken).not.toBe(first.refreshToken)
    const me = await request.get(`${API}/api/v1/auth/me`, { headers: { Authorization: `Bearer ${second.accessToken}` } })
    expect(me.status()).toBe(200)

    // 旧令牌再用一次：401，并且这次登录整串撤销，第二个刷新令牌也不能用了。
    const replay = await request.post(`${API}/api/v1/auth/refresh`, { data: { refreshToken: first.refreshToken } })
    expect(replay.status()).toBe(401)
    const afterReplay = await request.post(`${API}/api/v1/auth/refresh`, { data: { refreshToken: second.refreshToken } })
    expect(afterReplay.status()).toBe(401)

    // 另一次登录：退出后刷新令牌失效。
    const third = await (await request.post(`${API}/api/v1/auth/login`, {
      data: { loginName: 'dev-customer', password: devPassword() },
    })).json()
    const logout = await request.post(`${API}/api/v1/auth/logout`, { data: { refreshToken: third.refreshToken } })
    expect(logout.status()).toBe(204)
    const afterLogout = await request.post(`${API}/api/v1/auth/refresh`, { data: { refreshToken: third.refreshToken } })
    expect(afterLogout.status()).toBe(401)
  })

  test('AR-005 真实后端：页面登录后令牌过期，下一次请求静默续期，不回登录页', async ({ page, request }) => {
    const probe = await request.post(`${API}/api/v1/auth/login`, {
      data: { loginName: 'dev-customer', password: devPassword() },
    })
    test.skip(!probe.ok(), '本地后端没有开发账号（不是 dev 配置）')
    test.skip(typeof (await probe.json()).refreshToken !== 'string', '本地后端还没有登录续期（V008 之前的版本）')

    await page.goto('/#/pages/service/inspection/login?returnUrl=%2Fpages%2Fmine%2Findex')
    // 用户名输入框不自动大写、不自动更正（iOS 走查问题）。
    const nameField = page.locator('.inspection-login__input input').nth(0)
    await expect(nameField).toHaveAttribute('autocapitalize', 'off')
    await expect(nameField).toHaveAttribute('autocorrect', 'off')
    await expect(nameField).toHaveAttribute('spellcheck', 'false')
    await nameField.fill('dev-customer')
    await page.locator('.inspection-login__input input').nth(1).fill(devPassword())
    await page.getByTestId('login-agreement').click()
    await page.locator('.inspection-login__submit').click()
    await expect(page.getByTestId('mine-login')).toHaveCount(0)
    await expect(page.locator('.platform-mine__name')).not.toBeEmpty()

    // 模拟 15 分钟后：访问令牌作废（换成签名不对的串）、本地过期时间已过。
    await page.evaluate(() => {
      localStorage.setItem('inspection.accessToken', 'expired-access-token')
      localStorage.setItem('inspection.accessTokenExpiresAt', String(Date.now() - 1000))
    })
    const refreshes: number[] = []
    page.on('response', (response) => {
      if (response.url().endsWith('/api/v1/auth/refresh')) refreshes.push(response.status())
    })
    await page.reload()

    // 续期前卡片先显示“已登录”，所以等续期回来、读到账号名字后再看。
    await expect.poll(() => refreshes).toEqual([200])
    await expect(page.locator('.platform-mine__name')).not.toHaveText(/^(已登录)?$/)
    await expect(page.getByTestId('mine-login')).toHaveCount(0)
    expect(refreshes).toEqual([200])
    expect(page.url()).not.toContain('inspection/login')
  })
})
