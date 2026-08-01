import { describe, expect, it } from 'vitest'

import loginPageSource from '../presentation/auth/pages/InspectionLoginPage.vue?raw'
import bookingPageSource from '../presentation/booking/pages/InspectionBookingPage.vue?raw'
import submittedPageSource from '../presentation/booking/pages/InspectionBookingSubmittedPage.vue?raw'
import planPageSource from '../presentation/plan/pages/InspectionPlanConfirmationPage.vue?raw'
import fulfillmentPageSource from '../presentation/fulfillment/pages/InspectionFulfillmentPage.vue?raw'
import deliveryPageSource from '../presentation/delivery/pages/InspectionDeliveryPage.vue?raw'

describe('existing-account inspection candidate visual shell', () => {
  it('keeps the real login form inside the new branded account shell', () => {
    expect(loginPageSource).toContain('inspection-login__skyline')
    expect(loginPageSource).toContain('inspection-login__robot')
    expect(loginPageSource).toContain('inspectionAuthApi.login(')
    expect(loginPageSource).not.toContain('注册')
  })

  it('presents the existing reservation form as the confirm-order screen', () => {
    expect(bookingPageSource).toContain('确认订单')
    expect(bookingPageSource).toContain('inspection-booking__price-summary')
    expect(bookingPageSource).toContain('submitBookingReservation(')
    expect(bookingPageSource).not.toContain('<BookingProgress')
  })

  it('matches the submitted and plan-confirmation milestones', () => {
    expect(submittedPageSource).toContain('订单已提交')
    expect(submittedPageSource).toContain('booking-submitted__page-step')
    expect(submittedPageSource).toContain('booking-submitted__next-steps')
    expect(planPageSource).toContain('巡检方案已生成')
    expect(planPageSource).toContain('plan-confirmation__route-preview')
    expect(planPageSource).toContain('plan-confirmation__arrangement')
  })

  it('matches the on-site and report-delivery milestones without changing APIs', () => {
    expect(fulfillmentPageSource).toContain('fulfillment__hero')
    expect(fulfillmentPageSource).toContain('作业进度')
    expect(fulfillmentPageSource).toContain('inspectionPlanApi.getServiceOrder(')
    expect(deliveryPageSource).toContain('巡检报告已送达')
    expect(deliveryPageSource).toContain('delivery__report-preview')
    expect(deliveryPageSource).toContain('成果文件')
    expect(deliveryPageSource).toContain('inspectionPlanApi.acceptDelivery')
  })
})
