'use strict';
/* Habit Lab desktop web client: thin sync mirror + desktop screens. */
import { parseRunYaml, YAML_GUIDE_PROMPT } from './yaml.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ROMAN = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII'];
const roman = (n) => ROMAN[n - 1] || String(n);
const GROUP_ICON = { wellbeing: '☀', focus: '◉', health: '♥', fitness: '⛹', learning: '✎', sleep: '☾', general: '●' };
const groupIcon = (g) => GROUP_ICON[String(g || '').toLowerCase()] || '●';

function todayLocal(d) {
  const t = d || new Date();
  return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`;
}
function addDays(iso, n) {
  const d = new Date(iso + 'T12:00:00');
  d.setDate(d.getDate() + n);
  return todayLocal(d);
}
function daysBetween(a, b) {
  return Math.round((new Date(b + 'T12:00:00') - new Date(a + 'T12:00:00')) / 86400000);
}
function fmtDay(iso) {
  return new Date(iso + 'T12:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}
function randHex(n) {
  const a = new Uint8Array(n);
  crypto.getRandomValues(a);
  return Array.from(a, (x) => x.toString(16).padStart(2, '0')).join('');
}
function uuid() {
  return [8, 4, 4, 4, 12].map((n) => randHex(n / 2)).join('-');
}
function toast(msg) {
  let el = $('snack');
  if (!el) {
    el = document.createElement('div');
    el.id = 'snack';
    el.className = 'snack';
    document.body.appendChild(el);
  }
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(window.snackTimer);
  window.snackTimer = setTimeout(() => el.classList.remove('show'), 2200);
}

/* ---------- local mirror ---------- */
const LS = { account: 'habitlab.account', key: 'habitlab.key', device: 'habitlab.device', token: 'habitlab.token', clock: 'habitlab.clock', db: 'habitlab.db', inf: 'habitlab.infinite' };
const TABLES = ['habit', 'run', 'goal', 'tracker', 'run_habit', 'check_in', 'tracker_entry'];
let mirror = {};
let dirty = new Map();
let dirtySeq = 0;
function loadMirror() {
  mirror = {};
  try { mirror = JSON.parse(localStorage.getItem(LS.db) || '{}'); } catch { mirror = {}; }
  for (const t of TABLES) if (!mirror[t]) mirror[t] = {};
  try { dirty = new Map(JSON.parse(localStorage.getItem('habitlab.dirty') || '[]').map((k) => [k, 0])); }
  catch { dirty = new Map(); }
}
function saveMirror() {
  localStorage.setItem(LS.db, JSON.stringify(mirror));
  localStorage.setItem('habitlab.dirty', JSON.stringify([...dirty.keys()]));
}
function rows(t) {
  return Object.values(mirror[t] || {}).map((r) => r.v).filter((v) => v._deleted !== true);
}
function row(t, u) { return (mirror[t] || {})[u]; }
function nextClock() {
  const last = Number(localStorage.getItem(LS.clock) || 0);
  const c = Math.max(Date.now(), last + 1);
  localStorage.setItem(LS.clock, String(c));
  return c;
}
function deviceId() {
  let d = localStorage.getItem(LS.device);
  if (!d) { d = uuid(); localStorage.setItem(LS.device, d); }
  return d;
}
function putRow(t, u, values) {
  const cur = row(t, u) || { v: {}, s: {} };
  const c = nextClock(), d = deviceId();
  const v = { ...cur.v, ...values };
  const s = { ...cur.s };
  for (const k of Object.keys(values)) s[k] = { c, d };
  // A live write resurrects locally; tombstones only travel via deleteRow.
  delete v._deleted;
  delete s._deleted;
  if (!mirror[t]) mirror[t] = {};
  mirror[t][u] = { v, s };
  dirty.set(t + '|' + u, ++dirtySeq);
  saveMirror();
  scheduleSync();
}
/** Tombstone a row: the delete syncs, and the mirror drops it at once. */
function deleteRow(t, u) {
  const cur = row(t, u);
  if (!cur) return;
  const c = nextClock(), d = deviceId();
  if (!mirror[t]) mirror[t] = {};
  mirror[t][u] = { v: { _deleted: true }, s: { ...cur.s, _deleted: { c, d } } };
  dirty.set(t + '|' + u, ++dirtySeq);
  saveMirror();
  scheduleSync();
  renderCurrent();
}
function infiniteOn() {
  try {
    const m = JSON.parse(localStorage.getItem(LS.inf) || '{}');
    return !!m[localStorage.getItem(LS.account) || ''];
  } catch { return false; }
}
function setInfinite(on) {
  let m = {};
  try { m = JSON.parse(localStorage.getItem(LS.inf) || '{}'); } catch { m = {}; }
  const a = localStorage.getItem(LS.account) || '';
  if (on) m[a] = 1; else delete m[a];
  localStorage.setItem(LS.inf, JSON.stringify(m));
}

/* ---------- wire ---------- */
async function api(path, body, authed) {
  const headers = { 'Content-Type': 'application/json' };
  if (authed !== false) {
    headers['X-Account-Id'] = localStorage.getItem(LS.account) || '';
    headers['Authorization'] = 'Bearer ' + (localStorage.getItem(LS.key) || '');
  }
  const res = await fetch(path, { method: 'POST', headers, body: JSON.stringify(body) });
  if (res.status === 401) throw new Error('auth');
  if (!res.ok) throw new Error('http ' + res.status);
  return res.json();
}
let syncing = false, syncQueued = false;
function setSync(state, label) {
  const dot = $('syncDot');
  if (dot) dot.className = 'sync-dot' + (state ? ' ' + state : '');
  const lab = $('syncLabel');
  if (lab) lab.textContent = label;
}
function scheduleSync() {
  if (syncing) { syncQueued = true; return; }
  sync();
}
async function sync() {
  if (!localStorage.getItem(LS.key)) return;
  if (syncing) { syncQueued = true; return; }
  syncing = true;
  setSync('busy', 'Syncing…');
  try {
    const snap = new Map(dirty);
    const changes = [...snap.keys()].map((k) => {
      const i = k.indexOf('|'), t = k.slice(0, i), u = k.slice(i + 1);
      const r = row(t, u);
      return r ? { t, u, v: r.v, s: r.s } : null;
    }).filter(Boolean);
    const resp = await api('/api/sync', { device: deviceId(), token: Number(localStorage.getItem(LS.token) || 0), changes });
    for (const r of resp.changes || []) {
      if (!mirror[r.t]) mirror[r.t] = {};
      if (r.v && r.v._deleted === true) delete mirror[r.t][r.u];
      else mirror[r.t][r.u] = { v: r.v, s: r.s };
    }
    localStorage.setItem(LS.token, String(resp.token));
    for (const [k, s] of snap) if (dirty.get(k) === s) dirty.delete(k);
    saveMirror();
    setSync('ok', 'Synced');
    renderCurrent();
  } catch (e) {
    setSync(e.message === 'auth' ? 'err' : '', e.message === 'auth' ? 'Sign in again' : 'Offline');
    if (e.message === 'auth') signOut();
  } finally {
    syncing = false;
    if (syncQueued) { syncQueued = false; sync(); } else renderCurrent();
  }
}

/* ---------- model ---------- */
function latestRun() {
  const runs = rows('run').filter((r) => r.status === 'active');
  runs.sort((a, b) => (a.start_date < b.start_date ? 1 : -1));
  return runs[0] || null;
}
function allRuns() {
  const runs = rows('run');
  runs.sort((a, b) => (a.start_date < b.start_date ? 1 : -1));
  return runs;
}
function habitsForRun(runUid) {
  const ids = new Set(rows('run_habit').filter((r) => r.run_uid === runUid).map((r) => r.habit_uid));
  return rows('habit').filter((h) => ids.has(h.uid) && !h.archived)
    .sort((a, b) => (a.group_name + a.title < b.group_name + b.title ? -1 : 1));
}
function unlinkedHabits() {
  const linked = new Set(rows('run_habit').map((r) => r.habit_uid));
  return rows('habit').filter((h) => !linked.has(h.uid) && !h.archived)
    .sort((a, b) => (a.group_name + a.title < b.group_name + b.title ? -1 : 1));
}
function runsUsingHabit(habitUid) {
  const runIds = new Set(rows('run_habit').filter((r) => r.habit_uid === habitUid).map((r) => r.run_uid));
  return rows('run').filter((r) => runIds.has(r.uid));
}
function checkInsByDay(day) {
  const out = {};
  for (const c of rows('check_in')) if (c.day === day) out[c.habit_uid] = c;
  return out;
}
function recurrenceLabel(h) {
  return h.recurrence_type === 'interval' ? `Every ${h.recurrence_value} day${h.recurrence_value === 1 ? '' : 's'}` : `${h.recurrence_value} days per week`;
}
function dueHabits(today, list) {
  const doneToday = new Set(Object.values(checkInsByDay(today)).filter((c) => c.state === 'done').map((c) => c.habit_uid));
  return list.filter((h) => {
    if (doneToday.has(h.uid)) return false;
    if (h.recurrence_type === 'interval') {
      const n = h.recurrence_value || 1;
      const recent = rows('check_in').filter((c) => c.habit_uid === h.uid && c.state === 'done')
        .map((c) => c.day).sort().reverse()[0];
      if (!recent) return true;
      for (let i = 0; i < n; i++) if (addDays(today, -i) === recent) return false;
      return true;
    }
    return true;
  });
}
function runDayIndex(run, day) {
  return daysBetween(run.start_date, day);
}
function logCheckIn(habitUid, day, state, note) {
  const values = { uid: habitUid + ':' + day, habit_uid: habitUid, day, state };
  if (note) values.comment = note;
  putRow('check_in', habitUid + ':' + day, values);
}
function slug(s) {
  return String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 24) || 'habit';
}
/* Server-side deletes are permanent per uid, so a re-attached run link
 * gets a fresh uid instead of reusing the tombstoned one. */
function linkUid(runUid, habitUid) {
  const base = `${runUid}:${habitUid}`;
  const cur = row('run_habit', base);
  if (cur && cur.v._deleted === true) return `${base}#${Date.now().toString(36)}`;
  return base;
}

