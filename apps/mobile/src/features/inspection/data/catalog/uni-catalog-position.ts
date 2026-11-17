import type { CatalogPosition } from '../../presentation/catalog/model/inspection-catalog'

const POSITION_TIMEOUT_MS = 10_000

/**
 * The customer's position for the distance sort. WGS84 is asked for because the server stores
 * centre points in WGS84 (docs/05-data-model.md §11); no map SDK or coordinate conversion is used.
 * Rejects when the system refuses, has no fix in time, or returns something that is not a position.
 */
export function locateCatalogPosition(): Promise<CatalogPosition> {
  return new Promise((resolve, reject) => {
    let settled = false
    const settle = (callback: () => void): void => {
      if (settled) return
      settled = true
      clearTimeout(timeoutId)
      callback()
    }
    const timeoutId = setTimeout(() => {
      settle(() => reject(new Error('POSITION_TIMEOUT')))
    }, POSITION_TIMEOUT_MS)

    uni.getLocation({
      type: 'wgs84',
      success: ({ latitude, longitude }) => {
        settle(() => {
          const valid = Number.isFinite(latitude) && Number.isFinite(longitude)
            && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180
          valid
            ? resolve({ latitude, longitude })
            : reject(new Error('POSITION_INVALID'))
        })
      },
      fail: () => settle(() => reject(new Error('POSITION_UNAVAILABLE'))),
    })
  })
}
