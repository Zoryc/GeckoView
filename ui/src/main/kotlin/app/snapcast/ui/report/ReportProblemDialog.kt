package app.snapcast.ui.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.snapcast.core.report.ProblemKind
import app.snapcast.core.report.ReportEnvironment
import app.snapcast.core.report.SiteReport
import app.snapcast.ui.R
import org.mozilla.geckoview.GeckoSession

/** Where site problems are filed (the issue form lives in .github/ISSUE_TEMPLATE/site-problem.yml). */
private const val ISSUE_REPOSITORY = "Zoryc/Snapcast"

/**
 * "Report a site problem": the user picks what went wrong and may describe it; the report (previewable, see
 * [SiteReport] for what it contains and never contains) is only sent when the user opens it on GitHub or shares
 * it. [buildReport] returns null when no report can be made (home page, private tab).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReportProblemDialog(
    siteHost: String,
    buildReport: (kind: ProblemKind, details: String, includePageUrl: Boolean, environment: ReportEnvironment) -> SiteReport?,
    onOpenInApp: (url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val environment = remember(context) { reportEnvironment(context) }
    var kind by rememberSaveable { mutableStateOf<ProblemKind?>(null) }
    var details by rememberSaveable { mutableStateOf("") }
    var includePageUrl by rememberSaveable { mutableStateOf(false) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    val report = kind?.let { buildReport(it, details, includePageUrl, environment) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_report_title, siteHost)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.ui_report_question), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProblemKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(stringResource(option.labelRes())) },
                        )
                    }
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(MAX_DETAILS) },
                    label = { Text(stringResource(R.string.ui_report_details)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The whole row is the consent toggle: tappable, and read as one checkbox with its label.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = includePageUrl, role = Role.Checkbox, onValueChange = { includePageUrl = it }),
                ) {
                    Checkbox(checked = includePageUrl, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.ui_report_include_url), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = stringResource(R.string.ui_report_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (report != null) {
                    TextButton(onClick = { showPreview = !showPreview }) {
                        Text(stringResource(if (showPreview) R.string.ui_report_hide_preview else R.string.ui_report_preview))
                    }
                    if (showPreview) {
                        Text(
                            text = report.body,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { report?.let { share(context, it) } }, enabled = report != null) {
                    Text(stringResource(R.string.ui_report_share))
                }
                Button(
                    onClick = {
                        val current = report ?: return@Button
                        val url = current.issueUrl(ISSUE_REPOSITORY)
                        if (!openExternally(context, url)) onOpenInApp(url)
                        onDismiss()
                    },
                    enabled = report != null,
                ) {
                    Text(stringResource(R.string.ui_report_open_github))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_video_dialog_cancel)) } },
    )
}

private const val MAX_DETAILS = 2_000

private fun ProblemKind.labelRes(): Int = when (this) {
    ProblemKind.NO_VIDEO -> R.string.ui_report_kind_no_video
    ProblemKind.WONT_PLAY -> R.string.ui_report_kind_wont_play
    ProblemKind.CAST_FAILS -> R.string.ui_report_kind_cast_fails
    ProblemKind.NO_THUMBNAIL -> R.string.ui_report_kind_no_thumbnail
    ProblemKind.WRONG_QUALITY -> R.string.ui_report_kind_wrong_quality
    ProblemKind.OTHER -> R.string.ui_report_kind_other
}

/** App, engine, Android and device versions; nothing that identifies the user. */
private fun reportEnvironment(context: Context): ReportEnvironment {
    val appVersion = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
    val engine = Regex("""Firefox/([\d.]+)""").find(GeckoSession.getDefaultUserAgent())?.groupValues?.get(1) ?: "?"
    return ReportEnvironment(
        appVersion = appVersion,
        engineVersion = engine,
        androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
    )
}

private fun share(context: Context, report: SiteReport) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Snapcast: ${report.title}")
        .putExtra(Intent.EXTRA_TEXT, report.body)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: ActivityNotFoundException) {
        // Nothing can share text: the GitHub button still works.
    }
}

/** Opens [url] in the user's browser or the GitHub app; false when nothing can (it then opens in a tab here). */
private fun openExternally(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (e: ActivityNotFoundException) {
    false
}
