package com.example.clubmate.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

/** Signatures, group-content encryption and password-derived channel keys. */
class GroupChannelCryptoTest {

    // RFC 8032 section 7.1, test 1
    private val signSeed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val signPub = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")

    private fun aad(
        context: String = "group-activity",
        scope: String = "grp42",
        epoch: String = "-Nepoch1",
        messageId: String = "-Nmsg9",
        sender: String = "uidAlice",
        type: String = "Text",
        timestamp: Long = 1727200000000,
        signingKey: ByteArray = signPub
    ) = E2eeCrypto.contentAad(context, scope, epoch, messageId, sender, type, timestamp, signingKey)

    @Test
    fun ed25519MatchesRfc8032() {
        assertArrayEquals(signPub, E2eeCrypto.signingPublicKeyOf(signSeed))
        assertArrayEquals(
            hex(
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc6" +
                    "1e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
            ),
            E2eeCrypto.sign(signSeed, ByteArray(0))
        )
    }

    @Test
    fun groupMessageMatchesIndependentPythonReference() {
        val groupKey = ByteArray(32) { it.toByte() }
        val sealed = E2eeCrypto.encryptWithNonce(
            groupKey, ByteArray(12) { (100 + it).toByte() },
            E2eeCrypto.encodeStrings(listOf("Meeting moved to 6pm")), aad()
        )
        assertArrayEquals(
            hex(
                "6465666768696a6b6c6d6e6f481bde72348c33ea570c38c8b70a1c9826e27265ab5a831f62a2aeb5" +
                    "7d64b9eab62f13c1d7a08f8c"
            ),
            sealed
        )
        assertArrayEquals(
            hex(
                "1e414ce663fe30cfc7864b7de70cc86d93da3cdf61d78a7a24366628740fcac7724ceafa069c724ec1" +
                    "9a6aa7c07d0dde44557d81b07eca22234b460fcf850904"
            ),
            E2eeCrypto.sign(signSeed, E2eeCrypto.signedData(aad(), sealed))
        )
    }

    @Test
    fun signaturesVerifyOnlyForTheExactData() {
        val pair = E2eeCrypto.generateSigningKeyPair()
        val other = E2eeCrypto.generateSigningKeyPair()
        val data = E2eeCrypto.signedData(aad(), "ciphertext".toByteArray())
        val signature = E2eeCrypto.sign(pair.privateKey, data)

        assertTrue(E2eeCrypto.verify(pair.publicKey, signature, data))
        assertFalse(E2eeCrypto.verify(other.publicKey, signature, data))
        assertFalse(
            E2eeCrypto.verify(
                pair.publicKey, signature, E2eeCrypto.signedData(aad(sender = "uidBob"), "ciphertext".toByteArray())
            )
        )
        assertFalse(E2eeCrypto.verify(pair.publicKey, signature.copyOf(63), data))
        assertFalse(E2eeCrypto.verify(pair.publicKey.copyOf(31), signature, data))
        for (i in signature.indices) {
            val bad = signature.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse(E2eeCrypto.verify(pair.publicKey, bad, data))
        }
    }

