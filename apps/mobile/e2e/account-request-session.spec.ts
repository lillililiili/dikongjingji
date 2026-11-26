import { expect, test } from '@playwright/test'

import { mask, openSignedIn, randomPhone, signUpByApi } from './support/account-fixtures'
import { API } from './support/trade-fixtures'

const preferences = '/api/v1/notifications/preferences'
const settings = '/pages/mine/settings'

test('AUTH-LOGOUT an old logout response preserves the next account and revokes only the old login', async ({ page, request }) => {
  const first = await signUpByApi(request, randomPhone())
  const phone = randomPhone()
  const second = await signUpByApi(request, phone)
  let release!: () => void
  const delayed = new Promise<void>(resolve => { release = resolve })
  const logout = '/api/v1/auth/logout'
  await page.route('**' + logout, async route => {
    await delayed
    await route.continue()
  })
  try {
    await openSignedIn(page, first.token, settings)
    await page.evaluate(value => sessionStorage.setItem('inspection.refreshToken', value), first.refreshToken)
    await page.getByTestId('settings-logout').click()
    const sent = page.waitForRequest(r => r.url().endsWith(logout) && r.method() === 'POST')
    await page.locator('.uni-modal__btn_primary').click()
    expect((await sent).postDataJSON().refreshToken === first.refreshToken).toBe(true)
    await expect(page.getByTestId('mine-login')).toBeVisible()
    expect(await page.evaluate(() => localStorage.getItem('inspection.accessToken'))).toBeNull()
    await page.evaluate(({ access, refresh }) => {
      localStorage.setItem('inspection.accessToken', access)
      sessionStorage.setItem('inspection.refreshToken', refresh)
    }, { access: second.token, refresh: second.refreshToken })
    const completed = page.waitForResponse(r => r.url().endsWith(logout) && r.request().method() === 'POST')
    release()
    expect((await completed).status()).toBe(204)
    const canRead = (session: typeof first) => request.get(API + '/api/v1/auth/me', { headers: session.headers })
    expect((await canRead(first)).status()).toBe(401)
    expect((await canRead(second)).status()).toBe(200)
    expect(await page.evaluate(() => localStorage.getItem('inspection.accessToken')) === second.token).toBe(true)
    await page.goto('/?check=logout-complete#' + settings)
    await expect(page.getByTestId('settings-phone-value')).toHaveText(mask(phone))
  } finally {
    release()
  }
})

for (const change of ['switch', 'renew'] as const) {
  test(`AUTH-WRITE-${change} delayed 401 during a settings save`, async ({ page, request }) => {
    const firstPhone = randomPhone()
    const first = await signUpByApi(request, firstPhone)
    const nextPhone = change === 'switch' ? randomPhone() : firstPhone
    const other = change === 'switch' ? await signUpByApi(request, nextPhone) : null
    const read = async (token: string) => {
      const result = await request.get(API + preferences, { headers: { Authorization: 'Bearer ' + token } })
      expect(result.ok()).toBe(true)
      return (await result.json()).activityPush as boolean
    }
    const initial = await read(first.token)
    const otherInitial = other ? await read(other.token) : initial
    let release!: () => void
    const delayed = new Promise<void>(resolve => { release = resolve })
    const owners: string[] = []
    await page.route('**' + preferences, async route => {
      if (route.request().method() !== 'PUT') return route.continue()
      owners.push(route.request().headers().authorization)
      if (owners.length > 1) return route.continue()
      await delayed
      await route.fulfill({ status: 401, json: { code: 'COMMON_UNAUTHORIZED', message: '请重新登录' } })
    })
    try {
      await openSignedIn(page, first.token, settings)
      await expect(page.getByTestId('settings-phone-value')).toHaveText(mask(firstPhone))
      await expect(page.getByTestId('settings-switch-activity')).toHaveAttribute('aria-checked', String(initial))
      await page.getByTestId('settings-switch-activity').click()
      await expect.poll(() => owners.length).toBe(1)
      let next = other?.token
      if (!next) {
        const rotated = await request.post(API + '/api/v1/auth/refresh', { data: { refreshToken: first.refreshToken } })
        expect(rotated.ok()).toBe(true)
        next = (await rotated.json()).accessToken as string
      }
      await page.evaluate(value => localStorage.setItem('inspection.accessToken', value), next)
      const response = page.waitForResponse(r => r.url().endsWith(preferences) && r.status() === 401)
      release()
      await response
      if (change === 'renew') await expect.poll(() => read(next!)).toBe(!initial)
      await page.getByTestId('settings-about').click()
      await page.getByTestId('about-back').click()
      await expect(page.getByTestId('settings-phone-value')).toHaveText(mask(nextPhone))
      await expect(page.getByTestId('settings-switch-activity')).toHaveAttribute('aria-checked', String(other ? otherInitial : !initial))
      expect(await read(next)).toBe(other ? otherInitial : !initial)
      expect(owners).toEqual(other ? [first.headers.Authorization] : [first.headers.Authorization, 'Bearer ' + next])
    } finally {
      release()
    }
  })
}
