package com.gamify.smsreporter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 监听短信广播，过滤银行短信并上报到服务器
 */
public class SMSReceiver extends BroadcastReceiver {
    private static final String TAG = "SMSReporter";
    // 银行短信发送号码白名单
    private static final String[] BANK_SENDERS = {
        "95599",  // 农业银行
        "95588",  // 工商银行
        "95533",  // 建设银行
        "95566",  // 中国银行
        "95555",  // 招商银行
        "95568",  // 民生银行
        "95558",  // 中信银行
    };
    // 关键词检测：确保是动账短信
    private static final String[] BANK_KEYWORDS = {
        "人民币", "余额", "交易", "入账", "支出", "收入", "消费", "转账", "代付"
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        String format = bundle.getString("format");
        if (pdus == null) return;

        StringBuilder fullMessage = new StringBuilder();
        String sender = null;

        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (sender == null) sender = sms.getOriginatingAddress();
            fullMessage.append(sms.getMessageBody());
        }

        String smsText = fullMessage.toString();
        String smsSender = sender != null ? sender : "";

        Log.d(TAG, "SMS from: " + smsSender + " → " + smsText.substring(0, Math.min(50, smsText.length())));

        // 检查是否为银行短信
        if (!isBankSMS(smsSender, smsText)) {
            Log.d(TAG, "Not a bank SMS, ignoring");
            return;
        }

        Log.i(TAG, "Bank SMS detected! Reporting to server...");

        // 记录到本地日志
        SharedPreferences prefs = context.getSharedPreferences("sms_log", Context.MODE_PRIVATE);
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        int count = prefs.getInt("total_count", 0) + 1;
        prefs.edit()
            .putString("last_sms", smsText)
            .putString("last_time", timestamp)
            .putString("last_sender", smsSender)
            .putInt("total_count", count)
            .apply();

        // 异步上报
        executor.execute(() -> reportToServer(context, smsText, smsSender));
    }

    private boolean isBankSMS(String sender, String text) {
        // 检查发送者号码
        boolean senderMatch = false;
        for (String bankSender : BANK_SENDERS) {
            if (sender.contains(bankSender)) {
                senderMatch = true;
                break;
            }
        }

        // 也检查短信内容签名（有些手机显示不同号码）
        if (!senderMatch) {
            if (text.contains("中国农业银行") || text.contains("工商银行") ||
                text.contains("建设银行") || text.contains("中国银行") ||
                text.contains("招商银行")) {
                senderMatch = true;
            }
        }

        if (!senderMatch) return false;

        // 检查是否包含动账关键词
        for (String keyword : BANK_KEYWORDS) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private void reportToServer(Context context, String smsText, String sender) {
        SharedPreferences config = context.getSharedPreferences("config", Context.MODE_PRIVATE);
        String serverUrl = config.getString("server_url", "https://api.666-lufengyuan-nb.top");
        String authToken = config.getString("auth_token", "");

        if (authToken.isEmpty()) {
            Log.e(TAG, "Not logged in, cannot report SMS");
            updateStatus(context, "error", "未登录, 请先在APP中登录");
            return;
        }

        try {
            URL url = new URL(serverUrl + "/api/finance/sms");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            String json = "{" +
                "\"smsText\":" + escapeJson(smsText) + "," +
                "\"bankType\":\"auto\"" +
                "}";

            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                Log.i(TAG, "SMS reported successfully!");
                int success = config.getInt("success_count", 0) + 1;
                config.edit().putInt("success_count", success).apply();
                updateStatus(context, "success", "上报成功 (#" + success + ")");
            } else {
                Log.e(TAG, "Server returned: " + code);
                updateStatus(context, "error", "服务器返回: " + code);
            }

            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Report failed: " + e.getMessage());
            updateStatus(context, "error", "上报失败: " + e.getMessage());
            // TODO: 失败重试队列
        }
    }

    private void updateStatus(Context context, String status, String message) {
        SharedPreferences prefs = context.getSharedPreferences("sms_log", Context.MODE_PRIVATE);
        prefs.edit()
            .putString("last_status", status)
            .putString("last_status_msg", message)
            .putString("last_status_time", new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date()))
            .apply();
    }

    private String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
