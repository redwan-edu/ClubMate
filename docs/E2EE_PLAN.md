# ClubMate: End-to-End Encryption Plan

Status: **basic Diffie-Hellman mode implemented** for 1:1 chats (see [`E2EE_IMPLEMENTATION.md`](E2EE_IMPLEMENTATION.md)). The ratchet sections below are the next step. This document describes how to move ClubMate's
1:1 chats to end-to-end encryption (E2EE) using **X3DH + the Double Ratchet** (the design Signal and
WhatsApp use), and how to switch it on and migrate existing data safely.

Scope is set for an intermediate 3rd-year project: small enough to finish, and every claim in it can
be defended in a viva.

---

## 0. Short answer: is "Symmetric-Key Ratchet + DH Ratchet" more secure?

Yes. Combining the two ratchets gives you the **Double Ratchet**. Here is how it compares to
simpler options:

| Property | Plaintext in Firebase (today) | Static-key E2EE (e.g. RSA/one AES key per chat) | Double Ratchet |
|---|---|---|---|
| Server/Firebase can read messages | ✅ yes | ❌ no | ❌ no |
| **Forward secrecy**: stealing today's keys does not expose past messages | ❌ | ❌ every past message is exposed | ✅ each message key is derived once and then deleted (symmetric ratchet) |
| **Post-compromise security**: after a key theft, the attacker gets locked out again | ❌ | ❌ attacker reads everything forever | ✅ the next DH ratchet step mixes in fresh randomness the attacker doesn't have |
| Handles lost/out-of-order messages | n/a | ✅ | ✅ (skipped-message keys) |
| Complexity | none | low | medium (you have to store state per conversation) |

- **Symmetric-key ratchet (KDF chain):** every message gets its own key, and the chain only moves
  forward. That gives you forward secrecy.
- **DH ratchet:** every time the conversation changes direction, each side mixes a fresh X25519
  key exchange into the root key. That gives you post-compromise ("self-healing") security.
- **X3DH** is needed *before* either ratchet: it lets Alice start a session with Bob while he is
  offline, using keys Bob published in advance.

What the Double Ratchet does **not** protect against (state these limits clearly in the report):
- a device that is compromised *at the moment* the message is read,
- a malicious server swapping public keys, **unless users compare safety numbers** (covered in §4.8),
- metadata: who talks to whom, when, and how often.

### Build our own protocol layer, or use libsignal?

**Recommendation:** implement X3DH + Double Ratchet yourselves by following the published Signal
specifications exactly. Build it **only on audited primitives from Google Tink**. **Never** write
your own cryptographic primitives.

- It is roughly 500–700 lines of Kotlin, which is the right size for a 3rd-year project, and you
  can explain every line in a viva.
- libsignal is production-grade, but it is AGPL-3.0 licensed, recent versions require post-quantum
  (Kyber/PQXDH) pre-keys and a heavy store API, and it would be a black box you can't explain.
  Keep it as the "production alternative" in your discussion section.
- The risk of your own implementation is that nobody has audited it. You reduce that risk in three
  ways: stick exactly to the spec, run known-answer tests on the primitives, and cross-check against
  an independent reference implementation (§8).

---

## 1. Current state (audit of this repository)

