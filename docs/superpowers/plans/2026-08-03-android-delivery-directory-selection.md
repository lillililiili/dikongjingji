# Android Delivery Directory Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Android 用户每次下载交付文件时通过系统目录选择器决定保存位置，取消时不产生下载请求，同名文件不覆盖。

**Architecture:** 新建职责单一的仓库自有 UTS Android 模块，封装 `ACTION_OPEN_DOCUMENT_TREE`、目录 URI 写入、同名避让和失败清理。TypeScript 数据层把 UTS 回调包装为平台无关 Promise 端口，应用层在请求短时效下载意图之前完成目录选择，交付页面只处理保存成功、取消和错误结果。

**Tech Stack:** uni-app 5.15、Vue 3、TypeScript 4.9 严格模式、UTS Android、Android Storage Access Framework、Vitest 1.6、HBuilderX CLI、ADB。

## Global Constraints

- 只实现 Android；iOS、H5 保持当前保存行为。
- 单文件每次重新选择目录；“全部下载”只选择一次并保存现有接口返回的一个 ZIP。
- 不持久化目录 URI 或授权，不申请 `MANAGE_EXTERNAL_STORAGE`，不新增 Android 存储运行时权限。
- 不修改后端、数据库、OpenAPI、对象存储打包规则、预约 PDF 选择器、预览、验收或审计状态机。
- 同名文件生成 `(1)`、`(2)`，不得覆盖、删除或修改用户已有文件。
- 用户取消目录选择时不得调用下载意图接口或下载临时文件。
- 当前工作树包含其他未提交改动；每次提交只能显式暂存本任务列出的文件，禁止使用 `git add .`。
- 现有 `delivery-file-storage.ts`、`delivery-file-storage.test.ts` 和交付页中的公共 Download 修复属于本功能的直接基线，不得丢失其非 Android 回退和错误处理。
- 验证等级为 L2，并因原生目录与文件写入强制执行 Android 真机路径。

---

## File Map

**Create**

- `apps/mobile/src/uni_modules/inspection-delivery-storage/package.json`：UTS 模块元数据。
- `apps/mobile/src/uni_modules/inspection-delivery-storage/utssdk/interface.uts`：跨平台稳定类型和函数签名。
- `apps/mobile/src/uni_modules/inspection-delivery-storage/utssdk/app-android/index.uts`：Android 系统目录选择与文件写入。
- `apps/mobile/src/uni_modules/inspection-delivery-storage/utssdk/app-ios/index.uts`：iOS 编译兼容实现，只返回“不支持”，运行时不会调用。
- `apps/mobile/src/features/inspection/tests/delivery-directory-storage.test.ts`：UTS 合约、Android 实现和页面接线的静态门禁。

**Modify**

- `apps/mobile/src/features/inspection/data/service-flow/delivery-file-storage.ts`：把固定 MediaStore 保存改为可注入的目录选择/保存桥接器，保留非 Android `uni.saveFile`。
- `apps/mobile/src/features/inspection/tests/delivery-file-storage.test.ts`：覆盖 Android 选择、取消、保存和非 Android 回退。
- `apps/mobile/src/features/inspection/application/service-flow/delivery-actions.ts`：新增“先选目录、再创建下载意图”的可测试协调器。
- `apps/mobile/src/features/inspection/tests/delivery-actions.test.ts`：覆盖取消时零请求、选择成功时调用顺序和目标透传。
- `apps/mobile/src/features/inspection/presentation/delivery/pages/InspectionDeliveryPage.vue`：接入 UTS 桥接器和新协调器，显示取消/成功文案。

---

### Task 1: Add the repository-owned Android directory storage module

**Files:**

- Create: `apps/mobile/src/uni_modules/inspection-delivery-storage/package.json`
- Create: `apps/mobile/src/uni_modules/inspection-delivery-storage/utssdk/interface.uts`
- Create: `apps/mobile/src/uni_modules/inspection-delivery-storage/utssdk/app-android/index.uts`
- Create: `apps/mobile/src/uni_modules/inspection-delivery-storage/utssdk/app-ios/index.uts`
- Create/Test: `apps/mobile/src/features/inspection/tests/delivery-directory-storage.test.ts`

**Interfaces:**

- Consumes: Android `Intent.ACTION_OPEN_DOCUMENT_TREE`, `UTSAndroid.onAppActivityResult`, `DocumentsContract`, `ContentResolver` and a readable absolute temporary file path.
- Produces: `pickInspectionDeliveryDirectory(options)` and `saveInspectionDeliveryFile(options)` with the exact types below.

- [ ] **Step 1: Write the failing contract and Android source tests**

Create `delivery-directory-storage.test.ts` with raw-source assertions that fail while the module is absent:

```ts
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
```

- [ ] **Step 2: Run the new test and verify RED**

