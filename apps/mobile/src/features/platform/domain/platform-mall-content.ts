export interface PlatformMallCategory {
  code: string
  title: string
  subtitle: string
  imageUrl: string
}
export interface PlatformMallItem {
  id: string
  title: string
  subtitle: string
  imageUrl: string
  priceLabel: string
  salesLabel?: string
  badges?: string[]
}

export interface PlatformMallSolution {
  id: string
  title: string
  subtitle: string
  imageUrl: string
}

export interface PlatformMallContent {
  hero: {
    imageUrl: string
    title: string
    highlight: string
    description: string
    highlights: readonly string[]
    actionLabel: string
  }
  categories: PlatformMallCategory[]
  products: PlatformMallItem[]
  rentals: PlatformMallItem[]
  solutions: PlatformMallSolution[]
  brands: string[]
  stores: PlatformMallItem[]
}
