package org.schabi.newpipe.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.SurfaceView;
import android.view.animation.DecelerateInterpolator;

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
    private ValueAnimator resetAnimator = null;

    private static final float PINCH_MIN_SCALE = 0.5f;
    private static final float PINCH_MAX_SCALE = 8.0f;
    private static final float PINCH_BELOW_BASE_DAMPING = 0.5f;
    private static final long PINCH_RESET_DURATION_MS = 220L;

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
        final float displayScale = displayedPinchScale();
        final float totalScaleX = (Float.isFinite(scaleX) ? scaleX : 1.0f) * displayScale;
        final float totalScaleY = (Float.isFinite(scaleY) ? scaleY : 1.0f) * displayScale;
        setPivotX(getWidth() / 2.0f);
        setPivotY(getHeight() / 2.0f);
        setScaleX(totalScaleX);
        setScaleY(totalScaleY);
        setTranslationX(pinchTranslationX);
        setTranslationY(pinchTranslationY);
    }

    /**
     * The scale actually drawn: above 1.0 it follows the fingers 1:1, below 1.0 it is damped so
     * that pulling under the base state feels like stretching a rubber band.
     */
    private float displayedPinchScale() {
        final float raw = Float.isFinite(pinchScale) ? pinchScale : 1.0f;
        return raw >= 1.0f ? raw : 1.0f - (1.0f - raw) * PINCH_BELOW_BASE_DAMPING;
    }

    private void clampPinchTranslation() {
        final float displayScale = displayedPinchScale();
        final float overflowX = getWidth() * (Float.isFinite(scaleX) ? scaleX : 1.0f)
                * displayScale - getWidth();
        final float overflowY = getHeight() * (Float.isFinite(scaleY) ? scaleY : 1.0f)
                * displayScale - getHeight();
        // At or below the base state there is nothing to pan.
        final float maxX = displayScale > 1.0f ? Math.max(0.0f, overflowX / 2.0f) : 0.0f;
        final float maxY = displayScale > 1.0f ? Math.max(0.0f, overflowY / 2.0f) : 0.0f;
        pinchTranslationX = Math.max(-maxX, Math.min(maxX, pinchTranslationX));
        pinchTranslationY = Math.max(-maxY, Math.min(maxY, pinchTranslationY));
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
        cancelPinchReset();
        lastPinchFocusX = focusX;
        lastPinchFocusY = focusY;
    }

    /**
     * @param newScale the raw scale relative to the current resize mode (1.0 = base state)
     */
    public void setPinchScale(final float newScale, final float focusX, final float focusY) {
        final float oldDisplayScale = displayedPinchScale();
        pinchScale = Math.max(PINCH_MIN_SCALE, Math.min(newScale, PINCH_MAX_SCALE));
        final float scaleChange = displayedPinchScale() / oldDisplayScale;

        if (Float.isFinite(lastPinchFocusX) && Float.isFinite(lastPinchFocusY)) {
            // The view pivots around its center: keep the content that was under the fingers
            // anchored while also following their midpoint.
            final float centerX = getWidth() / 2.0f;
            final float centerY = getHeight() / 2.0f;
            pinchTranslationX = focusX - centerX
                    - (lastPinchFocusX - centerX - pinchTranslationX) * scaleChange;
            pinchTranslationY = focusY - centerY
                    - (lastPinchFocusY - centerY - pinchTranslationY) * scaleChange;
        }
        lastPinchFocusX = focusX;
        lastPinchFocusY = focusY;

        clampPinchTranslation();
        applyScaleAndTranslation();
    }

    /** Moves the zoomed viewport by the given amount of pixels. No-op unless zoomed in. */
    public void panBy(final float dx, final float dy) {
        if (!isPinchZoomed()) {
            return;
        }
        pinchTranslationX += dx;
        pinchTranslationY += dy;
        clampPinchTranslation();
        applyScaleAndTranslation();
    }

    /** The raw pinch scale, 1.0 being the state before zooming (Fit, Fill, ...). */
    public float getPinchScale() {
        return pinchScale;
    }

    /** True while zoomed in beyond the base state and not already springing back. */
    public boolean isPinchZoomed() {
        return resetAnimator == null && pinchScale > 1.0f;
    }

    public void resetPinchScale() {
        cancelPinchReset();
        pinchScale = 1.0f;
        pinchTranslationX = 0.0f;
        pinchTranslationY = 0.0f;
        lastPinchFocusX = Float.NaN;
        lastPinchFocusY = Float.NaN;
        applyScaleAndTranslation();
    }

    /** Springs back to the base state (scale 1.0, no pan) with a short animation. */
    public void animatePinchReset() {
        cancelPinchReset();
        lastPinchFocusX = Float.NaN;
        lastPinchFocusY = Float.NaN;
        final float startScale = pinchScale;
        final float startX = pinchTranslationX;
        final float startY = pinchTranslationY;
        if (startScale == 1.0f && startX == 0.0f && startY == 0.0f) {
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
        animator.setDuration(PINCH_RESET_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(2.0f));
        animator.addUpdateListener(a -> {
            final float f = (float) a.getAnimatedValue();
            pinchScale = startScale + (1.0f - startScale) * f;
            pinchTranslationX = startX * (1.0f - f);
            pinchTranslationY = startY * (1.0f - f);
            applyScaleAndTranslation();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(final Animator animation) {
                if (resetAnimator == animation) {
                    resetAnimator = null;
                    resetPinchScale();
                }
            }
        });
        resetAnimator = animator;
        animator.start();
    }

    private void cancelPinchReset() {
        if (resetAnimator != null) {
            final ValueAnimator animator = resetAnimator;
            resetAnimator = null;
            animator.cancel();
        }
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
