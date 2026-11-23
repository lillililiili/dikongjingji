import { describe, expect, it, vi } from 'vitest'

import experienceRecordsHost from '../../../pages/mine/experience-records.vue?raw'
import mineSource from '../../../pages/mine/index.vue?raw'
import pagesJson from '../../../pages.json?raw'
import channelSource from '../presentation/channel/ExperienceChannel.vue?raw'
import { normalizeInspectionReturnUrl } from '../../inspection/navigation/inspection-routes'
import { describeLastSimulation, loadLastSimulationNote } from '../application/product-last-experience'
import {
  describeSimulationOutcome,
  outcomeOfLocal,
  outcomeOfRecord,
  summariseSimulationOutcome,
} from '../application/simulation-result-view'
import { uploadLocalResult, uploadPendingResults } from '../application/simulation-sync'
import {
  formatTrialRemaining,
  summariseTrial,
  trialRecordLine,
} from '../application/trial-view'
import { createExperienceApi } from '../data/experience-api'
import { createExperienceRecordApi } from '../data/experience-record-api'
import {
  LOCAL_RESULT_LIMIT,
  createLocalSimulationResults,
  type LocalSimulationResult,
  type ResultStorage,
} from '../data/local-simulation-results'
import type { ExperienceRecord } from '../domain/experience-record'
import type { SimulationResult } from '../domain/simulation'
import {
  buildCourseTrialUrl,
  buildExperienceRecordsUrl,
  buildSimulationResultUrl,
  buildSimulationUrl,
  buildSoftwareTrialUrl,
  parseSimulationResultTarget,
} from '../navigation/experience-routes'

// The shape of the HTTP client's ApiRequestError.
class ApiRequestError extends Error {
  constructor(message: string, readonly status: number, readonly code: string | null) { super(message) }
}

const ROBOT = 'b1000000-0000-4000-8000-000000000001'
const SOFTWARE = 'b1000000-0000-4000-8000-000000000004'
const KIT = 'c2000000-0000-4000-8000-000000000101'

const RESULT: SimulationResult = {
  experienceId: ROBOT,
  template: 'ROBOT_OBSTACLE',
  startedAt: '2026-11-02T06:00:00.000Z',
  endedAt: '2026-11-02T06:02:36.000Z',
  durationSeconds: 156,
  levelsCompleted: 3,
  levelsTotal: 3,
  mistakes: 1,
}

function memoryStorage(initial: unknown = null): ResultStorage & { value: unknown } {
  const storage = {
    value: initial,
    read: () => storage.value,
    write: (next: LocalSimulationResult[]) => { storage.value = next },
  }
  return storage
}

function keys() {
  let next = 0
  return () => `key-${++next}`
}

function record(overrides: Partial<ExperienceRecord> = {}): ExperienceRecord {
  return {
    recordId: 'r1',
    experience: {
      experienceId: ROBOT,
      type: 'SIMULATION',
      title: '机器人避障模拟',
      coverImage: null,
      productId: KIT,
      simulationTemplate: 'ROBOT_OBSTACLE',
    },
    status: 'COMPLETED',
    startedAt: RESULT.startedAt,
    endedAt: RESULT.endedAt,
    expiresAt: null,
    result: { score: 95, durationSeconds: 156, levelsCompleted: 3, levelsTotal: 3, mistakes: 1 },
    ...overrides,
  }
}

function trial(overrides: Partial<ExperienceRecord> = {}): ExperienceRecord {
  return record({
    recordId: 't1',
    experience: {
      experienceId: SOFTWARE,
      type: 'SOFTWARE_TRIAL',
      title: '图形化编程软件试用',
      coverImage: null,
      productId: KIT,
      simulationTemplate: null,
    },
    status: 'ACTIVE',
    startedAt: '2026-11-03T06:02:00.000Z',
    endedAt: null,
    expiresAt: '2026-11-03T06:27:00.000Z',
    result: null,
    ...overrides,
  })
}

