const { spawn } = require('node:child_process');
const { createInterface } = require('node:readline');

class LongCapture {
  constructor({ helper, options, allowed, send, log, changed, spawnProcess = spawn }) {
    Object.assign(this, { helper, options, allowed, send, log, changed, spawnProcess });
    this.state = { phase: 'idle', height: 0, unmatched: false, error: '' };
    this.run = null;
  }
  get active() { return this.state.phase !== 'idle'; }
  update(patch) { Object.assign(this.state, patch); this.changed(); }
  async toggle() {
    if (this.active) {
      const run = this.run;
      if (run && !run.finish) {
        run.finish = true;
        this.update({ phase: 'finishing' });
        if (run.ready) this.finish(run);
      }
      return;
    }
    if (!this.allowed()) { this.update({ error: '请先连接手机并恢复截图，再开始长截屏' }); this.log(this.state.error); return; }
    const run = { chunks: [], bytes: 0, finish: false, ready: false, error: '' };
    this.run = run;
    this.update({ phase: 'starting', height: 0, unmatched: false, error: '' });
    try {
      const options = await this.options();
      if (this.run !== run) return;
      if (!this.allowed()) throw new Error('长截屏已取消：手机断开或截图暂停');
      run.options = options;
      const child = this.spawnProcess(this.helper(), ['scroll', options.id, String(options.width), String(options.quality)], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
      run.child = child;
      child.stdin.on('error', error => { if (this.run === run) this.fail(run, error.message); });
      child.on('error', error => this.fail(run, error.message));
      run.lines = createInterface({ input: child.stderr });
      run.lines.on('line', line => {
        if (this.run !== run) return;
        let progress;
        try { progress = JSON.parse(line); } catch { run.error = (run.error + line).slice(-4096); return; }
        if (progress.state !== 'capturing') return;
        if (!run.ready) {
          run.ready = true; clearTimeout(run.timer);
          this.log('长截屏已开始，请手动缓慢向下滚动；再次双击 Ctrl 完成');
          if (run.finish) this.finish(run);
        }
        this.update({ phase: run.finish ? 'finishing' : 'capturing', height: progress.height, unmatched: !!progress.unmatched });
      });
      child.stdout.on('data', chunk => {
        if (this.run !== run) return;
        run.bytes += chunk.length;
        if (run.bytes > 6 * 1024 * 1024) this.fail(run, '长截图超过手机传输上限，请缩短范围或降低质量');
        else run.chunks.push(chunk);
      });
      child.on('close', code => {
        if (this.run !== run) return;
        try {
          if (code !== 0 || !run.finish || !run.bytes) throw new Error(run.error || '长截屏进程提前退出，请重新开始');
          if (!this.allowed()) throw new Error('长截屏已取消：手机断开或截图暂停');
          this.send(Buffer.concat(run.chunks), { screenName: options.name, answer: true });
          this.cleanup(run);
          this.update({ phase: 'idle', unmatched: false });
        } catch (error) { this.fail(run, error.message); }
      });
      run.timer = setTimeout(() => this.fail(run, '长截屏启动超时，请重试'), 10000);
    } catch (error) { this.fail(run, error.message); }
  }
  finish(run) {
    run.child.stdin.end('finish\n');
    clearTimeout(run.timer);
    run.timer = setTimeout(() => this.fail(run, '长截图拼接超时，请缩短范围后重试'), 20000);
  }
  cleanup(run) {
    clearTimeout(run.timer); run.lines?.close();
    if (this.run === run) this.run = null;
    run.chunks.length = 0;
  }
  fail(run, error) {
    if (this.run !== run) return;
    this.cleanup(run); run.child?.kill();
    this.log(`长截屏失败：${error}`);
    this.update({ phase: 'idle', unmatched: false, error });
  }
  cancel(reason = '长截屏已取消') {
    const run = this.run;
    if (!run) return;
    this.cleanup(run); run.child?.kill();
    this.log(reason); this.update({ phase: 'idle', unmatched: false, error: reason });
  }
}

class ControlWatcher {
  constructor({ helper, toggle, changed, failed, spawnProcess = spawn }) {
    Object.assign(this, { helper, toggle, changed, failed, spawnProcess });
    this.ready = false; this.child = null;
  }
  start() {
    if (this.child) return;
    const child = this.spawnProcess(this.helper(), ['watch-control'], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    this.child = child;
    let error = '';
    const lines = createInterface({ input: child.stdout });
    const timer = setTimeout(() => fail('Ctrl 监听启动超时'), 8000);
    const fail = message => {
      if (this.child !== child) return;
      this.stop(); this.failed(message);
    };
    child.stdin.on('error', e => fail(e.message));
    child.on('error', e => fail(e.message));
    child.stderr.on('data', b => { error = (error + b.toString()).slice(-4096); });
    lines.on('line', line => {
      if (this.child !== child) return;
      if (line === 'ready') { clearTimeout(timer); this.ready = true; this.changed(); }
      if (line === 'toggle' && this.ready) void this.toggle();
    });
    child.on('close', () => fail(error || 'Ctrl 监听已退出，请重启连接服务'));
    this.cleanup = () => { clearTimeout(timer); lines.close(); };
  }
  stop() {
    const child = this.child; this.child = null; this.ready = false;
    this.cleanup?.(); child?.kill(); this.changed();
  }
}
module.exports = { LongCapture, ControlWatcher };
