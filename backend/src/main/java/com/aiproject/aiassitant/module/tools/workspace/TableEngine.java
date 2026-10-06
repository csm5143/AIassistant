package com.aiproject.aiassitant.module.tools.workspace;

import com.aiproject.aiassitant.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import java.io.*;
import java.math.*;
import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Bounded, deterministic table operations. No generated code, macros or formula execution. */
@Component
public class TableEngine {
    public static final int MAX_ROWS = 50000, MAX_COLUMNS = 100, MAX_CELLS = 500000;
    public record Table(String sheet, List<String> sheets, List<String> columns, List<List<String>> rows) {}
    public record Result(List<String> columns, List<List<Object>> rows, Map<String,Object> audit) {}
    public Table read(byte[] bytes, String name, String selectedSheet) throws IOException {
        if (ToolFileStore.extension(name).equals("csv")) return csv(bytes);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            List<String> sheets = new ArrayList<>(); for (Sheet s : wb) sheets.add(s.getSheetName());
            Sheet sheet = selectedSheet == null || selectedSheet.isBlank() ? wb.getSheetAt(0) : wb.getSheet(selectedSheet);
            if (sheet == null) throw bad("工作表不存在，请先查看工作表列表");
            DataFormatter formatter = new DataFormatter(Locale.ROOT); formatter.setUseCachedValuesForFormulaCells(true);
            List<List<String>> rows = new ArrayList<>(); int cells=0;
            for (Row row : sheet) {
                int n = Math.max(0, row.getLastCellNum()); if(n > MAX_COLUMNS) throw bad("最多支持 100 列");
                List<String> values = new ArrayList<>();
                for (int i=0;i<n;i++) {
                    Cell cell=row.getCell(i); String value="";
                    if(cell!=null) {
                        CellType type=cell.getCellType()==CellType.FORMULA?cell.getCachedFormulaResultType():cell.getCellType();
                        if(cell.getCellType()==CellType.FORMULA && cell instanceof org.apache.poi.xssf.usermodel.XSSFCell x && !x.getCTCell().isSetV()) value="";
                        else if(type==CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) value=cell.getLocalDateTimeCellValue().toString();
                        else value=formatter.formatCellValue(cell);
                    }
                    values.add(boundCell(value));
                }
                if(values.stream().allMatch(String::isBlank)) continue;
                cells+=values.size(); checkBounds(rows.size(),cells); rows.add(values);
            }
            return normalized(sheet.getSheetName(),sheets,rows);
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw bad("表格无法读取，请检查文件格式、密码及缓存公式结果"); }
    }
    private Table csv(byte[] bytes) {
        String text;
        try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException e) { text=Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString(); }
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        char delimiter=com.aiproject.aiassitant.module.documentqa.service.DelimitedTableReader.detectDelimiter(text);
        List<List<String>> rows=new ArrayList<>(); List<String> row=new ArrayList<>(); StringBuilder cell=new StringBuilder();
        boolean quoted=false,closed=false; int cells=0;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) {
                if(c=='"') {if(i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}
                else cell.append(c);
            } else if(c=='"') {if(!cell.isEmpty()||closed)throw bad("CSV 引号格式无效");quoted=true;}
            else if(c==delimiter || c=='\n' || c=='\r') {
                row.add(boundCell(cell.toString()));cell.setLength(0);closed=false;cells++;
                if(row.size()>MAX_COLUMNS)throw bad("最多支持 100 列");
                if(c!=delimiter) {if(row.stream().anyMatch(v->!v.isBlank()))rows.add(row);row=new ArrayList<>();if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;}
                checkBounds(rows.size(),cells);
            } else {if(closed&&!Character.isWhitespace(c))throw bad("CSV 引号后存在非法字符");if(!closed)cell.append(c);}
            if(cell.length()>10000)throw bad("单元格内容超过 10000 字符");
        }
        if(quoted)throw bad("CSV 引号未闭合");
        if(!cell.isEmpty()||!row.isEmpty()||closed){row.add(boundCell(cell.toString()));if(row.stream().anyMatch(v->!v.isBlank()))rows.add(row);}
        return normalized("CSV",List.of("CSV"),rows);
    }
    private static String boundCell(String value) {if(value.length()>10000)throw bad("单元格超过 10000 字符");return value;}
    private static void checkBounds(int rows,int cells) {if(rows>MAX_ROWS||cells>MAX_CELLS)throw bad("工具支持最多 50000 行、500000 个单元格，请拆分表格");}
    private Table normalized(String sheet,List<String>sheets,List<List<String>> input) {
        if(input.isEmpty())throw bad("表格没有内容");
        List<String> columns=new ArrayList<>();Set<String> used=new HashSet<>();
        for(int i=0;i<input.get(0).size();i++) {String base=input.get(0).get(i).strip();if(base.isEmpty())base="列"+(i+1);String col=base;int suffix=2;while(!used.add(col))col=base+"_"+suffix++;columns.add(col);}
        if(columns.size()>MAX_COLUMNS)throw bad("最多支持 100 列");
        List<List<String>>rows=new ArrayList<>();int cells=columns.size();
        for(var r:input.subList(1,input.size())) {
            if(r.size()>columns.size()&&r.subList(columns.size(),r.size()).stream().anyMatch(v->!v.isBlank()))throw bad("数据列数超过表头列数，请检查第一行表头");
            List<String> v=new ArrayList<>();for(int i=0;i<columns.size();i++)v.add(i<r.size()?r.get(i):"");rows.add(v);cells+=v.size();checkBounds(rows.size(),cells);
        }
        return new Table(sheet,sheets,columns,rows);
    }
    public Map<String,Object> inspect(Table table) {
        List<Map<String,Object>> profiles=new ArrayList<>();
        for(int c=0;c<table.columns.size();c++) {
            int empty=0,numeric=0; BigDecimal min=null,max=null;
            for(var row:table.rows){String v=row.get(c);if(v.isBlank()){empty++;continue;}BigDecimal n=number(v);if(n!=null){numeric++;min=min==null?n:min.min(n);max=max==null?n:max.max(n);}}
            Map<String,Object> p=new LinkedHashMap<>();p.put("name",table.columns.get(c));p.put("empty",empty);p.put("numeric",numeric);p.put("nonNumeric",table.rows.size()-empty-numeric);p.put("min",min);p.put("max",max);profiles.add(p);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("sheet",table.sheet);result.put("sheets",table.sheets);result.put("rowCount",table.rows.size());result.put("columns",profiles);result.put("sample",table.rows.stream().limit(5).toList());result.put("notice","第一行是表头；公式只读取缓存结果，不运行宏或公式；百分数按比例计算，日期保持原始精度。");return result;
    }
    public Result analyze(Table table,JsonNode plan) {
        List<String>groups=strings(plan.path("groupBy"));for(String g:groups)index(table,g);
        String dateColumn=plan.path("dateGroup").path("column").asText("");String unit=plan.path("dateGroup").path("unit").asText("month");
        if(!dateColumn.isBlank()){index(table,dateColumn);if(!groups.contains(dateColumn))groups.add(0,dateColumn);if(!Set.of("month","year","day").contains(unit))throw bad("日期分组仅支持 month、year、day");}
        List<JsonNode>filters=new ArrayList<>();plan.path("filters").forEach(f->{index(table,f.path("column").asText());if(!Set.of("eq","ne","contains","gt","gte","lt","lte","notEmpty","empty").contains(f.path("op").asText()))throw bad("不支持的筛选操作");filters.add(f);});
        List<List<String>> selected=new ArrayList<>();Set<List<String>> seen=new HashSet<>();int duplicates=0;
        for(var row:table.rows) {
            boolean matches=true;for(var f:filters)if(!matches(row.get(index(table,f.path("column").asText())),f)){matches=false;break;}
            if(!matches)continue;
            if(plan.path("deduplicate").asBoolean()&&!seen.add(row)){duplicates++;continue;}selected.add(row);
        }
        List<JsonNode>aggs=new ArrayList<>();plan.path("aggregations").forEach(a->{String op=a.path("op").asText();if(!Set.of("sum","avg","min","max","count","countNumeric").contains(op))throw bad("聚合支持 sum、avg、min、max、count、countNumeric");if(!op.equals("count")||!a.path("column").asText("*").equals("*"))index(table,a.path("column").asText());aggs.add(a);});
        if(!groups.isEmpty()&&aggs.isEmpty())throw bad("分组时请提供 aggregations，例如 count 或 sum");
        List<String>outColumns=new ArrayList<>();List<List<Object>>out=new ArrayList<>();Map<String,Object>audit=new LinkedHashMap<>();
        audit.put("inputRows",table.rows.size());audit.put("filteredRows",selected.size());audit.put("removedDuplicates",duplicates);audit.put("sheet",table.sheet);audit.put("plan",plan);
        Map<String,Integer>invalid=new LinkedHashMap<>(),empty=new LinkedHashMap<>();
        if(!aggs.isEmpty()) {
            outColumns.addAll(groups);for(var a:aggs)outColumns.add(a.path("alias").asText(a.path("column").asText("*")+"_"+a.path("op").asText()));
            audit.put("chartValueColumn",outColumns.get(groups.size()));
            if(new HashSet<>(outColumns).size()!=outColumns.size())throw bad("分组列和统计列名称不能重复，请设置不同 alias");
            Map<List<String>,List<List<String>>> buckets=new LinkedHashMap<>();
            if(groups.isEmpty())buckets.put(List.of(),selected);
            for(var row:selected){List<String>key=new ArrayList<>();for(String g:groups){String value=row.get(index(table,g));if(g.equals(dateColumn))value=dateKey(value,unit);key.add(value);}if(!groups.isEmpty())buckets.computeIfAbsent(key,k->new ArrayList<>()).add(row);}
            for(var bucket:buckets.entrySet()){
                List<Object>r=new ArrayList<>(bucket.getKey());
                for(var a:aggs){String op=a.path("op").asText(),col=a.path("column").asText("*"),label=a.path("alias").asText(col+"_"+op);int pos=col.equals("*")?-1:index(table,col);BigDecimal sum=BigDecimal.ZERO,min=null,max=null;int count=0;
                    for(var row:bucket.getValue()){
                        String raw=pos<0?"1":row.get(pos);if(raw.isBlank()){empty.merge(label,1,Integer::sum);continue;}
                        if(op.equals("count")){count++;continue;}
                        BigDecimal n=number(raw);if(n==null){invalid.merge(label,1,Integer::sum);continue;}
                        count++;sum=sum.add(n);min=min==null?n:min.min(n);max=max==null?n:max.max(n);
                    }
                    r.add(switch(op){case "count","countNumeric"->count;case "sum"->count==0?null:sum;case "avg"->count==0?null:sum.divide(BigDecimal.valueOf(count),10,RoundingMode.HALF_UP).stripTrailingZeros();case "min"->min;default->max;});
                }out.add(r);
            }
        } else {
            List<String>cols=strings(plan.path("select"));if(cols.isEmpty())cols.addAll(table.columns);for(String c:cols)index(table,c);outColumns.addAll(cols);
            for(var row:selected){List<Object> r=new ArrayList<>();for(String c:cols)r.add(row.get(index(table,c)));out.add(r);}
        }
        String sort=plan.path("sortBy").asText("");if(!sort.isBlank()){int pos=outColumns.indexOf(sort);if(pos<0)throw bad("排序列不存在");boolean desc=plan.path("sortDirection").asText("asc").equals("desc");out.sort((a,b)->{Object x=a.get(pos),y=b.get(pos);if(x==null||y==null)return x==y?0:x==null?1:-1;BigDecimal nx=number(x.toString()),ny=number(y.toString());int cmp=nx!=null&&ny!=null?nx.compareTo(ny):x.toString().compareTo(y.toString());return desc?-cmp:cmp;});}
        int limit=plan.path("limit").asInt(MAX_ROWS);if(limit<1||limit>MAX_ROWS)throw bad("limit 应在 1 到 50000 之间");int total=out.size();if(out.size()>limit)out=new ArrayList<>(out.subList(0,limit));
        audit.put("resultRowsBeforeLimit",total);audit.put("resultRows",out.size());audit.put("invalidNumericCells",invalid);audit.put("emptyNumericCells",empty);audit.put("notice","统计为确定性计算；count 计非空单元格（含非数字），count(*) 计行数，countNumeric 仅计有效数字；空值及非数字不参与数值聚合，全部为空时返回 null。百分数按比例计算。输出超过 15 位精度的数值以文本保存，避免 Excel 截断。");
        return new Result(outColumns,out,audit);
    }
    static BigDecimal number(String raw) {
        String s=raw.strip().replaceAll("^[￥¥$]\\s*","");boolean percent=s.endsWith("%");if(percent)s=s.substring(0,s.length()-1);
        if(s.matches("[+-]?\\d{1,3}(,\\d{3})+(\\.\\d+)?"))s=s.replace(",","");
        if(!s.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d{1,3})?"))return null;
        try{BigDecimal value=new BigDecimal(s);if(value.precision()>100||Math.abs(value.scale())>100)return null;return percent?value.movePointLeft(2):value;}catch(NumberFormatException e){return null;}
    }
    private static boolean matches(String raw,JsonNode f) {String value=f.path("value").asText();String op=f.path("op").asText();return switch(op){case "eq"->raw.equals(value);case "ne"->!raw.equals(value);case "contains"->raw.contains(value);case "empty"->raw.isBlank();case "notEmpty"->!raw.isBlank();default->{BigDecimal a=number(raw),b=number(value);if(b==null)throw bad("数字筛选值无效");if(a==null)yield false;int c=a.compareTo(b);yield switch(op){case "gt"->c>0;case "gte"->c>=0;case "lt"->c<0;default->c<=0;};}};}
    private static String dateKey(String raw,String unit) {
        for(String pattern:List.of("uuuu-MM-dd","uuuu/M/d","uuuu/MM/dd","uuuuMMdd")) {
            try {String s=raw.strip().split("[T ]",2)[0];LocalDate d=LocalDate.parse(s,DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT));return switch(unit){case "year"->String.valueOf(d.getYear());case "day"->d.toString();default->YearMonth.from(d).toString();};}catch(DateTimeException ignored){}
        }
        try{YearMonth m=YearMonth.parse(raw.strip());return unit.equals("year")?String.valueOf(m.getYear()):m.toString();}catch(DateTimeException ignored){}
        throw bad("无法按日期分组："+raw+"；支持 yyyy-MM-dd、yyyy/M/d、yyyy-MM 和 Excel 日期");
    }
    private static List<String>strings(JsonNode n){List<String>s=new ArrayList<>();if(!n.isMissingNode()&&!n.isArray())throw bad("列表参数必须是数组");n.forEach(x->s.add(x.asText()));return s;}
    private static int index(Table table,String name){int n=table.columns.indexOf(name);if(n<0)throw bad("表格中没有列："+name+"，请先 inspectTable 确认列名");return n;}
    private static BizException bad(String message){return new BizException(400,message);}
    public byte[] workbook(Result result,String auditText) throws IOException {
        try(XSSFWorkbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Sheet s=wb.createSheet("结果");CellStyle header=wb.createCellStyle();header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);header.setBorderBottom(BorderStyle.THIN);org.apache.poi.ss.usermodel.Font bold=wb.createFont();bold.setBold(true);header.setFont(bold);
            Row h=s.createRow(0);for(int c=0;c<result.columns.size();c++){h.createCell(c).setCellValue(result.columns.get(c));h.getCell(c).setCellStyle(header);s.setColumnWidth(c,Math.min(60,Math.max(18,result.columns.get(c).length()*2+4))*256);}
            for(int r=0;r<result.rows.size();r++){Row row=s.createRow(r+1);for(int c=0;c<result.columns.size();c++){Cell cell=row.createCell(c);Object v=result.rows.get(r).get(c);if(v==null)continue;if(v instanceof Number n&&(!(v instanceof BigDecimal b)||b.precision()<=15))cell.setCellValue(n.doubleValue());else cell.setCellValue(v.toString());}}
            s.createFreezePane(0,1);s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,result.rows.size(),0,result.columns.size()-1));
            Sheet notes=wb.createSheet("处理说明");int row=0;for(String line:auditText.split("\n")){notes.createRow(row++).createCell(0).setCellValue(line.length()>32000?line.substring(0,32000):line);}notes.setColumnWidth(0,100*256);wb.write(out);return out.toByteArray();
        }
    }
    public byte[] chart(Result result,String type,String title) throws IOException {
        if(!Set.of("bar","line","none").contains(type))throw bad("chartType 支持 bar、line、none");if(type.equals("none")||result.rows.isEmpty())return null;
        int valueCol=result.columns.indexOf(Objects.toString(result.audit.get("chartValueColumn"),""));if(valueCol<0)for(int c=0;c<result.columns.size();c++){final int i=c;if(result.rows.stream().anyMatch(r->r.get(i)!=null&&number(r.get(i).toString())!=null)){valueCol=c;break;}}
        if(valueCol<0)return null;List<List<Object>>rows=result.rows.stream().filter(r->!r.isEmpty()).limit(20).toList();
        double[] values=new double[rows.size()];double max=0,min=0;for(int i=0;i<values.length;i++){Object v=rows.get(i).get(valueCol);BigDecimal n=v==null?null:number(v.toString());values[i]=n==null?0:n.doubleValue();max=Math.max(max,values[i]);min=Math.min(min,values[i]);}
        if(max==min)max=min+1;double span=max-min;
        BufferedImage image=new BufferedImage(1100,600,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(Color.WHITE);g.fillRect(0,0,1100,600);g.setColor(Color.BLACK);g.setFont(new Font("Microsoft YaHei",Font.BOLD,23));g.drawString(title.length()>38?title.substring(0,38):title,75,44);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,15));g.drawString(result.columns.get(valueCol)+" · "+(result.rows.size()>20?"展示前 20 条，共 "+result.rows.size()+" 条":"共 "+result.rows.size()+" 条"),75,76);
        int left=100,top=115,height=345,width=930,base=top+(int)(max/span*height);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,12));
        for(int i=0;i<=5;i++){double value=max-span*i/5;int y=top+height*i/5;g.setColor(new Color(225,225,225));g.drawLine(left,y,left+width,y);g.setColor(Color.DARK_GRAY);g.drawString(String.format(Locale.ROOT,"%.3g",value),20,y+4);}
        int step=width/Math.max(1,values.length),previousX=0,previousY=0;
        for(int i=0;i<values.length;i++){int x=left+step*i+step/2,y=top+(int)((max-values[i])/span*height);g.setColor(new Color(75,75,75));if(type.equals("line")){if(i>0)g.drawLine(previousX,previousY,x,y);g.fillOval(x-4,y-4,8,8);}else g.fillRect(x-step/3,Math.min(y,base),Math.max(2,step*2/3),Math.max(1,Math.abs(base-y)));previousX=x;previousY=y;
            String label=valueCol==0?String.valueOf(i+1):Objects.toString(rows.get(i).get(0),"");if(label.length()>12)label=label.substring(0,11)+"…";g.setColor(Color.DARK_GRAY);var transform=g.getTransform();g.translate(x-4,top+height+20);g.rotate(Math.PI/5);g.drawString(label,0,0);g.setTransform(transform);
        }
        g.dispose();try(ByteArrayOutputStream out=new ByteArrayOutputStream()){ImageIO.write(image,"png",out);return out.toByteArray();}
    }
}
