package com.example.voterroll.extract;

import com.example.voterroll.model.PageMeta;
import com.example.voterroll.model.VoterRecord;
import com.example.voterroll.model.Word;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns the positioned words of one roll page into voter records.
 *
 * Layout logic (identical for PDF text and OCR output, at any scale):
 *  1. Every card has an EPIC number in its top-right corner; those locate the cards.
 *  2. The right edges of the EPIC numbers cluster into columns (3 on a standard roll page);
 *     each word belongs to the column whose right edge it falls left of.
 *  3. Within a column, cards are cut at each EPIC row. The serial number is the integer on
 *     the same row, left of the EPIC. Serials OCR misses are filled from neighbouring cards,
 *     since serials run consecutively in reading order.
 *  4. A card's text is matched against "Name / X's Name / House Number / Age / Gender",
 *     so values that wrap onto a second line stay whole.
 */
@Component
public class VoterCardParser {

    /** EPIC: 3 letters + 7 digits (MPV2582039, ZIO1954833). OCR may read the letters as digits. */
    private static final Pattern EPIC = Pattern.compile("^[A-Z0-9]{3}\\d{7}$");
    private static final Pattern SERIAL = Pattern.compile("^\\d{1,5}$");
    private static final Set<String> PHOTO_WORDS = Set.of("photo", "available");

