const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');
const { LongCapture, ControlWatcher } = require('../src/long-capture.cjs');

function processFixture() {
  const child = new EventEmitter();
  child.stdin = new PassThrough(); child.stdout = new PassThrough(); child.stderr = new PassThrough();
  child.kill = () => { child.killed = true; };
  return child;
}
function fixture(extra = {}) {
  const children = [], sent = [], logs = [];
  const capture = new LongCapture({ helper: () => 'helper.exe', options: async () => ({ id: 'monitor-2', name: 'Monitor 2', width: 1920, quality: 85 }),
    allowed: () => true, send: (...args) => sent.push(args), log: message => logs.push(message), changed() {},
    spawnProcess: (_exe, args, options) => { assert.equal(options.windowsHide, true); assert.deepEqual(args, ['scroll', 'monitor-2', '1920', '85']); const child = processFixture(); children.push(child); return child; }, ...extra });
  return { capture, children, sent, logs };
}
const ready = child => child.stderr.write('{"state":"capturing","height":1080}\n');

test('double toggle captures once and sends only after native finalization', async t => {
  const { capture, children, sent } = fixture(); t.after(() => capture.cancel());
  await capture.toggle(); const child = children[0]; ready(child);
  assert.equal(capture.state.phase, 'capturing'); assert.equal(sent.length, 0);
  child.stderr.write('{"state":"capturing","height":2880,"unmatched":true}\n');
  assert.equal(capture.state.unmatched, true);
  await capture.toggle(); await capture.toggle();
  assert.equal(capture.state.phase, 'finishing'); assert.equal(child.stdin.read().toString(), 'finish\n');
  child.stdout.write(Buffer.from('jpeg')); assert.equal(sent.length, 0);
  child.emit('close', 0);
  assert.equal(sent.length, 1); assert.equal(sent[0][0].toString(), 'jpeg'); assert.equal(sent[0][1].answer, true);
  assert.equal(capture.state.phase, 'idle');
});
test('second toggle during asynchronous startup finishes that session', async t => {
  let resolve;
  const { capture, children, sent } = fixture({ options: () => new Promise(r => { resolve = r; }) }); t.after(() => capture.cancel());
  const pending = capture.toggle(); await capture.toggle();
  resolve({ id: 'monitor-2', name: 'Monitor 2', width: 1920, quality: 85 }); await pending;
  const child = children[0]; ready(child);
  assert.equal(capture.state.phase, 'finishing'); assert.equal(child.stdin.read().toString(), 'finish\n');
  child.stdout.write(Buffer.from('jpeg')); child.emit('close', 0); assert.equal(sent.length, 1);
});
test('cancel during startup and disconnect during finish discard all pixels', async () => {
  let resolve;
  const pending = fixture({ options: () => new Promise(r => { resolve = r; }) });
  const start = pending.capture.toggle(); pending.capture.cancel(); resolve({}); await start;
  assert.equal(pending.children.length, 0); assert.equal(pending.capture.active, false);
  let allowed = true;
  const { capture, children, sent } = fixture({ allowed: () => allowed });
  await capture.toggle(); const child = children[0]; ready(child); await capture.toggle();
  child.stdout.write(Buffer.from('jpeg')); allowed = false; child.emit('close', 0);
  assert.equal(sent.length, 0); assert.equal(capture.active, false); assert.match(capture.state.error, /取消/);
});
test('native error, premature exit and oversized output never send a partial capture', async () => {
  for (const failure of ['native', 'early', 'large', 'cancel']) {
    const { capture, children, sent } = fixture(); await capture.toggle(); const child = children[0]; ready(child);
    if (failure === 'native') { await capture.toggle(); child.stderr.write('滚动缺少重叠\n'); child.emit('close', 1); }
    if (failure === 'early') child.emit('close', 0);
    if (failure === 'large') child.stdout.write(Buffer.alloc(6 * 1024 * 1024 + 1));
    if (failure === 'cancel') { capture.cancel(); child.stdout.write(Buffer.from('late')); child.emit('close', 0); }
    assert.equal(sent.length, 0); assert.equal(capture.active, false); assert.ok(capture.state.error);
  }
});
test('paused/disconnected state never launches a capture process', async () => {
  const { capture, children } = fixture({ allowed: () => false }); await capture.toggle();
  assert.equal(children.length, 0); assert.equal(capture.active, false);
});
test('watcher readiness, global toggle, shutdown and restart', () => {
  const children = []; let toggles = 0; const errors = [];
  const watcher = new ControlWatcher({ helper: () => 'helper', changed() {}, toggle: () => toggles++, failed: e => errors.push(e), spawnProcess: () => { const child = processFixture(); children.push(child); return child; } });
  watcher.start(); watcher.start(); assert.equal(children.length, 1);
  children[0].stdout.write('toggle\n'); assert.equal(toggles, 0);
  children[0].stdout.write('ready\ntoggle\n'); assert.equal(watcher.ready, true); assert.equal(toggles, 1);
  watcher.stop(); children[0].emit('close', 1); assert.equal(errors.length, 0); assert.equal(watcher.ready, false);
  watcher.start(); children[1].emit('error', new Error('hook unavailable'));
  assert.equal(watcher.ready, false); assert.match(errors[0], /hook unavailable/); assert.equal(children[1].killed, true);
});
