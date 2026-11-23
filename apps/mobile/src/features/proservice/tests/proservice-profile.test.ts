import { describe, expect, it } from 'vitest'

import { buildProServiceSections } from '../application/proservice-view'
import { toProviderProfile } from '../data/proservice-api'
import { detailOf } from './proservice-fixtures'

// contracts/openapi-proservice.yaml 1.2.0 (S4-4): the warranty of a service, the reviewed profile of
// a provider page.
describe('pro service 1.2.0', () => {
  it('puts the warranty under 保修与收费边界 of a maintenance service, as the server words it', () => {
    const maintenance = detailOf({
      type: 'MAINTENANCE',
      terms: [{ label: '保修', detail: '维修部位' }],
      warrantyText: '维修后质保 90 天',
    })
    expect(buildProServiceSections(maintenance).find((section) => section.kind === 'badges')).toEqual({
      kind: 'badges', title: '保修与收费边界', lines: [{ label: '保修', detail: '维修部位' }], note: '维修后质保 90 天',
    })
    // Without a warranty nothing is added; with only a warranty the section still shows it.
    expect(buildProServiceSections({ ...maintenance, warrantyText: null }).find((section) => section.kind === 'badges'))
      .toMatchObject({ note: '' })
    expect(buildProServiceSections({ ...maintenance, terms: [] }).some((section) => section.kind === 'badges')).toBe(true)
    expect(buildProServiceSections({ ...maintenance, terms: [], warrantyText: null }).some((section) => section.kind === 'badges')).toBe(false)
  })

  it('reads the approved profile and nothing when there is none', () => {
    expect(toProviderProfile(null)).toBeNull()
    expect(toProviderProfile(undefined)).toBeNull()
    expect(toProviderProfile({
      legalEntity: ' 示例鹰眼智能科技有限公司 ',
      serviceModes: '无人机巡检 · 机器狗夜巡',
      authorizationUntil: '2027-10-31',
      deliverables: ['巡检报告', ' ', '隐患清单'],
      localTeamLabel: ' ',
    })).toEqual({
      legalEntity: '示例鹰眼智能科技有限公司',
      serviceModes: '无人机巡检 · 机器狗夜巡',
      authorizationUntil: '2027-10-31',
      deliverables: ['巡检报告', '隐患清单'],
      localTeamLabel: null,
    })
  })
})