| # | Finding | Where | Impact |
|---|---|---|---|
| 1 | Messages are stored **in plaintext** in Firebase RTDB (`chat/{chatId}/messages`, `msg/last`, `incognito`). | `viewmodel/ChatViewmodel.kt` | Firebase, and anyone who gets DB access, can read every message. |
| 2 | The RSA key pair is generated at registration but **never used** to encrypt anything. | `crypto_manager/E2E.kt`, `AuthViewmodel.register2Realtime` | This is not E2EE yet. |
| 3 | The private key is "encrypted" with the **raw password bytes used as the AES key** (no KDF). This throws for any password that isn't exactly 16, 24 or 32 bytes, so `encryptedPrivateKey` is usually `""`. | `CryptoManager.encryptAESKey` | Broken. Even when it works, it can be brute-forced offline. |
| 4 | `encryptedPrivateKey` is uploaded to `user/{uid}`, which user search reads. | `AuthViewmodel`, `Routes.UserModel` | Private key material ends up on the server. Must be removed. |
| 5 | The login "restore key" branch does nothing. | `AuthViewmodel.logIn` | Dead code. |
| 6 | `End2EndEncryptionViewmodel` is a placeholder that writes `"30":"50"` into `user/...`. | `viewmodel/E2EEviewmodel.kt` | Delete it. |
| 7 | The repo has **no Firebase security rules** (`database.rules.json`). | repo root | Without rules, any user could overwrite someone's public keys (man-in-the-middle) or read other chats. |
| 8 | `android:allowBackup="true"` and the backup rules are empty. | `AndroidManifest.xml`, `res/xml/*` | Restoring a copied ratchet state breaks sessions and duplicates key material. Must be excluded from backups. |
| 9 | Private-channel passwords are stored **in plaintext** (`setPassword`) and checked on the client. | `PrivateChannelViewmodel` | Anyone can read the channel password in the DB. |
| 10 | **The Cloudinary `api_secret` is hardcoded and committed.** | `ClubMate.kt` | Anyone with the APK or the repo controls the media account. Rotate it and switch to an unsigned upload preset. This is separate from E2EE, but examiners will notice it. |
| 11 | `androidx.security:security-crypto` (EncryptedSharedPreferences) is used, but upstream has deprecated it. | `E2E.kt`, `app/build.gradle.kts` | Replace with an Android Keystore-wrapped key (§5.2). |

---

## 2. Threat model and security goals

**Attacker capabilities we defend against**
1. Reads everything stored in Firebase RTDB and Cloudinary (a curious or breached server).
2. Actively modifies server data: injects, replays, reorders, drops or tampers with messages.
3. Steals a device's keys at time *T*.

**Goals (MVP)**
- **G1 Confidentiality:** only the two participants can read 1:1 message text and images.
- **G2 Integrity/authenticity:** tampered, forged or cross-chat-replayed messages are rejected.
- **G3 Forward secrecy:** a compromise at *T* does not reveal messages from before *T*.
- **G4 Post-compromise security:** after a compromise at *T*, security returns once both sides have
  exchanged new messages.
- **G5 MITM detection:** users can verify identity keys with safety numbers, and the app warns when
  a contact's key changes.

**Non-goals (declared as future work)**
- metadata privacy (chat IDs contain both UIDs; timestamps and "seen" flags stay visible)
- multi-device accounts
- encrypted cloud history backup
- group/club E2EE
- post-quantum security (PQXDH)
- header encryption
- message-length padding

---

## 3. Scope

| In scope (MVP) | Stretch (only if time allows) | Out of scope (future work) |
|---|---|---|
| 1:1 chat text messages | Private channels: password-derived encryption (§7, Phase 8) | Group/club noticeboards E2EE (Sender Keys / MLS) |
| 1:1 chat images (encrypted before Cloudinary upload) | QR-code safety number scan | Multi-device sync, history transfer |
| Incognito messages over the same session | Signed pre-key rotation automation | Sealed sender, metadata hiding |
| Safety numbers and key-change warnings | | PQXDH |
| Local encrypted message store (Room) | | |
| Firebase security rules | | |

**Groups stay server-protected (TLS + Firebase rules).** Club noticeboards have many members and
change membership often. Doing group E2EE properly is exactly the "sky high" part, so present it
honestly as future work.

---

## 4. Protocol design

### 4.1 Primitives and library

