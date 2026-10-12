package tw.jimmy.aioffice;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static tw.jimmy.aioffice.Json.*;

final class Usage {
    static boolean numeric(Map<String,Object> m,String k){return m.get(k) instanceof Number n&&Double.isFinite(n.doubleValue())&&n.doubleValue()>=0;}
    static long num(Map<String,Object> m,String k){return numeric(m,k)?((Number)m.get(k)).longValue():0;}
    static Map<String,Object> tokens(String p,Object raw){var m=raw instanceof Map?object(raw):map();long in=0,out=0,cache=0,total=0;boolean known=false;
        if(p.equals("Gemini")){if(m.get("models") instanceof Map)for(Object v:object(m.get("models")).values()){if(!(v instanceof Map)||!(object(v).get("tokens")instanceof Map))continue;var t=object(object(v).get("tokens"));in+=num(t,"prompt");out+=num(t,"candidates");cache+=num(t,"cached");total+=num(t,"total");known|=List.of("prompt","candidates","cached","total").stream().anyMatch(k->numeric(t,k));}}
        else{known=List.of("input_tokens","prompt_tokens","output_tokens","completion_tokens","total_tokens","cache_creation_input_tokens","cache_read_input_tokens").stream().anyMatch(k->numeric(m,k));in=m.containsKey("input_tokens")?num(m,"input_tokens"):num(m,"prompt_tokens");out=m.containsKey("output_tokens")?num(m,"output_tokens"):num(m,"completion_tokens");cache=num(m,"cached_input_tokens")+num(m,"cache_read_input_tokens");if(m.get("prompt_tokens_details")instanceof Map)cache+=num(object(m.get("prompt_tokens_details")),"cached_tokens");total=m.containsKey("total_tokens")?num(m,"total_tokens"):in+out+ (p.equals("Claude")?num(m,"cache_creation_input_tokens")+num(m,"cache_read_input_tokens"):0);}
        return map("input",in,"output",out,"cached",cache,"total",total,"known",known);
    }
    static Map<String,Object> local(Map<String,Object> state){Map<String,Object> result=map();for(String p:Agents.PROVIDERS)result.put(p,map("calls",0L,"reportedCalls",0L,"diagnosticCalls",0L,"input",0L,"output",0L,"cached",0L,"total",0L,"lastAt",""));
        for(Object o:array(state.get("tasks"))){var t=object(o);if(!str(t,"result").isBlank())add(result,str(t,"owner"),t.get("usage"),str(t,"finishedAt"));}
        for(Object o:array(state.get("meetings"))){var m=object(o);for(Object v:array(m.get("messages"))){var message=object(v);if(!str(message,"who").equals("你"))add(result,str(message,"who"),message.get("usage"),str(message,"at"));}if(!str(m,"summary").isBlank())add(result,str(m,"summarizer"),m.get("summaryUsage"),str(m,"finishedAt"));}for(Object o:array(state.getOrDefault("usageChecks",List.of()))){var item=object(o);String p=str(item,"provider");add(result,p,item.get("usage"),str(item,"at"));if(result.containsKey(p)){var v=object(result.get(p));v.put("diagnosticCalls",num(v,"diagnosticCalls")+1);}}return result;
    }
    static void add(Map<String,Object> r,String p,Object raw,String at){if(!r.containsKey(p))return;var v=object(r.get(p));var t=tokens(p,raw);v.put("calls",num(v,"calls")+1);if(Boolean.TRUE.equals(t.get("known")))v.put("reportedCalls",num(v,"reportedCalls")+1);for(String k:List.of("input","output","cached","total"))v.put(k,num(v,k)+num(t,k));if(at.compareTo(str(v,"lastAt"))>0)v.put("lastAt",at);}
    static Map<String,Object> codex(Map<String,Object> config)throws Exception {
        var command=new ArrayList<>(Agents.resolve(str(object(config.get("Codex")),"command"),"Codex"));command.addAll(List.of("app-server","-c","forced_login_method=\"chatgpt\""));var pb=new ProcessBuilder(command);pb.redirectError(ProcessBuilder.Redirect.DISCARD);for(String k:List.of("OPENAI_API_KEY","CODEX_API_KEY","CODEX_ACCESS_TOKEN"))pb.environment().remove(k);
        Process p=pb.start();ExecutorService reader=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"quota-rpc");t.setDaemon(true);return t;});
        var input=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8));var output=new BufferedWriter(new OutputStreamWriter(p.getOutputStream(),StandardCharsets.UTF_8));
        try{
            send(output,map("id",1,"method","initialize","params",map("clientInfo",map("name","ai_office","version","0.7"))));receive(reader,input,1);
            send(output,map("method","initialized","params",map()));send(output,map("id",2,"method","account/rateLimits/read"));var result=receive(reader,input,2);List<Object> windows=new ArrayList<>();
            var limits=result.get("rateLimitsByLimitId")instanceof Map?object(result.get("rateLimitsByLimitId")):map("codex",result.get("rateLimits"));
            for(var entry:limits.entrySet()){if(!(entry.getValue() instanceof Map))continue;var bucket=object(entry.getValue());for(String key:List.of("primary","secondary")){if(bucket.get(key)instanceof Map){var w=object(bucket.get(key));if(w.get("usedPercent")instanceof Number){var mins=num(w,"windowDurationMins");windows.add(map("name",entry.getKey()+" · "+(mins>0?mins+" 分鐘":key),"usedPercent",w.get("usedPercent"),"resetsAt",w.get("resetsAt")));}}}}
            return map("source","Codex 官方 account/rateLimits/read","updatedAt",Office.now(),"windows",windows,"status",windows.isEmpty()?"沒有提供額度視窗":"已取得");
        }finally{Agents.kill(p);reader.shutdownNow();try{input.close();}catch(Exception ignored){}try{output.close();}catch(Exception ignored){}}
    }
    static void send(BufferedWriter out,Object data)throws IOException{out.write(stringify(data));out.newLine();out.flush();}
    static Map<String,Object> receive(ExecutorService worker,BufferedReader in,int id)throws Exception {
        return worker.submit(()->{for(int n=0;n<100;n++){StringBuilder b=new StringBuilder();int c;while((c=in.read())!=-1&&c!='\n'){if(b.length()>1024*1024)throw new IOException("額度輸出過大");b.append((char)c);}if(c==-1&&b.isEmpty())throw new IOException("額度查詢程序結束");var m=object(parse(b.toString()));if(m.get("id") instanceof Number&&((Number)m.get("id")).intValue()==id){if(m.containsKey("error"))throw new IOException("CLI 拒絕額度查詢，請確認版本與訂閱登入");return object(m.get("result"));}}throw new IOException("額度查詢回覆無效");}).get(15,TimeUnit.SECONDS);
    }
    static void validateCompanyUrl(Map<String,Object> c){String url=str(c,"quotaUrl");if(url.isBlank())return;var target=Company.endpoint(map("baseUrl",url));var base=Company.endpoint(c);if(!Objects.equals(target.getScheme(),base.getScheme())||!Objects.equals(target.getHost(),base.getHost())||target.getPort()!=base.getPort())throw new IllegalArgumentException("額度 URL 需與公司 API 相同協定、主機與埠號");}
    static Map<String,Object> company(Map<String,Object> c)throws Exception{
        String url=str(c,"quotaUrl");if(url.isBlank())return map("source","公司額度端點","status","尚未提供額度查詢 URL","windows",List.of());validateCompanyUrl(c);
        var request=HttpRequest.newBuilder(java.net.URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept","application/json");if(!str(c,"apiKey").isBlank())request.header("Authorization","Bearer "+str(c,"apiKey"));
        var future=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build().sendAsync(request.GET().build(),HttpResponse.BodyHandlers.ofInputStream());ExecutorService reader=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"quota-http");t.setDaemon(true);return t;});InputStream stream=null;
        try{var response=future.get(15,TimeUnit.SECONDS);stream=response.body();InputStream body=stream;var bytes=reader.submit(()->body.readNBytes(65537)).get(15,TimeUnit.SECONDS);if(response.statusCode()!=200)throw new IOException("公司額度 HTTP "+response.statusCode());if(bytes.length>65536)throw new IOException("公司額度回覆過大");var m=object(parse(new String(bytes,StandardCharsets.UTF_8)));List<Object> windows=new ArrayList<>();for(Object v:array(m.get("windows"))){var w=object(v);if(!(w.get("usedPercent") instanceof Number))throw new IOException("公司額度欄位無效");windows.add(map("name",str(w,"name"),"usedPercent",w.get("usedPercent"),"resetsAt",w.get("resetsAt")));}return map("source","公司設定的額度端點","status",windows.isEmpty()?"未提供額度視窗":"已取得","windows",windows,"updatedAt",Office.now());}
        finally{future.cancel(true);if(stream!=null)try{stream.close();}catch(Exception ignored){}reader.shutdownNow();}
    }
    static Map<String,Object> antigravity(Map<String,Object> config, Path data){
        Path file = data.resolve("antigravity-quota.json");
        if(Files.isRegularFile(file)){
            try{return snapshot(file, "Antigravity 官方 statusline 快照");}catch(Exception ignored){}
        }
        Path userQuota = Path.of(System.getProperty("user.home"), ".gemini", "antigravity-cli", "quota.json");
        if(Files.isRegularFile(userQuota)){
            try{return snapshot(userQuota, "Antigravity 本機 statusline 快照");}catch(Exception ignored){}
        }
        Path userStatus = Path.of(System.getProperty("user.home"), ".gemini", "antigravity-cli", "statusline.json");
        if(Files.isRegularFile(userStatus)){
            try{return snapshot(userStatus, "Antigravity 本機 statusline 快照");}catch(Exception ignored){}
        }
        return map("source","Antigravity 官方 CLI","status","點擊「開啟官方額度查詢」輸入 /usage，或連接 statusline 快照","windows",List.of());
    }
    static Map<String,Object> gemini(Map<String,Object> config, Path data){
        Path file = data.resolve("gemini-quota.json");
        if(Files.isRegularFile(file)){
            try{return snapshot(file, "Gemini 官方快照");}catch(Exception ignored){}
        }
        return map("source","Gemini 官方 CLI","status","點擊「開啟官方額度查詢」輸入 /stats model，或匯入快照","windows",List.of());
    }
    static Map<String,Object> snapshot(Path file, String source)throws Exception{
        if(!Files.isRegularFile(file)||Files.isSymbolicLink(file))return map("source",source,"status","尚未連接快照","windows",List.of());
        if(Files.size(file)>65536)throw new IOException("快照過大");
        var m=object(parse(Files.readString(file)));
        List<Object> windows=new ArrayList<>();
        var limits=m.get("rate_limits") instanceof Map?object(m.get("rate_limits")):m.get("windows") instanceof List?m:map();
        if(m.get("windows") instanceof List){
            for(Object v:array(m.get("windows"))){
                var w=object(v);
                if(w.get("usedPercent") instanceof Number)windows.add(map("name",str(w,"name"),"usedPercent",w.get("usedPercent"),"resetsAt",w.get("resetsAt")));
            }
        }else{
            for(String key:List.of("five_hour","seven_day","primary","secondary","daily")){
                if(limits.get(key)instanceof Map){
                    var w=object(limits.get(key));
                    Object pct=w.get("used_percentage")!=null?w.get("used_percentage"):w.get("usedPercent");
                    Object reset=w.get("resets_at")!=null?w.get("resets_at"):w.get("resetsAt");
                    if(pct instanceof Number)windows.add(map("name",key.equals("five_hour")?"5 小時":key.equals("seven_day")?"7 天":key,"usedPercent",pct,"resetsAt",reset));
                }
            }
        }
        return map("source",source,"status",windows.isEmpty()?"未提供額度欄位":"已取得快照","updatedAt",Files.getLastModifiedTime(file).toInstant().toString(),"windows",windows);
    }
}
