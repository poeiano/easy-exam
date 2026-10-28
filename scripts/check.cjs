const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
for (const file of ['src/main.cjs', 'src/preload.cjs', 'src/relay.cjs', 'src/shortcuts.cjs', 'src/long-capture.cjs', 'ui/app.js', 'scripts/package.cjs', 'test/relay.test.cjs', 'test/long-capture.test.cjs']) {
  const r = spawnSync(process.execPath, ['--check', file], { stdio: 'inherit' }); if (r.status) process.exit(r.status);
}
for (const file of ['assets/tray.png', 'ui/index.html', 'android/app/src/main/AndroidManifest.xml']) if (!fs.existsSync(file)) throw new Error(`Missing ${file}`);
console.log('JavaScript syntax and required resources OK');
