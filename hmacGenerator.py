import hmac
import hashlib
import base64
import re

class HMACGenerator:
    HMAC_SHA256 = "HmacSHA256"
    SALT_KEY_OLD = "47A0C8C22AFDEDB246779C1E2BE191EB"
    SALT_KEY_BASE64 = "QVhJT01fRUlMX1NBTFQxNg=="

    @staticmethod
    def hex_to_byte_array(s: str) -> bytes:
        if s is None:
            return b""
        
        # Remove all whitespace and non-hex characters
        clean_hex = re.sub(r'[^0-9a-fA-F]', '', s)
        
        # If the hex string length is odd, prepend '0' to make it even
        if len(clean_hex) % 2 != 0:
            clean_hex = "0" + clean_hex
            
        return bytes.fromhex(clean_hex)

    @staticmethod
    def bytes_to_hex(byte_arr: bytes) -> str:
        return byte_arr.hex().upper()

    @staticmethod
    def generate_hmac_sha256(hex_key: str, hex_message: str) -> str:
        if hex_key is None or hex_message is None:
            raise ValueError("Key and message must not be null.")

        key_bytes = HMACGenerator.hex_to_byte_array(hex_key)
        message_bytes = HMACGenerator.hex_to_byte_array(hex_message)

        return HMACGenerator.generate_hmac_sha256_from_bytes(key_bytes, message_bytes)

    @staticmethod
    def generate_hmac_sha256_from_bytes(key_bytes: bytes, message_bytes: bytes) -> str:
        hmac_obj = hmac.new(key_bytes, message_bytes, hashlib.sha256)
        return HMACGenerator.bytes_to_hex(hmac_obj.digest())

    @staticmethod
    def compute_hmac(key: bytes, message: bytes) -> str:
        hmac_obj = hmac.new(key, message, hashlib.sha256)
        return HMACGenerator.bytes_to_hex(hmac_obj.digest())

    @staticmethod
    def convert_base64_to_hex(base64_str: str) -> str:
        # Decode the Base64 string into a byte array
        decoded_bytes = base64.b64decode(base64_str)
        # Format as lowercase hex string to match Java's %02x formatting
        return decoded_bytes.hex().lower()

    @staticmethod
    def convert_hex_to_base64(hex_str: str) -> str:
        byte_data = HMACGenerator.hex_to_byte_array(hex_str)
        return base64.b64encode(byte_data).decode('utf-8')


if __name__ == "__main__":
    salt_key = HMACGenerator.convert_base64_to_hex(HMACGenerator.SALT_KEY_BASE64)
    
    # Test Example 1
    msg1 = "30 30 30 30 30 30 30 30 30 30 34 37 36 31 37 33 39 30 30 31 30 31 30 31 33 35 32 34 31 32"
    hmac1 = HMACGenerator.generate_hmac_sha256(salt_key, msg1)
    print("Output 1:", hmac1)
    print("Output 1 Base64:", HMACGenerator.convert_hex_to_base64(hmac1))

    # Test Example 2 using bytes
    msg2 = "30 30 30 30 30 30 30 30 30 30 30 30 33 36 30 37 30 35 30 30 31 30 30 37 31 35 34 39 31 32"
    salt_key_bytes = HMACGenerator.hex_to_byte_array(salt_key)
    msg_bytes = HMACGenerator.hex_to_byte_array(msg2)
    hmac2 = HMACGenerator.generate_hmac_sha256_from_bytes(salt_key_bytes, msg_bytes)
    print("Output 2:", hmac2)
    print("Output 2 Base64:", HMACGenerator.convert_hex_to_base64(hmac2))

    # Test Example 3 (converting signed Java bytes to unsigned Python bytes via & 0xFF equivalent)
    signed_input_bytes = [-48, 126, -2, 74, -76, 72, -17, -38, -23, 55, -27, 109, 36, -51, 77, -108, -31,
                          48, 92, -110, 70, 104, -62, 96, -14, 40, -1, 11, 23, -45, -18, -34]
    input_bytes = bytes([b & 0xFF for b in signed_input_bytes])

    hmac3 = HMACGenerator.generate_hmac_sha256_from_bytes(salt_key_bytes, input_bytes)
    print("Output 3:", hmac3)
    print("Output 3 Base64:", HMACGenerator.convert_hex_to_base64(hmac3))

    text_key_bytes = salt_key.encode('utf-8')
    result_text_key = HMACGenerator.compute_hmac(text_key_bytes, input_bytes)
    print("Output (Salt Key as UTF-8 Text):", result_text_key)

    result_hex_key = HMACGenerator.compute_hmac(salt_key_bytes, input_bytes)
    print("Output (Salt Key as Hex Decoded):", result_hex_key)