/* ---------- navigation ---------- */
const TITLES = { runs: 'Runs & Habits', analytics: 'Analytics', goals: 'Goals', trackers: 'Trackers', quicklog: 'Quick Log', builder: 'New run', edit: 'Edit run' };
let currentView = 'runs';
function showView(id) {
  currentView = id;
  document.querySelectorAll('.view').forEach((s) => s.classList.toggle('active', s.id === 'v-' + id));
  document.querySelectorAll('.sidenav [data-view]').forEach((b) => b.classList.toggle('active', b.dataset.view === id));
  $('topTitle').textContent = TITLES[id] || id;
  window.scrollTo(0, 0);
  renderCurrent();
}
function renderCurrent() {
  if (!localStorage.getItem(LS.key)) return;
  if (currentView === 'runs') renderRuns();
  else if (currentView === 'analytics') renderAnalytics();
  else if (currentView === 'goals') renderGoals();
  else if (currentView === 'trackers') renderTrackers();
  else if (currentView === 'quicklog') renderQuick();
  else if (currentView === 'builder') renderBuilder();
  else if (currentView === 'edit') renderEdit();
}
document.querySelectorAll('.sidenav [data-view]').forEach((b) => b.addEventListener('click', () => showView(b.dataset.view)));

/* ---------- login + account ---------- */
function setLoggedIn(on) {
  $('desk').style.display = on ? '' : 'none';
  $('loginWrap').style.display = on ? 'none' : '';
  if (!on) return;
  const a = localStorage.getItem(LS.account) || '';
  $('acctBtn').textContent = (a[0] || '?').toUpperCase();
  $('sideUser').textContent = a;
  renderAcctMenu();
}
function renderAcctMenu() {
  const m = $('acctMenu');
  const a = localStorage.getItem(LS.account) || '';
  m.innerHTML = `<div class="eyebrow">Signed in</div><div class="who" style="margin-top:6px">${esc(a)}</div>
    <div class="sub">Synced across your devices.</div>
    <button class="build-button" id="acctSignOut">Sign out</button>`;
  $('acctSignOut').addEventListener('click', () => { m.classList.remove('open'); signOut(); });
}
function signOut() {
  for (const k of Object.values(LS)) localStorage.removeItem(k);
  localStorage.removeItem('habitlab.dirty');
  mirror = {}; dirty = new Map();
  setLoggedIn(false);
  $('loginPass').value = '';
}
async function onLogin(e) {
  e.preventDefault();
  const user = $('loginUser').value.trim(), pass = $('loginPass').value;
  const err = $('loginError');
  err.classList.remove('show');
  if (!user || !pass) { err.textContent = 'Enter your username and password.'; err.classList.add('show'); return; }
  $('loginBtn').disabled = true;
  try {
    const resp = await api('/api/sign-in', { username: user, password: pass }, false);
    localStorage.setItem(LS.account, resp.account);
    localStorage.setItem(LS.key, resp.accessToken);
    if (!localStorage.getItem(LS.token)) localStorage.setItem(LS.token, '0');
    deviceId();
    $('loginPass').value = '';
    setLoggedIn(true);
    showView('runs');
    sync();
  } catch (ex) {
    err.textContent = ex.message === 'auth' ? 'Invalid username or password.' : 'Cannot reach the sync server. Check your connection.';
    err.classList.add('show');
  } finally {
    $('loginBtn').disabled = false;
  }
}
function boot() {
  loadMirror();
  $('loginForm').addEventListener('submit', onLogin);
  $('acctBtn').addEventListener('click', (e) => { e.stopPropagation(); $('acctMenu').classList.toggle('open'); });
  document.addEventListener('click', (e) => { if (!e.target.closest('.acct-wrap')) $('acctMenu').classList.remove('open'); });
  if (localStorage.getItem(LS.key)) {
    setLoggedIn(true);
    showView('runs');
    setSync('', 'Connecting…');
    sync();
  } else {
    setLoggedIn(false);
  }
}

