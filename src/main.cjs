const { app, BrowserWindow, ipcMain, desktopCapturer, globalShortcut, Tray, Menu, nativeImage, screen } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { CodeDiscovery } = require('./discovery.cjs');
const { createPeer } = require('./protocol-peer.cjs');
const { Relay } = require('./relay.cjs');
const QRCode = require('qrcode');
const { isTwoKey, migrateHotkey } = require('./shortcuts.cjs');
const { clipboard } = require('electron');
const { LongCapture, ControlWatcher } = require('./long-capture.cjs');

// Keep existing settings, trusted devices and certificate across the display-name change.
const smoke = process.argv.includes('--smoke-test');
const userDataDir = smoke ? path.join(app.getPath('temp'), `easy-exam-smoke-${process.pid}`) : path.join(app.getPath('appData'), 'OfferIN Local');
fs.mkdirSync(userDataDir, { recursive: true });
app.setPath('userData', userDataDir);
app.setName('easy exam');
let win, tray, relay, discovery, captureBusy = false, captureCount = 0, lastCapture = null, quitting = false;
let settings = { screenId: '', width: 1920, quality: 85, hotkey: 'Alt+S', paused: false };
let shortcutOK = false;
let longCapture, controlWatcher;
const smokeErrors = [];
const execFileAsync = promisify(execFile);
function captureHelper() { return app.isPackaged ? path.join(process.resourcesPath, 'app.asar.unpacked/bin/ScreenCapture.exe') : path.join(__dirname, '../bin/ScreenCapture.exe'); }
if (!smoke && !app.requestSingleInstanceLock()) app.exit(0);
app.on('second-instance', () => { if (win && !win.isDestroyed()) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); } });

function settingsPath() { return path.join(app.getPath('userData'), 'screenshot-settings.json'); }
function save() { fs.writeFileSync(settingsPath(), JSON.stringify(settings, null, 2), { mode: 0o600 }); }
function notify() {
  if (win && !win.isDestroyed()) win.webContents.send('state-changed');
  if (tray && !tray.isDestroyed()) tray.setToolTip(longCapture?.active ? 'easy exam · 长截屏中，再次双击 Ctrl 完成' : 'easy exam · 笔试截图副端');
}
function status() { return { ...relay.status(), direct: relay.directStatus(), pairing: discovery.status(), settings, shortcutOK, longCapture: { ...longCapture.state, shortcutOK: !!controlWatcher?.ready, supported: process.platform === 'win32' }, captureCount, lastCapture, version: app.getVersion() }; }
function registerShortcut() {
  globalShortcut.unregisterAll();
  shortcutOK = globalShortcut.register(settings.hotkey, () => void capture({ answer: true }));
  if (process.platform === 'win32' && relay.server?.listening) controlWatcher.start();
  notify();
}
function canCapture() { return !!relay.server?.listening && !settings.paused && relay.clients.size > 0; }
function sendImage(image, { id, requestId = randomUUID(), answer = true, screenName = '当前显示器' } = {}) {
  const size = nativeImage.createFromBuffer(image).getSize();
  if (size.width < 1 || size.height < 1 || size.width > 4096 || size.height > 8192) throw new Error('截屏图像尺寸无效或超出手机支持的范围');
  if (image.length > 6 * 1024 * 1024) throw new Error('截图过大，请降低分辨率');
  if (!canCapture()) throw new Error('发送已取消：服务停止、截图暂停或手机已断开');
  const payload = { type: 'screenshot', requestId, answer, timestamp: new Date().toISOString(), mimeType: 'image/jpeg', width: size.width, height: size.height, screen: screenName, image: image.toString('base64') };
  if (id) relay.send(id, payload); else relay.broadcast(payload);
  captureCount++; lastCapture = { timestamp: payload.timestamp, bytes: image.length, width: size.width, height: size.height };
  relay.log('截图已发送到安卓主端');
}
async function sources() {
  if (process.platform === 'win32') {
    const { stdout } = await execFileAsync(captureHelper(), ['list'], { windowsHide: true, timeout: 8000, maxBuffer: 128 * 1024, encoding: 'utf8' });
    return JSON.parse(stdout);
  }
  const list = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } });
  return list.map((s, i) => ({ id: s.id, displayId: s.display_id, name: s.name || `显示器 ${i + 1}` }));
}
async function capture({ id, requestId = randomUUID(), answer = false } = {}) {
  if (longCapture?.active) { if (id) relay.send(id, { type: 'error', requestId, message: '长截屏进行中，请再次双击 Ctrl 完成' }); return; }
  if (captureBusy) { if (id) relay.send(id, { type: 'error', requestId, message: '上一张截图正在传输，请稍后重试' }); return; }
  if (!relay.server?.listening || settings.paused || relay.clients.size === 0) {
    if (id) relay.send(id, { type: 'error', requestId, message: settings.paused ? '电脑副端已暂停截图' : '请先启动服务并配对主端' }); return;
  }
  captureBusy = true;
  try {
    let image, size, screenName;
    if (process.platform === 'win32') {
      const available = await sources();
      const selected = available.find(s => s.id === settings.screenId) || available[0];
      if (!selected) throw new Error('没有可用显示器');
      const { stdout } = await execFileAsync(captureHelper(), ['capture', selected.id, String(settings.width), String(settings.quality)], { windowsHide: true, timeout: 15000, maxBuffer: 7 * 1024 * 1024, encoding: 'buffer' });
      image = stdout; size = nativeImage.createFromBuffer(image).getSize(); screenName = selected.name;
      if (size.width < 1 || size.height < 1) throw new Error('截屏图像无效');
    } else {
    const displays = screen.getAllDisplays();
    const selected = (await sources()).find(s => s.id === settings.screenId);
    const display = displays.find(d => String(d.id) === selected?.displayId) || screen.getPrimaryDisplay();
    const ratio = display.size.height / display.size.width;
    const shots = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: settings.width, height: Math.round(settings.width * ratio) } });
    const shot = shots.find(s => s.id === settings.screenId) || shots.find(s => s.display_id === String(display.id)) || shots[0];
    if (!shot || shot.thumbnail.isEmpty()) throw new Error('未能读取显示器图像；请检查 Windows 屏幕捕获权限');
    image = shot.thumbnail.toJPEG(settings.quality);
    size = shot.thumbnail.getSize(); screenName = shot.name;
    }
    sendImage(image, { id, requestId, answer, screenName });
  } catch (e) {
    const payload = { type: 'error', requestId, message: e.message };
    if (id) relay.send(id, payload); else relay.broadcast(payload);
    relay.log(`截图失败：${e.message}`);
  } finally { captureBusy = false; notify(); }
}
function isMain(event) { const frame = event.senderFrame; return win && !win.isDestroyed() && event.sender === win.webContents && frame && frame.processId === win.webContents.mainFrame.processId && frame.routingId === win.webContents.mainFrame.routingId; }
function handle(name, fn) { ipcMain.handle(name, (event, ...args) => { if (quitting) return null; if (!isMain(event)) throw new Error('Invalid IPC sender'); return fn(...args); }); }

