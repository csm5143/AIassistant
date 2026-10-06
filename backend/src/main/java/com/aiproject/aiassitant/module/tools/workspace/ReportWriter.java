package com.aiproject.aiassitant.module.tools.workspace;

import com.aiproject.aiassitant.common.BizException;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.util.Units;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.fontbox.ttf.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Standalone reports (not chat transcripts), with real Word/PDF tables and CJK text. */
@Component
public class ReportWriter {
    public record Figure(String caption, byte[] bytes) {}
    record Block(String kind, int level, String text, List<List<String>> cells) {}
    public byte[] write(String title,String markdown,String format,List<Figure> figures) throws IOException {
        if(title==null||title.isBlank()||title.length()>120)throw new BizException(400,"报告标题需为 1–120 字");
        if(markdown==null||markdown.isBlank()||markdown.length()>60000)throw new BizException(400,"报告正文需为 1–60000 字");
        var blocks=blocks(markdown);
        // The export already renders the title; models often repeat it as the first H1.
        if(!blocks.isEmpty() && blocks.get(0).kind.equals("heading") && plain(blocks.get(0).text).strip().equals(plain(title).strip()))
            blocks.remove(0);
        return switch(format){case "docx"->word(title,blocks,figures);case "pdf"->pdf(title,blocks,figures);default->throw new BizException(400,"报告支持 docx、pdf");};
    }
    static List<Block> blocks(String markdown) {
        String[] lines=markdown.replace("\r\n","\n").split("\n");List<Block> out=new ArrayList<>();boolean code=false;
        for(int i=0;i<lines.length;i++){
            String s=lines[i];if(s.strip().startsWith("```")){code=!code;continue;}
            if(!code&&i+1<lines.length&&s.strip().startsWith("|")&&lines[i+1].matches("\\s*\\|?\\s*:?-{3,}.*")){
                List<List<String>> rows=new ArrayList<>();rows.add(cells(s));i++;
                while(i+1<lines.length&&lines[i+1].strip().startsWith("|")){rows.add(cells(lines[++i]));}
                int count=rows.get(0).size();if(count>8)throw new BizException(400,"报告表格最多 8 列，请把宽表作为 Excel 附件");
                for(var row:rows)if(row.size()!=count)throw new BizException(400,"Markdown 表格各行列数不一致");
                out.add(new Block("table",0,"",rows));continue;
            }
            if(s.isBlank()||s.matches("\\s*[-*_]{3,}\\s*"))continue;
            var h=Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(s);
            if(!code&&h.matches())out.add(new Block("heading",h.group(1).length(),h.group(2),List.of()));
            else out.add(new Block(code?"code":"paragraph",0,s.replaceFirst("^\\s*[-*+]\\s+","• ").replaceFirst("^>\\s?",""),List.of()));
        }return out;
    }
    private static List<String> cells(String s){String t=s.strip();if(t.startsWith("|"))t=t.substring(1);if(t.endsWith("|"))t=t.substring(0,t.length()-1);return Arrays.stream(t.split("(?<!\\\\)\\|",-1)).map(v->v.strip().replace("\\|","|")).toList();}
    private byte[] word(String title,List<Block> blocks,List<Figure> figures) throws IOException {
        try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            XWPFStyles styles=doc.createStyles();
            for(String name:List.of("Title","Heading1","Heading2","Heading3")){
                var definition=org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory.newInstance();definition.setStyleId(name);definition.addNewName().setVal(name);var r=definition.addNewRPr();r.addNewColor().setVal("000000");r.addNewB();styles.addStyle(new XWPFStyle(definition));
            }
            var section=doc.getDocument().getBody().addNewSectPr();var margins=section.addNewPgMar();margins.setTop(1100);margins.setBottom(1100);margins.setLeft(1100);margins.setRight(1100);
            XWPFParagraph heading=doc.createParagraph();heading.setStyle("Title");heading.setSpacingAfter(240);inline(heading,title,22,true);
            for(Block block:blocks){
                if(block.kind.equals("table")){
                    XWPFTable table=doc.createTable(block.cells.size(),block.cells.get(0).size());table.setWidth("100%");
                    table.setTopBorder(XWPFTable.XWPFBorderType.SINGLE,4,0,"D9D9D9");table.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE,4,0,"D9D9D9");table.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE,4,0,"D9D9D9");table.setRightBorder(XWPFTable.XWPFBorderType.SINGLE,4,0,"D9D9D9");table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE,4,0,"D9D9D9");table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE,4,0,"D9D9D9");table.setCellMargins(90,100,90,100);
                    for(int r=0;r<block.cells.size();r++){var row=table.getRow(r);if(r==0)row.setRepeatHeader(true);for(int c=0;c<block.cells.get(r).size();c++){var cell=row.getCell(c);cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);if(r==0)cell.setColor("E8E8E8");var p=cell.getParagraphs().get(0);p.setSpacingAfter(40);inline(p,block.cells.get(r).get(c),10,r==0);}}
                    doc.createParagraph().setSpacingAfter(80);continue;
                }
                XWPFParagraph p=doc.createParagraph();p.setSpacingAfter(block.kind.equals("heading")?140:100);p.setSpacingBetween(1.35);
                if(block.kind.equals("heading")){p.setStyle("Heading"+Math.min(3,block.level));p.setKeepNext(true);inline(p,block.text,block.level==1?16:13,true);}
                else if(block.kind.equals("code")){XWPFRun r=p.createRun();r.setFontFamily("Consolas");r.setFontSize(9);r.setColor("000000");r.setText(block.text);}
                else inline(p,block.text,11,false);
            }
            for(Figure figure:figures){XWPFParagraph caption=doc.createParagraph();caption.setSpacingAfter(120);caption.setKeepNext(true);inline(caption,figure.caption,11,true);var p=doc.createParagraph();p.setAlignment(ParagraphAlignment.CENTER);try(var image=new ByteArrayInputStream(figure.bytes)){p.createRun().addPicture(image,Document.PICTURE_TYPE_PNG,"chart.png",Units.toEMU(460),Units.toEMU(251));}catch(org.apache.poi.openxml4j.exceptions.InvalidFormatException e){throw new IOException("图表无法嵌入",e);}}
            doc.write(bytes);return bytes.toByteArray();
        }
    }
    private static void inline(XWPFParagraph p,String text,int size,boolean bold){
        Pattern pattern=Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^]\\n]+)]\\((https?://[^)\\s]+)\\)");Matcher m=pattern.matcher(text);int last=0;
        while(m.find()){if(m.start()>last)run(p,text.substring(last,m.start()),size,bold,false);if(m.group(1)!=null)run(p,m.group(1),size,true,false);else if(m.group(2)!=null)run(p,m.group(2),size,bold,true);else{var link=p.createHyperlinkRun(m.group(4));link.setText(m.group(3));link.setColor("000000");link.setUnderline(UnderlinePatterns.SINGLE);link.setFontSize(size);}last=m.end();}
        if(last<text.length())run(p,text.substring(last),size,bold,false);
    }
    private static void run(XWPFParagraph p,String value,int size,boolean bold,boolean code){var r=p.createRun();r.setFontFamily(code?"Consolas":"Microsoft YaHei");r.setFontSize(size);r.setColor("000000");r.setBold(bold);r.setText(value);}
    private static String plain(String s){return s.replaceAll("\\*\\*(.+?)\\*\\*","$1").replaceAll("`([^`]+)`","$1").replaceAll("\\[([^]]+)]\\((https?://[^)]+)\\)","$1 ($2)");}
    private byte[] pdf(String title,List<Block> blocks,List<Figure> figures) throws IOException {
        try(PdfLayout layout=new PdfLayout();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            layout.text(title,21,30);layout.y-=12;
            for(Block b:blocks){if(b.kind.equals("table"))layout.table(b.cells);else if(b.kind.equals("heading")){layout.ensure(65);layout.y-=8;layout.text(plain(b.text),b.level==1?16:13,22);}else layout.text(plain(b.text),b.kind.equals("code")?9:11,17);}
            for(Figure f:figures){var img=PDImageXObject.createFromByteArray(layout.doc,f.bytes,"chart");float height=layout.width*img.getHeight()/img.getWidth();layout.ensure(height+80);layout.text(f.caption,12,22);layout.cs.drawImage(img,layout.left,layout.y-height,layout.width,height);layout.y-=height+20;}
            layout.finish();layout.doc.save(bytes);return bytes.toByteArray();
        }
    }
    static final class PdfLayout implements AutoCloseable {
        final PDDocument doc=new PDDocument();final List<TrueTypeCollection>collections=new ArrayList<>();PDType0Font font;PDPageContentStream cs;float y;final float left=48,width=499;int pageNumber=0;
        PdfLayout() throws IOException {
            for(String path:List.of("C:/Windows/Fonts/msyh.ttc","C:/Windows/Fonts/simhei.ttf","D:/AIassistant-env/models/fonts/NotoSansSC-Regular.ttf","/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")){
                if(!Files.isRegularFile(Path.of(path)))continue;
                try{if(path.endsWith(".ttc")){var collection=new TrueTypeCollection(new File(path));collections.add(collection);TrueTypeFont[]first=new TrueTypeFont[1];collection.processAllFonts(f->{if(first[0]==null)first[0]=f;});if(first[0]!=null)font=PDType0Font.load(doc,first[0],true);}else font=PDType0Font.load(doc,new File(path));if(font!=null)break;}catch(Exception ignored){}
            }
            if(font==null){doc.close();for(var c:collections)c.close();throw new IOException("未找到中文字体，请配置 NotoSansSC-Regular.ttf 或系统中文字体");}page();
        }
        String supported(String text){StringBuilder b=new StringBuilder();text.codePoints().forEach(c->{if(c=='\n'||c=='\t'){b.append(c=='\t'?' ': '\n');return;}if(c<32)return;String s=new String(Character.toChars(c));try{font.getStringWidth(s);b.append(s);}catch(Exception e){b.append('□');}});return b.toString();}
        List<String>wrap(String raw,float size,float available) throws IOException {String text=supported(raw);List<String>lines=new ArrayList<>();StringBuilder s=new StringBuilder();for(int c:text.codePoints().toArray()){String part=new String(Character.toChars(c));if(c=='\n'){lines.add(s.toString());s.setLength(0);continue;}if(!s.isEmpty()&&font.getStringWidth(s+part)*size/1000>available){lines.add(s.toString());s.setLength(0);}s.append(part);}if(!s.isEmpty()||lines.isEmpty())lines.add(s.toString());return lines;}
        void ensure(float h)throws IOException{if(y-h<48)page();}
        void page()throws IOException{if(cs!=null){footer();cs.close();}PDPage p=new PDPage(PDRectangle.A4);doc.addPage(p);pageNumber++;cs=new PDPageContentStream(doc,p);y=790;}
        void line(String s,float x,float baseline,float size)throws IOException{cs.beginText();cs.setFont(font,size);cs.newLineAtOffset(x,baseline);cs.showText(s);cs.endText();}
        void text(String s,float size,float leading)throws IOException{for(String l:wrap(s,size,width)){ensure(leading);line(l,left,y,size);y-=leading;}y-=4;}
        void table(List<List<String>>rows)throws IOException{
            float cellWidth=width/rows.get(0).size();List<List<String>>header=wrappedCells(rows.get(0),cellWidth);int headerLines=header.stream().mapToInt(List::size).max().orElse(1);
            for(int r=0;r<rows.size();r++){
                var cells=wrappedCells(rows.get(r),cellWidth);int count=cells.stream().mapToInt(List::size).max().orElse(1),offset=0;
                while(offset<count){if(y<100){page();if(r>0)drawRow(header,0,headerLines,cellWidth,true);}int fit=Math.max(1,Math.min(count-offset,(int)((y-48-12)/14)));drawRow(cells,offset,fit,cellWidth,r==0);offset+=fit;}
            }y-=14;
        }
        List<List<String>>wrappedCells(List<String>row,float w)throws IOException{List<List<String>>cells=new ArrayList<>();for(String c:row)cells.add(wrap(plain(c),10,w-12));return cells;}
        void drawRow(List<List<String>>cells,int offset,int lines,float w,boolean header)throws IOException{float height=lines*14+12;for(int c=0;c<cells.size();c++){float x=left+c*w;if(header){cs.setNonStrokingColor(new java.awt.Color(235,235,235));cs.addRect(x,y-height,w,height);cs.fill();}cs.setStrokingColor(new java.awt.Color(210,210,210));cs.setLineWidth(.5f);cs.addRect(x,y-height,w,height);cs.stroke();cs.setNonStrokingColor(java.awt.Color.BLACK);for(int n=0;n<lines;n++){int index=offset+n;if(index<cells.get(c).size())line(cells.get(c).get(index),x+6,y-16-n*14,10);}}y-=height;}
        void footer()throws IOException{line(String.valueOf(pageNumber),left+width/2,27,9);}
        void finish()throws IOException{if(cs!=null){footer();cs.close();cs=null;}}
        @Override public void close()throws IOException{if(cs!=null)cs.close();doc.close();for(var c:collections)c.close();}
    }
}