Run:

```powershell
pnpm.cmd --dir apps/mobile exec vitest run src/features/inspection/tests/delivery-directory-storage.test.ts
```

Expected: FAIL because `inspection-delivery-storage` files do not exist.

- [ ] **Step 3: Create the UTS interface and package metadata**

Use this public contract in `interface.uts`:

```ts
export type InspectionDeliveryStorageError = {
  code: string
  errMsg: string
}

export type PickInspectionDeliveryDirectoryResult = {
  directoryUri: string
}

export type PickInspectionDeliveryDirectoryOptions = {
  success?: (result: PickInspectionDeliveryDirectoryResult) => void
  fail?: (error: InspectionDeliveryStorageError) => void
}

export type SaveInspectionDeliveryFileResult = {
  finalDisplayName: string
}

export type SaveInspectionDeliveryFileOptions = {
  directoryUri: string
  sourcePath: string
  suggestedFileName: string
  contentType: string
  success?: (result: SaveInspectionDeliveryFileResult) => void
  fail?: (error: InspectionDeliveryStorageError) => void
}

export type PickInspectionDeliveryDirectory = (
  options: PickInspectionDeliveryDirectoryOptions,
) => void

export type SaveInspectionDeliveryFile = (
  options: SaveInspectionDeliveryFileOptions,
) => void
```

Create `package.json` with ID `inspection-delivery-storage`, version `1.0.0`, type `uts`, and the same HBuilderX/uni-app engine floors as `inspection-document-picker`.

- [ ] **Step 4: Implement Android directory selection**

In `app-android/index.uts`, use a request code distinct from the PDF picker and the following completion pattern:

```ts
import Activity from 'android.app.Activity'
import ContentResolver from 'android.content.ContentResolver'
import Intent from 'android.content.Intent'
import Uri from 'android.net.Uri'
import DocumentsContract from 'android.provider.DocumentsContract'
import Document from 'android.provider.DocumentsContract.Document'
import File from 'java.io.File'
import FileInputStream from 'java.io.FileInputStream'

import {
  InspectionDeliveryStorageError,
  PickInspectionDeliveryDirectory,
  SaveInspectionDeliveryFile,
  SaveInspectionDeliveryFileOptions,
  SaveInspectionDeliveryFileResult,
} from '../interface.uts'

const DIRECTORY_REQUEST_CODE: Int = 28_722
let pickerInFlight = false

export const pickInspectionDeliveryDirectory: PickInspectionDeliveryDirectory = function (options): void {
  if (pickerInFlight) {
    options.fail?.({
      code: 'DELIVERY_DIRECTORY_PICKER_UNAVAILABLE',
      errMsg: '正在选择保存文件夹，请稍后再试',
    })
    return
  }
  const activity = UTSAndroid.getUniActivity()
  if (activity == null) {
    options.fail?.({
      code: 'DELIVERY_DIRECTORY_PICKER_UNAVAILABLE',
      errMsg: '当前页面不可用，无法打开文件夹选择器',
    })
    return
  }

  pickerInFlight = true
  let completed = false
  let resultListener: (requestCode: Int, resultCode: Int, data: Intent | null) => void =
    (_requestCode, _resultCode, _data) => {}
  let destroyListener: () => void = () => {}

  const finish = (): void => {
    if (completed) return
    completed = true
    pickerInFlight = false
    UTSAndroid.offAppActivityResult(resultListener)
    UTSAndroid.offAppActivityDestroy(destroyListener)
  }
  resultListener = (requestCode, resultCode, data) => {
    if (requestCode != DIRECTORY_REQUEST_CODE || completed) return
    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
      finish()
      options.fail?.({
        code: 'DELIVERY_DIRECTORY_PICKER_CANCELLED',
        errMsg: '已取消保存',
      })
      return
    }
    const directoryUri = data.getData()!.toString()
    finish()
    options.success?.({ directoryUri })
  }
  destroyListener = () => {
    finish()
    options.fail?.({
      code: 'DELIVERY_DIRECTORY_PICKER_UNAVAILABLE',
      errMsg: '页面已关闭，无法继续选择文件夹',
    })
  }

  UTSAndroid.onAppActivityResult(resultListener)
  UTSAndroid.onAppActivityDestroy(destroyListener)
  try {
    const intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    activity.startActivityForResult(intent, DIRECTORY_REQUEST_CODE)
  } catch (_) {
    finish()
    options.fail?.({
      code: 'DELIVERY_DIRECTORY_PICKER_UNAVAILABLE',
      errMsg: '当前设备无法打开文件夹选择器',
    })
  }
}
```

All callbacks must be dispatched to the main dispatcher, matching the repository-owned PDF picker pattern.

- [ ] **Step 5: Implement unique naming and atomic best-effort cleanup**

