# Inspection Attachment Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Every implementation task must be followed by an independent review before the next task starts.

**Goal:** Make selected photos upload with their real bytes during reservation submission and make “选择 PDF 文件” open a working platform document picker whose result appears in the existing attachment list.

**Architecture:** Keep the existing reservation-first, attachment-upload, completion, and scan-state workflow. Introduce one file-byte access port shared by hashing and raw PUT, and one typed document-picker port used by the booking page. Use current uni-app APIs where supported, a `plus.io` byte-read fallback for App runtimes without `getFileSystemManager`, and a repository-owned UTS adapter for the iOS system document picker.

**Tech Stack:** uni-app 3.0, Vue 3.4, TypeScript 4.9 strict mode, Vitest 1.6, HTML5+ `plus.io`, Android system document picker through supported uni-app/App APIs, iOS UIKit/Foundation through a local UTS plugin.

## Global Constraints

- Work only in `C:\Users\Admin（无密码）\Documents\低空经济\.worktrees\inspection-service-flow` on branch `codex/inspection-service-flow`.
- Preserve the existing untracked `apps/mobile/.hbuilderx/` and `artifacts/` directories.
- This is one attachment bug change set. Do not modify the backend security rules, database schema, OpenAPI contract, reservation state machine, route table, time selector, payment/refund, or unrelated pages.
- Keep the existing maximum of nine attachments and the backend MIME, extension, size, ownership, SHA-256, and scan validations.
- Never fake a SHA-256, upload completion, ETag, `READY` state, or successful file selection.
- Selected files must remain `PENDING_UPLOAD` until the existing real upload chain completes.
- User cancellation must leave the attachment draft unchanged. Unsupported platforms and real picker/read errors must be visible to the user.
- PDF selection must accept PDF only and reject mismatched extension/MIME before it enters the draft.
- No third-party runtime package or marketplace plugin is allowed. The iOS UTS code is repository-owned and may call only UIKit/Foundation.
- Follow TDD: record a failing targeted test before production edits, then make the smallest production change that passes.
- Do not format whole files or directories. Review `git diff --stat` and the full diff after every task.
- Real Android/iOS device verification is required for product acceptance, but absence of a connected device does not permit simulated success claims.

## File Map

Expected create/modify set; an implementer may reduce this set but must request approval before expanding it beyond the attachment boundary.

**Create**

- `apps/mobile/src/features/inspection/data/service-flow/booking-file-access.ts`
- `apps/mobile/src/features/inspection/application/service-flow/booking-document-picker.ts`
- `apps/mobile/src/features/inspection/data/service-flow/uni-booking-document-picker.ts`
- `apps/mobile/uni_modules/inspection-document-picker/package.json`
- `apps/mobile/uni_modules/inspection-document-picker/utssdk/interface.uts`
- `apps/mobile/uni_modules/inspection-document-picker/utssdk/app-ios/index.uts`
- Targeted tests under `apps/mobile/src/features/inspection/tests/` when separation improves readability.

**Modify**

- `apps/mobile/src/features/inspection/application/service-flow/booking-crypto.ts`
- `apps/mobile/src/features/inspection/data/service-flow/uni-booking-http-client.ts`
- `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue`
- `apps/mobile/src/features/inspection/tests/booking-flow.test.ts`

---

## Task 1: Share Real File Bytes Between Hashing and Raw PUT

**Files:**

- Create: `apps/mobile/src/features/inspection/data/service-flow/booking-file-access.ts`
- Modify: `apps/mobile/src/features/inspection/application/service-flow/booking-crypto.ts`
- Modify: `apps/mobile/src/features/inspection/data/service-flow/uni-booking-http-client.ts`
- Test: `apps/mobile/src/features/inspection/tests/booking-flow.test.ts` or a focused new test file

**Interfaces:**

- Produce a production-owned `BookingFileAccessPort` with `readBytes(filePath): Promise<Uint8Array>`.
- `uni.getFileSystemManager().readFile` is the preferred path when present.
- In App-Plus runtimes where that API is absent, resolve the file using `plus.io.resolveLocalFileSystemURL`, read it as a data URL with `plus.io.FileReader`, strictly decode the base64 payload, and return the exact bytes.
- `uniBookingFileHashPort.sha256` must hash those bytes.
- Raw PUT must send the same exact bytes as an `ArrayBuffer`/compatible binary body and preserve the existing request headers and error mapping.

- [ ] Add a failing test proving that the App `plus.io` fallback reads known bytes when `getFileSystemManager` is missing and produces the known SHA-256.
- [ ] Add a failing test proving that raw PUT receives the same bytes, not a path, data URL string, fake payload, or empty body.
- [ ] Add failure tests for unreadable paths and malformed data URLs.
- [ ] Run the focused test and retain the RED output in the implementation report.
- [ ] Implement one shared file-access adapter; do not duplicate fallback logic between hash and HTTP client.
- [ ] Run the focused test, full mobile tests, and type-check.
- [ ] Review the complete diff for unrelated edits.
- [ ] Leave Task 1 uncommitted for independent review; do not stage, commit, or push.

