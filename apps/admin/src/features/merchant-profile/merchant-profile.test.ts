import type { FormItemRule } from 'element-plus'
import { describe, expect, it } from 'vitest'

import { jsonBody, recordingClient } from '@/core/http/recording-client'
import { firstError } from '@/core/ui/form-rules'

import {
  createMerchantProfileApi,
  fieldText,
  formFrom,
  merchantProfileRules,
  PROFILE_FIELD_LABELS,
  profileRequestBody,
  SUBMISSION_STATUS_LABELS,
  SUBMISSION_STATUS_TAGS,
  type MallMerchantProfileState,
  type MerchantProfileForm,
} from './merchant-profile-api'

const EMPTY: MerchantProfileForm = { businessEntity: '', intro: '', saleScopeNote: '', authorizationUntil: '',
  categoryNote: '', invoiceIssuer: '', aftersaleParty: '' }

const DRAFT = { businessEntity: '东营示例机器人科技有限公司', intro: '教育机器人厂家直营', saleScopeNote: '全国发货',
  authorizationUntil: '2027-12-31', categoryNote: '教育机器人', invoiceIssuer: null, aftersaleParty: null }

function state(overrides: Partial<MallMerchantProfileState> = {}): MallMerchantProfileState {
  return { organizationId: 'o-1', organizationName: '示例机器人商家', published: null, latest: null, ...overrides }
}

function rules(field: keyof MerchantProfileForm): FormItemRule[] {
  return (merchantProfileRules[field] ?? []) as FormItemRule[]
}

describe('工作台·商家资料接口', () => {
  it('GET / PUT 同一个地址；提交时去掉首尾空白，空的写 null', async () => {
    const { http, requests } = recordingClient(state())
    const api = createMerchantProfileApi(http)
    await api.read()
    await api.submit({ ...EMPTY, businessEntity: ' 东营示例机器人科技有限公司 ', saleScopeNote: ' 全国发货 ',
      authorizationUntil: '2027-12-31', invoiceIssuer: '  ' })

    expect(requests[0]?.url).toBe('/api/v1/provider/mall/merchant-profile')
    expect(requests[0]?.method).toBe('GET')
    expect(requests[1]?.url).toBe('/api/v1/provider/mall/merchant-profile')
    expect(requests[1]?.method).toBe('PUT')
    expect(jsonBody(requests[1])).toEqual({ businessEntity: '东营示例机器人科技有限公司', intro: null,
      saleScopeNote: '全国发货', authorizationUntil: '2027-12-31', categoryNote: null, invoiceIssuer: null,
      aftersaleParty: null })
    expect(profileRequestBody(EMPTY).businessEntity).toBe('')
  })

  it('表单初值：最近一次提交 → 对外的资料 → 机构名称', () => {
    expect(formFrom(state()).businessEntity).toBe('示例机器人商家')
    const published = { ...DRAFT, businessEntity: '已通过的主体', approvedAt: '2026-11-20T00:00:00Z' }
    expect(formFrom(state({ published })).businessEntity).toBe('已通过的主体')
    const latest = { submissionId: 's-1', organizationId: 'o-1', organizationName: '示例机器人商家', draft: DRAFT,
      status: 'REJECTED' as const, submittedByName: '张三', submittedAt: '2026-11-21T00:00:00Z', reviewedByName: '运营',
      reviewedAt: '2026-11-22T00:00:00Z', rejectReason: '主体不一致', version: 2 }
    const form = formFrom(state({ published, latest }))
    expect(form).toEqual({ ...DRAFT, invoiceIssuer: '', aftersaleParty: '' })
  })
})

describe('表单规则（和契约 MerchantProfileRequest 一致）', () => {
  it('经营主体必填 2–160 字', async () => {
    expect(await firstError(rules('businessEntity'), '')).toBe('请填写经营主体')
    expect(await firstError(rules('businessEntity'), '东')).toBe('经营主体至少 2 个字')
    expect(await firstError(rules('businessEntity'), '东'.repeat(161))).toContain('160')
    expect(await firstError(rules('businessEntity'), '东营')).toBeNull()
  })

  it('简介 ≤300、可售说明 ≤40、品类 ≤40、开票方 ≤60、售后责任方 ≤60；都可空', async () => {
    const limits: Array<[keyof MerchantProfileForm, number]> = [['intro', 300], ['saleScopeNote', 40],
      ['categoryNote', 40], ['invoiceIssuer', 60], ['aftersaleParty', 60]]
    for (const [field, max] of limits) {
      expect(await firstError(rules(field), ''), field).toBeNull()
      expect(await firstError(rules(field), '字'.repeat(max)), field).toBeNull()
      expect(await firstError(rules(field), '字'.repeat(max + 1)), field).toContain(String(max))
    }
  })

  it('授权有效期是日期或空', async () => {
    expect(await firstError(rules('authorizationUntil'), '')).toBeNull()
    expect(await firstError(rules('authorizationUntil'), '2027-12-31')).toBeNull()
    expect(await firstError(rules('authorizationUntil'), '2027/12/31')).not.toBeNull()
  })
})

describe('中文', () => {
  it('提交状态四个值都有中文和颜色；七项都有中文名；空值写“未填”', () => {
    const statuses = ['SUBMITTED', 'APPROVED', 'REJECTED', 'SUPERSEDED']
    expect(Object.keys(SUBMISSION_STATUS_LABELS).sort()).toEqual([...statuses].sort())
    expect(Object.keys(SUBMISSION_STATUS_TAGS).sort()).toEqual([...statuses].sort())
    expect(Object.keys(PROFILE_FIELD_LABELS)).toHaveLength(7)
    expect(fieldText(null)).toBe('未填')
    expect(fieldText('')).toBe('未填')
    expect(fieldText('全国发货')).toBe('全国发货')
  })
})
