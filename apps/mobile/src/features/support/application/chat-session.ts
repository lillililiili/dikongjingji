import type { SupportApi } from '../data/support-api'
import {
  SUPPORT_MESSAGE_PAGE_SIZE,
  SUPPORT_TEXT_LIMIT,
  type SupportCard,
  type SupportConversation,
  type SupportMessage,
  type SupportMessageBody,
} from '../domain/support'
import type { SupportTarget } from '../navigation/support-routes'

// A message this page sent that the server has not answered yet, or did not take. It keeps its
// idempotency key, so sending it again cannot make a second message.
export interface ChatOutgoing {
  localId: string
  key: string
  body: SupportMessageBody
  status: 'SENDING' | 'FAILED'
}

export interface ChatReady {
  kind: 'ready'
  conversation: SupportConversation
  // Oldest first, one per seq.
  messages: SupportMessage[]
  hasOlder: boolean
  nextBefore: number | null
  loadingOlder: boolean
  olderFailed: boolean
  outgoing: ChatOutgoing[]
  // The offered card has been sent in this visit; the banner goes away.
  cardSent: boolean
}

export type ChatState =
  | { kind: 'loading' }
  // The server no longer accepts the login (or there is none): the page goes to sign in.
  | { kind: 'login' }
  | { kind: 'error' }
  // No such conversation for this account.
  | { kind: 'missing' }
  // The merchant or provider cannot take conversations (gone, inactive or not a seller).
  | { kind: 'unavailable' }
  | ChatReady

export interface ChatSessionOptions {
  api: SupportApi
  getAccessToken(): string | null
  getState(): ChatState
  setState(state: ChatState): void
  createKey(): string
  /** Told after the conversation was marked read, so the message tab badge can follow. */
  onRead?(): void
}

const statusOf = (error: unknown): number | null =>
  typeof error === 'object' && error !== null && typeof (error as { status?: unknown }).status === 'number'
    ? (error as { status: number }).status
    : null

const codeOf = (error: unknown): string | null =>
  typeof error === 'object' && error !== null && typeof (error as { code?: unknown }).code === 'string'
    ? (error as { code: string }).code
    : null

/** Merges a page into what is on screen: one message per seq, oldest first; the newer copy wins. */
export function mergeSupportMessages(current: SupportMessage[], incoming: SupportMessage[]): SupportMessage[] {
  const bySeq = new Map<number, SupportMessage>()
  for (const message of current) bySeq.set(message.seq, message)
  for (const message of incoming) bySeq.set(message.seq, message)
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq)
}

/** Text the input may send: trimmed, not empty, at most 500 characters. */
export function sendableSupportText(draft: string): string | null {
  const text = draft.trim()
  return text && text.length <= SUPPORT_TEXT_LIMIT ? text : null
}

/**
 * One conversation on screen: the latest page, older pages on demand, polling for replies, sending
 * text and the offered card with a key per message, and marking read when the customer has seen the
 * counterpart's messages. Answers that arrive after the page moved to another conversation are
 * dropped.
 */
