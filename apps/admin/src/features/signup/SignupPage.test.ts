import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import ElementPlus, { ElForm } from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import { ApiRequestError } from '@/core/http/api-error'

import SignupPage from './SignupPage.vue'
import type { DirectoryProvince, SignupApi, SignupForm, SignupResult } from './signup-api'

const result: SignupResult = {
  organizationId: 'org-1', organizationName: '东营翼飞', loginName: 'yifei-shop', qualificationStatus: 'SUBMITTED',
}

const sampleProvinces: DirectoryProvince[] = [
  { code: '110000', name: '北京市', cities: [{ code: '110000', name: '北京市', provinceName: '北京市' }] },
  {
    code: '370000',
    name: '山东省',
    cities: [
      { code: '370500', name: '东营市', provinceName: '山东省' },
      { code: '370100', name: '济南市', provinceName: '山东省' },
    ],
  },
]

function mountSignup(signUp: SignupApi['signUp'], provinces: DirectoryProvince[] = sampleProvinces) {
  const wrapper = mount(SignupPage, {
    props: { api: { signUp, sendCode: vi.fn(), provinces: vi.fn().mockResolvedValue(provinces) } },
    global: { plugins: [ElementPlus], stubs: { RouterLink: RouterLinkStub } },
    attachTo: document.body,
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

  it('使用用户名与密码文案，占用错误和提交成功均按新文案提示', async () => {
    const signUp = vi.fn<SignupApi['signUp']>()
      .mockRejectedValueOnce(new ApiRequestError(409, 'OPS_LOGIN_NAME_TAKEN', '', null))
      .mockResolvedValueOnce(result)
    const { wrapper, submit } = mountSignup(signUp)

    expect(wrapper.text()).toContain('用户名')
    expect(wrapper.text()).toContain('密码')
    expect(wrapper.text()).toContain('再输一次密码')
    expect(wrapper.text()).not.toContain('登录名')
    expect(wrapper.text()).not.toContain('口令')

    await submit()
    expect(wrapper.text()).toContain('用户名已被占用，换一个试试')

    await submit()
    const resultText = wrapper.get('[data-testid="signup-result"]').text()
    expect(resultText).toContain('用户名：yifei-shop，密码是你刚设的。')
    expect(resultText).not.toContain('登录名')
    expect(resultText).not.toContain('口令')
    wrapper.unmount()
  })

  it('所在城市使用省市两级级联选择：先选省份再选城市，支持直辖市', async () => {
    const wrapper = mount(SignupPage, {
      props: { api: { signUp: vi.fn(), sendCode: vi.fn(), provinces: vi.fn().mockResolvedValue(sampleProvinces) } },
      global: { plugins: [ElementPlus], stubs: { RouterLink: RouterLinkStub } },
      attachTo: document.body,
    })
    const form = wrapper.getComponent(ElForm).props('model') as SignupForm
    await flushPromises()

    await wrapper.get('[data-testid="signup-city"] .el-cascader').trigger('click')
    await flushPromises()

    const menus = () => Array.from(document.body.querySelectorAll<HTMLElement>('.el-cascader-menu'))
    const menuItems = (menu: ParentNode = document.body) =>
      Array.from(menu.querySelectorAll<HTMLElement>('.el-cascader-node[role="menuitem"]'))
    const clickMenuItem = async (label: string, menu?: ParentNode) => {
      const node = menuItems(menu).find((el) => el.textContent?.trim() === label)
      expect(node, `应存在级联项“${label}”`).toBeTruthy()
      node!.click()
      await flushPromises()
    }

    // 首次展开只有一列省份，不直接平铺城市。
    expect(menus()).toHaveLength(1)
    expect(menuItems(menus()[0]).map((el) => el.textContent?.trim())).toEqual(['北京市', '山东省'])

    await clickMenuItem('山东省', menus()[0])
    expect(menus()).toHaveLength(2)
    expect(menuItems(menus()[1]).map((el) => el.textContent?.trim())).toEqual(['东营市', '济南市'])
    await clickMenuItem('东营市', menus()[1])
    expect(form.cityCode).toBe('370500')

    // 直辖市（省码与市码均为 110000）也能正常选中。
    await wrapper.get('[data-testid="signup-city"] .el-cascader').trigger('click')
    await flushPromises()
    await clickMenuItem('北京市', menus()[0])
    await clickMenuItem('北京市', menus()[1])
    expect(form.cityCode).toBe('110000')

    wrapper.unmount()
  })
})
