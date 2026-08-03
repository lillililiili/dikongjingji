# Power Catalog Sales Sort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The user explicitly prohibited subagents, so execution is inline in the current session.

**Goal:** Remove the Mine-page top header, replace distance sorting with real completed-service sales sorting, and expose five visibly sortable power-inspection services through the real catalog API.

**Architecture:** Keep `POWER` as the public parent entry while the catalog API treats it as the five-member power group (`POWER`, `SUBSTATION`, `DISTRIBUTION`, `CABLE`, `PHOTOVOLTAIC`). The backend validates a four-value sort whitelist and uses fixed parameterized SQL ordering; the mobile layer maps `completedCount`, applies the same deterministic comparison for its local filtering/pagination view, and reloads the real API when sort changes. Mine removes only its own `AppHeader` contract and weather loading, leaving shared header behavior on the other root pages unchanged.

**Tech Stack:** uni-app 5.15, Vue 3.4, TypeScript 4.9 strict, Vitest 1.6, Java 21, Spring Boot 3.5, MyBatis, PostgreSQL/Flyway, OpenAPI 3.1, HBuilderX Android debug base, ADB.

## Global Constraints

- Execute directly in the current session; do not use subagents.
- Perform one implementation round and one review round only.
- After focused tests pass, perform one mobile build and one Android device-verification session only.
- Stop immediately and report if implementation time reaches 60 minutes.
- Record unrelated findings as observations; do not modify them.
- Preserve all pre-existing dirty-worktree changes, especially the status-icon adjustments already present in `PlatformMinePage.vue` and `platform-mine.test.ts`.
- Do not touch payment, refunds, booking, detail, fulfillment, delivery, or unrelated platform flows.
- Do not add dependencies, format directories, or commit/push implementation without a new explicit user request.
- Development counts and prices remain development-only seed data; production code must not import the catalog fixture.

## File Structure

### Contract and backend

- Modify `contracts/openapi-inspection-catalog.yaml`: replace `DISTANCE_ASC` with `SALES_DESC` in `CatalogSort`.
- Modify `contracts/inspection-catalog.examples.json`: publish `SALES_DESC` in supported development sorts.
- Modify `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionServiceCatalogController.java`: accept optional `categoryCode` and `sort` query parameters.
- Modify `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionServiceCatalogReadService.java`: validate the category and sort whitelist, carry typed sort state to the store, and return `completedCount` on list cards.
- Modify `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/PostgresInspectionServiceCatalogReadStore.java`: pass category/sort to MyBatis and map `completedCount`.
- Modify `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/InspectionServiceCatalogMapper.java`: apply the power-group filter and fixed stable order expressions.
- Modify `backend/bootstrap/src/main/resources/db/development/inspection/R__dev_inspection_catalog.sql`: add the three missing power subcategories, coverage, booking items, prices, and development completed counts.
- Modify `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionServiceCatalogControllerTest.java`: verify HTTP query forwarding and list count serialization.
- Modify `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionServiceCatalogReadServiceTest.java`: verify whitelist validation and typed store inputs.
- Create `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/InspectionServiceCatalogMapperContractTest.java`: verify fixed SQL ordering and absence of raw substitution.
- Modify `backend/bootstrap/src/test/java/com/lowaltitude/inspection/bootstrap/infrastructure/DevelopmentInspectionCatalogMigrationTest.java`: verify five power categories and their counts.

### Mobile catalog

- Modify `apps/mobile/src/features/inspection/navigation/inspection-routes.ts`: expose `SALES_DESC` instead of `DISTANCE_ASC`.
- Modify `apps/mobile/src/features/inspection/presentation/catalog/model/inspection-catalog.ts`: add numeric `completedCount`, remove distance behavior, and implement stable comprehensive/sales/price comparators.
- Modify `apps/mobile/src/features/inspection/presentation/catalog/model/inspection-catalog.test.ts`: prove old distance routes fall back and the approved orders are exact.
- Modify `apps/mobile/src/features/inspection/data/catalog/inspection-catalog-api.ts`: send `categoryCode=POWER&sort=...`, map all five subcategories, local images, and `completedCount`.
- Modify `apps/mobile/src/features/inspection/tests/inspection-catalog-api.test.ts`: verify URL, mappings, images, and real count display data.
- Modify `apps/mobile/src/features/inspection/presentation/catalog/components/CatalogToolbar.vue`: render and emit “销量”/`SALES_DESC`.
- Modify `apps/mobile/src/features/inspection/presentation/catalog/components/InspectionServiceCard.vue`: render `已服务 X 次` from numeric data.
- Modify `apps/mobile/src/features/inspection/presentation/catalog/pages/InspectionCatalogPage.vue`: remove distance-only location state and reload the API on sort change while retaining city relocation.
- Modify `apps/mobile/src/features/inspection/presentation/catalog/fixtures/power-inspection-catalog.fixture.ts`: keep the test-only fixture type-correct with numeric counts and no distance values; production continues not to import it.

