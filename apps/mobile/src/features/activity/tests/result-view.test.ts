import { describe, expect, it } from 'vitest'

import { describeResult, hasResultPage, resultPhase } from '../application/result-view'
import { s53Enrollment, s53Ticket } from './result-change-fixtures'

// The session runs 09:00–11:30 on 15 November, Beijing time.
const BEFORE = Date.parse('2026-11-14T02:00:00Z')
const DURING = Date.parse('2026-11-15T01:45:00Z')
const AFTER = Date.parse('2026-11-15T08:00:00Z')

const signed = (name: string, at: string, extra = {}) =>
  s53Ticket(name, { status: 'CHECKED_IN', checkedInAt: at, ...extra })

describe('result page phases', () => {
  it('follows the session time and the check-ins', () => {
    expect(resultPhase(s53Enrollment(), BEFORE)).toBe('UPCOMING')
    expect(resultPhase(s53Enrollment(), DURING)).toBe('IN_PROGRESS')
    expect(resultPhase(s53Enrollment({ tickets: [signed('王小禾', '2026-11-15T00:42:00Z')] }), AFTER)).toBe('COMPLETED')
    expect(resultPhase(s53Enrollment({ tickets: [s53Ticket('王小禾', { status: 'EXPIRED' })] }), AFTER)).toBe('ABSENT')
  })

  it('shows the sign-in records while it runs', () => {
    const view = describeResult(s53Enrollment({
      tickets: [signed('王小禾', '2026-11-15T00:42:00Z'), signed('王小川', '2026-11-15T00:43:00Z', { age: 9 })],
    }), DURING)
    expect(view.heroTitle).toBe('已签到 · 活动进行中')
    expect(view.heroSub).toBe('2 人都已签到，活动结束后这里显示结果记录')
    expect(view.people.map((person) => person.state)).toEqual(['08:42 已签到', '08:43 已签到'])
    expect(view.peopleNote).toBe('2 / 2 人')
    expect(view.steps.map((step) => step.kind)).toEqual(['DONE', 'DONE', 'DONE', 'CURRENT'])
    expect(view.facts[2]).toEqual({ label: '活动时间', value: '09:00–11:30', strong: false })
    expect(view.bar).toBe('NOTE')
  })

  it('shows the results of the organiser once it is over', () => {
    const result = { completion: 'COMPLETED' as const, scoreText: '5/5', certificateNote: '完赛证书现场发放', recordedAt: '2026-11-15T08:00:00Z' }
    const view = describeResult(s53Enrollment({
      tickets: [signed('王小禾', '2026-11-15T00:42:00Z', { result }), signed('王小川', '2026-11-15T00:43:00Z', {
        age: 9, result: { ...result, completion: 'INCOMPLETE' as const, scoreText: null, recordedAt: '2026-11-15T07:00:00Z' },
      })],
    }), AFTER)
    expect(view.heroTitle).toBe('活动已完成')
    expect(view.heroSub).toBe('11/15 11:30 结束 · 主办方已发布结果记录')
    expect(view.publishedLine).toBe('主办方 11/15 16:00 发布')
    expect(view.records.map((record) => [record.name, record.badge, record.stats.map((stat) => stat.value)])).toEqual([
      ['王小禾', '完成', ['完成', '5/5', '08:42']],
      ['王小川', '未完成', ['未完成', '—', '08:43']],
    ])
    expect(view.certificateNotes).toEqual(['完赛证书现场发放'])
    expect(view.showNext).toBe(true)
    expect(view.parties.map((row) => row.label)).toEqual(['主办', '收款方', '退改处理'])
  })

  it('tells someone who did not come', () => {
    const view = describeResult(s53Enrollment({ tickets: [s53Ticket('王小禾', { status: 'EXPIRED' })] }), AFTER)
    expect(view.heroTitle).toBe('未到场')
    expect(view.grey).toBe(true)
    expect(view.steps.map((step) => step.title)).toEqual(['已支付', '报名成功', '未签到', '完成'])
    expect(view.noticeTitle).toBe('费用说明')
  })

  it('shows the old and new time after a reschedule', () => {
    const view = describeResult(s53Enrollment({
      reschedule: { previousSession: { sessionId: 's0', label: '上午', startsAt: '2026-11-08T01:00:00Z' }, rescheduledAt: '2026-11-03T02:00:00Z' },
    }), BEFORE)
    expect(view.move).toEqual({ from: '11/08 上午 09:00', to: '11/15 上午 09:00' })
  })

  it('is offered on the ticket page from the start of the session or once a result is in', () => {
    expect(hasResultPage(s53Enrollment(), BEFORE)).toBe(false)
    expect(hasResultPage(s53Enrollment(), DURING)).toBe(true)
  })
})