| Purpose | Primitive | Source |
|---|---|---|
| Key agreement | **X25519** (RFC 7748) | Tink `com.google.crypto.tink.subtle.X25519` |
| Signatures | **Ed25519** (RFC 8032) | Tink `subtle.Ed25519Sign` / `Ed25519Verify` |
| Key derivation | **HKDF-SHA256** (RFC 5869) | Tink `subtle.Hkdf` |
| Chain KDF | **HMAC-SHA256** | `javax.crypto.Mac` |
| AEAD | **AES-256-GCM** | `javax.crypto.Cipher` |
| Randomness | `SecureRandom` | platform |
| At-rest key wrapping | AES-256-GCM key inside **Android Keystore** (non-exportable) | platform |

Dependency: `com.google.crypto.tink:tink-android` (latest stable, Apache-2.0). Tink works on
`minSdk 24`. Raise `minSdk` to **26** anyway: the code already relies on `@RequiresApi(O)` /
`java.util.Base64`, and PBKDF2-HMAC-SHA256 (Phase 8) needs API 26.

**Identity keys.** Signal uses one key for both DH and signing (XEdDSA). We use **two keys**: an
Ed25519 signing key (`IKs`) and an X25519 DH key (`IKd`), where `IKs` signs `IKd`. Matrix/Olm uses
the same well-known split. It avoids implementing XEdDSA and is easy to explain.

All shared-secret outputs are checked to be non-zero. A zero output means a low-order point, and the
session is aborted.

### 4.2 Keys per user (per device)

| Key | Type | Lifetime | Published? |
|---|---|---|---|
| `IKs` identity signing key | Ed25519 | as long as the app install | public part |
| `IKd` identity DH key | X25519, signed by `IKs` | as long as the app install | public part + signature |
| `SPK` signed pre-key | X25519, signed by `IKs` | rotate every 7 days; keep the old private key for 30 days | public part + signature + id |
| `OPK` one-time pre-keys | X25519 | used once, then deleted | batch of 50; top up when fewer than 10 are left |
| `EK` ephemeral key | X25519 | one X3DH run | sent in the first message |
| ratchet keys `DHs` | X25519 | one DH-ratchet step | sent in each message header |

Keys are **generated on the device after login** (not at registration) and **never leave the
device**. The password has nothing to do with them.

### 4.3 Key directory in Firebase

```
keys/{uid}/
  identity:      { iks: b64(32), ikd: b64(32), ikdSig: b64(64), createdAt }
  signedPreKey:  { id: Int, pub: b64(32), sig: b64(64), createdAt }
  oneTimePreKeys/{id}: b64(32)
```
- Only the owner can write their own `identity` and `signedPreKey`.
- Anyone signed in can **claim** (delete) one OPK. The claim runs as an RTDB **transaction** so two
  senders can't use the same OPK.
- If no OPKs are left, X3DH runs without `DH4`. That is still secure, just with weaker
  replay protection for the first message.

### 4.4 Session setup: X3DH (Alice → Bob)

1. Alice fetches `keys/{bob}` and claims one OPK if one is available.
2. Alice verifies `ikdSig` and the `SPK` signature against `IKs_B`. If either check fails, abort.
3. **TOFU check:** if Alice has a pinned `IKs_B` and the fetched one differs, **block sending** and
   show the "security code changed" dialog (§4.8).
4. Alice generates `EK_A` and computes:
   ```
   DH1 = X25519(IKd_A, SPK_B)
   DH2 = X25519(EK_A,  IKd_B)
   DH3 = X25519(EK_A,  SPK_B)
   DH4 = X25519(EK_A,  OPK_B)          // only if an OPK was claimed
   SK  = HKDF(salt = 0x00*32, ikm = 0xFF*32 || DH1 || DH2 || DH3 [|| DH4],
              info = "ClubMate-X3DH-v1", len = 32)
   AD  = "ClubMate-v1" || lp(uid_A) || IKd_A || lp(uid_B) || IKd_B       // lp = length-prefixed
   ```
5. Alice initialises the Double Ratchet as the initiator, using `SPK_B` as Bob's first ratchet key.
6. Until Alice receives Bob's first reply, every message she sends carries a `prekey` block:
   `{ iks, ikd, ikdSig, ek, spkId, opkId? }`.
