import type { WechatAppCall } from '../domain/trade-order'

/**
 * Opens the WeChat payment sheet with parameters the server signed.
 *
 * What the sheet reports is never the payment result: whatever comes back, the page goes on to the
 * result page and asks the server, which only trusts WeChat's notification or its own query.
 * - LAUNCHED: the sheet said the payment went through.
 * - CANCELLED: the user closed the sheet.
 * - FAILED: the sheet could not be opened or reported an error.
 * - UNSUPPORTED: this platform cannot open WeChat Pay (H5, or a build without the payment module).
 */
export type PaymentLaunchOutcome = 'LAUNCHED' | 'CANCELLED' | 'FAILED' | 'UNSUPPORTED'

export interface PaymentLauncherPort {
  launchWechatApp(appCall: WechatAppCall): Promise<PaymentLaunchOutcome>
}
