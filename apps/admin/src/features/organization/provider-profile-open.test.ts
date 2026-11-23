import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'

import {
  ACTION_LABELS,
  createOrganizationApi,
  providerProfileOpening,
  type OpsOrganizationDetail,
  type OrganizationApi,
} from './organization-api'

// S6-01：运营端“机构与资质”给经营机构开通服务商资料（契约 openapi-ops-organization.yaml 1.1.0）。

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
const confirmAction = vi.fn()
const showError = vi.fn()
const notifyRecorded = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, showError, notifyRecorded }))

const { default: ProviderProfilePanel } = await import('./ProviderProfilePanel.vue')

function detail(overrides: {
  type?: 'PROVIDER' | 'CUSTOMER'
  status?: string
  reviewedAt?: string | null
  profile?: OpsOrganizationDetail['providerProfileStatus']
} = {}): OpsOrganizationDetail {
  return {
    organization: {
      organizationId: 'org-1',
      organizationCode: 'ORG-20261123-ABCDEF01',
      name: '淄博示例服务商',
      type: overrides.type ?? 'PROVIDER',
      cityCode: '370300',
      cityName: '淄博市',
      status: overrides.status ?? 'ACTIVE',
      qualificationStatus: 'APPROVED',
      qualificationReviewedAt: overrides.reviewedAt === undefined ? '2026-11-23T02:00:00Z' : overrides.reviewedAt,
      createdAt: '2026-11-23T01:00:00Z',
    },
    qualification: null,
    attachments: [],
    accounts: [],
    grantableRoles: ['PROVIDER_OPERATOR', 'LIVE_HOST'],
    providerProfileStatus: overrides.profile === undefined ? 'NOT_OPENED' : overrides.profile,
    operations: [],
  }
}

function fakeHttp() {
  const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () =>
    new Response(JSON.stringify(detail({ profile: 'ACTIVE' })), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }))
  const http = createApiClient({ getToken: () => 't', onUnauthorized: vi.fn(), fetchImpl, newRequestId: () => 'r' })
  return { http, fetchImpl }
}

function button(text: string): HTMLButtonElement | undefined {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
    .find((candidate) => candidate.textContent?.trim() === text)
}

describe('开通服务商资料：能不能开', () => {
  it('资质审核通过的在用经营机构、还没开通时能开', () => {
    expect(providerProfileOpening(detail())).toEqual({ visible: true, canOpen: true, reason: null })
    expect(providerProfileOpening(detail({ profile: 'DISABLED' })).canOpen).toBe(true)
  })

  it('资质没审核通过、机构停用、已开通时不能开，并说明原因', () => {
    expect(providerProfileOpening(detail({ reviewedAt: null }))).toEqual({
      visible: true, canOpen: false, reason: '资质审核通过后才能开通',
    })
    expect(providerProfileOpening(detail({ status: 'DISABLED' })).reason).toContain('停用')
    expect(providerProfileOpening(detail({ profile: 'ACTIVE' }))).toEqual({
      visible: true, canOpen: false, reason: '已开通',
    })
  })

  it('企业客户没有服务商资料这一项', () => {
    expect(providerProfileOpening(detail({ type: 'CUSTOMER', profile: null })).visible).toBe(false)
  })

  it('操作记录和错误码有中文说明', () => {
    expect(ACTION_LABELS.PROVIDER_PROFILE_OPEN).toBe('开通服务商资料')
    expect(errorMessage(new ApiRequestError(409, 'OPS_QUALIFICATION_NOT_APPROVED', '', null)))
      .toBe('资质审核通过后才能开通服务商资料')
    expect(errorMessage(new ApiRequestError(422, 'OPS_PROVIDER_PROFILE_NOT_APPLICABLE', '', null)))
      .toBe('只有在用的经营机构能开通服务商资料')
  })
})

describe('开通服务商资料：接口', () => {
  it('POST /api/v1/ops/organizations/{id}/provider-profile，没有请求体', async () => {
    const { http, fetchImpl } = fakeHttp()
    const result = await createOrganizationApi(http).openProviderProfile('org-1')
    const [url, init] = fetchImpl.mock.calls[0]!
    expect(`${init!.method} ${String(url)}`).toBe('POST /api/v1/ops/organizations/org-1/provider-profile')
    expect(init!.body ?? null).toBeNull()
    expect(result.providerProfileStatus).toBe('ACTIVE')
  })
})

describe('机构详情的“服务商资料”面板', () => {
  const openProviderProfile = vi.fn<OrganizationApi['openProviderProfile']>()
  const api = { openProviderProfile } as unknown as OrganizationApi

  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  function render(current: OpsOrganizationDetail) {
    return mount(ProviderProfilePanel, {
      props: { api, detail: current },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
  }

  it('确认后开通，把新的机构详情交给页面，并提示记了操作记录', async () => {
    const opened = detail({ profile: 'ACTIVE' })
    openProviderProfile.mockResolvedValue(opened)
    confirmAction.mockResolvedValue(true)
    const wrapper = render(detail())

    expect(wrapper.text()).toContain('未开通')
    button('开通服务商资料')!.click()
    await flushPromises()

    expect(confirmAction).toHaveBeenCalledOnce()
    expect(openProviderProfile).toHaveBeenCalledWith('org-1')
    expect(wrapper.emitted('changed')).toEqual([[opened]])
    expect(notifyRecorded).toHaveBeenCalledWith('开通服务商资料', '操作记录')
  })

  it('取消确认就不调接口；接口报错时给出说明', async () => {
    confirmAction.mockResolvedValueOnce(false)
    render(detail())
    button('开通服务商资料')!.click()
    await flushPromises()
    expect(openProviderProfile).not.toHaveBeenCalled()

    const failure = new ApiRequestError(409, 'OPS_QUALIFICATION_NOT_APPROVED', '', null)
    confirmAction.mockResolvedValueOnce(true)
    openProviderProfile.mockRejectedValueOnce(failure)
    button('开通服务商资料')!.click()
    await flushPromises()
    expect(showError).toHaveBeenCalledWith(failure)
  })

  it('资质没审核通过时按钮不能点，并说明原因', () => {
    const wrapper = render(detail({ reviewedAt: null }))
    expect(button('开通服务商资料')!.disabled).toBe(true)
    expect(wrapper.text()).toContain('资质审核通过后才能开通')
  })

  it('已开通时不再显示开通按钮，可以去“服务商资料审核”', async () => {
    const wrapper = render(detail({ profile: 'ACTIVE' }))
    expect(wrapper.text()).toContain('已开通')
    expect(button('开通服务商资料')).toBeUndefined()
    button('去“服务商资料审核”')!.click()
    expect(push).toHaveBeenCalledWith({ name: 'provider-profile-reviews' })
  })

  it('停用的显示“恢复服务商资料”', () => {
    render(detail({ profile: 'DISABLED' }))
    expect(button('恢复服务商资料')).toBeDefined()
  })
})
