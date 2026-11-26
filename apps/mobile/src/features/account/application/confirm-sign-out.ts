import { useRootTabBadgeStore } from '@/app-shell/state/root-tab-badges'
import { getBookingAuthContext, sameBookingAccountToken } from '../../inspection/application/service-flow/booking-auth-context'
import { signOutBookingSession } from '../../inspection/data/service-flow/uni-booking-http-client'
import { MINE_TAB_PATH } from '../../inspection/navigation/inspection-routes'
import { revokePushDevice } from '@/features/message/application/push-registration-client'

export function confirmSignOut(): void {
  const auth = getBookingAuthContext()
  const openedBy = auth.getAccessToken()
  uni.showModal({
    title: '退出登录',
    content: '退出后需要重新登录才能查看订单、消息和会话',
    confirmText: '退出',
    cancelText: '取消',
    confirmColor: '#ff6a00',
    success: result => {
      if (!result.confirm) return
      const token = auth.getAccessToken()
      if (!sameBookingAccountToken(openedBy, token)) return
      signOutBookingSession(() => revokePushDevice(token))
      const badges = useRootTabBadgeStore()
      badges.setMessageUnread(0)
      badges.setCartCount(0)
      uni.reLaunch({ url: MINE_TAB_PATH })
    },
  })
}
