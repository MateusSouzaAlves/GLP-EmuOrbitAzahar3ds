// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Objects;

/** Non-blocking in-game editor for the independent Nintendo 3DS control profile. */
final class Nintendo3DsVirtualControlEditorController {
    interface Listener {
        void onPreview(
                Nintendo3DsVirtualControlProfile profile,
                Nintendo3DsVirtualControlOrientation orientation);

        void onSaved();

        void onCancelled();

        void onSaveFailed();
    }

    private static final int OFFSET_STEPS = 40;
    private static final int SCALE_STEPS = 20;
    private static final float OFFSET_STEP = 0.05f;
    private static final float SCALE_STEP = 0.05f;

    private final Activity activity;
    private final FrameLayout host;
    private final Nintendo3DsVirtualControlProfileStore store;
    private final Listener listener;
    @Nullable
    private FrameLayout layer;
    @Nullable
    private Nintendo3DsVirtualControlEditorSession session;
    private TextView selectedGroup;
    private Button portrait;
    private Button landscape;
    private CheckBox groupVisible;
    private TextView horizontalLabel;
    private SeekBar horizontal;
    private TextView verticalLabel;
    private SeekBar vertical;
    private TextView scaleLabel;
    private SeekBar scale;
    private boolean rendering;

    Nintendo3DsVirtualControlEditorController(
            Activity activity,
            FrameLayout host,
            Nintendo3DsVirtualControlProfileStore store,
            Listener listener) {
        this.activity = Objects.requireNonNull(activity);
        this.host = Objects.requireNonNull(host);
        this.store = Objects.requireNonNull(store);
        this.listener = Objects.requireNonNull(listener);
    }

    void show(
            String persistentId,
            Nintendo3DsVirtualControlProfile current,
            Nintendo3DsVirtualControlOrientation orientation) {
        show(
                persistentId,
                current,
                orientation,
                Nintendo3DsVirtualControlGroup.CIRCLE_PAD);
    }

    void show(
            String persistentId,
            Nintendo3DsVirtualControlProfile current,
            Nintendo3DsVirtualControlOrientation orientation,
            Nintendo3DsVirtualControlGroup selectedGroup) {
        Objects.requireNonNull(persistentId);
        close();
        session = new Nintendo3DsVirtualControlEditorSession(
                current,
                orientation,
                selectedGroup);
        layer = createLayer(persistentId);
        host.addView(layer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        render();
        preview();
    }

    boolean isShowing() {
        return layer != null;
    }

    boolean cancel() {
        if (!isShowing()) {
            return false;
        }
        detach();
        listener.onCancelled();
        return true;
    }

    void close() {
        detach();
    }

    Nintendo3DsVirtualControlEditorSession getSessionForTesting() {
        return getSession();
    }

    Nintendo3DsVirtualControlEditorSession getSession() {
        return requireSession();
    }

    SeekBar getHorizontalForTesting() {
        return horizontal;
    }

    SeekBar getVerticalForTesting() {
        return vertical;
    }

    SeekBar getScaleForTesting() {
        return scale;
    }

    CheckBox getGroupVisibleForTesting() {
        return groupVisible;
    }

    Button getSaveForTesting() {
        return requireLayer().findViewWithTag("save");
    }

    Button getResetForTesting() {
        return requireLayer().findViewWithTag("reset");
    }

    Button getCancelForTesting() {
        return requireLayer().findViewWithTag("cancel");
    }

    Button getNextGroupForTesting() {
        return requireLayer().findViewWithTag("next_group");
    }

    Button getLandscapeForTesting() {
        return landscape;
    }

    Button getPortraitForTesting() {
        return portrait;
    }

    private FrameLayout createLayer(String persistentId) {
        FrameLayout editorLayer = new FrameLayout(activity);
        editorLayer.setBackgroundColor(Color.argb(118, 0, 0, 0));
        editorLayer.setClickable(true);
        editorLayer.setContentDescription(activity.getString(R.string.n3ds_control_editor_title));
        editorLayer.setOnClickListener(ignored -> cancel());

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(14));
        content.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(31, 34, 43));
        background.setStroke(dp(1), Color.rgb(62, 216, 245));
        background.setCornerRadius(dp(18));
        content.setBackground(background);

