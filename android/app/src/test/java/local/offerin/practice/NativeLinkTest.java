package local.offerin.practice;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import org.json.JSONObject;

public class NativeLinkTest {
    @Test(timeout=20000) public void pinnedJavaClientPairsWithActualWindowsProtocol() throws Exception {
        exchange(false);
    }
    @Test(timeout=20000) public void qrJavaClientPairsAndRequestsScreenshot() throws Exception {exchange(true);}
    private void exchange(boolean direct) throws Exception {
        Process process=new ProcessBuilder("node","test/native-peer-server.cjs",direct?"--direct":"--srp").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        OkHttpClient client=null;
        try {
            Pairing p;
            if(direct){p=Pairing.direct(new BufferedReader(new InputStreamReader(process.getInputStream())).readLine());}
            else{Pairing.Session session=new Pairing.Session("0042");
            process.getOutputStream().write((new JSONObject().put("offer",session.offer("a")).put("code","0042").toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));process.getOutputStream().flush();
            String challenge=new BufferedReader(new InputStreamReader(process.getInputStream())).readLine();
            p=session.challenge(new JSONObject(challenge),"127.0.0.1");}client=p.client();
            CountDownLatch done=new CountDownLatch(1);AtomicReference<JSONObject> received=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
            WebSocket ws=client.newWebSocket(new Request.Builder().url(p.wsUrl()).header("Origin","offerin-local-native").build(),new WebSocketListener(){
                public void onOpen(WebSocket socket,Response r){try{socket.send(p.hello("JVM Android client integration").toString());}catch(Exception e){error.set(e);done.countDown();}}
                public void onMessage(WebSocket socket,String message){try{JSONObject m=new JSONObject(message);if(m.getString("type").equals("pair-proof"))socket.send(p.confirm(m).toString());else if(m.getString("type").equals("paired"))socket.send(new JSONObject().put("type","capture").put("requestId","android-java").put("answer",true).toString());else{received.set(m);done.countDown();}}catch(Exception e){error.set(e);done.countDown();}}
                public void onFailure(WebSocket socket,Throwable t,Response r){error.set(t);done.countDown();}
            });
            assertTrue("WSS exchange timed out",done.await(12,TimeUnit.SECONDS));assertNull(error.get()==null?"":error.get().toString(),error.get());
            assertEquals("screenshot",received.get().getString("type"));assertEquals("android-java",received.get().getString("requestId"));assertEquals("fixture-image",received.get().getString("image"));assertTrue(received.get().getBoolean("answer"));
            ws.close(1000,"Test complete");
        } finally {if(client!=null){client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}process.destroy();process.waitFor(3,TimeUnit.SECONDS);if(process.isAlive())process.destroyForcibly();}
    }
}
