// A QR code for one enrolment ticket code ("ACT-XXXX-XXXX-XXXX").
//
// The project has no QR library, and a ticket only ever needs one short ASCII text, so this is
// the smallest standard encoder that covers it: version 2 (25 × 25 modules), error correction
// level M, byte mode, one data block. That holds up to 26 characters; longer or non-ASCII text
// is refused rather than encoded wrongly. The steps follow ISO/IEC 18004: data codewords,
// Reed–Solomon check codewords, function patterns, zigzag placement, the mask with the lowest
// penalty, format information.

export const TICKET_QR_SIZE = 25

const DATA_CODEWORDS = 28
const CHECK_CODEWORDS = 16
const MAX_TEXT_LENGTH = 26
// Two format bits for level M.
const LEVEL_M_FORMAT_BITS = 0

export type QrMatrix = boolean[][]

export function encodeTicketQr(text: string): QrMatrix {
  if (text.length === 0 || text.length > MAX_TEXT_LENGTH) {
    throw new Error('ticket code length is not supported')
  }
  const bytes = Array.from(text, (character) => character.charCodeAt(0))
  if (bytes.some((byte) => byte > 0x7f)) {
    throw new Error('ticket code must be ASCII')
  }

  const data = dataCodewords(bytes)
  const codewords = [...data, ...checkCodewords(data)]

  const modules = emptyGrid(false)
  const reserved = emptyGrid(false)
  drawFunctionPatterns(modules, reserved)
  placeCodewords(modules, reserved, codewords)

  let best: QrMatrix | null = null
  let bestPenalty = Number.POSITIVE_INFINITY
  for (let mask = 0; mask < 8; mask += 1) {
    const candidate = modules.map((row) => [...row])
    applyMask(candidate, reserved, mask)
    drawFormatBits(candidate, mask)
    const penalty = penaltyOf(candidate)
    if (penalty < bestPenalty) {
      best = candidate
      bestPenalty = penalty
    }
  }
  return best as QrMatrix
}

// One path of 1 × 1 squares, for an SVG with viewBox "0 0 25 25".
export function ticketQrPath(matrix: QrMatrix): string {
  let path = ''
  matrix.forEach((row, y) => {
    row.forEach((dark, x) => {
      if (dark) path += `M${x} ${y}h1v1h-1z`
    })
  })
  return path
}

// An image source every uni-app platform can show; inline SVG elements are not available there.
export function ticketQrImage(text: string, color = '#202c42'): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${TICKET_QR_SIZE} ${TICKET_QR_SIZE}"`
    + ` shape-rendering="crispEdges"><path d="${ticketQrPath(encodeTicketQr(text))}" fill="${color}"/></svg>`
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

function emptyGrid(value: boolean): boolean[][] {
  return Array.from({ length: TICKET_QR_SIZE }, () =>
    Array.from({ length: TICKET_QR_SIZE }, () => value))
}

// Mode indicator (byte), 8-bit length, the bytes, a terminator, then padding up to 28 codewords.
function dataCodewords(bytes: number[]): number[] {
  const bits: number[] = []
  const push = (value: number, length: number) => {
    for (let index = length - 1; index >= 0; index -= 1) bits.push((value >>> index) & 1)
  }
  push(0b0100, 4)
  push(bytes.length, 8)
  bytes.forEach((byte) => push(byte, 8))
  push(0, Math.min(4, DATA_CODEWORDS * 8 - bits.length))
  while (bits.length % 8 !== 0) bits.push(0)

  const codewords: number[] = []
  for (let index = 0; index < bits.length; index += 8) {
    codewords.push(bits.slice(index, index + 8).reduce((byte, bit) => (byte << 1) | bit, 0))
  }
  for (let pad = 0xec; codewords.length < DATA_CODEWORDS; pad ^= 0xec ^ 0x11) codewords.push(pad)
  return codewords
}

// Multiplication in GF(256) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1.
function multiply(left: number, right: number): number {
  let product = 0
  for (let bit = 7; bit >= 0; bit -= 1) {
    product = (product << 1) ^ ((product >>> 7) * 0x11d)
    product ^= ((right >>> bit) & 1) * left
  }
  return product
}

// The remainder of the data polynomial divided by (x - 2^0)(x - 2^1)…(x - 2^15).
function checkCodewords(data: number[]): number[] {
  const divisor: number[] = Array.from({ length: CHECK_CODEWORDS }, () => 0)
  divisor[CHECK_CODEWORDS - 1] = 1
  let root = 1
  for (let step = 0; step < CHECK_CODEWORDS; step += 1) {
    for (let index = 0; index < CHECK_CODEWORDS; index += 1) {
      divisor[index] = multiply(divisor[index], root)
      if (index + 1 < CHECK_CODEWORDS) divisor[index] ^= divisor[index + 1]
    }
    root = multiply(root, 2)
  }

  const remainder: number[] = Array.from({ length: CHECK_CODEWORDS }, () => 0)
  data.forEach((byte) => {
    const factor = byte ^ (remainder.shift() as number)
    remainder.push(0)
    divisor.forEach((coefficient, index) => {
      remainder[index] ^= multiply(coefficient, factor)
    })
  })
  return remainder
}

