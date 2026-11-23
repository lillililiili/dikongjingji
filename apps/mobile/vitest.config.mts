import { defineConfig } from 'vitest/config'

import { releaseDefines } from './src/app-shell/release/feature-flags'

// The App shows times in Beijing time whatever the phone's zone is. The tests run in a zone
// 13 hours away (12 in summer), so a formatter that reads device time fails here even on a
// machine in China. It has to be set before the workers start: they cannot change zone later.
// The App's tsconfig has no Node types (they would change setTimeout and friends), so only what
// is used here is declared.
declare const process: { env: Record<string, string | undefined> }
process.env.TZ = 'America/New_York'

export default defineConfig({
  // Unit tests see the committed public defaults of the release switches. import.meta.env.DEV is
  // true here, so the module flags stay open as they were.
  define: releaseDefines({}),
  test: {
    environment: 'node',
    globals: true,
    include: ['src/**/*.test.ts'],
  },
})
