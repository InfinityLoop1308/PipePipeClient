package org.schabi.newpipe.util;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.annotation.NonNull;

public final class StatusBarHelper {
    private static int portraitHeight;
    private static int landscapeHeight;

    private StatusBarHelper() {
    }

    public static void init(@NonNull final Context context) {
        final Resources resources = context.getResources();
        portraitHeight = getInternalDimensionPixelSize(resources, "status_bar_height_portrait");
        landscapeHeight = getInternalDimensionPixelSize(resources, "status_bar_height_landscape");

        final int fallbackHeight = getInternalDimensionPixelSize(resources, "status_bar_height");
        if (portraitHeight == 0) {
            portraitHeight = fallbackHeight;
        }
        if (landscapeHeight == 0) {
            landscapeHeight = fallbackHeight;
        }
    }

    public static int get(@NonNull final Context context) {
        return context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE ? landscapeHeight : portraitHeight;
    }

    private static int getInternalDimensionPixelSize(@NonNull final Resources resources,
                                                     @NonNull final String name) {
        final int resourceId = resources.getIdentifier(name, "dimen", "android");
        if (resourceId == 0) {
            return 0;
        }
        return resources.getDimensionPixelSize(resourceId);
    }
}
