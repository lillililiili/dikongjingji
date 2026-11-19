import { buildSupportOpenUrl, type SupportCardHint, type SupportTarget } from '../navigation/support-routes'

/**
 * 客服 / 咨询 / 联系商家 / 联系服务商 on any page (preview builds): the conversation with that
 * counterpart, offering a card about what the customer is looking at. The chat asks for a login
 * itself, opens (or makes) the conversation and sends the card only when the customer taps 发送.
 */
export function openSupportChat(target: SupportTarget, card: SupportCardHint | null = null): void {
  uni.navigateTo({ url: buildSupportOpenUrl(target, card) })
}
