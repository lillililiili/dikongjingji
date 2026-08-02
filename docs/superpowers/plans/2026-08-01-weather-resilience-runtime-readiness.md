# Weather Resilience And Runtime Readiness Implementation Plan

> **Execution note:** This plan is approved by the user's instruction “按这个来”. Execute test-first in the current worktree without staging or committing, and preserve the existing login changes.

**Goal:** Keep the home page usable when location or weather fails, retain the last successful weather across backend restarts, and make local LAN/device testing report real readiness after network or Docker changes.

**Architecture:** The mobile home route treats location and weather as optional context: it retains the current city or a configured/default city and renders the existing unknown-weather presentation instead of replacing the page with a location-permission error. The backend weather service keeps its short freshness TTL, adds a fail-open Redis snapshot store for the last successful result, and returns a stale snapshot only when the upstream provider is unavailable. A development/test PowerShell preflight checks backend health, upstream TLS from the backend container, and the weather API; the existing LAN startup invokes it and reports READY, DEGRADED, or BLOCKED. Production does not run this local script; production keeps the mobile/backend resilience and should use deployment monitoring.

**Tech Stack:** uni-app, Vue 3, TypeScript, Vitest, Java 21, Spring Boot, StringRedisTemplate, Jackson, PowerShell, Docker Compose.

---

### Task 1: Mobile location/weather degradation

**Files:**
- Modify: `apps/mobile/src/features/inspection/tests/platform-home.test.ts`
- Modify: `apps/mobile/src/pages/home/index.vue`

1. Add a failing source-contract test proving native App failures do not bypass the fallback and the generic page error does not claim location permission.
2. Run the targeted Vitest file and capture the expected failure.
3. Separate location and weather failure handling, preserve the current city when present, otherwise use the configured city or Dongying default, and render the existing unknown-weather presentation.
4. Keep genuine portal/content failures as a non-location-specific retry error.
5. Re-run the targeted test and mobile type-check.

### Task 2: Durable last-success weather cache

**Files:**
- Modify: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/platform/weather/PlatformWeatherServiceTest.java`
- Add: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/platform/weather/RedisPlatformWeatherCacheTest.java`
- Modify: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/platform/weather/PlatformWeatherService.java`
- Add: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/platform/weather/PlatformWeatherCache.java`
- Add: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/platform/weather/RedisPlatformWeatherCache.java`
- Modify: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/platform/weather/PlatformWeatherConfiguration.java`
- Modify: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/platform/weather/PlatformWeatherProperties.java`
- Modify: `backend/bootstrap/src/main/resources/application.yml`

1. Add failing service tests for reuse after a simulated process restart and stale fallback when the provider is unavailable.
2. Add a failing adapter test for Redis key isolation, JSON round-trip, retention TTL, and fail-open Redis errors.
3. Run only the weather tests and capture the expected compile/test failure.
4. Implement the cache port and Redis adapter. Keep cache read/write failures from breaking the provider path.
5. Update the service to prefer fresh cache, refresh through the provider, persist only successful results, and use stale cached data only on provider unavailability.
6. Configure a 15-minute freshness TTL and a longer last-success retention TTL without exposing the provider key.
7. Re-run weather tests and the affected backend module test suite.

### Task 3: Development/test runtime readiness check

**Files:**
- Add: `infra/scripts/verify-runtime-readiness.Tests.ps1`
- Add: `infra/scripts/verify-runtime-readiness.ps1`
- Modify: `infra/scripts/start-lan-acceptance.Tests.ps1`
- Modify: `infra/scripts/start-lan-acceptance.ps1`
- Modify: `AGENTS.md`

1. Add failing PowerShell tests for the check plan, status rules, secret-free output, and LAN startup integration.
2. Run the PowerShell tests and capture the expected failure.
3. Implement checks for backend health, AMap TLS from the backend container, and the backend weather endpoint.
4. Return READY only when all checks pass, DEGRADED when core backend works but weather does not, and BLOCKED when core backend is unavailable.
5. Invoke the check after LAN Docker startup and add the project rule requiring re-check after Wi-Fi/hotspot changes or Docker restarts.
6. Explicitly document that this script is local development/test infrastructure, not code shipped in the production App.

### Task 4: Verification and device handoff

**Files:**
- Inspect all approved files with `git diff --stat` and full `git diff`.

1. Run targeted mobile and backend tests, then full mobile tests/type-check and backend tests.
2. Build H5 and the HBuilder App resources without cloud packaging.
3. Rebuild/restart the local backend, run the readiness script, and report READY/DEGRADED/BLOCKED with evidence.
4. Sync the current HBuilder build to the connected Android device and verify that a location/weather outage shows the retained city plus unknown-weather icon rather than a white error page.
5. Confirm existing login changes remain present and no unrelated files were staged or committed.
