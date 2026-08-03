import { describe, expect, it } from 'vitest'
import interfaceSource from '../../../uni_modules/inspection-delivery-storage/utssdk/interface.uts?raw'
import androidSource from '../../../uni_modules/inspection-delivery-storage/utssdk/app-android/index.uts?raw'

describe('Android delivery directory storage module', () => {
  it('exposes directory-pick and file-save contracts', () => {
    expect(interfaceSource).toContain('export type PickInspectionDeliveryDirectory')
    expect(interfaceSource).toContain('export type SaveInspectionDeliveryFile')
    expect(interfaceSource).toContain('directoryUri: string')
    expect(interfaceSource).toContain('finalDisplayName: string')
  })

  it('uses the system directory picker without persisting URI permission', () => {
    expect(androidSource).toContain('Intent.ACTION_OPEN_DOCUMENT_TREE')
    expect(androidSource).toContain('UTSAndroid.onAppActivityResult')
    expect(androidSource).not.toContain('takePersistableUriPermission')
  })

  it('creates a unique document and removes partial output on failure', () => {
    expect(androidSource).toContain('DocumentsContract.createDocument')
    expect(androidSource).toContain('DocumentsContract.deleteDocument')
    expect(androidSource).toContain('uniqueDisplayName')
    expect(androidSource).toContain("'DELIVERY_FILE_WRITE_FAILED'")
  })
})
