const path = require('node:path');
const fs = require('node:fs');
const { execFileSync } = require('node:child_process');
(async () => {
  const { packager } = await import('@electron/packager');
  const root = path.join(__dirname, '..');
  const desktopVersion = require('../package.json').version;
  const androidGradle = fs.readFileSync(path.join(root, 'android/app/build.gradle'), 'utf8');
  const mobileVersion = androidGradle.match(/versionName\s+'([\d.]+)'/)?.[1];
  if (!mobileVersion) throw new Error('Missing Android versionName');
  const desktopName = `easy-exam-Desktop-v${desktopVersion}-Windows-x64`;
  const mobileName = `easy-exam-Mobile-v${mobileVersion}-Android.apk`;
  const desktopDir = path.join(root, 'release', desktopName);
  if (fs.existsSync(desktopDir)) throw new Error(`Existing release: ${desktopDir}. Move it before packaging a replacement.`);
  const zipPath = `${desktopDir}.zip`;
  if (fs.existsSync(zipPath)) throw new Error(`Existing archive: ${zipPath}`);
  const archive = path.join(root, '.toolchain/electron-v44.9.0-win32-x64.zip');
  if (!fs.existsSync(archive)) throw new Error('Run npm install to obtain the verified Electron runtime');
  const outputs = await packager({ dir: root, name: 'easy-exam-Desktop', executableName: 'easy-exam-Desktop', platform: 'win32', arch: 'x64',
    electronVersion: '44.9.0', electronZipDir: path.join(root, '.toolchain'), out: path.join(root, 'release'), overwrite: true, asar: { unpackDir: 'bin' }, prune: true,
    icon: path.join(root, 'assets/app.ico'),
    ignore: [/^\/(android|native|test|scripts|release|docs|\.toolchain|\.private)(\/|$)/, /^\/smoke-/, /^\/\.git/],
    win32metadata: { CompanyName: 'Local Tools', FileDescription: 'easy exam Screenshot Companion', ProductName: 'easy exam' }
  });
  // Keep the official runtime byte-for-byte; executable resource editing changes its identity.
  fs.copyFileSync(path.join(root, 'node_modules/electron/dist/electron.exe'), path.join(outputs[0], 'easy-exam-Desktop.exe'));
  fs.renameSync(outputs[0], desktopDir);
  const apkSource = path.join(root, 'android/app/build/outputs/apk/debug/app-debug.apk');
  const mobileRelease = path.join(root, 'release', mobileName);
  if (!fs.existsSync(mobileRelease)) fs.copyFileSync(apkSource, mobileRelease);
  const literal = value => `'${value.replaceAll("'", "''")}'`;
  execFileSync('powershell.exe', ['-NoProfile', '-Command', `Add-Type -AssemblyName System.IO.Compression.FileSystem\n[IO.Compression.ZipFile]::CreateFromDirectory(${literal(desktopDir)}, ${literal(zipPath)}, [IO.Compression.CompressionLevel]::Optimal, $true)`], { stdio: 'inherit', windowsHide: true });
  fs.writeFileSync(path.join(root, 'release/README.md'), `# easy exam\n\n电脑端：${desktopVersion}；安卓端：${mobileVersion}。\n\n- 电脑启动：\`${desktopName}/easy-exam-Desktop.exe\`。请保留整个目录。\n- 手机安装：\`${mobileName}\`，覆盖安装以保留记录。\n- 电脑便携压缩包：\`${desktopName}.zip\`，解压后运行。\n\n普通截图：**Alt + S**。长截屏：**快速按下并松开 Ctrl 两次**开始，手动缓慢向下滚动，再次双击 Ctrl 完成并发送。Ctrl 组合键和长按不会触发。\n\n手机“记录”页点击“继续作答”恢复整场笔试；回看时可对指定题目点击“继续解答／追问这道题”。旧题图及答案保留，新解答追加保存。\n\n安卓包包含个人模型预配置，仅供本人使用。\n`, 'utf8');
  console.log(desktopDir);
  console.log(path.join(root, 'release', mobileName));
})().catch(e => { console.error(e); process.exit(1); });


