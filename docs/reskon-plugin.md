# Connect Habit Lab to Reskon

Reskon is a dashboard app that shows other apps as widgets. This server can be connected to it as a *connected app* (a plugin): Reskon reads Habit Lab's data and shows it in its own dashboard, and one widget, **Today's habits**, can be ticked from inside Reskon.

The contract is Reskon's `docs/plugins.md`, contract version 1. Nothing is sent to Reskon that the person has not put in their own Habit Lab account, and Reskon cannot change anything except through the tickable widget.

## What you need

- This sync server reachable from the machine running Reskon, over `http://` or `https://`.
- A Habit Lab account on that server (the same one the phone or browser app syncs with).
- `HABITLAB_RESKON_ORIGIN` set to Reskon's own address, for example `http://192.168.0.8:8097`. It decides which site may show the widget page in a frame and whose messages the page obeys. Without it the widget page still loads, but it refuses every frame and every message.

## Connecting

1. In Reskon, open **Settings > Apps**, choose **Add app**, and enter this server's address. Reskon reads `/.well-known/reskon-app.json` from it; that file needs no sign-in.
2. Reskon asks for a Habit Lab sign-in (the same username and password as the web app) and keeps the resulting token on its own server.
3. Reskon calls `connect_url` once with its `changed_url` and a 43-character key. Save that: it is how this server tells Reskon that data changed somewhere else.

To disconnect, remove the app in Reskon. The key stops working and this server keeps nothing but the saved address, which you can delete by removing `reskon-connect.json` from the data directory.

## What Reskon shows

| Widget | Kind | What it shows |
| --- | --- | --- |
| Today's habits | page | Today's habits as checkboxes. Ticking one writes a real check-in, visible in the app and on other devices. |
| Today | data table | One row per habit in the current run, with today's status and schedule. |
| Done per day | data | How many habits were completed each day (or each week) over the range the person picked. |
| Streaks | data | Each habit's current and longest run of consecutive days. |
| Longest streak | data | The longest run of any habit, in days. |

"Today" means today in the time zone Reskon passes along; without one it is UTC. The **Today** table shows only habits linked to the current active run. Duplicate links are collapsed to one row per habit; standalone habits, archived habits, and habits from stopped runs are excluded. When an active run has passed its final date, both Today feeds are empty and carry a completed-run message; the tickable page explains that a new run must be started in Habit Lab. The separate **Today's habits** page widget otherwise continues to show the web app's current-run and standalone habits.

## How it works

- **Description file:** `GET /.well-known/reskon-app.json`. Readable without signing in, contract version 1.
- **Sign-in:** `POST /api/reskon/login` with `{"username": ..., "password": ...}` returns `{"token": ...}`. It uses the same account store and the same failed-attempt limiter as the web app's sign-in, so guessing is slow and rate-limited.
- **Widget page:** Reskon asks `POST /api/reskon/launch` for a one-minute, single-use ticket, opens `/widgets/today#ticket=...&theme=...` in a frame, and the page swaps the ticket for a session it keeps in `sessionStorage` (never a cookie, which browsers block inside a foreign frame). The page then clears the address so the ticket cannot be seen or reused.
- **Framing:** widget pages send `Content-Security-Policy: frame-ancestors <Reskon origin>` and no `X-Frame-Options: DENY`. Every other response keeps the usual `X-Frame-Options: DENY` and `frame-ancestors 'none'`.
- **Data feeds:** `GET /api/reskon/feeds/<id>` with `Authorization: Bearer <token>`. Each answer matches the shape the description file promises, stays well under 1 MB, and answers an empty range with an empty list rather than an error.
- **Ticks:** `POST /api/reskon/page/today` writes the check-in straight into the same sync tables the app clients use, under its own device id and with fresh stamps and sequence numbers, so every other device picks it up on its next sync.
- **"Data changed":** after a sync or a tick, and after Reskon calls `connect_url`, the server calls Reskon's `changed_url` with the saved key. Calls are folded together so at most one goes out every two seconds per account, and a failed call is dropped rather than retried.

## Privacy and limits

- Reskon receives only what is already in the person's Habit Lab account. No other account's data can be read by changing a header: every feed resolves the account from the bearer token.
- Text sent to Reskon is plain text: control, direction-changing and invisible format characters are removed, and each field is length-limited (habit names 120 bytes, details 200, units 20, keys 100).
- Feeds are capped at 500 rows or items and 2,000 points, so a large history cannot produce an oversized answer.
- The server never gives Reskon a way to write data. The only write in the whole contract is a tick inside the widget page, and it is limited to habits that exist in the person's own account.

## Environment variables

| Variable | Required | Meaning |
| --- | --- | --- |
| `HABITLAB_RESKON_ORIGIN` | Recommended | Reskon's origin (`http://host:port`). Used for `frame-ancestors` and for checking which site's messages the widget page obeys. The most recently connected `changed_url` is used as well, so a connection made from another address also works. |

`HABITLAB_SYNC_SECRET` and `HABITLAB_SYNC_DATA` are required as usual; the plugin stores its per-account data in the same data directory (`reskon-connect.json`).
