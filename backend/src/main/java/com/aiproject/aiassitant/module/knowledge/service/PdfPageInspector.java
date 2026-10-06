package com.aiproject.aiassitant.module.knowledge.service;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.function.IntConsumer;

/** Cheap page routing. Scans, damaged text, tables, columns and substantial graphics. */
final class PdfPageInspector extends PDFTextStripper {
    private final Map<Integer, List<TextPosition>> rows = new HashMap<>();
    private StringWriter pageWriter;
    private List<PageText> capturedPages;
    private IntConsumer pageProgress;
    private int notifiedPage;
    record PageText(String text, boolean complexColumns) {}
    PdfPageInspector() throws IOException { setSortByPosition(true); }

    /** PDFTextStripper traverses every page even when a one-page range is set. Capture all pages in one pass. */
    List<PageText> extractPages(PDDocument pdf) throws IOException {
        return extractPages(pdf, null);
    }

    List<PageText> extractPages(PDDocument pdf, IntConsumer progress) throws IOException {
        int count = pdf.getNumberOfPages();
        List<PageText> pages = new ArrayList<>(Collections.nCopies(count, new PageText("", false)));
        pageWriter = new StringWriter(4096);
        capturedPages = pages;
        pageProgress = progress;
        notifiedPage = 0;
        try {
            setStartPage(1);
            setEndPage(count);
            writeText(pdf, pageWriter);
            notifyThrough(count); // Trailing pages without a content stream have no endPage callback.
            return pages;
        } finally {
            pageWriter = null;
            capturedPages = null;
            pageProgress = null;
            rows.clear();
        }
    }

    private void notifyThrough(int page) {
        while (notifiedPage < page) {
            notifiedPage++;
            if (pageProgress != null) pageProgress.accept(notifiedPage);
        }
    }

    @Override protected void startPage(PDPage page) throws IOException {
        super.startPage(page);
        if (pageWriter != null) {
            notifyThrough(getCurrentPageNo() - 1); // Include blank pages before this one.
            pageWriter.getBuffer().setLength(0);
            rows.clear();
        }
    }

