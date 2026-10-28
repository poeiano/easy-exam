package local.offerin.practice;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.JSONObject;

public final class BalanceClient {
    private final OkHttpClient client;
    private final String root;
    public interface Listener{void done(Balance balance);void error(String message);}
    public static final class Balance{
        public final BigDecimal available,total;
        public final String currency;
        Balance(BigDecimal available,BigDecimal total){this(available,total,"CNY");}
        Balance(BigDecimal available,BigDecimal total,String currency){this.available=available;this.total=total;this.currency=currency;}
        public String symbol(){return "USD".equals(currency)?"$":"¥";}
        public String availableText(){return available.setScale(4,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
        public String totalText(){return total.setScale(4,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    }
    public BalanceClient(){this(new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build(),null);}
    // Explicit fixture root is package-private; production selects an official host.
    BalanceClient(OkHttpClient client,String root){this.client=client;this.root=root;}
    private static boolean deepSeek(String base){try{return "api.deepseek.com".equalsIgnoreCase(new URI(base.trim()).getHost());}catch(Exception e){return false;}}
    public static boolean supports(String base){try{URI u=new URI(base.trim());String h=u.getHost();return "https".equals(u.getScheme())&&(h!=null&&(h.equalsIgnoreCase("api.deepseek.com")||h.equalsIgnoreCase("open.bigmodel.cn")||h.equalsIgnoreCase("bigmodel.cn")||h.equalsIgnoreCase("www.bigmodel.cn")));}catch(Exception e){return false;}}
    public static Balance parseDeepSeek(JSONObject response)throws Exception{
        org.json.JSONArray infos=response.optJSONArray("balance_infos");
        if(infos==null||infos.length()==0)throw new IOException("平台未提供余额数值，请稍后重试");
        JSONObject selected=null;
        for(int i=0;i<infos.length();i++){JSONObject item=infos.optJSONObject(i);if(item==null)continue;String currency=item.optString("currency");if("CNY".equals(currency)){selected=item;break;}if(selected==null&&"USD".equals(currency))selected=item;}
        if(selected==null||!selected.has("total_balance")||selected.isNull("total_balance"))throw new IOException("平台未提供支持的币种或余额");
        try{BigDecimal value=new BigDecimal(selected.getString("total_balance"));return new Balance(value,value,selected.getString("currency"));}catch(NumberFormatException e){throw new IOException("平台余额格式无法识别");}
    }
    public static Balance parse(JSONObject response)throws Exception{
        if(!response.optBoolean("success",false)||response.optInt("code",0)!=200)throw new IOException("平台未返回余额，请检查密钥权限或稍后重试");
        JSONObject data=response.optJSONObject("data");
        if(data==null||!data.has("availableBalance")||data.isNull("availableBalance")||!data.has("balance")||data.isNull("balance"))throw new IOException("平台未提供余额数值，请稍后重试");
        try{return new Balance(new BigDecimal(data.get("availableBalance").toString()),new BigDecimal(data.get("balance").toString()));}catch(NumberFormatException e){throw new IOException("平台余额格式无法识别");}
    }
    public Call query(String base,String key,Listener listener){
        if(!supports(base)){listener.error("当前支持 DeepSeek 和智谱国内平台的账户余额查询");return null;}
        if(key==null||key.trim().isEmpty()){listener.error("请先保存 API Key");return null;}
        final boolean ds=deepSeek(base);
        String host=root!=null?root:(ds?"https://api.deepseek.com":"https://open.bigmodel.cn");
        Request request=new Request.Builder().url(host+(ds?"/user/balance":"/api/biz/account/query-customer-account-report")).header("Authorization",(ds?"Bearer ":"")+key.trim()).header("Accept","application/json").get().build();
        Call call=client.newCall(request);
        call.enqueue(new Callback(){
            public void onFailure(Call c,IOException error){if(!c.isCanceled())listener.error("余额查询连接失败（"+error.getClass().getSimpleName()+"），可稍后刷新");}
            public void onResponse(Call c,Response response){try(response){
                if(!response.isSuccessful())throw new IOException("余额查询返回 HTTP "+response.code()+"，请检查网络及密钥权限");
                if(response.body()==null)throw new IOException("平台未返回余额内容");
                JSONObject payload=new JSONObject(response.body().string());
                listener.done(ds?parseDeepSeek(payload):parse(payload));
            }catch(Exception e){if(!c.isCanceled())listener.error(e instanceof IOException?e.getMessage():"余额响应格式无法识别");}}
        });
        return call;
    }
}