describe('online experience routes', () => {
  it('carries only stable ids', () => {
    expect(buildSimulationUrl('e 1')).toBe('/pages/experience/simulation?experienceId=e%201')
    expect(buildSimulationResultUrl({ experienceId: 'e', recordId: 'r' }))
      .toBe('/pages/experience/result?experienceId=e&recordId=r')
    expect(buildSimulationResultUrl({ experienceId: 'e', localId: 'l' }))
      .toBe('/pages/experience/result?experienceId=e&localId=l')
    expect(buildSoftwareTrialUrl('e')).toBe('/pages/experience/software-trial?experienceId=e')
    expect(buildCourseTrialUrl('e', 'r')).toBe('/pages/experience/course-trial?experienceId=e&recordId=r')
    expect(buildExperienceRecordsUrl()).toBe('/pages/mine/experience-records?tab=ALL')
  })

  it('reads the result target back, a record before a local result', () => {
    expect(parseSimulationResultTarget({ experienceId: 'e', recordId: 'r', localId: 'l' }))
      .toEqual({ experienceId: 'e', recordId: 'r' })
    expect(parseSimulationResultTarget({ experienceId: 'e', localId: 'l' })).toEqual({ experienceId: 'e', localId: 'l' })
    expect(parseSimulationResultTarget({ experienceId: 'e' })).toBeNull()
    expect(parseSimulationResultTarget(undefined)).toBeNull()
  })

  // The login page only returns to URLs it knows; the task page itself needs no login.
  it('can be returned to after signing in', () => {
    for (const url of [
      buildSimulationResultUrl({ experienceId: 'e', localId: 'l' }),
      buildSimulationResultUrl({ experienceId: 'e', recordId: 'r' }),
      buildSoftwareTrialUrl('e', 'r'),
      buildCourseTrialUrl('e'),
      buildExperienceRecordsUrl('VENUE'),
    ]) {
      expect(normalizeInspectionReturnUrl(url)).toBe(url)
    }
    expect(normalizeInspectionReturnUrl('/pages/experience/result?recordId=r')).toBe('/pages/home/index')
  })

  it('registers the pages, the Mine entry and the channel link', () => {
    for (const path of [
      'pages/experience/simulation',
      'pages/experience/result',
      'pages/experience/software-trial',
      'pages/experience/course-trial',
      'pages/mine/experience-records',
    ]) {
      expect(pagesJson).toContain(`"path": "${path}"`)
    }
    expect(mineSource).toContain("EXPERIENCE_PREVIEW_ENABLED ? [{ code: 'EXPERIENCE_RECORDS', title: '体验记录' }]")
    expect(channelSource).toContain('v-if="EXPERIENCE_PREVIEW_ENABLED"')
    expect(channelSource).toContain('我的体验记录 ›')
    expect(experienceRecordsHost).toContain('v-if="EXPERIENCE_PREVIEW_ENABLED && shown"')
  })
})

describe('experience record API (1.3.0)', () => {
  it('sends the result with its key and reads the record back', async () => {
    const request = vi.fn().mockResolvedValue({ ...record(), experience: { ...record().experience, coverImage: { url: 'http://x/a.jpg', alt: 'a' } } })
    const api = createExperienceRecordApi(request)

    const saved = await api.recordSimulation('token', 'key-1', RESULT)

    expect(request.mock.calls[0][0]).toEqual({
      url: '/api/v1/experience/records',
      method: 'POST',
      data: {
        experienceId: ROBOT,
        startedAt: RESULT.startedAt,
        endedAt: RESULT.endedAt,
        durationSeconds: 156,
        levelsCompleted: 3,
        levelsTotal: 3,
        mistakes: 1,
      },
      headers: { Authorization: 'Bearer token', 'Idempotency-Key': 'key-1' },
    })
    expect(saved.experience.coverImage).toBeNull()
  })

  it('lists, starts and ends with the token', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ total: 0, hasMore: false, items: [] })
      .mockResolvedValue(trial())
    const api = createExperienceRecordApi(request)

    await api.list('token', { experienceId: SOFTWARE, page: 1, size: 1 })
    await api.startTrial('token', SOFTWARE)
    await api.endTrial('token', 't 1')

    expect(request.mock.calls.map(([input]) => [input.method, input.url])).toEqual([
      ['GET', `/api/v1/experience/records?experienceId=${SOFTWARE}&page=1&size=1`],
      ['POST', '/api/v1/experience/trials'],
      ['POST', '/api/v1/experience/records/t%201/end'],
    ])
    expect(request.mock.calls[1][0].data).toEqual({ experienceId: SOFTWARE })
  })

  it('reads one item with its template and trial length', async () => {
    const request = vi.fn().mockResolvedValue({
      experienceId: SOFTWARE, type: 'SOFTWARE_TRIAL', title: '图形化编程软件试用', summary: null,
      coverImage: null, loginRequired: true, productId: KIT, simulationTemplate: null, trialMinutes: 25,
    })
    const item = await createExperienceApi({ request }).loadExperienceItem(SOFTWARE)

    expect(request.mock.calls[0][0]).toEqual({ method: 'GET', url: `/api/v1/experiences/${SOFTWARE}` })
    expect(item).toMatchObject({ trialMinutes: 25, simulationTemplate: null, productId: KIT })
  })
})

