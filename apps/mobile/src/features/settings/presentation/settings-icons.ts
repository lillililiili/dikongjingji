import type { AgreementDoc } from '../domain/agreement'

// Line icons copied from the boards Settings and About.
export const AGREEMENT_ICONS: Record<'USER_AGREEMENT' | 'PRIVACY_POLICY' | 'CHILD_PRIVACY_RULES', string> = {
  USER_AGREEMENT: 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h5',
  PRIVACY_POLICY: 'M6 11h12v10H6zM8.5 11V8a3.5 3.5 0 0 1 7 0v3M12 15v2',
  CHILD_PRIVACY_RULES: 'M12 9.5a3 3 0 1 0 0-6a3 3 0 1 0 0 6zM8 21v-4.5a4 4 0 0 1 8 0V21M10.5 21v-3M13.5 21v-3',
}

export const SETTINGS_RULE_DOCS: ReadonlyArray<keyof typeof AGREEMENT_ICONS & AgreementDoc> = [
  'USER_AGREEMENT',
  'PRIVACY_POLICY',
  'CHILD_PRIVACY_RULES',
]

export const SETTINGS_CHEVRON = 'M9 5l7 7-7 7'

// The App mark of the board About: a white drone frame.
export const APP_MARK = 'M9 9L6 6M15 9l3-3M9 15l-3 3M15 15l3 3M10 9h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1zM3 6h6M15 6h6M3 18h6M15 18h6'