7. Bob receives it, verifies Alice's identity signature, checks and pins it (TOFU), loads the
   `SPK`/`OPK` private keys by id, recomputes `SK`, initialises as the responder and decrypts.
   **Bob deletes the OPK private key only after decryption succeeds.** If decryption fails, he
   throws away the new session.

### 4.5 Double Ratchet

This follows *The Double Ratchet Algorithm* (Perrin & Marlinspike, Signal) exactly.

State per session:
- `DHs` (our ratchet key pair)
- `DHr` (their ratchet public key)
- `RK`, `CKs`, `CKr`, `Ns`, `Nr`, `PN`
- `MKSKIPPED`

```
KDF_RK(rk, dh_out) = HKDF(salt = rk, ikm = dh_out, info = "ClubMate-DR-RK-v1", len = 64) → (rk', ck)
KDF_CK(ck)         = mk = HMAC(ck, 0x01);  ck' = HMAC(ck, 0x02)
ENCRYPT(mk, pt, ad):
    key || nonce = HKDF(salt = 0x00*32, ikm = mk, info = "ClubMate-DR-MK-v1", len = 44)
    ct = AES-256-GCM(key, nonce(12), pt, aad = ad)
```
- The GCM nonce is derived rather than random, which is safe **because every `mk` is used exactly
  once**.
- `ad = AD || encode(header) || messageId || senderUid || receiverUid`. Binding the RTDB
  `messageId` (created on the client with `push().key` before encrypting) and the UIDs means the
  server can't move a ciphertext into another slot or another chat.
- `MAX_SKIP = 1000` per chain. Skipped keys are kept in the session state, capped at 2000 in total,
  and expire after 30 days.
- **Transactional decrypt:** decrypt using a *copy* of the state, and commit the copy only if
  AES-GCM authentication succeeds. A tampered message then can't corrupt the session.
- **Replays** fail automatically, because the message key no longer exists after first use.
- **Simultaneous initiation** (both users send a first message at once): keep up to 5 archived
  sessions per contact. When decrypting, try the current session first and then the archived ones.
  Promote whichever one succeeds. This is the same approach libsignal uses.

### 4.6 Wire format (`chat/{chatId}/messages/{messageId}`)

```jsonc
{
  "v": 1,                                   // protocol version (0 = legacy plaintext)
  "senderId": "...", "receiverId": "...",   // needed for routing and rules (metadata)
  "timestamp": 1727200000000,               // metadata
  "header": { "dh": "b64(32)", "pn": 3, "n": 7 },
  "prekey": { "iks": "...", "ikd": "...", "ikdSig": "...", "ek": "...", "spkId": 4, "opkId": 17 },  // only until the first reply
  "ct": "b64(ciphertext||tag)",
  "seen": false                              // stays plaintext, used as a receipt
}
```
Encrypted plaintext (JSON inside `ct`):
```jsonc
{ "type": "text" | "image" | "delete" | "incognito",
  "text": "...",
  "image": { "url": "...", "key": "b64(32)", "nonce": "b64(12)", "sha256": "...", "mime": "image/jpeg", "w": 0, "h": 0 },
  "target": "messageId" }            // for "delete for everyone"
```
`messageType`, the text and the image URL all move **inside** the ciphertext. `chat/{id}/msg/last`
only holds `{ messageId, senderId, timestamp }`. The chat list takes its preview text from the
local database.

### 4.7 Images (Cloudinary)

1. Generate a random 32-byte key and a 12-byte nonce, then AES-256-GCM-encrypt the image bytes on
   the device.
2. Upload the ciphertext as a **raw** resource (`resource_type = raw`) through an **unsigned upload
   preset**. Remove `api_secret` from the app.
3. Put `url`, `key`, `nonce` and `sha256` in the ratchet-encrypted message.
4. The receiver downloads, checks `sha256`, decrypts and caches the decrypted image in app-private
   storage.

Cloudinary only ever sees random-looking bytes, so server-side transformations and thumbnails stop
working. That is an expected trade-off; write it up as one.