### Mine header

- Modify `apps/mobile/src/features/inspection/presentation/platform/mine/PlatformMinePage.vue`: remove only `AppHeader`, its props/emits, wrapper, and wrapper CSS.
- Modify `apps/mobile/src/pages/mine/index.vue`: remove Mine-only weather/header wiring while retaining current city for account location text.
- Modify `apps/mobile/src/features/inspection/tests/platform-mine.test.ts`: assert the Mine component contains no header/search/city/message contract while preserving existing status-icon assertions.
- Modify `apps/mobile/src/features/platform/tests/app-header-consistency.test.ts`: require the shared header on Home/Service/Discover/Mall and explicitly require its absence on Mine.

---

### Task 1: Write all focused failing tests before production edits

**Files:**

- Modify all six test files listed above.

**Interfaces:**

- Consumes: approved sort values and category order from the design spec.
- Produces: a red focused suite that precisely defines Mine header absence, numeric sales mapping, service ordering, backend whitelist forwarding, SQL safety, and five development products.

- [ ] **Step 1: Add mobile model assertions**

Update the service builder to use `completedCount`, import `selectCatalogServices` and `formatCompletedCount`, and assert the exact orders:

```ts
const services = [
  service('transmission', { category: 'TRANSMISSION', completedCount: 8560 }),
  service('substation', { category: 'SUBSTATION', completedCount: 6230 }),
  service('distribution', { category: 'DISTRIBUTION', completedCount: 5420 }),
  service('cable', { category: 'CABLE', completedCount: 7310 }),
  service('photovoltaic', { category: 'PHOTOVOLTAIC', completedCount: 3210 }),
]

expect(selectCatalogServices(services, {
  ...DEFAULT_CATALOG_QUERY,
  sort: 'COMPREHENSIVE',
}).map(({ id }) => id)).toEqual([
  'transmission', 'substation', 'distribution', 'cable', 'photovoltaic',
])

expect(selectCatalogServices(services, {
  ...DEFAULT_CATALOG_QUERY,
  sort: 'SALES_DESC',
}).map(({ id }) => id)).toEqual([
  'transmission', 'cable', 'substation', 'distribution', 'photovoltaic',
])

expect(normalizeCatalogQuery({ sort: 'DISTANCE_ASC' }).sort)
  .toBe('COMPREHENSIVE')
expect(formatCompletedCount(8560)).toBe('已服务 8,560 次')
```

- [ ] **Step 2: Add mobile API and component-contract assertions**

Add `completedCount: 8560` to the DTO test response and expect:

```ts
expect(calls).toEqual([
  '/api/v1/inspection/services?cityCode=370500&categoryCode=POWER&sort=SALES_DESC&page=1&size=50',
])
expect(content.services[0]).toMatchObject({
  category: 'TRANSMISSION',
  completedCount: 8560,
})
```

Call `loadCatalog('370500', 'SALES_DESC')`. Add a five-category DTO scenario and assert that `POWER`, `SUBSTATION`, `DISTRIBUTION`, `CABLE`, and `PHOTOVOLTAIC` map to the five catalog category codes. Assert that the production page still contains no fixture import.

In Mine tests, add:

```ts
expect(minePageSource).not.toContain("AppHeader")
expect(minePageSource).not.toContain('<AppHeader')
expect(minePageSource).not.toContain('searchPlaceholder')
expect(minePageSource).not.toContain("message: []")
```

In the root header consistency test, use four shared-header sources and assert:

```ts
expect(minePageSource).not.toContain("AppHeader")
expect(minePageSource).not.toContain('<AppHeader')
```

- [ ] **Step 3: Add backend list/whitelist tests**

Extend the in-memory store test contract to receive the normalized parent category and typed sort:

