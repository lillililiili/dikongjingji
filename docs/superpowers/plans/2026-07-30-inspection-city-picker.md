# Inspection City Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the development-only three-city ActionSheet with a production-capable nationwide province/city picker whose hot cities are ranked by persisted search-selection counts.

**Architecture:** Add a versioned `platform_city` reference table and two narrow public APIs inside the existing modular monolith. Add reusable city-domain, API, recent-storage, and location adapters under the mobile shared city boundary; the inspection catalog page only owns sheet visibility and catalog refresh orchestration.

**Tech Stack:** PostgreSQL/Flyway, Java 21, Spring Boot 3.5, MyBatis, OpenAPI 3.1, uni-app, Vue 3, TypeScript strict mode, Pinia, Vitest.

## Global Constraints

- Work only in `.worktrees/inspection-city-picker` on `codex/inspection-city-picker`.
- Do not write into `.worktrees/inspection-integration`.
- Reserve Flyway version `V006` because the integration worktree already contains uncommitted I2 migrations `V004` and `V005`.
- Production city reference rows are real data; production `search_count` values are all `0`.
- Simulated search counts exist only in `backend/bootstrap/src/main/resources/db/development/inspection/`.
- No fake services are created for cities without inspection services.
- Only a city selected from filtered search results increments `search_count`.
- The city picker has no route and does not modify home, weather, detail, booking, order, payment, or refund behavior.
- Do not add a runtime package dependency. Data generation may use pinned `pinyin-pro@3.27.0` as a one-time MIT-licensed tool; the generated SQL is committed, not the package.
- The administrative source is the last publicly downloadable 2023 National Bureau of Statistics-derived province/city dataset, with six-digit platform codes and explicit Hong Kong, Macao, and Taiwan province-level entries documented in the generated SQL header.
- Do not commit or push unless the user explicitly asks. Each task ends with a diff checkpoint instead of an automatic commit.

---

## Planned File Structure

### Backend

- `backend/modules/inspection/migrations/V006__platform_city_directory.sql` — table, constraints, indexes.
- `backend/modules/inspection/migrations/R__platform_city_reference.sql` — real nationwide reference rows with zero counts.
- `backend/bootstrap/src/main/resources/db/development/inspection/R__dev_platform_city_popularity.sql` — development-only simulated counts.
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/city/CityDirectoryReadService.java` — validation, grouping, sorting, hot ranking.
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/city/PostgresCityDirectoryStore.java` — application/persistence adapter.
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/PlatformCityMapper.java` — read and atomic increment SQL.
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationConfiguration.java` — one bean registration.
- `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/city/PlatformCityController.java` — public GET and POST endpoints.
- `backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/security/SecurityConfiguration.java` — exact city endpoint whitelist only.
- `contracts/openapi-platform-city.yaml` — public contract.

### Mobile

- `apps/mobile/src/shared/city/domain/city-directory.ts` — types, sorting, search, located-name resolution.
- `apps/mobile/src/shared/city/data/city-directory-api.ts` — DTO mapper and API client.
- `apps/mobile/src/shared/city/data/recent-city-storage.ts` — injected local-storage adapter.
- `apps/mobile/src/shared/city/data/uni-city-location.ts` — native geocoded city-name adapter.
- `apps/mobile/src/shared/city/presentation/CitySelectionSheet.vue` — reference-matched sheet and province accordion.
- `apps/mobile/src/features/inspection/data/service-flow/uni-booking-http-client.ts` — exports the public city API client.
- `apps/mobile/src/features/inspection/presentation/catalog/pages/InspectionCatalogPage.vue` — opens sheet, applies selection, reloads catalog.

### Tests

- `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/PlatformCityMigrationTest.java`
- `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/city/CityDirectoryReadServiceTest.java`
- `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/PlatformCityMapperSqlTest.java`
- `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/city/PlatformCityControllerTest.java`
- `backend/bootstrap/src/test/java/com/lowaltitude/inspection/bootstrap/AuthenticationSecurityIntegrationTest.java`
- `backend/bootstrap/src/test/java/com/lowaltitude/inspection/bootstrap/infrastructure/DevelopmentInspectionCatalogMigrationTest.java`
- `apps/mobile/src/shared/city/domain/city-directory.test.ts`
- `apps/mobile/src/shared/city/data/recent-city-storage.test.ts`
- `apps/mobile/src/features/inspection/tests/platform-city-api.test.ts`
- `apps/mobile/src/features/inspection/tests/inspection-city-picker.test.ts`

---

### Task 1: Nationwide City Schema and Environment-Safe Data

**Files:**
- Create: `backend/modules/inspection/migrations/V006__platform_city_directory.sql`
- Create: `backend/modules/inspection/migrations/R__platform_city_reference.sql`
- Create: `backend/bootstrap/src/main/resources/db/development/inspection/R__dev_platform_city_popularity.sql`
- Create: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/PlatformCityMigrationTest.java`
- Modify: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/bootstrap/infrastructure/DevelopmentInspectionCatalogMigrationTest.java`

