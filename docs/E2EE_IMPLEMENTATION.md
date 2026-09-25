# ClubMate E2EE: what is implemented

End-to-end encryption covers every place people write content in ClubMate:

| Feature | Message text | Images | Previews | How the key is agreed |
|---|---|---|---|---|
| 1:1 chats (+ incognito) | ✅ | ✅ | ✅ chat list | X25519 Diffie-Hellman between the two users |
| Groups (chat + notice board) | ✅ | ✅ | ✅ group list | random group key, handed to each member via their Diffie-Hellman key, rotated on membership change |
| Private channels | ✅ | ✅ | n/a | derived from the channel password (PBKDF2 + HKDF) |

Firebase and Cloudinary only ever store ciphertext for these. Group and channel messages are also
**signed** (Ed25519), so a member can't post in someone else's name.

This is the "basic Diffie-Hellman mode" step of [`E2EE_PLAN.md`](E2EE_PLAN.md). The Double Ratchet
(forward secrecy) is the next step.

---

## 1. Keys every user has

On first sign-in each phone creates two key pairs. The private halves never leave the device: they
are encrypted with a non-exportable AES key in the **Android Keystore** (`e2ee/KeyVault.kt`) and
excluded from backups. The public halves are published in the user's Firebase node:

| Key | Algorithm | Firebase field | Used for |
|---|---|---|---|
| Key-exchange key | X25519 | `user/{uid}/publicKey` | Diffie-Hellman for 1:1 messages and for handing out group keys |
| Signing key | Ed25519 | `user/{uid}/signingKey` | signing group and channel messages |

**Trust rule:** a contact's key is accepted only if the directory (`user/{uid}`) shows or showed it
for that contact. A key that appears only inside a message is rejected.

## 2. 1:1 chats

```
shared = X25519(my private key, their public key)        (same value on both phones, never sent)
key    = HKDF-SHA256(shared, info = both uids + both public keys)
ct     = AES-256-GCM(key, random nonce, content, AAD)
```
The AAD (associated data) binds each ciphertext to its chat, message ID, sender, receiver, type,
timestamp and both public keys, so the server can't move, re-attribute, re-date or alter it.

## 3. Groups (`e2ee/GroupE2ee.kt`)

```
groups/{grpId}/e2ee/current           = epochId
groups/{grpId}/e2ee/epochs/{epochId}  = { createdBy, createdAt, creatorKey,
                                          keys/{memberUid} = { ct, receiverKey } }
```
- A **group key** is 32 random bytes. It is never stored in the clear: for each member it is
  encrypted with the 1:1 Diffie-Hellman key between the epoch's creator and that member.
- **Automatic rotation:** before sending, the app checks that the current epoch was made for exactly
  the current members and their current keys. If anyone joined, left, was removed or reinstalled,
  it creates a new epoch first. As a result:
  - removed members can't read anything sent after they left;
  - new members can't read what was sent before they joined (they see "🔒 Sent before you
    joined…").
- **Messages and notices** are AES-256-GCM encrypted with the group key and **signed** by the sender.
  A notice's category and audience (`visibility`) are bound to the ciphertext, so the server can't
  change them.
- Only members can send. Members who haven't signed in to an E2EE version yet have no key. They
  aren't included in new epochs until they do, and are added automatically at the next message.
- **Leaving a group** now also removes you from `participants`. Before, users who left stayed on the
  member list and would have kept receiving keys.

## 4. Private channels (`e2ee/ChannelE2ee.kt`)

```
private_channels/{id} = { createdAt, v: 1, salt, iterations, verifier }   (no password!)

master   = PBKDF2-HMAC-SHA256(password, 16-byte random salt, 200 000 iterations)
key      = HKDF(master, "channel-key" + channelId)
verifier = HKDF(master, "channel-verifier" + channelId)
```
- The password is the shared secret. The app checks a typed password by recomputing the verifier
  and comparing in constant time. The password itself is never stored.
- PBKDF2 is deliberately slow (about 1 s on a phone, once per session), so guessing passwords from
  the stored salt and verifier is expensive. New channels need a password of at least 8 characters.
- Messages are AES-256-GCM encrypted with the channel key and signed by the sender.
- **Legacy channels** stored the password in plain text (`setPassword`). The first person to join
  with the right password upgrades the channel and deletes the stored password.

## 5. Images (`e2ee/SecureImages.kt`)

1. The picture is re-encoded on the phone. It is downscaled to 1600 px and its rotation is applied.
   All metadata, including GPS location, is dropped.
2. It is encrypted with a **fresh random AES-256-GCM key** and uploaded to Cloudinary as an opaque
   "raw" file.
3. The message carries `e2ee-image:v1:<key>:<url>` **inside its encrypted content**, so only the
   chat, group or channel members learn the key.
4. When displayed, the file is downloaded, checked, decrypted and cached in memory
   (`util/SecureImage.kt`). Old unencrypted images still load as before.

## 6. What the server can and can't see

| Server **can't** see | Server **can** see (metadata) |
|---|---|
| message text, notice title and body, image contents and links | who is in which chat, group or channel; timestamps; message sizes; seen flags |
| channel passwords | group name, description, photo and member list (needed for search and joining) |
| private keys, group keys | public keys |

