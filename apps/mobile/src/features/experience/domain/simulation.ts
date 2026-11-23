// The two in-App simulation templates (S5-4, default decision D7; board ExpSim). The levels live in
// the App; the server only knows each template has SIMULATION_LEVEL_COUNT levels
// (backend SimulationTemplate) and checks the reported numbers against it.
//
// The user builds a program from blocks and runs it. A run either passes the level or fails it;
// a failed run can be edited and run again. Every bump (robot) or wrong move (drone) counts as a
// mistake for the whole task, which lowers the score.

export type SimulationTemplate = 'ROBOT_OBSTACLE' | 'DRONE_HOVER'

export type RobotBlock = 'FORWARD' | 'TURN_LEFT' | 'TURN_RIGHT' | 'IF_BLOCKED'
export type DroneBlock = 'TAKEOFF' | 'THROTTLE' | 'HOVER' | 'LAND'
export type SimulationBlock = RobotBlock | DroneBlock

export const SIMULATION_LEVEL_COUNT = 3
// Board ExpSim: more than 12 blocks push 重置 / 运行程序 off the screen, so the palette greys out.
export const MAX_PROGRAM_BLOCKS = 12

// The words in the program area (board `prog`).
export const BLOCK_TEXT: Record<SimulationBlock, string> = {
  FORWARD: '前进 1 格',
  TURN_LEFT: '左转 90°',
  TURN_RIGHT: '右转 90°',
  IF_BLOCKED: '如果 前方有障碍',
  TAKEOFF: '起飞',
  THROTTLE: '油门 +0.5 m',
  HOVER: '悬停 3 秒',
  LAND: '降落',
}

// Board colours: move / throttle orange, turn / take-off / land blue, sense / hover green.
export const BLOCK_COLOURS: Record<SimulationBlock, { fill: string; text: string }> = {
  FORWARD: { fill: '#ff6a00', text: '#ba470b' },
  TURN_LEFT: { fill: '#2f80ed', text: '#1d63c4' },
  TURN_RIGHT: { fill: '#2f80ed', text: '#1d63c4' },
  IF_BLOCKED: { fill: '#159a70', text: '#11804a' },
  THROTTLE: { fill: '#ff6a00', text: '#ba470b' },
  TAKEOFF: { fill: '#2f80ed', text: '#1d63c4' },
  HOVER: { fill: '#159a70', text: '#11804a' },
  LAND: { fill: '#2f80ed', text: '#1d63c4' },
}

// The palette in the board's order, with its short labels.
export const PALETTE: Record<SimulationTemplate, ReadonlyArray<{ block: SimulationBlock; label: string }>> = {
  ROBOT_OBSTACLE: [
    { block: 'FORWARD', label: '前进' },
    { block: 'TURN_LEFT', label: '左转' },
    { block: 'TURN_RIGHT', label: '右转' },
    { block: 'IF_BLOCKED', label: '如果…' },
  ],
  DRONE_HOVER: [
    { block: 'THROTTLE', label: '油门' },
    { block: 'TAKEOFF', label: '起飞' },
    { block: 'HOVER', label: '悬停' },
    { block: 'LAND', label: '降落' },
  ],
}

export const TEMPLATE_TITLES: Record<SimulationTemplate, string> = {
  ROBOT_OBSTACLE: '机器人避障模拟',
  DRONE_HOVER: '无人机起降与悬停',
}

export function addBlock(program: readonly SimulationBlock[], block: SimulationBlock): SimulationBlock[] {
  return program.length >= MAX_PROGRAM_BLOCKS ? [...program] : [...program, block]
}

export function removeBlock(program: readonly SimulationBlock[], index: number): SimulationBlock[] {
  return program.filter((_, position) => position !== index)
}

// ---- Robot obstacle ----

export const ROBOT_GRID_SIZE = 5
export type Heading = 'N' | 'E' | 'S' | 'W'

export interface RobotLevel {
  // Cells counted row by row from the top-left corner.
  start: number
  heading: Heading
  goal: number
  obstacles: readonly number[]
}

export const ROBOT_LEVELS: readonly RobotLevel[] = [
  { start: 10, heading: 'E', goal: 13, obstacles: [12] },
  { start: 15, heading: 'E', goal: 14, obstacles: [17, 7] },
  { start: 0, heading: 'E', goal: 24, obstacles: [3, 11, 18] },
]

