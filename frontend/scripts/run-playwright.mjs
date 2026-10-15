import { spawn } from 'node:child_process'
import { createServer } from 'vite'

process.env.VITE_EXAM_SIMULATOR_MOCK = 'true'
process.env.VITE_EXAM_SIMULATOR_START_DELAY_SECONDS = '1'
process.env.VITE_PLATFORM_MOCK = 'true'
process.env.VITE_ESSAY_DEV_PREVIEW = 'true'
process.env.VITE_MOCK_STUDENT_USERNAME ??= 'e2e-student'
process.env.VITE_MOCK_STUDENT_PASSWORD ??= 'e2e-student-password'
process.env.VITE_MOCK_ADMIN_USERNAME ??= 'e2e-admin'
process.env.VITE_MOCK_ADMIN_PASSWORD ??= 'e2e-admin-password'

let server

try {
  server = await createServer({
    logLevel: 'warn',
    server: {
      host: '127.0.0.1',
      port: 4173,
      strictPort: true,
    },
  })
  await server.listen()

  const playwright = spawn(
    process.execPath,
    ['./node_modules/@playwright/test/cli.js', 'test', ...process.argv.slice(2)],
    {
      stdio: 'inherit',
      env: process.env,
    },
  )

  const exitCode = await new Promise((resolve, reject) => {
    playwright.once('error', reject)
    playwright.once('exit', (code) => resolve(code ?? 1))
  })

  process.exitCode = exitCode
} catch (error) {
  console.error(error)
  process.exitCode = 1
} finally {
  await server?.close()
}
