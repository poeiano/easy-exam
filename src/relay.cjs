const https = require('node:https');
const { randomBytes, randomUUID, timingSafeEqual, X509Certificate, createHmac, createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { WebSocketServer, WebSocket } = require('ws');
const selfsigned = require('selfsigned');
const srp = require('secure-remote-password/server');
const client = require('secure-remote-password/client');
const os = require('node:os');
const hex = (v, n) => typeof v === 'string' && new RegExp(`^[0-9a-f]{${n}}$`).test(v);
function equal(a, b) { if (typeof a !== 'string' || typeof b !== 'string') return false; const x=Buffer.from(a),y=Buffer.from(b);return x.length===y.length&&timingSafeEqual(x,y); }
function binding(key, label, context) { return createHmac('sha256',Buffer.from(key,'hex')).update(`${label}|${context}`).digest('hex'); }
class Relay extends EventEmitter {
  constructor({ host='0.0.0.0',port=18731,identityDir=null }={}) {super();this.host=host;this.port=port;this.identityDir=identityDir;this.clients=new Map();this.pending=new Map();this.attempts=new Map();this.logs=[];this.pin='';this.trusted={};if(identityDir){fs.mkdirSync(identityDir,{recursive:true});try{this.trusted=JSON.parse(fs.readFileSync(path.join(identityDir,'trusted-devices.json'),'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;}}}
  persistTrust(){if(this.identityDir){const p=path.join(this.identityDir,'trusted-devices.json');fs.writeFileSync(p+'.tmp',JSON.stringify(this.trusted),{mode:0o600});fs.renameSync(p+'.tmp',p);}}
  forgetAll(){this.trusted={};this.persistTrust();for(const c of this.clients.values())c.ws.close(4003,'Device trust revoked');this.log('已取消所有设备信任，需要重新扫码');}
  status(){return {running:!!this.server?.listening,port:this.port,peers:[...this.clients.values()].map(c=>({id:c.id,name:c.name,connectedAt:c.connectedAt})),logs:this.logs};}
  log(message){this.logs=[...this.logs,{at:new Date().toISOString(),message}].slice(-80);this.emit('change');}
  rotateDirect(){this.directToken=randomBytes(32).toString('hex');this.directExpires=Date.now()+300000;this.emit('change');}
  directStatus(){
    const hosts=[...new Set(Object.values(os.networkInterfaces()).flat().filter(x=>x&&x.family==='IPv4'&&!x.internal&&!x.address.startsWith('169.254.')).map(x=>x.address))];
    return {expires:this.directExpires||0,available:!!this.directToken&&!!this.server?.listening,links:this.directToken&&this.server?.listening?hosts.map(host=>({host,uri:`offerin-local://direct?v=3&host=${host}&port=${this.port}&pin=${this.pin}&token=${this.directToken}`})):[]};
  }
  async start(){if(this.starting)return this.starting;this.starting=this.startInternal();try{return await this.starting;}finally{this.starting=null;}}
  async startInternal(){
    if(this.server)return this.status();
    let cert;const certPath=this.identityDir&&path.join(this.identityDir,'tls-identity.json');
    if(certPath){try{cert=JSON.parse(fs.readFileSync(certPath,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;}}
    if(!cert){cert=await selfsigned.generate([{name:'commonName',value:'easy exam Screenshot Companion'}],{keySize:2048,algorithm:'sha256',notAfterDate:new Date(Date.now()+3650*86400000)});if(certPath)fs.writeFileSync(certPath,JSON.stringify({cert:cert.cert,private:cert.private}),{mode:0o600});}
    this.pin=new X509Certificate(cert.cert).fingerprint256.replace(/:/g,'').toLowerCase();
    const server=https.createServer({key:cert.private,cert:cert.cert,minVersion:'TLSv1.2'},(_req,res)=>res.writeHead(404,{'Content-Type':'text/plain','Cache-Control':'no-store'}).end('Native clients only'));
    this.wss=new WebSocketServer({noServer:true,maxPayload:32*1024});
    server.on('upgrade',(req,socket,head)=>{if(req.url!=='/relay'||req.headers.origin!=='offerin-local-native'||this.wss.clients.size>=8){socket.destroy();return;}this.wss.handleUpgrade(req,socket,head,ws=>this.attach(ws,req));});
    this.server=server;
    try{await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(this.port,this.host,resolve);});}catch(e){this.server=null;this.wss.close();this.wss=null;throw e;}
    this.port=server.address().port;
    this.heartbeat=setInterval(()=>{for(const ws of this.wss.clients){if(!ws.alive)ws.terminate();else{ws.alive=false;ws.ping();}}for(const [id,p] of this.pending)if(p.expires<Date.now())this.pending.delete(id);for(const [ip,a]of this.attempts)if(Date.now()-a.at>60000)this.attempts.delete(ip);},15000);
    this.heartbeat.unref();this.rotateDirect();this.log('连接服务已启动，请用安卓扫描连接二维码');return this.status();
  }
  challenge(offer, code, address){
    if(!this.server?.listening)throw new Error('请先启动连接服务');
    if(!/^\d{4}$/.test(code)||!hex(offer.sessionId,32)||!hex(offer.salt,64)||!hex(offer.A,512))throw new Error('配对参数无效');
    if(this.pending.size>=16)throw new Error('正在处理其他配对，请稍后');
    const username=`offerin-local:${offer.sessionId}`;
    const verifier=client.deriveVerifier(client.derivePrivateKey(offer.salt,username,code));
    const ephemeral=srp.generateEphemeral(verifier),attemptId=randomBytes(16).toString('hex');
    const context=`offerin-pair-v2|${offer.sessionId}|${attemptId}|${this.pin}|${this.port}`;
    this.pending.set(attemptId,{offer,username,verifier,ephemeral,context,address,expires:Date.now()+30000});
    return {type:'offerin-challenge',v:2,sessionId:offer.sessionId,attemptId,B:ephemeral.public,pin:this.pin,port:this.port};
  }
  attach(ws,req){
    ws.alive=true;ws.on('pong',()=>{ws.alive=true;});ws.on('error',()=>{});
    const id=randomUUID();let paired=false,lastAction=0,proofState=null;
    const timer=setTimeout(()=>ws.close(1008,'Pairing timeout'),15000);timer.unref();
    ws.on('message',raw=>{
      let msg;try{msg=JSON.parse(raw);}catch{ws.close(1008,'Invalid JSON');return;}if(!msg||typeof msg!=='object')return;
      if(!paired){
        try{
          if((msg.type==='link-pair'||msg.type==='trusted-pair')&&!proofState){
            const ip=req.socket.remoteAddress.replace(/^::ffff:/,'');let a=this.attempts.get(ip);if(!a||Date.now()-a.at>60000){a={at:Date.now(),count:0};this.attempts.set(ip,a);}if(++a.count>5)throw new Error('连接失败次数过多，请一分钟后重试');
            const resume=msg.type==='trusted-pair';let deviceToken,trustHash;const name=String(msg.name||'安卓主端').replace(/[\x00-\x1f]/g,'').slice(0,50);
            if(resume){if(!hex(msg.token,64))throw new Error('设备信任已失效，请重新扫码');trustHash=createHash('sha256').update(msg.token).digest('hex');if(!this.trusted[trustHash]){ws.close(4003,'Device trust revoked');return;}}
            else{if(!this.directToken||Date.now()>this.directExpires||!hex(msg.token,64)||!equal(msg.token,this.directToken))throw new Error('连接二维码已失效，请在电脑刷新后重新扫描');deviceToken=randomBytes(32).toString('hex');trustHash=createHash('sha256').update(deviceToken).digest('hex');this.trusted[trustHash]={name,createdAt:Date.now()};try{this.persistTrust();}catch(e){delete this.trusted[trustHash];throw e;}this.directToken=null;}
            for(const c of this.clients.values())if(c.trustHash===trustHash)c.ws.close(4001,'Connection replaced');
            a.count=0;paired=true;clearTimeout(timer);this.clients.set(id,{ws,id,name,trustHash,connectedAt:Date.now()});
            ws.send(JSON.stringify({type:'paired',id,protocol:3,...(deviceToken?{deviceToken}:{})}));this.log(resume?'已信任主端自动重连成功':'首次配对成功，已保存设备信任');this.emit('peer-joined',id);return;
          }
          if(msg.type==='pair'&&!proofState){
            const ip=req.socket.remoteAddress.replace(/^::ffff:/,'');let a=this.attempts.get(ip);if(!a||Date.now()-a.at>60000){a={at:Date.now(),count:0};this.attempts.set(ip,a);}if(++a.count>5)throw new Error('配对失败次数过多，请稍后重试');
            const p=this.pending.get(msg.attemptId);this.pending.delete(msg.attemptId);
            if(!p||p.expires<Date.now()||p.address!==ip||msg.sessionId!==p.offer.sessionId||!hex(msg.proof,64))throw new Error('密码已失效，请在主端重新生成');
            let session;try{session=srp.deriveSession(p.ephemeral.secret,p.offer.A,p.offer.salt,p.username,p.verifier,msg.proof);}catch{this.emit('pair-error','密码不匹配，请检查主端显示的四位密码');throw new Error('电脑输入的密码不正确');}
            proofState={session,context:p.context,name:String(msg.name||'安卓主端').replace(/[\x00-\x1f]/g,'').slice(0,50)};
            ws.send(JSON.stringify({type:'pair-proof',proof:session.proof,binding:binding(session.key,'server',p.context)}));return;
          }
          if(msg.type==='pair-confirm'&&proofState&&equal(msg.binding,binding(proofState.session.key,'client',proofState.context))){
            paired=true;clearTimeout(timer);this.clients.set(id,{ws,id,name:proofState.name,connectedAt:Date.now()});proofState=null;
            ws.send(JSON.stringify({type:'paired',id,protocol:2}));this.log('四位密码验证成功，主端已连接');this.emit('peer-joined',id);return;
          }
          throw new Error('请完成四位密码验证');
        }catch(e){ws.send(JSON.stringify({type:'error',message:e.message}));ws.close(1008,'Pairing rejected');return;}
      }
      if(msg.type==='capture'&&Date.now()-lastAction>1000){lastAction=Date.now();this.emit('capture',{id,requestId:typeof msg.requestId==='string'?msg.requestId.slice(0,80):'',answer:msg.answer===true});}
    });
    ws.on('close',()=>{clearTimeout(timer);if(this.clients.delete(id)){this.log('安卓主端已断开');this.emit('peer-left',id);}});
  }
  send(id,message){const c=this.clients.get(id);if(c?.ws.readyState!==WebSocket.OPEN)return;if(c.ws.bufferedAmount>10*1024*1024){c.ws.close(1013,'Slow receiver');return;}c.ws.send(JSON.stringify(message));}
  broadcast(message){for(const id of this.clients.keys())this.send(id,message);}
  disconnect(id){const c=this.clients.get(id);if(c?.trustHash){delete this.trusted[c.trustHash];this.persistTrust();}c?.ws.close(4003,'Device trust revoked');}
  async stop(){if(this.starting){try{await this.starting;}catch{}}clearInterval(this.heartbeat);if(!this.server)return;for(const ws of this.wss.clients)ws.terminate();await new Promise(r=>this.wss.close(r));await new Promise(r=>this.server.close(r));this.server=null;this.wss=null;this.clients.clear();this.pending.clear();this.pin='';this.log('服务已停止');}
}
module.exports={Relay,binding,equal};

