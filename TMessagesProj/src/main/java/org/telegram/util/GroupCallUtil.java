package org.telegram.util;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import androidx.collection.LongSparseArray;
import androidx.core.app.NotificationManagerCompat;

import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.SPUtils;
import com.blankj.utilcode.util.StringUtils;
import com.blankj.utilcode.util.ThreadUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class GroupCallUtil {
    private static volatile GroupCallUtil Instance = new GroupCallUtil();
    private final List<Long> groupsExcludeMe = new ArrayList<>();

    public static GroupCallUtil getInstance() {
        GroupCallUtil localInstance = Instance;
        if (localInstance == null) {
            synchronized (GroupCallUtil.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new GroupCallUtil();
                }
            }
        }
        return localInstance;
    }

    public static SPUtils excludeSp() {
        return SPUtils.getInstance("excludeUsers" + UserConfig.selectedAccount);
    }

    public static SPUtils callingSp() {
        return SPUtils.getInstance("callingUsers" + UserConfig.selectedAccount);
    }

    public static void startGroupCall(TLRPC.ChatFull chatFull) {
        Set<String> callingUsers = new HashSet<>();
        Set<String> excludeUsers = excludeSp().getStringSet(chatFull.id + "", new HashSet<>());

        for (TLRPC.ChatParticipant participant : chatFull.participants.participants) {
            if (ChatObject.isAdmin(participant))
                excludeUsers.add(participant.user_id + "");
            else if (!excludeUsers.contains(participant.user_id + ""))
                callingUsers.add(participant.user_id + "_" + System.currentTimeMillis());
        }

        excludeSp().put(chatFull.id + "", excludeUsers);
        callingSp().put(chatFull.id + "", callingUsers);
        if (excludeUsers.size() > 0) {
            List<TLRPC.User> users = new ArrayList<>(excludeUsers).stream()
                    .map(it -> getInstance().getMessagesController().getUser(Long.parseLong(it)))
                    .collect(Collectors.toList());
            GroupCallUtil.sendExcludeUsers(chatFull.id, users);
        }
        ThreadUtils.runOnUiThreadDelayed(() -> {
            GroupCallUtil.sendCommandMessage(GroupCallUtil.INVITE_ALL, chatFull.id, null);
        }, 5000);
    }

    public static void callUser(long chatId, long userId) {
        Set<String> callingUsers = callingSp().getStringSet(chatId + "", new HashSet<>());
        callingUsers.add(userId + "_" + System.currentTimeMillis());
        callingSp().put(chatId + "", callingUsers);
    }

    private static boolean callingUsersContains(Set<String> callingUsers, long userId) {
        for (String value : callingUsers) {
            if (value.contains(userId + "_"))
                return true;
        }
        return false;
    }

    public static void callAll(TLRPC.Chat chat, ArrayList<TLObject> participants) {
        Set<String> callingUsers = callingSp().getStringSet(chat.id + "", new HashSet<>());
        Set<String> excludeUsers = excludeSp().getStringSet(chat.id + "", new HashSet<>());
        participants.stream().forEach(participant -> {
            long userId = -1;
            if (participant instanceof TLRPC.ChatParticipant)
                userId = ((TLRPC.ChatParticipant) participant).user_id;
            else if (participant instanceof TLRPC.ChannelParticipant)
                userId = ((TLRPC.ChannelParticipant) participant).peer.user_id;
            if (userId != -1 && !callingUsersContains(callingUsers, userId) && !excludeUsers.contains(userId + ""))
                callingUsers.add(userId + "_" + System.currentTimeMillis());
        });

        callingSp().put(chat.id + "", callingUsers);
    }

    public void processFcmCommandMessage(MessageObject messageObject) {
        try {
            LogUtils.d("process command from fcm");
            // Only when process is killed we need fcm to trigger, other time we just use tdlib
            if (messageObject.isFcmMessage()
                    && messageObject.messageOwner instanceof TLRPC.TL_message) {
                TLRPC.Message message = messageObject.messageOwner;
                processCommandMessage(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Add feature of group call ringing, use a fake VoIPService call to give ringing
     */
    public void processCommandMessage(TLRPC.Message tlMessage) {
        try {
            long chatId = tlMessage.peer_id.chat_id;
            long userId = tlMessage.peer_id.user_id;
            long fromId = tlMessage.from_id.user_id;
            String message = tlMessage.message;
            List<TLRPC.MessageEntity> entities = tlMessage.entities;

            if (TextUtils.isEmpty(message)) return;

            boolean chatIdInvalid = false;
            if (chatId == 0) {
                chatId = tlMessage.peer_id.channel_id;
                chatIdInvalid = true;
            }

            boolean needRing = false;
            boolean toMe = false;

            if (userId == getUserConfig().getClientUserId())
                toMe = true;

            if (message.contains("@" + getUserConfig().getClientUserName()))
                toMe = true;

            if (entities != null && entities.size() > 0) {
                for (TLRPC.MessageEntity entity : entities) {
                    if (entity instanceof TLRPC.TL_messageEntityMentionName)
                        if (((TLRPC.TL_messageEntityMentionName) entity).user_id == getUserConfig().getClientUserId()) {
                            toMe = true;
                        }
                }
            }

            if (message.contains(INVITE_ALL_STR)) {
                if (!groupsExcludeMe.contains(chatId))
                    needRing = true;
            } else if (message.contains(UNEXCLUDE_STR)) {
                if (toMe) {
                    groupsExcludeMe.remove(chatId);
                    needRing = false;
                }
            } else if (message.contains(INVITE_USER_STR)) {
                if (!groupsExcludeMe.contains(chatId) && toMe)
                    needRing = true;
            } else if (message.contains(EXCLUDE_STR)) {
                if (toMe) {
                    if (!groupsExcludeMe.contains(chatId))
                        groupsExcludeMe.add(chatId);
                    if (VoIPService.getSharedInstance() != null && VoIPService.getSharedInstance().isGroupCallRinging())
                        VoIPService.getSharedInstance().endGroupCallRinging();
                }
            } else if (message.contains(REFUSE_INVITE_STR)) {
                if (entities != null && entities.size() > 0 && entities.get(0) instanceof TLRPC.TL_messageEntityMentionName) {
                    long finalChatId = chatId;
                    ThreadUtils.runOnUiThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.refuseGroupCall, finalChatId,
                            ((TLRPC.TL_messageEntityMentionName) entities.get(0)).user_id)
                    );
                }
                return;
            } else return;

            if (!needRing) return;

            // Only admin can set command, so check the sender
            List<Long> ids = new ArrayList<>();
            if (chatIdInvalid) {
                TLRPC.ChatFull fullChat = getFullChatLocal(chatId);
                if (fullChat == null || fullChat.call == null) return;

                LongSparseArray<TLRPC.ChannelParticipant> adminArray = getMessagesController().channelAdmins.get(chatId);
                if (adminArray != null) {
                    for (int i = 0; i < adminArray.size(); i++)
                        ids.add(adminArray.valueAt(i).peer.user_id);
                } else {
                    ids = getMessagesStorage().loadChannelAdminsSync(chatId);
                }

                if (ids.size() > 0) {
                    if (ids.contains(fromId)) {
                        doGroupCallRinging(chatId);
                    }
                } else {
                    TLRPC.TL_channels_getParticipants req = new TLRPC.TL_channels_getParticipants();
                    req.channel = getMessagesController().getInputChannel(chatId);
                    req.limit = 100;
                    req.filter = new TLRPC.TL_channelParticipantsAdmins();

                    long finalChatId = chatId;
                    getConnectionsManager().sendRequest(req, (response, error) -> {
                        if (error == null) {
                            if (response instanceof TLRPC.TL_channels_channelParticipants) {
                                TLRPC.TL_channels_channelParticipants participants = (TLRPC.TL_channels_channelParticipants) response;
                                List<Long> list = participants.participants.stream().map(it -> it.peer.user_id).collect(Collectors.toList());
                                if (list.contains(fromId)) {
                                    doGroupCallRinging(finalChatId);
                                }
                            }
                        }
                    });
                }
            } else {
                TLRPC.ChatFull fullChat = getFullChatLocal(chatId);
                if (fullChat != null) {
                    if (fullChat.call == null) return;
                    ids = fullChat.participants.participants.stream().filter(item -> item instanceof TLRPC.TL_chatParticipantCreator
                            || item instanceof TLRPC.TL_chatParticipantAdmin).map(item -> item.user_id).collect(Collectors.toList());
                    if (ids.contains(fromId)) {
                        doGroupCallRinging(chatId);
                    }
                } else {
                    TLRPC.TL_messages_getFullChat req = new TLRPC.TL_messages_getFullChat();
                    req.chat_id = chatId;

                    getConnectionsManager().sendRequest(req, (response, error) -> {
                        if (error == null) {
                            TLRPC.TL_messages_chatFull res = (TLRPC.TL_messages_chatFull) response;
                            if (res.full_chat.call == null) return;
                            List<Long> list = res.full_chat.participants.participants.stream().filter(item -> item instanceof TLRPC.TL_chatParticipantCreator
                                    || item instanceof TLRPC.TL_chatParticipantAdmin).map(item -> item.user_id).collect(Collectors.toList());
                            if (list.contains(fromId))
                                doGroupCallRinging(req.chat_id);
                        }
                    });
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(String message, long chatId) {
        getSendMessagesHelper().sendMessage(message, -chatId, null, null, null, false, null, null, null, true, 0, null);
    }

    private void sendMessage(String message, long chatId, TLRPC.User user) {
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        TLRPC.TL_inputMessageEntityMentionName entity = new TLRPC.TL_inputMessageEntityMentionName();
        entity.user_id = getMessagesController().getInputUser(user);
        entity.offset = 0;
        entity.length = user.first_name.length();
        entities.add(entity);
        getSendMessagesHelper().sendMessage(message, -chatId, null, null, null, false, entities, null, null, true, 0, null);
    }

    private SendMessagesHelper getSendMessagesHelper() {
        return AccountInstance.getInstance(UserConfig.selectedAccount).getSendMessagesHelper();
    }

    public static final int INVITE_ALL = 1;
    public static final int INVITE_USER = 2;
    public static final int EXCLUDE = 3;
    public static final int UNEXCLUDE = 4;
    public static final int REFUSE_INVITE = 5;

    public static final String INVITE_ALL_STR = "invited all to the video chat";
    public static final String INVITE_USER_STR = "invited you to the video chat";
    public static final String EXCLUDE_STR = "will not invite you to the video chat";
    public static final String UNEXCLUDE_STR = "re-invited you to the video chat";
    public static final String REFUSE_INVITE_STR = "refused to join the video chat";

    public static void sendCommandMessage(int type, long chatId, TLRPC.User user) {
        TLRPC.User me = getInstance().getUserConfig().getCurrentUser();
        String myName = ContactsController.formatName(me.first_name, me.last_name);
        String command;
        switch (type) {
            case INVITE_ALL:
                command = myName + " " + INVITE_ALL_STR;
                getInstance().sendMessage(command, chatId);
                getInstance().deleteMessage(chatId, getInstance().getUserConfig().lastSendMessageId + 1);
                break;
            case INVITE_USER:
            case EXCLUDE:
            case UNEXCLUDE:
                String str;
                if (type == INVITE_USER)
                    str = INVITE_USER_STR;
                else if (type == EXCLUDE)
                    str = EXCLUDE_STR;
                else str = UNEXCLUDE_STR;

                if (!StringUtils.isEmpty(user.username)) {
                    command = "@" + user.username + " " + myName + " " + str;
                    getInstance().sendMessage(command, chatId);
                } else {
                    command = user.first_name + " " + myName + " " + str;
                    getInstance().sendMessage(command, chatId, user);
                }
                getInstance().deleteMessage(chatId, getInstance().getUserConfig().lastSendMessageId + 1);
                break;
            case REFUSE_INVITE:
                command = me.first_name + " " + REFUSE_INVITE_STR;
                getInstance().sendMessage(command, chatId, me);
                getInstance().deleteMessage(chatId, getInstance().getUserConfig().lastSendMessageId + 1);
                break;
        }
    }

    public static void sendExcludeUsers(long chatId, List<TLRPC.User> users) {
        List<TLRPC.User> byUserName = users.stream().filter(it -> !StringUtils.isEmpty(it.username)).collect(Collectors.toList());
        List<TLRPC.User> byFirstName = users.stream().filter(it -> StringUtils.isEmpty(it.username)).collect(Collectors.toList());
        TLRPC.User me = getInstance().getUserConfig().getCurrentUser();
        String myName = ContactsController.formatName(me.first_name, me.last_name);

        if (!byUserName.isEmpty()) {
            String command = TextUtils.join(" ", byUserName.stream().map(it -> "@" + it.username)
                    .collect(Collectors.toList())) + " " + myName + " " + EXCLUDE_STR;
            getInstance().sendMessage(command, chatId);
            getInstance().deleteMessage(chatId, getInstance().getUserConfig().lastSendMessageId + 1);
        }

        if (!byFirstName.isEmpty()) {
            ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
            for (TLRPC.User user : byFirstName) {
                TLRPC.TL_inputMessageEntityMentionName entity = new TLRPC.TL_inputMessageEntityMentionName();
                entity.user_id = getInstance().getMessagesController().getInputUser(user);
                entity.offset = 0;
                entity.length = user.first_name.length();
                entities.add(entity);
            }

            String command = TextUtils.join(" ", byFirstName.stream().map(it -> it.first_name).collect(Collectors.toList()))
                    + " " + myName + " " + EXCLUDE_STR;
            getInstance().getSendMessagesHelper().sendMessage(command, -chatId, null, null, null, false, entities, null, null, true, 0, null);
            getInstance().deleteMessage(chatId, getInstance().getUserConfig().lastSendMessageId + 1);
        }
    }

    private void deleteMessage(long chatId, int msgId) {
        getNotificationCenter().addObserver(new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int id, int account, Object... args) {
                if (msgId == (int) args[0]) {
                    int newMsgId = (int) args[1];
                    ArrayList<Integer> msgs = new ArrayList<>();
                    msgs.add(newMsgId);
                    ThreadUtils.runOnUiThreadDelayed(() -> getMessagesController().deleteMessages(msgs, null, null, -chatId, true, false), 200);
                    getNotificationCenter().removeObserver(this, NotificationCenter.messageReceivedByServer);
                }
            }
        }, NotificationCenter.messageReceivedByServer);
    }

    private NotificationCenter getNotificationCenter() {
        return AccountInstance.getInstance(UserConfig.selectedAccount).getNotificationCenter();
    }

    public static boolean isCommandMessage(TLRPC.Message tlMessage) {
        String message = tlMessage.message;
        return !TextUtils.isEmpty(message) && (message.contains(INVITE_ALL_STR) ||
                message.contains(UNEXCLUDE_STR) ||
                message.contains(INVITE_USER_STR) ||
                message.contains(EXCLUDE_STR) ||
                message.contains(REFUSE_INVITE_STR));
    }

    private ConnectionsManager getConnectionsManager() {
        return AccountInstance.getInstance(UserConfig.selectedAccount).getConnectionsManager();
    }

    private UserConfig getUserConfig() {
        return AccountInstance.getInstance(UserConfig.selectedAccount).getUserConfig();
    }

    private MessagesController getMessagesController() {
        return AccountInstance.getInstance(UserConfig.selectedAccount).getMessagesController();
    }

    private TLRPC.ChatFull getFullChatLocal(long chatId) {
        TLRPC.ChatFull fullChat = getMessagesController().getChatFull(chatId);
        if (fullChat == null) {
            fullChat = getMessagesStorage().loadChatInfo(chatId, false, null, true, false);
        }
        return fullChat;
    }

    private MessagesStorage getMessagesStorage() {
        return AccountInstance.getInstance(UserConfig.selectedAccount).getMessagesStorage();
    }

    private void doGroupCallRinging(long chatId) {
        long lastHangup = MessagesController.getGlobalMainSettings().getLong("lastHangup", -1);
        if (lastHangup > 0 && System.currentTimeMillis() - lastHangup < 60000)       // When user hangup, can not ring up again in 1 minite
            return;

        boolean notificationsDisabled = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !NotificationManagerCompat.from(ApplicationLoader.applicationContext).areNotificationsEnabled()) {
            notificationsDisabled = true;
            if (ApplicationLoader.mainInterfacePaused || !ApplicationLoader.isScreenOn) {
                return;
            }
        }

        TelephonyManager tm = (TelephonyManager) ApplicationLoader.applicationContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (VoIPService.getSharedInstance() != null || tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
            return;
        }

        Intent intent = new Intent(ApplicationLoader.applicationContext, VoIPService.class);
        intent.putExtra("is_outgoing", false);
        intent.putExtra("chat_id", chatId);
        intent.putExtra("account", UserConfig.selectedAccount);
        intent.putExtra("group_call_ringing", true);
        intent.putExtra("notifications_disabled", notificationsDisabled);
        if (!notificationsDisabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ApplicationLoader.applicationContext.startForegroundService(intent);
        } else {
            ApplicationLoader.applicationContext.startService(intent);
        }

        if (ApplicationLoader.mainInterfacePaused || !ApplicationLoader.isScreenOn)
            getMessagesController().ignoreSetOnline = true;
    }
}
