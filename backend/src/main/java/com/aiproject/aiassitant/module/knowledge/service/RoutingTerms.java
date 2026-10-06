package com.aiproject.aiassitant.module.knowledge.service;
import java.util.*;
import java.util.regex.Pattern;
/** Local extraction only. Document text is never executed or turned into instructions. */
public final class RoutingTerms {
    private static final Pattern WORDS=Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{1,48}|[\\p{IsHan}]{2,}");
    private static final Set<String> STOP=Set.of("这个","那个","什么","哪些","为什么","如何","怎么","一下","请问","根据","文档","资料","教程","回答","问题","说明","可以","分别","以及","是否","多少","给出","的是","一个","我们","进行","使用","需要","通过","the","and","this","that","from","with","return","import","self","none","true","false");
    private RoutingTerms(){}
    public static Set<String> query(String text){var result=new LinkedHashSet<String>();extract(text==null?"":text).keySet().forEach(result::add);return result;}
    private static Map<String,Integer> extract(String text){
        var terms=new HashMap<String,Integer>();var matcher=WORDS.matcher(text);
        while(matcher.find()){
            String word=matcher.group().toLowerCase(Locale.ROOT);
            if(Character.UnicodeScript.of(word.charAt(0))==Character.UnicodeScript.HAN){
                for(int i=0;i<word.length()-1;i++){String term=word.substring(i,i+2);if(!STOP.contains(term))terms.merge(term,1,Integer::sum);}
            }else if(!STOP.contains(word))terms.merge(word,1,Integer::sum);
        }
        return terms;
    }
    public static String profile(String filename,String text){
        var terms=extract(text==null?"":text);
        var ranked=terms.entrySet().stream().sorted(Comparator.<Map.Entry<String,Integer>>comparingDouble(e->e.getValue()*(ascii(e.getKey())?3:1)).reversed().thenComparing(Map.Entry::getKey)).limit(1800).map(Map.Entry::getKey).toList();
        String result=(filename==null?"":filename)+" "+String.join(" ",ranked);
        return result.substring(0,Math.min(24000,result.length()));
    }
    public static boolean ascii(String term){return !term.isEmpty()&&term.charAt(0)<128;}
}
