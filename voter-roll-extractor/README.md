# Voter Roll → Excel

Spring Boot web app: upload electoral-roll PDFs (or page images), download an `.xlsx`
with one row per voter card.

**Columns:** S.No · EPIC No · Name · Relation Type (Father/Husband/Mother/Other) · Relation Name ·
House Number · Age · Gender · Section · Part No · Assembly Constituency · Page · Source File ·
Read By (PDF text / OCR) · Remarks

## Run

Requires Java 17+ and Maven.

```bash
mvn spring-boot:run
# or
mvn package && java -jar target/voter-roll-extractor-1.0.0.jar
```

Open http://localhost:8080, choose one or more files, click **Convert to Excel**.

API: `POST /api/convert` (multipart, field `files`, optional `forceOcr=true`) → xlsx.

```bash
curl -F files=@roll.pdf http://localhost:8080/api/convert -o voters.xlsx
```

## OCR (only for scanned PDFs and images)

PDFs with a text layer (like the ECI draft roll PDFs) are read directly — exact and fast,
no OCR needed. Scanned pages and images need [Tesseract](https://github.com/tesseract-ocr/tesseract):

- **Windows:** install from https://github.com/UB-Mannheim/tesseract/wiki, then set in
  `src/main/resources/application.properties`:
  `ocr.tesseract-path=C:/Program Files/Tesseract-OCR/tesseract.exe`
- **Ubuntu:** `sudo apt install tesseract-ocr`
- **macOS:** `brew install tesseract`

When a PDF page has (almost) no text layer it is OCR'd automatically at 300 DPI. If a PDF has text
but the result comes out empty or garbled (some PDFs use fonts that don't map to real letters),
tick **Force OCR**.

## How it works

1. **Words with positions** — PDFBox reads each word and its box from the text layer
   (`PdfWordExtractor`); for scans/images, Tesseract returns the same (`OcrWordExtractor`).
2. **Layout** (`VoterCardParser`) — every card has an EPIC number (3 letters + 7 digits) top-right.
   The EPICs' right edges define the columns; each column is cut into cards at each EPIC row.
   "Photo Available" text is dropped.
3. **Fields** — each card's text is matched against
   `Name : … Father's/Husband's Name : … House Number : … Age : … Gender : …`,
   so values wrapped onto a second line (e.g. `VENKATESWARA RAO` / `PADARTHI`) stay whole.
4. **Header** — Assembly Constituency, Part Number and Section are read from the top of each
   page and carried forward to pages that lack them. Cover/map/summary pages are skipped.

### OCR safeguards
- OCR often reads an EPIC prefix like `ZIO` as `210`; prefixes are mapped back to letters.
- Small boxed serial numbers are re-read in a second targeted OCR pass.
- Serials run consecutively, so ones that couldn't be read, or were misread (`14` → `4`),
  are filled from the sequence. Those rows say so in **Remarks**.
- Cards that couldn't be parsed are kept, highlighted yellow, with the raw text in Remarks.
  A **Warnings** sheet summarises anything to check.

Screenshots work but are best-effort: on-screen text is too small for reliable OCR.
Upload the PDF itself, or scans at 300 DPI or more.

## Project layout

```
src/main/java/com/example/voterroll/
  VoterRollApplication.java
  web/ConvertController.java        upload endpoint, returns xlsx
  service/ExtractionService.java    PDF vs image, text layer vs OCR fallback
  extract/PdfWordExtractor.java     PDFBox words + positions
  extract/OcrWordExtractor.java     Tesseract TSV words + serial-box pass
  extract/VoterCardParser.java      layout + field parsing
  excel/ExcelWriter.java            Apache POI workbook
  model/                            Word, VoterRecord, PageMeta
src/main/resources/static/index.html   upload page
```

To support another layout (e.g. a different state's roll or a Telugu-language roll), the
places to adjust are the `CARD`, `AC`, `PART` and `SECTION` patterns in `VoterCardParser`
and `ocr.language` (e.g. `tel+eng`, with the Telugu traineddata installed).
