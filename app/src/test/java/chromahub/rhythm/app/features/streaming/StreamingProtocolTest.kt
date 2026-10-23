// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StreamingProtocolTest {
    @Test fun certificateDigestUsesStandardLowercaseSha256AndTokensHaveFullEntropyLength() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", StreamingProtocol.hash(byteArrayOf()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", StreamingProtocol.hash("abc"))
        assertTrue((1..100).map { StreamingProtocol.token() }.also { assertTrue(it.all(StreamingProtocol::validToken)) }.toSet().size == 100)
    }
    @Test fun rangesCoverSeekingSuffixesAndClampingWithoutOverflow() {
        val parse = StreamingProtocol.ByteRange.Companion::parse
        assertEquals(StreamingProtocol.ByteRange(0, 100), parse(null, 100))
        assertEquals(StreamingProtocol.ByteRange(10, 21), parse("bytes=10-30", 100))
        assertEquals(StreamingProtocol.ByteRange(99, 1), parse("bytes=99-", 100))
        assertEquals(StreamingProtocol.ByteRange(80, 20), parse("bytes=-20", 100))
        assertEquals(StreamingProtocol.ByteRange(0, 100), parse("bytes=-999", 100))
        assertEquals(StreamingProtocol.ByteRange(90, 10), parse("bytes=90-9223372036854775807", 100))
        assertEquals(StreamingProtocol.ByteRange(Long.MAX_VALUE - 1, 1), parse("bytes=9223372036854775806-", Long.MAX_VALUE))
        assertEquals(StreamingProtocol.ByteRange(0, 0), parse(null, 0))
    }
    @Test fun malformedOrMultipleRangesAreRejected() {
        listOf("bytes=", "bytes=-", "bytes=-0", "bytes=100-", "bytes=20-10", "bytes=0-1,4-5", "bytes=0-999999999999999999999", "items=0-1", "bytes=--1", "bytes=+1-3").forEach {
            assertNull(it, StreamingProtocol.ByteRange.parse(it, 100))
        }
        assertNull(StreamingProtocol.ByteRange.parse("bytes=0-1", 0))
        assertNull(StreamingProtocol.ByteRange.parse(null, -1))
    }
    @Test fun addressNormalizationSupportsLanVpnIpv6AndDns() {
        assertEquals("https://192.168.1.3:8898", StreamingProtocol.endpoint("192.168.1.3"))
        assertEquals("https://music.example:8898", StreamingProtocol.endpoint("https://MUSIC.example/"))
        assertEquals("https://100.90.80.70:443", StreamingProtocol.endpoint("https://100.90.80.70:443"))
        assertEquals("https://[fd7a:115c:a1e0::1]:8898", StreamingProtocol.endpoint("https://[fd7a:115c:a1e0::1]"))
    }
    @Test fun endpointsCannotSmuggleCredentialsPathsQueriesOrCleartext() {
        listOf("http://192.168.1.3", "https://user:password@example.com", "https://example.com/v1/audio", "https://example.com/?token=secret", "https://example.com/#secret", "https://example.com:0", "https://example.com:65536", "file:///etc/passwd", "https://[fe80::1%25wlan0]").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { StreamingProtocol.endpoint(it) }
        }
    }
    @Test fun invitationRoundTripsAndExpiresWithoutContainingPersistentClientCredentials() {
        val now = 123_000L
        val invite = StreamingProtocol.Invite(UUID.randomUUID().toString(), "서브폰", "https://192.168.1.3:8898", "a".repeat(64), StreamingProtocol.token(), now + 600_000)
        assertEquals(invite, StreamingProtocol.Invite.decode("T2Rhythm Streaming\n${invite.encode()}", now))
        assertThrows(IllegalArgumentException::class.java) { StreamingProtocol.Invite.decode(invite.encode(), invite.expires) }
        assertThrows(IllegalArgumentException::class.java) { StreamingProtocol.Invite.decode(invite.copy(expires = now + 700_000).encode(), now) }
        assertThrows(IllegalArgumentException::class.java) { StreamingProtocol.Invite.decode(invite.copy(pin = "wrong").encode(), now) }
        assertThrows(IllegalArgumentException::class.java) { StreamingProtocol.Invite.decode(invite.copy(secret = "123456").encode(), now) }
    }
    @Test fun pairingIsOneUseAndDoesNotGrantLibraryAccessBeforeApproval() {
        val access = StreamingAccess(); val now = 1000L
        val invite = access.invite(now).first; val token = StreamingProtocol.token()
        val request = access.pair(invite, token, "My\nphone", now)!!
        assertEquals("Myphone", request.peer.name)
        assertNull(access.authorized(token)); assertEquals("pending", access.status(token, now))
        assertNull(access.pair(invite, StreamingProtocol.token(), "other", now))
        assertTrue(access.approve(request.peer.id, now)); assertEquals("approved", access.status(token, now))
        assertEquals(request.peer, access.authorized(token))
        access.revoke(request.peer.id); assertNull(access.authorized(token)); assertEquals("denied", access.status(token, now))
    }
    @Test fun concurrentRequestsCannotReuseAnInvitation() {
        val access = StreamingAccess(); val invite = access.invite().first
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(6)
        try {
            val results = (0..11).map { pool.submit<Boolean> { start.await(); access.pair(invite, StreamingProtocol.token(), "Device") != null } }
            start.countDown(); assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) }); assertEquals(1, access.requests().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun expiredAndDeclinedRequestsCannotBeApproved() {
        val access = StreamingAccess(); val token = StreamingProtocol.token()
        val request = access.pair(access.invite(1000).first, token, "Phone", 1000)!!
        assertFalse(access.approve(request.peer.id, request.expires)); assertEquals("denied", access.status(token, request.expires))
        val next = access.pair(access.invite().first, token, "Phone")!!; access.revoke(next.peer.id)
        assertFalse(access.approve(next.peer.id))
    }
    @Test fun refreshedInvitationInvalidatesThePreviousOneAndStopClearsPending() {
        val access = StreamingAccess(); val old = access.invite().first; val fresh = access.invite().first
        assertNull(access.pair(old, StreamingProtocol.token(), "Phone"))
        val token = StreamingProtocol.token(); assertNotNull(access.pair(fresh, token, "Phone"))
        access.closePairing(); assertEquals("denied", access.status(token)); assertTrue(access.requests().isEmpty())
    }
    @Test fun failedCredentialWritesFailClosedOnApprovalAndPreserveExistingPeersOnRevocation() {
        var fail = true; var saved = emptyList<StreamPeer>()
        val access = StreamingAccess { if (fail) throw IOException("disk full"); saved = it }
        val token = StreamingProtocol.token(); val request = access.pair(access.invite().first, token, "Phone")!!
        assertThrows(IOException::class.java) { access.approve(request.peer.id) }; assertNull(access.authorized(token))
        fail = false; access.approve(request.peer.id); assertEquals(listOf(request.peer), saved)
        fail = true; assertThrows(IOException::class.java) { access.revoke(request.peer.id) }; assertNotNull(access.authorized(token))
    }
    @Test fun persistentHostStoresHashesAndPeerLimitIsEnforced() {
        var saved = emptyList<StreamPeer>(); val access = StreamingAccess { saved = it }
        val tokens = (1..StreamingProtocol.MAX_PEERS).map {
            val token = StreamingProtocol.token(); access.approve(access.pair(access.invite().first, token, "Phone $it")!!.peer.id); token
        }
        tokens.forEach { token -> assertFalse(saved.any { it.tokenHash == token }); assertNotNull(StreamingAccess(saved).authorized(token)) }
        assertThrows(IllegalStateException::class.java) { access.invite() }
        assertNull(access.authorized("123456")); assertNull(access.authorized(StreamingProtocol.token()))
    }
}
