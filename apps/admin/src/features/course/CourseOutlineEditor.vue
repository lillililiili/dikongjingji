<script setup lang="ts">
/**
 * 线上课目录：章（标题）和课时（标题、时长、是否试听、视频）。可以增删、上下移动（课时到了章的头尾再移就进相邻的章），
 * 改的是父组件持有的目录表单（整份保存由父组件做）。有人学过的课时不能删，含这种课时的章也不能删。
 */
import MediaField from '@/core/ops/MediaField.vue'

import {
  chapterLocked,
  LIMITS,
  lessonLocked,
  newChapter,
  newLesson,
  parseDuration,
  formatDuration,
  type ChapterForm,
  type LessonForm,
} from './course-api'

const props = withDefaults(defineProps<{
  chapters: ChapterForm[]
  disabled?: boolean
}>(), { disabled: false })

function totalLessons(): number {
  return props.chapters.reduce((sum, chapter) => sum + chapter.lessons.length, 0)
}

/** 全课第几课（跨章连续编号，和后端保存后的课序一致）。 */
function lessonNo(chapterIndex: number, lessonIndex: number): number {
  return props.chapters.slice(0, chapterIndex).reduce((sum, chapter) => sum + chapter.lessons.length, 0) + lessonIndex + 1
}

function addChapter(): void {
  if (props.chapters.length >= LIMITS.chapters) return
  props.chapters.push(newChapter())
}

function removeChapter(index: number): void {
  const chapter = props.chapters[index]
  if (!chapter || chapterLocked(chapter)) return
  props.chapters.splice(index, 1)
}

function moveChapter(index: number, offset: number): void {
  const target = index + offset
  if (target < 0 || target >= props.chapters.length) return
  const [moved] = props.chapters.splice(index, 1)
  if (moved) props.chapters.splice(target, 0, moved)
}

function addLesson(chapter: ChapterForm): void {
  if (chapter.lessons.length >= LIMITS.lessonsPerChapter || totalLessons() >= LIMITS.lessonsTotal) return
  chapter.lessons.push(newLesson())
}

function removeLesson(chapter: ChapterForm, index: number): void {
  const lesson = chapter.lessons[index]
  if (!lesson || lessonLocked(lesson)) return
  chapter.lessons.splice(index, 1)
}

function canMoveLesson(chapterIndex: number, lessonIndex: number, offset: number): boolean {
  const chapter = props.chapters[chapterIndex]
  if (!chapter) return false
  const target = lessonIndex + offset
  if (target >= 0 && target < chapter.lessons.length) return true
  const neighbour = props.chapters[chapterIndex + offset]
  return neighbour !== undefined && neighbour.lessons.length < LIMITS.lessonsPerChapter
}

function moveLesson(chapterIndex: number, lessonIndex: number, offset: number): void {
  if (!canMoveLesson(chapterIndex, lessonIndex, offset)) return
  const chapter = props.chapters[chapterIndex]!
  const target = lessonIndex + offset
  const insideChapter = target >= 0 && target < chapter.lessons.length
  const [moved] = chapter.lessons.splice(lessonIndex, 1) as [LessonForm]
  if (insideChapter) {
    chapter.lessons.splice(target, 0, moved)
    return
  }
  // 到了本章头（尾）再上（下）移：进上一章的末尾（下一章的开头）。
  const neighbour = props.chapters[chapterIndex + offset]!
  if (offset < 0) neighbour.lessons.push(moved)
  else neighbour.lessons.unshift(moved)
}

function durationHint(text: string): string {
  const seconds = parseDuration(text)
  if (seconds === null || seconds < 1 || seconds > LIMITS.lessonSeconds) return '按“分:秒”或秒数填，最长 240 分钟'
  return `${formatDuration(seconds)}（${seconds} 秒）`
}
</script>

