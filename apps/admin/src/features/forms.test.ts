import type { FormItemRule, FormRules } from 'element-plus'
import { describe, expect, it } from 'vitest'

import { firstError } from '@/core/ui/form-rules'

import { hidePath } from './feedback/feedback-api'
import {
  accountRules,
  attachmentProblem,
  createOrganizationRules,
  qualificationRules,
  rejectRules,
} from './organization/organization-rules'

function rulesOf(rules: FormRules, field: string): FormItemRule[] {
  const value = rules[field]
  return (Array.isArray(value) ? value : value ? [value] : []) as FormItemRule[]
}

describe('新建机构表单', () => {
  it('类型、名称、城市必填；名称最多 60 字', async () => {
    expect(await firstError(rulesOf(createOrganizationRules, 'name'), '  ')).toBe('请填写机构名称')
    expect(await firstError(rulesOf(createOrganizationRules, 'name'), '黄'.repeat(61))).toBe('机构名称最多 60 个字')
    expect(await firstError(rulesOf(createOrganizationRules, 'name'), '黄'.repeat(60))).toBeNull()
    expect(await firstError(rulesOf(createOrganizationRules, 'cityCode'), '')).toBe('请填写所在城市')
  })
})

describe('资质资料表单', () => {
  it('统一社会信用代码是 18 位数字或大写字母，不含 I、O、Z、S、V；小写会转成大写再判断', async () => {
    const rules = rulesOf(qualificationRules, 'licenseNumber')
    expect(await firstError(rules, '00000000000000000x')).toBeNull()
    for (const bad of ['12345', '0000000000000000O0', '00000000000000000Z', '0000000000000000000']) {
      expect(await firstError(rules, bad), bad).toContain('18 位')
    }
    expect(await firstError(rules, '')).toBe('请填写统一社会信用代码')
  })

  it('经营范围可空，最多 500 字', async () => {
    const rules = rulesOf(qualificationRules, 'businessScope')
    expect(await firstError(rules, '')).toBeNull()
    expect(await firstError(rules, '字'.repeat(501))).toBe('经营范围最多 500 个字')
  })

  it('驳回必须写原因', async () => {
    expect(await firstError(rulesOf(rejectRules, 'reason'), ' ')).toBe('请填写驳回原因')
  })
})

describe('新建账号表单', () => {
  it('登录名小写字母开头 4–32 位；至少一个角色', async () => {
    const login = rulesOf(accountRules, 'loginName')
    expect(await firstError(login, 'hhk-ops')).toBeNull()
    expect(await firstError(login, 'Hhk')).toContain('小写字母开头')
    expect(await firstError(login, '1abc')).toContain('小写字母开头')
    expect(await firstError(rulesOf(accountRules, 'roles'), [])).toBe('请填写角色')
  })
})

describe('附件', () => {
  it('上传前挡住空文件、超过 10 MB、不认识的类型和超出份数', () => {
    expect(attachmentProblem({ size: 10, type: 'application/pdf' }, 0)).toBeNull()
    expect(attachmentProblem({ size: 0, type: 'application/pdf' }, 0)).toBe('文件是空的')
    expect(attachmentProblem({ size: 10 * 1024 * 1024 + 1, type: 'image/png' }, 0)).toBe('单个附件不能超过 10 MB')
    expect(attachmentProblem({ size: 10, type: 'application/zip' }, 0)).toBe('只能上传 PDF、JPEG 或 PNG')
    expect(attachmentProblem({ size: 10, type: 'image/jpeg' }, 10)).toBe('每家最多 10 份附件')
  })
})

describe('举报的隐藏接口', () => {
  it('视频评论按评论 ID，直播弹幕按直播间加弹幕 ID，用户反馈没有', () => {
    const target = { id: 'c-1', parentId: 'r-1' }
    expect(hidePath({ source: 'VIDEO_COMMENT_REPORT', target })).toBe('/api/v1/content/comments/c-1/hide')
    expect(hidePath({ source: 'LIVE_COMMENT_REPORT', target }))
      .toBe('/api/v1/platform/live/rooms/r-1/comments/c-1/hide')
    expect(hidePath({ source: 'USER', target: null })).toBeNull()
  })
})
