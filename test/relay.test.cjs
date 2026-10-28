const {test}=require('node:test');
const assert=require('node:assert/strict');
const {once}=require('node:events');
const https=require('node:https'),dgram=require('node:dgram');
const {WebSocket}=require('ws');
const {Relay}=require('../src/relay.cjs');
const {CodeDiscovery}=require('../src/discovery.cjs');
const {createPeer}=require('../src/protocol-peer.cjs');
async function connect(r){const w=new WebSocket(`wss://127.0.0.1:${r.port}/relay`,{rejectUnauthorized:false,headers:{Origin:'offerin-local-native'}});await once(w,'open');return w;}
function message(w,value){const p=once(w,'message').then(([b])=>JSON.parse(b));w.send(JSON.stringify(value));return p;}
test('leading zero, mutual proof, screenshot routing and disconnect',{timeout:15000},async t=>{
 const r=new Relay({host:'127.0.0.1',port:0});await r.start();t.after(()=>r.stop());
 const p=createPeer('0042');p.accept(r.challenge(p.offer,p.code,'127.0.0.1'));const w=await connect(r);
 const proof=await message(w,p.hello());assert.equal(proof.type,'pair-proof');assert.equal(r.clients.size,0);
 assert.throws(()=>p.confirm({...proof,binding:'0'.repeat(64)}));
 const paired=await message(w,p.confirm(proof));assert.equal(paired.type,'paired');
 const capture=once(r,'capture');w.send(JSON.stringify({type:'capture',requestId:'shot',answer:true}));const [req]=await capture;assert.equal(req.requestId,'shot');assert.equal(req.answer,true);
 const delivered=once(w,'message');r.send(req.id,{type:'screenshot',image:'fixture'});assert.equal(JSON.parse((await delivered)[0]).image,'fixture');
 const closed=once(w,'close');r.disconnect(paired.id);await closed;assert.equal(r.clients.size,0);
});
test('wrong password, expired attempt, premature capture rejected',{timeout:15000},async t=>{
 const r=new Relay({host:'127.0.0.1',port:0});await r.start();t.after(()=>r.stop());
 for(const mode of ['wrong','expired','premature']){const p=createPeer();const c=r.challenge(p.offer,mode==='wrong'?'9999':p.code,'127.0.0.1');p.accept(c);if(mode==='expired')r.pending.get(c.attemptId).expires=0;const w=await connect(r);assert.equal((await message(w,mode==='premature'?{type:'capture'}:p.hello())).type,'error');assert.equal(r.clients.size,0);w.terminate();}
});
test('UDP discovery connects using primary code and times out cleanly',{timeout:15000},async t=>{
 const r=new Relay({host:'127.0.0.1',port:0});await r.start();t.after(()=>r.stop());
 const mobile=dgram.createSocket('udp4');mobile.bind(0,'127.0.0.1');await once(mobile,'listening');t.after(()=>mobile.close());
 const d=new CodeDiscovery(r,{port:mobile.address().port,timeout:5000});t.after(()=>d.cancel());const p=createPeer('0007');let w;
 const paired=new Promise((resolve,reject)=>mobile.on('message',async(raw,remote)=>{try{const m=JSON.parse(raw);assert.equal(m.code,undefined);if(m.type==='offerin-discover')mobile.send(JSON.stringify({...p.offer,queryId:m.queryId}),remote.port,remote.address);else if(m.type==='offerin-challenge'){p.accept(m);w=await connect(r);const proof=await message(w,p.hello());assert.equal((await message(w,p.confirm(proof))).type,'paired');resolve();}}catch(e){reject(e);}}));
 await d.connect('0007','127.0.0.1');await paired;assert.equal(d.status().state,'connected');w.terminate();
 const short=new CodeDiscovery(r,{port:mobile.address().port,timeout:80});await short.connect('0007','127.0.0.2');await new Promise(resolve=>setTimeout(resolve,120));assert.equal(short.status().state,'error');assert.equal(short.socket,null);
});
test('no web pages or browser-origin pairing',{timeout:15000},async t=>{
 const r=new Relay({host:'127.0.0.1',port:0});await r.start();t.after(()=>r.stop());
 const code=await new Promise((resolve,reject)=>https.get(`https://127.0.0.1:${r.port}/`,{rejectUnauthorized:false},res=>{res.resume();resolve(res.statusCode);}).on('error',reject));assert.equal(code,404);
 const w=new WebSocket(`wss://127.0.0.1:${r.port}/relay`,{rejectUnauthorized:false,headers:{Origin:'https://example.com'}});await once(w,'error');assert.equal(r.clients.size,0);
});
test('direct link validates token, is single use, expires and can be refreshed',{timeout:15000},async t=>{
 const r=new Relay({host:'127.0.0.1',port:0});await r.start();t.after(()=>r.stop());
 let w=await connect(r);assert.equal((await message(w,{type:'link-pair',token:'0'.repeat(64)})).type,'error');w.terminate();
 const token=r.directToken;w=await connect(r);const paired=await message(w,{type:'link-pair',token,name:'QR Android'});assert.equal(paired.protocol,3);assert.equal(r.clients.size,1);assert.equal(r.directStatus().available,false);
 const capture=once(r,'capture');w.send(JSON.stringify({type:'capture',requestId:'direct-shot'}));assert.equal((await capture)[0].requestId,'direct-shot');
 const replay=await connect(r);assert.equal((await message(replay,{type:'link-pair',token})).type,'error');replay.terminate();w.terminate();
 r.rotateDirect();assert.notEqual(r.directToken,token);r.directExpires=0;const expired=await connect(r);assert.equal((await message(expired,{type:'link-pair',token:r.directToken})).type,'error');expired.terminate();
});
test('trusted device survives restart and QR expiry, with stable certificate and revocation',{timeout:20000},async t=>{
 const fs=require('node:fs'),path=require('node:path'),{randomUUID,X509Certificate}=require('node:crypto');
 const root=path.resolve('.toolchain'),dir=path.join(root,'connection-test-'+randomUUID());let r;
 t.after(async()=>{if(r)await r.stop();if(!path.resolve(dir).startsWith(root+path.sep))throw Error('Invalid test cleanup path');fs.rmSync(dir,{recursive:true,force:true});});
 r=new Relay({host:'127.0.0.1',port:0,identityDir:dir});await r.start();let w=await connect(r);
 const result=await message(w,{type:'link-pair',token:r.directToken});assert.equal(result.type,'paired');assert.match(result.deviceToken,/^[a-f0-9]{64}$/);
 const token=result.deviceToken,pin=r.pin,port=r.port;
 assert.equal(fs.readFileSync(path.join(dir,'trusted-devices.json'),'utf8').includes(token),false);
 const cert=JSON.parse(fs.readFileSync(path.join(dir,'tls-identity.json'),'utf8'));assert.ok(Date.parse(new X509Certificate(cert.cert).validTo)>Date.now()+365*86400000);
 w.terminate();await r.stop();r=new Relay({host:'127.0.0.1',port,identityDir:dir});await r.start();assert.equal(r.pin,pin);r.directExpires=0;
 w=await connect(r);const resumed=await message(w,{type:'trusted-pair',token});assert.equal(resumed.type,'paired');assert.equal(resumed.deviceToken,undefined);
 const closed=once(w,'close');r.disconnect(resumed.id);assert.equal((await closed)[0],4003);
 w=await connect(r);const revoked=once(w,'close');w.send(JSON.stringify({type:'trusted-pair',token}));assert.equal((await revoked)[0],4003);
});