    @Test
    fun groupContentIsBoundToItsMetadata() {
        val key = E2eeCrypto.generateSymmetricKey()
        val sealed = E2eeCrypto.encrypt(key, "hello club".toByteArray(), aad())
        val others = listOf(
            aad(context = "group-event"), aad(scope = "grp43"), aad(epoch = "-Nepoch2"),
            aad(messageId = "-Nmsg10"), aad(sender = "uidMallory"), aad(type = "Image"),
            aad(timestamp = 1), aad(signingKey = ByteArray(32))
        )
        assertEquals("hello club", String(E2eeCrypto.decrypt(key, sealed, aad())))
        for (other in others) {
            assertThrows(GeneralSecurityException::class.java) { E2eeCrypto.decrypt(key, sealed, other) }
        }
        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.decrypt(E2eeCrypto.generateSymmetricKey(), sealed, aad())
        }
    }

    @Test
    fun stringPayloadRoundTrips() {
        for (values in listOf(
            emptyList(), listOf(""), listOf("title", "body"), listOf("বাংলা 🔐", "", "x".repeat(5000))
        )) {
            assertEquals(values, E2eeCrypto.decodeStrings(E2eeCrypto.encodeStrings(values)))
        }
        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.decodeStrings(byteArrayOf(0, 0, 0, 9, 1, 2))
        }
        assertThrows(GeneralSecurityException::class.java) { E2eeCrypto.decodeStrings(byteArrayOf(0, 0)) }
    }

    @Test
    fun pbkdf2MatchesRfc7914() {
        assertArrayEquals(
            hex(
                "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                    "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"
            ),
            E2eeCrypto.pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        )
    }

    @Test
    fun channelKeysMatchIndependentPythonReference() {
        val keys = E2eeCrypto.deriveChannelKeys(
            "a1b2c3d4e5", "correct horse battery", ByteArray(16) { it.toByte() }, 1000
        )
        assertArrayEquals(hex("bb2543ae1ce77c79da5069dbb889cb106a36024fe14dabc6d18ce6eac4e821d6"), keys.encryptionKey)
        assertArrayEquals(hex("5b3a4a7f4aa2ae0cba4b9a39dcc307b186e58d15eb1729d44504748a3434a83f"), keys.verifier)
    }

    @Test
    fun channelKeysDependOnPasswordSaltAndChannel() {
        val salt = E2eeCrypto.generateSalt()
        val base = E2eeCrypto.deriveChannelKeys("room1", "Secret#123", salt, 1000)
        val variants = listOf(
            E2eeCrypto.deriveChannelKeys("room1", "Secret#124", salt, 1000),
            E2eeCrypto.deriveChannelKeys("room2", "Secret#123", salt, 1000),
            E2eeCrypto.deriveChannelKeys("room1", "Secret#123", E2eeCrypto.generateSalt(), 1000),
            E2eeCrypto.deriveChannelKeys("room1", "Secret#123", salt, 1001)
        )
        for (v in variants) {
            assertFalse(E2eeCrypto.constantTimeEquals(base.encryptionKey, v.encryptionKey))
            assertFalse(E2eeCrypto.constantTimeEquals(base.verifier, v.verifier))
        }
        // the verifier stored in Firebase is not the encryption key
        assertFalse(base.encryptionKey.contentEquals(base.verifier))
        assertTrue(
            E2eeCrypto.constantTimeEquals(
                base.verifier, E2eeCrypto.deriveChannelKeys("room1", "Secret#123", salt, 1000).verifier
            )
        )
    }

    @Test
    fun invalidChannelInputsAreRejected() {
        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.deriveChannelKeys("room", "", E2eeCrypto.generateSalt(), 1000)
        }
        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.deriveChannelKeys("room", "pw", ByteArray(4), 1000)
        }
        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.deriveChannelKeys("room", "pw", E2eeCrypto.generateSalt(), 0)
        }
    }

    @Test
    fun attachmentsRoundTripAndDetectTampering() {
        val image = ByteArray(200_000) { (it * 31).toByte() }
        val sealed = E2eeCrypto.encryptAttachment(image)
        assertEquals(32, sealed.key.size)
        assertFalse("ciphertext must not contain the plaintext", sealed.data.copyOfRange(12, 44)
            .contentEquals(image.copyOfRange(0, 32)))
        assertArrayEquals(image, E2eeCrypto.decryptAttachment(sealed.key, sealed.data))

        val tampered = sealed.data.copyOf().also { it[5000] = (it[5000].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { E2eeCrypto.decryptAttachment(sealed.key, tampered) }
        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.decryptAttachment(E2eeCrypto.generateSymmetricKey(), sealed.data)
        }
        // every file gets its own key
        assertFalse(sealed.key.contentEquals(E2eeCrypto.encryptAttachment(image).key))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
