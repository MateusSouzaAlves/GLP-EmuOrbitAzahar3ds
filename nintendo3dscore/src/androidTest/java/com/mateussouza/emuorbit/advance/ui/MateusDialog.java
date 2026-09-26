// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.advance.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/** Resource-free MateusDialog contract for the isolated Nintendo 3DS instrumentation APK. */
public final class MateusDialog {
    public static final String TEST_TAG_TITLE = "n3ds-test-dialog-title";
    public static final String TEST_TAG_MESSAGE = "n3ds-test-dialog-message";
    public static final String TEST_TAG_POSITIVE = "n3ds-test-dialog-positive";
    public static final String TEST_TAG_NEGATIVE = "n3ds-test-dialog-negative";
    public static final String TEST_TAG_NEUTRAL = "n3ds-test-dialog-neutral";

    private MateusDialog() {
    }

    public interface ValidatingActionListener {
        boolean onClick(AlertDialog dialog, int which);
    }

    public static final class Builder {
        private final Context context;
        private CharSequence title;
        private CharSequence message;
        private CharSequence positiveText;
        private CharSequence negativeText;
        private CharSequence neutralText;
        private DialogInterface.OnClickListener positiveListener;
        private DialogInterface.OnClickListener negativeListener;
        private DialogInterface.OnClickListener neutralListener;
        private ValidatingActionListener validatingPositiveListener;
        private ValidatingActionListener validatingNeutralListener;
        private DialogInterface.OnDismissListener dismissListener;
        private View customContent;
        private boolean cancelable = true;

        public Builder(Context context) {
            this.context = context;
        }

        public Builder setTitle(int resource) {
            return setTitle(context.getText(resource));
        }

        public Builder setTitle(CharSequence value) {
            title = value;
            return this;
        }

        public Builder setMessage(int resource) {
            return setMessage(context.getText(resource));
        }

        public Builder setMessage(CharSequence value) {
            message = value;
            return this;
        }

        public Builder setPositiveButton(
                int resource,
                DialogInterface.OnClickListener listener) {
            positiveText = context.getText(resource);
            positiveListener = listener;
            validatingPositiveListener = null;
            return this;
        }

        public Builder setPageTransitionPositiveButton(
                int resource,
                DialogInterface.OnClickListener listener) {
            return setPositiveButton(resource, listener);
        }

        public Builder setValidatingPositiveButton(
                int resource,
                ValidatingActionListener listener) {
            positiveText = context.getText(resource);
            validatingPositiveListener = listener;
            positiveListener = null;
            return this;
        }

        public Builder setCustomContent(View value) {
            customContent = value;
            return this;
        }

        public Builder setAvatar(int ignoredResource) {
            return this;
        }

        public Builder setNegativeButton(
                int resource,
                DialogInterface.OnClickListener listener) {
            negativeText = context.getText(resource);
            negativeListener = listener;
            return this;
        }

        public Builder setPageTransitionNegativeButton(
                int resource,
                DialogInterface.OnClickListener listener) {
            return setNegativeButton(resource, listener);
        }

        public Builder setSkipOpeningAnimation(boolean ignored) {
            return this;
        }

        public Builder setNeutralButton(
                int resource,
                DialogInterface.OnClickListener listener) {
            neutralText = context.getText(resource);
            neutralListener = listener;
            validatingNeutralListener = null;
            return this;
        }

        public Builder setValidatingNeutralButton(
                int resource,
                ValidatingActionListener listener) {
            neutralText = context.getText(resource);
            validatingNeutralListener = listener;
            neutralListener = null;
            return this;
        }

        public Builder setOnDismissListener(DialogInterface.OnDismissListener listener) {
            dismissListener = listener;
            return this;
        }

        public Builder setCancelable(boolean value) {
            cancelable = value;
            return this;
        }

        public AlertDialog show() {
            AlertDialog dialog = new AlertDialog(context);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.max(1, Math.round(
                    16f * context.getResources().getDisplayMetrics().density));
            content.setPadding(padding, padding, padding, padding);

            TextView titleView = new TextView(context);
            titleView.setTag(TEST_TAG_TITLE);
            titleView.setText(title);
            titleView.setTextSize(20f);
            titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            content.addView(titleView, matchWidthWrapHeight());

            TextView messageView = new TextView(context);
            messageView.setTag(TEST_TAG_MESSAGE);
            messageView.setText(message);
            messageView.setTextSize(16f);
            messageView.setVisibility(message == null ? View.GONE : View.VISIBLE);
            content.addView(messageView, matchWidthWrapHeight());

            if (customContent != null) {
                if (customContent.getParent() instanceof ViewGroup) {
                    ((ViewGroup) customContent.getParent()).removeView(customContent);
                }
                int height = customContent.getLayoutParams() == null
                        ? ViewGroup.LayoutParams.WRAP_CONTENT
                        : customContent.getLayoutParams().height;
                content.addView(customContent, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        height));
            }

            ActionFlowLayout actions = new ActionFlowLayout(context);
            addActionButton(actions, dialog, DialogInterface.BUTTON_NEUTRAL,
                    neutralText, neutralListener, validatingNeutralListener,
                    TEST_TAG_NEUTRAL);
            addActionButton(actions, dialog, DialogInterface.BUTTON_NEGATIVE,
                    negativeText, negativeListener, null,
                    TEST_TAG_NEGATIVE);
            addActionButton(actions, dialog, DialogInterface.BUTTON_POSITIVE,
                    positiveText, positiveListener, validatingPositiveListener,
                    TEST_TAG_POSITIVE);
            content.addView(actions, matchWidthWrapHeight());

