import { createFakeMapPlaceProvider } from './fake-map-place-provider'

// The one map adapter of the App. Only the local fake exists until a map service is chosen
// (概要设计 §14, plan 08 §5 地图); a real one is made here behind the same port.
export const mapPlaceProvider = createFakeMapPlaceProvider()
