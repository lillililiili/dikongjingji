// vite.config.ts runs in Node, but the App's tsconfig has no Node types (they would change
// setTimeout and friends for the App code). Only what the build config uses is declared here.
declare module 'node:fs' {
  export function rmSync(path: string, options?: { recursive?: boolean; force?: boolean }): void
}

declare module 'node:path' {
  export function resolve(...paths: string[]): string
}