### 4.8 Identity verification

- **Safety number:**
  `SHA-256("ClubMate-SN-v1" || min(uidA‖IKs_A, uidB‖IKs_B) || max(...))`, truncated and shown as
  12 groups of 5 digits. Show it on the (currently "Under construction") `SecurityScreen` and on a
  contact's details screen, with a **"Mark as verified"** button.
- **TOFU pinning:** the first identity key you see for a contact is stored in `trusted_identity`.
  If it changes later, show an in-chat banner saying "security code changed", reset the verified
  flag, and require an explicit tap before sending again.
- A lock icon in the chat header shows the session state: 🔒 encrypted, ✅ verified, ⚠️ key changed.

---

## 5. App architecture changes

### 5.1 New code layout

```
crypto/            (pure Kotlin, no Android imports → JVM unit-testable)
  Primitives.kt        Tink/JCA wrappers: x25519, ed25519, hkdf, hmac, aesGcm
  X3dh.kt              initiator/responder key agreement
  DoubleRatchet.kt     encrypt/decrypt, skipped keys, DH ratchet step
  RatchetState.kt      @Serializable state + versioned (de)serialisation
  SafetyNumber.kt
  AttachmentCipher.kt
e2ee/              (Android glue)
  KeystoreWrapper.kt   Android Keystore AES key: wraps/unwraps secret blobs
  KeyManager.kt        ensureIdentity(), rotateSignedPreKey(), replenishOneTimePreKeys()
  KeyDirectory.kt      Firebase keys/{uid} read/publish/claim-OPK (transaction)
  SessionManager.kt    per-peer Mutex, load/create/archive sessions, TOFU checks
  E2eeMessenger.kt     encryptOutgoing(), decryptIncoming() → envelopes
  E2eeConfig.kt        mode flag: OFF | COMPAT | ENFORCE (§7)
data/local/        Room database
  entities: Identity, SignedPreKey, OneTimePreKey, Session, TrustedIdentity, Message
  daos + ClubMateDb
```
Delete: `crypto_manager/E2E.kt`, `viewmodel/E2EEviewmodel.kt`, the `publicKey` and
`encryptedPrivateKey` fields of `Routes.UserModel`, and the `security-crypto` dependency.

### 5.2 Local storage (the biggest architectural change)

The ratchet **deletes message keys after use**, so the app can't re-decrypt old messages from
Firebase every time a chat opens, which is what it does today. Decrypted messages therefore have to
live in a **local Room database**, and the UI reads from it.

- **Secrets** (identity private keys, pre-key private keys, session state blobs) are stored in Room,
  **wrapped with a non-exportable AES-256-GCM key held in the Android Keystore**.
- **Message plaintext** is stored in Room and protected by the Android app sandbox plus
  file-based encryption. SQLCipher is an optional stretch goal.
- **Atomicity:** "insert decrypted message + save new session state" runs in **one Room
  transaction**. If the app crashes before the commit, the message is simply processed again later
  from the old state, so the two sides never fall out of sync.
- **Backups:** exclude the Room DB and prefs in `backup_rules.xml` and
  `data_extraction_rules.xml`, or set `allowBackup=false`. Keystore keys aren't backed up anyway,
  so any restored blobs would be undecryptable.

### 5.3 Send flow

```
UI → ChatViewmodel.sendMessage
   → SessionManager.withPeerLock(peer) {
        session = load or X3DH-create
        envelope = DoubleRatchet.encrypt(...)
        Room tx { insert Message(status=SENDING, outboxEnvelope=envelope); save session }
     }
   → Firebase setValue(envelope)  → on success: status=SENT, clear outbox
                                   → on failure: retry the SAME envelope (never re-encrypt)
```

### 5.4 Receive flow

