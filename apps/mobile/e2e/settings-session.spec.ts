import { expect, test, type Page, type Route } from '@playwright/test'
import { mask, openSignedIn, randomPhone, signUpByApi } from './support/account-fixtures'
import { API } from './support/trade-fixtures'

const settings = '/pages/mine/settings'
const preferences = '/api/v1/notifications/preferences'
const closure = '/api/v1/account/closure'

function latch() {
  let open!: () => void
  const wait = new Promise<void>(resolve => { open = resolve })
  return { open, wait }
}

async function changeSession(page: Page, token: string) {
  await page.evaluate(value => localStorage.setItem('inspection.accessToken', value), token)
}

test('SET-001 返回缓存设置页马上移除旧资料，新账号不等待上一账号的注销条件与通知回执', async ({ page, request }) => {
  const firstPhone = randomPhone()
  const secondPhone = randomPhone()
  const first = await signUpByApi(request, firstPhone)
  const second = await signUpByApi(request, secondPhone)
  const held = latch()
  const secondProfile = latch()
  const firstClosure = latch()
  const firstWrite = latch()
  let delayFirstClosure = false
  const hold = async (route: Route, signal: ReturnType<typeof latch>, release: ReturnType<typeof latch>) => {
    const response = await route.fetch()
    signal.open()
    await release.wait
    await route.fulfill({ response })
  }
  const openClosure = await request.post(API + closure, { headers: first.headers })
  expect(openClosure.ok()).toBe(true)
  try {
    await page.route('**' + preferences, async route => {
      if (route.request().method() === 'PUT') return hold(route, firstWrite, held)
      await route.continue()
    })
    await page.route('**' + closure, async route => {
      if (delayFirstClosure && route.request().headers().authorization === first.headers.Authorization) {
        return hold(route, firstClosure, held)
      }
      await route.continue()
    })
    await page.route('**/api/v1/auth/me', async route => {
      if (route.request().headers().authorization === second.headers.Authorization) await secondProfile.wait
      await route.continue()
    })
    await openSignedIn(page, first.token, settings)
    await expect(page.getByTestId('settings-phone-value')).toHaveText(mask(firstPhone))
    await expect(page.getByTestId('settings-closure-tag')).toBeVisible()
    await page.getByTestId('settings-switch-activity').click()
    await firstWrite.wait
    await page.getByTestId('settings-about').click()
    delayFirstClosure = true
    await page.getByTestId('about-back').click()
    await firstClosure.wait
    await page.getByTestId('settings-about').click()
    await expect(page.getByTestId('about-back')).toBeVisible()
    await changeSession(page, second.token)
    await page.getByTestId('about-back').click()
    await expect(page.getByTestId('settings-phone-value')).not.toHaveText(mask(firstPhone))
    await expect(page.getByTestId('settings-closure-tag')).toHaveCount(0)
    await expect(page.getByTestId('settings-switch-activity')).toHaveAttribute('aria-checked', 'true')
    await page.getByTestId('settings-closure').click()
    await expect(page.getByTestId('closure-apply')).toBeVisible()
    await page.getByTestId('closure-dismiss').click()
    secondProfile.open()
    await expect(page.getByTestId('settings-phone-value')).toHaveText(mask(secondPhone))
    held.open()
    // A round trip on the same page also re-reads the saved settings of the second account.
    await page.getByTestId('settings-about').click()
    await page.getByTestId('about-back').click()
    await expect(page.getByTestId('settings-phone-value')).toHaveText(mask(secondPhone))
    await expect(page.getByTestId('settings-closure-tag')).toHaveCount(0)
    await expect(page.getByTestId('settings-switch-activity')).toHaveAttribute('aria-checked', 'true')
  } finally {
    secondProfile.open()
    held.open()
    expect((await request.delete(API + closure, { headers: first.headers })).ok()).toBe(true)
  }
})

test('SET-002 同一账号真实续期时，原请求仍可显示设置和注销条件', async ({ page, request }) => {
  const phone = randomPhone()
  const account = await signUpByApi(request, phone)
  const received = latch()
  const release = latch()
  await page.route('**/api/v1/auth/me', async route => {
    const profile = await route.fetch()
    received.open()
    await release.wait
    await route.fulfill({ response: profile })
  })
  try {
    await openSignedIn(page, account.token, settings)
    await received.wait
    const refreshed = await request.post(API + '/api/v1/auth/refresh', { data: { refreshToken: account.refreshToken } })
    expect(refreshed.ok()).toBe(true)
    const { accessToken } = await refreshed.json()
    expect(accessToken !== account.token).toBe(true)
    await changeSession(page, accessToken)
    release.open()
    await expect(page.getByTestId('settings-phone-value')).toHaveText(mask(phone))
    await page.getByTestId('settings-closure').click()
    await expect(page.getByTestId('closure-apply')).toBeVisible()
  } finally {
    release.open()
  }
})

