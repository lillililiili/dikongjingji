import { describe, expect, it } from 'vitest'

import {
  TICKET_QR_SIZE,
  encodeTicketQr,
  ticketQrImage,
  ticketQrPath,
} from './ticket-qr'

// Each row of the code for "ACT-1234-5678-9ABC" as 25 bits in hex. This exact pattern was
// rendered to an image and read back as the same text by the macOS QR detector (2026-11-17);
// a change to the encoder that alters it must be checked against a real scanner again.
const VERIFIED_ROWS = [
  '1fc137f', '104da41', '175445d', '175925d', '1758b5d', '105ef41', '1fd557f', '001fa00', '17c967c',
  '12b7383', '0ce33eb', '13166fa', '0c5e15d', '1d09c85', '14ecfc3', '109db2b', '17755f5', '0010319',
  '1fc7153', '105e718', '175e3f5', '1755e7b', '1754f8d', '10459f9', '1fdd687',
]

function rows(text: string): string[] {
  return encodeTicketQr(text).map((row) =>
    parseInt(row.map((dark) => (dark ? 1 : 0)).join(''), 2).toString(16).padStart(7, '0'))
}

describe('ticket QR code', () => {
  it('reproduces the pattern that a real scanner read', () => {
    expect(rows('ACT-1234-5678-9ABC')).toEqual(VERIFIED_ROWS)
  })

  it('is a 25 × 25 grid with the three finder patterns and the timing lines', () => {
    const matrix = encodeTicketQr('ACT-0000-0000-0000')

    expect(matrix).toHaveLength(TICKET_QR_SIZE)
    expect(matrix.every((row) => row.length === TICKET_QR_SIZE)).toBe(true)
    for (const [x, y] of [[0, 0], [18, 0], [0, 18]]) {
      // The outer ring and the centre are dark, the ring between them is light.
      expect(matrix[y][x]).toBe(true)
      expect(matrix[y + 1][x + 1]).toBe(false)
      expect(matrix[y + 3][x + 3]).toBe(true)
      expect(matrix[y + 6][x + 6]).toBe(true)
    }
    for (let index = 8; index < 17; index += 1) {
      expect(matrix[6][index]).toBe(index % 2 === 0)
      expect(matrix[index][6]).toBe(index % 2 === 0)
    }
    // The module that is always dark.
    expect(matrix[17][8]).toBe(true)
  })

  it('gives different codes for different tickets', () => {
    expect(rows('ACT-AAAA-AAAA-AAAA')).not.toEqual(rows('ACT-AAAA-AAAA-AAAB'))
  })

  it('refuses text it cannot hold instead of drawing a wrong code', () => {
    expect(() => encodeTicketQr('')).toThrow()
    expect(() => encodeTicketQr('A'.repeat(27))).toThrow()
    expect(() => encodeTicketQr('凭证')).toThrow()
    expect(() => encodeTicketQr('A'.repeat(26))).not.toThrow()
  })

  it('draws one square per dark module', () => {
    const matrix = encodeTicketQr('ACT-1234-5678-9ABC')
    const dark = matrix.reduce((count, row) => count + row.filter(Boolean).length, 0)

    expect(ticketQrPath(matrix).match(/h1v1h-1z/g)).toHaveLength(dark)
    expect(ticketQrPath(matrix).startsWith('M0 0h1v1h-1z')).toBe(true)
  })

  it('wraps the code in an SVG image source', () => {
    const source = ticketQrImage('ACT-1234-5678-9ABC')
    const svg = decodeURIComponent(source.replace('data:image/svg+xml;charset=utf-8,', ''))

    expect(source.startsWith('data:image/svg+xml;charset=utf-8,')).toBe(true)
    expect(svg).toContain('viewBox="0 0 25 25"')
    expect(svg).toContain('fill="#202c42"')
  })
})
