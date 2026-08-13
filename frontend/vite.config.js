import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { sentryVitePlugin } from '@sentry/vite-plugin'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const plugins = [react()]

  // 소스맵 업로드(선택) - authToken/org/project 셋 다 있을 때만 활성화, 없으면 조용히 건너뜀
  const sentryEnabled = env.SENTRY_AUTH_TOKEN && env.SENTRY_ORG && env.SENTRY_PROJECT
  if (sentryEnabled) {
    plugins.push(sentryVitePlugin({
      org: env.SENTRY_ORG,
      project: env.SENTRY_PROJECT,
      authToken: env.SENTRY_AUTH_TOKEN,
    }))
  }

  return {
    plugins,
    server: {
      proxy: {
        '/api': 'http://localhost:8080',
      },
    },
    build: {
      outDir: '../backend/src/main/resources/static',
      emptyOutDir: true,
      sourcemap: Boolean(sentryEnabled),
    },
  }
})
