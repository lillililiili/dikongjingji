import { AGREEMENT_DOCS, type AgreementDoc } from '../domain/agreement'

export const SETTINGS_PATH = '/pages/mine/settings'
export const AGREEMENT_PATH = '/pages/mine/agreement'
export const ABOUT_PATH = '/pages/mine/about'

export function buildAgreementUrl(doc: AgreementDoc): string {
  return `${AGREEMENT_PATH}?doc=${doc}`
}

// An unknown or missing document opens the privacy policy, as on the board.
export function parseAgreementDoc(value: unknown): AgreementDoc {
  return typeof value === 'string' && (AGREEMENT_DOCS as readonly string[]).includes(value)
    ? value as AgreementDoc
    : 'PRIVACY_POLICY'
}
