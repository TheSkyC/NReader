// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.widget.ImageViewCompat;

import com.theskyc.nreader.R;

/** Renders a touch-through reader overlay without participating in image measurement. */
public final class ReaderStatusOverlayView extends FrameLayout {
    private static final int[] ITEMS = {
        ReaderStatusSettings.ITEM_TIME,
        ReaderStatusSettings.ITEM_BATTERY,
        ReaderStatusSettings.ITEM_PROGRESS
    };
    private static final int[] BATTERY_ICONS = {
        R.drawable.ic_reader_status_battery_low,
        R.drawable.ic_reader_status_battery_half,
        R.drawable.ic_reader_status_battery_full
    };

    private final LinearLayout timeItem;
    private final LinearLayout batteryItem;
    private final LinearLayout progressItem;
    private final TextView timeText;
    private final ImageView batteryIcon;
    private final TextView batteryText;
    private final TextView progressText;
    private final View[] itemViews;
    private final boolean[] targetVisible = new boolean[3];
    private Insets safeInsets = Insets.NONE;
    private Insets systemSafeInsets = Insets.NONE;
    private Insets cutoutInsets = Insets.NONE;
    private Insets previewSystemInsets = Insets.NONE;
    private Insets previewCutoutInsets = Insets.NONE;
    private int previewSourceWidth;
    private int previewSourceHeight;
    private ReaderStatusSettings settings;
    private CharSequence timeValue = "";
    private int batteryLevel = -1;
    private boolean charging;
    private int currentPage;
    private int totalPages;
    private boolean hostActive;
    private boolean controlsVisible = true;
    private boolean modalVisible;
    private boolean idleExpired;
    private boolean interactionActive;
    private boolean editorMode;
    private int selectedItem = ReaderStatusSettings.ITEM_TIME;
    private final float[][] editorPositions = new float[3][];
    private float downX;
    private float downY;
    private float itemDownX;
    private float itemDownY;
    @Nullable private PositionListener positionListener;

    public ReaderStatusOverlayView(Context context) {
        this(context, null);
    }

    public ReaderStatusOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        settings = ReaderStatusSettings.load(context.getSharedPreferences("Settings", Context.MODE_PRIVATE));

