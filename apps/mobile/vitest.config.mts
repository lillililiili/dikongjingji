import { defineConfig } from 'vitest/config'

import { releaseDefines } from './src/app-shell/release/feature-flags'

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
