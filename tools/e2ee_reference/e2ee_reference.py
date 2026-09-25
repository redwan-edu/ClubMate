"""Independent reference implementation of ClubMate's E2EE message format.

Written against the Python `cryptography` package (not Tink), so the Kotlin code in
`app/src/main/java/com/example/clubmate/crypto/E2eeCrypto.kt` can be cross-checked against it.
It prints the known-answer values used in `E2eeCryptoTest.kt`.

Usage:  pip install cryptography && python3 tools/e2ee_reference/e2ee_reference.py
"""

import hashlib
import struct

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

HKDF_SALT = hashlib.sha256(b"ClubMate-E2EE-v1-salt").digest()
HKDF_INFO_LABEL = b"ClubMate-E2EE-v1-conversation-key"
AAD_LABEL = b"ClubMate-E2EE-v1-message"


def encode_fields(*fields: bytes) -> bytes:
    return b"".join(struct.pack(">I", len(f)) + f for f in fields)


def public_key_of(private_key: bytes) -> bytes:
    return (
        X25519PrivateKey.from_private_bytes(private_key)
        .public_key()
        .public_bytes(Encoding.Raw, PublicFormat.Raw)
    )


def conversation_key(my_uid: str, my_private: bytes, their_uid: str, their_public: bytes) -> bytes:
    shared = X25519PrivateKey.from_private_bytes(my_private).exchange(
        X25519PublicKey.from_public_bytes(their_public)
    )
    pairs = sorted([(my_uid, public_key_of(my_private)), (their_uid, their_public)], key=lambda p: p[0])
    info = encode_fields(
        HKDF_INFO_LABEL,
        pairs[0][0].encode(), pairs[0][1],
        pairs[1][0].encode(), pairs[1][1],
    )
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=HKDF_SALT, info=info).derive(shared)


def message_aad(context, chat_id, message_id, sender_id, receiver_id, message_type, timestamp,
                sender_pub, receiver_pub) -> bytes:
    return encode_fields(
        AAD_LABEL, context.encode(), chat_id.encode(), message_id.encode(), sender_id.encode(),
        receiver_id.encode(), message_type.encode(), str(timestamp).encode(), sender_pub, receiver_pub,
    )


if __name__ == "__main__":
    # RFC 7748 section 6.1 test keys
    alice_priv = bytes.fromhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    bob_priv = bytes.fromhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    alice_pub, bob_pub = public_key_of(alice_priv), public_key_of(bob_priv)
    alice_uid, bob_uid = "uidAlice", "uidBob"

    key = conversation_key(alice_uid, alice_priv, bob_uid, bob_pub)
    assert key == conversation_key(bob_uid, bob_priv, alice_uid, alice_pub)

    aad = message_aad("chat", "uidAlice+uidBob", "-Nmsg001", alice_uid, bob_uid, "Text",
                      1727200000000, alice_pub, bob_pub)
    nonce = bytes(range(12))
    sealed = nonce + AESGCM(key).encrypt(nonce, "Hello Bob 👋".encode(), aad)

    print("alice_pub      =", alice_pub.hex())
    print("bob_pub        =", bob_pub.hex())
    print("conversation   =", key.hex())
    print("aad_sha256     =", hashlib.sha256(aad).hexdigest())
    print("sealed         =", sealed.hex())
