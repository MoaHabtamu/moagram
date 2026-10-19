package org.telegram.ui.Components;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashSet;

public class DialogsGroupsAdapter extends UniversalAdapter {

    private final Context context;
    private final int currentAccount;
    private final int folderId;
    private final Theme.ResourcesProvider resourcesProvider;

    public final ArrayList<MessageObject> messages = new ArrayList<>();
    public final ArrayList<TLRPC.Chat> searchMyGroups = new ArrayList<>();
    public final ArrayList<TLRPC.Chat> searchGroups = new ArrayList<>();
    public boolean expandedSearchGroups = true;

    public boolean expandedMyGroups;
    public final ArrayList<TLRPC.Chat> myGroups = new ArrayList<>();

    public static boolean isGroup(TLRPC.Chat chat) {
        return chat != null && !chat.deactivated && (ChatObject.isMegagroup(chat) || !ChatObject.isChannel(chat));
    }

    public DialogsGroupsAdapter(RecyclerListView listView, Context context, int currentAccount, int folderId, Theme.ResourcesProvider resourcesProvider) {
        super(listView, context, currentAccount, 0, null, resourcesProvider);
        super.fillItems = this::fillItems;
        this.context = context;
        this.currentAccount = currentAccount;
        this.folderId = folderId;
        this.resourcesProvider = resourcesProvider;
        update(false);
    }