/* ---------- tooltips ---------- */
function showTip(html, x, y) {
  const tip = $('tip');
  tip.innerHTML = html;
  tip.style.display = 'block';
  tip.style.left = x + 'px';
  tip.style.top = y + 'px';
}
function hideTip() { $('tip').style.display = 'none'; }
function wireBarTips(scope) {
  scope.querySelectorAll('.chart .bar').forEach((b) => {
    b.addEventListener('mousemove', (e) => showTip(`Day <b>${b.dataset.day}</b> · <b>${b.dataset.val}</b> pts`, e.clientX, e.clientY));
    b.addEventListener('mouseleave', hideTip);
  });
}
function burnupPaths(values, elapsed, dur) {
  // values: per-day earned; elapsed: days so far; returns {actual, forecast, yMax, cum}.
  const cum = [];
  let acc = 0;
  for (let i = 0; i < elapsed; i++) { acc += values[i] || 0; cum.push(acc); }
  const pace = elapsed ? acc / elapsed : 0;
  const yMax = Math.max(acc, pace * dur, 1);
  const X = (i) => (20 + (i * 300) / Math.max(dur - 1, 1)).toFixed(1);
  const Y = (v) => (116 - (v / yMax) * 96).toFixed(1);
  const actual = cum.map((v, i) => `${i ? 'L' : 'M'} ${X(i)} ${Y(v)}`).join(' ');
  let forecast = '';
  for (let i = elapsed - 1; i < dur; i++) {
    forecast += `${forecast ? 'L' : 'M'} ${X(i)} ${Y(acc + pace * (i - elapsed + 1))} `;
  }
  return { actual, forecast, yMax, cum, pace, X, Y };
}
function burnupSvg(id, values, elapsed, dur, baseDay) {
  const b = burnupPaths(values, elapsed, dur);
  let dots = '';
  b.cum.forEach((v, i) => { dots += `<circle class="dot-pt" cx="${b.X(i)}" cy="${b.Y(v)}" r="11" data-day="${baseDay + i}" data-val="${Math.round(v)}" data-label="actual"/>`; });
  for (let i = elapsed - 1; i < dur; i++) {
    const v = (b.cum[b.cum.length - 1] || 0) + b.pace * (i - elapsed + 1);
    dots += `<circle class="dot-pt" cx="${b.X(i)}" cy="${b.Y(v)}" r="11" data-day="${baseDay + i}" data-val="${Math.round(v)}" data-label="forecast"/>`;
  }
  return `<svg id="${id}" viewBox="0 0 330 148" style="width:100%;height:auto;margin-top:8px" role="img" aria-label="Points burnup">
    <line x1="20" y1="116" x2="320" y2="116" stroke="#465349"/><line x1="20" y1="14" x2="20" y2="116" stroke="#465349"/>
    <path d="${b.actual}" fill="none" stroke="#a4d0ad" stroke-width="3" stroke-linecap="round"/>
    <path d="${b.forecast}" fill="none" stroke="#dfbd82" stroke-width="3" stroke-dasharray="6 5" stroke-linecap="round"/>
    <text x="20" y="136" fill="#a3afa6" font-size="9">Day ${baseDay}</text><text x="285" y="136" fill="#a3afa6" font-size="9">Day ${baseDay + dur - 1}</text>${dots}</svg>`;
}
function wireBurnupTips(scope) {
  scope.querySelectorAll('.dot-pt').forEach((c) => {
    c.addEventListener('mousemove', (e) => showTip(`Day <b>${c.dataset.day}</b> · <b>${c.dataset.val}</b> pts <span class="caption">${c.dataset.label}</span>`, e.clientX, e.clientY));
    c.addEventListener('mouseleave', hideTip);
  });
}
function barChart(values, todayIdx) {
  const maxE = Math.max(1, ...values);
  const bars = values.map((v, i) => `<div class="bar${i === todayIdx ? ' today' : ''}" data-day="${i + 1}" data-val="${v}"><i style="height:${Math.max(4, Math.round((v / maxE) * 100))}%"></i></div>`).join('');
  const labels = values.map((_, i) => `<span class="${i === todayIdx ? 'today' : ''}">${i + 1}</span>`).join('');
  return `<div class="chart">${bars}</div><div class="xlabels">${labels}</div>`;
}

