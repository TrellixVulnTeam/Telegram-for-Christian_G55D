package org.telegram.util;

import android.text.TextUtils;
import android.view.View;

import com.blankj.utilcode.util.ActivityUtils;
import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.NumberUtils;
import com.blankj.utilcode.util.RegexUtils;
import com.blankj.utilcode.util.SPUtils;
import com.google.zxing.common.StringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.MemberRequestCell;

import java.util.Arrays;
import java.util.Set;

public class TimeRecordUtil {

    /**
     * automatic add last time record when user not hangup after group call is finished
     *
     * @param chat
     * @param chatId
     */
    public static void autoAddOfflineWhenCallEnd(TLRPC.Chat chat, long chatId) {

        if (chat != null && ChatObject.canManageCalls(chat)) {
            SPUtils sp = SPUtils.getInstance("TimeRecord_" + chatId + "_" + UserConfig.selectedAccount);
            sp.getAll().forEach((key, value) -> {
                ChatObject.TimeRecord record = GsonUtils.fromJson(value.toString(), ChatObject.TimeRecord.class);
                if (record.getOfflines().size() < record.getOnlines().size()) {
                    record.getOfflines().add(System.currentTimeMillis());
                    sp.put(key, GsonUtils.toJson(record));
                }
            });

            TimeRecordUtil.timeHoleSp(chatId).put("endTime", System.currentTimeMillis());
        }
    }

