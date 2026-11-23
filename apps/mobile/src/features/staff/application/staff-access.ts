import type { CheckinApi } from '../data/checkin-api'

/**
 * Whether the signed-in account may open 工作模式: an operator (PROVIDER_OPERATOR) of an organization
 * that hosts activities or runs venues. The server decides; any refusal or failure means no entry.
 */
export async function loadStaffAccess(api: Pick<CheckinApi, 'workbench'>, accessToken: string): Promise<boolean> {
  try {
    const workbench = await api.workbench(accessToken)
    return workbench.kinds.length > 0
  } catch {
    return false
  }
}