        timeText = createText();
        timeItem = createItem(timeText);
        batteryIcon = new ImageView(context);
        batteryIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        batteryText = createText();
        batteryItem = new LinearLayout(context);
        batteryItem.setGravity(Gravity.CENTER_VERTICAL);
        batteryItem.setOrientation(LinearLayout.HORIZONTAL);
        batteryItem.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        batteryItem.addView(batteryIcon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        LinearLayout.LayoutParams batteryTextParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        batteryTextParams.setMarginStart(dp(4));
        batteryItem.addView(batteryText, batteryTextParams);
        styleItem(batteryItem);
        progressText = createText();
        progressItem = createItem(progressText);
        itemViews = new View[]{timeItem, batteryItem, progressItem};

        for (int item : ITEMS) {
            View child = itemViews[item];
            child.setAlpha(1f);
            child.setVisibility(INVISIBLE);
            addView(child, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
            installEditorTouch(child, item);
        }
        refreshAppearance();
        refreshContent();
    }

    public void setSettings(@NonNull ReaderStatusSettings value) {
        settings = value;
        updateSafeInsets();
        refreshAppearance();
        refreshContent();
        updateVisibility(false);
        requestLayout();
    }

    public void setSafeInsets(@NonNull Insets value) {
        if (safeInsets.equals(value)) return;
        safeInsets = value;
        requestLayout();
    }

    public void setWindowInsets(@NonNull Insets systemSafe, @NonNull Insets cutout) {
        systemSafeInsets = systemSafe;
        cutoutInsets = cutout;
        updateSafeInsets();
    }

    private void updateSafeInsets() {
        setSafeInsets(settings.nearEdges ? cutoutInsets : systemSafeInsets);
    }

    public void setHostState(boolean active, boolean controls, boolean modal,
                             boolean expired, boolean interaction, boolean animate) {
        hostActive = active;
        controlsVisible = controls;
        modalVisible = modal;
        idleExpired = expired;
        interactionActive = interaction;
        updateVisibility(animate);
    }

    public void setTimeValue(@NonNull CharSequence value) {
        timeValue = value;
        refreshContent();
        updateVisibility(false);
    }

    public void setBatteryValue(int level, boolean isCharging) {
        batteryLevel = level < 0 || level > 100 ? -1 : level;
        charging = isCharging;
        refreshContent();
        updateVisibility(false);
    }

    public void setProgressValue(int page, int total) {
        currentPage = page;
        totalPages = total;
        refreshContent();
        updateVisibility(false);
    }

    public void setEditorMode(boolean enabled, @Nullable PositionListener listener) {
        if (!enabled || !editorMode) {
            for (int item : ITEMS) editorPositions[item] = null;
        }
        editorMode = enabled;
        positionListener = listener;
        selectedItem = ReaderStatusSettings.ITEM_TIME;
        for (View item : itemViews) {
            item.setClickable(enabled);
            item.setFocusable(enabled);
        }
        refreshContent();
        updateVisibility(false);
    }

    public void setSelectedItem(int item) {
        if (item < ReaderStatusSettings.ITEM_TIME || item > ReaderStatusSettings.ITEM_PROGRESS) return;
        selectedItem = item;
        for (int index : ITEMS) {
            itemViews[index].setSelected(index == item);
            itemViews[index].setElevation(index == item ? dp(2) : 0f);
        }
    }

    public void setPosition(int item, float x, float y) {
        if (item < ReaderStatusSettings.ITEM_TIME || item > ReaderStatusSettings.ITEM_PROGRESS) return;
        ReaderStatusSettings.Position position = ReaderStatusSettings.Position.custom(x, y);
        editorPositions[item] = new float[]{position.x, position.y};
        float[] coordinates = normalizedCoordinates(position, itemViews[item]);
        itemViews[item].setX(coordinates[0]);
        itemViews[item].setY(coordinates[1]);
    }

    public void clearEditorPosition(int item) {
        if (item < ReaderStatusSettings.ITEM_TIME || item > ReaderStatusSettings.ITEM_PROGRESS) return;
        editorPositions[item] = null;
        requestLayout();
    }

    @NonNull
    public float[] normalizedPositionFor(int item) {
        if (item < ReaderStatusSettings.ITEM_TIME || item > ReaderStatusSettings.ITEM_PROGRESS)
            throw new IllegalArgumentException("Unknown reader status item");
        return normalizedPosition(itemViews[item]);
    }

    public void copyPreviewStateTo(@NonNull ReaderStatusOverlayView target,
                                   @Nullable PositionListener listener) {
        target.setSettings(settings);
        target.previewSystemInsets = systemSafeInsets;
        target.previewCutoutInsets = cutoutInsets;
        target.previewSourceWidth = getWidth();
        target.previewSourceHeight = getHeight();
        target.updatePreviewInsets(target.getWidth(), target.getHeight());
        target.setTimeValue(timeValue.length() == 0
            ? android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date())
            : timeValue);
        target.setBatteryValue(batteryLevel < 0 ? 76 : batteryLevel, batteryLevel >= 0 && charging);
        boolean validProgress = currentPage > 0 && totalPages > 0 && currentPage <= totalPages;
        target.setProgressValue(validProgress ? currentPage : 12, validProgress ? totalPages : 48);
        target.setEditorMode(true, listener);
    }

    private static Insets scaleInsets(Insets insets, float scaleX, float scaleY) {
        return Insets.of(Math.round(insets.left * scaleX), Math.round(insets.top * scaleY),
            Math.round(insets.right * scaleX), Math.round(insets.bottom * scaleY));
    }

    private void updatePreviewInsets(int width, int height) {
        if (previewSourceWidth <= 0 || previewSourceHeight <= 0 || width <= 0 || height <= 0) return;
        float scaleX = (float) width / previewSourceWidth;
        float scaleY = (float) height / previewSourceHeight;
        setWindowInsets(scaleInsets(previewSystemInsets, scaleX, scaleY),
            scaleInsets(previewCutoutInsets, scaleX, scaleY));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updatePreviewInsets(width, height);
    }

    public boolean hasOverlappingItems() {
        for (int left = 0; left < itemViews.length; left++) {
            if (itemViews[left].getVisibility() != VISIBLE) continue;
            RectF leftBounds = boundsOf(itemViews[left]);
            for (int right = left + 1; right < itemViews.length; right++) {
                if (itemViews[right].getVisibility() == VISIBLE
                    && RectF.intersects(leftBounds, boundsOf(itemViews[right]))) return true;
            }
        }
        return false;
    }

