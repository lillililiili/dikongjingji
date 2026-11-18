import type { Address, AddressForm } from '../domain/address'

type AddressRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

export interface AddressApi {
  list(accessToken: string): Promise<Address[]>
  create(accessToken: string, form: AddressForm): Promise<Address>
  update(accessToken: string, addressId: string, form: AddressForm): Promise<Address>
  remove(accessToken: string, addressId: string): Promise<void>
}

// Every call carries the token it was given and uses the plain request helper, so a rejected
// token reaches the page as a 401 and the page decides where the user goes.
export function createAddressApi(request: AddressRequest): AddressApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const addressUrl = (addressId: string) => `/api/v1/account/addresses/${encodeURIComponent(addressId)}`
  return {
    async list(accessToken) {
      const dto = await request<{ items: Address[] }>({
        url: '/api/v1/account/addresses',
        method: 'GET',
        headers: auth(accessToken),
      })
      return dto.items.map(toAddress)
    },
    async create(accessToken, form) {
      return toAddress(await request<Address>({
        url: '/api/v1/account/addresses',
        method: 'POST',
        data: form,
        headers: auth(accessToken),
      }))
    },
    async update(accessToken, addressId, form) {
      return toAddress(await request<Address>({
        url: addressUrl(addressId),
        method: 'PUT',
        data: form,
        headers: auth(accessToken),
      }))
    },
    async remove(accessToken, addressId) {
      await request<void>({ url: addressUrl(addressId), method: 'DELETE', headers: auth(accessToken) })
    },
  }
}

export function toAddress(dto: Address): Address {
  return {
    addressId: dto.addressId,
    recipientName: dto.recipientName,
    phone: dto.phone,
    region: {
      provinceCode: dto.region.provinceCode,
      provinceName: dto.region.provinceName,
      cityCode: dto.region.cityCode,
      cityName: dto.region.cityName,
    },
    detail: dto.detail,
    isDefault: Boolean(dto.isDefault),
  }
}
