// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Maps Android Surface coordinates through the exact aspect-fit used by the Vulkan host. */
public final class Nintendo3DsTouchMapper {
    private Nintendo3DsTouchMapper() {
    }

    public static Result mapToFrame(
            float surfaceX,
            float surfaceY,
            int surfaceWidth,
            int surfaceHeight,
            int frameWidth,
            int frameHeight) {
        if (!Float.isFinite(surfaceX) || !Float.isFinite(surfaceY)) {
            throw new IllegalArgumentException("As coordenadas de toque 3DS devem ser finitas.");
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("As dimensões de toque 3DS devem ser positivas.");
        }

        int contentWidth = surfaceWidth;
        int contentHeight = surfaceHeight;
        int contentLeft = 0;
        int contentTop = 0;
        long fitWidth = (long) surfaceHeight * frameWidth / frameHeight;
        if (fitWidth <= surfaceWidth) {
            contentWidth = (int) fitWidth;
            contentLeft = (surfaceWidth - contentWidth) / 2;
        } else {
            contentHeight = (int) ((long) surfaceWidth * frameHeight / frameWidth);
            contentTop = (surfaceHeight - contentHeight) / 2;
        }
        if (contentWidth <= 0 || contentHeight <= 0) {
            throw new IllegalArgumentException("A proporção do frame 3DS não cabe na Surface.");
        }

        int contentRight = contentLeft + contentWidth;
        int contentBottom = contentTop + contentHeight;
        boolean inside = surfaceX >= contentLeft
                && surfaceX <= contentRight
                && surfaceY >= contentTop
                && surfaceY <= contentBottom;
        int frameX = 0;
        int frameY = 0;
        if (inside) {
            frameX = mapCoordinate(surfaceX - contentLeft, contentWidth, frameWidth);
            frameY = mapCoordinate(surfaceY - contentTop, contentHeight, frameHeight);
        }
        return new Result(
                inside,
                frameX,
                frameY,
                contentLeft,
                contentTop,
                contentRight,
                contentBottom);
    }

    private static int mapCoordinate(float coordinate, int contentExtent, int frameExtent) {
        int mapped = (int) (coordinate * frameExtent / contentExtent);
        return Math.max(0, Math.min(mapped, frameExtent - 1));
    }

    public static final class Result {
        private final boolean insideFrame;
        private final int frameX;
        private final int frameY;
        private final int contentLeft;
        private final int contentTop;
        private final int contentRight;
        private final int contentBottom;

        Result(
                boolean insideFrame,
                int frameX,
                int frameY,
                int contentLeft,
                int contentTop,
                int contentRight,
                int contentBottom) {
            this.insideFrame = insideFrame;
            this.frameX = frameX;
            this.frameY = frameY;
            this.contentLeft = contentLeft;
            this.contentTop = contentTop;
            this.contentRight = contentRight;
            this.contentBottom = contentBottom;
        }

        public boolean isInsideFrame() {
            return insideFrame;
        }

        public int getFrameX() {
            return frameX;
        }

        public int getFrameY() {
            return frameY;
        }

        public int getContentLeft() {
            return contentLeft;
        }

        public int getContentTop() {
            return contentTop;
        }

        public int getContentRight() {
            return contentRight;
        }

        public int getContentBottom() {
            return contentBottom;
        }
    }
}
