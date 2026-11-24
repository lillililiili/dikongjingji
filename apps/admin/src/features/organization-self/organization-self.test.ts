import { describe, expect, it } from 'vitest'

import { jsonBody, recordingClient } from '@/core/http/recording-client'

import { createOrganizationSelfApi } from './organization-self-api'

describe('工作台·入驻资料接口', () => {
  it('读本机构、改资质、传和移除附件都在 /api/v1/provider/organization 下', async () => {
    const { http, requests } = recordingClient({})
    const api = createOrganizationSelfApi(http)
    await api.read()
    await api.submitQualification({ subjectName: ' 东营翼飞科技有限公司 ', licenseNumber: '00000000000000000x',
      businessScope: '  ', qualificationNote: '合格证 A-1' }, 2)
    await api.removeAttachment('att-1')

    expect(requests.map((request) => `${request.method} ${request.url}`)).toEqual([
      'GET /api/v1/provider/organization',
      'PUT /api/v1/provider/organization/qualification',
      'DELETE /api/v1/provider/organization/attachments/att-1',
    ])
    expect(jsonBody(requests[1])).toEqual({ subjectName: '东营翼飞科技有限公司', licenseNumber: '00000000000000000X',
      businessScope: null, qualificationNote: '合格证 A-1', version: 2 })
  })
})
