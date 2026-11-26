export const AGREEMENT_DOCS = [
  'PRIVACY_POLICY',
  'USER_AGREEMENT',
  'CHILD_PRIVACY_RULES',
  'TRADE_AGREEMENT',
  'ENROLLMENT_NOTICE',
] as const
export type AgreementDoc = (typeof AGREEMENT_DOCS)[number]

export const AGREEMENT_DOCUMENTS: Record<AgreementDoc, { title: string }> = {
  PRIVACY_POLICY: { title: '隐私政策' },
  USER_AGREEMENT: { title: '用户协议' },
  CHILD_PRIVACY_RULES: { title: '儿童个人信息保护规则' },
  TRADE_AGREEMENT: { title: '平台交易协议' },
  ENROLLMENT_NOTICE: { title: '报名须知与照片使用说明' },
}

export interface PublishedAgreement {
  title: string
  version: string
  publishedOn: string
  sections: { title: string; body: string }[]
}
