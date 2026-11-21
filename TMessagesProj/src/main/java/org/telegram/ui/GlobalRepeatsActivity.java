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

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocalRepeatController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/** Account-wide overview of all local repeating message rules across chats. */
public class GlobalRepeatsActivity extends BaseFragment {

    private ListAdapter listAdapter;
    private RecyclerListView listView;
    private final ArrayList<LocalRepeatController.RepeatInfo> repeats = new ArrayList<>();

    private static final int TYPE_RULE = 0;
    private static final int TYPE_INFO = 1;

    public GlobalRepeatsActivity() {
        this(UserConfig.selectedAccount);
    }

    public GlobalRepeatsActivity(int account) {
        setCurrentAccount(account);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        reloadRepeats();
        return true;
    }

    private void reloadRepeats() {
        repeats.clear();
        repeats.addAll(LocalRepeatController.getInstance(currentAccount).getAllRepeats());
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.GlobalRepeatsTitle));
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
            if (position < 0 || position >= repeats.size()) {
                return;
            }
            showRuleDialog(repeats.get(position));
        });

        return fragmentView;
    }

    private String resolvePeerTitle(long dialogId) {
        if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return LocaleController.getString(R.string.SavedMessages);
        }
        MessagesController mc = MessagesController.getInstance(currentAccount);
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = mc.getUser(dialogId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = mc.getChat(-dialogId);
            if (chat != null && !TextUtils.isEmpty(chat.title)) {
                return chat.title;
            }
        }
        return LocaleController.formatString(R.string.GlobalRepeatsUnknownChat, dialogId);
    }

    private String formatInterval(int intervalSeconds) {
        return intervalSeconds >= 86400
                ? LocaleController.formatPluralString("Days", intervalSeconds / 86400)
                : LocaleController.formatPluralString("Minutes", Math.max(1, intervalSeconds / 60));
    }

    private void openChat(long dialogId) {
        Bundle args = new Bundle();
        if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else if (DialogObject.isChatDialog(dialogId)) {
            args.putLong("chat_id", -dialogId);
        } else {
            return;
        }
        presentFragment(new ChatActivity(args));
    }

    private void showRuleDialog(LocalRepeatController.RepeatInfo info) {
        if (getParentActivity() == null) {
            return;
        }
        LocalRepeatController controller = LocalRepeatController.getInstance(currentAccount);
        String peerTitle = resolvePeerTitle(info.dialogId);
        String messagePreview = TextUtils.isEmpty(info.text)
                ? LocaleController.getString(R.string.LocalRepeatMedia)
                : info.text.replace('\n', ' ');
        String interval = formatInterval(info.interval);
        StringBuilder details = new StringBuilder();
        details.append(messagePreview).append("\n\n");
        details.append(LocaleController.formatString(R.string.LocalRepeatDetails, interval, info.queued));
        if (info.nextDate > 0) {
            details.append("\n").append(LocaleController.formatString(
                    R.string.GlobalRepeatsNextSend,
                    LocaleController.formatDateForBan(info.nextDate)
            ));
        }
        if (info.paused) {
            details.append("\n\n").append(LocaleController.getString(R.string.LocalRepeatPaused));
        }
        if (!TextUtils.isEmpty(info.error)) {
            details.append("\n\n").append(LocaleController.formatString(R.string.LocalRepeatError, info.error));
        }
        if (info.stopping) {
            details.append("\n\n").append(LocaleController.getString(R.string.LocalRepeatStopping));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle(peerTitle);
        builder.setMessage(details.toString());
        builder.setNeutralButton(LocaleController.getString(R.string.GlobalRepeatsOpenChat), (d, w) -> openChat(info.dialogId));
        if (info.paused) {
            builder.setPositiveButton(LocaleController.getString(R.string.LocalRepeatRetry), (d, w) -> {
                controller.retry(info.id);
                reloadRepeats();
            });
            if (!info.stopping) {
                builder.setNegativeButton(LocaleController.getString(R.string.LocalRepeatStop), (d, w) -> confirmStop(controller, info.id));
            }
        } else {
            if (!info.stopping) {
                builder.setPositiveButton(LocaleController.getString(R.string.LocalRepeatStop), (d, w) -> confirmStop(controller, info.id));
            }
            builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        }
        showDialog(builder.create());
    }

    private void confirmStop(LocalRepeatController controller, long ruleId) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(LocaleController.getString(R.string.LocalRepeatStop))
                .setMessage(LocaleController.getString(R.string.LocalRepeatStopConfirm))
                .setPositiveButton(LocaleController.getString(R.string.LocalRepeatStop), (d, w) -> {
                    controller.stop(ruleId);
                    reloadRepeats();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadRepeats();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return repeats.isEmpty() ? 1 : repeats.size() + 1;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_RULE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_RULE) {
                TextDetailSettingsCell cell = new TextDetailSettingsCell(mContext);
                cell.setMultilineDetail(true);
                cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                view = cell;
            } else {
                view = new TextInfoPrivacyCell(mContext);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_RULE) {
                TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                LocalRepeatController.RepeatInfo info = repeats.get(position);
                String peerTitle = resolvePeerTitle(info.dialogId);
                String preview = TextUtils.isEmpty(info.text)
                        ? LocaleController.getString(R.string.LocalRepeatMedia)
                        : info.text.replace('\n', ' ');
                if (preview.length() > 70) {
                    preview = preview.substring(0, 70) + "…";
                }
                String interval = formatInterval(info.interval);
                String status;
                if (info.stopping) {
                    status = LocaleController.formatString(R.string.GlobalRepeatsRuleSummaryStopping, interval);
                } else if (info.paused) {
                    status = LocaleController.formatString(R.string.GlobalRepeatsRuleSummaryPaused, interval, info.queued);
                } else {
                    status = LocaleController.formatString(R.string.GlobalRepeatsRuleSummary, interval, info.queued);
                }
                cell.setTextAndValue(peerTitle, preview + "\n" + status, position < repeats.size() - 1);
            } else {
                TextInfoPrivacyCell info = (TextInfoPrivacyCell) holder.itemView;
                info.setText(LocaleController.getString(
                        repeats.isEmpty() ? R.string.GlobalRepeatsEmpty : R.string.GlobalRepeatsInfo
                ));
            }
        }

        @Override
        public int getItemViewType(int position) {
            return position < repeats.size() ? TYPE_RULE : TYPE_INFO;
        }
    }
}