/* ---------- Runs ---------- */
function earnedOn(habits, day) {
  const pts = {};
  for (const h of habits) pts[h.uid] = h.impact_points;
  let total = 0;
  for (const c of Object.values(checkInsByDay(day))) {
    if (c.state === 'done' && pts[c.habit_uid]) total += pts[c.habit_uid];
  }
  return total;
}
function habitTable(habits, startDate, count, todayIso, numberedBase) {
  // numberedBase: day number shown under the first column (1 for runs,
  // calendar offset for trailing windows). Returns {head, rows}.
  const cols = `grid-template-columns:repeat(${count},1fr)`;
  let head = `<div class="hrow head"><span></span><span></span><div class="daynums" style="${cols}">`;
  for (let d = 0; d < count; d++) {
    const day = addDays(startDate, d);
    head += `<span class="${day === todayIso ? 'today' : ''}">${numberedBase + d}</span>`;
  }
  head += `</div><span></span></div>`;
  let body = '';
  for (const h of habits) {
    body += `<div class="hrow"><div class="symbol">${groupIcon(h.group_name)}</div>
      <div><div class="habit-name">${esc(h.title)}<span class="iteration">${roman(h.iteration || 1)}</span></div>
      <div class="habit-meta">${h.impact_points} impact pts · ${esc(recurrenceLabel(h).toLowerCase())}</div></div>
      <div class="dots" style="${cols}">`;
    for (let d = 0; d < count; d++) {
      const day = addDays(startDate, d);
      const c = checkInsByDay(day)[h.uid];
      const future = day > todayIso;
      body += future
        ? `<span class="dot future"></span>`
        : `<button class="dot ${c ? c.state : ''}${day === todayIso ? ' today' : ''}" aria-label="${esc(h.title)} ${day}" onclick="toggleDay('${h.uid}','${day}')"></button>`;
    }
    body += `</div><div class="point">${h.impact_points}</div></div>`;
  }
  return `<div class="htable">${head}<div id="runTable">${body}</div></div>`;
}
function toggleDay(habitUid, day) {
  const cur = checkInsByDay(day)[habitUid];
  const state = cur && cur.state === 'done' ? 'skipped' : 'done';
  const values = { uid: habitUid + ':' + day, habit_uid: habitUid, day, state };
  if (cur && cur.comment) values.comment = cur.comment;
  putRow('check_in', habitUid + ':' + day, values);
  renderCurrent();
}
function standaloneSection() {
  const list = unlinkedHabits();
  if (!list.length) return '';
  const today = todayLocal();
  let html = `<div class="section-head"><h3>Standalone habits</h3><span class="caption">Not in any run · always due</span></div><div class="htable">`;
  for (const h of list) {
    const c = checkInsByDay(today)[h.uid];
    html += `<div class="hrow" style="grid-template-columns:34px minmax(170px,1.1fr) minmax(0,2fr) 44px"><div class="symbol">${groupIcon(h.group_name)}</div>
      <div><div class="habit-name">${esc(h.title)}<span class="iteration">${roman(h.iteration || 1)}</span></div>
      <div class="habit-meta">${h.impact_points} impact pts · ${esc(recurrenceLabel(h).toLowerCase())}</div></div>
      <div><button class="btn${c && c.state === 'done' ? ' primary' : ''}" onclick="toggleDay('${h.uid}','${today}')">${c && c.state === 'done' ? '✓ Done today' : c ? esc(c.state) + ' · tap for done' : 'Log today'}</button></div>
      <div class="point">${h.impact_points}</div></div>`;
  }
  html += `</div><button class="add-habit" onclick="openStandalone()">＋ New standalone habit</button><div id="standaloneForm"></div>`;
  return html;
}
function renderRuns() {
  const body = $('runsBody');
  const run = latestRun();
  const today = todayLocal();
  if (!run) {
    body.innerHTML = infiniteOn() ? infiniteView(today) : emptyRunsView();
    wireBarTips(body); wireBurnupTips(body);
    return;
  }
  const dur = run.duration_days || 21;
  const elapsed = Math.min(Math.max(runDayIndex(run, today) + 1, 1), dur);
  const endDay = addDays(run.start_date, dur - 1);
  const habits = habitsForRun(run.uid);
  let earned = 0;
  const perDay = [];
  for (let i = 0; i < elapsed; i++) {
    const e = earnedOn(habits, addDays(run.start_date, i));
    perDay.push(e);
    earned += e;
  }
  const possible = habits.reduce((s, h) => s + h.impact_points, 0) * elapsed;
  const pct = possible ? Math.round((earned / possible) * 100) : 0;
  const groups = {};
  for (const h of habits) (groups[h.group_name] = groups[h.group_name] || []).push(h);
  let html = `<div class="run-hero"><div><div class="run-dates">${fmtDay(run.start_date)} — ${fmtDay(endDay)} · ${habits.length} habits</div>
    <div style="display:flex;align-items:baseline;gap:12px;margin-top:10px"><span class="big-pct">${pct}%</span></div>
    <div class="track"><i style="width:${pct}%"></i></div>
    <div class="caption" style="margin-top:8px;color:#c4d2c7">${earned} / ${possible}</div></div>
    <div class="metrics-card"><div class="metrics">
    <div class="metric"><small>Total impact</small><b>${earned} pts</b></div>
    <div class="metric"><small>Average impact</small><b>${elapsed ? (earned / elapsed).toFixed(1) : '0.0'} / day</b></div>
    <div class="metric"><small>Days logged</small><b>${perDay.filter((e) => e > 0).length} / ${elapsed}</b></div>
    <div class="metric"><small>Habits</small><b>${habits.length}</b></div>
    </div></div></div>
    <div class="section-head"><h3>Habits</h3><span class="head-btns"><button class="btn" onclick="openBuilder()">＋ New run</button><button class="btn" onclick="openEdit('${run.uid}')">Edit run</button></span></div>
    ${habitTable(habits, run.start_date, dur, today, 1)}
    <div class="charts-2">
    <div class="card"><div class="chart-title">Points per day</div><div class="caption">Whole run · hover a bar for values</div>
    ${barChart(perDay.concat(Array(Math.max(0, dur - elapsed)).fill(0)), elapsed - 1)}
    <div class="chart-key"><span>Earned impact points</span><span>${fmtDay(run.start_date)} – ${fmtDay(endDay)}</span></div></div>
    <div class="card"><div class="chart-title">Points burnup</div><div class="caption">Cumulative actual and predicted · hover the line for values</div>
    ${burnupSvg('burnSvg', perDay, elapsed, dur, 1)}
    <div class="chart-key"><span>━ Actual</span><span>┄ Predicted</span></div></div>
    </div>
    ${standaloneSection()}`;
  body.innerHTML = html;
  wireBarTips(body); wireBurnupTips(body);
}
function emptyRunsView() {
  return `<div class="page-intro"><div class="eyebrow">Runs</div><h1 style="font-size:26px;font-weight:500;margin:0 0 4px">No run running</h1>
    <div class="sub">Build a fixed-length run, or continue without one.</div></div>
    <div class="empty">Nothing here yet.<br><br><button class="btn primary" onclick="openBuilder()">Build a run</button> <button class="btn" onclick="setInfinite(true);renderRuns()">Continue without a run</button></div>
    ${standaloneSection()}`;
}
function infiniteView(today) {
  const list = unlinkedHabits();
  const start = addDays(today, -20);
  const perDay = [];
  for (let i = 0; i < 21; i++) perDay.push(earnedOn(list, addDays(start, i)));
  // Burnup carries the all-time baseline so the line never starts at zero.
  let baseline = 0;
  for (const c of rows('check_in')) {
    if (c.state !== 'done' || c.day >= start) continue;
    const h = row('habit', c.habit_uid);
    if (h && !h.v.archived) baseline += h.v.impact_points;
  }
  let html = `<div class="section-head"><h3>Habits</h3><span class="head-btns"><span class="caption">Infinite run · last 21 days</span><button class="btn primary" onclick="openBuilder()">Build a run</button><button class="btn" onclick="setInfinite(false);renderRuns()">Exit</button></span></div>`;
  if (!list.length) {
    html += `<div class="empty">No standalone habits yet.<br>Add one below — it stays due with or without a run.</div>`;
  } else {
    html += habitTable(list, start, 21, today, daysBetween(start, today) + 1 - 20);
  }
  html += `<div class="charts-2">
    <div class="card"><div class="chart-title">Points per day</div><div class="caption">Last 21 days · hover a bar for values</div>
    ${barChart(perDay, 20)}
    <div class="chart-key"><span>Earned impact points</span><span>${fmtDay(start)} – ${fmtDay(today)}</span></div></div>
    <div class="card"><div class="chart-title">Points burnup</div><div class="caption">Last 21 days from a ${baseline}-point baseline · hover for values</div>
    ${infiniteBurnup(perDay, baseline)}
    <div class="chart-key"><span>━ Actual</span><span>┄ Pace</span></div></div>
    </div>
    <button class="add-habit" onclick="openStandalone()">＋ New standalone habit</button><div id="standaloneForm"></div>`;
  return html;
}
function infiniteBurnup(perDay, baseline) {
  const cum = [];
  let acc = 0;
  for (const v of perDay) { acc += v; cum.push(baseline + acc); }
  const total = baseline + acc;
  const pace = acc / 21;
  const yMax = Math.max(total, total + pace * 3, 1);
  const X = (i) => (20 + (i * 300) / 20).toFixed(1);
  const Y = (v) => (116 - (v / yMax) * 96).toFixed(1);
  const actual = cum.map((v, i) => `${i ? 'L' : 'M'} ${X(i)} ${Y(v)}`).join(' ');
  let forecast = '';
  for (let i = 20; i < 24; i++) {
    const v = total + pace * (i - 20);
    forecast += `${forecast ? 'L' : 'M'} ${(20 + Math.min(i, 20) * 15 + (i > 20 ? (i - 20) * 15 : 0)).toFixed(1)} ${Y(v)} `;
  }
  let dots = '';
  cum.forEach((v, i) => { dots += `<circle class="dot-pt" cx="${X(i)}" cy="${Y(v)}" r="11" data-day="D-${20 - i}" data-val="${Math.round(v)}" data-label="total"/>`; });
  return `<svg viewBox="0 0 330 148" style="width:100%;height:auto;margin-top:8px" role="img" aria-label="Points burnup">
    <line x1="20" y1="116" x2="320" y2="116" stroke="#465349"/><line x1="20" y1="14" x2="20" y2="116" stroke="#465349"/>
    <path d="${actual}" fill="none" stroke="#a4d0ad" stroke-width="3" stroke-linecap="round"/>
    <path d="${forecast}" fill="none" stroke="#dfbd82" stroke-width="3" stroke-dasharray="6 5" stroke-linecap="round"/>
    <text x="20" y="136" fill="#a3afa6" font-size="9">${fmtDay(addDays(todayLocal(), -20))}</text><text x="285" y="136" fill="#a3afa6" font-size="9">Today</text>${dots}</svg>`;
}

