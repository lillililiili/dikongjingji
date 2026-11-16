import { describe, expect, it } from 'vitest'

import {
  PLAY_BOARD_SIZE,
  PLAY_GAMES,
  PLAY_START_CELL,
  createPlaySession,
  describePlayOutcome,
  findPlayGame,
  formatPlayDuration,
  movePlaySession,
  resolvePlayCells,
  type PlayDirection,
  type PlayGame,
  type PlaySession,
} from '../domain/play-game'

const DIRECTIONS: readonly PlayDirection[] = ['UP', 'DOWN', 'LEFT', 'RIGHT']

function play(
  game: PlayGame,
  directions: readonly PlayDirection[],
  from: PlaySession = createPlaySession(),
): PlaySession {
  return directions.reduce(
    (session, direction) => movePlaySession(game, session, direction),
    from,
  )
}

// Breadth-first search over (cell, collected stars): proves a level can be finished at all.
function findWinningRoute(game: PlayGame): PlayDirection[] | null {
  const key = (session: PlaySession): string =>
    `${session.position}:${[...session.collected].sort((a, b) => a - b).join(',')}`
  const start = createPlaySession()
  const queue: { session: PlaySession; route: PlayDirection[] }[] = [
    { session: start, route: [] },
  ]
  const seen = new Set([key(start)])

  while (queue.length) {
    const { session, route } = queue.shift()!
    for (const direction of DIRECTIONS) {
      const next = movePlaySession(game, session, direction)
      if (next.won) return [...route, direction]
      if (next.moves === session.moves || seen.has(key(next))) continue
      seen.add(key(next))
      queue.push({ session: next, route: [...route, direction] })
    }
  }
  return null
}

const maze = findPlayGame('robot-maze')!

describe('play games', () => {
  it('offers the two levels of the revised prototype', () => {
    expect(PLAY_GAMES.map((game) => game.title)).toEqual(['机器人迷宫', '无人机寻星'])
    expect(findPlayGame('drone-route')?.avatar).toBe('DRONE')
    expect(findPlayGame('unknown')).toBeUndefined()
    expect(findPlayGame(undefined)).toBeUndefined()
  })

  it.each(PLAY_GAMES)('keeps $title solvable and its pieces apart', (game) => {
    const cells = PLAY_BOARD_SIZE * PLAY_BOARD_SIZE
    const placed = [...game.walls, ...game.stars, game.goal, PLAY_START_CELL]
    expect(new Set(placed).size).toBe(placed.length)
    expect(placed.every((cell) => cell >= 0 && cell < cells)).toBe(true)

    const route = findWinningRoute(game)
    expect(route).not.toBeNull()
    const finished = play(game, route!)
    expect(finished.won).toBe(true)
    expect(finished.collected).toHaveLength(game.stars.length)
    expect(finished.moves).toBe(route!.length)
  })
})

describe('play session', () => {
  it('starts in the top-left corner with nothing collected', () => {
    expect(createPlaySession()).toEqual({
      position: 0,
      moves: 0,
      collected: [],
      won: false,
      outcome: 'READY',
    })
  })

  it('stops at the edge without counting a move', () => {
    const session = play(maze, ['UP'])
    expect(session.position).toBe(0)
    expect(session.moves).toBe(0)
    expect(session.outcome).toBe('BLOCKED_BY_EDGE')
    expect(play(maze, ['LEFT']).outcome).toBe('BLOCKED_BY_EDGE')
  })

  it('stops at a wall without counting a move', () => {
    // Cell 1 sits above wall 6.
    const session = play(maze, ['RIGHT', 'DOWN'])
    expect(session.position).toBe(1)
    expect(session.moves).toBe(1)
    expect(session.outcome).toBe('BLOCKED_BY_WALL')
  })

  it('collects each star once', () => {
    const first = play(maze, ['RIGHT', 'RIGHT'])
    expect(first.collected).toEqual([2])
    expect(first.outcome).toBe('STAR_COLLECTED')

    const again = play(maze, ['LEFT', 'RIGHT'], first)
    expect(again.collected).toEqual([2])
    expect(again.outcome).toBe('MOVED')
    expect(again.moves).toBe(4)
  })

  it('does not finish at the goal while stars are missing', () => {
    const route: PlayDirection[] = [
      'RIGHT', 'RIGHT', 'RIGHT', 'RIGHT', 'DOWN', 'DOWN', 'DOWN', 'DOWN',
    ]
    const session = play(maze, route)
    expect(session.position).toBe(maze.goal)
    expect(session.won).toBe(false)
    expect(session.outcome).toBe('GOAL_MISSING_STARS')
    expect(describePlayOutcome(maze, session)).toBe('到达终点啦，还差 2 颗星星。回去找找吧！')

    // The level stays playable after the early arrival.
    expect(play(maze, ['UP'], session).position).toBe(19)
  })

  it('ignores moves after the level is won', () => {
    const won = play(maze, findWinningRoute(maze)!)
    expect(won.outcome).toBe('WON')
    expect(play(maze, ['UP', 'LEFT'], won)).toBe(won)
  })

  it('tells the player what happened in plain words', () => {
    expect(describePlayOutcome(maze, createPlaySession()))
      .toBe('收集 3 颗星星，再到达右下角终点。深色格是障碍。')
    expect(describePlayOutcome(maze, createPlaySession('RESTARTED')))
      .toBe('新的挑战开始！收集 3 颗星星，再到达终点。')
    expect(describePlayOutcome(maze, play(maze, ['RIGHT', 'RIGHT'])))
      .toBe('找到一颗星星！已收集 1 / 3。')
    expect(describePlayOutcome(maze, play(maze, ['RIGHT'])))
      .toBe('继续规划路线，收集沿途的星星。')
    expect(describePlayOutcome(maze, play(maze, ['UP'])))
      .toBe('到边界啦，试试其他方向。')
    expect(describePlayOutcome(maze, play(maze, ['RIGHT', 'DOWN'])))
      .toBe('前方有障碍，换条路线吧！')
  })
})

describe('play board', () => {
  it('draws the avatar, walls, remaining stars and the goal', () => {
    const cells = resolvePlayCells(maze, play(maze, ['RIGHT', 'RIGHT']))
    expect(cells).toHaveLength(25)
    expect(cells[2]).toMatchObject({ kind: 'AVATAR', label: '第 1 行第 3 列，当前位置' })
    expect(cells[6].kind).toBe('WALL')
    expect(cells[10].kind).toBe('STAR')
    expect(cells[24]).toMatchObject({ kind: 'GOAL', isGoal: true })
    expect(cells[1].kind).toBe('EMPTY')
    // The star under the avatar is collected, so it does not come back when the avatar leaves.
    expect(resolvePlayCells(maze, play(maze, ['RIGHT', 'RIGHT', 'LEFT']))[2].kind).toBe('EMPTY')
  })

  it('formats the round time as minutes and seconds', () => {
    expect(formatPlayDuration(0)).toBe('00:00')
    expect(formatPlayDuration(59_999)).toBe('00:59')
    expect(formatPlayDuration(61_000)).toBe('01:01')
    expect(formatPlayDuration(-5)).toBe('00:00')
  })
})
