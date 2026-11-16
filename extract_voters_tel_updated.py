import json
import re
import os
from pdf2image import convert_from_path
import pytesseract
from pypdf import PdfReader

# Paths configuration
TESSERACT_PATH = r"C:\\Program Files\\Tesseract-OCR\\tesseract.exe"
PDF_PATH = r"C:\\Users\\hp\\Downloads\\PEDARIKATLA.pdf"
OUTPUT_JSON = r"C:\\Users\\hp\\Downloads\\PEDARIKATLA_2.json"
POPPLER_PATH = r"C:\\poppler\\poppler-26.09.0\\Library\\bin"

if os.path.exists(TESSERACT_PATH):
    pytesseract.pytesseract.tesseract_cmd = TESSERACT_PATH

def clean_text(value):
    if not value:
        return ""
    # Clean up common Telugu punctuation leftovers alongside standard ones
    return re.sub(r"\s+", " ", value).strip(" :,-|ుిీాెేొోుూృౄెేైొోౌంః")

def parse_voter_box(cell_image):
    w, h = cell_image.size
    
    record = {
        "serial_number": "",
        "assembly_serial_number": "",
        "voter_id": "",
        "name": "",
        "relation_type": "",
        "relation_name": "",
        "address": "",
        "age": "",
        "gender": ""
    }

    # 1. Read full body text of the cell in Telugu
    cell_text = pytesseract.image_to_string(cell_image, lang="tel")
    
    # 2. Extract Serial Number (గ్రా.పం.ఓ.జా.వ.సం.)
    sno_match = re.search(r"గ్రా\.పం\.ఓ\.జా\.వ\.సం\.\s*(\d+)", cell_text)
    if sno_match:
        record["serial_number"] = clean_text(sno_match.group(1))
    else:
        # Fallback to top-left crop using eng if regex missed
        sno_crop = cell_image.crop((0, 0, int(w * 0.45), int(h * 0.25)))
        sno_text = pytesseract.image_to_string(sno_crop, config='--psm 7', lang="eng")
        sno_dig = re.search(r"(\d+)", sno_text)
        if sno_dig:
            record["serial_number"] = clean_text(sno_dig.group(1))

    # 3. Extract Assembly Serial Number (అ.ఓ.జా.వ.సం.)
    asno_match = re.search(r"అ\.ఓ\.జా\.వ\.సం\.\s*([0-9\-\s]+)", cell_text)
    if asno_match:
        record["assembly_serial_number"] = clean_text(asno_match.group(1))

    # 4. Extract Voter ID (Alphanumeric strings)
    id_match = re.search(r"\b([A-Z0-9]{3}\s*\d{7})\b", cell_text, re.IGNORECASE)
    if id_match:
        record["voter_id"] = clean_text(id_match.group(1)).upper().replace(" ", "")
    else:
        cell_text_eng = pytesseract.image_to_string(cell_image, lang="eng")
        fallback_id = re.search(r"\b([A-Z0-9]{3}\s*\d{7})\b", cell_text_eng, re.IGNORECASE)
        if fallback_id:
            record["voter_id"] = clean_text(fallback_id.group(1)).upper().replace(" ", "")

    # 5. Extract Name following "ఓటరు పేరు" or "పేరు"
    name_match = re.search(r"(?:ఓటరు\s*)?పేరు\s*[:\-]\s*([^\n\t:,\-]+?)(?=\n|భర్త|తండ్రి|తల్లి|వయస్సు|లింగం|ఇంటి|$)", cell_text)
    if name_match:
        record["name"] = clean_text(name_match.group(1))

    # 6. Extract Relation Type and Name (తండ్రి/భర్త/తల్లి పేరు)
    rel_match = re.search(r"(తండ్రి|భర్త|తల్లి)\s*పేరు\s*[:\-]\s*([^\n\t:,\-]+?)(?=\n|వయస్సు|లింగం|ఇంటి|ఓటరు|$)", cell_text)
    if rel_match:
        record["relation_type"] = clean_text(rel_match.group(1))
        record["relation_name"] = clean_text(rel_match.group(2))

    # 7. Extract House Number / Address (ఇంటి సంఖ్య / ఇంటి నంబరు)
    addr_match = re.search(r"ఇంటి\s*(?:సంఖ్య|నంబరు|నం|నెం)\.?\s*[:\-]?\s*([A-Za-z0-9\-\/\s]+)", cell_text)
    if addr_match:
        record["address"] = clean_text(addr_match.group(1))

    # 8. Extract Age (వయస్సు)
    age_match = re.search(r"వయస్సు\s*[:\-]?\s*(\d{1,3})", cell_text)
    if age_match:
        record["age"] = clean_text(age_match.group(1))

    # 9. Extract Gender (లింగము / లింగం)
    gender_match = re.search(r"లింగము?\s*[:\-]?\s*(పురుషుడు|స్త్రీ|ఇతర|పు|స్త్రీ)", cell_text)
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

        # Fine-tune header offset if Telugu headings occupy slightly different vertical spaces
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