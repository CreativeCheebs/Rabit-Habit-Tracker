# Writing a Reskon plugin

This guide is for whoever builds a plugin, usually an AI coding agent. A **plugin** (Reskon calls it a *connected app*) is a separate web app that runs on its own address and can provide:

- **web page widgets:** small pages of its own, shown in a frame on a Reskon dashboard;
- **data widgets:** numbers, lists, tables and checklists that Reskon draws in its own style;
- **a sidebar entry:** under the **Apps** heading, opening the plugin's full page.

In this version the data only goes one way, from the plugin to Reskon. Reskon only reads. It never sends ticks, edits or events to a plugin. Anything a person clicks happens inside the plugin's own web page widget. A plugin's data never counts in Reskon's totals.

> **Status: draft, contract version 1.** Reskon is building this now ([`next-phase.md`](next-phase.md) section 0, steps 3–7). Until those steps are done, details here may change, and this file will say so in the same commit. The reasons behind each rule are in [`decisions.md`](decisions.md), under "Plugins (connected apps) and live updates".

Example used throughout: a habit tracker at `http://192.168.0.8:5000`.

---

## 1. What a plugin must serve

| Address on the plugin | What it is | Needed? |
| --- | --- | --- |
| `GET /.well-known/reskon-app.json` | The description file (manifest) | Always |
| `POST <auth.login_url>` | Swap a username and password for a token | Only with `"auth": {"kind": "password"}` |
| `POST <launch_url>` | Give a one-minute ticket for a web page widget | Only with sign-in and web page widgets |
| `GET <feed url>` | The data for one data widget | One per data widget |
| widget pages | The pages shown in frames | One per web page widget |

Every answer is JSON (`Content-Type: application/json`), except the widget pages.

## 2. The description file

`GET /.well-known/reskon-app.json`

```json
{
  "reskon_app": 1,
  "id": "habits",
  "name": "Habits",
  "version": "1.0.0",
  "home_url": "/",
  "icon": "check",
  "auth": { "kind": "password", "login_url": "/api/reskon/login" },
  "launch_url": "/api/reskon/launch",
  "widgets": [
    {
      "id": "today",
      "title": "Today's habits",
      "kind": "page",
      "url": "/widgets/today",
      "size": { "w": 4, "h": 4 },
      "style": "reskon"
    },
    {
      "id": "last_days",
      "title": "Habits, last days",
      "kind": "data",
      "shape": "series",
      "url": "/api/reskon/feeds/done-per-day",
      "ranged": true,
      "groups": ["day", "week"],
      "size": { "w": 6, "h": 3 }
    }
  ]
}
```

**The fields:**
- **`reskon_app`**: the contract version. It is always `1` for now. Reskon refuses versions it doesn't know, and says so in Settings.
- **`id`**: short and stable, lower case letters, digits and `_` only. Never change it: saved widgets point at it.
- **`name`**, **`version`**: shown in Settings → Apps.
- **`home_url`**: what the sidebar entry opens, in a new tab.
- **`icon`**: one plain word (check, food, heart, chart).
- **`auth.kind`**: how Reskon signs in.
  - `"none"` means no sign-in, for a home-network app.
  - `"token"` means the person pastes a token the plugin gave them.
  - `"password"` means Reskon asks for a username and password once, swaps them at `login_url` for a token, and forgets the password.
- **`launch_url`** (optional): where Reskon gets a ticket for a web page widget (section 4.1).
- **`connect_url`** (optional): where Reskon hands the plugin a key for saying "my data changed" from its own server (section 7).
- **`widgets[].id`**: stable, unique within the plugin.
- **`widgets[].kind`**: `"page"` (web page widget) or `"data"` (data widget).
- **`widgets[].size`**: the starting size on Reskon's 12-column grid. People can resize it.
- **`widgets[].style`** (page widgets only): `"reskon"` if the page uses the colours Reskon sends (section 5); `"own"` if it keeps its own look. `"reskon"` is recommended.
- **`widgets[].shape`** (data widgets only): one of the shapes in section 6.
- **`widgets[].ranged`** (data widgets only): `true` if the feed accepts a date range.
- **`widgets[].groups`** (data widgets only): which groupings the feed accepts.

Addresses can be relative to the plugin's own address (recommended) or full. Every address must be under the address the person connected: the same scheme, machine and port, and inside its path. Connected at `http://192.168.0.8:5000/habits`, the address `/api/x` means `http://192.168.0.8:5000/habits/api/x`, and `http://192.168.0.8:5000/food/x` is refused. The description file itself must be readable without signing in.

## 3. Signing in

