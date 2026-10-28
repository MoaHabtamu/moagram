package org.telegram.ui;

import android.content.Context;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MoagramUpdateController;
import org.telegram.messenger.MoagramUpdatePolicy;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** Opening this page does not trigger a network check or a download. */
public class MoagramUpdatesActivity extends BaseFragment {
    private final MoagramUpdateController updates = MoagramUpdateController.getInstance();
    private final MoagramUpdatePolicy initial;
    private TextView status, notes;
    private Button check, action, cancel;
    private ProgressBar progress;
    private final Runnable listener = this::refresh;
    public MoagramUpdatesActivity() { this(null); }
    public MoagramUpdatesActivity(MoagramUpdatePolicy update) { initial = update; }
    private String text(int id) { return LocaleController.getString(id); }
    @Override public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setTitle(text(R.string.MoagramUpdates));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = frame;
        ScrollView scroll = new ScrollView(context);
        frame.addView(scroll, LayoutHelper.createFrame(-1, -1));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        TextView installed = label(context, 17, Theme.key_windowBackgroundWhiteBlackText);
        installed.setText(LocaleController.formatString(R.string.MoagramInstalledVersion, updates.installedVersion()));
        content.addView(installed, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 18));
        status = label(context, 17, Theme.key_windowBackgroundWhiteBlackText);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        notes = label(context, 15, Theme.key_windowBackgroundWhiteGrayText);
        content.addView(notes, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 16));
        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        content.addView(progress, LayoutHelper.createLinear(-1, 6, 0, 0, 0, 14));
        check = button(context, text(R.string.MoagramCheckUpdates));
        check.setOnClickListener(v -> updates.check());
        content.addView(check, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        action = button(context, "");
        action.setOnClickListener(v -> { if (updates.ready()) updates.install(getParentActivity()); else updates.download(); });
        content.addView(action, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        cancel = button(context, text(R.string.MoagramCancelDownload));
        cancel.setOnClickListener(v -> updates.cancelDownload());
        content.addView(cancel, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 16));
        TextView info = label(context, 14, Theme.key_windowBackgroundWhiteGrayText);
        info.setText(text(R.string.MoagramUpdateConsentInfo));
        content.addView(info, LayoutHelper.createLinear(-1, -2));
        updates.addListener(listener);
        if (initial != null) updates.offer(initial);
        refresh();
        return fragmentView;
    }
    private TextView label(Context context, int size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(getThemedColor(color));
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        return view;
    }
    private Button button(Context context, String label) {
        Button view = new Button(context);
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(16);
        view.setMinHeight(AndroidUtilities.dp(48));
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        view.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
        return view;
    }
    private void refresh() {
        if (status == null) return;
        MoagramUpdatePolicy policy = updates.policy();
        boolean newer = policy != null && policy.newerThan(updates.installedCode());
        boolean compatible = newer && policy.compatible(Build.VERSION.SDK_INT);
        check.setEnabled(!updates.busy() && MoagramUpdateController.supported());
        action.setVisibility(compatible && !updates.checking() && !updates.downloading() ? View.VISIBLE : View.GONE);
        action.setEnabled(!updates.busy());
        action.setText(text(updates.ready() ? R.string.MoagramInstallUpdate : R.string.MoagramDownloadUpdate));
        cancel.setVisibility(updates.downloading() ? View.VISIBLE : View.GONE);
        progress.setVisibility(updates.downloading() ? View.VISIBLE : View.GONE);
        if (updates.downloading() && policy != null) progress.setProgress((int) Math.min(1000, updates.downloadedBytes() * 1000 / policy.size));
        notes.setVisibility(newer && !policy.notes.isEmpty() ? View.VISIBLE : View.GONE);
        if (newer) notes.setText(policy.notes);
        if (updates.error() != null) status.setText(updates.error());
        else if (!MoagramUpdateController.supported()) status.setText(text(R.string.MoagramUpdateUnsupported));
        else if (updates.checking()) status.setText(text(R.string.MoagramCheckingUpdates));
        else if (updates.downloading()) status.setText(LocaleController.formatString(R.string.MoagramDownloadingUpdate, (int) Math.min(100, updates.downloadedBytes() * 100 / policy.size)));
        else if (updates.ready()) status.setText(text(R.string.MoagramUpdateReady));
        else if (newer && !compatible) status.setText(text(R.string.MoagramUpdateAndroidRequired));
        else if (newer) status.setText(LocaleController.formatString(R.string.MoagramUpdateAvailable, policy.versionName));
        else if (policy != null) status.setText(text(R.string.MoagramUpdateCurrent));
        else status.setText(text(R.string.MoagramUpdateInitial));
    }
    @Override public void onResume() { super.onResume(); refresh(); }
    @Override public void onFragmentDestroy() { updates.removeListener(listener); super.onFragmentDestroy(); }
}
