import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { ApiRequestError } from '@/core/http/api-error'

const login = vi.fn()
vi.mock('@/core/auth/session', () => ({
  useSessionStore: () => ({ login }),
}))

const { default: LoginPage } = await import('./LoginPage.vue')

/** 表单校验是异步的，错误提示还要等一轮渲染。 */
async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginPage },
      { path: '/', component: { template: '<div>home</div>' } },
      { path: '/cities', component: { template: '<div>cities</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(LoginPage, { global: { plugins: [ElementPlus, router] }, attachTo: document.body })
  return { wrapper, router }
}

describe('登录页', () => {
  beforeEach(() => {
    login.mockReset()
  })

  it('没填就提交：显示必填提示，不调登录', async () => {
    const { wrapper } = await mountAt('/login')

    await wrapper.find('form').trigger('submit')
    await settle()

    // 错误提示有 100ms 防抖（Element Plus 表单项）。
    await vi.waitFor(() => expect(wrapper.text()).toContain('请填写登录名'), { timeout: 2000 })
    expect(wrapper.text()).toContain('请填写口令')
    expect(login).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('登录成功回到 redirect 指的页面', async () => {
    login.mockResolvedValue({})
    const { wrapper, router } = await mountAt('/login?redirect=/cities')

    await wrapper.find('input[autocomplete="username"]').setValue('  dev-operator ')
    await wrapper.find('input[type="password"]').setValue('pw-123456789')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(login).toHaveBeenCalledWith('dev-operator', 'pw-123456789')
    expect(router.currentRoute.value.fullPath).toBe('/cities')
    wrapper.unmount()
  })

  it('口令不对：提示并清空口令', async () => {
    login.mockRejectedValue(new ApiRequestError(401, 'COMMON_UNAUTHORIZED', '用户名或密码错误', 't'))
    const { wrapper, router } = await mountAt('/login')

    await wrapper.find('input[autocomplete="username"]').setValue('dev-operator')
    const password = wrapper.find('input[type="password"]')
    await password.setValue('wrong-password')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(wrapper.text()).toContain('登录名或口令不对')
    expect((password.element as HTMLInputElement).value).toBe('')
    expect(router.currentRoute.value.path).toBe('/login')
    wrapper.unmount()
  })
})