**Interfaces:**
- Produces table `platform_city`.
- Produces columns consumed by `PlatformCityMapper`: `city_code`, `city_name`, `city_pinyin`, `city_initials`, `province_code`, `province_name`, `province_pinyin`, `province_initials`, `search_count`, `status`.

- [ ] **Step 1: Write the failing migration contract test**

```java
class PlatformCityMigrationTest {
    @Test
    void createsVersionedCityDirectoryWithZeroProductionPopularity() throws Exception {
        String schema = resource("/db/migration/inspection/V006__platform_city_directory.sql");
        String reference = resource("/db/migration/inspection/R__platform_city_reference.sql");

        assertThat(schema)
                .contains("CREATE TABLE platform_city")
                .contains("search_count bigint NOT NULL DEFAULT 0")
                .contains("CHECK (search_count >= 0)")
                .contains("idx_platform_city_hot");
        assertThat(reference)
                .contains("'370100', '济南市'")
                .contains("'370200', '青岛市'")
                .contains("'370500', '东营市'")
                .doesNotContain("search_count");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
mvn -pl backend/modules/inspection -am -Dtest=PlatformCityMigrationTest test
```

Expected: FAIL because `PlatformCityMigrationTest` or the migration resources do not exist.

- [ ] **Step 3: Add the table migration**

```sql
CREATE TABLE platform_city (
    city_code varchar(16) PRIMARY KEY,
    city_name varchar(80) NOT NULL,
    city_pinyin varchar(160) NOT NULL,
    city_initials varchar(32) NOT NULL,
    province_code varchar(16) NOT NULL,
    province_name varchar(80) NOT NULL,
    province_pinyin varchar(160) NOT NULL,
    province_initials varchar(32) NOT NULL,
    search_count bigint NOT NULL DEFAULT 0 CHECK (search_count >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_city_tree
    ON platform_city (
        status,
        province_initials,
        province_pinyin,
        city_initials,
        city_pinyin,
        city_code
    );

CREATE INDEX idx_platform_city_hot
    ON platform_city (status, search_count DESC, city_pinyin, city_code);
```

- [ ] **Step 4: Add the real reference rows**

Generate and check in `R__platform_city_reference.sql` with this invariant:

```sql
INSERT INTO platform_city (
    city_code, city_name, city_pinyin, city_initials,
    province_code, province_name, province_pinyin, province_initials,
    status
) VALUES
    ('370100', '济南市', 'jinanshi', 'jns',
     '370000', '山东省', 'shandongsheng', 'sds', 'ACTIVE'),
    ('370200', '青岛市', 'qingdaoshi', 'qds',
     '370000', '山东省', 'shandongsheng', 'sds', 'ACTIVE'),
    ('370500', '东营市', 'dongyingshi', 'dys',
     '370000', '山东省', 'shandongsheng', 'sds', 'ACTIVE')
ON CONFLICT (city_code) DO UPDATE
SET city_name = EXCLUDED.city_name,
    city_pinyin = EXCLUDED.city_pinyin,
    city_initials = EXCLUDED.city_initials,
    province_code = EXCLUDED.province_code,
    province_name = EXCLUDED.province_name,
    province_pinyin = EXCLUDED.province_pinyin,
    province_initials = EXCLUDED.province_initials,
    status = EXCLUDED.status,
    updated_at = now();
```

The checked-in file must contain every active province/prefecture row, retain existing `search_count` during repeatable migration, include a source/version/license header, use six-digit codes, and contain at least 34 distinct province codes and at least 330 city rows.

- [ ] **Step 5: Add development-only popularity**

```sql
UPDATE platform_city AS city
SET search_count = seed.search_count,
    version = city.version + 1,
    updated_at = now()
FROM (VALUES
    ('370100', 1260::bigint),
    ('370200', 1120::bigint),
    ('370500', 980::bigint),
    ('370600', 760::bigint),
    ('370700', 690::bigint),
    ('371300', 610::bigint)
) AS seed(city_code, search_count)
WHERE city.city_code = seed.city_code;
```

- [ ] **Step 6: Extend the development profile test**

```java
assertThat(sql)
        .contains("'370100', 1260::bigint")
        .contains("'370200', 1120::bigint")
        .contains("'370500', 980::bigint");
```

- [ ] **Step 7: Run migration tests**

Run:

```powershell
mvn -pl backend/modules/inspection,backend/bootstrap -am -Dtest=PlatformCityMigrationTest,DevelopmentInspectionCatalogMigrationTest test
```

Expected: PASS.

- [ ] **Step 8: Diff checkpoint**

Run:

```powershell
git diff --stat
git diff -- backend/modules/inspection/migrations backend/bootstrap/src/main/resources/db/development/inspection
```

Expected: only city migration, reference data, development popularity, and their tests.

---

### Task 2: Backend Directory and Atomic Popularity Service

