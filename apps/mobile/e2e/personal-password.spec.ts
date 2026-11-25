import { randomInt, randomUUID } from 'node:crypto'

import { expect, test, type APIRequestContext } from '@playwright/test'

import { field, forgetSentCodes, latestCode, mask, openSignedIn, randomPhone, signUpByApi } from './support/account-fixtures'
import {
  API, CITY, MERCHANT, addAddressByApi, apiLogin, databaseAvailable, offerId, psql,
  readOrderByApi, submitOrderByApi,
} from './support/trade-fixtures'

test.skip(!databaseAvailable(), '需要独立测试数据库和开发短信发件箱。')
test.use({ trace: 'off', screenshot: 'off' })

const KIT_STORE = offerId('101', '3')

test.describe('个人账号两种登录方式', () => {
  test('PWD-001 首次注册可设置密码，退出后用手机号和密码读取同一账号的订单与地址', async ({ page, request }) => {
    const phone = randomPhone()
    const password = 'T3-' + randomUUID()
    const loginRoute = '/#/pages/service/inspection/login?returnUrl=%2Fpages%2Fmine%2Faddresses'
    await page.goto(loginRoute)
    await page.getByTestId('login-switch-mode').click()
    await page.getByTestId('login-register').click()
    await field(page, 'login-phone').fill(phone)
    await page.getByTestId('login-send-code').click()
    await expect(page.getByTestId('login-send-code')).toContainText('秒后重新获取')
    await field(page, 'login-code').fill(latestCode(phone))
    await page.getByTestId('login-agreement').click()
    const signup = page.waitForResponse(response => response.url().endsWith('/api/v1/auth/phone-login'))
    await page.getByTestId('login-phone-submit').click()
    const { accessToken, refreshToken, registered } = await (await signup).json()
    expect(registered).toBe(true)
    const claims = (token: string) => JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
    expect(claims(accessToken).roles).toContain('CUSTOMER')
    const session = { token: accessToken, headers: { Authorization: 'Bearer ' + accessToken } }
    const addressId = await addAddressByApi(request, session)
    const order = await submitOrderByApi(request, session, { offer: KIT_STORE, mode: 'PICKUP', payableMinor: 138000 })

    await page.getByTestId('login-setup-start').click()
    await expect(page.getByTestId('password-send')).toHaveCount(0)
    await field(page, 'password-new').fill(password)
    await field(page, 'password-confirm').fill(password)
    await page.getByTestId('password-save').click()
    await expect(page).toHaveURL(/pages\/mine\/addresses/)
    expect((await request.post(API + '/api/v1/auth/logout', { data: { refreshToken } })).status()).toBe(204)

    await page.goto(loginRoute)
    await page.getByTestId('login-switch-mode').click()
    await field(page, 'login-password-name').fill(phone)
    await field(page, 'login-password-value').fill(password)
    await page.getByTestId('login-agreement').click()
    const signed = page.waitForResponse(response => response.url().endsWith('/api/v1/auth/phone-password-login'))
    await page.getByTestId('login-password-submit').click()
    const { accessToken: nextToken } = await (await signed).json()
    await expect(page).toHaveURL(/pages\/mine\/addresses/)
    const nextSession = { token: nextToken, headers: { Authorization: 'Bearer ' + nextToken } }
    expect(claims(nextToken).sub).toBe(claims(accessToken).sub)
    expect(await readOrderByApi(request, nextSession, order.orderId)).toEqual(order)
    const addresses = await request.get(API + '/api/v1/account/addresses', { headers: nextSession.headers })
    expect((await addresses.json()).items.map((item: { addressId: string }) => item.addressId)).toContain(addressId)
  })

  test('PWD-002 在设置中补设密码，首次设置重复提交不会改写密码', async ({ page, request }) => {
    const phone = randomPhone()
    const me = await signUpByApi(request, phone)
    const password = 'T3-' + randomUUID()
    await openSignedIn(page, me.token, '/pages/mine/settings')
    await page.getByTestId('settings-password').click()
    await field(page, 'password-new').fill(password)
    await field(page, 'password-confirm').fill(password)
    const setup = page.waitForResponse(response => response.url().endsWith('/api/v1/account/password/setup'))
    await page.getByTestId('password-save').click()
    expect((await setup).status()).toBe(204)
    const status = await request.get(API + '/api/v1/account/password', { headers: me.headers })
    expect(await status.json()).toMatchObject({ eligible: true, hasPassword: true, canSetWithoutCode: false })
    const replay = await request.post(API + '/api/v1/account/password/setup', {
      headers: me.headers, data: { newPassword: 'Other1-' + randomUUID() },
    })
    expect([403, 409]).toContain(replay.status())
    expect((await request.post(API + '/api/v1/auth/phone-password-login', { data: { phone, password } })).status()).toBe(200)
  })

  test('PWD-003 机构工作台继续使用原登录名，个人自助设密不可用', async ({ request }) => {
    const merchant = await apiLogin(request, MERCHANT)
    expect(merchant).not.toBeNull()
    const headers = merchant!.headers
    const status = await request.get(API + '/api/v1/account/password', { headers })
    expect(await status.json()).toMatchObject({ eligible: false, canSetWithoutCode: false })
    const setup = await request.post(API + '/api/v1/account/password/setup', {
      headers, data: { newPassword: 'T3-' + randomUUID() },
    })
    expect(setup.status()).toBe(403)
    expect((await request.get(API + '/api/v1/auth/me', { headers })).ok()).toBe(true)
  })

  test('PWD-004 同次注册重复提交只建一个用户和个人机构，并发首设只接受一份密码', async ({ request }) => {
    const phone = randomPhone()
    const countOrganizations = () => Number(psql(`SELECT count(*) FROM identity_organization
      WHERE organization_name='个人客户 ${mask(phone)}';`))
    const before = countOrganizations()
    expect((await request.post(API + '/api/v1/auth/sms-codes', { data: { phone } })).status()).toBe(202)
    const data = { phone, code: latestCode(phone) }
    const signups = await Promise.all([1, 2].map(() => request.post(API + '/api/v1/auth/phone-login', { data })))
    expect(signups.map(response => response.status()).sort()).toEqual([200, 400])
    const { accessToken, registered } = await signups.find(response => response.status() === 200)!.json()
    expect(registered).toBe(true)
    expect(psql(`SELECT count(*) FROM identity_user WHERE mobile='${phone}';`)).toBe('1')
    expect(countOrganizations()).toBe(before + 1)
    const headers = { Authorization: 'Bearer ' + accessToken }
    const setup = await Promise.all([1, 2].map(() => request.post(API + '/api/v1/account/password/setup', {
      headers, data: { newPassword: 'T3-' + randomUUID() },
    })))
    expect(setup.map(response => response.status()).sort()).toEqual([204, 409])
  })

  test('PWD-005 过期的短信登录须重新验号，首次设密短信限发送会话使用', async ({ request }) => {
    const phone = randomPhone()
    const sender = await signUpByApi(request, phone)
    const other = await signUpByApi(request, phone, '另一测试设备')
    psql(`UPDATE identity_login_session SET password_setup_until = now() - interval '1 minute'
      WHERE user_id = (SELECT id FROM identity_user WHERE mobile = '${phone}');`)
    const data = { newPassword: 'T3-' + randomUUID() }
    const setupUrl = API + '/api/v1/account/password/setup'
    const attempt = (headers: { Authorization: string }, code?: string) =>
      request.post(setupUrl, { headers, data: { ...data, code } })
    expect((await attempt(sender.headers)).status()).toBe(409)
    expect((await request.post(setupUrl + '-codes', { headers: sender.headers, data: {} })).status()).toBe(202)
    const code = latestCode(phone)
    expect((await attempt(other.headers, code)).status()).toBe(400)
    expect((await attempt(sender.headers, code === '000000' ? '000001' : '000000')).status()).toBe(400)
    expect((await attempt(sender.headers, code)).status()).toBe(204)
    const wrong = await request.post(API + '/api/v1/auth/phone-password-login', {
      data: { phone, password: 'Wrong1-' + randomUUID() },
    })
    expect(wrong.status()).toBe(401)
    const missing = await request.post(API + '/api/v1/auth/phone-password-login', {
      data: { phone: randomPhone(), password: data.newPassword },
    })
    expect(missing.status()).toBe(wrong.status())
    expect((await missing.json()).code).toBe((await wrong.json()).code)
  })

  test('PWD-006 服务端校验密码规则，达到错误次数上限后暂缓密码登录', async ({ request }) => {
    const phone = randomPhone()
    const me = await signUpByApi(request, phone)
    for (const invalid of ['A1short', 'withoutdigits', 'Has Space1', 'A1' + '界'.repeat(24)]) {
      const result = await request.post(API + '/api/v1/account/password/setup', {
        headers: me.headers, data: { newPassword: invalid },
      })
      expect(result.status()).toBe(400)
    }
    const password = 'A1' + '界'.repeat(23)
    expect((await request.post(API + '/api/v1/account/password/setup', {
      headers: me.headers, data: { newPassword: password },
    })).status()).toBe(204)
    for (let previous = 0; previous < 10; previous++) {
      const denied = await request.post(API + '/api/v1/auth/phone-password-login', {
        data: { phone, password: 'Incorrect1' },
      })
      expect(denied.status()).toBe(401)
    }
    const limited = await request.post(API + '/api/v1/auth/phone-password-login', { data: { phone, password } })
    expect(limited.status()).toBe(429)
  })
})

