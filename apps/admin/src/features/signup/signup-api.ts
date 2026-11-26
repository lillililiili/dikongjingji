import type { CascaderOption, FormRules } from 'element-plus'

import { newIdempotencyKey, type ApiClient } from '@/core/http/api-client'
import { matches, maxChars, required } from '@/core/ui/form-rules'
import type { DirectoryCity, QualificationStatus } from '@/features/organization/organization-api'
import { LICENSE_NUMBER, LIMITS, LOGIN_NAME } from '@/features/organization/organization-rules'

// 契约 contracts/openapi-organization-signup.yaml 1.0.0 的公开接口：商家在登录页“申请入驻”。
// 不登录；验证码的频率和错误次数由后端限制。申请成功后用用户名在登录页登录。

export const SIGNUP_LIMITS = {
  organizationName: LIMITS.name,
  subjectName: LIMITS.subjectName,
  businessScope: LIMITS.businessScope,
  contactName: 40,
  passwordMin: 8,
  passwordMax: 64,
  /** 重新发码的间隔（秒），和后端的 60 秒一致。 */
  resendSeconds: 60,
} as const

export const PHONE = /^1[3-9][0-9]{9}$/
export const SMS_CODE = /^[0-9]{6}$/
/** 密码：8–64 位，至少一个字母和一个数字，不含空白。 */
export const PASSWORD = /^(?=.*[A-Za-z])(?=.*[0-9])\S{8,64}$/

export interface SignupForm {
  organizationName: string
  cityCode: string
  subjectName: string
  licenseNumber: string
  businessScope: string
  contactName: string
  phone: string
  smsCode: string
  loginName: string
  password: string
  passwordAgain: string
}

export interface SignupResult {
  organizationId: string
  organizationName: string
  loginName: string
  qualificationStatus: QualificationStatus
}

export function emptySignupForm(): SignupForm {
  return { organizationName: '', cityCode: '', subjectName: '', licenseNumber: '', businessScope: '', contactName: '',
    phone: '', smsCode: '', loginName: '', password: '', passwordAgain: '' }
}

export function signupRules(form: SignupForm): FormRules {
  return {
    organizationName: [required('机构名称'), maxChars('机构名称', SIGNUP_LIMITS.organizationName)],
    cityCode: [required('所在城市', 'change')],
    subjectName: [required('主体名称'), maxChars('主体名称', SIGNUP_LIMITS.subjectName)],
    licenseNumber: [required('统一社会信用代码'),
      matches(LICENSE_NUMBER, '统一社会信用代码是 18 位数字或大写字母（不含 I、O、Z、S、V）', (v) => v.trim().toUpperCase())],
    businessScope: [maxChars('经营范围', SIGNUP_LIMITS.businessScope)],
    contactName: [required('联系人'), maxChars('联系人', SIGNUP_LIMITS.contactName)],
    phone: [required('手机号'), matches(PHONE, '手机号格式不对')],
    smsCode: [required('验证码'), matches(SMS_CODE, '验证码是 6 位数字')],
    loginName: [required('用户名'), matches(LOGIN_NAME, '用户名 4–32 位：小写字母开头，只能用小写字母、数字、_ 和 -')],
    password: [required('密码'), matches(PASSWORD, '密码 8–64 位，至少一个字母和一个数字，不含空格', (v) => v)],
    passwordAgain: [required('确认密码'), {
      validator: (_rule, value: string, callback) => {
        callback(value === form.password ? undefined : new Error('两次输入的密码不一样'))
      },
      trigger: 'blur',
    }],
  }
}

export function signupRequestBody(form: SignupForm) {
  const scope = form.businessScope.trim()
  return {
    organizationName: form.organizationName.trim(),
    cityCode: form.cityCode,
    subjectName: form.subjectName.trim(),
    licenseNumber: form.licenseNumber.trim().toUpperCase(),
    businessScope: scope === '' ? null : scope,
    contactName: form.contactName.trim(),
    phone: form.phone.trim(),
    smsCode: form.smsCode.trim(),
    loginName: form.loginName.trim(),
    password: form.password,
  }
}

export interface DirectoryProvince {
  code: string
  name: string
  cities: DirectoryCity[]
}

interface CityDirectoryResponse {
  provinces: DirectoryProvince[]
}

/** 转为省 → 市两级级联选项；省份节点加前缀避免北京等直辖市省码与市码相同（如均为 110000）时冲突。 */
export function toCityCascaderOptions(provinces: DirectoryProvince[]): CascaderOption[] {
  return provinces
    .filter((province) => province.cities.length > 0)
    .map((province) => ({
      value: `province:${province.code}`,
      label: province.name,
      children: province.cities.map((city) => ({
        value: city.code,
        label: city.name,
      })),
    }))
}

export function createSignupApi(http: ApiClient) {
  return {
    sendCode(phone: string): Promise<void> {
      return http.post<void>('/api/v1/organization-signups/sms-codes', { phone: phone.trim() })
    },
    /** 同一次填写里的重试要用同一个幂等键（页面保存）。 */
    signUp(form: SignupForm, idempotencyKey: string = newIdempotencyKey()): Promise<SignupResult> {
      return http.post<SignupResult>('/api/v1/organization-signups', signupRequestBody(form), { idempotencyKey })
    },
    async provinces(): Promise<DirectoryProvince[]> {
      const directory = await http.get<CityDirectoryResponse>('/api/v1/platform/cities')
      return directory.provinces
    },
  }
}

export type SignupApi = ReturnType<typeof createSignupApi>
