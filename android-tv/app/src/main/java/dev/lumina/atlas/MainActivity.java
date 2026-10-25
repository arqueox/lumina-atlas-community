package dev.lumina.atlas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URI;

public class MainActivity extends Activity {
    private static final int FILE_PICKER_REQUEST = 41;
    private static final String PREFS = "lumina_atlas_tv";
    private static final String SERVER_KEY = "server_url";
    private LinearLayout root;
    private WebView web;
    private View fullScreenView;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private ValueCallback<Uri[]> fileCallback;
    private String serverUrl;

    // Noir: the web app's palette, type and square shapes (styles.css), so setup and app look like one product.
    private static final int BG = Color.rgb(5, 5, 5);
    private static final int PANEL = Color.rgb(10, 10, 9);
    private static final int RAISED = Color.rgb(21, 21, 19);
    private static final int TEXT = Color.rgb(242, 242, 238);
    private static final int SOFT = Color.rgb(200, 200, 194);
    private static final int MUTED = Color.rgb(154, 154, 148);
    private static final int RULE = Color.rgb(106, 106, 102);
    private static final int LINE = Color.rgb(42, 42, 39);
    private static final int OUTLINE = Color.rgb(74, 74, 70);
    private static final int ACCENT = Color.rgb(255, 230, 0);
    private static final int ERROR = Color.rgb(255, 90, 79);
    private Typeface display = Typeface.DEFAULT_BOLD;
    private Typeface mono = Typeface.MONOSPACE;
    private Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                display = getResources().getFont(R.font.anton);
                mono = getResources().getFont(R.font.jetbrains_mono);
                monoBold = Build.VERSION.SDK_INT >= 28 ? Typeface.create(mono, 700, false) : Typeface.create(mono, Typeface.BOLD);
            } catch (Exception ignored) {}
        }
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);
        serverUrl = getPreferences(MODE_PRIVATE).getString(SERVER_KEY, "");
        if (serverUrl.isEmpty()) showSetup(); else openServer();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, float size, int color, Typeface face, float spacing) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setLetterSpacing(spacing);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable box(int fill, int stroke, int strokeDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        if (strokeDp > 0) shape.setStroke(dp(strokeDp), stroke);
        return shape;
    }

    /** A square button: accent fill for the main action, an outline otherwise; focus is always a thick accent edge. */
    private TextView button(String label, boolean primary, Runnable action) {
        TextView button = text(label.toUpperCase(), 13, primary ? BG : TEXT, primary ? monoBold : mono, 0.24f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(26), 0, dp(26), 0);
        button.setFocusable(true);
        button.setClickable(true);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                primary ? box(ACCENT, TEXT, 3) : box(RAISED, ACCENT, 3));
        states.addState(new int[]{}, primary ? box(ACCENT, ACCENT, 1) : box(BG, OUTLINE, 1));
        button.setBackground(states);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams wrap(int heightDp, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(heightDp));
        params.topMargin = dp(topDp);
        return params;
    }

    /** "00 —— LIGAÇÃO AO SERVIDOR", as the web app's kickers. */
    private LinearLayout kicker(String index, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(index, 12, ACCENT, monoBold, 0.3f));
        View rule = new View(this);
        rule.setBackgroundColor(RULE);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(dp(40), dp(1));
        ruleParams.setMargins(dp(14), 0, dp(14), 0);
        row.addView(rule, ruleParams);
        row.addView(text(label.toUpperCase(), 12, SOFT, mono, 0.3f));
        return row;
    }

    /** The Lumina Atlas "A". */
    private View mark() {
        return new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                float s = getWidth() / 44f;
                Path a = new Path();
                a.moveTo(22 * s, 2 * s); a.lineTo(42 * s, 38 * s); a.lineTo(31 * s, 38 * s);
                a.lineTo(22 * s, 21 * s); a.lineTo(13 * s, 38 * s); a.lineTo(2 * s, 38 * s); a.close();
                paint.setColor(ACCENT);
                canvas.drawPath(a, paint);
                paint.setColor(BG);
                canvas.drawRect(17 * s, 30 * s, 27 * s, 33 * s, paint);
            }
        };
    }

    private void showSetup() {
        root.removeAllViews();
        if (web != null) { web.destroy(); web = null; }
        root.setPadding(0, 0, 0, 0);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(screen, new LinearLayout.LayoutParams(-1, -1));

        // Rail with the mark, as in the app.
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(0, dp(30), 0, 0);
        rail.addView(mark(), new LinearLayout.LayoutParams(dp(38), dp(35)));
        screen.addView(rail, new LinearLayout.LayoutParams(dp(88), -1));
        View edge = new View(this);
        edge.setBackgroundColor(Color.rgb(30, 30, 28));
        screen.addView(edge, new LinearLayout.LayoutParams(dp(1), -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        screen.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(56), dp(34), dp(56), dp(28));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        body.addView(kicker("00", "Ligação ao servidor"));
        TextView title = text("LUMINA ATLAS", 60, ACCENT, display, 0f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.topMargin = dp(16);
        body.addView(title, titleParams);
        TextView lede = text("Indica o endereço do teu servidor Lumina Atlas. O fornecedor IPTV configura-se depois, dentro da app, e fica guardado no servidor.", 14, SOFT, mono, 0.02f);
        lede.setLineSpacing(0, 1.35f);
        lede.setMaxWidth(dp(620));
        LinearLayout.LayoutParams ledeParams = new LinearLayout.LayoutParams(-2, -2);
        ledeParams.topMargin = dp(14);
        body.addView(lede, ledeParams);

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.topMargin = dp(26);
        body.addView(text("ENDEREÇO DO SERVIDOR", 11, MUTED, mono, 0.3f), labelParams);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(serverUrl);
        input.setHint("https://atlas.exemplo.pt");
        input.setTextColor(TEXT);
        input.setHintTextColor(RULE);
        input.setTextSize(17);
        input.setTypeface(mono);
        input.setPadding(dp(18), 0, dp(18), 0);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        StateListDrawable field = new StateListDrawable();
        field.addState(new int[]{android.R.attr.state_focused}, box(PANEL, ACCENT, 2));
        field.addState(new int[]{}, box(PANEL, OUTLINE, 1));
        input.setBackground(field);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, dp(56));
        inputParams.topMargin = dp(10);
        body.addView(input, inputParams);

        TextView error = text("", 12, ERROR, mono, 0.04f);
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(-2, -2);
        errorParams.topMargin = dp(10);
        body.addView(error, errorParams);

        Runnable open = () -> {
            String candidate = input.getText().toString().trim();
            if (!candidate.matches("(?i)^https?://.*")) candidate = "https://" + candidate;
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
                error.setText("Endereço inválido. Usa só o servidor, por exemplo https://atlas.exemplo.pt, sem caminho nem credenciais.");
                error.setVisibility(View.VISIBLE);
                input.requestFocus();
            }
        };
        input.setOnEditorActionListener((v, actionId, event) -> { open.run(); return true; });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-2, -2);
        actionsParams.topMargin = dp(22);
        body.addView(actions, actionsParams);
        actions.addView(button("Ligar", true, open), wrap(52, 0));
        LinearLayout.LayoutParams second = wrap(52, 0);
        second.leftMargin = dp(12);
        actions.addView(button("Botão Home", false, () -> openSettings(Settings.ACTION_HOME_SETTINGS)), second);
        LinearLayout.LayoutParams third = wrap(52, 0);
        third.leftMargin = dp(12);
        actions.addView(button("Acessibilidade", false, () -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)), third);

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(26);
        body.addView(divider, dividerParams);
        TextView help = text("MENU volta a este ecrã · BOTÃO HOME escolhe esta app como ecrã inicial, se o Google TV o permitir.", 11, MUTED, mono, 0.08f);
        help.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(-2, -2);
        helpParams.topMargin = dp(14);
        body.addView(help, helpParams);
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
        web.setBackgroundColor(BG);
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
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_PICKER_REQUEST); }
                catch (Exception ex) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "Não há gestor de ficheiros disponível", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
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

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override protected void onDestroy() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
