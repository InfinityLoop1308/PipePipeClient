package org.schabi.newpipe.local.history

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.util.ThemeHelper
import java.time.LocalDate
import java.time.DateTimeException

/**
 * Filters the watch history with a start and an end date, both typed as yyyy-MM-dd.
 * An empty field means unbounded on that side.
 */
class HistoryDateFilterDialog : DialogFragment() {

    interface Callback {
        /** Both values are null when the filter was cleared. Dates are inclusive. */
        fun onHistoryDateRangeSelected(start: LocalDate?, end: LocalDate?)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), theme)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = requireArguments()
        val initialStart = args.getString(ARG_START).orEmpty()
        val initialEnd = args.getString(ARG_END).orEmpty()
        val hasFilter = initialStart.isNotEmpty() || initialEnd.isNotEmpty()
        val callback = parentFragment as? Callback ?: activity as? Callback
        val isLight = ThemeHelper.isLightThemeSelected(requireContext())

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(colorScheme = if (isLight) lightColorScheme() else darkColorScheme()) {
                    var startText by rememberSaveable { mutableStateOf(initialStart) }
                    var endText by rememberSaveable { mutableStateOf(initialEnd) }

                    val start = parseDate(startText)
                    val end = parseDate(endText)
                    val startInvalid = startText.isNotBlank() && start == null
                    val endInvalid = endText.isNotBlank() && end == null
                    val reversed = start != null && end != null && start.isAfter(end)
                    val canApply = !startInvalid && !endInvalid && !reversed &&
                        (startText.isNotBlank() || endText.isNotBlank())

                    Surface(shape = MaterialTheme.shapes.large) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                stringResource(R.string.history_filter_by_date),
                                style = MaterialTheme.typography.titleLarge
                            )
                            DateField(
                                value = startText,
                                onValueChange = { startText = it },
                                label = stringResource(R.string.history_filter_start_date),
                                isError = startInvalid || reversed
                            )
                            DateField(
                                value = endText,
                                onValueChange = { endText = it },
                                label = stringResource(R.string.history_filter_end_date),
                                isError = endInvalid || reversed
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (hasFilter) {
                                    TextButton(onClick = {
                                        callback?.onHistoryDateRangeSelected(null, null)
                                        dismiss()
                                    }) { Text(stringResource(R.string.history_clear_date_filter)) }
                                }
                                TextButton(onClick = { dismiss() }) {
                                    Text(stringResource(R.string.cancel))
                                }
                                TextButton(
                                    enabled = canApply,
                                    onClick = {
                                        callback?.onHistoryDateRangeSelected(start, end)
                                        dismiss()
                                    }
                                ) { Text(stringResource(android.R.string.ok)) }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "HistoryDateFilterDialog"
        private const val ARG_START = "start"
        private const val ARG_END = "end"
        private const val DATE_HINT = "2024-03-15  /  20240315  /  0315"

        /**
         * Lenient date parser. Accepts any non-digit separator and these shapes:
         * - 20240315, 2024-3-15, 2024/03/15, 2024.3.15, 24-3-15 (2 digit year means 20xx)
         * - 0315, 3-15, 3/15 (no year: current year, or last year if that would be in the future)
         * Returns null if the text is not a real calendar date.
         */
        internal fun parseDate(text: String, today: LocalDate = LocalDate.now()): LocalDate? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split(Regex("\\D+")).filter { it.isNotEmpty() }
            val (year, month, day) = when {
                parts.size == 1 && parts[0].length == 8 && trimmed.all { it.isDigit() } ->
                    Triple(parts[0].substring(0, 4), parts[0].substring(4, 6), parts[0].substring(6))
                parts.size == 1 && parts[0].length == 6 && trimmed.all { it.isDigit() } ->
                    Triple(parts[0].substring(0, 2), parts[0].substring(2, 4), parts[0].substring(4))
                parts.size == 1 && parts[0].length == 4 && trimmed.all { it.isDigit() } ->
                    Triple(null, parts[0].substring(0, 2), parts[0].substring(2))
                parts.size == 2 -> Triple(null, parts[0], parts[1])
                parts.size == 3 -> Triple(parts[0], parts[1], parts[2])
                else -> return null
            }
            val m = month.toIntOrNull() ?: return null
            val d = day.toIntOrNull() ?: return null
            return try {
                if (year != null) {
                    val y = year.toIntOrNull() ?: return null
                    LocalDate.of(if (year.length <= 2) 2000 + y else y, m, d)
                } else {
                    val candidate = LocalDate.of(today.year, m, d)
                    if (candidate.isAfter(today)) LocalDate.of(today.year - 1, m, d) else candidate
                }
            } catch (e: DateTimeException) {
                null
            }
        }

        @JvmStatic
        fun newInstance(start: LocalDate?, end: LocalDate?): HistoryDateFilterDialog {
            return HistoryDateFilterDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_START, start?.toString())
                    putString(ARG_END, end?.toString())
                }
            }
        }

        @androidx.compose.runtime.Composable
        private fun DateField(
            value: String,
            onValueChange: (String) -> Unit,
            label: String,
            isError: Boolean
        ) {
            val resolved = parseDate(value)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                placeholder = { Text(DATE_HINT) },
                supportingText = {
                    when {
                        resolved != null -> Text("= $resolved")
                        value.isNotBlank() -> Text(stringResource(R.string.history_filter_invalid_date))
                    }
                },
                singleLine = true,
                isError = isError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
        }
    }
}
