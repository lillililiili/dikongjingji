import { defineStore } from 'pinia'

/**
 * The shared picture viewer (board PhotoViewer): review pictures, delivery pictures and content
 * pictures open here. The pictures travel through this store, never through the address; each page
 * passes what it already shows. A picture behind a controlled link (a delivery file) says when its
 * link stops working; saving it goes back to the page that knows how (its `save`).
 */
export interface ViewerPhoto {
  url: string
  alt: string
  // The small tag on the picture (“整机外观”); empty for none.
  tag: string
  // The rows under the picture; a missing one is not shown.
  time?: string
  by?: string
  note?: string
}

export interface PhotoViewerRequest {
  title: string
  subtitle: string
  photos: ViewerPhoto[]
  startIndex: number
  // The 原图 line at the bottom (“第 1 张 · 整机外观 · 原图”) and its button. Absent: the pictures
  // can only be looked at, which the page says.
  save?: (index: number) => Promise<{ until: string | null }>
}

export const PHOTO_VIEWER_PATH = '/pages/common/photo-viewer'

export const usePhotoViewerStore = defineStore('photo-viewer', {
  state: (): { request: PhotoViewerRequest | null } => ({ request: null }),
  actions: {
    show(request: PhotoViewerRequest): void {
      const photos = request.photos.filter((photo) => /^(https:\/\/|\/static\/|data:image\/)/.test(photo.url) || /^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?\//.test(photo.url))
      this.request = {
        ...request,
        photos,
        startIndex: Math.min(Math.max(0, request.startIndex), Math.max(0, photos.length - 1)),
      }
    },
  },
})

export function openPhotoViewer(request: PhotoViewerRequest): void {
  usePhotoViewerStore().show(request)
  uni.navigateTo({ url: PHOTO_VIEWER_PATH })
}

// "第 2 张，共 3 张" / "2 / 3"
export function photoPageText(index: number, count: number): { text: string; aria: string } {
  return { text: `${index + 1} / ${count}`, aria: `第 ${index + 1} 张，共 ${count} 张` }
}
