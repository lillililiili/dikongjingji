// 去合作平台回看: the replay is on a partner platform outside the App (board LiveNotice). Only an
// https address the server gave is opened, and only after the viewer confirmed leaving the App.
export function openPartnerReplay(url: string): boolean {
  if (!/^https:\/\/\S+$/i.test(url)) return false
  // #ifdef APP-PLUS
  plus.runtime.openURL(url)
  // #endif
  // #ifdef H5
  window.open(url, '_blank', 'noopener')
  // #endif
  return true
}