Use the selected tree URI to query child display names and copy on the IO dispatcher:

```ts
function uniqueDisplayName(existing: string[], suggested: string): string {
  if (existing.indexOf(suggested) < 0) return suggested
  const dot = suggested.lastIndexOf('.')
  const stem = dot > 0 ? suggested.substring(0, dot) : suggested
  const extension = dot > 0 ? suggested.substring(dot) : ''
  let suffix = 1
  while (existing.indexOf(`${stem} (${suffix})${extension}`) >= 0) suffix++
  return `${stem} (${suffix})${extension}`
}

function storageError(code: string, message: string): Error {
  return new Error(`${code}: ${message}`)
}

function queryDisplayNames(
  resolver: ContentResolver,
  treeUri: Uri,
  treeId: string,
): string[] {
  const names = [] as string[]
  const childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
  const cursor = resolver.query(
    childrenUri,
    [Document.COLUMN_DISPLAY_NAME],
    null,
    null,
    null,
  )
  if (cursor == null) {
    throw storageError('DELIVERY_DIRECTORY_NOT_WRITABLE', '无法读取所选文件夹')
  }
  try {
    const nameIndex = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
    while (cursor.moveToNext()) {
      if (nameIndex >= 0) names.push(cursor.getString(nameIndex))
    }
  } finally {
    cursor.close()
  }
  return names
}

function copyToDirectory(options: SaveInspectionDeliveryFileOptions): SaveInspectionDeliveryFileResult {
  const context = UTSAndroid.getAppContext()
  if (context == null) throw storageError('DELIVERY_DIRECTORY_PICKER_UNAVAILABLE', '当前应用上下文不可用')
  const source = new File(options.sourcePath)
  if (!source.isFile() || !source.canRead()) {
    throw storageError('DELIVERY_FILE_SOURCE_UNAVAILABLE', '临时下载文件不可读取')
  }
  const resolver = context.getContentResolver()
  const treeUri = Uri.parse(options.directoryUri)
  const treeId = DocumentsContract.getTreeDocumentId(treeUri)
  const parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
  const displayName = uniqueDisplayName(queryDisplayNames(resolver, treeUri, treeId), options.suggestedFileName)
  const documentUri = DocumentsContract.createDocument(
    resolver,
    parentUri,
    options.contentType,
    displayName,
  )
  if (documentUri == null) {
    throw storageError('DELIVERY_DIRECTORY_NOT_WRITABLE', '该文件夹不可写，请重新选择')
  }
  try {
    const input = new FileInputStream(source)
    const output = resolver.openOutputStream(documentUri, 'w')
    if (output == null) {
      input.close()
      throw storageError('DELIVERY_FILE_WRITE_FAILED', '文件写入失败，请重试')
    }
    try {
      const buffer = new ByteArray(8_192)
      let count = input.read(buffer)
      while (count > 0) {
        output.write(buffer, 0, count)
        count = input.read(buffer)
      }
      output.flush()
    } finally {
      output.close()
      input.close()
    }
    return { finalDisplayName: displayName }
  } catch (error) {
    try { DocumentsContract.deleteDocument(resolver, documentUri) } catch (_) {}
    throw error
  }
}
```

Import `ContentResolver` from `android.content.ContentResolver` and `Document` from `android.provider.DocumentsContract.Document`. `queryDisplayNames` closes the cursor in `finally` and returns an empty array only when the provider returns a valid empty result.

Export `saveInspectionDeliveryFile` with explicit validation, IO dispatch and coded failures:

```ts
function runOnMain(action: () => void): void {
  UTSAndroid.getDispatcher('main').async((_) => action())
}

function codedFailure(error: unknown): InspectionDeliveryStorageError {
  const message = error instanceof Error ? error.message : 'DELIVERY_FILE_WRITE_FAILED: 文件写入失败，请重试'
  const separator = message.indexOf(': ')
  if (separator > 0 && message.substring(0, separator).startsWith('DELIVERY_')) {
    return {
      code: message.substring(0, separator),
      errMsg: message.substring(separator + 2),
    }
  }
  return { code: 'DELIVERY_FILE_WRITE_FAILED', errMsg: '文件写入失败，请重试' }
}

export const saveInspectionDeliveryFile: SaveInspectionDeliveryFile = function (options): void {
  if (
    options.directoryUri.trim().length == 0
    || options.sourcePath.trim().length == 0
    || options.suggestedFileName.trim().length == 0
    || options.contentType.trim().length == 0
  ) {
    options.fail?.({ code: 'DELIVERY_FILE_NAME_INVALID', errMsg: '交付文件信息无效' })
    return
  }
  UTSAndroid.getDispatcher('io').async((_) => {
    try {
      const result = copyToDirectory(options)
      runOnMain(() => options.success?.(result))
    } catch (error) {
      const failure = codedFailure(error)
      runOnMain(() => options.fail?.(failure))
    }
  })
}
```

