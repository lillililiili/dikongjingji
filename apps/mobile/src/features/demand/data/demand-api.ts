import {
  DEMAND_DELIVERABLE_LABELS,
  type Demand,
  type DemandDraftInput,
  type DemandPage,
  type DemandProvider,
  type DemandPublisher,
  type DemandQuote,
  type DemandQuoteList,
  type DemandQuoteSort,
  type DemandStatus,
  type DemandType,
} from '../domain/demand'

type DemandRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// The server sends every field; a missing one reads the same as null.
type Nullable<T> = { [K in keyof T]?: T[K] | null }
type DemandDto = Nullable<Demand> & Pick<Demand, 'demandId' | 'status' | 'type' | 'title' | 'version'>
type DemandQuoteDto = Nullable<DemandQuote> & Pick<DemandQuote, 'quoteId' | 'version' | 'status' | 'amountMinor'>

interface DemandPageDto {
  total: number
  hasMore: boolean
  items: DemandDto[]
}

interface DemandQuoteListDto {
  demandId: string
  demandStatus: DemandStatus
  demandVersion: number
  sort: DemandQuoteSort
  items: DemandQuoteDto[]
}

interface CurrentAccountDto {
  organizationName?: string | null
  organizationType?: string | null
}

export interface DemandApi {
  // Who a new demand is recorded under: the account's own organization when it is a customer one.
  loadPublisher(accessToken: string): Promise<DemandPublisher>
  create(accessToken: string, idempotencyKey: string, input: DemandDraftInput, submit: boolean): Promise<Demand>
  updateDraft(accessToken: string, demandId: string, input: DemandDraftInput): Promise<Demand>
  submit(accessToken: string, demandId: string): Promise<Demand>
  list(accessToken: string, status: DemandStatus | null, page: number, size: number): Promise<DemandPage>
  read(accessToken: string, demandId: string): Promise<Demand>
  cancel(accessToken: string, demandId: string): Promise<Demand>
  deleteDraft(accessToken: string, demandId: string): Promise<void>
  listQuotes(accessToken: string, demandId: string, sort: DemandQuoteSort): Promise<DemandQuoteList>
  // `demandVersion` is the version the quotes were read with; it goes out as If-Match.
  choose(accessToken: string, demandId: string, demandVersion: number, quoteId: string, quoteVersion: number): Promise<Demand>
  listProviders(accessToken: string, cityCode: string, type: DemandType): Promise<DemandProvider[]>
}

