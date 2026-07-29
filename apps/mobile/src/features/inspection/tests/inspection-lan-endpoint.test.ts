import { afterEach, describe, expect, it, vi } from 'vitest'

import * as lanEndpoint from '../data/service-flow/inspection-lan-endpoint'
import {
  INSPECTION_DISCOVERY_CONTRACT,
  INSPECTION_DISCOVERY_SERVICE,
  createInspectionLanEndpointResolver,
  ipv4FromLittleEndianInt,
} from '../data/service-flow/inspection-lan-endpoint'
import { inspectionAuthApi } from '../data/service-flow/uni-booking-http-client'
import appSource from '../../../App.vue?raw'
import manifestSource from '../../../manifest.json?raw'

const discoveryResponse = {
  service: INSPECTION_DISCOVERY_SERVICE,
  contract: INSPECTION_DISCOVERY_CONTRACT,
}

describe('inspection LAN endpoint resolver', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
  })

  it('discovers the inspection backend on the Android Wi-Fi /24 subnet and persists it', async () => {
    const probed: string[] = []
    const saved: string[] = []
    const promptForHost = vi.fn(async () => null)
    const resolver = createInspectionLanEndpointResolver({
      enabled: true,
      configuredBaseUrl: 'http://192.168.3.32:8080',
      getPrivateIpv4Candidates: () => ['192.168.10.23'],
      loadLastKnownBaseUrl: () => null,
      saveLastKnownBaseUrl: (baseUrl) => saved.push(baseUrl),
      promptForHost,
      probeEndpoint: async (baseUrl) => {
        probed.push(baseUrl)
        return baseUrl === 'http://192.168.10.7:8080'
          ? discoveryResponse
          : null
      },
    })

    await expect(resolver.resolve()).resolves.toBe('http://192.168.10.7:8080')
    expect(saved).toEqual(['http://192.168.10.7:8080'])
    expect(promptForHost).not.toHaveBeenCalled()
    expect(probed).toContain('http://192.168.10.7:8080')
    expect(probed).not.toContain('http://192.168.10.23:8080')
    expect(probed).not.toContain('http://192.168.10.0:8080')
    expect(probed).not.toContain('http://192.168.10.255:8080')
  })

  it('rejects a non-inspection HTTP response and then accepts a private manual IP', async () => {
    const probed: string[] = []
    let probesBeforePrompt = 0
    const resolver = createInspectionLanEndpointResolver({
      enabled: true,
      configuredBaseUrl: 'http://192.168.3.32:8080',
      getPrivateIpv4Candidates: () => ['192.168.10.23'],
      loadLastKnownBaseUrl: () => null,
      saveLastKnownBaseUrl: vi.fn(),
      promptForHost: async () => {
        probesBeforePrompt = probed.length
        return ' 192.168.50.7 '
      },
      probeEndpoint: async (baseUrl) => {
        probed.push(baseUrl)
        if (baseUrl === 'http://192.168.10.7:8080') {
          return { service: 'another-service', contract: INSPECTION_DISCOVERY_CONTRACT }
        }
        return baseUrl === 'http://192.168.50.7:8080'
          ? discoveryResponse
          : null
      },
    })

    await expect(resolver.resolve()).resolves.toBe('http://192.168.50.7:8080')
    expect(probesBeforePrompt).toBeGreaterThan(0)
    expect(probed.at(-1)).toBe('http://192.168.50.7:8080')
  })

  it('uses the configured private endpoint only as the final fallback', async () => {
    const probeEndpoint = vi.fn(async () => null)
    const resolver = createInspectionLanEndpointResolver({
      enabled: true,
      configuredBaseUrl: 'http://192.168.3.32:8080',
      getPrivateIpv4Candidates: () => [],
      loadLastKnownBaseUrl: () => 'http://192.168.9.9:8080',
      saveLastKnownBaseUrl: vi.fn(),
      promptForHost: async () => '8.8.8.8',
      probeEndpoint,
    })

    await expect(resolver.resolve()).resolves.toBe('http://192.168.3.32:8080')
    expect(probeEndpoint).toHaveBeenCalledWith('http://192.168.9.9:8080')
    expect(probeEndpoint).not.toHaveBeenCalledWith('http://8.8.8.8:8080')
  })

  it.each([
    { enabled: false, configuredBaseUrl: 'http://192.168.3.32:8080' },
    { enabled: true, configuredBaseUrl: 'https://api.example.com' },
  ])('does not scan outside an explicitly enabled private-LAN acceptance build: $configuredBaseUrl', async (input) => {
    const getPrivateIpv4Candidates = vi.fn(() => ['192.168.10.23'])
    const probeEndpoint = vi.fn(async () => discoveryResponse)
    const promptForHost = vi.fn(async () => '192.168.10.7')
    const resolver = createInspectionLanEndpointResolver({
      ...input,
      getPrivateIpv4Candidates,
      loadLastKnownBaseUrl: () => null,
      saveLastKnownBaseUrl: vi.fn(),
      promptForHost,
      probeEndpoint,
    })

    await expect(resolver.resolve()).resolves.toBe(input.configuredBaseUrl)
    expect(getPrivateIpv4Candidates).not.toHaveBeenCalled()
    expect(probeEndpoint).not.toHaveBeenCalled()
    expect(promptForHost).not.toHaveBeenCalled()
  })

  it('converts Android WifiInfo little-endian addresses to IPv4', () => {
    expect(ipv4FromLittleEndianInt(0x170aa8c0)).toBe('192.168.10.23')
    expect(ipv4FromLittleEndianInt(0)).toBeNull()
  })
  it('routes the first real request through the resolved endpoint without replaying it', async () => {
    const requests: Array<Record<string, unknown>> = []
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', 'http://192.168.3.32:8080')
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LAN_API', 'true')
    vi.spyOn(lanEndpoint, 'resolveInspectionApiBaseUrl')
      .mockResolvedValue('http://192.168.10.7:8080')
    vi.stubGlobal('uni', {
      request: (options: {
        success: (response: {
          statusCode: number
          data: unknown
          header: Record<string, string>
        }) => void
      } & Record<string, unknown>) => {
        requests.push(options)
        options.success({
          statusCode: 200,
          data: {
            accessToken: 'real-jwt',
            tokenType: 'Bearer',
            expiresInSeconds: 900,
          },
          header: {},
        })
      },
    })

    await inspectionAuthApi.login('customer', 'secret')

    expect(requests).toHaveLength(1)
    expect(requests[0]).toMatchObject({
      url: 'http://192.168.10.7:8080/api/v1/auth/login',
      method: 'POST',
    })
  })

  it('warms discovery on app launch and declares only Android network-state permissions', () => {
    expect(appSource).toContain('onLaunch(')
    expect(appSource).toContain('warmUpInspectionLanEndpoint()')
    expect(manifestSource).toContain('android.permission.ACCESS_WIFI_STATE')
    expect(manifestSource).toContain('android.permission.ACCESS_NETWORK_STATE')
    expect(manifestSource).not.toContain('CHANGE_WIFI_STATE')
  })
})
