// What the scanner or the operator's typing gave, turned into a code the server takes:
// ACT-XXXX-XXXX-XXXX (活动凭证) or VEN-XXXX-XXXX-XXXX (到馆核销码). The QR code holds the bare code,
// but a link or text around it is tolerated; spaces, lower case and missing dashes are fixed.
const WITH_DASHES = /(ACT|VEN)-([0-9A-Z]{4})-([0-9A-Z]{4})-([0-9A-Z]{4})/
const WITHOUT_DASHES = /^(ACT|VEN)([0-9A-Z]{4})([0-9A-Z]{4})([0-9A-Z]{4})$/

export function parseCheckinCode(text: string | null | undefined): string | null {
  const upper = (text ?? '').toUpperCase()
  const dashed = WITH_DASHES.exec(upper)
  if (dashed) return `${dashed[1]}-${dashed[2]}-${dashed[3]}-${dashed[4]}`
  const compact = WITHOUT_DASHES.exec(upper.replace(/[\s-]+/g, ''))
  return compact ? `${compact[1]}-${compact[2]}-${compact[3]}-${compact[4]}` : null
}