// Every call carries the access token it was given; a rejected token comes back to the page as an
// error (status 401) and the page decides where the user goes.
export function createDemandApi(request: DemandRequest): DemandApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const demandUrl = (demandId: string) => `/api/v1/demands/${encodeURIComponent(demandId)}`

  return {
    async loadPublisher(accessToken) {
      const dto = await request<CurrentAccountDto>({ url: '/api/v1/auth/me', method: 'GET', headers: auth(accessToken) })
      const organizationName = dto.organizationName?.trim() || null
      return dto.organizationType === 'CUSTOMER' && organizationName
        ? { kind: 'ORGANIZATION', organizationName }
        : { kind: 'PERSONAL', organizationName: null }
    },
    async create(accessToken, idempotencyKey, input, submit) {
      return toDemand(await request<DemandDto>({
        url: '/api/v1/demands',
        method: 'POST',
        data: { ...toRequestBody(input), submit },
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async updateDraft(accessToken, demandId, input) {
      return toDemand(await request<DemandDto>({
        url: demandUrl(demandId),
        method: 'PUT',
        data: toRequestBody(input),
        headers: auth(accessToken),
      }))
    },
    async submit(accessToken, demandId) {
      return toDemand(await request<DemandDto>({
        url: `${demandUrl(demandId)}/submit`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
    async list(accessToken, status, page, size) {
      const filter = status ? `status=${status}&` : ''
      const dto = await request<DemandPageDto>({
        url: `/api/v1/demands?${filter}page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return { total: dto.total, hasMore: Boolean(dto.hasMore), items: dto.items.map(toDemand) }
    },
    async read(accessToken, demandId) {
      return toDemand(await request<DemandDto>({ url: demandUrl(demandId), method: 'GET', headers: auth(accessToken) }))
    },
    async cancel(accessToken, demandId) {
      return toDemand(await request<DemandDto>({
        url: `${demandUrl(demandId)}/cancel`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
    async deleteDraft(accessToken, demandId) {
      await request<unknown>({ url: demandUrl(demandId), method: 'DELETE', headers: auth(accessToken) })
    },
    async listQuotes(accessToken, demandId, sort) {
      const dto = await request<DemandQuoteListDto>({
        url: `${demandUrl(demandId)}/quotes?sort=${sort}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        demandId: dto.demandId,
        demandStatus: dto.demandStatus,
        demandVersion: dto.demandVersion,
        sort: dto.sort,
        items: dto.items.map(toDemandQuote),
      }
    },
    async choose(accessToken, demandId, demandVersion, quoteId, quoteVersion) {
      return toDemand(await request<DemandDto>({
        url: `${demandUrl(demandId)}/choice`,
        method: 'POST',
        data: { quoteId, quoteVersion },
        // The ETag of a demand is its version in quotes.
        headers: { ...auth(accessToken), 'If-Match': `"${demandVersion}"` },
      }))
    },
    async listProviders(accessToken, cityCode, type) {
      const dto = await request<{ items: Nullable<DemandProvider>[] }>({
        url: `/api/v1/demand-providers?cityCode=${encodeURIComponent(cityCode)}&type=${type}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return dto.items.map(toDemandProvider)
    },
  }
}

// Empty text goes out as null: a draft may leave anything but the type blank.
function toRequestBody(input: DemandDraftInput) {
  const text = (value: string | null) => (value?.trim() ? value.trim() : null)
  return {
    type: input.type,
    cityCode: input.cityCode,
    address: text(input.address),
    scope: text(input.scope),
    dueDate: input.dueDate,
    budgetMinMinor: input.budgetMinMinor,
    budgetMaxMinor: input.budgetMaxMinor,
    deliverables: input.deliverables,
    note: text(input.note),
    quoteScope: input.quoteScope,
    invitedProviderIds: input.quoteScope === 'INVITE' ? input.invitedProviderIds : [],
  }
}

function toDemandProvider(dto: Nullable<DemandProvider>): DemandProvider {
  return {
    providerId: dto.providerId ?? '',
    name: dto.name ?? '',
    certificationLabel: dto.certificationLabel ?? null,
    description: dto.description ?? null,
    // Only an explicit true shows 资质已审核.
    qualificationReviewed: dto.qualificationReviewed === true,
  }
}

export function toDemand(dto: DemandDto): Demand {
  return {
    demandId: dto.demandId,
    status: dto.status,
    closeReason: dto.closeReason ?? null,
    type: dto.type,
    title: dto.title,
    publisher: {
      kind: dto.publisher?.kind ?? 'PERSONAL',
      organizationName: dto.publisher?.organizationName ?? null,
    },
    cityCode: dto.cityCode ?? null,
    cityName: dto.cityName ?? null,
    address: dto.address ?? null,
    scope: dto.scope ?? null,
    dueDate: dto.dueDate ?? null,
    budgetMinMinor: dto.budgetMinMinor ?? null,
    budgetMaxMinor: dto.budgetMaxMinor ?? null,
    deliverables: (dto.deliverables ?? []).map((item) => ({
      code: item.code,
      label: item.label || DEMAND_DELIVERABLE_LABELS[item.code] || item.code,
    })),
    note: dto.note ?? null,
    quoteScope: dto.quoteScope ?? 'MATCH',
    invitedProviders: (dto.invitedProviders ?? []).map(toDemandProvider),
    submittedAt: dto.submittedAt ?? null,
    quoteDeadline: dto.quoteDeadline ?? null,
    quotingOpen: Boolean(dto.quotingOpen),
    quoteCount: dto.quoteCount ?? 0,
    chosen: dto.chosen
      ? {
          quoteId: dto.chosen.quoteId,
          quoteVersion: dto.chosen.quoteVersion,
          providerName: dto.chosen.providerName,
          amountMinor: dto.chosen.amountMinor,
          chosenAt: dto.chosen.chosenAt,
        }
      : null,
    editable: Boolean(dto.editable),
    cancellable: Boolean(dto.cancellable),
    deletable: Boolean(dto.deletable),
    createdAt: dto.createdAt ?? '',
    updatedAt: dto.updatedAt ?? '',
    version: dto.version,
  }
}

export function toDemandQuote(dto: DemandQuoteDto): DemandQuote {
  return {
    quoteId: dto.quoteId,
    provider: {
      providerId: dto.provider?.providerId ?? '',
      name: dto.provider?.name ?? '',
      certificationLabel: dto.provider?.certificationLabel ?? null,
      qualificationReviewed: dto.provider?.qualificationReviewed === true,
    },
    version: dto.version,
    status: dto.status,
    amountMinor: dto.amountMinor,
    method: dto.method ?? '',
    schedule: dto.schedule ?? '',
    durationDays: dto.durationDays ?? 0,
    deliverables: dto.deliverables ?? '',
    validUntil: dto.validUntil ?? '',
    submittedAt: dto.submittedAt ?? '',
    revisedAt: dto.revisedAt ?? '',
    choosable: Boolean(dto.choosable),
  }
}
