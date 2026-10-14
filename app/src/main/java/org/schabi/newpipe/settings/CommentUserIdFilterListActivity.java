package org.schabi.newpipe.settings;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.utils.ServiceScopedId;

public class CommentUserIdFilterListActivity extends BaseFilterListActivity {
    @Override
    protected String getPreferenceKey() {
        return getString(R.string.comment_filter_by_user_id_key) + "_set";
    }

    @Override
    protected String getEmptyViewText() {
        return getString(R.string.no_filters);
    }

    @Override
    protected String getAddDialogTitle() {
        return getString(R.string.add_filter);
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.filter_by_channel_id_title);
    }

    @Override
    protected String normalizeEntry(final String input) {
        return ServiceScopedId.normalize(input);
    }

    @Override
    protected String getInvalidEntryMessage() {
        return getString(R.string.filter_id_invalid_format);
    }
}
