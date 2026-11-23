import type {
  AccountClosure,
  AccountCodePurpose,
  AccountSession,
  CreatedInvitation,
  CurrentIdentity,
  DataExport,
  DataExportOverview,
  DataSectionCode,
  DownloadIntent,
  Enterprise,
  EnterpriseList,
  EnterpriseMember,
  EnterpriseMembers,
  EnterpriseOrderPage,
  LoginDevice,
  LoginToken,
  MemberRole,
  PendingInvitation,
  PhoneLoginResult,
  ReceivedInvitation,
  SmsCodeSent,
} from '../domain/identity-account'

type AccountRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// The account and enterprise calls of contracts/openapi-identity-account.yaml 1.0.0, plus the
// enterprise order list of openapi-trade.yaml 0.6.0. Every signed-in call carries the token it was
// given; a rejected token reaches the page as a 401 and the page decides where the user goes.
export function createIdentityAccountApi(request: AccountRequest) {
  const auth = (token: string) => ({ Authorization: `Bearer ${token}` })
  const enterpriseUrl = (enterpriseId: string) => `/api/v1/account/enterprises/${encodeURIComponent(enterpriseId)}`
  const invitationUrl = (invitationId: string) =>
    `/api/v1/account/enterprise-invitations/${encodeURIComponent(invitationId)}`
  return {
    // ---- phone login (no token) ----
    sendLoginCode(phone: string): Promise<SmsCodeSent> {
      return request({ url: '/api/v1/auth/sms-codes', method: 'POST', data: { phone } })
    },
    loginWithCode(phone: string, code: string, device: LoginDevice): Promise<PhoneLoginResult> {
      return request({ url: '/api/v1/auth/phone-login', method: 'POST', data: { phone, code, device } })
    },

    // ---- phone number ----
    sendAccountCode(token: string, purpose: AccountCodePurpose, phone?: string): Promise<SmsCodeSent> {
      return request({
        url: '/api/v1/account/sms-codes',
        method: 'POST',
        data: { purpose, phone: phone ?? null },
        headers: auth(token),
      })
    },
    verifyCurrentPhone(token: string, code: string): Promise<{ changeTicket: string; expiresInSeconds: number }> {
      return request({ url: '/api/v1/account/phone/verifications', method: 'POST', data: { code }, headers: auth(token) })
    },
    changePhone(token: string, input: { newPhone: string; code: string; changeTicket: string | null }):
    Promise<{ maskedPhone: string; signedOutSessions: number }> {
      return request({ url: '/api/v1/account/phone', method: 'PUT', data: input, headers: auth(token) })
    },

    // ---- devices ----
    async listSessions(token: string): Promise<AccountSession[]> {
      const dto = await request<{ items: AccountSession[] }>({
        url: '/api/v1/account/sessions',
        method: 'GET',
        headers: auth(token),
      })
      return dto.items
    },
    revokeSession(token: string, sessionId: string): Promise<void> {
      return request({
        url: `/api/v1/account/sessions/${encodeURIComponent(sessionId)}`,
        method: 'DELETE',
        headers: auth(token),
      })
    },
    revokeOtherSessions(token: string): Promise<{ revoked: number }> {
      return request({ url: '/api/v1/account/sessions/revocations', method: 'POST', headers: auth(token) })
    },

    // ---- closure ----
    readClosure(token: string): Promise<AccountClosure> {
      return request({ url: '/api/v1/account/closure', method: 'GET', headers: auth(token) })
    },
    requestClosure(token: string): Promise<AccountClosure> {
      return request({ url: '/api/v1/account/closure', method: 'POST', headers: auth(token) })
    },
    withdrawClosure(token: string): Promise<AccountClosure> {
      return request({ url: '/api/v1/account/closure', method: 'DELETE', headers: auth(token) })
    },

    // ---- personal data ----
    readDataExports(token: string): Promise<DataExportOverview> {
      return request({ url: '/api/v1/account/data-exports', method: 'GET', headers: auth(token) })
    },
    requestDataExport(token: string, sections: DataSectionCode[], code: string): Promise<DataExport> {
      return request({
        url: '/api/v1/account/data-exports',
        method: 'POST',
        data: { sections, code },
        headers: auth(token),
      })
    },
    createDownload(token: string, exportId: string): Promise<DownloadIntent> {
      return request({
        url: `/api/v1/account/data-exports/${encodeURIComponent(exportId)}/download-intents`,
        method: 'POST',
        headers: auth(token),
      })
    },

    // ---- enterprises ----
    listEnterprises(token: string): Promise<EnterpriseList> {
      return request({ url: '/api/v1/account/enterprises', method: 'GET', headers: auth(token) })
    },
    createEnterprise(token: string, key: string, form: { name: string; creditCode: string; cityCode: string | null }):
    Promise<Enterprise> {
      return request({
        url: '/api/v1/account/enterprises',
        method: 'POST',
        data: form,
        headers: { ...auth(token), 'Idempotency-Key': key },
      })
    },
    readEnterprise(token: string, enterpriseId: string): Promise<Enterprise> {
      return request({ url: enterpriseUrl(enterpriseId), method: 'GET', headers: auth(token) })
    },
    listMembers(token: string, enterpriseId: string): Promise<EnterpriseMembers> {
      return request({ url: `${enterpriseUrl(enterpriseId)}/members`, method: 'GET', headers: auth(token) })
    },
    changeRole(token: string, enterpriseId: string, memberId: string, role: MemberRole): Promise<EnterpriseMember> {
      return request({
        url: `${enterpriseUrl(enterpriseId)}/members/${encodeURIComponent(memberId)}`,
        method: 'PUT',
        data: { role },
        headers: auth(token),
      })
    },
    removeMember(token: string, enterpriseId: string, memberId: string): Promise<void> {
      return request({
        url: `${enterpriseUrl(enterpriseId)}/members/${encodeURIComponent(memberId)}`,
        method: 'DELETE',
        headers: auth(token),
      })
    },
    leaveEnterprise(token: string, enterpriseId: string): Promise<void> {
      return request({ url: `${enterpriseUrl(enterpriseId)}/membership`, method: 'DELETE', headers: auth(token) })
    },
    invite(token: string, enterpriseId: string, kind: 'PHONE' | 'LINK', phone?: string): Promise<CreatedInvitation> {
      return request({
        url: `${enterpriseUrl(enterpriseId)}/invitations`,
        method: 'POST',
        data: { kind, phone: phone ?? null },
        headers: auth(token),
      })
    },
    revokeInvitation(token: string, enterpriseId: string, invitationId: string): Promise<void> {
      return request({
        url: `${enterpriseUrl(enterpriseId)}/invitations/${encodeURIComponent(invitationId)}`,
        method: 'DELETE',
        headers: auth(token),
      })
    },
    renewInvitation(token: string, enterpriseId: string, invitationId: string): Promise<PendingInvitation> {
      return request({
        url: `${enterpriseUrl(enterpriseId)}/invitations/${encodeURIComponent(invitationId)}/renewals`,
        method: 'POST',
        headers: auth(token),
      })
    },
    readInvitation(token: string, invitationId: string, code: string | null): Promise<ReceivedInvitation> {
      const query = code ? `?code=${encodeURIComponent(code)}` : ''
      return request({ url: `${invitationUrl(invitationId)}${query}`, method: 'GET', headers: auth(token) })
    },
    acceptInvitation(token: string, invitationId: string, code: string | null): Promise<Enterprise> {
      return request({
        url: `${invitationUrl(invitationId)}/acceptance`,
        method: 'POST',
        data: { code },
        headers: auth(token),
      })
    },
    declineInvitation(token: string, invitationId: string, code: string | null): Promise<ReceivedInvitation> {
      return request({ url: `${invitationUrl(invitationId)}/decline`, method: 'POST', data: { code }, headers: auth(token) })
    },
    switchIdentity(token: string, enterpriseId: string | null): Promise<LoginToken & { identity: CurrentIdentity }> {
      return request({ url: '/api/v1/account/identity', method: 'PUT', data: { enterpriseId }, headers: auth(token) })
    },
    listEnterpriseOrders(token: string, enterpriseId: string, page = 1, size = 20): Promise<EnterpriseOrderPage> {
      return request({
        url: `/api/v1/trade/enterprise-orders?enterpriseId=${encodeURIComponent(enterpriseId)}&page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(token),
      })
    },
  }
}

export type IdentityAccountApi = ReturnType<typeof createIdentityAccountApi>
