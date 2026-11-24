// Line icons copied from the 2026-11-14 boards (prototype/v2-20261114).
// Every board icon is a 24×24 stroke path, usually 1.8 wide with round caps. Paths are kept
// exactly as the boards write them so a side-by-side screenshot matches; when a page needs an
// icon that is not listed here, pass the board's path to LineIcon through `d` instead.

export const LINE_ICON_PATHS = {
  back: 'M15 5l-7 7 7 7',
  forward: 'M9 5l7 7-7 7',
  chevronRight: 'M9 6l6 6-6 6',
  chevronDown: 'M6 9l6 6 6-6',
  chevronUp: 'M6 15l6-6 6 6',
  close: 'M6 6l12 12M18 6L6 18',
  check: 'M5 12l5 5 9-10',
  plus: 'M12 5v14M5 12h14',
  search: 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4',
  scan: 'M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4M4 12h16',
  share: 'M12 15V4M8 8l4-4 4 4M5 13v7h14v-7',
  cart: 'M3 4h2l2 11h11l2-8H6M9 20a1 1 0 1 0 0-.1M17 20a1 1 0 1 0 0-.1',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  filter: 'M4 5h16l-6.2 7.6V19l-3.6 1.8v-8.2z',
  sort: 'M4 6h16M7 12h10M10 18h4',
  info: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01',
  alert: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7.5v5.5M12 16.5h.01',
  warning: 'M12 4l9 16H3zM12 10v4M12 17h.01',
  clock: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2',
  calendar: 'M5 5h14v15H5zM5 9h14M9 3v4M15 3v4',
  location: 'M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11z',
  pin: 'M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11zM12 12.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 0 0 0 5z',
  navigate: 'M3 11l18-8-8 18-2-8z',
  phone: 'M6 3h3l2 5-2.5 1.5a11 11 0 0 0 6 6L16 13l5 2v3a3 3 0 0 1-3 3C9.7 21 3 14.3 3 6a3 3 0 0 1 3-3z',
  mobile: 'M7 3h10a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2',
  chat: 'M4 5h16v11H9l-5 4z',
  chatDots: 'M4 5h16v11H9l-5 4zM8.5 10.5h.01M12 10.5h.01M15.5 10.5h.01',
  headset: 'M4 14v-2a8 8 0 1 1 16 0v2M4 14h3v5H5a1 1 0 0 1-1-1zM20 14h-3v5h2a1 1 0 0 0 1-1zM17 19c0 1.5-2 2-5 2',
  store: 'M4 9l2-5h12l2 5M4 9v11h16V9M4 9h16M9 20v-6h6v6',
  shield: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM8.5 12l2.5 2.5 4.5-5',
  shieldPlain: 'M12 3l7 3v6c0 4.5-3 7.5-7 9c-4-1.5-7-4.5-7-9V6z',
  heart: 'M12 20s-7-4.6-9-9.1C1.7 7.7 3.8 5 6.7 5c2 0 3.6 1 5.3 3 1.7-2 3.3-3 5.3-3 2.9 0 5 2.7 3.7 5.9-2 4.5-9 9.1-9 9.1z',
  play: 'M8 5l11 7-11 7z',
  lock: 'M6 11h12v9H6zM9 11V8a3 3 0 0 1 6 0v3',
  wallet: 'M4 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM4 7V6a2 2 0 0 1 2-2h10v3M16 13.5h.01',
  doc: 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h5',
  download: 'M6 3h8l4 4v14H6zM14 3v4h4M12 11v6M9.5 14.5L12 17l2.5-2.5',
  upload: 'M7 18a4 4 0 0 1-.5-8A6 6 0 0 1 18 9.5a4.5 4.5 0 0 1-.5 8.5M12 20v-8M9 15l3-3 3 3',
  image: 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9h.01',
  camera: 'M4 8h3l2-3h6l2 3h3v11H4zM12 16.5a3.5 3.5 0 1 0 0-7a3.5 3.5 0 1 0 0 7z',
  edit: 'M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17zM13.5 7.5l3 3',
  qr: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 14h2M14 18h2v2M18 18h2v2',
  truck: 'M2 7h12v9H2zM14 10h4l3 3v3h-7zM6.5 19a1.5 1.5 0 1 0 0-3a1.5 1.5 0 1 0 0 3zM17.5 19a1.5 1.5 0 1 0 0-3a1.5 1.5 0 1 0 0 3z',
  box: 'M3 7l9-4 9 4v10l-9 4-9-4zM3 7l9 4 9-4M12 11v10',
  bell: 'M6 16v-5a6 6 0 1 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
  gear: 'M12 15a3 3 0 1 0 0-6a3 3 0 1 0 0 6zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z',
  person: 'M12 12a4 4 0 1 0 0-8a4 4 0 1 0 0 8zM4 21c1-4 4-6 8-6s7 2 8 6',
  checkCircle: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM8 12l3 3 5-6',
  undo: 'M9 7L4 12l5 5M4 12h11a5 5 0 0 1 5 5',
  refresh: 'M12 3a9 9 0 1 0 9 9',
  spinner: 'M12 3a9 9 0 0 1 9 9',
  arrowRight: 'M4 12h15M13 6l6 6-6 6',
  swap: 'M4 9h14l-3-3M20 15H6l3 3',
  grid: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
  book: 'M12 6.5C10 5 7 4.5 4 5v14c3-.5 6 0 8 1.5c2-1.5 5-2 8-1.5V5c-3-.5-6 0-8 1.5zM12 6.5V20.5',
  flag: 'M6 21V4M6 4h11l-2 4 2 4H6',
  // Bottom tab bar (HomeFull.html `tabs`).
  tabHome: 'M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z',
  tabDiscover: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM15.5 8.5l-2 5-5 2 2-5z',
  tabMessage: 'M4 5h16v11H9l-5 4zM8.5 10.5h.01M12 10.5h.01M15.5 10.5h.01',
  tabCart: 'M3 4h2l2 11h11l2-8H6M9 20a1 1 0 1 0 0-.1M17 20a1 1 0 1 0 0-.1',
  tabMine: 'M12 12a4 4 0 1 0 0-8a4 4 0 1 0 0 8zM4 21c1-4 4-6 8-6s7 2 8 6',
  // Home entries (HomeFull.html `renderVals`).
  drone: 'M10 10h4v4h-4zM10 10L7.3 7.3M14 10l2.7-2.7M14 14l2.7 2.7M10 14l-2.7 2.7M3 5.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M16 5.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M16 18.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M3 18.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0',
  robot: 'M6 8h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2zM12 8V5.5M10.8 4a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0-2.4 0M9 12.5v1M15 12.5v1M9.5 16h5M2 12v3M22 12v3',
  game: 'M7 8h10l3 10-3 2-4-4h-2l-4 4-3-2zM7 11v4M5 13h4M15 12h.01M17 14h.01',
  service: 'M9 3.5h6a1 1 0 0 1 1 1V6a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V4.5a1 1 0 0 1 1-1zM16 5h2a1.5 1.5 0 0 1 1.5 1.5v13A1.5 1.5 0 0 1 18 21H6a1.5 1.5 0 0 1-1.5-1.5v-13A1.5 1.5 0 0 1 6 5h2M8.5 13.5l2.5 2.5 4.5-5',
  rental: 'M4 8h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM9 8V5.5A1.5 1.5 0 0 1 10.5 4h3A1.5 1.5 0 0 1 15 5.5V8M3 13h18M10.5 13v2h3v-2',
  course: 'M2.5 9.5L12 5l9.5 4.5L12 14zM6.5 11.5V16c0 1.4 2.5 2.8 5.5 2.8s5.5-1.4 5.5-2.8v-4.5M21.5 9.5v5',
  trophy: 'M8 4h8v5a4 4 0 0 1-8 0zM12 13v4M8 20h8M8 6H5v2a3 3 0 0 0 3 3M16 6h3v2a3 3 0 0 1-3 3',
  studyTour: 'M5 4h10l4 4v12H5zM9 12h6M9 16h6',
  venue: 'M3 21h18M5 21V10l7-5 7 5v11M9 21v-6h6v6',
  screenPlay: 'M4 5h16v11H4zM8 20h8M12 16v4M10 8.5l4 2.5-4 2.5z',
} as const

export type LineIconName = keyof typeof LINE_ICON_PATHS

export interface LineIconSource {
  d: string
  color: string
  strokeWidth: number
  fill?: string
}

/** Builds a data URI so the icon renders through <image> on H5, App and mini programs alike. */
export function lineIconDataUri({ d, color, strokeWidth, fill }: LineIconSource): string {
  const paint = fill
    ? `fill="${fill}" stroke="none"`
    : `fill="none" stroke="${color}" stroke-width="${strokeWidth}" stroke-linecap="round" stroke-linejoin="round"`
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" ${paint}><path d="${d}"/></svg>`
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

export function lineIconPath(name: LineIconName): string {
  return LINE_ICON_PATHS[name]
}
