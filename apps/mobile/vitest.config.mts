import { defineConfig } from 'vitest/config'

// The App shows times in Beijing time whatever the phone's zone is. The tests run in a zone
// 13 hours away (12 in summer), so a formatter that reads device time fails here even on a
// machine in China. It has to be set before the workers start: they cannot change zone later.
// The App's tsconfig has no Node types (they would change setTimeout and friends), so only what
// is used here is declared.
declare const process: { env: Record<string, string | undefined> }
process.env.TZ = 'America/New_York'

export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    include: ['src/**/*.test.ts'],
  },
})
