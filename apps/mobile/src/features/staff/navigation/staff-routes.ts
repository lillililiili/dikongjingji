import { CHECKIN_KINDS, type CheckinKind } from '../domain/checkin'

// 工作模式 (boards StaffVerify, StaffRecords, StaffException). Only stable ids and a date travel in
// the URL; every page reads the facts again from the server.
export const STAFF_VERIFY_PATH = '/pages/staff/verify'
export const STAFF_RECORDS_PATH = '/pages/staff/records'
export const STAFF_EXCEPTION_PATH = '/pages/staff/exception'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DATE = /^\d{4}-\d{2}-\d{2}$/

export interface StaffRecordRef {
  kind: CheckinKind
  recordId: string
}

export function buildStaffVerifyUrl(): string {
  return STAFF_VERIFY_PATH
}

export function buildStaffRecordsUrl(date?: string | null): string {
  return date && DATE.test(date) ? `${STAFF_RECORDS_PATH}?date=${date}` : STAFF_RECORDS_PATH
}

/** Without a record the page opens on the first exception still waiting for a decision today. */
export function buildStaffExceptionUrl(record?: StaffRecordRef | null): string {
  if (!record || !UUID.test(record.recordId)) return STAFF_EXCEPTION_PATH
  return `${STAFF_EXCEPTION_PATH}?kind=${record.kind}&recordId=${encodeURIComponent(record.recordId)}`
}

const text = (value: unknown): string => (typeof value === 'string' ? decodeURIComponent(value).trim() : '')

export function parseStaffRecordsQuery(query: Record<string, unknown> | undefined): { date: string | null } {
  const date = text(query?.date)
  return { date: DATE.test(date) ? date : null }
}

export function parseStaffExceptionQuery(query: Record<string, unknown> | undefined): StaffRecordRef | null {
  const kind = text(query?.kind)
  const recordId = text(query?.recordId)
  if (!(CHECKIN_KINDS as readonly string[]).includes(kind) || !UUID.test(recordId)) return null
  return { kind: kind as CheckinKind, recordId }
}
