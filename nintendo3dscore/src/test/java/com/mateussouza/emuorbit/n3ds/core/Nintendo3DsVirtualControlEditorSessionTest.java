// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public final class Nintendo3DsVirtualControlEditorSessionTest {
    @Test
    public void editsOnlyTheSelectedGroupAndOrientationThenRestoresDefaults() {
        Nintendo3DsVirtualControlEditorSession session =
                new Nintendo3DsVirtualControlEditorSession(
                        Nintendo3DsVirtualControlProfile.identity(),
                        Nintendo3DsVirtualControlOrientation.PORTRAIT);
        session.selectNextGroup();
        assertEquals(
                Nintendo3DsVirtualControlGroup.DIRECTIONAL,
                session.getSelectedGroup());
        session.setHorizontalOffset(0.5f);
        session.setVerticalOffset(-0.25f);
        session.setScale(1.4f);
        session.setVisible(false);

        Nintendo3DsVirtualControlTransform portrait = session.getSelectedTransform();
        assertEquals(0.5f, portrait.getNormalizedOffsetX(), 0.0f);
        assertEquals(-0.25f, portrait.getNormalizedOffsetY(), 0.0f);
        assertEquals(1.4f, portrait.getScale(), 0.0f);
        assertFalse(portrait.isVisible());

        session.setOrientation(Nintendo3DsVirtualControlOrientation.LANDSCAPE);
        assertSame(
                Nintendo3DsVirtualControlTransform.identity(),
                session.getSelectedTransform());
        session.selectPreviousGroup();
        assertEquals(
                Nintendo3DsVirtualControlGroup.CIRCLE_PAD,
                session.getSelectedGroup());
        session.resetToDefault();
        assertEquals(Nintendo3DsVirtualControlProfile.identity(), session.getDraft());
    }

    @Test
    public void restoresTheSelectedOrientationAndGroupWithTheDraft() {
        Nintendo3DsVirtualControlProfile draft =
                Nintendo3DsVirtualControlProfile.identity().withTransform(
                        Nintendo3DsVirtualControlOrientation.LANDSCAPE,
                        Nintendo3DsVirtualControlGroup.SCREEN_SWAP,
                        new Nintendo3DsVirtualControlTransform(0.3f, -0.2f, 1.1f, true));

        Nintendo3DsVirtualControlEditorSession restored =
                new Nintendo3DsVirtualControlEditorSession(
                        draft,
                        Nintendo3DsVirtualControlOrientation.LANDSCAPE,
                        Nintendo3DsVirtualControlGroup.SCREEN_SWAP);

        assertSame(draft, restored.getDraft());
        assertEquals(
                Nintendo3DsVirtualControlOrientation.LANDSCAPE,
                restored.getOrientation());
        assertEquals(Nintendo3DsVirtualControlGroup.SCREEN_SWAP, restored.getSelectedGroup());
        assertEquals(0.3f, restored.getSelectedTransform().getNormalizedOffsetX(), 0.0f);
    }
}
