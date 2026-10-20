// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.local.presentation.metadata

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.local.data.discovery.*
import chromahub.rhythm.app.features.local.data.experience.*
import kotlinx.coroutines.*
import java.io.File

private data class AlgorithmSnapshot(
    val patterns: List<ContextPreferenceIndex.Pattern> = emptyList(),
    val model: EnrichmentModel = EnrichmentModel(),
    val recommendation: RecommendationModel = RecommendationModel(),
    val validation: TimingValidationResult? = null,
    val reviews: List<TimingReview> = emptyList(),
    val trainedAt: Long = 0,
    val bytes: Long = 0,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlgorithmManagement(training: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val learningUi by chromahub.rhythm.app.shared.data.model.AppSettings.getInstance(context).learningUiEnabled.collectAsState()
    if (training && !learningUi) { LaunchedEffect(Unit) { onDismiss() }; return }
    var tab by remember(training) { mutableIntStateOf(0) }
    var dimension by remember { mutableStateOf("volume") }
    var snapshot by remember { mutableStateOf(AlgorithmSnapshot()) }
    val (patterns, model, recommendation, validation, reviews, trainedAt, bytes) = snapshot
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var showWeights by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    val work by remember { androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(TimingLearningWorker.NAME) }.collectAsState(emptyList())
    val learning = work.any { !it.state.isFinished }
    val running = work.any { it.state == androidx.work.WorkInfo.State.RUNNING }
    LaunchedEffect(requested) { if (requested) { delay(1500); requested = false } }
    val revision by EnrichmentStore.revision.collectAsState()
    LaunchedEffect(revision, retry) {
        if (!loading) delay(200) // Coalesce bursts of per-song updates into one summary read.
        loading = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                AlgorithmSnapshot(
                    ContextPreferenceIndex(context).use { it.patterns() },
                    EnrichmentStore(context).model(), RecommendationLearning.get(context).model(),
                    TimingValidation.read(context), LyricTimingFeedback(context).read(),
                    maxOf(context.getSharedPreferences("enrichment_progress", android.content.Context.MODE_PRIVATE).getLong("lastPass", 0),
                        File(context.cacheDir, "enrichment-model.json").lastModified()),
                    File(context.filesDir, "music_metadata/personalization/learning").walkTopDown().filter { it.isFile }.sumOf { it.length() } +
                        listOf("enrichment-model.json", "enrichment-learning.db").sumOf { File(context.cacheDir, it).length() },
                )
            }
            snapshot = loaded; failed = false
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { android.util.Log.w("AlgorithmManagement", "Could not read learning summary", error); failed = true }
        finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(if (training) R.string.algorithm_training_title else R.string.algorithm_title),
                    Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) }
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                (if (training) listOf(R.string.genre_training_title, R.string.algorithm_learning) else listOf(R.string.algorithm_patterns, R.string.algorithm_data)).forEachIndexed { i, title ->
                    Tab(tab == i, onClick = { tab = i }, text = { Text(stringResource(title)) })
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp).navigationBarsPadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (failed) item {
                    Text(stringResource(R.string.usability_load_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.mv_retry)) }
                }
                if (training && tab == 0) {
                    item { GenreTrainingPanel() }
                } else if (!training && tab == 0) {
                    item { Text(stringResource(R.string.algorithm_observations), style = MaterialTheme.typography.bodySmall) }
                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("volume" to R.string.algorithm_volume_label, "place" to R.string.algorithm_place_label,
                            "hour" to R.string.algorithm_hour_label, "output" to R.string.algorithm_output, "motion" to R.string.algorithm_motion).forEach { (key, title) ->
                            FilterChip(dimension == key, onClick = { dimension = key }, label = { Text(stringResource(title)) })
                        }
                    } }
                    if (!loading && !failed && patterns.none { it.dimension == dimension }) item { Text(stringResource(R.string.algorithm_empty)) }
                    val places = patterns.filter { it.dimension == "place" }.map { it.bucket }.sorted()
                    patterns.filter { it.dimension == dimension }.forEach { pattern -> item(key = pattern.dimension + pattern.bucket) {
                        val title = when (pattern.dimension) {
                            "place" -> context.getString(R.string.algorithm_place, places.indexOf(pattern.bucket) + 1)
                            "volume" -> { val n = pattern.bucket.toIntOrNull() ?: 0; context.getString(R.string.algorithm_volume, n * 25, (n + 1) * 25) }
                            "hour" -> context.getString(R.string.algorithm_hour, pattern.bucket.toIntOrNull() ?: 0)
                            "motion" -> context.getString(when (pattern.bucket) { "walking" -> R.string.mobility_walking; "cycling" -> R.string.mobility_cycling; "vehicle" -> R.string.mobility_vehicle; "airborne" -> R.string.mobility_airborne; else -> R.string.mobility_still })
                            else -> context.getString(when (pattern.bucket) { "bluetooth" -> R.string.context_route_bluetooth; "wired" -> R.string.context_route_wired; "usb" -> R.string.context_route_usb; "cast" -> R.string.context_route_cast; else -> R.string.context_route_speaker })
                        }
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            val total = pattern.genres.sumOf { it.second }.coerceAtLeast(1.0)
                            pattern.genres.forEach { (genre, minutes) ->
                                Text(context.getString(R.string.personal_dimension_minutes, genre, minutes.toLong()), style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = { (minutes / total).toFloat() }, modifier = Modifier.fillMaxWidth())
                            }
                        } }
                    } }
                } else if (training && tab == 1) {
                    item {
                        OutlinedButton(enabled = !running && !requested, onClick = {
                            try {
                                AudioAnalysisWorker.enqueue(context); EnrichmentLearningWorker.enqueue(context)
                                TimingLearningWorker.enqueue(context, userInitiated = true); RecommendationTrainingWorker.enqueue(context)
                                requested = true
                            } catch (_: Exception) { failed = true }
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.algorithm_schedule)) }
                        if (learning || requested) Text(stringResource(if (running) R.string.usability_learning_running else R.string.usability_learning_waiting), style = MaterialTheme.typography.bodySmall)
                    }
                    item { Text(stringResource(R.string.algorithm_recommendation_counts, recommendation.samples, recommendation.positive, recommendation.negative), style = MaterialTheme.typography.titleMedium) }
                    item { TextButton(onClick = { showWeights = !showWeights }) { Text(stringResource(R.string.usability_recommendation_details)) } }
                    if (showWeights) {
                    if (recommendation.samples >= 20) item { Text(stringResource(R.string.algorithm_recommendation_error,
                        recommendation.squaredError / recommendation.samples, recommendation.baselineError / recommendation.samples), style = MaterialTheme.typography.bodySmall) }
                    item { Text(stringResource(R.string.algorithm_weight_hint), style = MaterialTheme.typography.bodySmall) }
                    listOf("place" to R.string.algorithm_place_label, "volume" to R.string.algorithm_volume_label, "hour" to R.string.algorithm_hour_label).forEach { (feature, title) -> item {
                        val weight = recommendation.weights.getOrNull(AdaptiveRecommendation.featureNames.indexOf(feature)) ?: 0.0
                        Text(context.getString(title) + " · " + "%+.2f".format(weight), style = MaterialTheme.typography.labelLarge)
                    } }
                    }
                    item { HorizontalDivider() }
                    item { Text(stringResource(R.string.algorithm_model_counts, model.examples.size, model.timings.size, reviews.size), style = MaterialTheme.typography.titleMedium) }
                    item { Text(stringResource(R.string.timing_learning_pending, reviews.count { it.pending }), style = MaterialTheme.typography.bodySmall) }
                    item { Text(stringResource(when {
                        validation?.method == "insufficient" || validation == null -> R.string.timing_learning_provisional
                        validation?.promoted != true -> R.string.timing_learning_kept
                        validation?.method == "recording_cross_validation" -> R.string.timing_learning_cross
                        else -> R.string.timing_learning_validated
                    }), style = MaterialTheme.typography.bodySmall) }
                    item { Text(if (trainedAt > 0) context.getString(R.string.discovery_updated, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(trainedAt))) else context.getString(R.string.usability_learning_never)) }
                    item { Text(validation?.meanErrorMs?.let { context.getString(R.string.algorithm_validation, validation!!.songs, it / 1000) } ?: context.getString(R.string.algorithm_validation_pending), style = MaterialTheme.typography.bodySmall) }
                    item { Text(stringResource(R.string.algorithm_limits), style = MaterialTheme.typography.bodySmall) }
                    item { LearningPreferences() }
                    item { RecommendationFeedbackSettings() }
                } else {
                    item { Text(context.getString(R.string.algorithm_storage, Formatter.formatShortFileSize(context, bytes)), style = MaterialTheme.typography.titleMedium) }
                    item { Text(stringResource(R.string.algorithm_storage_hint), style = MaterialTheme.typography.bodySmall) }
                    item { PersonalExperiencePanel() }
                }
            }
        }
    }
}