```
Firebase onChildAdded(chat/{id}/messages/{mid})
   → skip if senderId == me (sender already has the local plaintext)
   → skip if mid already in Room          // onChildAdded replays history on each attach
   → v == 0 → legacy path (COMPAT mode only), mark "not encrypted"
   → v == 1 → SessionManager.withPeerLock(peer) { decrypt on state copy; Room tx { insert msg; save state } }
   → failure → store a placeholder "⚠️ Message could not be decrypted"
```
All crypto work runs on `Dispatchers.Default`, not the main thread. That matters because Firebase
callbacks arrive on the main thread.

### 5.5 Feature adaptations

| Feature | Change |
|---|---|
| Chat list preview (`msg/last`) | Metadata only. The preview text comes from Room. |
| Delete for me | Local delete only. |
| Delete for everyone / `deleteMyMessages` | Send an encrypted `{type:"delete", target}` control message, then remove the ciphertext node. |
| Seen status | Unchanged (plaintext metadata). |
| Incognito messages | Sent over the same session with `type:"incognito"`. Kept only in memory, never in Room. The existing vanish-after-read deletion stays. |
| New device / reinstall | New identity, so contacts see "security code changed". Old history can't be recovered. This is a documented limitation. |
| Logout | Keys stay stored per UID on the device. "Log out and erase" wipes the DB and the Keystore alias. |

---

## 6. Firebase security rules (new `database.rules.json`)

**First export the current rules from the Firebase console**, so the groups and private-channel
paths keep working. Then add the rules below, adjusted for your paths:

```jsonc
{
  "rules": {
    "config": { ".read": "auth != null", ".write": false },          // E2EE mode flag, console-only
    "user": {
      ".read": "auth != null",
      "$uid": { ".write": "auth != null && auth.uid === $uid" }
    },
    "keys": {
      "$uid": {
        ".read": "auth != null",
        "identity":     { ".write": "auth != null && auth.uid === $uid" },
        "signedPreKey": { ".write": "auth != null && auth.uid === $uid" },
        "oneTimePreKeys": {
          "$keyId": { ".write": "auth != null && (auth.uid === $uid || !newData.exists())" }  // others may only delete (claim)
        }
      }
    },
    "chat": {
      "$chatId": {
        ".read":  "auth != null && $chatId.contains(auth.uid)",
        ".write": "auth != null && $chatId.contains(auth.uid)",
        "messages": {
          "$mid": {
            // ENFORCE mode: new messages must be encrypted envelopes, never plaintext
            ".validate": "!newData.hasChild('messageText') && newData.child('v').val() === 1 && newData.hasChildren(['senderId','header','ct'])"
          }
        }
      }
    }
  }
}
```
Test the rules with the **Firebase Emulator Suite** or the Rules Playground, including these cases:
- a stranger can't read or write a chat,
- a user can't overwrite someone else's identity key,
- a plaintext write is rejected once ENFORCE is on.

---

## 7. Enabling and migration plan

The mode is controlled by a flag at `config/e2eeMode` (RTDB, only writable from the console),
with a compile-time default in `E2eeConfig`:

| Mode | Send | Receive | Purpose |
|---|---|---|---|
| `OFF` | plaintext v0 (today) | v0 | before rollout |
| `COMPAT` | v1 if the peer has published keys, otherwise v0 | v0 and v1 (v0 shown as "Not encrypted") | transition; users update at different times |
| `ENFORCE` | v1 only (a peer without keys gets an "Ask X to update ClubMate" notice) | v1; legacy v0 is shown read-only or deleted | final state; rules reject plaintext |

### Phases

