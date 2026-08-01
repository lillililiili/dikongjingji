export interface PlatformDiscoverItem {
  id: string
  title: string
  subtitle: string
  imageUrl: string
  tag?: string
  meta?: string
  metric?: string
  priceLabel?: string
}
export interface PlatformDiscoverCommunity {
  id: string
  name: string
  memberLabel: string
  imageUrl: string
}

export interface PlatformDiscoverContent {
  hero: {
    imageUrl: string
    title: string
    highlight: string
    description: string
    highlights: readonly string[]
    actionLabel: string
  }
  activities: PlatformDiscoverItem[]
  stories: PlatformDiscoverItem[]
  communities: PlatformDiscoverCommunity[]
}
