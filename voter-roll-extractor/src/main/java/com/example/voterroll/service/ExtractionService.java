package com.example.voterroll.service;

import com.example.voterroll.extract.OcrWordExtractor;
import com.example.voterroll.extract.PdfWordExtractor;
import com.example.voterroll.extract.VoterCardParser;
import com.example.voterroll.extract.VoterCardParser.PageResult;
import com.example.voterroll.model.PageMeta;
import com.example.voterroll.model.VoterRecord;
import com.example.voterroll.model.Word;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    /** A page with fewer text-layer words than this is treated as scanned. */
    private static final int MIN_TEXT_WORDS = 30;

    private final PdfWordExtractor pdfWords;
    private final OcrWordExtractor ocrWords;
    private final VoterCardParser parser;
    private final int ocrDpi;

    public ExtractionService(PdfWordExtractor pdfWords, OcrWordExtractor ocrWords,
                             VoterCardParser parser, @Value("${ocr.dpi:300}") int ocrDpi) {
        this.pdfWords = pdfWords;
        this.ocrWords = ocrWords;
        this.parser = parser;
        this.ocrDpi = ocrDpi;
    }

    public record Result(List<VoterRecord> records, List<String> warnings) {}

    public Result extract(String fileName, byte[] bytes, boolean forceOcr) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return extractPdf(fileName, bytes, forceOcr);
        if (lower.matches(".*\\.(png|jpe?g|bmp|gif|tiff?)$")) return extractImage(fileName, bytes);
        throw new IllegalArgumentException(fileName + ": only PDF or image files (PNG/JPG/TIFF) are supported");
    }

    private Result extractPdf(String fileName, byte[] bytes, boolean forceOcr) throws IOException {
        List<VoterRecord> records = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean ocrAvailable = ocrWords.isAvailable();

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            PageMeta meta = PageMeta.EMPTY;

            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                int pageNo = i + 1;
                List<Word> words = forceOcr ? List.of() : pdfWords.extract(doc, i);
                PageResult res = parser.parsePage(words, pageNo, fileName, "PDF text", meta);

                // No voter cards from the text layer: scanned page or unreadable font -> OCR it.
                boolean looksScanned = words.size() < MIN_TEXT_WORDS;
                if (res.records().isEmpty() && (forceOcr || looksScanned)) {
                    if (!ocrAvailable) {
                        warnings.add(fileName + " page " + pageNo
                                + ": no text layer and Tesseract OCR is not installed - page skipped");
                        continue;
                    }
                    log.info("{} page {}: running OCR", fileName, pageNo);
                    BufferedImage img = renderer.renderImageWithDPI(i, ocrDpi, ImageType.GRAY);
                    res = parser.parsePage(ocrWords.extract(img), pageNo, fileName, "OCR", meta);
                }
                meta = res.meta();
                records.addAll(res.records());
            }
        }
        addCheckWarnings(fileName, records, warnings);
        return new Result(records, warnings);
    }

    private Result extractImage(String fileName, byte[] bytes) throws IOException {
        if (!ocrWords.isAvailable()) {
            throw new IllegalStateException("Reading images needs Tesseract OCR installed (see README)");
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IllegalArgumentException(fileName + ": not a readable image");
        PageResult res = parser.parsePage(ocrWords.extract(img), 1, fileName, "OCR", PageMeta.EMPTY);
        List<String> warnings = new ArrayList<>();
        addCheckWarnings(fileName, res.records(), warnings);
        return new Result(res.records(), warnings);
    }

    private static void addCheckWarnings(String fileName, List<VoterRecord> records, List<String> warnings) {
        long bad = records.stream().filter(r -> !r.remarks().isEmpty()).count();
        if (bad > 0) warnings.add(fileName + ": " + bad + " card(s) need checking - see the Remarks column");
        if (records.isEmpty()) warnings.add(fileName + ": no voter cards found");
    }
}
