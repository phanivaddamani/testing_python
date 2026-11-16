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
    def generate_hmac_for_card(cls, card_number: str, secondary_code: str):
        # 1. Combine padding and data to form the 30-byte payload structure
        raw_combined = f"0000000000{card_number}{secondary_code}"
        
        # Convert each character to its ASCII hex representation
        hex_msg_bytes = "".join([f"{ord(c):02X}" for c in raw_combined])
        
        # Setup keys and bytes for HMAC calculation
        salt_key = cls.convert_base64_to_hex(cls.SALT_KEY_BASE64)
        salt_key_bytes = cls.hex_to_byte_array(salt_key)
        msg_bytes = cls.hex_to_byte_array(hex_msg_bytes)
        
        # Pass to HMAC generator
        hmac_obj = hmac.new(salt_key_bytes, msg_bytes, hashlib.sha256)
        hmac_hex = cls.bytes_to_hex(hmac_obj.digest())
        hmac_base64 = cls.convert_hex_to_base64(hmac_hex)
        
        # Return both the hex representation and the base64 string
        return hmac_hex, hmac_base64


# Array containing all card numbers and codes extracted from the image
cards_array = [
    {"card": "5282374871404992", "code": "2710"},
    {"card": "5282374871404992", "code": "2710"},
    {"card": "4701624226117393", "code": "2610"},
    {"card": "5282374871404992", "code": "2710"},
    {"card": "4077801292382703", "code": "2601"},
    {"card": "4031613180556420", "code": "2601"},
    {"card": "4077801292382703", "code": "3601"},
    {"card": "4072158249501504", "code": "3512"},
    {"card": "5413330089099130", "code": "4912"},
    {"card": "4761732000020023", "code": "3112"},
]

if __name__ == "__main__":
    # Print Markdown Table Header including the Hex column
    print("| Card Number | Expiry / Code | HMAC Hex Value | Generated Base64 HMAC |")
    print("| :--- | :---: | :--- | :--- |")
    
    for entry in cards_array:
        hmac_hex, hmac_base64 = CardHMACProcessor.generate_hmac_for_card(
            entry["card"], entry["code"]
        )
        print(f"| {entry['card']} | {entry['code']} | `{hmac_hex}` | `{hmac_base64}` |")