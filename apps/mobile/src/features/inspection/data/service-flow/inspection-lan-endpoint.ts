export const INSPECTION_DISCOVERY_SERVICE = 'low-altitude-inspection'
export const INSPECTION_DISCOVERY_CONTRACT = 'inspection-lan-discovery-v1'

interface InspectionDiscoveryResponse {
  service?: unknown
  contract?: unknown
}

interface InspectionLanEndpointResolverOptions {
  enabled: boolean
  configuredBaseUrl: string
  getPrivateIpv4Candidates: () => string[] | Promise<string[]>
  loadLastKnownBaseUrl: () => string | null
  saveLastKnownBaseUrl: (baseUrl: string) => void
  promptForHost: () => Promise<string | null>
  probeEndpoint: (baseUrl: string) => Promise<InspectionDiscoveryResponse | null>
}

interface InspectionLanEndpointResolver {
  resolve: () => Promise<string>
}

const PROBE_CONCURRENCY = 24

export function createInspectionLanEndpointResolver(
  options: InspectionLanEndpointResolverOptions,
): InspectionLanEndpointResolver {
  let pendingResolution: Promise<string> | null = null

  return {
    resolve(): Promise<string> {
      pendingResolution ??= resolveInspectionLanEndpoint(options)
      return pendingResolution
    },
  }
}

async function resolveInspectionLanEndpoint(
  options: InspectionLanEndpointResolverOptions,
): Promise<string> {
  const configured = parsePrivateHttpBaseUrl(options.configuredBaseUrl)
  if (!options.enabled || !configured) return options.configuredBaseUrl

  const automaticCandidates: string[] = []
  const lastKnown = options.loadLastKnownBaseUrl()
  if (lastKnown && parsePrivateHttpBaseUrl(lastKnown)) automaticCandidates.push(lastKnown)

  const localAddresses = await options.getPrivateIpv4Candidates()
  automaticCandidates.push(...buildSubnetCandidates(localAddresses, configured.port))

  const discovered = await findInspectionEndpoint(
    [...new Set(automaticCandidates)],
    options.probeEndpoint,
  )
  if (discovered) {
    options.saveLastKnownBaseUrl(discovered)
    return discovered
  }

  const manualHost = await options.promptForHost()
  const manualBaseUrl = manualHost
    ? privateHostToBaseUrl(manualHost, configured.port)
    : null
  if (manualBaseUrl) {
    const response = await safeProbe(manualBaseUrl, options.probeEndpoint)
    if (isInspectionDiscoveryResponse(response)) {
      options.saveLastKnownBaseUrl(manualBaseUrl)
      return manualBaseUrl
    }
  }

  return options.configuredBaseUrl
}

async function findInspectionEndpoint(
  candidates: string[],
  probeEndpoint: InspectionLanEndpointResolverOptions['probeEndpoint'],
): Promise<string | null> {
  let cursor = 0
  let discovered: string | null = null

  async function worker(): Promise<void> {
    while (!discovered && cursor < candidates.length) {
      const candidate = candidates[cursor]
      cursor += 1
      const response = await safeProbe(candidate, probeEndpoint)
      if (isInspectionDiscoveryResponse(response)) discovered = candidate
    }
  }

  const workerCount = Math.min(PROBE_CONCURRENCY, candidates.length)
  await Promise.all(Array.from({ length: workerCount }, () => worker()))
  return discovered
}

async function safeProbe(
  baseUrl: string,
  probeEndpoint: InspectionLanEndpointResolverOptions['probeEndpoint'],
): Promise<InspectionDiscoveryResponse | null> {
  try {
    return await probeEndpoint(baseUrl)
  } catch {
    return null
  }
}

function isInspectionDiscoveryResponse(
  response: InspectionDiscoveryResponse | null,
): boolean {
  return response?.service === INSPECTION_DISCOVERY_SERVICE
    && response.contract === INSPECTION_DISCOVERY_CONTRACT
}

function buildSubnetCandidates(localAddresses: string[], port: number): string[] {
  const candidates: string[] = []
  const seenSubnets = new Set<string>()

  for (const address of localAddresses) {
    if (!isPrivateLanIpv4(address)) continue
    const octets = address.split('.')
    const subnet = `${octets[0]}.${octets[1]}.${octets[2]}`
    if (seenSubnets.has(subnet)) continue
    seenSubnets.add(subnet)

    for (let host = 1; host <= 254; host += 1) {
      const candidate = `${subnet}.${host}`
      if (candidate !== address) candidates.push(`http://${candidate}:${port}`)
    }
  }

  return candidates
}

function privateHostToBaseUrl(value: string, port: number): string | null {
  const host = value.trim()
  return isPrivateLanIpv4(host) ? `http://${host}:${port}` : null
}

