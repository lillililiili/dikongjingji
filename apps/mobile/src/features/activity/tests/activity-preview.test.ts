import { describe, expect, it } from 'vitest'

import { moduleFlagExpression } from '../../../app-shell/release/feature-flags'
import activityFlagSource from '../application/activity-preview.ts?raw'

const activitySources = import.meta.glob('../**/*.{ts,vue}', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

function flagExpression(source: string, name: string): string {
  const match = new RegExp(`export const ${name} = (.+)`).exec(source)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1].trim()
}

describe('activity preview flag', () => {
  it('reads its own release switch', () => {
    expect(flagExpression(activityFlagSource, 'ACTIVITY_PREVIEW_ENABLED'))
      .toBe(moduleFlagExpression('ACTIVITY'))
  })

  it('keeps the activity feature from importing the shared flag module', () => {
    // Importing it from the detail page chunk moves the flag out of the home chunk and leaks
    // the home page's sample content into public bundles.
    const offenders = Object.entries(activitySources)
      .filter(([path]) => !path.includes('/tests/'))
      .filter(([, source]) => /from\s+'[^']*platform-preview'/.test(source))
      .map(([path]) => path)

    expect(offenders).toEqual([])
  })
})
