import { resolveMallPreviewCover } from '@/features/mall/data/preview/mall-preview-covers'

// The device's picture: the server's when it has one, else the sample picture a preview build
// shows for the development seed's products (as the mall and the trade pages do); null for none.
export function rentalCoverUrl(coverUrl: string | null, productId: string): string | null {
  return coverUrl ?? resolveMallPreviewCover(productId)?.url ?? null
}