## 7. Firebase rules (important)

Encryption stops the server from **reading** content. The Realtime Database rules decide who can
**write**, and some protections depend on them:
- Nobody may change another user's public keys (otherwise they could swap in their own key).
- Only group members may write a group's messages and keys.

Merge this with your existing rules in the Firebase console and test with the Rules Playground:

```jsonc
{
  "rules": {
    "user": {
      ".read": "auth != null",
      "$uid": {
        ".write": "auth != null && auth.uid === $uid",        // only you can change your keys/profile
        "groups_connected": { ".write": "auth != null" }      // admins update this when adding/removing you
      }
    },
    "chat": {
      "$chatId": {
        ".read": "auth != null && $chatId.contains(auth.uid)",
        ".write": "auth != null && $chatId.contains(auth.uid)"
      }
    },
    "groups": {
      ".read": "auth != null",   // group search/join reads grpInfo; tighten if you add an index node
      "$grpId": {
        "grpInfo":      { ".write": "auth != null" },
        "request":      { "$uid": { ".write": "auth != null && auth.uid === $uid || root.child('groups/'+$grpId+'/participants/'+auth.uid).exists()" } },
        "participants": { ".write": "auth != null" },
        "activities":   { ".write": "root.child('groups/'+$grpId+'/participants/'+auth.uid).exists()" },
        "events":       { ".write": "root.child('groups/'+$grpId+'/participants/'+auth.uid).exists()" },
        "e2ee":         { ".write": "root.child('groups/'+$grpId+'/participants/'+auth.uid).exists()" }
      }
    },
    "private_channels": {
      "$id": { ".read": "auth != null", ".write": "auth != null" }  // content is protected by the password
    }
  }
}
```
The `participants` rule above keeps today's behaviour, where admins add members from the app. For
stronger protection, restrict it to existing admins.

## 8. Code map

| File | Role |
|---|---|
| `crypto/E2eeCrypto.kt` | Pure crypto (no Android): X25519, Ed25519, HKDF, PBKDF2, AES-GCM, AAD encoding. |
| `e2ee/KeyVault.kt` | Keystore-wrapped private keys and remembered contact keys. |
| `e2ee/E2eeManager.kt` | Key publishing and lookup, 1:1 sealing, shared signed-sealing helpers. |
| `e2ee/GroupE2ee.kt` | Group keys (epochs, rotation), group messages and notices. |
| `e2ee/ChannelE2ee.kt` | Password-derived channel keys, legacy upgrade, channel messages. |
| `e2ee/SecureImages.kt`, `util/SecureImage.kt` | Encrypted image upload, download and display. |
| `viewmodel/*` | Send only sealed data; decrypt everything shown. Listeners no longer pile up or leak between chats or groups. |

## 9. Testing

**Unit tests** in `app/src/test/java/com/example/clubmate/crypto/` (25 tests). Run them with
`./gradlew :app:testDebugUnitTest`. They cover:
- the official test vectors: RFC 7748 (X25519), RFC 8032 (Ed25519) and RFC 7914 (PBKDF2);
- byte-for-byte agreement with an independent Python implementation
  (`tools/e2ee_reference/e2ee_reference.py`) for 1:1 messages, group messages, signatures and channel
  keys;
- tamper detection, metadata binding, signature forgery, wrong passwords and malformed input.

**Multi-device simulation (26 scenarios).** During development the real `E2eeManager`, `GroupE2ee`
and `ChannelE2ee` code was also run against an in-memory Firebase with several simulated phones. The
scenarios checked:
- members read group messages and the server sees no text;
- the group key is reused until membership changes, then rotated;
- removed members can't read new messages;
- new members can't read history;
- a member who reinstalls gets the next key;
- non-members can't send;
- members can't impersonate each other, even using the victim's public key;
- notice audience can't be widened;
- channel passwords are never stored, and wrong passwords get nothing;
- legacy channels are upgraded and their plain-text password removed.

**Manual check on two phones:**
1. Send text, images and notices in a chat, a group and a private channel.
2. In the Firebase console you should see only `ct`, `sig` and `epochId` values and empty
   `messageText`, `title` and `imageRef` fields.
3. Open an uploaded image URL in a browser: it is unreadable bytes.
4. Remove a member, send a message, and check that the removed member's phone can't read it.

## 10. Limitations (say these in the viva)

- **No forward secrecy:** long-term keys protect stored messages. The Double Ratchet in the plan
  fixes this.
- **No safety-number screen yet:** users trust the key directory. Firebase rules must stop users from
  editing other users' keys, and a malicious *server operator* could still substitute keys.
- **Group membership is server-controlled:** the app trusts the `participants` list when deciding
  who gets the group key, as early WhatsApp and Signal groups did. Rules must restrict who can
  change it.
- **Notice visibility is a display filter:** every member holds the group key, so an "Admins only"
  notice is hidden in the UI but readable by any member's device.
- **Channel security equals password strength:** anyone who knows or guesses the password can read
  the channel.
- **One device per account:** a new phone gets new keys and can't read older messages.
- Old messages from before encryption are shown with a "⚠️ Not encrypted" label. Delete them in
  the console for a clean demo.
