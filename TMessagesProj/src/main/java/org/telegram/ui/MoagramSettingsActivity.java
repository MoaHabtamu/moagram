package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.GhostModeController;
import org.telegram.messenger.HiddenChatsController;
import org.telegram.messenger.LocalRepeatController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MoagramConfig;
import org.telegram.messenger.MoagramPush;
import org.telegram.messenger.MoagramUnifiedPush;
import org.telegram.messenger.MoagramUpdateController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/** Unified hub for Moagram features, UX mods, background health, and updates. */
public class MoagramSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private int rowCount;
    private int featuresHeaderRow;
    private int ghostModeRow;
    private int hiddenChatsRow;
    private int localHistoryRow;
    private int repeatsRow;
    private int uxRow;
    private int featuresShadowRow;
    private int healthHeaderRow;
    private int batteryRow;
    private int notificationsRow;
    private int pushRow;
    private int keepAliveRow;
    private int healthInfoRow;
    private int updatesRow;
    private int endShadowRow;

    private static final int TYPE_TEXT = 0;
    private static final int TYPE_SETTINGS = 1;
    private static final int TYPE_HEADER = 2;
    private static final int TYPE_INFO = 3;
    private static final int TYPE_SHADOW = 4;

    public MoagramSettingsActivity() {
        this(UserConfig.selectedAccount);
    }

    public MoagramSettingsActivity(int account) {
        setCurrentAccount(account);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        MoagramConfig.ensureLoaded();
        getNotificationCenter().addObserver(this, NotificationCenter.ghostModeChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.moagramConfigChanged);
        updateRows();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.ghostModeChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.moagramConfigChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if ((id == NotificationCenter.ghostModeChanged && account == currentAccount)
                || id == NotificationCenter.moagramConfigChanged) {
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        }
    }

    private void updateRows() {
        rowCount = 0;
        featuresHeaderRow = rowCount++;
        ghostModeRow = rowCount++;
        hiddenChatsRow = rowCount++;
        localHistoryRow = rowCount++;
        repeatsRow = rowCount++;
        uxRow = rowCount++;
        featuresShadowRow = rowCount++;
        healthHeaderRow = rowCount++;
        batteryRow = rowCount++;
        notificationsRow = rowCount++;
        pushRow = rowCount++;
        keepAliveRow = rowCount++;
        healthInfoRow = rowCount++;
        updatesRow = rowCount++;
        endShadowRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MoagramSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == ghostModeRow) {
                presentFragment(new GhostModeActivity(currentAccount));
            } else if (position == hiddenChatsRow) {
                presentFragment(new HiddenChatsSettingsActivity(currentAccount));
            } else if (position == localHistoryRow) {
                presentFragment(new LocalHistoryActivity(currentAccount));
            } else if (position == repeatsRow) {
                presentFragment(new GlobalRepeatsActivity(currentAccount));
            } else if (position == uxRow) {
                presentFragment(new MoagramUxActivity());
            } else if (position == batteryRow) {
                MoagramConfig.openBatteryOptimizationSettings(getParentActivity());
            } else if (position == notificationsRow) {
                MoagramConfig.openNotificationSettings(getParentActivity());
            } else if (position == pushRow) {
                showPushDialog();
            } else if (position == keepAliveRow) {
                presentFragment(new NotificationsSettingsActivity());
            } else if (position == updatesRow) {
                presentFragment(new MoagramUpdatesActivity());
            }
        });

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void showPushDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.MoagramPushTitle));
        if (!MoagramPush.isConfigured()) {
            builder.setMessage(LocaleController.getString(R.string.MoagramPushNoRelayInfo));
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        } else if (MoagramPush.isUserEnabled() && !MoagramPush.isAvailable()) {
            builder.setMessage(LocaleController.getString(R.string.MoagramPushNoDistributorInfo));
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        } else if (MoagramPush.isUserEnabled()) {
            builder.setMessage(LocaleController.getString(R.string.MoagramPushOnInfo));
            builder.setPositiveButton(LocaleController.getString(R.string.MoagramPushTurnOff), (dialog, which) -> {
                MoagramPush.setUserEnabled(false);
                listAdapter.notifyDataSetChanged();
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        } else {
            builder.setMessage(LocaleController.getString(R.string.MoagramPushOffInfo));
            builder.setPositiveButton(LocaleController.getString(R.string.MoagramPushTurnOn), (dialog, which) -> {
                MoagramPush.setUserEnabled(true);
                listAdapter.notifyDataSetChanged();
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        }
        showDialog(builder.create());
    }

    private String pushStatusValue() {
        if (!MoagramPush.isConfigured()) {
            return LocaleController.getString(R.string.MoagramPushNoRelay);
        }
        if (!MoagramPush.isUserEnabled()) {
            return LocaleController.getString(R.string.BackgroundHealthDisabled);
        }
        if (!MoagramPush.isAvailable()) {
            return LocaleController.getString(R.string.MoagramPushNoDistributor);
        }
        if (MoagramPush.isActive()) {
            String name = MoagramPush.TRANSPORT_FCM.equals(MoagramPush.activeTransport())
                    ? LocaleController.getString(R.string.MoagramPushGoogle)
                    : MoagramUnifiedPush.distributorName();
            return name == null ? LocaleController.getString(R.string.BackgroundHealthActive)
                    : LocaleController.formatString(R.string.MoagramPushActiveVia, name);
        }
        return LocaleController.getString(R.string.MoagramPushConnecting);
    }

    private String formatRepeatsValue() {
        ArrayList<LocalRepeatController.RepeatInfo> all = LocalRepeatController.getInstance(currentAccount).getAllRepeats();
        if (all.isEmpty()) {
            return LocaleController.getString(R.string.MoagramNone);
        }
        int paused = 0;
        for (LocalRepeatController.RepeatInfo info : all) {
            if (info.paused) paused++;
        }
        if (paused > 0) {
            return LocaleController.formatString(R.string.MoagramPausedCount, paused);
        }
        return LocaleController.formatString(R.string.MoagramActiveCount, all.size());
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_TEXT || type == TYPE_SETTINGS;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_TEXT:
                    view = new TextCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_SETTINGS:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case TYPE_SHADOW:
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == ghostModeRow) {
                        boolean enabled = GhostModeController.getInstance(currentAccount).enabled;
                        String state = LocaleController.getString(enabled ? R.string.NotificationsOn : R.string.NotificationsOff);
                        cell.setTextAndValueAndIcon(
                                LocaleController.getString(R.string.GhostMode),
                                state,
                                R.drawable.msg_stories_stealth,
                                true
                        );
                    } else if (position == hiddenChatsRow) {
                        int count = HiddenChatsController.getInstance(currentAccount).getHiddenChatsCount();
                        String value = count > 0
                                ? LocaleController.formatString(R.string.HiddenChatsCount, count)
                                : LocaleController.getString(R.string.MoagramNone);
                        cell.setTextAndValueAndIcon(
                                LocaleController.getString(R.string.HiddenChatsTitle),
                                value,
                                R.drawable.msg_secret,
                                true
                        );
                    } else if (position == localHistoryRow) {
                        int count = MoagramConfig.getEnabledLocalHistoryCount();
                        String value = count > 0
                                ? LocaleController.formatString(R.string.MoagramEnabledCount, count)
                                : LocaleController.getString(R.string.NotificationsOff);
                        cell.setTextAndValueAndIcon(
                                LocaleController.getString(R.string.LocalHistoryTitle),
                                value,
                                R.drawable.msg_archive,
                                true
                        );
                    } else if (position == repeatsRow) {
                        cell.setTextAndValueAndIcon(
                                LocaleController.getString(R.string.GlobalRepeatsTitle),
                                formatRepeatsValue(),
                                R.drawable.msg_retry,
                                true
                        );
                    } else if (position == uxRow) {
                        int count = MoagramConfig.getEnabledUxModCount();
                        String value = count > 0
                                ? LocaleController.formatString(R.string.MoagramEnabledCount, count)
                                : LocaleController.getString(R.string.MoagramNone);
                        cell.setTextAndValueAndIcon(
                                LocaleController.getString(R.string.MoagramUxTitle),
                                value,
                                R.drawable.msg_customize,
                                false
                        );
                    } else if (position == updatesRow) {
                        cell.setTextAndValueAndIcon(
                                LocaleController.getString(R.string.MoagramUpdates),
                                MoagramUpdateController.getInstance().installedVersion(),
                                R.drawable.msg_download,
                                false
                        );
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == batteryRow) {
                        boolean unrestricted = MoagramConfig.isIgnoringBatteryOptimizations(mContext);
                        cell.setTextAndValue(
                                LocaleController.getString(R.string.BackgroundHealthBattery),
                                LocaleController.getString(unrestricted
                                        ? R.string.BackgroundHealthBatteryUnrestricted
                                        : R.string.BackgroundHealthBatteryRestricted),
                                true
                        );
                        cell.setTextValueColor(Theme.getColor(unrestricted
                                ? Theme.key_windowBackgroundWhiteValueText
                                : Theme.key_text_RedRegular));
                    } else if (position == notificationsRow) {
                        boolean allowed = MoagramConfig.areNotificationsEnabled(mContext);
                        cell.setTextAndValue(
                                LocaleController.getString(R.string.BackgroundHealthNotifications),
                                LocaleController.getString(allowed
                                        ? R.string.BackgroundHealthAllowed
                                        : R.string.BackgroundHealthBlocked),
                                true
                        );
                        cell.setTextValueColor(Theme.getColor(allowed
                                ? Theme.key_windowBackgroundWhiteValueText
                                : Theme.key_text_RedRegular));
                    } else if (position == pushRow) {
                        boolean active = MoagramPush.isActive();
                        cell.setTextAndValue(
                                LocaleController.getString(R.string.MoagramPushTitle),
                                pushStatusValue(),
                                true
                        );
                        cell.setTextValueColor(Theme.getColor(active
                                ? Theme.key_windowBackgroundWhiteValueText
                                : Theme.key_windowBackgroundWhiteGrayText2));
                    } else if (position == keepAliveRow && MoagramPush.isActive()) {
                        cell.setTextAndValue(
                                LocaleController.getString(R.string.BackgroundHealthKeepAlive),
                                LocaleController.getString(R.string.MoagramPushKeepAliveNotNeeded),
                                false
                        );
                        cell.setTextValueColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
                    } else if (position == keepAliveRow) {
                        MessagesController mc = MessagesController.getInstance(currentAccount);
                        boolean both = mc.keepAliveService && mc.backgroundConnection;
                        boolean any = mc.keepAliveService || mc.backgroundConnection;
                        cell.setTextAndValue(
                                LocaleController.getString(R.string.BackgroundHealthKeepAlive),
                                LocaleController.getString(both
                                        ? R.string.BackgroundHealthActive
                                        : (any ? R.string.BackgroundHealthPartial : R.string.BackgroundHealthDisabled)),
                                false
                        );
                        cell.setTextValueColor(Theme.getColor(both
                                ? Theme.key_windowBackgroundWhiteValueText
                                : Theme.key_text_RedRegular));
                    }
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell header = (HeaderCell) holder.itemView;
                    if (position == featuresHeaderRow) {
                        header.setText(LocaleController.getString(R.string.MoagramFeaturesHeader));
                    } else if (position == healthHeaderRow) {
                        header.setText(LocaleController.getString(R.string.BackgroundHealthHeader));
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell info = (TextInfoPrivacyCell) holder.itemView;
                    if (position == healthInfoRow) {
                        info.setText(LocaleController.getString(R.string.BackgroundHealthInfo));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == featuresHeaderRow || position == healthHeaderRow) return TYPE_HEADER;
            if (position == batteryRow || position == notificationsRow || position == pushRow || position == keepAliveRow) return TYPE_SETTINGS;
            if (position == healthInfoRow) return TYPE_INFO;
            if (position == featuresShadowRow || position == endShadowRow) return TYPE_SHADOW;
            return TYPE_TEXT;
        }
    }
}