    // Labels are matched loosely so common OCR slips ("Nam", "Fathers Nam", "Numbe'", "Ag +30") still parse.
    private static final String SEP = "\\s*[:;.,?)=+'\"]*\\s*";
    private static final Pattern CARD = Pattern.compile(
            "\\bNa(?:me|rne|m)\\b" + SEP + "(?<name>.*?)\\s*"
            + "\\b(?<rel>Fath|Husb|Moth|Wife|Othe)\\S*\\s*(?:\\S{1,2}\\s+)?(?:Na\\S*)?" + SEP + "(?<relname>.*?)\\s*"
            + "(?:\\b\\S*(?:ouse|use)\\s*)?\\bNum\\S*" + SEP + "(?<house>.*?)\\s*"
            + "\\bAge?" + SEP + "(?<age>\\d{1,3})\\s*"
            + "\\bGen\\S*" + SEP + "(?<gender>Third\\s*Gender|\\S+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AC = Pattern.compile(
            "Assembly\\s+Constituency\\s+No\\.?\\s+and\\s+Name\\s*[:;]?\\s*(.+?)(?:\\s+Part\\s+(?:No|Number).*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PART = Pattern.compile(
            "Part\\s+(?:No\\.?|Number)\\s*[:;]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION = Pattern.compile(
            "Section\\s+No\\.?\\s+and\\s+Name\\s*[:;]?\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    public record PageResult(List<VoterRecord> records, PageMeta meta) {}

    /** A card found on the page, before serials are finalised. */
    private record Card(int column, float y, String serial, String epic, String text) {}

    public PageResult parsePage(List<Word> words, int pageNo, String sourceFile,
                                String extractedBy, PageMeta previousMeta) {
        List<Word> epics = words.stream().filter(w -> isEpic(w.text())).toList();
        if (epics.isEmpty()) {
            return new PageResult(List.of(), previousMeta);
        }

        float lineH = median(words.stream().map(Word::height).toList());
        float firstEpicY = (float) epics.stream().mapToDouble(Word::y).min().orElse(0);
        PageMeta meta = readHeader(words, firstEpicY, lineH).orElse(previousMeta);

        // --- columns from EPIC right edges ---
        float pageWidth = (float) words.stream().mapToDouble(Word::right).max().orElse(1);
        List<Float> colRights = clusterRightEdges(epics, pageWidth * 0.08f);
        float pad = lineH * 1.5f; // card border sits a little right of the EPIC text

        Map<Integer, List<Word>> byColumn = new TreeMap<>();
        for (Word w : words) {
            int col = columnOf(w.centerX(), colRights, pad);
            if (col >= 0) byColumn.computeIfAbsent(col, k -> new ArrayList<>()).add(w);
        }

        List<Card> cards = new ArrayList<>();
        byColumn.forEach((col, colWords) -> cards.addAll(parseColumn(col, colWords, lineH)));

        // Reading order: row by row (EPICs within ~1 line of each other share a row), then column.
        cards.sort(Comparator.comparingDouble(Card::y));
        List<Card> ordered = new ArrayList<>();
        int i = 0;
        while (i < cards.size()) {
            int j = i;
            while (j < cards.size() && cards.get(j).y() - cards.get(i).y() < lineH * 2) j++;
            List<Card> row = new ArrayList<>(cards.subList(i, j));
            row.sort(Comparator.comparingInt(Card::column));
            ordered.addAll(row);
            i = j;
        }

        String[] serials = fillSerials(ordered, meta.lastSerial());
        List<VoterRecord> records = new ArrayList<>();
        int lastSerial = meta.lastSerial();
        for (int k = 0; k < ordered.size(); k++) {
            Card c = ordered.get(k);
            String note = "";
            if (c.serial().isEmpty() && !serials[k].isEmpty()) note = "Serial inferred from position";
            else if (!c.serial().isEmpty() && !c.serial().equals(serials[k])) note = "Serial corrected (read as " + c.serial() + ")";
            records.add(buildRecord(serials[k], note, c, meta, pageNo, sourceFile, extractedBy));
            try { lastSerial = Math.max(lastSerial, Integer.parseInt(serials[k])); } catch (NumberFormatException ignored) { }
        }
        return new PageResult(records, meta.withLastSerial(lastSerial));
    }

    private List<Card> parseColumn(int col, List<Word> colWords, float lineH) {
        List<Word> epics = colWords.stream()
                .filter(w -> isEpic(w.text()))
                .sorted(Comparator.comparingDouble(Word::y))
                .toList();

        // Serial number = integer on the EPIC's row, left of it (box borders stripped).
        Map<Word, String> serialFor = new HashMap<>();
        Set<Word> used = new HashSet<>(epics);
        for (Word epic : epics) {
            colWords.stream()
                    .filter(w -> SERIAL.matcher(stripBox(w.text())).matches())
                    .filter(w -> w.right() <= epic.x())
                    .filter(w -> Math.abs(w.centerY() - epic.centerY()) < lineH * 1.2f)
                    .max(Comparator.comparingDouble(Word::x))
                    .ifPresent(s -> { serialFor.put(epic, stripBox(s.text())); used.add(s); });
        }

        List<Card> out = new ArrayList<>();
        for (int i = 0; i < epics.size(); i++) {
            Word epic = epics.get(i);
            float top = epic.y() - lineH;
            float bottom = i + 1 < epics.size() ? epics.get(i + 1).y() - lineH : Float.MAX_VALUE;

            List<Word> cardWords = colWords.stream()
                    .filter(w -> !used.contains(w))
                    .filter(w -> w.centerY() >= top && w.centerY() < bottom)
                    .filter(w -> !PHOTO_WORDS.contains(w.text().toLowerCase(Locale.ROOT).replaceAll("\\W", "")))
                    .toList();
            String text = String.join(" ", toLines(cardWords, lineH));
            out.add(new Card(col, epic.y(), serialFor.getOrDefault(epic, ""), normaliseEpic(epic.text()), text));
        }
        return out;
    }

    /**
     * Serials on a roll page run consecutively in reading order, so serial - position is the same
     * for every card. The value most cards agree on fixes the sequence; serials that were not read,
     * or disagree with it (OCR dropped a digit), are filled from it. With fewer than two agreeing
     * cards the sequence continues from the previous page instead.
     */
    private static String[] fillSerials(List<Card> ordered, int previousLast) {
        int n = ordered.size();
        Integer[] read = new Integer[n];
        Map<Integer, Integer> votes = new HashMap<>();
        for (int i = 0; i < n; i++) {
            try {
                read[i] = Integer.parseInt(ordered.get(i).serial());
                votes.merge(read[i] - i, 1, Integer::sum);
            } catch (NumberFormatException ignored) { }
        }
        Integer base = votes.entrySet().stream()
                .filter(e -> e.getValue() >= 2 || (n == 1 && previousLast == 0))
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (base == null && previousLast > 0) base = previousLast + 1;

        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            if (base == null) out[i] = read[i] == null ? "" : String.valueOf(read[i]);
            else out[i] = String.valueOf(base + i);
        }
        return out;
    }

    private VoterRecord buildRecord(String serial, String serialNote, Card c, PageMeta meta,
                                    int pageNo, String sourceFile, String extractedBy) {
        List<String> notes = new ArrayList<>();
        if (serial.isEmpty()) notes.add("Serial number not found");
        else if (!serialNote.isEmpty()) notes.add(serialNote);

        Matcher m = CARD.matcher(c.text());
        if (m.find()) {
            String gender = gender(m.group("gender"));
            if (gender.isEmpty()) notes.add("Gender unreadable: " + m.group("gender"));
            return new VoterRecord(serial, c.epic(),
                    clean(m.group("name")),
                    relationType(m.group("rel")),
                    clean(m.group("relname")),
                    clean(m.group("house")),
                    m.group("age"),
                    gender,
                    meta.section(), meta.partNo(), meta.assemblyConstituency(),
                    pageNo, sourceFile, extractedBy, String.join("; ", notes));
        }
        notes.add(0, "CHECK - could not read card: " + c.text());
        return new VoterRecord(serial, c.epic(), "", "", "", "", "", "",
                meta.section(), meta.partNo(), meta.assemblyConstituency(),
                pageNo, sourceFile, extractedBy, String.join("; ", notes));
    }

