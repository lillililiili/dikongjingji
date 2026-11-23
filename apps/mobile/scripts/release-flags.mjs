// The same resolution as src/app-shell/release/feature-flags.ts, for scripts that run in plain Node
// (verify-public-production-preview.mjs). src/app-shell/release/release-flags.test.ts keeps them identical.
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const mobileRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export const PREVIEW_BUILD_ENV = 'VITE_PLATFORM_PREVIEW_ENABLED'

export function readReleaseFeatures() {
  const file = join(mobileRoot, 'src', 'app-shell', 'release', 'release-features.json')
  return JSON.parse(readFileSync(file, 'utf8')).features
}

export const featureEnvName = (key) => `VITE_FEATURE_${key}`

export function resolveReleaseFlags(env, features = readReleaseFeatures()) {
  const previewBuild = env[PREVIEW_BUILD_ENV] === 'true'
  const resolved = {}
  for (const feature of features) {
    const name = featureEnvName(feature.key)
    const override = env[name]?.trim()
    if (override && override !== 'true' && override !== 'false') {
      throw new Error(`${name} must be true or false`)
    }
    const open = override ? override === 'true' : feature.publicRelease
    resolved[feature.key] = previewBuild || open
  }
  return { previewBuild, features: resolved }
}

/** KEY=VALUE lines of a dotenv file, the way vite reads the plain ones (no expansion). */
export function readDotenv(path) {
  if (!existsSync(path)) return {}
  const values = {}
  for (const raw of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const match = /^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line)
    if (!match) continue
    values[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2')
  }
  return values
}

/** What a production build sees, in vite's order: .env, .env.local, .env.production, .env.production.local, then the process's VITE_ variables. */
export function productionBuildEnv(processEnv = process.env) {
  const env = {}
  for (const name of ['.env', '.env.local', '.env.production', '.env.production.local']) {
    Object.assign(env, readDotenv(join(mobileRoot, name)))
  }
  for (const [name, value] of Object.entries(processEnv)) {
    if (name.startsWith('VITE_')) env[name] = value
  }
  return env
}
