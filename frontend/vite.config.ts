import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const sourceRoot = fileURLToPath(new URL('./src', import.meta.url))
const productionNoop = fileURLToPath(
  new URL('./src/modules/foundation/api/production-noop.ts', import.meta.url),
)
const runtimeCommit = process.env.VITE_RUNTIME_COMMIT ?? 'UNKNOWN'
const devPort = Number.parseInt(process.env.VITE_DEV_PORT ?? '5173', 10)
const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://127.0.0.1:8000'
const apiProxyOrigin = new URL(apiProxyTarget).origin

function runtimeCommitPlugin() {
  return {
    name: 'aiexam-runtime-commit',
    configureServer(server: {
      middlewares: {
        use: (
          path: string,
          handler: (_request: unknown, response: {
            statusCode: number
            setHeader: (name: string, value: string) => void
            end: (body: string) => void
          }) => void,
        ) => void
      }
    }) {
      server.middlewares.use('/__runtime/commit', (_request, response) => {
        response.statusCode = 200
        response.setHeader('Content-Type', 'application/json; charset=utf-8')
        response.end(JSON.stringify({ commit: runtimeCommit }))
      })
    },
  }
}

export default defineConfig(({ command }) => ({
  plugins: [vue(), runtimeCommitPlugin()],
  resolve: {
    alias: [
      ...(command === 'build'
        ? [
            {
              find: '@/modules/foundation/api/dev-auth-challenge',
              replacement: productionNoop,
            },
            {
              find: '@/modules/foundation/views/DevVerificationCodePanel.vue',
              replacement: productionNoop,
            },
          ]
        : []),
      { find: '@', replacement: sourceRoot },
    ],
  },
  server: {
    port: devPort,
    strictPort: true,
    allowedHosts: ['aisoft-exam.cpolar.cn'],
    proxy: {
      '/api': {
        target: apiProxyTarget,
        // The backend validates the browser origin against the proxied host.
        // Rewrite both headers so cookie-authenticated mutations pass that
        // boundary in local development without weakening backend CSRF rules.
        changeOrigin: true,
        timeout: 180_000,
        proxyTimeout: 180_000,
        headers: {
          origin: apiProxyOrigin,
          // The browser may reach Vite through HTTPS (for example via cpolar),
          // but the internal proxy hop to the backend is plain HTTP. Keep the
          // forwarded protocol aligned with the rewritten Origin header.
          'x-forwarded-proto': 'http',
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    css: true,
    include: ['src/**/*.spec.ts'],
  },
}))