    @Nullable
    public RectF boundsForItem(int item) {
        if (item < ReaderStatusSettings.ITEM_TIME || item > ReaderStatusSettings.ITEM_PROGRESS) return null;
        return boundsOf(itemViews[item]);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        layoutItems();
    }

    private void layoutItems() {
        int edge = dp(10);
        int safeLeft = safeInsets.left + edge;
        int safeTop = safeInsets.top + edge;
        int safeRight = Math.max(safeLeft, getWidth() - safeInsets.right - edge);
        int safeBottom = Math.max(safeTop, getHeight() - safeInsets.bottom - edge);
        int availableWidth = Math.max(0, safeRight - safeLeft);
        for (View item : itemViews) {
            if (item instanceof LinearLayout) item.setMinimumWidth(Math.min(dp(28), availableWidth));
            if (item instanceof TextView) ((TextView) item).setMaxWidth(availableWidth);
            if (item == timeItem) timeText.setMaxWidth(availableWidth);
            if (item == batteryItem) batteryText.setMaxWidth(Math.max(0, availableWidth - dp(24)));
            if (item == progressItem) progressText.setMaxWidth(availableWidth);
        }
        measureChildForLayout(timeItem, availableWidth);
        measureChildForLayout(batteryItem, availableWidth);
        measureChildForLayout(progressItem, availableWidth);

        int batteryDefaultX = safeRight - batteryItem.getMeasuredWidth();
        int timeDefaultX = batteryDefaultX - dp(6) - timeItem.getMeasuredWidth();
        int timeDefaultY = safeTop;
        int batteryDefaultY = safeTop;
        if (timeItem.getMeasuredWidth() + batteryItem.getMeasuredWidth() + dp(6) > availableWidth) {
            timeDefaultX = safeRight - timeItem.getMeasuredWidth();
            batteryDefaultX = safeRight - batteryItem.getMeasuredWidth();
            batteryDefaultY += timeItem.getMeasuredHeight() + dp(4);
            int maxY = Math.max(safeTop, safeBottom - batteryItem.getMeasuredHeight());
            batteryDefaultY = Math.min(batteryDefaultY, maxY);
        }
        placeItem(ReaderStatusSettings.ITEM_TIME, timeDefaultX, timeDefaultY, safeLeft, safeTop,
            safeRight, safeBottom);
        placeItem(ReaderStatusSettings.ITEM_BATTERY, batteryDefaultX, batteryDefaultY, safeLeft, safeTop,
            safeRight, safeBottom);
        placeItem(ReaderStatusSettings.ITEM_PROGRESS,
            safeRight - progressItem.getMeasuredWidth(),
            safeBottom - progressItem.getMeasuredHeight(), safeLeft, safeTop, safeRight, safeBottom);
    }

