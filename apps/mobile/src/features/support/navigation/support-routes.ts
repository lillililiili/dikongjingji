import {
  SUPPORT_CARD_TEXT_LIMIT,
  SUPPORT_CARD_TYPES,
  type SupportCard,
  type SupportCardType,
  type SupportCounterpartKind,
} from '../domain/support'

export const SUPPORT_CHAT_PATH = '/pages/support/chat'
export const SUPPORT_HELP_PATH = '/pages/mine/help'

// Who to talk to. The platform support has no organization; a merchant or a provider is the
// organization that sells, rents, quotes or serves.
export type SupportTarget =
  | { kind: 'PLATFORM' }
  | { kind: 'MERCHANT' | 'PROVIDER'; organizationId: string }

// A "关于什么" card the chat offers to send. Its words are only a hint for the banner: the page that
// opens from the card reads the facts again by type and id.
export type SupportCardHint = SupportCard

export type SupportChatQuery =
  | { conversationId: string; target: null; card: SupportCardHint | null }
  | { conversationId: null; target: SupportTarget; card: SupportCardHint | null }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const isUuid = (value: string): boolean => UUID.test(value)

// One line, at most 60 characters, as the server takes it.
export function toCardLine(value: string | null | undefined): string {
  return (value ?? '').replace(/[\r\n\t]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, SUPPORT_CARD_TEXT_LIMIT)
}

function cardParameters(card: SupportCardHint | null | undefined): string[] {
  if (!card) return []
  const title = toCardLine(card.title)
  if (!title || !isUuid(card.refId)) return []
  const subtitle = toCardLine(card.subtitle)
  return [
    `cardType=${card.type}`,
    `cardId=${encodeURIComponent(card.refId)}`,
    `cardTitle=${encodeURIComponent(title)}`,
    ...(subtitle ? [`cardSubtitle=${encodeURIComponent(subtitle)}`] : []),
  ]
}

export function buildSupportChatUrl(conversationId: string, card?: SupportCardHint | null): string {
  return `${SUPPORT_CHAT_PATH}?${[`conversationId=${encodeURIComponent(conversationId)}`, ...cardParameters(card)].join('&')}`
}

// Opens (or makes) the conversation with this counterpart first; the chat page then replaces its
// own address with the conversation's id.
export function buildSupportOpenUrl(target: SupportTarget, card?: SupportCardHint | null): string {
  const who = target.kind === 'PLATFORM'
    ? ['kind=PLATFORM']
    : [`kind=${target.kind}`, `organizationId=${encodeURIComponent(target.organizationId)}`]
  return `${SUPPORT_CHAT_PATH}?${[...who, ...cardParameters(card)].join('&')}`
}

const read = (query: Record<string, unknown> | undefined, key: string): string => {
  const value = query?.[key]
  if (typeof value !== 'string') return ''
  try {
    return decodeURIComponent(value).trim()
  } catch {
    return value.trim()
  }
}

function parseCard(query: Record<string, unknown> | undefined): SupportCardHint | null {
  const type = read(query, 'cardType')
  const refId = read(query, 'cardId')
  const title = toCardLine(read(query, 'cardTitle'))
  if (!(SUPPORT_CARD_TYPES as readonly string[]).includes(type) || !isUuid(refId) || !title) return null
  return { type: type as SupportCardType, refId, title, subtitle: toCardLine(read(query, 'cardSubtitle')) || null }
}

export function parseSupportChatQuery(query: Record<string, unknown> | undefined): SupportChatQuery | null {
  const card = parseCard(query)
  const conversationId = read(query, 'conversationId')
  if (conversationId) return isUuid(conversationId) ? { conversationId, target: null, card } : null
  const kind = read(query, 'kind') as SupportCounterpartKind
  if (kind === 'PLATFORM') return { conversationId: null, target: { kind }, card }
  const organizationId = read(query, 'organizationId')
  if ((kind === 'MERCHANT' || kind === 'PROVIDER') && isUuid(organizationId)) {
    return { conversationId: null, target: { kind, organizationId }, card }
  }
  return null
}

export function buildSupportHelpUrl(): string {
  return SUPPORT_HELP_PATH
}
