import { isRootTabReturnUrl } from '../../navigation/inspection-routes'

// Board Login: “登录后继续：…” names the action the login interrupted. The page only knows the
// return address, so the action is named after that page. A root tab (home, cart, mine,
// messages) is not an interrupted action, and the board then shows no card.
const RESUME_ACTIONS: Record<string, string> = {
  '/pages/service/inspection/booking': '预约巡检服务',
  '/pages/service/inspection/booking-submitted': '查看预约进度',
  '/pages/service/inspection/plan-confirmation': '确认服务方案',
  '/pages/service/inspection/fulfillment': '查看服务履约',
  '/pages/service/inspection/delivery': '查看交付与验收',
  '/pages/mine/inspection-orders': '查看巡检订单',
  '/pages/activity/enroll': '活动报名',
  '/pages/activity/participants': '管理参与人',
  '/pages/activity/ticket': '查看报名凭证',
  '/pages/mine/activity-enrollments': '查看我的报名',
  '/pages/mall/product': '购买商品',
  '/pages/cart/checkout': '确认订单',
  '/pages/mall/inquiry': '发起询价',
  '/pages/mall/inquiry-quotes': '查看商家报价',
  '/pages/mine/addresses': '管理收货地址',
  '/pages/trade/cashier': '继续支付',
  '/pages/trade/pay-result': '查看支付结果',
  '/pages/mine/goods-orders': '查看商品订单',
  '/pages/mine/goods-order': '查看商品订单',
  '/pages/venue/book': '预约到馆体验',
  '/pages/venue/booking': '查看到馆预约',
  '/pages/mine/venue-bookings': '查看到馆预约',
  '/pages/demand/publish': '发布需求',
  '/pages/demand/quotes': '查看需求报价',
  '/pages/demand/compare': '比选服务方案',
  '/pages/mine/demands': '查看我的需求',
  '/pages/rental/inquiry': '租赁询价',
  '/pages/rental/quote': '查看租赁报价',
  '/pages/rental/order': '查看租赁订单',
  '/pages/rental/contract': '查看租赁合同',
  '/pages/mine/rental-orders': '查看租赁订单',
  '/pages/support/chat': '联系客服',
  '/pages/mine/settings': '设置',
  '/pages/mine/help': '帮助与反馈',
}

/** “预约巡检服务” for a booking return address; '' when there is nothing to continue. */
export function describeLoginResume(returnUrl: string): string {
  if (!returnUrl || isRootTabReturnUrl(returnUrl)) return ''
  const path = returnUrl.split('?')[0]
  return RESUME_ACTIONS[path] ?? '回到刚才的页面'
}
