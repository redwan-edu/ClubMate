package com.example.clubmate.crypto

import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import java.security.GeneralSecurityException
import org.junit.Test

class E2eeCryptoTest {

    // RFC 7748 section 6.1 key pairs
    private val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    private val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")

    private val alice = "uidAlice"
    private val bob = "uidBob"
    private val chatId = "uidAlice+uidBob"

    private fun aad(
        messageId: String = "-Nmsg001",
        sender: String = alice,
        receiver: String = bob,
        type: String = "Text",
        timestamp: Long = 1727200000000,
        context: String = "chat",
        chat: String = chatId,
        senderKey: ByteArray = alicePub,
        receiverKey: ByteArray = bobPub
    ) = E2eeCrypto.messageAad(
        context, chat, messageId, sender, receiver, type, timestamp, senderKey, receiverKey
    )

    private fun aliceKey() = E2eeCrypto.conversationKey(alice, alicePriv, bob, bobPub)
    private fun bobKey() = E2eeCrypto.conversationKey(bob, bobPriv, alice, alicePub)

    @Test
    fun x25519MatchesRfc7748() {
        assertArrayEquals(alicePub, E2eeCrypto.publicKeyOf(alicePriv))
        assertArrayEquals(bobPub, E2eeCrypto.publicKeyOf(bobPriv))
        assertArrayEquals(
            hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"),
            X25519.computeSharedSecret(alicePriv, bobPub)
        )
    }

    @Test
    fun bothSidesDeriveTheSameConversationKey() {
        assertArrayEquals(aliceKey(), bobKey())
    }

    @Test
    fun conversationKeyMatchesIndependentPythonReference() {
        // Produced by tools/e2ee_reference/e2ee_reference.py (Python `cryptography`, not Tink).
        assertArrayEquals(
            hex("c9dde7dffe5cc6287aa024caf01e154081d6f3aaad35d7a9333bfd1d6df69dca"),
            aliceKey()
        )
    }

    @Test
    fun ciphertextMatchesIndependentPythonReference() {
        val sealed = E2eeCrypto.encryptWithNonce(
            aliceKey(), ByteArray(12) { it.toByte() }, "Hello Bob 👋".toByteArray(), aad()
        )
        assertArrayEquals(
            hex("000102030405060708090a0b65f9e8903c3a714334e1d731a14e2da5570b542a232f57ee615a6b4b6269"),
            sealed
        )
    }

    @Test
    fun aliceEncryptsAndBobDecrypts() {
        val sealed = E2eeCrypto.encrypt(aliceKey(), "Hello Bob".toByteArray(), aad())
        assertEquals("Hello Bob", String(E2eeCrypto.decrypt(bobKey(), sealed, aad())))
    }

    @Test
    fun senderCanDecryptOwnMessage() {
        val sealed = E2eeCrypto.encrypt(aliceKey(), "note to self".toByteArray(), aad())
        assertEquals("note to self", String(E2eeCrypto.decrypt(aliceKey(), sealed, aad())))
    }

    @Test
    fun unicodeAndEmptyMessagesRoundTrip() {
        for (text in listOf("", "আমি বাংলায় লিখছি", "emoji 🔐🎉", "x".repeat(10_000))) {
            val sealed = E2eeCrypto.encrypt(aliceKey(), text.toByteArray(), aad())
            assertEquals(text, String(E2eeCrypto.decrypt(bobKey(), sealed, aad())))
        }
    }

