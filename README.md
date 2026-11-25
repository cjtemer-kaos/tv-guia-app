# TV Guía — mejoras de la app Android

Correcciones y mejoras al proyecto **TV Guía** del equipo
([`siroblidium/tv-guia`](https://github.com/siroblidium/tv-guia), MIT): la app Android que corre
esa misma interfaz en TV box y teléfono, con navegación por control remoto.

La web sigue siendo la base y este repo no la duplica: aporta la capa Android (`app-tv`) y el
modo TV, pensados para poder volver al proyecto original.

No es una app nativa: es un `WebView` que sirve la misma web desde los assets del APK, con un
proxy propio que reemplaza al `server.py` de la versión de escritorio. Anda **sin la PC
encendida**.

## Qué hace la app

| Pieza de la web | Cómo se resuelve acá |
|---|---|
| `/api/channels`, `/api/health` | los JSON empaquetados en `assets/web/` |
| `/proxy?u=&r=&a=` | descarga con el `User-Agent`/`Referer` del canal y **reescribe la playlist** para que playlist y segmentos vuelvan a pasar por el proxy (sin eso, CORS rompe el HLS) |
| reproductor | `hls.js` dentro del `WebView`, con pantalla completa |
| navegación | `tvmode.js`: flechas mueven el foco entre canales, OK reproduce, Atrás cierra |

Además: oculta el botón *Verificar* (el sondeo corre en la PC) y filtra del catálogo los canales
que apuntan a `127.0.0.1`, que son los del puente de audio local y no pueden andar en el aparato.

## Actualización del catálogo (solo GitHub)

La app **no depende de la PC**: al arrancar baja la lista del catálogo público
([`cjtemer-kaos/tv-guia-data`](https://github.com/cjtemer-kaos/tv-guia-data)) y la refresca cada
12 h, con `If-None-Match` para no gastar datos si no cambió.

```
https://raw.githubusercontent.com/cjtemer-kaos/tv-guia-data/main/public/catalog.json
```

El catálogo público trae otro formato (`{n,name,g,c,lang,ok,logo,url}`) y no incluye
`agent`/`referrer` por las reglas anti-fuga del repo. `Catalogo.java` lo **convierte al formato
que espera la web** al bajar, una sola vez (4,2 MB ya listos en disco):

- `{id,name,logo,group,g,url,c,l,k,num,lang,lang_label}` + `groups` + `facets`
- los grupos vienen con el idioma pegado (`"en · Generalistas"`): se normaliza a los 14 canónicos
  y el idioma queda en `lang_label`
- la categoría se deriva del grupo, para que el filtro "categorías" siga sirviendo
- de `ok` se arma el `status` que la UI usa para el filtro *En vivo*

Si el aparato no tiene red al arrancar, usa el catálogo empaquetado en el APK. Cuando la
descarga termina y no hay nada reproduciéndose, la página se recarga sola.

Consecuencia a tener en cuenta: un canal que **exige `User-Agent` o `Referer`** (por ejemplo los
DSports) no va a andar con el catálogo de GitHub, porque esos campos no se publican.

## El "está vivo" se mide en el aparato, no en un servidor

Una lista con un solo `ok` global es mentira: el mismo canal puede andar en Montevideo y estar
bloqueado en Madrid, y un CDN puede caerse para un ISP y no para otro. Por eso la app **mide
desde su propia conexión**:

- `Sondeo.java` prueba el catálogo por lotes de **400 canales** en segundo plano (12 hilos, 6 s de
  conexión y 9 s de lectura, se conforma con que el manifiesto devuelva `#EXTM3U`).
- El resultado se guarda en `medidos.json` con fecha, **TTL 72 h**, y un cursor que avanza para
  cubrir todo el catálogo en varias vueltas sin repetir siempre los mismos.
- `/api/health` mezcla: parte del `status` publicado y **lo pisa con lo medido en este aparato**
  (campo `medidos_local`). Lo local manda.
- Es automático: al arrancar corre un lote, y la web ya dispara sola la verificación cuando los
  datos tienen más de 6 h. El botón **Verificar** fuerza un lote nuevo cuando quieras.

En el X96-W, primera corrida: 400 canales medidos en 52 s y el contador de "En vivo" bajó de
7.964 a 7.906 (58 que la lista publicada daba vivos y desde esa red no abren).

## Compilar

Necesitás JDK 17+ y el SDK de Android (`compileSdk 37`, `minSdk 24`).

```bat
:: 1) copiar los assets de la web (una sola vez, o cada vez que cambie el catalogo)
powershell -ExecutionPolicy Bypass -File .\preparar-assets.ps1 -Origen D:\ias\proyectos\tv-guia

:: 2) compilar
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=D:\ias\tools\android-sdk
gradlew.bat assembleDebug
```

El APK queda en `app\build\outputs\apk\debug\app-debug.apk` (~2,6 MB).

Instalar en un aparato conectado:

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.kaos.tvguia/.MainActivity
```

## Los assets NO están en el repo

`channels.json` y `status.json` se quedan fuera a propósito: las URLs de canales pueden traer
credenciales y tokens, y este repo es público. `preparar-assets.ps1` los copia desde tu copia
local de TV Guía al momento de compilar, y el `.gitignore` los excluye.

Del mismo modo no se publican `index.html`, `app.js`, `style.css` ni `hls.min.js`: vienen del
proyecto web del equipo (`siroblidium/tv-guia`, MIT). Sólo se versiona `tvmode.js`, que es de
este repo.

## Estados probados

- **X96-W** (Android TV, 1280x720): grilla con logos, 6.075 canales en vivo, reproducción real
  (Toonami, DSports LatAm 1 y 2).
- **OPPO Find X6** (Android 16): misma APK, reproducción a pantalla completa por touch.
- **Auto-actualización (1.1)**: en el X96-W, con los datos borrados, bajó el catálogo de GitHub en
  6 s (`10890 canales, 7951 vivos, version 2026-11-25T15:32:11Z`) y recargó la grilla sola.

Límites conocidos: el audio **MP2** no suena en el `WebView` (el navegador no lo decodifica, igual
que en la PC); esos canales necesitan VLC o el puente de audio. Los canales muertos del catálogo
siguen listados si el último sondeo de la PC los marcó como activos.

## Licencia

Código de este repo: MIT (ver `LICENSE`). La interfaz web es del equipo
([`siroblidium/tv-guia`](https://github.com/siroblidium/tv-guia), MIT) y `hls.js` es Apache-2.0.
