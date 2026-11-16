import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, extname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const mobileRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const productionEnvPath = join(mobileRoot, '.env.production')
const productionLocalEnvPath = join(mobileRoot, '.env.production.local')
const h5OutputPath = join(mobileRoot, 'dist', 'build', 'h5')
const forbiddenMarkers = [
  '专业航拍',
  '无人机足球赛',
  '小小飞行家',
  '智能装备',
  'development-platform-content-repository',
  'platform-discover.fixture',
  'platform-mall.fixture',
  'platform-service.fixture',
]
const textExtensions = new Set(['.css', '.html', '.js', '.json'])

function readProductionPreviewFlag() {
  if (existsSync(productionLocalEnvPath)) {
    throw new Error(
      'Public production verification requires no apps/mobile/.env.production.local override.',
    )
  }

  const productionEnv = readFileSync(productionEnvPath, 'utf8')
  const previewFlag = productionEnv
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line.startsWith('VITE_PLATFORM_PREVIEW_ENABLED='))

  if (previewFlag !== 'VITE_PLATFORM_PREVIEW_ENABLED=false') {
    throw new Error(
      'Public production verification requires apps/mobile/.env.production to set VITE_PLATFORM_PREVIEW_ENABLED=false.',
    )
  }
}

function readOutputText(directory) {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry)
    if (statSync(path).isDirectory()) return [readOutputText(path)]
    return textExtensions.has(extname(path)) ? [readFileSync(path, 'utf8')] : []
  }).join('\n')
}

function buildPublicProductionH5() {
  const command = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
  const result = spawnSync(command, ['run', 'build:h5'], {
    cwd: mobileRoot,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  })

  if (result.error || result.status !== 0) {
    throw new Error('Public production H5 build failed.')
  }
}

readProductionPreviewFlag()
buildPublicProductionH5()

const output = readOutputText(h5OutputPath)
const leakedMarkers = forbiddenMarkers.filter((marker) => output.includes(marker))
if (leakedMarkers.length > 0) {
  throw new Error(`Public production H5 output contains development markers: ${leakedMarkers.join(', ')}`)
}

console.log('Public production H5 preview fixture guard passed.')