        TextView title = label(
                activity.getString(R.string.n3ds_control_editor_title),
                20.0f,
                Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(title);
        TextView hint = label(
                activity.getString(R.string.n3ds_control_editor_hint),
                13.0f,
                Color.rgb(207, 211, 224));
        LinearLayout.LayoutParams hintLayout = matchWrap();
        hintLayout.topMargin = dp(4);
        content.addView(hint, hintLayout);

        LinearLayout orientationRow = row();
        portrait = button(R.string.n3ds_control_editor_portrait);
        landscape = button(R.string.n3ds_control_editor_landscape);
        orientationRow.addView(portrait, weighted());
        LinearLayout.LayoutParams landscapeLayout = weighted();
        landscapeLayout.setMarginStart(dp(8));
        orientationRow.addView(landscape, landscapeLayout);
        content.addView(orientationRow, topMargin(10));

        LinearLayout groupRow = row();
        Button previous = button(R.string.n3ds_control_editor_previous_group);
        previous.setText(isRtl() ? "›" : "‹");
        previous.setTextSize(26.0f);
        previous.setContentDescription(
                activity.getString(R.string.n3ds_control_editor_previous_group));
        previous.setTag("previous_group");
        groupRow.addView(previous, fixed(48, 48));
        selectedGroup = label("", 15.0f, Color.WHITE);
        selectedGroup.setGravity(Gravity.CENTER);
        groupRow.addView(selectedGroup, weighted());
        Button next = button(R.string.n3ds_control_editor_next_group);
        next.setText(isRtl() ? "‹" : "›");
        next.setTextSize(26.0f);
        next.setContentDescription(
                activity.getString(R.string.n3ds_control_editor_next_group));
        next.setTag("next_group");
        groupRow.addView(next, fixed(48, 48));
        content.addView(groupRow, topMargin(8));

        groupVisible = new CheckBox(activity);
        groupVisible.setText(R.string.n3ds_control_editor_group_visible);
        groupVisible.setTextColor(Color.WHITE);
        content.addView(groupVisible, topMargin(2));

        horizontalLabel = label("", 13.0f, Color.rgb(207, 211, 224));
        horizontal = seekBar(OFFSET_STEPS);
        horizontal.setId(View.generateViewId());
        horizontalLabel.setLabelFor(horizontal.getId());
        content.addView(horizontalLabel, topMargin(4));
        content.addView(horizontal, matchWrap());

        verticalLabel = label("", 13.0f, Color.rgb(207, 211, 224));
        vertical = seekBar(OFFSET_STEPS);
        vertical.setId(View.generateViewId());
        verticalLabel.setLabelFor(vertical.getId());
        content.addView(verticalLabel);
        content.addView(vertical, matchWrap());

        scaleLabel = label("", 13.0f, Color.rgb(207, 211, 224));
        scale = seekBar(SCALE_STEPS);
        scale.setId(View.generateViewId());
        scaleLabel.setLabelFor(scale.getId());
        content.addView(scaleLabel);
        content.addView(scale, matchWrap());

        LinearLayout actions = row();
        Button reset = button(R.string.n3ds_settings_reset);
        reset.setTag("reset");
        Button cancel = button(R.string.n3ds_product_cancel);
        cancel.setTag("cancel");
        Button save = button(R.string.n3ds_settings_save);
        save.setTag("save");
        actions.addView(reset, weighted());
        LinearLayout.LayoutParams cancelLayout = weighted();
        cancelLayout.setMarginStart(dp(6));
        actions.addView(cancel, cancelLayout);
        LinearLayout.LayoutParams saveLayout = weighted();
        saveLayout.setMarginStart(dp(6));
        actions.addView(save, saveLayout);
        content.addView(actions, topMargin(8));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        boolean isLandscape = activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        FrameLayout.LayoutParams panelLayout = new FrameLayout.LayoutParams(
                isLandscape ? dp(420) : ViewGroup.LayoutParams.MATCH_PARENT,
                isLandscape ? ViewGroup.LayoutParams.MATCH_PARENT : dp(440),
                isLandscape ? Gravity.END : Gravity.BOTTOM);
        panelLayout.setMargins(dp(8), dp(8), dp(8), dp(8));
        editorLayer.addView(scroll, panelLayout);

        portrait.setOnClickListener(ignored -> selectOrientation(
                Nintendo3DsVirtualControlOrientation.PORTRAIT));
        landscape.setOnClickListener(ignored -> selectOrientation(
                Nintendo3DsVirtualControlOrientation.LANDSCAPE));
        previous.setOnClickListener(ignored -> {
            requireSession().selectPreviousGroup();
            render();
        });
        next.setOnClickListener(ignored -> {
            requireSession().selectNextGroup();
            render();
        });
        groupVisible.setOnCheckedChangeListener((ignored, checked) -> {
            if (rendering) {
                return;
            }
            requireSession().setVisible(checked);
            preview();
        });
        horizontal.setOnSeekBarChangeListener(listener(progress -> {
            requireSession().setHorizontalOffset(-1.0f + progress * OFFSET_STEP);
            renderValues();
            preview();
        }));
        vertical.setOnSeekBarChangeListener(listener(progress -> {
            requireSession().setVerticalOffset(-1.0f + progress * OFFSET_STEP);
            renderValues();
            preview();
        }));
        scale.setOnSeekBarChangeListener(listener(progress -> {
            requireSession().setScale(
                    Nintendo3DsVirtualControlTransform.MINIMUM_SCALE
                            + progress * SCALE_STEP);
            renderValues();
            preview();
        }));
        reset.setOnClickListener(ignored -> {
            requireSession().resetToDefault();
            render();
            preview();
        });
        cancel.setOnClickListener(ignored -> cancel());
        save.setOnClickListener(ignored -> {
            if (!store.saveForGame(persistentId, requireSession().getDraft())) {
                listener.onSaveFailed();
                return;
            }
            detach();
            listener.onSaved();
        });
        return editorLayer;
    }

