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

export default defineConfig({
  plugins: [uni(), releaseFlags()],
})
