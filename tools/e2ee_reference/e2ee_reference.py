"""Independent reference implementation of ClubMate's E2EE message format.

Written against the Python `cryptography` package (not Tink), so the Kotlin code in
`app/src/main/java/com/example/clubmate/crypto/E2eeCrypto.kt` can be cross-checked against it.
It prints the known-answer values used in `E2eeCryptoTest.kt`.

Usage:  pip install cryptography && python3 tools/e2ee_reference/e2ee_reference.py
"""

import hashlib
import struct

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

HKDF_SALT = hashlib.sha256(b"ClubMate-E2EE-v1-salt").digest()
HKDF_INFO_LABEL = b"ClubMate-E2EE-v1-conversation-key"
AAD_LABEL = b"ClubMate-E2EE-v1-message"
CONTENT_AAD_LABEL = b"ClubMate-E2EE-v1-content"
SIGNATURE_LABEL = b"ClubMate-E2EE-v1-signature"
CHANNEL_KEY_LABEL = b"ClubMate-E2EE-v1-channel-key"
CHANNEL_VERIFIER_LABEL = b"ClubMate-E2EE-v1-channel-verifier"


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


def content_aad(context, scope_id, epoch_id, message_id, sender_id, message_type, timestamp,
                signing_pub) -> bytes:
    return encode_fields(
        CONTENT_AAD_LABEL, context.encode(), scope_id.encode(), epoch_id.encode(),
        message_id.encode(), sender_id.encode(), message_type.encode(), str(timestamp).encode(),
        signing_pub,
    )


def signed_data(aad: bytes, ciphertext: bytes) -> bytes:
    return encode_fields(SIGNATURE_LABEL, aad, ciphertext)


def encode_strings(values) -> bytes:
    return encode_fields(*[v.encode() for v in values])


def hkdf(ikm: bytes, info: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=HKDF_SALT, info=info).derive(ikm)


def channel_keys(channel_id: str, password: str, salt: bytes, iterations: int):
    master = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations, 32)
    key = hkdf(master, encode_fields(CHANNEL_KEY_LABEL, channel_id.encode()))
    verifier = hkdf(master, encode_fields(CHANNEL_VERIFIER_LABEL, channel_id.encode()))
    return key, verifier


def print_group_and_channel_vectors():
    # RFC 8032 section 7.1 test 1 key
    sign_seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    signer = Ed25519PrivateKey.from_private_bytes(sign_seed)
    sign_pub = signer.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)

    group_key = bytes(range(32))
    aad = content_aad("group-activity", "grp42", "-Nepoch1", "-Nmsg9", "uidAlice", "Text",
                      1727200000000, sign_pub)
    nonce = bytes(range(100, 112))
    sealed = nonce + AESGCM(group_key).encrypt(nonce, encode_strings(["Meeting moved to 6pm"]), aad)
    signature = signer.sign(signed_data(aad, sealed))

    print("sign_pub       =", sign_pub.hex())
    print("rfc8032_sig    =", signer.sign(b"").hex())
    print("content_aad_sha=", hashlib.sha256(aad).hexdigest())
    print("group_sealed   =", sealed.hex())
    print("group_sig      =", signature.hex())

    salt = bytes(range(16))
    key, verifier = channel_keys("a1b2c3d4e5", "correct horse battery", salt, 1000)
    print("channel_key    =", key.hex())
    print("channel_verif  =", verifier.hex())
    print("pbkdf2_rfc7914 =", hashlib.pbkdf2_hmac("sha256", b"passwd", b"salt", 1, 64).hex())


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

    print_group_and_channel_vectors()
