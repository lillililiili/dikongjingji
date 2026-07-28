const { resolve, join } = require('node:path')

const sharpModule = process.env.INSPECTION_SHARP_MODULE
if (!sharpModule) {
  throw new Error('Set INSPECTION_SHARP_MODULE to an installed sharp module directory.')
}
const sharp = require(sharpModule)

const width = 390
const height = 737
const evidenceDir = resolve(
  process.env.INSPECTION_VISUAL_EVIDENCE_DIR
    || 'docs/evidence/inspection-service-flow',
)
const pages = ['ins-m-004', 'ins-m-005', 'ins-m-006', 'ins-m-007', 'ins-m-008']

async function normalizeReference(page) {
  const output = join(evidenceDir, `${page}-reference-390x737.png`)
  await sharp(join(evidenceDir, `${page}-reference-source.png`))
    .resize(width, height, {
      fit: 'contain',
      position: 'top',
      background: '#ffffff',
    })
    .png()
    .toFile(output)
  return sharp(output).png().toBuffer()
}

async function normalizeRoundOne(page) {
  const output = join(evidenceDir, `${page}-round1-390x737.png`)
  await sharp(join(evidenceDir, `${page}-round1-source.png`))
    .resize(width, height, {
      fit: 'cover',
      position: 'top',
    })
    .png()
    .toFile(output)
  return sharp(output).png().toBuffer()
}

async function readFinal(page) {
  const input = join(evidenceDir, `${page}-final.png`)
  const metadata = await sharp(input).metadata()
  if (metadata.width !== width || metadata.height !== height) {
    throw new Error(`${page} final image must be exactly ${width}x${height}.`)
  }
  return sharp(input).png().toBuffer()
}

async function writeComparison(page, label, reference, actual) {
  await sharp({
    create: {
      width: width * 2,
      height,
      channels: 4,
      background: '#ffffff',
    },
  })
    .composite([
      { input: reference, left: 0, top: 0 },
      { input: actual, left: width, top: 0 },
    ])
    .png()
    .toFile(join(evidenceDir, `${page}-${label}-side-by-side.png`))

  const translucentActual = await sharp(actual)
    .removeAlpha()
    .ensureAlpha(0.45)
    .png()
    .toBuffer()
  await sharp(reference)
    .composite([{ input: translucentActual, left: 0, top: 0 }])
    .png()
    .toFile(join(evidenceDir, `${page}-${label}-overlay.png`))
}

async function main() {
  for (const page of pages) {
    const reference = await normalizeReference(page)
    const roundOne = await normalizeRoundOne(page)
    const final = await readFinal(page)
    await writeComparison(page, 'round1', reference, roundOne)
    await writeComparison(page, 'final', reference, final)
  }
  process.stdout.write(JSON.stringify({
    viewport: `${width}x${height}`,
    pages,
    evidenceDir,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
