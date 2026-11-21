package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.HiddenChatsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/** Dedicated account-scoped settings screen for Hidden Chats. */
public class HiddenChatsSettingsActivity extends BaseFragment {

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private int rowCount;
    private int openVaultRow;
    private int vaultShadowRow;
    private int passcodeHeaderRow;
    private int passcodeModeRow;
    private int changePinRow;
    private int passcodeShadowRow;
    private int triggersHeaderRow;
    private int triggerSearchRow;
    private int triggerTitleRow;
    private int triggerDoubleTapRow;
    private int triggerAllTabRow;
    private int triggerButtonRow;
    private int triggerMenuRow;
    private int triggersInfoRow;
    private int notificationsHeaderRow;
    private int hideNotificationsRow;
    private int disguiseNotificationsRow;
    private int endShadowRow;

    private static final int TYPE_TEXT = 0;
    private static final int TYPE_SETTINGS = 1;
    private static final int TYPE_CHECK = 2;
    private static final int TYPE_HEADER = 3;
    private static final int TYPE_INFO = 4;
    private static final int TYPE_SHADOW = 5;

    public HiddenChatsSettingsActivity() {
        this(UserConfig.selectedAccount);
    }

    public HiddenChatsSettingsActivity(int account) {
        setCurrentAccount(account);
    }

