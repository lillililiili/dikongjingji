import { describe, expect, it } from 'vitest'

import { parseCheckinCode } from '../application/checkin-code'
import {
  dayTitle,
  daySummary,
  exceptionChips,
  exceptionCopy,
  exceptionOptions,
  failureLine,
  groupRecords,
  paidText,
  pickDefaultSession,
  recentAdmissions,
  refusalLine,
  resolutionLines,
  scopeHint,
  sessionChips,
  sessionKey,
  tabCounts,
  toRecordRow,
  toSessionRow,
} from '../application/workbench-view'

import { DAY, SESSION_1000, SESSION_1400, SESSION_1530, mismatch, record, repeated } from './staff-fixtures'

describe('check-in codes', () => {
  it('takes a scanned or typed code in any case, with or without dashes, and refuses anything else', () => {
    expect(parseCheckinCode('ACT-7K2M-9QXD-4HTP')).toBe('ACT-7K2M-9QXD-4HTP')
    expect(parseCheckinCode(' ven-7k2m-9qxd-0314 ')).toBe('VEN-7K2M-9QXD-0314')
    expect(parseCheckinCode('act 7k2m 9qxd 4htp')).toBe('ACT-7K2M-9QXD-4HTP')
    expect(parseCheckinCode('ACT7K2M9QXD4HTP')).toBe('ACT-7K2M-9QXD-4HTP')
    expect(parseCheckinCode('https://example.com/t?code=ACT-7K2M-9QXD-4HTP')).toBe('ACT-7K2M-9QXD-4HTP')
    expect(parseCheckinCode('ORD-7K2M-9QXD-4HTP')).toBeNull()
    expect(parseCheckinCode('4HTP')).toBeNull()
    expect(parseCheckinCode('')).toBeNull()
  })
})

describe('workbench sessions (board StaffVerify)', () => {
  const sessions = [SESSION_1000, SESSION_1400, SESSION_1530]

  it('starts on the session checking in now, keeps a picked one and falls back to the next or the last', () => {
    expect(pickDefaultSession(sessions, null)).toBe(sessionKey(SESSION_1400))
    expect(pickDefaultSession(sessions, sessionKey(SESSION_1530))).toBe(sessionKey(SESSION_1530))
    expect(pickDefaultSession(sessions, 'VENUE:gone')).toBe(sessionKey(SESSION_1400))
    expect(pickDefaultSession([SESSION_1000, SESSION_1530], null)).toBe(sessionKey(SESSION_1530))
    expect(pickDefaultSession([SESSION_1000], null)).toBe(sessionKey(SESSION_1000))
    expect(pickDefaultSession([], null)).toBeNull()
  })

  it('draws the board numbers, units, tones and day in Beijing time', () => {
    const live = toSessionRow(SESSION_1400)
    expect(live).toMatchObject({ time: '14:00', sub: '14:00 场 · 进行中', done: 3, total: 8, unit: '已核销 / 应到', percent: '37.5%' })
    expect(live.tone.barColor).toBe('#ff6a00')
    expect(toSessionRow(SESSION_1000)).toMatchObject({ unit: '到场 / 应到', percent: '100%' })
    expect(toSessionRow(SESSION_1530).tone.status).toBe('未开始')
    expect(toSessionRow({ ...SESSION_1530, expectedPeople: 0 }).percent).toBe('0%')
    expect(dayTitle('2026-11-03')).toBe('11/03 周二')
  })

  it('says what the operator may verify', () => {
    expect(scopeHint(['VENUE'])).toBe('仅能核销本场馆场次，服务端校验场馆与场次归属')
    expect(scopeHint(['ACTIVITY'])).toContain('主办或承办')
    expect(scopeHint(['ACTIVITY', 'VENUE'])).toContain('活动和场馆')
  })
})

