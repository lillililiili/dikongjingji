// The city a live page reads the room in: the one in its address, otherwise (opened from a
// notification) the App's current city, otherwise the build's default city.
export function liveCityCode(
  fromAddress: string,
  currentCity: { code: string } | null | undefined,
  defaultCode: string | undefined = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE,
): string {
  return fromAddress || currentCity?.code || defaultCode?.trim() || ''
}
