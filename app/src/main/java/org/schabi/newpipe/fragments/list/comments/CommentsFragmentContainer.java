package org.schabi.newpipe.fragments.list.comments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.fragments.BackPressable;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.views.FocusAwareCoordinator;

import java.io.IOException;
import java.util.Objects;

public class CommentsFragmentContainer extends BaseFragment implements BackPressable {

    protected int serviceId = Constants.NO_SERVICE_ID;
    protected String url;
    protected String name;

    /** Adapter position of the comment whose replies were opened with the DPAD focus on it. */
    private int focusedCommentPosition = RecyclerView.NO_POSITION;

    public static CommentsFragmentContainer getInstance(
            final int serviceId, final String url, final String name) {
        final CommentsFragmentContainer fragment = new CommentsFragmentContainer();
        fragment.serviceId = serviceId;
        fragment.url = url;
        fragment.name = name;
        return fragment;
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("serviceId", serviceId);
        outState.putString("url", url);
        outState.putString("name", name);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        serviceId = savedInstanceState.getInt("serviceId", Constants.NO_SERVICE_ID);
        url = savedInstanceState.getString("url");
        name = savedInstanceState.getString("name");
    }

    @Override
    public View onCreateView(
            final LayoutInflater inflater, @Nullable final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_container, container, false);
        if (savedInstanceState == null) {
            setFragment(getChildFragmentManager(), serviceId, url, name);
        }
        return view;
    }

    public void update(final int serviceId, final String url, final String name) {
        if (!isAdded() || isRemoving()) {
            return;
        }
        if (this.serviceId == serviceId
                && Objects.equals(this.url, url)
                && Objects.equals(this.name, name)) {
            return;
        }
        this.serviceId = serviceId;
        this.url = url;
        this.name = name;
        setFragment(getChildFragmentManager(), serviceId, url, name);
    }

    @Override
    public boolean onBackPressed() {
        // Replies are pushed onto our OWN child FragmentManager (tied to this fragment's view), so
        // backing out of a reply pops here. When the view is gone (e.g. video sent to pop-up) the
        // child FM is torn down with it, so there's no stale entry to recreate -> no crash on back.
        if (!isAdded()) {
            return false;
        }
        final FragmentManager childFm = getChildFragmentManager();
        if (childFm.getBackStackEntryCount() > 0) {
            if (childFm.isStateSaved()) {
                childFm.popBackStack();
                return true;
            }
            final int position = focusedCommentPosition;
            focusedCommentPosition = RecyclerView.NO_POSITION;
            swapKeepingFocus(childFm::popBackStackImmediate, position);
            return true;
        }
        return false;
    }

    /** Shows the replies of {@code comment} instead of the comments. */
    public void openReplies(final CommentsInfoItem comment)
            throws IOException, ClassNotFoundException {
        final FragmentManager childFm = getChildFragmentManager();
        if (!isAdded() || childFm.isStateSaved()) {
            return;
        }
        final View root = getView();
        if (root != null && root.hasFocus()) {
            final RecyclerView list = root.findViewById(R.id.items_list);
            final View focused = root.findFocus();
            final View item = list == null || focused == null
                    ? null : list.findContainingItemView(focused);
            focusedCommentPosition = item == null
                    ? RecyclerView.NO_POSITION : list.getChildAdapterPosition(item);
        }
        final CommentReplyFragment fragment = CommentReplyFragment.getInstance(
                comment.getServiceId(), comment.getUrl(),
                comment.getName(), comment, comment.getReplies());
        swapKeepingFocus(() -> {
            childFm.beginTransaction()
                    .replace(R.id.fragment_container_view, fragment)
                    .addToBackStack(null)
                    .commit();
            childFm.executePendingTransactions();
        }, RecyclerView.NO_POSITION);
    }

    /**
     * Runs {@code swap}, which synchronously replaces the shown fragment, without losing the DPAD
     * focus: when the focused view is removed, Android focuses the first view of the window, the
     * thumbnail at the top of the detail page, and the page scrolls up there.
     *
     * @param swap          replaces the shown fragment
     * @param focusPosition adapter position of the item to focus in the list of the fragment
     *                      shown then, if possible
     */
    private void swapKeepingFocus(final Runnable swap, final int focusPosition) {
        final View root = getView();
        if (root == null || !root.hasFocus()) {
            swap.run();
            return;
        }
        final FocusAwareCoordinator coordinator = findFocusScroller(root);
        if (coordinator != null) {
            coordinator.setFocusScrollSuppressed(true);
        }
        // hold the focus here while the views are swapped
        root.setFocusable(true);
        root.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                v.setOnFocusChangeListener(null);
                v.setFocusable(false);
            }
        });
        root.requestFocus();
        swap.run();
        // move the focus into the new fragment's views once they are laid out
        root.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        root.getViewTreeObserver().removeOnPreDrawListener(this);
                        if (coordinator != null) {
                            coordinator.setFocusScrollSuppressed(false);
                        }
                        if (root.isFocused()) {
                            focusShownFragment(root, focusPosition);
                        }
                        return true;
                    }
                });
    }

    private static void focusShownFragment(final View root, final int position) {
        final RecyclerView list = root.findViewById(R.id.items_list);
        if (list != null && position != RecyclerView.NO_POSITION
                && list.getLayoutManager() != null) {
            final View item = list.getLayoutManager().findViewByPosition(position);
            if (item != null && item.requestFocus()) {
                return;
            }
        }
        final View container = root.findViewById(R.id.fragment_container_view);
        if (container != null) {
            container.requestFocus(View.FOCUS_DOWN);
        }
    }

    @Nullable
    private static FocusAwareCoordinator findFocusScroller(final View view) {
        ViewParent parent = view.getParent();
        while (parent != null && !(parent instanceof FocusAwareCoordinator)) {
            parent = parent.getParent();
        }
        return (FocusAwareCoordinator) parent;
    }

    public static void setFragment(
            FragmentManager fm,
            int sid, String u, String title) {

        if (fm == null) {
            return;
        }

        if (fm.isStateSaved()) {
            return;
        }

        Fragment existing = fm.findFragmentById(R.id.fragment_container_view);
        if (existing instanceof CommentsFragment) {
            CommentsFragment cf = (CommentsFragment) existing;
            if (cf.getServiceId() == sid && cf.getUrl().equals(u) && cf.getName().equals(title)) {
                return;
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
        if (fm == null || fm.isStateSaved()) {
            return;
        }
        final Page reply = comment.getReplies();
        final CommentReplyFragment fragment = CommentReplyFragment.getInstance(
                comment.getServiceId(), comment.getUrl(),
                comment.getName(), comment, reply
        );
        fm.beginTransaction()
                .replace(R.id.fragment_container_view, fragment)
                .addToBackStack(null)
                .commitAllowingStateLoss();
    }
}
