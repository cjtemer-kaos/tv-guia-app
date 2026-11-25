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

Límites conocidos: el audio **MP2** no suena en el `WebView` (el navegador no lo decodifica, igual
que en la PC); esos canales necesitan VLC o el puente de audio. Los canales muertos del catálogo
siguen listados si el último sondeo de la PC los marcó como activos.

## Licencia

Código de este repo: MIT (ver `LICENSE`). La interfaz web es del equipo
([`siroblidium/tv-guia`](https://github.com/siroblidium/tv-guia), MIT) y `hls.js` es Apache-2.0.
