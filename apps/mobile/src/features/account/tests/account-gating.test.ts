import { describe, expect, it } from 'vitest'

import flagSource from '../application/account-preview.ts?raw'
import loginSource from '../../inspection/presentation/auth/pages/InspectionLoginPage.vue?raw'
import settingsHost from '../../../pages/mine/settings.vue?raw'
import mineHost from '../../../pages/mine/index.vue?raw'
import homeHost from '../../../pages/home/index.vue?raw'
import pagesJson from '../../../pages.json?raw'
import { moduleFlagExpression } from '../../../app-shell/release/feature-flags'

const hosts = import.meta.glob(['../../../pages/mine/{phone,password,devices,data,permissions,org,org-members,org-invite}.vue', '../../../pages/onboarding/index.vue'], {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

describe('account pages stay behind the ACCOUNT switch (S5-7, S5-8)', () => {
  it('reads its own release constant', () => {
    expect(flagSource.replace(/\r\n/g, '\n')).toContain(`export const ACCOUNT_PREVIEW_ENABLED = ${moduleFlagExpression('ACCOUNT')}\n`)
  })

  it('every new page renders only with the switch on', () => {
    expect(Object.keys(hosts)).toHaveLength(9)
    for (const [path, source] of Object.entries(hosts)) {
      expect(source, path).toContain('ACCOUNT_PREVIEW_ENABLED')
      expect(source, path).toContain('SupportUnavailable')
    }
    for (const page of ['phone', 'password', 'devices', 'data', 'permissions', 'org', 'org-members', 'org-invite']) {
      expect(pagesJson).toContain(`"path": "pages/mine/${page}"`)
    }
    expect(pagesJson).toContain('"path": "pages/onboarding/index"')
  })

  it('public builds keep the password login; the code login is the switch default', () => {
    expect(loginSource).toContain("ref<'phone' | 'password'>(ACCOUNT_PREVIEW_ENABLED ? 'phone' : 'password')")
    expect(loginSource).toContain('v-if="ACCOUNT_PREVIEW_ENABLED"')
    expect(loginSource).toContain('inspectionAuthApi.login(')
  })

  it('settings, 我的 and the home page only add account things with the switch on', () => {
    expect(settingsHost).toContain(':account-pages="ACCOUNT_PREVIEW_ENABLED"')
    expect(settingsHost).toMatch(/ACCOUNT_PREVIEW_ENABLED\s*\?\s*readClosure\([^)]+\)\s*:\s*Promise\.resolve\(null\)/)
    expect(mineHost).toContain("if (!user || !ACCOUNT_PREVIEW_ENABLED || !own) return user")
    expect(mineHost.replace(/\r\n/g, '\n')).toContain("...(ACCOUNT_PREVIEW_ENABLED\n      ? [{ code: 'ENTERPRISE', title: '企业组织'")
    expect(homeHost).toContain('shouldOpenOnboardingNow(ACCOUNT_PREVIEW_ENABLED)')
  })
})
