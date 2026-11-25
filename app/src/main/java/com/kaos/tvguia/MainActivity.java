package com.kaos.tvguia;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * TV Guia en el TV box: envuelve la MISMA interfaz web del proyecto (index.html + app.js +
 * hls.js) servida desde los assets del APK.
 *
 * La web pide tres cosas al servidor Python. Aca se resuelven dentro de la app:
 *   /api/channels y /api/health  -> los JSON empaquetados en assets/web/
 *   /proxy?u=&r=&a=              -> descarga con User-Agent/Referer y, si es una playlist HLS,
 *                                   reescribe cada URI para que tambien pase por el proxy
 *                                   (igual que server.py; sin eso los segmentos fallan por CORS)
 */
public class MainActivity extends Activity {

    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String HOME = ORIGIN + "/assets/web/index.html";
    private static final String UA_DEFECTO =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Safari/537.36";

    private WebView web;
    private WebViewAssetLoader assets;
    private FrameLayout root;
    private View pantallaCompleta;
    private WebChromeClient.CustomViewCallback volverDePantalla;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        inmersivo();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b0e14"));
        setContentView(root);

        assets = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setBackgroundColor(Color.parseColor("#0b0e14"));
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            // sin esto Android dibuja un recuadro azul intermitente alrededor del WebView
            web.setDefaultFocusHighlightEnabled(false);
        }
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setWebViewClient(new Cliente());
        web.setWebChromeClient(new Cromo());
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web.loadUrl(HOME);
    }

    private void inmersivo() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean tieneFoco) {
        super.onWindowFocusChanged(tieneFoco);
        if (tieneFoco) inmersivo();
    }

    /* --------------------------------------------------------------- cliente web */

    private class Cliente extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView vista, WebResourceRequest pedido) {
            Uri u = pedido.getUrl();
            if (!ORIGIN.equals(u.getScheme() + "://" + u.getHost())) {
                return null; // streams, logos y CDNs: los maneja el WebView
            }
            String ruta = u.getPath() == null ? "" : u.getPath();
            try {
                if (ruta.startsWith("/assets/")) {
                    return assets.shouldInterceptRequest(u);
                }
                if ("/proxy".equals(ruta) || "/logo".equals(ruta)) {
                    return proxy(u);
                }
                switch (ruta) {
                    case "/api/channels":
                        return canales();
                    case "/api/health":
                        return asset("web/status.json");
                    case "/api/epg":
                        return json("{\"channels\":{}}");
                    case "/api/apps":
                        return json("[]");
                    case "/api/status":
                        return json("{\"ok\":true,\"vlc\":false,\"app\":true}");
                    case "/api/check":
                        return json("{\"ok\":false,\"msg\":\"La verificacion corre en la PC, no en la app\"}");
                    case "/api/info":
                    case "/api/config":
                        return json("{\"app\":\"TV Guia\"}");
                    case "/api/open":
                    case "/api/vlc":
                        return new WebResourceResponse("text/plain", "utf-8",
                                new ByteArrayInputStream(new byte[0]));
                    default:
                        return null;
                }
            } catch (Exception e) {
                return texto(500, "error: " + e);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView vista, WebResourceRequest pedido) {
            Uri u = pedido.getUrl();
            String origen = u.getScheme() + "://" + u.getHost();
            if (ORIGIN.equals(origen)) return false;
            // un enlace externo: lo dejamos en el WebView igual, para no sacar al usuario de la app
            return false;
        }

        @Override
        public void onPageFinished(WebView vista, String url) {
            // modo TV: flechas del control mueven el foco entre canales
            vista.evaluateJavascript(
                    "(function(){var s=document.createElement('script');"
                    + "s.src='" + ORIGIN + "/assets/web/tvmode.js?v=1';"
                    + "document.body.appendChild(s);})()", null);
        }
    }

    private class Cromo extends WebChromeClient {
        @Override
        public void onShowCustomView(View v, CustomViewCallback cb) {
            if (pantallaCompleta != null) {
                cb.onCustomViewHidden();
                return;
            }
            pantallaCompleta = v;
            volverDePantalla = cb;
            root.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
            inmersivo();
        }

        @Override
        public void onHideCustomView() {
            if (pantallaCompleta == null) return;
            root.removeView(pantallaCompleta);
            pantallaCompleta = null;
            web.setVisibility(View.VISIBLE);
            if (volverDePantalla != null) volverDePantalla.onCustomViewHidden();
            volverDePantalla = null;
            inmersivo();
        }
    }

    /* --------------------------------------------------------------- respuestas */

    private WebResourceResponse asset(String nombre) throws IOException {
        InputStream in = getAssets().open(nombre);
        return new WebResourceResponse("application/json", "utf-8", 200, "OK",
                cors(), in);
    }

    /**
     * Catalogo sin los canales que apuntan a 127.0.0.1 / localhost: esos son los del
     * "puente" de audio que corre en la PC y en el box nunca van a andar.
     */
    private WebResourceResponse canales() {
        try {
            byte[] crudo = leerTodo(getAssets().open("web/channels.json"));
            org.json.JSONObject raiz = new org.json.JSONObject(
                    new String(crudo, StandardCharsets.UTF_8));
            org.json.JSONArray lista = raiz.optJSONArray("channels");
            if (lista == null) return asset("web/channels.json");
            org.json.JSONArray filtrada = new org.json.JSONArray();
            int quitados = 0;
            for (int i = 0; i < lista.length(); i++) {
                org.json.JSONObject c = lista.optJSONObject(i);
                if (c == null) continue;
                String u = c.optString("url", "");
                if (u.contains("127.0.0.1") || u.contains("localhost")) {
                    quitados++;
                    continue;
                }
                filtrada.put(c);
            }
            raiz.put("channels", filtrada);
            Log.d("tvguia", "catalogo: " + filtrada.length() + " canales (" + quitados + " locales quitados)");
            return json(raiz.toString());
        } catch (Exception e) {
            Log.e("tvguia", "no pude filtrar el catalogo", e);
            try {
                return asset("web/channels.json");
            } catch (IOException io) {
                return json("{\"channels\":[]}");
            }
        }
    }

    private static byte[] leerTodo(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(5 * 1024 * 1024);
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private WebResourceResponse json(String cuerpo) {
        return new WebResourceResponse("application/json", "utf-8", 200, "OK", cors(),
                new ByteArrayInputStream(cuerpo.getBytes(StandardCharsets.UTF_8)));
    }

    private WebResourceResponse texto(int codigo, String cuerpo) {
        return new WebResourceResponse("text/plain", "utf-8", codigo,
                codigo == 200 ? "OK" : "Error",
                cors(), new ByteArrayInputStream(cuerpo.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> cors() {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        return h;
    }

    /* --------------------------------------------------------------- proxy */

    private WebResourceResponse proxy(Uri u) {
        String objetivo = u.getQueryParameter("u");
        if (objetivo == null || objetivo.isEmpty()) return texto(400, "falta u");
        Log.d("tvguia", "proxy -> " + objetivo);
        String ref = u.getQueryParameter("r");
        String agente = u.getQueryParameter("a");

        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(objetivo).openConnection();
            con.setInstanceFollowRedirects(true);
            con.setConnectTimeout(10000);
            con.setReadTimeout(20000);
            con.setRequestProperty("User-Agent",
                    agente != null && !agente.isEmpty() ? agente : UA_DEFECTO);
            con.setRequestProperty("Accept", "*/*");
            // sin esto el servidor puede mandar gzip y el Content-Length no coincide con
            // los bytes que entregamos (el WebView se queda esperando y la imagen no carga)
            con.setRequestProperty("Accept-Encoding", "identity");
            if (ref != null && !ref.isEmpty()) con.setRequestProperty("Referer", ref);

            int codigo = con.getResponseCode();
            Log.d("tvguia", "proxy <- " + codigo + " " + con.getContentType());
            String tipo = con.getContentType();
            if (tipo == null) tipo = "application/octet-stream";
            InputStream entrada = codigo >= 400 ? con.getErrorStream() : con.getInputStream();
            if (entrada == null) return texto(codigo, "");

            byte[] cabeza = leer(entrada, 8192);
            if (esPlaylist(cabeza, tipo)) {
                ByteArrayOutputStream todo = new ByteArrayOutputStream();
                todo.write(cabeza);
                copiar(entrada, todo);
                String texto = new String(todo.toByteArray(), StandardCharsets.UTF_8);
                String base = objetivo.substring(0, objetivo.lastIndexOf('/') + 1);
                String reescrito = reescribir(texto, base, ref, agente);
                Map<String, String> h = cors();
                return new WebResourceResponse("application/vnd.apple.mpegurl", "utf-8",
                        200, "OK", h,
                        new ByteArrayInputStream(reescrito.getBytes(StandardCharsets.UTF_8)));
            }

            Map<String, String> h = cors();
            InputStream resto = new SequenceInputStream(
                    new ByteArrayInputStream(cabeza), entrada);
            return new WebResourceResponse(tipo, null, codigo,
                    con.getResponseMessage() == null ? "OK" : con.getResponseMessage(), h, resto);
        } catch (Exception e) {
            if (con != null) con.disconnect();
            Log.e("tvguia", "proxy fallo: " + objetivo, e);
            return texto(502, "error upstream: " + e.getMessage());
        }
    }

    private static boolean esPlaylist(byte[] cabeza, String tipo) {
        if (tipo != null && tipo.toLowerCase().contains("mpegurl")) return true;
        String ini = new String(cabeza, StandardCharsets.UTF_8).trim();
        return ini.startsWith("#EXTM3U");
    }

    /** Igual que server.py: cada URI de la playlist vuelve a entrar por /proxy (playlist y segmentos). */
    private String reescribir(String playlist, String base, String ref, String agente) {
        StringBuilder salida = new StringBuilder(playlist.length() + 512);
        for (String cruda : playlist.split("\n")) {
            String linea = cruda.trim();
            if (linea.isEmpty()) continue;
            if (linea.startsWith("#")) {
                int i = linea.indexOf("URI=\"");
                if (i >= 0) {
                    String pre = linea.substring(0, i + 5);
                    String resto = linea.substring(i + 5);
                    int fin = resto.indexOf('"');
                    if (fin > 0) {
                        String uri = resto.substring(0, fin);
                        String post = resto.substring(fin);
                        linea = pre + proxificar(absoluta(base, uri), ref, agente) + post;
                    }
                }
                salida.append(linea).append('\n');
            } else {
                salida.append(proxificar(absoluta(base, linea), ref, agente)).append('\n');
            }
        }
        return salida.toString();
    }

    private static String absoluta(String base, String uri) {
        try {
            return new URI(base).resolve(uri).toString();
        } catch (Exception e) {
            return uri;
        }
    }

    private static String proxificar(String url, String ref, String agente) {
        StringBuilder sb = new StringBuilder("/proxy?u=").append(enc(url));
        if (ref != null && !ref.isEmpty()) sb.append("&r=").append(enc(ref));
        if (agente != null && !agente.isEmpty()) sb.append("&a=").append(enc(agente));
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static byte[] leer(InputStream in, int maximo) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (out.size() < maximo) {
            int n = in.read(buf, 0, Math.min(buf.length, maximo - out.size()));
            if (n < 0) break;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void copiar(InputStream in, ByteArrayOutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /* --------------------------------------------------------------- teclas */

    @Override
    public boolean onKeyDown(int tecla, KeyEvent evento) {
        if (tecla == KeyEvent.KEYCODE_BACK) {
            if (pantallaCompleta != null) {
                ((Cromo) web.getWebChromeClient()).onHideCustomView();
                return true;
            }
            // 1) si el buscador tiene el foco, la primera vez solo cierra el teclado
            // 2) si el reproductor esta abierto, lo cierra
            // 3) si no, sale de la app
            web.evaluateJavascript(
                    "(function(){"
                    + "var q=document.getElementById('q');"
                    + "if(q&&document.activeElement===q){q.blur();return 'teclado';}"
                    + "var p=document.getElementById('player');"
                    + "if(p&&!p.classList.contains('hidden')){"
                    + "var b=document.getElementById('btnClose');if(b){b.click();}return 'cerrado';}"
                    + "return 'salir';})()",
                    valor -> {
                        if (valor != null && valor.contains("salir")) finish();
                    });
            return true;
        }
        return super.onKeyDown(tecla, evento);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
