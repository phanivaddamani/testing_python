import json
import re
import os
from pdf2image import convert_from_path
import pytesseract
from pypdf import PdfReader

# Paths configuration
TESSERACT_PATH = r"C:\\Program Files\\Tesseract-OCR\\tesseract.exe"
PDF_PATH = r"C:\\Users\\hp\\Downloads\\PEDARIKATLA.pdf"
OUTPUT_JSON = r"C:\\Users\\hp\\Downloads\\PEDARIKATLA_1.json"
POPPLER_PATH = r"C:\\poppler\\poppler-26.09.0\\Library\\bin"

if os.path.exists(TESSERACT_PATH):
    pytesseract.pytesseract.tesseract_cmd = TESSERACT_PATH

def clean_text(value):
    if not value:
        return ""
    return re.sub(r"\s+", " ", value).strip(" :,-")

def parse_voter_box(cell_image):
    w, h = cell_image.size
    
    record = {
        "serial_number": "",
        "voter_id": "",
        "name": "",
        "address": "",
        "age": "",
        "gender": ""
    }

    # 1. Extract Serial Number from Top-Left box crop
    sno_crop = cell_image.crop((0, 0, int(w * 0.40), int(h * 0.22)))
    sno_text = pytesseract.image_to_string(sno_crop, config='--psm 7', lang="eng")
    sno_match = re.search(r"(\d+)", sno_text)
    if sno_match:
        record["serial_number"] = clean_text(sno_match.group(1))

    # 2. Extract Voter ID from Top-Right box crop
    id_crop = cell_image.crop((int(w * 0.50), 0, w, int(h * 0.30)))
    id_text = pytesseract.image_to_string(id_crop, config='--psm 7', lang="eng")
    # Matches standard voter IDs like ZIO1478015 or Z101516343
    id_match = re.search(r"\b([A-Z0-9]{3}\s*\d{7})\b", id_text, re.IGNORECASE)
    if id_match:
        record["voter_id"] = clean_text(id_match.group(1)).upper().replace(" ", "")

    # 3. Read full body text of the cell
    cell_text = pytesseract.image_to_string(cell_image, lang="eng")
    
    # Fallback Voter ID search in full cell text if top-right crop missed it
    if not record["voter_id"]:
        fallback_id = re.search(r"\b([A-Z0-9]{3}\s*\d{7})\b", cell_text, re.IGNORECASE)
        if fallback_id:
            record["voter_id"] = clean_text(fallback_id.group(1)).upper().replace(" ", "")

    # 4. Extract Name strictly following "Name :"
    name_match = re.search(r"Name\s*[:\-]\s*([A-Za-z\s\.]+?)(?=\n|Husband|Father|Mother|House|Age|Gender|$)", cell_text, re.IGNORECASE)
    if name_match:
        record["name"] = clean_text(name_match.group(1))

    # 5. Extract House Number / Address
    addr_match = re.search(r"(?:House\s*Number|House\s*No\.?|Address)\s*[:\-]\s*([A-Za-z0-9\-/]+)", cell_text, re.IGNORECASE)
    if addr_match:
        record["address"] = clean_text(addr_match.group(1))

    # 6. Extract Age
    age_match = re.search(r"Age\s*[:\-]?\s*(\d{1,3})", cell_text, re.IGNORECASE)
    if age_match:
        record["age"] = clean_text(age_match.group(1))

    # 7. Extract Gender
    gender_match = re.search(r"(?:Gender|Sex)\s*[:\-]?\s*(Male|Female|Other|Third\s*Gender)", cell_text, re.IGNORECASE)
    if gender_match:
        record["gender"] = clean_text(gender_match.group(1))

    # Validate that we got at least a name or voter ID before keeping
    if len(record["name"]) > 1 or record["voter_id"]:
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
                
                record = parse_voter_box(cell_image)
                if record:
                    records.append(record)

        del image
        del pages

    with open(OUTPUT_JSON, "w", encoding="utf-8") as file:
        json.dump(records, file, ensure_ascii=False, indent=2)

    print(f"Extraction complete. Total valid voter records saved: {len(records)}")
    print(f"JSON saved to: {OUTPUT_JSON}")

if __name__ == "__main__":
    main()