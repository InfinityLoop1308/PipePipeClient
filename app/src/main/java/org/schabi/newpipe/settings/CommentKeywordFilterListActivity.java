package org.schabi.newpipe.settings;

import org.schabi.newpipe.R;

public class CommentKeywordFilterListActivity extends BaseFilterListActivity {
    @Override
    protected String getPreferenceKey() {
        return getString(R.string.comment_filter_by_keyword_key) + "_set";
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
        return getString(R.string.filter_by_keyword_title);
    }

    @Override
    protected boolean supportsRegex() {
        return true;
    }
}
