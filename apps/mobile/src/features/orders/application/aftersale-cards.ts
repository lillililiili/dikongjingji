import {
  AFTERSALE_KIND_LABELS,
  AFTERSALE_STATUS_LABELS,
  aftersaleAmount,
  aftersaleCardLine,
  aftersaleGoods,
} from '../../trade/application/aftersale-view'
import { formatTradeAmount } from '../../trade/application/order-view'
import type { Aftersale } from '../../trade/domain/aftersale'
import { buildAftersaleApplyUrl, buildAftersaleDetailUrl } from '../../trade/navigation/aftersale-routes'
import { buildGoodsOrderUrl } from '../../trade/navigation/trade-routes'
import type { MyOrderCardAction, MyOrderCardView } from './my-order-cards'

/**
 * 一张商品售后单的卡片（画板 AftersaleList；我的订单“退款/售后”胶囊下也用它，画板 OrderList）：
 * 类型标签（退货退款橙、仅退款蓝）、商家、状态、商品图、标题、金额、规格×数量、一行说明，
 * 按钮：售后中“查看进度”（待寄回再加“填写运单”），已完成“查看详情 / 查看订单”，
 * 已关闭“查看详情”加“重新申请”（服务端说还能申请时）或“查看订单”。
 */
export function describeAftersaleCard(aftersale: Aftersale): MyOrderCardView {
  const first = aftersale.lines[0]
  const goods = aftersaleGoods(aftersale.lines)
  const status = AFTERSALE_STATUS_LABELS[aftersale.status]
  const line = aftersaleCardLine(aftersale)
  const detail = buildAftersaleDetailUrl(aftersale.aftersaleId)
  const order = buildGoodsOrderUrl(aftersale.orderId)
  const view = (label: string): MyOrderCardAction => ({ key: 'view', label, style: 'OUTLINE', target: { kind: 'URL', url: detail } })
  const actions: MyOrderCardAction[] = []
  switch (aftersale.status) {
    case 'PENDING_REVIEW':
    case 'AWAITING_RETURN':
    case 'RETURN_SHIPPED':
    case 'REFUNDING':
    case 'REFUND_FAILED':
      actions.push(view('查看进度'))
      if (aftersale.actions.canSubmitReturn) {
        actions.push({
          key: 'waybill',
          label: '填写运单',
          style: 'PRIMARY',
          target: { kind: 'URL', url: buildAftersaleDetailUrl(aftersale.aftersaleId, { openWaybill: true }) },
        })
      }
      break
    case 'REFUNDED':
      actions.push(view('查看详情'), { key: 'order', label: '查看订单', style: 'OUTLINE', target: { kind: 'URL', url: order } })
      break
    default:
      actions.push(view('查看详情'))
      actions.push(aftersale.actions.canReapply
        ? { key: 'reapply', label: '重新申请', style: 'OUTLINE', target: { kind: 'URL', url: buildAftersaleApplyUrl(aftersale.orderId) } }
        : { key: 'order', label: '查看订单', style: 'OUTLINE', target: { kind: 'URL', url: order } })
  }
  return {
    tag: { label: AFTERSALE_KIND_LABELS[aftersale.kind], tone: aftersale.kind === 'REFUND_ONLY' ? 'BLUE' : 'ORANGE' },
    head: aftersale.merchant.name,
    headMuted: true,
    status: { text: status.text, tone: status.tone },
    cover: first ? first.coverImage?.url ?? null : null,
    coverAlt: first?.productName ?? '',
    title: goods.name,
    titleTail: '',
    amount: formatTradeAmount(aftersaleAmount(aftersale)),
    lines: [
      { key: 'spec', text: goods.spec, tone: 'MUTED' },
      { key: 'note', text: line.text, tone: line.urgent ? 'ORANGE' : 'PLAIN' },
    ],
    url: detail,
    actions,
  }
}
