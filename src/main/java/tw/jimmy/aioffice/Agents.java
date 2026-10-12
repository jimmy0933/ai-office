package tw.jimmy.aioffice;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import static tw.jimmy.aioffice.Json.*;

final class Agents {
    static final boolean WINDOWS=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final Set<Long> groups=ConcurrentHashMap.newKeySet();
    static final List<String> PROVIDERS=List.of("Codex","Gemini","Antigravity","Claude","Company");
    final Map<String,ReentrantLock> lanes=new ConcurrentHashMap<>();
    Agents(){for(String p:PROVIDERS)lanes.put(p,new ReentrantLock(true));}
    static final class Control {
        volatile boolean cancelled;volatile Process process;volatile Thread thread;
        void check()throws InterruptedException{if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException("已停止");}
        void stop(){cancelled=true;Process p=process;if(p!=null)kill(p);Thread t=thread;if(t!=null)t.interrupt();}
    }
    record Answer(String text,Object usage,String model){}
    static void kill(Process p){
        try{
            if(groups.contains(p.pid())){
                Process killer=new ProcessBuilder("/bin/kill","-KILL","--","-"+p.pid()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if(!killer.waitFor(2,TimeUnit.SECONDS))killer.destroyForcibly();
            }else if(WINDOWS&&p.isAlive()){
                Process killer=new ProcessBuilder("taskkill.exe","/PID",Long.toString(p.pid()),"/T","/F").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if(!killer.waitFor(3,TimeUnit.SECONDS))killer.destroyForcibly();
            }
        }catch(Exception ignored){}
        try{var descendants=p.descendants().toList();for(int i=descendants.size()-1;i>=0;i--)descendants.get(i).destroyForcibly();}catch(Exception ignored){}p.destroyForcibly();
    }
    static List<String> resolve(String command,String provider)throws Exception {
        if(command.isBlank()||command.indexOf('\n')>=0)throw new IllegalArgumentException("請指定 CLI 執行檔");
        Path input=Path.of(command);List<Path> candidates=new ArrayList<>();
        if(input.isAbsolute()||input.getNameCount()>1)candidates.add(input.toAbsolutePath());
        else {String env=System.getenv("PATH");if(env==null)env=System.getenv("Path");for(String dir:(env==null?"":env).split(java.util.regex.Pattern.quote(File.pathSeparator))){if(!dir.isBlank())for(String ext:WINDOWS?List.of(".exe",".cmd",""):List.of(""))candidates.add(Path.of(dir.replace("\"",""),command+ext));}}
        if(provider.equals("Antigravity")&&command.equals("agy")){
            if(WINDOWS&&System.getenv("LOCALAPPDATA")!=null)candidates.add(Path.of(System.getenv("LOCALAPPDATA"),"agy","bin","agy.exe"));
            else candidates.add(Path.of(System.getProperty("user.home"),".local","bin","agy"));
        }
        for(Path p:candidates){if(!Files.isRegularFile(p))continue;String name=p.getFileName().toString().toLowerCase(Locale.ROOT);
            if(name.endsWith(".cmd")||name.endsWith(".bat")){
                if(provider.equals("Antigravity"))throw new IllegalArgumentException("請指定官方 Antigravity CLI 的 agy.exe，不是 IDE 的 CMD 啟動檔");
                String rel=provider.equals("Codex")?"node_modules/@openai/codex/bin/codex.js":provider.equals("Claude")?"node_modules/@anthropic-ai/claude-code/cli.js":"node_modules/@google/gemini-cli/dist/index.js";
                Path js=p.toAbsolutePath().getParent().resolve(rel);if(!Files.isRegularFile(js))throw new IllegalArgumentException("只支援官方 npm CLI 的 CMD 啟動檔；請指定 .exe 或官方 CLI 路徑");
                Path localNode=p.toAbsolutePath().getParent().resolve("node.exe");List<String> node=Files.isRegularFile(localNode)?List.of(localNode.toString()):resolve("node","Node");List<String> result=new ArrayList<>(node);result.add(js.toString());return result;
            }
            if(name.endsWith(".js")){List<String> result=new ArrayList<>(resolve("node","Node"));result.add(p.toString());return result;}
            if(!WINDOWS&&!Files.isExecutable(p))continue;return List.of(p.toAbsolutePath().toString());
        }throw new IllegalArgumentException("找不到 "+command+"；請安裝 CLI，或指定完整執行檔路徑");
    }

    static List<String> findExecutables(String provider){
        if(provider.equals("Company"))return List.of();
        Set<String> set=new LinkedHashSet<>();
        String defaultCmd=provider.equals("Codex")?"codex":provider.equals("Gemini")?"gemini":provider.equals("Antigravity")?"agy":"claude";
        try{
            resolve(defaultCmd,provider);
            set.add(defaultCmd);
        }catch(Exception ignored){}

        String env=System.getenv("PATH");if(env==null)env=System.getenv("Path");
        for(String dir:(env==null?"":env).split(java.util.regex.Pattern.quote(File.pathSeparator))){
            if(!dir.isBlank()){
                for(String ext:WINDOWS?List.of(".exe",".cmd",".bat",""):List.of("")){
                    Path p=Path.of(dir.replace("\"",""),defaultCmd+ext);
                    if(Files.isRegularFile(p)){
                        try{
                            resolve(p.toString(),provider);
                            set.add(p.toAbsolutePath().toString());
                        }catch(Exception ignored){}
                    }
                }
            }
        }
        if(provider.equals("Antigravity")){
            Path agyPath=WINDOWS&&System.getenv("LOCALAPPDATA")!=null?
                Path.of(System.getenv("LOCALAPPDATA"),"agy","bin","agy.exe"):
                Path.of(System.getProperty("user.home"),".local","bin","agy");
            if(Files.isRegularFile(agyPath)){
                try{
                    resolve(agyPath.toString(),provider);
                    set.add(agyPath.toAbsolutePath().toString());
                }catch(Exception ignored){}
            }
        }
        return new ArrayList<>(set);
    }

    List<String> discoverModels(String provider,Map<String,Object> config)throws Exception {
        if(provider.equals("Company"))return Company.fetchModels(object(config.get("Company")));
        var settings=object(config.get(provider));
        Set<String> set=new LinkedHashSet<>();
        try{
            List<String> command=new ArrayList<>(resolve(str(settings,"command"),provider));
            if(provider.equals("Antigravity")){
                command.add("models");
                Control ctrl=new Control();
                String out=execute(command,"",null,Map.of(),ctrl,10000,l->{});
                for(String line:out.split("\n")){
                    String trimmed=line.trim();
                    if(trimmed.startsWith("- ")&&trimmed.length()>2)set.add(trimmed.substring(2).trim());
                    else if(!trimmed.isBlank()&&!trimmed.contains(" ")&&!trimmed.startsWith("Usage")&&!trimmed.startsWith("Models:"))set.add(trimmed);
                }
            }
        }catch(Exception ignored){}
        return new ArrayList<>(set);
    }
    Map<String,Object> doctor(Map<String,Object> config)throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();for(String provider:PROVIDERS){
            try{var c=object(config.get(provider));if(provider.equals("Company")){Company.validate(c,false);result.put(provider,map("installed",!str(c,"baseUrl").isBlank(),"version","OpenAI 相容 HTTP；請按測試連線","authenticated",null));continue;}List<String> command=new ArrayList<>(resolve(str(c,"command"),provider));command.add(provider.equals("Antigravity")?"--help":"--version");Control ctrl=new Control();String out=execute(command,"",null,Map.of(),ctrl,12000,line->{});if(provider.equals("Antigravity")&&(!out.contains("--input-format")||!out.contains("--output-format")))throw new IOException("此執行檔不支援 headless；請安裝官方 agy CLI");
                if(provider.equals("Antigravity")){String issue=antigravityIssue();result.put(provider,map("installed",true,"version","官方 headless CLI","authenticated",null,"subscriptionReady",issue.isBlank(),"warning",issue));continue;}result.put(provider,map("installed",true,"version",out.strip(),"authenticated",null));}
            catch(Exception e){result.put(provider,map("installed",false,"error",clean(e),"authenticated",null));}}
        return result;
    }
    Answer run(String provider,String prompt,Map<String,Object> config,Path session,Control ctrl,Consumer<String> event)throws Exception {
        ReentrantLock lock=lanes.get(provider);ctrl.check();lock.lockInterruptibly();try{
            ctrl.check();var settings=object(config.get(provider));if(provider.equals("Company"))return Company.run(prompt,settings,ctrl,event);String model=str(settings,"model");List<String> command=new ArrayList<>(resolve(str(settings,"command"),provider));Files.createDirectories(session);Map<String,String> env=new HashMap<>();
            if(provider.equals("Codex")){
                List<String> statusCommand=new ArrayList<>(command);statusCommand.addAll(List.of("login","status"));
                String login=execute(statusCommand,"",session,Map.of(),ctrl,12000,s->{}).toLowerCase(Locale.ROOT);
                if(!login.contains("chatgpt")||login.contains("api key"))throw new IOException("此版只使用 ChatGPT 訂閱登入。請先執行 codex login 並選擇 ChatGPT，再重試。");
                command.addAll(List.of("exec","--ignore-user-config","--sandbox","read-only","--skip-git-repo-check","--json","-c","forced_login_method=\"chatgpt\"","-c","cli_auth_credentials_store=\"auto\"","-c","approval_policy=\"never\"","-c","features.shell_tool=false","-c","features.apply_patch_freeform=false"));
                if(!model.isBlank())command.addAll(List.of("--model",model));command.add("-");
            }else if(provider.equals("Claude")){
                List<String> auth=new ArrayList<>(command);auth.addAll(List.of("auth","status"));
                var status=object(parse(execute(auth,"",session,Map.of(),ctrl,12000,x->{}).strip()));
                if(!Boolean.TRUE.equals(status.get("loggedIn"))||!str(status,"authMethod").equals("claude.ai"))throw new IOException("請在設定頁登入 Claude 訂閱帳號；不接受 API 或第三方驗證。");
                command.addAll(List.of("-p","--output-format","json","--tools","","--strict-mcp-config","--mcp-config","{\"mcpServers\":{}}","--setting-sources","","--settings","{\"forceLoginMethod\":\"claudeai\",\"disableAllHooks\":true}","--no-session-persistence"));
                if(!model.isBlank())command.addAll(List.of("--model",model));
            }else if(provider.equals("Antigravity")){
                String issue=antigravityIssue();if(!issue.isBlank())throw new IOException(issue);
                String name="ai-office-"+UUID.randomUUID();Path agent=session.resolve(".agents/agents/"+name+"/agent.md");Files.createDirectories(agent.getParent());
                Files.writeString(agent,"---\nname: "+name+"\ndescription: AI Office text analysis without tools\nmainAgent: true\nsubagent: false\ntools: []\nmcpServers: []\nskills: []\nplugins: []\ncommandExecutionPolicy: off\n---\n"+Office.BASE);
                command.addAll(List.of("--agent",name,"--disable-slash-commands","--input-format","stream-json","--output-format","stream-json","--print-timeout","10m"));
                if(!model.isBlank())command.addAll(List.of("--model",model));
                prompt=stringify(map("event","user","message",map("content",prompt)))+"\n";
            }else{
                Path system=session.resolve("gemini-system.json");Files.writeString(system,stringify(map("tools",map("core",List.of(),"exclude",List.of("run_shell_command","write_file","replace"),"discoveryCommand","","callCommand",""),"admin",map("mcp",map("enabled",false),"extensions",map("enabled",false)),"mcpServers",map(),"hooksConfig",map("enabled",false),"skills",map("enabled",false),"advanced",map("ignoreLocalEnv",true),"privacy",map("usageStatisticsEnabled",false),"general",map("enableAutoUpdate",false))));
                var restricted=object(parse(Files.readString(system)));restricted.put("security",map("auth",map("selectedType","oauth-personal","enforcedType","oauth-personal")));Files.writeString(system,stringify(restricted));
                env.put("GEMINI_CLI_SYSTEM_SETTINGS_PATH",system.toAbsolutePath().toString());command.addAll(List.of("--prompt","請回答標準輸入提供的任務，不要使用工具。","--output-format","json","--approval-mode","default","--extensions","none"));
                if(!model.isBlank())command.addAll(List.of("--model",model));
            }
            event.accept("執行中 · "+provider);String[] answer={""};Object[] usage={null};String[] failure={""};String[] observedModel={model};boolean[] initialized={false},finished={false};
            String out=execute(command,prompt,session,env,ctrl,600000,line->{if(provider.equals("Antigravity")){
                try{var e=object(parse(line));String type=str(e,"event");
                    if(type.equals("init")){var init=object(e.get("init"));initialized[0]=true;if(!str(init,"model").isBlank())observedModel[0]=str(init,"model");event.accept("連線已初始化 · Antigravity");}
                    else if(type.equals("step_update")){if(!initialized[0])throw new IllegalArgumentException("Antigravity 缺少初始化事件");var step=object(e.get("step_update"));if(str(step,"step_type").equals("tool"))throw new IllegalArgumentException("Antigravity 嘗試使用工具，已停止");if(str(step,"state").equals("DONE"))event.accept("Antigravity · "+str(step,"step_type")+" · DONE");}
                    else if(type.equals("result")){if(!initialized[0]||finished[0])throw new IllegalArgumentException("Antigravity 結果事件無效");var result=object(e.get("result"));finished[0]=true;if(!str(result,"status").equals("SUCCESS"))failure[0]="Antigravity · "+str(result,"status")+"："+stringify(result.get("error"));else{answer[0]=str(result,"response");usage[0]=result.get("usage");}event.accept("Antigravity · "+str(result,"status"));}
                }catch(IllegalArgumentException ex){throw new IllegalStateException("Antigravity 輸出無效："+ex.getMessage(),ex);}return;
            }if(!provider.equals("Codex"))return;try{
                var e=object(parse(line));String type=str(e,"type");if(e.get("item")instanceof Map){var item=object(e.get("item"));event.accept(type+" · "+str(item,"type"));if(type.equals("item.completed")&&str(item,"type").equals("agent_message"))answer[0]=str(item,"text");}
                if(type.equals("turn.completed"))usage[0]=e.get("usage");if(type.equals("error")||type.equals("turn.failed"))failure[0]=str(e,"message")+(e.get("error")==null?"":stringify(e.get("error")));
            }catch(IllegalArgumentException ignored){}});
            ctrl.check();if(provider.equals("Claude")){var obj=object(parse(out.strip()));if(Boolean.TRUE.equals(obj.get("is_error")))throw new IOException("Claude 未完成："+str(obj,"result"));answer[0]=str(obj,"result");usage[0]=obj.get("usage");}
            if(provider.equals("Gemini")){
                var obj=object(parse(out.strip()));if(obj.get("error")!=null)throw new IOException("Gemini："+stringify(obj.get("error")));answer[0]=str(obj,"response");usage[0]=obj.get("stats");
                if(usage[0]instanceof Map m&&m.get("models")instanceof Map models)model=String.join(", ",models.keySet().stream().map(Object::toString).toList());
            }
            if(provider.equals("Antigravity")){if(!finished[0])throw new IOException("Antigravity 沒有回報最終結果");model=observedModel[0];}
            if(!failure[0].isBlank())throw new IOException(failure[0]);if(answer[0].isBlank())throw new IOException("模型未回傳回答；請檢查 CLI 版本與登入");
            return new Answer(answer[0],usage[0],model.isBlank()?"CLI 預設":model);
        }finally{lock.unlock();}
    }
    Object login(Map<String,Object> config)throws Exception {
        List<String> cmd=new ArrayList<>(resolve(str(object(config.get("Claude")),"command"),"Claude"));cmd.addAll(List.of("auth","login"));
        return terminal(cmd,"已開啟 Claude 官方登入視窗；請完成瀏覽器登入後再檢查。");
    }
    Object quotaTerminal(String provider,Map<String,Object> config)throws Exception{
        if(!List.of("Gemini","Antigravity","Claude").contains(provider))throw new IllegalArgumentException("此工具使用面板查詢");
        return terminal(resolve(str(object(config.get(provider)),"command"),provider),"已開啟官方 CLI，請輸入 "+(provider.equals("Gemini")?"/stats model":"/usage")+" 查看額度。");
    }
    Object terminal(List<String> cmd,String message)throws Exception{
        ProcessBuilder pb;
        if(WINDOWS){String script="& "+String.join(" ",cmd.stream().map(x->"'"+x.replace("'","''")+"'").toList());String encoded=Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));pb=new ProcessBuilder("powershell.exe","-NoExit","-NoProfile","-EncodedCommand",encoded);}
        else if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")){String script=String.join(" ",cmd.stream().map(x->"'"+x.replace("'","'\\''")+"'").toList());pb=new ProcessBuilder("osascript","-e","tell application \"Terminal\" to do script \""+script.replace("\\","\\\\").replace("\"","\\\"")+"\"");}
        else{List<String> terminal=new ArrayList<>(resolve("x-terminal-emulator","Terminal"));terminal.add("-e");terminal.addAll(cmd);pb=new ProcessBuilder(terminal);}
        for(String key:new ArrayList<>(pb.environment().keySet()))if(key.startsWith("ANTHROPIC_")||key.startsWith("CLAUDE_"))pb.environment().remove(key);
        pb.start();return map("ok",true,"message",message);
    }
    // Read preferences only; never open token stores or change the user's global CLI settings.
    static String antigravityIssue()throws Exception {
        Path settings=Path.of(System.getProperty("user.home"),".gemini","antigravity-cli","settings.json");
        if(!Files.exists(settings))return "";if(Files.size(settings)>1024*1024)throw new IOException("Antigravity 設定檔過大");
        var c=object(parse(Files.readString(settings)));
        if(!str(c,"modelProvider").isBlank())return "Antigravity 設為 API provider。請在 ~/.gemini/antigravity-cli/settings.json 移除 modelProvider，再用 agy 登入 Google 訂閱帳號。";
        if(c.containsKey("useG1Credits")&&!Boolean.FALSE.equals(c.get("useG1Credits")))return "Antigravity 已啟用 AI 點數。請在 agy /config 關閉 Use AI Credits（useG1Credits=false），此版只用訂閱額度。";
        if(c.containsKey("allowNonWorkspaceAccess")&&!Boolean.FALSE.equals(c.get("allowNonWorkspaceAccess")))return "請在 agy /config 關閉 Non-Workspace Access，再重試。";
        return "";
    }
    static String execute(List<String> command,String input,Path cwd,Map<String,String> env,Control ctrl,long timeout,Consumer<String> line)throws Exception {
        ctrl.check();boolean group=!WINDOWS&&System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")&&Files.isExecutable(Path.of("/usr/bin/setsid"))&&Files.isExecutable(Path.of("/bin/kill"));
        List<String> launch=new ArrayList<>();if(group)launch.addAll(List.of("/usr/bin/setsid","--"));launch.addAll(command);
        ProcessBuilder pb=new ProcessBuilder(launch);if(cwd!=null)pb.directory(cwd.toFile());
        // Subscription-only mode: do not inherit API billing credentials or Vertex routing.
        for(String key:List.of("OPENAI_API_KEY","CODEX_API_KEY","GEMINI_API_KEY","GOOGLE_API_KEY","GOOGLE_GENAI_USE_VERTEXAI","GOOGLE_GENAI_USE_GCA","GOOGLE_APPLICATION_CREDENTIALS","GOOGLE_GEMINI_BASE_URL","GEMINI_CLI_USE_COMPUTE_ADC","CLOUD_SHELL","GEMINI_DEFAULT_AUTH_TYPE","AGY_LLM_GATEWAY_API_KEY","AGY_LLM_GATEWAY_URL","AGY_LLM_GATEWAY_HEADERS","AGY_LLM_GATEWAY_MODELS","AGY_LLM_GATEWAY_PROXY_URL","AGY_LLM_GATEWAY_WIRE_PROTOCOL","AGY_BUSINESS_PAYGO_TIER","AGY_ADC_AUTH"))pb.environment().remove(key);
        for(String key:new ArrayList<>(pb.environment().keySet()))if(key.startsWith("ANTHROPIC_")||key.startsWith("CLAUDE_CODE_")||key.startsWith("CLAUDE_"))pb.environment().remove(key);
        pb.environment().putAll(env);Process p=pb.start();if(group)groups.add(p.pid());ctrl.process=p;
        ExecutorService readers=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"cli-output");t.setDaemon(true);return t;});
        Future<String> out=readers.submit(()->{try{return read(p.getInputStream(),8*1024*1024,line);}catch(Exception e){kill(p);throw e;}});Future<String> err=readers.submit(()->{try{return read(p.getErrorStream(),256*1024,s->{});}catch(Exception e){kill(p);throw e;}});
        try{ctrl.check();try(OutputStream stdin=p.getOutputStream()){stdin.write(input.getBytes(StandardCharsets.UTF_8));}
            if(!p.waitFor(timeout,TimeUnit.MILLISECONDS)){kill(p);throw new IOException("CLI 超時，已停止（10 分鐘上限）");}
            ctrl.check();String stdout=out.get(5,TimeUnit.SECONDS),stderr=err.get(5,TimeUnit.SECONDS);
            if(p.exitValue()!=0)throw new IOException("CLI exit "+p.exitValue()+"："+(stderr.isBlank()?stdout:stderr).substring(0,Math.min(2500,(stderr.isBlank()?stdout:stderr).length())));
            return stdout.isBlank()?stderr:stdout;
        }finally{kill(p);groups.remove(p.pid());ctrl.process=null;readers.shutdownNow();}
    }
    private static String read(InputStream stream,int limit,Consumer<String> line)throws Exception {
        StringBuilder full=new StringBuilder(),current=new StringBuilder();try(Reader r=new InputStreamReader(stream,StandardCharsets.UTF_8)){int c;while((c=r.read())!=-1){if(full.length()>=limit)throw new IOException("CLI 輸出超過上限");full.append((char)c);if(c=='\n'){line.accept(current.toString());current.setLength(0);}else current.append((char)c);}if(!current.isEmpty())line.accept(current.toString());}return full.toString();
    }
    static String clean(Throwable ex){String s=ex.getMessage();if(s==null)s=ex.getClass().getSimpleName();return s.replaceAll("(?i)(bearer\\s+|api[_-]?key[=:]\\s*)[^\\s\"']+","$1[已隱藏]").replaceAll("\\x1B\\[[0-9;]*[A-Za-z]","");}
}