function parsePrivateHttpBaseUrl(
  value: string,
): { hostname: string; port: number } | null {
  const match = /^http:\/\/([^/:?#]+)(?::(\d+))?\/?$/i.exec(value.trim())
  if (!match || !isPrivateLanIpv4(match[1])) return null
  const port = match[2] ? Number(match[2]) : 80
  return port >= 1 && port <= 65_535 ? { hostname: match[1], port } : null
}

function isPrivateLanIpv4(hostname: string): boolean {
  const octets = hostname.split('.')
  if (octets.length !== 4 || octets.some((octet) => !/^\d{1,3}$/.test(octet)
    || (octet.length > 1 && octet.startsWith('0')))) return false

  const [first, second, third, fourth] = octets.map(Number)
  if ([first, second, third, fourth].some((octet) => octet > 255)) return false
  return first === 10
    || (first === 172 && second >= 16 && second <= 31)
    || (first === 192 && second === 168)
}

export function ipv4FromLittleEndianInt(value: number): string | null {
  if (!Number.isInteger(value) || value === 0) return null
  const unsigned = value >>> 0
  return [
    unsigned & 0xff,
    (unsigned >>> 8) & 0xff,
    (unsigned >>> 16) & 0xff,
    (unsigned >>> 24) & 0xff,
  ].join('.')
}
const LAST_KNOWN_ENDPOINT_STORAGE_KEY = 'inspection.lanApiBaseUrl'
const DISCOVERY_PATH = '/api/v1/inspection/discovery'
const DISCOVERY_TIMEOUT_MS = 650

let runtimeResolver: {
  cacheKey: string
  resolver: InspectionLanEndpointResolver
} | null = null

export function resolveInspectionApiBaseUrl(configuredBaseUrl: string): Promise<string> {
  return getRuntimeResolver(configuredBaseUrl).resolve()
}

export function warmUpInspectionLanEndpoint(): void {
  const configuredBaseUrl = import.meta.env.VITE_INSPECTION_API_BASE_URL?.trim()
  if (!configuredBaseUrl) return
  // App launch starts discovery early; real requests still await the same cached promise.
  void resolveInspectionApiBaseUrl(configuredBaseUrl).catch(() => undefined)
}

function getRuntimeResolver(configuredBaseUrl: string): InspectionLanEndpointResolver {
  const enabled = import.meta.env.VITE_INSPECTION_ENABLE_LAN_DISCOVERY === 'true'
    && import.meta.env.VITE_INSPECTION_ALLOW_INSECURE_LAN_API === 'true'
  const cacheKey = `${enabled}:${configuredBaseUrl}`
  if (runtimeResolver?.cacheKey === cacheKey) return runtimeResolver.resolver

  const resolver = createInspectionLanEndpointResolver({
    enabled,
    configuredBaseUrl,
    getPrivateIpv4Candidates: readAndroidPrivateIpv4Candidates,
    loadLastKnownBaseUrl: loadLastKnownBaseUrl,
    saveLastKnownBaseUrl: saveLastKnownBaseUrl,
    promptForHost: promptForManualHost,
    probeEndpoint: probeInspectionEndpoint,
  })
  runtimeResolver = { cacheKey, resolver }
  return resolver
}

function readAndroidPrivateIpv4Candidates(): string[] {
  if (typeof plus === 'undefined' || !plus.android) return []
  try {
    const activity = plus.android.runtimeMainActivity()
    const wifiManager = plus.android.invoke(activity, 'getSystemService', 'wifi')
    const wifiInfo = plus.android.invoke(wifiManager, 'getConnectionInfo')
    const numericAddress = Number(plus.android.invoke(wifiInfo, 'getIpAddress'))
    const address = ipv4FromLittleEndianInt(numericAddress)
    return address && isPrivateLanIpv4(address) ? [address] : []
  } catch {
    return []
  }
}

function loadLastKnownBaseUrl(): string | null {
  const stored = uni.getStorageSync(LAST_KNOWN_ENDPOINT_STORAGE_KEY)
  return typeof stored === 'string' && stored.trim() ? stored.trim() : null
}

function saveLastKnownBaseUrl(baseUrl: string): void {
  uni.setStorageSync(LAST_KNOWN_ENDPOINT_STORAGE_KEY, baseUrl)
}

function promptForManualHost(): Promise<string | null> {
  return new Promise((resolve) => {
    uni.showModal({
      title: '连接巡检验收服务',
      content: '未自动找到同一 Wi-Fi 下的电脑，请输入电脑 IPv4 地址。',
      editable: true,
      placeholderText: '例如 192.168.10.7',
      confirmText: '连接',
      cancelText: '使用原地址',
      success: (result) => {
        const content = (result as { confirm?: boolean; content?: unknown }).content
        resolve(result.confirm && typeof content === 'string' ? content : null)
      },
      fail: () => resolve(null),
    })
  })
}

function probeInspectionEndpoint(
  baseUrl: string,
): Promise<InspectionDiscoveryResponse | null> {
  return new Promise((resolve) => {
    uni.request({
      url: `${baseUrl}${DISCOVERY_PATH}`,
      method: 'GET',
      timeout: DISCOVERY_TIMEOUT_MS,
      success: (response) => resolve(
        response.statusCode === 200
          ? response.data as InspectionDiscoveryResponse
          : null,
      ),
      fail: () => resolve(null),
    })
  })
}