/* ---------- Quick Log (kept, nav hidden) ---------- */
let quickList = [], quickResults = [], quickCommentOpen = false, quickLogged = new Set(), quickSession = '';
function renderQuick() {
  const today = todayLocal();
  const run = latestRun();
  const session = today + '|' + (run ? run.uid : '');
  if (session !== quickSession) { quickSession = session; quickLogged = new Set(); quickResults = []; }
  const pool = run ? habitsForRun(run.uid).concat(unlinkedHabits()) : unlinkedHabits();
  quickList = dueHabits(today, pool).filter((h) => !quickLogged.has(h.uid));
  const host = $('quickBody');
  let html = `<div class="quick-progress" style="display:flex;justify-content:space-between;margin:2px 2px 12px;max-width:460px"><strong>Today's due habits</strong><span class="pill">${quickList.length ? (quickResults.length + 1) + ' of ' + (quickResults.length + quickList.length) : 'Complete'}</span></div>`;
  if (!quickList.length) {
    const done = quickResults.filter((x) => x === 'done').length;
    const partial = quickResults.filter((x) => x === 'partial').length;
    const skipped = quickResults.filter((x) => x === 'skipped').length;
    host.innerHTML = html + `<div class="quick-summary"><h3 style="font-size:20px;font-weight:500">Quick Log complete</h3><p class="sub">${done} done · ${partial} partial · ${skipped} skipped</p></div>`;
    return;
  }
  const h = quickList[0];
  const next = quickList[1];
  host.innerHTML = html + `<div class="quick-stack">${next ? `<div class="quick-card quick-under"><div class="quick-under-name">${esc(next.title)}</div></div>` : ''}
    <article class="quick-card" id="swipeCard"><div class="swipe-stamp done" id="stampDone">DONE</div>
    <div class="swipe-stamp skip" id="stampSkip">SKIP</div>
    <div class="quick-art"><span class="quick-art-label">${esc((h.group_name || '').toUpperCase())} · DUE</span>
    <div class="quick-art-icon">${groupIcon(h.group_name)}</div></div>
    <div class="quick-habit-name">${esc(h.title)}</div>
    <div class="quick-meta">${esc(recurrenceLabel(h))} · due today</div>
    <div class="quick-impact">${h.impact_points} impact points</div>
    <div class="comment-box" id="quickComment" style="display:${quickCommentOpen ? 'block' : 'none'}">
    <textarea class="field" id="quickNoteText" placeholder="Add a note about this habit today"></textarea></div>
    </article></div>
    <div class="quick-actions"><button class="quick-action partial" onclick="recordQuick('partial')"><span>◐</span> Partial</button>
    <button class="comment-btn" onclick="setQuickComment(!quickCommentOpen)">✎ Comment</button></div>
    <div class="quick-note">Swipe right to mark done · swipe left to skip. Skipped interval habits stay due until completed.</div>`;
  const card = $('swipeCard');
  card.addEventListener('pointerdown', startQuickDrag);
  card.addEventListener('pointermove', moveQuickDrag);
  card.addEventListener('pointerup', endQuickDrag);
  card.addEventListener('pointercancel', endQuickDrag);
}
function setQuickComment(on) { quickCommentOpen = on; renderQuick(); }
let quickDragging = false, quickStartX = 0, quickDragX = 0;
function startQuickDrag(e) {
  if (e.target.closest('button,textarea')) return;
  quickDragging = true; quickStartX = e.clientX; quickDragX = 0;
  e.currentTarget.classList.add('dragging');
  try { e.currentTarget.setPointerCapture(e.pointerId); } catch { /* noop */ }
}
function moveQuickDrag(e) {
  if (!quickDragging) return;
  quickDragX = e.clientX - quickStartX;
  e.currentTarget.style.transform = `translateX(${quickDragX * 0.82}px) rotate(${quickDragX / 18}deg)`;
  $('stampDone').style.opacity = Math.max(0, Math.min(1, quickDragX / 90));
  $('stampSkip').style.opacity = Math.max(0, Math.min(1, -quickDragX / 90));
}
function endQuickDrag(e) {
  if (!quickDragging) return;
  quickDragging = false;
  const card = e.currentTarget;
  card.classList.remove('dragging');
  if (quickDragX > 95) { card.style.transform = 'translateX(120%) rotate(22deg)'; setTimeout(() => recordQuick('done'), 190); }
  else if (quickDragX < -95) { card.style.transform = 'translateX(-120%) rotate(-22deg)'; setTimeout(() => recordQuick('skip'), 190); }
  else { card.style.transform = ''; $('stampDone').style.opacity = 0; $('stampSkip').style.opacity = 0; }
}
function recordQuick(kind) {
  if (!quickList.length) return;
  const h = quickList[0];
  const state = kind === 'skip' ? 'skipped' : kind;
  const noteEl = $('quickNoteText');
  const note = noteEl && noteEl.value.trim() ? noteEl.value.trim() : null;
  logCheckIn(h.uid, todayLocal(), state, note);
  quickResults.push(state);
  quickLogged.add(h.uid);
  quickCommentOpen = false;
  renderQuick();
}

/* ---------- Analytics / Goals / Trackers ---------- */
function renderAnalytics() {
  const body = $('analyticsBody');
  const run = latestRun();
  const today = todayLocal();
  if (!run) { body.innerHTML = `<div class="empty">No run data yet.</div>`; return; }
  const dur = run.duration_days || 21;
  const elapsed = Math.min(Math.max(runDayIndex(run, today) + 1, 1), dur);
  const habits = habitsForRun(run.uid);
  const groups = {};
  for (const h of habits) (groups[h.group_name] = groups[h.group_name] || []).push(h);
  const stats = Object.entries(groups).map(([g, list]) => {
    const pts = {};
    for (const h of list) pts[h.uid] = h.impact_points;
    let earned = 0;
    for (let i = 0; i < elapsed; i++) {
      for (const c of Object.values(checkInsByDay(addDays(run.start_date, i)))) {
        if (c.state === 'done' && pts[c.habit_uid]) earned += pts[c.habit_uid];
      }
    }
    const possible = list.reduce((s, h) => s + h.impact_points, 0) * elapsed;
    return { g, earned, possible, pct: possible ? Math.round((earned / possible) * 100) : 0 };
  });
  const maxP = Math.max(1, ...stats.map((s) => s.pct));
  let html = `<div class="ana-grid">
    <div class="card"><div class="chart-title">Impact completion by group</div><div class="caption">This run · earned vs possible so far</div>
    <div class="group-bars">`;
  for (const s of stats) {
    html += `<div class="bar"><i style="height:${Math.max(4, Math.round((s.pct / maxP) * 100))}%"></i><label>${esc(s.g)} · ${s.pct}%</label></div>`;
  }
  html += `</div><div class="chart-key"><span>Earned vs possible so far</span><span>${fmtDay(run.start_date)}–${fmtDay(addDays(run.start_date, dur - 1))}</span></div></div>
    <div class="card"><div class="chart-title">Daily impact</div><div class="caption">Whole run</div>`;
  const perDay = [];
  for (let i = 0; i < elapsed; i++) perDay.push(earnedOn(habits, addDays(run.start_date, i)));
  html += `${barChart(perDay.concat(Array(Math.max(0, dur - elapsed)).fill(0)), elapsed - 1)}
    <div class="chart-key"><span>Earned impact points</span><span>Day 1–${dur}</span></div></div></div>
    <div class="card" style="margin-top:14px"><div class="chart-title">Group totals</div><div style="margin-top:6px">`;
  for (const s of stats) html += `<div class="grow"><span>${esc(s.g)}</span><b>${s.pct}% · ${s.earned} / ${s.possible}</b></div>`;
  html += `</div></div>`;
  body.innerHTML = html;
  wireBarTips(body);
}
function renderGoals() {
  const body = $('goalsBody');
  const goals = rows('goal').sort((a, b) => (a.start_date < b.start_date ? 1 : -1));
  let html = `<div class="section-head" style="margin-top:0"><h3>Your goals</h3><span class="caption">Progress from each start date</span></div><div class="cards-3">`;
  for (const g of goals) {
    const pct = Math.round((g.progress || 0) * 100);
    html += `<div class="goal"><div class="row"><div class="goal-title">${esc(g.description)}</div><div class="percent">${pct}%</div></div>
      <div class="goal-sub">Started ${fmtDay(g.start_date)}${g.target_date ? ' · target ' + fmtDay(g.target_date) : ''}</div>
      <div class="track"><i style="width:${pct}%"></i></div></div>`;
  }
  html += `<div class="habit-editor" style="margin:0"><label class="field-label" for="goalDesc" style="margin-top:0">New goal</label>
    <input class="field" id="goalDesc" placeholder="e.g. Read 20 pages daily">
    <label class="field-label" for="goalTarget">Target date (optional)</label>
    <input class="field" id="goalTarget" type="date">
    <button class="build-button" style="margin-bottom:0" onclick="addGoal()">Add goal</button></div></div>`;
  body.innerHTML = html;
}
function addGoal() {
  const desc = $('goalDesc').value.trim();
  if (!desc) { toast('Describe the goal first.'); return; }
  const target = $('goalTarget').value || null;
  const uid = 'g-' + randHex(4);
  const values = { uid, description: desc, start_date: todayLocal(), progress: 0 };
  if (target) values.target_date = target;
  putRow('goal', uid, values);
  toast('Goal added.');
  renderGoals();
}
function renderTrackers() {
  const body = $('trackersBody');
  const trackers = rows('tracker').sort((a, b) => (a.name < b.name ? -1 : 1));
  let html = `<div class="section-head" style="margin-top:0"><h3>Trackers</h3><span class="caption">Quick entries and recent trends</span></div><div class="cards-3">`;
  for (const t of trackers) {
    const entries = rows('tracker_entry').filter((e) => e.tracker_uid === t.uid)
      .sort((a, b) => (a.recorded_at < b.recorded_at ? 1 : -1));
    const latest = entries[0];
    const week = entries.slice(0, 7).reverse();
    const maxV = Math.max(1, ...week.map((e) => e.value));
    html += `<div class="tracker-card"><div class="row"><div><div class="chart-title">${esc(t.name)}</div>
      <div class="tracker-value">${latest ? esc(String(latest.value)) + ' <span class="caption">/ ' + esc(t.unit) + '</span>' : '—'}</div>
      ${latest ? `<div class="trend">${esc(latest.recorded_at.slice(0, 10))}${latest.note ? ' · ' + esc(latest.note) : ''}</div>` : '<div class="caption">No entries yet</div>'}
      </div><span class="pill">${esc(t.cadence)}</span></div>
      ${week.length ? `<div class="mini-bars">${week.map((e) => `<i style="height:${Math.max(5, Math.round((e.value / maxV) * 100))}%"></i>`).join('')}</div>` : ''}
      <div class="log-row"><input class="field" id="tv-${t.uid}" type="number" step="any" placeholder="New value" aria-label="New value for ${esc(t.name)}">
      <button class="btn primary" onclick="logTracker('${t.uid}')">Log</button></div></div>`;
  }
  html += `</div>`;
  body.innerHTML = html;
}
function logTracker(uid) {
  const input = $('tv-' + uid);
  const v = Number(String(input.value).replace(',', '.'));
  if (!Number.isFinite(v)) { toast('Enter a number first.'); return; }
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const recorded = `${todayLocal()}T${pad(now.getHours())}:${pad(now.getMinutes())}`;
  const eid = 'te-' + randHex(4);
  putRow('tracker_entry', eid, { uid: eid, tracker_uid: uid, recorded_at: recorded, value: v });
  toast('Logged.');
  renderTrackers();
}

