import { describe, expect, it } from 'vitest'

import {
  EMPTY_ACTIVITY_LIST,
  createActivityListLoader,
  type ActivityListState,
} from '../application/activity-list-loader'
import {
  isActivitySessionSelectable,
  resolveActivityIntent,
  resolveActivityPrimaryAction,
} from '../application/activity-intent'
import type { ActivityCard, ActivityListQuery, ActivityPage } from '../domain/activity'

const QUERY: ActivityListQuery = {
  cityCode: '370500',
  type: null,
  date: null,
  enrollingOnly: false,
  freeOnly: false,
}

function card(id: string): ActivityCard {
  return {
    activityId: id,
    type: 'CONTEST',
    title: id,
    coverImage: null,
    venueName: '体验馆',
    scheduleNote: null,
    price: { mode: 'FREE', amountMinor: null },
    ageRange: { minAge: null, maxAge: null },
    enrollmentStatus: 'OPEN',
    nextSession: null,
  }
}

function page(ids: string[], hasMore: boolean, pageNumber = 1): ActivityPage {
  return { page: pageNumber, size: 2, total: 5, hasMore, items: ids.map(card) }
}

interface Deferred<T> {
  promise: Promise<T>
  resolve(value: T): void
  reject(error: unknown): void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function setup(loadPage: (query: ActivityListQuery, page: number, size: number) => Promise<ActivityPage>) {
  let state: ActivityListState = EMPTY_ACTIVITY_LIST
  const loader = createActivityListLoader({
    loadPage,
    getState: () => state,
    setState: (next) => { state = next },
    pageSize: 2,
  })
  return { loader, state: () => state }
}

describe('activity list loader', () => {
  it('loads the first page and then appends the next one', async () => {
    const calls: number[] = []
    const { loader, state } = setup(async (_query, pageNumber) => {
      calls.push(pageNumber)
      return pageNumber === 1 ? page(['a', 'b'], true) : page(['c'], false, 2)
    })

    await loader.reload(QUERY)
    expect(state()).toMatchObject({ status: 'ready', hasMore: true })
    expect(state().items.map((item) => item.activityId)).toEqual(['a', 'b'])

    await loader.loadMore()
    expect(state().items.map((item) => item.activityId)).toEqual(['a', 'b', 'c'])
    expect(state().hasMore).toBe(false)

    await loader.loadMore()
    expect(calls).toEqual([1, 2])
  })

  it('keeps one copy when an activity shows up again on the next page', async () => {
    const { loader, state } = setup(async (_query, pageNumber) =>
      pageNumber === 1 ? page(['a', 'b'], true) : page(['b', 'c'], false, 2))

    await loader.reload(QUERY)
    await loader.loadMore()

    expect(state().items.map((item) => item.activityId)).toEqual(['a', 'b', 'c'])
  })

  it('lets only the latest reload write the list', async () => {
    const first = deferred<ActivityPage>()
    const second = deferred<ActivityPage>()
    const pending = [first, second]
    const { loader, state } = setup(() => pending.shift()!.promise)

    const slow = loader.reload(QUERY)
    const fast = loader.reload({ ...QUERY, freeOnly: true })
    second.resolve(page(['free'], false))
    await fast
    first.resolve(page(['stale'], false))
    await slow

    expect(state().items.map((item) => item.activityId)).toEqual(['free'])
  })

  it('drops a next page that returns after the filters changed', async () => {
    const more = deferred<ActivityPage>()
    let call = 0
    const { loader, state } = setup(async () => {
      call += 1
      if (call === 1) return page(['a', 'b'], true)
      if (call === 2) return more.promise
      return page(['x'], false)
    })

    await loader.reload(QUERY)
    const loadingMore = loader.loadMore()
    await loader.reload({ ...QUERY, type: 'TRAINING' })
    more.resolve(page(['c'], false, 2))
    await loadingMore

    expect(state().items.map((item) => item.activityId)).toEqual(['x'])
  })

  it('does not start a second next-page request while one is running', async () => {
    const more = deferred<ActivityPage>()
    let calls = 0
    const { loader, state } = setup(async (_query, pageNumber) => {
      calls += 1
      return pageNumber === 1 ? page(['a', 'b'], true) : more.promise
    })

    await loader.reload(QUERY)
    const running = loader.loadMore()
    expect(state().loadingMore).toBe(true)
    await loader.loadMore()
    more.resolve(page(['c'], false, 2))
    await running

    expect(calls).toBe(2)
  })

  it('reports a failed first page as an error and a failed next page without losing the list', async () => {
    let call = 0
    const { loader, state } = setup(async () => {
      call += 1
      if (call === 1) throw new Error('offline')
      if (call === 2) return page(['a', 'b'], true)
      if (call === 3) throw new Error('offline')
      return page(['c'], false, 2)
    })

    await loader.reload(QUERY)
    expect(state()).toMatchObject({ status: 'error', items: [] })

    await loader.reload(QUERY)
    await loader.loadMore()
    expect(state()).toMatchObject({ status: 'ready', loadMoreFailed: true, loadingMore: false, hasMore: true })
    expect(state().items).toHaveLength(2)

    // Retrying asks for the same page again.
    await loader.loadMore()
    expect(state().items.map((item) => item.activityId)).toEqual(['a', 'b', 'c'])
    expect(state().loadMoreFailed).toBe(false)
  })
})

describe('activity primary action', () => {
  it('asks for a session first, then follows the chosen session', () => {
    expect(resolveActivityPrimaryAction('OPEN', null)).toMatchObject({ label: '选择场次', enabled: false })
    expect(resolveActivityPrimaryAction('OPEN', 'OPEN')).toMatchObject({ kind: 'ENROLL', label: '立即报名', enabled: true })
    expect(resolveActivityPrimaryAction('OPEN', 'FULL_WAITLIST')).toMatchObject({ kind: 'JOIN_WAITLIST', label: '加入候补', enabled: true })
    expect(resolveActivityPrimaryAction('OPEN', 'FULL')).toMatchObject({ label: '本场已满', enabled: false })
    expect(resolveActivityPrimaryAction('OPEN', 'CLOSED')).toMatchObject({ label: '本场报名已截止', enabled: false })
    expect(resolveActivityPrimaryAction('OPEN', 'ENDED')).toMatchObject({ label: '本场已结束', enabled: false })
  })

  it('stops at the activity status once everything is closed or over', () => {
    expect(resolveActivityPrimaryAction('CLOSED', 'OPEN')).toMatchObject({ label: '报名已截止', enabled: false })
    expect(resolveActivityPrimaryAction('ENDED', null)).toMatchObject({ label: '活动已结束', enabled: false })
  })

  it('only lets sessions that can take a booking or a waitlist entry be picked', () => {
    expect(isActivitySessionSelectable('OPEN')).toBe(true)
    expect(isActivitySessionSelectable('FULL_WAITLIST')).toBe(true)
    expect(isActivitySessionSelectable('FULL')).toBe(false)
    expect(isActivitySessionSelectable('CLOSED')).toBe(false)
    expect(isActivitySessionSelectable('ENDED')).toBe(false)
  })
})

describe('activity intents', () => {
  it('explains in preview builds and hides in public builds', () => {
    expect(resolveActivityIntent({ type: 'ENROLL', activityId: 'a', sessionId: 's' }, true))
      .toEqual({ action: 'SHOW_NOTICE', message: '报名功能即将开放' })
    expect(resolveActivityIntent({ type: 'JOIN_WAITLIST', activityId: 'a', sessionId: 's' }, true))
      .toEqual({ action: 'SHOW_NOTICE', message: '候补功能即将开放' })
    expect(resolveActivityIntent({ type: 'TEAM_ENROLL', activityId: 'a' }, true))
      .toEqual({ action: 'SHOW_NOTICE', message: '团队报名即将开放' })
    expect(resolveActivityIntent({ type: 'CONSULT', activityId: 'a' }, true))
      .toEqual({ action: 'SHOW_NOTICE', message: '咨询功能即将开放' })
    expect(resolveActivityIntent({ type: 'ENROLL', activityId: 'a', sessionId: 's' }, false))
      .toEqual({ action: 'HIDE' })
  })
})
