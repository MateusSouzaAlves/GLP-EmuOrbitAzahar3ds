// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded, ROM-agnostic input script used only by the private debug harness. */
final class Nintendo3DsInteractiveInputPlan {
    private static final int MAX_ENCODED_CHARACTERS = 16_384;
    private static final int MAX_STEPS = 256;
    private static final long MAX_STEP_DURATION_MILLIS = 60_000;

    enum Kind {
        WAIT,
        BUTTON,
        CIRCLE,
        CIRCLE_BUTTON,
        CIRCLE_BUTTONS,
        TOUCH
    }

    static final class Step {
        private final Kind kind;
        private final long durationMillis;
        private final Nintendo3DsButton button;
        private final Nintendo3DsButton secondButton;
        private final float horizontal;
        private final float vertical;

        private Step(
                Kind kind,
                long durationMillis,
                Nintendo3DsButton button,
                float horizontal,
                float vertical) {
            this(kind, durationMillis, button, null, horizontal, vertical);
        }

        private Step(
                Kind kind,
                long durationMillis,
                Nintendo3DsButton button,
                Nintendo3DsButton secondButton,
                float horizontal,
                float vertical) {
            this.kind = kind;
            this.durationMillis = durationMillis;
            this.button = button;
            this.secondButton = secondButton;
            this.horizontal = horizontal;
            this.vertical = vertical;
        }

        Kind getKind() {
            return kind;
        }

        long getDurationMillis() {
            return durationMillis;
        }

        Nintendo3DsButton getButton() {
            return button;
        }

        Nintendo3DsButton getSecondButton() {
            return secondButton;
        }

        float getHorizontal() {
            return horizontal;
        }

        float getVertical() {
            return vertical;
        }
    }

    private Nintendo3DsInteractiveInputPlan() {
    }

    /**
     * Parses {@code wait:ms}, {@code button:name:ms}, and
     * {@code circle:x:y:ms}, {@code circle-button:x:y:name:ms},
     * {@code circle-buttons:x:y:first:second:ms}, and
     * {@code touch:surfaceX:surfaceY:ms} steps separated by commas. Touch coordinates are
     * normalized to the Android Surface. Commas keep the value
     * safe to pass as one {@code adb shell am instrument -e} argument.
     */
    static List<Step> parse(String encoded, long maximumTotalDurationMillis) {
        if (maximumTotalDurationMillis < 0) {
            throw new IllegalArgumentException("O limite total do plano não pode ser negativo.");
        }
        String normalized = encoded == null ? "" : encoded.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.length() > MAX_ENCODED_CHARACTERS) {
            throw new IllegalArgumentException("O plano de entrada excede o tamanho permitido.");
        }

        String[] tokens = normalized.split(",", -1);
        if (tokens.length > MAX_STEPS) {
            throw new IllegalArgumentException("O plano de entrada excede o número de passos.");
        }
        List<Step> steps = new ArrayList<>(tokens.length);
        long totalDurationMillis = 0;
        for (String token : tokens) {
            String[] fields = token.trim().split(":", -1);
            if (fields.length == 0 || fields[0].trim().isEmpty()) {
                throw new IllegalArgumentException("Passo vazio no plano de entrada.");
            }
            String operation = fields[0].trim().toLowerCase(Locale.ROOT);
            Step step;
            switch (operation) {
                case "wait":
                    requireFieldCount(fields, 2, operation);
                    step = new Step(
                            Kind.WAIT,
                            parseDuration(fields[1]),
                            null,
                            0.0f,
                            0.0f);
                    break;
                case "button":
                    requireFieldCount(fields, 3, operation);
                    step = new Step(
                            Kind.BUTTON,
                            parseDuration(fields[2]),
                            parseButton(fields[1]),
                            0.0f,
                            0.0f);
                    break;
                case "circle":
                    requireFieldCount(fields, 4, operation);
                    step = new Step(
                            Kind.CIRCLE,
                            parseDuration(fields[3]),
                            null,
                            parseAxis(fields[1]),
                            parseAxis(fields[2]));
                    break;
                case "circle-button":
                    requireFieldCount(fields, 5, operation);
                    step = new Step(
                            Kind.CIRCLE_BUTTON,
                            parseDuration(fields[4]),
                            parseButton(fields[3]),
                            parseAxis(fields[1]),
                            parseAxis(fields[2]));
                    break;
                case "circle-buttons":
                    requireFieldCount(fields, 6, operation);
                    Nintendo3DsButton firstButton = parseButton(fields[3]);
                    Nintendo3DsButton secondButton = parseButton(fields[4]);
                    if (firstButton == secondButton) {
                        throw new IllegalArgumentException(
                                "Os dois botões do passo combinado devem ser distintos.");
                    }
                    step = new Step(
                            Kind.CIRCLE_BUTTONS,
                            parseDuration(fields[5]),
                            firstButton,
                            secondButton,
                            parseAxis(fields[1]),
                            parseAxis(fields[2]));
                    break;
                case "touch":
                    requireFieldCount(fields, 4, operation);
                    step = new Step(
                            Kind.TOUCH,
                            parseDuration(fields[3]),
                            null,
                            parseTouchCoordinate(fields[1]),
                            parseTouchCoordinate(fields[2]));
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Operação desconhecida no plano de entrada: " + operation);
            }
            totalDurationMillis = Math.addExact(totalDurationMillis, step.durationMillis);
            if (totalDurationMillis > maximumTotalDurationMillis) {
                throw new IllegalArgumentException(
                        "O plano de entrada ultrapassa a janela interativa.");
            }
            steps.add(step);
        }
        return List.copyOf(steps);
    }

    private static void requireFieldCount(String[] fields, int expected, String operation) {
        if (fields.length != expected) {
            throw new IllegalArgumentException(
                    "Número de campos inválido para a operação " + operation + '.');
        }
    }

    private static long parseDuration(String encoded) {
        final long durationMillis;
        try {
            durationMillis = Long.parseLong(encoded.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Duração inválida no plano de entrada.", exception);
        }
        if (durationMillis <= 0 || durationMillis > MAX_STEP_DURATION_MILLIS) {
            throw new IllegalArgumentException(
                    "Cada passo deve durar entre 1 e 60000 ms.");
        }
        return durationMillis;
    }

    private static Nintendo3DsButton parseButton(String encoded) {
        try {
            return Nintendo3DsButton.valueOf(encoded.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Botão inválido no plano de entrada.", exception);
        }
    }

    private static float parseAxis(String encoded) {
        final float value;
        try {
            value = Float.parseFloat(encoded.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Eixo inválido no plano de entrada.", exception);
        }
        if (!Float.isFinite(value) || value < -1.0f || value > 1.0f) {
            throw new IllegalArgumentException("Eixo do plano deve estar entre -1 e 1.");
        }
        return value;
    }

    private static float parseTouchCoordinate(String encoded) {
        final float value;
        try {
            value = Float.parseFloat(encoded.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Coordenada de touch inválida no plano de entrada.", exception);
        }
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(
                    "Coordenada de touch deve estar entre 0 e 1.");
        }
        return value;
    }
}
