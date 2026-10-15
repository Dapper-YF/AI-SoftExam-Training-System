import { readdir, readFile, stat } from 'node:fs/promises'
import { extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const forbidden = [
  '/auth/dev/challenges/',
  '本机验证码',
  '开发验证码',
  'fill-register-dev-code',
  'fill-forgot-dev-code',
  'VITE_MOCK_STUDENT_PASSWORD',
  'VITE_MOCK_ADMIN_PASSWORD',
  'mock-student-',
  'mock-admin-',
  'fixture-password',
  '本地 Mock 登录未配置',
]

async function files(root) {
  const entries = await readdir(root, { withFileTypes: true })
  const nested = await Promise.all(entries.map((entry) => {
    const path = join(root, entry.name)
    return entry.isDirectory() ? files(path) : [path]
  }))
  return nested.flat()
}

const matches = []
const distRoot = fileURLToPath(new URL('../dist', import.meta.url))
for (const path of await files(distRoot)) {
  if (!['.html', '.js', '.css'].includes(extname(path))) continue
  const source = await readFile(path, 'utf8')
  for (const marker of forbidden) {
    if (source.includes(marker)) matches.push(`${path}: ${marker}`)
  }
}

const indexHtml = await readFile(join(distRoot, 'index.html'), 'utf8')
const entryScript = indexHtml.match(/<script[^>]+src="\/assets\/([^"?]+\.js)"/)?.[1]
const entryStyle = indexHtml.match(/<link[^>]+href="\/assets\/([^"?]+\.css)"/)?.[1]
if (!entryScript || !entryStyle) {
  console.error('Production bundle entry assets could not be resolved from index.html.')
  process.exit(1)
}

const entryScriptBytes = (await stat(join(distRoot, 'assets', entryScript))).size
const entryStyleBytes = (await stat(join(distRoot, 'assets', entryStyle))).size
const entryBudgets = { script: 700 * 1024, style: 250 * 1024 }
if (entryScriptBytes > entryBudgets.script || entryStyleBytes > entryBudgets.style) {
  console.error('Production entry asset budget exceeded:')
  console.error(`- JavaScript: ${(entryScriptBytes / 1024).toFixed(2)} kB / 700.00 kB`)
  console.error(`- CSS: ${(entryStyleBytes / 1024).toFixed(2)} kB / 250.00 kB`)
  process.exit(1)
}

if (matches.length) {
  console.error('Production bundle contains forbidden DEV/Mock markers:')
  for (const match of matches) console.error(`- ${match}`)
  process.exit(1)
}

console.log('Production bundle DEV/Mock marker check passed.')
console.log(`Production entry asset budget passed: JS ${(entryScriptBytes / 1024).toFixed(2)} kB, CSS ${(entryStyleBytes / 1024).toFixed(2)} kB.`)
