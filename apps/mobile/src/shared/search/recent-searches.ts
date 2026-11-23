// 最近搜索 of boards SearchPre and OrderSearch: kept on this device only, never sent anywhere,
// at most six, newest first, each word once. Clearing removes them all. Every storage call may fail
// (private mode, a full or blocked store); the list then simply starts empty.

export const MAX_RECENT_SEARCHES = 6

export interface SearchWordStorage {
  get(key: string): unknown
  set(key: string, value: string): void
  remove(key: string): void
}

export interface RecentSearches {
  load(): string[]
  // Returns the list after adding the word.
  add(word: string): string[]
  clear(): void
}

function parse(value: unknown): string[] {
  if (typeof value !== 'string') return []
  try {
    const parsed: unknown = JSON.parse(value)
    return Array.isArray(parsed)
      ? parsed.filter((item): item is string => typeof item === 'string' && item.trim() !== '').map((item) => item.trim())
      : []
  } catch {
    return []
  }
}

function unique(words: string[]): string[] {
  return words.filter((word, index) => words.indexOf(word) === index).slice(0, MAX_RECENT_SEARCHES)
}

export function createRecentSearches(storage: SearchWordStorage, key: string): RecentSearches {
  return {
    load() {
      try {
        return unique(parse(storage.get(key)))
      } catch {
        return []
      }
    },
    add(word) {
      const trimmed = word.trim()
      const next = unique([trimmed, ...this.load()].filter(Boolean))
      try {
        storage.set(key, JSON.stringify(next))
      } catch {
        // Only this device's convenience.
      }
      return next
    },
    clear() {
      try {
        storage.remove(key)
      } catch {
        // Nothing to clear then.
      }
    },
  }
}

export const uniSearchWordStorage: SearchWordStorage = {
  get: (key) => uni.getStorageSync(key),
  set: (key, value) => uni.setStorageSync(key, value),
  remove: (key) => uni.removeStorageSync(key),
}

export const GLOBAL_RECENT_SEARCH_KEY = 'search.recent-words'
export const ORDER_RECENT_SEARCH_KEY = 'orders.recent-search-words'