    public static void tempJoinCall(ChatObject.Call call, TLRPC.Chat chat, View.OnClickListener listener) {
        SPUtils spHoles = TimeRecordUtil.timeHoleSp(call.chatId);
        boolean callIsGoing = spHoles.contains("startTime") && !spHoles.contains("endTime");

        // I am admin, but not the caller, when I join the call, I need to set online timestamp for everyone
        if (chat != null && ChatObject.canManageCalls(chat)) {
            SPUtils sp = SPUtils.getInstance("TimeRecord_" + call.chatId + "_" + UserConfig.selectedAccount);
            if (sp.getAll().isEmpty()) {
                for (int i = 0; i < call.participants.size(); i++) {
                    long key = call.participants.keyAt(i);
                    ChatObject.TimeRecord record = new ChatObject.TimeRecord();
                    record.getOnlines().add(System.currentTimeMillis());
                    sp.put(key + "", GsonUtils.toJson(record));
                }
                long myId = AccountInstance.getInstance(UserConfig.selectedAccount).getUserConfig().getClientUserId();
                if (!sp.contains(myId + "")) {
                    ChatObject.TimeRecord record = new ChatObject.TimeRecord();
                    record.getOnlines().add(System.currentTimeMillis());
                    sp.put(myId + "", GsonUtils.toJson(record));
                }
                spHoles.put("startTime", System.currentTimeMillis());
                listener.onClick(null);
            } else {
                if (!callIsGoing) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(ActivityUtils.getTopActivity());
                    builder.setMessage(LocaleController.getString("TimeRecordLastExists", R.string.TimeRecordLastExists));
                    builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
                    builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), (dialog, which) -> {
                        sp.clear();
                        for (int i = 0; i < call.participants.size(); i++) {
                            long key = call.participants.keyAt(i);
                            ChatObject.TimeRecord record = new ChatObject.TimeRecord();
                            record.getOnlines().add(System.currentTimeMillis());
                            sp.put(key + "", GsonUtils.toJson(record));
                        }

                        long myId = AccountInstance.getInstance(UserConfig.selectedAccount).getUserConfig().getClientUserId();
                        if (!sp.contains(myId + "")) {
                            ChatObject.TimeRecord record = new ChatObject.TimeRecord();
                            record.getOnlines().add(System.currentTimeMillis());
                            sp.put(myId + "", GsonUtils.toJson(record));
                        }

                        spHoles.clear();
                        spHoles.put("startTime", System.currentTimeMillis());
                        listener.onClick(null);
                    });
                    builder.show();
                } else {
                    listener.onClick(null);

                    long myId = AccountInstance.getInstance(UserConfig.selectedAccount).getUserConfig().getClientUserId();
                    boolean hasJoinIn = false;
                    for (int i = 0; i < call.participants.size(); i++) {
                        if (myId == call.participants.keyAt(i)) {
                            hasJoinIn = true;
                            break;
                        }
                    }

                    if (!hasJoinIn) {
                        ChatObject.TimeRecord record = new ChatObject.TimeRecord();
                        if (sp.contains(myId + ""))
                            record = GsonUtils.fromJson(sp.getString(myId + ""), ChatObject.TimeRecord.class);
                        record.getOnlines().add(System.currentTimeMillis());
                        sp.put(myId + "", GsonUtils.toJson(record));
                    }
                }
            }
        } else {
            listener.onClick(null);
        }
    }

    public static void addTimeRecord(long chatId, TLRPC.TL_groupCallParticipant participant, boolean online) {
        SPUtils sp1 = TimeRecordUtil.timeHoleSp(chatId);
        boolean callIsGoing = sp1.contains("startTime") && !sp1.contains("endTime");

        TLRPC.Chat chat = getCurrentAccount().getMessagesController().getChat(chatId);
        if (chat != null && ChatObject.canManageCalls(chat) && participant.peer.user_id
                != getCurrentAccount().getUserConfig().getClientUserId() && callIsGoing) {
            SPUtils sp = SPUtils.getInstance("TimeRecord_" + chatId + "_" + UserConfig.selectedAccount);
            String data = sp.getString(participant.peer.user_id + "");
            try {
                JSONObject obj;
                if (!TextUtils.isEmpty(data))
                    obj = new JSONObject(data);
                else obj = new JSONObject();

                JSONArray onlineArr, offlineArr;
                if (online) {
                    if (obj.has("onlines"))
                        onlineArr = obj.getJSONArray("onlines");
                    else {
                        onlineArr = new JSONArray();
                        obj.put("onlines", onlineArr);
                    }
                    onlineArr.put(System.currentTimeMillis());

                    Set<String> callingUsers = GroupCallUtil.callingSp().getStringSet(chatId + "");
                    callingUsers.removeIf(it ->
                            it.contains(participant.peer.user_id + ""));
                    GroupCallUtil.callingSp().put(chatId + "", callingUsers);
                } else {
                    if (obj.has("offlines"))
                        offlineArr = obj.getJSONArray("offlines");
                    else {
                        offlineArr = new JSONArray();
                        obj.put("offlines", offlineArr);
                    }
                    offlineArr.put(System.currentTimeMillis());
                }
                sp.put(participant.peer.user_id + "", obj.toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    private static AccountInstance getCurrentAccount() {
        return AccountInstance.getInstance(UserConfig.selectedAccount);
    }

    public static void startGroupCall(long chatId) {
        SPUtils sp = TimeRecordUtil.timeHoleSp(chatId);
        sp.put("startTime", System.currentTimeMillis());
        sp.remove("endTime");

        sp = TimeRecordUtil.timeRecordSp(chatId);
        long myId = AccountInstance.getInstance(UserConfig.selectedAccount).getUserConfig().getClientUserId();
        if (!sp.contains(myId + "")) {
            ChatObject.TimeRecord record = new ChatObject.TimeRecord();
            record.getOnlines().add(System.currentTimeMillis());
            sp.put(myId + "", GsonUtils.toJson(record));
        }
    }

    public static SPUtils timeRecordSp(long chatId) {
        return SPUtils.getInstance("TimeRecord_" + chatId + "_" + UserConfig.selectedAccount);
    }

    public static SPUtils timeHoleSp(long chatId) {
        return SPUtils.getInstance("TimeHole_" + chatId + "_" + UserConfig.selectedAccount);
    }
}
