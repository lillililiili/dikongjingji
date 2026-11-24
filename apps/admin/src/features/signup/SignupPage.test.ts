import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import ElementPlus, { ElForm } from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import { ApiRequestError } from '@/core/http/api-error'

import SignupPage from './SignupPage.vue'
import type { SignupApi, SignupForm, SignupResult } from './signup-api'

const result: SignupResult = {
  organizationId: 'org-1', organizationName: '东营翼飞', loginName: 'yifei-shop', qualificationStatus: 'SUBMITTED',
}

function mountSignup(signUp: SignupApi['signUp']) {
  const wrapper = mount(SignupPage, {
    props: { api: { signUp, sendCode: vi.fn(), cities: vi.fn().mockResolvedValue([]) } },
    global: { plugins: [ElementPlus], stubs: { RouterLink: RouterLinkStub } },
  })
  const form = wrapper.getComponent(ElForm).props('model') as SignupForm
  Object.assign(form, {
    organizationName: result.organizationName, cityCode: '370500', subjectName: '东营翼飞有限公司',
    licenseNumber: '00000000000000000X', contactName: '王芳', phone: '13800001234', smsCode: '123456',
    loginName: result.loginName, password: 'Fly2026ok', passwordAgain: 'Fly2026ok',
  })
  async function submit() {
    await wrapper.get('form').trigger('submit')
    await flushPromises()
  }
  return { wrapper, form, submit }
}

describe('入驻页重试', () => {
  it.each([
    new ApiRequestError(0, 'CLIENT_NETWORK_ERROR', '', null),
    new ApiRequestError(502, 'COMMON_SERVICE_UNAVAILABLE', '', null),
    new ApiRequestError(201, 'CLIENT_INVALID_RESPONSE', '', null),
  ])('申请已经建好，但没收到有效回复（$status）时重试能取回原申请', async (failure) => {
    let createdKey: string | undefined
    const signUp = vi.fn<SignupApi['signUp']>(async (_form, key) => {
      if (createdKey === undefined) {
        createdKey = key
        throw failure
      }
      if (key !== createdKey) throw new ApiRequestError(409, 'OPS_LOGIN_NAME_TAKEN', '', null)
      return result
    })
    const { wrapper, form, submit } = mountSignup(signUp)
    await submit()
    expect(signUp).toHaveBeenCalledTimes(1)
    expect(createdKey).toBeTruthy()
    // 首尾空格和执照号大小写都不会改变实际请求。
    form.organizationName = ' 东营翼飞 '
    form.licenseNumber = form.licenseNumber.toLowerCase()
    await submit()
    expect(signUp.mock.calls[1]?.[1]).toBe(createdKey)
    expect(wrapper.get('[data-testid="signup-result"]').text()).toContain('申请已提交')
    wrapper.unmount()
  })

  it.each(['更改申请内容', '验证码被拒'])('%s 后使用新的申请键', async (scenario) => {
    const failure = scenario === '验证码被拒' ? new ApiRequestError(400, 'SMS_CODE_INVALID', '', null)
      : new ApiRequestError(0, 'CLIENT_NETWORK_ERROR', '', null)
    const signUp = vi.fn<SignupApi['signUp']>().mockRejectedValueOnce(failure).mockResolvedValue(result)
    const { wrapper, form, submit } = mountSignup(signUp)
    await submit()
    if (scenario === '更改申请内容') form.smsCode = '987654'
    await submit()
    expect(signUp).toHaveBeenCalledTimes(2)
    expect(signUp.mock.calls[1]?.[1]).not.toBe(signUp.mock.calls[0]?.[1])
    expect(wrapper.find('[data-testid="signup-result"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
