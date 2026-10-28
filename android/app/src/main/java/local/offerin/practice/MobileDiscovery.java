package local.offerin.practice;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public final class MobileDiscovery implements AutoCloseable{
    public interface Listener{void challenge(Pairing pairing);void status(String message);}
    public final Pairing.Session session=new Pairing.Session();
    private final Listener listener;
    private volatile DatagramSocket socket;
    private volatile boolean closed,connecting;
    private int failures;
    public MobileDiscovery(Listener listener){this.listener=listener;}
    public void start(){Thread t=new Thread(this::listen,"offerin-code-pairing");t.setDaemon(true);t.start();}
    private void listen(){
        Set<String> seen=new HashSet<>();
        try(DatagramSocket s=new DatagramSocket(null)){
            s.setReuseAddress(true);s.bind(new InetSocketAddress("0.0.0.0",18732));s.setBroadcast(true);s.setSoTimeout(1000);socket=s;
            if(closed)return;
            while(!closed&&System.currentTimeMillis()<session.expires){
                byte[] bytes=new byte[4096];DatagramPacket packet=new DatagramPacket(bytes,bytes.length);
                try{s.receive(packet);}catch(SocketTimeoutException e){continue;}
                if(!(packet.getAddress() instanceof Inet4Address))continue;
                JSONObject m;try{m=new JSONObject(new String(packet.getData(),0,packet.getLength(),StandardCharsets.UTF_8));}catch(Exception e){continue;}
                if(m.optInt("v")!=2)continue;
                if("offerin-discover".equals(m.optString("type"))&&m.optString("queryId").matches("[0-9a-f]{32}")){
                    byte[] answer=session.offer(m.getString("queryId")).toString().getBytes(StandardCharsets.UTF_8);s.send(new DatagramPacket(answer,answer.length,packet.getAddress(),packet.getPort()));
                }else if("offerin-challenge".equals(m.optString("type"))&&session.id.equals(m.optString("sessionId"))&&!connecting&&seen.size()<10&&seen.add(m.optString("attemptId"))){
                    try{Pairing p=session.challenge(m,packet.getAddress().getHostAddress());connecting=true;listener.status("已发现电脑，正在验证四位密码…");listener.challenge(p);}catch(Exception e){listener.status("配对响应无效，请重新生成密码");}
                }
            }
            if(!closed)listener.status("密码已过期，请刷新密码");
        }catch(Exception e){if(!closed)listener.status("无法等待连接，请检查 Wi-Fi 后重新生成密码");}
    }
    public void failed(){connecting=false;if(++failures>=5){listener.status("验证失败次数过多，请刷新密码");close();}else listener.status("密码不匹配或连接失败，请在电脑检查四位密码");}
    public static String addresses(){StringBuilder text=new StringBuilder();try{for(NetworkInterface n:java.util.Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress a:java.util.Collections.list(n.getInetAddresses()))if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&!a.isLinkLocalAddress()){if(text.length()>0)text.append(" / ");text.append(a.getHostAddress());}}catch(Exception ignored){}return text.length()>0?text.toString():"请先连接 Wi-Fi";}
    public void close(){closed=true;DatagramSocket s=socket;if(s!=null)s.close();}
}
