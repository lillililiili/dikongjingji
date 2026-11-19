import { describe, expect, it } from 'vitest'

import {
  DEMAND_BUDGET_OPTIONS,
  DEMAND_NOTICE_TEXT,
  buildDemandDueOptions,
  changeDemandCity,
  createDemandForm,
  createDemandKeyHolder,
  describeDemandSaveFailure,
  findDemandFormError,
  formFromDemand,
  formatDemandBudget,
  formatDemandDueDate,
  preselectInvitedProvider,
  switchDemandType,
  toDemandDraftInput,
  toggleDemandDeliverable,
  toggleInvitedProvider,
  type DemandForm,
} from '../application/demand-form'
import { DEMAND_TYPES, DEMAND_TYPE_INFO } from '../domain/demand'
import { demandOf } from './demand-fixtures'

const CITY = { code: '370500', name: '东营市' }
const PROVIDER = (id: string) => ({ providerId: id, name: `服务商${id}`, certificationLabel: null, description: null })

function filled() {
  return { ...createDemandForm('INSPECTION', CITY), address: '开发区', scope: '1.2 平方公里' }
}

describe('demand form', () => {
  it('starts each type with the board defaults and a matching scope label', () => {
    expect(createDemandForm('INSPECTION', CITY).deliverables).toEqual(['INSPECTION_REPORT', 'ORTHOPHOTO', 'HAZARD_LIST'])
    expect(createDemandForm('AERIAL', null).deliverables).toEqual(['VIDEO_FOOTAGE', 'PHOTOS'])
    expect(DEMAND_TYPE_INFO.MAINTENANCE.scopeLabel).toBe('设备情况')
    expect(DEMAND_TYPE_INFO.TECH_SUPPORT.scopePlaceholder).toBe('需要支持的事项')
    for (const type of DEMAND_TYPES) {
      const info = DEMAND_TYPE_INFO[type]
      expect(info.defaultDeliverables.every((code) => info.deliverables.includes(code))).toBe(true)
    }
  })

  it('switching the type resets deliverables to its defaults and clears the scope', () => {
    const form = { ...filled(), deliverables: ['MODEL_3D' as const] }
    const switched = switchDemandType(form, 'MAINTENANCE')
    expect(switched.deliverables).toEqual(['REPAIR_RECORD', 'TEST_REPORT'])
    expect(switched.scope).toBe('')
    expect(switched.address).toBe('开发区')
    expect(switchDemandType(form, 'INSPECTION')).toBe(form)
  })

  it('keeps ticked deliverables in page order', () => {
    let form: DemandForm = { ...filled(), deliverables: [] }
    form = toggleDemandDeliverable(form, 'VIDEO_FOOTAGE')
    form = toggleDemandDeliverable(form, 'INSPECTION_REPORT')
    expect(form.deliverables).toEqual(['INSPECTION_REPORT', 'VIDEO_FOOTAGE'])
    expect(toggleDemandDeliverable(form, 'VIDEO_FOOTAGE').deliverables).toEqual(['INSPECTION_REPORT'])
  })

  it('invites at most three providers and drops them when the city changes', () => {
    let form = filled()
    for (const id of ['a', 'b', 'c', 'd']) form = toggleInvitedProvider(form, PROVIDER(id))
    expect(form.invited.map((item) => item.providerId)).toEqual(['a', 'b', 'c'])
    expect(toggleInvitedProvider(form, PROVIDER('b')).invited.map((item) => item.providerId)).toEqual(['a', 'c'])
    expect(changeDemandCity(form, CITY).invited).toHaveLength(3)
    expect(changeDemandCity(form, { code: '370100', name: '济南市' }).invited).toEqual([])
  })

  // 发布需求 on a service or provider page: 仅邀请这家 only when the city's list has the provider.
  it('preselects the provider of a service page only when it can be invited', () => {
    const form = createDemandForm('AERIAL', CITY)
    const listed = [PROVIDER('a'), PROVIDER('b')]
    const picked = preselectInvitedProvider(form, listed, 'b')
    expect(picked.found).toBe(true)
    expect(picked.form.quoteScope).toBe('INVITE')
    expect(picked.form.invited).toEqual([PROVIDER('b')])
    expect(toDemandDraftInput(picked.form).invitedProviderIds).toEqual(['b'])

    const missing = preselectInvitedProvider(form, listed, 'z')
    expect(missing.found).toBe(false)
    expect(missing.form).toBe(form)
    expect(missing.form.quoteScope).toBe('MATCH')
  })

  it('finds the first missing field top to bottom, like the server', () => {
    expect(findDemandFormError(createDemandForm('INSPECTION', null))?.field).toBe('cityCode')
    expect(findDemandFormError(createDemandForm('INSPECTION', CITY))).toEqual({ field: 'address', message: '请填写服务地点' })
    expect(findDemandFormError({ ...filled(), scope: ' ' })).toEqual({ field: 'scope', message: '请填写作业范围' })
    expect(findDemandFormError({ ...switchDemandType(filled(), 'MAINTENANCE') })?.message).toBe('请填写设备情况')
    expect(findDemandFormError({ ...filled(), deliverables: [] })?.field).toBe('deliverables')
    expect(findDemandFormError({ ...filled(), quoteScope: 'INVITE' })?.field).toBe('invitedProviderIds')
    expect(findDemandFormError(filled())).toBeNull()
  })

  it('sends only the invited ids of an INVITE demand', () => {
    const form = toggleInvitedProvider({ ...filled(), quoteScope: 'INVITE' }, PROVIDER('a'))
    expect(toDemandDraftInput(form)).toMatchObject({ cityCode: '370500', quoteScope: 'INVITE', invitedProviderIds: ['a'] })
    expect(toDemandDraftInput({ ...form, quoteScope: 'MATCH' }).invitedProviderIds).toEqual([])
  })

  it('reads a draft back into the form', () => {
    const form = formFromDemand(demandOf({
      status: 'DRAFT',
      cityCode: null,
      cityName: null,
      address: null,
      quoteScope: 'INVITE',
      invitedProviders: [PROVIDER('a')],
    }))
    expect(form.city).toBeNull()
    expect(form.address).toBe('')
    expect(form.deliverables).toEqual(['INSPECTION_REPORT', 'ORTHOPHOTO'])
    expect(form.invited.map((item) => item.providerId)).toEqual(['a'])
  })

  it('maps a 422 to the field it names', () => {
    expect(describeDemandSaveFailure(
      { status: 422, code: 'DEMAND_INCOMPLETE', message: '必填项不满足', details: { field: 'dueDate' } },
      'INSPECTION',
    )).toEqual({ field: 'dueDate', message: '期望完成日期已过，请重新选择', reload: false, newKey: false })
    expect(describeDemandSaveFailure(
      { status: 422, code: 'DEMAND_INCOMPLETE', message: '', details: { field: 'scope' } },
      'TECH_SUPPORT',
    ).message).toBe('请填写支持内容')
    expect(describeDemandSaveFailure(
      { status: 422, code: 'DEMAND_INCOMPLETE', message: '服务器说的', details: { field: 'other' } },
      'INSPECTION',
    )).toMatchObject({ field: null, message: '服务器说的' })
    expect(describeDemandSaveFailure(
      { status: 409, code: 'DEMAND_IDEMPOTENCY_KEY_REUSED', message: '', details: null },
      'INSPECTION',
    )).toMatchObject({ newKey: true })
    expect(describeDemandSaveFailure(
      { status: 409, code: 'DEMAND_STATE_INVALID', message: '', details: null },
      'INSPECTION',
    )).toMatchObject({ reload: true, message: '这份需求已经提交，不能再改' })
    expect(describeDemandSaveFailure(null, 'INSPECTION').message).toBe('保存失败，请稍后重试')
  })

  it('formats budgets as the board does', () => {
    expect(DEMAND_BUDGET_OPTIONS.map((option) => formatDemandBudget(option.minMinor, option.maxMinor)))
      .toEqual(DEMAND_BUDGET_OPTIONS.map((option) => option.label))
    expect(formatDemandBudget(300000, 800000)).toBe('3,000–8,000元')
    expect(formatDemandBudget(500000, 1500000)).toBe('5,000–1.5 万元')
    expect(formatDemandBudget(0, 1000000)).toBe('1 万元以下')
    expect(formatDemandBudget(1000000, 1000000)).toBe('1 万元')
  })

  it('offers due dates a week, two weeks and a month out, then 时间可协商', () => {
    const now = new Date('2026-11-19T02:00:00Z')
    expect(buildDemandDueOptions(now)).toEqual([
      { label: '11/26 前', date: '2026-11-26' },
      { label: '12/03 前', date: '2026-12-03' },
      { label: '12/19 前', date: '2026-12-19' },
      { label: '时间可协商', date: null },
    ])
    expect(formatDemandDueDate('2027-01-05', now)).toBe('2027/01/05 前')
    expect(formatDemandDueDate(null, now)).toBe('时间可协商')
  })

  it('reuses one idempotency key for the same content and makes a new one when it changes', () => {
    let count = 0
    const keys = createDemandKeyHolder(() => `k${++count}`)
    const input = toDemandDraftInput(filled())
    expect(keys.keyFor(input, true)).toBe('k1')
    expect(keys.keyFor({ ...input }, true)).toBe('k1')
    expect(keys.keyFor(input, false)).toBe('k2')
    expect(keys.keyFor({ ...input, scope: '2 平方公里' }, false)).toBe('k3')
    keys.reset()
    expect(keys.keyFor({ ...input, scope: '2 平方公里' }, false)).toBe('k4')
  })

  it('says 72 hours where the board left the agreed time open', () => {
    expect(DEMAND_NOTICE_TEXT).toContain('72 小时内报价')
    expect(DEMAND_NOTICE_TEXT).not.toContain('[')
    expect(DEMAND_NOTICE_TEXT).toContain('提交需求不产生费用')
  })
})
