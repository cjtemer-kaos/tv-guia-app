package com.kaos.tvguia;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Catalogo de canales que baja de GitHub (repo tv-guia-data) y adapta al formato que espera
 * la interfaz web.
 *
 * El catalogo publico trae {version, count, vivos, channels:[{n,name,g,c,lang,ok,logo,url}]}
 * pero la web necesita {id,name,logo,group,url,c,l,k,num,lang,lang_label} + groups + facets.
 * La conversion se hace una vez por descarga y se guarda lista en disco, asi servirla es
 * solo leer un archivo.
 */
public class Catalogo {

    static final String URL_CATALOGO =
            "https://raw.githubusercontent.com/cjtemer-kaos/tv-guia-data/main/public/catalog.json";
    static final long VENCE_MS = 12L * 60 * 60 * 1000; // 12 h, igual que la app anterior

    /** Grupo canonico -> codigo de categoria (para que el filtro "categorias" siga sirviendo). */
    private static final String[][] GRUPOS = {
            {"Deportes", "deportes"}, {"Cine", "cine"}, {"Series", "series"},
            {"Infantil", "infantil"}, {"Documentales", "documentales"}, {"Música", "musica"},
            {"Noticias", "noticias"}, {"Religión", "religion"}, {"Cultura", "cultura"},
            {"Estilo de vida", "estilo-de-vida"}, {"Negocios", "negocios"},
            {"Entretenimiento", "entretenimiento"}, {"Generalistas", "generalistas"},
            {"Sin clasificar", "sin-clasificar"},
    };

    private final Context contexto;
    private final File fDatos;
    private final File fSalud;
    private final File fMeta;
    private final AtomicBoolean bajando = new AtomicBoolean(false);
    private Runnable alTerminar;

    public Catalogo(Context contexto) {
        this.contexto = contexto.getApplicationContext();
        File dir = this.contexto.getFilesDir();
        fDatos = new File(dir, "catalogo-web.json");
        fSalud = new File(dir, "catalogo-salud.json");
        fMeta = new File(dir, "catalogo.meta");
    }

    public void alTerminar(Runnable r) {
        alTerminar = r;
    }

    public boolean hayDatos() {
        return fDatos.exists() && fDatos.length() > 1000;
    }

    public File datos() {
        return fDatos;
    }

    public File salud() {
        return fSalud;
    }

    /** "version|cuando|etag" */
    public String[] meta() {
        if (!fMeta.exists()) return new String[]{"", "0", ""};
        try {
            String t = new String(leer(fMeta), StandardCharsets.UTF_8).trim();
            String[] p = t.split("\\|", -1);
            if (p.length >= 3) return p;
        } catch (Exception e) {
            Log.w("tvguia", "meta ilegible", e);
        }
        return new String[]{"", "0", ""};
    }

    public long edadMs() {
        long cuando = Long.parseLong(meta()[1]);
        return cuando <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - cuando;
    }