app.whenReady().then(async () => {
  fs.mkdirSync(app.getPath('userData'), { recursive: true });
  try { settings = { ...settings, ...JSON.parse(fs.readFileSync(settingsPath(), 'utf8')) }; } catch {}
  settings.hotkey = migrateHotkey(settings.hotkey);
  settings.width = Math.max(800, Math.min(3840, Number(settings.width) || 1920));
  settings.quality = Math.max(50, Math.min(95, Number(settings.quality) || 85));
  save();
  relay = new Relay({ port: smoke ? 0 : 18731, identityDir: path.join(app.getPath('userData'), 'connection') });
  discovery = new CodeDiscovery(relay);
  longCapture = new LongCapture({ helper: captureHelper, options: async () => {
    const list = await sources(); const selected = list.find(s => s.id === settings.screenId) || list[0];
    if (!selected) throw new Error('没有可用显示器');
    return { ...selected, width: settings.width, quality: settings.quality };
  }, allowed: () => process.platform === 'win32' && canCapture() && !captureBusy,
  send: sendImage, log: message => relay.log(message), changed: notify });
  controlWatcher = new ControlWatcher({ helper: captureHelper, toggle: () => longCapture.toggle(), changed: notify,
    failed: message => { longCapture.cancel('Ctrl 监听中断，长截屏已取消'); relay.log(`长截屏快捷键不可用：${message}`); notify(); } });
  discovery.on('change', notify);
  relay.on('change', () => { if (!canCapture()) longCapture.cancel('服务停止、截图暂停或手机断开，长截屏已取消'); notify(); });
  relay.on('capture', request => void capture(request));
  const desktopArea = screen.getPrimaryDisplay().workAreaSize;
  const windowWidth = Math.min(1080, desktopArea.width - 24), windowHeight = Math.min(900, desktopArea.height - 24);
  win = new BrowserWindow({ width: windowWidth, height: windowHeight, minWidth: Math.min(780, windowWidth), minHeight: Math.min(600, windowHeight), show: !smoke, backgroundColor: '#f3f6fb', title: 'easy exam · 电脑副端',
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true } });
  win.setMenuBarVisibility(false);
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', event => event.preventDefault());
  win.webContents.on('console-message', (_event, details) => { if (details.level === 'error') smokeErrors.push(details.message); });
  win.webContents.on('render-process-gone', (_event, details) => smokeErrors.push(details.reason));
  win.on('close', e => { if (!quitting && !smoke) { e.preventDefault(); win.hide(); } });
  const icon = nativeImage.createFromPath(path.join(__dirname, '../assets/tray.png'));
  tray = new Tray(icon);
  tray.setToolTip('easy exam · 笔试截图副端');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '打开控制台', click: () => win.show() },
    { label: '截图并发送', click: () => void capture({ answer: true }) },
    { label: '开始／完成长截屏（双击 Ctrl）', click: () => void longCapture.toggle() },
    { label: '暂停／恢复截图', click: () => { settings.paused = !settings.paused; if (settings.paused) longCapture.cancel('截图已暂停，长截屏已取消'); save(); registerShortcut(); notify(); } },
    { type: 'separator' }, { label: '退出', click: () => { quitting = true; app.quit(); } }
  ]));
  tray.on('double-click', () => win.show());
  handle('status', status);
  handle('screens', sources);
  handle('forget-devices', () => relay.forgetAll());
  handle('direct-rotate', () => { if(!relay.server?.listening) throw new Error('请先启动服务'); relay.rotateDirect(); });
  handle('direct-qr', host => { const link=relay.directStatus().links.find(x=>x.host===host);if(!link)throw new Error('连接地址不可用');return QRCode.toDataURL(link.uri,{width:360,margin:2,errorCorrectionLevel:'M'}); });
  handle('direct-copy', host => { const link=relay.directStatus().links.find(x=>x.host===host);if(!link)throw new Error('连接地址不可用');clipboard.writeText(link.uri); });
  handle('start', async () => { const result = await relay.start(); registerShortcut(); return result; });
  handle('stop', async () => { longCapture.cancel('服务停止，长截屏已取消'); controlWatcher.stop(); discovery.finish('idle', '已停止连接'); globalShortcut.unregisterAll(); shortcutOK = false; await relay.stop(); });
  handle('connect-code', async (code, host) => { await discovery.connect(String(code), String(host || '').trim()); registerShortcut(); return status(); });
  handle('cancel-pair', () => discovery.finish('idle', '已取消连接')); 
  handle('disconnect', id => relay.disconnect(String(id)));
  handle('hide', () => win.hide());
  handle('capture', () => capture({ answer: true }));
  handle('long-capture', () => longCapture.toggle());
  handle('cancel-long-capture', () => longCapture.cancel());
  handle('settings', input => {
    if (!input || typeof input !== 'object') throw new Error('设置格式无效');
    if (longCapture.active && (input.paused === true || (typeof input.screenId === 'string' && input.screenId !== settings.screenId) || (input.width !== undefined && Number(input.width) !== settings.width) || (input.quality !== undefined && Number(input.quality) !== settings.quality))) longCapture.cancel('截图设置已更改，长截屏已取消，请重新开始');
    if (typeof input.screenId === 'string') settings.screenId = input.screenId.slice(0, 100);
    if ([1280, 1920, 2560, 3840].includes(Number(input.width))) settings.width = Number(input.width);
    if (Number.isFinite(Number(input.quality))) settings.quality = Math.max(50, Math.min(95, Number(input.quality)));
    if (typeof input.paused === 'boolean') settings.paused = input.paused;
    if (input.hotkey !== undefined) { if (!isTwoKey(input.hotkey)) throw new Error('普通截图请使用 Alt／Ctrl 加一个字母或数字的双键组合'); settings.hotkey = input.hotkey; }
    save(); if (relay.server?.listening) registerShortcut(); notify(); return settings;
  });
  await win.loadFile(path.join(__dirname, '../ui/index.html'));
  if(!smoke){await relay.start();registerShortcut();}
  if (smoke) { win.show(); await runSmoke(); }
}).catch(e => { console.error(e); app.exit(1); });

