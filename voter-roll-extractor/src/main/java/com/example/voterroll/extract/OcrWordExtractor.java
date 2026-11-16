package com.example.voterroll.extract;

import com.example.voterroll.model.Word;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the Tesseract command-line tool on an image and returns the words with their boxes
 * (TSV output). Used for scanned PDFs, PDFs with garbled fonts, and uploaded photos/screenshots.
 */
@Component
public class OcrWordExtractor {

    /** Small text OCRs badly; images narrower than this are upscaled first. */
    private static final int MIN_WIDTH = 3000;

    private final String tesseractPath;
    private final String language;

    public OcrWordExtractor(@Value("${ocr.tesseract-path:tesseract}") String tesseractPath,
                            @Value("${ocr.language:eng}") String language) {
        this.tesseractPath = tesseractPath;
        this.language = language;
    }

    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(tesseractPath, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public List<Word> extract(BufferedImage image) throws IOException {
        BufferedImage prepared = prepare(image);
        // psm 11 = sparse text: every word with its box; the parser does the layout itself.
        List<Word> words = new ArrayList<>(runTsv(prepared, "--psm", "11", "-l", language));
        words.addAll(readSerialBoxes(prepared, words));
        return words;
    }

    /**
     * Sparse-text OCR tends to skip the small boxed serial numbers. For every EPIC found, crop the
     * strip to its left on the same row, erase the box borders, stack the strips into one image and
     * OCR it once as digits. Each result becomes a word positioned just left of its EPIC.
     */
    private List<Word> readSerialBoxes(BufferedImage page, List<Word> words) throws IOException {
        List<Word> epics = words.stream().filter(w -> VoterCardParser.isEpic(w.text())).toList();
        if (epics.isEmpty()) return List.of();

        List<Rectangle> crops = new ArrayList<>();
        for (Word e : epics) {
            int x0 = Math.max(0, Math.round(e.x() - 3.5f * e.width()));
            int x1 = Math.round(e.x() - 0.1f * e.width());
            int y0 = Math.max(0, Math.round(e.y() - 0.8f * e.height()));
            int y1 = Math.min(page.getHeight(), Math.round(e.y() + 1.8f * e.height()));
            crops.add(x1 > x0 && y1 > y0 ? new Rectangle(x0, y0, x1 - x0, y1 - y0) : null);
        }

        int gap = 40, width = 0, height = gap;
        for (Rectangle r : crops) if (r != null) { width = Math.max(width, r.width); height += r.height + gap; }
        if (width == 0) return List.of();

        BufferedImage sheet = new BufferedImage(width + 2 * gap, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        int[] offsetY = new int[crops.size()];
        int y = gap;
        for (int i = 0; i < crops.size(); i++) {
            Rectangle r = crops.get(i);
            if (r == null) continue;
            BufferedImage strip = eraseLines(page.getSubimage(r.x, r.y, r.width, r.height));
            g.drawImage(strip, gap + (width - r.width), y, null); // right-align: digits sit near the EPIC
            offsetY[i] = y;
            y += r.height + gap;
        }
        g.dispose();

        List<Word> found = runTsv(sheet, "--psm", "6", "-l", "eng", "-c", "tessedit_char_whitelist=0123456789");
        List<Word> serials = new ArrayList<>();
        for (int i = 0; i < crops.size(); i++) {
            Rectangle r = crops.get(i);
            if (r == null) continue;
            Word epic = epics.get(i);
            int top = offsetY[i], bottom = top + r.height;
            found.stream()
                    .filter(w -> w.centerY() >= top && w.centerY() < bottom && w.text().matches("\\d{1,5}"))
                    .max(java.util.Comparator.comparingDouble(Word::x))
                    .ifPresent(w -> serials.add(new Word(w.text(), r.x, epic.y(), r.width, epic.height())));
        }
        return serials;
    }

    /** Whitens long horizontal/vertical dark runs (box borders) so they aren't read as 1s. */
    private static BufferedImage eraseLines(BufferedImage src) {
        BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        img.getGraphics().drawImage(src, 0, 0, null);
        int w = img.getWidth(), h = img.getHeight();
        boolean[][] dark = new boolean[w][h];
        for (int x = 0; x < w; x++)
            for (int yy = 0; yy < h; yy++) dark[x][yy] = (img.getRGB(x, yy) & 0xFF) < 128;
        int white = Color.WHITE.getRGB();
        for (int x = 0; x < w; x++) {
            int count = 0;
            for (int yy = 0; yy < h; yy++) if (dark[x][yy]) count++;
            if (count > h * 0.6) for (int yy = 0; yy < h; yy++) img.setRGB(x, yy, white);
        }
        for (int yy = 0; yy < h; yy++) {
            int count = 0;
            for (int x = 0; x < w; x++) if (dark[x][yy]) count++;
            if (count > w * 0.4) for (int x = 0; x < w; x++) img.setRGB(x, yy, white);
        }
        return img;
    }

    private List<Word> runTsv(BufferedImage image, String... args) throws IOException {
        Path tmp = Files.createTempFile("ocr-", ".png");
        try {
            ImageIO.write(image, "png", tmp.toFile());
            List<String> cmd = new ArrayList<>(List.of(tesseractPath, tmp.toString(), "stdout"));
            cmd.addAll(List.of(args));
            cmd.add("tsv");
            Process p = new ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            List<Word> words = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean header = true;
                while ((line = r.readLine()) != null) {
                    if (header) { header = false; continue; }
                    String[] c = line.split("\t", -1);
                    // level page block par line word left top width height conf text
                    if (c.length < 12 || !"5".equals(c[0])) continue;
                    String text = c[11].trim();
                    if (text.isEmpty()) continue;
                    words.add(new Word(text,
                            Float.parseFloat(c[6]), Float.parseFloat(c[7]),
                            Float.parseFloat(c[8]), Float.parseFloat(c[9])));
                }
            }
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("Tesseract timed out");
            }
            if (p.exitValue() != 0) {
                throw new IOException("Tesseract failed with exit code " + p.exitValue());
            }
            return words;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OCR interrupted", e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Greyscale and upscale so the small card text is legible to Tesseract. */
    private BufferedImage prepare(BufferedImage src) {
        double scale = src.getWidth() < MIN_WIDTH ? (double) MIN_WIDTH / src.getWidth() : 1.0;
        scale = Math.min(scale, 4.0);
        int w = (int) Math.round(src.getWidth() * scale);
        int h = (int) Math.round(src.getHeight() * scale);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
