import { resolveContentRefs, type ContentLinkedApis, type ContentRefView } from '../../content/application/content-refs'

import type { LiveItem } from '../domain/live'

export interface LiveItemView extends ContentRefView {
  seq: number
}

/**
 * 讲解清单: each item read from its own module in the room's city (the same reading an article's
 * 内容中提到 does), in the host's order. One that cannot be read (taken down, not sold in this city,
 * or the request failed) is left out, so nothing on the list leads to a dead page.
 */
export async function resolveLiveItems(
  items: readonly LiveItem[],
  cityCode: string,
  apis: ContentLinkedApis,
): Promise<LiveItemView[]> {
  const views = await resolveContentRefs(items.map((item) => ({ type: item.type, refId: item.refId, title: item.title })),
    cityCode, apis)
  const byKey = new Map(views.map((view) => [view.key, view]))
  return items.flatMap((item) => {
    const view = byKey.get(`${item.type}:${item.refId}`)
    return view ? [{ ...view, seq: item.seq }] : []
  })
}

/** 讲解中: the current item when it could be read; otherwise none is pinned. */
export function currentLiveItem(views: readonly LiveItemView[], currentSeq: number | null): LiveItemView | null {
  if (currentSeq === null) return null
  return views.find((view) => view.seq === currentSeq) ?? null
}

/** "¥380/天 起", "按项目报价", "免费" — the price line of an item. */
export function liveItemPrice(view: Pick<ContentRefView, 'price'>): string {
  if (!view.price) return ''
  return `${view.price.amount}${view.price.unit}`
}