**Verification commands:**

```powershell
pnpm.cmd test -- booking-flow.test.ts
pnpm.cmd test
pnpm.cmd run type-check
```

---

## Task 2: Replace the WeChat-only PDF Call With a Typed Picker

**Files:**

- Create: `apps/mobile/src/features/inspection/application/service-flow/booking-document-picker.ts`
- Create: `apps/mobile/src/features/inspection/data/service-flow/uni-booking-document-picker.ts`
- Modify: `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue`
- Modify/Test: `apps/mobile/src/features/inspection/tests/booking-flow.test.ts` or a focused new test file

**Interfaces:**

- Define a platform-neutral result containing `filePath`, `originalName`, `contentType: 'application/pdf'`, and `sizeBytes`.
- Expose a typed cancellation outcome distinct from failure.
- Use `uni.chooseFile` on H5 and supported App-Android runtimes, limited to remaining attachment capacity and PDF extensions.
- If App-Android support is unavailable at runtime, either use a minimal system `ACTION_OPEN_DOCUMENT` fallback that copies the selected URI into app-private storage, or return an explicit unsupported error. Do not silently do nothing.
- Validate the returned name/extension and MIME before appending a `PENDING_UPLOAD` attachment.
- The page must not call `uni.chooseMessageFile` directly.

- [ ] Replace the source-string test that requires `chooseMessageFile` with behavior-focused tests.
- [ ] Add failing tests for successful PDF selection, cancellation with no mutation, non-PDF rejection, capacity forwarding, and visible picker failure.
- [ ] Run the focused test and retain the RED output.
- [ ] Implement the picker port and page integration with the smallest presentation change.
- [ ] Verify the selected PDF appears in the existing list and keeps the existing upload workflow.
- [ ] Run focused tests, full mobile tests, type-check, and H5 build.
- [ ] Review the complete diff for unrelated edits.
- [ ] Leave Task 2 uncommitted for independent review; do not stage, commit, or push.

**Verification commands:**

```powershell
pnpm.cmd test -- booking-flow.test.ts
pnpm.cmd test
pnpm.cmd run type-check
pnpm.cmd run build:h5
```

---

## Task 3: Add the iOS System Document Picker Adapter

**Files:**

- Create: `apps/mobile/uni_modules/inspection-document-picker/package.json`
- Create: `apps/mobile/uni_modules/inspection-document-picker/utssdk/interface.uts`
- Create: `apps/mobile/uni_modules/inspection-document-picker/utssdk/app-ios/index.uts`
- Modify: `apps/mobile/src/features/inspection/data/service-flow/uni-booking-document-picker.ts`
- Test: focused document-picker tests and source-contract checks only where runtime execution is unavailable

**Interfaces:**

- Implement `UIDocumentPickerViewController` with PDF content type and multiple-selection limited by the requested remaining count.
- Use `UTSiOS.getCurrentViewController()` for presentation and `UIDocumentPickerDelegate` for success/cancel callbacks.
- Import/copy selected files into an app-accessible sandbox path before returning; do not retain a transient security-scoped external URL as the upload path.
- Return only validated PDF metadata and surface copy/picker failures.
- The plugin must use only UIKit/Foundation and contain no binary dependency.

- [ ] Add a failing contract test showing the App-iOS branch delegates to the UTS picker and maps success/cancel/failure distinctly.
- [ ] Run the focused test and retain the RED output.
- [ ] Implement the local UTS module following the installed HBuilderX UTS module structure and types.
- [ ] Wire only the App-iOS branch to the module; leave the Task 2 Android/H5 behavior intact.
- [ ] Run focused tests, full mobile tests, type-check, H5 build, and the available HBuilderX App compilation/static validation.
- [ ] Record that iOS product acceptance remains pending unless a real iPhone build and selection/upload flow are actually executed.
- [ ] Review the complete diff for unrelated edits.
- [ ] Leave Task 3 uncommitted for independent review; do not stage, commit, or push.

**Verification commands:**

```powershell
pnpm.cmd test -- booking-flow.test.ts
pnpm.cmd test
pnpm.cmd run type-check
pnpm.cmd run build:h5
```

---

## Final Cross-Task Review and Verification

- [ ] Run an independent review of the complete attachment range from `b6201c6` to final `HEAD`.
- [ ] Run `git diff --stat b6201c6..HEAD` and `git diff --check b6201c6..HEAD`.
- [ ] Run the full mobile test suite, type-check, and H5 build from a clean working tree.
- [ ] Re-run the existing backend upload-state tests only if Task 1 changes any shared request assumption; otherwise document that backend code was unchanged.
- [ ] If an Android device is connected, verify photo selection → submit → real PUT → completion → submitted page, and PDF picker → selected PDF shown → submit.
- [ ] If an iPhone is available through an iOS build environment, verify the same PDF path. Otherwise mark iOS device acceptance pending, not passed.
- [ ] Confirm no backend, database, OpenAPI, payment/refund, time-selection, route, or unrelated UI file changed.
