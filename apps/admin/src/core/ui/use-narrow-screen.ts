import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/** 窗口窄到放不下完整侧栏时为 true（侧栏收成图标）。没有 matchMedia 的环境一直按宽屏。 */
export function useNarrowScreen(maxWidth = 960): Ref<boolean> {
  const query = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(`(max-width: ${maxWidth}px)`)
    : null
  const narrow = ref(query?.matches ?? false)
  const update = (event: { matches: boolean }) => {
    narrow.value = event.matches
  }
  onMounted(() => query?.addEventListener('change', update))
  onBeforeUnmount(() => query?.removeEventListener('change', update))
  return narrow
}
