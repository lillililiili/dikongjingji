import { randomInt, randomUUID } from 'node:crypto'

import { expect, test, type Page } from '@playwright/test'

import { field, forgetSentCodes, latestCode, mask, openSignedIn, randomPhone, signUpByApi } from './support/account-fixtures'
import { API, CITY, apiLogin, databaseAvailable, offerId } from './support/trade-fixtures'

// S5-7 企业组织与成员、S5-8 手机号登录与账号（contracts/openapi-identity-account.yaml 1.0.0）。
// Runs against the independent test stack with this branch (dev profile: the SMS is the local
// fake, codes are read from identity_dev_sms_outbox), H5 dev server as PLAYWRIGHT_BASE_URL. Every
// case signs up its own 199 numbers, so dev-customer and the seeded enterprise are not touched.
test.skip(!databaseAvailable(), 'Needs the isolated test database to read the fake SMS codes.')

const KIT_STORE = offerId('101', '3')
// Hold the first real read until the UI has rendered, as on a slow connection.
async function holdRead(page: Page, endpoint: string): Promise<() => void> {
  let release!: () => void
  const wait = new Promise<void>((resolve) => { release = resolve })
  await page.route('**/api/v1/account/' + endpoint, async (route) => {
    if (route.request().method() === 'GET') await wait
    await route.continue()
  })
  return release
}

test.describe('手机号验证码登录（Login）', () => {
  test('ACC-001 新号码收验证码登录即开通账号，回到“我的”显示打码手机号', async ({ page }) => {
    const phone = randomPhone()
    await page.goto('/#/pages/service/inspection/login?returnUrl=%2Fpages%2Fmine%2Findex')
    await expect(page.getByTestId('login-phone-form')).toBeVisible()
    await field(page, 'login-phone').fill(phone)
    await page.waitForTimeout(150)
    await page.getByTestId('login-send-code').click()
    await expect(page.getByTestId('login-send-code')).toContainText('秒后重新获取')
    await field(page, 'login-code').fill(latestCode(phone))
    await page.waitForTimeout(150)
    // Board Login: 登录 stays grey until the agreement is ticked.
    await expect(page.getByTestId('login-phone-submit')).toHaveAttribute('disabled', /.*/)
    await page.getByTestId('login-agreement').click()
    await page.getByTestId('login-phone-submit').click()
    await page.getByTestId('login-setup-skip').click()

    await expect(page.getByTestId('mine-page')).toBeVisible()
    await expect(page.getByTestId('mine-phone-line')).toContainText(mask(phone))
    await expect(page.getByTestId('mine-switch-identity')).toHaveText('切换企业身份')
  })

  test('ACC-002 验证码不对时留在登录页并提示；账号密码登录仍可切换', async ({ page, request }) => {
    const phone = randomPhone()
    forgetSentCodes(phone)
    await request.post(`${API}/api/v1/auth/sms-codes`, { data: { phone } })
    const wrong = latestCode(phone) === '000000' ? '111111' : '000000'
    await page.goto('/#/pages/service/inspection/login')
    await expect(page.getByTestId('login-phone-form')).toBeVisible()
    await expect(page.getByText(/本机号码一键登录/)).toHaveCount(0)
    await field(page, 'login-phone').fill(phone)
    await field(page, 'login-code').fill(wrong)
    await page.waitForTimeout(150)
    await page.getByTestId('login-agreement').click()
    await page.getByTestId('login-phone-submit').click()
    await expect(page.locator('.inspection-login__error')).toContainText('验证码不正确或已失效')

    await page.getByTestId('login-switch-mode').click()
    await expect(page.locator('.inspection-login__form-title')).toHaveText('账号登录')
    await expect(page.getByText(/本机号码一键登录/)).toHaveCount(0)
    await page.getByTestId('login-switch-mode').click()
    await expect(page.getByTestId('login-phone-form')).toBeVisible()
    await expect(page.locator('.inspection-login__error')).toHaveCount(0)
  })
})