- [ ] **Step 6: Add the iOS compile-only implementation**

Export the same two functions from `app-ios/index.uts`. They must not open an iOS picker or change existing iOS saving behavior:

```ts
import {
  PickInspectionDeliveryDirectory,
  SaveInspectionDeliveryFile,
} from '../interface.uts'

export const pickInspectionDeliveryDirectory: PickInspectionDeliveryDirectory = function (options): void {
  options.fail?.({
    code: 'DELIVERY_DIRECTORY_PICKER_UNAVAILABLE',
    errMsg: '当前平台不支持选择交付文件夹',
  })
}

export const saveInspectionDeliveryFile: SaveInspectionDeliveryFile = function (options): void {
  options.fail?.({
    code: 'DELIVERY_DIRECTORY_PICKER_UNAVAILABLE',
    errMsg: '当前平台不支持写入所选文件夹',
  })
}
```

- [ ] **Step 7: Run the module source test and App-Plus compile**

Run:

```powershell
pnpm.cmd --dir apps/mobile exec vitest run src/features/inspection/tests/delivery-directory-storage.test.ts
pnpm.cmd --dir apps/mobile exec uni build -p app-plus
```

Expected: source test PASS; App-Plus build exits 0 without UTS syntax or type errors.

- [ ] **Step 8: Commit only the UTS module and its test**

```powershell
git add -- apps/mobile/src/uni_modules/inspection-delivery-storage/package.json `
  apps/mobile/src/uni_modules/inspection-delivery-storage/utssdk/interface.uts `
  apps/mobile/src/uni_modules/inspection-delivery-storage/utssdk/app-android/index.uts `
  apps/mobile/src/uni_modules/inspection-delivery-storage/utssdk/app-ios/index.uts `
  apps/mobile/src/features/inspection/tests/delivery-directory-storage.test.ts
git diff --cached --name-only
git commit -m "feat(inspection): add Android delivery directory storage"
```

Expected: cached list contains exactly the five paths above before commit.

---

### Task 2: Wrap directory selection and saving in a platform-neutral storage adapter

**Files:**

- Modify: `apps/mobile/src/features/inspection/data/service-flow/delivery-file-storage.ts`
- Modify/Test: `apps/mobile/src/features/inspection/tests/delivery-file-storage.test.ts`

**Interfaces:**

- Consumes: UTS-compatible callback functions passed through `DeliveryDirectoryStorageBridge` and `plus.io.convertLocalFileSystemURL` on Android.
- Produces: `createDeliveryFileStorage(bridge)` with `pickSaveTarget()` and `save(filePath, suggestedFileName, target)`.

- [ ] **Step 1: Replace the MediaStore-focused test with failing bridge tests**

Define these expectations before changing production code:

```ts
it('returns null when the Android directory picker is cancelled', async () => {
  const storage = createDeliveryFileStorage(bridgeWith({
    pick: (options) => options.fail?.({
      code: 'DELIVERY_DIRECTORY_PICKER_CANCELLED',
      errMsg: '已取消保存',
    }),
  }))
  await expect(storage.pickSaveTarget()).resolves.toBeNull()
})

it('surfaces a picker-unavailable failure', async () => {
  const storage = createDeliveryFileStorage(bridgeWith({
    pick: (options) => options.fail?.({
      code: 'DELIVERY_DIRECTORY_PICKER_UNAVAILABLE',
      errMsg: '当前设备无法打开文件夹选择器',
    }),
  }))
  await expect(storage.pickSaveTarget())
    .rejects.toThrow('当前设备无法打开文件夹选择器')
})

it('passes the selected URI and absolute source path to Android storage', async () => {
  const calls: unknown[] = []
  const storage = createDeliveryFileStorage(bridgeWith({ calls }))
  const target = await storage.pickSaveTarget()
  await expect(storage.save('_doc/uniapp_temp/file.zip', '现场影像.zip', target!))
    .resolves.toBe('现场影像.zip')
  expect(calls).toContainEqual(expect.objectContaining({
    directoryUri: 'content://tree/selected',
    sourcePath: '/absolute/file.zip',
    suggestedFileName: '现场影像.zip',
    contentType: 'application/zip',
  }))
})

it('keeps the current uni.saveFile fallback outside Android', async () => {
  const bridge = bridgeWith()
  const pick = vi.spyOn(bridge, 'pick')
  const saveFile = vi.fn((options: {
    success: (result: { savedFilePath: string }) => void
  }) => options.success({ savedFilePath: '_doc/uniapp_save/file.zip' }))
  vi.stubGlobal('plus', { os: { name: 'iOS' } })
  vi.stubGlobal('uni', { saveFile })

  const storage = createDeliveryFileStorage(bridge)
  const target = await storage.pickSaveTarget()
  await expect(storage.save('/tmp/file.zip', '现场影像.zip', target!))
    .resolves.toBe('_doc/uniapp_save/file.zip')
  expect(pick).not.toHaveBeenCalled()
  expect(saveFile).toHaveBeenCalled()
})

function bridgeWith(overrides: {
  pick?: DeliveryDirectoryStorageBridge['pick']
  save?: DeliveryDirectoryStorageBridge['save']
  calls?: unknown[]
} = {}): DeliveryDirectoryStorageBridge {
  return {
    pick: overrides.pick ?? ((options) => options.success?.({
      directoryUri: 'content://tree/selected',
    })),
    save: overrides.save ?? ((options) => {
      overrides.calls?.push({
        directoryUri: options.directoryUri,
        sourcePath: options.sourcePath,
        suggestedFileName: options.suggestedFileName,
        contentType: options.contentType,
      })
      options.success?.({ finalDisplayName: options.suggestedFileName })
    }),
  }
}
```

