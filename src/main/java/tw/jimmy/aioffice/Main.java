package tw.jimmy.aioffice;

import com.sun.net.httpserver.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import static tw.jimmy.aioffice.Json.*;

final class Log {
    private static Path logPath;
    static synchronized void init(Path dir){
        try{logPath=dir.resolve("ai-office.log").toAbsolutePath();}catch(Exception ignored){}
    }
    static synchronized void info(String msg){write("INFO",msg,null);}
    static synchronized void error(String msg,Throwable t){write("ERROR",msg,t);}
    private static void write(String level,String msg,Throwable t){
        String time=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String line="["+time+"] ["+level+"] "+msg+(t==null?"":" - "+Agents.clean(t));
        System.out.println(line);
        if(logPath!=null){
            try{Files.writeString(logPath,line+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}
        }
    }
}

public final class Main {
    static Path jarDir(){
        try{
            URI uri=Main.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p=Path.of(uri).toAbsolutePath();
            return Files.isDirectory(p)?p:p.getParent();
        }catch(Exception e){
            return Path.of(".").toAbsolutePath().normalize();
        }
    }
    final String token=UUID.randomUUID().toString();final Office office;final HttpServer server;final String origin;final ExecutorService http=Executors.newFixedThreadPool(8);
    Main(Path data,int port)throws Exception{server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),32);try{office=new Office(data);}catch(Exception e){server.stop(0);http.shutdownNow();throw e;}origin="http://127.0.0.1:"+server.getAddress().getPort();server.createContext("/",this::handle);server.setExecutor(http);}
    private void handle(HttpExchange x)throws IOException {
        try{
            String host=x.getRequestHeaders().getFirst("Host");if(!("127.0.0.1:"+server.getAddress().getPort()).equals(host)){respond(x,403,map("error","Invalid host"));return;}
            String requestOrigin=x.getRequestHeaders().getFirst("Origin"),fetch=x.getRequestHeaders().getFirst("Sec-Fetch-Site");
            if(requestOrigin!=null&&!requestOrigin.equals(origin)||"cross-site".equals(fetch)){respond(x,403,map("error","不允許跨站存取"));return;}
            Headers h=x.getResponseHeaders();h.set("X-Content-Type-Options","nosniff");h.set("Cache-Control","no-store");h.set("Referrer-Policy","no-referrer");h.set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();if(!path.startsWith("/api/")){
                if(!method.equals("GET")){respond(x,405,map("error","Method not allowed"));return;}
                String file=switch(path){case "/"->"index.html";case "/app.js"->"app.js";case "/style.css"->"style.css";default->null;};
                if(file==null){respond(x,404,map("error","Not found"));return;}
                byte[] bytes;try(InputStream in=Main.class.getResourceAsStream("/public/"+file)){if(in==null)throw new IOException("缺少介面資源");bytes=in.readAllBytes();}
                if(file.equals("index.html"))bytes=new String(bytes,StandardCharsets.UTF_8).replace("__TOKEN__",token).getBytes(StandardCharsets.UTF_8);
                h.set("Content-Type",file.endsWith(".js")?"text/javascript; charset=utf-8":file.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);return;
            }
            if(!token.equals(x.getRequestHeaders().getFirst("X-Office-Token"))){respond(x,403,map("error","請重新開啟辦公室頁面"));return;}
            if(method.equals("GET")){
                var query=query(x.getRequestURI().getRawQuery());Object result=switch(path){case "/api/state"->office.view();case "/api/files"->office.files(query.getOrDefault("dir",""));case "/api/file"->office.file(query.getOrDefault("path",""));case "/api/export"->office.view();case "/api/options"->office.discoverOptions();default->throw new IllegalArgumentException("找不到此 API");};respond(x,200,result);return;
            }
            if(!method.equals("POST")){respond(x,405,map("error","Method not allowed"));return;}
            if(!origin.equals(requestOrigin)){respond(x,403,map("error","Origin 驗證失敗"));return;}
            if(!Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").startsWith("application/json")){respond(x,415,map("error","需要 JSON"));return;}
            byte[] bytes=x.getRequestBody().readNBytes(1024*1024+1);if(bytes.length>1024*1024){respond(x,413,map("error","請求過大"));return;}var body=object(parse(new String(bytes,StandardCharsets.UTF_8)));
            Object result=switch(path){
                case "/api/quota-terminal"->office.agents.quotaTerminal(str(body,"provider"),office.config());case "/api/quota-refresh"->office.refreshQuota();case "/api/claude-login"->office.agents.login(office.config());case "/api/company-test"->office.testCompany();
                case "/api/config"->office.updateConfig(body);case "/api/check"->office.check();case "/api/task"->office.addTask(body);
                case "/api/retry"->office.retry(str(body,"id"));case "/api/task-followup"->office.followupTask(str(body,"id"),str(body,"text"));case "/api/task-delete"->office.deleteTask(str(body,"id"));case "/api/stop"->office.stop(str(body,"id"));case "/api/approval"->office.decide(str(body,"id"),str(body,"decision"));
                case "/api/meeting"->office.addMeeting(body);case "/api/interject"->office.interject(str(body,"id"),str(body,"text"));
                default->throw new IllegalArgumentException("找不到此 API");};respond(x,200,result);
        }catch(IllegalArgumentException e){respond(x,400,map("error",Agents.clean(e)));}catch(Exception e){respond(x,500,map("error",Agents.clean(e)));}finally{x.close();}
    }
    static Map<String,String> query(String s){Map<String,String> q=new HashMap<>();if(s!=null)for(String p:s.split("&")){String[] kv=p.split("=",2);q.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),kv.length>1?URLDecoder.decode(kv[1],StandardCharsets.UTF_8):"");}return q;}
    static void respond(HttpExchange x,int status,Object result)throws IOException {byte[] b=stringify(result).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);}
    void close(){server.stop(0);office.close();http.shutdownNow();}
    static void browse(String url){try{if(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)){Desktop.getDesktop().browse(URI.create(url));return;}
        if(Agents.WINDOWS)new ProcessBuilder("rundll32.exe","url.dll,FileProtocolHandler",url).start();
        else if(System.getProperty("os.name").toLowerCase().contains("mac"))new ProcessBuilder("open",url).start();else new ProcessBuilder("xdg-open",url).start();
    }catch(Exception e){System.out.println("請自行開啟："+url);}}
    void tray(){try{if(!SystemTray.isSupported())return;BufferedImage icon=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);Graphics g=icon.getGraphics();g.setColor(new Color(0x152526));g.fillRect(0,0,32,32);g.setColor(new Color(0xbaf39b));g.fillRect(7,7,18,14);g.fillRect(13,21,6,5);g.dispose();PopupMenu menu=new PopupMenu();MenuItem open=new MenuItem("Open AI Office"),exit=new MenuItem("Exit");open.addActionListener(e->browse(origin));exit.addActionListener(e->System.exit(0));menu.add(open);menu.add(exit);TrayIcon tray=new TrayIcon(icon,"AI Office Java",menu);tray.setImageAutoSize(true);tray.addActionListener(e->browse(origin));SystemTray.getSystemTray().add(tray);}catch(Exception ignored){}}
    public static void main(String[] args){try{
        if(args.length>=2&&(args[0].equals("--capture-claude-quota")||args[0].equals("--capture-antigravity-quota")||args[0].equals("--capture-gemini-quota")||args[0].equals("--capture-quota"))){
            byte[] bytes=System.in.readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException("輸入過大");
            var json=object(parse(new String(bytes,StandardCharsets.UTF_8)));
            String targetPath=args[0].equals("--capture-quota")?(args.length>2?args[2]:args[1]):args[1];
            Path target=Path.of(targetPath).toAbsolutePath();Files.createDirectories(target.getParent());
            ProjectFiles.atomicWrite(target,stringify(args[0].equals("--capture-claude-quota")?map("rate_limits",json.getOrDefault("rate_limits",map())):json));
            System.out.println("AI Office quota synced");return;
        }

        Log.init(jarDir());
        Path data=Path.of(System.getProperty("user.home"),".ai-office-java");int port=4317;boolean browser=true;
        for(int i=0;i<args.length;i++)switch(args[i]){case "--data"->data=Path.of(args[++i]);case "--port"->port=Integer.parseInt(args[++i]);case "--no-browser"->browser=false;case "--help"->{System.out.println("AI Office Java: java -jar AI-Office-Java.jar [--port 4317] [--data PATH] [--no-browser]");return;}default->throw new IllegalArgumentException("未知參數："+args[i]);}
        if(port<0||port>65535)throw new IllegalArgumentException("埠號無效");Main app=new Main(data,port);Runtime.getRuntime().addShutdownHook(new Thread(app::close));app.server.start();
        Log.info("AI Office Java 0.7.2 started in JAR dir: "+jarDir()+", data dir: "+data.toAbsolutePath()+", server: "+app.origin);
        System.out.println("AI Office Java 0.7.2\n辦公室："+app.origin+"\n紀錄："+data.toAbsolutePath()+"\n僅限本機 · Ctrl+C 停止");
        if(browser){app.tray();browse(app.origin);}
    }catch(Exception e){String message="無法啟動："+Agents.clean(e)+"\n若埠已被使用，請先結束已開啟的辦公室，或加上 --port 4318。";System.err.println(message);if(!List.of(args).contains("--no-browser")&&!GraphicsEnvironment.isHeadless())javax.swing.JOptionPane.showMessageDialog(null,message,"AI Office",javax.swing.JOptionPane.ERROR_MESSAGE);System.exit(1);}}
}
