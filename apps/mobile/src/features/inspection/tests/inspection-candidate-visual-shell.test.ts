import { describe, expect, it } from 'vitest'

import loginPageSource from '../presentation/auth/pages/InspectionLoginPage.vue?raw'
import bookingPageSource from '../presentation/booking/pages/InspectionBookingPage.vue?raw'
import submittedPageSource from '../presentation/booking/pages/InspectionBookingSubmittedPage.vue?raw'
import planPageSource from '../presentation/plan/pages/InspectionPlanConfirmationPage.vue?raw'
import fulfillmentPageSource from '../presentation/fulfillment/pages/InspectionFulfillmentPage.vue?raw'
import deliveryPageSource from '../presentation/delivery/pages/InspectionDeliveryPage.vue?raw'

describe('existing-account inspection candidate visual shell', () => {
  it('keeps the real login form inside the new branded account shell', () => {
    // 2026-11-20 board Login: the shared page head and the 登录后继续 card, no robot picture.
    expect(loginPageSource).toContain('<AppPageHeader')
    expect(loginPageSource).toContain('登录后继续：')
    expect(loginPageSource).not.toContain('inspection-login__robot')
    expect(loginPageSource).toContain('inspectionAuthApi.login(')
    expect(loginPageSource).toContain('未注册的手机号验证后自动注册')
    expect(loginPageSource).toMatch(/v-if="ACCOUNT_PREVIEW_ENABLED"[^>]*class="inspection-login__links"[\s\S]*?data-testid="login-register"/)
  })

  it('presents the existing reservation form as the confirm-order screen', () => {
    expect(bookingPageSource).toContain('预约服务')
    expect(bookingPageSource).toContain('inspection-booking__price-summary')
    expect(bookingPageSource).toContain('submitBookingReservation(')
    expect(bookingPageSource).not.toContain('<BookingProgress')
  })

  it('matches the submitted and plan-confirmation milestones', () => {
    expect(submittedPageSource).toContain("'需求已提交'")
    expect(submittedPageSource).not.toContain('booking-submitted__page-step')
    expect(submittedPageSource).toContain('booking-submitted__next-steps')
    expect(planPageSource).toContain('方案确认')
    expect(planPageSource).toContain('PlanArrangementCard')
  })

  it('matches the on-site and report-delivery milestones without changing APIs', () => {
    expect(fulfillmentPageSource).toContain('服务履约')
    expect(fulfillmentPageSource).toContain('inspectionPlanApi.getServiceOrder(')
    expect(deliveryPageSource).not.toContain('delivery__hero-copy')
    expect(deliveryPageSource).not.toContain('delivery__report-preview')
    expect(deliveryPageSource).toContain('delivery__summary')
    expect(deliveryPageSource).toContain('inspectionPlanApi.acceptDelivery')
  })
})
