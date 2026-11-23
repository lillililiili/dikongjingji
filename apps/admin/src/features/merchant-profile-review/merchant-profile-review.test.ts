import type { FormItemRule } from 'element-plus'
import { describe, expect, it } from 'vitest'

import { ApiRequestError, errorMessage } from '@/core/http/api-error'
import { jsonBody, recordingClient } from '@/core/http/recording-client'
import { firstError } from '@/core/ui/form-rules'

import {
  createMerchantProfileReviewApi,
  rejectReviewRules,
  REVIEW_STATUS_OPTIONS,
  reviewIsStale,
} from './merchant-profile-review-api'

const SUBMISSION = 'd1000000-0000-4000-8000-000000000001'

describe('商家资料审核接口', () => {
  it('列表带状态、关键词、分页，空的不带；详情按提交号读', async () => {
    const { http, requests } = recordingClient({ page: 1, size: 20, total: 0, hasMore: false, items: [] })
    const api = createMerchantProfileReviewApi(http)
    await api.list({ status: 'SUBMITTED', keyword: ' 机器人 ' }, 1, 20)
    await api.list({ status: '', keyword: '' }, 2, 10)
    await api.detail(SUBMISSION)

    expect(requests[0]?.url).toBe('/api/v1/ops/mall/merchant-profiles?status=SUBMITTED'
      + '&keyword=%E6%9C%BA%E5%99%A8%E4%BA%BA&page=1&size=20')
    expect(requests[1]?.url).toBe('/api/v1/ops/mall/merchant-profiles?page=2&size=10')
    expect(requests[2]?.url).toBe(`/api/v1/ops/mall/merchant-profiles/${SUBMISSION}`)
  })

  it('通过只带 version；驳回带 version 和去掉首尾空白的原因', async () => {
    const { http, requests } = recordingClient({})
    const api = createMerchantProfileReviewApi(http)
    await api.approve({ submissionId: SUBMISSION, version: 3 })
    await api.reject({ submissionId: SUBMISSION, version: 3 }, ' 主体与营业执照不一致 ')

    expect(requests[0]?.url).toBe(`/api/v1/ops/mall/merchant-profiles/${SUBMISSION}/approval`)
    expect(requests[0]?.method).toBe('POST')
    expect(jsonBody(requests[0])).toEqual({ version: 3 })
    expect(requests[1]?.url).toBe(`/api/v1/ops/mall/merchant-profiles/${SUBMISSION}/rejection`)
    expect(jsonBody(requests[1])).toEqual({ version: 3, reason: '主体与营业执照不一致' })
  })

  it('已处理过、版本不对要刷新，都有中文说明', () => {
    const closed = new ApiRequestError(409, 'OPS_MERCHANT_PROFILE_REVIEW_CLOSED', 'x', 't')
    const version = new ApiRequestError(409, 'OPS_VERSION_CONFLICT', 'x', 't')
    expect(reviewIsStale(closed)).toBe(true)
    expect(reviewIsStale(version)).toBe(true)
    expect(reviewIsStale(new ApiRequestError(400, 'COMMON_INVALID_ARGUMENT', 'x', 't'))).toBe(false)
    expect(errorMessage(closed)).toContain('刷新')
    expect(errorMessage(version)).toContain('刷新')
  })

  it('驳回原因必填、不超过 200 字；状态筛选覆盖四个值', async () => {
    const reason = rejectReviewRules.reason as FormItemRule[]
    expect(await firstError(reason, ' ')).toBe('请填写驳回原因')
    expect(await firstError(reason, '原'.repeat(201))).toContain('200')
    expect(await firstError(reason, '主体不一致')).toBeNull()
    expect(REVIEW_STATUS_OPTIONS.map((option) => option.value)).toEqual(
      ['SUBMITTED', 'APPROVED', 'REJECTED', 'SUPERSEDED'])
  })
})