**Files:**
- Create: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/city/CityDirectoryReadService.java`
- Create: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/city/PostgresCityDirectoryStore.java`
- Create: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/PlatformCityMapper.java`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationConfiguration.java`
- Create: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/city/CityDirectoryReadServiceTest.java`
- Create: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/PlatformCityMapperSqlTest.java`

**Interfaces:**
- Produces `CityDirectoryReadService.DirectoryView directory()`.
- Produces `boolean recordSearchSelection(String cityCode)`.
- `DirectoryView` contains `List<CityView> hotCities` and `List<ProvinceView> provinces`.

- [ ] **Step 1: Write failing service tests**

```java
@Test
void groupsSortedProvincesAndLimitsHotCitiesToSix() {
    CityDirectoryReadService service = new CityDirectoryReadService(new FakeStore(rows()));

    DirectoryView view = service.directory();

    assertThat(view.hotCities()).extracting(CityView::code)
            .containsExactly("370100", "370200", "370500", "370600", "370700", "371300");
    assertThat(view.provinces()).extracting(ProvinceView::name)
            .containsExactly("安徽省", "山东省");
    assertThat(view.provinces().get(1).cities()).extracting(CityView::name)
            .containsExactly("东营市", "济南市", "青岛市");
}

@Test
void incrementsOnlyValidSixDigitActiveCity() {
    FakeStore store = new FakeStore(rows());
    CityDirectoryReadService service = new CityDirectoryReadService(store);

    assertThat(service.recordSearchSelection("370100")).isTrue();
    assertThat(store.incrementedCode).isEqualTo("370100");
    assertThatThrownBy(() -> service.recordSearchSelection("../bad"))
            .isInstanceOf(IllegalArgumentException.class);
    assertThat(service.recordSearchSelection("999999")).isFalse();
}
```

- [ ] **Step 2: Run the service test to verify it fails**

Run:

```powershell
mvn -pl backend/modules/inspection -am -Dtest=CityDirectoryReadServiceTest test
```

Expected: FAIL because the service does not exist.

- [ ] **Step 3: Implement the application contract**

```java
public final class CityDirectoryReadService {
    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");
    private final CityDirectoryStore store;

    public DirectoryView directory() {
        List<StoredCity> rows = store.findActiveCities();
        List<CityView> hot = rows.stream()
                .filter(city -> city.searchCount() > 0)
                .sorted(Comparator.comparingLong(StoredCity::searchCount).reversed()
                        .thenComparing(StoredCity::cityPinyin)
                        .thenComparing(StoredCity::cityCode))
                .limit(6)
                .map(CityDirectoryReadService::toCity)
                .toList();
        Map<String, List<StoredCity>> grouped = rows.stream()
                .collect(Collectors.groupingBy(
                        StoredCity::provinceCode,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<ProvinceView> provinces = grouped.values().stream()
                .map(CityDirectoryReadService::toProvince)
                .sorted(PROVINCE_ORDER)
                .toList();
        return new DirectoryView(hot, provinces);
    }

    public boolean recordSearchSelection(String cityCode) {
        if (cityCode == null || !CITY_CODE.matcher(cityCode).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return store.incrementSearchCount(cityCode) == 1;
    }
}
```

Define exact records in the same file:

```java
public record DirectoryView(
        List<CityView> hotCities,
        List<ProvinceView> provinces) {}

public record ProvinceView(
        String code,
        String name,
        String pinyin,
        String initials,
        List<CityView> cities) {}

public record CityView(
        String code,
        String name,
        String pinyin,
        String initials,
        String provinceCode,
        String provinceName) {}
```

- [ ] **Step 4: Write failing mapper SQL tests**

```java
assertThat(source)
        .contains("ORDER BY province_initials, province_pinyin")
        .contains("city_initials, city_pinyin, city_code")
        .contains("SET search_count = search_count + 1")
        .contains("version = version + 1")
        .contains("WHERE city_code = #{cityCode}")
        .contains("AND status = 'ACTIVE'");
```

- [ ] **Step 5: Implement mapper and PostgreSQL store**

```java
@Select("""
        SELECT city_code, city_name, city_pinyin, city_initials,
               province_code, province_name, province_pinyin,
               province_initials, search_count
        FROM platform_city
        WHERE status = 'ACTIVE'
        ORDER BY province_initials, province_pinyin, province_code,
                 city_initials, city_pinyin, city_code
        """)
List<CityRow> findActiveCities();

@Update("""
        UPDATE platform_city
        SET search_count = search_count + 1,
            version = version + 1,
            updated_at = now()
        WHERE city_code = #{cityCode}
          AND status = 'ACTIVE'
        """)
int incrementSearchCount(@Param("cityCode") String cityCode);
```

- [ ] **Step 6: Register one bean**

```java
@Bean
CityDirectoryReadService cityDirectoryReadService(PlatformCityMapper mapper) {
    return new CityDirectoryReadService(new PostgresCityDirectoryStore(mapper));
}
```

