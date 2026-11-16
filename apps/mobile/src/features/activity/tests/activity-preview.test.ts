import { describe, expect, it } from 'vitest'

import activityFlagSource from '../application/activity-preview.ts?raw'
import platformFlagSource from '../../platform/application/platform-preview.ts?raw'

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
  it('uses exactly the expression of the shared preview flag', () => {
    expect(flagExpression(activityFlagSource, 'ACTIVITY_PREVIEW_ENABLED'))
      .toBe(flagExpression(platformFlagSource, 'PLATFORM_PREVIEW_ENABLED'))
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
