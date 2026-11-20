import {
  ADDRESS_DETAIL_MAX_LENGTH,
  ADDRESS_NAME_MAX_LENGTH,
  type Address,
  type AddressForm,
} from '../domain/address'

// The same rules as the server, so the save button only lights up for a form it will accept.
const PHONE = /^1[3-9]\d{9}$/

export const EMPTY_ADDRESS_FORM: AddressForm = {
  recipientName: '',
  phone: '',
  cityCode: '',
  detail: '',
  isDefault: false,
}

export function formFromAddress(address: Address): AddressForm {
  return {
    recipientName: address.recipientName,
    phone: address.phone,
    cityCode: address.region.cityCode,
    detail: address.detail,
    isDefault: address.isDefault,
  }
}

export type AddressFormProblem = 'NAME' | 'PHONE' | 'CITY' | 'DETAIL'

export function findAddressFormProblems(form: AddressForm): AddressFormProblem[] {
  const problems: AddressFormProblem[] = []
  const name = form.recipientName.trim()
  if (!name || name.length > ADDRESS_NAME_MAX_LENGTH) problems.push('NAME')
  if (!PHONE.test(form.phone.trim())) problems.push('PHONE')
  if (!form.cityCode) problems.push('CITY')
  const detail = form.detail.trim()
  if (!detail || detail.length > ADDRESS_DETAIL_MAX_LENGTH) problems.push('DETAIL')
  return problems
}

export function trimAddressForm(form: AddressForm): AddressForm {
  return {
    recipientName: form.recipientName.trim(),
    phone: form.phone.trim(),
    cityCode: form.cityCode,
    detail: form.detail.trim(),
    isDefault: form.isDefault,
  }
}

// "138****2026": shown everywhere except the edit form.
export function maskPhone(phone: string): string {
  return /^\d{11}$/.test(phone) ? `${phone.slice(0, 3)}****${phone.slice(7)}` : phone
}

// "山东省东营市东营区 示例路 88 号" (board AddressList): the region runs straight into the detail,
// which starts with the district; a municipality's province and city share one name.
export function formatAddressLine(address: Address): string {
  const { provinceName, cityName } = address.region
  const region = provinceName === cityName ? cityName : `${provinceName}${cityName}`
  return `${region}${address.detail}`
}
