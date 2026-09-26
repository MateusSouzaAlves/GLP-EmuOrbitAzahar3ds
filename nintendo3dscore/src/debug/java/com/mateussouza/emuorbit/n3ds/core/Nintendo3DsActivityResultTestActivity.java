// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Debug-only Activity Result host used without exposing Nintendo 3DS in the base app. */
public final class Nintendo3DsActivityResultTestActivity extends ComponentActivity {
    public static final String EXTRA_CORE_PATH = "n3ds.activityResult.corePath";
    public static final String EXTRA_CONTENT_PATH = "n3ds.activityResult.contentPath";

    private final AtomicInteger styledDialogCount = new AtomicInteger();
    private final TestMiiPickerRegistration miiPickerRegistration =
            new TestMiiPickerRegistration();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "n3ds-activity-result-test");
        thread.setDaemon(true);
        return thread;
    });

    private Nintendo3DsExperimentalHost experimentalHost;
    private Nintendo3DsActivityResultHost activityResultHost;
    private Nintendo3DsExperimentalHost.PreparedLaunch deliveredLaunch;
    private Exception failure;
    private boolean callbackOnMainThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new FrameLayout(this));
        try {
            Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout.open(
                    getFilesDir(), getCacheDir());
            File existingMii = storage.getMiiDatabaseFile();
            if (existingMii.exists() && !existingMii.delete()) {
                throw new IOException("Could not reset the Activity Result test Mii database");
            }
            experimentalHost = new Nintendo3DsExperimentalHost(this, storage);
            activityResultHost = new Nintendo3DsActivityResultHost(
                    this,
                    experimentalHost,
                    new Nintendo3DsMiiRecoveryCoordinator.LaunchRequest(
                            new File(requireExtra(EXTRA_CORE_PATH)),
                            new File(requireExtra(EXTRA_CONTENT_PATH)),
                            Nintendo3DsLaunchReadiness.ContentStatus.READY,
                            0,
                            Nintendo3DsLaunchReadiness.MiiRequirement.REQUIRED,
                            true),
                    worker,
                    new Nintendo3DsReadinessDialogController.StyleAdapter() {
                        @Override
                        public int getThemeResource(
                                android.app.Activity activity,
                                Nintendo3DsReadinessPresentation.Model model) {
                            return 0;
                        }

                        @Override
                        public void onDialogShown(
                                android.app.Activity activity,
                                AlertDialog dialog,
                                Nintendo3DsReadinessPresentation.Model model) {
                            styledDialogCount.incrementAndGet();
                        }
                    },
                    miiPickerRegistration,
                    new Nintendo3DsActivityResultHost.Listener() {
                        @Override
                        public void onLaunchReady(
                                Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch) {
                            callbackOnMainThread = Looper.myLooper() == Looper.getMainLooper();
                            deliveredLaunch = preparedLaunch;
                        }

                        @Override
                        public void onExtractionRequested() {
                            failure = new IllegalStateException("Unexpected extraction request");
                        }

                        @Override
                        public void onUnderstood() {
                            failure = new IllegalStateException("Unexpected understood action");
                        }

                        @Override
                        public void onFailure(
                                Nintendo3DsReadinessPresentation.Action action,
                                Exception hostFailure) {
                            failure = hostFailure;
                        }
                    });
            if (!activityResultHost.start()) {
                throw new IllegalStateException("Could not start Activity Result host");
            }
        } catch (IOException storageFailure) {
            throw new IllegalStateException(
                    "Could not prepare Activity Result test storage", storageFailure);
        }
    }

    @Override
    protected void onDestroy() {
        if (activityResultHost != null) {
            activityResultHost.close();
        }
        worker.shutdownNow();
        if (experimentalHost != null) {
            try {
                experimentalHost.close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                }
            }
        }
        super.onDestroy();
    }

    public Nintendo3DsActivityResultHost getActivityResultHost() {
        return activityResultHost;
    }

    public Nintendo3DsExperimentalHost.PreparedLaunch getDeliveredLaunch() {
        return deliveredLaunch;
    }

    public Exception getFailure() {
        return failure;
    }

    public int getStyledDialogCount() {
        return styledDialogCount.get();
    }

    public boolean isCallbackOnMainThread() {
        return callbackOnMainThread;
    }

    public int getMiiPickerLaunchCount() {
        return miiPickerRegistration.getLaunchCount();
    }

    public Intent getLastMiiPickerIntent() {
        return miiPickerRegistration.getLastIntent();
    }

    public void deliverMiiPickerResult(int resultCode, Intent data) {
        miiPickerRegistration.deliver(resultCode, data);
    }

    public Nintendo3DsMiiDataManager.Status getMiiStatus() throws IOException {
        return experimentalHost.getMiiDataManagerForTesting().inspect().getStatus();
    }

    private String requireExtra(String name) {
        String value = getIntent().getStringExtra(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Required extra missing: " + name);
        }
        return value;
    }

    private static final class TestMiiPickerRegistration
            implements Nintendo3DsActivityResultHost.MiiPickerRegistration {
        private Nintendo3DsActivityResultHost.MiiPickerResultCallback callback;
        private Intent lastIntent;
        private int launchCount;

        @Override
        public Nintendo3DsActivityResultHost.MiiPickerLauncher register(
                Nintendo3DsActivityResultHost.MiiPickerResultCallback registeredCallback) {
            if (callback != null) {
                throw new IllegalStateException("Mii picker already registered");
            }
            callback = registeredCallback;
            return intent -> {
                launchCount++;
                lastIntent = new Intent(intent);
            };
        }

        private int getLaunchCount() {
            return launchCount;
        }

        private Intent getLastIntent() {
            return lastIntent == null ? null : new Intent(lastIntent);
        }

        private void deliver(int resultCode, Intent data) {
            if (callback == null) {
                throw new IllegalStateException("Mii picker result callback is not registered");
            }
            callback.onResult(resultCode, data);
        }
    }
}
