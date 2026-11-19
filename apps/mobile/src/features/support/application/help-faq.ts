// 常见问题 of 帮助与反馈 (board Help). Only rules that are built and written in the plan (§19–§25)
// are answered here; nothing promises a deadline the platform has not set.
export interface HelpQuestion {
  id: string
  question: string
  answer: string
}

export interface HelpCategory {
  id: 'PAYMENT' | 'ACTIVITY' | 'DEMAND' | 'RENTAL'
  label: string
  questions: HelpQuestion[]
}

export const HELP_CATEGORIES: HelpCategory[] = [
  {
    id: 'PAYMENT',
    label: '订单与支付',
    questions: [
      {
        id: 'pay-channel',
        question: '可以用哪些方式付款？',
        answer: '目前只支持微信支付。钱直接付给卖出商品或提供服务的商家，平台不经手。',
      },
      {
        id: 'pay-merchants',
        question: '不同商家的商品能一起付款吗？',
        answer: '不能。购物车每次只结算一个商家，不同商家分别下单付款。',
      },
      {
        id: 'pay-deadline',
        question: '待付款订单要在多久内支付？',
        answer: '商城和租赁订单 30 分钟，活动报名和到馆预约 15 分钟。超时未付，订单自动关闭。',
      },
      {
        id: 'pay-refund',
        question: '退款退到哪里？',
        answer: '原路退回付款的微信账户。商城订单在商家发货前可以自己取消，全额退款。',
      },
      {
        id: 'pay-receipt',
        question: '收到货后要做什么？',
        answer: '在订单详情点“确认收货”。发货 10 天后还没确认的，系统自动确认收货。',
      },
    ],
  },
  {
    id: 'ACTIVITY',
    label: '活动与到馆',
    questions: [
      {
        id: 'act-cancel',
        question: '收费活动报名后能退吗？',
        answer: '场次开始前可以取消其中几位，每位按报名时的单价原路退款；已签到的不能退。',
      },
      {
        id: 'venue-change',
        question: '到馆预约能改期或取消吗？',
        answer: '开场前可以改到同一项目的另一场次（旧码作废、换新码），也可以取消，整单原路全额退款。',
      },
      {
        id: 'venue-noshow',
        question: '没去到馆会退款吗？',
        answer: '场次结束后预约变为“已过期”，不自动退款。',
      },
      {
        id: 'venue-code',
        question: '到馆核销码在哪里看？',
        answer: '在“我的”点“到馆预约”打开预约，到馆时出示核销码。',
      },
    ],
  },
  {
    id: 'DEMAND',
    label: '需求与报价',
    questions: [
      {
        id: 'demand-fee',
        question: '发布需求收费吗？',
        answer: '不收费。提交需求不产生费用。',
      },
      {
        id: 'demand-window',
        question: '多久能收到报价？',
        answer: '提交后 72 小时内收服务商报价；期间没有服务商报价，需求自动关闭。',
      },
      {
        id: 'demand-choose',
        question: '怎么选定报价？',
        answer: '在报价有效期内比选后选定一家；选定后不能再选别家，需求也不能再取消。',
      },
      {
        id: 'inquiry-goods',
        question: '询价商品怎么买？',
        answer: '提交询价单后 72 小时内收商家报价，在报价有效期内选定一家，按报价下单付款。',
      },
    ],
  },
  {
    id: 'RENTAL',
    label: '设备租赁',
    questions: [
      {
        id: 'rental-deposit',
        question: '押金怎么交？',
        answer: '押金不在线收取，按合同和出租方线下约定。',
      },
      {
        id: 'rental-pay',
        question: '租金怎么付？',
        answer: '确认合同后一次付清租金。30 分钟内没有付款，订单关闭，需要重新询价。',
      },
      {
        id: 'rental-cancel',
        question: '付款后能取消吗？',
        answer: '付款后不能自己取消，退改按合同约定，请联系商家。交接前商家取消订单的，全额原路退款。',
      },
      {
        id: 'rental-start',
        question: '从什么时候开始计租？',
        answer: '从你确认交接起计租。',
      },
    ],
  },
]