Reskon keeps the token on its server. It is never sent to the browser.

**With `"password"`:**

```
POST /api/reskon/login
{ "username": "aakash", "password": "..." }
→ 200 { "token": "long-random-string" }
→ 401 { "error": "wrong username or password" }
```

**With `"token"`:** the plugin shows the person a token in its own settings page, and the person pastes it into Reskon once.

After that, every call from Reskon carries the header `Authorization: Bearer <token>`. A plugin answers `401` when the token is wrong or withdrawn, and Reskon then asks the person to sign in again.

A plugin's token should not expire on its own, or the person has to sign in again each time it does.

## 4. Web page widgets

### 4.1 The ticket
The browser loads the page straight from the plugin, so it needs its own short sign-in. Before showing the frame, Reskon's server asks for a ticket:

```
POST /api/reskon/launch          (Authorization: Bearer <token>)
{ "widget": "today" }
→ 200 { "ticket": "one-time-string", "expires_in": 60 }
```

A ticket:
- works **once**;
- lasts at most **60 seconds**;
- is good only for that person;
- is at most 1024 characters of plain ASCII with no spaces (letters, digits and `-_.~` are safest). Reskon refuses anything else.

Reskon asks for a new ticket each time the widget is shown and each time the person presses the widget's refresh button, and loads the page again with it.

Reskon then opens the page with the ticket after `#`, so it is never sent in a request or written to a log:

```
http://192.168.0.8:5000/widgets/today#ticket=one-time-string&theme=...
```

The page reads `location.hash` and sends the ticket to its own server to swap it for a session. It keeps that session **in memory or `sessionStorage`, not a cookie**: browsers block or partition cookies inside a frame from another site, so a cookie may not be kept. Then the page removes the hash with `history.replaceState`.

With `"auth": {"kind": "none"}` there is no ticket, and Reskon just opens the address (with only `#theme=...`).

If the launch call answers `401` or `403`, Reskon tells the person to sign in to the app again in Settings › Apps.

### 4.2 Being allowed in a frame
By default many web servers refuse to be shown in a frame. The widget pages must allow Reskon:

```
Content-Security-Policy: frame-ancestors <Reskon's address, e.g. http://192.168.0.8:8080>
```

Do **not** send `X-Frame-Options: DENY` or `SAMEORIGIN` on widget pages. A good plugin lets the person set Reskon's address in its own settings and uses it here.

### 4.3 The frame is walled off
Reskon shows the page in a sandboxed frame. The page:
- can run its own scripts, call its own server and send forms;
- can open links in a new tab; links that should open the full plugin need `target="_blank"`;
- cannot reach Reskon's page or storage from its scripts;
- cannot move the top window (the Reskon tab) to another address, and neither can a tab it opens (Reskon sends `Cross-Origin-Opener-Policy: same-origin`).

The page must be on a different address (scheme, machine or port) from Reskon. Reskon refuses to show a page on its own address, because there the walls would not hold.

**Same machine name, shared cookies.** Browsers send a site's cookies to every port on the same machine name. A plugin at `http://192.168.0.8:8098` beside Reskon at `http://192.168.0.8:8080` therefore receives Reskon's sign-in cookie with every request the browser makes to it (its widget pages and its home page). The cookie is HttpOnly, so the page's scripts cannot read it, but the plugin's server can see it. A plugin must never log, keep or use cookies it did not set. People should connect only plugins they trust on Reskon's machine name, and Reskon warns them in Settings › Apps. A plugin on a different machine name (or behind HTTPS with its own name) does not receive it.

### 4.4 Size
Fill the frame (`width: 100%; height: 100%`) and scroll inside if needed. Widgets can be as narrow as about 300 px.

## 5. Matching Reskon's look

`#theme=` carries the theme colours as URL-encoded JSON:

```json
{ "mode": "dark", "bg": "#1d2125", "surface": "#22272b", "text": "#c7d1db",
  "muted": "#9fadbc", "accent": "#579dff", "border": "#38414a",
  "font": "system-ui, sans-serif", "radius": 6 }
```

Set these as CSS variables, with a transparent or `bg` background. `bg` is the colour of the widget around the page, so either blends in; `surface` is for boxes and rows on it. When the person changes theme, Reskon sends the new colours as a message (section 7); it also sends them once each time the page has loaded. Pages with `"style": "own"` can ignore all of this.

## 6. Data widgets

### 6.1 The request

```
GET /api/reskon/feeds/done-per-day?from=2026-11-09&to=2026-11-15&group=day&tz=Asia/Kolkata
Authorization: Bearer <token>
```

