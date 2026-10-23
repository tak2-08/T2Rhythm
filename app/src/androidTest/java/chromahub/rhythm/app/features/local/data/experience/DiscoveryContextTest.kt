// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.data.experience

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DiscoveryContextTest {
    @get:org.junit.Rule val analysisLoad = chromahub.rhythm.app.features.local.data.experience.AnalysisRetryRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun automaticRefreshRespectsRecentInteractionAndRechecksBeforeReplacement() {
        val now = System.currentTimeMillis()
        val current =
            RecommendationShelf.create(
                context,
                PersonalCollection.DAYLIST,
                null,
                listOf("one", "two"),
                10,
            )!!
        val old = current.copy(createdAt = now - 50 * 3600000L)
        val file = File(context.filesDir, "music_metadata/personalization/mixes/${old.id}.json")
        file.writeText(com.google.gson.Gson().toJson(old))
        assertTrue(RecommendationShelf.refreshDue(context, old, now))
        RecommendationShelf.interacted(context, old.id)
        assertFalse(RecommendationShelf.refreshDue(context, old, now))
        assertNull(
            RecommendationShelf.createIfCurrent(context, old.id, old.mode, null, listOf("new"), 11)
        )
        assertEquals(old.id, RecommendationShelf.current(context)!!.id)
        assertTrue(RecommendationShelf.refreshDue(context, old, now + 31 * 60000L))
    }

    @Test
    fun jointContextsSeparatePatternsWithIdenticalMotionAndDaylightMarginals() {
        val nonce = UUID.randomUUID().toString().uppercase(java.util.Locale.ROOT)
        val root = File(context.cacheDir, "joint-$nonce").apply { mkdirs() }
        val isolated =
            object : ContextWrapper(context) {
                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }

                override fun getFilesDir() = File(root, "files").apply { mkdirs() }

                override fun getDatabasePath(name: String) =
                    File(name).takeIf { it.isAbsolute } ?: File(root, name)
            }
        // Keep a fresh source DB as well as a fresh derived index. Replaying previous runs is
        // unbounded and correctly hits the production analysis slice limit.
        val ledger =
            ListeningLedger::class
                .java
                .getDeclaredConstructor(android.content.Context::class.java)
                .apply { isAccessible = true }
                .newInstance(isolated)
        val now = System.currentTimeMillis()
        fun scene(motion: String, day: Boolean) =
            ListeningSituation(
                hour = 8,
                weekday = 4,
                motion = MotionSnapshot(motion, .9, "test", now),
                daylight = DaylightSnapshot(day, "solar"),
            )
        // Both genres have the same separate motion/daylight totals, but opposite combinations.
        val scenes =
            listOf(
                Triple("$nonce-POP", "walking", true),
                Triple("$nonce-POP", "vehicle", false),
                Triple("$nonce-ROCK", "walking", false),
                Triple("$nonce-ROCK", "vehicle", true),
            )
        try {
            scenes.forEachIndexed { i, (genre, motion, day) ->
                val id = "$nonce-$i"
                ledger.record(
                    ListeningRecord(
                        id,
                        id,
                        "segment",
                        id,
                        id,
                        "Song",
                        "Artist",
                        "Album",
                        genre,
                        now,
                        now + 60000,
                        60000,
                        180000,
                        0,
                        60000,
                        1f,
                        "test",
                        situation = scene(motion, day),
                    )
                )
            }
            assertEquals(4, ledger.records().size)
            ContextPreferenceIndex(isolated).use { index ->
                index.rebuild(ledger, AnalysisBudget(context, maxMillis = 60000))
                val result = index.preferences(scene("walking", true), null)
                assertEquals(
                    result.motionGenres["$nonce-POP"]!!,
                    result.motionGenres["$nonce-ROCK"]!!,
                    .00001,
                )
                assertEquals(
                    result.daylightGenres["$nonce-POP"]!!,
                    result.daylightGenres["$nonce-ROCK"]!!,
                    .00001,
                )
                assertTrue(
                    (result.jointGenres["$nonce-POP"] ?: 0.0) >
                        (result.jointGenres["$nonce-ROCK"] ?: 0.0)
                )
            }
        } finally {
            ledger.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectedGenreDoesNotContaminateAReplacementFileAtTheSameUri() {
        val root =
            File(context.cacheDir, "learning-signature-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated =
            object : ContextWrapper(context) {
                override fun getCacheDir() = root
            }
        try {
            val vector = listOf(1.0, 0.0)
            val key = "content://tests/replaced"
            val rejection =
                chromahub.rhythm.app.features.local.data.discovery.EnrichmentProposal(
                    "id",
                    "GENRE",
                    key,
                    "Song",
                    "Artist",
                    "old-signature",
                    "rock",
                    .8,
                    "local_neighbors",
                    3,
                    state = "REJECTED",
                )
            chromahub.rhythm.app.features.local.data.discovery
                .EnrichmentLearningIndex(isolated)
                .use { index ->
                    index.put(key, "pop", vector, null, "new-signature")
                    assertFalse(index.model(listOf(rejection)).examples.any { !it.positive })
                    val current = rejection.copy(sourceSignature = "new-signature")
                    assertTrue(
                        index.model(listOf(current)).examples.any {
                            !it.positive && it.genre == "ROCK"
                        }
                    )
                }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun allNewAnalysisWorkersUseChargingAndIdleCompatibleRequests() {
        chromahub.rhythm.app.features.local.data.discovery.EnrichmentLearningWorker.enqueue(context)
        val manager = androidx.work.WorkManager.getInstance(context)
        assertTrue(
            manager
                .getWorkInfosForUniqueWork("enrichment_learning")
                .get(15, java.util.concurrent.TimeUnit.SECONDS)
                .isNotEmpty()
        )
        RecommendationRefreshWorker.enqueue(context)
        assertTrue(
            manager
                .getWorkInfosForUniqueWork("recommendation_refresh")
                .get(15, java.util.concurrent.TimeUnit.SECONDS)
                .isNotEmpty()
        )
    }
}
