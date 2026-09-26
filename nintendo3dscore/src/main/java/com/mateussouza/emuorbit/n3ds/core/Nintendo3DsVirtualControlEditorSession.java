// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Objects;

/** In-memory draft for one per-game Nintendo 3DS control layout. */
final class Nintendo3DsVirtualControlEditorSession {
    private Nintendo3DsVirtualControlProfile draft;
    private Nintendo3DsVirtualControlOrientation orientation;
    private Nintendo3DsVirtualControlGroup selectedGroup =
            Nintendo3DsVirtualControlGroup.CIRCLE_PAD;

    Nintendo3DsVirtualControlEditorSession(
            Nintendo3DsVirtualControlProfile initial,
            Nintendo3DsVirtualControlOrientation orientation) {
        this(initial, orientation, Nintendo3DsVirtualControlGroup.CIRCLE_PAD);
    }

    Nintendo3DsVirtualControlEditorSession(
            Nintendo3DsVirtualControlProfile initial,
            Nintendo3DsVirtualControlOrientation orientation,
            Nintendo3DsVirtualControlGroup selectedGroup) {
        draft = Objects.requireNonNull(initial);
        this.orientation = Objects.requireNonNull(orientation);
        this.selectedGroup = Objects.requireNonNull(selectedGroup);
    }

    Nintendo3DsVirtualControlProfile getDraft() {
        return draft;
    }

    Nintendo3DsVirtualControlOrientation getOrientation() {
        return orientation;
    }

    void setOrientation(Nintendo3DsVirtualControlOrientation orientation) {
        this.orientation = Objects.requireNonNull(orientation);
    }

    Nintendo3DsVirtualControlGroup getSelectedGroup() {
        return selectedGroup;
    }

    void selectPreviousGroup() {
        Nintendo3DsVirtualControlGroup[] groups = Nintendo3DsVirtualControlGroup.values();
        int index = (selectedGroup.ordinal() + groups.length - 1) % groups.length;
        selectedGroup = groups[index];
    }

    void selectNextGroup() {
        Nintendo3DsVirtualControlGroup[] groups = Nintendo3DsVirtualControlGroup.values();
        selectedGroup = groups[(selectedGroup.ordinal() + 1) % groups.length];
    }

    Nintendo3DsVirtualControlTransform getSelectedTransform() {
        return draft.getTransform(orientation, selectedGroup);
    }

    void setHorizontalOffset(float value) {
        Nintendo3DsVirtualControlTransform current = getSelectedTransform();
        replaceSelectedTransform(new Nintendo3DsVirtualControlTransform(
                value,
                current.getNormalizedOffsetY(),
                current.getScale(),
                current.isVisible()));
    }

    void setVerticalOffset(float value) {
        Nintendo3DsVirtualControlTransform current = getSelectedTransform();
        replaceSelectedTransform(new Nintendo3DsVirtualControlTransform(
                current.getNormalizedOffsetX(),
                value,
                current.getScale(),
                current.isVisible()));
    }

    void setScale(float value) {
        Nintendo3DsVirtualControlTransform current = getSelectedTransform();
        replaceSelectedTransform(new Nintendo3DsVirtualControlTransform(
                current.getNormalizedOffsetX(),
                current.getNormalizedOffsetY(),
                value,
                current.isVisible()));
    }

    void setVisible(boolean visible) {
        replaceSelectedTransform(getSelectedTransform().withVisible(visible));
    }

    void resetToDefault() {
        draft = Nintendo3DsVirtualControlProfile.identity();
    }

    private void replaceSelectedTransform(Nintendo3DsVirtualControlTransform transform) {
        draft = draft.withTransform(orientation, selectedGroup, transform);
    }
}
