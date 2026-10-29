package org.telegram.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
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
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MoagramUpdateBar;

/** Opening this page does not trigger a network check or a download. */
public class MoagramUpdatesActivity extends BaseFragment {
    private final MoagramUpdateController updates = MoagramUpdateController.getInstance();
    private final MoagramUpdatePolicy initial;
    private TextView status, details, notes;
    private LinearLayout notesCard;
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
        root.addView(scroll, LayoutHelper.createLinear(-1, 0, 1));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        ImageView emblem = new ImageView(context);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_actionBarDefault)});
        gradient.setCornerRadius(AndroidUtilities.dp(26));
        emblem.setBackground(gradient);
        emblem.setImageResource(R.drawable.menu_download_round);
        emblem.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        emblem.setScaleType(ImageView.ScaleType.CENTER);
        emblem.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(emblem, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 18));
        TextView heading = label(context, 24, Theme.key_windowBackgroundWhiteBlackText);
        heading.setTypeface(AndroidUtilities.bold());
        heading.setGravity(Gravity.CENTER);
        heading.setText(text(R.string.MoagramUpdateHeading));
        content.addView(heading, LayoutHelper.createLinear(-1, -2, 8, 0, 8, 8));
        TextView installed = label(context, 14, Theme.key_windowBackgroundWhiteGrayText);
        installed.setGravity(Gravity.CENTER);
        installed.setText(LocaleController.formatString(R.string.MoagramInstalledVersion, updates.installedVersion()));
        content.addView(installed, LayoutHelper.createLinear(-1, -2, 8, 0, 8, 24));

        LinearLayout release = card(context);
        content.addView(release, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        status = label(context, 19, Theme.key_windowBackgroundWhiteBlackText);
        status.setTypeface(AndroidUtilities.bold());
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        release.addView(status, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 8));
        details = label(context, 14, Theme.key_windowBackgroundWhiteGrayText);
        release.addView(details, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 16));
        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(getThemedColor(Theme.key_featuredStickers_addButton)));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(getThemedColor(Theme.key_windowBackgroundGray)));
        progress.setMax(1000);
        release.addView(progress, LayoutHelper.createLinear(-1, 6, 0, 0, 0, 18));
        action = button(context, "", true);
        action.setOnClickListener(v -> { if (updates.ready()) updates.install(getParentActivity()); else updates.download(); });
        release.addView(action, LayoutHelper.createLinear(-1, -2));
        cancel = button(context, text(R.string.MoagramCancelDownload), false);
        cancel.setOnClickListener(v -> updates.cancelDownload());
        release.addView(cancel, LayoutHelper.createLinear(-1, -2));
        check = button(context, text(R.string.MoagramCheckUpdates), false);
        check.setOnClickListener(v -> updates.check());
        release.addView(check, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));

        notesCard = card(context);
        TextView notesTitle = label(context, 16, Theme.key_windowBackgroundWhiteBlackText);
        notesTitle.setTypeface(AndroidUtilities.bold());
        notesTitle.setText(text(R.string.MoagramUpdateWhatsNew));
        notesCard.addView(notesTitle, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 10));
        notes = label(context, 15, Theme.key_windowBackgroundWhiteGrayText);
        notes.setTextIsSelectable(true);
        notesCard.addView(notes, LayoutHelper.createLinear(-1, -2));
        content.addView(notesCard, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 16));
        TextView info = label(context, 13, Theme.key_windowBackgroundWhiteGrayText);
        info.setText(text(R.string.MoagramUpdateConsentInfo));
        content.addView(info, LayoutHelper.createLinear(-1, -2, 8, 4, 8, 0));

        UpdateLayoutWrapper bottom = new UpdateLayoutWrapper(context);
        bottom.addView(new MoagramUpdateBar(context, getParentActivity(), null));
        root.addView(bottom, LayoutHelper.createLinear(-1, -2));
        updates.removeListener(listener);
        updates.addListener(listener);
        if (initial != null) updates.offer(initial);
        refresh();
        return fragmentView;
    }

    private LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(16));
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(18), getThemedColor(Theme.key_windowBackgroundWhite)));
        return card;
    }
    private TextView label(Context context, int size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(getThemedColor(color));
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        view.setLineSpacing(AndroidUtilities.dp(3), 1);
        return view;
    }
    private Button button(Context context, String label, boolean primary) {
        Button view = new Button(context);
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(15);
        view.setTypeface(AndroidUtilities.bold());
        view.setMinHeight(AndroidUtilities.dp(50));
        view.setMinimumHeight(AndroidUtilities.dp(50));
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        view.setTextColor(getThemedColor(primary ? Theme.key_featuredStickers_buttonText : Theme.key_featuredStickers_addButton));
        view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12),
                primary ? getThemedColor(Theme.key_featuredStickers_addButton) : Color.TRANSPARENT,
                primary ? getThemedColor(Theme.key_featuredStickers_addButtonPressed) : getThemedColor(Theme.key_listSelector)));
        return view;
    }
    private void refresh() {
        if (status == null) return;
        MoagramUpdatePolicy policy = updates.policy();
        boolean newer = policy != null && policy.newerThan(updates.installedCode());
        boolean compatible = newer && policy.compatible(Build.VERSION.SDK_INT);
        check.setEnabled(!updates.busy() && MoagramUpdateController.supported());
        check.setAlpha(check.isEnabled() ? 1f : .45f);
        check.setVisibility(updates.downloading() || updates.installing() ? View.GONE : View.VISIBLE);
        action.setVisibility(compatible && !updates.checking() && !updates.downloading() ? View.VISIBLE : View.GONE);
        action.setEnabled(!updates.busy());
        action.setAlpha(action.isEnabled() ? 1f : .45f);
        action.setText(text(updates.ready() ? (updates.needsInstallPermission()
                ? R.string.MoagramAllowInstall : R.string.MoagramInstallUpdate) : R.string.MoagramDownloadUpdate));
        cancel.setVisibility(updates.downloading() ? View.VISIBLE : View.GONE);
        progress.setVisibility(updates.downloading() || updates.installing() || updates.checking() ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(updates.checking() || updates.installing());
        if (updates.downloading() && policy != null) progress.setProgress((int) Math.min(1000, updates.downloadedBytes() * 1000 / policy.size));
        notesCard.setVisibility(newer && !policy.notes.isEmpty() ? View.VISIBLE : View.GONE);
        if (newer) notes.setText(policy.notes);

        details.setVisibility(View.VISIBLE);
        if (updates.error() != null) {
            status.setText(text(R.string.MoagramUpdateNeedsAttention));
            details.setText(updates.error());
        } else if (!MoagramUpdateController.supported()) {
            status.setText(text(R.string.MoagramUpdates));
            details.setText(text(R.string.MoagramUpdateUnsupported));
        } else if (updates.checking()) {
            status.setText(text(R.string.MoagramCheckingUpdates));
            details.setText(text(R.string.MoagramUpdateInitial));
        } else if (updates.installing()) {
            status.setText(text(R.string.MoagramPreparingInstall));
            details.setText(text(R.string.MoagramUpdateReady));
        } else if (updates.downloading()) {
            status.setText(LocaleController.formatString(R.string.MoagramDownloadingUpdate, (int) Math.min(100, updates.downloadedBytes() * 100 / policy.size)));
            details.setText(AndroidUtilities.formatFileSize(updates.downloadedBytes()) + " / " + AndroidUtilities.formatFileSize(policy.size)
                    + "\n" + text(R.string.MoagramDownloadContinue));
        } else if (updates.ready()) {
            status.setText(text(R.string.MoagramUpdateReadyTitle));
            details.setText(text(updates.needsInstallPermission() ? R.string.MoagramUpdatePermissionInfo : R.string.MoagramUpdateReady));
        } else if (newer && !compatible) {
            status.setText(LocaleController.formatString(R.string.MoagramUpdateAvailable, policy.versionName));
            details.setText(text(R.string.MoagramUpdateAndroidRequired));
        } else if (newer) {
            status.setText(LocaleController.formatString(R.string.MoagramUpdateAvailable, policy.versionName));
            details.setText(AndroidUtilities.formatFileSize(policy.size));
        } else if (policy != null) {
            status.setText(text(R.string.MoagramUpdateCurrent));
            details.setVisibility(View.GONE);
        } else {
            status.setText(text(R.string.MoagramUpdateHeading));
            details.setText(text(R.string.MoagramUpdateInitial));
        }
    }
    @Override public void onResume() { super.onResume(); refresh(); }
    @Override public void onFragmentDestroy() { updates.removeListener(listener); super.onFragmentDestroy(); }
}