describe('results kept on this device', () => {
  it('keeps the newest first, at most twenty, and ignores what is not a result', () => {
    const storage = memoryStorage([{ broken: true }])
    const store = createLocalSimulationResults(storage, keys())
    for (let index = 0; index < LOCAL_RESULT_LIMIT + 2; index += 1) {
      store.save({ ...RESULT, mistakes: index }, { title: '机器人避障模拟', productId: KIT })
    }
    const all = store.all()
    expect(all).toHaveLength(LOCAL_RESULT_LIMIT)
    expect(all[0].mistakes).toBe(LOCAL_RESULT_LIMIT + 1)
    expect(all[0].idempotencyKey).not.toBe(all[0].localId)
    expect(store.pending()).toHaveLength(LOCAL_RESULT_LIMIT)
  })

  it('sends pending results oldest first with their own keys, once', async () => {
    const store = createLocalSimulationResults(memoryStorage(), keys())
    const first = store.save(RESULT, { title: '机器人避障模拟', productId: KIT })
    const second = store.save({ ...RESULT, levelsCompleted: 1 }, { title: '机器人避障模拟', productId: KIT })
    const api = { recordSimulation: vi.fn().mockImplementation(async (_token: string, key: string) => record({ recordId: `r-${key}` })) }

    expect(await uploadPendingResults(api, store, 'token')).toEqual({ kind: 'DONE', uploaded: 2 })
    expect(api.recordSimulation.mock.calls.map(([, key]) => key)).toEqual([first.idempotencyKey, second.idempotencyKey])
    expect(store.find(first.localId)?.recordId).toBe(`r-${first.idempotencyKey}`)
    expect(await uploadPendingResults(api, store, 'token')).toEqual({ kind: 'DONE', uploaded: 0 })
    expect(api.recordSimulation).toHaveBeenCalledTimes(2)
  })

  it('stops on a refused token or a network error, and gives up on what the server will never take', async () => {
    const store = createLocalSimulationResults(memoryStorage(), keys())
    const old = store.save(RESULT, { title: '机器人避障模拟', productId: KIT })
    store.save(RESULT, { title: '机器人避障模拟', productId: KIT })

    const refused = { recordSimulation: vi.fn().mockRejectedValue(new ApiRequestError('no', 401, 'COMMON_UNAUTHORIZED')) }
    expect(await uploadPendingResults(refused, store, 'token')).toEqual({ kind: 'UNAUTHORIZED', uploaded: 0 })
    const offline = { recordSimulation: vi.fn().mockRejectedValue(new Error('offline')) }
    expect(await uploadPendingResults(offline, store, 'token')).toEqual({ kind: 'STOPPED', uploaded: 0 })
    expect(store.pending()).toHaveLength(2)

    const tooOld = { recordSimulation: vi.fn().mockRejectedValue(new ApiRequestError('old', 422, 'EXPERIENCE_RESULT_INVALID')) }
    expect(await uploadLocalResult(tooOld, store, 'token', old)).toBeNull()
    expect(store.find(old.localId)?.recordId).toBe('')
    expect(await uploadLocalResult(tooOld, store, 'token', store.find(old.localId)!)).toBeNull()
    expect(tooOld.recordSimulation).toHaveBeenCalledTimes(1)
    expect(store.pending()).toHaveLength(1)
  })
})

