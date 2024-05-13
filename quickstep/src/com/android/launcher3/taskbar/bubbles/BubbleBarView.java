/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.taskbar.bubbles;

import static com.android.app.animation.Interpolators.EMPHASIZED_ACCELERATE;
import static com.android.launcher3.LauncherAnimUtils.VIEW_ALPHA;
import static com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_X;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.LayoutDirection;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.SpringForce;

import com.android.launcher3.R;
import com.android.launcher3.anim.SpringAnimationBuilder;
import com.android.launcher3.util.DisplayController;
import com.android.wm.shell.common.bubbles.BubbleBarLocation;

import java.util.List;
import java.util.function.Consumer;

/**
 * The view that holds all the bubble views. Modifying this view should happen through
 * {@link BubbleBarViewController}. Updates to the bubbles themselves (adds, removes, updates,
 * selection) should happen through {@link BubbleBarController} which is the source of truth
 * for state information about the bubbles.
 * <p>
 * The bubble bar has a couple of visual states:
 * - stashed as a handle
 * - unstashed but collapsed, in this state the bar is showing but the bubbles are stacked within it
 * - unstashed and expanded, in this state the bar is showing and the bubbles are shown in a row
 * with one of the bubbles being selected. Additionally, WMShell will display the expanded bubble
 * view above the bar.
 * <p>
 * The bubble bar has some behavior related to taskbar:
 * - When taskbar is unstashed, bubble bar will also become unstashed (but in its "collapsed"
 * state)
 * - When taskbar is stashed, bubble bar will also become stashed (unless bubble bar is in its
 * "expanded" state)
 * - When bubble bar is in its "expanded" state, taskbar becomes stashed
 * <p>
 * If there are no bubbles, the bubble bar and bubble stashed handle are not shown. Additionally
 * the bubble bar and stashed handle are not shown on lockscreen.
 * <p>
 * When taskbar is in persistent or 3 button nav mode, the bubble bar is not available, and instead
 * the bubbles are shown fully by WMShell in their floating mode.
 */
public class BubbleBarView extends FrameLayout {

    private static final String TAG = "BubbleBarView";

    // TODO: (b/273594744) calculate the amount of space we have and base the max on that
    //  if it's smaller than 5.
    private static final int MAX_BUBBLES = 5;
    private static final int MAX_VISIBLE_BUBBLES_COLLAPSED = 2;
    private static final int ARROW_POSITION_ANIMATION_DURATION_MS = 200;
    private static final int WIDTH_ANIMATION_DURATION_MS = 200;

    private static final long FADE_OUT_ANIM_ALPHA_DURATION_MS = 50L;
    private static final long FADE_OUT_ANIM_ALPHA_DELAY_MS = 50L;
    private static final long FADE_OUT_ANIM_POSITION_DURATION_MS = 100L;
    // During fade out animation we shift the bubble bar 1/80th of the screen width
    private static final float FADE_OUT_ANIM_POSITION_SHIFT = 1 / 80f;

    private static final long FADE_IN_ANIM_ALPHA_DURATION_MS = 100L;
    // Use STIFFNESS_MEDIUMLOW which is not defined in the API constants
    private static final float FADE_IN_ANIM_POSITION_SPRING_STIFFNESS = 400f;
    // During fade in animation we shift the bubble bar 1/60th of the screen width
    private static final float FADE_IN_ANIM_POSITION_SHIFT = 1 / 60f;

    /**
     * Custom property to set alpha value for the bar view while a bubble is being dragged.
     * Skips applying alpha to the dragged bubble.
     */
    private static final FloatProperty<BubbleBarView> BUBBLE_DRAG_ALPHA =
            new FloatProperty<>("bubbleDragAlpha") {
                @Override
                public void setValue(BubbleBarView bubbleBarView, float alpha) {
                    bubbleBarView.setAlphaDuringBubbleDrag(alpha);
                }

                @Override
                public Float get(BubbleBarView bubbleBarView) {
                    return bubbleBarView.mAlphaDuringDrag;
                }
            };

    private final BubbleBarBackground mBubbleBarBackground;

    private boolean mIsAnimatingNewBubble = false;

    /**
     * The current bounds of all the bubble bar. Note that these bounds may not account for
     * translation. The bounds should be retrieved using {@link #getBubbleBarBounds()} which
     * updates the bounds and accounts for translation.
     */
    private final Rect mBubbleBarBounds = new Rect();
    // The amount the bubbles overlap when they are stacked in the bubble bar
    private final float mIconOverlapAmount;
    // The spacing between the bubbles when bubble bar is expanded
    private final float mExpandedBarIconsSpacing;
    // The spacing between the bubbles and the borders of the bubble bar
    private float mBubbleBarPadding;
    // The size of a bubble in the bar
    private float mIconSize;
    // The elevation of the bubbles within the bar
    private final float mBubbleElevation;
    private final float mDragElevation;
    private final int mPointerSize;

