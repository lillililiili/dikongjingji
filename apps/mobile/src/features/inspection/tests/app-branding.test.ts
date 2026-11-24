import { describe, expect, it } from 'vitest'

import manifestSource from '../../../manifest.json?raw'

interface AppManifest {
  name: string
  h5?: {
    title?: string
  }
  'app-plus': {
    modules?: Record<string, unknown>
    distribute?: {
      icons?: {
        android?: Record<string, string>
      }
    }
  }
}

const manifest = JSON.parse(manifestSource) as AppManifest

describe('app branding', () => {
  it('uses 飞享 as the installed app name and H5 title', () => {
    expect(manifest.name).toBe('飞享')
    expect(manifest.h5?.title).toBe('飞享')
  })

  it('declares the supplied 飞享 artwork for every supported Android density', () => {
    expect(manifest['app-plus'].distribute?.icons?.android).toEqual({
      hdpi: 'src/static/app-icons/feixiang-hdpi.png',
      xhdpi: 'src/static/app-icons/feixiang-xhdpi.png',
      xxhdpi: 'src/static/app-icons/feixiang-xxhdpi.png',
      xxxhdpi: 'src/static/app-icons/feixiang-xxxhdpi.png',
    })
  })

  it('packs the barcode module that uni.scanCode needs (扫一扫 and 工作模式核销)', () => {
    expect(manifest['app-plus'].modules).toHaveProperty('Barcode')
    expect(manifest['app-plus'].modules).toHaveProperty('Camera')
  })
})
