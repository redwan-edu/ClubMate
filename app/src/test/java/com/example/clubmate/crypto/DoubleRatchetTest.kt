package com.example.clubmate.crypto

import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import kotlin.random.Random

class DoubleRatchetTest {

    // RFC 7748 keys as identity keys; fixed prekey/ephemeral/ratchet keys for known-answer tests
    private val ikA = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val ikB = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val spkB = ByteArray(32) { (32 + it).toByte() }
    private val ekA = ByteArray(32) { (64 + it).toByte() }
    private val ratchetA = ByteArray(32) { (96 + it).toByte() }
    private val extra = "extra-aad".toByteArray()

    private fun pub(k: ByteArray) = X25519.publicFromPrivate(k)
    private fun ad() = X3dh.associatedData("uidAlice", pub(ikA), "uidBob", pub(ikB))

    /** A fresh session: Alice initiates to Bob, returns (alice, bob-after-first-message). */
    private class Pair2(var alice: RatchetState, var bob: RatchetState)

    private fun session(): Pair2 {
        val aliceIk = X25519.generatePrivateKey()
        val bobIk = X25519.generatePrivateKey()
        val bobSpk = X25519.generatePrivateKey()
        val init = X3dh.initiate(aliceIk, pub(bobIk), pub(bobSpk))
        val ad = X3dh.associatedData("a", pub(aliceIk), "b", pub(bobIk))
        var alice = DoubleRatchet.initInitiator(init.sharedSecret, pub(bobSpk), ad)
        val bobSk = X3dh.respond(bobIk, bobSpk, pub(aliceIk), init.ephemeralPublic)
        var bob = DoubleRatchet.initResponder(bobSk, bobSpk, ad)

        val hello = DoubleRatchet.encrypt(alice, "hello".toByteArray(), extra)
        alice = hello.state
        val opened = DoubleRatchet.decrypt(bob, hello.header, hello.ciphertext, extra)
        assertEquals("hello", String(opened.plaintext))
        bob = opened.state
        return Pair2(alice, bob)
    }

    @Test
    fun x3dhBothSidesAgreeAndMatchPythonReference() {
        val init = X3dh.initiateWith(ikA, pub(ikB), pub(spkB), ekA)
        val responder = X3dh.respond(ikB, spkB, pub(ikA), init.ephemeralPublic)
        assertArrayEquals(init.sharedSecret, responder)
        assertArrayEquals(hex("cc9cedd5b6576ff1811851e74e14b2699aae90926bd5ab9696166d8402e7f981"), init.sharedSecret)
    }

    @Test
    fun firstRatchetMessageMatchesPythonReference() {
        val sk = X3dh.initiateWith(ikA, pub(ikB), pub(spkB), ekA).sharedSecret
        val alice = DoubleRatchet.initInitiatorWith(sk, pub(spkB), ad(), ratchetA)
        assertArrayEquals(hex("df9116a0387417f4fb50ecdf8087436e775b1668989d022bf9863658a42348a8"), alice.rootKey)
        assertArrayEquals(hex("d45c2e6ad735268e6af5d3b91a14b628cf254d0d128f6c004308fe805ccf1f18"), alice.sendingChain)

        val first = DoubleRatchet.encrypt(alice, "first ratchet message".toByteArray(), extra)
        assertArrayEquals(
            hex("0f273aa4e437e5522e6463f8c886c24748423e40aea0df058eb2f2e6f03b6b0467c4acd9a4"),
            first.ciphertext
        )
        // and Bob opens it with the responder side of X3DH
        val bob = DoubleRatchet.initResponder(X3dh.respond(ikB, spkB, pub(ikA), pub(ekA)), spkB, ad())
        assertEquals("first ratchet message", String(DoubleRatchet.decrypt(bob, first.header, first.ciphertext, extra).plaintext))
    }

    @Test
    fun longConversationInRandomDirections() {
        val s = session()
        val random = Random(42)
        repeat(300) { i ->
            val text = "message $i ✓"
            if (random.nextBoolean()) {
                val e = DoubleRatchet.encrypt(s.alice, text.toByteArray(), extra); s.alice = e.state
                val d = DoubleRatchet.decrypt(s.bob, e.header, e.ciphertext, extra); s.bob = d.state
                assertEquals(text, String(d.plaintext))
            } else {
                val e = DoubleRatchet.encrypt(s.bob, text.toByteArray(), extra); s.bob = e.state
                val d = DoubleRatchet.decrypt(s.alice, e.header, e.ciphertext, extra); s.alice = d.state
                assertEquals(text, String(d.plaintext))
            }
        }
    }

