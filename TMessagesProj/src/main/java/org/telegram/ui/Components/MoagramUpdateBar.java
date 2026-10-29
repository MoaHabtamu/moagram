package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MoagramUpdateController;
import org.telegram.messenger.MoagramUpdatePolicy;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** The same download/installation state on the main tabs and the update page. */
public class MoagramUpdateBar extends FrameLayout {
    private final MoagramUpdateController updates = MoagramUpdateController.getInstance();
    private final TextView title;
    private final ImageView cancel;
    private final RadialProgress2 icon;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable listener = this::refresh;

    public MoagramUpdateBar(Context context, Activity activity, Runnable showDetails) {
        super(context);
        setWillNotDraw(false);
        setBackground(Theme.getSelectorDrawable(0x30ffffff, false));
        title = new TextView(context);
        title.setTextSize(14);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(0xffffffff);
        title.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(title, LayoutHelper.createFrame(-1, 44, Gravity.TOP, 48, 0, 48, 0));
        icon = new RadialProgress2(this);
        icon.setColors(0xffffffff, 0xffffffff, Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButton));
        icon.setCircleRadius(dp(11));
        icon.setAsMini();
        cancel = new ImageView(context);
        cancel.setImageResource(R.drawable.ic_close_white);
        cancel.setScaleType(ImageView.ScaleType.CENTER);
        cancel.setBackground(Theme.getSelectorDrawable(0x30ffffff, false));
        cancel.setContentDescription(LocaleController.getString(R.string.MoagramCancelDownload));
        addView(cancel, LayoutHelper.createFrame(44, 44, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP));
        cancel.setOnClickListener(v -> updates.cancelDownload());
        setOnClickListener(v -> {
            if (updates.busy()) {
                if (showDetails != null) showDetails.run();
            } else if (updates.ready()) {
                updates.install(activity);
            } else if (updates.error() != null && showDetails != null) {
                showDetails.run();
            } else {
                updates.download();
            }
        });
        refresh();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updates.addListener(listener);
        refresh();
    }

    @Override protected void onDetachedFromWindow() {
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
        cancel.setVisibility(updates.downloading() ? VISIBLE : GONE);
        int actionIcon = MediaActionDrawable.ICON_DOWNLOAD;
        if (updates.installing()) {
            title.setText(LocaleController.getString(R.string.MoagramPreparingInstall));
            actionIcon = MediaActionDrawable.ICON_UPDATE;
        } else if (updates.downloading()) {
            int percent = (int) Math.min(100, updates.downloadedBytes() * 100 / policy.size);
            title.setText(LocaleController.formatString(R.string.MoagramDownloadingUpdate, percent));
            actionIcon = MediaActionDrawable.ICON_CANCEL;
        } else if (updates.error() != null) {
            title.setText(LocaleController.getString(R.string.MoagramUpdateAttention));
        } else if (updates.ready()) {
            title.setText(LocaleController.getString(updates.needsInstallPermission()
                    ? R.string.MoagramAllowInstall : R.string.MoagramInstallUpdate));
            actionIcon = MediaActionDrawable.ICON_UPDATE;
        } else {
            title.setText(LocaleController.getString(R.string.MoagramDownloadUpdate) + " · " + AndroidUtilities.formatFileSize(policy.size));
        }
        icon.setIcon(actionIcon, true, false);
        icon.setProgress(updates.downloading() ? (float) updates.downloadedBytes() / policy.size : 0, true);
        setContentDescription(title.getText());
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = LocaleController.isRTL ? getWidth() - dp(35) : dp(13);
        icon.setProgressRect(left, dp(11), left + dp(22), dp(33));
        icon.draw(canvas);
        if (updates.downloading() && updates.policy() != null) {
            float progress = Math.min(1f, (float) updates.downloadedBytes() / updates.policy().size);
            track.setColor(0x40ffffff);
            canvas.drawRect(0, 0, getWidth(), dp(2), track);
            track.setColor(0xffffffff);
            if (LocaleController.isRTL) canvas.drawRect(getWidth() * (1 - progress), 0, getWidth(), dp(2), track);
            else canvas.drawRect(0, 0, getWidth() * progress, dp(2), track);
        }
    }
}
