// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.RequiresApi;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.play.core.splitcompat.SplitCompat;
import com.mateussouza.emuorbit.advance.nintendo3ds.delivery.Nintendo3DsAndroidCompatibility;
import com.mateussouza.emuorbit.advance.nintendo3ds.delivery.Nintendo3DsFeatureContract;
import com.mateussouza.emuorbit.advance.sharing.SharingFeatureGate;
import com.mateussouza.emuorbit.advance.data.preferences.GamepadVirtualControlsPreferenceStore;
import com.mateussouza.emuorbit.advance.databinding.ViewBugReportInputBinding;
import com.mateussouza.emuorbit.advance.domain.model.EmulatorSystem;
import com.mateussouza.emuorbit.advance.ui.MateusDialog;
import com.mateussouza.emuorbit.advance.ui.emulation.input.ExternalGamepadMonitor;
import com.mateussouza.emuorbit.advance.ui.emulation.input.GamepadVirtualControlsCoordinator;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hidden product-ready host for readiness, settings, input, Surface and native lifecycle.
 *
 * <p>The Activity is deliberately not exported and the base app does not depend on this module.
 * The install-time feature is launched through
 * {@link Nintendo3DsProductLaunchRequest} after the N3DS-12 packaging decision.</p>
 */
@RequiresApi(Nintendo3DsAndroidCompatibility.MINIMUM_SDK)
public final class Nintendo3DsProductActivity extends ComponentActivity
        implements SurfaceHolder.Callback,
        Nintendo3DsActivityResultHost.Listener,
        Nintendo3DsExperienceSettingsDialogController.Listener {
    public static final String EXTRA_RESULT_ACTION = "n3ds.product.result_action";
    public static final String RESULT_ACTION_EXTRACT_CONTENT = "extract_content";

    private static final int DEFAULT_FRAME_WIDTH = 400;
    private static final int DEFAULT_FRAME_HEIGHT = 480;
    private static final int FRAME_LOOP_BATCH_SIZE = 2;
    private static final String STATE_AUDIO_ENABLED = "n3ds.product.audio_enabled";
    private static final String STATE_SPEED_MULTIPLIER = "n3ds.product.speed_multiplier";
    private static final String STATE_MANUALLY_PAUSED = "n3ds.product.manually_paused";
    private static final String STATE_PENDING_ACTION = "n3ds.product.pending_action";
    private static final String STATE_REPORT_DRAFT = "n3ds.product.report_draft";
    private static final String STATE_REPORT_VALIDATION_FAILED =
            "n3ds.product.report_validation_failed";
    private static final String STATE_CONTROL_EDITOR_OPEN =
            "n3ds.product.control_editor_open";
    private static final String STATE_CONTROL_EDITOR_DRAFT =
            "n3ds.product.control_editor_draft";
    private static final String STATE_CONTROL_EDITOR_ORIENTATION =
            "n3ds.product.control_editor_orientation";
    private static final String STATE_CONTROL_EDITOR_GROUP =
            "n3ds.product.control_editor_group";
    private static final String STATE_PHONE_ORIENTATION = "n3ds.product.phone_orientation";
    static final int MENU_SETTINGS_ID = 1;
    static final int MENU_RESET_ID = 2;
    static final int MENU_SHARE_ID = 3;
    static final int MENU_REPORT_ID = 4;
    static final int MENU_CUSTOMIZE_CONTROLS_ID = 5;
    static final int MENU_AUDIO_ID = 6;
    static final int MENU_GAMEPAD_CONTROLS_ID = 7;

    private final Nintendo3DsFrameLoopGate frameLoopGate = new Nintendo3DsFrameLoopGate();
    private final AtomicLong renderedFrames = new AtomicLong();
    private final AtomicLong routedInputEvents = new AtomicLong();
    private final Map<Integer, Button> actionMenuButtons = new LinkedHashMap<>();
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(runnable ->
            daemonThread(runnable, "n3ds-product-preflight"));
    private final ExecutorService frameExecutor = Executors.newSingleThreadExecutor(runnable ->
            displayDaemonThread(runnable, "n3ds-product-frames"));
    private final Nintendo3DsTransitionQueue transitions =
            new Nintendo3DsTransitionQueue(workerExecutor, this::runOnUiThread);

    private ActivityResultLauncher<String> microphonePermissionLauncher;
    private ActivityResultLauncher<Intent> miiImportLauncher;
    private Nintendo3DsActivityResultHost.MiiPickerResultCallback miiPickerResultCallback;
    private androidx.activity.result.ActivityResult pendingMiiPickerResult;
    private Nintendo3DsProductLaunchRequest launchRequest;
    private Nintendo3DsExperimentalHost experimentalHost;
    private com.mateussouza.emuorbit.advance.data.save.sync.SaveSyncRuntime.ForegroundPreparationLease protectionLease;
    private Nintendo3DsActivityResultHost activityResultHost;
    private Nintendo3DsExperienceSettingsStore settingsStore;
    private Nintendo3DsVirtualControlProfileStore virtualControlProfileStore;
    private Nintendo3DsVirtualControlEditorController virtualControlEditorController;
    private Nintendo3DsExperienceSettingsDialogController settingsDialogController;
    private Nintendo3DsProductDialogStyleAdapter dialogStyleAdapter;
    private Nintendo3DsCoreLifecycleController controller;
    private Nintendo3DsPhysicalControllerRouter inputRouter;
    private ExternalGamepadMonitor externalGamepadMonitor;
    private GamepadVirtualControlsCoordinator gamepadVirtualControlsCoordinator;
    private Nintendo3DsSurfaceFrameCapture surfaceFrameCapture;
    private Nintendo3DsBaseProductBridge baseProductBridge;
    private Nintendo3DsUsageBridge usage;
    private SurfaceHolder surfaceHolder;
    private SurfaceView surfaceView;
    private volatile Surface currentSurface;
    private Nintendo3DsOwnedLayer ownedLayer;
    private int currentSurfaceWidth;
    private int currentSurfaceHeight;
    private volatile int currentFrameWidth = DEFAULT_FRAME_WIDTH;
    private volatile int currentFrameHeight = DEFAULT_FRAME_HEIGHT;
    private Nintendo3DsProductUiState uiState = Nintendo3DsProductUiState.preparing();
    private TextView titleView;
    private TextView statusView;
    private Button backButton;
    private Button pauseButton;
    private Button audioButton;
    private Button speedButton;
    private Button orientationButton;
    private Button menuButton;
    private FrameLayout productShell;
    private LinearLayout shellIdentity;
    private LinearLayout shellQuickActions;
    private boolean shellLandscape;
    private int phoneOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    private androidx.core.graphics.Insets shellInsets = androidx.core.graphics.Insets.NONE;
    private Nintendo3DsScreenLayout preferredScreenLayout = Nintendo3DsScreenLayout.DEFAULT;
    private FrameLayout contentRoot;
    private GridLayout actionsMenuPanel;
    private ScrollView actionsMenuContainer;
    private PopupMenu activeActionsMenu;
    private AlertDialog activeProductDialog;
    private AlertDialog gamepadVirtualControlsDialog;
    private GamepadVirtualControlsPreferenceStore gamepadVirtualControlsPreferenceStore;
    private AlertDialog activeReportDialog;
    private EditText activeReportInput;
    private com.google.android.material.textfield.TextInputLayout activeReportInputLayout;
    private Nintendo3DsVirtualControlsOverlay virtualControls;
    private volatile boolean hostResumed;
    private volatile long lifecycleRevision;
    private long settingsRevision;
    private boolean controllerResumed;
    private boolean microphonePermissionPending;
    private boolean microphonePermissionInFlight;
    private boolean shareInFlight;
    private boolean externalGamepadConnected;
    private boolean reportOwnsPause;
    private boolean reportValidationFailed;
    private Map<String, String> activeReportContext = Collections.emptyMap();
    private int lastFeedbackResource;
    private Toast activeFeedbackToast;
    private volatile Throwable productFailure;
    private ProductAction activeConfirmationAction = ProductAction.NONE;
    private ProductAction pendingRestoredAction = ProductAction.NONE;
    private boolean hasRestoredSessionState;
    private boolean restoredAudioEnabled = true;
    private int restoredSpeedMultiplier = 1;
    private boolean restoredManualPause;
    private String restoredReportDraft = "";
    private boolean restoredReportValidationFailed;
    private boolean virtualControlsEnabledBySettings = true;
    private int virtualControlsOpacityPercent =
            Nintendo3DsExperienceSettings.DEFAULT_VIRTUAL_CONTROL_OPACITY;
    private boolean gamepadVirtualControlsVisible = true;
    private Nintendo3DsVirtualControlProfile restoredControlEditorDraft;
    private Nintendo3DsVirtualControlOrientation restoredControlEditorOrientation;
    private Nintendo3DsVirtualControlGroup restoredControlEditorGroup;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (Nintendo3DsProductRuntimePolicy.requiresSplitCompat(base.getPackageName())) {
            SplitCompat.installActivity(this);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Defense for restored/direct internal intents, before saves, ROMs or graphics.
        if (!Nintendo3DsFeatureContract.isDeviceSupported()) {
            Toast.makeText(this,
                    com.mateussouza.emuorbit.advance.R.string.n3ds_android_version_required,
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        usage = new Nintendo3DsUsageBridge(this);
        WindowCompat.enableEdgeToEdge(getWindow());
        protectionLease = com.mateussouza.emuorbit.advance.data.save.sync.SaveSyncRuntime.beginForegroundPreparation();
        restoreTransientState(savedInstanceState);
        applyWindowOrientation(getResources().getConfiguration(), isInMultiWindowMode());
        microphonePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::onMicrophonePermissionResult);
        // Register before STARTED even when storage preparation is asynchronous.
        // Keep a restored picker result until the actual host is ready.
        miiImportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    pendingMiiPickerResult = result;
                    deliverPendingMiiPickerResult();
                });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackAction();
            }
        });

        try {
            launchRequest = Nintendo3DsProductLaunchRequest.fromIntent(getIntent());
            settingsStore = new Nintendo3DsExperienceSettingsStore(this);
            virtualControlProfileStore = new Nintendo3DsVirtualControlProfileStore(this);
            gamepadVirtualControlsPreferenceStore =
                    new GamepadVirtualControlsPreferenceStore(this);
            gamepadVirtualControlsCoordinator = new GamepadVirtualControlsCoordinator(
                    EmulatorSystem.N3DS,
                    gamepadVirtualControlsPreferenceStore,
                    new GamepadVirtualControlsCoordinator.Listener() {
                        @Override
                        public boolean onChoiceRequired() {
                            return showGamepadVirtualControlsChoice();
                        }

                        @Override
                        public void onControlsVisibilityChanged(
                                boolean visible, boolean gamepadConnected) {
                            boolean connectionChanged =
                                    externalGamepadConnected != gamepadConnected;
                            gamepadVirtualControlsVisible = visible;
                            externalGamepadConnected = gamepadConnected;
                            if (connectionChanged) {
                                closeActionsMenu();
                            }
                            applyVirtualControlsPresentation();
                            bindProductUi();
                        }

                        @Override
                        public void onPreferenceSaveFailed() {
                            showProductFeedback(R.string.n3ds_gamepad_controls_save_failed);
                        }
                    });
            externalGamepadMonitor = new ExternalGamepadMonitor(
                    this,
                    this::onExternalGamepadConnectionChanged);
            dialogStyleAdapter = new Nintendo3DsProductDialogStyleAdapter();
            surfaceFrameCapture = new Nintendo3DsSurfaceFrameCapture();
            baseProductBridge = new Nintendo3DsBaseProductBridge(this);
            settingsDialogController = new Nintendo3DsExperienceSettingsDialogController(
                    settingsStore,
                    dialogStyleAdapter);
        } catch (IllegalArgumentException failure) {
            failAndFinish(failure);
            return;
        }

        setContentView(createContentView());
        virtualControlEditorController = new Nintendo3DsVirtualControlEditorController(
                this,
                contentRoot,
                virtualControlProfileStore,
                new Nintendo3DsVirtualControlEditorController.Listener() {
                    @Override
                    public void onPreview(
                            Nintendo3DsVirtualControlProfile profile,
                            Nintendo3DsVirtualControlOrientation orientation) {
                        virtualControls.applyLayoutProfile(profile, orientation);
                    }

                    @Override
                    public void onSaved() {
                        restoreResolvedVirtualControlProfile();
                        applyVirtualControlsPresentation();
                        bindProductUi();
                        showProductFeedback(R.string.n3ds_control_editor_saved);
                    }

                    @Override
                    public void onCancelled() {
                        restoreResolvedVirtualControlProfile();
                        applyVirtualControlsPresentation();
                        bindProductUi();
                    }

                    @Override
                    public void onSaveFailed() {
                        showProductFeedback(R.string.n3ds_control_editor_save_failed);
                    }
                });
        startStoragePreparation();
    }

    private void startStoragePreparation() {
        java.io.File files = getFilesDir(), cache = getCacheDir();
        // Keep the existing PREPARING shell interactive (including Back).
        // Only storage setup waits for an admitted background copy to retire.
        workerExecutor.execute(() -> {
            try {
                Nintendo3DsStorageLayout storage = Nintendo3DsStorageLayout
                        .openAfterStoppedCopy(files, cache);
                runOnUiThread(() -> finishStoragePreparation(storage));
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) failAndFinish(failure);
                });
            }
        });
    }

    private void finishStoragePreparation(Nintendo3DsStorageLayout storage) {
        // A queued callback cannot create a new host after Back/destruction.
        if (isFinishing() || isDestroyed()) return;
        try {
            experimentalHost = new Nintendo3DsExperimentalHost(this, storage,
                    launchRequest.getPersistentId());
            activityResultHost = new Nintendo3DsActivityResultHost(
                    this, experimentalHost,
                    new Nintendo3DsMiiRecoveryCoordinator.LaunchRequest(
                            launchRequest.getCoreLibrary(), launchRequest.getContent(),
                            launchRequest.getContentStatus(), launchRequest.getRequiredPrivateStorageBytes(),
                            launchRequest.getMiiRequirement(), launchRequest.isDeviceQualified()),
                    workerExecutor, dialogStyleAdapter, callback -> {
                        miiPickerResultCallback = callback;
                        return miiImportLauncher::launch;
                    }, this);
            activityResultHost.start();
            deliverPendingMiiPickerResult();
        } catch (RuntimeException failure) {
            failAndFinish(failure);
        }
    }

    private void deliverPendingMiiPickerResult() {
        if (pendingMiiPickerResult == null || miiPickerResultCallback == null
                || activityResultHost == null || isFinishing() || isDestroyed()) return;
        var result = pendingMiiPickerResult;
        pendingMiiPickerResult = null;
        miiPickerResultCallback.onResult(result.getResultCode(), result.getData());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        boolean hasLiveSession = uiState.getSessionState()
                != Nintendo3DsProductUiState.SessionState.PREPARING;
        outState.putBoolean(STATE_AUDIO_ENABLED,
                hasLiveSession ? uiState.isAudioEnabled() : restoredAudioEnabled);
        outState.putInt(STATE_SPEED_MULTIPLIER,
                hasLiveSession ? uiState.getSpeedMultiplier() : restoredSpeedMultiplier);

        ProductAction pendingAction = pendingProductAction();
        boolean reportPause = pendingAction == ProductAction.REPORT && reportOwnsPause;
        outState.putBoolean(STATE_MANUALLY_PAUSED,
                uiState.canControlSession()
                        ? uiState.isPaused() && !reportPause
                        : restoredManualPause);
        outState.putString(STATE_PENDING_ACTION, pendingAction.name());
        CharSequence reportDraft = activeReportInput == null
                ? restoredReportDraft
                : activeReportInput.getText();
        outState.putString(STATE_REPORT_DRAFT,
                reportDraft == null ? "" : reportDraft.toString());
        outState.putBoolean(
                STATE_REPORT_VALIDATION_FAILED,
                activeReportDialog != null
                        ? reportValidationFailed
                        : restoredReportValidationFailed);
        saveControlEditorState(outState);
        outState.putInt(STATE_PHONE_ORIENTATION, phoneOrientation);
    }

    private void restoreTransientState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        phoneOrientation = Nintendo3DsWindowOrientationPolicy.phoneChoice(
                savedInstanceState.getInt(STATE_PHONE_ORIENTATION,
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        boolean audioEnabled = savedInstanceState.getBoolean(STATE_AUDIO_ENABLED, true);
        int speedMultiplier = savedInstanceState.getInt(STATE_SPEED_MULTIPLIER, 1);
        boolean manuallyPaused = savedInstanceState.getBoolean(
                STATE_MANUALLY_PAUSED,
                false);
        try {
            Nintendo3DsProductUiState.restoredSession(
                    audioEnabled,
                    speedMultiplier,
                    manuallyPaused);
            restoredSpeedMultiplier = speedMultiplier;
        } catch (IllegalArgumentException invalid) {
            restoredSpeedMultiplier = 1;
        }
        restoredAudioEnabled = audioEnabled;
        restoredManualPause = manuallyPaused;
        pendingRestoredAction = parseProductAction(
                savedInstanceState.getString(STATE_PENDING_ACTION));
        restoredReportDraft = savedInstanceState.getString(STATE_REPORT_DRAFT, "");
        restoredReportValidationFailed = savedInstanceState.getBoolean(
                STATE_REPORT_VALIDATION_FAILED,
                false);
        restoreControlEditorState(savedInstanceState);
        hasRestoredSessionState = true;
    }

    private void saveControlEditorState(Bundle outState) {
        if (virtualControlEditorController == null
                || !virtualControlEditorController.isShowing()) {
            return;
        }
        Nintendo3DsVirtualControlEditorSession editor =
                virtualControlEditorController.getSession();
        try {
            outState.putByteArray(
                    STATE_CONTROL_EDITOR_DRAFT,
                    Nintendo3DsVirtualControlProfileCodec.encode(editor.getDraft()));
            outState.putString(
                    STATE_CONTROL_EDITOR_ORIENTATION,
                    editor.getOrientation().name());
            outState.putString(
                    STATE_CONTROL_EDITOR_GROUP,
                    editor.getSelectedGroup().name());
            outState.putBoolean(STATE_CONTROL_EDITOR_OPEN, true);
        } catch (IOException invalidDraft) {
            outState.remove(STATE_CONTROL_EDITOR_DRAFT);
            outState.putBoolean(STATE_CONTROL_EDITOR_OPEN, false);
        }
    }

    private void restoreControlEditorState(Bundle savedInstanceState) {
        if (!savedInstanceState.getBoolean(STATE_CONTROL_EDITOR_OPEN, false)) {
            return;
        }
        byte[] encoded = savedInstanceState.getByteArray(STATE_CONTROL_EDITOR_DRAFT);
        Nintendo3DsVirtualControlProfileCodec.Result decoded =
                Nintendo3DsVirtualControlProfileCodec.decode(encoded);
        if (decoded.getStatus() != Nintendo3DsVirtualControlProfileCodec.Status.VALID) {
            return;
        }
        restoredControlEditorDraft = decoded.getProfile();
        restoredControlEditorOrientation = parseControlOrientation(
                savedInstanceState.getString(STATE_CONTROL_EDITOR_ORIENTATION));
        restoredControlEditorGroup = parseControlGroup(
                savedInstanceState.getString(STATE_CONTROL_EDITOR_GROUP));
    }

    private ProductAction pendingProductAction() {
        if (activeReportDialog != null && activeReportDialog.isShowing()) {
            return ProductAction.REPORT;
        }
        if (activeProductDialog != null && activeProductDialog.isShowing()) {
            return activeConfirmationAction;
        }
        return pendingRestoredAction;
    }

    private void restorePendingProductAction() {
        if (!uiState.canControlSession()) {
            return;
        }
        ProductAction pendingAction = pendingRestoredAction;
        pendingRestoredAction = ProductAction.NONE;
        if (pendingAction == ProductAction.EXIT) {
            confirmExit();
            return;
        }
        if (pendingAction == ProductAction.RESET) {
            confirmReset();
            return;
        }
        if (pendingAction == ProductAction.FAILURE) {
            showSessionFailure(new IllegalStateException(
                    "A falha anterior da sessão Nintendo 3DS foi restaurada."));
            return;
        }
        if (pendingAction != ProductAction.REPORT) {
            return;
        }

        String draft = restoredReportDraft;
        boolean validationFailed = restoredReportValidationFailed;
        restoredReportDraft = "";
        restoredReportValidationFailed = false;
        showProblemReport();
        if (activeReportInput == null) {
            return;
        }
        activeReportInput.setText(draft);
        activeReportInput.setSelection(activeReportInput.length());
        if (validationFailed) {
            reportValidationFailed = true;
            if (activeReportInputLayout != null) {
                activeReportInputLayout.setError(
                        getString(R.string.n3ds_product_report_required));
            }
        }
    }

    private static ProductAction parseProductAction(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return ProductAction.NONE;
        }
        try {
            ProductAction action = ProductAction.valueOf(serialized);
            return action == ProductAction.NONE ? ProductAction.NONE : action;
        } catch (IllegalArgumentException ignored) {
            return ProductAction.NONE;
        }
    }

    private static Nintendo3DsVirtualControlOrientation parseControlOrientation(
            String serialized) {
        try {
            return Nintendo3DsVirtualControlOrientation.valueOf(serialized);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Nintendo3DsVirtualControlOrientation.PORTRAIT;
        }
    }

    private static Nintendo3DsVirtualControlGroup parseControlGroup(String serialized) {
        try {
            return Nintendo3DsVirtualControlGroup.valueOf(serialized);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Nintendo3DsVirtualControlGroup.CIRCLE_PAD;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (!Nintendo3DsFeatureContract.isDeviceSupported()) return;
        applyWindowOrientation(configuration, isInMultiWindowMode());
        if (contentRoot == null || productShell == null || virtualControls == null) return;
        stopFrameLoop();
        if (inputRouter != null) inputRouter.reset();
        virtualControls.refreshResponsiveLayout();
        Nintendo3DsCoreLifecycleController active = controller;
        if (active != null && !active.isClosed()) {
            active.setTouchPosition(0, 0, currentFrameWidth, currentFrameHeight, false);
            active.setDisplayRotation(getWindowManager().getDefaultDisplay().getRotation());
        }
        layoutProductShell(configuration.orientation == Configuration.ORIENTATION_LANDSCAPE);
        reflowActionsMenuPanel();
        if (virtualControlEditorController != null && virtualControlEditorController.isShowing()) {
            // Physical rotation must not change the independently selected profile
            // orientation/group or commit/replace the unsaved draft.
            virtualControlEditorController.refreshResponsiveLayout();
        } else {
            restoreResolvedVirtualControlProfile();
        }
        if (activeProductDialog != null) MateusDialog.refreshResponsiveLayout(activeProductDialog);
        if (activeReportDialog != null) MateusDialog.refreshResponsiveLayout(activeReportDialog);
        if (gamepadVirtualControlsDialog != null) MateusDialog.refreshResponsiveLayout(gamepadVirtualControlsDialog);
        if (settingsDialogController != null) settingsDialogController.refreshResponsiveLayout();
        androidx.core.view.ViewCompat.requestApplyInsets(contentRoot);
        hideSystemBars();
        bindProductUi();
        if (active != null && !active.isClosed()) {
            if (active.getRecoveryFailure() != null) {
                showSessionFailure(active.getRecoveryFailure());
                return;
            }
            // Change only the render layout, not audio, microphone, performance
            // settings or manual pause. The serialized owner captures recovery
            // before replacing a session; failed recovery never becomes a reset.
            queueTransition(() -> active.setScreenLayout(
                    Nintendo3DsScreenLayoutPolicy.resolve(preferredScreenLayout, configuration.orientation)));
            bindCurrentSurfaceIfReady();
        }
    }

    @Override
    public void onMultiWindowModeChanged(boolean multiWindow, Configuration configuration) {
        super.onMultiWindowModeChanged(multiWindow, configuration);
        if (!Nintendo3DsFeatureContract.isDeviceSupported()) return;
        applyWindowOrientation(configuration, multiWindow);
        // Geometry, Surface and input transitions belong to onConfigurationChanged/
        // Surface callbacks. Do not independently pause or replace the native owner.
        bindProductUi();
    }

    private void applyWindowOrientation(Configuration configuration, boolean multiWindow) {
        int requested = Nintendo3DsWindowOrientationPolicy.resolve(
                phoneOrientation, configuration.smallestScreenWidthDp, multiWindow);
        if (getRequestedOrientation() != requested) setRequestedOrientation(requested);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Nintendo3DsFeatureContract.isDeviceSupported()) return;
        if (usage != null) usage.foreground(true);
        hostResumed = true;
        lifecycleRevision++;
        if (externalGamepadMonitor != null) {
            externalGamepadMonitor.start();
        }
        hideSystemBars();
        resumeControllerIfReady();
        launchPendingMicrophonePermission();
    }

    @Override
    protected void onPause() {
        if (usage != null) usage.foreground(false);
        hostResumed = false;
        lifecycleRevision++;
        if (externalGamepadMonitor != null) {
            externalGamepadMonitor.stop();
        }
        stopFrameLoop();
        if (inputRouter != null) {
            inputRouter.reset();
        }
        if (virtualControls != null) {
            virtualControls.reset();
        }
        if (controller != null && controllerResumed) {
            Nintendo3DsCoreLifecycleController active = controller;
            active.requestPause();
            queueTransition(active::onPauseAndAwait);
            controllerResumed = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (usage != null && !isChangingConfigurations()) usage.finish();
        hostResumed = false;
        lifecycleRevision++;
        stopFrameLoop();
        if (controller != null) controller.requestPause();
        if (ownedLayer != null) {
            try { ownedLayer.retire(); } catch (RuntimeException failure) { recordFailure(failure); }
            ownedLayer = null;
        }
        currentSurface = null;
        if (externalGamepadMonitor != null) {
            externalGamepadMonitor.close();
        }
        if (surfaceHolder != null) {
            surfaceHolder.removeCallback(this);
        }
        if (virtualControls != null) {
            virtualControls.setControlsEnabled(false);
        }
        closeActionsMenu();
        if (activeProductDialog != null) {
            activeProductDialog.dismiss();
            activeProductDialog = null;
        }
        if (gamepadVirtualControlsDialog != null) {
            gamepadVirtualControlsDialog.dismiss();
            gamepadVirtualControlsDialog = null;
        }
        if (activeReportDialog != null) {
            reportOwnsPause = false;
            activeReportDialog.dismiss();
            activeReportDialog = null;
            activeReportInput = null;
            activeReportInputLayout = null;
            activeReportContext = Collections.emptyMap();
        }
        shareInFlight = false;
        if (activeFeedbackToast != null) {
            activeFeedbackToast.cancel();
            activeFeedbackToast = null;
        }
        if (settingsDialogController != null) {
            settingsDialogController.close();
        }
        if (virtualControlEditorController != null) {
            virtualControlEditorController.close();
        }
        if (activityResultHost != null) {
            activityResultHost.close();
        }
        miiPickerResultCallback = null;
        pendingMiiPickerResult = null;
        Nintendo3DsExperimentalHost closingHost = experimentalHost;
        var closingProtection = protectionLease;
        transitions.finish(() -> {
            try {
                if (closingHost != null) closingHost.close();
            } finally {
                // This is only an advisory foreground preparation lease. An
                // unconfirmed JNI unload retains the separate actual native
                // lease; a failed checkpoint must not strand this UI lease.
                if (closingProtection != null) closingProtection.close();
            }
        }, this::recordFailure);
        frameExecutor.shutdown(); // Preserve any already-entered native frame/cleanup.
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (inputRouter != null && inputRouter.applyKeyCode(keyCode, true)) {
            if (externalGamepadMonitor != null) {
                externalGamepadMonitor.recordControllerInteraction(
                        event.getDevice(), event.getSource());
            }
            routedInputEvents.incrementAndGet();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (inputRouter != null && inputRouter.applyKeyCode(keyCode, false)) {
            if (externalGamepadMonitor != null) {
                externalGamepadMonitor.recordControllerInteraction(
                        event.getDevice(), event.getSource());
            }
            routedInputEvents.incrementAndGet();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (inputRouter != null && inputRouter.handleMotion(event)) {
            if (externalGamepadMonitor != null) {
                externalGamepadMonitor.recordControllerInteraction(
                        event.getDevice(), event.getSource());
            }
            routedInputEvents.incrementAndGet();
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // surfaceChanged provides the authoritative non-zero dimensions.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (isFinishing() || isDestroyed() || width <= 0 || height <= 0) return;
        if (ownedLayer != null && ownedLayer.width == width && ownedLayer.height == height) {
            try { ownedLayer.attachTo(surfaceView); }
            catch (RuntimeException failure) { showSessionFailure(failure); return; }
            bindCurrentSurfaceIfReady();
            return;
        }
        retireCurrentLayer();
        try { ownedLayer = new Nintendo3DsOwnedLayer(surfaceView, width, height); }
        catch (RuntimeException failure) { showSessionFailure(failure); return; }
        currentSurface = ownedLayer.surface;
        currentSurfaceWidth = width;
        currentSurfaceHeight = height;
        requestNintendo3DsFrameRate(currentSurface);
        bindCurrentSurfaceIfReady();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopFrameLoop();
        retireCurrentLayer();
    }

    private void retireCurrentLayer() {
        Nintendo3DsOwnedLayer retiring = ownedLayer;
        ownedLayer = null;
        currentSurface = null;
        currentSurfaceWidth = currentSurfaceHeight = 0;
        if (retiring == null) return;
        // Only our child is detached. Its native lease survives until actual JNI close.
        try { retiring.retire(); } catch (RuntimeException failure) { recordFailure(failure); }
        Nintendo3DsCoreLifecycleController active = controller;
        if (active != null && !isDestroyed())
            queueTransition(() -> active.onSurfaceDestroyedAndAwait(retiring.surface));
    }

    @Override
    public void onLaunchReady(Nintendo3DsExperimentalHost.PreparedLaunch preparedLaunch) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Nintendo3DsCoreLifecycleController prepared = preparedLaunch.requireController();
        if (controller != null && controller != prepared) {
            failAndFinish(new IllegalStateException(
                    "O controlador Nintendo 3DS mudou durante o launch."));
            return;
        }
        controller = prepared;
        inputRouter = new Nintendo3DsPhysicalControllerRouter(prepared);
        Nintendo3DsExperienceSettings settings = settingsStore
                .loadGameOverrides(launchRequest.getPersistentId())
                .resolve(settingsStore.loadGlobal());
        applySettings(settings);
        virtualControls.applyLayoutProfile(
                virtualControlProfileStore.resolve(launchRequest.getPersistentId()),
                Nintendo3DsVirtualControlOrientation.from(
                        getResources().getConfiguration()));
        boolean audioEnabled = hasRestoredSessionState
                ? restoredAudioEnabled
                : settings.isAudioEnabled();
        int speedMultiplier = hasRestoredSessionState ? restoredSpeedMultiplier : 1;
        boolean manuallyPaused = hasRestoredSessionState && restoredManualPause;
        uiState = Nintendo3DsProductUiState.restoredSession(
                audioEnabled,
                speedMultiplier,
                manuallyPaused);
        queueTransition(() -> { prepared.setAudioEnabled(audioEnabled); prepared.setFastForwardSpeed(speedMultiplier); });
        hasRestoredSessionState = false;
        bindProductUi();
        if (gamepadVirtualControlsCoordinator != null) {
            gamepadVirtualControlsCoordinator.refresh();
            maybeShowFirstGamepadSetupGuide();
        }
        resumeControllerIfReady();
        restorePendingProductAction();
        restorePendingControlEditor();
    }

    @Override
    public void onExtractionRequested() {
        setResult(
                RESULT_FIRST_USER,
                new Intent().putExtra(EXTRA_RESULT_ACTION, RESULT_ACTION_EXTRACT_CONTENT));
        finish();
    }

    @Override
    public void onUnderstood() {
        finish();
    }

    @Override
    public void onFailure(
            Nintendo3DsReadinessPresentation.Action action,
            Exception failure) {
        showSessionFailure(failure);
    }

    @Override
    public void onSettingsChanged(
            Nintendo3DsExperienceSettings settings,
            Nintendo3DsExperienceSettingsDialogController.Scope scope,
            boolean reset) {
        applySettings(settings);
        uiState = uiState.withAudioEnabled(settings.isAudioEnabled());
        bindProductUi();
        startFrameLoopIfReady();
    }

    @Override
    public void onPersistenceFailed(
            Nintendo3DsExperienceSettingsDialogController.Scope scope) {
        Toast.makeText(this, R.string.n3ds_product_settings_save_failed, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDialogDismissed(
            Nintendo3DsExperienceSettingsDialogController.Scope scope,
            boolean settingsChanged) {
        if (!settingsChanged) {
            startFrameLoopIfReady();
        }
    }

    Nintendo3DsCoreLifecycleController getControllerForTesting() {
        return controller;
    }

    Nintendo3DsExperienceSettingsDialogController getSettingsDialogForTesting() {
        return settingsDialogController;
    }

    Nintendo3DsVirtualControlEditorController getControlEditorForTesting() {
        return virtualControlEditorController;
    }

    Button getMenuButtonForTesting() {
        return menuButton;
    }

    View getProductShellForTesting() {
        return productShell;
    }

    PopupMenu getActionsMenuForTesting() {
        return activeActionsMenu;
    }

    GridLayout getActionsMenuPanelForTesting() {
        return actionsMenuPanel;
    }

    Button getActionMenuButtonForTesting(int actionId) {
        return actionMenuButtons.get(actionId);
    }

    void dismissActionsMenuForTesting() {
        closeActionsMenu();
    }

    AlertDialog getProductDialogForTesting() {
        return activeProductDialog;
    }

    Button getPauseButtonForTesting() {
        return pauseButton;
    }

    Button getBackButtonForTesting() {
        return backButton;
    }

    Button getAudioButtonForTesting() {
        return audioButton;
    }

    Button getSpeedButtonForTesting() {
        return speedButton;
    }

    Button getOrientationButtonForTesting() {
        return orientationButton;
    }

    TextView getStatusViewForTesting() {
        return statusView;
    }

    TextView getTitleViewForTesting() {
        return titleView;
    }

    Nintendo3DsProductUiState getUiStateForTesting() {
        return uiState;
    }

    Nintendo3DsVirtualControlsOverlay getVirtualControlsForTesting() {
        return virtualControls;
    }

    AlertDialog getGamepadVirtualControlsDialogForTesting() {
        return gamepadVirtualControlsDialog;
    }

    void setExternalGamepadConnectedForTesting(boolean connected) {
        gamepadVirtualControlsCoordinator.onGamepadConnectionChanged(connected);
    }

    SurfaceView getSurfaceViewForTesting() {
        return surfaceView;
    }

    Throwable getProductFailureForTesting() {
        return productFailure;
    }

    long getRenderedFrameCountForTesting() {
        return renderedFrames.get();
    }

    int getCurrentFrameWidthForTesting() {
        return currentFrameWidth;
    }

    int getCurrentFrameHeightForTesting() {
        return currentFrameHeight;
    }

    long getRoutedInputEventCountForTesting() {
        return routedInputEvents.get();
    }

    AlertDialog getReportDialogForTesting() {
        return activeReportDialog;
    }

    EditText getReportInputForTesting() {
        return activeReportInput;
    }

    int getLastFeedbackResourceForTesting() {
        return lastFeedbackResource;
    }

    boolean isShareInFlightForTesting() {
        return shareInFlight;
    }

    boolean didReportValidationFailForTesting() {
        return reportValidationFailed;
    }

    void capturePresentedFrameForTesting(Nintendo3DsSurfaceFrameCapture.Callback callback) {
        surfaceFrameCapture.capture(ownedLayer, callback);
    }

    private View createContentView() {
        contentRoot = new FrameLayout(this);
        contentRoot.setBackgroundColor(Color.BLACK);
        contentRoot.setMotionEventSplittingEnabled(true);

        surfaceView = new Nintendo3DsSurfaceView(this);
        surfaceView.setKeepScreenOn(true);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        surfaceView.setOnTouchListener(this::routeTouch);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        contentRoot.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        virtualControls = new Nintendo3DsVirtualControlsOverlay(
                this,
                new Nintendo3DsVirtualControlsOverlay.InputSink() {
                    @Override
                    public void setButtonPressed(Nintendo3DsButton button, boolean pressed) {
                        Nintendo3DsCoreLifecycleController active = controller;
                        if (active != null && !active.isClosed()) {
                            active.setButtonPressed(button, pressed);
                            routedInputEvents.incrementAndGet();
                        }
                    }

                    @Override
                    public void setCirclePad(float horizontal, float vertical) {
                        Nintendo3DsCoreLifecycleController active = controller;
                        if (active != null && !active.isClosed()) {
                            active.setCirclePad(horizontal, vertical);
                            routedInputEvents.incrementAndGet();
                        }
                    }

                    @Override
                    public void setCStick(float horizontal, float vertical) {
                        Nintendo3DsCoreLifecycleController active = controller;
                        if (active != null && !active.isClosed()) {
                            active.setCStick(horizontal, vertical);
                            routedInputEvents.incrementAndGet();
                        }
                    }
                });
        contentRoot.addView(virtualControls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        addProductShell(contentRoot);

        com.mateussouza.emuorbit.advance.ui.EdgeToEdgeInsets.applyStableGameplayPadding(virtualControls);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (view, insets) -> {
            androidx.core.graphics.Insets safe = androidx.core.graphics.Insets.max(
                    insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()),
                    insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()));
            // Move the shell/menu, not the surface: touch/native coordinates and
            // the lifecycle controller continue to own the unchanged viewport.
            shellInsets = safe;
            updateShellBounds();
            return insets;
        });
        contentRoot.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateShellBounds());
        contentRoot.post(() -> androidx.core.view.ViewCompat.requestApplyInsets(contentRoot));

        surfaceView.requestFocus();
        return contentRoot;
    }

    private void addProductShell(FrameLayout root) {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        productShell = new FrameLayout(this);
        productShell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.addView(productShell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(landscape ? 60 : 116),
                Gravity.TOP));

        backButton = createToolbarButton(
                R.drawable.n3ds_ic_arrow_back,
                R.string.n3ds_product_back,
                ignored -> handleBackAction());

        menuButton = createToolbarButton(
                R.drawable.n3ds_ic_more_vert,
                R.string.n3ds_product_more_actions_show,
                ignored -> toggleActionsMenu());

        LinearLayout identity = shellIdentity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER);

        titleView = new TextView(this);
        titleView.setText(displayTitle());
        // The shell must not obscure the guest image with its filename.
        titleView.setVisibility(View.GONE);
        titleView.setTextColor(Color.rgb(247, 247, 255));
        titleView.setTextSize(12);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(62, 216, 245));
        statusView.setTextSize(10);
        statusView.setGravity(Gravity.CENTER);
        statusView.setSingleLine(true);
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        identity.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout quickActions = shellQuickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setGravity(Gravity.CENTER);

        speedButton = createToolbarButton(
                0,
                R.string.n3ds_product_speed,
                ignored -> cycleSpeed());
        speedButton.setText("1×");
        speedButton.setTextSize(11);
        speedButton.setTextColor(Color.rgb(62, 216, 245));
        quickActions.addView(speedButton, quickActionLayout(0));
        orientationButton = createToolbarButton(
                R.drawable.n3ds_ic_screen_rotation,
                R.string.n3ds_product_orientation,
                ignored -> toggleOrientation());
        quickActions.addView(orientationButton, quickActionLayout(6));
        pauseButton = createToolbarButton(
                R.drawable.n3ds_ic_pause,
                R.string.n3ds_product_pause,
                ignored -> toggleManualPause());
        quickActions.addView(pauseButton, quickActionLayout(6));

        layoutProductShell(landscape);
        addActionsMenuPanel(root, landscape);
        applyGameplayActionsOpacity();
        bindProductUi();
    }

    private void layoutProductShell(boolean landscape) {
        // Reuse the actual buttons/listeners. Rebuilding the content view here would
        // replace the Surface, lose editor drafts and risk a second native owner.
        for (View child : new View[]{backButton, menuButton, shellIdentity, shellQuickActions}) {
            if (child.getParent() instanceof ViewGroup) {
                ((ViewGroup) child.getParent()).removeView(child);
            }
        }
        productShell.removeAllViews();
        shellLandscape = landscape;
        LinearLayout identity = shellIdentity;
        LinearLayout quickActions = shellQuickActions;

        if (landscape) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            productShell.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout.LayoutParams identityLayout = new LinearLayout.LayoutParams(
                    0,
                    dp(48),
                    1.0f);
            identityLayout.setMargins(dp(6), 0, dp(6), 0);
            row.addView(identity, identityLayout);
            row.addView(quickActions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48)));
            LinearLayout.LayoutParams menuLayout = new LinearLayout.LayoutParams(
                    dp(48),
                    dp(48));
            menuLayout.setMarginStart(dp(6));
            row.addView(menuButton, menuLayout);
        } else {
            productShell.addView(
                    backButton,
                    anchoredToolbarButton(Gravity.TOP | Gravity.START, 8, 6));
            productShell.addView(
                    menuButton,
                    anchoredToolbarButton(Gravity.TOP | Gravity.END, 8, 6));
            FrameLayout.LayoutParams identityLayout = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(56),
                    Gravity.TOP);
            identityLayout.setMargins(dp(64), 0, dp(64), 0);
            productShell.addView(identity, identityLayout);
            FrameLayout.LayoutParams quickLayout = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(52),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            quickLayout.topMargin = dp(58);
            productShell.addView(quickActions, quickLayout);
        }

        updateShellBounds();
    }

    private void updateShellBounds() {
        if (productShell == null) return;
        FrameLayout.LayoutParams shell = (FrameLayout.LayoutParams) productShell.getLayoutParams();
        int height = dp(shellLandscape ? 60 : 116);
        if (shell.height != height || shell.leftMargin != shellInsets.left
                || shell.topMargin != shellInsets.top || shell.rightMargin != shellInsets.right) {
            shell.height = height;
            shell.setMargins(shellInsets.left, shellInsets.top, shellInsets.right, 0);
            productShell.setLayoutParams(shell);
        }
        if (actionsMenuContainer == null || contentRoot.getHeight() <= 0) return;
        FrameLayout.LayoutParams menu = (FrameLayout.LayoutParams) actionsMenuContainer.getLayoutParams();
        int top = shellInsets.top + dp(shellLandscape ? 64 : 58);
        int boundedHeight = Math.min(dp(8 + 48 * actionsMenuPanel.getRowCount()),
                Math.max(1, contentRoot.getHeight() - top - shellInsets.bottom - dp(8)));
        if (menu.height != boundedHeight || menu.topMargin != top
                || menu.leftMargin != shellInsets.left
                || menu.rightMargin != shellInsets.right + dp(8)
                || menu.bottomMargin != shellInsets.bottom) {
            menu.height = boundedHeight;
            menu.setMargins(shellInsets.left, top, shellInsets.right + dp(8), shellInsets.bottom);
            actionsMenuContainer.setLayoutParams(menu);
        }
    }

    private void addActionsMenuPanel(FrameLayout root, boolean landscape) {
        actionsMenuPanel = new GridLayout(this);
        actionsMenuPanel.setColumnCount(landscape ? 2 : 1);
        actionsMenuPanel.setRowCount(landscape ? 4 : 7);
        actionsMenuPanel.setPadding(dp(4), dp(4), dp(4), dp(4));
        actionsMenuPanel.setBackgroundResource(
                R.drawable.n3ds_action_menu_background);
        actionsMenuPanel.setElevation(dp(12));
        actionsMenuPanel.setVisibility(View.GONE);
        actionsMenuPanel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        addActionMenuButton(
                MENU_REPORT_ID,
                R.drawable.n3ds_ic_bug_report,
                R.string.n3ds_product_report_problem);
        addActionMenuButton(
                MENU_SETTINGS_ID,
                R.drawable.n3ds_ic_game_settings,
                R.string.n3ds_product_settings);
        addActionMenuButton(
                MENU_CUSTOMIZE_CONTROLS_ID,
                R.drawable.n3ds_ic_edit_controls,
                R.string.n3ds_product_customize_controls);
        Button gamepadControlsButton = addActionMenuButton(
                MENU_GAMEPAD_CONTROLS_ID,
                R.drawable.n3ds_ic_visibility_off,
                R.string.n3ds_gamepad_controls_hide);
        actionsMenuPanel.removeView(gamepadControlsButton);
        addActionMenuButton(
                MENU_SHARE_ID,
                R.drawable.n3ds_ic_share,
                R.string.n3ds_product_share);
        audioButton = addActionMenuButton(
                MENU_AUDIO_ID,
                R.drawable.n3ds_ic_volume_up,
                R.string.n3ds_product_audio_disable);
        addActionMenuButton(
                MENU_RESET_ID,
                R.drawable.n3ds_ic_emulator_reset,
                R.string.n3ds_product_reset);

        FrameLayout.LayoutParams menuLayout = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        menuLayout.setMargins(0, dp(landscape ? 64 : 58), dp(8), 0);
        actionsMenuContainer = new ScrollView(this);
        actionsMenuContainer.setVisibility(View.GONE);
        actionsMenuContainer.addView(actionsMenuPanel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(actionsMenuContainer, menuLayout);
    }

    private Button addActionMenuButton(
            int actionId,
            int iconResource,
            int descriptionResource) {
        Button button = createToolbarButton(
                iconResource,
                descriptionResource,
                ignored -> performActionMenuItem(actionId));
        button.setTag(actionId);
        GridLayout.LayoutParams layout = new GridLayout.LayoutParams();
        layout.width = dp(48);
        layout.height = dp(48);
        actionsMenuPanel.addView(button, layout);
        actionMenuButtons.put(actionId, button);
        return button;
    }

    private void reflowActionsMenuPanel() {
        reflowActionsMenuPanel(shellLandscape ? 2 : 1);
    }

    private void reflowActionsMenuPanel(int columns) {
        if (actionsMenuPanel == null) {
            return;
        }
        // Do not shrink columnCount while old landscape column specs still exist.
        actionsMenuPanel.setColumnCount(Math.max(columns, actionsMenuPanel.getColumnCount()));
        // Expand for the worst existing auto-placement first. Android validates every
        // intermediate LayoutParams mutation and may still hold a portrait row index here.
        actionsMenuPanel.setRowCount(Math.max(1, actionsMenuPanel.getChildCount()));
        int visibleIndex = 0;
        for (int index = 0; index < actionsMenuPanel.getChildCount(); index++) {
            View child = actionsMenuPanel.getChildAt(index);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            ViewGroup.LayoutParams current = child.getLayoutParams();
            if (!(current instanceof GridLayout.LayoutParams)) {
                continue;
            }
            GridLayout.LayoutParams layout = (GridLayout.LayoutParams) current;
            layout.rowSpec = GridLayout.spec(visibleIndex / columns);
            layout.columnSpec = GridLayout.spec(visibleIndex % columns);
            child.setLayoutParams(layout);
            visibleIndex++;
        }
        actionsMenuPanel.setRowCount(Math.max(1, (visibleIndex + columns - 1) / columns));
        actionsMenuPanel.setColumnCount(columns);
        updateShellBounds();
    }

    private Button createToolbarButton(
            int iconResource,
            int descriptionResource,
            View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.n3ds_toolbar_button_background);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setCompoundDrawableTintList(ColorStateList.valueOf(Color.rgb(62, 216, 245)));
        button.setContentDescription(descriptionResource == R.string.n3ds_product_speed
                ? getString(descriptionResource, 1)
                : getString(descriptionResource));
        button.setTooltipText(button.getContentDescription());
        if (iconResource != 0) {
            button.setCompoundDrawablesWithIntrinsicBounds(0, iconResource, 0, 0);
        }
        button.setOnClickListener(listener);
        return button;
    }

    private FrameLayout.LayoutParams anchoredToolbarButton(int gravity, int edge, int top) {
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(dp(48), dp(48), gravity);
        layout.setMargins(dp(edge), dp(top), dp(edge), 0);
        return layout;
    }

    private LinearLayout.LayoutParams quickActionLayout(int startMargin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(48), dp(48));
        layout.setMarginStart(dp(startMargin));
        return layout;
    }

    private void bindProductUi() {
        if (statusView == null) {
            return;
        }
        int statusResource;
        switch (uiState.getSessionState()) {
            case RESTARTING:
                statusResource = R.string.n3ds_product_restarting;
                break;
            case RUNNING:
                statusResource = R.string.n3ds_product_running;
                break;
            case PAUSED:
                statusResource = R.string.n3ds_product_paused;
                break;
            case FAILED:
                statusResource = R.string.n3ds_product_failed;
                break;
            case PREPARING:
            default:
                statusResource = R.string.n3ds_product_preparing;
                break;
        }
        statusView.setText(statusResource);
        // Keep preparation/failure feedback, but no persistent label over gameplay.
        statusView.setVisibility(uiState.canControlSession() ? View.GONE : View.VISIBLE);

        boolean enabled = uiState.canControlSession() && productFailure == null;
        pauseButton.setEnabled(enabled);
        audioButton.setEnabled(enabled);
        speedButton.setEnabled(enabled);
        orientationButton.setEnabled(enabled && Nintendo3DsWindowOrientationPolicy.allowsPhoneChoice(
                getResources().getConfiguration().smallestScreenWidthDp, isInMultiWindowMode()));
        menuButton.setEnabled(enabled);
        virtualControls.setControlsEnabled(
                enabled
                        && !uiState.isPaused()
                        && virtualControlsEnabledBySettings
                        && gamepadVirtualControlsVisible
                        && (virtualControlEditorController == null
                                || !virtualControlEditorController.isShowing()));

        int pauseDescription = uiState.isPaused()
                ? R.string.n3ds_product_resume
                : R.string.n3ds_product_pause;
        pauseButton.setCompoundDrawablesWithIntrinsicBounds(
                0,
                uiState.isPaused()
                        ? R.drawable.n3ds_ic_play
                        : R.drawable.n3ds_ic_pause,
                0,
                0);
        applyDescription(pauseButton, pauseDescription);
        pauseButton.setSelected(uiState.isPaused());

        audioButton.setCompoundDrawablesWithIntrinsicBounds(
                0,
                uiState.isAudioEnabled()
                        ? R.drawable.n3ds_ic_volume_up
                        : R.drawable.n3ds_ic_volume_off,
                0,
                0);
        applyDescription(
                audioButton,
                uiState.isAudioEnabled()
                        ? R.string.n3ds_product_audio_disable
                        : R.string.n3ds_product_audio_enable);
        audioButton.setSelected(uiState.isAudioEnabled());
        audioButton.setAlpha(uiState.isAudioEnabled() ? 1.0f : 0.55f);

        speedButton.setText(uiState.getSpeedMultiplier() + "×");
        CharSequence speedDescription = getString(
                R.string.n3ds_product_speed,
                uiState.getSpeedMultiplier());
        speedButton.setContentDescription(speedDescription);
        speedButton.setTooltipText(speedDescription);
        speedButton.setSelected(uiState.getSpeedMultiplier() > 1);
        bindActionsMenuButton();
    }

    private void toggleActionsMenu() {
        if (activeActionsMenu != null) {
            closeActionsMenu();
            return;
        }
        showActionsMenu();
    }

    private void showActionsMenu() {
        PopupMenu popup = new PopupMenu(this, menuButton);
        popup.getMenu().add(
                0,
                MENU_REPORT_ID,
                0,
                R.string.n3ds_product_report_problem);
        popup.getMenu().add(
                0,
                MENU_SETTINGS_ID,
                1,
                R.string.n3ds_product_settings);
        popup.getMenu().add(
                0,
                MENU_CUSTOMIZE_CONTROLS_ID,
                2,
                R.string.n3ds_product_customize_controls);
        if (externalGamepadConnected) {
            popup.getMenu().add(
                    0,
                    MENU_GAMEPAD_CONTROLS_ID,
                    3,
                    gamepadVirtualControlsVisible
                            ? R.string.n3ds_gamepad_controls_hide
                            : R.string.n3ds_gamepad_controls_show);
        }
        popup.getMenu().add(
                0,
                MENU_SHARE_ID,
                4,
                R.string.n3ds_product_share);
        popup.getMenu().add(
                0,
                MENU_AUDIO_ID,
                5,
                uiState.isAudioEnabled()
                        ? R.string.n3ds_product_audio_disable
                        : R.string.n3ds_product_audio_enable);
        popup.getMenu().add(
                0,
                MENU_RESET_ID,
                6,
                R.string.n3ds_product_reset);
        popup.setOnMenuItemClickListener(item -> {
            performActionMenuItem(item.getItemId());
            return true;
        });
        activeActionsMenu = popup;
        actionsMenuPanel.setVisibility(View.VISIBLE);
        actionsMenuContainer.setVisibility(View.VISIBLE);
        bindActionsMenuButton();
        Button firstAction = actionMenuButtons.get(MENU_REPORT_ID);
        if (firstAction != null) {
            firstAction.requestFocus();
        }
    }

    private void performActionMenuItem(int actionId) {
        closeActionsMenu();
        if (actionId == MENU_SETTINGS_ID) {
            showGameSettings();
        } else if (actionId == MENU_RESET_ID) {
            confirmReset();
        } else if (actionId == MENU_CUSTOMIZE_CONTROLS_ID) {
            showVirtualControlEditor();
        } else if (actionId == MENU_GAMEPAD_CONTROLS_ID) {
            if (gamepadVirtualControlsCoordinator != null && externalGamepadConnected) {
                if (gamepadVirtualControlsVisible) {
                    gamepadVirtualControlsCoordinator.hideControls();
                } else {
                    gamepadVirtualControlsCoordinator.showControls();
                }
            }
        } else if (actionId == MENU_SHARE_ID) {
            shareScreenshot();
        } else if (actionId == MENU_REPORT_ID) {
            showProblemReport();
        } else if (actionId == MENU_AUDIO_ID) {
            toggleAudio();
        }
    }

    private boolean closeActionsMenu() {
        PopupMenu popup = activeActionsMenu;
        if (popup == null) {
            return false;
        }
        activeActionsMenu = null;
        actionsMenuPanel.setVisibility(View.GONE);
        actionsMenuContainer.setVisibility(View.GONE);
        popup.dismiss();
        bindActionsMenuButton();
        menuButton.requestFocus();
        return true;
    }

    private void bindActionsMenuButton() {
        if (menuButton == null) {
            return;
        }
        boolean expanded = activeActionsMenu != null;
        menuButton.setCompoundDrawablesWithIntrinsicBounds(
                0,
                expanded
                        ? R.drawable.n3ds_ic_close
                        : R.drawable.n3ds_ic_more_vert,
                0,
                0);
        Button customize = actionMenuButtons.get(MENU_CUSTOMIZE_CONTROLS_ID);
        if (customize != null) {
            customize.setCompoundDrawablesWithIntrinsicBounds(
                    0, R.drawable.n3ds_ic_edit_controls, 0, 0);
            applyDescription(customize, R.string.n3ds_product_customize_controls);
        }
        Button gamepadControls = actionMenuButtons.get(MENU_GAMEPAD_CONTROLS_ID);
        if (gamepadControls != null) {
            if (externalGamepadConnected && gamepadControls.getParent() == null) {
                GridLayout.LayoutParams layout = new GridLayout.LayoutParams();
                layout.width = dp(48);
                layout.height = dp(48);
                actionsMenuPanel.addView(
                        gamepadControls,
                        Math.min(3, actionsMenuPanel.getChildCount()),
                        layout);
            } else if (!externalGamepadConnected
                    && gamepadControls.getParent() == actionsMenuPanel) {
                actionsMenuPanel.removeView(gamepadControls);
            }
            if (externalGamepadConnected) {
                int description = gamepadVirtualControlsVisible
                        ? R.string.n3ds_gamepad_controls_hide
                        : R.string.n3ds_gamepad_controls_show;
                gamepadControls.setCompoundDrawablesWithIntrinsicBounds(
                        0,
                        gamepadVirtualControlsVisible
                                ? R.drawable.n3ds_ic_visibility_off
                                : R.drawable.n3ds_ic_visibility,
                        0,
                        0);
                applyDescription(gamepadControls, description);
            }
            reflowActionsMenuPanel();
        }
        applyDescription(
                menuButton,
                expanded
                        ? R.string.n3ds_product_more_actions_hide
                        : R.string.n3ds_product_more_actions_show);
        menuButton.setSelected(expanded);
    }

    private void handleBackAction() {
        if (virtualControlEditorController != null
                && virtualControlEditorController.cancel()) {
            return;
        }
        if (closeActionsMenu()) {
            return;
        }
        if (uiState.canControlSession()) {
            confirmExit();
        } else {
            finish();
        }
    }

    private void confirmExit() {
        showProductConfirmation(
                ProductAction.EXIT,
                R.string.n3ds_product_exit_title,
                R.string.n3ds_product_exit_message,
                R.string.n3ds_product_exit_confirm,
                this::finish);
    }

    private void confirmReset() {
        showProductConfirmation(
                ProductAction.RESET,
                R.string.n3ds_product_reset_title,
                R.string.n3ds_product_reset_message,
                R.string.n3ds_product_reset_confirm,
                this::restartSession);
    }

    private void shareScreenshot() {
        closeActionsMenu();
        if (SharingFeatureGate.showComingSoonIfDisabled(this)) {
            return;
        }
        if (shareInFlight) {
            return;
        }
        if (!uiState.canControlSession() || renderedFrames.get() <= 0L
                || surfaceFrameCapture == null || surfaceView == null) {
            showProductFeedback(R.string.n3ds_product_share_frame_unavailable);
            return;
        }

        shareInFlight = true;
        showProductFeedback(R.string.n3ds_product_share_preparing);
        surfaceFrameCapture.capture(ownedLayer, new Nintendo3DsSurfaceFrameCapture.Callback() {
            @Override
            public void onCaptured(Bitmap bitmap) {
                prepareScreenshotShare(bitmap);
            }

            @Override
            public void onFailure(int result) {
                shareInFlight = false;
                showProductFeedback(R.string.n3ds_product_share_frame_unavailable);
            }
        });
    }

    private void prepareScreenshotShare(Bitmap frame) {
        if (isFinishing() || isDestroyed()) {
            frame.recycle();
            shareInFlight = false;
            return;
        }
        try {
            workerExecutor.submit(() -> {
                try {
                    String shareText = getString(
                            R.string.n3ds_product_share_text,
                            baseProductBridge.listingUrl());
                    Intent prepared = baseProductBridge.createScreenshotShareIntent(
                            frame,
                            getString(R.string.n3ds_product_share_brand_caption),
                            shareText);
                    runOnUiThread(() -> launchScreenshotShare(prepared));
                } catch (IOException | RuntimeException failure) {
                    runOnUiThread(() -> {
                        shareInFlight = false;
                        showProductFeedback(R.string.n3ds_product_share_failed);
                    });
                } finally {
                    frame.recycle();
                }
            });
        } catch (RuntimeException failure) {
            frame.recycle();
            shareInFlight = false;
            showProductFeedback(R.string.n3ds_product_share_failed);
        }
    }

    private void launchScreenshotShare(Intent shareIntent) {
        shareInFlight = false;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        boolean launched = Nintendo3DsShareIntentLauncher.launch(
                shareIntent,
                getText(R.string.n3ds_product_share_chooser_title),
                this::startActivity);
        if (!launched) {
            showProductFeedback(R.string.n3ds_product_share_no_compatible_app);
        }
    }

    private void showProblemReport() {
        closeActionsMenu();
        if (activeReportDialog != null && activeReportDialog.isShowing()) {
            return;
        }
        activeReportContext = Nintendo3DsBugReportContext.create(
                uiState,
                renderedFrames.get(),
                controller == null ? "unknown" : controller.getScreenLayout().name(),
                controller == null ? "unknown" : controller.getPerformanceProfile().name());
        reportOwnsPause = !uiState.isPaused() && uiState.canControlSession();
        reportValidationFailed = false;
        if (reportOwnsPause) {
            toggleManualPause();
        }

        ViewBugReportInputBinding reportBinding = ViewBugReportInputBinding.inflate(
                getLayoutInflater());
        reportBinding.bugReportInputLayout.setHint(
                getString(R.string.n3ds_product_report_hint));
        EditText input = reportBinding.bugReportInput;

        activeReportDialog = new MateusDialog.Builder(this)
                .setAvatar(com.mateussouza.emuorbit.advance.R.drawable.mateus_dialog_bug_report)
                .setTitle(R.string.n3ds_product_report_title)
                .setMessage(R.string.n3ds_product_report_message)
                .setCustomContent(reportBinding.getRoot())
                .setValidatingPositiveButton(
                        R.string.n3ds_product_report_send,
                        (dialog, which) -> submitProblemReport(input, reportBinding))
                .setNegativeButton(R.string.n3ds_product_cancel, null)
                .setOnDismissListener(ignored -> {
            activeReportDialog = null;
            activeReportInput = null;
            activeReportInputLayout = null;
            activeReportContext = Collections.emptyMap();
            boolean resume = reportOwnsPause;
            reportOwnsPause = false;
            if (resume && !isFinishing() && !isDestroyed() && hostResumed
                    && uiState.isPaused()) {
                toggleManualPause();
            }
            hideSystemBars();
        })
                .show();
        activeReportInput = input;
        activeReportInputLayout = reportBinding.bugReportInputLayout;
    }

    private boolean submitProblemReport(
            EditText input,
            ViewBugReportInputBinding reportBinding) {
        CharSequence raw = input.getText();
        String description = raw == null ? "" : raw.toString().trim();
        if (description.isEmpty()) {
            reportValidationFailed = true;
            reportBinding.bugReportInputLayout.setError(
                    getString(R.string.n3ds_product_report_required));
            input.requestFocus();
            return false;
        }
        reportValidationFailed = false;
        reportBinding.bugReportInputLayout.setError(null);
        try {
            baseProductBridge.sendProblemReport(
                    description,
                    "nintendo_3ds_gameplay",
                    activeReportContext);
            showProductFeedback(R.string.n3ds_product_report_sent);
            return true;
        } catch (IOException | RuntimeException failure) {
            showProductFeedback(R.string.n3ds_product_report_failed);
            return false;
        }
    }

    private void showProductFeedback(int messageResource) {
        lastFeedbackResource = messageResource;
        if (!isFinishing() && !isDestroyed()) {
            if (activeFeedbackToast != null) {
                activeFeedbackToast.cancel();
            }
            activeFeedbackToast = Toast.makeText(this, messageResource, Toast.LENGTH_LONG);
            activeFeedbackToast.show();
        }
    }

    private void showProductConfirmation(
            ProductAction action,
            int titleResource,
            int messageResource,
            int positiveResource,
            Runnable confirmedAction) {
        closeActionsMenu();
        if (activeProductDialog != null && activeProductDialog.isShowing()) {
            return;
        }
        int avatar = action == ProductAction.RESET
                ? com.mateussouza.emuorbit.advance.R.drawable.mateus_dialog_game_reset
                : com.mateussouza.emuorbit.advance.R.drawable.mateus_dialog_save_details;
        activeConfirmationAction = action;
        activeProductDialog = new MateusDialog.Builder(this)
                .setAvatar(avatar)
                .setTitle(titleResource)
                .setMessage(messageResource)
                .setPositiveButton(positiveResource, (ignored, which) -> confirmedAction.run())
                .setNegativeButton(R.string.n3ds_product_cancel, null)
                .setOnDismissListener(ignored -> {
                    activeProductDialog = null;
                    activeConfirmationAction = ProductAction.NONE;
                    hideSystemBars();
                })
                .show();
    }

    private void showSessionFailure(Throwable failure) {
        if (usage != null) usage.failed();
        stopFrameLoop();
        if (virtualControlEditorController != null) {
            virtualControlEditorController.close();
        }
        if (activeFeedbackToast != null) {
            activeFeedbackToast.cancel();
            activeFeedbackToast = null;
        }
        recordFailure(failure);
        uiState = uiState.failed();
        bindProductUi();
        if (virtualControls != null) {
            virtualControls.setControlsEnabled(false);
        }
        closeActionsMenu();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        AlertDialog priorDialog = activeProductDialog;
        if (priorDialog != null && priorDialog.isShowing()) {
            if (activeConfirmationAction == ProductAction.FAILURE) {
                return;
            }
            priorDialog.dismiss();
        }
        activeConfirmationAction = ProductAction.FAILURE;
        activeProductDialog = new MateusDialog.Builder(this)
                .setAvatar(
                        com.mateussouza.emuorbit.advance.R.drawable
                                .mateus_dialog_emulator_error)
                .setTitle(R.string.n3ds_product_failed)
                .setMessage(failure instanceof Nintendo3DsCoreLifecycleController.RecoveryException
                        ? R.string.n3ds_product_recovery_failed
                        : R.string.n3ds_product_launch_failed)
                .setPositiveButton(android.R.string.ok, (ignored, which) -> finish())
                .setCancelable(false)
                .setOnDismissListener(ignored -> {
                    activeProductDialog = null;
                    activeConfirmationAction = ProductAction.NONE;
                })
                .show();
    }

    private void restartSession() {
        Nintendo3DsCoreLifecycleController active = controller;
        if (active == null || active.isClosed() || !uiState.canControlSession()) {
            return;
        }
        stopFrameLoop();
        if (inputRouter != null) {
            inputRouter.reset();
        }
        virtualControls.reset();
        uiState = uiState.restarting();
        bindProductUi();
        try {
            workerExecutor.submit(() -> {
                try {
                    active.restartSessionAndAwait();
                    runOnUiThread(() -> completeSessionRestart(active));
                } catch (IOException | RuntimeException failure) {
                    runOnUiThread(() -> failSessionRestart(active, failure));
                }
            });
        } catch (RuntimeException failure) {
            failSessionRestart(active, failure);
        }
    }

    private void completeSessionRestart(Nintendo3DsCoreLifecycleController restartedController) {
        if (isFinishing() || isDestroyed() || controller != restartedController
                || restartedController.isClosed()) {
            return;
        }
        try {
            if (hostResumed && !controllerResumed) {
                restartedController.setDisplayRotation(currentDisplayRotation());
                enqueueControllerResume(restartedController);
            }
            uiState = uiState.sessionReady(uiState.isAudioEnabled());
            boolean audio = uiState.isAudioEnabled();
            int speed = uiState.getSpeedMultiplier();
            queueTransition(() -> { restartedController.setAudioEnabled(audio); restartedController.setFastForwardSpeed(speed); });
            bindProductUi();
            startFrameLoopIfReady();
        } catch (RuntimeException failure) {
            failSessionRestart(restartedController, failure);
        }
    }

    private void failSessionRestart(
            Nintendo3DsCoreLifecycleController restartedController,
            Throwable failure) {
        if (controller != restartedController || isFinishing() || isDestroyed()) {
            return;
        }
        showSessionFailure(failure);
    }

    private void applyDescription(Button button, int descriptionResource) {
        CharSequence description = getText(descriptionResource);
        button.setContentDescription(description);
        button.setTooltipText(description);
    }

    private String displayTitle() {
        String name = launchRequest.getContent().getName();
        int extension = name.lastIndexOf('.');
        if (extension > 0) {
            name = name.substring(0, extension);
        }
        return name.isBlank() ? getString(R.string.n3ds_settings_global_title) : name;
    }

    private void toggleManualPause() {
        Nintendo3DsCoreLifecycleController active = controller;
        if (active == null || active.isClosed() || !uiState.canControlSession()) {
            return;
        }
        stopFrameLoop();
        try {
            if (uiState.isPaused()) {
                if (!controllerResumed) {
                    enqueueControllerResume(active);
                }
                uiState = uiState.togglePause();
                bindProductUi();
                startFrameLoopIfReady();
            } else {
                if (inputRouter != null) {
                    inputRouter.reset();
                }
                virtualControls.reset();
                // A manual/UI pause must freeze frame production without closing the native
                // session. Closing here makes the next resume reload the ROM from its boot state.
                uiState = uiState.togglePause();
                bindProductUi();
            }
        } catch (RuntimeException failure) {
            showSessionFailure(failure);
        }
    }

    private void toggleAudio() {
        Nintendo3DsCoreLifecycleController active = controller;
        if (active == null || active.isClosed() || !uiState.canControlSession()) {
            return;
        }
        uiState = uiState.toggleAudio();
        boolean enabled = uiState.isAudioEnabled();
        queueTransition(() -> active.setAudioEnabled(enabled));
        bindProductUi();
    }

    private void cycleSpeed() {
        Nintendo3DsCoreLifecycleController active = controller;
        if (active == null || active.isClosed() || !uiState.canControlSession()) {
            return;
        }
        uiState = uiState.cycleSpeed();
        int speed = uiState.getSpeedMultiplier();
        queueTransition(() -> active.setFastForwardSpeed(speed));
        bindProductUi();
    }

    private void toggleOrientation() {
        Configuration configuration = getResources().getConfiguration();
        if (!uiState.canControlSession()
                || !Nintendo3DsWindowOrientationPolicy.allowsPhoneChoice(
                        configuration.smallestScreenWidthDp, isInMultiWindowMode())) {
            return;
        }
        boolean portrait = configuration.orientation
                == Configuration.ORIENTATION_PORTRAIT;
        phoneOrientation = portrait
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        applyWindowOrientation(configuration, isInMultiWindowMode());
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(
                getWindow(),
                getWindow().getDecorView());
        insetsController.hide(WindowInsetsCompat.Type.systemBars());
        insetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void showGameSettings() {
        if (controller == null || settingsDialogController == null) {
            return;
        }
        stopFrameLoop();
        try {
            boolean shown = settingsDialogController.showForGame(
                    this,
                    launchRequest.getPersistentId(),
                    this);
            if (!shown) {
                startFrameLoopIfReady();
            }
        } catch (RuntimeException failure) {
            recordFailure(failure);
            startFrameLoopIfReady();
        }
    }

    private void showVirtualControlEditor() {
        if (virtualControlEditorController == null
                || launchRequest == null
                || !uiState.canControlSession()) {
            return;
        }
        Nintendo3DsVirtualControlProfile resolved = virtualControlProfileStore.resolve(
                launchRequest.getPersistentId());
        showVirtualControlEditor(
                resolved,
                Nintendo3DsVirtualControlOrientation.from(
                        getResources().getConfiguration()),
                Nintendo3DsVirtualControlGroup.CIRCLE_PAD);
    }

    private boolean showGamepadVirtualControlsChoice() {
        if (!hostResumed
                || !uiState.canControlSession()
                || !virtualControlsEnabledBySettings
                || isFinishing()
                || isDestroyed()
                || gamepadVirtualControlsDialog != null) {
            return false;
        }
        gamepadVirtualControlsDialog = new MateusDialog.Builder(this)
                .setTitle(R.string.n3ds_gamepad_controls_title)
                .setMessage(buildGamepadVirtualControlsMessage())
                .setPositiveButton(R.string.n3ds_gamepad_controls_hide,
                        (ignored, which) -> gamepadVirtualControlsCoordinator.hideControls())
                .setNegativeButton(R.string.n3ds_gamepad_controls_keep,
                        (ignored, which) ->
                                gamepadVirtualControlsCoordinator.keepControlsVisible())
                .setCancelable(false)
                .setOnDismissListener(ignored -> {
                    gamepadVirtualControlsDialog = null;
                    hideSystemBars();
                })
                .show();
        markCurrentGamepadSetupGuideShown();
        return true;
    }

    private void onExternalGamepadConnectionChanged(boolean connected) {
        if (gamepadVirtualControlsCoordinator == null) {
            return;
        }
        gamepadVirtualControlsCoordinator.onGamepadConnectionChanged(connected);
        if (connected) {
            maybeShowFirstGamepadSetupGuide();
        }
    }

    private void maybeShowFirstGamepadSetupGuide() {
        ExternalGamepadMonitor.ControllerInfo controller = externalGamepadMonitor == null
                ? null
                : externalGamepadMonitor.getConnectedController();
        if (controller == null || gamepadVirtualControlsPreferenceStore == null
                || gamepadVirtualControlsPreferenceStore.wasSetupGuideShown(
                        controller.getFamily())) {
            return;
        }
        if (gamepadVirtualControlsDialog != null) {
            // The first visibility choice already includes the family-specific setup tip.
            markCurrentGamepadSetupGuideShown();
            return;
        }
        if (!hostResumed || !uiState.canControlSession() || isFinishing() || isDestroyed()) {
            return;
        }
        gamepadVirtualControlsDialog = new MateusDialog.Builder(this)
                .setTitle(R.string.n3ds_gamepad_controls_title)
                .setMessage(buildGamepadSetupGuideMessage())
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(ignored -> {
                    gamepadVirtualControlsDialog = null;
                    hideSystemBars();
                })
                .show();
        markCurrentGamepadSetupGuideShown();
    }

    private void markCurrentGamepadSetupGuideShown() {
        ExternalGamepadMonitor.ControllerInfo controller = externalGamepadMonitor == null
                ? null
                : externalGamepadMonitor.getConnectedController();
        if (controller != null && gamepadVirtualControlsPreferenceStore != null
                && !gamepadVirtualControlsPreferenceStore.markSetupGuideShown(
                        controller.getFamily())) {
            showProductFeedback(R.string.n3ds_gamepad_controls_save_failed);
        }
    }

    private CharSequence buildGamepadVirtualControlsMessage() {
        return getString(R.string.n3ds_gamepad_controls_message)
                + "\n\n"
                + buildGamepadSetupGuideMessage();
    }

    private CharSequence buildGamepadSetupGuideMessage() {
        ExternalGamepadMonitor.ControllerInfo controller = externalGamepadMonitor == null
                ? null
                : externalGamepadMonitor.getConnectedController();
        String name = controller == null
                ? getString(R.string.n3ds_gamepad_setup_unknown_name)
                : controller.getName();
        ExternalGamepadMonitor.ControllerFamily family = controller == null
                ? ExternalGamepadMonitor.ControllerFamily.GENERIC
                : controller.getFamily();
        int guideResource;
        switch (family) {
        case D3:
            guideResource = R.string.n3ds_gamepad_setup_d3_guide;
            break;
        case XBOX:
            guideResource = R.string.n3ds_gamepad_setup_xbox_guide;
            break;
        case PLAYSTATION:
            guideResource = R.string.n3ds_gamepad_setup_playstation_guide;
            break;
        case NINTENDO:
            guideResource = R.string.n3ds_gamepad_setup_nintendo_guide;
            break;
        case EIGHT_BIT_DO:
            guideResource = R.string.n3ds_gamepad_setup_8bitdo_guide;
            break;
        case GENERIC:
        default:
            guideResource = R.string.n3ds_gamepad_setup_generic_guide;
            break;
        }
        return getString(R.string.n3ds_gamepad_setup_detected_device, name)
                + "\n\n"
                + getString(guideResource);
    }

    private void restorePendingControlEditor() {
        Nintendo3DsVirtualControlProfile draft = restoredControlEditorDraft;
        Nintendo3DsVirtualControlOrientation orientation = restoredControlEditorOrientation;
        Nintendo3DsVirtualControlGroup group = restoredControlEditorGroup;
        restoredControlEditorDraft = null;
        restoredControlEditorOrientation = null;
        restoredControlEditorGroup = null;
        if (draft == null || orientation == null || group == null) {
            return;
        }
        showVirtualControlEditor(draft, orientation, group);
    }

    private void showVirtualControlEditor(
            Nintendo3DsVirtualControlProfile profile,
            Nintendo3DsVirtualControlOrientation orientation,
            Nintendo3DsVirtualControlGroup group) {
        if (virtualControlEditorController == null
                || launchRequest == null
                || !uiState.canControlSession()) {
            return;
        }
        virtualControlEditorController.show(
                launchRequest.getPersistentId(),
                profile,
                orientation,
                group);
        applyVirtualControlsPresentation();
        bindProductUi();
    }

    private void restoreResolvedVirtualControlProfile() {
        if (launchRequest == null || virtualControlProfileStore == null || virtualControls == null) {
            return;
        }
        virtualControls.applyLayoutProfile(
                virtualControlProfileStore.resolve(launchRequest.getPersistentId()),
                Nintendo3DsVirtualControlOrientation.from(
                        getResources().getConfiguration()));
    }

    private void applySettings(Nintendo3DsExperienceSettings settings) {
        preferredScreenLayout = settings.getScreenLayout();
        virtualControlsEnabledBySettings = settings.areVirtualControlsVisible();
        virtualControlsOpacityPercent = settings.getVirtualControlOpacityPercent();
        applyVirtualControlsPresentation();
        if (virtualControlsEnabledBySettings && gamepadVirtualControlsCoordinator != null) {
            gamepadVirtualControlsCoordinator.refresh();
        }
        Nintendo3DsCoreLifecycleController active = controller;
        if (active == null || active.isClosed()) {
            return;
        }
        stopFrameLoop();
        long revision = ++settingsRevision;
        Nintendo3DsScreenLayout effectiveLayout = Nintendo3DsScreenLayoutPolicy.resolve(
                    settings.getScreenLayout(),
                    getResources().getConfiguration().orientation);
        var microphone = new java.util.concurrent.atomic.AtomicReference<Nintendo3DsMicrophoneStartResult>();
        queueTransition(() -> microphone.set(settings.applyTo(active, effectiveLayout)), () -> {
            if (settingsRevision != revision || controller != active) return;
            if (microphone.get() == Nintendo3DsMicrophoneStartResult.PERMISSION_REQUIRED
                    || (settings.isMicrophoneEnabled() && androidx.core.content.ContextCompat.checkSelfPermission(
                            this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                microphonePermissionPending = true;
                launchPendingMicrophonePermission();
            } else {
                microphonePermissionPending = false;
            }
        });
    }

    private void applyVirtualControlsPresentation() {
        if (virtualControls == null) {
            return;
        }
        boolean editorShowing = virtualControlEditorController != null
                && virtualControlEditorController.isShowing();
        if (!virtualControlsEnabledBySettings
                && virtualControlEditorController != null
                && editorShowing) {
            virtualControlEditorController.cancel();
            editorShowing = false;
        }
        virtualControls.applyPresentation(
                virtualControlsEnabledBySettings
                        && (gamepadVirtualControlsVisible || editorShowing),
                virtualControlsOpacityPercent);
        applyGameplayActionsOpacity();
    }

    private void applyGameplayActionsOpacity() {
        float opacity = Nintendo3DsExperienceSettings
                .normalizeVirtualControlOpacity(virtualControlsOpacityPercent) / 100f;
        // Do not fade productShell: it also holds status text. The menu is separate.
        if (backButton != null) backButton.setAlpha(opacity);
        if (menuButton != null) menuButton.setAlpha(opacity);
        if (shellQuickActions != null) shellQuickActions.setAlpha(opacity);
    }

    private void onMicrophonePermissionResult(boolean granted) {
        microphonePermissionInFlight = false;
        microphonePermissionPending = false;
        Nintendo3DsCoreLifecycleController active = controller;
        if (active == null || active.isClosed()) {
            return;
        }
        if (!granted) {
            Toast.makeText(
                    this,
                    R.string.n3ds_product_microphone_permission_denied,
                    Toast.LENGTH_LONG).show();
            startFrameLoopIfReady();
            return;
        }
        queueTransition(() -> active.setMicrophoneEnabled(true));
    }

    private void launchPendingMicrophonePermission() {
        if (!hostResumed || !microphonePermissionPending || microphonePermissionInFlight) {
            return;
        }
        microphonePermissionInFlight = true;
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void resumeControllerIfReady() {
        Nintendo3DsCoreLifecycleController active = controller;
        if (!hostResumed || active == null || active.isClosed()) {
            return;
        }
        if (transitions.getFailure() != null) { showSessionFailure(transitions.getFailure()); return; }
        Nintendo3DsCoreLifecycleController.RecoveryException recovery = active.getRecoveryFailure();
        if (recovery != null) {
            showSessionFailure(recovery);
            return;
        }
        if (uiState.isPaused()) {
            // A recreated paused Activity still owns a new Surface. Bind it now so the first
            // explicit resume can render immediately, while startFrameLoopIfReady() keeps the
            // native session stopped until the user asks to continue.
            bindCurrentSurfaceIfReady();
            return;
        }
        if (!controllerResumed) {
            active.setDisplayRotation(currentDisplayRotation());
            enqueueControllerResume(active);
        }
        bindCurrentSurfaceIfReady();
    }

    private void bindCurrentSurfaceIfReady() {
        Nintendo3DsCoreLifecycleController active = controller;
        Surface target = currentSurface;
        if (active == null || active.isClosed() || target == null || !target.isValid()
                || currentSurfaceWidth <= 0 || currentSurfaceHeight <= 0) {
            return;
        }
        stopFrameLoop();
        Nintendo3DsOwnedLayer binding = ownedLayer;
        if (binding == null) return;
        queueTransition(() -> {
            if (currentSurface != target || binding.lifetime.isRetired()) return;
            active.onOwnedSurfaceAvailable(target, binding.width, binding.height, binding.lifetime);
        });
    }

    private void enqueueControllerResume(Nintendo3DsCoreLifecycleController active) {
        long revision = lifecycleRevision;
        controllerResumed = true;
        queueTransition(() -> {
            if (hostResumed && lifecycleRevision == revision && transitions.getFailure() == null)
                active.onResume();
        });
    }

    private boolean routeTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN
                && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL) {
            return false;
        }
        boolean pressed = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE;
        Nintendo3DsCoreLifecycleController active = controller;
        if (active != null && !active.isClosed() && view.getWidth() > 0 && view.getHeight() > 0) {
            active.setTouchPositionFromSurface(
                    event.getX(),
                    event.getY(),
                    view.getWidth(),
                    view.getHeight(),
                    currentFrameWidth,
                    currentFrameHeight,
                    pressed);
            routedInputEvents.incrementAndGet();
        }
        if (action == MotionEvent.ACTION_UP) {
            view.performClick();
        }
        return true;
    }

    private void startFrameLoopIfReady() {
        Nintendo3DsCoreLifecycleController active = controller;
        if (!hostResumed || !uiState.canControlSession() || uiState.isPaused() || !controllerResumed
                || !transitions.isIdle() || transitions.getFailure() != null
                || active == null || active.isClosed()
                || settingsDialogController.isShowing() || !active.isReadyForFrames()) {
            return;
        }
        Nintendo3DsFrameLoopGate.Run run = frameLoopGate.start();
        if (run == null) return;
        if (usage != null) usage.running(true);
        frameExecutor.execute(() -> {
            try {
                while (frameLoopGate.isCurrent(run)) {
                    if (!active.isReadyForFrames()) {
                        SystemClock.sleep(8);
                        continue;
                    }
                    Nintendo3DsCoreFrameReport frame = active.runFrames(
                            FRAME_LOOP_BATCH_SIZE,
                            Nintendo3DsCoreLifecycleController.DEFAULT_TRANSITION_TIMEOUT_MS);
                    if (!frameLoopGate.isCurrent(run)) break;
                    if (usage != null && frame.getPresentedFrames() > 0 && frame.getVideoFrames() > 0) usage.frame();
                    if (frame.getWidth() > 0 && frame.getHeight() > 0) {
                        currentFrameWidth = frame.getWidth();
                        currentFrameHeight = frame.getHeight();
                    }
                    renderedFrames.addAndGet(FRAME_LOOP_BATCH_SIZE);
                }
            } catch (IOException | RuntimeException failure) {
                if (frameLoopGate.isCurrent(run)) {
                    runOnUiThread(() -> {
                        if (frameLoopGate.isLatest(run)
                                && !isFinishing() && !isDestroyed() && controller == active) {
                            showSessionFailure(failure);
                        }
                    });
                }
            } finally {
                frameLoopGate.finish(run);
            }
        });
    }

    private void stopFrameLoop() {
        if (usage != null) usage.running(false);
        // Invalidate admission without a UI-thread Future.get(). A frame already
        // in JNI still finishes on the existing serial owner. Surface replacement,
        // recovery capture and final close keep their controller barriers OFF the UI thread.
        // A retired run cannot become live again when a new loop starts.
        frameLoopGate.stop();
    }

    private void queueTransition(Nintendo3DsTransitionQueue.Work work) {
        queueTransition(work, () -> {});
    }

    private void queueTransition(Nintendo3DsTransitionQueue.Work work, Runnable after) {
        stopFrameLoop();
        transitions.submit(work, failure -> {
            if (failure != null) recordFailure(failure);
            if (isFinishing() || isDestroyed()) return;
            if (transitions.getFailure() != null) {
                if (hostResumed) showSessionFailure(transitions.getFailure());
                return;
            }
            after.run();
            startFrameLoopIfReady();
        });
    }

    private void recordFailure(Throwable failure) {
        if (failure != null && productFailure == null) {
            productFailure = failure;
        }
    }

    private void failAndFinish(Throwable failure) {
        recordFailure(failure);
        uiState = uiState.failed();
        bindProductUi();
        Toast.makeText(this, R.string.n3ds_product_launch_failed, Toast.LENGTH_LONG).show();
        if (virtualControls != null) {
            virtualControls.setControlsEnabled(false);
        }
        finish();
    }

    @SuppressWarnings("deprecation")
    private int currentDisplayRotation() {
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static Thread displayDaemonThread(Runnable runnable, String name) {
        return daemonThread(() -> {
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_DISPLAY);
            } catch (RuntimeException | LinkageError ignored) {
                // Keep the default priority when a host test or vendor scheduler rejects the hint.
            }
            runnable.run();
        }, name);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static void requestNintendo3DsFrameRate(Surface surface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || surface == null || !surface.isValid()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                        Nintendo3DsFramePacer.NOMINAL_FRAMES_PER_SECOND_FLOAT,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            } else {
                surface.setFrameRate(
                        Nintendo3DsFramePacer.NOMINAL_FRAMES_PER_SECOND_FLOAT,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // This is a compositor hint only. The deadline pacer remains the portable fallback.
        }
    }

    @FunctionalInterface
    private interface CheckedTransition {
        void run() throws IOException;
    }

    private enum ProductAction {
        NONE,
        EXIT,
        RESET,
        REPORT,
        FAILURE
    }

    private static final class Nintendo3DsSurfaceView extends SurfaceView {
        Nintendo3DsSurfaceView(Context context) {
            super(context);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
