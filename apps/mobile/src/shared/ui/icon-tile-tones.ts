// Tile colours of the 2026-11-14 boards, in the order light-theme.js hands them out.
export type IconTileTone = 'blue' | 'violet' | 'orange' | 'green' | 'yellow'

export const ICON_TILE_TONES: Record<IconTileTone, { bg: string; ink: string; shadow: string }> = {
  blue: { bg: '#E3EFFF', ink: '#377DCE', shadow: 'rgba(52,121,208,0.10)' },
  violet: { bg: '#EFE6FF', ink: '#8254C8', shadow: 'rgba(128,82,198,0.10)' },
  orange: { bg: '#FFEBD8', ink: '#CE681D', shadow: 'rgba(207,107,27,0.10)' },
  green: { bg: '#DFF5EB', ink: '#21856C', shadow: 'rgba(33,133,108,0.10)' },
  yellow: { bg: '#FFF0C8', ink: '#9D7610', shadow: 'rgba(167,138,21,0.10)' },
}

export const ICON_TILE_TONE_ORDER: readonly IconTileTone[] = ['blue', 'violet', 'orange', 'green', 'yellow']

/** The nth tile on a board gets the nth colour, wrapping after five. */
export function iconTileToneAt(index: number): IconTileTone {
  return ICON_TILE_TONE_ORDER[((index % 5) + 5) % 5]
}
