package org.schabi.newpipe.views;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.SurfaceView;

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import static com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
import static com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM;

public class ExpandableSurfaceView extends SurfaceView {
    private int resizeMode = RESIZE_MODE_FIT;
    private int baseHeight = 0;
    private int maxHeight = 0;
    private float videoAspectRatio = 0.0f;
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;
    private float pinchScale = 1.0f;
    private float pinchTranslationX = 0.0f;
    private float pinchTranslationY = 0.0f;
    private float lastPinchFocusX = Float.NaN;
    private float lastPinchFocusY = Float.NaN;

    public ExpandableSurfaceView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (videoAspectRatio == 0.0f) {
            return;
        }

        int width = MeasureSpec.getSize(widthMeasureSpec);
        final boolean verticalVideo = videoAspectRatio < 1;
        // Use maxHeight only on non-fit resize mode and in vertical videos
        int height = maxHeight != 0
                && resizeMode != RESIZE_MODE_FIT
                && verticalVideo ? maxHeight : baseHeight;

        if (height == 0) {
            return;
        }

        final float viewAspectRatio = width / ((float) height);
        final float aspectDeformation = videoAspectRatio / viewAspectRatio - 1;
        scaleX = 1.0f;
        scaleY = 1.0f;

        // KitKat doesn't work well when a view has a scale like needed for ZOOM
        if (resizeMode == RESIZE_MODE_FIT) {
            if (aspectDeformation > 0) {
                height = (int) (width / videoAspectRatio);
            } else {
                width = (int) (height * videoAspectRatio);
            }
        } else if (resizeMode == RESIZE_MODE_ZOOM) {
            if (aspectDeformation < 0) {
                scaleY = viewAspectRatio / videoAspectRatio;
            } else {
                scaleX = videoAspectRatio / viewAspectRatio;
            }
        }

        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    /**
     * Scale view only in {@link #onLayout} to make transition for ZOOM mode as smooth as possible.
     */
    @Override
    protected void onLayout(final boolean changed,
                            final int left, final int top, final int right, final int bottom) {
        applyScaleAndTranslation();
    }

    private void applyScaleAndTranslation() {
        final float safePinchScale = Float.isFinite(pinchScale) ? pinchScale : 1.0f;
        final boolean pinchActive = safePinchScale > 1.0f;
        setPivotX(pinchActive ? 0.0f : getWidth() / 2.0f);
        setPivotY(pinchActive ? 0.0f : getHeight() / 2.0f);
        setScaleX((Float.isFinite(scaleX) ? scaleX : 1.0f) * safePinchScale);
        setScaleY((Float.isFinite(scaleY) ? scaleY : 1.0f) * safePinchScale);
        setTranslationX(pinchActive ? pinchTranslationX : 0.0f);
        setTranslationY(pinchActive ? pinchTranslationY : 0.0f);
    }

    /**
     * @param base The height that will be used in every resize mode as a minimum height
     * @param max  The max height for vertical videos in non-FIT resize modes
     */
    public void setHeights(final int base, final int max) {
        if (baseHeight == base && maxHeight == max) {
            return;
        }
        baseHeight = base;
        maxHeight = max;
        requestLayout();
    }

    public void setResizeMode(@AspectRatioFrameLayout.ResizeMode final int newResizeMode) {
        if (resizeMode == newResizeMode) {
            return;
        }

        resizeMode = newResizeMode;
        requestLayout();
    }

    @AspectRatioFrameLayout.ResizeMode
    public int getResizeMode() {
        return resizeMode;
    }

    public void beginPinchGesture(final float focusX, final float focusY) {
        lastPinchFocusX = focusX;
        lastPinchFocusY = focusY;
    }

    public void setPinchScale(final float newScale, final float focusX, final float focusY) {
        final float oldScale = pinchScale;
        pinchScale = Math.max(1.0f, Math.min(newScale, 8.0f));
        final float scaleChange = pinchScale / oldScale;

        if (Float.isFinite(lastPinchFocusX) && Float.isFinite(lastPinchFocusY)) {
            // Keep the content that was under the fingers anchored while also following their
            // midpoint. This avoids the inverted, jumpy motion caused by changing View pivots.
            pinchTranslationX = focusX
                    - (lastPinchFocusX - pinchTranslationX) * scaleChange;
            pinchTranslationY = focusY
                    - (lastPinchFocusY - pinchTranslationY) * scaleChange;
        }
        lastPinchFocusX = focusX;
        lastPinchFocusY = focusY;

        pinchTranslationX = Math.max(getWidth() * (1.0f - pinchScale),
                Math.min(0.0f, pinchTranslationX));
        pinchTranslationY = Math.max(getHeight() * (1.0f - pinchScale),
                Math.min(0.0f, pinchTranslationY));
        applyScaleAndTranslation();
    }

    public float getPinchScale() {
        return pinchScale;
    }

    public void resetPinchScale() {
        pinchScale = 1.0f;
        pinchTranslationX = 0.0f;
        pinchTranslationY = 0.0f;
        lastPinchFocusX = Float.NaN;
        lastPinchFocusY = Float.NaN;
        applyScaleAndTranslation();
    }

    public void setAspectRatio(final float aspectRatio) {
        // A 0x0 / not-yet-known video gives NaN (or Infinity) here; keep it as "no ratio" (0) so the
        // measure path skips scaling instead of pushing NaN into setScaleX, which throws
        // IllegalArgumentException and crashes the player during layout (#2515).
        final float sanitized = Float.isFinite(aspectRatio) ? aspectRatio : 0.0f;
        if (videoAspectRatio == sanitized) {
            return;
        }

        videoAspectRatio = sanitized;
        requestLayout();
    }
}