/* ---------- Run builder ---------- */
let builder = null;
function blankHabit() {
  return { uid: null, name: '', group: 'General', points: 1, interval: false, count: 3 };
}
function rowToEditor(h) {
  return { uid: h.uid, name: h.title, group: h.group_name, points: h.impact_points, interval: h.recurrence_type === 'interval', count: h.recurrence_value };
}
function openBuilder() {
  const run = latestRun();
  builder = {
    mode: run ? 'copy' : 'scratch',
    copyFrom: run ? run.uid : '',
    name: '', days: 21, custom: false, start: todayLocal(),
    includeStandalone: true, showGuide: false, yamlText: '', yamlErrors: [],
    habits: run ? habitsForRun(run.uid).map(rowToEditor) : [],
  };
  showView('builder');
}
function builderMode(m) {
  builder.mode = m;
  if (m === 'scratch') builder.habits = [];
  else if (m === 'copy') builderCopyFrom(builder.copyFrom || (latestRun() || {}).uid || '');
  renderBuilder();
}
function builderCopyFrom(runUid) {
  builder.copyFrom = runUid;
  const run = allRuns().find((r) => r.uid === runUid);
  builder.habits = run ? habitsForRun(run.uid).map(rowToEditor) : [];
  if (builder.includeStandalone) {
    for (const h of unlinkedHabits()) builder.habits.push(rowToEditor(h));
  }
  if (run) { builder.days = run.duration_days || 21; builder.custom = ![7, 14, 21].includes(builder.days); }
  renderBuilder();
}
function builderDays(d) { builder.days = d; builder.custom = false; renderBuilder(); }
function builderCustom() { builder.custom = true; renderBuilder(); }
function habitEditorHTML(h, i, ctx) {
  return `<div class="habit-editor"><div class="editor-head">
    <input class="field" value="${esc(h.name)}" aria-label="Habit name" placeholder="Habit name" oninput="${ctx}[${i}].name=this.value">
    <input class="field editor-impact" value="${esc(h.group)}" aria-label="Group" oninput="${ctx}[${i}].group=this.value">
    <button class="remove-habit" aria-label="Remove" onclick="${ctx}.splice(${i},1);render${ctx === 'builder.habits' ? 'Builder' : 'Edit'}()">×</button></div>
    <label class="field-label">Impact points per completion</label>
    <div class="editor-head"><input class="field editor-impact" type="number" min="0" value="${h.points}" oninput="${ctx}[${i}].points=Number(this.value)||0"><span class="caption">points</span></div>
    <div class="segmented"><button class="${h.interval ? '' : 'selected'}" onclick="${ctx}[${i}].interval=false;render${ctx === 'builder.habits' ? 'Builder' : 'Edit'}()">Days per week</button>
    <button class="${h.interval ? 'selected' : ''}" onclick="${ctx}[${i}].interval=true;render${ctx === 'builder.habits' ? 'Builder' : 'Edit'}()">Every N days</button></div>
    ${h.interval
      ? `<div><label class="field-label">Repeat interval</label><div class="interval-row">Every <input class="field" type="number" min="1" value="${h.count}" oninput="${ctx}[${i}].count=Number(this.value)||1"> days</div>
        <div class="helper">If missed, it stays due until completed. After a Done it hides for N-1 days.</div></div>`
      : `<div><label class="field-label">Days per week</label><div class="interval-row"><input class="field" type="number" min="1" max="7" value="${h.count}" oninput="${ctx}[${i}].count=Number(this.value)||1"> days per week</div>
        <div class="helper">Set a weekly target; complete it on any days. Due every day until done.</div></div>`}</div>`;
}
function renderBuilder() {
  const body = $('builderBody');
  const b = builder;
  const runs = allRuns();
  let html = `<div class="eyebrow">New run</div>
    <h1 style="font-size:26px;font-weight:500;margin:4px 0">Build a run</h1>
    <div class="sub">Copy the last run, start from scratch, or paste YAML from any AI chatbot.</div>
    <div class="builder-source">
    <button class="source-btn${b.mode === 'copy' ? ' active' : ''}" onclick="builderMode('copy')"><b>Copy previous</b>Reuse habits and edit them</button>
    <button class="source-btn${b.mode === 'scratch' ? ' active' : ''}" onclick="builderMode('scratch')"><b>Start from scratch</b>Choose habits yourself</button>
    <button class="source-btn${b.mode === 'yaml' ? ' active' : ''}" onclick="builderMode('yaml')"><b>From YAML</b>Configure using external AI</button></div>`;
  if (b.mode === 'yaml') {
    html += `<div class="card" style="max-width:760px"><div class="chart-title">Paste a run file</div>
      <div class="helper">Copy the guide prompt into any chatbot, describe the habits you want, paste the YAML answer here, then load it into the editor below.</div>
      <div class="yaml-actions"><button class="btn" onclick="copyGuidePrompt()">Copy guide prompt</button>
      <button class="btn" onclick="builder.showGuide=!builder.showGuide;renderBuilder()">${b.showGuide ? 'Hide guide' : 'Show guide'}</button></div>
      ${b.showGuide ? `<pre class="prompt">${esc(YAML_GUIDE_PROMPT)}</pre>` : ''}
      <label class="field-label" for="yamlText">Run YAML</label>
      <textarea class="field code" id="yamlText" placeholder="name: Reset week&#10;days: 14&#10;habits:&#10;  - name: Morning light&#10;    every: 1 day" oninput="builder.yamlText=this.value">${esc(b.yamlText)}</textarea>
      <div class="form-errors${b.yamlErrors.length ? ' show' : ''}" id="yamlErrors">${b.yamlErrors.map(esc).join('<br>')}</div>
      <div class="yaml-actions"><button class="btn primary" onclick="loadYamlIntoEditor()">Load into editor</button></div></div>`;
  }
  if (b.mode === 'copy') {
    html += `<div class="card form-narrow"><label class="field-label" for="previousRun" style="margin-top:0">Copy from</label>
      <select class="field" id="previousRun" onchange="builderCopyFrom(this.value)">
      ${runs.map((r) => `<option value="${r.uid}"${r.uid === b.copyFrom ? ' selected' : ''}>${esc(r.name)} · ${r.duration_days} days</option>`).join('')}</select>
      <label class="checkline"><input type="checkbox"${b.includeStandalone ? ' checked' : ''} onchange="builder.includeStandalone=this.checked;builderCopyFrom(builder.copyFrom)"> Also include standalone habits</label>
      <div class="helper">Copied habits stay editable. Past check-ins on overlapping dates count toward the new run automatically.</div></div>`;
  }
  html += `<div class="form-row form-narrow"><div><label class="field-label" for="runName">Run name</label>
    <input class="field" id="runName" placeholder="Name this run" value="${esc(b.name)}" oninput="builder.name=this.value"></div>
    <div><label class="field-label" for="runStart">Start date</label>
    <input class="field" id="runStart" type="date" value="${esc(b.start)}" oninput="builder.start=this.value"></div></div>
    <div class="form-narrow"><label class="field-label">Run length</label><div class="duration-row">
    ${[7, 14, 21].map((d) => `<button class="duration-chip${!b.custom && b.days === d ? ' selected' : ''}" onclick="builderDays(${d})">${d} days</button>`).join('')}
    <button class="duration-chip${b.custom ? ' selected' : ''}" onclick="builderCustom()">Custom</button></div>
    ${b.custom ? `<div class="interval-row" style="margin-top:8px"><input class="field" id="customDays" type="number" min="1" max="365" value="${b.days}" oninput="builder.days=Number(this.value)||21"> days</div>` : ''}</div>
    <div class="section-head"><h3>Habits in this run</h3><span class="caption">${b.habits.length} habit${b.habits.length === 1 ? '' : 's'}</span></div>
    <div id="builderHabits">`;
  if (!b.habits.length) html += `<div class="empty-editor" style="border:1px dashed var(--outline);color:var(--muted);border-radius:16px;text-align:center;padding:20px 12px;font-size:12px;max-width:760px">No habits added yet. Add the first habit below.</div>`;
  b.habits.forEach((h, i) => { html += habitEditorHTML(h, i, 'builder.habits'); });
  html += `</div><button class="add-habit" onclick="builder.habits.push(blankHabit());renderBuilder()">＋ Add a habit</button><br>
    <button class="build-button" onclick="createRun()">Create run</button> <button class="btn" onclick="showView('runs')">Cancel</button>`;
  body.innerHTML = html;
  const yt = $('yamlText');
  if (yt && !yt.value && b.yamlText) yt.value = b.yamlText;
}
function copyGuidePrompt() {
  const done = () => toast('Guide prompt copied — paste it into any chatbot.');
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(YAML_GUIDE_PROMPT).then(done, () => toast('Copy failed — select the guide text manually.'));
  } else toast('Clipboard unavailable — select the guide text manually.');
}
function loadYamlIntoEditor() {
  const text = $('yamlText').value;
  const parsed = parseRunYaml(text);
  if (!parsed.ok) {
    builder.yamlErrors = parsed.errors;
    builder.yamlText = text;
    renderBuilder();
    return;
  }
  builder.yamlErrors = [];
  builder.yamlText = text;
  if (parsed.run.name) builder.name = parsed.run.name;
  builder.days = parsed.run.days;
  builder.custom = ![7, 14, 21].includes(parsed.run.days);
  if (parsed.run.start) builder.start = parsed.run.start;
  builder.habits = parsed.habits.map((h) => ({ uid: null, name: h.name, group: h.group, points: h.points, interval: h.interval > 0, count: h.interval > 0 ? h.interval : h.weekly }));
  toast(`Loaded ${builder.habits.length} habits — review and create the run.`);
  renderBuilder();
}
function createRun() {
  const b = builder;
  const days = Math.min(Math.max(b.days || 21, 1), 365);
  const start = /^\d{4}-\d{2}-\d{2}$/.test(b.start || '') ? b.start : todayLocal();
  const named = b.habits.filter((h) => (h.name || '').trim());
  if (!named.length) { toast('Add at least one habit.'); return; }
  const runId = `run-${start}-${randHex(4)}`;
  putRow('run', runId, { uid: runId, name: (b.name || '').trim() || `${days}-day run`, start_date: start, duration_days: days, status: 'active' });
  for (const h of named) {
    const uid = h.uid || `h-${slug(h.name)}-${randHex(2)}`;
    if (!h.uid) {
      putRow('habit', uid, {
        uid, title: h.name.trim(), group_name: (h.group || '').trim() || 'General', iteration: 1,
        impact_points: Math.max(0, h.points || 0),
        recurrence_type: h.interval ? 'interval' : 'weekly',
        recurrence_value: Math.max(1, h.count || 1), archived: 0,
      });
    }
    const luid = linkUid(runId, uid);
    putRow('run_habit', luid, { uid: luid, run_uid: runId, habit_uid: uid });
  }
  quickLogged = new Set(); quickResults = []; quickSession = '';
  toast('Run created.');
  showView('runs');
}

