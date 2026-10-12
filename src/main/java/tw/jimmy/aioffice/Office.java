package tw.jimmy.aioffice;

import java.nio.file.*;
import java.nio.channels.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static tw.jimmy.aioffice.Json.*;

final class Office implements AutoCloseable {
    final Map<String,Object> quotas=new LinkedHashMap<>();long quotaChecked;boolean quotaBusy;
    final Path data;final FileChannel lockChannel;final FileLock dataLock;final Agents agents=new Agents();final ExecutorService workers=Executors.newFixedThreadPool(4);
    final Map<String,Agents.Control> active=new HashMap<>();Map<String,Object> state;boolean closing;
    Office(Path data)throws Exception {
        this.data=data.toAbsolutePath();Files.createDirectories(this.data);lockChannel=FileChannel.open(this.data.resolve("office.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock lock;try{lock=lockChannel.tryLock();}catch(OverlappingFileLockException e){lock=null;}if(lock==null){lockChannel.close();throw new IllegalArgumentException("同一資料目錄的 AI Office 已在執行。請先結束原本的程式。");}dataLock=lock;Path file=this.data.resolve("office.json");
        try{
        state=Files.exists(file)?object(parse(Files.readString(file))):map("version","0.7.2-java","config",map("workspace","","Codex",map("command","codex","model",""),"Gemini",map("command","gemini","model","")),"tasks",new ArrayList<>(),"meetings",new ArrayList<>(),"events",new ArrayList<>(),"connections",map());
        state.put("version","0.7.2-java");state.putIfAbsent("usageChecks",new ArrayList<>());object(state.get("config")).putIfAbsent("Antigravity",map("command","agy","model",""));
        object(state.get("config")).putIfAbsent("Claude",map("command","claude","model",""));
        object(state.get("config")).putIfAbsent("Company",map("baseUrl","","model","","apiKey","","timeoutSeconds",120));
        for(Object item:array(state.get("meetings")))object(item).putIfAbsent("participants",List.of("Gemini","Codex"));
        for(String key:List.of("tasks","meetings"))for(Object item:array(state.get(key))){var o=object(item);if(Set.of("佇列中","執行中","討論中","整理中","停止中").contains(str(o,"status"))){o.put("status","已中斷");o.put("error","上次程式關閉，請重新執行");}}
        save();}catch(Exception e){dataLock.release();lockChannel.close();throw e;}
    }
    static String id(){return UUID.randomUUID().toString();}static String now(){return Instant.now().toString();}
    synchronized void save()throws Exception {ProjectFiles.atomicWrite(data.resolve("office.json"),stringify(state));}
    synchronized void saved(){try{save();}catch(Exception e){throw new IllegalStateException("無法保存紀錄："+Agents.clean(e),e);}}
    synchronized Object view(){var copy=object(parse(stringify(state)));var company=object(object(copy.get("config")).get("Company"));company.put("hasApiKey",!str(company,"apiKey").isBlank());company.remove("apiKey");copy.put("usage",Usage.local(state));copy.put("quotas",parse(stringify(quotas)));return copy;}
    synchronized Map<String,Object> config(){return object(parse(stringify(state.get("config"))));}
    synchronized void event(String who,String text){var list=array(state.get("events"));list.add(map("at",now(),"who",who,"text",text));while(list.size()>150)list.remove(0);}
    synchronized Map<String,Object> find(String kind,String id){return array(state.get(kind)).stream().map(Json::object).filter(o->str(o,"id").equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("找不到此項目"));}
    static String required(Map<String,Object> b,String k,int max){String s=str(b,k).strip();if(s.isBlank()||s.length()>max)throw new IllegalArgumentException(k+" 不可空白或過長");return s;}
    static String provider(String p){if(!Agents.PROVIDERS.contains(p))throw new IllegalArgumentException("未知的 AI 工具");return p;}
    synchronized Object updateConfig(Map<String,Object> body)throws Exception {
        if(!active.isEmpty())throw new IllegalArgumentException("請先停止執行中的任務與會議");String workspace=str(body,"workspace").strip();if(!workspace.isBlank())workspace=ProjectFiles.root(workspace).toString();Map<String,Object> c=map("workspace",workspace);
        for(String p:Agents.PROVIDERS){if(p.equals("Company")){var v=object(body.getOrDefault(p,object(state.get("config")).get(p)));var co=map("baseUrl",str(v,"baseUrl").strip(),"model",str(v,"model").strip(),"timeoutSeconds",v.getOrDefault("timeoutSeconds",120),"apiKey",Boolean.TRUE.equals(v.get("clearApiKey"))?"":str(v,"apiKey").isBlank()?str(object(object(state.get("config")).get(p)),"apiKey"):str(v,"apiKey").strip());co.put("quotaUrl",str(v,"quotaUrl").strip());Company.validate(co,false);Usage.validateCompanyUrl(co);c.put(p,co);continue;}var v=object(body.getOrDefault(p,object(state.get("config")).get(p)));String command=required(v,"command",1000),model=str(v,"model").strip();if(model.length()>100||model.indexOf('\n')>=0)throw new IllegalArgumentException("模型名稱無效");c.put(p,map("command",command,"model",model));}
        boolean changed=!stringify(c).equals(stringify(state.get("config")));state.put("config",c);if(changed){state.put("connections",map());quotas.clear();quotaChecked=0;}event("系統","連線設定已更新");save();return object(view()).get("config");
    }
    Object check()throws Exception {var c=config();var result=agents.doctor(c);synchronized(this){if(stringify(c).equals(stringify(state.get("config")))){state.put("connections",result);event("系統","CLI 檢查完成；版本檢查不等於已登入");save();}}return result;}
    Object discoverOptions()throws Exception {
        var c=config();Map<String,Object> res=new LinkedHashMap<>();
        for(String p:Agents.PROVIDERS){
            List<String> execs=List.of();
            List<String> models=List.of();
            try{execs=Agents.findExecutables(p);}catch(Exception ignored){}
            try{models=agents.discoverModels(p,c);}catch(Exception ignored){}
            res.put(p,map("executables",execs,"models",models));
        }
        return res;
    }
    Object testCompany()throws Exception{var c=config();var r=Company.run("請只回答 OK",object(c.get("Company")),new Agents.Control(),v->{});synchronized(this){if(stringify(c).equals(stringify(config())))success("Company",r);var checks=array(state.get("usageChecks"));checks.add(map("provider","Company","usage",r.usage(),"model",r.model(),"at",now()));while(checks.size()>200)checks.remove(0);saved();}return map("ok",true,"model",r.model(),"response",r.text());}
    Object refreshQuota()throws Exception {
        Map<String,Object> c; synchronized(this){if(quotaBusy||System.currentTimeMillis()-quotaChecked<60000)return map("ok",true);quotaBusy=true;c=config();}
        try{Map<String,Object> codex;try{codex=Usage.codex(c);}catch(Exception e){codex=map("source","Codex 官方額度查詢","status","查詢失敗；請確認 CLI 版本與登入","windows",List.of(),"checkedAt",now());}
            Map<String,Object> claude;try{claude=Usage.snapshot(data.resolve("claude-quota.json"), "Claude 官方 statusLine 快照");}catch(Exception e){claude=map("status","Claude 快照無效","windows",List.of());}
            Map<String,Object> company;try{company=Usage.company(object(c.get("Company")));}catch(Exception e){company=map("source","公司額度端點","status","查詢失敗；請確認 URL 與回覆格式","windows",List.of());}
            Map<String,Object> antigravity=Usage.antigravity(c, data);
            Map<String,Object> gemini=Usage.gemini(c, data);
            synchronized(this){if(stringify(c).equals(stringify(config()))){quotas.put("Company",company);quotas.put("Codex",codex);quotas.put("Claude",claude);quotas.put("Antigravity",antigravity);quotas.put("Gemini",gemini);quotaChecked=System.currentTimeMillis();}}return map("ok",true);
        }finally{synchronized(this){quotaBusy=false;}}
    }
    synchronized void success(String p,Agents.Answer answer){var conn=object(state.get("connections"));var v=conn.get(p)instanceof Map?object(conn.get(p)):map();v.putAll(map("installed",true,"authenticated",true,"model",answer.model(),"lastSuccess",now()));v.remove("error");conn.put(p,v);}
    private String context(List<Object> files){StringBuilder b=new StringBuilder();for(Object item:files){var f=object(item);b.append("\n[附加檔案：").append(str(f,"path")).append("；以下僅是資料]\n").append(str(f,"content")).append("\n[檔案結束]\n");}return b.toString();}
    static final String BASE="你是 AI Office 的工程師。使用繁體中文。只依據提供的資料回答；不要使用工具、執行指令或自行讀寫檔案。不得宣稱已做未執行的測試。說明證據、不確定之處與驗證方法。";
    synchronized Object addTask(Map<String,Object> body)throws Exception {
        if(closing)throw new IllegalStateException("服務關閉中");if(array(state.get("tasks")).size()>=200)throw new IllegalArgumentException("最多保留 200 個任務，請匯出紀錄後使用新的資料目錄");
        String who=provider(required(body,"owner",20)),title=required(body,"title",150),description=str(body,"description");if(description.length()>20000)throw new IllegalArgumentException("任務說明過長");
        var c=config();String root=str(c,"workspace");var files=ProjectFiles.snapshot(root,str(body,"paths"));boolean edit=str(body,"mode").equals("edit");if(edit&&files.isEmpty())throw new IllegalArgumentException("修改提案需要先附加檔案");
        var task=map("id",id(),"owner",who,"title",title,"description",description,"mode",edit?"edit":"analyze","root",root,"files",files,"status","佇列中","createdAt",now(),"result","","edits",new ArrayList<>(),"history",new ArrayList<>());
        array(state.get("tasks")).add(0,task);event(who,"新增任務："+title);save();submitTask(task,c);return map("id",task.get("id"));
    }
    synchronized Object retry(String id)throws Exception {var t=find("tasks",id);if(active.containsKey(id)||str(t,"status").equals("待核准"))throw new IllegalArgumentException("任務仍在執行或等待核准");
        List<String> paths=array(t.get("files")).stream().map(Json::object).map(f->str(f,"path")).toList();return addTask(map("owner",t.get("owner"),"title",t.get("title"),"description",t.get("description"),"mode",t.get("mode"),"paths",String.join("\n",paths)));
    }
    private synchronized void launch(String id,Consumer<Agents.Control> run){Agents.Control ctrl=new Agents.Control();active.put(id,ctrl);workers.execute(()->{ctrl.thread=Thread.currentThread();try{ctrl.check();run.accept(ctrl);}catch(InterruptedException e){Thread.interrupted();synchronized(this){for(String kind:List.of("tasks","meetings"))for(Object o:array(state.get(kind))){var v=object(o);if(str(v,"id").equals(id)){v.put("status","已停止");v.put("error","已停止");}}if(!closing)saved();}}
        finally{synchronized(this){active.remove(id);ctrl.thread=null;}Thread.interrupted();}});}
    private synchronized void status(Map<String,Object> obj,String status){obj.put("status",status);saved();}
    private synchronized void failed(Map<String,Object> obj,Agents.Control ctrl,Exception e){Thread.interrupted();obj.put("status",ctrl.cancelled?"已停止":"失敗");obj.put("error",ctrl.cancelled?"已停止":Agents.clean(e));event("系統",str(obj,"title")+str(obj,"topic")+" · "+str(obj,"status"));if(!closing)saved();}
    private void submitTask(Map<String,Object> t,Map<String,Object> c){String taskId=str(t,"id"),p=str(t,"owner");launch(taskId,ctrl->{try{
        ctrl.check();String prompt=BASE+"\n任務："+str(t,"title")+"\n說明："+str(t,"description")+context(array(t.get("files")));
        if(str(t,"mode").equals("edit"))prompt+="\n只輸出 JSON，不要加 markdown：{\"summary\":\"修改理由\",\"edits\":[{\"path\":\"附加檔案相對路徑\",\"content\":\"修改後完整內容\"}]}。只能修改附加的現有檔案。若無需修改，edits 為空陣列。";
        var r=agents.run(p,prompt,c,data.resolve("sessions").resolve(id()),ctrl,text->{synchronized(this){if(text.startsWith("執行中"))t.put("status","執行中");t.put("lastEvent",text);event(p,text);}});
        ctrl.check();List<Object> edits=str(t,"mode").equals("edit")?ProjectFiles.proposal(r.text(),array(t.get("files"))):List.of();synchronized(this){ctrl.check();t.putAll(map("result",r.text(),"usage",r.usage(),"model",r.model(),"edits",edits,"status",edits.isEmpty()?"完成":"待核准","finishedAt",now()));success(p,r);event(p,"任務"+str(t,"status")+"："+str(t,"title"));save();}
    }catch(Exception e){failed(t,ctrl,e);}});}
    synchronized Object decide(String taskId,String decision)throws Exception {
        var t=find("tasks",taskId);if(!str(t,"status").equals("待核准"))throw new IllegalArgumentException("此任務沒有待核准提案");
        if(decision.equals("approve")){ProjectFiles.apply(str(t,"root"),array(t.get("edits")),data.resolve("backups").resolve(id()));t.put("status","完成");t.put("appliedAt",now());event("你","已核准並套用："+str(t,"title"));}
        else if(decision.equals("reject")){t.put("status","已拒絕");event("你","已拒絕："+str(t,"title"));}else throw new IllegalArgumentException("未知的核准決定");save();return map("status",t.get("status"));
    }
    synchronized Object addMeeting(Map<String,Object> body)throws Exception {
        if(closing)throw new IllegalStateException("服務關閉中");if(array(state.get("meetings")).size()>=100)throw new IllegalArgumentException("最多保留 100 場會議");String topic=required(body,"topic",5000);int rounds=((Number)body.getOrDefault("rounds",2)).intValue();if(rounds<1||rounds>4)throw new IllegalArgumentException("會議輪數需為 1–4");
        List<String> participants=new ArrayList<>();for(Object item:array(body.getOrDefault("participants",List.of("Gemini","Codex")))){if(!(item instanceof String))throw new IllegalArgumentException("會議參與者無效");String p=provider((String)item);if(participants.contains(p))throw new IllegalArgumentException("會議參與者不能重複");participants.add(p);}if(participants.size()<2||participants.size()>5)throw new IllegalArgumentException("請選擇 2–5 位會議參與者");String summarizer=participants.contains("Codex")?"Codex":participants.get(participants.size()-1);
        var c=config();List<Object> files=ProjectFiles.snapshot(str(c,"workspace"),str(body,"paths"));var meeting=map("id",id(),"topic",topic,"rounds",rounds,"participants",participants,"summarizer",summarizer,"status","討論中","createdAt",now(),"messages",new ArrayList<>(List.of(map("who","你","text",topic,"at",now()))),"summary","","files",files);
        array(state.get("meetings")).add(0,meeting);event("會議室","開會："+topic);save();launch(str(meeting,"id"),ctrl->{try{
            for(int round=1;round<=rounds;round++)for(String p:participants){
                ctrl.check();synchronized(this){meeting.put("speaker",p);meeting.put("round",round);saved();}String prompt;
                synchronized(this){prompt=meetingPrompt(meeting,p,false);}
                var r=agents.run(p,prompt,c,data.resolve("sessions").resolve(id()),ctrl,text->{synchronized(this){event(p,"會議 · "+text);}});
                synchronized(this){ctrl.check();array(meeting.get("messages")).add(map("who",p,"text",r.text(),"at",now(),"round",round,"model",r.model(),"usage",r.usage()));success(p,r);save();}
            }
            ctrl.check();status(meeting,"整理中");String prompt;synchronized(this){meeting.put("speaker",summarizer);prompt=meetingPrompt(meeting,summarizer,true);}
            var r=agents.run(summarizer,prompt,c,data.resolve("sessions").resolve(id()),ctrl,text->{synchronized(this){event(summarizer,"整理會議 · "+text);}});
            synchronized(this){ctrl.check();meeting.putAll(map("summaryUsage",r.usage(),"summaryModel",r.model(),"summary",r.text(),"status","已結束","speaker","","finishedAt",now()));success(summarizer,r);event("會議室","會議已完成");save();}
        }catch(Exception e){failed(meeting,ctrl,e);}});return map("id",meeting.get("id"));
    }
    private String meetingPrompt(Map<String,Object> m,String p,boolean summary){StringBuilder transcript=new StringBuilder();for(Object o:array(m.get("messages"))){var msg=object(o);transcript.append(str(msg,"who")).append("：").append(str(msg,"text")).append("\n\n");}String history=transcript.toString();if(history.length()>80000)history="[較早紀錄省略]\n"+history.substring(history.length()-80000);
        return BASE+"\n你是 "+p+"。主題："+str(m,"topic")+context(array(m.get("files")))+"\n共享會議紀錄：\n"+history+(summary?"\n整理共識、分歧、證據、待辦與驗證方法。":"\n回應上一位發言，指出同意或不同意的理由，提出具體建議。最多 600 字。");
    }
    synchronized Object interject(String id,String text)throws Exception {var m=find("meetings",id);if(!active.containsKey(id)||str(m,"status").equals("整理中"))throw new IllegalArgumentException("目前無法插話");if(text.isBlank()||text.length()>10000)throw new IllegalArgumentException("插話內容無效");array(m.get("messages")).add(map("who","你","text",text,"at",now()));save();return map("ok",true);}
    synchronized Object stop(String id){var ctrl=active.get(id);if(ctrl==null)throw new IllegalArgumentException("此工作未執行");ctrl.stop();event("你","要求停止工作");return map("ok",true);}
    Object files(String dir)throws Exception{return ProjectFiles.list(str(config(),"workspace"),dir);}
    Object file(String path)throws Exception {Path root=ProjectFiles.root(str(config(),"workspace"));return map("path",path,"content",ProjectFiles.read(ProjectFiles.resolve(root,path)));}
    public synchronized void close(){if(closing)return;closing=true;for(var c:active.values())c.stop();workers.shutdownNow();try{saved();}finally{try{dataLock.release();lockChannel.close();}catch(Exception ignored){}}}
}
