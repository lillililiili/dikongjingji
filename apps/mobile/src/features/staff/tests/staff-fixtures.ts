import type { CheckinRecord, CheckinSession } from '../domain/checkin'

// The board's afternoon (北京时间 2026-11-23): 10:00 模拟飞行 is over, 14:00 机器人避障闯关 is on,
// 15:30 机器人闯关 has not started.
export const DAY = '2026-11-23'

export const SESSION_1000: CheckinSession = {
  kind: 'VENUE',
  sessionId: 'b6000000-0000-4000-8000-000000001000',
  title: '模拟飞行',
  placeName: '东营低空与机器人体验馆',
  label: null,
  startsAt: '2026-11-23T02:00:00Z',
  endsAt: '2026-11-23T03:00:00Z',
  checkInOpensAt: '2026-11-23T01:30:00Z',
  checkInClosesAt: '2026-11-23T03:00:00Z',
  phase: 'ENDED',
  expectedPeople: 6,
  checkedInPeople: 6,
}

export const SESSION_1400: CheckinSession = {
  ...SESSION_1000,
  sessionId: 'b6000000-0000-4000-8000-000000001400',
  title: '机器人避障闯关',
  startsAt: '2026-11-23T06:00:00Z',
  endsAt: '2026-11-23T07:00:00Z',
  checkInOpensAt: '2026-11-23T05:30:00Z',
  checkInClosesAt: '2026-11-23T07:00:00Z',
  phase: 'OPEN',
  expectedPeople: 8,
  checkedInPeople: 3,
}

export const SESSION_1530: CheckinSession = {
  ...SESSION_1000,
  kind: 'ACTIVITY',
  sessionId: 'a3000000-0000-4000-8000-000000001530',
  title: '机器人闯关',
  label: '下午场',
  startsAt: '2026-11-23T07:30:00Z',
  endsAt: '2026-11-23T08:30:00Z',
  checkInOpensAt: '2026-11-23T07:00:00Z',
  checkInClosesAt: '2026-11-23T08:30:00Z',
  phase: 'UPCOMING',
  expectedPeople: 2,
  checkedInPeople: 0,
}

const ref1400 = { kind: 'VENUE' as const, sessionId: SESSION_1400.sessionId, title: SESSION_1400.title, label: null, startsAt: SESSION_1400.startsAt }
const ref1530 = { kind: 'ACTIVITY' as const, sessionId: SESSION_1530.sessionId, title: SESSION_1530.title, label: '下午场', startsAt: SESSION_1530.startsAt }

export function record(overrides: Partial<CheckinRecord> = {}): CheckinRecord {
  return {
    kind: 'VENUE',
    recordId: 'b8000000-0000-4000-8000-000000000001',
    outcome: 'CHECKED_IN',
    method: 'SCAN',
    occurredAt: '2026-11-23T06:05:00Z',
    codeTail: '0314',
    holderName: '王**',
    people: 1,
    amountMinor: 3900,
    ticketSession: ref1400,
    currentSession: ref1400,
    firstCheckedInAt: null,
    allowedActions: [],
    resolution: null,
    ...overrides,
  }
}

/** 孙** 的 15:30 凭证在 14:00 场被扫：场次不符。 */
export function mismatch(overrides: Partial<CheckinRecord> = {}): CheckinRecord {
  return record({
    kind: 'ACTIVITY',
    recordId: 'a7000000-0000-4000-8000-000000000002',
    outcome: 'SESSION_MISMATCH',
    occurredAt: '2026-11-23T06:08:00Z',
    codeTail: '0815',
    holderName: '孙**',
    amountMinor: 0,
    ticketSession: ref1530,
    currentSession: ref1400,
    allowedActions: ['REJECT'],
    ...overrides,
  })
}

export function repeated(overrides: Partial<CheckinRecord> = {}): CheckinRecord {
  return record({
    recordId: 'b8000000-0000-4000-8000-000000000003',
    outcome: 'REPEATED',
    occurredAt: '2026-11-23T06:06:00Z',
    codeTail: '3762',
    holderName: '李**',
    firstCheckedInAt: '2026-11-23T06:02:00Z',
    allowedActions: ['BLOCK'],
    ...overrides,
  })
}

export const SESSION_REF_1530 = ref1530
export const SESSION_REF_1400 = ref1400
