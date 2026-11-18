# Jellyfin Offline (fork de jellyfin-androidtv)

Fork personal del cliente oficial de Jellyfin para Android TV / Google TV que añade
**descargas y reproducción sin conexión**. Se instala junto a la app oficial
(`applicationId` = `org.jellyfin.androidtv.offline`).

## Instalar en Google TV / RayNeo Pocket TV

1. En el dispositivo: Ajustes → Sistema → Acerca de → pulsa 7 veces "Compilación del SO" para
   activar las opciones de desarrollador. Después, en Ajustes → Apps → Seguridad y restricciones →
   **Fuentes desconocidas**, permite instalar a la app que vayas a usar (Downloader).
2. Instala **Downloader** (AFTVnews) desde Google Play.
3. En Downloader, abre esta URL (siempre apunta a la última versión):

   `https://github.com/gnbazay/jellyfin-androidtv-offline/releases/latest/download/jellyfin-offline.apk`

4. Abre "Jellyfin Offline", conecta con tu servidor e inicia sesión.

Después la app comprueba las releases de GitHub (cada 12 h y desde **Descargas → Buscar
actualizaciones**) y se actualiza sola. La primera vez Android te pedirá que permitas a
Jellyfin Offline instalar aplicaciones.

> Las descargas se guardan en el almacenamiento de la app: **sobreviven a las actualizaciones,
> pero se borran si desinstalas la app**.

## Requisitos en el servidor

Panel de Jellyfin → Usuarios → tu usuario:
- **Permitir la descarga de contenido** (obligatorio).
- Permitir la reproducción que requiera transcodificación (solo para descargas 1080p/720p/480p).

## Uso

- **Descargar**: abre una película o episodio → botón **Descargar** → elige la calidad:
  - *Original*: el archivo tal cual (más rápido, sin carga para el servidor; se reanuda si se corta).
  - *1080p / 720p / 480p*: el servidor lo transcodifica a H.264/AAC estéreo (ocupa mucho menos,
    pero si se corta la conexión vuelve a empezar).
- En una **serie o temporada**: botón **Descargar episodios** → todos o solo los no vistos.
- **Descargas**: icono de descarga en la barra superior (también en la pantalla de selección de
  servidor, sin iniciar sesión).
- **Sin conexión**: si al abrir la app no hay red, o el servidor no responde en 12 s, se abre
  directamente la pantalla de descargas.
- El progreso visto offline se envía al servidor la próxima vez que abras la app con conexión.

## Publicar una versión nueva

```bash
git tag v0.20.0-offline.2
git push origin v0.20.0-offline.2
```

El workflow `.github/workflows/offline-release.yaml` compila el APK firmado y crea la release
con `jellyfin-offline.apk` + `version.txt` (lo que usa el actualizador). El número tras
`-offline.` debe ir de 1 a 98 y subir en cada release.

Secretos necesarios en el repo (ya configurados): `KEYSTORE` (base64), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. **Guarda una copia del keystore**: sin él no podrás publicar
actualizaciones instalables sobre la app existente.

## Traer cambios de la app oficial

```bash
git fetch upstream --tags
git merge v0.21.0          # tag estable de jellyfin/jellyfin-androidtv
git tag v0.21.0-offline.1 && git push origin offline --tags
```

El código propio está aislado en `app/src/main/java/org/jellyfin/androidtv/offline/` y solo toca
unos pocos puntos del código original (marcados con el comentario `Offline fork`), por lo que
los merges deberían dar pocos conflictos.

## Compilar en local

Requiere JDK 21 y Android SDK (`local.properties` con `sdk.dir=...`).

```bash
./gradlew assembleDebug
adb connect <ip-del-dispositivo>:5555
adb install -r app/build/outputs/apk/debug/*.apk
```
