package org.telegram.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MoagramUpdateController;
import org.telegram.messenger.MoagramUpdatePolicy;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

/** Opening this page does not trigger a network check or a download. */
public class MoagramUpdatesActivity extends BaseFragment {
    private final MoagramUpdateController updates = MoagramUpdateController.getInstance();
    private final MoagramUpdatePolicy initial;
    private TextDetailSettingsCell release;
    private TextView notes;
    private LinearLayout notesSection;
    private TextSettingsCell check, action, cancel;
    private ProgressBar progress;
    private final Runnable listener = this::refresh;

    public MoagramUpdatesActivity() { this(null); }
    public MoagramUpdatesActivity(MoagramUpdatePolicy update) { initial = update; }
    private String text(int id) { return LocaleController.getString(id); }

    @Override public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(text(R.string.MoagramUpdates));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = root;
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets system = AndroidUtilities.getDefaultWindowInsets(insets, false);
            view.setPadding(system.left, 0, system.right, system.bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        // The float selects weight rather than the int-gravity overload.
        root.addView(scroll, LayoutHelper.createLinear(-1, 0, 1f));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout updateSection = new LinearLayout(context);
        updateSection.setOrientation(LinearLayout.VERTICAL);
        updateSection.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content.addView(updateSection, LayoutHelper.createLinear(-1, -2));
        TextDetailSettingsCell installed = new TextDetailSettingsCell(context);
        installed.setMultilineDetail(true);
        installed.setTextAndValue(text(R.string.MoagramCurrentVersion), updates.installedVersion(), true);
        updateSection.addView(installed, LayoutHelper.createLinear(-1, -2));
        release = new TextDetailSettingsCell(context);
        release.setMultilineDetail(true);
        release.setMinimumHeight(AndroidUtilities.dp(50));
        release.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        updateSection.addView(release, LayoutHelper.createLinear(-1, -2));

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(getThemedColor(Theme.key_windowBackgroundWhiteBlueText4)));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(getThemedColor(Theme.key_windowBackgroundGray)));
        progress.setMax(1000);
        updateSection.addView(progress, LayoutHelper.createLinear(-1, 3));
        action = actionRow(context, "", false);
        action.setOnClickListener(v -> { if (updates.ready()) updates.install(getParentActivity()); else updates.download(); });
        updateSection.addView(action, LayoutHelper.createLinear(-1, -2));
        cancel = actionRow(context, text(R.string.MoagramCancelDownload), true);
        cancel.setOnClickListener(v -> updates.cancelDownload());
        updateSection.addView(cancel, LayoutHelper.createLinear(-1, -2));
        check = actionRow(context, text(R.string.MoagramCheckUpdates), false);
        check.setOnClickListener(v -> updates.check());
        updateSection.addView(check, LayoutHelper.createLinear(-1, -2));
        content.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(-1, -2));

        notesSection = new LinearLayout(context);
        notesSection.setOrientation(LinearLayout.VERTICAL);
        HeaderCell notesTitle = new HeaderCell(context);
        notesTitle.setText(text(R.string.MoagramUpdateWhatsNew));
        notesTitle.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        notesSection.addView(notesTitle, LayoutHelper.createLinear(-1, -2));
        notes = new TextView(context);
        notes.setTextSize(16);
        notes.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        notes.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        notes.setLineSpacing(AndroidUtilities.dp(2), 1);
        notes.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(16));
        notes.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        notes.setTextIsSelectable(true);
        notesSection.addView(notes, LayoutHelper.createLinear(-1, -2));
        notesSection.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(-1, -2));
        content.addView(notesSection, LayoutHelper.createLinear(-1, -2));

        updates.removeListener(listener);
        updates.addListener(listener);
        if (initial != null) updates.offer(initial);
        refresh();
        return fragmentView;
    }

    private TextSettingsCell actionRow(Context context, String label, boolean destructive) {
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setText(label, false);
        cell.setTextColor(getThemedColor(destructive ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlueText4));
        cell.setBackground(Theme.getSelectorDrawable(true));
        return cell;
    }

    private void refresh() {
        if (release == null) return;
        MoagramUpdatePolicy policy = updates.policy();
        boolean newer = policy != null && policy.newerThan(updates.installedCode());
        boolean compatible = newer && policy.compatible(Build.VERSION.SDK_INT);
        boolean showCheck = !updates.downloading() && !updates.installing();
        check.setEnabled(!updates.busy() && MoagramUpdateController.supported());
        check.setAlpha(check.isEnabled() ? 1f : .45f);
        check.setVisibility(showCheck ? View.VISIBLE : View.GONE);
        action.setVisibility(compatible && !updates.checking() && !updates.downloading() ? View.VISIBLE : View.GONE);
        action.setEnabled(!updates.busy());
        action.setAlpha(action.isEnabled() ? 1f : .45f);
        action.setText(text(updates.ready() ? (updates.needsInstallPermission()
                ? R.string.MoagramAllowInstall : R.string.MoagramInstallUpdate) : R.string.MoagramDownloadUpdate), showCheck);
        cancel.setVisibility(updates.downloading() ? View.VISIBLE : View.GONE);
        progress.setVisibility(updates.downloading() || updates.installing() || updates.checking() ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(updates.checking() || updates.installing());
        if (updates.downloading() && policy != null) progress.setProgress((int) Math.min(1000, updates.downloadedBytes() * 1000 / policy.size));
        notesSection.setVisibility(newer && !policy.notes.isEmpty() ? View.VISIBLE : View.GONE);
        if (newer) notes.setText(policy.notes.trim());

        String status = null;
        String detail = "";
        if (updates.error() != null) {
            status = text(R.string.MoagramUpdateNeedsAttention);
            detail = updates.error();
        } else if (!MoagramUpdateController.supported()) {
            status = text(R.string.MoagramUpdates);
            detail = text(R.string.MoagramUpdateUnsupported);
        } else if (updates.checking()) {
            status = text(R.string.MoagramCheckingUpdates);
        } else if (updates.installing()) {
            status = text(R.string.MoagramPreparingInstall);
        } else if (updates.downloading() && policy != null) {
            status = LocaleController.formatString(R.string.MoagramDownloadingUpdate, (int) Math.min(100, updates.downloadedBytes() * 100 / policy.size));
            detail = AndroidUtilities.formatFileSize(updates.downloadedBytes()) + " / " + AndroidUtilities.formatFileSize(policy.size);
        } else if (updates.ready()) {
            status = text(R.string.MoagramUpdateReadyTitle);
            detail = updates.needsInstallPermission() ? text(R.string.MoagramUpdatePermissionInfo) : "";
        } else if (newer) {
            status = LocaleController.formatString(R.string.MoagramUpdateAvailable, policy.versionName);
            detail = compatible ? AndroidUtilities.formatFileSize(policy.size) : text(R.string.MoagramUpdateAndroidRequired);
        } else if (policy != null) {
            status = text(R.string.MoagramUpdateCurrent);
        }
        release.setVisibility(status != null ? View.VISIBLE : View.GONE);
        release.setTextAndValue(status == null ? "" : status, detail, true);
        release.getValueTextView().setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override public void onResume() { super.onResume(); refresh(); }
    @Override public void onFragmentDestroy() { updates.removeListener(listener); super.onFragmentDestroy(); }
}
