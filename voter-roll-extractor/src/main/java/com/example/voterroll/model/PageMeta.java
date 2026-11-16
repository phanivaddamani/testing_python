package com.example.voterroll.model;

/**
 * Header values printed at the top of each roll page, carried forward when a page omits them,
 * plus the last serial number seen (used to fill serials OCR could not read).
 */
public record PageMeta(String assemblyConstituency, String partNo, String section, int lastSerial) {

    public static final PageMeta EMPTY = new PageMeta("", "", "", 0);

    /** Fill blanks in this page's header from the previous page's header. */
    public PageMeta orElse(PageMeta previous) {
        return new PageMeta(
                assemblyConstituency.isBlank() ? previous.assemblyConstituency() : assemblyConstituency,
                partNo.isBlank() ? previous.partNo() : partNo,
                section.isBlank() ? previous.section() : section,
                lastSerial > 0 ? lastSerial : previous.lastSerial());
    }

    public PageMeta withLastSerial(int serial) {
        return new PageMeta(assemblyConstituency, partNo, section, serial);
    }
}