    public void updateMyGroups() {
        myGroups.clear();
        if (SharedConfig.searchHideJoined) {
            return;
        }
        ArrayList<TLRPC.Chat> groups = new ArrayList<>();
        ArrayList<TLRPC.Dialog> dialogs = MessagesController.getInstance(currentAccount).getAllDialogs();
        for (TLRPC.Dialog d : dialogs) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-d.id);
            if (chat == null || !isGroup(chat) || ChatObject.isNotInChat(chat)) continue;
            groups.add(chat);
            if (groups.size() >= 500)
                break;
        }
        myGroups.addAll(groups);
    }

    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (TextUtils.isEmpty(query)) {
            if (!SharedConfig.searchHideJoined && myGroups != null && !myGroups.isEmpty()) {
                if (myGroups.size() > 5) {
                    items.add(UItem.asGraySection(getString(R.string.SearchMyGroups), getString(expandedMyGroups ? R.string.ShowLess : R.string.ShowMore), this::toggleExpandedMyGroups));
                } else {
                    items.add(UItem.asGraySection(getString(R.string.SearchMyGroups)));
                }
                int count = myGroups.size();
                if (!expandedMyGroups)
                    count = Math.min(5, count);
                for (int i = 0; i < count; ++i) {
                    items.add(UItem.asProfileCell(myGroups.get(i)).withUsername(true));
                }
            } else {
                items.add(UItem.asFlicker(FlickerLoadingView.GRAY_SECTION));
                items.add(UItem.asFlicker(FlickerLoadingView.PROFILE_SEARCH_CELL));
                items.add(UItem.asFlicker(FlickerLoadingView.PROFILE_SEARCH_CELL));
                items.add(UItem.asFlicker(FlickerLoadingView.PROFILE_SEARCH_CELL));
                items.add(UItem.asFlicker(FlickerLoadingView.PROFILE_SEARCH_CELL));
            }
        } else {
            if (!SharedConfig.searchHideJoined && !searchMyGroups.isEmpty()) {
                items.add(UItem.asGraySection(getString(R.string.SearchMyGroups)));
                for (TLRPC.Chat chat : searchMyGroups) {
                    items.add(UItem.asProfileCell(chat).withUsername(true));
                }
            }
            ArrayList<TLRPC.Chat> visibleGroups = new ArrayList<>();
            for (TLRPC.Chat chat : searchGroups) {
                if (chat == null) continue;
                TLRPC.Chat localChat = MessagesController.getInstance(currentAccount).getChat(chat.id);
                if (SharedConfig.searchHideJoined && (!ChatObject.isNotInChat(chat) || (localChat != null && !ChatObject.isNotInChat(localChat)))) {
                    continue;
                }
                visibleGroups.add(chat);
            }
            if (!visibleGroups.isEmpty()) {
                if (visibleGroups.size() > 5 && !messages.isEmpty()) {
                    items.add(UItem.asGraySection(getString(R.string.SearchGlobalGroups), getString(expandedSearchGroups ? R.string.ShowLess : R.string.ShowMore), this::toggleExpandedSearchGroups));
                } else {
                    items.add(UItem.asGraySection(getString(R.string.SearchGlobalGroups)));
                }
                int count = visibleGroups.size();
                if (!expandedSearchGroups && !messages.isEmpty())
                    count = Math.min(5, count);
                else
                    count = Math.min(50, count);
                for (int i = 0; i < count; ++i) {
                    items.add(UItem.asProfileCell(visibleGroups.get(i)).withUsername(true));
                }
            }
            if (!messages.isEmpty()) {
                items.add(UItem.asGraySection(getString(R.string.SearchMessages)));
                for (MessageObject message : messages) {
                    items.add(UItem.asSearchMessage(message));
                }
                if (hasMore) {
                    items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_TYPE));
                }
            }
        }
    }

    public void toggleExpandedSearchGroups(View view) {
        expandedSearchGroups = !expandedSearchGroups;
        update(true);
        if (expandedSearchGroups) {
            hideKeyboard();
        }
    }

    public void toggleExpandedMyGroups(View view) {
        expandedMyGroups = !expandedMyGroups;
        update(true);
        if (expandedMyGroups) {
            hideKeyboard();
        }
    }

    protected void hideKeyboard() {

    }

    public TLRPC.Chat getChat(int position) {
        UItem item = getItem(position);
        return item != null && item.object instanceof TLRPC.Chat ? (TLRPC.Chat) item.object : null;
    }

    public Object getObject(int position) {
        UItem item = getItem(position);
        return item != null ? item.object : null;
    }

    public boolean loadingMessages;
    public boolean loadingGroups;

    private boolean hasMore;
    private int allCount;
    private int nextRate;
    private int searchGroupsId;
    public String query;

    private void searchMessages(boolean next) {
        loadingMessages = true;
        final int searchId = ++searchGroupsId;
        TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
        req.broadcasts_only = false;
        req.groups_only = true;
        if (folderId != 0) {
            req.flags |= 1;
            req.folder_id = folderId;
        }
        req.q = this.query;
        req.limit = 100;
        req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        if (next && !messages.isEmpty()) {
            MessageObject lastMessage = messages.get(messages.size() - 1);
            req.offset_rate = nextRate;
            req.offset_id = lastMessage.getId();
            if (lastMessage.messageOwner.peer_id == null) {
                req.offset_peer = new TLRPC.TL_inputPeerEmpty();
            } else {
                req.offset_peer = MessagesController.getInstance(currentAccount).getInputPeer(lastMessage.messageOwner.peer_id);
            }
        } else {
            req.offset_rate = 0;
            req.offset_id = 0;
            req.offset_peer = new TLRPC.TL_inputPeerEmpty();
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (searchId != searchGroupsId || !TextUtils.equals(req.q, this.query)) return;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (searchId != searchGroupsId || !TextUtils.equals(req.q, this.query)) return;
                loadingMessages = false;
                if (!next) {
                    messages.clear();
                }
                if (res instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages response = (TLRPC.messages_Messages) res;
                    MessagesStorage.getInstance(currentAccount).putUsersAndChats(response.users, response.chats, true, true);
                    MessagesController.getInstance(currentAccount).putUsers(response.users, false);
                    MessagesController.getInstance(currentAccount).putChats(response.chats, false);

                    for (TLRPC.Message message : response.messages) {
                        long did = MessageObject.getDialogId(message);
                        if (did < 0) {
                            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
                            if (chat != null && !isGroup(chat)) {
                                continue;
                            }
                            if (SharedConfig.searchHideJoined && chat != null && !ChatObject.isNotInChat(chat)) {
                                continue;
                            }
                        }
                        if (SharedConfig.searchHideJoined && MessagesController.getInstance(currentAccount).dialogs_dict.get(did) != null) {
                            continue;
                        }
                        MessageObject messageObject = new MessageObject(currentAccount, message, false, true);
                        messageObject.setQuery(query);
                        messages.add(messageObject);
                    }

                    if (response.chats != null) {
                        for (TLRPC.Chat chat : response.chats) {
                            if (chat == null || !isGroup(chat)) continue;
                            TLRPC.Chat localChat = MessagesController.getInstance(currentAccount).getChat(chat.id);
                            if (SharedConfig.searchHideJoined && (!ChatObject.isNotInChat(chat) || (localChat != null && !ChatObject.isNotInChat(localChat)))) {
                                continue;
                            }
                            boolean exists = false;
                            for (int c = 0; c < searchGroups.size(); ++c) {
                                if (searchGroups.get(c).id == chat.id) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                searchGroups.add(chat);
                            }
                        }
                    }

                    hasMore = response instanceof TLRPC.TL_messages_messagesSlice;
                    allCount = Math.max(messages.size(), response.count);
                    nextRate = response.next_rate;
                }
                update(true);
            }));
        }, next ? 800 : 0);

        if (!next) {
            loadingGroups = true;
            TLRPC.TL_contacts_search req2 = new TLRPC.TL_contacts_search();
            req2.limit = 50;
            req2.broadcasts = false;
            req2.q = this.query;
            ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req2, AndroidUtilities::runOnUIThread, (res, err) -> {
                if (!TextUtils.equals(req2.q, this.query) || TextUtils.isEmpty(this.query)) return;

                loadingGroups = false;
                TLRPC.TL_contacts_found response = null;
                if (res instanceof TLRPC.TL_contacts_found) {
                    response = (TLRPC.TL_contacts_found) res;
                    MessagesStorage.getInstance(currentAccount).putUsersAndChats(response.users, response.chats, true, true);
                    MessagesController.getInstance(currentAccount).putUsers(response.users, false);
                    MessagesController.getInstance(currentAccount).putChats(response.chats, false);
                }

                HashSet<Long> chatIds = new HashSet<>();
                String q = this.query.toLowerCase(), qT = AndroidUtilities.translitSafe(q);

                searchMyGroups.clear();
                if (!SharedConfig.searchHideJoined) {
                    if (response != null) {
                        for (TLRPC.Peer peer : response.my_results) {
                            long chatId = peer.channel_id != 0 ? peer.channel_id : peer.chat_id;
                            if (chatId == 0) continue;
                            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
                            if (chat == null || !isGroup(chat)) continue;
                            if (chatIds.contains(chat.id)) continue;
                            chatIds.add(chat.id);
                            searchMyGroups.add(chat);
                        }
                    }
                    for (TLRPC.Chat chat : myGroups) {
                        if (chat == null || chatIds.contains(chat.id)) continue;
                        String t = chat.title.toLowerCase(), tT = AndroidUtilities.translitSafe(t);
                        if (t.contains(q) || tT.contains(qT)) {
                            chatIds.add(chat.id);
                            searchMyGroups.add(chat);
                        }
                    }
                }

                if (response != null) {
                    for (TLRPC.Peer peer : response.results) {
                        long chatId = peer.channel_id != 0 ? peer.channel_id : peer.chat_id;
                        if (chatId == 0) continue;
                        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
                        if (chat == null || !isGroup(chat)) continue;
                        TLRPC.Chat localChat = MessagesController.getInstance(currentAccount).getChat(chat.id);
                        if (SharedConfig.searchHideJoined && (!ChatObject.isNotInChat(chat) || (localChat != null && !ChatObject.isNotInChat(localChat)))) {
                            continue;
                        }
                        boolean exists = false;
                        for (int c = 0; c < searchGroups.size(); ++c) {
                            if (searchGroups.get(c).id == chat.id) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            searchGroups.add(chat);
                        }
                    }
                }

                update(true);
            });
        }
    }

    private final Runnable searchMessagesRunnable = () -> searchMessages(false);
    public void search(String query) {
        updateMyGroups();
        if (TextUtils.equals(query, this.query)) return;
        this.query = query;
        AndroidUtilities.cancelRunOnUIThread(searchMessagesRunnable);
        if (TextUtils.isEmpty(this.query)) {
            messages.clear();
            searchGroups.clear();
            searchMyGroups.clear();
            update(true);
            searchGroupsId++;
            loadingMessages = false;
            loadingGroups = false;
            hasMore = false;
            nextRate = 0;
            if (listView != null) {
                listView.scrollToPosition(0);
            }
            return;
        }

        messages.clear();
        searchGroups.clear();
        searchMyGroups.clear();

        AndroidUtilities.runOnUIThread(searchMessagesRunnable, 1000);
        loadingMessages = true;
        loadingGroups = true;

        update(true);

        if (listView != null) {
            listView.scrollToPosition(0);
        }
    }

    public void searchMore() {
        if (!hasMore || loadingMessages || TextUtils.isEmpty(this.query)) {
            return;
        }
        searchMessages(true);
    }

    public void checkBottom() {
        if (!hasMore || loadingMessages || TextUtils.isEmpty(this.query) || listView == null)
            return;
        if (seesLoading()) {
            searchMore();
        }
    }

    public boolean seesLoading() {
        if (listView == null) return false;
        for (int i = 0; i < listView.getChildCount(); ++i) {
            View child = listView.getChildAt(i);
            if (child instanceof FlickerLoadingView) {
                return true;
            }
        }
        return false;
    }

    public boolean atTop() {
        if (listView == null) return false;
        for (int i = 0; i < listView.getChildCount(); ++i) {
            View child = listView.getChildAt(i);
            if (listView.getChildAdapterPosition(child) == 0)
                return true;
        }
        return false;
    }
}