```java
InspectionServicePageView page = service.search(
        "370500", "POWER", "SALES_DESC", 1, 20);
assertThat(page.items()).singleElement()
        .extracting(InspectionServiceCardView::completedCount)
        .isEqualTo(8_560L);

assertThatThrownBy(() -> service.search(
        "370500", "POWER", "DROP TABLE", 1, 20))
        .isInstanceOf(IllegalArgumentException.class);
```

Add a controller list test that invokes:

```java
get("/api/v1/inspection/services")
    .param("cityCode", "370500")
    .param("categoryCode", "POWER")
    .param("sort", "SALES_DESC")
```

and expects HTTP 200 plus `$.items[0].completedCount == 8560`, while verifying the exact service call.

- [ ] **Step 4: Add SQL and development-seed contract tests**

Create a reflection test over `InspectionServiceCatalogMapper.findActivePage`'s `@Select` value:

```java
String sql = String.join("\n", InspectionServiceCatalogMapper.class
        .getMethod("findActivePage", String.class, String.class,
                String.class, int.class, int.class)
        .getAnnotation(Select.class).value());

assertThat(sql)
        .contains("#{sort}")
        .contains("completed_count DESC")
        .contains("category_code IN")
        .contains("ORDER BY")
        .doesNotContain("${");
```

Extend the development migration test to require the service numbers/categories and counts `8560`, `6230`, `5420`, `7310`, and `3210`.

- [ ] **Step 5: Run the focused suite and capture the expected red state**

```powershell
pnpm.cmd --filter @low-altitude/mobile test -- `
  src/features/inspection/presentation/catalog/model/inspection-catalog.test.ts `
  src/features/inspection/tests/inspection-catalog-api.test.ts `
  src/features/inspection/tests/platform-mine.test.ts `
  src/features/platform/tests/app-header-consistency.test.ts

.\mvnw.cmd -pl :inspection,:bootstrap -am `
  -Dtest=InspectionServiceCatalogReadServiceTest,InspectionServiceCatalogMapperContractTest,InspectionServiceCatalogControllerTest,DevelopmentInspectionCatalogMigrationTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: mobile tests fail on missing `SALES_DESC`, `completedCount`, and Mine header removal; Java tests fail to compile or assert until the new search signature, card field, SQL, and seed rows exist. Do not begin implementation unless failures correspond to these approved gaps.

### Task 2: Perform the single implementation round

**Files:**

- Modify only the production, contract, and development-seed files listed in File Structure.

**Interfaces:**

- Consumes: the red tests from Task 1.
- Produces: `SALES_DESC`, real count mapping, five power products, deterministic API ordering, and a header-free Mine page.

- [ ] **Step 1: Update contract enums**

Use this exact enum in `openapi-inspection-catalog.yaml` and the examples sort list:

```yaml
CatalogSort:
  type: string
  enum:
    - COMPREHENSIVE
    - SALES_DESC
    - PRICE_ASC
    - PRICE_DESC
  default: COMPREHENSIVE
```

- [ ] **Step 2: Implement typed backend query validation and card counts**

Add a package-private enum in `InspectionServiceCatalogReadService.java`:

```java
enum InspectionCatalogSort {
    COMPREHENSIVE,
    SALES_DESC,
    PRICE_ASC,
    PRICE_DESC;

    static InspectionCatalogSort parse(String value) {
        try {
            return value == null || value.isBlank()
                    ? COMPREHENSIVE
                    : valueOf(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("catalog sort is invalid");
        }
    }
}
```

Change the service boundary to:

```java
public InspectionServicePageView search(
        String cityCode,
        String categoryCode,
        String sortCode,
        int page,
        int size
)
```

Normalize a blank category to `null`, validate a present value with `^[A-Z0-9_]{2,64}$`, parse `sortCode`, and add `long completedCount` to `StoredInspectionServiceCard` and `InspectionServiceCardView`. `toView` must copy `row.completedCount()`. Use these exact store signatures:

```java
long countActive(String cityCode, String categoryCode);

List<StoredInspectionServiceCard> findActivePage(
        String cityCode,
        String categoryCode,
        InspectionCatalogSort sort,
        int limit,
        int offset
);
```

The PostgreSQL store passes `sort.name()` to this exact mapper signature:

```java
List<ServiceCardRow> findActivePage(
        @Param("cityCode") String cityCode,
        @Param("categoryCode") String categoryCode,
        @Param("sort") String sort,
        @Param("limit") int limit,
        @Param("offset") int offset
);
```

