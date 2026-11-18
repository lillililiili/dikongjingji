import { defineStore } from 'pinia'

// The address picked on the address page for the order being confirmed. The confirm page reads
// it when it is shown again; only the stable id travels, the page reads the address itself.
export const useAddressChoiceStore = defineStore('address-choice', {
  state: () => ({
    addressId: null as string | null,
  }),
  actions: {
    choose(addressId: string | null): void {
      this.addressId = addressId
    },
  },
})
