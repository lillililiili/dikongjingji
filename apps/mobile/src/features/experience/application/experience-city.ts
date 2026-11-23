// The city an online experience page reads products and venues in: the App's current city,
// otherwise the build's default city. Online experiences are the same in every city; only the
// product card and 到馆体验 depend on it.
export function experienceCityCode(
  currentCity: { code: string } | null | undefined,
  defaultCode: string | undefined = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE,
): string {
  return currentCity?.code || defaultCode?.trim() || ''
}