- [ ] **Step 2: Run the adapter test and verify RED**

Run:

```powershell
pnpm.cmd --dir apps/mobile exec vitest run src/features/inspection/tests/delivery-file-storage.test.ts
```

Expected: FAIL because `createDeliveryFileStorage`, `pickSaveTarget`, and target-aware `save` do not exist.

- [ ] **Step 3: Implement the bridge and discriminated save target**

Replace direct Native.js MediaStore access with these public types:

```ts
export type DeliverySaveTarget =
  | { kind: 'ANDROID_TREE'; directoryUri: string }
  | { kind: 'APPLICATION_STORAGE' }

export interface DeliveryDirectoryStorageBridge {
  pick(options: {
    success?: (result: { directoryUri: string }) => void
    fail?: (error: { code: string; errMsg: string }) => void
  }): void
  save(options: {
    directoryUri: string
    sourcePath: string
    suggestedFileName: string
    contentType: string
    success?: (result: { finalDisplayName: string }) => void
    fail?: (error: { code: string; errMsg: string }) => void
  }): void
}

export interface DeliveryFileStorage {
  pickSaveTarget(): Promise<DeliverySaveTarget | null>
  save(filePath: string, suggestedFileName: string, target: DeliverySaveTarget): Promise<string>
}
```

`createDeliveryFileStorage(bridge)` must:

- Return `{ kind: 'APPLICATION_STORAGE' }` immediately outside Android.
- Resolve `null` only for `DELIVERY_DIRECTORY_PICKER_CANCELLED`.
- Reject all other picker failures using their `errMsg`.
- Sanitize the suggested filename before either native or fallback saving.
- Convert `_doc`/relative App paths with `plus.io.convertLocalFileSystemURL` before the UTS save call.
- Map ZIP/KML/XLSX/PDF extensions to the existing MIME types and default to `application/octet-stream`.
- Return the UTS `finalDisplayName` for Android and `savedFilePath` for the fallback.

Implement those rules with this structure:

```ts
const CONTENT_TYPES: Record<string, string> = {
  zip: 'application/zip',
  kml: 'application/vnd.google-earth.kml+xml',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pdf: 'application/pdf',
}

function isAndroidApp(): boolean {
  return typeof plus !== 'undefined' && plus.os.name === 'Android'
}

function sanitizeFileName(value: string): string {
  const fileName = value.trim().replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
  if (!fileName) throw new Error('交付文件名称无效')
  return fileName
}

function saveToApplicationStorage(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.saveFile({
      tempFilePath: filePath,
      success: ({ savedFilePath }) => savedFilePath
        ? resolve(savedFilePath)
        : reject(new Error('文件保存失败，请检查设备存储权限')),
      fail: () => reject(new Error('文件保存失败，请检查设备存储权限')),
    })
  })
}

export function createDeliveryFileStorage(
  bridge: DeliveryDirectoryStorageBridge,
): DeliveryFileStorage {
  return {
    pickSaveTarget() {
      if (!isAndroidApp()) {
        return Promise.resolve({ kind: 'APPLICATION_STORAGE' })
      }
      return new Promise((resolve, reject) => {
        bridge.pick({
          success: ({ directoryUri }) => resolve({
            kind: 'ANDROID_TREE',
            directoryUri,
          }),
          fail: ({ code, errMsg }) => code === 'DELIVERY_DIRECTORY_PICKER_CANCELLED'
            ? resolve(null)
            : reject(new Error(errMsg)),
        })
      })
    },
    save(filePath, suggestedFileName, target) {
      const fileName = sanitizeFileName(suggestedFileName)
      if (target.kind === 'APPLICATION_STORAGE') {
        return saveToApplicationStorage(filePath)
      }
      const extension = fileName.split('.').pop()?.toLowerCase() ?? ''
      const sourcePath = filePath.startsWith('file://')
        ? filePath.slice('file://'.length)
        : filePath.startsWith('/')
          ? filePath
          : plus.io.convertLocalFileSystemURL(filePath)
      return new Promise((resolve, reject) => {
        bridge.save({
          directoryUri: target.directoryUri,
          sourcePath,
          suggestedFileName: fileName,
          contentType: CONTENT_TYPES[extension] ?? 'application/octet-stream',
          success: ({ finalDisplayName }) => resolve(finalDisplayName),
          fail: ({ errMsg }) => reject(new Error(errMsg)),
        })
      })
    },
  }
}
```