| Phase | Work | Done when | Est. |
|---|---|---|---|
| **0: Groundwork** | Delete the broken `CryptoManager` usage and the placeholder VM. Stop writing `publicKey`/`encryptedPrivateKey` and remove them from existing `user/*` nodes. Add Tink and Room, raise `minSdk` to 26. Add backup exclusions. Commit the baseline `database.rules.json`. **Rotate the Cloudinary secret** and use an unsigned preset. | App behaves exactly as before, no secrets in the repo | 3–4 d |
| **1: Crypto core** | Write `crypto/` as pure Kotlin, plus the full unit-test suite (§8). No UI work. | All tests green, including the reference-vector cross-check | 7–9 d |
| **2: Keys** | `KeystoreWrapper`, `KeyManager`, `KeyDirectory`. After login, call `ensureIdentity()` and publish the bundle. Replenish OPKs and rotate the SPK on app start. Add the `keys/` rules. Existing users get keys automatically the first time they log in on the new version. | Every active user has `keys/{uid}`, and the rules tests pass | 4–5 d |
| **3: Local store** | Room DB. The chat UI reads from Room, and the Firebase listener writes into Room. Transport is **still plaintext**. This separates "store locally" from "encrypt", so each change can be debugged on its own. | Chats work offline from Room, and there are no duplicates when a chat is reopened | 5–6 d |
| **4: Encrypted text (COMPAT)** | `SessionManager` + `E2eeMessenger`. Send and receive v1, dual-read v0, metadata-only `msg/last`, lock icon. Switch the flag to `COMPAT`. | Two test accounts on two emulators exchange v1 messages, and the Firebase console shows only ciphertext | 6–8 d |
| **5: Images** | `AttachmentCipher`, raw Cloudinary upload, decrypt and cache. | Images render, and the Cloudinary asset is unreadable | 3 d |
| **6: Verification UI** | Safety numbers on `SecurityScreen` and the contact details screen, TOFU pinning, key-change banner. | A deliberate key reset triggers the warning | 3–4 d |
| **7: Cutover (ENFORCE)** | Switch the flag to `ENFORCE` and deploy the rules that reject plaintext. **Legacy data:** because there are no real users, delete the old plaintext `chat/*/messages`, `msg/last` and `incognito` (recommended), or keep them locally as read-only "Not encrypted" history. | No plaintext message content left anywhere in Firebase | 1–2 d |
| **8 (stretch): Private channels** | Store `salt` plus a verifier instead of the plaintext password. Channel key = PBKDF2-HMAC-SHA256(password, salt, 310k iterations). Encrypt vanishing messages with AES-GCM. **This is not a ratchet**: say explicitly that it has no forward secrecy. | Channel password no longer in the DB | 3 d |
| **Buffer** | Bug fixing, report, demo | | 5 d |

**Total:** about 6–8 weeks for one developer, less with a team.

**Rollback:** until Phase 7, switching the flag back to `OFF` is safe. v1 messages already stored
locally stay readable. Once rules reject plaintext, rolling back also means redeploying the old
rules.

---

## 8. Testing strategy (how you stay confident)

1. **Primitive known-answer tests:** RFC 7748 (X25519), RFC 8032 (Ed25519) and RFC 5869 (HKDF)
   test vectors, plus NIST AES-GCM vectors. These prove the library is wired up correctly.
2. **Independent reference implementation:** write a ~150-line Python version of the same KDFs,
   X3DH and ratchet using the `cryptography` package. Generate fixed-seed test vectors with it
   (keys → `SK` → first 5 message keys → ciphertexts) and require the Kotlin output to match
   **byte for byte**. This is the strongest evidence you can show a marker.
3. **Protocol unit tests (JVM):**
   - X3DH with and without OPK gives the same `SK` on both sides. A bad SPK or identity signature is rejected.
   - 1,000 messages in random directions all decrypt.
   - Out-of-order delivery (shuffled windows) and lost messages. Exceeding `MAX_SKIP` is rejected.
   - Tampering with any bit of `ct`, the header, `messageId` or the UIDs is rejected, **and the session state is unchanged**.
   - A replayed message is rejected.
   - **Forward-secrecy test:** after messages 1..n, serialise the state and show that no key for messages 1..n can be derived from it (skipped-key store empty, old chain keys gone).
   - **Post-compromise test:** clone Bob's state as the "attacker", then run two more round trips. The attacker's copy can't decrypt the new messages.
   - Simultaneous initiation converges.
   - State serialisation round-trips.
