# ClubMate E2EE: basic Diffie-Hellman mode (implemented)

1:1 chat messages are end-to-end encrypted with a basic **Elliptic-Curve Diffie-Hellman (X25519)**
key exchange, **HKDF-SHA256** and **AES-256-GCM**. This covers normal messages, image links, the
chat-list preview and incognito messages. Firebase only ever stores ciphertext for them.

This is the "basic mode" step of [`E2EE_PLAN.md`](E2EE_PLAN.md). The ratchets (forward secrecy and
post-compromise security) are the next step and are **not** part of this mode.

## How it works

```
 Alice's phone                         Firebase                          Bob's phone
 ─────────────                         ────────                          ───────────
 a  = random X25519 private key                                          b = random X25519 private key
 A  = X25519 public key  ──────▶  user/{alice}/publicKey = A
                                  user/{bob}/publicKey   = B  ◀──────   B = X25519 public key

 shared = X25519(a, B)                                                   shared = X25519(b, A)
        (both sides get the same 32 bytes; it is never transmitted)
 key = HKDF-SHA256(shared, salt, info = "ClubMate-E2EE-v1…" + both uids + both public keys)

 ct = AES-256-GCM(key, random 12-byte nonce, text, AAD)  ──▶  chat/{chatId}/messages/{id}  ──▶  decrypt + verify
```

- **Key exchange (DH):** each device creates one X25519 key pair on first sign-in. Only the public
  key is uploaded, to `user/{uid}/publicKey`. The private key stays on the phone, encrypted with a
  non-exportable AES key in the **Android Keystore** (`e2ee/KeyVault.kt`), and is excluded from backups.
- **Key derivation:** the raw DH output goes through HKDF-SHA256. It is bound to both users' IDs and
  public keys, so each pair of users gets its own AES-256 key.
- **Encryption:** AES-256-GCM with a fresh random nonce per message. GCM also authenticates, so any
  change to the ciphertext is detected.
- **AAD (associated data):** each ciphertext is bound to its chat ID, message ID, sender, receiver,
  message type, timestamp, both public keys, and whether it is a normal or incognito message. The
  server can't move a message to another chat, change who sent it, change its time, or replay an
  incognito message as a normal one.
- **Key trust:** a contact's key is only accepted if Firebase's `user/{uid}/publicKey` directory has
  shown it for that contact. A key that appears only inside a message is rejected, which blocks
  forged "from Alice" messages. Previously seen keys are remembered, so old messages stay readable
  after a contact reinstalls.
- **No plaintext fallback:** if the other user has no E2EE key yet (they haven't signed in to the
  new version), sending fails with a toast. The message is not sent unencrypted.

## Message format in Firebase

```jsonc
// chat/{chatId}/messages/{messageId}  (the same object is also written to chat/{chatId}/msg/last)
{
  "messageId": "-Nx…", "senderId": "…", "receiverId": "…", "timestamp": 1727200000000,
  "messageType": "Text",            // or "Image"
  "messageText": "", "imageRef": "", // always empty for encrypted messages
  "v": 1,                            // 1 = encrypted, 0 = old plaintext message
  "ct": "base64(nonce ‖ ciphertext ‖ tag)",
  "senderKey": "base64(sender X25519 public key)",
  "receiverKey": "base64(receiver X25519 public key)",
  "seen": false, "status": "SENDING"
}
```
Incognito messages (`chat/{chatId}/incognito/{id}`) use the same fields.

## Code map

| File | Role |
|---|---|
| `crypto/E2eeCrypto.kt` | Pure crypto: X25519 DH, HKDF, AES-GCM, AAD encoding. No Android code, so it is unit-tested on the JVM. |
| `e2ee/KeyVault.kt` | Keystore-wrapped private key storage and the list of known contact keys. |
| `e2ee/E2eeManager.kt` | Publishes our key, looks up contacts' keys, and seals/opens `Message` and `IncognitoMessage`. |
| `viewmodel/ChatViewmodel.kt` | Sends only sealed messages, and decrypts messages, previews and incognito messages for display. Also detaches Firebase listeners when a chat closes (the old code leaked them, so one chat's messages could show up in another). |
| `viewmodel/AuthViewmodel.kt` | Creates and publishes the key at registration and sign-in. The old password-based RSA code is removed. |
| `screens/ChatScreen.kt` | Shows a toast when a message can't be sent securely. |
| `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Keep the key file out of backups. |

## What users see

| Situation | Shown as |
|---|---|
| Normal encrypted message | the text or image |
| Message from before E2EE | `⚠️ Not encrypted: …` |
| Tampered or forged message, or one sent to a key this phone no longer has | `🔒 This message can't be decrypted on this device` / `🔒 Message blocked: the sender's key isn't recognised` |
| Sending to someone without an E2EE key | Toast: *"Can't send securely yet: the other user needs to sign in to the latest ClubMate first"* |

## Testing

- `app/src/test/java/com/example/clubmate/crypto/E2eeCryptoTest.kt` has 15 JUnit tests. They cover
  RFC 7748 X25519 test vectors, both sides deriving the same key, byte-for-byte agreement with an
  independent Python implementation (`tools/e2ee_reference/e2ee_reference.py`, which uses the
  `cryptography` library instead of Tink), tamper detection on every byte, metadata binding, an
  eavesdropper failing to decrypt, and rejection of low-order and malformed keys.

  Run them with `./gradlew :app:testDebugUnitTest --tests "*E2eeCryptoTest*"`.
- Manual check on two emulators or phones:
  1. Install the new build on both and sign in as two accounts. Each account's
     `user/{uid}/publicKey` becomes a 44-character Base64 string, and `encryptedPrivateKey`
     disappears.
  2. Send text, an image and an incognito message both ways. Both sides read everything.
  3. In the Firebase console, `messageText` and `imageRef` are empty, `v` is `1` and `ct` is
     unreadable. This makes a good screenshot for the presentation.
  4. Edit one character of a `ct` in the console. The receiver shows the "can't be decrypted"
     placeholder instead of garbage.

## Firebase rule needed to protect the key directory

Encryption keeps message content away from the server, but the key directory is only trustworthy if
**no one except the owner can change a user's public key**. Otherwise someone could swap in their
own key (a man-in-the-middle attack). Make sure the Realtime Database rules include at least:

```jsonc
"user": {
  ".read": "auth != null",
  "$uid": {
    ".write": "auth != null && auth.uid === $uid"
  }
}
```

Merge this into your existing rules in the Firebase console and test with the Rules Playground.

## Limitations of basic mode (say these in the viva)

- **No forward secrecy:** each pair of users has one long-term key. Someone who steals a phone's
  private key can decrypt that user's past messages stored in Firebase. The Double Ratchet in
  `E2EE_PLAN.md` fixes this.
- **Trust on the directory:** there is no safety-number screen yet, so a malicious server operator
  could publish a fake key. This is planned in `E2EE_PLAN.md` §4.8.
- **One device per account:** signing in on a second phone publishes a new key, and the first phone
  can no longer read new messages.
- **Image files:** the image *link* is encrypted, but the file on Cloudinary is not.
- **Metadata:** who talks to whom, timestamps and seen status are visible to the server.
- **Groups and private channels** are not end-to-end encrypted.
