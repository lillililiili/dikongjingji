/**
 * 登录令牌：访问令牌和刷新令牌放内存，同时存一份到 sessionStorage（关掉浏览器标签就没了，不进 localStorage），
 * 照 App 在 H5 上的做法（契约 openapi-inspection-service-flow.yaml 2.4.0 LoginResponse.refreshToken：
 * “H5 只放 sessionStorage 或内存”）。
 * 访问令牌 900 秒过期；过期后接口客户端用刷新令牌换一次新的两个令牌（POST /api/v1/auth/refresh，旧的随即作废）。
 * 刷新令牌也过期（或被撤销）后要重新登录。
 */
export interface StoredToken {
  accessToken: string
  /** 访问令牌过期时刻（毫秒时间戳）。 */
  expiresAt: number
  /** 刷新令牌；老版本存的数据没有。 */
  refreshToken?: string | null
  /** 刷新令牌过期时刻（毫秒时间戳）。 */
  refreshExpiresAt?: number | null
}

/** 登录或续期时后端签发的令牌（LoginResponse 的四个字段）。 */
export interface IssuedTokens {
  accessToken: string
  expiresInSeconds: number
  refreshToken?: string | null
  refreshExpiresInSeconds?: number | null
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

  /** 还能用的访问令牌；快过期或已过期时是 null（刷新令牌还在时不清掉登录，等接口客户端续期）。 */
  get(): string | null {
    if (!this.current) return null
    if (this.current.expiresAt - EXPIRY_MARGIN_MS > this.now()) return this.current.accessToken
    this.dropIfNothingLeft()
    return null
  }

  /** 还能用的刷新令牌；没有或已过期时是 null。 */
  refreshToken(): string | null {
    const token = this.current?.refreshToken
    if (!token) return null
    const expiresAt = this.current?.refreshExpiresAt
    if (typeof expiresAt === 'number' && expiresAt <= this.now()) {
      this.dropIfNothingLeft()
      return null
    }
    return token
  }

  /** 还算登录着：访问令牌能用，或者能用刷新令牌续期。 */
  hasLogin(): boolean {
    return this.get() !== null || this.refreshToken() !== null
  }

  expiresAt(): number | null {
    return this.get() ? (this.current?.expiresAt ?? null) : null
  }

  /** 登录或续期后存新的令牌；续期时刷新令牌也换新（轮换），旧的不再保留。 */
  set(accessToken: string, expiresInSeconds: number, refreshToken?: string | null,
    refreshExpiresInSeconds?: number | null): void {
    const now = this.now()
    this.current = {
      accessToken,
      expiresAt: now + expiresInSeconds * 1000,
      refreshToken: refreshToken || null,
      refreshExpiresAt: refreshToken && refreshExpiresInSeconds ? now + refreshExpiresInSeconds * 1000 : null,
    }
    try {
      this.storage?.setItem(STORAGE_KEY, JSON.stringify(this.current))
    } catch {
      // 存不进去（隐私模式等）时只放内存，刷新页面要重新登录。
    }
  }

  /** 存后端签发的一组令牌（登录、续期）。 */
  save(issued: IssuedTokens): void {
    this.set(issued.accessToken, issued.expiresInSeconds, issued.refreshToken, issued.refreshExpiresInSeconds)
  }

  clear(): void {
    this.current = null
    try {
      this.storage?.removeItem(STORAGE_KEY)
    } catch {
      // 同上。
    }
  }

  /** 访问令牌过期、也没有能用的刷新令牌时，整份清掉。 */
  private dropIfNothingLeft(): void {
    const current = this.current
    if (!current) return
    const now = this.now()
    const accessAlive = current.expiresAt - EXPIRY_MARGIN_MS > now
    const refreshAlive = !!current.refreshToken
      && (typeof current.refreshExpiresAt !== 'number' || current.refreshExpiresAt > now)
    if (!accessAlive && !refreshAlive) this.clear()
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
        const stored = parsed as StoredToken
        return {
          accessToken: stored.accessToken,
          expiresAt: stored.expiresAt,
          refreshToken: typeof stored.refreshToken === 'string' && stored.refreshToken ? stored.refreshToken : null,
          refreshExpiresAt: typeof stored.refreshExpiresAt === 'number' ? stored.refreshExpiresAt : null,
        }
      }
      return null
    } catch {
      return null
    }
  }
}

export const tokenStore = new TokenStore()
