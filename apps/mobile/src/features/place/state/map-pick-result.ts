import { defineStore } from 'pinia'

import type { PickedPlace } from '../domain/map-place'

// Hands the confirmed place back to the form that opened the picker. The form makes a request id,
// opens the picker with it and takes the answer for that id when it is shown again.
interface MapPickResultState {
  result: { requestId: string; place: PickedPlace } | null
}

export const useMapPickResultStore = defineStore('map-pick-result', {
  state: (): MapPickResultState => ({ result: null }),
  actions: {
    put(requestId: string, place: PickedPlace): void {
      this.result = { requestId, place }
    },
    take(requestId: string): PickedPlace | null {
      if (!this.result || this.result.requestId !== requestId) return null
      const place = this.result.place
      this.result = null
      return place
    },
  },
})

export function createMapPickRequestId(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`
}
