import { defineComponent } from 'vue'

export default defineComponent({
  props: {
    source: { type: String, required: true },
    attempt: { type: Number, required: true },
    latitude: { type: Number, required: true },
    longitude: { type: Number, required: true },
    title: { type: String, required: true },
  },
  emits: { state: (value: 'ready' | 'error') => value === 'ready' || value === 'error' },
  data() { return { mountedMap: false } },
  mounted() { this.mountedMap = true },
  computed: {
    mapInput() {
      if (!this.mountedMap) return null
      let webKey = ''
      let proxyPath = ''
      // #ifdef H5
      webKey = import.meta.env.VITE_AMAP_WEB_KEY || ''
      proxyPath = import.meta.env.VITE_AMAP_WEB_PROXY_PATH || ''
      // #endif
      return { ...this.$props, webKey, proxyPath }
    },
  },
  methods: {
    // renderjs calls the component's public Options API method on App and H5.
    mapState(value: 'ready' | 'error') { this.$emit('state', value) },
  },
})
