# Jellyfin Offline

Fork personal del [cliente oficial de Jellyfin para Android TV](https://github.com/jellyfin/jellyfin-androidtv)
que añade **descargas y reproducción sin conexión**, pensado para llevarse películas y series de
viaje en un Google TV portátil (RayNeo Pocket TV).

Se instala **junto** a la app oficial (paquete `org.jellyfin.androidtv.offline`, nombre
"Jellyfin Offline"); todo lo demás funciona igual que en la app oficial.

- 📦 **Descargar la última versión:** [jellyfin-offline.apk](https://github.com/gnbazay/jellyfin-androidtv-offline/releases/latest/download/jellyfin-offline.apk)
- 📝 **Novedades de cada versión:** [CHANGELOG.md](../CHANGELOG.md)
- 🛠️ **Desarrollo:** [CLAUDE.md](../CLAUDE.md)

## Funcionalidades

| | |
|---|---|
| **Descargar películas y episodios** | Botón **Descargar** en la ficha. Calidad *Original* (el archivo tal cual, se reanuda si se corta) o *1080p / 720p / 480p* (el servidor lo convierte y ocupa mucho menos). |
| **Descargar por temporadas** | En una serie, **Descargar temporada** → eliges la temporada → todos los episodios o solo los no vistos. Se muestra el tamaño total estimado. |
| **Desde un episodio hasta el final de la temporada** | **Mantén pulsado** OK sobre un episodio (en cualquier lista, o sobre su botón Descargar) → *Descargar desde aquí hasta el final de la temporada*. |
| **Guardar en la tarjeta SD** | Por defecto se usa la SD/USB si hay una. En **Descargas → Guardar en** puedes elegir SD o almacenamiento interno. |
| **Biblioteca sin conexión** | Pantalla **Descargas** (icono ⬇ arriba): seguir viendo, películas y una fila por serie. Se abre sola si no hay red o el servidor no responde. |
| **Reproductor sin conexión** | Subtítulos, cambio de pista de audio, saltos con el mando (← 10 s, → 30 s), retoma donde lo dejaste y pasa al siguiente episodio descargado. |
| **Sincronización** | Lo que ves sin conexión se envía al servidor (posición y "visto") la próxima vez que haya red. |
| **Actualizaciones automáticas** | La app avisa cuando hay una versión nueva en GitHub y se instala con dos pulsaciones. |

## Instalar en Google TV / RayNeo Pocket TV

1. Instala **Downloader** (AFTVnews) desde Google Play.
2. Ajustes → Apps → Seguridad y restricciones → **Fuentes desconocidas** → permite a Downloader.
3. En Downloader abre:
   `https://github.com/gnbazay/jellyfin-androidtv-offline/releases/latest/download/jellyfin-offline.apk`
4. Abre **Jellyfin Offline**, conecta con tu servidor e inicia sesión.

### Actualizar

Al abrir la app (como mucho una vez cada 12 h) o desde **Descargas → Buscar actualizaciones**
aparece "Actualización disponible" → **Actualizar** → en el aviso de Android muévete a la
izquierda hasta **Actualizar** (viene marcado "Cancelar"). La primera vez Android pide permitir a
la app instalar aplicaciones; después ya no.

Las descargas, la sesión y los ajustes **se conservan** al actualizar.

## Requisitos en el servidor Jellyfin

Panel → Usuarios → tu usuario:
- **Permitir la descarga de contenido** (obligatorio).
- Permitir la reproducción que requiera transcodificación (solo para 1080p/720p/480p).

## Uso

### Calidades

| Calidad | Qué hace | Tamaño aproximado (película de 2 h) |
|---|---|---|
| Original | Descarga el archivo tal cual. Se reanuda si se corta la conexión. | Lo que ocupe en el servidor (4K: 20–60 GB) |
| 1080p · 8 Mbps | El servidor lo convierte a H.264/AAC estéreo. Si se corta, vuelve a empezar. | ~7 GB |
| 720p · 4 Mbps | Ídem | ~3,6 GB |
| 480p · 1,5 Mbps | Ídem | ~1,4 GB |

Para la pantalla del Pocket TV, **720p** suele ser el mejor equilibrio entre calidad y espacio.

### Almacenamiento

- **Automático** (por defecto): tarjeta SD/USB si hay una; si no, almacenamiento interno.
- Si eliges la **SD** y no está puesta, la app avisa y **no** descarga en el interno.
- Lo que hay en la SD desaparece de la lista al sacarla y vuelve al ponerla.
- La SD debe estar formateada como **almacenamiento portátil** (no "como almacenamiento interno").
- Se guarda en la carpeta de la app (`Android/data/org.jellyfin.androidtv.offline/files/downloads`):
  **si desinstalas la app se borran las descargas**, también las de la SD.

### Sin conexión

Si al abrir la app no hay red, o el servidor no responde en 12 s, se abre directamente
**Descargas**. Desde ahí, "Conectar al servidor" vuelve al inicio normal.

## Publicar una versión nueva (resumen)

Añade la entrada en [CHANGELOG.md](../CHANGELOG.md) y sube una etiqueta:

```bash
git tag v0.20.0-offline.4
git push origin v0.20.0-offline.4
```

GitHub compila, firma y publica la release (con las notas del CHANGELOG). Detalles en
[CLAUDE.md](../CLAUDE.md).

## Mantenimiento

- **Revisar de vez en cuando si hay una versión estable nueva de la app oficial**
  ([releases](https://github.com/jellyfin/jellyfin-androidtv/releases), sin contar beta/rc).
  Si la hay, se trae a la rama `offline` manteniendo nuestras funciones y se publica como
  `v<nueva>-offline.1` (por ejemplo `v0.21.0-offline.1`). Pasos en [CLAUDE.md](../CLAUDE.md).
- **Espacio en el Mac**: las herramientas para compilar y probar en local (Android SDK, emulador,
  servidor Jellyfin de prueba en Docker, cachés de Gradle) ocupan unos 17 GB. Se pueden borrar si
  hace falta espacio; las releases se compilan en GitHub y no las necesitan. Nunca borres la
  carpeta `keystore/`.

## Licencia

GPL-2.0, igual que el proyecto original de Jellyfin.
