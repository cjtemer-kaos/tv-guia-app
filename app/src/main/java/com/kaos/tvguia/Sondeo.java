package com.kaos.tvguia;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sondeo de canales EN EL APARATO.
 *
 * La disponibilidad de un canal depende de la red desde donde se mira (geo-bloqueos, CDNs
 * caidos, ISP). Por eso el "esta vivo" no se puede publicar de forma universal: cada aparato
 * mide el catalogo desde su propia conexion y guarda el resultado.
 *
 * Se hace por lotes (LOTE canales por corrida, en segundo plano), con TTL de 72 h y un cursor
 * que avanza para cubrir todo el catalogo en varias vueltas sin repetir siempre los mismos.
 * /api/health mezcla estas mediciones con las del catalogo publico: lo medido aca manda.
 */
public class Sondeo {

    static final long TTL_MS = 72L * 60 * 60 * 1000;
    static final int LOTE = 400;
    static final int HILOS = 12;
    static final String UA = "Mozilla/5.0 (Linux; Android 13) TVGuia/1.4";

    private final File fMedidos;
    private final File fCursor;
    private final File fUrls;
    private final File fCatalogo;

    private final Map<String, Boolean> medidos = new ConcurrentHashMap<>();
    private final Map<String, Long> cuando = new ConcurrentHashMap<>();
    private final AtomicBoolean corriendo = new AtomicBoolean(false);
    private volatile String cacheSalud;

    public Sondeo(Context contexto) {
        File dir = contexto.getApplicationContext().getFilesDir();
        fMedidos = new File(dir, "medidos.json");
        fCursor = new File(dir, "sondeo.cursor");
        fUrls = new File(dir, "catalogo-urls.txt");
        fCatalogo = new File(dir, "catalogo-web.json");
        cargar();
    }

    public boolean corriendo() {
        return corriendo.get();
    }

    public int medidos() {
        return medidos.size();
    }

    /** Lanza un lote en segundo plano si hay algo para medir. */
    public void quizas(boolean forzar) {
        if (!corriendo.compareAndSet(false, true)) return;
        new Thread(() -> {
            int hechos = 0;
            try {
                hechos = lote(LOTE, forzar);
            } catch (Exception e) {
                Log.e("tvguia", "sondeo fallo", e);
            } finally {
                corriendo.set(false);
                if (hechos > 0) {
                    guardar();
                    cacheSalud = null;
                    Log.d("tvguia", "sondeo: " + hechos + " medidos en este lote, total "
                            + medidos.size());
                }
            }
        }, "sondeo").start();
    }

    /** /api/health: parte del status publicado y lo pisa con lo medido en este aparato. */
    public String salud(File base) {
        String cache = cacheSalud;
        if (cache == null) {
            cache = construirSalud(base);
            cacheSalud = cache;
        }
        return corriendo.get() ? cache.replace("\"running\":false", "\"running\":true") : cache;
    }

    private String construirSalud(File base) {
        JSONObject salida;
        try {
            if (base != null && base.exists()) {
                salida = new JSONObject(new String(Catalogo.leer(base), StandardCharsets.UTF_8));
            } else {
                salida = new JSONObject();
            }
        } catch (Exception e) {
            salida = new JSONObject();
        }
        try {
            JSONObject canales = salida.optJSONObject("channels");
            if (canales == null) {
                canales = new JSONObject();
                salida.put("channels", canales);
            }
            for (Map.Entry<String, Boolean> e : medidos.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("ok", e.getValue());
                Long t = cuando.get(e.getKey());
                if (t != null) o.put("ts", t);
                canales.put(e.getKey(), o);
            }
            salida.put("running", false);
            salida.put("medidos_local", medidos.size());
        } catch (Exception e) {
            Log.w("tvguia", "no pude armar /api/health", e);
        }
        return salida.toString();
    }

    /* ------------------------------------------------------------------ lote */

    private int lote(int cuantos, boolean forzar) throws Exception {
        List<String> urls = leerUrls();
        if (urls.isEmpty()) {
            Log.d("tvguia", "sondeo: no hay lista de urls todavia");
            return 0;
        }
        long ahora = System.currentTimeMillis();
        int inicio = leerCursor();
        int total = urls.size();

        List<String> pendientes = new ArrayList<>(cuantos);
        int recorridos = 0;
        int i = 0;
        while (recorridos < total && pendientes.size() < cuantos) {
            String u = urls.get((inicio + i) % total);
            i++;
            recorridos++;
            Long t = cuando.get(u);
            boolean alDia = t != null && (ahora - t) < TTL_MS;
            if (alDia && !forzar) continue;
            pendientes.add(u);
        }
        if (pendientes.isEmpty()) return 0;

        Log.d("tvguia", "sondeo: probando " + pendientes.size() + " canales...");
        ExecutorService pool = Executors.newFixedThreadPool(HILOS);
        for (String u : pendientes) {
            pool.submit(() -> {
                boolean ok = probar(u);
                medidos.put(u, ok);
                cuando.put(u, System.currentTimeMillis());
            });
        }
        pool.shutdown();
        pool.awaitTermination(6, TimeUnit.MINUTES);

        escribirCursor((inicio + i) % total);
        return pendientes.size();
    }

