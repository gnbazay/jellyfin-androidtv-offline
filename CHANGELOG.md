# Novedades de Jellyfin Offline

Cada versión se basa en una versión estable de la app oficial (`v<oficial>-offline.<n>`).
El texto de cada versión se usa como notas de la release en GitHub y se muestra en el aviso de
actualización de la app, así que se escribe para el usuario.

## [v0.20.0-offline.3] - 2026-11-18

Basada en Jellyfin Android TV 0.20.0.

### Nuevo
- **Descargar desde un episodio hasta el final de la temporada**: mantén pulsado OK sobre un
  episodio en cualquier lista (o sobre su botón Descargar) y elige *Descargar desde aquí hasta el
  final de la temporada*. Se saltan los episodios que ya tienes.
- En el menú de pulsación larga de películas, episodios, temporadas y series aparecen también las
  opciones de descarga.

### Documentación
- Nueva guía del proyecto (README del repositorio), este registro de cambios y guía técnica
  (CLAUDE.md). Las notas de cada release salen de este archivo.

## [v0.20.0-offline.2] - 2026-11-18

### Nuevo
- **Descargas en la tarjeta SD**: por defecto se guardan en la SD/USB si hay una puesta.
- Botón **Guardar en** en Descargas para elegir *Automático*, *Tarjeta SD* o *Almacenamiento
  interno*, con el espacio libre de cada uno.
- **Descargar por temporadas**: en una serie, *Descargar temporada* muestra cada temporada con su
  estado (episodios, "4/10 descargados", "Descargada ✓"); después todos o solo no vistos y la
  calidad con el tamaño total estimado.

### Cambios
- Ya no se puede descargar una serie completa de golpe.
- Si eliges la SD y no está puesta, la app avisa y no descarga en el almacenamiento interno.

## [v0.20.0-offline.1] - 2026-11-18

Primera versión.

### Nuevo
- Botón **Descargar** en películas y episodios con calidad Original, 1080p, 720p o 480p.
- Cola de descargas en segundo plano con notificación; las descargas originales se reanudan
  si se corta la conexión.
- Pantalla **Descargas** (icono en la barra superior y en la pantalla de servidores) que funciona
  sin conexión y se abre sola si no hay red.
- Reproductor sin conexión con subtítulos, pistas de audio, saltos con el mando, reanudación y
  siguiente episodio.
- El progreso visto sin conexión se sincroniza con el servidor al volver a tener red.
- Actualizaciones automáticas desde las releases de GitHub.
- Se instala junto a la app oficial ("Jellyfin Offline").
