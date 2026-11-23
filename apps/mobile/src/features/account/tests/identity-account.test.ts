import { describe, expect, it } from 'vitest'

import { platformOf, deviceNameOf } from '../application/login-device'
import { shouldShowOnboarding } from '../application/onboarding-consent'
import { toState } from '../application/app-permissions'
import { createIdentityAccountApi } from '../data/identity-account-api'
import {
  ROLE_LABELS,
  avatarChar,
  codeButtonText,
  digitsOnly,
  enterpriseOrderTag,
  formatBytes,
  identityLine,
  invitationLinkPath,
  isCreditCode,
  isPhoneNumber,
  isSmsCode,
  maskPhone,
  sessionMeta,
} from '../domain/identity-account'
import {
  buildOnboardingUrl,
  buildOrgInviteUrl,
  buildOrgMembersUrl,
  buildOrgUrl,
} from '../navigation/account-routes'

describe('account rules (S5-7, S5-8)', () => {
  it('checks phone numbers, codes and credit codes as the server does', () => {
    expect(isPhoneNumber('13800002026')).toBe(true)
    expect(isPhoneNumber('12800002026')).toBe(false)
    expect(isPhoneNumber('1380000202')).toBe(false)
    expect(isSmsCode('012345')).toBe(true)
    expect(isSmsCode('12345a')).toBe(false)
    expect(isCreditCode('91370500EXAMPLE26X')).toBe(true)
    expect(isCreditCode('91370500example26x')).toBe(true)
    // I, O, Z, S, V are not used in 统一社会信用代码.
    expect(isCreditCode('91370500EXAMPLE26I')).toBe(false)
    expect(isCreditCode('91370500EXAMPLE26')).toBe(false)
  })

  it('keeps inputs to digits and masks numbers like the boards', () => {
    expect(digitsOnly('138-0000 2026x9', 11)).toBe('13800002026')
    expect(digitsOnly(undefined, 6)).toBe('')
    expect(maskPhone('13800002026')).toBe('138****2026')
    expect(maskPhone('1380')).toBe('1380')
    expect(codeButtonText(0)).toBe('获取验证码')
    expect(codeButtonText(58)).toBe('58 秒后重新获取')
  })

  it('describes devices, identities, orders and files in the boards words', () => {
    const at = (iso: string) => iso.slice(5, 10)
    const base = { sessionId: 's', deviceName: 'Android 手机', platform: 'ANDROID' as const, signedInAt: '2026-11-01T01:12:00Z', lastActiveAt: '2026-11-01T01:12:00Z' }
    expect(sessionMeta({ ...base, cityName: '东营市', current: true }, at)).toBe('东营 · 当前在线')
    expect(sessionMeta({ ...base, cityName: null, current: false }, at)).toBe('11-01 登录')
    expect(identityLine({ type: 'ENTERPRISE', enterpriseId: 'e', enterpriseName: '东营港务物流', role: 'MEMBER' })).toBe('东营港务物流 · 成员')
    expect(identityLine({ type: 'PERSONAL', enterpriseId: null, enterpriseName: null, role: null })).toBeNull()
    expect(ROLE_LABELS.ADMIN).toBe('管理员')
    expect(enterpriseOrderTag('PENDING_PAYMENT').text).toBe('待付款')
    expect(enterpriseOrderTag('COMPLETED').text).toBe('已完成')
    expect(avatarChar(' 王女士')).toBe('王')
    expect(formatBytes(2048)).toBe('2 KB')
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB')
    expect(formatBytes(null)).toBe('')
    expect(invitationLinkPath('i-1', 'a b')).toBe('/pages/mine/org-invite?invitationId=i-1&code=a%20b')
  })

  it('builds routes with stable ids only', () => {
    expect(buildOrgUrl()).toBe('/pages/mine/org')
    expect(buildOrgUrl('e/1')).toBe('/pages/mine/org?enterpriseId=e%2F1')
    expect(buildOrgMembersUrl('e1')).toBe('/pages/mine/org-members?enterpriseId=e1')
    expect(buildOrgInviteUrl('i1')).toBe('/pages/mine/org-invite?invitationId=i1')
    expect(buildOrgInviteUrl('i1', 'c')).toBe('/pages/mine/org-invite?invitationId=i1&code=c')
    expect(buildOnboardingUrl('settings')).toBe('/pages/onboarding/index?from=settings')
  })

  it('tells the server the platform and a readable device name, never a hardware id', () => {
    expect(platformOf('android', 'app')).toBe('ANDROID')
    expect(platformOf('ios', 'app')).toBe('IOS')
    expect(platformOf('mac', 'web')).toBe('H5')
    expect(platformOf(undefined, 'mp-weixin')).toBe('MP_WEIXIN')
    expect(deviceNameOf('H5', 'MacIntel')).toBe('网页浏览器')
    expect(deviceNameOf('IOS', 'iPhone 15')).toBe('iPhone 15')
    expect(deviceNameOf('ANDROID', '')).toBeNull()
  })

  it('opens the first-launch page only on a built preview, once', () => {
    expect(shouldShowOnboarding(true, false, false)).toBe(true)
    expect(shouldShowOnboarding(true, true, false)).toBe(false)
    expect(shouldShowOnboarding(true, false, true)).toBe(false)
    expect(shouldShowOnboarding(false, false, false)).toBe(false)
  })

  it('reads the system permission states', () => {
    expect(toState('authorized')).toBe('on')
    expect(toState('denied')).toBe('off')
    expect(toState('not determined')).toBe('ask')
    expect(toState(undefined)).toBe('unknown')
  })
})

