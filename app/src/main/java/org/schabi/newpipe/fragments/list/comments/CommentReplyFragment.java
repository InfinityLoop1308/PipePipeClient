package org.schabi.newpipe.fragments.list.comments;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;
import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.fragments.BackPressable;
import org.schabi.newpipe.util.Constants;

import java.io.IOException;

public class CommentReplyFragment extends BaseFragment implements BackPressable {

    protected int serviceId = Constants.NO_SERVICE_ID;
    protected String name;
    protected String url;
    protected CommentsInfoItem comment;
    protected Page replies;

    public static CommentReplyFragment getInstance(
            final int serviceId, final String url,
            final String name,
            final CommentsInfoItem comment,
            final Page replies
    ) throws IOException, ClassNotFoundException {
        final CommentReplyFragment instance = new CommentReplyFragment();
        instance.setInitialData(serviceId, url, name, comment, replies);
        return instance;
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("serviceId", serviceId);
        outState.putString("name", name);
        outState.putString("url", url);
        outState.putSerializable("comment", comment);
        outState.putSerializable("replies", replies);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        serviceId = savedInstanceState.getInt("serviceId", Constants.NO_SERVICE_ID);
        name = savedInstanceState.getString("name");
        url = savedInstanceState.getString("url");
        comment = (CommentsInfoItem) savedInstanceState.getSerializable("comment");
        replies = (Page) savedInstanceState.getSerializable("replies");
    }

    public static CommentsFragmentContainer newInstance(final int serviceId, final String url,
                                                        final String name) {
        final CommentsFragmentContainer fragment = new CommentsFragmentContainer();
        fragment.serviceId = serviceId;
        fragment.url = url;
        fragment.name = name;
        return new CommentsFragmentContainer();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_comments_reply, container,
                false);
        final ImageButton backButton = view.findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> onBackPressed());

        final CommentsFragment commentsFragment = CommentsFragment.getInstance(
                serviceId, url, name, comment
        );
        getChildFragmentManager().beginTransaction()
                .add(R.id.commentFragment, commentsFragment).commit();

        int marginStart = getResources().getDimensionPixelSize(R.dimen.video_item_search_avatar_left_margin);
        FragmentContainerView commentReplyFragment = view.findViewById(R.id.commentReplyFragment);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) commentReplyFragment.getLayoutParams();
        params.setMarginStart(marginStart);
        commentReplyFragment.setLayoutParams(params);

        final CommentsFragment commentsReplyFragment = CommentsFragment.getInstance(
                serviceId, url, name, replies
        );
        getChildFragmentManager().beginTransaction()
                .add(R.id.commentReplyFragment, commentsReplyFragment).commit();
        return view;
    }

    protected void setInitialData(final int sid, final String u, final String title,
                                  final CommentsInfoItem preComment,
                                  final Page repliesPage
    ) throws IOException, ClassNotFoundException {
        this.serviceId = sid;
        this.url = u;
        this.name = !TextUtils.isEmpty(title) ? title : "";
        // clone comment object to avoid replies actually set null
        this.comment = CommentUtils.clone(preComment);
        comment.setReplies(null);
        this.replies = repliesPage;
    }

    @Override
    public boolean onBackPressed() {
        final FragmentManager fm = getFM();
        fm.popBackStack();
        return true;
    }
}
