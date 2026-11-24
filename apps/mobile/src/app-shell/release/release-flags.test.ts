import { describe, expect, it } from 'vitest'

import {
  RELEASE_FEATURES,
  featureConstant,
  moduleFlagExpression,
  releaseDefines,
  resolveReleaseFlags,
  type ReleaseEnv,
} from './feature-flags'
import globalsSource from './release-globals.d.ts?raw'

const sources = import.meta.glob('../../**/*.{ts,vue}', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

// The glob keys are relative to this folder (../navigation/x.ts, ../../features/y.ts); the registry's
// paths are relative to apps/mobile (src/...). Both become src/... here.
function fromHere(key: string): string {
  const parts = ['src', 'app-shell', 'release']
  for (const part of key.split('/')) {
    if (part === '..') parts.pop()
    else if (part !== '.') parts.push(part)
  }
  return parts.join('/')
}

const byPath = new Map(Object.entries(sources).map(([key, source]) => [fromHere(key), source]))

const appSources = [...byPath.entries()]
  .filter(([path]) => !path.includes('/tests/') && !path.endsWith('.test.ts'))
  .filter(([path]) => !path.startsWith('src/app-shell/release/'))

function sourceOf(file: string): string {
  const source = byPath.get(file)
  if (source === undefined) throw new Error(`${file} is not a source file`)
  return source
}

describe('release switches (S2-5)', () => {
  it('registers every module flag, and each reads its own constant', () => {
    const declared = new Set<string>()
    for (const [, source] of appSources) {
      for (const match of source.matchAll(/export const ([A-Z_]+_PREVIEW_ENABLED) = /g)) declared.add(match[1])
    }
    declared.delete('PLATFORM_PREVIEW_ENABLED')
    const registered = RELEASE_FEATURES.flatMap((feature) => (feature.flag ? [feature.flag] : []))
    expect([...declared].sort()).toEqual([...registered].sort())

    for (const feature of RELEASE_FEATURES) {
      const source = sourceOf(feature.file)
      if (feature.flag) {
        expect(source.replace(/\r\n/g, '\n'), feature.file).toContain(`export const ${feature.flag} = ${moduleFlagExpression(feature.key)}\n`)
      } else {
        expect(source, feature.file).toContain(featureConstant(feature.key))
      }
    }
  })

  it('declares exactly the constants the build defines, and nothing else uses unknown ones', () => {
    const defined = Object.keys(releaseDefines({})).sort()
    const typed = [...globalsSource.matchAll(/declare const (__RELEASE_[A-Z_]+__): boolean/g)].map((m) => m[1]).sort()
    expect(typed).toEqual(defined)

    const used = new Set<string>()
    for (const [, source] of appSources) {
      for (const match of source.matchAll(/__RELEASE_[A-Z_]+__/g)) used.add(match[0])
    }
    expect([...used].filter((name) => !defined.includes(name))).toEqual([])
  })

  it('is read only by the build: no page imports it or reads VITE_FEATURE_ itself', () => {
    const offenders = appSources
      .filter(([, source]) =>
        /from '[^']*release\/(feature-flags|release-features\.json)'|import\.meta\.env\.VITE_FEATURE_/.test(source))
      .map(([path]) => path)
    expect(offenders).toEqual([])
  })

  it('keeps public builds closed by default, opens all on preview builds, and takes overrides', () => {
    const closed = resolveReleaseFlags({})
    expect(closed.previewBuild).toBe(false)
    expect(Object.values(closed.features).every((open) => !open)).toBe(true)
    // The committed defaults are the current public build: nothing but inspection.
    expect(RELEASE_FEATURES.every((feature) => !feature.publicRelease)).toBe(true)

    const preview = resolveReleaseFlags({ VITE_PLATFORM_PREVIEW_ENABLED: 'true', VITE_FEATURE_LIVE: 'false' })
    expect(Object.values(preview.features).every(Boolean)).toBe(true)

    const mallOnly = resolveReleaseFlags({ VITE_PLATFORM_PREVIEW_ENABLED: 'false', VITE_FEATURE_MALL: ' true ' })
    expect(mallOnly.previewBuild).toBe(false)
    expect(Object.entries(mallOnly.features).filter(([, open]) => open).map(([key]) => key)).toEqual(['MALL'])

    expect(() => resolveReleaseFlags({ VITE_FEATURE_TRADE: 'yes' })).toThrow('VITE_FEATURE_TRADE must be true or false')
  })

  it('defines JSON literals for vite', () => {
    const defines = releaseDefines({ VITE_FEATURE_CART: 'true' })
    expect(defines.__RELEASE_PREVIEW_BUILD__).toBe('false')
    expect(defines.__RELEASE_FEATURE_CART__).toBe('true')
    expect(defines.__RELEASE_FEATURE_TRADE__).toBe('false')
  })

  it('resolves exactly like scripts/release-flags.mjs, which the public-build check uses', async () => {
    const scriptPath = '../../../scripts/release-flags.mjs'
    const script = (await import(/* @vite-ignore */ scriptPath)) as {
      resolveReleaseFlags: (env: ReleaseEnv) => unknown
    }
    const cases: ReleaseEnv[] = [
      {},
      { VITE_PLATFORM_PREVIEW_ENABLED: 'true' },
      { VITE_PLATFORM_PREVIEW_ENABLED: 'false', VITE_FEATURE_ACTIVITY: 'true', VITE_FEATURE_LIVE: 'false' },
      { VITE_FEATURE_PLAY: ' true ', VITE_FEATURE_CONTENT: '' },
    ]
    for (const env of cases) expect(script.resolveReleaseFlags(env)).toEqual(resolveReleaseFlags(env))
    expect(() => script.resolveReleaseFlags({ VITE_FEATURE_MALL: '1' })).toThrow('VITE_FEATURE_MALL must be true or false')
  })
})