<template>
  <div class="outline">
    <div v-for="(chapter, chapterIndex) in chapters" :key="chapter.key" class="outline__chapter">
      <div class="outline__chapter-head">
        <span class="outline__chapter-no">第 {{ chapterIndex + 1 }} 章</span>
        <el-input v-model="chapter.title" :maxlength="LIMITS.chapterTitle" show-word-limit placeholder="章名"
                  :disabled="disabled" class="outline__chapter-title" />
        <el-button size="small" link :disabled="disabled || chapterIndex === 0" @click="moveChapter(chapterIndex, -1)">上移</el-button>
        <el-button size="small" link :disabled="disabled || chapterIndex === chapters.length - 1"
                   @click="moveChapter(chapterIndex, 1)">下移</el-button>
        <el-tooltip :disabled="!chapterLocked(chapter)" content="这一章里有已经有人学过的课时，不能删整章">
          <span>
            <el-button size="small" link type="danger" :disabled="disabled || chapterLocked(chapter)"
                       @click="removeChapter(chapterIndex)">删除本章</el-button>
          </span>
        </el-tooltip>
      </div>

      <div v-for="(lesson, lessonIndex) in chapter.lessons" :key="lesson.key" class="outline__lesson"
           :data-lesson="lesson.lessonId ?? 'new'">
        <div class="outline__lesson-row">
          <span class="outline__lesson-no">第 {{ lessonNo(chapterIndex, lessonIndex) }} 课</span>
          <el-input v-model="lesson.title" size="small" :maxlength="LIMITS.lessonTitle" show-word-limit placeholder="课时名称"
                    :disabled="disabled" class="outline__lesson-title" />
          <el-input v-model="lesson.durationText" size="small" placeholder="时长 12:30" :disabled="disabled"
                    class="outline__lesson-duration" />
          <el-checkbox v-model="lesson.trial" :disabled="disabled">试听</el-checkbox>
          <el-button size="small" link :disabled="disabled || !canMoveLesson(chapterIndex, lessonIndex, -1)"
                     @click="moveLesson(chapterIndex, lessonIndex, -1)">上移</el-button>
          <el-button size="small" link :disabled="disabled || !canMoveLesson(chapterIndex, lessonIndex, 1)"
                     @click="moveLesson(chapterIndex, lessonIndex, 1)">下移</el-button>
          <el-tooltip :disabled="!lessonLocked(lesson)"
                      :content="`已有 ${lesson.learnerCount} 人学过，不能删；可以改标题、时长和视频`">
            <span>
              <el-button size="small" link type="danger" class="outline__lesson-remove"
                         :disabled="disabled || lessonLocked(lesson)" @click="removeLesson(chapter, lessonIndex)">删除</el-button>
            </span>
          </el-tooltip>
        </div>
        <div class="outline__lesson-meta">
          <span>{{ durationHint(lesson.durationText) }}</span>
          <span v-if="lesson.learnerCount > 0">· 已有 {{ lesson.learnerCount }} 人学过</span>
          <span v-if="!lesson.lessonId">· 新加的，保存后生效</span>
        </div>
        <MediaField v-model="lesson.videoUrl" kind="VIDEO" :disabled="disabled" />
      </div>

      <div class="outline__footer">
        <el-button size="small" :disabled="disabled || chapter.lessons.length >= LIMITS.lessonsPerChapter
          || totalLessons() >= LIMITS.lessonsTotal" @click="addLesson(chapter)">加一节课时</el-button>
        <span class="outline__count">本章 {{ chapter.lessons.length }} / {{ LIMITS.lessonsPerChapter }} 节</span>
      </div>
    </div>

    <div class="outline__footer">
      <el-button type="primary" plain size="small" :disabled="disabled || chapters.length >= LIMITS.chapters"
                 @click="addChapter">加一章</el-button>
      <span class="outline__count">
        {{ chapters.length }} / {{ LIMITS.chapters }} 章，全课 {{ totalLessons() }} / {{ LIMITS.lessonsTotal }} 节
      </span>
    </div>
  </div>
</template>

<style scoped>
.outline {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.outline__chapter {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--admin-border, #e5ebf3);
  border-radius: 8px;
}

.outline__chapter-head,
.outline__lesson-row,
.outline__footer {
  display: flex;
  align-items: center;
  gap: 8px;
}

.outline__chapter-no {
  flex-shrink: 0;
  font-weight: 700;
}

.outline__chapter-title {
  max-width: 420px;
}

.outline__lesson {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 0 8px 16px;
  border-top: 1px dashed var(--admin-border, #e5ebf3);
}

.outline__lesson-no {
  flex-shrink: 0;
  width: 56px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.outline__lesson-title {
  max-width: 360px;
}

.outline__lesson-duration {
  width: 110px;
}

.outline__lesson-meta,
.outline__count {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
