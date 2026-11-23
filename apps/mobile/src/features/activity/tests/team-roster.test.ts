import { describe, expect, it } from 'vitest'

import {
  describeRoster,
  describeTeamOrderHead,
  isValidLeaderPhone,
  parseBirthMonth,
  parseRosterText,
  rosterEntryFromInput,
  rosterEntryFromParticipant,
  rosterLine,
  teamMemberState,
  teamMissingStep,
  teamOrderPhase,
  teamRuleLine,
  teamSignSummary,
  toTeamMemberInput,
} from '../application/team-roster'
import type { Enrollment, EnrollmentTicket, TeamMemberCheck } from '../domain/enrollment'

const AGES = { minAge: 8, maxAge: 15 }

describe('team roster (board TeamSignup)', () => {
  it('reads pasted lines in the usual spreadsheet forms', () => {
    const { entries, invalidLines } = parseRosterText(
      '姓名 出生年月\n李明轩 2014-05\n张雨桐\t2016/3\n赵子涵，2013.11\n陈可欣 2015年7月\n\n坏行\n未来 2099-01\n',
      new Date('2026-11-03T02:00:00Z'),
    )
    expect(entries.map((entry) => [entry.name, entry.birthMonth])).toEqual([
      ['李明轩', '2014-05'],
      ['张雨桐', '2016-03'],
      ['赵子涵', '2013-11'],
      ['陈可欣', '2015-07'],
    ])
    expect(invalidLines).toEqual(['坏行', '未来 2099-01'])
    expect(parseBirthMonth('201405')).toBe('2014-05')
    expect(parseBirthMonth('2014-13')).toBeNull()
  })

  it('sends an existing person by id and a typed one by name and month', () => {
    expect(toTeamMemberInput(rosterEntryFromParticipant('p1', '王小禾', '2015-03'))).toEqual({ participantId: 'p1' })
    expect(toTeamMemberInput(rosterEntryFromInput(' 刘思远 ', '2016-01'))).toEqual({ name: '刘思远', birthMonth: '2016-01' })
  })

  it('counts only the rows the server accepted', () => {
    const entries = ['李明轩', '王小禾', '王小满'].map((name) => rosterEntryFromInput(name, '2014-05'))
    const checks: TeamMemberCheck[] = [
      { participantId: null, name: '李明轩', birthMonth: '2014-05', ageAtSession: 12, eligible: true, reason: 'ELIGIBLE' },
      { participantId: 'p1', name: '王小禾', birthMonth: '2015-03', ageAtSession: 11, eligible: false, reason: 'ALREADY_ENROLLED' },
      { participantId: null, name: '王小满', birthMonth: '2020-01', ageAtSession: 6, eligible: false, reason: 'UNDER_MIN_AGE' },
    ]
    const roster = describeRoster(entries, checks, AGES)
    expect(roster.eligibleCount).toBe(1)
    expect(roster.issueCount).toBe(2)
    expect(roster.rows.map((row) => row.stateText)).toEqual([
      '符合报名条件',
      '已报名这一场，不能重复报名',
      '未满 8 岁，不符合参加年龄',
    ])
    expect(roster.rows[0].ageText).toBe('12 岁')
    expect(rosterLine(3, 1, 2)).toBe('共 3 人 · 可报名 1 人')
    expect(rosterLine(3, 3, 0)).toBe('共 3 人')
    // A stale answer (different length) is not shown.
    expect(describeRoster(entries, checks.slice(0, 2), AGES).rows[0].stateText).toBe('核对中…')
  })

  it('says what still stops the form', () => {
    const ready = {
      sessionOpen: true, teamName: '子女队', leaderName: '王女士', leaderPhone: '13800002026',
      eligibleCount: 4, rosterRows: 6, checking: false, agreed: true, seatsLeft: 12,
    }
    expect(teamMissingStep(ready)).toBeNull()
    expect(teamMissingStep({ ...ready, leaderPhone: '1380000' })).toBe('请输入 11 位领队手机号')
    expect(teamMissingStep({ ...ready, eligibleCount: 1 })).toBe('团队报名至少 2 人')
    expect(teamMissingStep({ ...ready, eligibleCount: 21 })).toBe('团队报名最多 20 人')
    expect(teamMissingStep({ ...ready, seatsLeft: 3 })).toBe('本场只剩 3 个名额')
    expect(teamMissingStep({ ...ready, agreed: false })).toBe('请先勾选同意')
    expect(teamMissingStep({ ...ready, sessionOpen: false })).toBe('请先选择一个可报名的场次')
    expect(isValidLeaderPhone('23800002026')).toBe(false)
  })
})