describe('results and records', () => {
  it('writes the result lines of the board', () => {
    expect(failureLine(mismatch(), DAY)).toBe('场次不符：凭证为 15:30 机器人闯关场')
    expect(failureLine(repeated(), DAY)).toBe('重复核销：该凭证已于 14:02 核销')
    expect(failureLine(record({ outcome: 'EXPIRED', ticketSession: { ...mismatch().ticketSession!, startsAt: '2026-11-22T06:00:00Z' } }), DAY))
      .toBe('凭证已过期：凭证为 11/22 14:00 机器人闯关场')
    expect(failureLine(record({ outcome: 'OTHER_ORGANIZATION', ticketSession: null }), DAY)).toContain('不属于本机构')
    expect(refusalLine('COMMON_NOT_FOUND')).toBe('没有这张凭证，请核对凭证号')
    expect(refusalLine('CHECKIN_NOT_OPEN')).toBe('还没到这一场的核销时间')
    expect(paidText(3900)).toBe('已支付 ¥39')
    expect(paidText(3950)).toBe('已支付 ¥39.50')
    expect(paidText(0)).toBe('免费')
    expect(paidText(null)).toBe('')
  })

  it('marks rows as checked in, let in by hand, or not checked in', () => {
    expect(toRecordRow(record())).toMatchObject({ title: '王** · 1 人', sub: '14:05 · 扫码', tag: '已核销', bad: false })
    expect(toRecordRow(record({ method: 'MANUAL' })).sub).toBe('14:05 · 手动输入凭证号')
    expect(toRecordRow(mismatch())).toMatchObject({ sub: '14:08 · 场次不符', tag: '未核销', bad: true, dotBg: '#ff6a00' })
    const released = mismatch({
      allowedActions: [],
      resolution: { action: 'RELEASE', identityChecked: true, note: null, resolvedAt: '2026-11-23T06:09:00Z', admittedSession: mismatch().currentSession },
    })
    expect(toRecordRow(released)).toMatchObject({ tag: '已放行', dotBg: '#159a70' })
    expect(toRecordRow(record({ holderName: null, outcome: 'OTHER_ORGANIZATION' })).title).toBe('持有人未知 · 1 人')
  })

  it('lists only what was let in under 最近核销', () => {
    const items = [mismatch(), record({ recordId: 'b8000000-0000-4000-8000-0000000000a1' }), repeated(),
      record({ recordId: 'b8000000-0000-4000-8000-0000000000a2' }), record({ recordId: 'b8000000-0000-4000-8000-0000000000a3' }),
      record({ recordId: 'b8000000-0000-4000-8000-0000000000a4' })]
    expect(recentAdmissions(items).map((item) => item.recordId)).toEqual([
      'b8000000-0000-4000-8000-0000000000a1', 'b8000000-0000-4000-8000-0000000000a2', 'b8000000-0000-4000-8000-0000000000a3'])
  })
})

