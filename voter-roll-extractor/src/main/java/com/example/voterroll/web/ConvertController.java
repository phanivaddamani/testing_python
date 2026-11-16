package com.example.voterroll.web;

import com.example.voterroll.excel.ExcelWriter;
import com.example.voterroll.model.VoterRecord;
import com.example.voterroll.service.ExtractionService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ConvertController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ExtractionService extraction;
    private final ExcelWriter excel;

    public ConvertController(ExtractionService extraction, ExcelWriter excel) {
        this.extraction = extraction;
        this.excel = excel;
    }

    /** Upload one or more PDFs/images; returns a single .xlsx with every voter found. */
    @PostMapping(value = "/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> convert(@RequestParam("files") List<MultipartFile> files,
                                          @RequestParam(value = "forceOcr", defaultValue = "false") boolean forceOcr)
            throws IOException {
        List<VoterRecord> all = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f.isEmpty()) continue;
            String name = f.getOriginalFilename() == null ? "upload" : f.getOriginalFilename();
            ExtractionService.Result r = extraction.extract(name, f.getBytes(), forceOcr);
            all.addAll(r.records());
            warnings.addAll(r.warnings());
        }

        String fileName = "voter-roll-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .header("X-Record-Count", String.valueOf(all.size()))
                .header("X-Warning-Count", String.valueOf(warnings.size()))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition, X-Record-Count, X-Warning-Count")
                .body(excel.write(all, warnings));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, IOException.class})
    public ResponseEntity<Map<String, String>> handleError(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
