package org.artcraft.camcraft.encoder

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.artcraft.camcraft.protocol.AudioConfigPayload
import org.artcraft.camcraft.protocol.CcftJson
import org.artcraft.camcraft.protocol.CcftPacket
import org.artcraft.camcraft.protocol.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStreamEngineTest {

    @Test
    fun audioStreamConstantsMatchProtocolSpec() {
        assertEquals(48_000, AudioStreamEngine.SAMPLE_RATE_HZ)
        assertEquals(1, AudioStreamEngine.CHANNEL_COUNT)
        assertEquals(20, AudioStreamEngine.FRAME_DURATION_MS)
        assertEquals(960, AudioStreamEngine.SAMPLES_PER_FRAME)
        assertEquals(1_920, AudioStreamEngine.FRAME_SIZE_BYTES)
        assertEquals("pcm_s16le", AudioStreamEngine.CODEC_PCM_S16LE)
    }

    @Test
    fun buildAudioConfigBytesMatchesProtocolJsonAndPacketType() {
        val bytes = AudioStreamEngine.buildAudioConfigBytes()
        val parsed = CcftJson.decodeFromString<AudioConfigPayload>(bytes.toString(Charsets.UTF_8))
        assertEquals(48_000, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals("pcm_s16le", parsed.codec)

        val packet = CcftPacket.audioConfig(1_234L, AudioStreamEngine.buildAudioConfig())
        assertEquals(PacketType.AUDIO_CONFIG, packet.header.packetType)
        assertEquals(0x03.toByte(), packet.header.packetType.id)
        assertArrayEquals(bytes, packet.payload)
    }

    @Test
    fun selectFramePayloadSwitchesZeroAllocationBetweenLiveAndSilence() {
        val captured = ByteArray(AudioStreamEngine.FRAME_SIZE_BYTES) { 0x42 }
        val silence = ByteArray(AudioStreamEngine.FRAME_SIZE_BYTES)

        val unmutedPayload = AudioStreamEngine.selectFramePayload(captured, silence, muted = false)
        assertSame(captured, unmutedPayload)

        val mutedPayload = AudioStreamEngine.selectFramePayload(captured, silence, muted = true)
        assertSame(silence, mutedPayload)
        assertTrue(mutedPayload.all { it == 0.toByte() })
    }

    @Test
    fun calculateBufferSizeBytesAlignsToFrameMultiple() {
        val minSmall = AudioStreamEngine.calculateBufferSizeBytes(512)
        assertEquals(AudioStreamEngine.FRAME_SIZE_BYTES * 4, minSmall)
        assertEquals(0, minSmall % AudioStreamEngine.FRAME_SIZE_BYTES)

        val minLarge = AudioStreamEngine.calculateBufferSizeBytes(10_000)
        assertTrue(minLarge >= 10_000)
        assertEquals(0, minLarge % AudioStreamEngine.FRAME_SIZE_BYTES)

        val invalidMin = AudioStreamEngine.calculateBufferSizeBytes(-1)
        assertEquals(AudioStreamEngine.FRAME_SIZE_BYTES * 4, invalidMin)
    }

    @Test
    fun evaluateReadStepAbortsImmediatelyOnNegativeReadAfterPartialBytes() {
        // read < 0 after partial read (0 < filled < FRAME_SIZE_BYTES) must abort capture loop.
        assertEquals(
            AudioStreamEngine.Companion.ReadStepDecision.ABORT_CAPTURE,
            AudioStreamEngine.evaluateReadStep(read = -3, filledBefore = 640),
        )
        assertEquals(
            AudioStreamEngine.Companion.ReadStepDecision.ABORT_CAPTURE,
            AudioStreamEngine.evaluateReadStep(read = -1, filledBefore = 0),
        )
        assertEquals(
            AudioStreamEngine.Companion.ReadStepDecision.ABORT_CAPTURE,
            AudioStreamEngine.evaluateReadStep(read = 0, filledBefore = 0),
        )
        assertEquals(
            AudioStreamEngine.Companion.ReadStepDecision.RETRY_FRAME,
            AudioStreamEngine.evaluateReadStep(read = 0, filledBefore = 640),
        )
        assertEquals(
            AudioStreamEngine.Companion.ReadStepDecision.ADVANCE,
            AudioStreamEngine.evaluateReadStep(read = 960, filledBefore = 0),
        )
    }

    @Test
    fun lifecycleCoordinatorSerializesRapidStopStartWithoutResourceOverlap() {
        val activeCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val createdCount = AtomicInteger(0)
        val releaseEvents = ArrayList<String>()

        class FakeRecord(val id: Int) {
            @Volatile
            var stopped = false
            @Volatile
            var released = false
            val allowExit = CountDownLatch(1)
        }

        val coordinator = AudioLifecycleCoordinator<FakeRecord>(
            stopResource = {
                it.stopped = true
                it.allowExit.countDown()
            },
            releaseResource = {
                Thread.sleep(25)
                it.released = true
                synchronized(releaseEvents) {
                    releaseEvents.add("release-${it.id}")
                }
                activeCount.decrementAndGet()
            },
        )

        lateinit var firstRecord: FakeRecord
        val firstWorkerStarted = CountDownLatch(1)
        assertTrue(
            coordinator.start(
                createAndStart = {
                    val concurrent = activeCount.incrementAndGet()
                    maxConcurrent.updateAndGet { prev -> maxOf(prev, concurrent) }
                    FakeRecord(createdCount.incrementAndGet()).also { firstRecord = it }
                },
                spawnWorker = { rec ->
                    Thread {
                        firstWorkerStarted.countDown()
                        rec.allowExit.await(1, TimeUnit.SECONDS)
                        coordinator.onWorkerExit(rec)
                    }.apply { start() }
                },
            ),
        )
        assertTrue(firstWorkerStarted.await(1, TimeUnit.SECONDS))

        // Rapid stop() + concurrent start() must wait until record 1 is stopped, joined, and released.
        val stopStarted = CountDownLatch(1)
        val startDone = CountDownLatch(1)
        val stopThread = Thread {
            stopStarted.countDown()
            coordinator.stop()
        }
        val startThread = Thread {
            stopStarted.await(1, TimeUnit.SECONDS)
            assertTrue(
                coordinator.start(
                    createAndStart = {
                        assertTrue("Prior resource must be released before creating next", firstRecord.released)
                        synchronized(releaseEvents) {
                            releaseEvents.add("create-2")
                        }
                        val concurrent = activeCount.incrementAndGet()
                        maxConcurrent.updateAndGet { prev -> maxOf(prev, concurrent) }
                        FakeRecord(createdCount.incrementAndGet())
                    },
                    spawnWorker = { rec ->
                        Thread {
                            rec.allowExit.await(1, TimeUnit.SECONDS)
                            coordinator.onWorkerExit(rec)
                        }.apply { start() }
                    },
                ),
            )
            startDone.countDown()
        }

        stopThread.start()
        startThread.start()
        stopThread.join(2_000)
        startThread.join(2_000)
        assertTrue(startDone.await(1, TimeUnit.SECONDS))
        assertEquals(1, maxConcurrent.get())
        assertEquals(listOf("release-1", "create-2"), synchronized(releaseEvents) { releaseEvents.toList() })

        coordinator.stop()
        assertFalse(coordinator.isActive)
    }
}
