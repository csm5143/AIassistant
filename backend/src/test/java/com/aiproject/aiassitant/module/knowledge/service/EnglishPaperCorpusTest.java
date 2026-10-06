package com.aiproject.aiassitant.module.knowledge.service;

import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

/** Optional real-paper regression; the corpus lives in D:/AIassistant/eval/english_papers. */
class EnglishPaperCorpusTest {
    @Test void inspectPaperLayout() throws Exception {
        Path root = Path.of("D:/AIassistant/eval/english_papers");
        assumeTrue(Files.isDirectory(root));
        for (String name : new String[]{"attention-is-all-you-need", "lost-in-the-middle", "bge-m3"}) {
            Path paper = root.resolve(name + ".pdf");
            assumeTrue(Files.isRegularFile(paper));
            try (var pdf = Loader.loadPDF(paper.toFile())) {
                var pages = new PdfPageInspector().extractPages(pdf);
                System.out.println("PAPER " + name + " PAGES=" + pages.size());
                if (name.equals("attention-is-all-you-need")) assertFalse(pages.get(1).complexColumns());
                else {
                    assertTrue(pages.get(1).complexColumns(), "real narrow-gutter columns must use layout extraction");
                    assertTrue(pages.get(2).complexColumns());
                }
                for (int i = 0; i < Math.min(4, pages.size()); i++) {
                    var page = pages.get(i);
                    System.out.println("PAGE " + (i + 1) + " COMPLEX=" + page.complexColumns() + " TEXT=" + page.text().length());
                    if (i == 1) System.out.println(page.text().substring(0, Math.min(1000, page.text().length())).replace('\n', '|'));
                }
                {
                    try (var sample = new org.apache.pdfbox.pdmodel.PDDocument()) {
                        int probePage = name.equals("attention-is-all-you-need") ? 3 : name.equals("bge-m3") ? 6 : 2;
                        var sourcePage = pdf.getPage(probePage-1);
                        var imported = sample.importPage(sourcePage);
                        if (sourcePage.getResources() != null) imported.setResources(sourcePage.getResources());
                        sample.save(root.resolve(name + "-page"+probePage+"-probe.pdf").toFile());
                    }
                }
            }
        }
    }
}
