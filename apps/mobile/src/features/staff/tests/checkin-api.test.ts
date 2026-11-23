import { describe, expect, it, vi } from 'vitest'

import { loadStaffAccess } from '../application/staff-access'
import { createCheckinApi } from '../data/checkin-api'
import {
  buildStaffExceptionUrl,
  buildStaffRecordsUrl,
  parseStaffExceptionQuery,
  parseStaffRecordsQuery,
} from '../navigation/staff-routes'

import { mismatch, record } from './staff-fixtures'

class RequestError extends Error {
  constructor(readonly status: number, readonly code: string | null, readonly details: Record<string, unknown> | null = null) {
    super(code ?? 'error')
  }
}

describe('check-in workbench API', () => {
  it('reads the workbench with the token and an optional date, dropping rows it cannot draw', async () => {
    const request = vi.fn().mockResolvedValue({
      date: '2026-11-23',
      kinds: ['ACTIVITY', 'VENUE', 'SOMETHING'],
      sessions: [
        { kind: 'VENUE', sessionId: 's1', title: '机器人避障闯关', startsAt: 'a', checkInOpensAt: 'b', checkInClosesAt: 'c', phase: 'OPEN', expectedPeople: 8, checkedInPeople: 3 },
        { kind: 'VENUE', sessionId: 's2', phase: 'LATER' },
      ],
    })
    const api = createCheckinApi(request)

    const workbench = await api.workbench('token', '2026-11-20')

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/checkin-workbench?date=2026-11-20',
      method: 'GET',
      headers: { Authorization: 'Bearer token' },
    })
    expect(workbench.kinds).toEqual(['ACTIVITY', 'VENUE'])
    expect(workbench.sessions).toHaveLength(1)
    expect(workbench.sessions[0]).toMatchObject({ title: '机器人避障闯关', placeName: null, label: null, endsAt: null })
  })

  it('posts a verification with the current session and turns a 409 into the stored record', async () => {
    const stored = mismatch()
    const request = vi.fn()
      .mockResolvedValueOnce(record())
      .mockRejectedValueOnce(new RequestError(409, 'CHECKIN_SESSION_MISMATCH', { record: stored }))
      .mockRejectedValueOnce(new RequestError(409, 'CHECKIN_NOT_OPEN'))
      .mockRejectedValueOnce(new RequestError(404, 'COMMON_NOT_FOUND'))
      .mockRejectedValueOnce(new RequestError(0, 'NETWORK_ERROR'))
    const api = createCheckinApi(request)
    const input = { code: 'VEN-7K2M-9QXD-0314', method: 'SCAN' as const, session: { kind: 'VENUE' as const, sessionId: 's1' } }

    expect(await api.verify('token', input)).toEqual({ kind: 'checked-in', record: record() })
    expect(request).toHaveBeenLastCalledWith({
      url: '/api/v1/checkin-workbench/verifications',
      method: 'POST',
      headers: { Authorization: 'Bearer token' },
      data: { code: 'VEN-7K2M-9QXD-0314', method: 'SCAN', sessionKind: 'VENUE', sessionId: 's1' },
    })
    expect(await api.verify('token', { ...input, session: null })).toEqual({ kind: 'rejected', code: 'CHECKIN_SESSION_MISMATCH', record: stored })
    expect(request.mock.calls[1][0].data).toEqual({ code: 'VEN-7K2M-9QXD-0314', method: 'SCAN' })
    expect(await api.verify('token', input)).toEqual({ kind: 'refused', code: 'CHECKIN_NOT_OPEN' })
    expect(await api.verify('token', input)).toEqual({ kind: 'refused', code: 'COMMON_NOT_FOUND' })
    await expect(api.verify('token', input)).rejects.toBeInstanceOf(RequestError)
  })

  it('resolves a record by kind and id, leaving out an empty note', async () => {
    const request = vi.fn().mockResolvedValue(mismatch({ allowedActions: [] }))
    const api = createCheckinApi(request)

    await api.resolve('token', 'ACTIVITY', 'a7000000-0000-4000-8000-000000000002', { action: 'REJECT', identityChecked: false, note: '  ' })
    await api.resolve('token', 'ACTIVITY', 'a7000000-0000-4000-8000-000000000002', { action: 'RELEASE', identityChecked: true, note: ' 本场有余位 ' })

    expect(request.mock.calls[0][0]).toEqual({
      url: '/api/v1/checkin-workbench/records/ACTIVITY/a7000000-0000-4000-8000-000000000002/resolution',
      method: 'POST',
      headers: { Authorization: 'Bearer token' },
      data: { action: 'REJECT', identityChecked: false },
    })
    expect(request.mock.calls[1][0].data).toEqual({ action: 'RELEASE', identityChecked: true, note: '本场有余位' })
  })

  it('opens 工作模式 only when the server names a kind the organization verifies', async () => {
    expect(await loadStaffAccess({ workbench: vi.fn().mockResolvedValue({ date: 'd', kinds: ['VENUE'], sessions: [] }) }, 't')).toBe(true)
    expect(await loadStaffAccess({ workbench: vi.fn().mockResolvedValue({ date: 'd', kinds: [], sessions: [] }) }, 't')).toBe(false)
    expect(await loadStaffAccess({ workbench: vi.fn().mockRejectedValue(new RequestError(403, 'COMMON_FORBIDDEN')) }, 't')).toBe(false)
  })
})

describe('staff routes', () => {
  it('carries only a kind, a record id or a date', () => {
    const recordId = 'a7000000-0000-4000-8000-000000000002'
    expect(buildStaffExceptionUrl({ kind: 'ACTIVITY', recordId })).toBe(`/pages/staff/exception?kind=ACTIVITY&recordId=${recordId}`)
    expect(buildStaffExceptionUrl(null)).toBe('/pages/staff/exception')
    expect(buildStaffExceptionUrl({ kind: 'VENUE', recordId: 'not-an-id' })).toBe('/pages/staff/exception')
    expect(parseStaffExceptionQuery({ kind: 'VENUE', recordId })).toEqual({ kind: 'VENUE', recordId })
    expect(parseStaffExceptionQuery({ kind: 'SHOP', recordId })).toBeNull()
    expect(buildStaffRecordsUrl('2026-11-20')).toBe('/pages/staff/records?date=2026-11-20')
    expect(buildStaffRecordsUrl('yesterday')).toBe('/pages/staff/records')
    expect(parseStaffRecordsQuery({ date: '2026-11-20' })).toEqual({ date: '2026-11-20' })
    expect(parseStaffRecordsQuery({ date: '20261120' })).toEqual({ date: null })
  })
})