const LEFT_OF: Record<Heading, Heading> = { N: 'W', W: 'S', S: 'E', E: 'N' }
const RIGHT_OF: Record<Heading, Heading> = { N: 'E', E: 'S', S: 'W', W: 'N' }

/** The cell ahead, or null past the edge of the grid. */
export function cellAhead(cell: number, heading: Heading): number | null {
  const row = Math.floor(cell / ROBOT_GRID_SIZE)
  const col = cell % ROBOT_GRID_SIZE
  const [nextRow, nextCol] = {
    N: [row - 1, col],
    S: [row + 1, col],
    E: [row, col + 1],
    W: [row, col - 1],
  }[heading]
  if (nextRow < 0 || nextCol < 0 || nextRow >= ROBOT_GRID_SIZE || nextCol >= ROBOT_GRID_SIZE) return null
  return nextRow * ROBOT_GRID_SIZE + nextCol
}

export type RobotEvent = 'MOVED' | 'TURNED' | 'BUMPED' | 'CHECKED' | 'SKIPPED' | 'GOAL'

export interface RobotStep {
  blockIndex: number
  event: RobotEvent
  cell: number
  heading: Heading
}

export interface RobotRun {
  steps: RobotStep[]
  passed: boolean
  bumps: number
}

/**
 * Runs a program on a level. 如果 前方有障碍 guards the next block: it runs only when the cell ahead is
 * an obstacle or the edge. A move into an obstacle or the edge is a bump: the robot stays. Reaching
 * the goal ends the run at once.
 */
export function runRobot(level: RobotLevel, program: readonly SimulationBlock[]): RobotRun {
  const blocked = (cell: number | null) => cell === null || level.obstacles.includes(cell)
  let cell = level.start
  let heading = level.heading
  let bumps = 0
  const steps: RobotStep[] = []
  let index = 0
  while (index < program.length) {
    const block = program[index]
    if (block === 'IF_BLOCKED') {
      steps.push({ blockIndex: index, event: 'CHECKED', cell, heading })
      if (blocked(cellAhead(cell, heading))) {
        index += 1
      } else {
        if (index + 1 < program.length) steps.push({ blockIndex: index + 1, event: 'SKIPPED', cell, heading })
        index += 2
      }
      continue
    }
    if (block === 'FORWARD') {
      const next = cellAhead(cell, heading)
      if (blocked(next)) {
        bumps += 1
        steps.push({ blockIndex: index, event: 'BUMPED', cell, heading })
      } else {
        cell = next as number
        const reached = cell === level.goal
        steps.push({ blockIndex: index, event: reached ? 'GOAL' : 'MOVED', cell, heading })
        if (reached) break
      }
    } else if (block === 'TURN_LEFT' || block === 'TURN_RIGHT') {
      heading = block === 'TURN_LEFT' ? LEFT_OF[heading] : RIGHT_OF[heading]
      steps.push({ blockIndex: index, event: 'TURNED', cell, heading })
    }
    // Drone blocks are not in the robot's palette; one that slipped in does nothing.
    index += 1
  }
  return { steps, passed: cell === level.goal, bumps }
}

// ---- Drone hover ----

// Heights are in decimetres so that 1.5 m is exact.
export const DRONE_TAKEOFF_DM = 10
export const DRONE_THROTTLE_DM = 5
export const DRONE_CEILING_DM = 50

export interface DroneLevel {
  // Heights to hover at, in this order, before landing.
  targets: readonly number[]
}

export const DRONE_LEVELS: readonly DroneLevel[] = [
  { targets: [15] },
  { targets: [25] },
  { targets: [20, 30] },
]

export type DroneEvent =
  | 'TOOK_OFF'
  | 'CLIMBED'
  | 'HOVERED'
  | 'LANDED'
  | 'LANDED_EARLY'
  | 'ALREADY_FLYING'
  | 'NOT_FLYING'
  | 'WRONG_HEIGHT'
  | 'CEILING'

export interface DroneStep {
  blockIndex: number
  event: DroneEvent
  heightDm: number
  // Index of the next target to hover at after this step.
  nextTarget: number
}

export type DroneFailure = 'STILL_FLYING' | 'LANDED_EARLY' | 'NEVER_TOOK_OFF' | null

