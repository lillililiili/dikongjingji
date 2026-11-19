import { resolveMallPreviewCover } from '../data/preview/mall-preview-covers'
import type { MallImage } from '../domain/mall'

// The product's picture: the server's when it has one, else the sample picture a preview build
// shows for the development seed's products (as the mall pages do); null for none.
export function inquiryCoverUrl(coverImage: MallImage | null, productId: string): string | null {
  return coverImage?.url ?? resolveMallPreviewCover(productId)?.url ?? null
}
