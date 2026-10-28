// Cross-language fixture: the real WSS relay, with an explicit fake screenshot.
const { Relay } = require('../src/relay.cjs');
(async () => {
  const relay = new Relay({ host: '127.0.0.1', port: 0 });
  await relay.start();
  relay.on('capture', req => relay.send(req.id, { type: 'screenshot', requestId: req.requestId, answer: req.answer, image: 'fixture-image', width: 1, height: 1 }));
  if(process.argv.includes('--direct'))console.log(`offerin-local://direct?v=3&host=127.0.0.1&port=${relay.port}&pin=${relay.pin}&token=${relay.directToken}`);
  else require('node:readline').createInterface({input:process.stdin}).once('line', line => {
    const input=JSON.parse(line);console.log(JSON.stringify(relay.challenge(input.offer,input.code,'127.0.0.1')));
  });
  setTimeout(async () => { await relay.stop(); process.exit(0); }, 18000).unref();
})().catch(e => { console.error(e.message); process.exit(1); });