test.describe('个人密码修改、找回和换号', () => {
  async function personalAccount(request: APIRequestContext) {
    const phone = randomPhone()
    const me = await signUpByApi(request, phone)
    const password = 'T3-' + randomUUID()
    const result = await request.post(API + '/api/v1/account/password/setup', {
      headers: me.headers, data: { newPassword: password },
    })
    expect(result.status()).toBe(204)
    return { ...me, phone, password }
  }

  function passwordLogin(request: APIRequestContext, phone: string, password: string) {
    return request.post(API + '/api/v1/auth/phone-password-login', { data: { phone, password } })
  }

  async function expectRevoked(request: APIRequestContext, accessToken: string, refreshToken: string) {
    expect((await request.get(API + '/api/v1/auth/me', {
      headers: { Authorization: 'Bearer ' + accessToken },
    })).status()).toBe(401)
    expect((await request.post(API + '/api/v1/auth/refresh', { data: { refreshToken } })).status()).toBe(401)
  }

  test('PWD-007 设置中核对原密码后修改，原密码和两台设备的会话均失效', async ({ page, request }) => {
    const me = await personalAccount(request)
    const other = await (await passwordLogin(request, me.phone, me.password)).json()
    const next = 'N3-' + randomUUID()
    await openSignedIn(page, me.token, '/pages/mine/settings')
    await page.getByTestId('settings-password').click()
    await field(page, 'password-current').fill(me.password)
    await field(page, 'password-new').fill(next)
    await field(page, 'password-confirm').fill(next)
    const changed = page.waitForResponse(response => response.url().endsWith('/api/v1/account/password/change'))
    await page.getByTestId('password-save').click()
    expect((await changed).status()).toBe(204)
    await expect(page).toHaveURL(/service\/inspection\/login/)
    await expectRevoked(request, me.token, me.refreshToken)
    await expectRevoked(request, other.accessToken, other.refreshToken)
    expect((await passwordLogin(request, me.phone, me.password)).status()).toBe(401)
    expect((await passwordLogin(request, me.phone, next)).status()).toBe(200)
  })

  test('PWD-008 手机号找回后可用新密码登录，旧密码、已用短信和原设备令牌失效', async ({ page, request }) => {
    const me = await personalAccount(request)
    const next = 'R3-' + randomUUID()
    await page.goto('/#/pages/service/inspection/login')
    await page.getByTestId('login-switch-mode').click()
    await page.getByTestId('login-forgot').click()
    await field(page, 'password-reset-phone').fill(me.phone)
    await page.getByTestId('password-send').click()
    await expect(page.getByTestId('password-send')).toContainText('秒后重新获取')
    const code = latestCode(me.phone)
    await field(page, 'password-code').fill(code)
    await field(page, 'password-new').fill(next)
    await field(page, 'password-confirm').fill(next)
    const reset = page.waitForResponse(response => response.url().endsWith('/api/v1/auth/password-reset'))
    await page.getByTestId('password-save').click()
    expect((await reset).status()).toBe(204)
    await expect(page.getByTestId('login-password-name')).toBeVisible()
    expect((await request.post(API + '/api/v1/auth/password-reset', {
      data: { phone: me.phone, code, newPassword: 'Again3-' + randomUUID() },
    })).status()).toBe(400)
    await expectRevoked(request, me.token, me.refreshToken)
    expect((await passwordLogin(request, me.phone, me.password)).status()).toBe(401)
    expect((await passwordLogin(request, me.phone, next)).status()).toBe(200)
  })

  test('PWD-009 找回不开户，未知号码、未设置密码和已禁用账号无法重设', async ({ request }) => {
    const unknown = randomPhone()
    const disabled = await personalAccount(request)
    psql(`UPDATE identity_user SET status='DISABLED' WHERE mobile='${disabled.phone}';`)
    const unset = randomPhone()
    await signUpByApi(request, unset)
    for (const phone of [unknown, disabled.phone, unset]) {
      const sent = await request.post(API + '/api/v1/auth/password-reset-codes', { data: { phone } })
      expect(sent.status()).toBe(202)
      expect((await sent.json()).maskedPhone).toBe(mask(phone))
      const result = await request.post(API + '/api/v1/auth/password-reset', {
        data: { phone, code: latestCode(phone), newPassword: 'R3-' + randomUUID() },
      })
      expect(result.status()).toBe(400)
      expect((await result.json()).code).toContain('SMS_CODE_INVALID')
    }
    expect(psql(`SELECT count(*) FROM identity_user WHERE mobile='${unknown}';`)).toBe('0')
  })

  test('PWD-010 换号后仅新手机号能用原密码登录，旧号的找回验证码失效', async ({ request }) => {
    const me = await personalAccount(request)
    const phone = randomPhone()
    const addressId = await addAddressByApi(request, me)
    const order = await submitOrderByApi(request, me, { offer: KIT_STORE, mode: 'PICKUP', payableMinor: 138000 })
    expect((await request.post(API + '/api/v1/auth/password-reset-codes', { data: { phone: me.phone } })).status()).toBe(202)
    const oldCode = latestCode(me.phone)
    const send = (data: object) => request.post(API + '/api/v1/account/sms-codes', { headers: me.headers, data })
    async function rebind(from: string, to: string) {
      expect((await send({ purpose: 'VERIFY_CURRENT' })).status()).toBe(202)
      const verified = await request.post(API + '/api/v1/account/phone/verifications', {
        headers: me.headers, data: { code: latestCode(from) },
      })
      expect(verified.status()).toBe(200)
      const { changeTicket } = await verified.json()
      expect((await send({ purpose: 'BIND_NEW', phone: to })).status()).toBe(202)
      expect((await request.put(API + '/api/v1/account/phone', {
        headers: me.headers, data: { newPhone: to, code: latestCode(to), changeTicket },
      })).status()).toBe(200)
    }
    await rebind(me.phone, phone)
    const attemptOldReset = () => request.post(API + '/api/v1/auth/password-reset', {
      data: { phone: me.phone, code: oldCode, newPassword: 'R3-' + randomUUID() },
    })
    expect((await attemptOldReset()).status()).toBe(400)
    expect((await passwordLogin(request, me.phone, me.password)).status()).toBe(401)
    const signed = await passwordLogin(request, phone, me.password)
    expect(signed.status()).toBe(200)
    const { accessToken } = await signed.json()
    const signedIn = { token: accessToken, headers: { Authorization: 'Bearer ' + accessToken } }
    const addresses = await request.get(API + '/api/v1/account/addresses', { headers: signedIn.headers })
    expect((await addresses.json()).items.map((entry: { addressId: string }) => entry.addressId)).toContain(addressId)
    expect(await readOrderByApi(request, signedIn, order.orderId)).toEqual(order)
    await rebind(phone, me.phone)
    expect((await attemptOldReset()).status()).toBe(400)
    expect((await passwordLogin(request, me.phone, me.password)).status()).toBe(200)
  })

  test('PWD-011 错用途、过期和重放的找回码均失败，原密码错误时不能改密', async ({ request }) => {
    const me = await personalAccount(request)
    const next = 'R3-' + randomUUID()
    const reset = (code: string) => request.post(API + '/api/v1/auth/password-reset', {
      data: { phone: me.phone, code, newPassword: next },
    })
    const change = (currentPassword: string, newPassword: string) => request.post(API + '/api/v1/account/password/change', {
      headers: me.headers, data: { currentPassword, newPassword },
    })
    expect((await change('Incorrect3-' + randomUUID(), next)).status()).toBe(400)
    expect((await change(me.password, me.password)).status()).toBe(400)
    forgetSentCodes(me.phone)
    expect((await request.post(API + '/api/v1/auth/sms-codes', { data: { phone: me.phone } })).status()).toBe(202)
    expect((await reset(latestCode(me.phone))).status()).toBe(400)
    const send = () => request.post(API + '/api/v1/auth/password-reset-codes', { data: { phone: me.phone } })
    expect((await send()).status()).toBe(202)
    const expired = latestCode(me.phone)
    psql(`UPDATE identity_sms_code SET sent_at=now()-interval '10 minute', expires_at=now()-interval '5 minute'
      WHERE phone='${me.phone}' AND purpose='PASSWORD_RESET';`)
    expect((await reset(expired)).status()).toBe(400)
    expect((await send()).status()).toBe(202)
    const code = latestCode(me.phone)
    const results = await Promise.all([reset(code), reset(code)])
    expect(results.map(r => r.status()).sort()).toEqual([204, 400])
  })

  test('PWD-012 已验证联系手机号的商家仍由工作台管理密码，个人找回无法改写商家凭据', async ({ request }) => {
    const phone = randomPhone()
    const loginName = 'merchant-' + randomUUID().slice(0, 8)
    const password = 'M3-' + randomUUID()
    expect((await request.post(API + '/api/v1/organization-signups/sms-codes', { data: { phone } })).status()).toBe(202)
    const signup = await request.post(API + '/api/v1/organization-signups', {
      headers: { 'Idempotency-Key': randomUUID() }, data: {
        organizationName: loginName, subjectName: loginName, cityCode: CITY,
        licenseNumber: '91370500' + String(randomInt(1e9)).padStart(10, '0'),
        contactName: '验收联系人', phone, smsCode: latestCode(phone), loginName, password,
      },
    })
    expect(signup.status()).toBe(201)
    expect((await request.post(API + '/api/v1/auth/password-reset-codes', { data: { phone } })).status()).toBe(202)
    const result = await request.post(API + '/api/v1/auth/password-reset', {
      data: { phone, code: latestCode(phone), newPassword: 'WrongOwner3-' + randomUUID() },
    })
    expect(result.status()).toBe(400)
    expect((await result.json()).code).toContain('SMS_CODE_INVALID')
    expect((await passwordLogin(request, phone, password)).status()).toBe(401)
    expect((await request.post(API + '/api/v1/auth/login', { data: { loginName, password } })).status()).toBe(200)
  })
})
