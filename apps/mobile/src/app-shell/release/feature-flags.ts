// The release configuration of the App (S2-5): which features a public build opens.
//
// One place: release-features.json lists every feature with its committed default for public
// builds (publicRelease). A one-off build can override a feature with VITE_FEATURE_<KEY>=true|false.
// Preview builds (VITE_PLATFORM_PREVIEW_ENABLED=true) and the dev server open everything, as before.
// What each switch controls and what must hold before opening it: docs/ops/发布开关.md.
//
// How the switches reach the code: vite.config.ts turns them into compile-time constants with
// `define` (__RELEASE_PREVIEW_BUILD__, __RELEASE_FEATURE_<KEY>__), and every module's own flag file
// reads its constant, e.g. `export const MALL_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_MALL__`.
// The constants are replaced by literals in every file that uses them, so a closed feature folds to
// `false` where it is read and its code is dropped there. That is why the modules do not import one
// shared flag module: an imported flag can end up in a common chunk where the minifier cannot fold
// it (features/activity/application/activity-preview.ts tells how that once leaked the home page's
// sample content into the public bundle). Pages never import this file; only the build does.
//
// scripts/release-flags.mjs repeats resolveReleaseFlags for the public-build check, which runs in
// plain Node; release-flags.test.ts keeps the two identical.
import registry from './release-features.json'

export type ReleaseFeatureKey =
  | 'ACTIVITY'
  | 'EXPERIENCE'
  | 'MALL'
  | 'CART'
  | 'TRADE'
  | 'RENTAL'
  | 'DEMAND'
  | 'PROSERVICE'
  | 'CONTENT'
  | 'LIVE'
  | 'SUPPORT'
  | 'SETTINGS'
  | 'ACCOUNT'
  | 'PLAY'

export interface ReleaseFeature {
  key: ReleaseFeatureKey
  /** The module's flag constant, or null when the switch is read in place (PLAY). */
  flag: string | null
  /** The file (relative to apps/mobile) that reads the switch. */
  file: string
  /** Whether a public build opens it when no VITE_FEATURE_<KEY> says otherwise. */
  publicRelease: boolean
}

export const RELEASE_FEATURES = registry.features as readonly ReleaseFeature[]

export const PREVIEW_BUILD_ENV = 'VITE_PLATFORM_PREVIEW_ENABLED'
export const PREVIEW_BUILD_CONSTANT = '__RELEASE_PREVIEW_BUILD__'

export const featureEnvName = (key: ReleaseFeatureKey): string => `VITE_FEATURE_${key}`
export const featureConstant = (key: ReleaseFeatureKey): string => `__RELEASE_FEATURE_${key}__`

/** The expression every module flag file declares (release-flags.test.ts checks them). */
export const moduleFlagExpression = (key: ReleaseFeatureKey): string =>
  `import.meta.env.DEV || ${featureConstant(key)}`

/** Sample pictures and videos only ever show on preview builds, whatever a feature switch says. */
export const PREVIEW_SAMPLES_EXPRESSION = `import.meta.env.DEV || ${PREVIEW_BUILD_CONSTANT}`

export interface ReleaseFlags {
  previewBuild: boolean
  features: Record<ReleaseFeatureKey, boolean>
}

export type ReleaseEnv = Record<string, string | undefined>

export function resolveReleaseFlags(env: ReleaseEnv): ReleaseFlags {
  const previewBuild = env[PREVIEW_BUILD_ENV] === 'true'
  const features = {} as Record<ReleaseFeatureKey, boolean>
  for (const feature of RELEASE_FEATURES) {
    const name = featureEnvName(feature.key)
    const override = env[name]?.trim()
    if (override && override !== 'true' && override !== 'false') {
      throw new Error(`${name} must be true or false`)
    }
    const open = override ? override === 'true' : feature.publicRelease
    features[feature.key] = previewBuild || open
  }
  return { previewBuild, features }
}

/** The `define` entries for vite: each value is a JSON literal. */
export function releaseDefines(env: ReleaseEnv): Record<string, string> {
  const flags = resolveReleaseFlags(env)
  const defines: Record<string, string> = {
    [PREVIEW_BUILD_CONSTANT]: JSON.stringify(flags.previewBuild),
  }
  for (const feature of RELEASE_FEATURES) {
    defines[featureConstant(feature.key)] = JSON.stringify(flags.features[feature.key])
  }
  return defines
}