Update the controller call exactly as follows:

```java
return service.search(
        cityCode.trim(),
        categoryCode == null ? null : categoryCode.trim(),
        sort,
        page,
        size);
```

with optional request parameters `categoryCode` and `sort`, where `sort` defaults to `COMPREHENSIVE`.

- [ ] **Step 3: Implement the fixed SQL whitelist behavior**

Add `categoryCode` to both mapper queries and `completed_count` to the list select. The power parent filter must be fixed SQL:

```sql
AND (
    #{categoryCode} IS NULL
    OR (#{categoryCode} = 'POWER' AND category_code IN (
        'POWER', 'SUBSTATION', 'DISTRIBUTION', 'CABLE', 'PHOTOVOLTAIC'
    ))
    OR (#{categoryCode} <> 'POWER' AND category_code = #{categoryCode})
)
```

The list order must use only bound comparisons and fixed columns:

```sql
ORDER BY
  CASE WHEN #{sort} = 'COMPREHENSIVE' THEN
    CASE category_code
      WHEN 'POWER' THEN 10
      WHEN 'SUBSTATION' THEN 20
      WHEN 'DISTRIBUTION' THEN 30
      WHEN 'CABLE' THEN 40
      WHEN 'PHOTOVOLTAIC' THEN 50
      ELSE 90
    END
  END ASC,
  CASE WHEN #{sort} IN ('COMPREHENSIVE', 'SALES_DESC')
    THEN completed_count END DESC,
  CASE WHEN #{sort} IN ('PRICE_ASC', 'PRICE_DESC')
    AND price_mode = 'PROJECT_QUOTE' THEN 1 ELSE 0 END ASC,
  CASE WHEN #{sort} = 'PRICE_ASC' THEN starting_amount_minor END ASC NULLS LAST,
  CASE WHEN #{sort} = 'PRICE_DESC' THEN starting_amount_minor END DESC NULLS LAST,
  id ASC
```

Pass `sort.name()` only after enum parsing; never use `${sort}`.

- [ ] **Step 4: Add the five development products**

Add `completed_count` to the seed insert/update and use these exact development rows:

```text
POWER          输电线路无人机巡检  69900  8560
SUBSTATION     变电站智能巡检      89900  6230
DISTRIBUTION   配电网无人机巡检    59900  5420
CABLE          电缆通道巡检        79900  7310
PHOTOVOLTAIC   光伏电站无人机巡检  69900  3210
```

Keep `33333333-3333-3333-3333-333333333333` for POWER and `66666666-6666-6666-6666-666666666666` for PHOTOVOLTAIC. Use these UUIDs and service numbers for the three new rows:

```text
88888888-8888-4888-8888-888888888888  INS-SVC-DEV-SUBSTATION
99999999-9999-4999-8999-999999999999  INS-SVC-DEV-DISTRIBUTION
aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa  INS-SVC-DEV-CABLE
```

Add both `370500` and `370100` coverage rows for each. Add these exact booking items with display orders 10, 20, and 30:

```text
SUBSTATION: THERMAL_IMAGING, METER_READING, EQUIPMENT_DIAGNOSIS
DISTRIBUTION: POLE_TOWER, CHANNEL, HAZARD
CABLE: TUNNEL, PARTIAL_DISCHARGE, ENVIRONMENT
```

Keep the repeatable migration idempotent with the existing `ON CONFLICT` clauses.

- [ ] **Step 5: Implement mobile types, comparison, API mapping, and display**

Use this sort union in both catalog model and navigation:

```ts
export type CatalogSort =
  | 'COMPREHENSIVE'
  | 'SALES_DESC'
  | 'PRICE_ASC'
  | 'PRICE_DESC'
```

Add `completedCount?: number` to `InspectionCatalogService`. Define the category rank map and comparators with service ID as the final key:

```ts
const CATEGORY_RANK: Record<Exclude<CatalogCategory, 'ALL'>, number> = {
  TRANSMISSION: 10,
  SUBSTATION: 20,
  DISTRIBUTION: 30,
  CABLE: 40,
  PHOTOVOLTAIC: 50,
}

function compareById(left: InspectionCatalogService, right: InspectionCatalogService): number {
  return left.id.localeCompare(right.id)
}
```

