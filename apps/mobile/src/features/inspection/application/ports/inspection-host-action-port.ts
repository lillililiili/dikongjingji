import type { RootTabCode } from '@/app-shell/navigation/root-tabs'

export type ActionResult =
  | { status: 'OPENED' }
  | { status: 'UNAVAILABLE'; message: string }
  | { status: 'LOGIN_REQUIRED' }
  | { status: 'FORBIDDEN'; message: string }

export interface InspectionRequestInput {
  cityCode: string
  categoryCode?: string
  serviceId?: string
}

export interface InspectionHostActionPort {
  openCitySelector(): Promise<ActionResult>
  openMessageCenter(): Promise<ActionResult>
  openCustomerService(): Promise<ActionResult>
  openInspectionServiceDetail(serviceId: string): Promise<ActionResult>
  startInspectionRequest(input: InspectionRequestInput): Promise<ActionResult>
  openRootTab(tab: RootTabCode): Promise<ActionResult>
}

