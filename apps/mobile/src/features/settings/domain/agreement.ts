// The five documents of 协议与规则 (board Agreement, design assumption 22). Their text is not
// written yet: the page shows the chapter titles with "[协议正文待确认]", and the version and dates
// are samples, marked as such on the page.
export const AGREEMENT_DOCS = [
  'PRIVACY_POLICY',
  'USER_AGREEMENT',
  'CHILD_PRIVACY_RULES',
  'TRADE_AGREEMENT',
  'ENROLLMENT_NOTICE',
] as const
export type AgreementDoc = (typeof AGREEMENT_DOCS)[number]

export interface AgreementDocument {
  doc: AgreementDoc
  title: string
  version: string
  updatedOn: string
  effectiveOn: string
  sections: string[]
}

const SAMPLE = { version: 'v1.0', updatedOn: '2026-10-20', effectiveOn: '2026-11-01' }

export const AGREEMENT_DOCUMENTS: Record<AgreementDoc, AgreementDocument> = {
  PRIVACY_POLICY: {
    doc: 'PRIVACY_POLICY',
    title: '隐私政策',
    ...SAMPLE,
    sections: ['一、我们收集的信息', '二、信息的使用与共享', '三、信息的存储与保护', '四、你的权利', '五、政策更新与联系方式'],
  },
  USER_AGREEMENT: {
    doc: 'USER_AGREEMENT',
    title: '用户协议',
    ...SAMPLE,
    sections: ['一、协议范围与主体', '二、账号注册与使用', '三、平台服务内容', '四、用户行为规范', '五、协议变更与联系方式'],
  },
  CHILD_PRIVACY_RULES: {
    doc: 'CHILD_PRIVACY_RULES',
    title: '儿童个人信息保护规则',
    ...SAMPLE,
    sections: ['一、适用范围与监护人同意', '二、收集的儿童信息', '三、儿童信息的使用与保护', '四、监护人的权利', '五、规则更新与联系方式'],
  },
  TRADE_AGREEMENT: {
    doc: 'TRADE_AGREEMENT',
    title: '平台交易协议',
    ...SAMPLE,
    sections: ['一、交易主体与收款方', '二、下单、付款与收货', '三、退款与售后', '四、服务订单与验收', '五、协商与平台协助'],
  },
  ENROLLMENT_NOTICE: {
    doc: 'ENROLLMENT_NOTICE',
    title: '报名须知与照片使用说明',
    ...SAMPLE,
    sections: ['一、报名与参与条件', '二、退改规则', '三、现场安全', '四、照片与视频的使用', '五、未成年人参与'],
  },
}

export const AGREEMENT_BODY_PLACEHOLDER = '[协议正文待确认]'
