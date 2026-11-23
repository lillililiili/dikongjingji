import { describe, expect, it } from 'vitest'

import { MAX_RECENT_SEARCHES, createRecentSearches, type SearchWordStorage } from './recent-searches'

function memory(initial?: string): SearchWordStorage & { values: Map<string, string> } {
  const values = new Map<string, string>()
  if (initial !== undefined) values.set('k', initial)
  return {
    values,
    get: (key) => values.get(key) ?? '',
    set: (key, value) => { values.set(key, value) },
    remove: (key) => { values.delete(key) },
  }
}

describe('recent searches (boards SearchPre, OrderSearch)', () => {
  it('keeps at most six words, newest first, each once', () => {
    const storage = memory()
    const recent = createRecentSearches(storage, 'k')
    for (const word of ['测绘', '航测', '三维建模', ' 测绘 ', '机器狗', '巡检', '课程', '研学']) recent.add(word)
    expect(recent.load()).toEqual(['研学', '课程', '巡检', '机器狗', '测绘', '三维建模'])
    expect(recent.load()).toHaveLength(MAX_RECENT_SEARCHES)
  })

  it('clears them and reads broken or foreign values as empty', () => {
    const storage = memory(JSON.stringify(['测绘', 3, '', '航测']))
    const recent = createRecentSearches(storage, 'k')
    expect(recent.load()).toEqual(['测绘', '航测'])
    recent.clear()
    expect(recent.load()).toEqual([])
    expect(createRecentSearches(memory('not json'), 'k').load()).toEqual([])
  })

  it('still works when the device storage throws', () => {
    const broken: SearchWordStorage = {
      get: () => { throw new Error('blocked') },
      set: () => { throw new Error('full') },
      remove: () => { throw new Error('blocked') },
    }
    const recent = createRecentSearches(broken, 'k')
    expect(recent.load()).toEqual([])
    expect(recent.add('测绘')).toEqual(['测绘'])
    expect(() => recent.clear()).not.toThrow()
  })
})
