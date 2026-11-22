package org.schabi.newpipe.fragments.list.comments

import android.view.View
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.info_list.PipePipeComposeTheme
import java.util.function.IntConsumer

/** Displays the sort orders advertised by the comment service in a single-choice segmented button row. */
class CommentsSortChips(
    private val view: ComposeView,
    private val onSortSelected: IntConsumer,
) {
    private var orders by mutableStateOf<List<String>>(emptyList())
    private var selected by mutableIntStateOf(-1)

    init {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent {
            PipePipeComposeTheme(view.context) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        orders.forEachIndexed { index, title ->
                            SegmentedButton(
                                selected = index == selected,
                                onClick = {
                                    if (index != selected) {
                                        selected = index
                                        onSortSelected.accept(index)
                                    }
                                },
                                label = { Text(title, fontSize = 12.sp, lineHeight = 16.sp) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = orders.size,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun update(info: CommentsInfo) {
        orders = info.sortOrders.map { title ->
            translatedTitle(title)
        }
        selected = info.sortOrder
        view.visibility = if (!info.isCommentsDisabled && orders.size > 1 &&
            selected in orders.indices
        ) View.VISIBLE else View.GONE
    }

    private fun translatedTitle(title: String): String = when (title) {
        // YouTube requests use Zulu to preserve original video titles.
        "Phezulu", "Top", "Top comments" -> view.context.getString(R.string.comments_sort_top)
        "Okusha", "Newest", "Newest first" -> view.context.getString(R.string.newest_first)
        else -> title
    }
}
