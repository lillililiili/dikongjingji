import { describe, expect, it } from 'vitest'

import { createBackGuard } from './h5-back-guard'

function createClock(): { now: () => number; advance: (ms: number) => void } {
  let time = 1000
  return {
    now: () => time,
    advance: (ms) => { time += ms },
  }
}

describe('createBackGuard', () => {
  it('lets the first back through', () => {
    const guard = createBackGuard(() => 'orders')

    expect(guard()).toBe(true)
  })

  it('drops a second back while the same page is still on top', () => {
    const clock = createClock()
    const guard = createBackGuard(() => 'orders', clock.now)

    guard()
    clock.advance(1)

    expect(guard()).toBe(false)
  })

  it('lets the page underneath go back at once', () => {
    const clock = createClock()
    let top = 'plan'
    const guard = createBackGuard(() => top, clock.now)

    guard()
    top = 'orders'

    expect(guard()).toBe(true)
  })

  it('treats a reopened page as a new page', () => {
    const clock = createClock()
    let top = { route: 'orders' }
    const guard = createBackGuard(() => top, clock.now)

    guard()
    top = { route: 'orders' }

    expect(guard()).toBe(true)
  })

  it('lets the same page try again if the browser had nowhere to go back to', () => {
    const clock = createClock()
    const guard = createBackGuard(() => 'orders', clock.now)

    guard()
    clock.advance(1000)

    expect(guard()).toBe(true)
  })

  it('keeps blocking from the last back that went through', () => {
    const clock = createClock()
    const guard = createBackGuard(() => 'orders', clock.now)

    guard()
    clock.advance(999)
    expect(guard()).toBe(false)
    clock.advance(1)
    expect(guard()).toBe(true)
    clock.advance(1)
    expect(guard()).toBe(false)
  })
})
