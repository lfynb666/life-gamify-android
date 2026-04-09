package com.gamify.smsreporter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.os.Handler;

/**
 * 完整用户端：WebView加载gamify页面 + 原生SMS监听层
 * 
 * - 主界面为全屏WebView，加载终末地gamify页面
 * - JWT token持久化在WebView的localStorage/cookie中
 * - 原生层提供SMS监听能力，通过JS Bridge与网页通信
 * - 设置页面通过JS Bridge从网页端触发
 */
public class MainActivity extends Activity {
    private static final String TAG = "LifeGamify";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String DEFAULT_SERVER = "https://api.666-lufengyuan-nb.top";

    private WebView webView;
    private String serverUrl;
    private boolean isSplashPhase = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏沉浸式
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#0a0a0a"));
            getWindow().setNavigationBarColor(Color.parseColor("#0a0a0a"));
        }

        // 读取配置
        SharedPreferences config = getSharedPreferences("config", MODE_PRIVATE);
        serverUrl = config.getString("server_url", DEFAULT_SERVER);
        // 首次启动写入默认值
        if (!config.contains("server_url")) {
            config.edit().putString("server_url", DEFAULT_SERVER).apply();
        }

        // 创建WebView
        webView = new WebView(this);
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.parseColor("#0a0a0a"));
        container.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(container);

        setupWebView();
        requestSmsPermissions();
        startSmsMonitorService();

        // 先加载官网首页，显示React原版loading动画
        String splashUrl = serverUrl + "/endfield/official-v4/zh-cn/";
        Log.i(TAG, "Loading splash: " + splashUrl);
        webView.loadUrl(splashUrl);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);        // localStorage持久化JWT
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " LifeGamifyAPK/1.0");
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // Cookie持久化
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        // JS Bridge — 让网页调用原生功能
        webView.addJavascriptInterface(new NativeBridge(), "NativeBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                // 只允许加载自己服务器的页面，外部链接用浏览器打开
                if (host != null && (host.contains("666-lufengyuan-nb.top") || host.equals("localhost"))) {
                    return false;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(intent);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (isSplashPhase) {
                    // 官网首页HTML加载完成，启动JS轮询检测loading动画结束
                    isSplashPhase = false;
                    pollForLoadingComplete();
                } else {
                    // gamify页面加载完成
                    injectSmsStatus();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(Color.parseColor("#0a0a0a"));
    }

    /**
     * JS Bridge：网页可以调用的原生方法
     */
    public class NativeBridge {
        @JavascriptInterface
        public String getServerUrl() {
            return serverUrl;
        }

        @JavascriptInterface
        public void setServerUrl(String url) {
            SharedPreferences config = getSharedPreferences("config", MODE_PRIVATE);
            config.edit().putString("server_url", url).apply();
            serverUrl = url;
        }

        @JavascriptInterface
        public String getAuthToken() {
            SharedPreferences config = getSharedPreferences("config", MODE_PRIVATE);
            return config.getString("auth_token", "");
        }

        @JavascriptInterface
        public void setAuthToken(String token) {
            SharedPreferences config = getSharedPreferences("config", MODE_PRIVATE);
            config.edit().putString("auth_token", token).apply();
        }

        @JavascriptInterface
        public String getSmsStats() {
            SharedPreferences log = getSharedPreferences("sms_log", MODE_PRIVATE);
            int total = log.getInt("total_count", 0);
            String lastTime = log.getString("last_time", "-");
            String lastStatus = log.getString("last_status", "-");
            String lastMsg = log.getString("last_status_msg", "-");
            return "{\"total\":" + total +
                ",\"lastTime\":\"" + lastTime +
                "\",\"lastStatus\":\"" + lastStatus +
                "\",\"lastMsg\":\"" + escapeJson(lastMsg) + "\"}";
        }

        @JavascriptInterface
        public boolean hasSmsPermission() {
            if (Build.VERSION.SDK_INT >= 23) {
                return checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                    == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        @JavascriptInterface
        public void requestSmsPermission() {
            runOnUiThread(() -> requestSmsPermissions());
        }

        @JavascriptInterface
        public void showToast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String getAppVersion() {
            return "1.0.0";
        }

        @JavascriptInterface
        public String getPlatform() {
            return "android";
        }
    }

    private void injectSmsStatus() {
        SharedPreferences log = getSharedPreferences("sms_log", MODE_PRIVATE);
        int total = log.getInt("total_count", 0);
        String js = "window.__SMS_MONITOR = {active: true, total: " + total + "};";
        if (Build.VERSION.SDK_INT >= 19) {
            webView.evaluateJavascript(js, null);
        }
    }

    private void requestSmsPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            String[] perms = {
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
            };

            boolean needRequest = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    needRequest = true;
                    break;
                }
            }

            if (needRequest) {
                requestPermissions(perms, PERMISSION_REQUEST_CODE);
            }

            // Android 13+ 通知权限
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE + 1
                    );
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            String msg = allGranted ? "SMS权限已授予" : "需要SMS权限才能自动记录银行交易";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
    }

    private void startSmsMonitorService() {
        Intent intent = new Intent(this, SMSMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            injectSmsStatus();
        }
    }

    private void pollForLoadingComplete() {
        final Handler handler = new Handler();
        final Runnable[] pollTask = new Runnable[1];

        pollTask[0] = new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= 19) {
                    String js = "(function(){" +
                        "var el=document.querySelector('.__00-Loading_value__Zf_CS');" +
                        "if(!el)return '-1';" +
                        "return el.textContent.trim();" +
                    "})()";
                    webView.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (value == null) {
                                handler.postDelayed(pollTask[0], 500);
                                return;
                            }
                            String cleaned = value.replace("\"", "").trim();
                            int progress = -1;
                            try { progress = Integer.parseInt(cleaned); } catch (Exception e) {}

                            if (progress >= 100) {
                                Log.i(TAG, "Loading reached 100");
                                new Handler().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        navigateToGamify();
                                    }
                                }, 200);
                            } else {
                                long interval;
                                if (progress < 0) {
                                    interval = 500;
                                } else if (progress < 50) {
                                    interval = 500;
                                } else if (progress < 80) {
                                    interval = 200;
                                } else if (progress < 90) {
                                    interval = 100;
                                } else if (progress < 95) {
                                    interval = 50;
                                } else {
                                    interval = 10;
                                }
                                handler.postDelayed(pollTask[0], interval);
                            }
                        }
                    });
                } else {
                    handler.postDelayed(pollTask[0], 500);
                }
            }
        };

        handler.postDelayed(pollTask[0], 500);
    }

    private void navigateToGamify() {
        String gamifyUrl = serverUrl + "/endfield/official-v4/zh-cn/gamify/tasks.html";
        Log.i(TAG, "Navigating to gamify: " + gamifyUrl);
        webView.loadUrl(gamifyUrl);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
