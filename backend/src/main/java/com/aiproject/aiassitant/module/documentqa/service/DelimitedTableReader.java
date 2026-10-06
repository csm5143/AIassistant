package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.common.BizException;
import java.util.*;

/** Quoted records with a bounded header-based comma/semicolon/tab dialect detection. */
public final class DelimitedTableReader {
    private DelimitedTableReader() {}
    public static char detectDelimiter(String text) {
        char[] candidates={',',';','\t'}; int[] counts=new int[3]; boolean quoted=false;
        int start=text.startsWith("\uFEFF")?1:0;
        for(int i=start;i<text.length();i++) {
            if(i-start>=65536)throw new BizException(400,"CSV 表头过长，无法判断分隔符");
            char c=text.charAt(i);
            if(c=='"') {
                if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"')i++;
                else quoted=!quoted;
            } else if(!quoted) {
                if(c=='\r'||c=='\n')break;
                for(int j=0;j<candidates.length;j++)if(c==candidates[j])counts[j]++;
            }
        }
        int best=0; for(int j=1;j<counts.length;j++)if(counts[j]>counts[best])best=j;
        if(counts[best]==0)return ','; // A single column is valid; quoted separators are data.
        for(int j=0;j<counts.length;j++)if(j!=best&&counts[j]==counts[best])
            throw new BizException(400,"CSV 表头存在多种同等可能的分隔符，请明确格式");
        return candidates[best];
    }
    public static List<List<String>> csv(String text) {
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        char delimiter=detectDelimiter(text);
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder(); boolean quoted = false, closed = false;
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) {
                if(c=='"') { if(i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;} }
                else cell.append(c);
            } else if(c=='"') {
                if(cell.length()!=0||closed)throw new BizException(400,"CSV 引号格式错误"); quoted=true;
            } else if(c==delimiter||c=='\n'||c=='\r') {
                row.add(cell.toString());cell.setLength(0);closed=false;
                if(c!=delimiter){rows.add(List.copyOf(row));row.clear();if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;}
            } else { if(closed&&!Character.isWhitespace(c))throw new BizException(400,"CSV 引号后存在无效字符"); if(!closed)cell.append(c); }
        }
        if(quoted)throw new BizException(400,"CSV 引号未闭合");
        if(!row.isEmpty()||cell.length()>0||closed){row.add(cell.toString());rows.add(List.copyOf(row));}
        if(!rows.isEmpty()&&!rows.get(0).isEmpty()) {var header=new ArrayList<>(rows.get(0));header.set(0,header.get(0).replace("\uFEFF",""));rows.set(0,header);}
        return rows;
    }
    /** Escape delimiters inside cells while retaining them as a reversible text representation. */
    public static String encode(String cell){return cell.replace("\\","\\\\").replace("\t","\\t").replace("\r","\\r").replace("\n","\\n");}
    public static String decode(String cell){StringBuilder out=new StringBuilder();for(int i=0;i<cell.length();i++){char c=cell.charAt(i);if(c=='\\'&&i+1<cell.length()){char n=cell.charAt(++i);out.append(n=='t'?'\t':n=='r'?'\r':n=='n'?'\n':n);}else out.append(c);}return out.toString();}
}
