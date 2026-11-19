/**
 * Several server-paged lists shown as one list, newest first (for example 需求与报价, which shows
 * demands and rental inquiries together, or a tab that covers two statuses). Every source is sorted
 * by the same time, newest first, so an item may be shown once no source that still has pages can
 * hold anything newer: its time is not older than the last loaded item of every such source.
 */
export interface MergedSource<T> {
  items: T[]
  hasMore: boolean
  // The last page loaded; 0 before the first one.
  page: number
}

export interface MergedKeys<T> {
  idOf(item: T): string
  // An ISO instant; newer first.
  timeOf(item: T): string
}

function timeValue(iso: string): number {
  const value = Date.parse(iso)
  return Number.isNaN(value) ? 0 : value
}

function lastTime<T>(source: MergedSource<T>, keys: MergedKeys<T>): number {
  const last = source.items[source.items.length - 1]
  return last ? timeValue(keys.timeOf(last)) : Number.POSITIVE_INFINITY
}

// The time below which an item could still be preceded by one not loaded yet.
function threshold<T>(sources: MergedSource<T>[], keys: MergedKeys<T>): number {
  return sources
    .filter((source) => source.hasMore)
    .reduce((highest, source) => Math.max(highest, lastTime(source, keys)), Number.NEGATIVE_INFINITY)
}

export function mergedItems<T>(sources: MergedSource<T>[], keys: MergedKeys<T>): T[] {
  const seen = new Set<string>()
  const all: T[] = []
  for (const source of sources) {
    for (const item of source.items) {
      const id = keys.idOf(item)
      if (seen.has(id)) continue
      seen.add(id)
      all.push(item)
    }
  }
  const limit = threshold(sources, keys)
  return all
    .filter((item) => timeValue(keys.timeOf(item)) >= limit)
    .sort((left, right) => {
      const difference = timeValue(keys.timeOf(right)) - timeValue(keys.timeOf(left))
      return difference !== 0 ? difference : keys.idOf(right).localeCompare(keys.idOf(left))
    })
}

export function mergedHasMore<T>(sources: MergedSource<T>[]): boolean {
  return sources.some((source) => source.hasMore)
}

// The source whose next page decides what can be shown next: the one with more pages whose last
// loaded item is the newest. -1 when every source is complete.
export function nextMergedSource<T>(sources: MergedSource<T>[], keys: MergedKeys<T>): number {
  let chosen = -1
  let chosenTime = Number.NEGATIVE_INFINITY
  sources.forEach((source, index) => {
    if (!source.hasMore) return
    const time = lastTime(source, keys)
    if (chosen === -1 || time > chosenTime) {
      chosen = index
      chosenTime = time
    }
  })
  return chosen
}

/**
 * Loads pages until at least one more item can be shown (or nothing is left). `load` fetches page
 * `page` of source `index`. Returns the new sources; the caller keeps them.
 */
export async function loadMoreMerged<T>(
  sources: MergedSource<T>[],
  keys: MergedKeys<T>,
  load: (index: number, page: number) => Promise<{ items: T[]; hasMore: boolean }>,
  maxPages = 6,
): Promise<MergedSource<T>[]> {
  let current = sources
  const before = mergedItems(current, keys).length
  for (let fetched = 0; fetched < maxPages; fetched += 1) {
    const index = nextMergedSource(current, keys)
    if (index === -1) break
    const source = current[index]
    const next = await load(index, source.page + 1)
    current = current.map((item, position) => (position === index
      ? { items: [...item.items, ...next.items], hasMore: next.hasMore && next.items.length > 0, page: item.page + 1 }
      : item))
    if (mergedItems(current, keys).length > before) break
  }
  return current
}
