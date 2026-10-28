const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { spawnSync } = require('node:child_process');
const root = path.join(__dirname, '..');
const electronRoot = path.join(root, 'node_modules/electron');
const version = require(path.join(electronRoot, 'package.json')).version;
if (process.platform !== 'win32' || process.arch !== 'x64') {
  const r = spawnSync(process.execPath, [path.join(electronRoot, 'install.js')], { stdio: 'inherit' }); process.exit(r.status || 0);
}
const filename = `electron-v${version}-win32-x64.zip`;
const expected = require(path.join(electronRoot, 'checksums.json'))[filename];
const tools = path.join(root, '.toolchain'); fs.mkdirSync(tools, { recursive: true });
const archive = path.join(tools, filename);
function hash(file) { return createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }
if (!fs.existsSync(archive) || hash(archive) !== expected) {
  const r = spawnSync('curl.exe', ['--fail', '--location', '--retry', '4', '--retry-all-errors', '--continue-at', '-', '--output', archive, `https://github.com/electron/electron/releases/download/v${version}/${filename}`], { stdio: 'inherit', windowsHide: true });
  if (r.status !== 0) throw new Error('Official Electron download failed; rerun npm install to resume');
}
if (hash(archive) !== expected) throw new Error('Electron SHA256 mismatch');
const executable = path.join(electronRoot, 'dist/electron.exe');
if (!fs.existsSync(executable)) {
  const r = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(__dirname, 'extract-electron.ps1'), '-Archive', archive, '-Destination', path.join(electronRoot, 'dist')], { stdio: 'inherit', windowsHide: true });
  if (r.status !== 0) throw new Error('Electron extraction failed');
}
fs.writeFileSync(path.join(electronRoot, 'path.txt'), 'electron.exe');
console.log(`Official Electron ${version} runtime checksum verified`);