test.describe('设置：手机号、登录设备、注销、导出（Settings, SetPhone, SetDevices, SetData）', () => {
  test('ACC-003 登录设备列出本机在前，退出另一台设备后它的令牌立刻失效', async ({ page, request }) => {
    const phone = randomPhone()
    const other = await signUpByApi(request, phone, 'Android 手机')
    const me = await signUpByApi(request, phone, 'iPhone 15')
    await openSignedIn(page, me.token, '/pages/mine/settings')
    await expect(page.getByTestId('settings-phone-value')).toHaveText(mask(phone))
    await page.getByTestId('settings-devices').click()

    await expect(page.getByTestId('device-current')).toContainText('iPhone 15')
    await expect(page.getByTestId('device-current')).toContainText('本机')
    await page.getByTestId('device-sign-out').first().click()
    await page.getByTestId('devices-confirm-confirm').click()
    await expect(page.getByTestId('devices-done')).toContainText('已退出 Android 手机')
    await expect(page.getByTestId('device-other')).toHaveCount(0)
    expect((await request.get(`${API}/api/v1/auth/me`, { headers: other.headers })).status()).toBe(401)
  })

  test('ACC-004 更换手机号：验证当前号码、绑定新号码，其他设备退出', async ({ page, request }) => {
    const phone = randomPhone()
    const newPhone = randomPhone()
    const other = await signUpByApi(request, phone, 'iPad')
    const me = await signUpByApi(request, phone)
    await openSignedIn(page, me.token, '/pages/mine/phone')
    await expect(page.getByTestId('set-phone-page')).toContainText(`验证码发送到 ${mask(phone)}`)
    forgetSentCodes(phone)
    await page.getByTestId('set-phone-old-send').click()
    await expect(page.getByTestId('set-phone-old-send')).toContainText('秒后重新获取')
    await field(page, 'set-phone-old-code').fill(latestCode(phone))
    await page.waitForTimeout(150)
    await page.getByTestId('set-phone-next').click()

    await field(page, 'set-phone-new').fill(newPhone)
    await page.waitForTimeout(150)
    await page.getByTestId('set-phone-new-send').click()
    await expect(page.getByTestId('set-phone-new-send')).toContainText('秒后重新获取')
    await field(page, 'set-phone-new-code').fill(latestCode(newPhone))
    await page.waitForTimeout(150)
    await page.getByTestId('set-phone-next').click()

    await expect(page.getByTestId('set-phone-done')).toContainText(`新手机号 ${mask(newPhone)}`)
    await expect(page.getByTestId('set-phone-devices')).toContainText('其他 1 台设备已退出')
    expect((await request.get(`${API}/api/v1/auth/me`, { headers: other.headers })).status()).toBe(401)
  })

  test('ACC-005 注销：条件满足时申请，进入 15 天冷静期，可撤回；冷静期内不能下单', async ({ page, request }) => {
    const me = await signUpByApi(request, randomPhone())
    const release = await holdRead(page, 'closure')
    try {
      await openSignedIn(page, me.token, '/pages/mine/settings')
      await page.getByTestId('settings-closure').click()
    } finally {
      release()
    }
    await expect(page.getByTestId('closure-sheet')).toContainText('注销条件已全部满足')
    await page.getByTestId('closure-apply').click()
    await expect(page.getByTestId('closure-sheet')).toContainText('注销申请处理中')
    await expect(page.getByTestId('settings-closure-tag')).toHaveText('处理中')

    const order = await request.post(`${API}/api/v1/trade/orders`, {
      headers: { ...me.headers, 'Idempotency-Key': randomUUID() },
      data: { cityCode: CITY, offerId: KIT_STORE, quantity: 1, deliveryMode: 'PICKUP', expectedPayableMinor: 138000, agreementAccepted: true },
    })
    expect(order.status()).toBe(409)
    expect((await order.json()).code).toBe('TRADE_ACCOUNT_CLOSING')

    await page.getByTestId('closure-withdraw').click()
    await expect(page.getByTestId('settings-closure-tag')).toHaveCount(0)
  })

  test('ACC-006 个人信息导出：验证手机号后申请，生成后一次性下载', async ({ page, request }) => {
    test.setTimeout(90_000) // Includes a real scheduling cycle, UI polling and the file transfer.
    const phone = randomPhone()
    const me = await signUpByApi(request, phone)
    const release = await holdRead(page, 'data-exports')
    try {
      await openSignedIn(page, me.token, '/pages/mine/data')
      await expect(page.getByTestId('data-apply')).toBeDisabled()
    } finally {
      release()
    }
    await expect(page.getByTestId('data-phone')).toHaveText(mask(phone))
    await page.getByTestId('data-apply').click()
    await expect(page.getByTestId('data-sheet')).toContainText(`验证手机号 ${mask(phone)}`)
    forgetSentCodes(phone)
    await page.getByTestId('data-export-send').click()
    await expect(page.getByTestId('data-export-send')).toContainText('秒后重新获取')
    await field(page, 'data-export-code').fill(latestCode(phone))
    await page.waitForTimeout(150)
    await page.getByTestId('data-submit').click()
    await expect(page.getByTestId('data-record-making')).toBeVisible()
    // The server job runs every 15 seconds; the page reloads itself every 5.
    const downloadButton = page.getByTestId('data-download')
    await expect(downloadButton).toBeVisible({ timeout: 60_000 })
    const [download] = await Promise.all([page.waitForEvent('download'), downloadButton.click()])
    expect(download.suggestedFilename()).toMatch(/\.zip$/i)
    const stream = await download.createReadStream()
    const chunks: Buffer[] = []
    for await (const chunk of stream) chunks.push(Buffer.from(chunk))
    expect(Buffer.concat(chunks).subarray(0, 4)).toEqual(Buffer.from([0x50, 0x4b, 3, 4]))
    expect((await request.get(download.url())).status()).toBe(404)
  })

  test('ACC-007 权限管理与新手引导（SetPerm, Onboarding）', async ({ page, request }) => {
    const me = await signUpByApi(request, randomPhone())
    await openSignedIn(page, me.token, '/pages/mine/settings')
    await page.getByTestId('settings-permissions').click()
    await expect(page.getByTestId('permission-location')).toContainText('确定所在城市、按距离排序、到馆导航')
    await expect(page.getByTestId('permission-camera')).toBeVisible()
    await expect(page.getByTestId('permission-notify')).toBeVisible()
    await page.goBack()
    await page.getByTestId('settings-onboarding').click()
    await expect(page.getByTestId('onboarding-privacy')).toContainText('欢迎使用低空产业平台')
    await page.getByTestId('onboarding-decline').click()
    await expect(page.getByTestId('onboarding-decline-dialog')).toContainText('需要你的同意才能继续')
    await page.getByTestId('onboarding-reread').click()
    await page.getByTestId('onboarding-agree').click()
    await expect(page.getByTestId('onboarding-location')).toContainText('开启定位，看本城可用的服务')
  })
})

