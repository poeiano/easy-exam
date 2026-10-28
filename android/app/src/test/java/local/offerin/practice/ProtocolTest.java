package local.offerin.practice;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;

public class ProtocolTest {
    @Test public void glm53FlashVisionRequestAndReasoningStream() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"internal reasoning\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"答案：C\"}}]}\n\ndata: [DONE]\n\n"));
            CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>(),error=new AtomicReference<>();
            new ModelClient().ask(server.url("/api/paas/v4").toString(),"mock-glm-key","glm-5.3-flash","解题","aW1hZ2U=",true,null,new ModelClient.Listener(){public void delta(String s){}public void done(String s){result.set(s);done.countDown();}public void error(String e){error.set(e);done.countDown();}});
            assertTrue(done.await(10,TimeUnit.SECONDS));assertNull(error.get());assertEquals("答案：C",result.get());
            RecordedRequest req=server.takeRequest(5,TimeUnit.SECONDS);assertEquals("/api/paas/v4/chat/completions",req.getPath());assertEquals("Bearer mock-glm-key",req.getHeader("Authorization"));
            JSONObject body=new JSONObject(req.getBody().readUtf8());assertEquals("glm-5.3-flash",body.getString("model"));assertEquals("enabled",body.getJSONObject("thinking").getString("type"));assertEquals("low",body.getString("reasoning_effort"));assertTrue(body.toString().contains("data:image/jpeg;base64,aW1hZ2U="));
        }
    }
    @Test public void directLinkValidation() throws Exception {
        String uri="offerin-local://direct?v=3&host=10.225.85.8&port=18731&pin="+"a".repeat(64)+"&token="+"b".repeat(64);
        Pairing p=Pairing.direct(uri);assertTrue(p.isDirect());assertEquals("wss://10.225.85.8:18731/relay",p.wsUrl());assertEquals("link-pair",p.hello("test").getString("type"));
        Pairing saved=Pairing.direct(p.trustedLink("c".repeat(64)));assertTrue(saved.isTrusted());assertEquals("trusted-pair",saved.hello("test").getString("type"));assertEquals("c".repeat(64),saved.hello("test").getString("token"));
        for(String invalid:new String[]{"https://example.com",uri+"&host=1.1.1.1",uri.replace("10.225.85.8","999.1.1.1"),uri.replace("v=3","v=2"),uri.replace("port=18731","port=0")}){
            try{Pairing.direct(invalid);fail("Invalid direct link accepted");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void fourDigitsPreserveLeadingZeros() throws Exception {
        Pairing.Session s=new Pairing.Session("0042");assertEquals("0042",s.code);assertEquals(512,s.A.length());assertEquals(64,s.salt.length());assertEquals(s.id,s.offer("a").getString("sessionId"));
        assertTrue(new Pairing.Session().code.matches("[0-9]{4}"));
    }
    @Test public void malformedCodesAndChallengesRejected() throws Exception {
        for(String code:new String[]{"123","12345","abcd"}){try{new Pairing.Session(code);fail("Invalid code accepted");}catch(IllegalArgumentException expected){}}
        try{new Pairing.Session().challenge(new JSONObject(),"127.0.0.1");fail("Invalid challenge accepted");}catch(IllegalArgumentException expected){}
    }
    @Test public void apiKeyImageAndStreamingRoundTrip() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"：B\"}}]}\n\ndata: [DONE]\n\n"));
            CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>(),error=new AtomicReference<>();
            new ModelClient().ask(server.url("/v1").toString(),"test-api-key","vision-test","解答题目","aW1hZ2U=",true,new JSONArray(),new ModelClient.Listener(){public void delta(String s){}public void done(String s){result.set(s);done.countDown();}public void error(String e){error.set(e);done.countDown();}});
            assertTrue(done.await(10,TimeUnit.SECONDS));assertNull(error.get());assertEquals("答案：B",result.get());
            RecordedRequest req=server.takeRequest(5,TimeUnit.SECONDS);assertNotNull(req);assertEquals("/v1/chat/completions",req.getPath());assertEquals("Bearer test-api-key",req.getHeader("Authorization"));
            JSONObject body=new JSONObject(req.getBody().readUtf8());assertEquals("vision-test",body.getString("model"));assertTrue(body.toString().contains("data:image/jpeg;base64,aW1hZ2U="));
        }
    }
    @Test public void nonStreamingAndHttpErrorAreReported() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"choices\":[{\"message\":{\"content\":\"42\"}}]}"));
            server.enqueue(new MockResponse().setResponseCode(401).setBody("private-provider-diagnostic"));
            for(int index=0;index<2;index++){
                CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>(),error=new AtomicReference<>();
                new ModelClient().ask(server.url("/v1/").toString(),"key","vision","question",null,false,null,new ModelClient.Listener(){public void delta(String s){}public void done(String s){result.set(s);done.countDown();}public void error(String e){error.set(e);done.countDown();}});
                assertTrue(done.await(10,TimeUnit.SECONDS));if(index==0)assertEquals("42",result.get());else{assertTrue(error.get().contains("401"));assertFalse(error.get().contains("private-provider-diagnostic"));}
            }
        }
    }
}
