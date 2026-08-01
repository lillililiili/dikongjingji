export interface BookingFileHashPort {
  sha256(filePath: string): Promise<string>
}

export interface BookingFileAccessPort {
  readBytes(filePath: string): Promise<Uint8Array>
}

export function createBookingUuid(): string {
  const timestamp = Date.now().toString(16).padStart(12, '0')
  const random = Array.from({ length: 20 }, () =>
    Math.floor(Math.random() * 16).toString(16),
  ).join('')

  return `${timestamp.slice(0, 8)}-${timestamp.slice(8)}-4${random.slice(0, 3)}-8${random.slice(3, 6)}-${random.slice(6, 18)}`
}

export function createBookingFileHashPort(fileAccess: BookingFileAccessPort): BookingFileHashPort {
  return {
    async sha256(filePath) {
      return sha256(await fileAccess.readBytes(filePath))
    },
  }
}

export function sha256(bytes: Uint8Array): string {
  const words: number[] = []
  const bitLength = bytes.length * 8
  for (let index = 0; index < bytes.length; index += 1) {
    words[index >> 2] = (words[index >> 2] ?? 0) | (bytes[index] << (24 - (index % 4) * 8))
  }
  words[Math.floor(bitLength / 32)] = (words[Math.floor(bitLength / 32)] ?? 0) | (0x80 << (24 - (bitLength % 32)))
  const totalWords = Math.floor((bitLength + 64) / 512) * 16 + 16
  words[totalWords - 2] = Math.floor(bitLength / 0x1_0000_0000)
  words[totalWords - 1] = bitLength >>> 0
  const hash = [1779033703, -1150833019, 1013904242, -1521486534, 1359893119, -1694144372, 528734635, 1541459225]
  const constants = '428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5 d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174 e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da 983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967 27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85 a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070 19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3 748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2'.split(' ').map((hex) => Number.parseInt(hex, 16))
  for (let offset = 0; offset < words.length; offset += 16) {
    const schedule = Array.from({ length: 16 }, (_, index) => words[offset + index] ?? 0)
    for (let i = 16; i < 64; i += 1) { const a = schedule[i - 15]; const b = schedule[i - 2]; schedule[i] = (((a >>> 7 | a << 25) ^ (a >>> 18 | a << 14) ^ (a >>> 3)) + schedule[i - 7] + ((b >>> 17 | b << 15) ^ (b >>> 19 | b << 13) ^ (b >>> 10)) + schedule[i - 16]) | 0 }
    let [a,b,c,d,e,f,g,h] = hash
    for (let i = 0; i < 64; i += 1) { const s1 = (e >>> 6 | e << 26) ^ (e >>> 11 | e << 21) ^ (e >>> 25 | e << 7); const choose = (e & f) ^ (~e & g); const t1 = (h + s1 + choose + constants[i] + schedule[i]) | 0; const s0 = (a >>> 2 | a << 30) ^ (a >>> 13 | a << 19) ^ (a >>> 22 | a << 10); const majority = (a & b) ^ (a & c) ^ (b & c); h=g; g=f; f=e; e=(d+t1)|0; d=c; c=b; b=a; a=(t1+s0+majority)|0 }
    hash[0]=(hash[0]+a)|0; hash[1]=(hash[1]+b)|0; hash[2]=(hash[2]+c)|0; hash[3]=(hash[3]+d)|0; hash[4]=(hash[4]+e)|0; hash[5]=(hash[5]+f)|0; hash[6]=(hash[6]+g)|0; hash[7]=(hash[7]+h)|0
  }
  return hash.map((word) => (word >>> 0).toString(16).padStart(8, '0')).join('')
}
