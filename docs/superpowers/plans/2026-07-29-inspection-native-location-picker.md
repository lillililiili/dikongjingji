# Inspection Native Location Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the INS-M-004 free-text inspection location with a real native map selection that submits address and coordinates.

**Architecture:** Keep map interaction in the booking presentation page through `uni.chooseLocation`, while all normalization, coordinate validation, and request mapping stay in the existing booking domain module. Reuse the existing backend/OpenAPI coordinate contract; do not add a page, dependency, backend endpoint, or database migration.

**Tech Stack:** uni-app, Vue 3, TypeScript, Vitest, existing Spring Boot/PostgreSQL/PostGIS backend.

## Global Constraints

- Work only in `.worktrees/inspection-service-flow` on `codex/inspection-service-flow`.
- Preserve all existing dirty changes and do not touch `infra/scripts/verify-f07-delivery-smoke.ps1`.
- No map key or secret may be committed.
- Do not modify the backend, database, M01, M02, M03, payment, refund, or iOS.
- Run only booking-focused tests and mobile type checking; do not repeat completed full regression.

---

### Task 1: Booking location domain contract

**Files:**
- Modify: `apps/mobile/src/features/inspection/tests/booking-flow.test.ts`
- Modify: `apps/mobile/src/features/inspection/domain/service-flow/booking.ts`

**Interfaces:**
- Produces: `BookingLocationSelection`, `createBookingLocationSelection()`, and a `CreateReservationPayload.location` containing address, longitude, and latitude.

- [ ] **Step 1: Write failing domain tests**

Add tests that require `createBookingLocationSelection()` to combine the selected detailed address and place name, preserve valid coordinates, reject invalid coordinates, and make `createReservationPayload()` include the selected coordinates.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
npm test -- src/features/inspection/tests/booking-flow.test.ts
```

Expected: FAIL because `createBookingLocationSelection` and coordinate fields do not exist.

- [ ] **Step 3: Implement the minimum domain change**

Add:

```ts
export interface BookingLocationSelection {
  address: string
  longitude: number
  latitude: number
}

export function createBookingLocationSelection(input: {
  name?: string
  address?: string
  longitude: number
  latitude: number
}): BookingLocationSelection
```

Extend `BookingDraft` with nullable coordinate fields and copy them into `CreateReservationPayload.location`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same Vitest command and expect all booking tests to pass.

### Task 2: Native picker page interaction

**Files:**
- Modify: `apps/mobile/src/features/inspection/tests/booking-flow.test.ts`
- Modify: `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue`

**Interfaces:**
- Consumes: `createBookingLocationSelection()`.
- Produces: `chooseInspectionLocation()` as the only tap handler for the location row.

- [ ] **Step 1: Write failing page contract tests**

Require the booking page source to call `uni.chooseLocation`, bind the location row to `chooseInspectionLocation`, render the selected address as text, and no longer bind a free-text input to `draft.locationAddress`.

- [ ] **Step 2: Run the focused test and verify RED**

Expected: FAIL because the page still uses a text input and has no picker handler.

- [ ] **Step 3: Implement the native location selection**

Add an in-flight guard. On success, normalize the returned place and store its address/coordinates. On user cancellation, preserve the previous value silently. On other failures, preserve the previous value and show a clear retry message.

- [ ] **Step 4: Run the focused test and verify GREEN**

Expected: all booking-flow tests pass.

### Task 3: App capability declaration and business-flow documentation

**Files:**
- Modify: `apps/mobile/src/manifest.json`
- Modify: `docs/flow-map.md`

**Interfaces:**
- Produces: App Maps/Geolocation module declarations, Android coarse/fine location permissions, and the documented location-selection data flow.

- [ ] **Step 1: Add App location capabilities without secrets**

Declare `Maps` and `Geolocation` under `app-plus.modules`, and add Android coarse/fine location permissions. Do not add provider keys.

- [ ] **Step 2: Update the business flow**

Document that selecting a location changes only the client draft; submitting the reservation persists the address and PostGIS point. Cancellation or failure changes neither draft nor server state.

- [ ] **Step 3: Validate declarative files**

Parse `manifest.json` as JSON and inspect the exact `docs/flow-map.md` diff.

### Task 4: Targeted verification and change review

**Files:**
- Verify only; no planned production changes.

- [ ] **Step 1: Run booking tests**

```powershell
npm test -- src/features/inspection/tests/booking-flow.test.ts
```

- [ ] **Step 2: Run mobile type checking**

```powershell
npm run type-check
```

- [ ] **Step 3: Inspect scope**

Run `git diff --stat`, `git diff --name-only`, and focused diffs for the approved files. Confirm no map key, unrelated formatting, backend change, or modification to the pre-existing smoke script.

- [ ] **Step 4: Record Android limitation honestly**

If the connected device uses a standard HBuilder base, record it as development-base validation only. Production stability remains blocked until a valid provider key is configured in a custom/production build.
