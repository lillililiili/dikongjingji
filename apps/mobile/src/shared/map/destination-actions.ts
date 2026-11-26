import { destinationPageUrl, mapApps, positionOf, routeUrl, type Destination } from './destination'

export function copyDestination(place: Destination) {
  uni.setClipboardData({ data: place.address || place.name,
    success: () => uni.showToast({ title: '已复制地址', icon: 'none' }),
    fail: () => uni.showModal({ title: place.name, content: place.address, showCancel: false }),
  })
}

export function viewDestination(place: Destination) {
  uni.navigateTo({ url: destinationPageUrl(place) })
}

export function chooseNavigation(place: Destination) {
  if (!positionOf(place)) {
    uni.showModal({ title: '暂无精确位置', content: place.address || place.name, confirmText: '复制地址',
      success: ({ confirm }) => { if (confirm) copyDestination(place) },
    })
    return
  }
  const ios = uni.getSystemInfoSync().platform === 'ios'
  const choices = mapApps.filter(app => app.id !== 'apple' || ios).map(app => {
    let installed = false
    // #ifdef APP-PLUS
    try { installed = plus.runtime.isApplicationExist({ pname: app.pkg, action: app.ios }) } catch { /* system restricts detection */ }
    // #endif
    return { ...app, installed }
  }).sort((a, b) => Number(b.installed) - Number(a.installed))
  const items = choices.map(app => {
    // #ifdef APP-PLUS
    return app.label + (app.installed ? '' : '（网页版）')
    // #endif
    // #ifndef APP-PLUS
    return app.label
    // #endif
  })
  uni.showActionSheet({ title: '选择导航地图 · ' + place.name, itemList: [...items, '复制地址'],
    success: ({ tapIndex }) => {
      const app = choices[tapIndex]
      if (!app) { copyDestination(place); return }
      const web = routeUrl(app.id, place, false, ios)!
      // #ifdef APP-PLUS
      const openWeb = () => plus.runtime.openURL(web, () => uni.showModal({
        title: '未能打开地图', content: place.address, confirmText: '复制地址',
        success: ({ confirm }) => { if (confirm) copyDestination(place) },
      }))
      if (app.installed) plus.runtime.openURL(routeUrl(app.id, place, true, ios)!, openWeb, ios ? undefined : app.pkg)
      else openWeb()
      // #endif
      // #ifdef H5
      window.location.assign(web)
      // #endif
    },
  })
}
