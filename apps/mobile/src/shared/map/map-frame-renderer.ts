interface MapSdk {
  Map: new (element: HTMLElement, options: {center: number[]; zoom: number; viewMode: string}) => MapView
  Marker: new (options: {position: number[]; title: string}) => object
}
interface MapView {
  on(event: string, fn: () => void): void
  add(marker: object): void
  destroy(): void
}
interface MapInput {
  source: string; attempt: number; latitude: number; longitude: number; title: string
  webKey: string; proxyPath: string
}
interface Owner { callMethod(method: 'mapState', value: 'error' | 'ready'): void }
interface ViewInstance {
  $el: HTMLElement
  releaseMap(): void
  mapRequest?: number
  mapTimeout?: ReturnType<typeof setTimeout>
  stopProbe?: AbortController
  offline?: () => void
  mapInstance?: MapView | null
  frame?: HTMLIFrameElement | null
  host?: HTMLElement | null
}
declare module '@vue/runtime-core' {
  interface ComponentCustomProperties {
    destinationMapRenderer: {draw(input: MapInput | null, previous: unknown, owner: Owner): void}
  }
}
let sdkPromise: Promise<MapSdk> | undefined
function webSdk(key: string, proxyPath: string): Promise<MapSdk> {
  const webWindow = window as Window & { AMap?: MapSdk; _AMapSecurityConfig?: {serviceHost: string} }
  if (webWindow.AMap && webWindow.AMap.Map) return Promise.resolve(webWindow.AMap)
  if (!sdkPromise) {
    sdkPromise = new Promise<MapSdk>((resolve, reject) => {
      const proxy = new URL(proxyPath, location.origin)
      if (proxy.origin !== location.origin) throw new Error('Map proxy must use this origin')
      webWindow._AMapSecurityConfig = { serviceHost: proxy.href }
      const script = document.createElement('script')
      const stop = () => { script.remove(); reject(new Error('Map SDK did not load')) }
      const timeout = setTimeout(stop, 10000)
      script.onload = () => {
        clearTimeout(timeout)
        if (webWindow.AMap && webWindow.AMap.Map) resolve(webWindow.AMap)
        else stop()
      }
      script.onerror = () => { clearTimeout(timeout); stop() }
      script.src = 'https://webapi.amap.com/maps?v=2.0&key=' + encodeURIComponent(key)
      document.head.appendChild(script)
    }).catch(error => { sdkPromise = undefined; throw error })
  }
  return sdkPromise
}

export default {
  beforeUnmount(this: ViewInstance) { this.releaseMap() },
  methods: {
    releaseMap(this: ViewInstance) {
      this.mapRequest = (this.mapRequest || 0) + 1
      clearTimeout(this.mapTimeout)
      if (this.stopProbe) this.stopProbe.abort()
      if (this.offline) window.removeEventListener('offline', this.offline)
      if (this.mapInstance) { this.mapInstance.destroy(); this.mapInstance = null }
      if (this.frame) { this.frame.onload = null; this.frame.onerror = null; this.frame.remove(); this.frame = null }
      if (this.host) { this.host.remove(); this.host = null }
    },
    draw(this: ViewInstance, input: MapInput | null, _previous: unknown, owner: Owner) {
      if (!input || !input.source) return
      this.releaseMap()
      const token = this.mapRequest
      const tell = (value: 'ready' | 'error') => {
        if (this.mapRequest !== token) return
        clearTimeout(this.mapTimeout)
        owner.callMethod('mapState', value)
      }
      const fail = () => { if (this.mapRequest === token) { tell('error'); this.releaseMap() } }
      // App renderjs mounts the logic on a comment node. Each native page has
      // its own document. H5 instead provides this component's element.
      const container = this.$el instanceof HTMLElement
        ? this.$el : document.querySelector<HTMLElement>('.destination-map')
      if (!container) { fail(); return }
      this.offline = fail
      window.addEventListener('offline', fail)
      this.mapTimeout = setTimeout(fail, 18000)
      if (navigator.onLine === false) { fail(); return }
      if (input.webKey && input.proxyPath) {
        webSdk(input.webKey, input.proxyPath).then(sdk => {
          if (token !== this.mapRequest) return
          this.host = document.createElement('div')
          this.host.style.cssText = 'width:100%;height:100%'
          container.appendChild(this.host)
          const center = [input.longitude, input.latitude]
          const map = this.mapInstance = new sdk.Map(this.host, {center, zoom:16, viewMode:'2D'})
          map.on('complete', () => tell('ready'))
          map.on('error', fail)
          map.add(new sdk.Marker({position:center, title:input.title}))
        }).catch(fail)
        return
      }
      const frame = this.frame = document.createElement('iframe')
      frame.title = input.title
      frame.style.cssText = 'width:100%;height:100%;border:0;display:block'
      frame.setAttribute('sandbox', 'allow-scripts allow-same-origin')
      frame.referrerPolicy = 'strict-origin-when-cross-origin'
      frame.onerror = fail
      frame.onload = () => {
        if (token !== this.mapRequest) return
        // Browsers also fire iframe.load for a DNS error page. An opaque HEAD checks
        // reachability without trying to inspect the provider's cross-origin document.
        this.stopProbe = typeof AbortController === 'function' ? new AbortController() : undefined
        fetch(input.source, {method:'HEAD', mode:'no-cors', credentials:'omit',
          signal:this.stopProbe ? this.stopProbe.signal : undefined}).then(() => tell('ready'), fail)
      }
      frame.src = input.source
      container.appendChild(frame)
    },
  },
}