export function createChatSession(options: ChatSessionOptions) {
  let conversationId: string | null = null
  let revision = 0
  let localCounter = 0

  const ready = (): ChatReady | null => {
    const state = options.getState()
    return state.kind === 'ready' && state.conversation.conversationId === conversationId ? state : null
  }

  function fail(error: unknown): ChatState {
    const status = statusOf(error)
    if (status === 401) return { kind: 'login' }
    if (status === 404) return { kind: 'missing' }
    return { kind: 'error' }
  }

  async function markRead(token: string): Promise<void> {
    if (!conversationId) return
    const asked = conversationId
    try {
      const conversation = await options.api.markRead(token, asked)
      const current = ready()
      if (current && asked === conversationId) options.setState({ ...current, conversation })
      options.onRead?.()
    } catch {
      // The next visit marks it again; the server keeps the count.
    }
  }

  async function send(body: SupportMessageBody, existing?: ChatOutgoing): Promise<void> {
    const current = ready()
    const token = options.getAccessToken()
    if (!current || !token || !conversationId) return
    const item: ChatOutgoing = existing
      ? { ...existing, status: 'SENDING' }
      : { localId: `local-${++localCounter}`, key: options.createKey(), body, status: 'SENDING' }
    const others = current.outgoing.filter((entry) => entry.localId !== item.localId)
    options.setState({ ...current, outgoing: [...others, item], cardSent: current.cardSent || 'card' in body })
    const asked = conversationId
    try {
      const message = await options.api.sendMessage(token, asked, item.key, body)
      const latest = ready()
      if (!latest || asked !== conversationId) return
      options.setState({
        ...latest,
        messages: mergeSupportMessages(latest.messages, [message]),
        outgoing: latest.outgoing.filter((entry) => entry.localId !== item.localId),
      })
    } catch (error) {
      const latest = ready()
      if (!latest || asked !== conversationId) return
      if (statusOf(error) === 401) {
        options.setState({ kind: 'login' })
        return
      }
      // A key already used for other content cannot be retried as it is; the retry gets a new one.
      const key = codeOf(error) === 'SUPPORT_IDEMPOTENCY_KEY_REUSED' ? options.createKey() : item.key
      options.setState({
        ...latest,
        outgoing: latest.outgoing.map((entry) => (entry.localId === item.localId ? { ...entry, key, status: 'FAILED' } : entry)),
      })
    }
  }

  return {
    /** Opens (or makes) the conversation with a counterpart; null when it cannot be opened. */
    async open(target: SupportTarget): Promise<string | null> {
      const token = options.getAccessToken()
      if (!token) {
        options.setState({ kind: 'login' })
        return null
      }
      options.setState({ kind: 'loading' })
      try {
        const conversation = await options.api.openConversation(
          token,
          target.kind,
          target.kind === 'PLATFORM' ? null : target.organizationId,
        )
        return conversation.conversationId
      } catch (error) {
        const status = statusOf(error)
        if (status === 409 || status === 400) options.setState({ kind: 'unavailable' })
        else options.setState(fail(error))
        return null
      }
    },

    async load(id: string): Promise<void> {
      conversationId = id
      const requested = ++revision
      const token = options.getAccessToken()
      if (!token) {
        options.setState({ kind: 'login' })
        return
      }
      if (!ready()) options.setState({ kind: 'loading' })
      try {
        const page = await options.api.loadMessages(token, id, null, SUPPORT_MESSAGE_PAGE_SIZE)
        if (requested !== revision) return
        const current = ready()
        options.setState({
          kind: 'ready',
          conversation: page.conversation,
          messages: current ? mergeSupportMessages(current.messages, page.items) : page.items,
          hasOlder: current ? current.hasOlder : page.hasMore,
          nextBefore: current ? current.nextBefore : page.nextBefore,
          loadingOlder: false,
          olderFailed: false,
          outgoing: current?.outgoing ?? [],
          cardSent: current?.cardSent ?? false,
        })
        // Coming in is reading: the counterpart's unread and the 客服消息 notification clear.
        await markRead(token)
      } catch (error) {
        if (requested !== revision) return
        options.setState(fail(error))
      }
    },

    /** Polling: the newest page again. A reply that came in is marked read at once. */
    async refresh(): Promise<void> {
      const current = ready()
      const token = options.getAccessToken()
      if (!current || !token || !conversationId) return
      const requested = revision
      const asked = conversationId
      try {
        const page = await options.api.loadMessages(token, asked, null, SUPPORT_MESSAGE_PAGE_SIZE)
        const latest = ready()
        if (requested !== revision || !latest || asked !== conversationId) return
        const newest = latest.messages.length ? latest.messages[latest.messages.length - 1].seq : 0
        // More came in than one page holds: start again from this page rather than leave a hole.
        const gap = page.items.length > 0 && page.items[0].seq > newest + 1 && page.hasMore
        const arrived = page.items.some((message) => message.seq > newest && !message.mine)
        options.setState({
          ...latest,
          conversation: page.conversation,
          messages: gap ? page.items : mergeSupportMessages(latest.messages, page.items),
          hasOlder: gap ? page.hasMore : latest.hasOlder,
          nextBefore: gap ? page.nextBefore : latest.nextBefore,
        })
        if (arrived || page.conversation.unreadCount > 0) await markRead(token)
      } catch (error) {
        if (statusOf(error) === 401) options.setState({ kind: 'login' })
        // Otherwise the next poll tries again; what is on screen stays.
      }
    },

    async loadOlder(): Promise<void> {
      const current = ready()
      const token = options.getAccessToken()
      if (!current || !token || !current.hasOlder || current.nextBefore === null || current.loadingOlder) return
      const requested = revision
      const asked = conversationId
      options.setState({ ...current, loadingOlder: true, olderFailed: false })
      try {
        const page = await options.api.loadMessages(token, asked as string, current.nextBefore, SUPPORT_MESSAGE_PAGE_SIZE)
        const latest = ready()
        if (requested !== revision || !latest || asked !== conversationId) return
        options.setState({
          ...latest,
          messages: mergeSupportMessages(page.items, latest.messages),
          hasOlder: page.hasMore,
          nextBefore: page.nextBefore,
          loadingOlder: false,
        })
      } catch (error) {
        const latest = ready()
        if (requested !== revision || !latest) return
        options.setState(statusOf(error) === 401 ? { kind: 'login' } : { ...latest, loadingOlder: false, olderFailed: true })
      }
    },

    /** Sends the draft; false when there is nothing that may be sent. */
    sendText(draft: string): boolean {
      const text = sendableSupportText(draft)
      if (!text || !ready()) return false
      void send({ text })
      return true
    },

    sendCard(card: SupportCard): void {
      const current = ready()
      if (!current || current.cardSent) return
      void send({ card })
    },

    retry(localId: string): void {
      const item = ready()?.outgoing.find((entry) => entry.localId === localId)
      if (item && item.status === 'FAILED') void send(item.body, item)
    },
  }
}
