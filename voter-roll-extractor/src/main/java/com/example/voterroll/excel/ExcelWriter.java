package com.example.voterroll.excel;

import com.example.voterroll.model.VoterRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;

@Component
public class ExcelWriter {

    private record Col(String header, Function<VoterRecord, Object> value, int width) {}

    private static final List<Col> COLUMNS = List.of(
            new Col("S.No", r -> asNumber(r.serialNo()), 8),
            new Col("EPIC No", VoterRecord::epicNo, 14),
            new Col("Name", VoterRecord::name, 30),
            new Col("Relation Type", VoterRecord::relationType, 13),
            new Col("Relation Name", VoterRecord::relationName, 30),
            new Col("House Number", VoterRecord::houseNumber, 13),
            new Col("Age", r -> asNumber(r.age()), 6),
            new Col("Gender", VoterRecord::gender, 9),
            new Col("Section", VoterRecord::section, 28),
            new Col("Part No", r -> asNumber(r.partNo()), 8),
            new Col("Assembly Constituency", VoterRecord::assemblyConstituency, 22),
            new Col("Page", VoterRecord::page, 6),
            new Col("Source File", VoterRecord::sourceFile, 30),
            new Col("Read By", VoterRecord::extractedBy, 9),
            new Col("Remarks", VoterRecord::remarks, 40));

    public byte[] write(List<VoterRecord> records, List<String> warnings) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Voters");

            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle checkStyle = wb.createCellStyle();
            checkStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            checkStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row header = sheet.createRow(0);
            for (int c = 0; c < COLUMNS.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(COLUMNS.get(c).header());
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(c, COLUMNS.get(c).width() * 256);
            }

            int rowIdx = 1;
            for (VoterRecord r : records) {
                Row row = sheet.createRow(rowIdx++);
                boolean needsCheck = !r.remarks().isEmpty();
                for (int c = 0; c < COLUMNS.size(); c++) {
                    Cell cell = row.createCell(c);
                    Object v = COLUMNS.get(c).value().apply(r);
                    if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                    else cell.setCellValue(v == null ? "" : v.toString());
                    if (needsCheck) cell.setCellStyle(checkStyle);
                }
            }

            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rowIdx - 1, 0), 0, COLUMNS.size() - 1));

            if (!warnings.isEmpty()) {
                Sheet ws = wb.createSheet("Warnings");
                ws.setColumnWidth(0, 120 * 256);
                for (int i = 0; i < warnings.size(); i++) {
                    ws.createRow(i).createCell(0).setCellValue(warnings.get(i));
                }
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Numeric cells sort and filter properly in Excel; fall back to text if it isn't a number. */
    private static Object asNumber(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return s;
        }
    }
}