- [ ] **Step 4: Run focused tests and type-check**

```powershell
pnpm.cmd --dir apps/mobile exec vitest run src/features/inspection/tests/delivery-file-storage.test.ts
pnpm.cmd --dir apps/mobile run type-check
```

Expected: all adapter tests PASS; type-check exits 0.

- [ ] **Step 5: Commit only the adapter and test**

```powershell
git add -- apps/mobile/src/features/inspection/data/service-flow/delivery-file-storage.ts `
  apps/mobile/src/features/inspection/tests/delivery-file-storage.test.ts
git diff --cached --name-only
git commit -m "refactor(inspection): target delivery saves to selected directories"
```

Expected: cached list contains exactly the two adapter paths.

---

### Task 3: Select the directory before requesting a download intent

**Files:**

- Modify: `apps/mobile/src/features/inspection/application/service-flow/delivery-actions.ts`
- Modify/Test: `apps/mobile/src/features/inspection/tests/delivery-actions.test.ts`
- Modify: `apps/mobile/src/features/inspection/presentation/delivery/pages/InspectionDeliveryPage.vue`
- Modify/Test: `apps/mobile/src/features/inspection/tests/delivery-directory-storage.test.ts`

**Interfaces:**

- Consumes: `DeliveryFileStorage.pickSaveTarget`, `DeliveryFileStorage.save`, existing `InspectionDownloadIntent` factories and `executeDeliveryFileAction`.
- Produces: `executeDeliverySaveAction(createIntent, fileType, runtime)` returning `DeliverySaveOutcome`.

- [ ] **Step 1: Write failing orchestration tests**

Add exact order and cancellation assertions:

```ts
it('does not create a download intent when directory selection is cancelled', async () => {
  const createIntent = vi.fn(async () => intent('images.zip'))
  const runtime = runtimeWith({ pickSaveTarget: async () => null })
  await expect(executeDeliverySaveAction(createIntent, 'IMAGE_ARCHIVE', runtime))
    .resolves.toEqual({ status: 'CANCELLED' })
  expect(createIntent).not.toHaveBeenCalled()
  expect(runtime.savedPaths).toEqual([])
})

it('selects, requests, downloads and saves in that order', async () => {
  const order: string[] = []
  const target = { kind: 'ANDROID_TREE', directoryUri: 'content://tree/selected' } as const
  const runtime = runtimeWith({
    pickSaveTarget: async () => { order.push('pick'); return target },
    download: async () => { order.push('download'); return '/tmp/file' },
    save: async (_path, _name, receivedTarget) => {
      order.push('save')
      expect(receivedTarget).toEqual(target)
      return 'images.zip'
    },
  })
  const createIntent = async () => { order.push('intent'); return intent('images.zip') }
  await expect(executeDeliverySaveAction(createIntent, 'IMAGE_ARCHIVE', runtime))
    .resolves.toEqual({ status: 'SAVED', savedName: 'images.zip' })
  expect(order).toEqual(['pick', 'intent', 'download', 'save'])
})

it('does not create an output document when the network download fails', async () => {
  const save = vi.fn(async () => 'unexpected.zip')
  const runtime = runtimeWith({
    download: async () => { throw new Error('network failed') },
    save,
  })
  await expect(executeDeliverySaveAction(
    async () => intent('images.zip'),
    'IMAGE_ARCHIVE',
    runtime,
  )).rejects.toThrow('network failed')
  expect(save).not.toHaveBeenCalled()
})

function runtimeWith(
  overrides: Partial<DeliveryFileRuntime>,
): DeliveryFileRuntime & { savedPaths: string[] } {
  const savedPaths: string[] = []
  return {
    savedPaths,
    pickSaveTarget: async () => ({ kind: 'APPLICATION_STORAGE' }),
    download: async () => '/tmp/delivery-file',
    preview: async () => undefined,
    save: async (path) => {
      savedPaths.push(path)
      return 'delivery-file.zip'
    },
    ...overrides,
  }
}
```

- [ ] **Step 2: Run orchestration tests and verify RED**

```powershell
pnpm.cmd --dir apps/mobile exec vitest run src/features/inspection/tests/delivery-actions.test.ts
```

Expected: FAIL because the coordinator and directory-aware runtime contract do not exist.

- [ ] **Step 3: Add the save coordinator**

Use these exact interfaces in `delivery-actions.ts`:

```ts
import type {
  DeliverySaveTarget,
} from '../../data/service-flow/delivery-file-storage'

