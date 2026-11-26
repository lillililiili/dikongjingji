import { ref, shallowRef } from 'vue'

import { errorMessage } from '@/core/http/api-error'

/** 留痕读取与资源写操作独立；切换记录或重试时只接收最后一次请求。 */
export function useChangeHistory<T>(read: (id: string) => Promise<T[]>) {
  const changes = shallowRef<T[]>([])
  const loading = ref(false)
  const error = ref('')
  let sequence = 0

  async function load(id: string): Promise<void> {
    const request = ++sequence
    changes.value = []
    error.value = ''
    loading.value = true
    try {
      const result = await read(id)
      if (request === sequence) changes.value = result
    } catch (failure) {
      if (request === sequence) error.value = errorMessage(failure)
    } finally {
      if (request === sequence) loading.value = false
    }
  }
  return { changes, loading, error, load }
}