    // ---------------------------------------------------------------- header

    private PageMeta readHeader(List<Word> words, float firstEpicY, float lineH) {
        List<Word> headerWords = words.stream().filter(w -> w.y() < firstEpicY - lineH * 0.5f).toList();
        String ac = "", part = "", section = "";
        for (String line : toLines(headerWords, lineH)) {
            Matcher m;
            if (ac.isEmpty() && (m = AC.matcher(line)).find()) ac = clean(m.group(1));
            if (part.isEmpty() && (m = PART.matcher(line)).find()) part = m.group(1);
            if (section.isEmpty() && (m = SECTION.matcher(line)).find()) section = clean(m.group(1));
        }
        return new PageMeta(ac, part, section, 0);
    }

    // ---------------------------------------------------------------- geometry helpers

    /** Groups words into reading-order lines (top to bottom, left to right). */
    static List<String> toLines(List<Word> words, float lineH) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(Word::centerY));
        List<List<Word>> lines = new ArrayList<>();
        float currentY = Float.NaN;
        for (Word w : sorted) {
            if (lines.isEmpty() || Math.abs(w.centerY() - currentY) > lineH * 0.5f) {
                lines.add(new ArrayList<>());
                currentY = w.centerY();
            }
            lines.get(lines.size() - 1).add(w);
        }
        return lines.stream()
                .map(l -> l.stream().sorted(Comparator.comparingDouble(Word::x))
                        .map(Word::text).collect(Collectors.joining(" ")))
                .toList();
    }

    private static List<Float> clusterRightEdges(List<Word> epics, float gap) {
        List<Float> rights = epics.stream().map(Word::right).sorted().toList();
        List<Float> clusters = new ArrayList<>();
        List<Float> current = new ArrayList<>();
        for (float r : rights) {
            if (!current.isEmpty() && r - current.get(current.size() - 1) > gap) {
                clusters.add(Collections.max(current));
                current.clear();
            }
            current.add(r);
        }
        if (!current.isEmpty()) clusters.add(Collections.max(current));
        return clusters;
    }

    private static int columnOf(float x, List<Float> colRights, float pad) {
        for (int i = 0; i < colRights.size(); i++) {
            if (x <= colRights.get(i) + pad) return i;
        }
        return -1; // right of the last column (page margin, side bars)
    }

    private static float median(List<Float> values) {
        if (values.isEmpty()) return 10f;
        List<Float> s = new ArrayList<>(values);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    // ---------------------------------------------------------------- text helpers

    static boolean isEpic(String t) {
        String s = t.replaceAll("[^A-Za-z0-9]", "");
        // Real EPIC prefixes are letters; demand at least one letter unless OCR turned all three into digits
        // that map back to letters (e.g. ZIO -> 210).
        return EPIC.matcher(s).matches()
                && (Character.isLetter(s.charAt(0)) || s.substring(0, 3).chars().allMatch(ch -> "01258".indexOf(ch) >= 0));
    }

    /** OCR often reads the letter prefix as look-alike digits: 0→O, 1→I, 2→Z, 5→S, 8→B. */
    private static String normaliseEpic(String t) {
        StringBuilder sb = new StringBuilder(t.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT));
        for (int i = 0; i < 3; i++) {
            switch (sb.charAt(i)) {
                case '0' -> sb.setCharAt(i, 'O');
                case '1' -> sb.setCharAt(i, 'I');
                case '2' -> sb.setCharAt(i, 'Z');
                case '5' -> sb.setCharAt(i, 'S');
                case '8' -> sb.setCharAt(i, 'B');
                default -> { }
            }
        }
        return sb.toString();
    }

    /** Serial boxes come through OCR as "4]", "|12|", "(7)". */
    private static String stripBox(String t) {
        return t.replaceAll("[\\[\\]|(){}_.,'`]", "");
    }

    private static String relationType(String raw) {
        String r = raw.toLowerCase(Locale.ROOT);
        if (r.startsWith("fath")) return "Father";
        if (r.startsWith("husb")) return "Husband";
        if (r.startsWith("moth")) return "Mother";
        if (r.startsWith("wife")) return "Wife";
        return "Other";
    }

    private static String gender(String raw) {
        String g = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (g.contains("third")) return "Third Gender";
        if (g.contains("fem") || g.endsWith("emale") || g.equals("emaie")) return "Female";
        if (g.equals("male") || g.equals("ale") || g.equals("maie")) return "Male";
        return "";
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("^[^\\p{L}\\p{N}]+", "")
                .replaceAll("[^\\p{L}\\p{N}.)]+$", "")
                .replaceAll("\\s+", " ").trim();
    }
}