    private void measureChildForLayout(View child, int availableWidth) {
        int width = MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST);
        int height = MeasureSpec.makeMeasureSpec(Math.max(0, getHeight()), MeasureSpec.AT_MOST);
        child.measure(width, height);
    }

    private void placeItem(int item, int defaultX, int defaultY, int minX, int minY, int maxX, int maxY) {
        ReaderStatusSettings.Position position = settings.positionFor(item);
        if (editorMode && editorPositions[item] != null) {
            position = ReaderStatusSettings.Position.custom(editorPositions[item][0], editorPositions[item][1]);
        }
        if (position.custom) {
            float[] coordinates = normalizedCoordinates(position, itemViews[item]);
            itemViews[item].setX(coordinates[0]);
            itemViews[item].setY(coordinates[1]);
            return;
        }
        int width = itemViews[item].getMeasuredWidth();
        int height = itemViews[item].getMeasuredHeight();
        itemViews[item].setX(clamp(defaultX, minX, Math.max(minX, maxX - width)));
        itemViews[item].setY(clamp(defaultY, minY, Math.max(minY, maxY - height)));
    }

    private float[] normalizedCoordinates(@NonNull ReaderStatusSettings.Position position, View child) {
        int edge = dp(10);
        int minX = safeInsets.left + edge;
        int minY = safeInsets.top + edge;
        int maxX = Math.max(minX, getWidth() - safeInsets.right - edge - child.getMeasuredWidth());
        int maxY = Math.max(minY, getHeight() - safeInsets.bottom - edge - child.getMeasuredHeight());
        return new float[]{minX + (maxX - minX) * position.x, minY + (maxY - minY) * position.y};
    }

    private void refreshAppearance() {
        timeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.textSizeSp);
        batteryText.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.textSizeSp);
        progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.textSizeSp);
        int iconSize = dp(Math.max(14, Math.min(24, Math.round(settings.textSizeSp + 4))));
        ViewGroup.LayoutParams iconParams = batteryIcon.getLayoutParams();
        if (iconParams != null) {
            iconParams.width = iconSize;
            iconParams.height = iconSize;
            batteryIcon.setLayoutParams(iconParams);
        }
        int paddingHorizontal = dp(settings.style == ReaderStatusSettings.STYLE_BACKED ? 5 : 1);
        int paddingVertical = dp(settings.style == ReaderStatusSettings.STYLE_BACKED ? 3 : 1);
        for (View child : itemViews) {
            child.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
            if (settings.style == ReaderStatusSettings.STYLE_BACKED) {
                GradientDrawable background = new GradientDrawable();
                background.setColor(Color.argb(settings.backgroundOpacity * 255 / 100, 0, 0, 0));
                background.setCornerRadius(dp(6));
                child.setBackground(background);
                setTextShadow(false);
            } else {
                child.setBackground(null);
                setTextShadow(true);
            }
        }
        ImageViewCompat.setImageTintList(batteryIcon,
            android.content.res.ColorStateList.valueOf(Color.WHITE));
        requestLayout();
    }

    private void setTextShadow(boolean enabled) {
        float radius = enabled ? dp(2) : 0f;
        for (TextView text : new TextView[]{timeText, batteryText, progressText})
            text.setShadowLayer(radius, 0f, dp(1), Color.BLACK);
    }

    private void refreshContent() {
        timeText.setText(timeValue);
        timeText.setContentDescription(getContext().getString(R.string.reader_status_time_description, timeValue));
        boolean showBatteryIcon = batteryLevel >= 0 && settings.batteryFormat != ReaderStatusSettings.BATTERY_PERCENT_ONLY;
        boolean showBatteryPercent = batteryLevel >= 0
            && settings.batteryFormat != ReaderStatusSettings.BATTERY_ICON_ONLY;
        batteryIcon.setVisibility(showBatteryIcon ? VISIBLE : GONE);
        if (showBatteryIcon) {
            int icon = charging ? R.drawable.ic_reader_status_battery_charging
                : batteryLevel < 20 ? BATTERY_ICONS[0]
                : batteryLevel < 60 ? BATTERY_ICONS[1] : BATTERY_ICONS[2];
            batteryIcon.setImageResource(icon);
        }
        batteryText.setVisibility(showBatteryPercent ? VISIBLE : GONE);
        batteryText.setText(getContext().getString(R.string.percentage_format, Math.max(0, batteryLevel)));
        String batteryDescription = batteryLevel < 0 ? "" : getContext().getString(
            charging ? R.string.reader_status_battery_charging_description
                : R.string.reader_status_battery_description, batteryLevel);
        batteryItem.setContentDescription(batteryDescription);
        batteryItem.setImportantForAccessibility(showBatteryPercent
            ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (batteryLevel < 0) batteryItem.setContentDescription(null);

        boolean validProgress = currentPage > 0 && totalPages > 0 && currentPage <= totalPages;
        if (validProgress && settings.progressFormat == ReaderStatusSettings.PROGRESS_PERCENT) {
            int percent = (int) ((long) currentPage * 100 / totalPages);
            progressText.setText(getContext().getString(R.string.percentage_format, percent));
            progressText.setContentDescription(getContext().getString(
                R.string.reader_status_progress_percent_description, percent));
        } else if (validProgress) {
            progressText.setText(getContext().getString(R.string.page_format, currentPage, totalPages));
            progressText.setContentDescription(getContext().getString(
                R.string.reader_status_progress_count_description, currentPage, totalPages));
        } else {
            progressText.setText("");
            progressText.setContentDescription(null);
        }
    }

    private void updateVisibility(boolean animate) {
        boolean hostAllows = editorMode || (settings.enabled && hostActive && !controlsVisible && !modalVisible);
        for (int item : ITEMS) {
            boolean valueValid = item == ReaderStatusSettings.ITEM_TIME ? timeValue.length() > 0
                : item == ReaderStatusSettings.ITEM_BATTERY ? batteryLevel >= 0
                : currentPage > 0 && totalPages > 0 && currentPage <= totalPages;
            boolean hideForIdle = !editorMode && idleExpired && !interactionActive
                && settings.visibilityFor(item) == ReaderStatusSettings.VISIBILITY_IDLE;
            boolean visible = editorMode || (hostAllows && settings.itemEnabled(item) && valueValid && !hideForIdle);
            setItemVisible(itemViews[item], item, visible, animate);
        }
        if (editorMode) setSelectedItem(selectedItem);
    }

    private void setItemVisible(View view, int item, boolean visible, boolean animate) {
        if (targetVisible[item] == visible && (view.getVisibility() == VISIBLE) == visible) return;
        targetVisible[item] = visible;
        view.animate().setListener(null).cancel();
        if (!visible) {
            if (!animate || !shouldAnimate()) {
                view.setAlpha(1f);
                view.setVisibility(INVISIBLE);
                return;
            }
            view.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                if (!targetVisible[item]) view.setVisibility(INVISIBLE);
            }).start();
        } else {
            view.setVisibility(VISIBLE);
            if (!animate || !shouldAnimate()) {
                view.setAlpha(1f);
                requestLayout();
                return;
            }
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(150).start();
            requestLayout();
        }
    }

    private void installEditorTouch(View itemView, int item) {
        itemView.setOnClickListener(view -> {
            if (positionListener != null) positionListener.onSelected(item);
        });
        itemView.setClickable(false);
        itemView.setFocusable(false);
        itemView.setOnTouchListener((view, event) -> {
            if (!editorMode) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    selectedItem = item;
                    setSelectedItem(item);
                    if (positionListener != null) positionListener.onSelected(item);
                    downX = event.getRawX();
                    downY = event.getRawY();
                    itemDownX = view.getX();
                    itemDownY = view.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float proposedX = itemDownX + event.getRawX() - downX;
                    float proposedY = itemDownY + event.getRawY() - downY;
                    float[] position = clampViewPosition(view, proposedX, proposedY);
                    view.setX(position[0]);
                    view.setY(position[1]);
                    float[] normalized = normalizedPosition(view);
                    editorPositions[item] = normalized;
                    if (positionListener != null)
                        positionListener.onMoved(item, normalized[0], normalized[1]);
                    return true;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
        });
    }

    private float[] clampViewPosition(View child, float x, float y) {
        int edge = dp(10);
        float minX = safeInsets.left + edge;
        float minY = safeInsets.top + edge;
        float maxX = Math.max(minX, getWidth() - safeInsets.right - edge - child.getWidth());
        float maxY = Math.max(minY, getHeight() - safeInsets.bottom - edge - child.getHeight());
        return new float[]{Math.max(minX, Math.min(maxX, x)), Math.max(minY, Math.min(maxY, y))};
    }

    private float[] normalizedPosition(View child) {
        int edge = dp(10);
        float minX = safeInsets.left + edge;
        float minY = safeInsets.top + edge;
        float maxX = Math.max(minX, getWidth() - safeInsets.right - edge - child.getWidth());
        float maxY = Math.max(minY, getHeight() - safeInsets.bottom - edge - child.getHeight());
        return new float[]{maxX == minX ? 1f : (child.getX() - minX) / (maxX - minX),
            maxY == minY ? 1f : (child.getY() - minY) / (maxY - minY)};
    }

    private RectF boundsOf(View view) {
        return new RectF(view.getX(), view.getY(), view.getX() + view.getWidth(), view.getY() + view.getHeight());
    }

    private TextView createText() {
        TextView text = new TextView(getContext());
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, ReaderStatusSettings.DEFAULT_TEXT_SIZE_SP);
        text.setMaxLines(1);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setIncludeFontPadding(false);
        text.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        text.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_NONE);
        text.setClickable(false);
        text.setFocusable(false);
        return text;
    }

    private LinearLayout createItem(View child) {
        LinearLayout item = new LinearLayout(getContext());
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(child, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        styleItem(item);
        return item;
    }

    private void styleItem(View item) {
        item.setClickable(false);
        item.setLongClickable(false);
        item.setFocusable(false);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean shouldAnimate() {
        return android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    public interface PositionListener {
        void onSelected(int item);
        void onMoved(int item, float x, float y);
    }
}
