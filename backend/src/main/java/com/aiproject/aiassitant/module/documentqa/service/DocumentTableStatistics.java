package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser;
import java.math.*;
import java.time.LocalDate;
import java.time.format.*;
import java.util.*;
import java.util.regex.Pattern;

/** Computes explicit, user-confirmed plans over source rows, never over overlapping retrieval chunks. */
public final class DocumentTableStatistics {
    private DocumentTableStatistics() {}
    public record Filter(String field,String operator,String value,String type,String dateFormat) {}
    public record Plan(String sourceVersion,List<String> tableIds,String operation,String valueField,
                       List<Filter> filters,List<String> groupBy,String distinctBy,String valueUnit) {
        public Plan(String version,List<String> tables,String operation,String field,List<Filter> filters,List<String> groups,String distinct) {
            this(version,tables,operation,field,filters,groups,distinct,null);
        }
    }
    public record Row(int number,int start,int end,Integer page,List<String> cells) {}
    public record Table(String id,String title,List<String> headers,List<Row> rows,int malformedRows,List<String> issues) {}
    public record Audit(String tableId,int row,int start,int end,Integer page,Map<String,String> cells,String decision,String reason) {}
    public record Group(Map<String,String> keys,int count,String value) {}
    public record Result(String sourceVersion,Plan plan,boolean accepted,String scope,int totalRows,int selectedRows,
                         int excludedRows,int duplicateRows,int invalidRows,List<String> warnings,List<Group> groups,List<Audit> audit) {}
    private static final Pattern NUMBER=Pattern.compile("[+-]?(?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d+)?");
    public static boolean requiresConfirmedPlan(String question) {
        return question != null && Pattern.compile("(?i)统计|合计|总额|多少|\\bsum\\b|\\bcount\\b|\\btotal\\b").matcher(question).find()
                && Pattern.compile("(?i)筛选|满足|Status\\s*=|Currency\\s*=|Type\\s*=|已付款|202[0-9]年|\\bwhere\\b|\\bfiltered?\\b|\\bpaid\\b").matcher(question).find();
    }

