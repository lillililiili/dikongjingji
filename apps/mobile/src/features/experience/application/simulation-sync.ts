import type { ExperienceRecordApi } from '../data/experience-record-api'
import type { LocalSimulationResult, LocalSimulationResults } from '../data/local-simulation-results'

// Sends the results kept on this device to the signed-in account (board ExpResult: 登录后可归档到账户).
// Each result goes with the key it was saved with, so sending twice records it once.

export type SyncOutcome =
  | { kind: 'DONE'; uploaded: number }
  // The token was refused: the caller sends the user to sign in.
  | { kind: 'UNAUTHORIZED'; uploaded: number }
  // Network or server trouble: the rest stay on the device and go next time.
  | { kind: 'STOPPED'; uploaded: number }

// The server will never take these, so trying again is pointless.
const NEVER_TAKEN = new Set([
  'EXPERIENCE_RESULT_INVALID',
  'EXPERIENCE_NOT_RECORDABLE',
  'EXPERIENCE_IDEMPOTENCY_KEY_REUSED',
  'COMMON_NOT_FOUND',
])

// The fields of the HTTP client's ApiRequestError, read by shape so this module stays testable
// without the uni-app client.
function apiFailure(error: unknown): { status: number | null; code: string | null } {
  if (!error || typeof error !== 'object') return { status: null, code: null }
  const { status, code } = error as { status?: unknown; code?: unknown }
  return {
    status: typeof status === 'number' ? status : null,
    code: typeof code === 'string' ? code : null,
  }
}

export function isUnauthorizedError(error: unknown): boolean {
  const failure = apiFailure(error)
  return failure.status === 401 || failure.code === 'COMMON_UNAUTHORIZED'
}

/** Sends one result; returns its record id, or null when the server will never take it. */
export async function uploadLocalResult(
  api: Pick<ExperienceRecordApi, 'recordSimulation'>,
  store: LocalSimulationResults,
  accessToken: string,
  result: LocalSimulationResult,
): Promise<string | null> {
  // Already sent ('' marks one the server refused for good).
  if (result.recordId !== null) return result.recordId || null
  try {
    const record = await api.recordSimulation(accessToken, result.idempotencyKey, result)
    store.markUploaded(result.localId, record.recordId)
    return record.recordId
  } catch (error) {
    const code = apiFailure(error).code
    if (code && NEVER_TAKEN.has(code)) {
      store.markUploaded(result.localId, '')
      return null
    }
    throw error
  }
}

export async function uploadPendingResults(
  api: Pick<ExperienceRecordApi, 'recordSimulation'>,
  store: LocalSimulationResults,
  accessToken: string,
): Promise<SyncOutcome> {
  let uploaded = 0
  // Oldest first, so the records keep the order they were played in.
  for (const result of [...store.pending()].reverse()) {
    try {
      if (await uploadLocalResult(api, store, accessToken, result)) uploaded += 1
    } catch (error) {
      return isUnauthorizedError(error) ? { kind: 'UNAUTHORIZED', uploaded } : { kind: 'STOPPED', uploaded }
    }
  }
  return { kind: 'DONE', uploaded }
}
