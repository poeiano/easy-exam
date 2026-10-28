package local.offerin.practice;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.json.JSONObject;

public class BalanceClientTest {
    @Test public void deepSeekBalancesPreserveCurrencyAndDoNotTreatUnavailableAsZero()throws Exception{
        BalanceClient.Balance value=BalanceClient.parseDeepSeek(new JSONObject("{\"is_available\":true,\"balance_infos\":[{\"currency\":\"USD\",\"total_balance\":\"2\"},{\"currency\":\"CNY\",\"total_balance\":\"12.3456\"}]}"));
        assertEquals("12.3456",value.availableText());assertEquals("CNY",value.currency);assertEquals("¥",value.symbol());
        value=BalanceClient.parseDeepSeek(new JSONObject("{\"is_available\":false,\"balance_infos\":[{\"currency\":\"USD\",\"total_balance\":\"0\"}]}"));
        assertEquals("0",value.availableText());assertEquals("$",value.symbol());
        for(String response:new String[]{"{}","{\"balance_infos\":[]}","{\"balance_infos\":[{\"currency\":\"CNY\"}]}"}){
            try{BalanceClient.parseDeepSeek(new JSONObject(response));fail("Missing balance accepted");}catch(IOException expected){}
        }
    }
    @Test public void deepSeekQueryUsesOfficialRouteAndBearerAuthentication()throws Exception{
        assertTrue(BalanceClient.supports("https://api.deepseek.com"));assertFalse(BalanceClient.supports("https://api.deepseek.com.attacker.invalid"));
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setBody("{\"is_available\":true,\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":\"5.25\"}]}"));
            CountDownLatch done=new CountDownLatch(1);AtomicReference<BalanceClient.Balance> result=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
            new BalanceClient(new OkHttpClient(),server.url("/").toString().replaceAll("/+$","")).query("https://api.deepseek.com","mock-deepseek-key",new BalanceClient.Listener(){public void done(BalanceClient.Balance value){result.set(value);done.countDown();}public void error(String message){error.set(message);done.countDown();}});
            assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(error.get());assertEquals("5.25",result.get().availableText());
            RecordedRequest request=server.takeRequest(2,TimeUnit.SECONDS);assertEquals("/user/balance",request.getPath());assertEquals("GET",request.getMethod());assertEquals("Bearer mock-deepseek-key",request.getHeader("Authorization"));assertEquals(0,request.getBodySize());
        }
    }
    @Test public void actualBalanceFieldsAndZeroAreParsedWithoutEstimates() throws Exception{
        BalanceClient.Balance value=BalanceClient.parse(new JSONObject("{\"code\":200,\"success\":true,\"data\":{\"availableBalance\":\"12.34567\",\"balance\":\"13.5\"}}"));
        assertEquals("12.3457",value.availableText());assertEquals("13.5",value.totalText());
        value=BalanceClient.parse(new JSONObject("{\"code\":200,\"success\":true,\"data\":{\"availableBalance\":0,\"balance\":0}}"));assertEquals("0",value.availableText());
        value=BalanceClient.parse(new JSONObject("{\"code\":200,\"success\":true,\"data\":{\"availableBalance\":-0.25,\"balance\":0}}"));assertEquals("-0.25",value.availableText());
    }
    @Test public void missingOrRejectedBalancesAreNeverPresentedAsZero()throws Exception{
        for(String response:new String[]{"{}","{\"code\":500,\"success\":false}","{\"code\":200,\"success\":true,\"data\":{\"balance\":2}}","{\"code\":200,\"success\":true,\"data\":{\"balance\":2,\"availableBalance\":null}}"}){
            try{BalanceClient.parse(new JSONObject(response));fail("Invalid balance accepted");}catch(IOException expected){}
        }
    }
    @Test public void onlyOfficialProviderConfigurationIsEligible(){
        assertTrue(BalanceClient.supports("https://open.bigmodel.cn/api/paas/v4"));
        assertFalse(BalanceClient.supports("https://open.bigmodel.cn.attacker.invalid/v1"));assertFalse(BalanceClient.supports("http://open.bigmodel.cn/v1"));assertFalse(BalanceClient.supports("https://api.openai.com/v1"));
    }
    @Test public void readOnlyBalanceQueryUsesCorrectAuthAndDoesNotExposeProviderErrors()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setBody("{\"code\":200,\"success\":true,\"data\":{\"availableBalance\":20.125,\"balance\":21}}"));
            server.enqueue(new MockResponse().setResponseCode(401).setBody("private-account-diagnostic"));
            BalanceClient client=new BalanceClient(new OkHttpClient(),server.url("/").toString().replaceAll("/+$",""));
            for(int i=0;i<2;i++){
                CountDownLatch done=new CountDownLatch(1);AtomicReference<BalanceClient.Balance> result=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
                client.query("https://open.bigmodel.cn/api/paas/v4","mock-account-key",new BalanceClient.Listener(){public void done(BalanceClient.Balance value){result.set(value);done.countDown();}public void error(String message){error.set(message);done.countDown();}});
                assertTrue(done.await(5,TimeUnit.SECONDS));RecordedRequest request=server.takeRequest(2,TimeUnit.SECONDS);assertEquals("GET",request.getMethod());assertEquals("/api/biz/account/query-customer-account-report",request.getPath());assertEquals("mock-account-key",request.getHeader("Authorization"));assertEquals(0,request.getBodySize());
                if(i==0){assertNull(error.get());assertEquals("20.125",result.get().availableText());}else{assertNull(result.get());assertTrue(error.get().contains("401"));assertFalse(error.get().contains("private-account-diagnostic"));}
            }
        }
    }
}
