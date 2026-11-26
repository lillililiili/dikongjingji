import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { confirmSignOut } from '../application/confirm-sign-out'
import { clearBookingAuthSession, getBookingAuthContext, saveBookingAuthSession } from '../../inspection/application/service-flow/booking-auth-context'

const { revoke, message, cart } = vi.hoisted(() => ({ revoke: vi.fn(), message: vi.fn(), cart: vi.fn() }))
vi.mock('@/features/message/application/push-registration-client', () => ({ revokePushDevice: revoke }))
vi.mock('@/app-shell/state/root-tab-badges', () => ({
  useRootTabBadgeStore: () => ({ setMessageUnread: message, setCartCount: cart }),
}))

describe('confirming sign-out', () => {
  let respond: (result: { confirm: boolean }) => void
  const navigate = vi.fn()
  const signIn = (token: string) => saveBookingAuthSession({ accessToken: token, expiresInSeconds: 900 })

  beforeEach(() => {
    vi.clearAllMocks()
    const storage = new Map<string, unknown>()
    vi.stubGlobal('uni', {
      getStorageSync: (key: string) => storage.get(key),
      setStorageSync: (key: string, value: unknown) => storage.set(key, value),
      removeStorageSync: (key: string) => storage.delete(key),
      showModal: ({ success }: { success: typeof respond }) => { respond = success },
      reLaunch: navigate,
    })
    signIn('a')
  })
  afterEach(() => {
    clearBookingAuthSession()
    vi.unstubAllGlobals()
  })

  it('clears local state immediately and a delayed device cleanup cannot sign out the next account', async () => {
    let finish!: () => void
    const delayed = new Promise<void>(resolve => { finish = resolve })
    revoke.mockReturnValue(delayed)
    confirmSignOut()
    respond({ confirm: true })
    expect(getBookingAuthContext().getAccessToken()).toBeNull()
    expect(revoke).toHaveBeenCalledWith('a')
    expect(message).toHaveBeenCalledWith(0)
    expect(cart).toHaveBeenCalledWith(0)
    expect(navigate).toHaveBeenCalledTimes(1)
    signIn('b')
    finish()
    await delayed
    expect(getBookingAuthContext().getAccessToken()).toBe('b')
    expect(navigate).toHaveBeenCalledTimes(1)
  })

  it('preserves a fresh login when a previous account confirms its old dialog', () => {
    confirmSignOut()
    signIn('b')
    respond({ confirm: true })
    expect(getBookingAuthContext().getAccessToken()).toBe('b')
    expect(revoke).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
  })

  it('leaves the current session active when the user cancels', () => {
    confirmSignOut()
    respond({ confirm: false })
    expect(getBookingAuthContext().getAccessToken()).toBe('a')
    expect(revoke).not.toHaveBeenCalled()
  })
})
