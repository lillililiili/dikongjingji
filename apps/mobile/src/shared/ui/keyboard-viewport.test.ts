import { describe, expect, it } from 'vitest'

import { appKeyboardViewport, keyboardViewport } from './keyboard-viewport'

describe('keyboard viewport', () => {
  it('reads the visible part above an open keyboard from the visual viewport', () => {
    // iPhone 15 Pro, 852pt tall: keyboard with accessory bar takes 336pt and iOS panned the page 40pt.
    expect(keyboardViewport(852, 516, 40)).toEqual({ top: 40, height: 516, keyboard: 336 })
  })

  it('treats the address bar collapsing as no keyboard', () => {
    expect(keyboardViewport(852, 852, 0)).toBeNull()
    expect(keyboardViewport(852, 790, 0)).toBeNull()
    expect(keyboardViewport(Number.NaN, 500, 0)).toBeNull()
  })

  it('turns the App keyboard height into the same viewport', () => {
    expect(appKeyboardViewport(844, 336)).toEqual({ top: 0, height: 508, keyboard: 336 })
    expect(appKeyboardViewport(844, 0)).toBeNull()
  })
})
