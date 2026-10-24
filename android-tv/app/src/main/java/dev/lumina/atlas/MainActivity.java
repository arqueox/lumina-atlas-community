package dev.lumina.atlas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URI;

public class MainActivity extends Activity {
    private static final String PREFS = "lumina_atlas_tv";
    private static final String SERVER_KEY = "server_url";
    private LinearLayout root;
    private WebView web;
    private View fullScreenView;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private String serverUrl;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(14, 15, 12));
        setContentView(root);
        serverUrl = getPreferences(MODE_PRIVATE).getString(SERVER_KEY, "");
        if (serverUrl.isEmpty()) showSetup(); else openServer();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(237, 238, 230));
        view.setPadding(0, dp(10), 0, dp(10));
        return view;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void showSetup() {
        root.removeAllViews();
        if (web != null) { web.destroy(); web = null; }
        root.setPadding(dp(56), dp(30), dp(56), dp(30));
        root.addView(text("Lumina Atlas · Google TV", 28));
        root.addView(text("Introduz o endereço do teu servidor Lumina Atlas. As credenciais IPTV ficam no servidor.", 17));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(serverUrl);
        input.setHint("https://atlas.exemplo.pt");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        root.addView(button("Abrir Lumina Atlas", () -> {
            String candidate = input.getText().toString().trim();
            try {
                URI uri = new URI(candidate);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || (uri.getPath() != null && !uri.getPath().replace("/", "").isEmpty())) {
                    throw new IllegalArgumentException();
                }
                serverUrl = candidate.replaceAll("/+$", "");
                getPreferences(MODE_PRIVATE).edit().putString(SERVER_KEY, serverUrl).apply();
                openServer();
            } catch (Exception ex) {
                Toast.makeText(this, "Usa um endereço http(s) sem caminho ou credenciais", Toast.LENGTH_LONG).show();
            }
        }));
        root.addView(button("Escolher app do botão Home", () -> openSettings(Settings.ACTION_HOME_SETTINGS)));
        root.addView(button("Definições de acessibilidade", () -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(text("Home: seleciona esta app como ecrã inicial, se o dispositivo oferecer essa opção. Prima Menu para voltar a esta configuração.", 14));
        input.requestFocus();
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (Exception ex) { Toast.makeText(this, "Esta opção não está disponível neste dispositivo", Toast.LENGTH_LONG).show(); }
    }

    private void openServer() {
        root.removeAllViews();
        root.setPadding(0, 0, 0, 0);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(14, 15, 12));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                URI target;
                try { target = new URI(request.getUrl().toString()); }
                catch (Exception ex) { return true; }
                URI configured = URI.create(serverUrl);
                if (target.getHost() != null
                        && target.getHost().equalsIgnoreCase(configured.getHost())
                        && target.getScheme().equalsIgnoreCase(configured.getScheme())
                        && target.getPort() == configured.getPort()) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl())); }
                catch (Exception ex) { Toast.makeText(MainActivity.this, "Não foi possível abrir a ligação", Toast.LENGTH_SHORT).show(); }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullScreenView != null) { callback.onCustomViewHidden(); return; }
                fullScreenView = view;
                fullScreenCallback = callback;
                web.setVisibility(View.GONE);
                root.addView(view, new LinearLayout.LayoutParams(-1, -1));
            }
            @Override public void onHideCustomView() {
                if (fullScreenView == null) return;
                root.removeView(fullScreenView);
                fullScreenView = null;
                web.setVisibility(View.VISIBLE);
                if (fullScreenCallback != null) fullScreenCallback.onCustomViewHidden();
                fullScreenCallback = null;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, -1));
        web.loadUrl(serverUrl + "/");
        web.requestFocus();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showSetup();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (fullScreenView != null) { web.getWebChromeClient().onHideCustomView(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        if (web != null) { showSetup(); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
