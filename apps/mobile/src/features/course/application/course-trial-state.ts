import { shallowRef } from 'vue'

import type { CourseApi } from '../data/course-api'
import type { CourseTrial } from '../domain/course'

type TrialState = { kind: 'loading' } | { kind: 'ready'; trial: CourseTrial } | { kind: 'missing' } | { kind: 'error' }

export function useCourseTrialState(read: CourseApi['loadTrial']) {
  const state = shallowRef<TrialState>({ kind: 'loading' })
  let revision = 0
  async function load(target: Parameters<typeof read>[0] | null): Promise<void> {
    const requested = ++revision
    if (!target) {
      state.value = { kind: 'missing' }
      return
    }
    state.value = { kind: 'loading' }
    try {
      const trial = await read(target)
      if (requested === revision) state.value = { kind: 'ready', trial }
    } catch (error) {
      if (requested !== revision) return
      state.value = { kind: error && typeof error === 'object' && 'status' in error && error.status === 404 ? 'missing' : 'error' }
    }
  }
  return { state, load }
}