    @Test
    fun sameMessageEncryptsDifferentlyEachTime() {
        val first = E2eeCrypto.encrypt(aliceKey(), "hi".toByteArray(), aad())
        val second = E2eeCrypto.encrypt(aliceKey(), "hi".toByteArray(), aad())
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun eavesdropperWithOwnKeysCannotDecrypt() {
        val evePriv = E2eeCrypto.generatePrivateKey()
        val eveWithAlice = E2eeCrypto.conversationKey("uidEve", evePriv, alice, alicePub)
        val eveWithBob = E2eeCrypto.conversationKey("uidEve", evePriv, bob, bobPub)
        val sealed = E2eeCrypto.encrypt(aliceKey(), "secret".toByteArray(), aad())

        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.decrypt(eveWithAlice, sealed, aad())
        }
        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.decrypt(eveWithBob, sealed, aad())
        }
    }

    @Test
    fun anyFlippedBitIsRejected() {
        val sealed = E2eeCrypto.encrypt(aliceKey(), "integrity".toByteArray(), aad())
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertThrows(GeneralSecurityException::class.java) {
                E2eeCrypto.decrypt(bobKey(), tampered, aad())
            }
        }
    }

    @Test
    fun truncatedCiphertextIsRejected() {
        val sealed = E2eeCrypto.encrypt(aliceKey(), "integrity".toByteArray(), aad())
        for (length in listOf(0, 5, 12, 27, sealed.size - 1)) {
            assertThrows(GeneralSecurityException::class.java) {
                E2eeCrypto.decrypt(bobKey(), sealed.copyOf(length), aad())
            }
        }
    }

    @Test
    fun ciphertextIsBoundToItsMetadata() {
        val sealed = E2eeCrypto.encrypt(aliceKey(), "bound".toByteArray(), aad())
        val otherContexts = listOf(
            aad(messageId = "-Nmsg002"),
            aad(sender = bob, receiver = alice),
            aad(type = "Image"),
            aad(timestamp = 1727200000001),
            aad(context = "incognito"),
            aad(chat = "uidAlice+uidCarol"),
            aad(senderKey = bobPub, receiverKey = alicePub)
        )
        for (otherAad in otherContexts) {
            assertThrows(GeneralSecurityException::class.java) {
                E2eeCrypto.decrypt(bobKey(), sealed, otherAad)
            }
        }
    }

    @Test
    fun differentPeersGetDifferentKeys() {
        val carolPriv = E2eeCrypto.generatePrivateKey()
        val carolPub = E2eeCrypto.publicKeyOf(carolPriv)
        val aliceCarol = E2eeCrypto.conversationKey(alice, alicePriv, "uidCarol", carolPub)
        assertFalse(aliceCarol.contentEquals(aliceKey()))
    }

    @Test
    fun freshKeyPairsAgree() {
        repeat(20) {
            val a = E2eeCrypto.generatePrivateKey()
            val b = E2eeCrypto.generatePrivateKey()
            assertArrayEquals(
                E2eeCrypto.conversationKey("a", a, "b", E2eeCrypto.publicKeyOf(b)),
                E2eeCrypto.conversationKey("b", b, "a", E2eeCrypto.publicKeyOf(a))
            )
        }
    }

    @Test
    fun lowOrderAndMalformedPublicKeysAreRejected() {
        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.conversationKey(alice, alicePriv, bob, ByteArray(32))
        }
        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.conversationKey(alice, alicePriv, bob, ByteArray(31))
        }
        assertThrows(GeneralSecurityException::class.java) {
            E2eeCrypto.conversationKey(alice, ByteArray(16), bob, bobPub)
        }
    }

    @Test
    fun safetyNumberIsTheSameForBothSides() {
        val fromAlice = E2eeCrypto.safetyNumber(alice, alicePub, bob, bobPub)
        val fromBob = E2eeCrypto.safetyNumber(bob, bobPub, alice, alicePub)
        assertEquals(fromAlice, fromBob)
        assertTrue(Regex("^(\\d{5} ){5}\\d{5}$").matches(fromAlice))
        // a swapped key gives a different number
        val evePub = E2eeCrypto.publicKeyOf(E2eeCrypto.generatePrivateKey())
        assertNotEquals(fromAlice, E2eeCrypto.safetyNumber(alice, alicePub, bob, evePub))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
