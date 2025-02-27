package org.schabi.newpipe.fragments.list.comments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.util.Constants;

import java.io.IOException;
import java.util.Objects;

import icepick.State;

public class CommentsFragmentContainer extends BaseFragment {

    @State
    protected int serviceId = Constants.NO_SERVICE_ID;
    @State
    protected String url;
    @State
    protected String name;

    public static CommentsFragmentContainer getInstance(
            final int serviceId, final String url, final String name) {
        final CommentsFragmentContainer fragment = new CommentsFragmentContainer();
        fragment.serviceId = serviceId;
        fragment.url = url;
        fragment.name = name;
        return fragment;
    }

    @Override
    public View onCreateView(
            final LayoutInflater inflater, @Nullable final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_container, container, false);
        // Only set the fragment if this is not a configuration change (like rotation)
        // or if the fragment doesn't exist yet
        if (savedInstanceState == null) {
            setFragment(getFM(), serviceId, url, name);
        }
        return view;
    }

    public void update(final int serviceId, final String url, final String name) {
        if (this.serviceId == serviceId
                && Objects.equals(this.url, url)
                && Objects.equals(this.name, name)) {
            return; // 参数未变化时跳过更新
        }
        this.serviceId = serviceId;
        this.url = url;
        this.name = name;
        setFragment(getFM(), serviceId, url, name);
    }

    public static void setFragment(
            FragmentManager fm,
            int sid, String u, String title) {

        if (fm == null) {
            return;
        }

        Fragment existing = fm.findFragmentById(R.id.fragment_container_view);
        if (existing instanceof CommentsFragment) {
            CommentsFragment cf = (CommentsFragment) existing;
            if (cf.getServiceId() == sid && cf.getUrl().equals(u) && cf.getName().equals(title)) {
                return; // 已有相同参数的Fragment存在
            }
        }

        CommentsFragment fragment = CommentsFragment.getInstance(sid, u, title);
        fm.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.fragment_container_view, fragment)
                .commitAllowingStateLoss();
    }

    public static void setFragment(
            final FragmentManager fm, final CommentsInfoItem comment
    ) throws IOException, ClassNotFoundException {
        final Page reply = comment.getReplies();
        final CommentReplyFragment fragment = CommentReplyFragment.getInstance(
                comment.getServiceId(), comment.getUrl(),
                comment.getName(), comment, reply
        );
        fm.beginTransaction()
                .replace(R.id.fragment_container_view, fragment)
                .addToBackStack(null)
                .commit();
    }
}