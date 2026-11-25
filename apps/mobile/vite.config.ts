import { rmSync } from 'node:fs'
import { resolve, sep } from 'node:path'

import { defineConfig, loadEnv, type Plugin } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

import { releaseDefines } from './src/app-shell/release/feature-flags'
import publicAssetExclusions from './scripts/public-asset-exclusions.json'

// Turns the release configuration (src/app-shell/release/release-features.json and the
// VITE_FEATURE_* / VITE_PLATFORM_PREVIEW_ENABLED variables) into compile-time constants
// (docs/ops/发布开关.md). It reads the env the way vite does, from the env directory uni settled on.
function releaseFlags(): Plugin {
  return {
    name: 'low-altitude:release-flags',
    config(config, { mode }) {
      const envDir = config.envDir ?? config.root ?? '.'
      return { define: releaseDefines(loadEnv(mode, envDir, 'VITE_')) }
    },
  }
}

// uni copies the whole static tree, including prototype artwork and retired illustrations.
// Keep the source assets for previews and remove the listed paths from public H5 and App builds.
function stripPreviewAssets(): Plugin {
  let publicBuild = false
  let outDir = ''
  return {
    name: 'low-altitude:strip-preview-assets',
    apply: 'build',
    configResolved(config) {
      publicBuild = loadEnv(config.mode, config.envDir, 'VITE_').VITE_PLATFORM_PREVIEW_ENABLED !== 'true'
      outDir = resolve(config.root, config.build.outDir)
    },
    closeBundle: {
      order: 'post',
      sequential: true,
      handler() {
        if (!publicBuild) return
        const staticRoot = resolve(outDir, 'static') + sep
        for (const asset of publicAssetExclusions) {
          const target = resolve(outDir, asset)
          if (!target.startsWith(staticRoot)) throw new Error(`Invalid public asset exclusion: ${asset}`)
          rmSync(target, { recursive: true, force: true })
        }
      },
    },
  }
}

export default defineConfig({
  plugins: [uni(), releaseFlags(), stripPreviewAssets()],
})
