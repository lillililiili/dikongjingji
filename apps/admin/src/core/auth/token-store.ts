/**
 * 访问令牌：放内存，同时存一份到 sessionStorage（关掉浏览器标签就没了，不进 localStorage）。
 * 令牌 15 分钟过期（后端 AuthenticationService），没有刷新令牌；过期后要重新登录。
 */
export interface StoredToken {
  accessToken: string
  /** 过期时刻（毫秒时间戳）。 */
  expiresAt: number
}

export interface KeyValueStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

const STORAGE_KEY = 'lowaltitude.admin.token'
/** 提前 30 秒当作过期，免得请求发到一半令牌失效。 */
const EXPIRY_MARGIN_MS = 30_000

function browserSessionStorage(): KeyValueStorage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage
  } catch {
    return null
  }
}

export class TokenStore {
  private current: StoredToken | null = null

  constructor(
    private readonly storage: KeyValueStorage | null = browserSessionStorage(),
    private readonly now: () => number = () => Date.now(),
  ) {
    this.current = this.read()
  }

  get(): string | null {
    if (!this.current) return null
    if (this.current.expiresAt - EXPIRY_MARGIN_MS <= this.now()) {
      this.clear()
      return null
    }
    return this.current.accessToken
  }

  expiresAt(): number | null {
    return this.get() ? (this.current?.expiresAt ?? null) : null
  }

  set(accessToken: string, expiresInSeconds: number): void {
    this.current = { accessToken, expiresAt: this.now() + expiresInSeconds * 1000 }
    try {
      this.storage?.setItem(STORAGE_KEY, JSON.stringify(this.current))
    } catch {
      // 存不进去（隐私模式等）时只放内存，刷新页面要重新登录。
    }
  }

  clear(): void {
    this.current = null
    try {
      this.storage?.removeItem(STORAGE_KEY)
    } catch {
      // 同上。
    }
  }

  private read(): StoredToken | null {
    try {
      const raw = this.storage?.getItem(STORAGE_KEY)
      if (!raw) return null
      const parsed: unknown = JSON.parse(raw)
      if (
        parsed && typeof parsed === 'object'
        && typeof (parsed as StoredToken).accessToken === 'string'
        && typeof (parsed as StoredToken).expiresAt === 'number'
      ) {
        return parsed as StoredToken
      }
      return null
    } catch {
      return null
    }
  }
}

export const tokenStore = new TokenStore()
