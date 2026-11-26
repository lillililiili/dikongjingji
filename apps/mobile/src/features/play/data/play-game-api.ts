import type { PublicApiHttpClient } from '@/shared/http/public-api-request'
import { PLAY_BOARD_SIZE, type PlayGame } from '../domain/play-game'

type GameRow = Omit<PlayGame, 'walls' | 'stars' | 'goal'> & { board: string }

export function decodeGame({ board, ...info }: GameRow): PlayGame {
  if (board.length !== PLAY_BOARD_SIZE ** 2 || !/^\.[.WSG]+$/.test(board)
      || !board.includes('S') || board.indexOf('G') < 0 || board.indexOf('G') !== board.lastIndexOf('G')) {
    throw new Error('关卡配置无效')
  }
  const walls: number[] = []
  const stars: number[] = []
  for (let i = 0; i < board.length; i++) {
    if (board[i] === 'W') walls.push(i)
    if (board[i] === 'S') stars.push(i)
  }
  return { ...info, walls, stars, goal: board.indexOf('G') }
}

export async function listPlayGames(http: PublicApiHttpClient): Promise<PlayGame[]> {
  const rows = await http.request<GameRow[]>({ method: 'GET', url: '/api/v1/experience/play-games' })
  return rows.map(decodeGame)
}
