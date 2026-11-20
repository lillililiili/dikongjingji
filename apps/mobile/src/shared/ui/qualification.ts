// R6-4: the green “资质已审核” tag beside a merchant's or provider's name. It only comes from the
// server's qualificationReviewed (the organization has a review time in the database), never from
// any text: a profile label that happens to read “资质已审核” is not shown for an unreviewed provider.
export const QUALIFICATION_REVIEWED_TEXT = '资质已审核'

// The tag in the slot where a provider's certification label used to be (inspection and
// professional service details, plans, the provider page, demand quotes): reviewed providers read
// “资质已审核”; the others keep their own label (such as “企业认证”), or nothing when they have none.
export function providerCredentialLabel(
  qualificationReviewed: boolean | null | undefined,
  certificationLabel: string | null | undefined,
): string | null {
  if (qualificationReviewed === true) return QUALIFICATION_REVIEWED_TEXT
  const label = certificationLabel?.trim()
  return label && label !== QUALIFICATION_REVIEWED_TEXT ? label : null
}
