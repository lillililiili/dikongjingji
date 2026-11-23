import { createVenueBookKey } from '../application/venue-booking-view'
import type { SimulationResult } from '../domain/simulation'

// Simulation results kept on this device (board ExpResult: 没登录时只存在这台设备上). Every run is
// saved here first with its own idempotency key; it is sent to the server when the user is signed in
// (now or later) and then remembers the record it became. Nothing personal is stored: the item, the
// numbers and the times only.

export interface LocalSimulationResult extends SimulationResult {
  localId: string
  idempotencyKey: string
  title: string
  productId: string | null
  // Set once the server has it; the result page then reads the record instead. '' when the server
  // refused it for good (too old, or the item is gone): it stays a local result only.
  recordId: string | null
}

export interface ResultStorage {
  read(): unknown
  write(value: LocalSimulationResult[]): void
}

const STORAGE_KEY = 'experience.localSimulationResults'
// Enough for a few weeks of playing; the oldest drop off first.
export const LOCAL_RESULT_LIMIT = 20

export const uniResultStorage: ResultStorage = {
  read: () => {
    try {
      return uni.getStorageSync(STORAGE_KEY)
    } catch {
      return null
    }
  },
  write: (value) => {
    try {
      uni.setStorageSync(STORAGE_KEY, value)
    } catch {
      // A full or blocked storage only loses the guest's copy; the page still shows the result.
    }
  },
}

function isResult(value: unknown): value is LocalSimulationResult {
  if (!value || typeof value !== 'object') return false
  const row = value as Record<string, unknown>
  return typeof row.localId === 'string'
    && typeof row.idempotencyKey === 'string'
    && typeof row.experienceId === 'string'
    && typeof row.startedAt === 'string'
    && typeof row.endedAt === 'string'
    && typeof row.durationSeconds === 'number'
    && typeof row.levelsCompleted === 'number'
    && typeof row.levelsTotal === 'number'
    && typeof row.mistakes === 'number'
    && (row.template === 'ROBOT_OBSTACLE' || row.template === 'DRONE_HOVER')
}

export function createLocalSimulationResults(storage: ResultStorage = uniResultStorage, createKey = createVenueBookKey) {
  const all = (): LocalSimulationResult[] => {
    const value = storage.read()
    return Array.isArray(value) ? value.filter(isResult) : []
  }
  return {
    /** Newest first. */
    all,
    save(result: SimulationResult, item: { title: string; productId: string | null }): LocalSimulationResult {
      const saved: LocalSimulationResult = {
        ...result,
        localId: createKey(),
        idempotencyKey: createKey(),
        title: item.title,
        productId: item.productId,
        recordId: null,
      }
      storage.write([saved, ...all()].slice(0, LOCAL_RESULT_LIMIT))
      return saved
    },
    find(localId: string): LocalSimulationResult | null {
      return all().find((row) => row.localId === localId) ?? null
    },
    pending(): LocalSimulationResult[] {
      return all().filter((row) => row.recordId === null)
    },
    markUploaded(localId: string, recordId: string): void {
      storage.write(all().map((row) => (row.localId === localId ? { ...row, recordId } : row)))
    },
  }
}

export type LocalSimulationResults = ReturnType<typeof createLocalSimulationResults>

export const localSimulationResults = createLocalSimulationResults()
