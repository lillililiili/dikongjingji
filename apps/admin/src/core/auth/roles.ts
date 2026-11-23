/**
 * 运营端认的角色（身份模块：V001 PLATFORM_OPERATOR、PROVIDER_OPERATOR，V004 PLATFORM_SUPPORT，V006 LIVE_HOST）。
 * 菜单和路由都按这些角色出。CUSTOMER 等别的角色进不了运营端。
 */
export const ADMIN_ROLES = [
  'PLATFORM_OPERATOR',
  'PLATFORM_SUPPORT',
  'PROVIDER_OPERATOR',
  'LIVE_HOST',
] as const

export type AdminRole = (typeof ADMIN_ROLES)[number]

export const ROLE_LABELS: Record<string, string> = {
  PLATFORM_OPERATOR: '平台运营',
  PLATFORM_SUPPORT: '平台客服',
  PROVIDER_OPERATOR: '服务商操作员',
  LIVE_HOST: '直播主办',
  CUSTOMER: '客户',
}

export function roleLabel(role: string): string {
  return ROLE_LABELS[role] ?? role
}

export function isAdminRole(role: string): role is AdminRole {
  return (ADMIN_ROLES as readonly string[]).includes(role)
}

/** 账号的角色里有没有其中任一个。roles 为空表示只要登录。 */
export function hasAnyRole(accountRoles: readonly string[], roles: readonly string[] | undefined): boolean {
  if (!roles || roles.length === 0) return true
  return roles.some((role) => accountRoles.includes(role))
}
