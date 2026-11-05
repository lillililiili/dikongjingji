const SETTLE_MS = 1000

// On H5 navigateBack is a history.go() that lands a moment later, and unlike the
// url-based routes uni-h5 gives it no lock. Until it lands the old page is still on
// top, so a second tap steps back again, out of the app when two pages are open.
// The time limit only matters if the browser had nowhere to go back to.
export function createBackGuard(
  getTopPage: () => unknown,
  now: () => number = Date.now,
): () => boolean {
  let leavingPage: unknown
  let leavingAt = 0

  return () => {
    const page = getTopPage()
    if (page === leavingPage && now() - leavingAt < SETTLE_MS) return false
    leavingPage = page
    leavingAt = now()
    return true
  }
}
