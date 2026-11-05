import { createPinia } from 'pinia'
import { createSSRApp } from 'vue'

import App from './App.vue'
// #ifdef H5
import { createBackGuard } from './app-shell/navigation/h5-back-guard'
// #endif

export function createApp() {
  const app = createSSRApp(App)

  app.use(createPinia())

  // #ifdef H5
  uni.addInterceptor('navigateBack', {
    invoke: createBackGuard(() => getCurrentPages().slice(-1)[0]),
  })
  // #endif

  return {
    app,
  }
}