- [ ] **Step 7: Run module tests**

Run:

```powershell
mvn -pl backend/modules/inspection -am -Dtest=CityDirectoryReadServiceTest,PlatformCityMapperSqlTest test
```

Expected: PASS.

- [ ] **Step 8: Diff checkpoint**

Run:

```powershell
git diff --check
git diff -- backend/modules/inspection/src/main/java backend/modules/inspection/src/test/java
```

Expected: no catalog detail, reservation, order, payment, or weather changes.

---

### Task 3: Public City APIs, Contract, and Narrow Security Rules

**Files:**
- Create: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/city/PlatformCityController.java`
- Modify: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/security/SecurityConfiguration.java`
- Create: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/city/PlatformCityControllerTest.java`
- Modify: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/bootstrap/AuthenticationSecurityIntegrationTest.java`
- Create: `contracts/openapi-platform-city.yaml`

**Interfaces:**
- Produces `GET /api/v1/platform/cities`.
- Produces `POST /api/v1/platform/cities/{cityCode}/search-selections`.
- POST success is `204`; inactive or unknown city is `404`.

- [ ] **Step 1: Write failing controller tests**

```java
mockMvc.perform(get("/api/v1/platform/cities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hotCities[0].code").value("370100"))
        .andExpect(jsonPath("$.provinces[0].cities").isArray());

mockMvc.perform(post("/api/v1/platform/cities/370100/search-selections"))
        .andExpect(status().isNoContent());

mockMvc.perform(post("/api/v1/platform/cities/999999/search-selections"))
        .andExpect(status().isNotFound());
```

- [ ] **Step 2: Run the controller test to verify it fails**

Run:

```powershell
mvn -pl backend/bootstrap -am -Dtest=PlatformCityControllerTest test
```

Expected: FAIL because the controller does not exist.

- [ ] **Step 3: Implement the controller**

```java
@RestController
public final class PlatformCityController {
    private final CityDirectoryReadService service;

    @GetMapping("/api/v1/platform/cities")
    public DirectoryView directory() {
        return service.directory();
    }

    @PostMapping("/api/v1/platform/cities/{cityCode}/search-selections")
    public ResponseEntity<Void> recordSearchSelection(
            @PathVariable String cityCode) {
        if (!service.recordSearchSelection(cityCode)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "city is unavailable");
        }
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Add exact anonymous security tests**

```java
mockMvc.perform(get("/api/v1/platform/cities"))
        .andExpect(status().isOk());
mockMvc.perform(post("/api/v1/platform/cities/370100/search-selections"))
        .andExpect(status().isNoContent());
mockMvc.perform(post("/api/v1/platform/cities"))
        .andExpect(status().isUnauthorized());
```

- [ ] **Step 5: Whitelist only the two exact operations**

```java
.requestMatchers(HttpMethod.GET,
        "/api/v1/platform/cities").permitAll()
.requestMatchers(HttpMethod.POST,
        "/api/v1/platform/cities/*/search-selections").permitAll()