    public static String formatTriggerSummary(int account) {
        HiddenChatsController controller = HiddenChatsController.getInstance(account);
        ArrayList<String> names = new ArrayList<>();
        if (controller.opensFromSearch()) names.add(LocaleController.getString(R.string.HiddenChatsTriggerSummarySearch));
        if (controller.opensFromTitle()) names.add(LocaleController.getString(R.string.HiddenChatsTriggerSummaryTitle));
        if (controller.opensFromDoubleTapTitle()) names.add(LocaleController.getString(R.string.HiddenChatsTriggerSummaryDoubleTap));
        if (controller.opensFromAllTab()) names.add(LocaleController.getString(R.string.HiddenChatsTriggerSummaryAllTab));
        if (controller.opensFromButton()) names.add(LocaleController.getString(R.string.HiddenChatsTriggerSummaryButton));
        if (controller.opensFromMenu()) names.add(LocaleController.getString(R.string.HiddenChatsTriggerSummaryMenu));
        if (names.isEmpty()) {
            return LocaleController.getString(R.string.HiddenChatsTriggerSearch);
        }
        if (names.size() >= 5) {
            return LocaleController.getString(R.string.HiddenChatsTriggerSummaryAll);
        }
        return TextUtils.join(", ", names);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        openVaultRow = rowCount++;
        vaultShadowRow = rowCount++;
        passcodeHeaderRow = rowCount++;
        passcodeModeRow = rowCount++;
        changePinRow = rowCount++;
        passcodeShadowRow = rowCount++;
        triggersHeaderRow = rowCount++;
        triggerSearchRow = rowCount++;
        triggerTitleRow = rowCount++;
        triggerDoubleTapRow = rowCount++;
        triggerAllTabRow = rowCount++;
        triggerButtonRow = rowCount++;
        triggerMenuRow = rowCount++;
        triggersInfoRow = rowCount++;
        notificationsHeaderRow = rowCount++;
        hideNotificationsRow = rowCount++;
        disguiseNotificationsRow = rowCount++;
        endShadowRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.HiddenChatsTitle));
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
            HiddenChatsController controller = HiddenChatsController.getInstance(currentAccount);
            if (position == openVaultRow) {
                openHiddenChats();
            } else if (position == passcodeModeRow) {
                showPasscodeModeDialog();
            } else if (position == changePinRow) {
                changeHiddenChatsPin();
            } else if (position == triggerSearchRow) {
                toggleTrigger(view, controller, HiddenChatsController.FLAG_OPEN_SEARCH, !controller.opensFromSearch());
            } else if (position == triggerTitleRow) {
                toggleTrigger(view, controller, HiddenChatsController.FLAG_OPEN_TITLE, !controller.opensFromTitle());
            } else if (position == triggerDoubleTapRow) {
                toggleTrigger(view, controller, HiddenChatsController.FLAG_OPEN_DOUBLE_TAP, !controller.opensFromDoubleTapTitle());
            } else if (position == triggerAllTabRow) {
                toggleTrigger(view, controller, HiddenChatsController.FLAG_OPEN_ALL_TAB, !controller.opensFromAllTab());
            } else if (position == triggerButtonRow) {
                toggleTrigger(view, controller, HiddenChatsController.FLAG_OPEN_BUTTON, !controller.opensFromButton());
            } else if (position == triggerMenuRow) {
                toggleTrigger(view, controller, HiddenChatsController.FLAG_OPEN_MENU, !controller.opensFromMenu());
            } else if (position == hideNotificationsRow) {
                boolean next = !controller.isHideNotifications();
                controller.setHideNotifications(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
                listAdapter.notifyItemChanged(disguiseNotificationsRow);
            } else if (position == disguiseNotificationsRow) {
                if (controller.isHideNotifications()) {
                    return;
                }
                boolean next = !controller.isDisguiseNotifications();
                controller.setDisguiseNotifications(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            }
        });

        return fragmentView;
    }

    private void toggleTrigger(View view, HiddenChatsController controller, int flag, boolean enabled) {
        controller.setOpenFlag(flag, enabled);
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(enabled);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.moagramConfigChanged);
    }

    private void openHiddenChats() {
        HiddenChatsController controller = HiddenChatsController.getInstance(currentAccount);
        PasscodeActivity passcodeActivity = new PasscodeActivity(
                !controller.isPasscodeSet()
                        ? PasscodeActivity.TYPE_SETUP_CODE
                        : PasscodeActivity.TYPE_ENTER_CODE_TO_MANAGE_SETTINGS
        );
        passcodeActivity.setForHiddenChats(true);
        passcodeActivity.setOnPasscodeSuccess(() -> {
            Bundle args = new Bundle();
            args.putBoolean("hidden_chats", true);
            presentFragment(new DialogsActivity(args));
        });
        presentFragment(passcodeActivity);
    }

    private void showPasscodeModeDialog() {
        if (getParentActivity() == null) {
            return;
        }
        HiddenChatsController controller = HiddenChatsController.getInstance(currentAccount);
        CharSequence[] items = new CharSequence[] {
                LocaleController.getString(R.string.HiddenChatsUseTelegramPasscode),
                LocaleController.getString(R.string.HiddenChatsUseCustomPin)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle(LocaleController.getString(R.string.HiddenChatsPasscodeHeader));
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                if (SharedConfig.passcodeHash.isEmpty()) {
                    AlertDialog.Builder alert = new AlertDialog.Builder(getParentActivity(), resourceProvider);
                    alert.setTitle(LocaleController.getString(R.string.HiddenChatsAppLockTitle));
                    alert.setMessage(LocaleController.getString(R.string.HiddenChatsAppLockPrompt));
                    alert.setPositiveButton(LocaleController.getString(R.string.HiddenChatsSetPasscode), (d, w) -> {
                        PasscodeActivity passcodeActivity = new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE);
                        passcodeActivity.setOnPasscodeSuccess(() -> {
                            controller.setUseTelegramPasscode(true);
                            if (listAdapter != null) listAdapter.notifyDataSetChanged();
                            BulletinFactory.of(HiddenChatsSettingsActivity.this)
                                    .createSimpleBulletin(R.drawable.msg_secret, LocaleController.getString(R.string.HiddenChatsPasscodeTelegramEnabled))
                                    .show();
                        });
                        presentFragment(passcodeActivity);
                    });
                    alert.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                    showDialog(alert.create());
                } else {
                    controller.setUseTelegramPasscode(true);
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    BulletinFactory.of(HiddenChatsSettingsActivity.this)
                            .createSimpleBulletin(R.drawable.msg_secret, LocaleController.getString(R.string.HiddenChatsPasscodeTelegramUsing))
                            .show();
                }
            } else if (which == 1) {
                if (controller.hasCustomPasscode()) {
                    controller.setUseTelegramPasscode(false);
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    BulletinFactory.of(HiddenChatsSettingsActivity.this)
                            .createSimpleBulletin(R.drawable.msg_secret, LocaleController.getString(R.string.HiddenChatsPasscodeCustomUsing))
                            .show();
                } else {
                    PasscodeActivity passcodeActivity = new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE)
                            .setForHiddenChats(true);
                    passcodeActivity.setOnPasscodeSuccess(() -> {
                        if (listAdapter != null) listAdapter.notifyDataSetChanged();
                        BulletinFactory.of(HiddenChatsSettingsActivity.this)
                                .createSimpleBulletin(R.drawable.msg_secret, LocaleController.getString(R.string.HiddenChatsPasscodeCustomSet))
                                .show();
                    });
                    presentFragment(passcodeActivity);
                }
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void changeHiddenChatsPin() {
        HiddenChatsController controller = HiddenChatsController.getInstance(currentAccount);
        if (controller.isPasscodeSet()) {
            PasscodeActivity enterActivity = new PasscodeActivity(PasscodeActivity.TYPE_ENTER_CODE_TO_MANAGE_SETTINGS)
                    .setForHiddenChats(true);
            enterActivity.setOnPasscodeSuccess(() -> {
                PasscodeActivity setupActivity = new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE)
                        .setForHiddenChats(true);
                setupActivity.setOnPasscodeSuccess(() ->
                        BulletinFactory.of(HiddenChatsSettingsActivity.this)
                                .createSimpleBulletin(R.drawable.msg_secret, LocaleController.getString(R.string.HiddenChatsPinUpdated))
                                .show()
                );
                presentFragment(setupActivity);
            });
            presentFragment(enterActivity);
        } else {
            PasscodeActivity setupActivity = new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE)
                    .setForHiddenChats(true);
            setupActivity.setOnPasscodeSuccess(() ->
                    BulletinFactory.of(HiddenChatsSettingsActivity.this)
                            .createSimpleBulletin(R.drawable.msg_secret, LocaleController.getString(R.string.HiddenChatsPinCreated))
                            .show()
            );
            presentFragment(setupActivity);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
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
            int position = holder.getAdapterPosition();
            if (position == disguiseNotificationsRow) {
                return !HiddenChatsController.getInstance(currentAccount).isHideNotifications();
            }
            return position == openVaultRow
                    || position == passcodeModeRow
                    || position == changePinRow
                    || position == triggerSearchRow
                    || position == triggerTitleRow
                    || position == triggerDoubleTapRow
                    || position == triggerAllTabRow
                    || position == triggerButtonRow
                    || position == triggerMenuRow
                    || position == hideNotificationsRow;
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
                case TYPE_CHECK:
                    view = new TextCheckCell(mContext);
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
            HiddenChatsController controller = HiddenChatsController.getInstance(currentAccount);
            switch (holder.getItemViewType()) {
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == openVaultRow) {
                        int count = controller.getHiddenChatsCount();
                        String value = count > 0
                                ? LocaleController.formatString(R.string.HiddenChatsCount, count)
                                : LocaleController.getString(R.string.MoagramNone);
                        cell.setTextAndValueAndIcon(
                                LocaleController.getString(R.string.HiddenChatsOpen),
                                value,
                                R.drawable.msg_secret,
                                false
                        );
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == passcodeModeRow) {
                        String value = LocaleController.getString(
                                controller.isUseTelegramPasscode()
                                        ? R.string.HiddenChatsPasscodeTelegram
                                        : R.string.HiddenChatsPasscodeCustom
                        );
                        cell.setTextAndValue(LocaleController.getString(R.string.HiddenChatsPasscodeMode), value, true);
                    } else if (position == changePinRow) {
                        cell.setText(LocaleController.getString(R.string.HiddenChatsChangePin), false);
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == triggerSearchRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.HiddenChatsTriggerSearch), controller.opensFromSearch(), true);
                        cell.setEnabled(true, null);
                    } else if (position == triggerTitleRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.HiddenChatsTriggerTitle), controller.opensFromTitle(), true);
                        cell.setEnabled(true, null);
                    } else if (position == triggerDoubleTapRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.HiddenChatsTriggerDoubleTap), controller.opensFromDoubleTapTitle(), true);
                        cell.setEnabled(true, null);
                    } else if (position == triggerAllTabRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.HiddenChatsTriggerAllTab), controller.opensFromAllTab(), true);
                        cell.setEnabled(true, null);
                    } else if (position == triggerButtonRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.HiddenChatsTriggerButton), controller.opensFromButton(), true);
                        cell.setEnabled(true, null);
                    } else if (position == triggerMenuRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.HiddenChatsTriggerMenu), controller.opensFromMenu(), false);
                        cell.setEnabled(true, null);
                    } else if (position == hideNotificationsRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.HiddenChatsHideNotifications),
                                LocaleController.getString(R.string.HiddenChatsHideNotificationsInfo),
                                controller.isHideNotifications(), true, true);
                        cell.setEnabled(true, null);
                    } else if (position == disguiseNotificationsRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.HiddenChatsDisguiseNotifications),
                                LocaleController.getString(R.string.HiddenChatsDisguiseNotificationsInfo),
                                controller.isDisguiseNotifications(), true, false);
                        cell.setEnabled(!controller.isHideNotifications(), null);
                    }
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell header = (HeaderCell) holder.itemView;
                    if (position == passcodeHeaderRow) {
                        header.setText(LocaleController.getString(R.string.HiddenChatsPasscodeHeader));
                    } else if (position == triggersHeaderRow) {
                        header.setText(LocaleController.getString(R.string.HiddenChatsTriggersHeader));
                    } else if (position == notificationsHeaderRow) {
                        header.setText(LocaleController.getString(R.string.HiddenChatsNotificationsHeader));
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell info = (TextInfoPrivacyCell) holder.itemView;
                    if (position == triggersInfoRow) {
                        info.setText(LocaleController.getString(R.string.HiddenChatsTriggersInfo));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == openVaultRow) return TYPE_TEXT;
            if (position == passcodeModeRow || position == changePinRow) return TYPE_SETTINGS;
            if (position == passcodeHeaderRow || position == triggersHeaderRow || position == notificationsHeaderRow) return TYPE_HEADER;
            if (position == triggersInfoRow) return TYPE_INFO;
            if (position == vaultShadowRow || position == passcodeShadowRow || position == endShadowRow) return TYPE_SHADOW;
            return TYPE_CHECK;
        }
    }
}