    private final Rect mTempBackgroundBounds = new Rect();

    // Whether the bar is expanded (i.e. the bubble activity is being displayed).
    private boolean mIsBarExpanded = false;
    // The currently selected bubble view.
    @Nullable
    private BubbleView mSelectedBubbleView;
    private BubbleBarLocation mBubbleBarLocation = BubbleBarLocation.DEFAULT;
    // The click listener when the bubble bar is collapsed.
    private View.OnClickListener mOnClickListener;

    private final Rect mTempRect = new Rect();
    private float mRelativePivotX = 1f;
    private float mRelativePivotY = 1f;

    // An animator that represents the expansion state of the bubble bar, where 0 corresponds to the
    // collapsed state and 1 to the fully expanded state.
    private final ValueAnimator mWidthAnimator = ValueAnimator.ofFloat(0, 1);

    @Nullable
    private Animator mBubbleBarLocationAnimator = null;

    // We don't reorder the bubbles when they are expanded as it could be jarring for the user
    // this runnable will be populated with any reordering of the bubbles that should be applied
    // once they are collapsed.
    @Nullable
    private Runnable mReorderRunnable;

    @Nullable
    private Consumer<String> mUpdateSelectedBubbleAfterCollapse;

    private boolean mDragging;

    @Nullable
    private BubbleView mDraggedBubbleView;
    private float mAlphaDuringDrag = 1f;

    private Controller mController;

    private int mPreviousLayoutDirection = LayoutDirection.UNDEFINED;

    public BubbleBarView(Context context) {
        this(context, null);
    }

    public BubbleBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleBarView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setAlpha(0);
        setVisibility(INVISIBLE);
        mIconOverlapAmount = getResources().getDimensionPixelSize(R.dimen.bubblebar_icon_overlap);
        mBubbleBarPadding = getResources().getDimensionPixelSize(R.dimen.bubblebar_icon_spacing);
        mIconSize = getResources().getDimensionPixelSize(R.dimen.bubblebar_icon_size);
        mExpandedBarIconsSpacing = getResources().getDimensionPixelSize(
                R.dimen.bubblebar_expanded_icon_spacing);
        mBubbleElevation = getResources().getDimensionPixelSize(R.dimen.bubblebar_icon_elevation);
        mDragElevation = getResources().getDimensionPixelSize(R.dimen.bubblebar_drag_elevation);
        mPointerSize = getResources()
                .getDimensionPixelSize(R.dimen.bubblebar_pointer_visible_size);

        setClipToPadding(false);

        mBubbleBarBackground = new BubbleBarBackground(context, getBubbleBarExpandedHeight());
        setBackgroundDrawable(mBubbleBarBackground);

        mWidthAnimator.setDuration(WIDTH_ANIMATION_DURATION_MS);
        mWidthAnimator.addUpdateListener(animation -> {
            updateChildrenRenderNodeProperties(mBubbleBarLocation);
            invalidate();
        });
        mWidthAnimator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationCancel(Animator animation) {
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mBubbleBarBackground.showArrow(mIsBarExpanded);
                if (!mIsBarExpanded && mReorderRunnable != null) {
                    mReorderRunnable.run();
                    mReorderRunnable = null;
                }
                // If the bar was just collapsed and the overflow was the last bubble that was
                // selected, set the first bubble as selected.
                if (!mIsBarExpanded && mUpdateSelectedBubbleAfterCollapse != null
                        && mSelectedBubbleView != null
                        && mSelectedBubbleView.getBubble() instanceof BubbleBarOverflow) {
                    BubbleView firstBubble = (BubbleView) getChildAt(0);
                    mUpdateSelectedBubbleAfterCollapse.accept(firstBubble.getBubble().getKey());
                }
                updateWidth();
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
            }

