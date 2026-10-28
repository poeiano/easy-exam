const $ = id => document.getElementById(id);
let qrKey = "";
let current, initialized = false, refreshing = false;
function showError(e) { $('error').textContent = e.message || String(e); $('error').hidden = false; }
function action(fn) { return async event => { const button = event?.currentTarget; if (button?.getAttribute('aria-busy') === 'true') return; button?.setAttribute('aria-busy', 'true'); button?.classList.add('is-busy'); $('error').hidden = true; try { await fn(); await refresh(); } catch (e) { showError(e); } finally { button?.removeAttribute('aria-busy'); button?.classList.remove('is-busy'); } }; }
async function refresh() {
  if (refreshing) return; refreshing = true;
  try {
    current = await window.desktop.status();
    const long = current.longCapture, longActive = long.phase !== 'idle';
    $('service-state').textContent = longActive ? '长截屏进行中 · 再次双击 Ctrl 完成' : current.running ? current.settings.paused ? '截图已暂停' : '连接服务运行中' : '服务未启动';
    $('service-dot').classList.toggle('off', !current.running || current.settings.paused);
    $('peer-count').textContent = `${current.peers.length} 台设备`;
    $('toggle-service').textContent = current.running ? '停止服务' : '启动连接服务';
    $('shots').textContent = `${current.captureCount} 次发送`;
    $('hotkey-state').textContent = current.shortcutOK ? '✓ 快捷键已生效，全屏应用内也可触发' : current.running ? '快捷键注册失败，可能被其他应用占用；请更换组合' : '启动服务后注册快捷键';
    $('shortcut-display').textContent = current.settings.hotkey.replace('Control', 'Ctrl').replace('+', ' + ');
    const direct=current.direct, selected=$('direct-address').value;
    $('direct-address').replaceChildren(...direct.links.map(x=>new Option(x.host,x.host)));
    if(direct.links.some(x=>x.host===selected))$('direct-address').value=selected;
    $('direct-rotate').disabled=!current.running; $('direct-copy').disabled=!direct.available;
    $('connection-controls').hidden = !direct.available;
    document.querySelector('.pairing').classList.toggle('is-connected', !direct.available && current.peers.length > 0);
    $('connection-hint').textContent = !direct.available && current.peers.length ? '已保存设备信任，断网后自动重连。需要连接另一台设备时，刷新二维码即可。' : '扫码不便时可粘贴链接连接。配对成功后长期保存，断网自动重连。';
    $('direct-qr').hidden=!direct.available; $('direct-empty').hidden=direct.available;
    if(!direct.available){qrKey='';$('direct-empty').textContent=current.peers.length?'连接已就绪':'启动服务后显示二维码';}
    if(direct.available){const key=$('direct-address').value+':'+direct.expires;if(key!==qrKey){$('direct-qr').src=await window.desktop.directQr($('direct-address').value);qrKey=key;}}
    const seconds=Math.max(0,Math.ceil((direct.expires-Date.now())/1000));$('direct-expiry').textContent=direct.available?(seconds?'有效期剩余 '+Math.floor(seconds/60)+':'+String(seconds%60).padStart(2,'0'):'已过期，请刷新二维码'):'';
    const unavailable = !current.running || !current.peers.length || current.settings.paused;
    $('capture').disabled = unavailable || longActive;
    $('long-capture').disabled = !long.supported || unavailable || long.phase === 'finishing';
    $('long-capture').textContent = long.phase === 'finishing' ? '正在拼接并发送…' : longActive ? '完成并发送' : '开始长截屏';
    $('cancel-long-capture').hidden = !longActive;
    $('long-capture-state').textContent = long.phase === 'starting' ? '正在开始采集…' : long.phase === 'finishing' ? '正在拼接并发送，请稍候…' : longActive ? long.unmatched ? '画面暂未匹配，请放慢滚动，保持前后内容重叠' : `采集中 · 已拼接 ${long.height} 像素 · 再次双击 Ctrl 完成` : long.error || (!long.supported ? '长截屏仅支持 Windows' : long.shortcutOK ? '✓ 双击 Ctrl 已就绪；Ctrl 组合键不触发' : current.running ? 'Ctrl 监听未就绪，可使用开始按钮或重启服务' : '启动服务后监听双击 Ctrl');
    document.querySelector('.long-capture-control').classList.toggle('is-active', longActive);
    if (!initialized) {
      const screens = await window.desktop.screens();
      $('screen').replaceChildren(...screens.map(s => new Option(s.name, s.id)));
      if (current.settings.screenId) $('screen').value = current.settings.screenId;
      $('width').value = current.settings.width; $('quality').value = current.settings.quality;
      $('hotkey').value = current.settings.hotkey; initialized = true;
    }
    $('paused').checked = current.settings.paused;
    $('peers').replaceChildren();
    if (!current.peers.length) { $('peers').textContent = '暂无连接，请使用安卓扫描电脑二维码。'; $('peers').className = 'empty'; }
    else {
      $('peers').className = '';
      for (const p of current.peers) {
        const row = document.createElement('div'); row.className = 'peer';
        const name = document.createElement('span'); name.textContent = `▣  ${p.name}`;
        const btn = document.createElement('button'); btn.className = 'text-button'; btn.textContent = '断开并取消信任'; btn.onclick = action(() => window.desktop.disconnect(p.id));
        row.append(name, btn); $('peers').append(row);
      }
    }
    $('last-shot').textContent = current.lastCapture ? `上次发送 ${new Date(current.lastCapture.timestamp).toLocaleTimeString()} · ${current.lastCapture.width} × ${current.lastCapture.height} · ${Math.round(current.lastCapture.bytes / 1024)} KB` : '';
    $('logs').replaceChildren(...current.logs.slice(-6).reverse().map(l => { const row = document.createElement('div'); row.textContent = `${new Date(l.at).toLocaleTimeString()}　${l.message}`; return row; }));
    if (!current.logs.length) $('logs').textContent = '等待启动服务…';
    $('version').textContent = current.version;
  } catch (e) { showError(e); } finally { refreshing = false; }
}
$('toggle-service').onclick = action(() => current.running ? window.desktop.stop() : window.desktop.start());
$('hide').onclick = action(() => window.desktop.hide());
$('capture').onclick = action(() => window.desktop.capture());
$('long-capture').onclick = action(() => window.desktop.longCapture());
$('cancel-long-capture').onclick = action(() => window.desktop.cancelLongCapture());
$('save').onclick = action(async () => { await window.desktop.settings({ screenId: $('screen').value, width: +$('width').value, quality: +$('quality').value, hotkey: $('hotkey').value, paused: $('paused').checked }); $('save-feedback').textContent = '已保存'; setTimeout(() => { $('save-feedback').textContent = ''; }, 2200); });
$('paused').onchange = action(() => window.desktop.settings({ paused: $('paused').checked }));
window.desktop.onChange(refresh);
void refresh();

$('direct-rotate').onclick=action(()=>window.desktop.directRotate());
$('direct-copy').onclick=action(async()=>{await window.desktop.directCopy($('direct-address').value);$('direct-copy').textContent='已复制';setTimeout(()=>{$('direct-copy').textContent='复制配对链接';},1800);});
$('direct-address').onchange=action(refresh);
setInterval(refresh,2000);

$('forget-devices').onclick=action(()=>window.desktop.forgetDevices());