4. **Rules tests** with the Firebase Emulator (§6).
5. **End-to-end:** two emulators, two accounts, kill the app mid-send and mid-receive, go offline
   and online, reinstall (key-change warning).
6. **Demo evidence:** screenshots of the Firebase console and the Cloudinary asset showing only
   ciphertext.

---

## 9. Viva / presentation guidance

**Claims you can defend:**
> "1:1 messages and images in ClubMate are end-to-end encrypted. Sessions are set up with X3DH and
> messages are protected with the Double Ratchet, following Signal's published specifications. The
> implementation is built on Google Tink's audited primitives (X25519, Ed25519, HKDF-SHA256,
> AES-256-GCM). Firebase and Cloudinary only store ciphertext. The design provides forward secrecy
> and post-compromise security, and users can detect key substitution by comparing safety numbers.
> The implementation is checked against RFC test vectors and an independent reference implementation."

**Don't claim:**
- "unbreakable" or "military-grade"
- "as secure as Signal" (the protocol is the same, but the implementation hasn't been audited)
- that metadata is private
- that groups are encrypted

**Good discussion points:**
- why forward secrecy and PCS need two ratchets
- why TOFU plus safety numbers are needed (the server is also the key directory)
- the single-device trade-off
- the libsignal vs own-implementation decision
- future work: Sender Keys/MLS for clubs, PQXDH, multi-device

---

## 10. Known limitations (write them up; don't hide them)

- Metadata is visible to the server: participants (the chat ID is `uidA+uidB`), timestamps, message
  count, seen flags, approximate message size.
- One device per account. A reinstall or new phone means a new identity and no old history.
- Group/club activity isn't E2EE.
- The implementation hasn't been audited, which is why it relies on spec adherence and testing.
- A compromised or rooted device at the time of reading defeats any E2EE.
- It isn't post-quantum. Signal has moved to PQXDH, and that is listed as future work.

---

## Appendix: file-by-file change list

| File | Change |
|---|---|
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | + Tink, + Room (+ KSP), − `security-crypto`, `minSdk` 26 |
| `AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | exclude DB and key files from backup |
| `ClubMate.kt` | remove the Cloudinary secret, unsigned preset; initialise Room/E2EE singletons |
| `crypto_manager/E2E.kt`, `viewmodel/E2EEviewmodel.kt` | **delete** |
| `db/data.kt` | remove `publicKey` and `encryptedPrivateKey` from `UserModel` |
| `viewmodel/AuthViewmodel.kt` | remove key generation from register; call `KeyManager.ensureIdentity()` after login |
| `viewmodel/ChatViewmodel.kt` | send/receive through `E2eeMessenger`; UI state from Room; metadata-only `msg/last`; delete control messages; incognito via the session |
| `util/chat/MessageModel.kt` | split into the local `Message` entity and the wire `Envelope` |
| `screens/ChatScreen.kt`, `util/chat/MessageDesign.kt` | lock icon, "not encrypted" and "could not decrypt" states, key-change banner |
| `navbarScreens/SecurityScreen.kt`, `screens/DetailsScreen.kt` | safety number and "mark verified" |
| `viewmodel/PrivateChannelViewmodel.kt` | (Phase 8) salted verifier + PBKDF2 channel key |
| new `crypto/`, `e2ee/`, `data/local/` packages | see §5.1 |
| new `database.rules.json` (+ `firebase.json` for the emulator) | see §6 |
| new tests `app/src/test/.../crypto/*`, `tools/reference/ratchet_ref.py` | see §8 |

### References
- Signal: *The X3DH Key Agreement Protocol* (Marlinspike & Perrin)
- Signal: *The Double Ratchet Algorithm* (Perrin & Marlinspike)
- RFC 7748 (X25519), RFC 8032 (Ed25519), RFC 5869 (HKDF), NIST SP 800-38D (GCM)
- Matrix Olm specification (separate Ed25519/Curve25519 identity keys)
