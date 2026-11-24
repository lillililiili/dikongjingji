import type { ExperienceImage } from '../domain/experience'
import type { ExperienceRecord, ExperienceRecordPage } from '../domain/experience-record'
import type { SimulationResult } from '../domain/simulation'
import { isMediaUrl } from '../../../shared/http/media-url'

type RecordRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

type RecordDto = Omit<ExperienceRecord, 'experience'> & {
  experience: Omit<ExperienceRecord['experience'], 'coverImage'> & { coverImage?: ExperienceImage | null }
}

interface RecordPageDto {
  total: number
  hasMore: boolean
  items: RecordDto[]
}

export interface ExperienceRecordApi {
  // The same key for the same run: a retry, or a guest's result sent after signing in.
  recordSimulation(accessToken: string, idempotencyKey: string, result: SimulationResult): Promise<ExperienceRecord>
  list(
    accessToken: string,
    input: { experienceId?: string | null; page: number; size: number },
  ): Promise<ExperienceRecordPage>
  read(accessToken: string, recordId: string): Promise<ExperienceRecord>
  startTrial(accessToken: string, experienceId: string): Promise<ExperienceRecord>
  endTrial(accessToken: string, recordId: string): Promise<ExperienceRecord>
}

function toImage(asset: ExperienceImage | null | undefined): ExperienceImage | null {
  return asset && isMediaUrl(asset.url) ? { url: asset.url, alt: asset.alt } : null
}

export function toExperienceRecord(dto: RecordDto): ExperienceRecord {
  return {
    recordId: dto.recordId,
    experience: {
      experienceId: dto.experience.experienceId,
      type: dto.experience.type,
      title: dto.experience.title,
      coverImage: toImage(dto.experience.coverImage),
      productId: dto.experience.productId ?? null,
      simulationTemplate: dto.experience.simulationTemplate ?? null,
    },
    status: dto.status,
    startedAt: dto.startedAt,
    endedAt: dto.endedAt ?? null,
    expiresAt: dto.expiresAt ?? null,
    result: dto.result ?? null,
  }
}

// Every call carries the access token it was given; a rejected token comes back to the page as an
// error (status 401) and the page decides where the user goes.
export function createExperienceRecordApi(request: RecordRequest): ExperienceRecordApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const recordUrl = (recordId: string) => `/api/v1/experience/records/${encodeURIComponent(recordId)}`

  return {
    async recordSimulation(accessToken, idempotencyKey, result) {
      return toExperienceRecord(await request<RecordDto>({
        url: '/api/v1/experience/records',
        method: 'POST',
        data: {
          experienceId: result.experienceId,
          startedAt: result.startedAt,
          endedAt: result.endedAt,
          durationSeconds: result.durationSeconds,
          levelsCompleted: result.levelsCompleted,
          levelsTotal: result.levelsTotal,
          mistakes: result.mistakes,
        },
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async list(accessToken, input) {
      const query = [
        ...(input.experienceId ? [`experienceId=${encodeURIComponent(input.experienceId)}`] : []),
        `page=${input.page}`,
        `size=${input.size}`,
      ].join('&')
      const dto = await request<RecordPageDto>({
        url: `/api/v1/experience/records?${query}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return { total: dto.total, hasMore: dto.hasMore, items: dto.items.map(toExperienceRecord) }
    },
    async read(accessToken, recordId) {
      return toExperienceRecord(await request<RecordDto>({
        url: recordUrl(recordId),
        method: 'GET',
        headers: auth(accessToken),
      }))
    },
    async startTrial(accessToken, experienceId) {
      return toExperienceRecord(await request<RecordDto>({
        url: '/api/v1/experience/trials',
        method: 'POST',
        data: { experienceId },
        headers: auth(accessToken),
      }))
    },
    async endTrial(accessToken, recordId) {
      return toExperienceRecord(await request<RecordDto>({
        url: `${recordUrl(recordId)}/end`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
  }
}