            @Override
            public void onAnimationStart(Animator animation) {
                mBubbleBarBackground.showArrow(true);
            }
        });
    }

    @Override
    public void setTranslationX(float translationX) {
        super.setTranslationX(translationX);
        if (mDraggedBubbleView != null) {
            // Apply reverse of the translation as an offset to the dragged view. This ensures
            // that the dragged bubble stays at the current location on the screen and its
            // position is not affected by the parent translation.
            mDraggedBubbleView.setOffsetX(-translationX);
        }
    }

    /**
     * Sets new icon size and spacing between icons and bubble bar borders.
     *
     * @param newIconSize new icon size
     * @param spacing     spacing between icons and bubble bar borders.
     */
    // TODO(b/335575529): animate bubble bar icons size change
    public void setIconSizeAndPadding(float newIconSize, float spacing) {
        // TODO(b/335457839): handle new bubble animation during the size change
        mBubbleBarPadding = spacing;
        mIconSize = newIconSize;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View childView = getChildAt(i);
            FrameLayout.LayoutParams params = (LayoutParams) childView.getLayoutParams();
            params.height = (int) mIconSize;
            params.width = (int) mIconSize;
            childView.setLayoutParams(params);
        }
        mBubbleBarBackground.setHeight(getBubbleBarExpandedHeight());
        updateLayoutParams();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mBubbleBarBounds.left = left;
        mBubbleBarBounds.top = top + mPointerSize;
        mBubbleBarBounds.right = right;
        mBubbleBarBounds.bottom = bottom;

        // The bubble bar handle is aligned according to the relative pivot,
        // by default it's aligned to the bottom edge of the screen so scale towards that
        setPivotX(mRelativePivotX * getWidth());
        setPivotY(mRelativePivotY * getHeight());

        if (!mDragging) {
            // Position the views when not dragging
            updateChildrenRenderNodeProperties(mBubbleBarLocation);
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        if (mBubbleBarLocation == BubbleBarLocation.DEFAULT
                && mPreviousLayoutDirection != layoutDirection) {
            Log.d(TAG, "BubbleBar RTL properties changed, new layoutDirection=" + layoutDirection
                    + " previous layoutDirection=" + mPreviousLayoutDirection);
            mPreviousLayoutDirection = layoutDirection;
            onBubbleBarLocationChanged();
        }
    }

    @SuppressLint("RtlHardcoded")
    private void onBubbleBarLocationChanged() {
        final boolean onLeft = mBubbleBarLocation.isOnLeft(isLayoutRtl());
        mBubbleBarBackground.setAnchorLeft(onLeft);
        mRelativePivotX = onLeft ? 0f : 1f;
        LayoutParams lp = (LayoutParams) getLayoutParams();
        lp.gravity = Gravity.BOTTOM | (onLeft ? Gravity.LEFT : Gravity.RIGHT);
        setLayoutParams(lp); // triggers a relayout
    }

    /**
     * @return current {@link BubbleBarLocation}
     */
    public BubbleBarLocation getBubbleBarLocation() {
        return mBubbleBarLocation;
    }

    /**
     * Update {@link BubbleBarLocation}
     */
    public void setBubbleBarLocation(BubbleBarLocation bubbleBarLocation) {
        resetDragAnimation();
        if (bubbleBarLocation != mBubbleBarLocation) {
            mBubbleBarLocation = bubbleBarLocation;
            onBubbleBarLocationChanged();
        }
    }

    /**
     * Set whether this view is currently being dragged
     */
    public void setIsDragging(boolean dragging) {
        if (mDragging == dragging) {
            return;
        }
        mDragging = dragging;
        setElevation(dragging ? mDragElevation : mBubbleElevation);
        if (!mDragging) {
            // Relayout after dragging to ensure that the dragged bubble is positioned correctly
            requestLayout();
        }
    }

    /**
     * Get translation for bubble bar when drag is released and it needs to animate back to the
     * resting position.
     * Resting position is based on the supplied location. If the supplied location is different
     * from the internal location that was used during bubble bar layout, translation values are
     * calculated to position the bar at the desired location.
     *
     * @param initialTranslation initial bubble bar translation at the start of drag
     * @param location           desired location of the bubble bar when drag is released
     * @return point with x and y values representing translation on x and y-axis
     */
    public PointF getBubbleBarDragReleaseTranslation(PointF initialTranslation,
            BubbleBarLocation location) {
        float dragEndTranslationX = initialTranslation.x;
        if (getBubbleBarLocation().isOnLeft(isLayoutRtl()) != location.isOnLeft(isLayoutRtl())) {
            // Bubble bar is laid out on left or right side of the screen. And the desired new
            // location is on the other side. Calculate x translation value required to shift
            // bubble bar from one side to the other.
            final float shift = getDistanceFromOtherSide();
            if (location.isOnLeft(isLayoutRtl())) {
                // New location is on the left, shift left
                // before -> |......ooo.| after -> |.ooo......|
                dragEndTranslationX = -shift;
            } else {
                // New location is on the right, shift right
                // before -> |.ooo......| after -> |......ooo.|
                dragEndTranslationX = shift;
            }
        }
        return new PointF(dragEndTranslationX, mController.getBubbleBarTranslationY());
    }

    /**
     * Get translation for a bubble when drag is released and it needs to animate back to the
     * resting position.
     * Resting position is based on the supplied location. If the supplied location is different
     * from the internal location that was used during bubble bar layout, translation values are
     * calculated to position the bar at the desired location.
     *
     * @param initialTranslation initial bubble bar translation at the start of drag
     * @param location           desired location of the bubble bar when drag is released
     * @return point with x and y values representing translation on x and y-axis
     */
    public PointF getDraggedBubbleReleaseTranslation(PointF initialTranslation,
            BubbleBarLocation location) {
        // Start with bubble bar translation
        final PointF dragEndTranslation = new PointF(
                getBubbleBarDragReleaseTranslation(initialTranslation, location));
        // Apply individual bubble translation, as the order may have changed
        int viewIndex = indexOfChild(mDraggedBubbleView);
        dragEndTranslation.x += getExpandedBubbleTranslationX(viewIndex,
                getChildCount(),
                location.isOnLeft(isLayoutRtl()));
        return dragEndTranslation;
    }

    private float getDistanceFromOtherSide() {
        // Calculate the shift needed to position the bubble bar on the other side
        int displayWidth = getResources().getDisplayMetrics().widthPixels;
        int margin = 0;
        if (getLayoutParams() instanceof MarginLayoutParams lp) {
            margin += lp.leftMargin;
            margin += lp.rightMargin;
        }
        return (float) (displayWidth - getWidth() - margin);
    }

    /**
     * Animate bubble bar to the given location transiently. Does not modify the layout or the value
     * returned by {@link #getBubbleBarLocation()}.
     */
    public void animateToBubbleBarLocation(BubbleBarLocation bubbleBarLocation) {
        if (mBubbleBarLocationAnimator != null && mBubbleBarLocationAnimator.isRunning()) {
            mBubbleBarLocationAnimator.removeAllListeners();
            mBubbleBarLocationAnimator.cancel();
        }

        // Location animation uses two separate animators.
        // First animator hides the bar.
        // After it completes, bubble positions in the bar and arrow position is updated.
        // Second animator is started to show the bar.
        mBubbleBarLocationAnimator = getLocationUpdateFadeOutAnimator(bubbleBarLocation);
        mBubbleBarLocationAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                updateChildrenRenderNodeProperties(bubbleBarLocation);
                mBubbleBarBackground.setAnchorLeft(bubbleBarLocation.isOnLeft(isLayoutRtl()));

                // Animate it in
                mBubbleBarLocationAnimator = getLocationUpdateFadeInAnimator(bubbleBarLocation);
                mBubbleBarLocationAnimator.start();
            }
        });
        mBubbleBarLocationAnimator.start();
    }

    private Animator getLocationUpdateFadeOutAnimator(BubbleBarLocation newLocation) {
        final float shift =
                getResources().getDisplayMetrics().widthPixels * FADE_OUT_ANIM_POSITION_SHIFT;
        final boolean onLeft = newLocation.isOnLeft(isLayoutRtl());
        final float tx = getTranslationX() + (onLeft ? -shift : shift);

        ObjectAnimator positionAnim = ObjectAnimator.ofFloat(this, VIEW_TRANSLATE_X, tx)
                .setDuration(FADE_OUT_ANIM_POSITION_DURATION_MS);
        positionAnim.setInterpolator(EMPHASIZED_ACCELERATE);

        ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(this, getLocationAnimAlphaProperty(), 0f)
                .setDuration(FADE_OUT_ANIM_ALPHA_DURATION_MS);
        alphaAnim.setStartDelay(FADE_OUT_ANIM_ALPHA_DELAY_MS);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(positionAnim, alphaAnim);
        return animatorSet;
    }

    private Animator getLocationUpdateFadeInAnimator(BubbleBarLocation newLocation) {
        final float shift =
                getResources().getDisplayMetrics().widthPixels * FADE_IN_ANIM_POSITION_SHIFT;

        final boolean onLeft = newLocation.isOnLeft(isLayoutRtl());
        final float startTx;
        final float finalTx;
        if (newLocation == mBubbleBarLocation) {
            // Animated location matches layout location.
            finalTx = 0;
        } else {
            // We are animating in to a transient location, need to move the bar accordingly.
            finalTx = getDistanceFromOtherSide() * (onLeft ? -1 : 1);
        }
        if (onLeft) {
            // Bar will be shown on the left side. Start point is shifted right.
            startTx = finalTx + shift;
        } else {
            // Bar will be shown on the right side. Start point is shifted left.
            startTx = finalTx - shift;
        }

        ValueAnimator positionAnim = new SpringAnimationBuilder(getContext())
                .setStartValue(startTx)
                .setEndValue(finalTx)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                .setStiffness(FADE_IN_ANIM_POSITION_SPRING_STIFFNESS)
                .build(this, VIEW_TRANSLATE_X);

        ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(this, getLocationAnimAlphaProperty(), 1f)
                .setDuration(FADE_IN_ANIM_ALPHA_DURATION_MS);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(positionAnim, alphaAnim);
        return animatorSet;
    }

    /**
     * Get property that can be used to animate the alpha value for the bar.
     * When a bubble is being dragged, uses {@link #BUBBLE_DRAG_ALPHA}.
     * Falls back to {@link com.android.launcher3.LauncherAnimUtils#VIEW_ALPHA} otherwise.
     */
    private FloatProperty<? super BubbleBarView> getLocationAnimAlphaProperty() {
        return mDraggedBubbleView == null ? VIEW_ALPHA : BUBBLE_DRAG_ALPHA;
    }

    /**
     * Set alpha value for the bar while a bubble is being dragged.
     * We can not update the alpha on the bar directly because the dragged bubble would be affected
     * as well. As it is a child view.
     * Instead, while a bubble is being dragged, set alpha on each child view, that is not the
     * dragged view. And set an alpha on the background.
     * This allows for the dragged bubble to remain visible while the bar is hidden during
     * animation.
     */
    private void setAlphaDuringBubbleDrag(float alpha) {
        mAlphaDuringDrag = alpha;
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View view = getChildAt(i);
            if (view != mDraggedBubbleView) {
                view.setAlpha(alpha);
            }
        }
        if (mBubbleBarBackground != null) {
            mBubbleBarBackground.setAlpha((int) (255 * alpha));
        }
    }

    private void resetDragAnimation() {
        if (mBubbleBarLocationAnimator != null) {
            mBubbleBarLocationAnimator.removeAllListeners();
            mBubbleBarLocationAnimator.cancel();
            mBubbleBarLocationAnimator = null;
        }
        setAlphaDuringBubbleDrag(1f);
        setTranslationX(0f);
        setAlpha(1f);
    }

    /**
     * Get bubble bar top coordinate on screen when bar is resting
     */
    public int getRestingTopPositionOnScreen() {
        int displayHeight = DisplayController.INSTANCE.get(getContext()).getInfo().currentSize.y;
        int bubbleBarHeight = getBubbleBarBounds().height();
        return displayHeight - bubbleBarHeight + (int) mController.getBubbleBarTranslationY();
    }

    /**
     * Updates the bounds with translation that may have been applied and returns the result.
     */
    public Rect getBubbleBarBounds() {
        mBubbleBarBounds.top = getTop() + (int) getTranslationY() + mPointerSize;
        mBubbleBarBounds.bottom = getBottom() + (int) getTranslationY();
        return mBubbleBarBounds;
    }

    /**
     * Set bubble bar relative pivot value for X and Y, applied as a fraction of view width/height
     * respectively. If the value is not in range of 0 to 1 it will be normalized.
     * @param x relative X pivot value in range 0..1
     * @param y relative Y pivot value in range 0..1
     */
    public void setRelativePivot(float x, float y) {
        mRelativePivotX = Float.max(Float.min(x, 1), 0);
        mRelativePivotY = Float.max(Float.min(y, 1), 0);
        requestLayout();
    }

    /** Like {@link #setRelativePivot(float, float)} but only updates pivot y. */
    public void setRelativePivotY(float y) {
        setRelativePivot(mRelativePivotX, y);
    }

    /**
     * Get current relative pivot for X axis
     */
    public float getRelativePivotX() {
        return mRelativePivotX;
    }

    /**
     * Get current relative pivot for Y axis
     */
    public float getRelativePivotY() {
        return mRelativePivotY;
    }

    /** Notifies the bubble bar that a new bubble animation is starting. */
    public void onAnimatingBubbleStarted() {
        mIsAnimatingNewBubble = true;
    }

    /** Notifies the bubble bar that a new bubble animation is complete. */
    public void onAnimatingBubbleCompleted() {
        mIsAnimatingNewBubble = false;
    }

    // TODO: (b/280605790) animate it
    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (getChildCount() + 1 > MAX_BUBBLES) {
            // the last child view is the overflow bubble and we shouldn't remove that. remove the
            // second to last child view.
            removeViewInLayout(getChildAt(getChildCount() - 2));
        }
        super.addView(child, index, params);
        updateWidth();
    }

    // TODO: (b/283309949) animate it
    @Override
    public void removeView(View view) {
        super.removeView(view);
        if (view == mSelectedBubbleView) {
            mSelectedBubbleView = null;
            mBubbleBarBackground.showArrow(false);
        }
        updateWidth();
    }

    private void updateWidth() {
        LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.width = (int) (mIsBarExpanded ? expandedWidth() : collapsedWidth());
        setLayoutParams(lp);
    }

    private void updateLayoutParams() {
        LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.height = (int) getBubbleBarExpandedHeight();
        lp.width = (int) (mIsBarExpanded ? expandedWidth() : collapsedWidth());
        setLayoutParams(lp);
    }

    /** @return the horizontal margin between the bubble bar and the edge of the screen. */
    int getHorizontalMargin() {
        LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        return lp.getMarginEnd();
    }

    /**
     * Updates the z order, positions, and badge visibility of the bubble views in the bar based
     * on the expanded state.
     */
    private void updateChildrenRenderNodeProperties(BubbleBarLocation bubbleBarLocation) {
        final float widthState = (float) mWidthAnimator.getAnimatedValue();
        final float currentWidth = getWidth();
        final float expandedWidth = expandedWidth();
        final float collapsedWidth = collapsedWidth();
        int bubbleCount = getChildCount();
        final float ty = (mBubbleBarBounds.height() - mIconSize) / 2f;
        final boolean animate = getVisibility() == VISIBLE;
        final boolean onLeft = bubbleBarLocation.isOnLeft(isLayoutRtl());
        // elevation state is opposite to widthState - when expanded all icons are flat
        float elevationState = (1 - widthState);
        for (int i = 0; i < bubbleCount; i++) {
            BubbleView bv = (BubbleView) getChildAt(i);
            if (bv == mDraggedBubbleView) {
                // Skip the dragged bubble. Its translation is managed by the drag controller.
                continue;
            }
            // Clear out drag translation and offset
            bv.setDragTranslationX(0f);
            bv.setOffsetX(0f);

            bv.setTranslationY(ty);

            // the position of the bubble when the bar is fully expanded
            final float expandedX = getExpandedBubbleTranslationX(i, bubbleCount, onLeft);
            // the position of the bubble when the bar is fully collapsed
            final float collapsedX = getCollapsedBubbleTranslationX(i, bubbleCount, onLeft);

            // slowly animate elevation while keeping correct Z ordering
            float fullElevationForChild = (MAX_BUBBLES * mBubbleElevation) - i;
            bv.setZ(fullElevationForChild * elevationState);

            if (mIsBarExpanded) {
                // If bar is on the right, account for bubble bar expanding and shifting left
                final float expandedBarShift = onLeft ? 0 : currentWidth - expandedWidth;
                // where the bubble will end up when the animation ends
                final float targetX = expandedX + expandedBarShift;
                bv.setTranslationX(widthState * (targetX - collapsedX) + collapsedX);
                // When we're expanded, we're not stacked so we're not behind the stack
                bv.setBehindStack(false, animate);
                bv.setAlpha(1);
            } else {
                // If bar is on the right, account for bubble bar expanding and shifting left
                final float collapsedBarShift = onLeft ? 0 : currentWidth - collapsedWidth;
                final float targetX = collapsedX + collapsedBarShift;
                bv.setTranslationX(widthState * (expandedX - targetX) + targetX);
                // If we're not the first bubble we're behind the stack
                bv.setBehindStack(i > 0, animate);
                // If we're fully collapsed, hide all bubbles except for the first 2. If there are
                // only 2 bubbles, hide the second bubble as well because it's the overflow.
                if (widthState == 0) {
                    if (i > MAX_VISIBLE_BUBBLES_COLLAPSED - 1) {
                        bv.setAlpha(0);
                    } else if (i == MAX_VISIBLE_BUBBLES_COLLAPSED - 1
                            && bubbleCount == MAX_VISIBLE_BUBBLES_COLLAPSED) {
                        bv.setAlpha(0);
                    }
                }
            }
        }

        // update the arrow position
        final float collapsedArrowPosition = arrowPositionForSelectedWhenCollapsed(
                bubbleBarLocation);
        final float expandedArrowPosition = arrowPositionForSelectedWhenExpanded(bubbleBarLocation);
        final float interpolatedWidth =
                widthState * (expandedWidth - collapsedWidth) + collapsedWidth;
        final float arrowPosition;

        float interpolatedShift = (expandedArrowPosition - collapsedArrowPosition) * widthState;
        if (onLeft) {
            arrowPosition = collapsedArrowPosition + interpolatedShift;
        } else {
            if (mIsBarExpanded) {
                arrowPosition = currentWidth - interpolatedWidth + collapsedArrowPosition
                        + interpolatedShift;
            } else {
                final float targetPosition = currentWidth - collapsedWidth + collapsedArrowPosition;
                arrowPosition =
                        targetPosition + widthState * (expandedArrowPosition - targetPosition);
            }
        }
        mBubbleBarBackground.setArrowPosition(arrowPosition);
        mBubbleBarBackground.setArrowAlpha((int) (255 * widthState));
        mBubbleBarBackground.setWidth(interpolatedWidth);
    }

    private float getExpandedBubbleTranslationX(int bubbleIndex, int bubbleCount,
            boolean onLeft) {
        if (bubbleIndex < 0 || bubbleIndex >= bubbleCount) {
            return 0;
        }
        if (onLeft) {
            // If bar is on the left, bubbles are ordered right to left
            return (bubbleCount - bubbleIndex - 1) * (mIconSize + mExpandedBarIconsSpacing);
        } else {
            // Bubbles ordered left to right, don't move the first bubble
            return bubbleIndex * (mIconSize + mExpandedBarIconsSpacing);
        }
    }

    private float getCollapsedBubbleTranslationX(int bubbleIndex, int bubbleCount,
            boolean onLeft) {
        if (bubbleIndex < 0 || bubbleIndex >= bubbleCount) {
            return 0;
        }
        if (onLeft) {
            // Shift the first bubble only if there are more bubbles in addition to overflow
            return bubbleIndex == 0 && bubbleCount > MAX_VISIBLE_BUBBLES_COLLAPSED
                    ? mIconOverlapAmount : 0;
        } else {
            return bubbleIndex == 0 ? 0 : mIconOverlapAmount;
        }
    }

    /**
     * Reorders the views to match the provided list.
     */
    public void reorder(List<BubbleView> viewOrder) {
        if (isExpanded() || mWidthAnimator.isRunning()) {
            mReorderRunnable = () -> doReorder(viewOrder);
        } else {
            doReorder(viewOrder);
        }
    }

    // TODO: (b/273592694) animate it
    private void doReorder(List<BubbleView> viewOrder) {
        if (!isExpanded()) {
            for (int i = 0; i < viewOrder.size(); i++) {
                View child = viewOrder.get(i);
                // this child view may have already been removed so verify that it still exists
                // before reordering it, otherwise it will be re-added.
                int indexOfChild = indexOfChild(child);
                if (child != null && indexOfChild >= 0) {
                    removeViewInLayout(child);
                    addViewInLayout(child, i, child.getLayoutParams());
                }
            }
            updateChildrenRenderNodeProperties(mBubbleBarLocation);
        }
    }

    public void setUpdateSelectedBubbleAfterCollapse(
            Consumer<String> updateSelectedBubbleAfterCollapse) {
        mUpdateSelectedBubbleAfterCollapse = updateSelectedBubbleAfterCollapse;
    }

    void setController(Controller controller) {
        mController = controller;
    }

    /**
     * Sets which bubble view should be shown as selected.
     */
    public void setSelectedBubble(BubbleView view) {
        BubbleView previouslySelectedBubble = mSelectedBubbleView;
        mSelectedBubbleView = view;
        mBubbleBarBackground.showArrow(view != null);
        // TODO: (b/283309949) remove animation should be implemented first, so than arrow
        //  animation is adjusted, skip animation for now
        updateArrowForSelected(previouslySelectedBubble != null);
    }

    /**
     * Sets the dragged bubble view to correctly apply Z order. Dragged view should appear on top
     */
    public void setDraggedBubble(@Nullable BubbleView view) {
        if (mDraggedBubbleView != null) {
            mDraggedBubbleView.setZ(0);
        }
        mDraggedBubbleView = view;
        if (view != null) {
            view.setZ(mDragElevation);
        }
        setIsDragging(view != null);
    }

    /**
     * Update the arrow position to match the selected bubble.
     *
     * @param shouldAnimate whether or not to animate the arrow. If the bar was just expanded, this
     *                      should be set to {@code false}. Otherwise set this to {@code true}.
     */
    private void updateArrowForSelected(boolean shouldAnimate) {
        if (mSelectedBubbleView == null) {
            Log.w(TAG, "trying to update selection arrow without a selected view!");
            return;
        }
        // Find the center of the bubble when it's expanded, set the arrow position to it.
        final float tx = arrowPositionForSelectedWhenExpanded(mBubbleBarLocation);
        final float currentArrowPosition = mBubbleBarBackground.getArrowPositionX();
        if (tx == currentArrowPosition) {
            // arrow position remains unchanged
            return;
        }
        if (shouldAnimate && currentArrowPosition > expandedWidth()) {
            Log.d(TAG, "arrow out of bounds of expanded view, skip animation");
            shouldAnimate = false;
        }
        if (shouldAnimate) {
            ValueAnimator animator = ValueAnimator.ofFloat(currentArrowPosition, tx);
            animator.setDuration(ARROW_POSITION_ANIMATION_DURATION_MS);
            animator.addUpdateListener(animation -> {
                float x = (float) animation.getAnimatedValue();
                mBubbleBarBackground.setArrowPosition(x);
                invalidate();
            });
            animator.start();
        } else {
            mBubbleBarBackground.setArrowPosition(tx);
            invalidate();
        }
    }

    private float arrowPositionForSelectedWhenExpanded(BubbleBarLocation bubbleBarLocation) {
        final int index = indexOfChild(mSelectedBubbleView);
        final int bubblePosition;
        if (bubbleBarLocation.isOnLeft(isLayoutRtl())) {
            // Bubble positions are reversed. First bubble is on the right.
            bubblePosition = getChildCount() - index - 1;
        } else {
            bubblePosition = index;
        }
        return getPaddingStart() + bubblePosition * (mIconSize + mExpandedBarIconsSpacing)
                + mIconSize / 2f;
    }

    private float arrowPositionForSelectedWhenCollapsed(BubbleBarLocation bubbleBarLocation) {
        final int index = indexOfChild(mSelectedBubbleView);
        final int bubblePosition;
        if (bubbleBarLocation.isOnLeft(isLayoutRtl())) {
            // Bubble positions are reversed. First bubble may be shifted, if there are more
            // bubbles than the current bubble and overflow.
            bubblePosition = index == 0 && getChildCount() > MAX_VISIBLE_BUBBLES_COLLAPSED ? 1 : 0;
        } else {
            bubblePosition = index;
        }
        return getPaddingStart() + bubblePosition * (mIconOverlapAmount) + mIconSize / 2f;
    }

    @Override
    public void setOnClickListener(View.OnClickListener listener) {
        mOnClickListener = listener;
        setOrUnsetClickListener();
    }

    /**
     * The click listener used for the bubble view gets added / removed depending on whether
     * the bar is expanded or collapsed, this updates whether the listener is set based on state.
     */
    private void setOrUnsetClickListener() {
        super.setOnClickListener(mIsBarExpanded ? null : mOnClickListener);
    }

    /**
     * Sets whether the bubble bar is expanded or collapsed.
     */
    public void setExpanded(boolean isBarExpanded) {
        if (mIsBarExpanded != isBarExpanded) {
            mIsBarExpanded = isBarExpanded;
            updateArrowForSelected(/* shouldAnimate= */ false);
            setOrUnsetClickListener();
            if (isBarExpanded) {
                mWidthAnimator.start();
            } else {
                mWidthAnimator.reverse();
            }
        }
    }

    /**
     * Returns whether the bubble bar is expanded.
     */
    public boolean isExpanded() {
        return mIsBarExpanded;
    }

    /**
     * Get width of the bubble bar as if it would be expanded.
     *
     * @return width of the bubble bar in its expanded state, regardless of current width
     */
    public float expandedWidth() {
        final int childCount = getChildCount();
        final int horizontalPadding = getPaddingStart() + getPaddingEnd();
        // spaces amount is less than child count by 1, or 0 if no child views
        int spacesCount = Math.max(childCount - 1, 0);
        return childCount * mIconSize + spacesCount * mExpandedBarIconsSpacing + horizontalPadding;
    }

    private float collapsedWidth() {
        final int childCount = getChildCount();
        final int horizontalPadding = getPaddingStart() + getPaddingEnd();
        // If there are more than 2 bubbles, the first 2 should be visible when collapsed.
        // Otherwise just the first bubble should be visible because we don't show the overflow.
        return childCount > MAX_VISIBLE_BUBBLES_COLLAPSED
                ? mIconSize + mIconOverlapAmount + horizontalPadding
                : mIconSize + horizontalPadding;
    }

    private float getBubbleBarExpandedHeight() {
        return getBubbleBarCollapsedHeight() + mPointerSize;
    }

    float getBubbleBarCollapsedHeight() {
        // the pointer is invisible when collapsed
        return mIconSize + mBubbleBarPadding * 2;
    }

    /**
     * Returns whether the given MotionEvent, *in screen coordinates*, is within bubble bar
     * touch bounds.
     */
    public boolean isEventOverAnyItem(MotionEvent ev) {
        if (getVisibility() == View.VISIBLE) {
            getBoundsOnScreen(mTempRect);
            return mTempRect.contains((int) ev.getX(), (int) ev.getY());
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mIsAnimatingNewBubble) {
            mController.onBubbleBarTouchedWhileAnimating();
        }
        if (!mIsBarExpanded) {
            // When the bar is collapsed, all taps on it should expand it.
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    /** Whether a new bubble is currently animating. */
    public boolean isAnimatingNewBubble() {
        return mIsAnimatingNewBubble;
    }

    /** Interface for BubbleBarView to communicate with its controller. */
    interface Controller {

        /** Returns the translation Y that the bubble bar should have. */
        float getBubbleBarTranslationY();

        /** Notifies the controller that the bubble bar was touched while it was animating. */
        void onBubbleBarTouchedWhileAnimating();
    }
}
