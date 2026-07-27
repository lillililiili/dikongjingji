declare module '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue' {
  import type { DefineComponent } from 'vue'

  const UniIcons: DefineComponent<{
    type?: string
    color?: string
    size?: string | number
    customPrefix?: string
    fontFamily?: string
  }>

  export default UniIcons
}
