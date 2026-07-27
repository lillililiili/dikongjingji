import { INSPECTION_PORTAL_PATH } from '../../features/inspection/navigation/inspection-routes'

export type NavigateTo = (url: string) => unknown

export function navigateToInspectionPortal(navigate: NavigateTo): unknown {
  return navigate(INSPECTION_PORTAL_PATH)
}