describe('identity account api (contract openapi-identity-account.yaml 1.0.0)', () => {
  function recorder() {
    const calls: Array<{ url: string; method: string; data?: unknown; headers?: Record<string, string> }> = []
    const api = createIdentityAccountApi(async <T>(input: { url: string; method: string; data?: unknown; headers?: Record<string, string> }) => {
      calls.push(input)
      return ({ items: [] } as unknown) as T
    })
    return { calls, api }
  }

  it('sends login codes and phone logins without a token', async () => {
    const { calls, api } = recorder()
    await api.sendLoginCode('13800002026')
    await api.loginWithCode('13800002026', '123456', { deviceKey: 'k'.repeat(16), deviceName: 'iPhone', platform: 'IOS', cityCode: '370500' })
    expect(calls[0]).toEqual({ url: '/api/v1/auth/sms-codes', method: 'POST', data: { phone: '13800002026' } })
    expect(calls[1].url).toBe('/api/v1/auth/phone-login')
    expect(calls[1].headers).toBeUndefined()
    expect(calls[1].data).toMatchObject({ phone: '13800002026', code: '123456', device: { platform: 'IOS' } })
  })

  it('carries the token and encodes ids on account and enterprise calls', async () => {
    const { calls, api } = recorder()
    await api.listSessions('t')
    await api.revokeSession('t', 'a/b')
    await api.createEnterprise('t', 'key-1', { name: '东营港务', creditCode: '91370500EXAMPLE26X', cityCode: null })
    await api.invite('t', 'e1', 'LINK')
    await api.readInvitation('t', 'i1', 'c d')
    await api.switchIdentity('t', null)
    await api.listEnterpriseOrders('t', 'e1')
    expect(calls.every((call) => call.headers?.Authorization === 'Bearer t')).toBe(true)
    expect(calls[1]).toMatchObject({ url: '/api/v1/account/sessions/a%2Fb', method: 'DELETE' })
    expect(calls[2].headers).toMatchObject({ 'Idempotency-Key': 'key-1' })
    expect(calls[3]).toMatchObject({ url: '/api/v1/account/enterprises/e1/invitations', data: { kind: 'LINK', phone: null } })
    expect(calls[4].url).toBe('/api/v1/account/enterprise-invitations/i1?code=c%20d')
    expect(calls[5]).toMatchObject({ url: '/api/v1/account/identity', method: 'PUT', data: { enterpriseId: null } })
    expect(calls[6].url).toBe('/api/v1/trade/enterprise-orders?enterpriseId=e1&page=1&size=20')
  })
})
