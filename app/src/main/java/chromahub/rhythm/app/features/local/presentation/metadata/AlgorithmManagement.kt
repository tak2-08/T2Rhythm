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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlgorithmManagement(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var dimension by remember { mutableStateOf("volume") }
    var patterns by remember { mutableStateOf(emptyList<ContextPreferenceIndex.Pattern>()) }
    var model by remember { mutableStateOf(EnrichmentModel()) }
    var recommendation by remember { mutableStateOf(RecommendationModel()) }
    var validation by remember { mutableStateOf<TimingValidationResult?>(null) }
    var reviews by remember { mutableStateOf(emptyList<TimingReview>()) }
    var trainedAt by remember { mutableLongStateOf(0) }
    var bytes by remember { mutableLongStateOf(0) }
    val revision by EnrichmentStore.revision.collectAsState()
    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            patterns = ContextPreferenceIndex(context).use { it.patterns() }
            model = EnrichmentStore(context).model()
            recommendation = RecommendationLearning.get(context).model()
            validation = TimingValidation.read(context)
            reviews = LyricTimingFeedback(context).read()
            trainedAt = context.getSharedPreferences("enrichment_progress", android.content.Context.MODE_PRIVATE).getLong("lastPass", 0)
            bytes = File(context.filesDir, "music_metadata/personalization/learning").walkTopDown().filter { it.isFile }.sumOf { it.length() } +
                listOf("enrichment-model.json", "enrichment-learning.db").sumOf { File(context.cacheDir, it).length() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column {
            Text(stringResource(R.string.algorithm_title), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.algorithm_patterns, R.string.algorithm_learning, R.string.algorithm_data).forEachIndexed { i, title ->
                    Tab(tab == i, onClick = { tab = i }, text = { Text(stringResource(title)) })
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp).navigationBarsPadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (tab == 0) {
                    item { Text(stringResource(R.string.algorithm_observations), style = MaterialTheme.typography.bodySmall) }
                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("volume" to R.string.algorithm_volume_label, "place" to R.string.algorithm_place_label,
                            "hour" to R.string.algorithm_hour_label, "output" to R.string.algorithm_output, "motion" to R.string.algorithm_motion).forEach { (key, title) ->
                            FilterChip(dimension == key, onClick = { dimension = key }, label = { Text(stringResource(title)) })
                        }
                    } }
                    if (patterns.none { it.dimension == dimension }) item { Text(stringResource(R.string.algorithm_empty)) }
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
                } else if (tab == 1) {
                    item { Text(stringResource(R.string.algorithm_recommendation_counts, recommendation.samples, recommendation.positive, recommendation.negative), style = MaterialTheme.typography.titleMedium) }
                    if (recommendation.samples >= 20) item { Text(stringResource(R.string.algorithm_recommendation_error,
                        recommendation.squaredError / recommendation.samples, recommendation.baselineError / recommendation.samples), style = MaterialTheme.typography.bodySmall) }
                    item { Text(stringResource(R.string.algorithm_weight_hint), style = MaterialTheme.typography.bodySmall) }
                    listOf("place" to R.string.algorithm_place_label, "volume" to R.string.algorithm_volume_label, "hour" to R.string.algorithm_hour_label).forEach { (feature, title) -> item {
                        val weight = recommendation.weights.getOrNull(AdaptiveRecommendation.featureNames.indexOf(feature)) ?: 0.0
                        Text(context.getString(title) + " · " + "%+.2f".format(weight), style = MaterialTheme.typography.labelLarge)
                    } }
                    item { HorizontalDivider() }
                    item { Text(stringResource(R.string.algorithm_model_counts, model.examples.size, model.timings.size, reviews.size), style = MaterialTheme.typography.titleMedium) }
                    item { Text(if (trainedAt > 0) context.getString(R.string.discovery_updated, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(trainedAt))) else context.getString(R.string.algorithm_empty)) }
                    item { Text(validation?.meanErrorMs?.let { context.getString(R.string.algorithm_validation, validation!!.songs, it / 1000) } ?: context.getString(R.string.algorithm_validation_pending), style = MaterialTheme.typography.bodySmall) }
                    item { Text(stringResource(R.string.algorithm_limits), style = MaterialTheme.typography.bodySmall) }
                    item { LearningPreferences() }
                    item { OutlinedButton(onClick = { AudioAnalysisWorker.enqueue(context); EnrichmentLearningWorker.enqueue(context); RecommendationTrainingWorker.enqueue(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.algorithm_schedule)) } }
                } else {
                    item { Text(context.getString(R.string.algorithm_storage, Formatter.formatShortFileSize(context, bytes)), style = MaterialTheme.typography.titleMedium) }
                    item { Text(stringResource(R.string.algorithm_storage_hint), style = MaterialTheme.typography.bodySmall) }
                    item { PersonalExperiencePanel() }
                }
            }
        }
    }
}
