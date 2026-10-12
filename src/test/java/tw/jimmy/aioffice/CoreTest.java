package tw.jimmy.aioffice;

import java.nio.file.*;
import java.util.*;
import static tw.jimmy.aioffice.Json.*;

public final class CoreTest {
    static int checks;
    static void check(boolean b,String msg){checks++;if(!b)throw new AssertionError(msg);}
    interface Action {void run()throws Exception;}
    static void rejects(Action a,String message)throws Exception {checks++;try{a.run();throw new AssertionError("Expected rejection: "+message);}catch(IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception {
        Object json=map("字串","繁體中文\n\t\"\\","array",List.of(true,42),"null",null);
        check(stringify(parse(stringify(json))).equals(stringify(json)),"JSON roundtrip");
        rejects(()->parse("{} garbage"),"trailing input");rejects(()->parse("{\"a\":1,\"a\":2}"),"duplicate keys");rejects(()->parse("[01]"),"bad number");rejects(()->parse("[".repeat(70)+"]".repeat(70)),"deep input");
        rejects(()->parse("1e309"),"nonfinite JSON input");
        rejects(()->stringify(Double.POSITIVE_INFINITY),"nonfinite JSON output");
        check(Boolean.FALSE.equals(Usage.tokens("Gemini",map("models",map("bad",map("tokens","wrong"),"null",null))).get("known")),"malformed Gemini stats remain unknown");
        check(Boolean.FALSE.equals(Usage.tokens("Company",map("total_tokens","5","input_tokens",null)).get("known")),"nonnumeric usage remains unknown");
        var good=Usage.tokens("Gemini",map("models",map("m",map("tokens",map("prompt",2,"candidates",3,"cached",1,"total",5)))));
        check(Usage.num(good,"input")==2&&Usage.num(good,"output")==3&&Usage.num(good,"cached")==1&&Usage.num(good,"total")==5,"valid Gemini totals");
        var totals=Usage.local(map("tasks",List.of(),"meetings",List.of(),"usageChecks",List.of(map("provider","Company","usage",map("total_tokens",5),"at","2026"))));
        var diagnostic=object(totals.get("Company"));check(Usage.num(diagnostic,"calls")==1&&Usage.num(diagnostic,"diagnosticCalls")==1&&Usage.num(diagnostic,"total")==5,"diagnostic calls counted");
        Path dir=Files.createTempDirectory("office-core-");Path file=dir.resolve("Demo.java");String before="class Demo { int n = 1; }\n";Files.writeString(file,before);
        check(ProjectFiles.snapshot(dir.toString(),"Demo.java\nDemo.java").size()==1,"deduplicate files");
        rejects(()->ProjectFiles.resolve(dir,"../outside"),"path traversal");Files.writeString(dir.resolve(".env"),"secret");rejects(()->ProjectFiles.resolve(dir,".env"),"credential file");
        Files.writeString(dir.resolve("id_ed25519"),"private");rejects(()->ProjectFiles.resolve(dir,"id_ed25519"),"private key name");
        Files.writeString(dir.resolve("CERT.PEM"),"private");rejects(()->ProjectFiles.resolve(dir,"CERT.PEM"),"case insensitive key extension");
        Files.writeString(dir.resolve("renamed.txt"),"-----BEGIN OPENSSH PRIVATE KEY-----\nsecret");rejects(()->ProjectFiles.read(dir.resolve("renamed.txt")),"renamed private key content");
        check(ProjectFiles.read(file).equals(before),"ordinary source allowed");
        Files.write(dir.resolve("binary"),new byte[]{0,1});rejects(()->ProjectFiles.read(dir.resolve("binary")),"binary");Files.write(dir.resolve("bad-utf8"),new byte[]{(byte)0xff});checks++;try{ProjectFiles.read(dir.resolve("bad-utf8"));throw new AssertionError("invalid UTF-8");}catch(java.nio.charset.CharacterCodingException expected){}
        try{Files.createSymbolicLink(dir.resolve("link"),file);rejects(()->ProjectFiles.resolve(dir,"link"),"symbolic link");}catch(UnsupportedOperationException|java.io.IOException ignored){}
        var snapshot=ProjectFiles.snapshot(dir.toString(),"Demo.java");String proposed="class Demo { int n = 2; }\n";var edits=ProjectFiles.proposal(stringify(map("summary","change","edits",List.of(map("path","Demo.java","content",proposed)))),snapshot);
        rejects(()->ProjectFiles.proposal("{\"edits\":[{\"path\":\"Other.java\",\"content\":\"bad\"}]}",snapshot),"unattached edit");
        Files.writeString(file,"changed elsewhere");rejects(()->ProjectFiles.apply(dir.toString(),edits,dir.resolve("backups/stale")),"stale edit");check(Files.readString(file).equals("changed elsewhere"),"stale refusal preserves file");
        Files.writeString(file,before);ProjectFiles.apply(dir.toString(),edits,dir.resolve("backups/good"));check(Files.readString(file).equals(proposed),"apply edits");check(Files.readString(dir.resolve("backups/good/originals.json")).contains("before"),"backup");
        Path store=dir.resolve("office-data");try(Office o=new Office(store)){o.updateConfig(map("workspace",dir.toString(),"Codex",map("command","codex","model",""),"Gemini",map("command","gemini","model","")));}
        try(Office o=new Office(store)){
            check(str(o.config(),"workspace").equals(dir.toString()),"config persistence");
            var opts=object(o.discoverOptions());
            check(opts.containsKey("Codex")&&opts.containsKey("Company"),"options discovery");
        }
        System.out.println("Core checks passed: "+checks);
    }
}
