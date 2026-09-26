// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.List;

public final class Nintendo3DsInteractiveInputPlanTest {
    @Test
    public void parsesBoundedRomAgnosticSteps() {
        List<Nintendo3DsInteractiveInputPlan.Step> steps =
                Nintendo3DsInteractiveInputPlan.parse(
                        "wait:250,button:a:650,circle:0.75:-0.5:1200,"
                                + "circle-button:1:0:a:300,"
                                + "circle-buttons:1:0:y:a:400,touch:0.5:0.75:80",
                        2_880);

        assertEquals(6, steps.size());
        assertEquals(Nintendo3DsInteractiveInputPlan.Kind.WAIT, steps.get(0).getKind());
        assertEquals(250, steps.get(0).getDurationMillis());
        assertEquals(Nintendo3DsButton.A, steps.get(1).getButton());
        assertEquals(650, steps.get(1).getDurationMillis());
        assertEquals(Nintendo3DsInteractiveInputPlan.Kind.CIRCLE, steps.get(2).getKind());
        assertEquals(0.75f, steps.get(2).getHorizontal(), 0.0f);
        assertEquals(-0.5f, steps.get(2).getVertical(), 0.0f);
        assertEquals(1_200, steps.get(2).getDurationMillis());
        assertEquals(Nintendo3DsInteractiveInputPlan.Kind.CIRCLE_BUTTON,
                steps.get(3).getKind());
        assertEquals(Nintendo3DsButton.A, steps.get(3).getButton());
        assertEquals(1.0f, steps.get(3).getHorizontal(), 0.0f);
        assertEquals(0.0f, steps.get(3).getVertical(), 0.0f);
        assertEquals(300, steps.get(3).getDurationMillis());
        assertEquals(Nintendo3DsInteractiveInputPlan.Kind.CIRCLE_BUTTONS,
                steps.get(4).getKind());
        assertEquals(Nintendo3DsButton.Y, steps.get(4).getButton());
        assertEquals(Nintendo3DsButton.A, steps.get(4).getSecondButton());
        assertEquals(1.0f, steps.get(4).getHorizontal(), 0.0f);
        assertEquals(0.0f, steps.get(4).getVertical(), 0.0f);
        assertEquals(400, steps.get(4).getDurationMillis());
        assertEquals(Nintendo3DsInteractiveInputPlan.Kind.TOUCH, steps.get(5).getKind());
        assertEquals(0.5f, steps.get(5).getHorizontal(), 0.0f);
        assertEquals(0.75f, steps.get(5).getVertical(), 0.0f);
        assertEquals(80, steps.get(5).getDurationMillis());
    }

    @Test
    public void acceptsAnEmptyOptionalPlan() {
        assertEquals(List.of(), Nintendo3DsInteractiveInputPlan.parse("  ", 0));
        assertEquals(List.of(), Nintendo3DsInteractiveInputPlan.parse(null, 0));
    }

    @Test
    public void rejectsMalformedOrUnboundedPlans() {
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse("tap:a:50", 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse("button:unknown:50", 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse("circle:NaN:0:50", 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse("circle:1.01:0:50", 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse(
                        "circle-button:0:0:unknown:50", 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse(
                        "circle-buttons:0:0:a:a:50", 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse("touch:-0.01:0.5:50", 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse("touch:0.5:1.01:50", 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse("wait:60001", 70_000));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse(
                        "wait:600,button:A:500", 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> Nintendo3DsInteractiveInputPlan.parse("wait:10,", 1_000));
    }
}
