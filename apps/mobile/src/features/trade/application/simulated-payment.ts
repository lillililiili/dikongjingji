import type { PaymentLauncherPort } from './payment-launcher'

// 现场演示用的“模拟付款”：演示包没有真实微信，由买家在收银台确认后，请后端让本机模拟微信付款
// （仅开发环境接口）。订单状态仍然只认后端收到的验签回调，这里不产生任何支付结果。
// 只有预览构建并且显式设了 VITE_SIMULATED_PAYMENT=true 才有；公开构建里这段代码被去掉。
export const SIMULATED_PAYMENT_ENABLED =
  (import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__) && import.meta.env.VITE_SIMULATED_PAYMENT === 'true'

export const SIMULATED_PAYMENT_NOTE = '演示环境：模拟微信支付，不产生真实扣款'

export function createSimulatedPaymentLauncher(options: {
  amountText: string
  confirm: (dialog: { title: string; content: string; confirmText: string; cancelText: string }) => Promise<boolean>
  pay: () => Promise<void>
}): PaymentLauncherPort {
  return {
    async launchWechatApp() {
      const confirmed = await options.confirm({
        title: '模拟微信支付',
        content: `应付 ${options.amountText}。${SIMULATED_PAYMENT_NOTE}。`,
        confirmText: '确认付款',
        cancelText: '取消',
      })
      if (!confirmed) return 'CANCELLED'
      try {
        await options.pay()
        return 'LAUNCHED'
      } catch {
        return 'FAILED'
      }
    },
  }
}
