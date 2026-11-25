// Public production build check (S2-5, docs/ops/发布开关.md). Builds the H5 bundle the way a public
// release is built and fails when:
// - the build is not a public one (an .env.production.local override, or VITE_PLATFORM_PREVIEW_ENABLED
//   not false in .env.production);
// - development or sample content made it into the bundle;
// - a release constant was left unreplaced;
// - a home channel whose release switch is off is marked publicly available in the bundle, or one
//   whose switch is on is not.
// Which features this build opens is printed first. The page-level proof that closed features stay
// out of reach is the public E2E (e2e/public-build.spec.ts) run against this bundle.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { extname, join } from 'node:path'
import { spawnSync } from 'node:child_process'

import { mobileRoot, productionBuildEnv, readReleaseFeatures, resolveReleaseFlags } from './release-flags.mjs'
const publicAssetExclusions = JSON.parse(readFileSync(new URL('./public-asset-exclusions.json', import.meta.url), 'utf8'))

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
  '/static/images/home/',
]
// Home channels that open with a release switch, by the label the bundle carries.
const homeChannels = { ACTIVITY: '赛事研学', EXPERIENCE: '体验', MALL: '商城' }
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

function resolvePublicFlags() {
  const flags = resolveReleaseFlags(productionBuildEnv(), readReleaseFeatures())
  if (flags.previewBuild) {
    throw new Error('VITE_PLATFORM_PREVIEW_ENABLED=true reaches the build (check the process environment).')
  }
  const open = Object.entries(flags.features).filter(([, on]) => on).map(([key]) => key)
  console.log(`Release switches open in this public build: ${open.length ? open.join(', ') : 'none (inspection only)'}`)
  return flags
}

function readOutputText(directory) {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry)
    if (statSync(path).isDirectory()) return [readOutputText(path)]
    return textExtensions.has(extname(path)) ? [readFileSync(path, 'utf8')] : []
  }).join('\n')
}

// Runs the uni CLI (what `build:h5` runs) with this Node directly. Going through `pnpm run` lets
// pnpm check and reinstall dependencies first, which rewrote a shared node_modules once.
function buildPublicProductionH5() {
  const uniCli = join(mobileRoot, 'node_modules', '@dcloudio', 'vite-plugin-uni', 'bin', 'uni.js')
  if (!existsSync(uniCli)) {
    throw new Error('apps/mobile/node_modules has no @dcloudio/vite-plugin-uni; install the dependencies first.')
  }
  const result = spawnSync(process.execPath, [uniCli, 'build'], {
    cwd: mobileRoot,
    stdio: 'inherit',
  })

  if (result.error || result.status !== 0) {
    throw new Error('Public production H5 build failed.')
  }
}

function checkHomeChannels(output, flags) {
  const problems = []
  for (const [key, label] of Object.entries(homeChannels)) {
    const pattern = new RegExp(`code:"${key}",label:"${label}",searchPlaceholder:"[^"]*",publiclyAvailable:(!0|!1|true|false)`)
    const match = pattern.exec(output)
    if (!match) {
      problems.push(`home channel ${key} not found in the bundle (update homeChannels in this script)`)
      continue
    }
    const shown = match[1] === '!0' || match[1] === 'true'
    if (shown !== flags.features[key]) {
      problems.push(`home channel ${key} is ${shown ? 'public' : 'hidden'} but its release switch is ${flags.features[key] ? 'on' : 'off'}`)
    }
  }
  return problems
}

readProductionPreviewFlag()
const flags = resolvePublicFlags()
buildPublicProductionH5()

const output = readOutputText(h5OutputPath)
// The prototype's sample pictures and clips are not licensed; vite.config.ts drops them from
// public builds (docs/ops/授权图片替换.md).
for (const asset of publicAssetExclusions) {
  if (existsSync(join(h5OutputPath, asset))) {
    throw new Error(`Public build contains excluded asset: ${asset}`)
  }
}
const leakedMarkers = forbiddenMarkers.filter((marker) => output.includes(marker))
if (leakedMarkers.length > 0) {
  throw new Error(`Public production H5 output contains development markers: ${leakedMarkers.join(', ')}`)
}
const unreplaced = [...new Set(output.match(/__RELEASE_[A-Z_]+__/g) ?? [])]
if (unreplaced.length > 0) {
  throw new Error(`Public production H5 output has unreplaced release constants: ${unreplaced.join(', ')}`)
}
const channelProblems = checkHomeChannels(output, flags)
if (channelProblems.length > 0) {
  throw new Error(`Public production H5 release switches disagree with the bundle:\n- ${channelProblems.join('\n- ')}`)
}

console.log('Public production H5 preview fixture guard passed.')
console.log('Next: serve dist/build/h5 and run E2E_PUBLIC_BUILD=1 npx playwright test e2e/public-build.spec.ts.')