test('SET-003 同一通知开关连点两次且两次保存均失败，回到服务端最后确认值', async ({ page, request }) => {
  const account = await signUpByApi(request, randomPhone())
  const reads = () => request.get(API + preferences, { headers: account.headers }).then(r => r.json())
  const original = (await reads()).activityPush as boolean
  const values: boolean[] = []
  const sent = latch()
  const release = latch()
  await page.route('**' + preferences, async route => {
    if (route.request().method() !== 'PUT') return route.continue()
    values.push(route.request().postDataJSON().activityPush)
    sent.open()
    await release.wait
    await route.fulfill({ status: 503, json: { code: 'COMMON_SERVICE_UNAVAILABLE', message: '验收服务暂时不可用' } })
  })
  try {
    await openSignedIn(page, account.token, settings)
    const toggle = page.getByTestId('settings-switch-activity')
    await expect(toggle).toHaveAttribute('aria-checked', String(original))
    await toggle.click()
    await sent.wait
    await expect(toggle).toHaveAttribute('aria-checked', String(!original))
    await toggle.click()
    release.open()
    await expect(page.locator('uni-toast')).toContainText('设置没有保存')
    expect(values).toEqual([!original, original])
    await expect(toggle).toHaveAttribute('aria-checked', String(original))
    expect((await reads()).activityPush).toBe(original)
  } finally {
    release.open()
  }
})

test('SET-004 上个账号的注销提交回执迟到时，新账号已打开的确认单仍显示自己的条件', async ({ page, request }) => {
  const first = await signUpByApi(request, randomPhone())
  const secondPhone = randomPhone()
  const second = await signUpByApi(request, secondPhone)
  const saved = latch()
  const release = latch()
  await page.route('**' + closure, async route => {
    if (route.request().method() !== 'POST') return route.continue()
    const response = await route.fetch()
    expect(response.ok()).toBe(true)
    saved.open()
    await release.wait
    await route.fulfill({ response })
  })
  try {
    await openSignedIn(page, first.token, settings)
    await page.getByTestId('settings-closure').click()
    await page.getByTestId('closure-apply').click()
    await saved.wait
    await page.getByTestId('closure-dismiss').click()
    await page.getByTestId('settings-about').click()
    await expect(page.getByTestId('about-back')).toBeVisible()
    await changeSession(page, second.token)
    await page.getByTestId('about-back').click()
    await expect(page.getByTestId('settings-phone-value')).toHaveText(mask(secondPhone))
    await page.getByTestId('settings-closure').click()
    const oldResponse = page.waitForResponse(r => r.url().endsWith(closure) && r.request().method() === 'POST')
    release.open()
    await (await oldResponse).finished()
    await expect(page.getByTestId('closure-apply')).toBeEnabled()
    await expect(page.getByTestId('closure-withdraw')).toHaveCount(0)
    await expect(page.getByTestId('settings-closure-tag')).toHaveCount(0)
    expect((await (await request.get(API + closure, { headers: second.headers })).json()).status).toBe('NONE')
  } finally {
    release.open()
    await request.delete(API + closure, { headers: first.headers })
  }
})

test('SET-005 同一账号提交注销后立即返回，申请前发出的读取不能恢复成未申请', async ({ page, request }) => {
  const phone = randomPhone()
  const account = await signUpByApi(request, phone)
  const held = latch()
  const release = latch()
  let delayNext = false
  await page.route('**' + closure, async route => {
    if (!delayNext || route.request().method() !== 'GET') return route.continue()
    delayNext = false
    const before = await route.fetch()
    held.open()
    await release.wait
    await route.fulfill({ response: before })
  })
  try {
    await openSignedIn(page, account.token, settings)
    await expect(page.getByTestId('settings-phone-value')).toHaveText(mask(phone))
    await page.getByTestId('settings-about').click()
    delayNext = true
    await page.getByTestId('about-back').click()
    await held.wait
    await page.getByTestId('settings-closure').click()
    await page.getByTestId('closure-apply').click()
    await expect(page.getByTestId('closure-withdraw')).toBeVisible()
    await page.getByTestId('closure-ok').click()
    await page.getByTestId('settings-about').click()
    const reread = page.waitForResponse(r => r.url().endsWith(closure) && r.request().method() === 'GET')
    await page.getByTestId('about-back').click()
    expect((await (await reread).json()).status).toBe('PENDING')
    const oldRead = page.waitForResponse(r => r.url().endsWith(closure) && r.request().method() === 'GET')
    release.open()
    await (await oldRead).finished()
    await expect(page.getByTestId('settings-closure-tag')).toHaveText('处理中')
  } finally {
    release.open()
    await request.delete(API + closure, { headers: account.headers })
  }
})
