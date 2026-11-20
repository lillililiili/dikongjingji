import { describe, expect, it } from 'vitest'

import { QUALIFICATION_REVIEWED_TEXT, providerCredentialLabel } from './qualification'

describe('providerCredentialLabel', () => {
  it('reads 资质已审核 only when the server says the qualification was reviewed', () => {
    expect(QUALIFICATION_REVIEWED_TEXT).toBe('资质已审核')
    expect(providerCredentialLabel(true, '企业认证')).toBe('资质已审核')
    expect(providerCredentialLabel(true, null)).toBe('资质已审核')
  })

  it('keeps the provider own label when not reviewed, and nothing without one', () => {
    expect(providerCredentialLabel(false, '企业认证')).toBe('企业认证')
    expect(providerCredentialLabel(undefined, ' 企业认证 ')).toBe('企业认证')
    expect(providerCredentialLabel(false, '  ')).toBeNull()
    expect(providerCredentialLabel(null, null)).toBeNull()
  })

  it('does not take a label that merely reads 资质已审核 as a review', () => {
    // The field is the only source: without it the tag is hidden even if the profile says so.
    expect(providerCredentialLabel(false, '资质已审核')).toBeNull()
    expect(providerCredentialLabel(undefined, ' 资质已审核 ')).toBeNull()
  })
})
