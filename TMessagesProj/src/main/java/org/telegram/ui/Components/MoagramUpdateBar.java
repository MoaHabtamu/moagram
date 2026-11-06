package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MoagramUpdateController;
import org.telegram.messenger.MoagramUpdatePolicy;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** The same download and install state on the main tabs and the update page. */
public class MoagramUpdateBar extends android.widget.FrameLayout {
    private final MoagramUpdateController updates = MoagramUpdateController.getInstance();
    private final AnimatedTextView label;
    private final AnimatedTextView.AnimatedTextDrawable sizeText;
    private final RadialProgress2 icon;
    private final Runnable listener = this::refresh;
    private final Activity activity;
    private final Runnable showDetails;
    private boolean animate;

    public MoagramUpdateBar(Context context, Activity activity, Runnable showDetails) {
        super(context);
        this.activity = activity;
        this.showDetails = showDetails;
        setBackground(Theme.getSelectorDrawable(0x40ffffff, false));
        label = new AnimatedTextView(context, true, true, true) {
            @Override
            protected void onDraw(Canvas canvas) {
                sizeText.setBounds(0, 0, getMeasuredWidth() - dp(20), getMeasuredHeight());
                sizeText.draw(canvas);
                canvas.save();
                canvas.translate(dp(15), 0);
                super.onDraw(canvas);
                canvas.translate((getMeasuredWidth() - width()) / 2f - dp(30), dp(11));
                icon.draw(canvas);
                canvas.restore();
            }

            @Override
            protected boolean verifyDrawable(@NonNull Drawable who) {
                return super.verifyDrawable(who) || who == sizeText;
            }
        };
        label.setTextSize(dp(15));
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(0xffffffff);
        label.setGravity(Gravity.CENTER);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        icon = new RadialProgress2(label);
        icon.setColors(0xffffffff, 0xffffffff, Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButton));
        icon.setProgressRect(0, 0, dp(22), dp(22));
        icon.setCircleRadius(dp(11));
        icon.setAsMini();

        sizeText = new AnimatedTextView.AnimatedTextDrawable(true, true, true);
        sizeText.setCallback(label);
        sizeText.setTextSize(dp(14));
        sizeText.setTypeface(AndroidUtilities.bold());
        sizeText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        sizeText.setTextColor(0xccffffff);

        setOnClickListener(v -> {
            if (updates.downloading()) {
                updates.cancelDownload();
            } else if (updates.ready()) {
                updates.install(activity);
            } else if (updates.installing()) {
                if (showDetails != null) showDetails.run();
            } else if (updates.error() != null) {
                if (showDetails != null) showDetails.run();
            } else {
                updates.download();
            }
        });
        refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updates.addListener(listener);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        updates.removeListener(listener);
        super.onDetachedFromWindow();
    }

    public void refresh() {
        MoagramUpdatePolicy policy = updates.policy();
        boolean visible = policy != null && policy.newerThan(updates.installedCode())
                && policy.compatible(android.os.Build.VERSION.SDK_INT) && !updates.checking();
        int visibility = visible ? VISIBLE : GONE;
        if (getVisibility() != visibility) {
            setVisibility(visibility);
            if (getParent() instanceof View) ((View) getParent()).requestLayout();
        }
        if (!visible) return;

        int actionIcon = MediaActionDrawable.ICON_DOWNLOAD;
        CharSequence text;
        boolean showSize = false;
        if (updates.installing()) {
            text = LocaleController.getString(R.string.MoagramPreparingInstall);
            actionIcon = MediaActionDrawable.ICON_UPDATE;
        } else if (updates.downloading()) {
            int percent = (int) Math.min(100, updates.downloadedBytes() * 100 / policy.size);
            text = LocaleController.formatString(R.string.MoagramDownloadingUpdate, percent);
            actionIcon = MediaActionDrawable.ICON_CANCEL;
        } else if (updates.error() != null) {
            text = LocaleController.getString(R.string.MoagramUpdateAttention);
        } else if (updates.ready()) {
            text = LocaleController.getString(updates.needsInstallPermission()
                    ? R.string.MoagramAllowInstall : R.string.MoagramInstallUpdate);
            actionIcon = MediaActionDrawable.ICON_UPDATE;
        } else {
            text = LocaleController.getString(R.string.MoagramDownloadUpdate);
            showSize = true;
        }
        label.setText(text, animate);
        sizeText.setText(showSize ? AndroidUtilities.formatFileSize(policy.size) : null, animate);
        icon.setIcon(actionIcon, true, animate);
        icon.setProgress(updates.downloading() ? (float) updates.downloadedBytes() / policy.size : 0, animate);
        setContentDescription(updates.downloading()
                ? LocaleController.getString(R.string.MoagramCancelDownload)
                : text);
        animate = true;
    }
}