    private static boolean probar(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(true);
            con.setConnectTimeout(6000);
            con.setReadTimeout(9000);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Accept-Encoding", "identity");
            con.setRequestProperty("Accept", "*/*");
            int codigo = con.getResponseCode();
            if (codigo != 200) return false;
            String tipo = con.getContentType();
            byte[] buf = new byte[2048];
            int n;
            try (InputStream in = con.getInputStream()) {
                n = in.read(buf);
            }
            if (n <= 0) return false;
            String cuerpo = new String(buf, 0, n, StandardCharsets.UTF_8);
            return cuerpo.contains("#EXTM3U") || cuerpo.contains("#EXT-X-")
                    || (tipo != null && tipo.toLowerCase().contains("mpegurl"));
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /* ------------------------------------------------------------------ disco */

    private void cargar() {
        try {
            if (!fMedidos.exists()) return;
            JSONObject raiz = new JSONObject(new String(Catalogo.leer(fMedidos), StandardCharsets.UTF_8));
            JSONObject canales = raiz.optJSONObject("channels");
            if (canales == null) return;
            for (java.util.Iterator<String> it = canales.keys(); it.hasNext(); ) {
                String u = it.next();
                JSONObject o = canales.optJSONObject(u);
                if (o == null) continue;
                medidos.put(u, o.optBoolean("ok", false));
                cuando.put(u, o.optLong("ts", 0L));
            }
            Log.d("tvguia", "sondeo: " + medidos.size() + " mediciones guardadas");
        } catch (Exception e) {
            Log.w("tvguia", "no pude leer medidos.json", e);
        }
    }

    private void guardar() {
        try {
            JSONObject canales = new JSONObject();
            for (Map.Entry<String, Boolean> e : medidos.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("ok", e.getValue());
                o.put("ts", cuando.getOrDefault(e.getKey(), 0L));
                canales.put(e.getKey(), o);
            }
            JSONObject raiz = new JSONObject();
            raiz.put("channels", canales);
            raiz.put("actualizado", System.currentTimeMillis());
            byte[] datos = raiz.toString().getBytes(StandardCharsets.UTF_8);
            File tmp = new File(fMedidos.getParentFile(), fMedidos.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(datos);
                out.getFD().sync();
            }
            if (!tmp.renameTo(fMedidos)) {
                try (FileOutputStream out = new FileOutputStream(fMedidos)) {
                    out.write(datos);
                }
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w("tvguia", "no pude guardar medidos.json", e);
        }
    }

    private List<String> leerUrls() {
        List<String> out = new ArrayList<>();
        if (!fUrls.exists() && fCatalogo.exists()) {
            // instalacion que ya tenia el catalogo de una version anterior: se deriva la lista
            try {
                JSONObject raiz = new JSONObject(new String(Catalogo.leer(fCatalogo), StandardCharsets.UTF_8));
                org.json.JSONArray canales = raiz.optJSONArray("channels");
                StringBuilder sb = new StringBuilder(1536 * 1024);
                for (int i = 0; canales != null && i < canales.length(); i++) {
                    JSONObject c = canales.optJSONObject(i);
                    if (c == null) continue;
                    String u = c.optString("url", "");
                    if (u.startsWith("http")) sb.append(u).append('\n');
                }
                try (FileOutputStream out2 = new FileOutputStream(fUrls)) {
                    out2.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
                Log.d("tvguia", "sondeo: lista de urls derivada del catalogo existente");
            } catch (Exception e) {
                Log.w("tvguia", "no pude derivar la lista de urls", e);
            }
        }
        if (!fUrls.exists()) return out;
        try (FileInputStream in = new FileInputStream(fUrls)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 20);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            for (String ln : new String(buf.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                String u = ln.trim();
                if (u.startsWith("http")) out.add(u);
            }
        } catch (Exception e) {
            Log.w("tvguia", "no pude leer catalogo-urls.txt", e);
        }
        return out;
    }

    private int leerCursor() {
        try {
            if (!fCursor.exists()) return 0;
            String t = new String(Catalogo.leer(fCursor), StandardCharsets.UTF_8).trim();
            return Math.max(0, Integer.parseInt(t));
        } catch (Exception e) {
            return 0;
        }
    }

    private void escribirCursor(int valor) {
        try (FileOutputStream out = new FileOutputStream(fCursor)) {
            out.write(String.valueOf(valor).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w("tvguia", "no pude guardar el cursor", e);
        }
    }
}