describe('the records page (board StaffRecords)', () => {
  const sessions = [SESSION_1400, SESSION_1000, SESSION_1530]
  const records = [mismatch(), repeated(), record()]
  const all = { tab: 'all' as const, session: '', keyword: '' }

  it('groups by the session being checked, keeps empty sessions and counts the tabs', () => {
    const groups = groupRecords(records, sessions, all, DAY)
    expect(groups.map((group) => group.title)).toEqual(['14:00 机器人避障闯关', '10:00 模拟飞行', '15:30 机器人闯关'])
    expect(groups[0].rows).toHaveLength(3)
    expect(groups[0]).toMatchObject({ done: 3, total: 8 })
    expect(groups[1].empty).toBe(true)
    expect(tabCounts(records)).toEqual({ all: 3, ok: 1, bad: 2 })
    expect(daySummary(sessions)).toEqual({ done: 9, total: 16 })
  })

  it('filters by tab, session and name or code tail, and drops empty groups while filtering', () => {
    const bad = groupRecords(records, sessions, { ...all, tab: 'bad' }, DAY)
    expect(bad).toHaveLength(1)
    expect(bad[0].rows.map((row) => row.recordId)).toEqual([mismatch().recordId, repeated().recordId])
    expect(groupRecords(records, sessions, { ...all, keyword: '3762' }, DAY)[0].rows).toHaveLength(1)
    expect(groupRecords(records, sessions, { ...all, keyword: '孙' }, DAY)[0].rows[0].recordId).toBe(mismatch().recordId)
    expect(groupRecords(records, sessions, { ...all, session: sessionKey(SESSION_1000) }, DAY)).toHaveLength(1)
    expect(groupRecords(records, sessions, { ...all, keyword: '没有这个人' }, DAY)).toEqual([])
  })

  it('names a chip by its time, and adds the title when two sessions share it', () => {
    expect(sessionChips(sessions).map((chip) => chip.label)).toEqual(['全部场次', '14:00 场', '10:00 场', '15:30 场'])
    const twin = { ...SESSION_1400, sessionId: 'b6000000-0000-4000-8000-00000000140b', title: '编程机器人' }
    expect(sessionChips([SESSION_1400, twin]).map((chip) => chip.label)).toEqual(['全部场次', '14:00 机器人避障闯关', '14:00 编程机器人'])
  })
})

describe('the exception page (board StaffException)', () => {
  it('words a mismatch the board way and offers only what the server allows', () => {
    const copy = exceptionCopy(mismatch(), DAY)
    expect(copy).toMatchObject({ title: '场次不符，需人工核验', fact: '凭证为 15:30 场，当前核销 14:00 场', blocked: false })
    expect(exceptionOptions(mismatch(), DAY).map((option) => option.title)).toEqual(['不予核销'])
    const releasable = mismatch({ allowedActions: ['RELEASE', 'REJECT'] })
    expect(exceptionOptions(releasable, DAY)).toEqual([
      { action: 'RELEASE', title: '本场有余位，改签并核销', sub: '释放 15:30 名额，记录改签原因', needsIdentity: true },
      { action: 'REJECT', title: '不予核销', sub: '提示按原场次 15:30 参加', needsIdentity: false },
    ])
  })

  it('blocks a repeated code without options and records what was decided', () => {
    expect(exceptionCopy(repeated(), DAY)).toMatchObject({ title: '重复核销，已拦截', blocked: true, fact: '该凭证已于 14:02 核销' })
    expect(exceptionOptions(repeated(), DAY)).toEqual([])
    const blocked = repeated({
      allowedActions: [],
      resolution: { action: 'BLOCK', identityChecked: false, note: null, resolvedAt: '2026-11-23T06:07:00Z', admittedSession: null },
    })
    expect(resolutionLines(blocked, DAY)).toEqual({ act: '拦截，不予入场', reason: '重复核销（该凭证已于 14:02 核销）' })
    const rejected = mismatch({
      allowedActions: [],
      resolution: { action: 'REJECT', identityChecked: false, note: '已告知', resolvedAt: '2026-11-23T06:09:00Z', admittedSession: null },
    })
    expect(resolutionLines(rejected, DAY)).toEqual({ act: '不予核销（拒绝入场）', reason: '凭证属于 15:30 场，按原场次参加' })
    expect(resolutionLines(mismatch(), DAY)).toBeNull()
  })

  it('shows the exceptions still waiting, plus the one opened, and tells two of a kind apart', () => {
    const settled = repeated({ allowedActions: [], recordId: 'b8000000-0000-4000-8000-0000000000b1' })
    const second = mismatch({ recordId: 'a7000000-0000-4000-8000-0000000000b2', codeTail: '7731' })
    const chips = exceptionChips([record(), mismatch(), settled, second], null)
    expect(chips.map((chip) => chip.label)).toEqual(['场次不符 0815', '场次不符 7731'])
    expect(exceptionChips([settled], settled.recordId).map((chip) => chip.label)).toEqual(['重复核销'])
  })
})
