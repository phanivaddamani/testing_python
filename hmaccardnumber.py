import hmac
import hashlib
import base64
import re

class CardHMACProcessor:
    SALT_KEY_BASE64 = "QVhJT01fRUlMX1NBTFQxNg=="

    @staticmethod
    def hex_to_byte_array(s: str) -> bytes:
        clean_hex = re.sub(r'[^0-9a-fA-F]', '', s)
        if len(clean_hex) % 2 != 0:
            clean_hex = "0" + clean_hex
        return bytes.fromhex(clean_hex)

    @staticmethod
    def bytes_to_hex(byte_arr: bytes) -> str:
        return byte_arr.hex().upper()

    @staticmethod
    def convert_base64_to_hex(base64_str: str) -> str:
        return base64.b64decode(base64_str).hex().lower()

    @staticmethod
    def convert_hex_to_base64(hex_str: str) -> str:
        return base64.b64encode(CardHMACProcessor.hex_to_byte_array(hex_str)).decode('utf-8')

    @classmethod
    def process_card(cls, card_number: str, secondary_code: str):
        # 1. Combine card number and secondary code (adjust concatenation order if needed based on your business logic)
        # In your Java example: 10 bytes of '0' padding + concatenated characters converted to hex bytes
        raw_combined = f"0000000000{card_number}{secondary_code}"
        
        # Ensure it fits the 30-byte (60 hex characters) requirement by padding or trimming if necessary
        # Or encode each character to its hex ASCII value:
        hex_msg_bytes = "".join([f"{ord(c):02X}" for c in raw_combined])
        
        # Format with spaces to match your example: "30 30 30..."
        spaced_hex_msg = " ".join([hex_msg_bytes[i:i+2] for i in range(0, len(hex_msg_bytes), 2)])
        
        # Compute HMAC
        salt_key = cls.convert_base64_to_hex(cls.SALT_KEY_BASE64)
        salt_key_bytes = cls.hex_to_byte_array(salt_key)
        msg_bytes = cls.hex_to_byte_array(hex_msg_bytes)
        
        hmac_obj = hmac.new(salt_key_bytes, msg_bytes, hashlib.sha256)
        hmac_hex = cls.bytes_to_hex(hmac_obj.digest())
        hmac_base64 = cls.convert_hex_to_base64(hmac_hex)
        
        return spaced_hex_msg, hmac_hex, hmac_base64

# Data extracted from your image: (Card Number, Secondary Code)
cards_data = [
    ("5282374871404992", "2710"),
    ("5282374871404992", "2710"),
    ("4701624226117393", "2610"),
    ("5282374871404992", "2710"),
    ("4077801292382703", "2601"),
    ("4031613180556420", "2601"),
    ("4077801292382703", "3601")
]

if __name__ == "__main__":
    for idx, (card, code) in enumerate(cards_data, start=1):
        spaced_msg, hmac_res, hmac_b64 = CardHMACProcessor.process_card(card, code)
        print(f"--- Row {idx} ---")
        print(f"Card: {card} | Code: {code}")
        print(f"30-Byte Hex Message: {spaced_msg}")
        print(f"HMAC (Hex):    {hmac_res}")
        print(f"HMAC (Base64): {hmac_b64}\n")