    public static List<Table> tables(DocumentQaService.DocSession session) {
        if(session.source==null)return List.of();
        List<Table> result=new ArrayList<>();String text=session.source.text();
        boolean encoded=session.fileName.toLowerCase(Locale.ROOT).endsWith(".csv") || session.source.parseReport()!=null
                && session.source.parseReport().engine().contains("escaped-cells-v1");
        for(var block:session.source.blocks()) {
            int cursor=block.start();List<String> headers=null;List<Row> rows=new ArrayList<>();List<String> issues=new ArrayList<>();int malformed=0;char delimiter=0;String title=block.title();
            while(cursor<block.end()) {
                int end=text.indexOf('\n',cursor);if(end<0||end>block.end())end=block.end();String raw=text.substring(cursor,end);String line=raw.strip();
                char kind=raw.indexOf('\t')>=0?'\t':line.indexOf('|')>=0?'|':0;
                if(kind!=0) {
                    List<String> cells=cells(kind=='\t'?raw:line,kind,encoded);
                    boolean separator=cells.stream().allMatch(c->c.matches(":?-{3,}:?"));
                    if(!separator) {
                        if(headers==null){headers=cells;delimiter=kind;}
                        else if(kind!=delimiter||cells.size()!=headers.size()){malformed++;issues.add("第"+(rows.size()+1)+"行列数不一致，偏移"+cursor);rows.add(new Row(rows.size()+1,cursor,end,block.page(),cells));}
                        else if(!cells.equals(headers))rows.add(new Row(rows.size()+1,cursor,end,block.page(),cells));
                    }
                } else if(headers!=null) {
                    if(!rows.isEmpty()||malformed>0)result.add(table(result.size(),title,headers,rows,malformed,issues));
                    headers=null;rows=new ArrayList<>();issues=new ArrayList<>();malformed=0;
                }
                cursor=end+1;
            }
            if(headers!=null&&(!rows.isEmpty()||malformed>0))result.add(table(result.size(),title,headers,rows,malformed,issues));
        }
        return List.copyOf(result);
    }
    private static Table table(int index,String title,List<String> headers,List<Row> rows,int malformed,List<String> issues){
        var notes=new ArrayList<>(issues);if(headers.stream().anyMatch(String::isBlank)||new HashSet<>(headers).size()!=headers.size())notes.add("表头为空或重名，无法唯一定位字段");
        return new Table("table-"+(index+1),title,List.copyOf(headers),List.copyOf(rows),malformed,List.copyOf(notes));
    }
    private static List<String> cells(String line,char delimiter,boolean encoded){
        if(delimiter=='|'&&line.startsWith("|"))line=line.substring(1);if(delimiter=='|'&&line.endsWith("|"))line=line.substring(0,line.length()-1);
        return Arrays.stream(line.split(delimiter=='|'?"\\|":"\t",-1)).map(String::strip).map(c->encoded?DelimitedTableReader.decode(c):c).toList();
    }
    public static Result calculate(DocumentQaService.DocSession session,Plan plan) {
        if(plan==null||!Objects.equals(session.sourceVersion,plan.sourceVersion()))throw new BizException(409,"文档版本已改变，请重新确认统计条件");
        var all=tables(session);var ids=plan.tableIds();if(ids==null||ids.isEmpty()||new HashSet<>(ids).size()!=ids.size())throw new BizException(400,"请选择不重复的统计表格");
        var selected=all.stream().filter(t->ids.contains(t.id())).toList();if(selected.size()!=ids.size())throw new BizException(400,"统计表格不存在");
        var headers=selected.get(0).headers();if(selected.stream().anyMatch(t->!t.headers().equals(headers)))throw new BizException(400,"多表统计要求表头及列顺序完全一致");
        if(headers.stream().anyMatch(String::isBlank)||new HashSet<>(headers).size()!=headers.size())throw new BizException(400,"表头不唯一，不能核验统计");
        String op=plan.operation();if(!List.of("SUM","COUNT","AVG","MIN","MAX","DISTINCT_COUNT").contains(op==null?"":op))throw new BizException(400,"不支持的统计运算");
        boolean numeric=!op.equals("COUNT")&&!op.equals("DISTINCT_COUNT");
        String declaredUnit=plan.valueUnit()==null?"":plan.valueUnit();
        if(!List.of("","GBP","USD","CNY","EUR").contains(declaredUnit))throw new BizException(400,"不支持的声明单位");
        if(!op.equals("COUNT"))field(headers,plan.valueField());
        var filters=plan.filters()==null?List.<Filter>of():plan.filters();if(filters.size()>12)throw new BizException(400,"筛选条件最多12项");
        for(var f:filters){field(headers,f.field());if(!List.of("EQ","NE","GT","GE","LT","LE","CONTAINS").contains(f.operator()==null?"":f.operator())||f.value()==null||f.value().length()>500)throw new BizException(400,"筛选条件无效");if(!List.of("TEXT","NUMBER","DATE").contains(f.type()==null?"":f.type()))throw new BizException(400,"请确认筛选字段类型");try{compare(f.value(),f.value(),f);}catch(IllegalArgumentException e){throw new BizException(400,"筛选条件值或日期格式无效");}}
        var grouping=plan.groupBy()==null?List.<String>of():plan.groupBy();if(grouping.size()>3||new HashSet<>(grouping).size()!=grouping.size())throw new BizException(400,"分组最多3个不重复字段");grouping.forEach(f->field(headers,f));
        boolean dedup=plan.distinctBy()!=null&&!plan.distinctBy().isBlank();if(dedup)field(headers,plan.distinctBy());
        Map<String,List<String>> seen=new LinkedHashMap<>();Map<List<String>,List<String>> values=new LinkedHashMap<>();Map<List<String>,Set<String>> units=new LinkedHashMap<>();List<Audit> audit=new ArrayList<>();List<String> warnings=new ArrayList<>();
        int total=0,matched=0,excluded=0,duplicates=0,invalid=0;
        for(var table:selected){warnings.addAll(table.issues());
            for(var row:table.rows()) {total++;Map<String,String> cells=new LinkedHashMap<>();for(int i=0;i<row.cells().size();i++)cells.put(i<headers.size()?headers.get(i):"额外列"+i,row.cells().get(i));
                if(row.cells().size()!=headers.size()){invalid++;audit.add(new Audit(table.id(),row.number(),row.start(),row.end(),row.page(),cells,"INVALID","列数与表头不一致"));continue;}
                String decision="SELECTED",reason="满足全部已确认条件";boolean eligible=true;
                // Evaluate all filters even if an earlier filter excluded the row; invalid data is never hidden by ordering.
                for(var f:filters){try{if(!compare(cells.get(f.field()),f.value(),f)){eligible=false;if(decision.equals("SELECTED")){decision="EXCLUDED";reason=f.field()+" "+f.operator()+" "+f.value();}}}catch(IllegalArgumentException e){decision="INVALID";reason="筛选字段无法按"+f.type()+"解析："+f.field();}}
                if(!decision.equals("INVALID")&&eligible&&dedup){String key=cells.get(plan.distinctBy());if(key.isBlank()){decision="INVALID";reason="去重编号为空";}else{var previous=seen.putIfAbsent(key,row.cells());if(previous!=null){if(previous.equals(row.cells())){decision="DUPLICATE";reason="同编号且全部字段相同";}else{decision="INVALID";reason="相同编号的字段发生冲突："+key;}}}}
                if(decision.equals("SELECTED")) {String value=op.equals("COUNT")?"1":cells.get(plan.valueField());try{if(numeric){number(value);validateUnit(value,declaredUnit,cells);}else if(op.equals("DISTINCT_COUNT")&&value.isBlank())throw new IllegalArgumentException();}catch(IllegalArgumentException e){decision="INVALID";reason="统计字段为空、数值不明确或与声明单位冲突："+plan.valueField();}
                    if(decision.equals("SELECTED")){var key=grouping.stream().map(cells::get).toList();values.computeIfAbsent(key,k->new ArrayList<>()).add(value);StringBuilder unit=new StringBuilder();for(String h:headers)if(h.matches("(?i).*currency.*|.*币种.*|.*货币.*|^unit$|^单位$"))unit.append(h).append('=').append(cells.get(h)).append(';');if(numeric){String symbol=value.replaceAll("[0-9,.()\\s+\\-]","");String expected=switch(declaredUnit){case "GBP"->"£";case "USD"->"$";case "CNY"->"¥";case "EUR"->"€";default->"";};if(!expected.isEmpty()&&(symbol.isEmpty()||symbol.equals(expected)||declaredUnit.equals("CNY")&&symbol.equals("￥")))symbol=expected;unit.append(symbol);}units.computeIfAbsent(key,k->new HashSet<>()).add(unit.toString());}}
                switch(decision){case "SELECTED"->matched++;case "EXCLUDED"->excluded++;case "DUPLICATE"->duplicates++;case "INVALID"->invalid++;}
                audit.add(new Audit(table.id(),row.number(),row.start(),row.end(),row.page(),cells,decision,reason));
            }
        }
        boolean mixed=numeric&&units.values().stream().anyMatch(u->u.size()>1);if(mixed)warnings.add("同一分组存在不同币种或单位，请添加币种/单位分组后重算");
        if(invalid>0)warnings.add("存在"+invalid+"条异常记录，不能确认完整结果");
        boolean legacyOffice = session.fileName.toLowerCase(Locale.ROOT).matches(".*\\.(xlsx|docx)$")
                && (session.source.parseReport()==null || !session.source.parseReport().engine().contains("escaped-cells-v1"));
        if(legacyOffice)warnings.add("此Office文档由旧解析器准备，请重新准备文档，避免多行单元格及反斜线被误读");
        if(!declaredUnit.isEmpty())warnings.add("统计单位由用户确认为"+declaredUnit+"；无符号数值按该单位解释。币种列仍逐组核验，声明不能覆盖不同币种。");
        if(session.source.parseReport()!=null)warnings.addAll(session.source.parseReport().warnings());
        warnings.add("覆盖范围为所选表格的全部已解析行；未证明OCR无遗漏、未选表格或自由文本已覆盖。小计行须通过条件明确排除。");
        List<Group> groups=new ArrayList<>();boolean accepted=invalid==0&&!mixed&&!legacyOffice&&selected.stream().allMatch(t->t.issues().isEmpty());
        if(accepted){if(values.isEmpty()&&grouping.isEmpty())values.put(List.of(),List.of());for(var entry:values.entrySet()){Map<String,String> key=new LinkedHashMap<>();for(int i=0;i<grouping.size();i++)key.put(grouping.get(i),entry.getKey().get(i));groups.add(new Group(key,entry.getValue().size(),aggregate(op,entry.getValue())));}}
        return new Result(session.sourceVersion,plan,accepted,"selectedParsedTables",total,matched,excluded,duplicates,invalid,List.copyOf(warnings),List.copyOf(groups),List.copyOf(audit));
    }
    private static void field(List<String> headers,String name){if(name==null||!headers.contains(name))throw new BizException(400,"字段不存在："+name);}
    static BigDecimal number(String raw){if(raw==null)throw new IllegalArgumentException();String value=raw.strip();boolean negative=value.startsWith("(")&&value.endsWith(")");if(negative)value=value.substring(1,value.length()-1);value=value.replaceFirst("^([+-]?)[£$¥￥€]\\s*","$1");if(!NUMBER.matcher(value).matches()||negative&&(value.startsWith("-")||value.startsWith("+")))throw new IllegalArgumentException("数值格式不明确");BigDecimal n=new BigDecimal(value.replace(",",""));return negative?n.negate():n;}
    private static void validateUnit(String value,String declared,Map<String,String> cells){if(declared.isEmpty())return;String symbol=value.replaceAll("[0-9,.()\\s+\\-]","");String expected=switch(declared){case "GBP"->"£";case "USD"->"$";case "CNY"->"¥";case "EUR"->"€";default->"";};if(!symbol.isEmpty()&&!symbol.equals(expected)&&!(declared.equals("CNY")&&symbol.equals("￥")))throw new IllegalArgumentException("货币符号与声明冲突");for(var cell:cells.entrySet())if(cell.getKey().matches("(?i).*currency.*|.*币种.*|.*货币.*")&&List.of("GBP","USD","CNY","EUR").contains(cell.getValue().toUpperCase(Locale.ROOT))&&!declared.equals(cell.getValue().toUpperCase(Locale.ROOT)))throw new IllegalArgumentException("币种字段与声明冲突");}
    private static boolean compare(String left,String right,Filter f){int comparison;switch(f.type()){case "NUMBER"->comparison=number(left).compareTo(number(right));case "DATE"->comparison=date(left,f.dateFormat()).compareTo(date(right,f.dateFormat()));default->comparison=left.compareTo(right);}return switch(f.operator()){case "EQ"->comparison==0;case "NE"->comparison!=0;case "GT"->comparison>0;case "GE"->comparison>=0;case "LT"->comparison<0;case "LE"->comparison<=0;case "CONTAINS"->{if(!f.type().equals("TEXT"))throw new BizException(400,"包含运算只支持文本");yield left.contains(right);}default->throw new IllegalArgumentException();};}
    private static LocalDate date(String value,String format){String pattern=switch(format==null?"":format){case "yyyy-MM-dd"->"uuuu-MM-dd";case "dd/MM/yyyy"->"dd/MM/uuuu";case "dd-MMM-yy"->"dd-MMM-uu";default->throw new BizException(400,"请明确日期格式");};try{return LocalDate.parse(value,DateTimeFormatter.ofPattern(pattern,Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT));}catch(DateTimeParseException e){throw new IllegalArgumentException("日期无效");}}
    private static String aggregate(String op,List<String> values){if(op.equals("COUNT"))return String.valueOf(values.size());if(op.equals("DISTINCT_COUNT"))return String.valueOf(new HashSet<>(values).size());if(values.isEmpty())return op.equals("SUM")?"0":null;var numbers=values.stream().map(DocumentTableStatistics::number).toList();var sum=numbers.stream().reduce(BigDecimal.ZERO,BigDecimal::add);return switch(op){case "SUM"->sum.toPlainString();case "AVG"->sum.divide(BigDecimal.valueOf(numbers.size()),10,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();case "MIN"->numbers.stream().min(BigDecimal::compareTo).orElseThrow().toPlainString();case "MAX"->numbers.stream().max(BigDecimal::compareTo).orElseThrow().toPlainString();default->throw new IllegalArgumentException();};}
}
