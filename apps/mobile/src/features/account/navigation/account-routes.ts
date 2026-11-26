// Routes of the account and enterprise pages (S5-7, S5-8). Pages pass only stable ids.
export const PHONE_PATH = '/pages/mine/phone'
export const PASSWORD_PATH = '/pages/mine/password'
export const DEVICES_PATH = '/pages/mine/devices'
export const DATA_EXPORT_PATH = '/pages/mine/data'
export const PERMISSIONS_PATH = '/pages/mine/permissions'
export const ONBOARDING_PATH = '/pages/onboarding/index'
export const ORG_PATH = '/pages/mine/org'
export const ORG_MEMBERS_PATH = '/pages/mine/org-members'
export const ORG_INVITE_PATH = '/pages/mine/org-invite'

export function buildOrgUrl(enterpriseId?: string | null): string {
  return enterpriseId ? `${ORG_PATH}?enterpriseId=${encodeURIComponent(enterpriseId)}` : ORG_PATH
}

export function buildOrgMembersUrl(enterpriseId: string): string {
  return `${ORG_MEMBERS_PATH}?enterpriseId=${encodeURIComponent(enterpriseId)}`
}

export function buildOrgInviteUrl(invitationId: string, code?: string | null): string {
  const base = `${ORG_INVITE_PATH}?invitationId=${encodeURIComponent(invitationId)}`
  return code ? `${base}&code=${encodeURIComponent(code)}` : base
}

export function buildOnboardingUrl(from?: 'settings'): string {
  return from ? `${ONBOARDING_PATH}?from=${from}` : ONBOARDING_PATH
}