    @Test
    fun outOfOrderAndLostMessages() {
        val s = session()
        // Bob replies with three messages, Alice with three, then Bob with three again
        val fromBob1 = (0 until 3).map { i -> DoubleRatchet.encrypt(s.bob, "b1-$i".toByteArray(), extra).also { s.bob = it.state } }
        val bobFirst = fromBob1[0]
        s.alice = DoubleRatchet.decrypt(s.alice, bobFirst.header, bobFirst.ciphertext, extra).state
        val fromAlice = (0 until 3).map { i -> DoubleRatchet.encrypt(s.alice, "a-$i".toByteArray(), extra).also { s.alice = it.state } }
        s.bob = DoubleRatchet.decrypt(s.bob, fromAlice[2].header, fromAlice[2].ciphertext, extra).state
        val fromBob2 = (0 until 3).map { i -> DoubleRatchet.encrypt(s.bob, "b2-$i".toByteArray(), extra).also { s.bob = it.state } }

        // Alice receives newest first, then the delayed ones from the old chain; b1-1 is lost forever
        for (m in listOf(fromBob2[2], fromBob1[2], fromBob2[0], fromBob2[1])) {
            val d = DoubleRatchet.decrypt(s.alice, m.header, m.ciphertext, extra)
            s.alice = d.state
        }
        // Bob gets Alice's delayed messages from her chain
        for ((i, m) in listOf(fromAlice[0], fromAlice[1]).withIndex()) {
            val d = DoubleRatchet.decrypt(s.bob, m.header, m.ciphertext, extra)
            assertEquals("a-$i", String(d.plaintext))
            s.bob = d.state
        }
        // the conversation carries on normally
        val e = DoubleRatchet.encrypt(s.alice, "still fine".toByteArray(), extra)
        assertEquals("still fine", String(DoubleRatchet.decrypt(s.bob, e.header, e.ciphertext, extra).plaintext))
    }

    @Test
    fun replaysAndTamperingAreRejectedWithoutDamagingTheSession() {
        val s = session()
        val e = DoubleRatchet.encrypt(s.alice, "pay 5 taka".toByteArray(), extra)
        val before = s.bob

        val flipped = e.ciphertext.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { DoubleRatchet.decrypt(before, e.header, flipped, extra) }
        assertThrows(GeneralSecurityException::class.java) {
            DoubleRatchet.decrypt(before, e.header, e.ciphertext, "other-message".toByteArray())
        }
        assertThrows(GeneralSecurityException::class.java) {
            DoubleRatchet.decrypt(before, RatchetHeader(e.header.dh, 0, 5), e.ciphertext, extra)
        }

        // the untouched state still decrypts the real message
        val d = DoubleRatchet.decrypt(before, e.header, e.ciphertext, extra)
        assertEquals("pay 5 taka", String(d.plaintext))
        // ...but only once: its key is gone afterwards
        assertThrows(GeneralSecurityException::class.java) { DoubleRatchet.decrypt(d.state, e.header, e.ciphertext, extra) }
    }

    @Test
    fun tooManySkippedMessagesAreRefused() {
        val s = session()
        val e = DoubleRatchet.encrypt(s.alice, "x".toByteArray(), extra)
        val farAhead = RatchetHeader(e.header.dh, 0, DoubleRatchet.MAX_SKIP + 5)
        assertThrows(GeneralSecurityException::class.java) { DoubleRatchet.decrypt(s.bob, farAhead, e.ciphertext, extra) }
    }

    @Test
    fun forwardSecrecyOldMessagesCantBeReadFromLaterState() {
        val s = session()
        val old = DoubleRatchet.encrypt(s.alice, "old secret".toByteArray(), extra)
        s.alice = old.state
        s.bob = DoubleRatchet.decrypt(s.bob, old.header, old.ciphertext, extra).state
        // an attacker who steals Bob's state *now* can't decrypt the message he already read
        val stolen = RatchetState.deserialize(s.bob.serialize())
        assertTrue(stolen.skipped.isEmpty())
        assertThrows(GeneralSecurityException::class.java) { DoubleRatchet.decrypt(stolen, old.header, old.ciphertext, extra) }
    }

