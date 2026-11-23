// Opens a short-lived download link. The link answers with the file (Content-Disposition:
// attachment), so the H5 page stays where it is; the App hands it to the system browser, which
// downloads it.
export function openServiceOrderFile(url: string, fileName: string): void {
  // #ifdef H5
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  anchor.rel = 'noopener'
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // #endif
  // #ifdef APP-PLUS
  plus.runtime.openURL(url)
  // #endif
}
