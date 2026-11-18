export interface CurrentAccount {
  displayName: string
  organizationName: string | null
}

interface CurrentAccountDto {
  displayName?: string | null
  organizationName?: string | null
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
      }
    },
  }
}