test.describe('企业组织（OrgDetail, OrgMembers, OrgInvite, Mine）', () => {
  test('ACC-008 创建企业、手机号邀请、被邀请人同意加入、切换企业身份下单记付款主体', async ({ page, browser, request }) => {
    const adminPhone = randomPhone()
    const memberPhone = randomPhone()
    const admin = await signUpByApi(request, adminPhone)
    const member = await signUpByApi(request, memberPhone)
    const creditCode = `9137${String(randomInt(0, 1e8)).padStart(8, '0')}TEAM${String(randomInt(0, 100)).padStart(2, '0')}`

    // The admin creates the enterprise from 我的 → 企业组织.
    await openSignedIn(page, admin.token, '/pages/mine/org')
    await field(page, 'org-create-name').fill('E2E 示例物流')
    await field(page, 'org-create-code').fill(creditCode)
    await page.waitForTimeout(150)
    await page.getByTestId('org-create-submit').click()
    await expect(page.getByTestId('org-name')).toHaveText('E2E 示例物流')
    await expect(page.getByTestId('org-my-role')).toHaveText('我是管理员')

    // Invite by phone from 成员与角色.
    await page.getByTestId('org-invite').click()
    await page.getByTestId('org-members-invite').click()
    await field(page, 'org-invite-phone').fill(memberPhone)
    await page.waitForTimeout(150)
    await page.getByTestId('org-invite-send').click()
    await expect(page.getByTestId('org-members-note')).toContainText(`已向 ${mask(memberPhone)} 发送邀请`)
    await expect(page.getByTestId('org-pending-invitation')).toContainText(mask(memberPhone))

    // The invited person sees it under 企业组织 and agrees.
    const memberContext = await browser.newContext({ viewport: { width: 390, height: 844 } })
    const memberPage = await memberContext.newPage()
    await openSignedIn(memberPage, member.token, '/pages/mine/org')
    await memberPage.getByTestId('org-received-invitation').click()
    await expect(memberPage.getByTestId('org-invite-page')).toContainText('E2E 示例物流')
    await memberPage.getByTestId('org-invite-accept').click()
    await expect(memberPage.getByTestId('org-name')).toHaveText('E2E 示例物流')
    await expect(memberPage.getByTestId('org-my-role')).toHaveText('我是成员')

    // The member switches to the enterprise on 我的 and orders as it.
    await memberPage.goto('/#/pages/mine/index')
    await memberPage.getByTestId('mine-switch-identity').click()
    await expect(memberPage.getByTestId('mine-enterprise-identity')).toContainText('E2E 示例物流')
    await expect(memberPage.getByTestId('mine-switch-identity')).toHaveText('切换个人身份')
    const token = await memberPage.evaluate(() => localStorage.getItem('inspection.accessToken'))
    const submitted = await request.post(`${API}/api/v1/trade/orders`, {
      headers: { Authorization: `Bearer ${String(token).replace(/^"|"$/g, '')}`, 'Idempotency-Key': randomUUID() },
      data: { cityCode: CITY, offerId: KIT_STORE, quantity: 1, deliveryMode: 'PICKUP', expectedPayableMinor: 138000, agreementAccepted: true },
    })
    expect(submitted.status()).toBe(201)
    const order = await submitted.json()
    expect(order.payer).toEqual({ type: 'ENTERPRISE', name: 'E2E 示例物流' })
    await memberPage.goto(`/#/pages/trade/cashier?orderId=${order.orderId}`)
    await expect(memberPage.getByTestId('cashier-payer')).toHaveText('E2E 示例物流（企业身份）')
    await memberContext.close()

    // The admin sees the order under 企业订单 with its operator.
    await page.goto('/#/pages/mine/org')
    await expect(page.getByTestId('org-orders')).toContainText('经办：用户')
    await expect(page.getByTestId('org-stats')).toContainText('待付款')
  })

  test('ACC-009 非管理员不能调整角色；管理员移除成员后对方回到个人身份', async ({ page, request }) => {
    const admin = await signUpByApi(request, randomPhone())
    const memberPhone = randomPhone()
    const member = await signUpByApi(request, memberPhone)
    const created = await (await request.post(`${API}/api/v1/account/enterprises`, {
      headers: { ...admin.headers, 'Idempotency-Key': randomUUID() },
      data: { name: 'E2E 移除测试', creditCode: `9137${String(randomInt(0, 1e8)).padStart(8, '0')}TEAM${String(randomInt(0, 100)).padStart(2, '0')}`, cityCode: CITY },
    })).json()
    const invitation = await (await request.post(`${API}/api/v1/account/enterprises/${created.enterpriseId}/invitations`, {
      headers: admin.headers, data: { kind: 'PHONE', phone: memberPhone },
    })).json()
    await request.post(`${API}/api/v1/account/enterprise-invitations/${invitation.invitation.invitationId}/acceptance`, { headers: member.headers })
    await request.put(`${API}/api/v1/account/identity`, { headers: member.headers, data: { enterpriseId: created.enterpriseId } })

    // The member sees the list read-only.
    await openSignedIn(page, member.token, `/pages/mine/org-members?enterpriseId=${created.enterpriseId}`)
    await expect(page.getByTestId('org-members-perm')).toContainText('调整角色和移除成员由管理员操作')
    await page.getByTestId('org-member').first().click()
    await expect(page.getByTestId('org-role-sheet')).toHaveCount(0)

    // The admin removes them.
    await openSignedIn(page, admin.token, `/pages/mine/org-members?enterpriseId=${created.enterpriseId}`)
    await page.getByTestId('org-member').first().click()
    await page.getByTestId('org-member-remove').click()
    await page.getByTestId('org-remove-confirm-confirm').click()
    await expect(page.getByTestId('org-members-note')).toContainText('已移除')
    const me = await (await request.get(`${API}/api/v1/auth/me`, { headers: member.headers })).json()
    expect(me.identity.type).toBe('PERSONAL')
  })

  test('ACC-010 dev-customer 仍能用账号密码登录，并是示例企业的管理员', async ({ request }) => {
    const session = await apiLogin(request, 'dev-customer')
    expect(session).not.toBeNull()
    const me = await (await request.get(`${API}/api/v1/auth/me`, { headers: session!.headers })).json()
    expect(me.maskedPhone).toBe('138****2026')
    const list = await (await request.get(`${API}/api/v1/account/enterprises`, { headers: session!.headers })).json()
    expect(list.items.map((item: { name: string }) => item.name)).toContain('东营港务物流（示例）')
  })
})