function ticket(name: string, status: EnrollmentTicket['status'], checkedInAt: string | null = null): EnrollmentTicket {
  return {
    ticketId: `t-${name}`, participantId: `p-${name}`, participantName: name, relation: 'MEMBER', age: 12,
    ticketCode: 'ACT-2611-0928-0011', status, checkedInAt, cancelledAt: null, refund: null, result: null,
  }
}

function team(tickets: EnrollmentTicket[], overrides: Partial<Enrollment> = {}): Enrollment {
  return {
    enrollmentId: 'e1',
    status: 'UPCOMING',
    activity: { activityId: 'a1', type: 'CONTEST', title: '挑战赛', coverImage: null, cityCode: '370500', organizerName: '体验馆' },
    session: { sessionId: 's1', label: '上午', startsAt: '2026-11-15T01:00:00Z', endsAt: '2026-11-15T03:30:00Z', checkInOpensAt: '2026-11-15T00:30:00Z' },
    location: { name: '体验馆', address: '示例路', latitude: null, longitude: null },
    createdAt: '2026-11-03T01:00:00Z',
    cancellableUntil: '2026-11-15T01:00:00Z',
    cancellable: true,
    tickets,
    payment: { status: 'PAID', tradeOrderId: 'o1', amountMinor: 48000, unitAmountMinor: 12000, payBefore: null },
    kind: 'TEAM',
    team: { teamName: '子女队', leaderName: '王女士', leaderPhoneMasked: '138****2026' },
    reschedule: null,
    changes: [],
    reschedulable: true,
    ...overrides,
  }
}

describe('team order (board TeamOrder)', () => {
  const four = [ticket('李明轩', 'CHECKED_IN', '2026-11-15T00:41:00Z'), ticket('张雨桐', 'ACTIVE'), ticket('赵子涵', 'ACTIVE'), ticket('陈可欣', 'ACTIVE')]

  it('waits before the day and counts check-ins on it', () => {
    const before = Date.parse('2026-11-03T03:00:00Z')
    const onDay = Date.parse('2026-11-15T00:45:00Z')
    expect(teamOrderPhase(team(four), before)).toBe('UPCOMING')
    expect(describeTeamOrderHead(team(four), before).title).toBe('报名成功 · 待参加')
    expect(teamSignSummary(team(four), before)).toBe('4 人 · 一人一码')
    expect(teamOrderPhase(team(four), onDay)).toBe('ON_DAY')
    expect(describeTeamOrderHead(team(four), onDay).title).toBe('签到中 · 1/4 已签到')
    expect(teamSignSummary(team(four), onDay)).toBe('1/4 已签到')
    expect(teamMemberState(four[0]).text).toBe('已签到 08:41')
    expect(teamMemberState(four[1])).toEqual({ text: '待签到', tone: 'PENDING' })
  })

  it('tells until when members can be cancelled', () => {
    expect(teamRuleLine(team(four))).toEqual({ text: '11/15 09:00 前可在线取消（整单或按人）', open: true })
    expect(teamRuleLine(team(four, { cancellable: false })).open).toBe(false)
    expect(teamOrderPhase(team(four, { payment: { status: 'PENDING_PAYMENT', tradeOrderId: 'o1', amountMinor: 48000, unitAmountMinor: 12000, payBefore: '2026-11-03T03:15:00Z' } }), Date.parse('2026-11-03T03:00:00Z')))
      .toBe('PENDING_PAYMENT')
  })
})