    /** Baja el catalogo si no hay datos, si vencio o si se fuerza. Nunca bloquea. */
    public void quizasActualizar(final boolean forzar) {
        if (!forzar && hayDatos() && edadMs() < VENCE_MS) return;
        if (!bajando.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = bajarYConvertir();
                } catch (Exception e) {
                    Log.e("tvguia", "no pude actualizar el catalogo", e);
                } finally {
                    bajando.set(false);
                    Log.d("tvguia", "catalogo: actualizacion " + (ok ? "ok" : "sin cambios/error")
                            + " | version " + meta()[0]);
                    if (ok && alTerminar != null) alTerminar.run();
                }
            }
        }, "catalogo").start();
    }

    private boolean bajarYConvertir() throws Exception {
        String[] m = meta();
        String etag = m[2];

        HttpURLConnection con = (HttpURLConnection) new URL(URL_CATALOGO).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("Accept-Encoding", "identity");
        if (etag != null && !etag.isEmpty()) con.setRequestProperty("If-None-Match", etag);
        int codigo = con.getResponseCode();
        if (codigo == 304) {
            guardarMeta(m[0], con.getHeaderField("ETag"));
            return false;
        }
        if (codigo != 200) {
            Log.w("tvguia", "catalogo: HTTP " + codigo);
            return false;
        }
        String nuevoEtag = con.getHeaderField("ETag");
        byte[] crudo = leer(con.getInputStream());
        JSONObject raiz = new JSONObject(new String(crudo, StandardCharsets.UTF_8));
        JSONArray canales = raiz.optJSONArray("channels");
        if (canales == null || canales.length() < 500) {
            Log.w("tvguia", "catalogo: muy chico (" + (canales == null ? 0 : canales.length()) + ")");
            return false;
        }
        String version = raiz.optString("version", "");

        Map<String, String> etiquetasIdioma = etiquetasIdioma();
        Map<String, String> etiquetasPais = etiquetasPais();
        JSONArray salida = new JSONArray();
        JSONObject saludCanales = new JSONObject();
        StringBuilder listaUrls = new StringBuilder(1536 * 1024);
        Map<String, Integer> grupos = new HashMap<>();
        int vivos = 0;

        for (int i = 0; i < canales.length(); i++) {
            JSONObject c = canales.optJSONObject(i);
            if (c == null) continue;
            String url = c.optString("url", "");
            if (url.isEmpty()) continue;
            String nombre = c.optString("name", "sin nombre");
            String grupo = normalizarGrupo(c.optString("g", "Sin clasificar"));
            String pais = c.optString("c", "");
            String idioma = c.optString("lang", "");

            JSONObject o = new JSONObject();
            o.put("id", url);
            o.put("name", nombre);
            o.put("logo", c.optString("logo", ""));
            o.put("group", grupo);
            o.put("g", grupo);
            o.put("referrer", "");   // el catalogo publico no los trae (regla anti-fuga)
            o.put("agent", "");
            o.put("origin", "tv-guia-data");
            o.put("url", url);
            o.put("c", pais);
            JSONArray idiomas = new JSONArray();
            if (!idioma.isEmpty()) idiomas.put(idioma);
            o.put("l", idiomas);
            JSONArray categorias = new JSONArray();
            String cat = codigoCategoria(grupo);
            if (cat != null) categorias.put(cat);
            o.put("k", categorias);
            o.put("num", c.optInt("n", i + 1));
            o.put("lang", idioma);
            o.put("lang_label", etiquetasIdioma.getOrDefault(idioma, idioma));
            salida.put(o);
            listaUrls.append(url).append('\n');

            Integer cuenta = grupos.get(grupo);
            grupos.put(grupo, cuenta == null ? 1 : cuenta + 1);

            Object ok = c.opt("ok");
            if (ok instanceof Boolean) {
                JSONObject est = new JSONObject();
                est.put("ok", ok);
                saludCanales.put(url, est);
                if ((Boolean) ok) vivos++;
            }
        }

        JSONObject payload = new JSONObject();
        payload.put("source", "tv-guia-data");
        payload.put("count", salida.length());
        payload.put("version", version);
        JSONObject jsGrupos = new JSONObject();
        for (Map.Entry<String, Integer> e : grupos.entrySet()) jsGrupos.put(e.getKey(), e.getValue());
        payload.put("groups", jsGrupos);
        payload.put("facets", facets(etiquetasPais, etiquetasIdioma));
        payload.put("channels", salida);

        JSONObject salud = new JSONObject();
        salud.put("ts", version);
        salud.put("total", salida.length());
        salud.put("ok", vivos);
        salud.put("channels", saludCanales);

        escribir(fDatos, payload.toString().getBytes(StandardCharsets.UTF_8));
        escribir(fSalud, salud.toString().getBytes(StandardCharsets.UTF_8));
        escribir(new File(fDatos.getParentFile(), "catalogo-urls.txt"),
                listaUrls.toString().getBytes(StandardCharsets.UTF_8));
        guardarMeta(version, nuevoEtag);
        Log.d("tvguia", "catalogo: " + salida.length() + " canales, " + vivos + " vivos, version " + version);
        return true;
    }

    private void guardarMeta(String version, String etag) throws Exception {
        String t = (version == null ? "" : version) + "|" + System.currentTimeMillis() + "|"
                + (etag == null ? "" : etag);
        escribir(fMeta, t.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * El catalogo publico marca el idioma en los grupos ajenos ("en · Noticias").
     * Para la UI eso rompe los chips (190 grupos en vez de 14), asi que se descarta el prefijo.
     */
    static String normalizarGrupo(String grupo) {
        if (grupo == null) return "Sin clasificar";
        String g = grupo.trim();
        int corte = g.indexOf(" · ");
        if (corte > 0 && corte <= 5) {
            String resto = g.substring(corte + 3).trim();
            if (!resto.isEmpty()) return resto;
        }
        return g.isEmpty() ? "Sin clasificar" : g;
    }

    private String codigoCategoria(String grupo) {
        for (String[] par : GRUPOS) {
            if (par[0].equalsIgnoreCase(grupo)) return par[1];
        }
        return "sin-clasificar";
    }

    private Map<String, String> etiquetasIdioma() {
        return mapaDeFacets("languages");
    }

    private Map<String, String> etiquetasPais() {
        return mapaDeFacets("countries");
    }

    private Map<String, String> mapaDeFacets(String clave) {
        Map<String, String> out = new HashMap<>();
        try {
            JSONObject f = new JSONObject(new String(leer(contexto.getAssets().open("web/facets.json")),
                    StandardCharsets.UTF_8));
            JSONObject m = f.optJSONObject(clave);
            if (m != null) {
                for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                    String k = it.next();
                    out.put(k, m.optString(k, k));
                }
            }
        } catch (Exception e) {
            Log.w("tvguia", "no pude leer facets (" + clave + ")", e);
        }
        return out;
    }

    private JSONObject facets(Map<String, String> paises, Map<String, String> idiomas) throws Exception {
        JSONObject f = new JSONObject();
        JSONObject jp = new JSONObject();
        for (Map.Entry<String, String> e : paises.entrySet()) jp.put(e.getKey(), e.getValue());
        JSONObject ji = new JSONObject();
        for (Map.Entry<String, String> e : idiomas.entrySet()) ji.put(e.getKey(), e.getValue());
        JSONObject jc = new JSONObject();
        for (String[] par : GRUPOS) jc.put(par[1], par[0]);
        f.put("countries", jp);
        f.put("languages", ji);
        f.put("categories", jc);
        return f;
    }

    private void escribir(File destino, byte[] datos) throws Exception {
        File tmp = new File(destino.getParentFile(), destino.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(datos);
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(destino)) {
            // en algunos FS renameTo no pisa: se copia a mano
            FileOutputStream out = new FileOutputStream(destino);
            try {
                out.write(datos);
            } finally {
                out.close();
            }
            tmp.delete();
        }
    }

    static byte[] leer(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    static byte[] leer(File f) throws Exception {
        return leer(new java.io.FileInputStream(f));
    }
}
