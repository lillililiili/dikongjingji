import { describe, expect, it, vi } from 'vitest'

import { useChangeHistory } from './use-change-history'

describe('运营留痕读取', () => {
  it('较早的响应不会显示在已切换的资源下', async () => {
    let finish!: (items: string[]) => void
    const previous = new Promise<string[]>(resolve => { finish = resolve })
    const history = useChangeHistory((id) => id === 'previous' ? previous : Promise.resolve(['当前操作']))
    const pending = history.load('previous')
    await history.load('current')
    finish(['旧操作'])
    await pending
    expect(history.changes.value).toEqual(['当前操作'])
    expect(history.loading.value).toBe(false)
  })

  it('请求失败提供独立错误，能重试读取同一条记录', async () => {
    const read = vi.fn().mockRejectedValueOnce(new Error('记录加载失败')).mockResolvedValueOnce(['保存'])
    const history = useChangeHistory<string>(read)
    await history.load('one')
    expect(history.error.value).toBe('记录加载失败')
    await history.load('one')
    expect(history.error.value).toBe('')
    expect(history.changes.value).toEqual(['保存'])
  })
})
