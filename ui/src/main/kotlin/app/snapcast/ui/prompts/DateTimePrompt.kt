package app.snapcast.ui.prompts

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.snapcast.browser.BrowserPrompt
import app.snapcast.ui.R
import app.snapcast.ui.util.HtmlDateTime
import java.time.LocalDate
import java.time.LocalTime

/**
 * Date/time inputs with the Material 3 pickers. Dates (also months and weeks, which are derived from
 * the picked day) use the date picker, times the time picker; datetime-local shows both in sequence.
 * Values are converted to the HTML formats by [HtmlDateTime].
 */
@Composable
internal fun DateTimePrompt(prompt: BrowserPrompt.DateTime) {
    val once = rememberOnce(prompt.id)
    val initial = remember(prompt.id) { HtmlDateTime.parse(prompt.type, prompt.defaultValue) }
    // Epoch day of the picked date while the time step of datetime-local is shown.
    var pickedEpochDay by rememberSaveable(prompt.id) { mutableStateOf<Long?>(null) }

    val cancel = { once { prompt.onResult(null) } }
    val clear = { once { prompt.onResult("") } }
    val finish: (LocalDate?, LocalTime?) -> Unit = { date, time ->
        val value = HtmlDateTime.format(prompt.type, date, time)
        once { prompt.onResult(value) }
    }

    val needsDate = HtmlDateTime.needsDate(prompt.type)
    val needsTime = HtmlDateTime.needsTime(prompt.type)
    val currentPicked = pickedEpochDay
    if (needsDate && currentPicked == null) {
        DateStep(
            prompt = prompt,
            initialDate = initial.date ?: LocalDate.now(),
            onCancel = cancel,
            onClear = clear,
            onPicked = { date ->
                if (needsTime) pickedEpochDay = date.toEpochDay() else finish(date, null)
            },
        )
    } else {
        TimeStep(
            initialTime = initial.time ?: LocalTime.now().withSecond(0).withNano(0),
            onCancel = cancel,
            onClear = clear,
            onPicked = { time -> finish(currentPicked?.let(LocalDate::ofEpochDay), time) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateStep(
    prompt: BrowserPrompt.DateTime,
    initialDate: LocalDate,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    val selectable = remember(prompt.id) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = HtmlDateTime.isDateAllowed(
                type = prompt.type,
                date = HtmlDateTime.fromUtcMillis(utcTimeMillis),
                min = prompt.minValue,
                max = prompt.maxValue,
            )
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = HtmlDateTime.toUtcMillis(initialDate),
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPicked(HtmlDateTime.fromUtcMillis(it)) } },
                enabled = state.selectedDateMillis != null,
            ) {
                Text(stringResource(R.string.ui_prompt_ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text(stringResource(R.string.ui_prompt_clear)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.ui_prompt_cancel)) }
            }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeStep(
    initialTime: LocalTime,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onPicked: (LocalTime) -> Unit,
) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_prompt_time_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                TimePicker(state = state)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.ui_prompt_clear)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.ui_prompt_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)) }) {
                        Text(stringResource(R.string.ui_prompt_ok))
                    }
                }
            }
        }
    }
}