For comprehensive: category rank, count descending, ID. For sales: count descending, ID. For both price directions: project quote last, amount direction, ID. `normalizeCatalogQuery({ sort: 'DISTANCE_ASC' })` must fall back to `COMPREHENSIVE`.

Format count through:

```ts
export function formatCompletedCount(count: number): string {
  return `已服务 ${Math.max(0, Math.trunc(count)).toLocaleString('zh-CN')} 次`
}
```

Map these API categories and existing images:

```ts
POWER: 'TRANSMISSION',
SUBSTATION: 'SUBSTATION',
DISTRIBUTION: 'DISTRIBUTION',
CABLE: 'CABLE',
PHOTOVOLTAIC: 'PHOTOVOLTAIC',
```

Change the API signature to `loadCatalog(cityCode: string, sort: CatalogSort)` and request:

```ts
`/api/v1/inspection/services?cityCode=${encodeURIComponent(cityCode)}&categoryCode=POWER&sort=${encodeURIComponent(sort)}&page=1&size=50`
```

Keep `loadPortal` on the unfiltered all-category request. Render `formatCompletedCount(service.completedCount)` only when the count is defined.

- [ ] **Step 6: Replace the toolbar action and remove only distance-specific page logic**

The second toolbar button must emit `SALES_DESC` and display `销量`. Remove `hasValidLocation` and `requestLocationForDistanceSort`. Keep `createCatalogCityController`, `locateCurrentCityName`, and the city sheet unchanged.

Implement sort selection as:

```ts
async function selectSort(sort: CatalogSort): Promise<void> {
  query.value = applyCatalogSort(query.value, sort)
  await loadCatalogContent()
}
```

The latest loader's closure must call `inspectionCatalogApi.loadCatalog(cityCode, query.value.sort)` so a sort change reloads the real API.

- [ ] **Step 7: Remove Mine's top header without disturbing existing profile/status edits**

In `PlatformMinePage.vue`, remove the `AppHeader` and weather imports; reduce props to `state` and optional `activeTab`; remove `location`, `search`, and `message` emits; remove the `.platform-mine__header` block and CSS. Keep `.platform-mine__scroll` safe-area padding and every profile/customer-service/settings element.

In `pages/mine/index.vue`, remove weather imports, weather state/loader, `cityName`, `loadWeather`, header props, and header event bindings. Keep `currentCityStore`, `restoreDefaultCity`, and city-derived account location text. `onShow` must still hide the native tab bar, restore the default city, and load real recent orders.

### Task 3: Run focused verification before any build

**Files:**

- Verify only; no production edit is authorized after this point unless a focused test exposes an in-scope blocker.

**Interfaces:**

- Consumes: the single implementation round.
- Produces: automated evidence permitting the one build.

- [ ] **Step 1: Run the same focused mobile tests**

```powershell
pnpm.cmd --filter @low-altitude/mobile test -- `
  src/features/inspection/presentation/catalog/model/inspection-catalog.test.ts `
  src/features/inspection/tests/inspection-catalog-api.test.ts `
  src/features/inspection/tests/platform-mine.test.ts `
  src/features/platform/tests/app-header-consistency.test.ts
```

Expected: all selected Vitest files pass once, with no rerun to chase flaky success.

- [ ] **Step 2: Run the same focused backend tests**

```powershell
.\mvnw.cmd -pl :inspection,:bootstrap -am `
  -Dtest=InspectionServiceCatalogReadServiceTest,InspectionServiceCatalogMapperContractTest,InspectionServiceCatalogControllerTest,DevelopmentInspectionCatalogMigrationTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all selected JUnit tests pass; the list card serializes `completedCount`, unknown sort is rejected, SQL contains no `${...}`, and the dev seed contains five power categories.

- [ ] **Step 3: Run static checks**

```powershell
pnpm.cmd --filter @low-altitude/mobile run type-check
git diff --check
```

Expected: both commands exit 0. If a failure is unrelated and pre-existing, record its exact file/message and do not modify it.

### Task 4: Perform the single review round

**Files:**

- Review the complete working tree while distinguishing the task patch from pre-existing user changes.

**Interfaces:**

- Consumes: passing focused tests/static checks.
- Produces: one whitelist/scope/safety decision and an observations list.

- [ ] **Step 1: Inspect the only complete diff review**

