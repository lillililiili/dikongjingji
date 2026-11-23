// The part of the screen the soft keyboard leaves visible.
//
// iOS Safari / WKWebView does not shrink the layout viewport when the keyboard opens: it shrinks the
// visual viewport and pans it (offsetTop) to keep the focused field in sight, which pushes headers
// under the status bar and full-screen pages up by the keyboard's height. A page that wants to stay
// put lays itself out in the visible part instead. On the App the input's keyboardheightchange event
// gives the height directly (with adjust-position off, nothing moves by itself).

/** The visible part of the page while the keyboard is open, in CSS px of the layout viewport. */
export interface KeyboardViewport {
  /** Distance from the top of the layout viewport to the top of what is visible. */
  top: number
  /** Height of what is visible above the keyboard. */
  height: number
  /** Height the keyboard (with its accessory bar) takes from the layout viewport. */
  keyboard: number
}

// Smaller differences are browser chrome (the address bar collapsing), not a keyboard.
const KEYBOARD_MIN_HEIGHT = 120

/** The keyboard viewport from the window's and the visual viewport's sizes, or null while it is closed. */
export function keyboardViewport(
  layoutHeight: number,
  visualHeight: number,
  visualOffsetTop: number,
): KeyboardViewport | null {
  if (![layoutHeight, visualHeight, visualOffsetTop].every(Number.isFinite)) return null
  const keyboard = Math.round(layoutHeight - visualHeight)
  if (keyboard < KEYBOARD_MIN_HEIGHT) return null
  return { top: Math.max(0, Math.round(visualOffsetTop)), height: Math.round(visualHeight), keyboard }
}

/** The App's keyboard height (keyboardheightchange) as the same viewport; null when it closes. */
export function appKeyboardViewport(windowHeight: number, keyboardHeight: number): KeyboardViewport | null {
  if (!Number.isFinite(keyboardHeight) || keyboardHeight < KEYBOARD_MIN_HEIGHT) return null
  return { top: 0, height: Math.max(0, Math.round(windowHeight - keyboardHeight)), keyboard: Math.round(keyboardHeight) }
}

interface VisualViewportLike {
  height: number
  offsetTop: number
  addEventListener(type: 'resize' | 'scroll', listener: () => void): void
  removeEventListener(type: 'resize' | 'scroll', listener: () => void): void
}

/**
 * Calls back with the keyboard viewport whenever the H5 visual viewport changes (null once the
 * keyboard closes). Does nothing where there is no visualViewport (the App, old browsers).
 * Returns the function that stops listening.
 */
export function watchH5KeyboardViewport(onChange: (viewport: KeyboardViewport | null) => void): () => void {
  if (typeof window === 'undefined') return () => {}
  const visual = (window as unknown as { visualViewport?: VisualViewportLike }).visualViewport
  if (!visual) return () => {}
  let last = ''
  const update = (): void => {
    const next = keyboardViewport(window.innerHeight, visual.height, visual.offsetTop)
    const key = next ? `${next.top}:${next.height}` : ''
    if (key === last) return
    last = key
    onChange(next)
  }
  visual.addEventListener('resize', update)
  visual.addEventListener('scroll', update)
  return () => {
    visual.removeEventListener('resize', update)
    visual.removeEventListener('scroll', update)
  }
}

/**
 * H5 only: once the keyboard is open, undoes the pan iOS made if the focused field would still be
 * above the keyboard with the page at the top. Keeps a page head out from under the status bar
 * when the field never needed the page to move; a field low on the page keeps the pan it needs.
 */
export function undoNeedlessKeyboardPan(viewport: KeyboardViewport | null): void {
  if (!viewport || typeof window === 'undefined' || typeof document === 'undefined') return
  const pannedBy = window.scrollY + viewport.top
  if (pannedBy <= 0) return
  const field = document.activeElement
  if (!(field instanceof HTMLElement)) return
  const fieldBottomOnPage = field.getBoundingClientRect().bottom + window.scrollY
  // 12px of air between the field and the keyboard's accessory bar.
  if (fieldBottomOnPage + 12 <= viewport.height) window.scrollTo(0, 0)
}
