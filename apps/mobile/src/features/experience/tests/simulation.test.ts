import { describe, expect, it } from 'vitest'

import {
  buildSimulationResult,
  createSimulationSession,
  isTaskFinished,
  levelGoal,
  levelLabel,
  nextLevel,
  runCurrentLevel,
  withBlockAdded,
  withBlockRemoved,
  type SimulationSession,
} from '../application/simulation-session'
import {
  DRONE_LEVELS,
  MAX_PROGRAM_BLOCKS,
  ROBOT_LEVELS,
  addBlock,
  cellAhead,
  formatSimulationClock,
  formatSimulationDuration,
  runDrone,
  runRobot,
  simulationScore,
  type SimulationBlock,
} from '../domain/simulation'

// The solutions of the three levels of each template, as a player would build them.
const ROBOT_SOLUTIONS: SimulationBlock[][] = [
  ['FORWARD', 'TURN_LEFT', 'FORWARD', 'TURN_RIGHT', 'FORWARD', 'FORWARD', 'TURN_RIGHT', 'FORWARD'],
  ['FORWARD', 'TURN_LEFT', 'FORWARD', 'TURN_RIGHT', 'FORWARD', 'FORWARD', 'FORWARD'],
  ['FORWARD', 'FORWARD', 'TURN_RIGHT', 'FORWARD', 'FORWARD', 'FORWARD', 'FORWARD', 'TURN_LEFT', 'FORWARD', 'FORWARD'],
]
const DRONE_SOLUTIONS: SimulationBlock[][] = [
  ['TAKEOFF', 'THROTTLE', 'HOVER', 'LAND'],
  ['TAKEOFF', 'THROTTLE', 'THROTTLE', 'THROTTLE', 'HOVER', 'LAND'],
  ['TAKEOFF', 'THROTTLE', 'THROTTLE', 'HOVER', 'THROTTLE', 'THROTTLE', 'HOVER', 'LAND'],
]

function play(session: SimulationSession, program: SimulationBlock[]): SimulationSession {
  return runCurrentLevel(program.reduce(withBlockAdded, session))
}

describe('robot obstacle', () => {
  it('has a solution for every level that fits the program area', () => {
    ROBOT_LEVELS.forEach((level, index) => {
      const run = runRobot(level, ROBOT_SOLUTIONS[index])
      expect(run.passed, `level ${index + 1}`).toBe(true)
      expect(run.bumps).toBe(0)
      expect(ROBOT_SOLUTIONS[index].length).toBeLessThanOrEqual(MAX_PROGRAM_BLOCKS)
      expect(level.obstacles).not.toContain(level.goal)
    })
  })

  it('counts a move into an obstacle or the edge as a bump and stays put', () => {
    const run = runRobot(ROBOT_LEVELS[0], ['FORWARD', 'FORWARD', 'TURN_RIGHT', 'TURN_RIGHT', 'FORWARD', 'FORWARD'])
    expect(run.steps.map((step) => step.event)).toEqual(['MOVED', 'BUMPED', 'TURNED', 'TURNED', 'MOVED', 'BUMPED'])
    expect(run.bumps).toBe(2)
    expect(run.passed).toBe(false)
    expect(cellAhead(10, 'W')).toBeNull()
    expect(cellAhead(0, 'N')).toBeNull()
  })

  it('runs the block after 如果 only when the way ahead is blocked, and stops at the goal', () => {
    const blocked = runRobot(ROBOT_LEVELS[0], ['FORWARD', 'IF_BLOCKED', 'TURN_LEFT', 'FORWARD'])
    expect(blocked.steps.map((step) => step.event)).toEqual(['MOVED', 'CHECKED', 'TURNED', 'MOVED'])
    expect(blocked.steps.at(-1)?.cell).toBe(6)

    const clear = runRobot(ROBOT_LEVELS[0], ['IF_BLOCKED', 'TURN_LEFT', 'FORWARD'])
    expect(clear.steps.map((step) => step.event)).toEqual(['CHECKED', 'SKIPPED', 'MOVED'])

    const past = runRobot(ROBOT_LEVELS[0], [...ROBOT_SOLUTIONS[0], 'FORWARD', 'FORWARD'])
    expect(past.steps.at(-1)?.event).toBe('GOAL')
    expect(past.passed).toBe(true)
  })
})

