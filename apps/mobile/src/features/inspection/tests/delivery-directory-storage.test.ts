import { describe, expect, it } from 'vitest'
import interfaceSource from '../../../uni_modules/inspection-delivery-storage/utssdk/interface.uts?raw'
import androidSource from '../../../uni_modules/inspection-delivery-storage/utssdk/app-android/index.uts?raw'
import pageSource from '../presentation/delivery/pages/InspectionDeliveryPage.vue?raw'

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

  it('guards temporary-source cleanup after every native copy attempt', () => {
    expect(androidSource).toMatch(
      /function deleteTemporarySource\(sourcePath: string\): void[\s\S]*new File\(sourcePath\)[\s\S]*source\.delete\(\)/,
    )
    expect(androidSource).toMatch(
      /UTSAndroid\.getDispatcher\('io'\)\.async[\s\S]*finally \{\s*deleteTemporarySource\(options\.sourcePath\)\s*\}/,
    )
  })

  it('guards the destroy callback after another picker result completed', () => {
    expect(androidSource).toMatch(
      /destroyListener = \(\) => \{\s*if \(completed\) return\s*finish\(\)/,
    )
  })

  it('closes each write stream even when its sibling close fails', () => {
    expect(androidSource).toContain('let input: FileInputStream | null = null')
    expect(androidSource).toContain('let output: OutputStream | null = null')
    expect(androidSource).toMatch(
      /try \{\s+output\?\.close\(\)\s+\} finally \{\s+input\?\.close\(\)\s+\}/,
    )
  })

  it('uses a JVM-compatible query projection', () => {
    expect(androidSource).toMatch(/resolver\.query\(\s*childrenUri,\s*null,/)
    expect(androidSource).not.toContain('[Document.COLUMN_DISPLAY_NAME]')
  })

  it('normalizes caught errors before crossing a typed helper boundary', () => {
    expect(androidSource).toContain('function codedFailure(message: string)')
    expect(androidSource).not.toContain('error: unknown')
  })

  it('uses block-bodied callbacks for main-dispatch side effects', () => {
    expect(androidSource).toMatch(/async\(\(_\) => \{\s*action\(\)\s*\}\)/)
    expect(androidSource).not.toMatch(/runOnMain\(\(\) => options\./)
  })

  it('injects the UTS storage bridge and coordinates selected-directory saves', () => {
    expect(pageSource).toContain("from '@/uni_modules/inspection-delivery-storage'")
    expect(pageSource).toContain('// #ifdef APP-PLUS')
    expect(pageSource).toContain('// @ts-ignore The UTS module resolves its native implementation for each App platform.')
    expect(pageSource).toContain('pick: pickInspectionDeliveryDirectory')
    expect(pageSource).toContain('save: saveInspectionDeliveryFile')
    expect(pageSource).toContain('executeDeliverySaveAction(')
    expect(pageSource).toContain("title: '已取消保存'")
    expect(pageSource).toContain("title: '全部文件压缩包已保存'")
  })
})