export interface DeliveryFileRuntime {
  pickSaveTarget(): Promise<DeliverySaveTarget | null>
  download(url: string): Promise<string>
  preview(filePath: string): Promise<void>
  save(
    filePath: string,
    suggestedFileName: string,
    target: DeliverySaveTarget,
  ): Promise<string>
}

export type DeliverySaveOutcome =
  | { status: 'CANCELLED' }
  | { status: 'SAVED'; savedName: string }

export async function executeDeliverySaveAction(
  createIntent: () => Promise<InspectionDownloadIntent>,
  fileType: InspectionDeliveryFileType,
  runtime: DeliveryFileRuntime,
): Promise<DeliverySaveOutcome> {
  const target = await runtime.pickSaveTarget()
  if (!target) return { status: 'CANCELLED' }
  const intent = await createIntent()
  const savedName = await executeDeliveryFileAction(
    intent,
    'SAVE',
    fileType,
    runtime,
    target,
  )
  if (!savedName) throw new Error('文件保存结果无效')
  return { status: 'SAVED', savedName }
}
```

Extend `executeDeliveryFileAction` with an optional `saveTarget` argument and replace the final SAVE branch with:

```ts
export async function executeDeliveryFileAction(
  intent: InspectionDownloadIntent,
  action: DeliveryFileAction,
  fileType: InspectionDeliveryFileType,
  runtime: DeliveryFileRuntime,
  saveTarget?: DeliverySaveTarget,
): Promise<string | null> {
  if (intent.method !== 'GET' || !intent.downloadUrl.trim()) {
    throw new Error('下载地址无效，请重新获取')
  }
  if (
    action === 'PREVIEW'
    && (fileType === 'IMAGE_ARCHIVE' || fileType === 'POINT_KML')
  ) {
    throw new Error('该文件类型不支持在线预览，请下载后查看')
  }

  const temporaryPath = await runtime.download(intent.downloadUrl)
  if (action === 'PREVIEW') {
    await runtime.preview(temporaryPath)
    return null
  }
  if (!saveTarget) throw new Error('请选择文件保存位置')
  return runtime.save(temporaryPath, intent.suggestedFileName, saveTarget)
}
```

- [ ] **Step 4: Wire the page to the UTS bridge and coordinator**

Import the UTS functions and instantiate the adapter once:

```ts
import {
  pickInspectionDeliveryDirectory,
  saveInspectionDeliveryFile,
} from '@/uni_modules/inspection-delivery-storage'
import {
  createDeliveryFileStorage,
} from '../../../data/service-flow/delivery-file-storage'

const deliveryFileStorage = createDeliveryFileStorage({
  pick: pickInspectionDeliveryDirectory,
  save: saveInspectionDeliveryFile,
})
```

`deliveryFileRuntime.pickSaveTarget` and `.save` must delegate to this storage object. For single SAVE and “全部下载”, call `executeDeliverySaveAction` with a lazy intent factory so the API request starts only after directory selection:

```ts
const outcome = await executeDeliverySaveAction(
  () => inspectionPlanApi.createDownloadIntent(
    serviceOrderId.value,
    file.fileId,
    createBookingUuid(),
  ),
  file.type,
  deliveryFileRuntime,
)
if (outcome.status === 'CANCELLED') {
  uni.showToast({ title: '已取消保存', icon: 'none' })
  return
}
showSavedName(outcome.savedName)
```

Use the same structure for `createDownloadAllIntent`, with `IMAGE_ARCHIVE`, and success copy “全部文件压缩包已保存”. Keep PREVIEW on the existing eager download-intent path because it does not select a directory.

- [ ] **Step 5: Extend the static integration guard**

In `delivery-directory-storage.test.ts`, load the delivery page as raw source and assert:

```ts
expect(pageSource).toContain("from '@/uni_modules/inspection-delivery-storage'")
expect(pageSource).toContain('executeDeliverySaveAction(')
expect(pageSource).toContain("title: '已取消保存'")
expect(pageSource).toContain("title: '全部文件压缩包已保存'")
```

- [ ] **Step 6: Run focused tests and type-check**

```powershell
pnpm.cmd --dir apps/mobile exec vitest run `
  src/features/inspection/tests/delivery-actions.test.ts `
  src/features/inspection/tests/delivery-file-storage.test.ts `
  src/features/inspection/tests/delivery-directory-storage.test.ts `
  src/features/inspection/tests/delivery-flow.test.ts
