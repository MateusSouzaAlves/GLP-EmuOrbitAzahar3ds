// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;

import java.io.IOException;

/** Debug-only host used to verify readiness rendering without exposing Nintendo 3DS. */
public final class Nintendo3DsReadinessTestActivity extends Activity {
    public static final String EXTRA_SCENARIO = "n3ds.readinessScenario";
    public static final String SCENARIO_MII_FALLBACK = "mii_fallback";
    public static final String SCENARIO_WARNING_MII = "warning_mii";
    public static final String SCENARIO_STORAGE_LOW = "storage_low";
    public static final String SCENARIO_ARCHIVE = "archive";
    public static final String SCENARIO_CORE_MISSING = "core_missing";
    public static final String SCENARIO_ENCRYPTED = "encrypted";
    public static final String SCENARIO_READY = "ready";

    private final Nintendo3DsReadinessDialogController dialogController =
            new Nintendo3DsReadinessDialogController(
                    new Nintendo3DsProductDialogStyleAdapter());
    private Nintendo3DsReadinessPresentation.Action dispatchedAction =
            Nintendo3DsReadinessPresentation.Action.NONE;
    private int dispatchCount;
    private Intent externalIntent;
    private int externalIntentCount;
    private Exception actionFailure;
    private Nintendo3DsExperimentalHost experimentalHost;
    private boolean showResult;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Nintendo3DsUiTestConfiguration.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new FrameLayout(this));
        try {
            Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                    getFilesDir(), getCacheDir());
            experimentalHost = new Nintendo3DsExperimentalHost(this, storage);
            Nintendo3DsReadinessActionCoordinator actionCoordinator =
                    new Nintendo3DsReadinessActionCoordinator(
                            getPackageName(),
                            experimentalHost,
                            intent -> {
                                externalIntent = intent;
                                externalIntentCount++;
                            },
                            new Nintendo3DsReadinessActionCoordinator.Callback() {
                                @Override
                                public void onActionRequested(
                                        Nintendo3DsReadinessPresentation.Action action) {
                                    dispatchedAction = action;
                                    dispatchCount++;
                                }

                                @Override
                                public void onActionFailed(
                                        Nintendo3DsReadinessPresentation.Action action,
                                        Exception failure) {
                                    dispatchedAction = action;
                                    actionFailure = failure;
                                }
                            });
            Nintendo3DsReadinessPresentation.Model model = modelFor(
                    getIntent().getStringExtra(EXTRA_SCENARIO));
            showResult = dialogController.show(this, model, actionCoordinator);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not prepare readiness test storage", failure);
        }
    }

    @Override
    protected void onDestroy() {
        dialogController.close();
        if (experimentalHost != null) {
            try {
                experimentalHost.close();
            } catch (IOException failure) {
                if (actionFailure == null) {
                    actionFailure = failure;
                }
            }
        }
        super.onDestroy();
    }

    public Nintendo3DsReadinessDialogController getDialogController() {
        return dialogController;
    }

    public Nintendo3DsReadinessPresentation.Action getDispatchedAction() {
        return dispatchedAction;
    }

    public int getDispatchCount() {
        return dispatchCount;
    }

    public Intent getExternalIntent() {
        return externalIntent;
    }

    public int getExternalIntentCount() {
        return externalIntentCount;
    }

    public Exception getActionFailure() {
        return actionFailure;
    }

    public boolean getShowResult() {
        return showResult;
    }

    private static Nintendo3DsReadinessPresentation.Model modelFor(String scenario) {
        Nintendo3DsLaunchReadiness.MiiRequirement requirement;
        Nintendo3DsMiiDataManager.Status miiStatus;
        boolean deviceQualified;
        Nintendo3DsLaunchReadiness.CoreStatus coreStatus =
                Nintendo3DsLaunchReadiness.CoreStatus.READY;
        Nintendo3DsLaunchReadiness.ContentStatus contentStatus =
                Nintendo3DsLaunchReadiness.ContentStatus.READY;
        long availableBytes = 64L * 1024L * 1024L;
        long requiredBytes = 32L * 1024L * 1024L;
        if (SCENARIO_MII_FALLBACK.equals(scenario)) {
            requirement = Nintendo3DsLaunchReadiness.MiiRequirement.REQUIRED;
            miiStatus = Nintendo3DsMiiDataManager.Status.MISSING;
            deviceQualified = true;
        } else if (SCENARIO_WARNING_MII.equals(scenario)) {
            requirement = Nintendo3DsLaunchReadiness.MiiRequirement.OPTIONAL;
            miiStatus = Nintendo3DsMiiDataManager.Status.EMPTY;
            deviceQualified = false;
        } else if (SCENARIO_STORAGE_LOW.equals(scenario)) {
            requirement = Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED;
            miiStatus = Nintendo3DsMiiDataManager.Status.MISSING;
            deviceQualified = true;
            availableBytes = 1;
        } else if (SCENARIO_ARCHIVE.equals(scenario)) {
            requirement = Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED;
            miiStatus = Nintendo3DsMiiDataManager.Status.MISSING;
            deviceQualified = true;
            contentStatus =
                    Nintendo3DsLaunchReadiness.ContentStatus.ARCHIVE_REQUIRES_EXTRACTION;
        } else if (SCENARIO_CORE_MISSING.equals(scenario)) {
            requirement = Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED;
            miiStatus = Nintendo3DsMiiDataManager.Status.MISSING;
            deviceQualified = true;
            coreStatus = Nintendo3DsLaunchReadiness.CoreStatus.MISSING;
        } else if (SCENARIO_ENCRYPTED.equals(scenario)) {
            requirement = Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED;
            miiStatus = Nintendo3DsMiiDataManager.Status.MISSING;
            deviceQualified = true;
            contentStatus = Nintendo3DsLaunchReadiness.ContentStatus.ENCRYPTED;
        } else if (SCENARIO_READY.equals(scenario)) {
            requirement = Nintendo3DsLaunchReadiness.MiiRequirement.NOT_REQUIRED;
            miiStatus = Nintendo3DsMiiDataManager.Status.MISSING;
            deviceQualified = true;
        } else {
            throw new IllegalArgumentException("Unknown readiness test scenario: " + scenario);
        }
        return Nintendo3DsReadinessPresentation.from(
                Nintendo3DsLaunchReadiness.evaluate(new Nintendo3DsLaunchReadiness.Probe(
                        coreStatus,
                        true,
                        true,
                        contentStatus,
                        availableBytes,
                        requiredBytes,
                        requirement,
                        miiStatus,
                        deviceQualified)));
    }
}