export interface DroneRun {
  steps: DroneStep[]
  passed: boolean
  mistakes: number
  failure: DroneFailure
}

const DRONE_MISTAKES: ReadonlySet<DroneEvent> = new Set(['ALREADY_FLYING', 'NOT_FLYING', 'WRONG_HEIGHT', 'CEILING'])

/**
 * Take off to 1 m, throttle adds 0.5 m (up to 5 m), hover counts only at the next target height,
 * landing after every target passes the level; landing earlier ends the run as failed. A block that
 * makes no sense where the drone is (throttle on the ground, take off in the air) is a mistake.
 */
export function runDrone(level: DroneLevel, program: readonly SimulationBlock[]): DroneRun {
  let height = 0
  let flying = false
  let tookOff = false
  let nextTarget = 0
  let landed = false
  const steps: DroneStep[] = []
  const push = (blockIndex: number, event: DroneEvent) =>
    steps.push({ blockIndex, event, heightDm: height, nextTarget })
  for (let index = 0; index < program.length && !landed; index += 1) {
    const block = program[index]
    if (block === 'TAKEOFF') {
      if (flying) push(index, 'ALREADY_FLYING')
      else {
        flying = true
        tookOff = true
        height = DRONE_TAKEOFF_DM
        push(index, 'TOOK_OFF')
      }
    } else if (block === 'THROTTLE') {
      if (!flying) push(index, 'NOT_FLYING')
      else if (height + DRONE_THROTTLE_DM > DRONE_CEILING_DM) push(index, 'CEILING')
      else {
        height += DRONE_THROTTLE_DM
        push(index, 'CLIMBED')
      }
    } else if (block === 'HOVER') {
      if (!flying) push(index, 'NOT_FLYING')
      else if (nextTarget < level.targets.length && height === level.targets[nextTarget]) {
        nextTarget += 1
        push(index, 'HOVERED')
      } else push(index, 'WRONG_HEIGHT')
    } else if (block === 'LAND') {
      if (!flying) push(index, 'NOT_FLYING')
      else {
        flying = false
        height = 0
        landed = true
        push(index, nextTarget === level.targets.length ? 'LANDED' : 'LANDED_EARLY')
      }
    }
  }
  const passed = landed && nextTarget === level.targets.length
  const failure: DroneFailure = passed
    ? null
    : !tookOff
      ? 'NEVER_TOOK_OFF'
      : flying
        ? 'STILL_FLYING'
        : 'LANDED_EARLY'
  return {
    steps,
    passed,
    mistakes: steps.filter((step) => DRONE_MISTAKES.has(step.event)).length,
    failure,
  }
}

export function formatMetres(decimetres: number): string {
  return `${(decimetres / 10).toFixed(1)} m`
}

// ---- The task ----

/**
 * Same rule as the server (ExperienceRecordService.score): the levels passed give up to 90, passing
 * all of them adds 10, every mistake takes 5, never below 0. Shown for a guest, whose result the
 * server has not scored.
 */
export function simulationScore(levelsCompleted: number, levelsTotal: number, mistakes: number): number {
  const base = Math.round((90 * levelsCompleted) / levelsTotal)
  const bonus = levelsCompleted === levelsTotal ? 10 : 0
  return Math.max(0, Math.min(100, base + bonus - 5 * mistakes))
}

export interface SimulationResult {
  experienceId: string
  template: SimulationTemplate
  startedAt: string
  endedAt: string
  durationSeconds: number
  levelsCompleted: number
  levelsTotal: number
  mistakes: number
}

export function isCompleted(result: Pick<SimulationResult, 'levelsCompleted' | 'levelsTotal'>): boolean {
  return result.levelsCompleted === result.levelsTotal
}

/** 2′36″, as the boards write the time. */
export function formatSimulationDuration(seconds: number): string {
  const whole = Math.max(0, Math.round(seconds))
  const minutes = Math.floor(whole / 60)
  const rest = whole % 60
  return minutes > 0 ? `${minutes}′${String(rest).padStart(2, '0')}″` : `${rest}″`
}

/** 01:48 on the task page's clock. */
export function formatSimulationClock(milliseconds: number): string {
  const whole = Math.max(0, Math.floor(milliseconds / 1000))
  return `${String(Math.floor(whole / 60)).padStart(2, '0')}:${String(whole % 60).padStart(2, '0')}`
}
