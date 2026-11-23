import type { FormItemRule } from 'element-plus'

import { charCount } from './format'

/**
 * 表单校验规则（Element Plus 的 FormItemRule）。和后端的校验一致：长度按码点算、去掉首尾空白再判断；
 * 后端仍然会再校验一次，这里只是让人提交前就知道哪里不对。
 */
export function required(label: string, trigger: 'blur' | 'change' = 'blur'): FormItemRule {
  return {
    trigger,
    validator: (_rule, value: unknown, callback) => {
      const empty = value === null || value === undefined
        || (typeof value === 'string' && value.trim() === '')
        || (Array.isArray(value) && value.length === 0)
      callback(empty ? new Error(`请填写${label}`) : undefined)
    },
  }
}

export function maxChars(label: string, max: number): FormItemRule {
  return {
    trigger: 'blur',
    validator: (_rule, value: unknown, callback) => {
      const text = typeof value === 'string' ? value.trim() : ''
      callback(charCount(text) > max ? new Error(`${label}最多 ${max} 个字`) : undefined)
    },
  }
}

export function matches(pattern: RegExp, message: string, transform: (value: string) => string = (v) => v.trim()):
FormItemRule {
  return {
    trigger: 'blur',
    validator: (_rule, value: unknown, callback) => {
      const text = typeof value === 'string' ? transform(value) : ''
      callback(text !== '' && !pattern.test(text) ? new Error(message) : undefined)
    },
  }
}

/** 直接跑一组规则（单元测试和不在 el-form 里的输入用）：返回第一条错误，没有错误返回 null。 */
export async function firstError(rules: FormItemRule[], value: unknown): Promise<string | null> {
  for (const rule of rules) {
    const message = await new Promise<string | null>((resolve) => {
      const validator = rule.validator
      if (!validator) {
        resolve(null)
        return
      }
      validator(rule, value, (error?: string | Error) => {
        resolve(error ? (typeof error === 'string' ? error : error.message) : null)
      }, {}, {})
    })
    if (message) return message
  }
  return null
}
