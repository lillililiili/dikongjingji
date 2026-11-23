import type { ActivitySession } from '../domain/activity'
import type { ChangeSummary, Enrollment, EnrollmentTicket } from '../domain/enrollment'

// Fixtures for the S5-3 views: a sign-up of two children at ¥120 on 15 November 09:00–11:30 (Beijing).
export function s53Ticket(name: string, overrides: Partial<EnrollmentTicket> = {}): EnrollmentTicket {
  return {
    ticketId: `t-${name}`,
    participantId: `p-${name}`,
    participantName: name,
    relation: 'CHILD',
    age: 11,
    ticketCode: 'ACT-1234-5678-9ABC',
    status: 'ACTIVE',
    checkedInAt: null,
    cancelledAt: null,
    refund: null,
    result: null,
    ...overrides,
  }
}

export function s53Enrollment(overrides: Partial<Enrollment> = {}): Enrollment {
  return {
    enrollmentId: 'e1',
    status: 'UPCOMING',
    activity: {
      activityId: 'a1',
      type: 'CONTEST',
      title: '青少年无人机编程挑战赛（东营站）',
      coverImage: null,
      cityCode: '370500',
      organizerName: '东营低空与机器人体验馆',
    },
    session: {
      sessionId: 's-am',
      label: '上午',
      startsAt: '2026-11-15T01:00:00Z',
      endsAt: '2026-11-15T03:30:00Z',
      checkInOpensAt: '2026-11-15T00:30:00Z',
    },
    location: { name: '东营低空与机器人体验馆', address: '示例路 66 号', latitude: null, longitude: null },
    createdAt: '2026-11-03T01:00:00Z',
    cancellableUntil: '2026-11-15T01:00:00Z',
    cancellable: true,
    tickets: [s53Ticket('王小禾'), s53Ticket('王小川', { age: 9 })],
    payment: { status: 'PAID', tradeOrderId: 'o1', amountMinor: 24000, unitAmountMinor: 12000, payBefore: null },
    kind: 'INDIVIDUAL',
    team: null,
    reschedule: null,
    changes: [],
    reschedulable: true,
    ...overrides,
  }
}

export function s53Session(id: string, startsAt: string, overrides: Partial<ActivitySession> = {}): ActivitySession {
  return {
    sessionId: id,
    label: null,
    startsAt,
    endsAt: null,
    checkInOpensAt: null,
    enrollmentClosesAt: startsAt,
    capacity: 30,
    seatsLeft: 10,
    status: 'OPEN',
    ...overrides,
  }
}

export function s53Change(overrides: Partial<ChangeSummary> = {}): ChangeSummary {
  return {
    changeId: 'c1',
    kind: 'CANCEL',
    reason: 'TIME_CONFLICT',
    people: 2,
    fromSession: { sessionId: 's-am', label: '上午', startsAt: '2026-11-15T01:00:00Z' },
    toSession: null,
    refundAmountMinor: 24000,
    refundStatus: 'PROCESSING',
    refundSucceededAt: null,
    createdAt: '2026-11-03T02:12:00Z',
    ...overrides,
  }
}
