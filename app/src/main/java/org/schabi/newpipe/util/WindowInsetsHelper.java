package org.schabi.newpipe.util;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public final class WindowInsetsHelper {

    private WindowInsetsHelper() {
    }

    public static void applyStatusBarInsets(@NonNull final AppCompatActivity activity,
                                            @NonNull final Toolbar toolbar) {
        final int initialLeft = toolbar.getPaddingLeft();
        final int initialTop = toolbar.getPaddingTop();
        final int initialRight = toolbar.getPaddingRight();
        final int initialBottom = toolbar.getPaddingBottom();
        final int actionBarSize = getActionBarSize(activity);

        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (view, insets) -> {
            final Insets statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            view.setPadding(initialLeft, initialTop + statusBars.top, initialRight, initialBottom);

            final ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            layoutParams.height = actionBarSize + initialTop + statusBars.top + initialBottom;
            view.setLayoutParams(layoutParams);

            return insets;
        });
    }

    public static void applyStatusBarInsets(@NonNull final AppCompatActivity activity,
                                            @NonNull final Toolbar toolbar,
                                            @NonNull final View content) {
        final ViewGroup.MarginLayoutParams layoutParams =
                (ViewGroup.MarginLayoutParams) content.getLayoutParams();
        final int initialTopMargin = layoutParams.topMargin;

        applyStatusBarInsets(activity, toolbar, insets -> {
            layoutParams.topMargin = initialTopMargin + insets.top;
            content.setLayoutParams(layoutParams);
        });
    }

    public static void applyStatusBarInsets(@NonNull final AppCompatActivity activity,
                                            @NonNull final Toolbar toolbar,
                                            @NonNull final InsetsConsumer insetsConsumer) {
        final int initialLeft = toolbar.getPaddingLeft();
        final int initialTop = toolbar.getPaddingTop();
        final int initialRight = toolbar.getPaddingRight();
        final int initialBottom = toolbar.getPaddingBottom();
        final int actionBarSize = getActionBarSize(activity);

        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (view, insets) -> {
            final Insets statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            view.setPadding(initialLeft, initialTop + statusBars.top, initialRight, initialBottom);

            final ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            layoutParams.height = actionBarSize + initialTop + statusBars.top + initialBottom;
            view.setLayoutParams(layoutParams);

            insetsConsumer.accept(statusBars);
            return insets;
        });
    }

    private static int getActionBarSize(@NonNull final AppCompatActivity activity) {
        final TypedValue typedValue = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
            return TypedValue.complexToDimensionPixelSize(
                    typedValue.data, activity.getResources().getDisplayMetrics());
        }
        return 0;
    }

    public interface InsetsConsumer {
        void accept(@NonNull Insets insets);
    }
}
