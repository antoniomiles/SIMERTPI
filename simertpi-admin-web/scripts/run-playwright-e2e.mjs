import { spawn, spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const servers = [
  { port: 4300, configuration: 'preview' },
  { port: 4301, configuration: 'production' },
];
const children = [];

function startServer({ port, configuration }) {
  const child = spawn(
    process.execPath,
    [
      'node_modules/@angular/cli/bin/ng.js',
      'serve',
      '--host',
      '127.0.0.1',
      '--port',
      String(port),
      '--configuration',
      configuration,
    ],
    { cwd: process.cwd(), env: process.env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true },
  );
  children.push(child);
  child.stdout.on('data', (chunk) => process.stdout.write(`[E2E ${port}] ${chunk}`));
  child.stderr.on('data', (chunk) => process.stderr.write(`[E2E ${port}] ${chunk}`));
  return child;
}

async function waitUntilReady(child, port) {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null)
      throw new Error(`Angular E2E server on ${port} exited with code ${child.exitCode}`);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/`);
      if (response.ok) return;
    } catch {
      // The server is still compiling or binding its local port.
    }
    await delay(500);
  }
  throw new Error(`Timed out waiting for Angular E2E server on ${port}`);
}

async function stopServer(child) {
  if (child.exitCode !== null || child.killed) return;
  if (process.platform === 'win32' && child.pid) {
    const result = spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], {
      encoding: 'utf8',
      windowsHide: true,
      timeout: 10_000,
    });
    if (result.error || (result.status !== 0 && child.exitCode === null)) {
      process.stderr.write(
        `Could not stop the complete E2E server process tree cleanly; applying direct process termination.\n`,
      );
      child.kill();
    }
  } else {
    child.kill('SIGTERM');
  }
  await Promise.race([new Promise((resolve) => child.once('close', resolve)), delay(10_000)]);
  if (child.exitCode === null && child.pid) {
    if (process.platform === 'win32') {
      spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], {
        windowsHide: true,
        timeout: 10_000,
      });
    } else {
      child.kill('SIGKILL');
    }
  }
}

let exitCode = 1;
try {
  for (const server of servers) {
    const child = startServer(server);
    await waitUntilReady(child, server.port);
  }
  const playwright = spawn(
    process.execPath,
    ['node_modules/@playwright/test/cli.js', 'test', ...process.argv.slice(2)],
    { cwd: process.cwd(), env: process.env, stdio: 'inherit', windowsHide: true },
  );
  exitCode = await new Promise((resolve, reject) => {
    playwright.once('error', reject);
    playwright.once('close', (code) => resolve(code ?? 1));
  });
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : 'E2E runner failed'}\n`);
  exitCode = 1;
} finally {
  for (const child of children.reverse()) await stopServer(child);
}

process.exitCode = exitCode;
