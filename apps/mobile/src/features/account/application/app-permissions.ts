// 权限管理 (board SetPerm): the App cannot change a system permission, it only reads the state and
// opens the system settings page. On H5 the browser keeps them, so the state is unknown there.
export type PermissionCode = 'location' | 'camera' | 'notify'
export type PermissionState = 'on' | 'off' | 'ask' | 'unknown'

export function readPermissionStates(): Record<PermissionCode, PermissionState> {
  const unknown: Record<PermissionCode, PermissionState> = { location: 'unknown', camera: 'unknown', notify: 'unknown' }
  try {
    const read = (uni as unknown as { getAppAuthorizeSetting?: () => Record<string, string> }).getAppAuthorizeSetting
    if (!read) return unknown
    const setting = read()
    return {
      location: toState(setting.locationAuthorized),
      camera: toState(setting.cameraAuthorized),
      notify: toState(setting.notificationAuthorized),
    }
  } catch {
    return unknown
  }
}

export function toState(value: string | undefined): PermissionState {
  if (value === 'authorized') return 'on'
  if (value === 'denied') return 'off'
  if (value === 'not determined') return 'ask'
  return 'unknown'
}

/** Opens this App's page in the system settings; false where there is none (H5). */
export function openSystemSettings(): boolean {
  const open = (uni as unknown as { openAppAuthorizeSetting?: (options: object) => void }).openAppAuthorizeSetting
  if (!open) return false
  try {
    open({})
    return true
  } catch {
    return false
  }
}
