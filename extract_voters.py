import json
import re
import os
from pdf2image import convert_from_path
import pytesseract
from pypdf import PdfReader

# Paths configuration
TESSERACT_PATH = r"C:\\Program Files\\Tesseract-OCR\\tesseract.exe"
PDF_PATH = r"C:\\Users\\hp\\Downloads\\209_ENG.pdf"
OUTPUT_JSON = r"C:\\Users\\hp\\Downloads\\voters_209_1.json"
POPPLER_PATH = r"C:\\poppler\\poppler-26.09.0\\Library\\bin"

if os.path.exists(TESSERACT_PATH):
    pytesseract.pytesseract.tesseract_cmd = TESSERACT_PATH

def clean_text(value):
    if not value:
        return ""
    return re.sub(r"\s+", " ", value).strip(" :,-")

# Patterns
patterns = {
    # Highly flexible pattern to capture Indian voter IDs (e.g., ZIO1478015, Z102316842, 2101341817)
    "voter_id": re.compile(r"\b([A-Z0-9]{2,3}\s*\d{7,8})\b", re.IGNORECASE),
    "name": re.compile(r"(?:Name\s*of\s*Elector|Electors?\s*Name|Name)\s*[:\-]\s*(.+)", re.IGNORECASE),
    "address": re.compile(r"(?:House\s*Number|House\s*No\.?|Address)\s*[:\-]?\s*(.+)", re.IGNORECASE),
    "age": re.compile(r"(?:Age)\s*[:\-]?\s*(\d{1,3})", re.IGNORECASE),
    "gender": re.compile(r"(?:Gender|Sex)\s*[:\-]?\s*(Male|Female|Other|Third\s*Gender)", re.IGNORECASE),
}

def parse_voter_box(cell_image, fallback_serial):
    """Extracts general info from the main cell, and targets the top-right corner for Voter ID."""
    w, h = cell_image.size
    
    record = {
        "serial_number": str(fallback_serial),
        "voter_id": "",
        "name": "",
        "address": "",
        "age": "",
        "gender": ""
    }

    # PASS 1: Target specifically the top-right section of the box where Voter ID lives
    # (Top 25% height, right 45% width of the individual box)
    id_crop = cell_image.crop((int(w * 0.55), 0, w, int(h * 0.28)))
    id_text = pytesseract.image_to_string(id_crop, config='--psm 7', lang="eng")
    
    id_match = patterns["voter_id"].search(id_text)
    if id_match:
        record["voter_id"] = clean_text(id_match.group(1)).replace(" ", "").upper()

    # PASS 2: Read the entire cell text for Name, Address, Age, Gender
    cell_text = pytesseract.image_to_string(cell_image, lang="eng")
    
    # If voter ID wasn't found in top-right crop, try scanning the whole cell text as backup
    if not record["voter_id"]:
        backup_id_match = patterns["voter_id"].search(cell_text)
        if backup_id_match:
            record["voter_id"] = clean_text(backup_id_match.group(1)).replace(" ", "").upper()

    for field in ["name", "address", "age", "gender"]:
        match = patterns[field].search(cell_text)
        if match:
            val = next((g for g in match.groups() if g is not None), "")
            record[field] = clean_text(val)

    # Clean up trailing noise like "Photo Available" or "Photo" in address fields
    if record["address"]:
        record["address"] = re.sub(r"Photo.*", "", record["address"]).strip(" :-")

    # Validate record has enough data to keep
    if len(record["name"]) > 2 or len(record["voter_id"]) >= 8:
        return record
    return None

def main():
    if not os.path.exists(PDF_PATH) or not os.path.exists(POPPLER_PATH):
        print("Check your PDF_PATH or POPPLER_PATH settings.")
        return

    reader = PdfReader(PDF_PATH)
    total_pages = len(reader.pages)
    print(f"Total pages in PDF: {total_pages}")

    records = []
    TOTAL_ROWS = 10
    TOTAL_COLS = 3
    global_serial = 1

    for page_number in range(1, total_pages + 1):
        print(f"Processing page {page_number} of {total_pages}...")
        
        pages = convert_from_path(
            PDF_PATH, dpi=250, first_page=page_number, last_page=page_number, poppler_path=POPPLER_PATH
        )
        if not pages:
            continue
            
        image = pages[0]
        width, height = image.size

        header_offset = int(height * 0.09)
        grid_height = height - header_offset

        col_width = width / float(TOTAL_COLS)
        row_height = grid_height / float(TOTAL_ROWS)

        for r in range(TOTAL_ROWS):
            for c in range(TOTAL_COLS):
                left = int(c * col_width)
                upper = int(header_offset + (r * row_height))
                right = int((c + 1) * col_width)
                lower = int(header_offset + ((r + 1) * row_height))

                cell_image = image.crop((left, upper, right, lower))
                
                record = parse_voter_box(cell_image, global_serial)
                if record:
                    records.append(record)
                
                global_serial += 1

        del image
        del pages

    with open(OUTPUT_JSON, "w", encoding="utf-8") as file:
        json.dump(records, file, ensure_ascii=False, indent=2)

    print(f"Extraction complete. Total valid voter records saved: {len(records)}")
    print(f"JSON saved to: {OUTPUT_JSON}")

if __name__ == "__main__":
    main()