async function runSmoke() {
  const { WebSocket } = require('ws');
  const folder = app.isPackaged ? path.dirname(app.getPath('exe')) : path.join(__dirname, '..');
  let stage = 'start';
  let smokeFixture;
  try {
    await relay.start();
    registerShortcut();
    stage = 'native-screenshot-transfer';
    const token = relay.directToken;
    const state = relay.status();
    const ws = new WebSocket(`wss://127.0.0.1:${state.port}/relay`, { rejectUnauthorized: false, headers: { Origin: 'offerin-local-native' } });
    const payload = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Screenshot transfer timeout')), 20_000);
      ws.on('error', reject);
      ws.on('open', () => ws.send(JSON.stringify({type:'link-pair',token,name:'QR smoke peer'})));
      ws.on('message', raw => {
        const msg = JSON.parse(raw);
        if (msg.type === 'paired') ws.send(JSON.stringify({ type: 'capture', requestId: 'smoke-shot', answer: true }));
        if (msg.type === 'error') { clearTimeout(timeout); reject(new Error(msg.message)); }
        if (msg.type === 'screenshot') { clearTimeout(timeout); resolve(msg); }
      });
    });
    if (payload.requestId !== 'smoke-shot' || payload.width < 100 || !payload.image) throw new Error('Invalid screenshot payload');
    await new Promise(r => setTimeout(r, 500));
    stage = 'ui-render';
    fs.writeFileSync(path.join(folder, 'smoke-ui.png'), (await win.webContents.capturePage()).toPNG());
    stage = 'native-long-capture-transfer';
    win.hide();
    smokeFixture = new BrowserWindow({ fullscreen: true, frame: false, show: true, alwaysOnTop: true, backgroundColor: '#ffffff', webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false } });
    smokeFixture.setAlwaysOnTop(true, 'screen-saver');
    const fixtureHtml = '<!doctype html><style>body{margin:0;font:18px monospace;background:white}header,footer{position:fixed;left:0;right:0;background:#203452;color:white;padding:20px;z-index:2}header{top:0}footer{bottom:0}main{padding:90px 80px}section{height:54px;border-bottom:1px solid #ddd}</style><header>easy exam scrolling capture test</header><main>' + Array.from({ length: 120 }, (_, i) => `<section style="color:rgb(${i * 17 % 170},${i * 29 % 170},${i * 43 % 170})">Question ${i + 1}: verify scrolling pixels and image transfer. ${i * 7919}</section>`).join('') + '</main><footer>Fixed footer — test content only</footer>';
    await smokeFixture.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(fixtureHtml));
    await new Promise(r => setTimeout(r, 700));
    const longPayload = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Long capture timeout: ' + JSON.stringify(longCapture.state))), 20000);
      ws.on('message', raw => { const msg = JSON.parse(raw); if (msg.type === 'screenshot') { clearTimeout(timeout); resolve(msg); } });
      void (async () => {
        await longCapture.toggle();
        const started = Date.now();
        while (longCapture.state.phase === 'starting' && Date.now() - started < 8000) await new Promise(r => setTimeout(r, 30));
        if (longCapture.state.phase !== 'capturing') throw new Error(longCapture.state.error || 'Long capture did not start');
        for (let step = 1; step <= 6; step++) {
          await smokeFixture.webContents.executeJavaScript(`window.scrollTo(0, ${step * 80})`);
          await new Promise(r => setTimeout(r, 400));
        }
        await longCapture.toggle();
      })().catch(error => { clearTimeout(timeout); reject(error); });
    });
    if (!longPayload.image || longPayload.height <= payload.height || longPayload.height > 8192 || !longPayload.answer) throw new Error('Invalid long screenshot payload');
    fs.writeFileSync(path.join(folder, 'smoke-result.json'), JSON.stringify({ ok: smokeErrors.length === 0, width: payload.width, height: payload.height, bytes: Buffer.from(payload.image, 'base64').length, requestId: payload.requestId, longCapture: { width: longPayload.width, height: longPayload.height, bytes: Buffer.from(longPayload.image, 'base64').length, shortcutOK: controlWatcher.ready }, consoleErrors: smokeErrors }, null, 2));
    smokeFixture.destroy(); longCapture.cancel(); controlWatcher.stop(); ws.close(); await relay.stop(); quitting = true; app.exit(smokeErrors.length ? 1 : 0);
  } catch (e) { smokeFixture?.destroy(); longCapture?.cancel(); controlWatcher?.stop(); fs.writeFileSync(path.join(folder, 'smoke-result.json'), JSON.stringify({ ok: false, stage, error: e.message, consoleErrors: smokeErrors }, null, 2)); console.error(stage, e); app.exit(1); }
}
app.on('before-quit', () => { quitting = true; longCapture?.cancel(); controlWatcher?.stop(); discovery?.cancel(); globalShortcut.unregisterAll(); });
app.on('window-all-closed', () => { if (smoke || quitting) app.quit(); });

