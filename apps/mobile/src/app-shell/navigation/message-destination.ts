import { buildActivityOrderUrl, buildActivityTicketUrl } from '../../features/activity/navigation/activity-routes'
import { buildDemandQuotesUrl } from '../../features/demand/navigation/demand-routes'
import { buildVenueBookingUrl } from '../../features/experience/navigation/experience-routes'
import {
  buildInspectionDeliveryUrl,
  buildInspectionFulfillmentUrl,
  buildInspectionPlanConfirmationUrl,
} from '../../features/inspection/navigation/inspection-routes'
import type { MessageTarget } from '../../features/message/domain/message'
import { buildSupportChatUrl } from '../../features/support/navigation/support-routes'
import { buildGoodsOrderUrl } from '../../features/trade/navigation/trade-routes'

// A notification only carries stable ids; the shell turns its target into a route, so the message
// feature never imports another business module, and the page it opens reads the facts itself.
// null: the notification names no page this version knows (a later contract's target) and opens nothing.
export function messageTargetUrl(target: MessageTarget | null): string | null {
  if (!target) return null
  const { type, id, subId } = target
  switch (type) {
    // Someone replied in a conversation: the chat reads its messages and marks them read.
    case 'CONVERSATION': return buildSupportChatUrl(id)
    // A quote came in, or the demand closed without any: its quotes page says which.
    case 'DEMAND': return buildDemandQuotesUrl(id)
    case 'INSPECTION_PLAN': return buildInspectionPlanConfirmationUrl(id)
    case 'INSPECTION_DELIVERY': return subId ? buildInspectionDeliveryUrl(id, subId) : buildInspectionFulfillmentUrl(id)
    case 'INSPECTION_FULFILLMENT': return buildInspectionFulfillmentUrl(id)
    // R6-5 交易物流: shipped, ready for pickup, refunded — the goods order shows which.
    case 'TRADE_ORDER': return buildGoodsOrderUrl(id)
    // R6-5 活动提醒: a sign-up's tickets, a paid sign-up's order (refunds), a venue booking.
    case 'ACTIVITY_ENROLLMENT': return buildActivityTicketUrl(id)
    case 'ACTIVITY_ORDER': return buildActivityOrderUrl(id)
    case 'VENUE_BOOKING': return buildVenueBookingUrl(id)
  }
}
