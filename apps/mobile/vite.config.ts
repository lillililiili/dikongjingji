import { rmSync } from 'node:fs'
import { resolve } from 'node:path'

import { defineConfig, loadEnv, type Plugin } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

import { releaseDefines } from './src/app-shell/release/feature-flags'

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

// The sample pictures and clips under static/images/preview and static/videos/preview come from
// the prototype and are not licensed (docs/ops/授权图片替换.md). Preview builds and the dev server
// show them; a public build must not ship them at all, so they are dropped from its output. Pages
// in a public build never point at them (every preview resolver is switched off there).
const PREVIEW_ASSET_DIRS = ['static/images/preview', 'static/videos/preview']

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
        for (const dir of PREVIEW_ASSET_DIRS) rmSync(resolve(outDir, dir), { recursive: true, force: true })
      },
    },
  }
}

export default defineConfig({
  plugins: [uni(), releaseFlags(), stripPreviewAssets()],
})
