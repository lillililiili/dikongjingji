import {
  DRONE_LEVELS,
  ROBOT_LEVELS,
  SIMULATION_LEVEL_COUNT,
  addBlock,
  formatMetres,
  removeBlock,
  runDrone,
  runRobot,
  type DroneFailure,
  type DroneStep,
  type RobotStep,
  type SimulationBlock,
  type SimulationResult,
  type SimulationTemplate,
} from '../domain/simulation'

// One simulation task on the page (board ExpSim): three levels in a row. A run passes or fails the
// level; a failed level is edited and run again. Bumps and wrong moves add up over the whole task.

export interface RunOutcome {
  passed: boolean
  message: string
  // The steps to animate, block by block.
  robotSteps: RobotStep[]
  droneSteps: DroneStep[]
}

export interface SimulationSession {
  template: SimulationTemplate
  // 0-based; equals SIMULATION_LEVEL_COUNT when every level is passed.
  levelIndex: number
  levelsCompleted: number
  mistakes: number
  runs: number
  program: SimulationBlock[]
  // The last run of the current level, until the program changes or the level does.
  lastRun: RunOutcome | null
}

export function createSimulationSession(template: SimulationTemplate): SimulationSession {
  return { template, levelIndex: 0, levelsCompleted: 0, mistakes: 0, runs: 0, program: [], lastRun: null }
}

export function isTaskFinished(session: SimulationSession): boolean {
  return session.levelsCompleted >= SIMULATION_LEVEL_COUNT
}

/** 关卡 2 / 3 */
export function levelLabel(session: SimulationSession): string {
  return `关卡 ${Math.min(session.levelIndex + 1, SIMULATION_LEVEL_COUNT)} / ${SIMULATION_LEVEL_COUNT}`
}

export function levelGoal(session: SimulationSession): string {
  if (session.template === 'ROBOT_OBSTACLE') return '绕过障碍，走到绿色终点'
  const targets = DRONE_LEVELS[Math.min(session.levelIndex, DRONE_LEVELS.length - 1)].targets.map(formatMetres)
  return targets.length === 1
    ? `升到 ${targets[0]} 悬停，再降落`
    : `先在 ${targets[0]} 悬停，再升到 ${targets[1]} 悬停，最后降落`
}

export function withBlockAdded(session: SimulationSession, block: SimulationBlock): SimulationSession {
  return { ...session, program: addBlock(session.program, block), lastRun: null }
}

export function withBlockRemoved(session: SimulationSession, index: number): SimulationSession {
  return { ...session, program: removeBlock(session.program, index), lastRun: null }
}

export function withProgramCleared(session: SimulationSession): SimulationSession {
  return { ...session, program: [], lastRun: null }
}

const DRONE_FAILURES: Record<Exclude<DroneFailure, null>, string> = {
  NEVER_TOOK_OFF: '还没起飞，先加一块“起飞”',
  STILL_FLYING: '程序结束时还在空中，最后要降落',
  LANDED_EARLY: '还没在目标高度悬停就降落了',
}

/** Runs the program on the current level. An empty program is not a run. */
export function runCurrentLevel(session: SimulationSession): SimulationSession {
  if (session.program.length === 0 || isTaskFinished(session)) return session
  const level = session.levelIndex + 1
  let outcome: RunOutcome
  let mistakes: number
  if (session.template === 'ROBOT_OBSTACLE') {
    const run = runRobot(ROBOT_LEVELS[session.levelIndex], session.program)
    mistakes = run.bumps
    const bumps = run.bumps > 0 ? `，碰撞 ${run.bumps} 次` : ''
    outcome = {
      passed: run.passed,
      message: run.passed
        ? `第 ${level} 关完成${bumps}`
        : run.bumps > 0
          ? `撞到障碍 ${run.bumps} 次，没到终点。改一改程序再运行`
          : '没到终点，再加几块积木试试',
      robotSteps: run.steps,
      droneSteps: [],
    }
  } else {
    const run = runDrone(DRONE_LEVELS[session.levelIndex], session.program)
    mistakes = run.mistakes
    const wrong = run.mistakes > 0 ? `，失误 ${run.mistakes} 次` : ''
    outcome = {
      passed: run.passed,
      message: run.passed
        ? `第 ${level} 关完成${wrong}`
        : `${DRONE_FAILURES[run.failure ?? 'STILL_FLYING']}${wrong}`,
      robotSteps: [],
      droneSteps: run.steps,
    }
  }
  return {
    ...session,
    runs: session.runs + 1,
    mistakes: session.mistakes + mistakes,
    levelsCompleted: session.levelsCompleted + (outcome.passed ? 1 : 0),
    lastRun: outcome,
  }
}

/** After a passed run: the next level with an empty program. */
export function nextLevel(session: SimulationSession): SimulationSession {
  if (!session.lastRun?.passed) return session
  return { ...session, levelIndex: session.levelsCompleted, program: [], lastRun: null }
}

/** The numbers sent to the server (or kept on the device for a guest). */
export function buildSimulationResult(
  session: SimulationSession,
  experienceId: string,
  startedAt: Date,
  endedAt: Date,
): SimulationResult {
  return {
    experienceId,
    template: session.template,
    startedAt: startedAt.toISOString(),
    endedAt: endedAt.toISOString(),
    // At least one second: the server keeps 1–7200.
    durationSeconds: Math.min(7200, Math.max(1, Math.floor((endedAt.getTime() - startedAt.getTime()) / 1000))),
    levelsCompleted: Math.min(session.levelsCompleted, SIMULATION_LEVEL_COUNT),
    levelsTotal: SIMULATION_LEVEL_COUNT,
    mistakes: Math.min(999, session.mistakes),
  }
}
