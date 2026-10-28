package local.offerin.practice;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import okhttp3.OkHttpClient;
import org.json.JSONObject;

// SRP-6a/SHA-256 client, interoperable with LinusU/secure-remote-password.
// Encoding follows that library (2048-bit group, fixed-width public values).
public final class Pairing {
    private static final BigInteger N=new BigInteger("AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",16);
    private static final BigInteger G=BigInteger.valueOf(2);
    private static final SecureRandom RANDOM=new SecureRandom();
    public final String host,pin,sessionId,attemptId,proof;
    public final int port;
    private final byte[] key,expectedProof;
    private final String context;
    private String directToken;
    private boolean trusted;
    public boolean isTrusted(){return trusted;}
    public String trustedLink(String deviceToken){if(!deviceToken.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid device token");return "offerin-local://direct?v=3&host="+host+"&port="+port+"&pin="+pin+"&token="+deviceToken+"&trusted=1";}
    public boolean isDirect(){return directToken!=null;}
    public static Pairing direct(String uri)throws Exception{
        java.net.URI u=new java.net.URI(uri.trim());
        if(!"offerin-local".equals(u.getScheme())||!"direct".equals(u.getHost())||u.getRawQuery()==null||u.getRawFragment()!=null)throw new IllegalArgumentException("请扫描电脑端生成的连接二维码");
        java.util.Map<String,String> fields=new java.util.HashMap<>();
        for(String part:u.getRawQuery().split("&")){String[] kv=part.split("=",2);if(kv.length!=2||fields.put(kv[0],kv[1])!=null)throw new IllegalArgumentException("配对链接格式无效");}
        String host=fields.getOrDefault("host",""),pin=fields.getOrDefault("pin",""),token=fields.getOrDefault("token","");
        if(!"3".equals(fields.get("v"))||!host.matches("([0-9]{1,3}\\.){3}[0-9]{1,3}")||!pin.matches("[0-9a-f]{64}")||!token.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("配对链接格式无效");
        for(String part:host.split("\\."))if(Integer.parseInt(part)>255)throw new IllegalArgumentException("电脑 IP 无效");
        int port=Integer.parseInt(fields.getOrDefault("port","0"));if(port<1||port>65535)throw new IllegalArgumentException("电脑端口无效");
        Pairing p=new Pairing(host,port,pin,"","",new byte[0],new byte[0],new byte[0]);p.directToken=token;p.trusted="1".equals(fields.get("trusted"));return p;
    }
    private Pairing(String host,int port,String pin,String sid,String aid,byte[] key,byte[] proof,byte[] expectedProof){this.host=host;this.port=port;this.pin=pin;this.sessionId=sid;this.attemptId=aid;this.key=key;this.proof=toHex(proof);this.expectedProof=expectedProof;this.context="offerin-pair-v2|"+sid+"|"+aid+"|"+pin+"|"+port;}
    public static final class Session{
        public final String code,id,salt,A;
        public final long expires;
        private final BigInteger a;
        public Session(){this(String.format(Locale.ROOT,"%04d",RANDOM.nextInt(10000)));}
        public Session(String code){
            if(!code.matches("[0-9]{4}"))throw new IllegalArgumentException("密码必须为四位数字");
            this.code=code;this.id=toHex(random(16));this.salt=toHex(random(32));this.a=new BigInteger(1,random(32));this.A=toHex(pad(G.modPow(a,N),256));this.expires=System.currentTimeMillis()+300000;
        }
        public JSONObject offer(String queryId)throws Exception{return new JSONObject().put("type","offerin-offer").put("v",2).put("queryId",queryId).put("sessionId",id).put("salt",salt).put("A",A);}
        public Pairing challenge(JSONObject m,String host)throws Exception{
            if(System.currentTimeMillis()>expires||!id.equals(m.optString("sessionId"))||!"offerin-challenge".equals(m.optString("type"))||m.optInt("v")!=2)throw new IllegalArgumentException("配对密码已过期");
            String bHex=m.optString("B"),pin=m.optString("pin"),attempt=m.optString("attemptId");int port=m.optInt("port");
            if(!bHex.matches("[0-9a-f]{512}")||!pin.matches("[0-9a-f]{64}")||!attempt.matches("[0-9a-f]{32}")||port<1||port>65535)throw new IllegalArgumentException("配对参数无效");
            BigInteger b=new BigInteger(bHex,16);if(b.mod(N).signum()==0)throw new IllegalArgumentException("无效配对响应");
            byte[] s=fromHex(salt),pubA=fromHex(A),pubB=fromHex(bHex),hN=hash(pad(N,256)),hg=hash(new byte[]{2});
            String identity="offerin-local:"+id;
            BigInteger x=new BigInteger(1,hash(s,hash((identity+":"+code).getBytes(StandardCharsets.UTF_8))));
            BigInteger k=new BigInteger(1,hash(pad(N,256),new byte[]{2}));
            BigInteger u=new BigInteger(1,hash(pubA,pubB));if(u.signum()==0)throw new IllegalArgumentException("无效配对响应");
            BigInteger shared=b.subtract(k.multiply(G.modPow(x,N))).mod(N).modPow(a.add(u.multiply(x)),N);
            byte[] key=hash(pad(shared,256)),xor=new byte[32];for(int i=0;i<32;i++)xor[i]=(byte)(hN[i]^hg[i]);
            byte[] proof=hash(xor,hash(identity.getBytes(StandardCharsets.UTF_8)),s,pubA,pubB,key);
            return new Pairing(host,port,pin,id,attempt,key,proof,hash(pubA,proof,key));
        }
    }
    public JSONObject hello(String name)throws Exception{if(isDirect())return new JSONObject().put("type",trusted?"trusted-pair":"link-pair").put("token",directToken).put("name",name);return new JSONObject().put("type","pair").put("sessionId",sessionId).put("attemptId",attemptId).put("proof",proof).put("name",name);}
    public JSONObject confirm(JSONObject response)throws Exception{
        if(!"pair-proof".equals(response.optString("type"))||!MessageDigest.isEqual(expectedProof,fromHex(response.optString("proof")))||!MessageDigest.isEqual(mac("server"),fromHex(response.optString("binding"))))throw new IllegalArgumentException("密码或电脑身份验证失败");
        return new JSONObject().put("type","pair-confirm").put("binding",toHex(mac("client")));
    }
    private byte[] mac(String label)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal((label+"|"+context).getBytes(StandardCharsets.UTF_8));}
    public OkHttpClient client()throws Exception{
        X509TrustManager tm=new X509TrustManager(){
            public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("Not supported");}
            public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException{
                if(chain.length!=1)throw new CertificateException("Unexpected chain");
                try{chain[0].checkValidity();if(!MessageDigest.isEqual(hash(chain[0].getEncoded()),fromHex(pin)))throw new CertificateException("电脑证书验证失败");}catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}
            }
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
        };
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,new TrustManager[]{tm},null);
        return new OkHttpClient.Builder().sslSocketFactory(ssl.getSocketFactory(),tm).hostnameVerifier((h,s)->host.equals(h)).connectTimeout(10,TimeUnit.SECONDS).pingInterval(15,TimeUnit.SECONDS).build();
    }
    public String wsUrl(){return "wss://"+host+":"+port+"/relay";}
    private static byte[] random(int size){byte[] b=new byte[size];RANDOM.nextBytes(b);return b;}
    private static byte[] hash(byte[]... items)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");for(byte[] b:items)d.update(b);return d.digest();}
    private static byte[] pad(BigInteger value,int width){byte[] raw=value.toByteArray(),result=new byte[width];int count=Math.min(raw.length,width);System.arraycopy(raw,raw.length-count,result,width-count,count);return result;}
    private static String toHex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    private static byte[] fromHex(String value){if(value.length()%2!=0||!value.matches("[0-9a-f]+"))throw new IllegalArgumentException("Invalid hexadecimal value");byte[] b=new byte[value.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(value.substring(2*i,2*i+2),16);return b;}
}