describe('what a result says', () => {
  it('reads like the board for a signed-in run and a guest', () => {
    const signedIn = describeSimulationOutcome(outcomeOfRecord(record())!)
    expect(signedIn).toMatchObject({ completed: true, heroTitle: '挑战完成', heroSub: '得分 95 分' })
    expect(signedIn.stats).toEqual([
      { value: '2′36″', label: '用时', tone: 'PLAIN' },
      { value: '3/3', label: '绕过障碍', tone: 'GOOD' },
      { value: '1 次', label: '轻微碰撞', tone: 'WARN' },
    ])

    const store = createLocalSimulationResults(memoryStorage(), keys())
    const guest = outcomeOfLocal(store.save(
      { ...RESULT, template: 'DRONE_HOVER', levelsCompleted: 1, mistakes: 3, durationSeconds: 108 },
      { title: '无人机起降与悬停', productId: null },
    ))
    expect(guest.score).toBe(15)
    expect(describeSimulationOutcome(guest)).toMatchObject({
      completed: false,
      heroTitle: '还差一点',
      heroSub: '记录只保存在本机，登录后可归档到账户',
    })
    expect(describeSimulationOutcome(guest).stats.map((stat) => stat.label)).toEqual(['用时', '完成关卡', '操作失误'])
    expect(summariseSimulationOutcome(guest)).toBe('未完成 · 停在第 2 关')
    expect(summariseSimulationOutcome(outcomeOfRecord(record())!)).toBe('挑战完成 · 用时 2′36″ · 碰撞 1 次')
    expect(outcomeOfRecord(trial())).toBeNull()
  })

  it('writes the product page line from the latest run, local for a guest', async () => {
    expect(describeLastSimulation(outcomeOfRecord(record())!)).toBe('你在 11/02 体验过“机器人避障模拟”，用时 2′36″')

    const api = { list: vi.fn().mockResolvedValue({ total: 1, hasMore: false, items: [record()] }) }
    const store = createLocalSimulationResults(memoryStorage(), keys())
    expect(await loadLastSimulationNote(api, store, 'token', ROBOT)).toBe('你在 11/02 体验过“机器人避障模拟”，用时 2′36″')
    expect(api.list).toHaveBeenCalledWith('token', { experienceId: ROBOT, page: 1, size: 1 })

    expect(await loadLastSimulationNote(api, store, null, ROBOT)).toBeNull()
    store.save({ ...RESULT, levelsCompleted: 1 }, { title: '机器人避障模拟', productId: KIT })
    expect(await loadLastSimulationNote(api, store, null, ROBOT)).toBe('你在 11/02 体验过“机器人避障模拟”，停在第 2 关')
    const failing = { list: vi.fn().mockRejectedValue(new Error('offline')) }
    expect(await loadLastSimulationNote(failing, store, 'token', ROBOT)).toBeNull()
  })
})

describe('what a trial says', () => {
  const now = new Date('2026-11-03T06:09:00.000Z').getTime()

  it('counts down in minutes, or in days for a course preview', () => {
    expect(formatTrialRemaining(25 * 60000)).toBe('剩余 25:00')
    expect(formatTrialRemaining(61_000)).toBe('剩余 01:01')
    expect(formatTrialRemaining(3 * 3600_000)).toBe('剩余 3 小时')
    expect(formatTrialRemaining(6.5 * 24 * 3600_000)).toBe('剩余 7 天')
    expect(formatTrialRemaining(24 * 3600_000)).toBe('剩余 1 天')
    expect(trialRecordLine(trial(), now)).toBe('试用中 · 剩余 18:00')
  })

  it('says how it ended, in Beijing time', () => {
    const ended = summariseTrial(trial({ status: 'ENDED', endedAt: '2026-11-03T06:09:00.000Z' }), now)
    expect(ended).toEqual({ stage: 'ENDED', endLine: '11/03 14:09 · 你主动结束', lengthText: '7 分钟' })
    const expired = summariseTrial(trial({ status: 'EXPIRED' }), now)
    expect(expired).toEqual({ stage: 'EXPIRED', endLine: '11/03 14:27 · 到期自动结束', lengthText: '25 分钟' })
    expect(trialRecordLine(trial({ status: 'EXPIRED' }), now)).toBe('已到期')
    expect(trialRecordLine(trial({ status: 'ENDED', endedAt: '2026-11-03T06:09:00.000Z' }), now)).toBe('已结束 · 试用 7 分钟')
  })
})
