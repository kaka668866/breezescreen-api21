package app.breezescreen.tv;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * kakadou v11：网页版 API + 签名 无限刷版。
 *
 * 方案：隐藏 WebView 充当"真实浏览器"——
 *   ① 加载 https://www.douyin.com/?recommend=1（服务器对 WebView 环境放行，真实页面会 Set-Cookie）
 *   ② 轮询 CookieManager 取出 msToken / ttwid / odin_tt 等完整 cookie 链
 *   ③ 在 WebView 里执行 assets/abogus_dy.js（myDV 同款签名 SDK）生成 a_bogus 签名
 *   ④ 带 cookie + msToken + a_bogus 请求网页版 /aweme/v1/web/tab/feed/
 *   ⑤ 用响应 max_cursor 真分页 + aweme_id 去重追加 → 无限往下刷
 * 失败时回退到 v10.6 的无签名车机版接口（保底能播）。
 */
public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "BreezeScreen";
    private static final String HOME_URL = "https://www.douyin.com/?recommend=1";
    private static final String FEED_API_WEB = "https://www.douyin.com/aweme/v1/web/tab/feed/?";
    private static final String FEED_API_EXT =
            "https://aweme.snssdk.com/aweme/v1/external_feed/?type=0&count=20"
                    + "&max_cursor=0&min_cursor=0&feed_style=504&volume=0&pull_type=2&need_relieve_aweme=0";
    private static final String FEED_API_MAIN =
            "https://aweme.snssdk.com/aweme/v1/feed/?type=0&count=20"
                    + "&max_cursor=0&min_cursor=0&feed_style=504&volume=0&pull_type=2&need_relieve_aweme=0";
    private static final String PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";
    private static final long INFO_BAR_HIDE_MS = 5000L;
    private static final long BACK_PRESS_EXIT_MS = 1500L;
    private static final int COOKIE_WAIT_MS = 3000;
    private static final int COOKIE_MAX_TRIES = 12; // 最多等 36 秒

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<VideoItem> items = new ArrayList<>();
    private final HashSet<String> seenIds = new HashSet<>();

    private VideoView videoView;
    private View infoBar;
    private View progressBarView;
    private View loadingPanel;
    private ProgressBar playProgress;
    private TextView titleText;
    private TextView subText;
    private TextView timeText;

    // WebView 会话（隐藏）
    private WebView sessionWebView;
    private boolean cookieReady = false;
    private String msToken = "";
    private String cookieChain = "";
    private int cookieTries = 0;
    private boolean sessionMode = true;   // 当前是否走"网页版+签名"链路
    private long maxCursor = 0;           // 网页版分页游标
    private boolean hasCursor = false;

    private int currentIndex = -1;
    private boolean userPaused = false;
    private boolean infoBarVisible = false;
    private long lastBackAt;
    private String lastError = "";
    private int fetchRetries = 0;
    private static final int MAX_FETCH_RETRIES = 3;
    private boolean fetchingMore = false;
    private int apiToggle = 0;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        bindViews();
        setupVideoView();
        startSession();
    }

    private void bindViews() {
        videoView = findViewById(R.id.video_view);
        infoBar = findViewById(R.id.info_bar);
        progressBarView = findViewById(R.id.progress_bar);
        loadingPanel = findViewById(R.id.loading_panel);
        playProgress = findViewById(R.id.play_progress);
        titleText = findViewById(R.id.video_title);
        subText = findViewById(R.id.video_sub);
        timeText = findViewById(R.id.play_time);
    }

    private void setupVideoView() {
        videoView.setOnPreparedListener(mp -> {
            Log.d(TAG, "prepared: " + currentIndex);
            loadingPanel.setVisibility(View.GONE);
            userPaused = false;
            videoView.start();
            showInfoBar();
            startProgressLoop();
        });
        videoView.setOnCompletionListener(mp -> nextVideo());
        videoView.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "play error what=" + what + " extra=" + extra);
            Toast.makeText(this, "视频加载失败，跳过", Toast.LENGTH_SHORT).show();
            nextVideo();
            return true;
        });
    }

    /* ================= WebView 会话：取 cookie + 生成签名 ================= */

    @SuppressLint("SetJavaScriptEnabled")
    private void startSession() {
        loadingPanel.setVisibility(View.VISIBLE);
        FrameLayout root = findViewById(R.id.root_layout);
        if (root == null) {
            // 布局里没有 root_layout 时用内容根
            root = (FrameLayout) findViewById(android.R.id.content);
        }
        sessionWebView = new WebView(this);
        WebSettings s = sessionWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 关键：用桌面 Chrome UA，抖音才会返回桌面推荐页 SSR（含 msToken）
        s.setUserAgentString(PC_UA);
        sessionWebView.setBackgroundColor(Color.TRANSPARENT);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(sessionWebView, true);
        sessionWebView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "session page finished: " + url);
                checkCookies();
                grabMsTokenFromPage();
            }

            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                String u = request.getUrl() != null ? request.getUrl().toString() : "";
                if (u.contains("/aweme/") || u.contains("mssdk") || u.contains("a_bogus") || u.contains("msToken")) {
                    Log.d(TAG, "PAGE-REQ " + u);
                }
                return null;
            }
        });
        // 1x1 隐藏 WebView，挂在根布局角落
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
        lp.leftMargin = 0;
        lp.topMargin = 0;
        root.addView(sessionWebView, lp);
        sessionWebView.loadUrl(HOME_URL);
        handler.postDelayed(checkCookiesRunnable, COOKIE_WAIT_MS);
    }

    private final Runnable checkCookiesRunnable = new Runnable() {
        @Override public void run() {
            checkCookies();
        }
    };

    /** 从页面 DOM 里正则抠 msToken（抖音把 msToken 内嵌在 SSR HTML/JS 里，不在 Set-Cookie）。 */
    private void grabMsTokenFromPage() {
        if (cookieReady) return;
        try {
            String js = "(function(){"
                    + "var out={};"
                    + "var h=document.documentElement.outerHTML||'';"
                    + "out.len=h.length;"
                    + "out.cookie=document.cookie;"
                    + "var idx=[];var p=0;"
                    + "while((p=h.indexOf('msToken',p))>=0&&idx.length<6){idx.push(h.substring(p-30,p+40));p+=7;}"
                    + "out.hits=idx.join('|||');"
                    + "out.router=typeof window._ROUTER_DATA;"
                    + "out.init=typeof window.__INITIAL_STATE__;"
                    + "out.next=typeof window.__NEXT_DATA__;"
                    + "return JSON.stringify(out);})()";
            sessionWebView.evaluateJavascript(js, new ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    Log.d(TAG, "page diag: " + value);
                    if (value == null) return;
                    String v = value.trim();
                    if (v.length() > 2 && v.startsWith("\"") && v.endsWith("\"")) {
                        v = v.substring(1, v.length() - 1).replace("\\\"", "\"");
                    }
                    try {
                        JSONObject o = new JSONObject(v);
                        String hits = o.optString("hits", "");
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("\\\"msToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(hits);
                        if (m.find()) {
                            String token = m.group(1);
                            onMsTokenFound(token);
                            return;
                        }
                        m = java.util.regex.Pattern
                                .compile("msToken=([A-Za-z0-9_\\-%.]+)").matcher(hits);
                        if (m.find()) {
                            onMsTokenFound(m.group(1));
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "diag parse failed: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "grabMsToken failed: " + e.getMessage());
        }
    }

    private void onMsTokenFound(String token) {
        if (cookieReady || token == null || token.length() < 8) return;
        msToken = token;
        cookieReady = true;
        cookieTries = 0;
        Log.d(TAG, "msToken found: " + token.substring(0, Math.min(16, token.length())) + "...");
        try {
            cookieChain = CookieManager.getInstance().getCookie("https://www.douyin.com");
            if (cookieChain == null) cookieChain = "";
        } catch (Exception ignored) {
        }
        Log.d(TAG, "cookie chain: " + cookieChain);
        generateAndFetchFirst();
    }

    /** 轮询 CookieManager，直到出现 msToken（或超时回退）。msToken cookie 在 bytedance.com 域。 */
    private void checkCookies() {
        if (cookieReady) return;
        String c = null;
        String b = null;
        try {
            c = CookieManager.getInstance().getCookie("https://www.douyin.com");
            b = CookieManager.getInstance().getCookie("https://mssdk.bytedance.com");
        } catch (Exception ignored) {
        }
        if (b != null && b.contains("msToken")) {
            cookieChain = (c == null ? "" : c) + "; " + b;
            msToken = extractCookieValue(b, "msToken");
            cookieReady = true;
            Log.d(TAG, "cookie ready, msToken=" + msToken.substring(0, Math.min(16, msToken.length())) + "...");
            Log.d(TAG, "cookie chain (douyin): " + (c == null ? "" : c));
            Log.d(TAG, "cookie chain (bytedance): " + b);
            generateAndFetchFirst();
            return;
        }
        cookieTries++;
        if (cookieTries >= COOKIE_MAX_TRIES) {
            Log.w(TAG, "cookie timeout, fallback to legacy api");
            fallbackToLegacy();
            return;
        }
        Log.d(TAG, "waiting cookie... try=" + cookieTries
                + " douyin=" + (c == null ? "null" : (c.contains("msToken") ? "hasMsToken" : "noMsToken"))
                + " bytedance=" + (b == null ? "null" : (b.contains("msToken") ? "hasMsToken" : "noMsToken")));
        handler.postDelayed(checkCookiesRunnable, COOKIE_WAIT_MS);
    }

    private String extractCookieValue(String cookieLine, String key) {
        String[] parts = cookieLine.split(";");
        for (String p : parts) {
            p = p.trim();
            if (p.startsWith(key + "=")) {
                return p.substring(key.length() + 1);
            }
        }
        return "";
    }

    /** 在 WebView 里执行签名 SDK 并调用 getABogus，然后请求第一批。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void generateAndFetchFirst() {
        final String sdk = readAsset("abogus_dy.js");
        if (sdk == null) {
            Log.w(TAG, "abogus_dy.js missing, fallback");
            fallbackToLegacy();
            return;
        }
        final String query = buildWebQuery(0, 18) + "&msToken=" + msToken;
        String js = sdk + ";getABogus('" + query + "','')";
        try {
            sessionWebView.evaluateJavascript(js, new ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    Log.d(TAG, "bogus result head: " + (value != null ? value.substring(0, Math.min(80, value.length())) : "null"));
                    String sig = parseBogusValue(value);
                    if (sig == null) {
                        Log.w(TAG, "bogus empty, fallback");
                        fallbackToLegacy();
                        return;
                    }
                    sessionMode = true;
                    fetchWebFeed(query, sig, 0, false);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "evaluateJavascript failed: " + e.getMessage());
            fallbackToLegacy();
        }
    }

    /** 解析 getABogus 返回的 JSON：{ok:true,value:"..."} */
    private String parseBogusValue(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String s = raw.trim();
        // evaluateJavascript 返回带引号的 JSON 字符串，去两端引号
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        s = s.replace("\\\"", "\"").replace("\\\\", "\\");
        try {
            JSONObject o = new JSONObject(s);
            if (o.optBoolean("ok", false)) {
                return o.optString("value", null);
            }
            Log.w(TAG, "bogus not ok: " + s);
        } catch (Exception e) {
            Log.w(TAG, "bogus parse failed: " + e.getMessage() + " raw=" + s);
        }
        return null;
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "read asset failed: " + e.getMessage());
            return null;
        }
    }

    /* ================= 网页版 feed 请求（签名链路） ================= */

    private String buildWebQuery(long cursor, int count) {
        return "device_platform=webapp&aid=6383&channel=channel_pc_web&pc_client_type=1"
                + "&version_code=170400&version_name=17.4.0&cookie_enabled=true"
                + "&screen_width=1920&screen_height=1080&browser_language=zh-CN&browser_platform=Win32"
                + "&browser_name=Chrome&browser_version=147.0.0.0&browser_online=true"
                + "&engine_name=Blink&engine_version=147.0.0.0&os_name=Windows&os_version=10"
                + "&cpu_core_num=8&device_memory=8&platform=PC&downlink=10&effective_type=4g"
                + "&round_trip_time=50&max_cursor=" + cursor + "&count=" + count;
    }

    private void fetchWebFeed(final String query, final String sig, final long cursor, final boolean isMore) {
        new Thread(() -> {
            final String url = FEED_API_WEB + query + "&a_bogus=" + encode(sig);
            String json = httpGet(url, true);
            if (json == null) {
                handler.post(() -> {
                    if (isMore) return;
                    fallbackToLegacy();
                });
                return;
            }
            final List<VideoItem> list = parseFeed(json);
            final long newCursor = extractMaxCursor(json);
            handler.post(() -> {
                if (list.isEmpty()) {
                    Log.w(TAG, "web feed empty, fallback");
                    if (!isMore) fallbackToLegacy();
                    return;
                }
                maxCursor = newCursor;
                hasCursor = newCursor > 0;
                if (!isMore) {
                    items.clear();
                    seenIds.clear();
                }
                int added = 0;
                for (VideoItem it : list) {
                    if (!it.awemeId.isEmpty() && seenIds.contains(it.awemeId)) continue;
                    seenIds.add(it.awemeId);
                    items.add(it);
                    added++;
                }
                Log.d(TAG, (isMore ? "web more added=" : "web feed loaded ") + added
                        + " total=" + items.size() + " cursor=" + newCursor);
                if (!isMore) playVideo(0);
            });
        }).start();
    }

    private long extractMaxCursor(String json) {
        try {
            JSONObject root = new JSONObject(json);
            long c = root.optLong("max_cursor", 0);
            return c;
        } catch (Exception e) {
            return 0;
        }
    }

    private String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /* ================= 回退：v10.6 无签名车机版接口 ================= */

    private void fallbackToLegacy() {
        if (fetchingMore || currentIndex >= 0) {
            // 已在播放，不打断，后续 fetchMore 自动用 legacy
            sessionMode = false;
            return;
        }
        sessionMode = false;
        fetchFeed();
    }

    private String nextApiUrl() {
        apiToggle ^= 1;
        return apiToggle == 0 ? FEED_API_EXT : FEED_API_MAIN;
    }

    private void fetchFeed() {
        loadingPanel.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String json = httpGet(nextApiUrl(), false);
            final List<VideoItem> list = parseFeed(json);
            handler.post(() -> {
                if (list.isEmpty()) {
                    fetchRetries++;
                    String reason = json == null
                            ? ("网络异常：" + lastError)
                            : ("接口返回数据为空（可能被限流），重试 " + fetchRetries + "/" + MAX_FETCH_RETRIES);
                    Log.w(TAG, "empty feed: " + reason);
                    Toast.makeText(this, reason, Toast.LENGTH_SHORT).show();
                    if (fetchRetries >= MAX_FETCH_RETRIES) {
                        Toast.makeText(this, "多次失败，请检查网络后重试", Toast.LENGTH_LONG).show();
                        loadingPanel.setVisibility(View.GONE);
                        return;
                    }
                    handler.postDelayed(this::fetchFeed, 3000L);
                    return;
                }
                fetchRetries = 0;
                items.clear();
                seenIds.clear();
                for (VideoItem it : list) {
                    if (!it.awemeId.isEmpty()) seenIds.add(it.awemeId);
                    items.add(it);
                }
                Log.d(TAG, "legacy feed loaded, items=" + items.size());
                playVideo(0);
            });
        }).start();
    }

    private void fetchMore() {
        if (fetchingMore) return;
        fetchingMore = true;
        if (sessionMode && cookieReady) {
            // 签名链路翻页：带真实 max_cursor
            final String query = buildWebQuery(hasCursor ? maxCursor : 0, 18) + "&msToken=" + msToken;
            final String sdk = readAsset("abogus_dy.js");
            if (sdk != null) {
                String js = sdk + ";getABogus('" + query + "','')";
                try {
                    sessionWebView.evaluateJavascript(js, new ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            String sig = parseBogusValue(value);
                            if (sig == null) {
                                fetchingMore = false;
                                fetchMoreLegacy();
                                return;
                            }
                            fetchWebFeed(query, sig, hasCursor ? maxCursor : 0, true);
                        }
                    });
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        fetchMoreLegacy();
    }

    private void fetchMoreLegacy() {
        new Thread(() -> {
            String json = httpGet(nextApiUrl(), false);
            final List<VideoItem> add = parseFeed(json);
            handler.post(() -> {
                fetchingMore = false;
                if (add.isEmpty()) {
                    Log.w(TAG, "fetchMore got empty, list stays " + items.size());
                    return;
                }
                int before = items.size();
                for (VideoItem it : add) {
                    if (!it.awemeId.isEmpty() && seenIds.contains(it.awemeId)) continue;
                    seenIds.add(it.awemeId);
                    items.add(it);
                }
                Log.d(TAG, "legacy fetchMore added=" + (items.size() - before) + " total=" + items.size());
            });
        }).start();
    }

    private String httpGet(String urlStr, boolean withCookie) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", PC_UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Referer", "https://www.douyin.com/");
            if (withCookie && cookieChain != null && !cookieChain.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieChain);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                lastError = "HTTP " + code;
                Log.w(TAG, "http " + code + " url=" + urlStr.substring(0, Math.min(120, urlStr.length())));
                return null;
            }
            String contentEncoding = conn.getHeaderField("Content-Encoding");
            InputStream in = conn.getInputStream();
            if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip")) {
                in = new java.util.zip.GZIPInputStream(in);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            if (lastError.length() > 120) lastError = lastError.substring(0, 120);
            Log.w(TAG, "http error: " + lastError);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /* ================= 解析与播放（与 v10.6 相同） ================= */

    private List<VideoItem> parseFeed(String json) {
        List<VideoItem> list = new ArrayList<>();
        if (json == null) return list;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("aweme_list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject aweme = arr.optJSONObject(i);
                    if (aweme == null) continue;
                    JSONObject play = aweme.optJSONObject("play_addr");
                    if (play == null) continue;
                    JSONArray urls = play.optJSONArray("url_list");
                    if (urls == null || urls.length() == 0) continue;
                    String u = urls.optString(0);
                    if (u.isEmpty()) continue;
                    VideoItem it = new VideoItem();
                    it.url = u;
                    it.title = aweme.optString("desc", "");
                    JSONObject author = aweme.optJSONObject("author");
                    it.author = author == null ? "" : author.optString("nickname", "");
                    it.awemeId = aweme.optString("aweme_id", "");
                    list.add(it);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parse feed failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (list.isEmpty()) {
            try {
                java.util.regex.Pattern idPat = java.util.regex.Pattern.compile(
                        "\"aweme_id\"\\s*:\\s*\"(\\d+)\"");
                java.util.regex.Pattern urlPat = java.util.regex.Pattern.compile(
                        "\"play_addr\"\\s*:\\s*\\{.*?\"url_list\"\\s*:\\s*\\[\"([^\"]+)\"",
                        java.util.regex.Pattern.DOTALL);
                java.util.regex.Pattern titlePat = java.util.regex.Pattern.compile(
                        "\"desc\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
                java.util.regex.Pattern authorPat = java.util.regex.Pattern.compile(
                        "\"nickname\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
                java.util.regex.Matcher am = idPat.matcher(json);
                int guard = 0;
                while (am.find() && guard < 200) {
                    String id = am.group(1);
                    int segEnd = json.indexOf("\"aweme_id\"", am.end());
                    if (segEnd < 0) segEnd = json.length();
                    String seg = json.substring(am.end(), segEnd);
                    java.util.regex.Matcher um = urlPat.matcher(seg);
                    if (um.find()) {
                        String u = um.group(1);
                        if (u != null && u.startsWith("http")) {
                            VideoItem it = new VideoItem();
                            it.url = u;
                            it.awemeId = id;
                            java.util.regex.Matcher tm = titlePat.matcher(seg);
                            if (tm.find()) {
                                it.title = tm.group(1).replace("\\u0026", "&").replace("\\/", "/").replace("\\\\", "\\");
                                if (it.title.length() > 60) it.title = it.title.substring(0, 60);
                            }
                            java.util.regex.Matcher au = authorPat.matcher(seg);
                            if (au.find()) it.author = au.group(1);
                            list.add(it);
                        }
                    }
                    guard++;
                }
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private void playVideo(int index) {
        if (items.isEmpty() || index < 0 || index >= items.size()) return;
        currentIndex = index;
        VideoItem it = items.get(index);
        Log.d(TAG, "play " + index + "/" + items.size() + " " + it.awemeId);
        titleText.setText(it.title.isEmpty() ? "抖音推荐" : it.title);
        subText.setText((it.author.isEmpty() ? "抖音推荐" : "@" + it.author)
                + "    " + (index + 1) + "/" + items.size());
        loadingPanel.setVisibility(View.VISIBLE);
        videoView.setVideoURI(Uri.parse(it.url));
        showInfoBar();
        if (index >= items.size() - 2) fetchMore();
    }

    private void nextVideo() {
        if (currentIndex + 1 < items.size()) {
            playVideo(currentIndex + 1);
        } else {
            Toast.makeText(this, "加载更多视频…", Toast.LENGTH_SHORT).show();
            fetchMore();
        }
    }

    private void prevVideo() {
        if (currentIndex > 0) playVideo(currentIndex - 1);
    }

    private void togglePlay() {
        if (videoView.isPlaying()) {
            videoView.pause();
            userPaused = true;
        } else {
            videoView.start();
            userPaused = false;
            showInfoBar();
        }
    }

    private void seekBySeconds(int seconds) {
        try {
            int pos = videoView.getCurrentPosition() + seconds * 1000;
            if (pos < 0) pos = 0;
            int dur = videoView.getDuration();
            if (dur > 0 && pos > dur) pos = dur - 500;
            videoView.seekTo(pos);
        } catch (Exception ignored) {
        }
        showInfoBar();
    }

    private void startProgressLoop() {
        handler.removeCallbacks(progressLoop);
        handler.post(progressLoop);
    }

    private final Runnable progressLoop = new Runnable() {
        @Override public void run() {
            try {
                int dur = videoView.getDuration();
                int pos = videoView.getCurrentPosition();
                if (dur > 0) {
                    playProgress.setProgress(Math.max(0, Math.min(1000, pos * 1000 / dur)));
                    timeText.setText(formatTime(pos) + " / " + formatTime(dur));
                }
            } catch (Exception ignored) {
            }
            if (videoView.isPlaying()) handler.postDelayed(this, 500L);
        }
    };

    private String formatTime(int ms) {
        int totalSec = Math.max(0, ms / 1000);
        int m = totalSec / 60;
        int s = totalSec % 60;
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    private void showInfoBar() {
        infoBar.setVisibility(View.VISIBLE);
        progressBarView.setVisibility(View.VISIBLE);
        infoBarVisible = true;
        handler.removeCallbacks(hideInfoBar);
        handler.postDelayed(hideInfoBar, INFO_BAR_HIDE_MS);
    }

    private final Runnable hideInfoBar = new Runnable() {
        @Override public void run() {
            infoBar.setVisibility(View.GONE);
            progressBarView.setVisibility(View.GONE);
            infoBarVisible = false;
        }
    };

    @Override public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && isHandledRemoteKey(event.getKeyCode())) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: prevVideo(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT: nextVideo(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: seekBySeconds(-10); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: seekBySeconds(10); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: togglePlay(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                videoView.start(); userPaused = false; showInfoBar(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                videoView.pause(); userPaused = true; return true;
            case KeyEvent.KEYCODE_BACK: return handleBack();
            default: return super.dispatchKeyEvent(event);
        }
    }

    private boolean isHandledRemoteKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    private boolean handleBack() {
        if (infoBarVisible) {
            hideInfoBar.run();
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < BACK_PRESS_EXIT_MS) {
            finish();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "再按一次返回退出", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (videoView != null && !userPaused && currentIndex >= 0 && !videoView.isPlaying()) {
            videoView.start();
        }
    }

    @Override protected void onPause() {
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (videoView != null) videoView.stopPlayback();
        if (sessionWebView != null) {
            sessionWebView.stopLoading();
            sessionWebView.destroy();
        }
        super.onDestroy();
    }

    private static final class VideoItem {
        String url = "";
        String title = "";
        String author = "";
        String awemeId = "";
    }
}