    private void selectOrientation(Nintendo3DsVirtualControlOrientation orientation) {
        requireSession().setOrientation(orientation);
        render();
        preview();
    }

    private void render() {
        rendering = true;
        Nintendo3DsVirtualControlEditorSession active = requireSession();
        portrait.setSelected(
                active.getOrientation() == Nintendo3DsVirtualControlOrientation.PORTRAIT);
        landscape.setSelected(
                active.getOrientation() == Nintendo3DsVirtualControlOrientation.LANDSCAPE);
        portrait.setAlpha(portrait.isSelected() ? 1.0f : 0.55f);
        landscape.setAlpha(landscape.isSelected() ? 1.0f : 0.55f);
        selectedGroup.setText(labelForGroup(active.getSelectedGroup()));
        renderValues();
        rendering = false;
    }

    private void renderValues() {
        Nintendo3DsVirtualControlTransform transform = requireSession().getSelectedTransform();
        horizontal.setProgress(Math.round(
                (transform.getNormalizedOffsetX() + 1.0f) / OFFSET_STEP));
        vertical.setProgress(Math.round(
                (transform.getNormalizedOffsetY() + 1.0f) / OFFSET_STEP));
        scale.setProgress(Math.round(
                (transform.getScale()
                        - Nintendo3DsVirtualControlTransform.MINIMUM_SCALE) / SCALE_STEP));
        groupVisible.setChecked(transform.isVisible());
        horizontalLabel.setText(activity.getString(
                R.string.n3ds_control_editor_horizontal,
                Math.round(transform.getNormalizedOffsetX() * 100.0f)));
        verticalLabel.setText(activity.getString(
                R.string.n3ds_control_editor_vertical,
                Math.round(transform.getNormalizedOffsetY() * 100.0f)));
        scaleLabel.setText(activity.getString(
                R.string.n3ds_control_editor_scale,
                Math.round(transform.getScale() * 100.0f)));
    }

    private void preview() {
        Nintendo3DsVirtualControlEditorSession active = requireSession();
        listener.onPreview(active.getDraft(), active.getOrientation());
    }

    private CharSequence labelForGroup(Nintendo3DsVirtualControlGroup group) {
        switch (group) {
            case CIRCLE_PAD:
                return activity.getText(R.string.n3ds_control_circle_pad);
            case DIRECTIONAL:
                return "D-pad";
            case ACTIONS:
                return "A / B / X / Y";
            case C_STICK:
                return activity.getText(R.string.n3ds_control_c_stick);
            case LEFT_SHOULDERS:
                return "L / ZL";
            case RIGHT_SHOULDERS:
                return "ZR / R";
            case SYSTEM_BUTTONS:
                return "SELECT / START";
            case SCREEN_SWAP:
                return activity.getText(R.string.n3ds_control_swap_screens);
            default:
                throw new IllegalArgumentException("Unknown 3DS control group: " + group);
        }
    }

    private SeekBar.OnSeekBarChangeListener listener(IntProgressListener listener) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!rendering && fromUser) {
                    listener.onChanged(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // No-op.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // No-op.
            }
        };
    }

    private void detach() {
        FrameLayout current = layer;
        layer = null;
        session = null;
        if (current != null && current.getParent() == host) {
            host.removeView(current);
        }
    }

    private FrameLayout requireLayer() {
        return Objects.requireNonNull(layer);
    }

    private Nintendo3DsVirtualControlEditorSession requireSession() {
        return Objects.requireNonNull(session);
    }

    private TextView label(CharSequence text, float sizeSp, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private Button button(int textResource) {
        Button view = new Button(activity);
        view.setText(textResource);
        view.setAllCaps(false);
        view.setMinWidth(0);
        view.setMinHeight(0);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private SeekBar seekBar(int maximum) {
        SeekBar view = new SeekBar(activity);
        view.setMax(maximum);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(48), 1.0f);
    }

    private LinearLayout.LayoutParams fixed(int widthDp, int heightDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams layout = matchWrap();
        layout.topMargin = dp(marginDp);
        return layout;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private boolean isRtl() {
        return activity.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
    }

    private interface IntProgressListener {
        void onChanged(int progress);
    }
}
