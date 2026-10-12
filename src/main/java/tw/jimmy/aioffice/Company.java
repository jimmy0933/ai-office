package tw.jimmy.aioffice;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static tw.jimmy.aioffice.Json.*;

final class Company {
    static URI endpoint(Map<String,Object> c){
        String url=str(c,"baseUrl").strip().replaceAll("/+$","");
        URI u;try{u=URI.create(url);}catch(Exception e){throw new IllegalArgumentException("公司 URL 格式無效");}
        if(!List.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new IllegalArgumentException("公司 URL 需為 http(s) 網址，不含帳密、query 或 fragment");
        return URI.create(url.endsWith("/chat/completions")?url:url+"/chat/completions");
    }
    static int timeout(Map<String,Object> c){try{int n=((Number)c.getOrDefault("timeoutSeconds",120)).intValue();if(n<5||n>600)throw new Exception();return n;}catch(Exception e){throw new IllegalArgumentException("公司連線逾時需為 5–600 秒");}}
    static void validate(Map<String,Object> c,boolean required){
        if(required||!str(c,"baseUrl").isBlank())endpoint(c);
        if(required&&str(c,"model").isBlank())throw new IllegalArgumentException("請在設定頁填寫公司模型名稱");
        if(str(c,"model").length()>200||str(c,"apiKey").length()>8192||str(c,"apiKey").contains("\n")||str(c,"apiKey").contains("\r"))throw new IllegalArgumentException("公司模型或 API Key 格式無效");timeout(c);
    }
    static Agents.Answer run(String prompt,Map<String,Object> c,Agents.Control ctrl,Consumer<String> event)throws Exception {
        validate(c,true);ctrl.check();String model=str(c,"model"),key=str(c,"apiKey");int seconds=timeout(c);
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(seconds,30))).followRedirects(HttpClient.Redirect.NEVER).build();
        var builder=HttpRequest.newBuilder(endpoint(c)).timeout(Duration.ofSeconds(seconds)).header("Content-Type","application/json").header("Accept","application/json");
        if(!key.isBlank())builder.header("Authorization","Bearer "+key);
        var request=builder.POST(HttpRequest.BodyPublishers.ofString(stringify(map("model",model,"stream",false,"messages",List.of(map("role","user","content",prompt)))),StandardCharsets.UTF_8)).build();
        event.accept("執行中 · Company");var future=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());InputStream body=null;ExecutorService reader=Executors.newSingleThreadExecutor(x->{Thread t=new Thread(x,"company-response");t.setDaemon(true);return t;});long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        try{
            var response=future.get(seconds,TimeUnit.SECONDS);body=response.body();InputStream stream=body;
            var bytes=reader.submit(()->stream.readNBytes(8*1024*1024+1)).get(Math.max(1,until-System.nanoTime()),TimeUnit.NANOSECONDS);ctrl.check();
            if(response.statusCode()<200||response.statusCode()>=300)throw new IOException("公司模型 HTTP "+response.statusCode()+"；請檢查 URL、權限與模型名稱");
            if(bytes.length>8*1024*1024)throw new IOException("公司模型回覆超過 8 MiB 上限");
            var obj=object(parse(new String(bytes,StandardCharsets.UTF_8)));if(obj.containsKey("error"))throw new IOException("公司模型回傳 error；請檢查服務端紀錄");
            var choices=array(obj.get("choices"));if(choices.isEmpty())throw new IOException("公司模型缺少 choices 回覆");
            Object content=object(object(choices.get(0)).get("message")).get("content");String text=content instanceof String?(String)content:"";
            if(content instanceof List){StringBuilder b=new StringBuilder();for(Object part:array(content))b.append(str(object(part),"text"));text=b.toString();}
            if(text.isBlank())throw new IOException("公司模型未回傳文字");return new Agents.Answer(text,obj.get("usage"),str(obj,"model").isBlank()?model:str(obj,"model"));
        }catch(TimeoutException e){throw new IOException("公司模型連線逾時（"+seconds+" 秒）");}
        catch(IllegalArgumentException e){throw new IOException("公司模型 JSON 回覆格式無效");}
        finally{future.cancel(true);if(body!=null)try{body.close();}catch(IOException ignored){}reader.shutdownNow();}
    }
    static List<String> fetchModels(Map<String,Object> c)throws Exception {
        validate(c,false);
        String url=str(c,"baseUrl").strip().replaceAll("/+$","");
        if(url.isBlank())return List.of();
        if(url.endsWith("/chat/completions"))url=url.substring(0,url.length()-"/chat/completions".length());
        URI modelsUri;try{modelsUri=URI.create(url.endsWith("/models")?url:url+"/models");}catch(Exception e){throw new IllegalArgumentException("公司 URL 格式無效");}
        String key=str(c,"apiKey");int seconds=timeout(c);
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(seconds,10))).followRedirects(HttpClient.Redirect.NEVER).build();
        var builder=HttpRequest.newBuilder(modelsUri).timeout(Duration.ofSeconds(Math.min(seconds,15))).header("Accept","application/json");
        if(!key.isBlank())builder.header("Authorization","Bearer "+key);
        var response=client.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()<200||response.statusCode()>=300)throw new IOException("公司模型清單 HTTP "+response.statusCode());
        var obj=object(parse(response.body()));List<String> list=new ArrayList<>();
        if(obj.get("data")instanceof List){
            for(Object item:array(obj.get("data"))){
                if(item instanceof Map){String id=str(object(item),"id");if(!id.isBlank())list.add(id);}
            }
        }
        return list;
    }
}