pnpm.cmd --dir apps/mobile run type-check
```

Expected: all focused tests PASS; type-check exits 0.

- [ ] **Step 7: Review the complete task diff before committing**

```powershell
git diff -- apps/mobile/src/features/inspection/application/service-flow/delivery-actions.ts `
  apps/mobile/src/features/inspection/tests/delivery-actions.test.ts `
  apps/mobile/src/features/inspection/presentation/delivery/pages/InspectionDeliveryPage.vue `
  apps/mobile/src/features/inspection/tests/delivery-directory-storage.test.ts
git diff --check
```

Expected: no backend/API/manifest/booking changes, no unrelated formatting, no whitespace errors.

- [ ] **Step 8: Commit only orchestration and page integration**

```powershell
git add -- apps/mobile/src/features/inspection/application/service-flow/delivery-actions.ts `
  apps/mobile/src/features/inspection/tests/delivery-actions.test.ts `
  apps/mobile/src/features/inspection/presentation/delivery/pages/InspectionDeliveryPage.vue `
  apps/mobile/src/features/inspection/tests/delivery-directory-storage.test.ts
git diff --cached --name-only
git commit -m "feat(inspection): choose Android delivery save directory"
```

Expected: cached list contains exactly the four paths above.

---

### Task 4: Complete regression, App build, and Android true-device verification

**Files:**

- Verify only; no new production file is expected.

**Interfaces:**

- Consumes: completed UTS module, storage adapter, coordinator and page integration.
- Produces: fresh automated, build, diff and Android filesystem evidence suitable for handoff.

- [ ] **Step 1: Run the complete mobile regression**

```powershell
pnpm.cmd --dir apps/mobile run test
pnpm.cmd --dir apps/mobile run type-check
pnpm.cmd --dir apps/mobile run build:h5
pnpm.cmd --dir apps/mobile exec uni build -p app-plus
```

Expected:

- 44 or more Vitest files pass with no failures.
- Type-check exits 0.
- H5 and App-Plus builds exit 0.
- Sass legacy API warnings may remain; no new compiler error is allowed.

- [ ] **Step 2: Compile the UTS module and synchronize to the connected Android device**

```powershell
adb devices
& 'D:\DevTools\HBuilderX\cli.exe' launch app-android `
  --project 'C:\Users\Admin（无密码）\Documents\低空经济\.worktrees\inspection-consolidation-20260801\apps\mobile' `
  --deviceId '<serial from adb devices>' `
  --playground standard `
  --native-log false
```

Expected: HBuilderX reports successful compile/sync; if the CLI remains attached after the app launches, stop only the CLI process after verifying the on-device bundle timestamp. Do not stop the device app or backend.

- [ ] **Step 3: Verify single-file directory selection**

On the delivery page:

1. Tap `site-images.zip` → “下载”.
2. In the system picker, create or choose `Download/巡检交付-选择测试` (choose a child directory, not the restricted Download root).
3. Confirm the directory.
4. Wait for the success toast.

Verify with ADB:

```powershell
adb -s <serial> shell ls -la '/storage/emulated/0/Download/巡检交付-选择测试'
```

Expected: `site-images.zip` exists, has the server-provided nonzero size, and is accessible from the system file manager.

- [ ] **Step 4: Verify duplicate naming without deletion**

Repeat Step 3 with the same selected directory.

Expected:

```text
site-images.zip
site-images (1).zip
```

Both files remain nonzero. Do not delete either file during verification.

- [ ] **Step 5: Verify download-all and cancellation**

1. Tap “全部下载”, select the same test directory once, and confirm one `FW...-交付文件.zip` appears.
2. Tap a single file download again, press Android Back in the picker, and confirm the toast says “已取消保存”.
3. Confirm no additional file appears after cancellation.

Expected: one picker for download-all, one ZIP output, and zero filesystem/network side effects after cancellation. The unit test from Task 3 is the authoritative proof that the intent factory is not called on cancel.

- [ ] **Step 6: Audit the final worktree and task whitelist**

```powershell
git status --short
git diff --check
git log -4 --oneline
```

Expected:

- No unexpected staged file.
- Any remaining dirty files are the pre-existing unrelated worktree changes, explicitly listed in the handoff.
- The latest task commits are the UTS module, storage adapter, and page integration commits, preceded by the approved design/plan documentation commits.
- No push, merge, archive, or release action has occurred.

- [ ] **Step 7: Report completion in the project Bug/verification format**

The handoff must state:

- Why the prior fixed-directory behavior did not meet the new requirement.
- Which exact files/functions changed.
- That Android now selects every time, download-all selects once, and cancel creates no request.
- Full test/build counts and true-device file names/sizes.
- Unchanged areas: backend, API, DB, iOS, H5 behavior, preview and acceptance.
- Remaining unrelated dirty worktree files and that nothing was pushed or archived.
