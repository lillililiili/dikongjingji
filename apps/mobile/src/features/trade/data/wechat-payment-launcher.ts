import type { PaymentLaunchOutcome, PaymentLauncherPort } from '../application/payment-launcher'
import type { WechatAppCall } from '../domain/trade-order'

// The `orderInfo` that uni.requestPayment takes for WeChat Pay on Android and iOS
// (uniapp.dcloud.net.cn/api/plugins/payment.html): an object with all-lowercase keys and the
// timestamp in seconds as a number. The values are the server's, untouched; the signature covers
// the digits of the timestamp, so turning the string into a number does not change it.
export interface WechatAppOrderInfo {
  appid: string
  partnerid: string
  prepayid: string
  package: string
  noncestr: string
  timestamp: number
  sign: string
}

export function toWechatAppOrderInfo(appCall: WechatAppCall): WechatAppOrderInfo {
  return {
    appid: appCall.appId,
    partnerid: appCall.partnerId,
    prepayid: appCall.prepayId,
    package: appCall.packageValue,
    noncestr: appCall.nonceStr,
    timestamp: Number(appCall.timeStamp),
    sign: appCall.sign,
  }
}

type RequestPayment = (options: {
  provider: 'wxpay'
  orderInfo: WechatAppOrderInfo
  success: () => void
  fail: (error: { errMsg?: string }) => void
}) => void

export function createWechatPaymentLauncher(options: {
  // Only the App (Android, iOS) can open the WeChat SDK; H5 and mini programs cannot use this path.
  isApp: boolean
  requestPayment: RequestPayment | null
}): PaymentLauncherPort {
  return {
    launchWechatApp(appCall) {
      const requestPayment = options.requestPayment
      if (!options.isApp || !requestPayment) return Promise.resolve('UNSUPPORTED')
      return new Promise<PaymentLaunchOutcome>((resolve) => {
        try {
          requestPayment({
            provider: 'wxpay',
            orderInfo: toWechatAppOrderInfo(appCall),
            success: () => resolve('LAUNCHED'),
            // uni-app does not document a code for "user cancelled"; its errMsg says cancel.
            fail: (error) => resolve(/cancel/i.test(error?.errMsg ?? '') ? 'CANCELLED' : 'FAILED'),
          })
        } catch {
          resolve('FAILED')
        }
      })
    },
  }
}

function isAppPlatform(): boolean {
  let app = false
  // #ifdef APP-PLUS
  app = true
  // #endif
  return app
}

export const wechatPaymentLauncher = createWechatPaymentLauncher({
  isApp: isAppPlatform(),
  requestPayment: typeof uni !== 'undefined' && typeof uni.requestPayment === 'function'
    ? (options) => uni.requestPayment(options as unknown as UniApp.RequestPaymentOptions)
    : null,
})