```

Do not add `/api/v1/platform/**` or another broad matcher.

- [ ] **Step 6: Add the OpenAPI contract**

```yaml
paths:
  /api/v1/platform/cities:
    get:
      operationId: getPlatformCityDirectory
      responses:
        "200":
          description: Nationwide city directory
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/CityDirectory"
  /api/v1/platform/cities/{cityCode}/search-selections:
    post:
      operationId: recordPlatformCitySearchSelection
      parameters:
        - name: cityCode
          in: path
          required: true
          schema:
            type: string
            pattern: "^[0-9]{6}$"
      responses:
        "204":
          description: Search selection recorded
        "404":
          description: City not found or inactive
```

- [ ] **Step 7: Run API and security tests**

Run:

```powershell
mvn -pl backend/bootstrap -am -Dtest=PlatformCityControllerTest,AuthenticationSecurityIntegrationTest test
```

Expected: PASS, with reservation/order endpoints still authenticated.

- [ ] **Step 8: Diff checkpoint**

Run:

```powershell
git diff --check
git diff -- backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/security/SecurityConfiguration.java contracts/openapi-platform-city.yaml
```

Expected: two narrow matchers and one new contract only.

---

### Task 4: Mobile City Domain, API, Recent Storage, and Location Adapter

**Files:**
- Create: `apps/mobile/src/shared/city/domain/city-directory.ts`
- Create: `apps/mobile/src/shared/city/domain/city-directory.test.ts`
- Create: `apps/mobile/src/shared/city/data/city-directory-api.ts`
- Create: `apps/mobile/src/shared/city/data/recent-city-storage.ts`
- Create: `apps/mobile/src/shared/city/data/recent-city-storage.test.ts`
- Create: `apps/mobile/src/shared/city/data/uni-city-location.ts`
- Create: `apps/mobile/src/features/inspection/tests/platform-city-api.test.ts`

**Interfaces:**
- Produces `searchCities(directory, keyword): PlatformCity[]`.
- Produces `createCityDirectoryApi(http)`.
- Produces `createRecentCityStorage(storage, key?)`.
- Produces `locateCurrentCityName(): Promise<string>`.

- [ ] **Step 1: Write failing city-domain tests**

```ts
it('sorts provinces and cities by initials, pinyin, then code', () => {
  expect(sortProvinces(input).map((item) => item.name))
    .toEqual(['安徽省', '山东省'])
  expect(sortCities(shandong).map((item) => item.name))
    .toEqual(['东营市', '济南市', '青岛市'])
})

it.each([
  ['济南', ['370100']],
  ['jinan', ['370100']],
  ['jns', ['370100']],
])('searches Chinese, pinyin, and initials: %s', (keyword, codes) => {
  expect(searchCities(directory, keyword).map((city) => city.code))
    .toEqual(codes)
})

it('matches located native city names without using telephone area codes', () => {
  expect(findCityByLocatedName(directory, '济南市')?.code).toBe('370100')
  expect(findCityByLocatedName(directory, '济南')?.code).toBe('370100')
})
```

- [ ] **Step 2: Run the domain test to verify it fails**

Run:

```powershell
npm test -- --run src/shared/city/domain/city-directory.test.ts
```

Working directory: `apps/mobile`.

Expected: FAIL because the domain file does not exist.

- [ ] **Step 3: Implement exact domain types and functions**

```ts
export interface PlatformCity {
  code: string
  name: string
  pinyin: string
  initials: string
  provinceCode: string
  provinceName: string
}

export interface PlatformProvince {
  code: string
  name: string
  pinyin: string
  initials: string
  cities: PlatformCity[]
}

export interface CityDirectory {
  hotCities: PlatformCity[]
  provinces: PlatformProvince[]
}

export function searchCities(
  directory: CityDirectory,
  rawKeyword: string,
): PlatformCity[] {
  const keyword = rawKeyword.trim().toLocaleLowerCase()
  if (!keyword) return []
  return sortCities(directory.provinces.flatMap((province) => province.cities)
    .filter((city) => [city.name, city.pinyin, city.initials]
      .some((value) => value.toLocaleLowerCase().includes(keyword))))
}
```

- [ ] **Step 4: Write failing API and recent-storage tests**

```ts
expect(calls).toEqual([
  { url: '/api/v1/platform/cities', method: 'GET' },
  {
    url: '/api/v1/platform/cities/370100/search-selections',
    method: 'POST',
  },
])

storage.save(dongying)
storage.save(jinan)
storage.save(dongying)
expect(storage.load().map((city) => city.code))
  .toEqual(['370500', '370100'])
```

- [ ] **Step 5: Implement API and recent-storage adapters**

```ts
export function createCityDirectoryApi(http: PlatformCityHttpClient) {
  return {
    load(): Promise<CityDirectory> {
      return http.request({
        url: '/api/v1/platform/cities',
        method: 'GET',
      })
    },
    recordSearchSelection(cityCode: string): Promise<void> {
      return http.request({
        url: `/api/v1/platform/cities/${encodeURIComponent(cityCode)}/search-selections`,
        method: 'POST',
      })
    },
  }
}
```

```ts
const MAX_RECENT_CITIES = 3

save(city: PlatformCity): void {
  const next = [
    city,
    ...this.load().filter((item) => item.code !== city.code),
  ].slice(0, MAX_RECENT_CITIES)
  storage.set(STORAGE_KEY, JSON.stringify(next))
}
```

- [ ] **Step 6: Implement the native location adapter**

```ts
export function locateCurrentCityName(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.getLocation({
      type: 'gcj02',
      geocode: true,
      success: (result) => {
        const address = (result as unknown as {
          address?: { city?: string; province?: string }
        }).address
        const city = address?.city?.trim()
          || (address?.province?.trim().endsWith('市')
            ? address.province.trim()
            : '')
        city ? resolve(city) : reject(new Error('CITY_NAME_UNAVAILABLE'))
      },
      fail: () => reject(new Error('LOCATION_UNAVAILABLE')),
    })
  })
}
```

- [ ] **Step 7: Run mobile domain/data tests**

Run:

```powershell
npm test -- --run src/shared/city/domain/city-directory.test.ts src/shared/city/data/recent-city-storage.test.ts src/features/inspection/tests/platform-city-api.test.ts
```

Working directory: `apps/mobile`.

Expected: PASS.

- [ ] **Step 8: Diff checkpoint**

Run:

```powershell
git diff --check
git diff -- apps/mobile/src/shared/city
```

Expected: only new shared-city files; `current-city.ts` remains unchanged.

---

### Task 5: Reference-Matched City Selection Sheet

**Files:**
- Create: `apps/mobile/src/shared/city/presentation/CitySelectionSheet.vue`
- Create: `apps/mobile/src/features/inspection/tests/inspection-city-picker.test.ts`

**Interfaces:**
- Consumes `directory`, `currentCity`, `recentCities`, `loading`, and `error`.
- Emits `close`, `retry`, `relocate`, and `select`.
- `select` payload is `{ city: PlatformCity; source: CitySelectionSource }`.

- [ ] **Step 1: Write the failing component contract test**

```ts
expect(source).toContain('选择服务城市')
expect(source).toContain('搜索城市')
expect(source).toContain('当前定位')
expect(source).toContain('最近使用')
expect(source).toContain('热门城市')
expect(source).toContain('更多城市')
expect(source).toContain('选择后自动筛选该城市服务')
expect(source).toContain('env(safe-area-inset-bottom)')
expect(source).toContain('grid-template-columns: repeat(3, minmax(0, 1fr))')
expect(source).toContain('@click.self=\"$emit(\\'close\\')\"')
expect(source).not.toContain('showActionSheet')
```

- [ ] **Step 2: Run the component contract test to verify it fails**

Run:

```powershell
npm test -- --run src/features/inspection/tests/inspection-city-picker.test.ts
```

Working directory: `apps/mobile`.

Expected: FAIL because `CitySelectionSheet.vue` does not exist.

- [ ] **Step 3: Implement props, state, and events**

```ts
export type CitySelectionSource =
  | 'CURRENT'
  | 'RECENT'
  | 'HOT'
  | 'SEARCH'
  | 'TREE'

const props = defineProps<{
  directory: CityDirectory | null
  currentCity: CurrentCity | null
  recentCities: PlatformCity[]
  loading: boolean
  error: string
  relocating: boolean
}>()

const emit = defineEmits<{
  close: []
  retry: []
  relocate: []
  select: [payload: {
    city: PlatformCity
    source: CitySelectionSource
  }]
}>()

const keyword = ref('')
const expandedProvinceCode = ref<string | null>(null)
const searchResults = computed(() =>
  props.directory ? searchCities(props.directory, keyword.value) : [])
```

- [ ] **Step 4: Implement the reference hierarchy**

The template must contain:

```vue
<view class="city-sheet__mask" @click.self="$emit('close')">
  <view class="city-sheet" role="dialog" aria-label="选择服务城市">
    <view class="city-sheet__handle" />
    <view class="city-sheet__header">
      <text class="city-sheet__title">选择服务城市</text>
      <button class="city-sheet__close" aria-label="关闭" @click="$emit('close')">
        <UniIcons type="closeempty" size="24" color="#292d32" />
      </button>
    </view>
    <view class="city-sheet__search">
      <UniIcons type="search" size="20" color="#8c9299" />
      <input v-model="keyword" placeholder="搜索城市" />
    </view>
    <scroll-view class="city-sheet__scroll" scroll-y>
      <!-- current, recent, hot, search result, and province accordion -->
    </scroll-view>
    <text class="city-sheet__hint">选择后自动筛选该城市服务</text>
  </view>
</view>
```

- [ ] **Step 5: Implement province accordion and selected state**

```vue
<view v-for="province in directory?.provinces ?? []" :key="province.code">
  <button
    class="city-sheet__province"
    :aria-expanded="expandedProvinceCode === province.code"
    @click="expandedProvinceCode =
      expandedProvinceCode === province.code ? null : province.code"
  >
    <text>{{ province.name }}</text>
    <UniIcons
      :type="expandedProvinceCode === province.code ? 'up' : 'right'"
      size="16"
      color="#8a9097"
    />
  </button>
  <view
    v-if="expandedProvinceCode === province.code"
    class="city-sheet__province-cities"
  >
    <button
      v-for="city in province.cities"
      :key="city.code"
      class="city-sheet__city"
      :class="{ 'city-sheet__city--selected': currentCity?.code === city.code }"
      @click="emit('select', { city, source: 'TREE' })"
    >
      {{ city.name }}
    </button>
  </view>
</view>
```

- [ ] **Step 6: Implement visual constraints**

Required CSS values:

```css
.city-sheet__mask {
  position: fixed;
  z-index: 80;
  inset: 0;
  background: rgba(0, 0, 0, 0.56);
}

.city-sheet {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  overflow: hidden;
  max-height: min(82vh, 760px);
  padding-bottom: env(safe-area-inset-bottom);
  border-radius: 24px 24px 0 0;
  background: #fff;
}

.city-sheet__hot,
.city-sheet__province-cities {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
```

All city buttons have a minimum 44px touch height. Orange is `#ff5a00`; body text is `#1e2126`; muted text is `#8a9097`.

- [ ] **Step 7: Run city picker tests and type check**

Run:

```powershell
npm test -- --run src/features/inspection/tests/inspection-city-picker.test.ts
npm run type-check
```

Working directory: `apps/mobile`.

Expected: PASS.

- [ ] **Step 8: Diff checkpoint**

Run:

```powershell
git diff --check
git diff -- apps/mobile/src/shared/city/presentation/CitySelectionSheet.vue
```

Expected: one new component; no existing platform page changed.

---

### Task 6: Catalog Integration and Request-Race Protection

**Files:**
- Modify: `apps/mobile/src/features/inspection/data/service-flow/uni-booking-http-client.ts`
- Modify: `apps/mobile/src/features/inspection/presentation/catalog/pages/InspectionCatalogPage.vue`
- Modify: `apps/mobile/src/features/inspection/tests/inspection-city-picker.test.ts`
- Modify: `apps/mobile/src/features/inspection/tests/inspection-catalog-api.test.ts`

**Interfaces:**
- Consumes `platformCityApi`, `CitySelectionSheet`, recent storage, and native location.
- Produces city selection behavior without `import.meta.env.DEV` gating.

- [ ] **Step 1: Add failing page integration assertions**

```ts
expect(catalogPageSource).toContain('<CitySelectionSheet')
expect(catalogPageSource).toContain('platformCityApi.load')
expect(catalogPageSource).toContain('currentCityStore.setCurrentCity')
expect(catalogPageSource).toContain('recentCityStorage.save')
expect(catalogPageSource).toContain("source === 'SEARCH'")
expect(catalogPageSource).toContain('loadCatalogContent')
expect(catalogPageSource).not.toContain('DEVELOPMENT_CITIES')
expect(catalogPageSource).not.toContain('showActionSheet')
expect(catalogPageSource).not.toContain("import.meta.env.DEV")
```

- [ ] **Step 2: Run the integration test to verify it fails**

Run:

```powershell
npm test -- --run src/features/inspection/tests/inspection-city-picker.test.ts src/features/inspection/tests/inspection-catalog-api.test.ts
```

Working directory: `apps/mobile`.

Expected: FAIL because the catalog still uses `DEVELOPMENT_CITIES`.

- [ ] **Step 3: Export the public city API client**

```ts
import { createCityDirectoryApi } from '@/shared/city/data/city-directory-api'

export const platformCityApi = createCityDirectoryApi({
  request: publicRequest,
})
```

- [ ] **Step 4: Add catalog state and open behavior**

```ts
const citySheetOpen = ref(false)
const cityDirectory = ref<CityDirectory | null>(null)
const cityDirectoryLoading = ref(false)
const cityDirectoryError = ref('')
const recentCities = ref(recentCityStorage.load())
const relocating = ref(false)

async function openCitySheet(): Promise<void> {
  citySheetOpen.value = true
  if (cityDirectory.value || cityDirectoryLoading.value) return
  await loadCityDirectory()
}
```

- [ ] **Step 5: Apply city selection**

```ts
async function applyCitySelection(payload: {
  city: PlatformCity
  source: CitySelectionSource
}): Promise<void> {
  currentCityStore.setCurrentCity({
    code: payload.city.code,
    name: payload.city.name,
    provinceCode: payload.city.provinceCode,
  })
  recentCityStorage.save(payload.city)
  recentCities.value = recentCityStorage.load()
  citySheetOpen.value = false
  query.value = { ...query.value, page: 1 }

  if (payload.source === 'SEARCH') {
    void platformCityApi.recordSearchSelection(payload.city.code)
      .catch(() => undefined)
  }
  await loadCatalogContent()
}
```

`loadCatalogContent()` keeps the existing monotonic `loadRevision`; the latest city request is the only request allowed to set `content`, `loadError`, and loading flags.

- [ ] **Step 6: Implement relocation without weather coupling**

```ts
async function relocateCity(): Promise<void> {
  if (!cityDirectory.value || relocating.value) return
  relocating.value = true
  try {
    const locatedName = await locateCurrentCityName()
    const city = findCityByLocatedName(cityDirectory.value, locatedName)
    if (!city) throw new Error('CITY_NOT_IN_DIRECTORY')
    await applyCitySelection({ city, source: 'CURRENT' })
  } catch {
    uni.showToast({ title: '定位失败，请手动选择城市', icon: 'none' })
  } finally {
    relocating.value = false
  }
}
```

- [ ] **Step 7: Render the sheet**

```vue
<CitySelectionSheet
  v-if="citySheetOpen"
  :directory="cityDirectory"
  :current-city="currentCity"
  :recent-cities="recentCities"
  :loading="cityDirectoryLoading"
  :error="cityDirectoryError"
  :relocating="relocating"
  @close="citySheetOpen = false"
  @retry="loadCityDirectory"
  @relocate="relocateCity"
  @select="applyCitySelection"
/>
```

Replace `@city="selectCity"` with `@city="openCitySheet"` and delete `DEVELOPMENT_CITIES`, `selectCity()`, and `restoreDevelopmentCity()`.

- [ ] **Step 8: Run focused mobile regression**

Run:

```powershell
npm test -- --run src/shared/city src/features/inspection/tests/platform-city-api.test.ts src/features/inspection/tests/inspection-city-picker.test.ts src/features/inspection/tests/inspection-catalog-api.test.ts src/features/inspection/presentation/catalog/model/inspection-catalog.test.ts
npm run type-check
```

Working directory: `apps/mobile`.

Expected: PASS.

- [ ] **Step 9: Diff checkpoint**

Run:

```powershell
git diff --stat
git diff -- apps/mobile/src/features/inspection/presentation/catalog/pages/InspectionCatalogPage.vue apps/mobile/src/features/inspection/data/service-flow/uni-booking-http-client.ts
```

Expected: catalog city integration only; portal, home, weather, detail, and booking files unchanged.

---

### Task 7: Full Verification and Visual Fidelity

**Files:**
- Modify: `design-qa.md`
- Create: `artifacts/inspection-city-picker/` screenshots and comparison evidence.

**Interfaces:**
- Produces final build/test evidence.
- Does not deploy, sync HBuilder, or occupy a real device without coordination.

- [ ] **Step 1: Run all mobile tests**

Run:

```powershell
npm test
npm run type-check
npm run build:h5
```

Working directory: `apps/mobile`.

Expected: all tests pass, TypeScript reports no errors, production H5 build succeeds.

- [ ] **Step 2: Run all backend tests**

Run:

```powershell
mvn test
```

Working directory: repository root.

Expected: all Maven modules pass.

- [ ] **Step 3: Verify production/development data separation**

Run:

```powershell
rg -n \"1260|1120|980|760|690|610\" backend/modules/inspection/migrations
rg -n \"1260|1120|980|760|690|610\" backend/bootstrap/src/main/resources/db/development/inspection
```

Expected: first command returns no matches; second returns only `R__dev_platform_city_popularity.sql`.

- [ ] **Step 4: Inspect complete diff**

Run:

```powershell
git status --short
git diff --stat
git diff --check
git diff
```

Expected: no files from other conversations, no broad formatting, no renames, no deletes.

- [ ] **Step 5: Start an isolated local preview**

Use ports that do not overlap the existing integration preview:

```powershell
$env:VITE_INSPECTION_API_BASE_URL='http://127.0.0.1:18083'
npm run dev:h5 -- --host 127.0.0.1 --port 4178
```

Working directory: `apps/mobile`.

The backend for this worktree uses port `18083`. Confirm `/actuator/health` and `/api/v1/platform/cities` return `200` before visual inspection.

- [ ] **Step 6: Obtain explicit browser choice before browser automation**

Because Product Design may only use the user's chosen browser, ask the user to explicitly choose the in-app browser before opening or automating the preview. Do not treat ambient browser state as consent.

- [ ] **Step 7: Capture reference-matched states**

At 390 CSS pixels wide, capture:

1. Default sheet with current/recent/hot/more sections.
2. 山东省 expanded with cities sorted.
3. Search “济南” with one result.
4. Empty search result.
5. Directory load error and retry.

Save under `artifacts/inspection-city-picker/`.

- [ ] **Step 8: Compare reference and implementation**

Place the user reference and implementation screenshot side-by-side and check:

- sheet top position and 24px top radius;
- drag handle, centered title, close icon;
- search height and border;
- vertical section rhythm;
- 3-column hot city grid;
- orange selected state;
- province dividers and chevrons;
- safe-area footer;
- no overflow at 375, 390, or 430 widths.

Fix only visible city-picker mismatches and rerun the focused tests after every visual patch.

- [ ] **Step 9: Record QA evidence**

Append to `design-qa.md`:

```markdown
## Inspection city picker

- Reference: user-provided nationwide city selection sheet.
- Viewports: 375 / 390 / 430 CSS px.
- Production data: nationwide reference rows, search_count defaults to 0.
- Development-only popularity: verified isolated repeatable seed.
- Interaction: search, current, recent, hot, province accordion, selection reload.
- Browser: explicitly selected by user before automation.
- Android/iOS: not claimed unless real-device evidence is captured.
```

- [ ] **Step 10: Final diff checkpoint**

Run:

```powershell
git status --short
git diff --stat
git diff --check
```

Expected: clean diff checks and only approved city-picker files.

---

## Self-Review

- Spec coverage: UI hierarchy, nationwide tree, pinyin search/sort, current location, recent cities, hot ranking, development-only fake counts, production zero counts, city refresh, errors, security, and isolation are each assigned to a task.
- Placeholder scan: no `TBD`, `TODO`, “implement later”, or unbounded error-handling steps.
- Type consistency:
  - Backend uses `DirectoryView`, `ProvinceView`, and `CityView`.
  - Frontend uses `CityDirectory`, `PlatformProvince`, and `PlatformCity`.
  - Selection source values are exactly `CURRENT`, `RECENT`, `HOT`, `SEARCH`, and `TREE`.
  - City popularity is updated only by `POST /api/v1/platform/cities/{cityCode}/search-selections`.
- Migration consistency: this plan uses `V006`, leaving the other task's uncommitted `V004` and `V005` untouched.
- Dependency review: no runtime dependency is introduced; one-time pinyin generation is pinned and generated output is reviewed.
