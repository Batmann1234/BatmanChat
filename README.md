# BatmanChat

Un solo plugin para **Paper 1.21.7 (Java 21)** que reemplaza a **ChatManager**, **ChatSentry** e **InfiniteAnnouncements**.

## Compilar

```
mvn package
```
El jar queda en `target/BatmanChat.jar`. Copialo a `plugins/`, **borra ChatManager, ChatSentry e InfiniteAnnouncements** y reinicia el servidor (no uses /reload).
Dependencias opcionales (se detectan solas): PlaceholderAPI, Vault, LuckPerms, Essentials.

> IMPORTANTE: este codigo se escribio sin poder compilar contra la API de Paper (el entorno donde se hizo no tenia acceso a los repositorios de Maven). Los YAML estan validados y la logica de las protecciones tiene 69 pruebas que pasan, pero si `mvn package` te marca algun error de compilacion, mandamelo y lo corrijo.

## Estructura de configuracion (cada cosa en su yml)

```
plugins/BatmanChat/
  config.yml                      activar/desactivar cada modulo completo
  mensajes.yml                    prefijo y mensajes generales
  anuncios/    anuncios.yml  config.yml  placeholders.yml  mensajes.yml      (InfiniteAnnouncements)
  servidor/    entradas-salidas.yml  broadcast.yml                           (ChatManager)
  chat/        formato.yml  emojis.yml  menciones.yml  respuestas-automaticas.yml
               items-interactivos.yml  muertes.yml  clearchat.yml  mutechat.yml  staffchat.yml
  tienda.yml   encuestas.yml
  proteccion/  config.yml (interruptor de cada proteccion + contextos)  lang.yml  almacenamiento.yml  lockdown.yml
    modulos/   un yml por proteccion (anti-chat-flood, spam-blocker, word-and-phrase-filter, ...)
               + admin-notifier, auto-punisher, command-spy, discord-notifier
```
`/batmanchat reload` recarga todo. `/anuncios reload` solo los anuncios.

## Comandos

| Comando | Para que |
|---|---|
| `/batmanchat reload` | recargar todo (`batmanchat.admin`) |
| `/anuncios <reload|toggle|togglesound|preview|list|send>` (`/ia`) | anuncios |
| `/broadcast`, `/announcement`, `/warning` | difusion (si tienes Essentials, su `/broadcast` tiene prioridad; el de BatmanChat es `/batmanchat:broadcast`, o usa `/announcement`) |
| `/clearchat` (`/cc`), `/mutechat`, `/staffchat` (`/sc`) | control del chat |
| `/encuesta crear|votar|resultados|cerrar` | encuestas |
| `/batmanchat menciones` | activar/desactivar tus avisos de mencion |
| `/batmanchat compra <jugador> <paquete> [precio] [transaccion]` | anuncio de compra (consola/Tebex) |
| `/kcs`, `/cswarn`, `/cswarnings`, `/cslockdown`, `/commandspy` | protecciones (igual que ChatSentry) |

### Tebex
En cada paquete anade el comando: `batmanchat compra {username} hero {price} {transaction}`
(`hero` = nombre del paquete en `tienda.yml`; si no existe usa `default`).

## Permisos

Los permisos viejos de ChatManager/ChatSentry **tambien funcionan** (`chatmanager.broadcast`, `chatmanager.clearchat`, `chatmanager.mutechat`, `chatmanager.staffchat`, `chatmanager.bypass.clearchat`, `chatmanager.bypass.mutechat`, `chatmanager.color.*`, `chatmanager.format.*`, `chatsentry.*`...), asi no tienes que tocar LuckPerms. Los nuevos son:

- General: `batmanchat.admin`
- Anuncios: `batmanchat.anuncios.toggle` (todos), `.reload`, `.admin`
- Difusion: `batmanchat.broadcast`, `.announcement`, `.warning`
- Chat: `batmanchat.clearchat`, `.clearchat.bypass`, `.mutechat`, `.mutechat.bypass`, `.staffchat`, `.delay.bypass`
- Colores del mensaje: `batmanchat.color.all`, `.color.<codigo>`, `.color.hex`, `.color.gradient`, `.format.all`, `.format.<codigo>`
- Emojis/menciones: `batmanchat.emojis`, `batmanchat.menciones`, `batmanchat.menciones.todos`
- Tokens: `batmanchat.items.mano|inv|ender|dinero|ping|ver` (todos), `batmanchat.items.cooldown.bypass`
- Encuestas: `batmanchat.encuesta.votar` (todos), `batmanchat.encuesta.crear`
- Tienda: `batmanchat.tienda.admin`
- Protecciones: `batmanchat.proteccion.bypass` (todas), `batmanchat.proteccion.<modulo>.bypass`, `.warn`, `.warnings`, `.lockdown`, `.lookup`, `.notify`, `.commandspy`, `.commandspy.toggle`, `.commandspy.exempt`, `.warn.exempt`, `.autopunisher.exempt`
- Mensajes de entrada/salida por grupo: los permisos que pusiste en `servidor/entradas-salidas.yml` (`chatmanager.join.staff`, `chatmanager.join.default`). Siguen siendo los de siempre: hay que darlos por LuckPerms.

Los OP se saltan las protecciones, salvo los modulos que marques en `override-bypass-permissions` de `proteccion/config.yml`.

## Que se cambio respecto a los plugins originales
- `anuncios/anuncios.yml`: `worlds: []` = todos los mundos (el ejemplo original `example_1/example_2` no mostraba nada).
- Los valores de cada proteccion son los de tu ChatSentry. Los comentarios de `proteccion/modulos/*.yml` siguen en ingles (como el original); los mensajes (`proteccion/lang.yml`) estan en espanol.
- Los valores de `chat/formato.yml` (`Retraso_Chat`) y `chat/items-interactivos.yml` (`Cooldown`) por rango son ejemplos: ajustalos a tus grupos.
- `chat/respuestas-automaticas.yml` trae la IP `play.tuservidor.com` de ejemplo: cambiala.
- Tu config de ChatManager tenia `Formatted_Messages.enable: false`, por eso `Colores_En_Mensajes` esta en `false`.

## No incluido (no lo pediste)
Mensajes privados (`/msg`, los maneja Essentials), social spy, lista de staff/jugadores, reglas, chat por mundo/radio, comandos baneados, logs de chat.
