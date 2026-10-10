package tw.jimmy.aioffice;

import java.util.*;

/** Small dependency-free JSON codec; rejects trailing input and excessive nesting. */
public final class Json {
    private final String s; private int i,depth;
    private Json(String s){this.s=s;}
    public static Object parse(String s){Json p=new Json(s);Object v=p.value();p.space();if(p.i!=s.length())throw new IllegalArgumentException("JSON 結尾有多餘內容");return v;}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object x){if(!(x instanceof Map))throw new IllegalArgumentException("需要 JSON 物件");return (Map<String,Object>)x;}
    @SuppressWarnings("unchecked") public static List<Object> array(Object x){if(!(x instanceof List))throw new IllegalArgumentException("需要 JSON 陣列");return (List<Object>)x;}
    public static String str(Map<String,Object> m,String key){Object v=m.get(key);return v==null?"":v.toString();}
    public static Map<String,Object> map(Object... kv){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    public static String stringify(Object v){StringBuilder b=new StringBuilder();write(v,b);return b.toString();}
    private static void write(Object v,StringBuilder b){
        if(v==null){b.append("null");return;}if(v instanceof Number n&&!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("JSON 數字超出有限範圍");if(v instanceof Boolean||v instanceof Number){b.append(v);return;}
        if(v instanceof Map<?,?> m){b.append('{');boolean first=true;for(var e:m.entrySet()){if(!first)b.append(',');first=false;write(e.getKey().toString(),b);b.append(':');write(e.getValue(),b);}b.append('}');return;}
        if(v instanceof Iterable<?> a){b.append('[');boolean first=true;for(Object o:a){if(!first)b.append(',');first=false;write(o,b);}b.append(']');return;}
        b.append('"');for(char c:v.toString().toCharArray())switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}b.append('"');
    }
    private void space(){while(i<s.length()&&" \t\r\n".indexOf(s.charAt(i))>=0)i++;}
    private char next(){if(i>=s.length())throw new IllegalArgumentException("JSON 未完成");return s.charAt(i++);}
    private Object value(){space();if(++depth>64)throw new IllegalArgumentException("JSON 過深");try{
        if(i>=s.length())throw new IllegalArgumentException("空白 JSON");char c=s.charAt(i);
        if(c=='"')return string();
        if(c=='{'){i++;Map<String,Object> m=new LinkedHashMap<>();space();if(i<s.length()&&s.charAt(i)=='}'){i++;return m;}while(true){space();String k=string();space();if(next()!=':')throw new IllegalArgumentException("缺少冒號");if(m.containsKey(k))throw new IllegalArgumentException("重複 JSON 欄位");m.put(k,value());space();char end=next();if(end=='}')return m;if(end!=',')throw new IllegalArgumentException("缺少逗號");}}
        if(c=='['){i++;List<Object>a=new ArrayList<>();space();if(i<s.length()&&s.charAt(i)==']'){i++;return a;}while(true){a.add(value());space();char end=next();if(end==']')return a;if(end!=',')throw new IllegalArgumentException("缺少逗號");}}
        for(String t:List.of("true","false","null"))if(s.startsWith(t,i)){i+=t.length();return t.equals("null")?null:t.equals("true");}
        int start=i;while(i<s.length()&&"-+0123456789.eE".indexOf(s.charAt(i))>=0)i++;String n=s.substring(start,i);if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IllegalArgumentException("JSON 數字格式錯誤");if(n.contains(".")||n.contains("e")||n.contains("E")){double number=Double.parseDouble(n);if(!Double.isFinite(number))throw new IllegalArgumentException("JSON 數字超出有限範圍");return number;}return Long.valueOf(n);
    }finally{depth--;}}
    private String string(){if(next()!='"')throw new IllegalArgumentException("需要 JSON 字串");StringBuilder b=new StringBuilder();while(true){char c=next();if(c=='"')return b.toString();if(c<32)throw new IllegalArgumentException("JSON 字串含控制字元");if(c!='\\'){b.append(c);continue;}char e=next();switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(i+4>s.length())throw new IllegalArgumentException("Unicode 未完成");b.append((char)Integer.parseInt(s.substring(i,i+4),16));i+=4;}default->throw new IllegalArgumentException("JSON 跳脫格式錯誤");}}
    }
}
