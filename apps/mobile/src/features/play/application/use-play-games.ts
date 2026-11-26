import { ref } from 'vue'
import { publicRequest } from '@/shared/http/public-api-request'
import { listPlayGames } from '../data/play-game-api'
import type { PlayGame } from '../domain/play-game'

export function usePlayGames() {
  const games = ref<PlayGame[]>([])
  const state = ref<'loading' | 'ready' | 'error'>('loading')
  let revision = 0
  async function load() {
    const current = ++revision
    state.value = 'loading'
    try {
      const rows = await listPlayGames({ request: publicRequest })
      if (current === revision) {
        games.value = rows
        state.value = 'ready'
      }
    } catch {
      if (current === revision) {
        games.value = []
        state.value = 'error'
      }
    }
  }
  return { games, state, load }
}