```powershell
git diff --stat
git diff --check
git diff -- `
  contracts/openapi-inspection-catalog.yaml `
  contracts/inspection-catalog.examples.json `
  backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionServiceCatalogController.java `
  backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionServiceCatalogReadService.java `
  backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/PostgresInspectionServiceCatalogReadStore.java `
  backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/InspectionServiceCatalogMapper.java `
  backend/bootstrap/src/main/resources/db/development/inspection/R__dev_inspection_catalog.sql `
  apps/mobile/src/features/inspection/navigation/inspection-routes.ts `
  apps/mobile/src/features/inspection/presentation/catalog `
  apps/mobile/src/features/inspection/data/catalog/inspection-catalog-api.ts `
  apps/mobile/src/features/inspection/presentation/platform/mine/PlatformMinePage.vue `
  apps/mobile/src/pages/mine/index.vue
```

Confirm all of the following in this one review:

- no payment/refund or unrelated route change;
- no fixture import from production;
- no `${sort}` or client-provided SQL fragment;
- old `DISTANCE_ASC` is absent from active catalog types/UI/OpenAPI;
- city relocation remains present;
- Mine profile/customer service/settings remain present;
- the pre-existing Mine status-icon changes are preserved;
- only development repeatable SQL contains the five illustrative counts;
- no unrelated existing dirty file was overwritten, removed, staged, or formatted.

Fix only an in-scope blocking defect found in this review; otherwise add it to observations and proceed without another review round.

### Task 5: Refresh runtime, build once, and verify Android once

**Files:**

- Runtime/build outputs only; do not stage generated files.

**Interfaces:**

- Consumes: passing tests and completed single review.
- Produces: one backend API snapshot, one HBuilderX build/sync, and one Android acceptance session.

- [ ] **Step 1: Refresh the backend and verify the real API before mobile build**

Use the existing LAN acceptance script once to rebuild/restart the backend container and apply the repeatable development seed:

```powershell
$acceptance = .\infra\scripts\start-lan-acceptance.ps1
$backendBaseUrl = $acceptance.BackendBaseUrl
```

Then request the exact runtime URL returned by the script:

```powershell
$comprehensive = Invoke-RestMethod "$backendBaseUrl/api/v1/inspection/services?cityCode=370500&categoryCode=POWER&sort=COMPREHENSIVE&page=1&size=50"
$sales = Invoke-RestMethod "$backendBaseUrl/api/v1/inspection/services?cityCode=370500&categoryCode=POWER&sort=SALES_DESC&page=1&size=50"
$comprehensive.items | Select-Object name,completedCount
$sales.items | Select-Object name,completedCount
```

Expected comprehensive names: 输电线路、变电站、配电网、电缆、光伏. Expected sales names: 输电线路、电缆、变电站、配电网、光伏. Each item exposes the approved `completedCount`.

- [ ] **Step 2: Perform the single mobile build/sync**

```powershell
$deviceSerial = (adb devices | Select-String '\tdevice$' | Select-Object -First 1).Line.Split("`t")[0]
if ([string]::IsNullOrWhiteSpace($deviceSerial)) { throw 'No authorized Android device is connected.' }
& 'D:\DevTools\HBuilderX\cli.exe' launch app-android `
  --project 'C:\Users\Admin（无密码）\Documents\低空经济\.worktrees\inspection-consolidation-20260801\apps\mobile' `
  --deviceId $deviceSerial `
  --playground standard `
  --native-log false
```

This HBuilderX launch is the one authorized mobile build. Do not run a separate H5/App-Plus build, cloud package, APK build, or second launch.

- [ ] **Step 3: Perform the single Android acceptance session**

Using the connected device once:

1. Open “我的”; verify the first visible business block is the profile region below the status-bar safe area, with no city/weather/search/message row; verify customer service and settings remain.
2. Navigate through “服务” → “我要巡检” → the power catalog.
3. On “综合排序”, record the five visible category/title order.
4. Tap “销量” once; verify the order becomes 输电线路、电缆、变电站、配电网、光伏 and each card shows its service count.
5. Tap “价格” once; verify ascending priced services and a coherent active arrow.
6. Tap each of 变电站、配电网、电缆 category chips and verify the matching real service remains visible.

Capture one Mine screenshot and the minimum catalog screenshots needed for comprehensive/sales evidence during this same session. Do not start another broad device pass.

- [ ] **Step 4: Report without committing**

Report changed files, focused test commands/results, API order evidence, the single build/sync result, Android observations, and unrelated observations. State explicitly that implementation was not committed or pushed.
