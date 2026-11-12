package ph.trailwatch.hikerphone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 10;
    private static final int EXPORT_REQUEST = 11;
    private int BG;
    private int TEXT;
    private int MUTED;
    private int HINT;
    private int GREEN;
    private int AMBER;
    private int ERROR;
    private int ERROR_TEXT;

    private EditText nodeField;
    private EditText movementThresholdField;
    private TextView stateView;
    private TextView gpsView;
    private TextView safetyView;
    private TextView nodeErrorView;
    private TextView thresholdErrorView;
    private TextView fixValue;
    private TextView recordsValue;
    private TextView taggedValue;
    private TextView sosTitleView;
    private TextView sosSubtitleView;
    private TextView locationErrorMessage;
    private TextView connectionStripText;
    private Button trackButton;
    private Button exportButton;
    private Button simulatorButton;
    private View sosTouchTarget;
    private ProgressBar gpsSpinner;
    private ProgressBar sosProgress;
    private View skeletonLong;
    private View skeletonShort;
    private View locationErrorCard;
    private View sosErrorCard;
    private Spinner designSpinner;
    private RadioGroup modeSelector;
    private RadioButton testModeOption;
    private RadioButton liveModeOption;
    private LinearLayout simulationCard;
    private LocationTrackingService service;
    private FakeEsp32Transport fakeEsp32Transport;
    private HikerSafetyState simulationState;
    private boolean bound;
    private boolean requestedStart;
    private boolean sosHoldTriggered;
    private boolean suppressDesignCallback;
    private boolean changingModeSelection;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable sosHoldAction = () -> {
        sosHoldTriggered = true;
        sosProgress.setProgress(1000);
        sosTitleView.setText("SOS requested");
        sosSubtitleView.setText("Saving locally if no radio link");
        triggerSOS();
    };
    private CountDownTimer inactivityCountdown;
    private AlertDialog inactivityDialog;
    private CountDownTimer simulationCountdown;
    private CountDownTimer sosProgressTimer;
    private AlertDialog simulationDialog;
    private boolean simulationPromptPending;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BG = getColor(R.color.tw_bg);
        TEXT = getColor(R.color.tw_text_primary);
        MUTED = getColor(R.color.tw_text_secondary);
        HINT = getColor(R.color.tw_hint);
        GREEN = getColor(R.color.tw_primary);
        AMBER = getColor(R.color.tw_test_amber);
        ERROR = getColor(R.color.tw_error);
        ERROR_TEXT = getColor(R.color.tw_error_text);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        prefs = getSharedPreferences("hiker", MODE_PRIVATE);
        resetSimulationState();
        setContentView(buildScreen());
        renderStoredState();
    }

    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(scroll, scrollLp);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(12), dp(20), dp(12));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView brand = label("TRAILWATCH");
        brand.setTextColor(GREEN);
        brand.setLetterSpacing(0.09f);
        identity.addView(brand);
        TextView title = text("Hiker", 22, TEXT, true);
        identity.addView(title);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));

        modeSelector = new RadioGroup(this);
        modeSelector.setOrientation(RadioGroup.HORIZONTAL);
        modeSelector.setBackground(getDrawable(R.drawable.tw_mode_track));
        modeSelector.setPadding(dp(3), dp(3), dp(3), dp(3));
        testModeOption = modeOption("TEST", R.drawable.tw_mode_test, "TEST mode");
        liveModeOption = modeOption("LIVE", R.drawable.tw_mode_live, "LIVE mode");
        testModeOption.setMinWidth(dp(76));
        testModeOption.setMinHeight(dp(48));
        liveModeOption.setMinWidth(dp(76));
        liveModeOption.setMinHeight(dp(48));
        modeSelector.addView(testModeOption, new RadioGroup.LayoutParams(-2, -2));
        modeSelector.addView(liveModeOption, new RadioGroup.LayoutParams(-2, -2));
        modeSelector.check(prefs.getBoolean("test_mode", true) ? testModeOption.getId() : liveModeOption.getId());
        modeSelector.setOnCheckedChangeListener((group, checkedId) -> {
            if (changingModeSelection) return;
            boolean enteringLive = checkedId == liveModeOption.getId();
            boolean currentlyTest = prefs.getBoolean("test_mode", true);
            if (enteringLive && currentlyTest) {
                selectOperatingMode(true);
                new AlertDialog.Builder(this)
                        .setTitle("Switch to LIVE mode?")
                        .setMessage("LIVE mode turns off the TEST flag and hides the simulator. GPS and safety records stay on this phone; nothing is sent to Base Camp because the ESP32 adapter is not connected.")
                        .setNegativeButton("Stay in TEST", (dialog, which) -> selectOperatingMode(true))
                        .setPositiveButton("Switch to LIVE", (dialog, which) -> applyOperatingMode(false))
                        .setOnCancelListener(dialog -> selectOperatingMode(true))
                        .show();
            } else {
                applyOperatingMode(!enteringLive);
            }
        });
        header.addView(modeSelector);
        page.addView(header);

        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(12), dp(10), dp(12), dp(10));
        strip.setBackground(getDrawable(R.drawable.tw_strip_warning));
        ImageView noSignal = new ImageView(this);
        noSignal.setImageResource(R.drawable.ic_signal_off);
        noSignal.setContentDescription("No radio signal");
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.rightMargin = dp(10);
        strip.addView(noSignal, iconLp);
        connectionStripText = text("Radio link not set up. GPS/SOS stay on phone.", 13,
                getColor(R.color.tw_warn_text), false);
        strip.addView(connectionStripText, new LinearLayout.LayoutParams(0, -2, 1f));
        addBlock(page, strip);

        locationErrorCard = buildLocationErrorCard();
        locationErrorCard.setVisibility(View.GONE);
        addBlock(page, locationErrorCard);
        sosErrorCard = buildSosErrorCard();
        sosErrorCard.setVisibility(View.GONE);
        addBlock(page, sosErrorCard);

        LinearLayout gpsCard = card();
        gpsCard.setPadding(dp(20), dp(20), dp(20), dp(20));
        gpsCard.addView(label("GPS TRACKING"));
        LinearLayout gpsTitleRow = new LinearLayout(this);
        gpsTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        gpsSpinner = new ProgressBar(this);
        gpsSpinner.setIndeterminate(true);
        gpsSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GREEN));
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        spinnerLp.rightMargin = dp(10);
        gpsTitleRow.addView(gpsSpinner, spinnerLp);
        stateView = text("Not tracking", 34, TEXT, true);
        gpsTitleRow.addView(stateView, new LinearLayout.LayoutParams(0, -2, 1f));
        gpsCard.addView(gpsTitleRow);
        gpsView = text("No records yet", 14, MUTED, false);
        LinearLayout.LayoutParams gpsInfoLp = params(-1, -2);
        gpsInfoLp.topMargin = dp(4);
        gpsCard.addView(gpsView, gpsInfoLp);
        skeletonLong = skeletonBar(0.72f);
        skeletonShort = skeletonBar(0.46f);
        gpsCard.addView(skeletonLong);
        LinearLayout.LayoutParams skeletonShortLp = params(-1, dp(7));
        skeletonShortLp.topMargin = dp(6);
        gpsCard.addView(skeletonShort, skeletonShortLp);
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.tw_border));
        LinearLayout.LayoutParams dividerLp = params(-1, dp(1));
        dividerLp.topMargin = dp(12);
        dividerLp.bottomMargin = dp(10);
        gpsCard.addView(divider, dividerLp);
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.addView(metric("FIX", "—"), new LinearLayout.LayoutParams(0, -2, 1f));
        View metricGap1 = new View(this);
        metrics.addView(metricGap1, new LinearLayout.LayoutParams(dp(10), 1));
        metrics.addView(metric("RECORDS", "0"), new LinearLayout.LayoutParams(0, -2, 1f));
        View metricGap2 = new View(this);
        metrics.addView(metricGap2, new LinearLayout.LayoutParams(dp(10), 1));
        metrics.addView(metric("TAGGED", "TEST"), new LinearLayout.LayoutParams(0, -2, 1f));
        fixValue = (TextView) ((LinearLayout) metrics.getChildAt(0)).getChildAt(1);
        recordsValue = (TextView) ((LinearLayout) metrics.getChildAt(2)).getChildAt(1);
        taggedValue = (TextView) ((LinearLayout) metrics.getChildAt(4)).getChildAt(1);
        gpsCard.addView(metrics);
        safetyView = text("", 14, GREEN, true);
        LinearLayout.LayoutParams safetyLp = params(-1, -2);
        safetyLp.topMargin = dp(10);
        gpsCard.addView(safetyView, safetyLp);
        safetyView.setVisibility(View.GONE);
        addBlock(page, gpsCard);

        LinearLayout nodeCard = card();
        TextView nodeLabel = label("HIKER NODE ID");
        nodeField = field("Your assigned ID, e.g. H1", false);
        nodeField.setText(prefs.getString("node_id", ""));
        nodeLabel.setLabelFor(nodeField.getId());
        nodeCard.addView(nodeLabel);
        nodeCard.addView(nodeField);
        nodeErrorView = text("Enter your ID to continue.", 13, ERROR_TEXT, false);
        LinearLayout.LayoutParams nodeErrorLp = params(-1, -2);
        nodeErrorLp.topMargin = dp(4);
        nodeCard.addView(nodeErrorView, nodeErrorLp);
        nodeField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateNodeIdState(false); }
            @Override public void afterTextChanged(Editable s) { }
        });
        addBlock(page, nodeCard);

        LinearLayout linkCard = card();
        TextView linkLabel = label("PHONE-TO-BOARD LINK · PLANNING ONLY");
        designSpinner = new Spinner(this);
        designSpinner.setId(View.generateViewId());
        designSpinner.setMinimumHeight(dp(48));
        linkLabel.setLabelFor(designSpinner.getId());
        String[] designs = {"Choose a design…", "Bluetooth LE (Design A)", "USB-C OTG serial (Design B)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, designs);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        designSpinner.setAdapter(adapter);
        String savedDesign = prefs.getString("link_design", "");
        suppressDesignCallback = true;
        designSpinner.setSelection("BLE".equals(savedDesign) ? 1 : "USB_OTG".equals(savedDesign) ? 2 : 0);
        suppressDesignCallback = false;
        designSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (suppressDesignCallback) return;
                if (position == 1) prefs.edit().putString("link_design", "BLE").apply();
                else if (position == 2) prefs.edit().putString("link_design", "USB_OTG").apply();
                else prefs.edit().remove("link_design").apply();
                updateHardwareStatus();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        linkCard.addView(linkLabel);
        linkCard.addView(designSpinner);
        addBlock(page, linkCard);

        LinearLayout inactivityCard = card();
        TextView inactivityLabel = label("INACTIVITY CHECK · METERS");
        movementThresholdField = field("Set a threshold to enable", true);
        inactivityLabel.setLabelFor(movementThresholdField.getId());
        movementThresholdField.setText(prefs.contains("movement_threshold_meters")
                ? Float.toString(prefs.getFloat("movement_threshold_meters", 0f)) : "");
        inactivityCard.addView(inactivityLabel);
        inactivityCard.addView(movementThresholdField);
        TextView inactivityHint = text("30 min stillness - ARE YOU OK? - emergency after 15 sec", 12, MUTED, false);
        LinearLayout.LayoutParams inactivityHintLp = params(-1, -2);
        inactivityHintLp.topMargin = dp(3);
        inactivityCard.addView(inactivityHint, inactivityHintLp);
        thresholdErrorView = text("Enter a positive distance in meters.", 13, ERROR_TEXT, false);
        LinearLayout.LayoutParams thresholdErrorLp = params(-1, -2);
        thresholdErrorLp.topMargin = dp(3);
        inactivityCard.addView(thresholdErrorView, thresholdErrorLp);
        thresholdErrorView.setVisibility(View.GONE);
        addBlock(page, inactivityCard);

        addBlock(page, buildUtilityRow());

        simulationCard = buildSimulatorPanel();

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(dp(20), dp(8), dp(20), dp(8));
        trackButton = new Button(this, null, 0, R.style.WidgetTrailwatchPrimaryButton);
        trackButton.setAllCaps(false);
        trackButton.setTextSize(16);
        trackButton.setGravity(Gravity.CENTER);
        trackButton.setMinimumHeight(dp(56));
        trackButton.setOnClickListener(v -> onTrackPressed());
        dock.addView(trackButton, params(-1, -2));

        FrameLayout sosFrame = new FrameLayout(this);
        sosFrame.setBackground(getDrawable(R.drawable.tw_button_sos));
        sosFrame.setMinimumHeight(dp(64));
        sosFrame.setClickable(true);
        sosFrame.setFocusable(true);
        sosFrame.setContentDescription("Hold for SOS. Press and hold for 2 seconds.");
        LinearLayout sosTextBlock = new LinearLayout(this);
        sosTextBlock.setGravity(Gravity.CENTER);
        sosTextBlock.setOrientation(LinearLayout.VERTICAL);
        sosTitleView = text("Hold for SOS", 18, getColor(R.color.tw_on_sos), true);
        sosSubtitleView = text("Press and hold 2 seconds", 13, getColor(R.color.tw_on_sos), false);
        sosTitleView.setGravity(Gravity.CENTER);
        sosSubtitleView.setGravity(Gravity.CENTER);
        sosTextBlock.addView(sosTitleView);
        sosTextBlock.addView(sosSubtitleView);
        FrameLayout.LayoutParams sosTextLp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        sosFrame.addView(sosTextBlock, sosTextLp);
        sosProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        sosProgress.setMax(1000);
        sosProgress.setProgress(0);
        sosProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.tw_on_sos)));
        sosProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.tw_error_border)));
        FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM);
        progressLp.leftMargin = dp(16);
        progressLp.rightMargin = dp(16);
        progressLp.bottomMargin = dp(8);
        sosFrame.addView(sosProgress, progressLp);
        sosTouchTarget = sosFrame;
        sosTouchTarget.setOnTouchListener(this::onSosTouch);
        sosTouchTarget.setOnLongClickListener(v -> { if (!sosHoldTriggered) { sosHoldTriggered = true; triggerSOS(); } return true; });
        LinearLayout.LayoutParams sosLp = params(-1, -2);
        sosLp.topMargin = dp(8);
        dock.addView(sosFrame, sosLp);

        TextView footer = text("Saved on this phone only. Nothing is uploaded.", 12, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = params(-1, -2);
        footerLp.topMargin = dp(6);
        dock.addView(footer, footerLp);
        root.addView(dock, params(-1, -2));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topInset = bars.top;
                bottomInset = bars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            page.setPadding(dp(20), dp(12) + topInset, dp(20), dp(12));
            dock.setPadding(dp(20), dp(8), dp(20), dp(8) + bottomInset);
            return insets;
        });

        updateNodeIdState(false);
        updateHardwareStatus();
        updateModeDisplay();
        renderStoredState();
        return root;
    }

    private RadioButton modeOption(String title, int background, String description) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId());
        option.setText(title);
        option.setTextSize(14);
        option.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        option.setGravity(Gravity.CENTER);
        option.setButtonDrawable((Drawable) null);
        option.setBackground(getDrawable(background));
        option.setPadding(dp(8), 0, dp(8), 0);
        option.setMinHeight(dp(48));
        option.setContentDescription(description);
        option.setTextColor(MUTED);
        return option;
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this, null, 0, R.style.TrailwatchCard);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setBackground(getDrawable(R.drawable.tw_card));
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 11, MUTED, true);
        view.setTextAppearance(R.style.TrailwatchLabel);
        view.setTextColor(MUTED);
        view.setLetterSpacing(0.09f);
        LinearLayout.LayoutParams lp = params(-1, -2);
        lp.bottomMargin = dp(4);
        view.setLayoutParams(lp);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        if (size >= 33) view.setTextAppearance(R.style.TextAppearanceTrailwatchScreenTitle);
        else if (size >= 24) view.setTextAppearance(R.style.TextAppearanceTrailwatchCardTitle);
        else if (size >= 21) view.setTextAppearance(R.style.TextAppearanceTrailwatchHeader);
        else if (size >= 16) view.setTextAppearance(R.style.TextAppearanceTrailwatchBody);
        else if (size >= 13) view.setTextAppearance(R.style.TextAppearanceTrailwatchCaption);
        else view.setTextAppearance(R.style.TextAppearanceTrailwatchLabel);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.create(bold ? "sans-serif-medium" : "sans-serif",
                android.graphics.Typeface.NORMAL));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private EditText field(String hint, boolean decimal) {
        EditText view = new EditText(this, null, 0, R.style.WidgetTrailwatchField);
        view.setId(View.generateViewId());
        view.setSingleLine(true);
        view.setTextSize(16);
        view.setTextColor(TEXT);
        view.setHintTextColor(HINT);
        view.setHint(hint);
        view.setMinHeight(dp(48));
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(getDrawable(R.drawable.tw_field));
        if (decimal) {
            view.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
        return view;
    }

    private LinearLayout metric(String title, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(title);
        heading.setLetterSpacing(0.08f);
        box.addView(heading);
        TextView data = text(value, 16, TEXT, true);
        data.setSingleLine(true);
        box.addView(data);
        return box;
    }

    private View skeletonBar(float fraction) {
        View bar = new View(this);
        bar.setBackground(getDrawable(R.drawable.tw_skeleton));
        float density = getResources().getDisplayMetrics().density;
        int screenDp = (int) (getResources().getDisplayMetrics().widthPixels / density);
        int width = Math.max(dp(72), dp(screenDp - 40 - 28) * Math.round(fraction * 100f) / 100);
        bar.setLayoutParams(params(width, dp(7)));
        bar.setVisibility(View.GONE);
        return bar;
    }

    private void addBlock(LinearLayout parent, View child) {
        LinearLayout.LayoutParams lp = params(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(child, lp);
    }

    private LinearLayout buildLocationErrorCard() {
        LinearLayout card = card();
        card.setBackground(getDrawable(R.drawable.tw_strip_error));
        TextView title = label("LOCATION ERROR");
        title.setTextColor(ERROR);
        card.addView(title);
        card.addView(text("Location is off", 22, TEXT, true));
        locationErrorMessage = text("Allow location, including in the background, so tracking continues with the screen off.", 14, ERROR_TEXT, false);
        LinearLayout.LayoutParams messageLp = params(-1, -2);
        messageLp.topMargin = dp(4);
        card.addView(locationErrorMessage, messageLp);
        Button settings = button("Open settings", R.drawable.tw_button_ghost);
        settings.setOnClickListener(v -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        LinearLayout.LayoutParams buttonLp = params(-1, -2);
        buttonLp.topMargin = dp(8);
        card.addView(settings, buttonLp);
        return card;
    }

    private LinearLayout buildSosErrorCard() {
        LinearLayout card = card();
        card.setBackground(getDrawable(R.drawable.tw_strip_error));
        TextView title = label("SOS NOT SENT");
        title.setTextColor(ERROR);
        card.addView(title);
        card.addView(text("SOS saved on this phone, not sent. Use your normal emergency procedure.", 14, ERROR_TEXT, false));
        return card;
    }

    private LinearLayout buildUtilityRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        simulatorButton = button("Simulator", R.drawable.tw_button_ghost);
        simulatorButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_simulator, 0, 0, 0);
        simulatorButton.setCompoundDrawablePadding(dp(8));
        simulatorButton.setOnClickListener(v -> openSimulatorPanel());
        LinearLayout.LayoutParams simLp = new LinearLayout.LayoutParams(0, -2, 1f);
        simLp.rightMargin = dp(6);
        row.addView(simulatorButton, simLp);
        exportButton = button("Export log", R.drawable.tw_button_ghost);
        exportButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_export, 0, 0, 0);
        exportButton.setCompoundDrawablePadding(dp(8));
        exportButton.setOnClickListener(v -> exportLog());
        LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(0, -2, 1f);
        exportLp.leftMargin = dp(6);
        row.addView(exportButton, exportLp);
        return row;
    }

    private Button button(String title, int background) {
        int style = background == R.drawable.tw_button_ghost
                ? R.style.WidgetTrailwatchGhostButton : R.style.WidgetTrailwatchButton;
        Button view = new Button(this, null, 0, style);
        view.setAllCaps(false);
        view.setText(title);
        view.setTextSize(14);
        view.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        view.setTextColor(TEXT);
        view.setGravity(Gravity.CENTER);
        view.setBackground(getDrawable(background));
        view.setMinimumHeight(dp(48));
        view.setMinHeight(dp(48));
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setStateListAnimator(null);
        return view;
    }

    private LinearLayout buildSimulatorPanel() {
        LinearLayout panel = card();
        panel.addView(text("Hardware-free simulation, local only, never sends to ESP32", 18, TEXT, true));
        TextView description = text("Use these controls to try phone alerts before the board is available. Simulated flags stay separate from real phone state.", 14, MUTED, false);
        LinearLayout.LayoutParams descriptionLp = params(-1, -2);
        descriptionLp.topMargin = dp(6);
        panel.addView(description, descriptionLp);
        addSimulationButton(panel, "Simulate OFF TRAIL alert", () -> simulateDownlink(HikerDownlink.Type.ALERT));
        addSimulationButton(panel, "Simulate STORM - TURN BACK", () -> simulateDownlink(HikerDownlink.Type.STORM_TURN_BACK));
        addSimulationButton(panel, "Simulate SOS RECEIVED", () -> simulateDownlink(HikerDownlink.Type.SOS_RECEIVED));
        addSimulationButton(panel, "Simulate 3 missed replies → LINK LOST", this::simulateThreeMissedReplies);
        addSimulationButton(panel, "Simulate reply restored", () -> simulationState.onReplySlotClosed(true));
        addSimulationButton(panel, "Simulate battery below 20%", () -> simulationState.onBatteryPercent(19));
        addSimulationButton(panel, "Simulate ARE YOU OK? prompt", this::simulateInactivityPrompt);
        addSimulationButton(panel, "Reset simulated alerts", this::resetSimulationState);
        panel.addView(text("Simulated link · runs the real service path, no real board", 14, MUTED, false));
        addSimulationButton(panel, "Connect simulated link", this::connectSimulatedLink);
        addSimulationButton(panel, "Disconnect simulated link", this::disconnectSimulatedLink);
        addSimulationButton(panel, "Send simulated ACK", this::sendSimulatedAck);
        addSimulationButton(panel, "Miss reply slot", () -> closeSimulatedReplySlot(false));
        addSimulationButton(panel, "Receive reply slot", () -> closeSimulatedReplySlot(true));
        return panel;
    }

    private void openSimulatorPanel() {
        if (!prefs.getBoolean("test_mode", true)) return;
        ViewGroup oldParent = (ViewGroup) simulationCard.getParent();
        if (oldParent != null) oldParent.removeView(simulationCard);
        ScrollView content = new ScrollView(this);
        content.setFillViewport(true);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));
        content.addView(simulationCard);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Simulator")
                .setView(content)
                .setPositiveButton("Done", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            ViewGroup parent = (ViewGroup) simulationCard.getParent();
            if (parent != null) parent.removeView(simulationCard);
        });
        dialog.show();
    }

    private void updateNodeIdState(boolean showInvalid) {
        if (nodeField == null) return;
        String value = nodeField.getText().toString().trim();
        boolean valid = value.matches("[A-Za-z0-9_-]{1,32}");
        boolean active = prefs != null && prefs.getBoolean("tracking_active", false);
        if (valid) {
            nodeErrorView.setVisibility(View.GONE);
            nodeField.setBackground(getDrawable(R.drawable.tw_field));
        } else {
            nodeErrorView.setText(value.isEmpty() ? "Enter your ID to continue." : "Use 1–32 letters, numbers, _ or -.");
            nodeErrorView.setVisibility(View.VISIBLE);
            nodeField.setBackground(getDrawable(R.drawable.tw_field_error));
        }
        if (trackButton != null) {
            trackButton.setEnabled(active || valid);
            trackButton.setTextColor(getColor(active || valid ? R.color.tw_on_primary : R.color.tw_disabled_text));
        }
    }

    private void updateConnectionStrip() {
        if (connectionStripText == null) return;
        boolean testMode = prefs.getBoolean("test_mode", true);
        boolean connected = service != null && service.isTransportConnected();
        boolean simulated = connected && service.isTransportSimulated();
        if (!connected) {
            connectionStripText.setText(testMode
                    ? "Radio link not set up. GPS/SOS stay on phone."
                    : "LIVE mode · local GPS only. ESP32 not connected. Nothing sent to Base Camp.");
        } else if (simulated) {
            connectionStripText.setText("SIMULATED link · no real ESP32. Nothing sent to Base Camp.");
        } else {
            connectionStripText.setText(testMode
                    ? "ESP32 link connected · TEST records remain local until firmware sends them."
                    : "LIVE mode · ESP32 link connected. Base Camp delivery is not confirmed.");
        }
    }

    private void showLocationError(String message) {
        if (locationErrorCard == null) return;
        locationErrorMessage.setText("Allow location, including in the background, so tracking continues with the screen off.");
        locationErrorCard.setVisibility(View.VISIBLE);
        if (message != null) {
            locationErrorCard.setContentDescription("Location error. " + message);
        }
    }

    private boolean onSosTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            sosHoldTriggered = false;
            sosTitleView.setText("Keep holding SOS");
            sosSubtitleView.setText("Hold until the bar completes");
            startSosProgress();
            mainHandler.postDelayed(sosHoldAction, 2000L);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            mainHandler.removeCallbacks(sosHoldAction);
            if (sosProgressTimer != null) sosProgressTimer.cancel();
            sosProgress.setProgress(0);
            if (!sosHoldTriggered) {
                sosTitleView.setText("Hold for SOS");
                sosSubtitleView.setText("Press and hold 2 seconds");
                if (event.getAction() == MotionEvent.ACTION_UP) Toast.makeText(this, "Keep holding the SOS button for 2 seconds", Toast.LENGTH_SHORT).show();
            } else {
                sosTitleView.setText("SOS saved");
                sosSubtitleView.setText("Hold again · 2 seconds");
            }
            return true;
        }
        return true;
    }

    private void startSosProgress() {
        if (sosProgressTimer != null) sosProgressTimer.cancel();
        sosProgress.setProgress(0);
        sosProgressTimer = new CountDownTimer(2000L, 40L) {
            @Override public void onTick(long remaining) {
                sosProgress.setProgress((int) ((2000L - remaining) * 1000L / 2000L));
            }
            @Override public void onFinish() { sosProgress.setProgress(1000); }
        }.start();
    }

    private void triggerSOS() {
        String nodeId = nodeField.getText().toString().trim();
        if (!nodeId.matches("[A-Za-z0-9_-]{1,32}")) {
            updateNodeIdState(true);
            sosTitleView.setText("Hold for SOS");
            sosSubtitleView.setText("Enter your hiker ID first");
            return;
        }
        prefs.edit().putString("node_id", nodeId).putBoolean("flag_emergency", true).apply();
        if (service != null) {
            service.requestManualSOS();
            if (!service.isTransportConnected() || service.isTransportSimulated()) sosErrorCard.setVisibility(View.VISIBLE);
        } else {
            appendLocalEvent("SOS_PENDING", "Manual SOS requested · not sent to an ESP32", true);
            AlertFeedback.alert(this, "SOS_PENDING");
            safetyView.setText("SOS NOT SENT · saved on this phone");
            safetyView.setVisibility(View.VISIBLE);
            sosErrorCard.setVisibility(View.VISIBLE);
            new AlertDialog.Builder(this)
                    .setTitle("SOS saved on this phone")
                    .setMessage("SOS saved on this phone, not sent. Use your normal emergency procedure.")
                    .setPositiveButton("Understood", null)
                    .show();
        }
    }

    private void onTrackPressed() {
        if (prefs.getBoolean("tracking_active", false)) {
            if (service != null) service.stopTracking();
            else stopService(new Intent(this, LocationTrackingService.class));
            prefs.edit().putBoolean("tracking_active", false).apply();
            renderStoredState();
            return;
        }
        String nodeId = nodeField.getText().toString().trim();
        if (!nodeId.matches("[A-Za-z0-9_-]{1,32}")) {
            updateNodeIdState(true);
            return;
        }
        if (!saveMovementThreshold()) return;
        prefs.edit().putString("node_id", nodeId).apply();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestedStart = true;
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, LOCATION_PERMISSION_REQUEST);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
            }
            return;
        }
        locationErrorCard.setVisibility(View.GONE);
        sosErrorCard.setVisibility(View.GONE);
        startTrackingService(nodeId);
    }

    private boolean saveMovementThreshold() {
        String raw = movementThresholdField.getText().toString().trim();
        if (raw.isEmpty()) {
            prefs.edit().remove("movement_threshold_meters").apply();
            movementThresholdField.setBackground(getDrawable(R.drawable.tw_field));
            thresholdErrorView.setVisibility(View.GONE);
            return true;
        }
        try {
            float value = Float.parseFloat(raw);
            if (!Float.isFinite(value) || value <= 0f) throw new NumberFormatException();
            prefs.edit().putFloat("movement_threshold_meters", value).apply();
        } catch (NumberFormatException e) {
            movementThresholdField.setBackground(getDrawable(R.drawable.tw_field_error));
            thresholdErrorView.setVisibility(View.VISIBLE);
            return false;
        }
        movementThresholdField.setBackground(getDrawable(R.drawable.tw_field));
        thresholdErrorView.setVisibility(View.GONE);
        return true;
    }

    private void startTrackingService(String nodeId) {
        android.location.LocationManager locationManager =
                (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null || !locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            prefs.edit().putBoolean("tracking_active", false).apply();
            showLocationError("Turn on Location / GPS");
            updateTrackButton(false);
            return;
        }
        locationErrorCard.setVisibility(View.GONE);
        Intent intent = new Intent(this, LocationTrackingService.class).putExtra("node_id", nodeId);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        bindToTrackingService();
        showLoadingState();
    }

    private void bindToTrackingService() {
        if (bound) return;
        bound = bindService(new Intent(this, LocationTrackingService.class), connection, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((LocationTrackingService.LocalBinder) binder).getService();
            service.setListener(new LocationTrackingService.Listener() {
                @Override public void onSample(GpsSample sample, long records) { showSample(sample, records); }
                @Override public void onStatus(String message, boolean active) {
                    if (!active && service.isTransportSimulated()) service.installTransport(null, PhoneLinkDesign.BLE);
                    if (active) {
                        showLoadingState();
                        locationErrorCard.setVisibility(View.GONE);
                    } else {
                        stateView.setText("Not tracking");
                        gpsSpinner.setVisibility(View.GONE);
                        skeletonLong.setVisibility(View.GONE);
                        skeletonShort.setVisibility(View.GONE);
                        gpsView.setText(new LocalSampleStore(MainActivity.this).getRecordCount() == 0
                                ? "No records yet" : "Records saved on this phone");
                        if (isLocationError(message)) {
                            showLocationError(message);
                        }
                    }
                    updateTrackButton(prefs.getBoolean("tracking_active", false));
                    nodeField.setEnabled(!active);
                    updateModeDisplay();
                }
                @Override public void onSafetyEvent(String type, String message, boolean urgent, HikerFlags flags) {
                    if ("STATE".equals(type) && "No active phone warning".equals(message)) {
                        safetyView.setVisibility(View.GONE);
                        return;
                    }
                    safetyView.setText(message);
                    safetyView.setVisibility(View.VISIBLE);
                    safetyView.setTextColor(urgent ? ERROR : flags.lowBattery ? AMBER : GREEN);
                    if ("SOS_PENDING".equals(type) && (!service.isTransportConnected() || service.isTransportSimulated())) sosErrorCard.setVisibility(View.VISIBLE);
                    updateHardwareStatus();
                    if ("OFF_TRAIL".equals(type) || "STORM_TURN_BACK".equals(type) || "SOS_RECEIVED".equals(type)) showSafetyDialog(message);
                }
                @Override public void onInactivityPrompt(long seconds) { showInactivityPrompt(seconds); }
            });
            recordsValue.setText(Long.toString(service.getRecordCount()));
            updateTrackButton(service.isActive());
            updateHardwareStatus();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (service != null && service.isTransportSimulated()) service.installTransport(null, PhoneLinkDesign.BLE);
            service = null;
            updateHardwareStatus();
        }
    };

    private void showSample(GpsSample s, long records) {
        String accuracy = s.accuracyMeters == null ? "Unknown" : String.format(Locale.US, "%.1f m", s.accuracyMeters);
        String sats = s.satellitesUsed == null ? "Sats —" : s.satellitesUsed + " sats";
        String battery = s.phoneBatteryPercent == null ? "Phone —" : "Phone " + s.phoneBatteryPercent + "%";
        stateView.setText("Tracking");
        gpsView.setText(String.format(Locale.US, "%.6f, %.6f  ·  %s  ·  %s", s.latitude, s.longitude, sats, battery));
        fixValue.setText(accuracy);
        recordsValue.setText(Long.toString(records));
        taggedValue.setText(s.flags != null && s.flags.testMode ? "TEST" : "REAL");
        taggedValue.setTextColor(s.flags != null && s.flags.testMode ? AMBER : GREEN);
        gpsSpinner.setVisibility(View.GONE);
        skeletonLong.setVisibility(View.GONE);
        skeletonShort.setVisibility(View.GONE);
    }

    private void showInactivityPrompt(long seconds) {
        if (isFinishing()) return;
        if (inactivityDialog != null && inactivityDialog.isShowing()) return;
        inactivityDialog = new AlertDialog.Builder(this)
                .setTitle("ARE YOU OK?")
                .setMessage("No movement has been detected. Respond within " + seconds + " seconds.")
                .setCancelable(false)
                .setPositiveButton("I'm OK", null)
                .create();
        inactivityDialog.setOnShowListener(dialog -> inactivityDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (inactivityCountdown != null) inactivityCountdown.cancel();
            if (service != null) service.respondToInactivityPrompt();
            inactivityDialog.dismiss();
        }));
        inactivityDialog.show();
        inactivityCountdown = new CountDownTimer(Math.max(1L, seconds) * 1000L, 1000L) {
            @Override public void onTick(long remaining) {
                if (inactivityDialog != null && inactivityDialog.isShowing()) {
                    inactivityDialog.setMessage("No movement has been detected. Respond within " + ((remaining + 999L) / 1000L) + " seconds.");
                }
            }
            @Override public void onFinish() {
                if (inactivityDialog != null && inactivityDialog.isShowing()) {
                    inactivityDialog.setMessage("Time expired · emergency flag set");
                    inactivityDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                }
            }
        }.start();
    }

    private void showSafetyDialog(String message) {
        showSafetyDialog(message, false);
    }

    private void showSafetyDialog(String message, boolean simulation) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(simulation ? "Simulation alert" : "Trailwatch warning").setMessage(message)
                .setPositiveButton("Understood", null).show();
    }

    private void renderStoredState() {
        boolean active = prefs.getBoolean("tracking_active", false);
        updateTrackButton(active);
        nodeField.setEnabled(!active);
        long records = new LocalSampleStore(this).getRecordCount();
        recordsValue.setText(Long.toString(records));
        taggedValue.setText(prefs.getBoolean("test_mode", true) ? "TEST" : "REAL");
        taggedValue.setTextColor(prefs.getBoolean("test_mode", true) ? AMBER : GREEN);
        if (active) showLoadingState();
        else {
            stateView.setText("Not tracking");
            gpsView.setText(records == 0 ? "No records yet" : "Records saved on this phone");
            gpsSpinner.setVisibility(View.GONE);
            skeletonLong.setVisibility(View.GONE);
            skeletonShort.setVisibility(View.GONE);
        }
        String lastSafetyMessage = prefs.getString("last_event_message", "");
        if (!lastSafetyMessage.isEmpty()) {
            safetyView.setText(lastSafetyMessage);
            safetyView.setVisibility(View.VISIBLE);
        }
        if (prefs.getBoolean("flag_emergency", false)) {
            safetyView.setText("SOS PENDING · delivery not confirmed");
            safetyView.setVisibility(View.VISIBLE);
            if (service == null || !service.isTransportConnected() || service.isTransportSimulated()) sosErrorCard.setVisibility(View.VISIBLE);
        }
        else if (prefs.getBoolean("flag_link_lost", false)) { safetyView.setText("LINK LOST"); safetyView.setVisibility(View.VISIBLE); }
        else if (prefs.getBoolean("flag_low_battery", false)) { safetyView.setText("Phone battery below 20%"); safetyView.setVisibility(View.VISIBLE); }
        else if (prefs.getBoolean("flag_turn_back_received", false)) { safetyView.setText("STORM - TURN BACK received"); safetyView.setVisibility(View.VISIBLE); }
        updateNodeIdState(false);
        updateModeDisplay();
    }

    private void showLoadingState() {
        stateView.setText("Finding GPS…");
        gpsView.setText("Move to open sky. Records start at first fix.");
        gpsSpinner.setVisibility(View.VISIBLE);
        skeletonLong.setVisibility(View.VISIBLE);
        skeletonShort.setVisibility(View.VISIBLE);
    }

    private void updateTrackButton(boolean active) {
        if (trackButton == null) return;
        trackButton.setText(active ? "Stop tracking" : "Start GPS tracking");
        trackButton.setBackground(getDrawable(active ? R.drawable.tw_button_ghost : R.drawable.tw_button_primary));
        trackButton.setEnabled(active || (nodeField != null && nodeField.getText().toString().trim().matches("[A-Za-z0-9_-]{1,32}")));
        trackButton.setTextColor(getColor(active ? R.color.tw_text_primary
                : trackButton.isEnabled() ? R.color.tw_on_primary : R.color.tw_disabled_text));
    }

    private boolean isLocationError(String message) {
        return message != null && (message.contains("Location permission")
                || message.contains("Turn on Location") || message.contains("GPS was turned off")
                || message.contains("Could not start GPS"));
    }

    private void updateHardwareStatus() {
        updateConnectionStrip();
    }

    private void selectOperatingMode(boolean testMode) {
        if (modeSelector == null) return;
        int id = testMode ? testModeOption.getId() : liveModeOption.getId();
        if (modeSelector.getCheckedRadioButtonId() != id) {
            changingModeSelection = true;
            try {
                modeSelector.check(id);
            } finally {
                changingModeSelection = false;
            }
        }
    }

    private void applyOperatingMode(boolean testMode) {
        boolean previous = prefs.getBoolean("test_mode", true);
        prefs.edit().putBoolean("test_mode", testMode).apply();
        selectOperatingMode(testMode);
        if (service != null) service.setTestMode(testMode);
        else if (previous != testMode) appendLocalEvent(testMode ? "TEST_MODE_ON" : "LIVE_MODE_ON",
                testMode ? "TEST mode enabled · records marked TEST" : "LIVE mode selected · local records only; no ESP32 delivery", false);
        if (!testMode) resetSimulationState();
        updateModeDisplay();
    }

    private void updateModeDisplay() {
        if (prefs == null) return;
        boolean testMode = prefs.getBoolean("test_mode", true);
        boolean tracking = prefs.getBoolean("tracking_active", false);
        updateConnectionStrip();
        if (modeSelector != null) {
            modeSelector.setEnabled(!tracking);
            testModeOption.setEnabled(!tracking);
            liveModeOption.setEnabled(!tracking);
            testModeOption.setTextColor(testMode ? getColor(R.color.tw_on_amber) : MUTED);
            liveModeOption.setTextColor(!testMode ? getColor(R.color.tw_on_primary) : MUTED);
        }
        if (simulatorButton != null) simulatorButton.setVisibility(testMode ? View.VISIBLE : View.GONE);
        if (taggedValue != null) {
            taggedValue.setText(testMode ? "TEST" : "REAL");
            taggedValue.setTextColor(testMode ? AMBER : GREEN);
        }
    }

    private void addSimulationButton(LinearLayout parent, String title, Runnable action) {
        Button button = button(title, R.drawable.tw_button_ghost);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = params(-1, -2);
        lp.topMargin = dp(4);
        parent.addView(button, lp);
    }

    private void resetSimulationState() {
        if (simulationCountdown != null) simulationCountdown.cancel();
        if (simulationDialog != null && simulationDialog.isShowing()) simulationDialog.dismiss();
        simulationPromptPending = false;
        simulationState = new HikerSafetyState();
        simulationState.restoreFlags(false, false, false, false, true);
        simulationState.setListener(this::onSimulationSafetyEvent);
        if (safetyView != null) {
            safetyView.setVisibility(View.GONE);
        }
    }

    private void connectSimulatedLink() {
        if (!prefs.getBoolean("test_mode", true)) return;
        if (service == null || !service.isActive()) {
            Toast.makeText(this, "Start GPS tracking first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (fakeEsp32Transport == null) fakeEsp32Transport = new FakeEsp32Transport();
        service.installTransport(fakeEsp32Transport, PhoneLinkDesign.BLE);
    }

    private void disconnectSimulatedLink() {
        if (service != null && service.isTransportSimulated()) service.installTransport(null, PhoneLinkDesign.BLE);
    }

    private void sendSimulatedAck() {
        if (!prefs.getBoolean("test_mode", true)) return;
        FakeEsp32Transport fake = service == null ? null : service.getSimulatedTransport();
        if (fake == null) {
            Toast.makeText(this, "Connect the simulated link first", Toast.LENGTH_SHORT).show();
            return;
        }
        fake.fakeDownlink(new HikerDownlink(nodeField.getText().toString().trim(), null, null, HikerDownlink.Type.ACKNOWLEDGEMENT));
    }

    private void closeSimulatedReplySlot(boolean received) {
        if (!prefs.getBoolean("test_mode", true)) return;
        FakeEsp32Transport fake = service == null ? null : service.getSimulatedTransport();
        if (fake == null) {
            Toast.makeText(this, "Connect the simulated link first", Toast.LENGTH_SHORT).show();
            return;
        }
        fake.closeReplySlot(received);
    }

    private void simulateDownlink(HikerDownlink.Type type) {
        String id = nodeField == null ? "" : nodeField.getText().toString().trim();
        simulationState.onDownlink(new HikerDownlink(id, null, null, type));
    }

    private void simulateThreeMissedReplies() {
        simulationState.onReplySlotClosed(false);
        simulationState.onReplySlotClosed(false);
        simulationState.onReplySlotClosed(false);
    }

    private void onSimulationSafetyEvent(String type, String message, boolean urgent) {
        String visible = "SIMULATION · " + message;
        appendLocalEvent(type, visible, urgent, "SIMULATION");
        safetyView.setText(visible);
        safetyView.setVisibility(View.VISIBLE);
        safetyView.setTextColor(urgent ? ERROR : AMBER);
        if (urgent || "LOW_BATTERY".equals(type)) AlertFeedback.alert(this, type);
        if ("OFF_TRAIL".equals(type) || "STORM_TURN_BACK".equals(type) || "SOS_RECEIVED".equals(type)) {
            showSafetyDialog(visible, true);
        }
    }

    private void simulateInactivityPrompt() {
        if (simulationPromptPending || isFinishing()) return;
        simulationPromptPending = true;
        appendLocalEvent("INACTIVITY_PROMPT", "SIMULATION · ARE YOU OK? Response required within 15 seconds", true, "SIMULATION");
        AlertFeedback.alert(this, "INACTIVITY_PROMPT");
        simulationDialog = new AlertDialog.Builder(this)
                .setTitle("SIMULATION · ARE YOU OK?")
                .setMessage("This is a test prompt. Respond within 15 seconds.")
                .setCancelable(false)
                .setPositiveButton("I'm OK", null)
                .create();
        simulationDialog.setOnShowListener(dialog -> simulationDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            simulationPromptPending = false;
            if (simulationCountdown != null) simulationCountdown.cancel();
            appendLocalEvent("INACTIVITY_CONFIRMED", "SIMULATION · Hiker answered I'm OK", false, "SIMULATION");
            safetyView.setText("SIMULATION · Hiker checked in");
            safetyView.setVisibility(View.VISIBLE);
            simulationDialog.dismiss();
        }));
        simulationDialog.show();
        simulationCountdown = new CountDownTimer(15000L, 1000L) {
            @Override public void onTick(long remaining) {
                if (simulationDialog != null && simulationDialog.isShowing()) {
                    simulationDialog.setMessage("Test prompt · respond within " + ((remaining + 999L) / 1000L) + " seconds.");
                }
            }
            @Override public void onFinish() {
                if (!simulationPromptPending) return;
                simulationPromptPending = false;
                if (simulationDialog != null && simulationDialog.isShowing()) simulationDialog.dismiss();
                simulationState.requestEmergency("inactivity timeout");
            }
        }.start();
    }

    private void appendLocalEvent(String type, String message, boolean urgent) {
        appendLocalEvent(type, message, urgent, prefs.getBoolean("test_mode", true) ? "TEST" : "REAL");
    }

    private void appendLocalEvent(String type, String message, boolean urgent, String source) {
        try {
            JSONObject event = new JSONObject();
            event.put("record_type", "safety_event");
            String id = prefs.getString("node_id", "");
            event.put("node_id", id.isEmpty() ? JSONObject.NULL : id);
            event.put("captured_at_utc", Instant.now().toString());
            event.put("event_type", type);
            event.put("message", message);
            event.put("urgent", urgent);
            event.put("source", source);
            new LocalSampleStore(this).appendRecord(event);
        } catch (Exception ignored) { }
    }

    private void exportLog() {
        if (new LocalSampleStore(this).getRecordCount() == 0) {
            Toast.makeText(this, "No GPS or safety records to export yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-ndjson");
        intent.putExtra(Intent.EXTRA_TITLE, LocalSampleStore.FILE_NAME);
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
        try (FileInputStream in = new FileInputStream(new LocalSampleStore(this).getFile());
             OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (out == null) throw new IllegalStateException("Could not open export destination");
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            Toast.makeText(this, "GPS and safety log exported", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != LOCATION_PERMISSION_REQUEST) return;
        boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (granted && requestedStart) startTrackingService(prefs.getString("node_id", ""));
        else if (requestedStart) showLocationError("Location permission denied");
        requestedStart = false;
    }

    @Override protected void onStart() {
        super.onStart();
        if (prefs.getBoolean("tracking_active", false)) {
            String nodeId = prefs.getString("node_id", "");
            if (nodeId.matches("[A-Za-z0-9_-]{1,32}")) startTrackingService(nodeId);
            else prefs.edit().putBoolean("tracking_active", false).apply();
        }
    }

    @Override protected void onStop() {
        if (inactivityCountdown != null) inactivityCountdown.cancel();
        if (inactivityDialog != null && inactivityDialog.isShowing()) inactivityDialog.dismiss();
        if (simulationCountdown != null) simulationCountdown.cancel();
        if (simulationDialog != null && simulationDialog.isShowing()) simulationDialog.dismiss();
        if (sosProgressTimer != null) sosProgressTimer.cancel();
        mainHandler.removeCallbacks(sosHoldAction);
        simulationPromptPending = false;
        if (bound) {
            if (service != null) service.setListener(null);
            unbindService(connection);
            bound = false;
        }
        super.onStop();
    }

    private LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int value) {
        int resource = 0;
        switch (value) {
            case 4: resource = R.dimen.tw_space_1; break;
            case 8: resource = R.dimen.tw_space_2; break;
            case 12: resource = R.dimen.tw_space_3; break;
            case 16: resource = R.dimen.tw_space_4; break;
            case 20: resource = R.dimen.tw_space_5; break;
            case 24: resource = R.dimen.tw_space_6; break;
            default: break;
        }
        return resource == 0 ? Math.round(value * getResources().getDisplayMetrics().density)
                : getResources().getDimensionPixelSize(resource);
    }
}