/* ---------- Edit run ---------- */
let editRun = null;
function openEdit(runUid) {
  const run = allRuns().find((r) => r.uid === runUid) || latestRun();
  if (!run) { toast('No run to edit.'); return; }
  editRun = {
    uid: run.uid, name: run.name, days: run.duration_days || 21,
    custom: ![7, 14, 21].includes(run.duration_days || 21), start: run.start_date,
    habits: habitsForRun(run.uid).map(rowToEditor), attachUid: '',
  };
  showView('edit');
}
function renderEdit() {
  const body = $('editBody');
  const e = editRun;
  if (!e) { body.innerHTML = `<div class="empty">Pick a run to edit.</div>`; return; }
  const others = runsUsingHabitInfo(e.habits.map((h) => h.uid));
  const unattached = rows('habit').filter((h) => !h.archived && !e.habits.some((x) => x.uid === h.uid))
    .sort((a, b) => (a.title < b.title ? -1 : 1));
  let html = `<div class="eyebrow">Edit run</div>
    <h1 style="font-size:26px;font-weight:500;margin:4px 0">Edit run</h1>
    <div class="form-row form-narrow"><div><label class="field-label" for="editName">Run name</label>
    <input class="field" id="editName" value="${esc(e.name)}" oninput="editRun.name=this.value"></div>
    <div><label class="field-label" for="editStart">Start date</label>
    <input class="field" id="editStart" type="date" value="${esc(e.start)}" oninput="editRun.start=this.value"></div></div>
    <div class="form-narrow"><label class="field-label">Run length</label><div class="duration-row">
    ${[7, 14, 21].map((d) => `<button class="duration-chip${!e.custom && e.days === d ? ' selected' : ''}" onclick="editRun.days=${d};editRun.custom=false;renderEdit()">${d} days</button>`).join('')}
    <button class="duration-chip${e.custom ? ' selected' : ''}" onclick="editRun.custom=true;renderEdit()">Custom</button></div>
    ${e.custom ? `<div class="interval-row" style="margin-top:8px"><input class="field" type="number" min="1" max="365" value="${e.days}" oninput="editRun.days=Number(this.value)||21"> days</div>` : ''}</div>
    <div class="section-head"><h3>Habits in this run</h3><span class="caption">${e.habits.length} habit${e.habits.length === 1 ? '' : 's'}</span></div>
    <div class="helper" style="max-width:760px">Editing a habit here updates it everywhere it is used${others}.</div>`;
  e.habits.forEach((h, i) => { html += habitEditorHTML(h, i, 'editRun.habits'); });
  html += `<div class="form-row form-narrow"><div><label class="field-label" for="attachSel">Attach an existing habit</label>
    <select class="field" id="attachSel" onchange="editRun.attachUid=this.value">
    <option value="">Choose…</option>
    ${unattached.map((h) => `<option value="${h.uid}">${esc(h.title)} · ${esc(h.group_name)}</option>`).join('')}</select></div>
    <div style="align-self:end"><button class="btn" onclick="attachExisting()">Attach</button></div></div>
    <button class="add-habit" onclick="editRun.habits.push(blankHabit());renderEdit()">＋ New habit in this run</button><br>
    <button class="build-button" onclick="saveEdit()">Save changes</button> <button class="btn" onclick="showView('runs')">Cancel</button>`;
  body.innerHTML = html;
}
function runsUsingHabitInfo(uids) {
  const multi = uids.filter((u) => u && runsUsingHabit(u).length > 1);
  return multi.length ? ` — ${multi.length} of these are shared with other runs` : '';
}
function attachExisting() {
  const uid = editRun.attachUid;
  if (!uid) { toast('Choose a habit first.'); return; }
  const h = row('habit', uid);
  if (!h) return;
  editRun.habits.push(rowToEditor(h.v));
  editRun.attachUid = '';
  renderEdit();
}
function saveEdit() {
  const e = editRun;
  const days = Math.min(Math.max(e.days || 21, 1), 365);
  const start = /^\d{4}-\d{2}-\d{2}$/.test(e.start || '') ? e.start : todayLocal();
  putRow('run', e.uid, { uid: e.uid, name: (e.name || '').trim() || 'Untitled run', start_date: start, duration_days: days, status: 'active' });
  const keep = new Set();
  for (const h of e.habits.filter((x) => (x.name || '').trim())) {
    const uid = h.uid || `h-${slug(h.name)}-${randHex(2)}`;
    if (!h.uid) {
      putRow('habit', uid, {
        uid, title: h.name.trim(), group_name: (h.group || '').trim() || 'General', iteration: 1,
        impact_points: Math.max(0, h.points || 0),
        recurrence_type: h.interval ? 'interval' : 'weekly',
        recurrence_value: Math.max(1, h.count || 1), archived: 0,
      });
    } else {
      const cur = row('habit', uid);
      if (cur) {
        putRow('habit', uid, {
          title: h.name.trim(), group_name: (h.group || '').trim() || 'General',
          impact_points: Math.max(0, h.points || 0),
          recurrence_type: h.interval ? 'interval' : 'weekly',
          recurrence_value: Math.max(1, h.count || 1),
        });
      }
    }
    const luid = linkUid(e.uid, uid);
    putRow('run_habit', luid, { uid: luid, run_uid: e.uid, habit_uid: uid });
    keep.add(luid);
  }
  // Detach habits removed in the editor (history stays on the habit).
  for (const rh of rows('run_habit').filter((r) => r.run_uid === e.uid)) {
    if (!keep.has(rh.uid)) deleteRow('run_habit', rh.uid);
  }
  toast('Run saved.');
  showView('runs');
}