function drawFunctionPatterns(modules: boolean[][], reserved: boolean[][]): void {
  const set = (x: number, y: number, dark: boolean) => {
    if (x < 0 || y < 0 || x >= TICKET_QR_SIZE || y >= TICKET_QR_SIZE) return
    modules[y][x] = dark
    reserved[y][x] = true
  }

  // Timing patterns.
  for (let index = 0; index < TICKET_QR_SIZE; index += 1) {
    set(6, index, index % 2 === 0)
    set(index, 6, index % 2 === 0)
  }

  // Three finder patterns with their light separators.
  const finder = (centerX: number, centerY: number) => {
    for (let dy = -4; dy <= 4; dy += 1) {
      for (let dx = -4; dx <= 4; dx += 1) {
        const distance = Math.max(Math.abs(dx), Math.abs(dy))
        set(centerX + dx, centerY + dy, distance !== 2 && distance !== 4)
      }
    }
  }
  finder(3, 3)
  finder(TICKET_QR_SIZE - 4, 3)
  finder(3, TICKET_QR_SIZE - 4)

  // Version 2 has one alignment pattern, centred on (18, 18).
  for (let dy = -2; dy <= 2; dy += 1) {
    for (let dx = -2; dx <= 2; dx += 1) {
      set(18 + dx, 18 + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1)
    }
  }

  // Keep the format information free of data; the real bits are drawn per mask.
  drawFormatBits(modules, 0, reserved)
}

// The 15 format bits (level and mask, BCH-protected, XOR-masked), drawn twice.
function drawFormatBits(modules: boolean[][], mask: number, reserved?: boolean[][]): void {
  const data = (LEVEL_M_FORMAT_BITS << 3) | mask
  let remainder = data
  for (let step = 0; step < 10; step += 1) {
    remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537)
  }
  const bits = ((data << 10) | remainder) ^ 0x5412
  const bit = (index: number) => ((bits >>> index) & 1) === 1
  const set = (x: number, y: number, dark: boolean) => {
    modules[y][x] = dark
    if (reserved) reserved[y][x] = true
  }
  const last = TICKET_QR_SIZE - 1

  for (let index = 0; index <= 5; index += 1) set(8, index, bit(index))
  set(8, 7, bit(6))
  set(8, 8, bit(7))
  set(7, 8, bit(8))
  for (let index = 9; index < 15; index += 1) set(14 - index, 8, bit(index))

  for (let index = 0; index < 8; index += 1) set(last - index, 8, bit(index))
  for (let index = 8; index < 15; index += 1) set(8, TICKET_QR_SIZE - 15 + index, bit(index))
  // The module that is always dark.
  set(8, TICKET_QR_SIZE - 8, true)
}

// Two columns at a time from the right edge, going up then down, skipping the timing column.
function placeCodewords(modules: boolean[][], reserved: boolean[][], codewords: number[]): void {
  let bitIndex = 0
  const totalBits = codewords.length * 8
  for (let right = TICKET_QR_SIZE - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5
    const upward = ((right + 1) & 2) === 0
    for (let step = 0; step < TICKET_QR_SIZE; step += 1) {
      const y = upward ? TICKET_QR_SIZE - 1 - step : step
      for (let offset = 0; offset < 2; offset += 1) {
        const x = right - offset
        if (reserved[y][x] || bitIndex >= totalBits) continue
        modules[y][x] = ((codewords[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) === 1
        bitIndex += 1
      }
    }
  }
}

function applyMask(modules: boolean[][], reserved: boolean[][], mask: number): void {
  const inverts: Array<(x: number, y: number) => boolean> = [
    (x, y) => (x + y) % 2 === 0,
    (_x, y) => y % 2 === 0,
    (x) => x % 3 === 0,
    (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
    (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
    (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
    (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
  ]
  for (let y = 0; y < TICKET_QR_SIZE; y += 1) {
    for (let x = 0; x < TICKET_QR_SIZE; x += 1) {
      if (!reserved[y][x] && inverts[mask](x, y)) modules[y][x] = !modules[y][x]
    }
  }
}

// The four penalty rules of the standard; the mask with the lowest total reads best.
function penaltyOf(modules: boolean[][]): number {
  const size = TICKET_QR_SIZE
  let penalty = 0
  const lines: boolean[][] = [
    ...modules,
    ...Array.from({ length: size }, (_unused, x) => modules.map((row) => row[x])),
  ]

  // Runs of five or more of one colour, and finder-like 1:1:3:1:1 sequences with four light modules beside them.
  const finderLike = [
    [true, false, true, true, true, false, true, false, false, false, false],
    [false, false, false, false, true, false, true, true, true, false, true],
  ]
  lines.forEach((line) => {
    let run = 1
    for (let index = 1; index <= size; index += 1) {
      if (index < size && line[index] === line[index - 1]) {
        run += 1
      } else {
        if (run >= 5) penalty += run - 2
        run = 1
      }
    }
    for (let start = 0; start + 11 <= size; start += 1) {
      if (finderLike.some((pattern) => pattern.every((dark, offset) => line[start + offset] === dark))) {
        penalty += 40
      }
    }
  })

  // 2 × 2 blocks of one colour.
  for (let y = 0; y < size - 1; y += 1) {
    for (let x = 0; x < size - 1; x += 1) {
      const colour = modules[y][x]
      if (modules[y][x + 1] === colour && modules[y + 1][x] === colour && modules[y + 1][x + 1] === colour) {
        penalty += 3
      }
    }
  }

  // How far the share of dark modules is from one half, in steps of 5%.
  const dark = modules.reduce((count, row) => count + row.filter(Boolean).length, 0)
  penalty += Math.floor(Math.abs((dark * 100) / (size * size) - 50) / 5) * 10
  return penalty
}
