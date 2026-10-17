import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((item) =>
    item.isDirectory() ? walk(join(dir, item.name)) : [join(dir, item.name)],
  );
}
const sources = walk('src').filter(
  (path) => /\.(ts|html|scss)$/.test(path) && !path.endsWith('.spec.ts'),
);
const failures = [];
for (const file of sources) {
  const text = readFileSync(file, 'utf8');
  if (
    /\blocalStorage\b/.test(text) &&
    file.replaceAll('\\', '/') !== 'src/app/core/preferences/sidebar-preference.ts'
  )
    failures.push(`${file}: visual storage is only allowed in the preference adapter`);
  for (const pattern of [
    /\b(?:sessionStorage|indexedDB)\b/,
    /console\.(?:log|debug|info|warn|error)\s*\(/,
    /\b(?:fetch|XMLHttpRequest)\s*\(/,
    /\[innerHTML\]/,
    /bypassSecurityTrust/,
  ])
    if (pattern.test(text)) failures.push(`${file}: forbidden storage, logging or bypass API`);
}
for (const file of [
  'src/environments/environment.ts',
  'src/environments/environment.dev.ts',
  'src/environments/environment.development.ts',
]) {
  if (!readFileSync(file, 'utf8').includes('localPreview: false'))
    failures.push(`${file}: preview must be off`);
}
if (failures.length) {
  process.stderr.write(failures.join('\n') + '\n');
  process.exit(1);
}
console.log(
  `PASS: ${sources.length} source files; only the isolated visual preference adapter may access localStorage; no token storage, console logging, raw network or HTML bypass; non-preview configs disabled.`,
);
