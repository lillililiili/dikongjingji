import { describe, expect, it, vi } from 'vitest'

import { useCourseTrialState } from '../application/course-trial-state'
import { createCourseApi } from '../data/course-api'

describe('试听页切换入口', () => {
  it.each(['success', 'error'])('前一次读取 %s 晚到时仍显示新课程的仿真关联', async outcome => {
    let finish!: () => void
    const first = new Promise((resolve, reject) => {
      finish = () => outcome === 'success'
        ? resolve({ courseId: 'old-course', simulationItemId: 'robot' })
        : reject({ status: 404 })
    })
    const request = vi.fn().mockReturnValueOnce(first)
      .mockResolvedValueOnce({ courseId: 'new-course', simulationItemId: 'drone' })
    const { state, load } = useCourseTrialState(createCourseApi(request).loadTrial)
    const pending = load({ experienceItemId: 'old-entry' })
    await load({ courseId: 'new-course' })
    finish()
    await pending
    expect(state.value).toMatchObject({ kind: 'ready', trial: { courseId: 'new-course', simulationItemId: 'drone' } })
  })
})
