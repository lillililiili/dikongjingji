export interface CurrentAccount {
  displayName: string
  organizationName: string | null
  /** Contract 2.4.0: 138****2026, or null when no number is bound (or an older server). */
  maskedPhone?: string | null
  /** Contract 2.4.0: the current identity; null from an older server. */
  identity?: {
    type: 'PERSONAL' | 'ENTERPRISE'
    enterpriseId: string | null
    enterpriseName: string | null
    role: 'ADMIN' | 'MEMBER' | null
  } | null
}

interface CurrentAccountDto {
  displayName?: string | null
  organizationName?: string | null
  maskedPhone?: string | null
  identity?: CurrentAccount['identity']
}

type AccountRequest = <T>(input: {
  url: string
  method: 'GET'
  headers: Record<string, string>
}) => Promise<T>

// The profile only decorates the Mine page. It is read with a plain request carrying the token,
// so a rejected token here never sends the user to the login page on its own; the requests that
// need the account (orders, booking) keep doing that.
export function createInspectionAccountApi(request: AccountRequest) {
  return {
    async loadCurrent(accessToken: string): Promise<CurrentAccount> {
      const dto = await request<CurrentAccountDto>({
        url: '/api/v1/auth/me',
        method: 'GET',
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      return {
        displayName: dto.displayName?.trim() ?? '',
        organizationName: dto.organizationName?.trim() || null,
        maskedPhone: dto.maskedPhone?.trim() || null,
        identity: dto.identity && (dto.identity.type === 'PERSONAL' || dto.identity.type === 'ENTERPRISE')
          ? {
              type: dto.identity.type,
              enterpriseId: dto.identity.enterpriseId ?? null,
              enterpriseName: dto.identity.enterpriseName ?? null,
              role: dto.identity.role ?? null,
            }
          : null,
      }
    },
  }
}
