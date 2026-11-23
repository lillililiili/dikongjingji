import type { FormItemRule, FormRules } from 'element-plus'

import { isHttpsUrl } from '@/core/ops/media-api'
import { yuanToMinor } from '@/core/ops/ops-common'
import { maxChars, required } from '@/core/ui/form-rules'

import { isoOfBeijingInput, LIMITS, type ActivityForm, type SessionForm } from './activity-api'

/** 和后端 ActivityOpsService 的校验一致；后端还会再查一次。 */
function httpsOrEmpty(label: string): FormItemRule {
  return {
    trigger: 'change',
    validator: (_rule, value: unknown, callback) => {
      const text = typeof value === 'string' ? value.trim() : ''
      callback(text !== '' && !isHttpsUrl(text) ? new Error(`${label}只能是 https 地址`) : undefined)
    },
  }
}

function priceRule(form: () => ActivityForm): FormItemRule {
  return {
    trigger: 'blur',
    validator: (_rule, value: unknown, callback) => {
      if (form().priceMode !== 'PER_PERSON') {
        callback()
        return
      }
      const minor = yuanToMinor(typeof value === 'string' ? value : '')
      if (minor === null || minor < 1 || minor > LIMITS.priceMinor) {
        callback(new Error('按人收费要填单价：0.01–100000 元，最多两位小数'))
        return
      }
      callback()
    },
  }
}

function ageRule(form: () => ActivityForm): FormItemRule {
  return {
    trigger: 'change',
    validator: (_rule, _value: unknown, callback) => {
      const { minAge, maxAge } = form()
      callback(minAge !== null && maxAge !== null && minAge > maxAge ? new Error('最小年龄不能大于最大年龄') : undefined)
    },
  }
}

function differentFrom(form: () => ActivityForm): FormItemRule {
  return {
    trigger: 'change',
    validator: (_rule, value: unknown, callback) => {
      callback(value && value === form().organizerId ? new Error('承办机构不能和主办机构相同') : undefined)
    },
  }
}

export function activityRules(form: () => ActivityForm): FormRules {
  return {
    type: [required('活动类型', 'change')],
    title: [required('活动名称'), maxChars('活动名称', LIMITS.title)],
    subtitle: [maxChars('一句话介绍', LIMITS.subtitle)],
    coverUrl: [httpsOrEmpty('封面')],
    organizerId: [required('主办机构', 'change')],
    coOrganizerId: [differentFrom(form)],
    cityCode: [required('城市', 'change')],
    venueName: [required('地点名称'), maxChars('地点名称', LIMITS.venueName)],
    venueAddress: [required('详细地址'), maxChars('详细地址', LIMITS.venueAddress)],
    scheduleNote: [maxChars('时间说明', LIMITS.scheduleNote)],
    priceMode: [required('收费方式', 'change')],
    priceYuan: [priceRule(form)],
    maxAge: [ageRule(form)],
    eligibilityNote: [maxChars('参与条件', LIMITS.shortNote)],
    seatNote: [maxChars('名额说明', LIMITS.shortNote)],
    refundNote: [maxChars('退改说明', LIMITS.refundNote)],
    enrollmentNotice: [maxChars('报名须知', LIMITS.enrollmentNotice)],
  }
}

function beijingTime(label: string, needed: boolean): FormItemRule {
  return {
    trigger: 'change',
    validator: (_rule, value: unknown, callback) => {
      const text = typeof value === 'string' ? value.trim() : ''
      if (text === '') {
        callback(needed ? new Error(`请填写${label}`) : undefined)
        return
      }
      callback(isoOfBeijingInput(text) ? undefined : new Error(`${label}格式不对`))
    },
  }
}

function before(form: () => SessionForm, field: keyof SessionForm, message: string, allowEqual: boolean): FormItemRule {
  return {
    trigger: 'change',
    validator: (_rule, value: unknown, callback) => {
      const mine = isoOfBeijingInput(typeof value === 'string' ? value : '')
      const starts = isoOfBeijingInput(String(form()[field] ?? ''))
      if (!mine || !starts) {
        callback()
        return
      }
      const diff = new Date(mine).getTime() - new Date(starts).getTime()
      callback(diff > 0 || (!allowEqual && diff === 0) ? new Error(message) : undefined)
    },
  }
}

function after(form: () => SessionForm, message: string): FormItemRule {
  return {
    trigger: 'change',
    validator: (_rule, value: unknown, callback) => {
      const mine = isoOfBeijingInput(typeof value === 'string' ? value : '')
      const starts = isoOfBeijingInput(form().startsAt)
      callback(mine && starts && new Date(mine).getTime() <= new Date(starts).getTime() ? new Error(message) : undefined)
    },
  }
}

const capacityRule: FormItemRule = {
  trigger: 'change',
  validator: (_rule, value: unknown, callback) => {
    const number = typeof value === 'number' ? value : NaN
    callback(Number.isInteger(number) && number >= 1 && number <= LIMITS.capacity
      ? undefined
      : new Error(`名额是 1–${LIMITS.capacity} 的整数`))
  },
}

export function sessionRules(form: () => SessionForm): FormRules {
  return {
    label: [maxChars('场次名称', LIMITS.label)],
    startsAt: [beijingTime('开始时间', true)],
    endsAt: [beijingTime('结束时间', false), after(form, '结束时间要晚于开始时间')],
    enrollmentClosesAt: [beijingTime('报名截止', true),
      before(form, 'startsAt', '报名截止不能晚于开始时间', true)],
    checkInOpensAt: [beijingTime('签到开放', false), before(form, 'startsAt', '签到开放不能晚于开始时间', true)],
    capacity: [capacityRule],
  }
}