- **`from`, `to`**: whole days, `YYYY-MM-DD`, both included. Only sent if `ranged` is true. The person picks today, yesterday (the one day before their today), 7, 30 or 90 days, a year or this month, counted back from their today; or the dashboard's own dates, at most 400 days apart.
- **`group`**: one of the widget's `groups`, the first if the person chose none. Only sent if the widget lists `groups`.
- **`tz`**: the person's time zone, for example `Asia/Kolkata` (`UTC` until their browser has told Reskon). Use it for "today" and day boundaries.

Reskon asks each time the widget is drawn: when it is shown, on its refresh button, when the person's Reskon data changes, and when they change the dashboard's dates. Answer within **5 seconds**, and keep each answer under **1 MB**. Reskon gives up past either limit and shows its cached copy. A `401` or `403` means Reskon's token is no longer good: Reskon shows its cached copy and asks the person to sign in again.

### 6.2 The shapes
Every answer has `shape`, plus the fields for that shape. Reskon draws each shape with its own charts, so the person can pick the chart type (bar, line, number and so on) where the shape allows.

**`number`**: one value.
```json
{ "shape": "number", "label": "Streak", "unit": "days", "value": 12 }
```

**`series`**: values over time or by category. Draws as bar, line, cumulative or number.
```json
{ "shape": "series", "unit": "count",
  "points": [ { "key": "2026-11-14", "label": "14 Nov", "value": 3 },
              { "key": "2026-11-15", "label": "15 Nov", "value": 5 } ],
  "total": 8 }
```
- With `group=day`, `key` is the date.
- Optional `split` on a point (for example `"Meditate"`) gives one series per value.
- The list `series: [{ "key", "label", "color" }]` names those series.

**`table`**: rows and columns, drawn as a table.
```json
{ "shape": "table",
  "columns": [ { "key": "habit", "label": "Habit" }, { "key": "done", "label": "Days done", "unit": "count" } ],
  "rows": [ { "habit": "Meditate", "done": 6 }, { "habit": "Walk", "done": 4 } ] }
```

**`checklist`**: a list of items with done or not done. Read-only in Reskon: ticking happens in the plugin's web page widget.
```json
{ "shape": "checklist", "label": "Today",
  "items": [ { "key": "meditate", "label": "Meditate", "done": true, "detail": "10 min" },
             { "key": "walk", "label": "Walk", "done": false } ] }
```

**`list`**: label and value lines.
```json
{ "shape": "list", "items": [ { "label": "Longest streak", "value": "21 days" } ] }
```

Only these shapes exist. A plugin that needs something else asks for a new shape in Reskon first. Until then, use a web page widget.

**Rules for every shape:**
- Numbers are plain JSON numbers (not `"12"`), between -10¹² and 10¹².
- Text is plain text, never HTML. Reskon shows it as text, so markup is shown as it is. No control, direction-changing or invisible format characters (zero-width spaces, byte-order marks, soft hyphens). This also holds for names and titles in the description file.
- A `unit` is shown as written ("days", "glasses"), after the number.
- An empty range is `points: []`, `rows: []` or `items: []` with status `200`, never an error.
- `shape` must be the one the description file promises for that widget.

**What Reskon checks.** An answer that breaks any of these is not used at all: Reskon shows its cached copy and says the app "sent data Reskon cannot use".

| Field | Limit |
| --- | --- |
| `points` | at most 2000; each needs `key` and `value`; a `key` (with its `split`) only once; either every point has a `split` or none has |
| `series` | at most 24; a split that `series` does not name gets one named after it; `color` is `#rrggbb` or ignored |
| `columns` | 1 to 12; `key` is 1 to 40 letters, digits or `_`, used once |
| `rows` | at most 500; each value is text, a number, `true`/`false` or `null`; keys no column names are dropped |
| `items` | at most 500; a checklist item needs `label` and `done` (`true` or `false`), its `key` is optional; a list item needs `label`, and `value` is text or a number |
| text | keys 100 characters, labels 120, details, list values and table text 200, units 20 |

### 6.3 Caching
Reskon keeps the last answer for each widget and range, up to 10 MB per plugin per person, dropping the least recently read first. When the plugin can't be reached, Reskon shows that copy with "fetched at ...". A plugin doesn't need to do anything for this. It can send `"fetched_at"` (a time such as `"2026-11-15T08:30:00Z"`) when the data is older than the request; a time later than the request is ignored.

A copy is kept per range as the person chose it ("the last 7 days"), not per dates, so on a new day with the plugin down the widget still shows yesterday's copy, with its time.

## 7. Telling Reskon "my data changed"

