const dgram=require('node:dgram');
const os=require('node:os');
const {randomBytes}=require('node:crypto');
const {EventEmitter}=require('node:events');
function broadcasts(){
  const result=new Set(['255.255.255.255']);
  for(const item of Object.values(os.networkInterfaces()).flat())if(item&&!item.internal&&item.family==='IPv4'){
    const ip=item.address.split('.').map(Number),mask=item.netmask.split('.').map(Number);
    result.add(ip.map((b,i)=>(b&mask[i])|(255^mask[i])).join('.'));
  }return [...result];
}
class CodeDiscovery extends EventEmitter{
  constructor(relay,{port=18732,timeout=30000}={}){super();this.relay=relay;this.port=port;this.timeout=timeout;this.state={state:'idle',message:'请输入安卓主端生成的四位密码'};relay.on('peer-joined',()=>this.finish('connected','主端已连接'));relay.on('pair-error',message=>{this.state={...this.state,message};this.emit('change');});}
  status(){return this.state;}
  async connect(code,host=''){
    if(!/^\d{4}$/.test(code))throw new Error('请输入四位数字密码，包含开头的 0');
    if(host&&!/^(\d{1,3}\.){3}\d{1,3}$/.test(host))throw new Error('主端 IP 格式无效');
    this.cancel();await this.relay.start();
    const queryId=randomBytes(16).toString('hex'),seen=new Map();
    const socket=dgram.createSocket('udp4');this.socket=socket;
    this.state={state:'searching',message:'正在查找主端并验证密码…',expires:Date.now()+this.timeout};this.emit('change');
    socket.on('error',()=>this.finish('error','无法搜索局域网设备，请检查网络权限'));
    socket.on('message',(bytes,rinfo)=>{
      if(bytes.length>4096)return;
      let offer;try{offer=JSON.parse(bytes);}catch{return;}
      if(offer.type!=='offerin-offer'||offer.v!==2||offer.queryId!==queryId)return;
      const cacheKey=`${offer.sessionId}:${rinfo.address}`;
      try{
        let challenge=seen.get(cacheKey);if(!challenge){challenge=this.relay.challenge(offer,code,rinfo.address);seen.set(cacheKey,challenge);}
        if(this.socket===socket)socket.send(Buffer.from(JSON.stringify(challenge)),rinfo.port,rinfo.address);
      }catch{}
    });
    await new Promise((resolve,reject)=>{socket.once('listening',resolve);socket.once('error',reject);socket.bind(0,'0.0.0.0');});
    if(this.socket!==socket)return;
    socket.setBroadcast(true);
    const query=Buffer.from(JSON.stringify({type:'offerin-discover',v:2,queryId}));
    const targets=host?[host]:broadcasts();
    const send=()=>{if(this.socket===socket)for(const ip of targets)socket.send(query,this.port,ip,()=>{});};
    send();this.interval=setInterval(send,1800);this.timer=setTimeout(()=>this.finish('error','未连接成功：请确认主端正在显示密码、两端处于同一局域网；必要时填写主端 IP'),this.timeout);
  }
  finish(state,message){this.cancel();this.state={state,message};this.emit('change');}
  cancel(){clearInterval(this.interval);clearTimeout(this.timer);if(this.socket){const s=this.socket;this.socket=null;try{s.close();}catch{}}this.relay.pending.clear();}
}
module.exports={CodeDiscovery,broadcasts};