/* ---------- Standalone habits ---------- */
function openStandalone() {
  const host = $('standaloneForm');
  if (!host) return;
  host.innerHTML = `<div class="habit-editor"><div class="eyebrow">New standalone habit</div>
    <div class="editor-head" style="margin-top:8px"><input class="field" id="saName" placeholder="Habit name" aria-label="Habit name">
    <input class="field editor-impact" id="saGroup" placeholder="Group" value="General" aria-label="Group"></div>
    <label class="field-label">Impact points per completion</label>
    <div class="editor-head"><input class="field editor-impact" id="saPoints" type="number" min="0" value="1"><span class="caption">points</span></div>
    <div class="segmented" id="saSeg"><button class="selected" data-v="0">Days per week</button><button data-v="1">Every N days</button></div>
    <div class="interval-row" id="saWeekly"><input class="field" id="saCount" type="number" min="1" max="7" value="3"> days per week</div>
    <div class="interval-row" id="saInterval" style="display:none">Every <input class="field" id="saEvery" type="number" min="1" value="2"> days</div>
    <div class="helper">Standalone habits are not in any run. They always appear, and count toward a run once attached to one.</div>
    <button class="build-button" onclick="createStandalone()">Add habit</button></div>`;
  host.querySelectorAll('#saSeg button').forEach((btn) => btn.addEventListener('click', () => {
    host.querySelectorAll('#saSeg button').forEach((x) => x.classList.toggle('selected', x === btn));
    const iv = btn.dataset.v === '1';
    $('saWeekly').style.display = iv ? 'none' : 'flex';
    $('saInterval').style.display = iv ? 'flex' : 'none';
  }));
}
function createStandalone() {
  const name = $('saName').value.trim();
  if (!name) { toast('Name the habit first.'); return; }
  const iv = $('saInterval').style.display !== 'none';
  const uid = `h-${slug(name)}-${randHex(2)}`;
  putRow('habit', uid, {
    uid, title: name, group_name: ($('saGroup').value || '').trim() || 'General', iteration: 1,
    impact_points: Math.max(0, Number($('saPoints').value) || 0),
    recurrence_type: iv ? 'interval' : 'weekly',
    recurrence_value: Math.max(1, Number((iv ? $('saEvery') : $('saCount')).value) || 1), archived: 0,
  });
  toast('Standalone habit added.');
  renderCurrent();
}

/* ---------- module → window (inline handlers) ---------- */
Object.assign(window, {
  showView, openBuilder, builderMode, builderCopyFrom, builderDays, builderCustom,
  copyGuidePrompt, loadYamlIntoEditor, createRun, openEdit, attachExisting, saveEdit,
  openStandalone, createStandalone, toggleDay, setInfinite, recordQuick, setQuickComment,
  addGoal, logTracker, renderRuns, renderQuick, renderBuilder, renderEdit, signOut,
  blankHabit,
});
Object.defineProperties(window, {
  builder: { get: () => builder, set: (v) => { builder = v; } },
  editRun: { get: () => editRun, set: (v) => { editRun = v; } },
  quickCommentOpen: { get: () => quickCommentOpen, set: (v) => { quickCommentOpen = v; } },
});

document.addEventListener('DOMContentLoaded', boot);
