import { resolvePublicApiUrl } from '@/shared/http/public-api-request'

// Hands the one-time link of a personal-data copy to the platform: the browser downloads it on H5,
// the App saves it with the downloader. The link works once and for five minutes; it carries no
// login, so it is never logged or kept.
export async function downloadExport(downloadUrl: string, fileName: string): Promise<string> {
  const url = await resolvePublicApiUrl(downloadUrl)
  // #ifdef H5
  if (typeof window !== 'undefined') {
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    link.rel = 'noopener'
    document.body.appendChild(link)
    link.click()
    link.remove()
    return '已开始下载'
  }
  // #endif
  return new Promise((resolve, reject) => {
    uni.downloadFile({
      url,
      success: (result) => {
        if (result.statusCode !== 200) {
          reject(new Error('下载链接已失效，请重新获取'))
          return
        }
        uni.saveFile({
          tempFilePath: result.tempFilePath,
          success: () => resolve('已保存到本机'),
          fail: () => resolve('已下载'),
        })
      },
      fail: () => reject(new Error('下载失败，请重试')),
    })
  })
}