            // Production's Mateus dialog uses an outer NestedScrollView so actions remain
            // reachable when large text or a translated label increases the content height.
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(false);
            scroll.setVerticalScrollBarEnabled(true);
            scroll.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.setView(scroll);
            dialog.setCancelable(cancelable);
            dialog.setCanceledOnTouchOutside(cancelable);
            dialog.setOnDismissListener(dismissListener);
            dialog.show();
            return dialog;
        }

        private static void addActionButton(
                ViewGroup parent,
                AlertDialog dialog,
                int which,
                CharSequence text,
                DialogInterface.OnClickListener listener,
                ValidatingActionListener validatingListener,
                String tag) {
            if (text == null) {
                return;
            }
            Button button = new Button(parent.getContext());
            button.setTag(tag);
            button.setText(text);
            button.setAllCaps(false);
            button.setMinWidth(0);
            int horizontalPadding = Math.max(1, Math.round(
                    14f * parent.getResources().getDisplayMetrics().density));
            button.setPadding(horizontalPadding, 0, horizontalPadding, 0);
            button.setOnClickListener(ignored -> {
                if (validatingListener != null) {
                    if (validatingListener.onClick(dialog, which)) {
                        dialog.dismiss();
                    }
                    return;
                }
                dialog.dismiss();
                if (listener != null) {
                    listener.onClick(dialog, which);
                }
            });
            parent.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private static LinearLayout.LayoutParams matchWidthWrapHeight() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        /** Minimal resource-free equivalent of the production ConstraintLayout Flow. */
        private static final class ActionFlowLayout extends ViewGroup {
            private final int gap;

            ActionFlowLayout(Context context) {
                super(context);
                gap = Math.max(1, Math.round(
                        8f * context.getResources().getDisplayMetrics().density));
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int availableWidth = Math.max(0,
                        MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight());
                int rowWidth = 0;
                int rowHeight = 0;
                int maxRowWidth = 0;
                int totalHeight = 0;
                for (int index = 0; index < getChildCount(); index++) {
                    View child = getChildAt(index);
                    if (child.getVisibility() == GONE) {
                        continue;
                    }
                    measureChild(child, widthMeasureSpec, heightMeasureSpec);
                    int childWidth = Math.min(child.getMeasuredWidth(), availableWidth);
                    int requiredWidth = rowWidth == 0 ? childWidth : rowWidth + gap + childWidth;
                    if (rowWidth > 0 && requiredWidth > availableWidth) {
                        maxRowWidth = Math.max(maxRowWidth, rowWidth);
                        totalHeight += rowHeight + gap;
                        rowWidth = childWidth;
                        rowHeight = child.getMeasuredHeight();
                    } else {
                        rowWidth = requiredWidth;
                        rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
                    }
                }
                maxRowWidth = Math.max(maxRowWidth, rowWidth);
                totalHeight += rowHeight;
                setMeasuredDimension(
                        resolveSize(maxRowWidth + getPaddingLeft() + getPaddingRight(),
                                widthMeasureSpec),
                        resolveSize(totalHeight + getPaddingTop() + getPaddingBottom(),
                                heightMeasureSpec));
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                int availableWidth = Math.max(0,
                        right - left - getPaddingLeft() - getPaddingRight());
                int rowStart = 0;
                int rowWidth = 0;
                int rowHeight = 0;
                int y = getPaddingTop();
                for (int index = 0; index < getChildCount(); index++) {
                    View child = getChildAt(index);
                    if (child.getVisibility() == GONE) {
                        continue;
                    }
                    int childWidth = Math.min(child.getMeasuredWidth(), availableWidth);
                    int requiredWidth = rowWidth == 0 ? childWidth : rowWidth + gap + childWidth;
                    if (rowWidth > 0 && requiredWidth > availableWidth) {
                        layoutRow(rowStart, index, availableWidth, rowWidth, y, rowHeight);
                        y += rowHeight + gap;
                        rowStart = index;
                        rowWidth = childWidth;
                        rowHeight = child.getMeasuredHeight();
                    } else {
                        rowWidth = requiredWidth;
                        rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
                    }
                }
                layoutRow(rowStart, getChildCount(), availableWidth, rowWidth, y, rowHeight);
            }

            private void layoutRow(
                    int start,
                    int end,
                    int availableWidth,
                    int rowWidth,
                    int y,
                    int rowHeight) {
                int x = getPaddingLeft() + Math.max(0, availableWidth - rowWidth);
                for (int index = start; index < end; index++) {
                    View child = getChildAt(index);
                    if (child.getVisibility() == GONE) {
                        continue;
                    }
                    int childWidth = Math.min(child.getMeasuredWidth(), availableWidth);
                    int childTop = y + Math.max(0, (rowHeight - child.getMeasuredHeight()) / 2);
                    child.layout(x, childTop, x + childWidth,
                            childTop + child.getMeasuredHeight());
                    x += childWidth + gap;
                }
            }
        }
    }
}