So that Reskon's widgets update live, for example a ticked habit updating the habit chart beside it. Reskon then asks the plugin's data feeds again. It doesn't matter what changed, and Reskon never asks.

**From a widget page:** post a message to the Reskon page around it.
```js
window.parent.postMessage({ reskon: 1, type: "changed" }, reskonOrigin); // or "*": the message holds no secret
```
Reskon accepts this only from a frame it opened for that plugin, and only from the plugin's own address (the page's origin). It reads nothing in the message but `type`. The plugin's data widgets on that page fetch again, and so do those in the person's other Reskon tabs. Several messages in a row (a few quick ticks) are folded into one fetch. Reskon does **not** reload the plugin's page widgets: what the person is doing inside them is left alone.

**From the plugin's own server**, for changes made elsewhere (the plugin's own site, another device). Add `connect_url` to the description file (optional), for example `"connect_url": "/api/reskon/connect"`. When the person connects the plugin, Reskon calls it once:
```
POST <connect_url>            (Authorization: Bearer <token>)
{ "changed_url": "http://192.168.0.8:8080/api/apps/changed", "key": "random-key-for-this-app-and-person" }
→ 200 or 204
```
- `changed_url` is Reskon's address as the person's browser reached it when they connected the plugin, plus `/api/apps/changed`. If the plugin's server cannot reach Reskon at that address, connect from an address it can reach.
- `key` is a fresh random secret for this person and this plugin, 43 characters. Reskon calls `connect_url` again, with a new key, each time the person presses **Check again** or **Sign in again**: save the new key in place of the old one. Once the plugin has answered, the old key stops working; if the plugin does not take the new one, the old key keeps working. Removing the plugin in Reskon stops the key working.
- Answer `200` or `204`. Any other answer (or none within 5 seconds) means the plugin is still connected, but Settings › Apps says Reskon won't hear about changes made elsewhere, with the reason. `401` or `403` means "sign in again".

The plugin saves both. After a change it calls:
```
POST http://192.168.0.8:8080/api/apps/changed
Authorization: Bearer random-key-for-this-app-and-person
```
→ `204`, with no body needed (any body is ignored). The key only allows this one call, and Reskon answers `204` whether or not the key is still good, so the plugin can't tell, and needn't care. At most one change every 2 seconds per plugin per person is passed on (counting messages from its widget pages too); calls in between are folded into one, a moment later. A call without a key gets `401`. If the call fails, the plugin just drops it: widgets also refresh when opened and when their refresh button is pressed. Call it from the plugin's server, never from a browser: a page on another site is refused.

**Messages Reskon sends to a widget page:**
```js
{ reskon: 1, type: "theme", theme: { ...as in section 5 } }   // the person changed theme
{ reskon: 1, type: "refresh" }                                   // the person pressed the widget's refresh button
```
In this version Reskon does not send `refresh`: its refresh button loads the page again with a new ticket. A page should still handle `refresh` (reload its data), as a later Reskon may send it.
Check `event.origin` equals Reskon's address before acting on a message.

## 8. Checklist before saying a plugin is done

- [ ] `/.well-known/reskon-app.json` loads and has `"reskon_app": 1`.
- [ ] Wrong token → `401`. Right token → data.
- [ ] A ticket works once and not after 60 seconds.
- [ ] Widget pages load inside a frame on Reskon's address, and are refused on any other.
- [ ] Widget pages work with no cookies, and look right at 300 px wide and in Reskon's colours.
- [ ] Each feed answers an empty range with an empty list, not an error.
- [ ] Each feed answers in under 5 seconds, and under 1 MB for a year's range.
- [ ] Ticking something in a widget page posts `changed`.
- [ ] With `connect_url`: it answers `204`, keeps `changed_url` and `key`, and a change made outside Reskon calls `changed_url` with the key.
- [ ] No HTML in any text field. No secrets in any address.

## 9. Example: the habit tracker

| Widget | Kind | Shape | What it shows |
| --- | --- | --- | --- |
| Today's habits | page | | Today's habits, tickable |
| Today | data | `checklist` | Today's habits, read-only, in Reskon's style |
| Done per day | data | `series` | Habits done each day over the chosen range |
| Streaks | data | `table` | Each habit's current and longest streak |
| Longest streak | data | `number` | The longest streak of any habit |

The pretend habit tracker Reskon's tests use (`cmd/example-plugin`) offers these five, with ids `today`, `today_list`, `last_days`, `streaks` and `best_streak`.

A tick in "Today's habits" posts `changed`, and "Today", "Done per day" and "Streaks" refresh on their own.