    @Override protected void endPage(PDPage page) throws IOException {
        if (pageWriter != null) {
            int index = getCurrentPageNo() - 1;
            capturedPages.set(index, new PageText(pageWriter.toString(), complexColumns()));
            pageWriter.getBuffer().setLength(0);
            notifyThrough(getCurrentPageNo());
        }
        super.endPage(page);
    }
    @Override protected void processTextPosition(TextPosition position) {
        rows.computeIfAbsent(Math.round(position.getYDirAdj()/4), ignored -> new ArrayList<>()).add(position);
        super.processTextPosition(position);
    }
    boolean complexColumns() {
        int tabularRows=0, columnRows=0, pairedColumnRows=0;
        double leftEdge=Double.POSITIVE_INFINITY, rightEdge=Double.NEGATIVE_INFINITY;
        for (var row : rows.values()) for (var position : row) {
            leftEdge=Math.min(leftEdge,position.getXDirAdj());
            rightEdge=Math.max(rightEdge,position.getXDirAdj()+position.getWidthDirAdj());
        }
        double width=rightEdge-leftEdge, center=(leftEdge+rightEdge)/2;
        for (var row : rows.values()) {
            row.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));
            int gaps=0; boolean wide=false, paired=false;
            for (int i=1;i<row.size();i++) {
                float gap=row.get(i).getXDirAdj()-row.get(i-1).getXDirAdj()-row.get(i-1).getWidthDirAdj();
                if(gap>32) gaps++;
                if(gap>80) wide=true;
                // Two justified columns can be only 15-30pt apart. The old >80pt
                // rule missed real papers and let PDFBox interleave the columns.
                // Require text on both sides of a stable central gutter to avoid
                // treating ordinary word spacing or short centered headings as columns.
                if(!paired && width>250 && gap>=14 &&
                        Math.abs((row.get(i).getXDirAdj()+row.get(i-1).getXDirAdj()+row.get(i-1).getWidthDirAdj())/2-center)<width*0.12 &&
                        row.get(i-1).getXDirAdj()-row.get(0).getXDirAdj()>width*0.23 &&
                        row.get(row.size()-1).getXDirAdj()-row.get(i).getXDirAdj()>width*0.23) {
                    paired=true;
                }
            }
            if(gaps>=2) tabularRows++;
            if(wide) columnRows++;
            if(paired) pairedColumnRows++;
        }
        return tabularRows>=3 || columnRows>=6 || pairedColumnRows>=4;
    }
    static boolean damaged(String text) {
        long visible=text.codePoints().filter(c->!Character.isWhitespace(c)).count();
        long bad=text.codePoints().filter(c->c==0xfffd || (Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')).count();
        return bad>0 && bad*100>=Math.max(1,visible);
    }
    static final class Graphics extends PDFGraphicsStreamEngine {
        int paths, horizontalRules, verticalRules, diagramEdges, chartShapes;
        private record Line(double axis, double from, double to) {}
        private final List<Line> horizontal = new ArrayList<>(), vertical = new ArrayList<>();
        private final List<Line> pendingHorizontal = new ArrayList<>(), pendingVertical = new ArrayList<>();
        private int pendingEdges, pendingShapes;
        boolean substantialImage; double largestImageCoverage; private Point2D point=new Point2D.Float();
        Graphics(PDPage page){super(page);}
        void inspect() throws IOException {processPage(getPage());}
        @Override public void drawImage(PDImage image) {
            var m=getGraphicsState().getCurrentTransformationMatrix();
            double area=Math.abs(m.getScaleX()*m.getScaleY()-m.getShearX()*m.getShearY());
            var box=getPage().getCropBox();
            if(image.getWidth()>=160 && image.getHeight()>=90) {
                double coverage=area/(box.getWidth()*box.getHeight());
                largestImageCoverage=Math.max(largestImageCoverage,coverage);
                if(coverage>=0.035) substantialImage=true;
            }
        }
        boolean tableGrid(){
            // A code box has two horizontal edges. A table has shared interior dividers.
            List<Line> merged=new ArrayList<>();
            for(var line:vertical.stream().sorted(Comparator.comparingDouble(Line::axis).thenComparingDouble(Line::from)).toList()) {
                if(!merged.isEmpty()) {
                    var last=merged.get(merged.size()-1);
                    if(Math.abs(last.axis()-line.axis())<2 && line.from()<=last.to()+2) {
                        merged.set(merged.size()-1,new Line(last.axis(),Math.min(last.from(),line.from()),Math.max(last.to(),line.to())));continue;
                    }
                }
                merged.add(line);
            }
            int crossing=0;
            for(var v:merged) {
                Set<Integer> levels=new HashSet<>();
                for(var h:horizontal) if(h.from()-2<=v.axis() && h.to()+2>=v.axis() && h.axis()>=v.from()-2 && h.axis()<=v.to()+2)
                    levels.add((int)Math.round(h.axis()/3));
                if(levels.size()>=3) crossing++;
            }
            return crossing>=2;
        }
        boolean diagram(){return diagramEdges>=6 || chartShapes>=3;}
        @Override public void appendRectangle(Point2D p0,Point2D p1,Point2D p2,Point2D p3){
            paths++;point=p0;
            double width=p0.distance(p1),height=p1.distance(p2);
            if(width>=18 && height>=20 && width<getPage().getCropBox().getWidth()*0.45) pendingShapes++;
            rememberLine(p0.getX(),p0.getY(),p1.getX(),p1.getY());rememberLine(p1.getX(),p1.getY(),p2.getX(),p2.getY());
            rememberLine(p2.getX(),p2.getY(),p3.getX(),p3.getY());rememberLine(p3.getX(),p3.getY(),p0.getX(),p0.getY());
        }
        private void rememberLine(double x0,double y0,double x1,double y1) {
            if(Math.abs(x1-x0)>=100 && Math.abs(y1-y0)<2) pendingHorizontal.add(new Line((y0+y1)/2,Math.min(x0,x1),Math.max(x0,x1)));
            if(Math.abs(y1-y0)>=30 && Math.abs(x1-x0)<2) pendingVertical.add(new Line((x0+x1)/2,Math.min(y0,y1),Math.max(y0,y1)));
        }
        @Override public void moveTo(float x,float y){point=new Point2D.Float(x,y);}
        @Override public void lineTo(float x,float y){
            double dx=Math.abs(x-point.getX()),dy=Math.abs(y-point.getY());
            if(dx>=100 && dy<2) horizontalRules++;
            if(dy>=50 && dx<2) verticalRules++;
            if(dx>=40 && dy>=15 || dy>=40 && dx>=15) pendingEdges++;
            rememberLine(point.getX(),point.getY(),x,y);
            paths++;point=new Point2D.Float(x,y);
        }
        @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3){
            if(point.distance(x3,y3)>=50) pendingEdges++;
            paths++;point=new Point2D.Float(x3,y3);
        }
        @Override public Point2D getCurrentPoint(){return point;}
        @Override public void closePath(){}
        private void clearPending(){pendingHorizontal.clear();pendingVertical.clear();pendingEdges=0;pendingShapes=0;}
        @Override public void endPath(){clearPending();} // Clipping rectangles are not visible table borders.
        @Override public void strokePath(){horizontal.addAll(pendingHorizontal);vertical.addAll(pendingVertical);diagramEdges+=pendingEdges;clearPending();}
        @Override public void fillPath(int rule){chartShapes+=pendingShapes;clearPending();}
        @Override public void fillAndStrokePath(int rule){chartShapes+=pendingShapes;strokePath();}
        @Override public void clip(int rule){}
        @Override public void shadingFill(COSName name){paths+=16;}
    }
}
