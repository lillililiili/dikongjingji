import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import { nextTick, reactive } from 'vue'

import { outlineFormOf, type ChapterForm } from './course-api'
import CourseOutlineEditor from './CourseOutlineEditor.vue'

function chapters(): ChapterForm[] {
  return reactive(outlineFormOf([
    { chapterId: 'ch-1', title: '第一章', lessons: [
      { lessonId: 'l-1', no: 1, title: '一', durationSeconds: 60, trial: false, videoUrl: null, learnerCount: 0 },
      { lessonId: 'l-2', no: 2, title: '二', durationSeconds: 60, trial: false, videoUrl: null, learnerCount: 0 },
    ] },
    { chapterId: 'ch-2', title: '第二章', lessons: [
      { lessonId: 'l-3', no: 3, title: '三', durationSeconds: 60, trial: false, videoUrl: null, learnerCount: 5 },
    ] },
  ])) as ChapterForm[]
}

function titles(outline: ChapterForm[]): string[][] {
  return outline.map((chapter) => chapter.lessons.map((lesson) => lesson.title))
}

function buttons(text: string): HTMLButtonElement[] {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('.outline__lesson button'))
    .filter((candidate) => candidate.textContent?.trim() === text)
}

describe('目录编辑', () => {
  it('课时到了章尾再下移进下一章开头，到了章头再上移进上一章末尾；课序跨章连续编号', async () => {
    document.body.innerHTML = ''
    const outline = chapters()
    mount(CourseOutlineEditor, { props: { chapters: outline }, global: { plugins: [ElementPlus] }, attachTo: document.body })

    expect(Array.from(document.body.querySelectorAll('.outline__lesson-no')).map((node) => node.textContent))
      .toEqual(['第 1 课', '第 2 课', '第 3 课'])
    expect(buttons('上移')[0]!.disabled).toBe(true)
    expect(buttons('下移')[2]!.disabled).toBe(true)

    buttons('下移')[1]!.click()
    await nextTick()
    expect(titles(outline)).toEqual([['一'], ['二', '三']])

    buttons('上移')[1]!.click()
    await nextTick()
    expect(titles(outline)).toEqual([['一', '二'], ['三']])
  })

  it('有人学过的课时和它所在的章不能删；新加的课时 ID 为空', async () => {
    document.body.innerHTML = ''
    const outline = chapters()
    mount(CourseOutlineEditor, { props: { chapters: outline }, global: { plugins: [ElementPlus] }, attachTo: document.body })

    const removes = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.outline__lesson-remove'))
    expect(removes.map((candidate) => candidate.disabled)).toEqual([false, false, true])
    expect(document.body.textContent).toContain('已有 5 人学过')

    const add = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .filter((candidate) => candidate.textContent?.trim() === '加一节课时')
    add[1]!.click()
    await nextTick()
    expect(outline[1]!.lessons.map((lesson) => lesson.lessonId)).toEqual(['l-3', null])
  })
})
