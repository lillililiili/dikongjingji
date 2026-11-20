import { describe, expect, it } from 'vitest'

import { iconTileToneAt } from './icon-tile-tones'
import { LINE_ICON_PATHS, lineIconDataUri } from './line-icons'

describe('line icons', () => {
  it('draws a board stroke icon as an svg data uri', () => {
    const uri = lineIconDataUri({ d: LINE_ICON_PATHS.share, color: '#202c42', strokeWidth: 1.8 })
    const svg = decodeURIComponent(uri.replace('data:image/svg+xml;charset=utf-8,', ''))
    expect(svg).toContain('viewBox="0 0 24 24"')
    expect(svg).toContain('stroke="#202c42"')
    expect(svg).toContain('stroke-width="1.8"')
    expect(svg).toContain(`d="${LINE_ICON_PATHS.share}"`)
  })

  it('draws a solid shape when a fill is given', () => {
    const svg = decodeURIComponent(lineIconDataUri({ d: 'M6 9l6 6 6-6z', color: '#000', strokeWidth: 1.8, fill: '#202c42' }))
    expect(svg).toContain('fill="#202c42" stroke="none"')
  })

  it('hands out tile colours in board order and wraps after five', () => {
    expect([0, 1, 2, 3, 4, 5, -1].map(iconTileToneAt)).toEqual(['blue', 'violet', 'orange', 'green', 'yellow', 'blue', 'yellow'])
  })
})
