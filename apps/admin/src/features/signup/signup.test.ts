import type { FormItemRule } from 'element-plus'
import { describe, expect, it } from 'vitest'

import { jsonBody, recordingClient } from '@/core/http/recording-client'
import { firstError } from '@/core/ui/form-rules'

import { createSignupApi, emptySignupForm, signupRequestBody, signupRules, type SignupForm } from './signup-api'

function filled(): SignupForm {
  return { ...emptySignupForm(), organizationName: ' 东营翼飞科技 ', cityCode: '370500', subjectName: '东营翼飞科技有限公司',
    licenseNumber: '00000000000000000x', businessScope: '', contactName: '王芳', phone: '13800001234', smsCode: '123456',
    loginName: 'yifei-shop', password: 'Fly2026ok', passwordAgain: 'Fly2026ok' }
}

describe('商家入驻申请', () => {
  it('发码和申请的地址、幂等键和请求体', async () => {
    const { http, requests } = recordingClient({ organizationId: 'o', organizationName: '东营翼飞科技',
      loginName: 'yifei-shop', qualificationStatus: 'SUBMITTED' })
    const api = createSignupApi(http)
    await api.sendCode(' 13800001234 ')
    await api.signUp(filled(), '11111111-1111-4111-8111-111111111111')

    expect(requests[0]?.method).toBe('POST')
    expect(requests[0]?.url).toBe('/api/v1/organization-signups/sms-codes')
    expect(jsonBody(requests[0])).toEqual({ phone: '13800001234' })
    expect(requests[1]?.url).toBe('/api/v1/organization-signups')
    expect(requests[1]?.headers['Idempotency-Key']).toBe('11111111-1111-4111-8111-111111111111')
    expect(jsonBody(requests[1])).toEqual({ organizationName: '东营翼飞科技', cityCode: '370500',
      subjectName: '东营翼飞科技有限公司', licenseNumber: '00000000000000000X', businessScope: null, contactName: '王芳',
      phone: '13800001234', smsCode: '123456', loginName: 'yifei-shop', password: 'Fly2026ok' })
    expect(signupRequestBody(filled())).not.toHaveProperty('passwordAgain')
  })

  it('表单规则和后端一致：代码格式、登录名、口令强度、两次口令', async () => {
    const form = filled()
    const rules = signupRules(form)
    const rule = (field: keyof SignupForm) => (rules[field] ?? []) as FormItemRule[]
    expect(await firstError(rule('licenseNumber'), '1234')).toContain('18 位')
    expect(await firstError(rule('licenseNumber'), '00000000000000000x')).toBeNull()
    expect(await firstError(rule('loginName'), 'Admin')).toContain('小写字母开头')
    expect(await firstError(rule('password'), 'onlyletters')).toContain('至少一个字母和一个数字')
    expect(await firstError(rule('password'), 'Fly2026ok')).toBeNull()
    expect(await firstError(rule('passwordAgain'), 'different')).toContain('不一样')
    expect(await firstError(rule('passwordAgain'), 'Fly2026ok')).toBeNull()
    expect(await firstError(rule('phone'), '12345')).toContain('手机号')
  })
})
