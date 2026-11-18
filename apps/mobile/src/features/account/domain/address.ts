// Mirrors contracts/openapi-account.yaml. The phone number comes back whole to its owner;
// pages show it masked.

export interface AddressRegion {
  provinceCode: string
  provinceName: string
  cityCode: string
  cityName: string
}

export interface Address {
  addressId: string
  recipientName: string
  phone: string
  region: AddressRegion
  detail: string
  isDefault: boolean
}

export interface AddressForm {
  recipientName: string
  phone: string
  cityCode: string
  detail: string
  isDefault: boolean
}

export const ADDRESS_NAME_MAX_LENGTH = 20
export const ADDRESS_DETAIL_MAX_LENGTH = 80
export const ADDRESS_LIMIT = 20