describe('drone hover', () => {
  it('has a solution for every level', () => {
    DRONE_LEVELS.forEach((level, index) => {
      const run = runDrone(level, DRONE_SOLUTIONS[index])
      expect(run.passed, `level ${index + 1}`).toBe(true)
      expect(run.mistakes).toBe(0)
    })
  })

  it('names why a run failed and counts the moves that make no sense', () => {
    expect(runDrone(DRONE_LEVELS[0], ['THROTTLE', 'HOVER'])).toMatchObject({ failure: 'NEVER_TOOK_OFF', mistakes: 2 })
    expect(runDrone(DRONE_LEVELS[0], ['TAKEOFF', 'THROTTLE', 'HOVER'])).toMatchObject({ failure: 'STILL_FLYING', mistakes: 0 })
    expect(runDrone(DRONE_LEVELS[0], ['TAKEOFF', 'HOVER', 'LAND'])).toMatchObject({ failure: 'LANDED_EARLY', mistakes: 1 })
    expect(runDrone(DRONE_LEVELS[0], ['TAKEOFF', 'TAKEOFF', 'THROTTLE', 'HOVER', 'LAND']))
      .toMatchObject({ passed: true, mistakes: 1 })
    const ceiling = Array<SimulationBlock>(9).fill('THROTTLE')
    expect(runDrone(DRONE_LEVELS[0], ['TAKEOFF', ...ceiling]).steps.at(-1)?.event).toBe('CEILING')
  })
})

describe('the task', () => {
  it('keeps the program area at twelve blocks', () => {
    const full = Array<SimulationBlock>(MAX_PROGRAM_BLOCKS).fill('FORWARD')
    expect(addBlock(full, 'TURN_LEFT')).toHaveLength(MAX_PROGRAM_BLOCKS)
    expect(withBlockRemoved({ ...createSimulationSession('ROBOT_OBSTACLE'), program: ['FORWARD', 'TURN_LEFT'] }, 0).program)
      .toEqual(['TURN_LEFT'])
  })

  it('goes through three levels, adding up mistakes, and builds the result', () => {
    let session = createSimulationSession('ROBOT_OBSTACLE')
    expect(levelLabel(session)).toBe('关卡 1 / 3')
    expect(levelGoal(session)).toBe('绕过障碍，走到绿色终点')

    session = play(session, ['FORWARD', 'FORWARD'])
    expect(session.lastRun).toMatchObject({ passed: false, message: '撞到障碍 1 次，没到终点。改一改程序再运行' })
    expect(nextLevel(session)).toBe(session)

    for (const [index, solution] of ROBOT_SOLUTIONS.entries()) {
      session = play({ ...session, program: [] }, solution)
      expect(session.lastRun?.message).toBe(`第 ${index + 1} 关完成`)
      session = nextLevel(session)
    }
    expect(isTaskFinished(session)).toBe(true)
    expect(session.mistakes).toBe(1)
    expect(session.runs).toBe(4)

    const started = new Date('2026-11-22T02:00:00Z')
    expect(buildSimulationResult(session, 'e', started, new Date('2026-11-22T02:02:36.900Z'))).toEqual({
      experienceId: 'e',
      template: 'ROBOT_OBSTACLE',
      startedAt: '2026-11-22T02:00:00.000Z',
      endedAt: '2026-11-22T02:02:36.900Z',
      durationSeconds: 156,
      levelsCompleted: 3,
      levelsTotal: 3,
      mistakes: 1,
    })
  })

  it('does not count an empty program as a run and writes the drone goals in metres', () => {
    const session = createSimulationSession('DRONE_HOVER')
    expect(runCurrentLevel(session)).toBe(session)
    expect(levelGoal(session)).toBe('升到 1.5 m 悬停，再降落')
    expect(levelGoal({ ...session, levelIndex: 2 })).toBe('先在 2.0 m 悬停，再升到 3.0 m 悬停，最后降落')
    expect(play(session, ['TAKEOFF', 'THROTTLE']).lastRun?.message).toBe('程序结束时还在空中，最后要降落')
  })

  it('scores like the server and writes times like the boards', () => {
    expect(simulationScore(3, 3, 1)).toBe(95)
    expect(simulationScore(1, 3, 3)).toBe(15)
    expect(simulationScore(0, 3, 9)).toBe(0)
    expect(formatSimulationDuration(156)).toBe('2′36″')
    expect(formatSimulationDuration(48)).toBe('48″')
    expect(formatSimulationClock(108_400)).toBe('01:48')
  })
})
