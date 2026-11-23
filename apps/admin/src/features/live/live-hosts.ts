import type { GrantableRole } from '@/features/organization/organization-api'

const GRANTABLE: readonly GrantableRole[] = ['PROVIDER_OPERATOR', 'LIVE_HOST', 'CUSTOMER']

/**
 * 开通直播主办时给账号的完整角色表：改角色接口是整组替换，所以保留账号现有的可授角色，再加上直播主办。
 * 不可授的角色（平台角色等）不在机构账号上出现，原样丢掉也不会被这个接口改动。
 */
export function grantLiveHostRoles(current: readonly string[]): GrantableRole[] {
  const kept = GRANTABLE.filter((role) => current.includes(role))
  return kept.includes('LIVE_HOST') ? kept : [...kept, 'LIVE_HOST']
}