    @Test
    fun postCompromiseSecurityAttackerIsLockedOutAfterARoundTrip() {
        val s = session()
        val eve = RatchetState.deserialize(s.bob.serialize()) // full copy of Bob's state at time T

        // Bob and Alice exchange messages: fresh DH ratchet keys that Eve doesn't have
        repeat(2) {
            val b = DoubleRatchet.encrypt(s.bob, "b".toByteArray(), extra); s.bob = b.state
            s.alice = DoubleRatchet.decrypt(s.alice, b.header, b.ciphertext, extra).state
            val a = DoubleRatchet.encrypt(s.alice, "a".toByteArray(), extra); s.alice = a.state
            s.bob = DoubleRatchet.decrypt(s.bob, a.header, a.ciphertext, extra).state
        }
        val secret = DoubleRatchet.encrypt(s.alice, "after healing".toByteArray(), extra)
        assertEquals("after healing", String(DoubleRatchet.decrypt(s.bob, secret.header, secret.ciphertext, extra).plaintext))
        assertThrows(GeneralSecurityException::class.java) { DoubleRatchet.decrypt(eve, secret.header, secret.ciphertext, extra) }
    }

    @Test
    fun stateSurvivesSerializationMidConversation() {
        val s = session()
        val e1 = DoubleRatchet.encrypt(s.bob, "one".toByteArray(), extra); s.bob = e1.state
        val e2 = DoubleRatchet.encrypt(s.bob, "two".toByteArray(), extra); s.bob = e2.state
        // Alice gets #2 first (so #1's key is stored as skipped), then restarts the app
        s.alice = DoubleRatchet.decrypt(s.alice, e2.header, e2.ciphertext, extra).state
        s.alice = RatchetState.deserialize(s.alice.serialize())
        assertEquals("one", String(DoubleRatchet.decrypt(s.alice, e1.header, e1.ciphertext, extra).plaintext))
        assertThrows(GeneralSecurityException::class.java) { RatchetState.deserialize(byteArrayOf(9, 9, 9)) }
    }

    @Test
    fun responderCantSendBeforeReceivingAndForgedHeadersFailCleanly() {
        val bobSpk = X25519.generatePrivateKey()
        val bob = DoubleRatchet.initResponder(ByteArray(32) { 1 }, bobSpk, ad())
        assertFalse(bob.canSend)
        assertThrows(GeneralSecurityException::class.java) { DoubleRatchet.encrypt(bob, "x".toByteArray(), extra) }

        // a header reusing the key Alice already has (Bob's prekey) before any reply: rejected, no crash
        val alice = DoubleRatchet.initInitiator(ByteArray(32) { 2 }, pub(bobSpk), ad())
        assertThrows(GeneralSecurityException::class.java) {
            DoubleRatchet.decrypt(alice, RatchetHeader(pub(bobSpk), 0, 0), ByteArray(40), extra)
        }
        assertThrows(GeneralSecurityException::class.java) {
            DoubleRatchet.decrypt(alice, RatchetHeader(ByteArray(31), 0, 0), ByteArray(40), extra)
        }
    }

    @Test
    fun sessionsWithDifferentIdentitiesDontInteroperate() {
        val init = X3dh.initiateWith(ikA, pub(ikB), pub(spkB), ekA)
        val alice = DoubleRatchet.initInitiator(init.sharedSecret, pub(spkB), ad())
        val e = DoubleRatchet.encrypt(alice, "hi".toByteArray(), extra)
        // Bob believing he talks to someone else (different associated data)
        val wrongAd = X3dh.associatedData("uidMallory", pub(ikA), "uidBob", pub(ikB))
        val bob = DoubleRatchet.initResponder(X3dh.respond(ikB, spkB, pub(ikA), init.ephemeralPublic), spkB, wrongAd)
        assertThrows(GeneralSecurityException::class.java) { DoubleRatchet.decrypt(bob, e.header, e.ciphertext, extra) }
        // or a wrong prekey
        val bob2 = DoubleRatchet.initResponder(X3dh.respond(ikB, ekA, pub(ikA), init.ephemeralPublic), ekA, ad())
        assertThrows(GeneralSecurityException::class.java) { DoubleRatchet.decrypt(bob2, e.header, e.ciphertext, extra) }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
