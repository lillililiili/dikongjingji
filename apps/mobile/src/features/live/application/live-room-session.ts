import type { LiveComment, LiveCommentPage } from '../domain/live'
import { LIVE_LIKES_PER_REQUEST } from '../domain/live'

// ---- 弹幕 ----

export interface CommentFeed {
  // The newest comment number seen; the next poll asks for the ones after it. null before the first.
  cursor: number | null
  items: LiveComment[]
}

export const COMMENTS_KEPT = 30

export function emptyCommentFeed(): CommentFeed {
  return { cursor: null, items: [] }
}

/**
 * Adds a polled page: one copy per comment number, in number order, without the ones hidden since
 * (hiddenSeqs), keeping the newest few. A page for an older cursor never moves the cursor back.
 */
export function mergeCommentPage(feed: CommentFeed, page: LiveCommentPage, kept: number = COMMENTS_KEPT): CommentFeed {
  const hidden = new Set(page.hiddenSeqs)
  const bySeq = new Map<number, LiveComment>()
  for (const comment of [...feed.items, ...page.items]) {
    if (!hidden.has(comment.seq)) bySeq.set(comment.seq, comment)
  }
  const items = [...bySeq.values()].sort((left, right) => left.seq - right.seq).slice(-kept)
  return { cursor: Math.max(feed.cursor ?? 0, page.cursor), items }
}

/** The viewer's own comment shows at once; the next poll brings the same number and keeps one copy. */
export function addOwnComment(feed: CommentFeed, comment: LiveComment, kept: number = COMMENTS_KEPT): CommentFeed {
  return mergeCommentPage(feed, { items: [comment], cursor: feed.cursor ?? 0, hiddenSeqs: [] }, kept)
}

/** A comment as the server takes it: one line, 1–50 characters after trimming; null when not sendable. */
export function sendableComment(draft: string): string | null {
  const text = draft.replace(/[\r\n]+/g, ' ').trim()
  if (!text || Array.from(text).length > 50) return null
  return text
}

// ---- 点赞 ----

export interface LikeBatcher {
  tap(): void
  // Sends what is waiting now (leaving the room); resolves when it is done.
  flush(): Promise<void>
  pending(): number
}

/**
 * Taps are merged and sent at most once per `delayMs` (the server merges them by a 10-second
 * window as well). A batch never carries more than the server takes in one request; a failed
 * batch is dropped rather than retried, so a tap never counts twice.
 */
export function createLikeBatcher(options: {
  send(count: number): Promise<void>
  delayMs?: number
  setTimer?: (run: () => void, ms: number) => unknown
  clearTimer?: (timer: unknown) => void
}): LikeBatcher {
  const delayMs = options.delayMs ?? 1500
  const setTimer = options.setTimer ?? ((run, ms) => setTimeout(run, ms))
  const clearTimer = options.clearTimer ?? ((timer) => clearTimeout(timer as ReturnType<typeof setTimeout>))
  let waiting = 0
  let timer: unknown = null

  async function flush(): Promise<void> {
    if (timer !== null) {
      clearTimer(timer)
      timer = null
    }
    while (waiting > 0) {
      const batch = Math.min(waiting, LIVE_LIKES_PER_REQUEST)
      waiting -= batch
      try {
        await options.send(batch)
      } catch {
        // Dropped: likes are a gesture, not a record.
      }
    }
  }

  return {
    tap() {
      waiting += 1
      if (waiting >= LIVE_LIKES_PER_REQUEST) {
        void flush()
        return
      }
      if (timer === null) {
        timer = setTimer(() => {
          timer = null
          void flush()
        }, delayMs)
      }
    },
    flush,
    pending: () => waiting,
  }
}

// ---- 本机标识与幂等键 ----

export function createLiveKey(random: () => number = Math.random): string {
  const hex = Array.from({ length: 32 }, () => Math.floor(random() * 16).toString(16))
  hex[12] = '4'
  hex[16] = (8 + Math.floor(random() * 4)).toString(16)
  const text = hex.join('')
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`
}

const DEVICE_KEY = 'live.deviceId'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * The random id this App keeps for counting viewers once per device (the server's 观看人数).
 * It is not a hardware id and says nothing about the person.
 */
export function liveDeviceId(storage: { get(key: string): unknown; set(key: string, value: string): void } = uniStorage()): string {
  const saved = storage.get(DEVICE_KEY)
  if (typeof saved === 'string' && UUID.test(saved)) return saved
  const created = createLiveKey()
  try {
    storage.set(DEVICE_KEY, created)
  } catch {
    // Without storage the id lives for this visit only; the count stays approximate.
  }
  return created
}

function uniStorage() {
  return {
    get: (key: string): unknown => {
      try {
        return uni.getStorageSync(key)
      } catch {
        return null
      }
    },
    set: (key: string, value: string) => uni.setStorageSync(key, value),
  }
}
