package tw.jimmy.aioffice;

import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static tw.jimmy.aioffice.Json.*;

final class ProjectFiles {
    static Path root(String s)throws Exception {if(s.isBlank())throw new IllegalArgumentException("請先設定專案資料夾");Path p=Path.of(s).toRealPath();if(!Files.isDirectory(p))throw new IllegalArgumentException("專案路徑不是資料夾");return p;}
    static boolean hidden(String s){String name=s.toLowerCase(Locale.ROOT);return s.startsWith(".")||Set.of("node_modules","data","build","target","id_rsa","id_ed25519","id_dsa","id_ecdsa","credentials.json","service-account.json").contains(name)||List.of(".key",".pem",".p12",".pfx",".jks",".keystore").stream().anyMatch(name::endsWith);}
    static Path resolve(Path root,String rel)throws Exception {
        if(rel.isBlank()||rel.contains("\\")||rel.contains(":")||Path.of(rel).isAbsolute())throw new IllegalArgumentException("請使用專案內的相對路徑");
        Path p=root;for(String part:rel.split("/",-1)){if(part.isBlank()||part.equals("..")||hidden(part))throw new IllegalArgumentException("不允許此路徑："+rel);p=p.resolve(part);if(Files.isSymbolicLink(p))throw new IllegalArgumentException("不接受符號連結");}
        if(!p.toRealPath().startsWith(root))throw new IllegalArgumentException("路徑超出專案");return p;
    }
    static String hash(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    static String read(Path p)throws Exception {
        if(!Files.isRegularFile(p)||Files.size(p)>256*1024)throw new IllegalArgumentException("只支援 256 KB 以内的文字檔");
        byte[] bytes=Files.readAllBytes(p);for(byte b:bytes)if(b==0)throw new IllegalArgumentException("不支援二進位檔");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();if(java.util.regex.Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----").matcher(text).find())throw new IllegalArgumentException("不允許附加私鑰內容");return text;
    }
    static List<Object> snapshot(String base,String lines)throws Exception {
        List<String> paths=lines.lines().map(String::trim).filter(s->!s.isEmpty()).distinct().toList();if(paths.size()>12)throw new IllegalArgumentException("最多附加 12 個檔案");
        List<Object> result=new ArrayList<>();if(paths.isEmpty())return result;Path root=root(base);int size=0;
        for(String rel:paths){String text=read(resolve(root,rel));size+=text.getBytes(StandardCharsets.UTF_8).length;if(size>512*1024)throw new IllegalArgumentException("附加檔案總量超過 512 KB");result.add(map("path",rel,"content",text,"hash",hash(text)));}return result;
    }
    static List<Object> list(String base,String dir)throws Exception {
        Path root=root(base),p=dir.isBlank()?root:resolve(root,dir);if(!Files.isDirectory(p))throw new IllegalArgumentException("不是資料夾");
        try(var stream=Files.list(p)){return stream.filter(f->!hidden(f.getFileName().toString())&&!Files.isSymbolicLink(f)).sorted(Comparator.comparing((Path f)->!Files.isDirectory(f)).thenComparing(Path::toString)).limit(300).map(f->(Object)map("path",root.relativize(f).toString().replace('\\','/'),"name",f.getFileName().toString(),"directory",Files.isDirectory(f))).toList();}
    }
    static List<Object> proposal(String answer,List<Object> originals)throws Exception {
        String json=answer.trim();if(json.startsWith("```")){int n=json.indexOf('\n');int end=json.lastIndexOf("```");if(n<0||end<=n)throw new IllegalArgumentException("修改提案格式錯誤");json=json.substring(n+1,end).trim();}
        Map<String,Object> p=object(parse(json));List<Object> edits=array(p.get("edits"));if(edits.size()>8)throw new IllegalArgumentException("最多修改 8 個檔案");List<Object> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object item:edits){var e=object(item);String rel=str(e,"path");if(!seen.add(rel))throw new IllegalArgumentException("重複修改檔案");var old=originals.stream().map(Json::object).filter(o->str(o,"path").equals(rel)).findFirst().orElseThrow(()->new IllegalArgumentException("只能修改已附加檔案："+rel));
            if(!(e.get("content")instanceof String content)||content.indexOf(0)>=0||content.getBytes(StandardCharsets.UTF_8).length>256*1024)throw new IllegalArgumentException("修改內容格式錯誤");
            result.add(map("path",rel,"before",str(old,"content"),"hash",str(old,"hash"),"content",content));}return result;
    }
    static void apply(String base,List<Object> edits,Path backups)throws Exception {
        Path root=root(base);List<Path> paths=new ArrayList<>();for(Object item:edits){var e=object(item);Path p=resolve(root,str(e,"path"));if(!hash(read(p)).equals(str(e,"hash")))throw new IllegalArgumentException("檔案已變動，請重新產生提案："+str(e,"path"));paths.add(p);}
        Files.createDirectories(backups);Files.writeString(backups.resolve("originals.json"),stringify(edits),StandardOpenOption.CREATE_NEW);
        int written=0;try{for(int i=0;i<paths.size();i++){atomicWrite(paths.get(i),str(object(edits.get(i)),"content"));written++;}}
        catch(Exception ex){for(int i=0;i<written;i++)try{atomicWrite(paths.get(i),str(object(edits.get(i)),"before"));}catch(Exception rollback){ex.addSuppressed(rollback);}throw ex;}
    }
    static void atomicWrite(Path p,String text)throws Exception {
        Files.createDirectories(p.toAbsolutePath().getParent());Path temp=Files.createTempFile(p.toAbsolutePath().getParent(),"office-",".tmp");try{
            Files.writeString(temp,text,StandardCharsets.UTF_8);if(Files.exists(p)&&Files.getFileStore(p).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(temp,Files.getPosixFilePermissions(p));
            try{Files.move(temp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
}
