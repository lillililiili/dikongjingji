export type PlayGameTone = 'VIOLET' | 'SKY'
export type PlayAvatar = 'ROBOT' | 'DRONE'

export interface PlayGame {
  id: string
  title: string
  subtitle: string
  skillLabel: string
  tone: PlayGameTone
  avatar: PlayAvatar
  // Cell indexes on the board, counted row by row from the top-left corner.
  walls: readonly number[]
  stars: readonly number[]
  goal: number
}

export const PLAY_BOARD_SIZE = 5
export const PLAY_START_CELL = 0

export type PlayDirection = 'UP' | 'DOWN' | 'LEFT' | 'RIGHT'

export type PlayOutcome =
  | 'READY'
  | 'RESTARTED'
  | 'MOVED'
  | 'STAR_COLLECTED'
  | 'BLOCKED_BY_EDGE'
  | 'BLOCKED_BY_WALL'
  | 'GOAL_MISSING_STARS'
  | 'WON'

export interface PlaySession {
  position: number
  moves: number
  collected: readonly number[]
  won: boolean
  outcome: PlayOutcome
}

export function createPlaySession(
  outcome: 'READY' | 'RESTARTED' = 'READY',
): PlaySession {
  return {
    position: PLAY_START_CELL,
    moves: 0,
    collected: [],
    won: false,
    outcome,
  }
}

const STEPS: Record<PlayDirection, { column: number; row: number }> = {
  UP: { column: 0, row: -1 },
  DOWN: { column: 0, row: 1 },
  LEFT: { column: -1, row: 0 },
  RIGHT: { column: 1, row: 0 },
}

export function movePlaySession(
  game: PlayGame,
  session: PlaySession,
  direction: PlayDirection,
): PlaySession {
  if (session.won) return session

  const step = STEPS[direction]
  const column = (session.position % PLAY_BOARD_SIZE) + step.column
  const row = Math.floor(session.position / PLAY_BOARD_SIZE) + step.row
  const outsideBoard = column < 0
    || column >= PLAY_BOARD_SIZE
    || row < 0
    || row >= PLAY_BOARD_SIZE
  if (outsideBoard) return { ...session, outcome: 'BLOCKED_BY_EDGE' }

  const position = row * PLAY_BOARD_SIZE + column
  if (game.walls.includes(position)) {
    return { ...session, outcome: 'BLOCKED_BY_WALL' }
  }

  const foundStar = game.stars.includes(position)
    && !session.collected.includes(position)
  const collected = foundStar
    ? [...session.collected, position]
    : session.collected
  const moves = session.moves + 1

  // Reaching the goal only counts once every star is collected, so the route has to be planned.
  if (position === game.goal) {
    const won = collected.length === game.stars.length
    return {
      position,
      moves,
      collected,
      won,
      outcome: won ? 'WON' : 'GOAL_MISSING_STARS',
    }
  }

  return {
    position,
    moves,
    collected,
    won: false,
    outcome: foundStar ? 'STAR_COLLECTED' : 'MOVED',
  }
}

export function describePlayOutcome(
  game: PlayGame,
  session: PlaySession,
): string {
  const total = game.stars.length
  const collected = session.collected.length

  switch (session.outcome) {
    case 'READY':
      return `收集 ${total} 颗星星，再到达右下角终点。深色格是障碍。`
    case 'RESTARTED':
      return `新的挑战开始！收集 ${total} 颗星星，再到达终点。`
    case 'BLOCKED_BY_EDGE':
      return '到边界啦，试试其他方向。'
    case 'BLOCKED_BY_WALL':
      return '前方有障碍，换条路线吧！'
    case 'STAR_COLLECTED':
      return `找到一颗星星！已收集 ${collected} / ${total}。`
    case 'GOAL_MISSING_STARS':
      return `到达终点啦，还差 ${total - collected} 颗星星。回去找找吧！`
    case 'WON':
      return '全部星星收集完成，成功到达终点！'
    case 'MOVED':
      return '继续规划路线，收集沿途的星星。'
  }
}

export type PlayCellKind = 'AVATAR' | 'WALL' | 'STAR' | 'GOAL' | 'EMPTY'

export interface PlayCell {
  index: number
  kind: PlayCellKind
  isGoal: boolean
  label: string
}

export function resolvePlayCells(
  game: PlayGame,
  session: PlaySession,
): readonly PlayCell[] {
  return Array.from({ length: PLAY_BOARD_SIZE * PLAY_BOARD_SIZE }, (_, index) => {
    const isGoal = index === game.goal
    const kind: PlayCellKind = index === session.position
      ? 'AVATAR'
      : game.walls.includes(index)
        ? 'WALL'
        : game.stars.includes(index) && !session.collected.includes(index)
          ? 'STAR'
          : isGoal
            ? 'GOAL'
            : 'EMPTY'
    const place = `第 ${Math.floor(index / PLAY_BOARD_SIZE) + 1} 行第 ${(index % PLAY_BOARD_SIZE) + 1} 列`
    const detail: Record<PlayCellKind, string> = {
      AVATAR: '，当前位置',
      WALL: '，障碍',
      STAR: '，星星',
      GOAL: '，终点',
      EMPTY: '',
    }
    return { index, kind, isGoal, label: `${place}${detail[kind]}` }
  })
}

export function formatPlayDuration(milliseconds: number): string {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000))
  const minutesText = String(Math.floor(seconds / 60)).padStart(2, '0')
  const secondsText = String(seconds % 60).padStart(2, '0')
  return `${minutesText}:${secondsText}`
}
