'use strict';
/* Habit Lab desktop web client: thin sync mirror + desktop screens. */
import { parseRunYaml, serializeRunYaml, YAML_GUIDE_PROMPT } from './yaml.js';
import { streakLength } from './streak.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
/* Ids come from other devices. Anything outside this charset is never put into markup or handlers. */
const SAFE_UID = /^[A-Za-z0-9._:@#+~-]{1,200}$/;
const SAFE_FIELD = /^_?[a-z][a-z_]{0,31}$/;
const safeId = (u) => (SAFE_UID.test(String(u)) ? String(u) : '');
const STATES = ['done', 'partial', 'skipped'];
const safeState = (st) => (STATES.includes(st) ? st : '');
const ROMAN = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII'];
const roman = (n) => ROMAN[n - 1] || String(n);
/* Line icons (24px grid, stroked with currentColor). */
const ICONS = {
  sun: 'M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41',
  moon: 'M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z',
  book: 'M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2zM22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z',
  drop: 'M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z',
  move: 'M22 12h-4l-3 9L9 3l-3 9H2',
  timer: 'M4 13a8 8 0 1 0 16 0a8 8 0 1 0-16 0M12 9v4l2 2M10 2h4',
  target: 'M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0M6 12a6 6 0 1 0 12 0a6 6 0 1 0-12 0M10 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0',
  leaf: 'M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10ZM2 21c0-3 1.85-5.36 5.08-6C9.5 14.52 12 13 13 12',
  write: 'M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z',
  heart: 'M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z',
  energy: 'M13 2 3 14h9l-1 8 10-12h-9l1-8z',
  star: 'm12 2 3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z',
  check: 'M20 6 9 17l-5-5',
  flame: 'M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.07-2.14-.22-4.05 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.15.43-2.29 1-3a2.5 2.5 0 0 0 2.5 2.5z',
  chart: 'M3 3v18h18M18 17V9M13 17V5M8 17v-3',
  trend: 'm22 7-8.5 8.5-5-5L2 17M16 7h6v6',
  today: 'M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0M9 12l2 2 4-4',
  sliders: 'M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6',
  copy: 'M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2zM4 16a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2',
  blank: 'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM8 12h8M12 8v8',
  code: 'm16 18 6-6-6-6M8 6l-6 6 6 6',
  cal: 'M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM16 2v4M8 2v4M3 10h18',
  download: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3',
  back: 'm12 19-7-7 7-7M19 12H5',
  next: 'm9 18 6-6-6-6',
  prev: 'm15 18-6-6 6-6',
  plus: 'M5 12h14M12 5v14',
  minus: 'M6 12h12',
  info: 'M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0M12 16v-4M12 8h.01',
  warn: 'm21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3ZM12 9v4M12 17h.01',
};
const svg = (name, size = 20, cls = '') => `<svg class="ic${cls ? ' ' + cls : ''}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="${ICONS[name] || ICONS.star}"/></svg>`;
/* Habits carry no synced icon, so the group picks one. */
const GROUP_ICON = { wellbeing: 'sun', morning: 'sun', focus: 'timer', work: 'timer', health: 'heart', fitness: 'move', exercise: 'move', learning: 'book', reading: 'book', sleep: 'moon', evening: 'moon', general: 'leaf' };
const ICON_CYCLE = ['star', 'target', 'drop', 'energy', 'leaf', 'book', 'heart', 'sun'];
function groupIconKey(g) {
  const k = String(g || '').trim().toLowerCase();
  if (Object.hasOwn(GROUP_ICON, k)) return GROUP_ICON[k];
  let hash = 0;
  for (const ch of k) hash = (hash * 31 + ch.charCodeAt(0)) | 0;
  return ICON_CYCLE[Math.abs(hash) % ICON_CYCLE.length];
}

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
function putRow(t, u, values, schedule = true) {
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
  if (schedule) scheduleSync();
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
  if (!res.ok) {
    const detail = await res.json().then((j) => (j && j.error) || '', () => '');
    throw Object.assign(new Error('http ' + res.status), { status: res.status, detail });
  }
  return res.json();
}
/** Same winner rule as the server and the app: higher clock, then device id, then value. */
function fieldWins(inc, cur, incValue, curValue) {
  if (!cur) return true;
  if (inc.c !== cur.c) return inc.c > cur.c;
  if (inc.d !== cur.d) return inc.d > cur.d;
  return JSON.stringify(incValue ?? null) > JSON.stringify(curValue ?? null);
}
/**
 * The server answers with only the fields another device changed, so a row
 * is merged field by field; replacing it would drop every untouched field.
 * A field edited here after the request was built keeps its newer stamp.
 */
function mergeIncoming(r) {
  if (!r || !TABLES.includes(r.t) || !SAFE_UID.test(String(r.u))) return;
  const incoming = r.v && typeof r.v === 'object' ? r.v : {};
  const stamps = r.s && typeof r.s === 'object' ? r.s : {};
  for (const [field, value] of Object.entries(incoming)) {
    if (!SAFE_FIELD.test(field)) return;
    if ((field === 'uid' || field.endsWith('_uid')) && typeof value === 'string' && !SAFE_UID.test(value)) return;
  }
  const table = mirror[r.t] || (mirror[r.t] = {});
  const cur = Object.hasOwn(table, r.u) ? table[r.u] : { v: {}, s: {} };
  const own = (o, k) => (Object.hasOwn(o, k) ? o[k] : undefined);
  if (own(cur.v, '_deleted') === true) return; // a delete here stays deleted, so late live fields cannot revive a partial row
  if (incoming._deleted === true) {
    // Keep a tombstone instead of dropping the row so stale fields arriving later stay hidden.
    table[r.u] = { v: { _deleted: true }, s: { ...cur.s, _deleted: stamps._deleted || { c: Date.now(), d: '' } } };
    return;
  }
  const v = { ...cur.v }, s = { ...cur.s };
  for (const [field, value] of Object.entries(incoming)) {
    const stamp = own(stamps, field);
    if (stamp && !fieldWins(stamp, own(cur.s, field), value, own(cur.v, field))) continue;
    v[field] = value;
    if (stamp) s[field] = stamp;
  }
  table[r.u] = { v, s };
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
    for (const r of resp.changes || []) mergeIncoming(r);
    ensureSingleActiveRun();
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
let historyRunUid = '';
function isRunEnded(run, today) {
  if (!run || !run.start_date || !today) return false;
  const dur = run.duration_days || 21;
  const end = addDays(run.start_date, dur - 1);
  return today > end;
}
function activeRun() {
  const today = todayLocal();
  return allRuns().find((r) => r.status === 'active' && !isRunEnded(r, today)) || null;
}
function latestRun() { return activeRun() || allRuns()[0] || null; }
function displayedRun() {
  const history = allRuns().find((r) => r.uid === historyRunUid && r.status === 'stopped');
  if (historyRunUid && !history) historyRunUid = '';
  return history || latestRun();
}
function allRuns() {
  const runs = rows('run');
  runs.sort((a, b) => b.start_date.localeCompare(a.start_date) || b.uid.localeCompare(a.uid));
  return runs;
}
// Keep the newest active run. Old parallel runs remain available as stopped history.
function ensureSingleActiveRun() {
  const active = allRuns().filter((r) => r.status === 'active');
  for (const run of active.slice(1)) putRow('run', run.uid, { status: 'stopped' });
  return Math.max(0, active.length - 1);
}
function duplicateHabitName(habits) {
  const seen = new Set();
  for (const h of habits) {
    const name = String(h.name || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase();
    if (!name) continue;
    if (seen.has(name)) return h.name.trim();
    seen.add(name);
  }
  return '';
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
// Today's completed habits stay in the main table; only interval habits resting
// after an earlier completion are placed in the optional section.
function restingHabits(today, list) {
  const done = rows('check_in').filter((c) => c.state === 'done' && c.day < today);
  return list.filter((h) => h.recurrence_type === 'interval' && !checkInsByDay(today)[h.uid] &&
    done.some((c) => c.habit_uid === h.uid && daysBetween(c.day, today) < (h.recurrence_value || 1)));
}
function runDayIndex(run, day) {
  return daysBetween(run.start_date, day);
}
/** Mirror key of the live check-in for a habit and day, or ''. */
function liveCheckInKey(habitUid, day) {
  for (const [key, r] of Object.entries(mirror.check_in || {})) {
    if (r.v._deleted !== true && r.v.habit_uid === habitUid && r.v.day === day) return key;
  }
  return '';
}
/* A cleared check-in is tombstoned for good on the server, so logging that
 * day again needs a fresh uid (same rule as linkUid). */
function checkInUid(habitUid, day) {
  const live = liveCheckInKey(habitUid, day);
  if (live) return live;
  const base = habitUid + ':' + day;
  const cur = row('check_in', base);
  if (cur && cur.v._deleted === true) return `${base}#${Date.now().toString(36)}`;
  return base;
}
function logCheckIn(habitUid, day, state, note) {
  const uid = checkInUid(habitUid, day);
  const values = { uid, habit_uid: habitUid, day, state };
  if (note) values.comment = note;
  putRow('check_in', uid, values);
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
const TITLES = { settings: 'Settings', runs: 'Today', analytics: 'Stats', goals: 'Goals', trackers: 'Trackers', quicklog: 'Quick Log', editor: 'New run' };
let currentView = 'runs';
let enterToken = 0, enterUntil = 0, popKey = '';
function showView(id) {
  currentView = id;
  document.querySelectorAll('.view').forEach((s) => s.classList.toggle('active', s.id === 'v-' + id));
  document.querySelectorAll('.sidenav [data-view]').forEach((b) => {
    const on = b.dataset.view === id;
    b.classList.toggle('active', on);
    if (on) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current');
  });
  $('topTitle').textContent = TITLES[id] || id;
  $('topDate').textContent = id === 'runs' ? new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' }) : '';
  $('topActions').innerHTML = '';
  window.scrollTo(0, 0);
  renderCurrent();
  // Entrance motion plays once per view switch; later re-renders must not replay it.
  enterUntil = Date.now() + 1300;
  const my = ++enterToken;
  const sec = document.getElementById('v-' + id);
  sec?.classList.add('enter');
  setTimeout(() => { if (enterToken === my) sec?.classList.remove('enter'); }, 1300);
}
/** Selector that finds the same control after a re-render, or ''. */
function focusKey(el) {
  if (!(el instanceof HTMLElement) || !el.closest('.content')) return '';
  if (el.id) return '#' + CSS.escape(el.id);
  const d = el.dataset;
  if (!d.click) return '';
  return `[data-click="${CSS.escape(d.click)}"]` + (d.a != null ? `[data-a="${CSS.escape(d.a)}"]` : '') + (d.b != null ? `[data-b="${CSS.escape(d.b)}"]` : '');
}
/* Sync finishes asynchronously and re-renders; keep keyboard focus where it was. */
function renderCurrent() {
  if (Date.now() >= enterUntil) document.querySelectorAll('.view.enter').forEach((s) => s.classList.remove('enter'));
  const key = focusKey(document.activeElement);
  renderView();
  if (key && !document.activeElement?.closest?.('.content')) document.querySelector(key)?.focus({ preventScroll: true });
  if (popKey) {
    const btn = document.querySelector(popKey);
    if (btn && !btn.classList.contains('pop')) {
      btn.classList.add('pop');
      btn.addEventListener('animationend', () => {
        btn.classList.remove('pop');
        popKey = '';
      }, { once: true });
    }
  }
}
function renderView() {
  if (!localStorage.getItem(LS.key)) return;
  if (currentView === 'runs') renderRuns();
  else if (currentView === 'analytics') renderAnalytics();
  else if (currentView === 'goals') renderGoals();
  else if (currentView === 'trackers') renderTrackers();
  else if (currentView === 'quicklog') renderQuick();
  else if (currentView === 'editor') renderEditor();
  else if (currentView === 'settings') renderSettings();
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
/* The welcome is stored per account in this browser, independently of sign-out. */
let welcomePage = 0;
function welcomeKey() { return 'habitlab.welcome.' + (localStorage.getItem(LS.account) || '').toLowerCase(); }
const welcomePages = [
  `<h1 id="welcomeTitle" tabindex="-1">Not every habit weighs the same</h1><p>Give each habit points for how much it moves your life. Progress is points earned out of points possible, not boxes ticked.</p><div class="welcome-example"><div class="welcome-label">EXAMPLE</div><div class="welcome-stat">4 of 5 points <span>80%</span></div><div class="welcome-track"><span></span></div><div class="welcome-row">A meaningful habit <b>4 pts</b></div><div class="welcome-row">A small habit <b>1 pt</b></div></div>`,
  `<h1 id="welcomeTitle" tabindex="-1">See how your run is going</h1><p>Every run shows the points you have earned and a forecast of where the run could end.</p><div class="welcome-example"><div class="welcome-label">YOUR PROGRESS</div><div class="welcome-stat">Points earned <span>12 pts</span></div><div class="welcome-track"><span></span></div><div class="welcome-row">Actual <b>━</b></div><div class="welcome-row">Forecast <b>┄</b></div></div>`,
  `<h1 id="welcomeTitle" tabindex="-1">Build a rhythm that fits you</h1><p>Plan a run, choose the habits that matter to you, and log each day. Done earns the habit’s points; skip earns none.</p><div class="welcome-example"><div class="welcome-label">GET STARTED</div><div class="welcome-row">1. Create or choose your habits <b>✓</b></div><div class="welcome-row">2. Start a run <b>✓</b></div><div class="welcome-row">3. Log your progress <b>✓</b></div></div>`,
];
function drawWelcome() {
  $('welcomeContent').innerHTML = welcomePages[welcomePage];
  $('welcomeDots').innerHTML = welcomePages.map((_, i) => `<span class="${i === welcomePage ? 'active' : ''}"></span>`).join('');
  $('welcomeDots').setAttribute('aria-label', `Page ${welcomePage + 1} of ${welcomePages.length}`);
  $('welcomeBack').textContent = welcomePage ? 'Back' : 'Skip';
  $('welcomeNext').textContent = welcomePage === welcomePages.length - 1 ? 'Get started' : 'Next';
  $('welcomeTitle').focus();
}
function finishWelcome() {
  localStorage.setItem(welcomeKey(), '1');
  $('welcome').hidden = true;
  $('acctBtn').focus();
}
function showWelcomeIfNeeded() {
  if (localStorage.getItem(welcomeKey())) return;
  welcomePage = 0;
  $('welcome').hidden = false;
  drawWelcome();
}
let creating = false;
function setCreateMode(on) {
  creating = on;
  $('confirmWrap').style.display = on ? '' : 'none';
  $('loginBtn').textContent = on ? 'Create account' : 'Sign in';
  $('loginSub').textContent = on ? 'Create an account on this sync server. Use the same one on your phone.' : 'Sign in to sync your runs across devices.';
  $('loginSwitch').textContent = on ? 'Already have an account? Sign in' : 'New here? Create an account';
  $('loginPass').autocomplete = on ? 'new-password' : 'current-password';
  $('loginError').classList.remove('show');
}
async function onLogin(e) {
  e.preventDefault();
  const user = $('loginUser').value.trim(), pass = $('loginPass').value;
  const err = $('loginError');
  err.classList.remove('show');
  const fail = (m) => { err.textContent = m; err.classList.add('show'); };
  if (!user || !pass) return fail(creating ? 'Choose a username and a password.' : 'Enter your username and password.');
  if (creating && pass !== $('loginPass2').value) return fail('The two passwords do not match.');
  $('loginBtn').disabled = true;
  try {
    const resp = await api(creating ? '/api/register' : '/api/sign-in', { username: user, password: pass }, false);
    $('loginPass2').value = '';
    localStorage.setItem(LS.account, resp.account);
    localStorage.setItem(LS.key, resp.accessToken);
    if (!localStorage.getItem(LS.token)) localStorage.setItem(LS.token, '0');
    deviceId();
    $('loginPass').value = '';
    setLoggedIn(true);
    showView('runs');
    showWelcomeIfNeeded();
    sync();
  } catch (ex) {
    err.textContent = ex.message === 'auth' ? 'Invalid username or password.'
      : ex.detail ? ex.detail : 'Cannot reach the sync server. Check your connection.';
    err.classList.add('show');
  } finally {
    $('loginBtn').disabled = false;
  }
}

/* ---------- Appearance settings (this browser only) ---------- */
const PREF_KEY = 'habitlab.prefs';
/* Accent keys are shared with the Android app. Values are [light, dark, name]. */
const ACCENTS = {
  classic: {
    green: ['#1F7A4D', '#4CC38A', 'Green'], teal: ['#00756A', '#3DD9C5', 'Teal'], blue: ['#2459C4', '#8AB4FF', 'Blue'], purple: ['#6E44B8', '#C3A6F5', 'Purple'],
    pink: ['#B8326F', '#F5A3C7', 'Pink'], orange: ['#B4490F', '#FFA866', 'Orange'], yellow: ['#8A6100', '#F0CF5C', 'Yellow'], red: ['#B42318', '#FF9C8F', 'Red'],
  },
  wa: {
    green: ['#56652A', '#A9BC6E', 'Matcha'], teal: ['#00737B', '#4FCBD3', 'Asagi'], blue: ['#165E83', '#6FA8C9', 'Ai'], purple: ['#6A4E96', '#BBA6DD', 'Fuji'],
    pink: ['#A8506A', '#F0B5C5', 'Sakura'], orange: ['#B23E0E', '#F08A64', 'Shu'], yellow: ['#8A5F00', '#F5C342', 'Yamabuki'], red: ['#A3262C', '#F08A8E', 'Beni'],
  },
};
const STYLES = [['classic', 'Classic'], ['wa', 'Japanese']];
/* The hero colours in the token table belong to each style's reference accent. */
const HERO_ACCENT = { classic: 'green', wa: 'blue' };
const SHARED_LIGHT = { 'warn-bg': '#FFF7C2', 'warn-ink': '#5C3D00', 'warn-icon': '#9E6C00', 'skip-bg': '#F0F0F3', 'skip-ink': '#60646C' };
const SHARED_DARK = { 'warn-bg': '#2D2305', 'warn-ink': '#FFE29A', 'warn-icon': '#FFCA16', 'skip-bg': '#2B2A2E', 'skip-ink': '#B0B4BA' };
const TOKENS = {
  classic: {
    light: { ...SHARED_LIGHT, bg: '#FBF8F3', surface: '#FFFFFF', 'surface-alt': '#F4EFE7', hero: '#E6F4EA', ink: '#211D18', muted: '#6B6259', divider: '#ECE5DA', field: '#E2D9CC', track: '#E9E2D7',
      streak: '#F76B15', 'streak-soft': '#FFEFD6', 'streak-ink': '#CC4E00', error: '#CE2C31', 'error-soft': '#FEEBEC', partial: '#FFC53D' },
    dark: { ...SHARED_DARK, bg: '#131210', surface: '#1C1A17', 'surface-alt': '#252220', hero: '#16291F', ink: '#F2EDE6', muted: '#A9A096', divider: '#322E29', field: '#3A352F', track: '#34302A',
      streak: '#FFA057', 'streak-soft': '#331E0B', 'streak-ink': '#FFA057', error: '#FF977D', 'error-soft': '#3B1219', partial: '#C99A2E' },
  },
  wa: {
    light: { ...SHARED_LIGHT, bg: '#F7F3EA', surface: '#FFFDF8', 'surface-alt': '#EFE8DA', hero: '#E3ECF1', ink: '#23201C', muted: '#6E665C', divider: '#E6DFD1', field: '#DDD4C3', track: '#E8E1D3',
      streak: '#E2541C', 'streak-soft': '#FBE4DA', 'streak-ink': '#B5381A', error: '#A3262C', 'error-soft': '#F9E4E3', partial: '#F8B500' },
    dark: { ...SHARED_DARK, bg: '#161514', surface: '#201E1C', 'surface-alt': '#2A2724', hero: '#14232D', ink: '#EDE6DA', muted: '#A69E92', divider: '#35312C', field: '#3D3832', track: '#2B2825',
      streak: '#F08A64', 'streak-soft': '#3A1A12', 'streak-ink': '#F08A64', error: '#F08A8E', 'error-soft': '#331517', partial: '#D9A21C' },
  },
};
let prefs = { mode: 'system', style: 'classic', accent: 'green', dense: false, hidePoints: false };
try { prefs = { ...prefs, ...JSON.parse(localStorage.getItem(PREF_KEY) || '{}') }; } catch (e) { /* keep defaults */ }
if (!Object.hasOwn(TOKENS, prefs.style)) prefs.style = 'classic';
if (!Object.hasOwn(ACCENTS.classic, prefs.accent)) prefs.accent = 'green';
function savePrefs() { try { localStorage.setItem(PREF_KEY, JSON.stringify(prefs)); } catch (e) { /* private mode */ } }
const hexToRgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
function mix(a, b, t) {
  const x = hexToRgb(a), y = hexToRgb(b);
  return '#' + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, '0')).join('');
}
/** CSS custom properties (without the leading --) for a style, mode and accent key. */
function themeTokens(style, dark, accentKey) {
  const base = TOKENS[style][dark ? 'dark' : 'light'];
  const accent = (ACCENTS[style][accentKey] || ACCENTS[style].green)[dark ? 1 : 0];
  const t = { ...base, accent, 'on-accent': dark ? '#0F0E0D' : '#FFFFFF', 'accent-soft': mix(base.surface, accent, dark ? 0.22 : 0.14) };
  if (accentKey !== HERO_ACCENT[style]) t.hero = mix(base.surface, accent, dark ? 0.14 : 0.12);
  return t;
}
function isDark() {
  return prefs.mode === 'dark' || (prefs.mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
}
function applyPrefs() {
  const dark = isDark();
  const tokens = themeTokens(prefs.style, dark, prefs.accent);
  const root = document.documentElement;
  for (const [k, v] of Object.entries(tokens)) root.style.setProperty('--' + k, v);
  root.style.colorScheme = dark ? 'dark' : 'light';
  root.dataset.theme = dark ? 'dark' : 'light';
  root.dataset.style = prefs.style;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', tokens.bg);
  document.body.classList.toggle('dense', !!prefs.dense);
  document.body.classList.toggle('hide-points', !!prefs.hidePoints);
}
/** Monday-start week of an ISO day. */
function weekStart(iso) {
  const dow = (new Date(iso + 'T12:00:00').getDay() + 6) % 7;
  return addDays(iso, -dow);
}
/** "3 days per week · 1 of 3 this week · 2 pts"; the points part hides with the hide-points setting. */
function habitMeta(h, day) {
  let text = esc(recurrenceLabel(h));
  if (day && h.recurrence_type !== 'interval') {
    const from = weekStart(day);
    const n = rows('check_in').filter((c) => c.habit_uid === h.uid && c.day >= from && c.day <= day && (c.state === 'done' || c.state === 'partial')).length;
    text += ` · ${n} of ${Number(h.recurrence_value) || 1} this week`;
  }
  return `${text}<span class="pts"> · ${Number(h.impact_points) || 0} pts</span>`;
}
async function tokenRequest(method, path, body) {
  const headers = { 'X-Account-Id': localStorage.getItem(LS.account) || '', 'Authorization': 'Bearer ' + (localStorage.getItem(LS.key) || '') };
  if (body) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, { method, headers, ...(body ? { body: JSON.stringify(body) } : {}) });
  if (!response.ok) {
    const detail = await response.json().then((j) => j.error || '', () => '');
    throw new Error(detail || 'Could not contact the server.');
  }
  return response.json();
}
async function refreshTokens() {
  const list = $('apiTokenList');
  if (!list) return;
  try {
    const tokens = await tokenRequest('GET', '/api/tokens');
    if (!list.isConnected) return;
    list.replaceChildren();
    if (!tokens.length) { list.textContent = 'No integration tokens yet.'; return; }
    for (const token of tokens) {
      const row = document.createElement('div'); row.className = 'set-row';
      const description = document.createElement('div');
      const label = document.createElement('b'); label.textContent = token.name;
      const expiry = document.createElement('div'); expiry.className = 'caption'; expiry.textContent = 'Expires ' + new Date(token.expiresAt).toLocaleDateString();
      description.append(label, expiry);
      const revoke = document.createElement('button'); revoke.type = 'button'; revoke.className = 'btn'; revoke.textContent = 'Revoke';
      revoke.setAttribute('aria-label', `Revoke ${token.name}`);
      revoke.addEventListener('click', async () => {
        if (!window.confirm(`Revoke the token for ${token.name}?`)) return;
        revoke.disabled = true;
        try { await tokenRequest('DELETE', '/api/tokens/' + encodeURIComponent(token.id)); await refreshTokens(); }
        catch (e) { toast(e.message); revoke.disabled = false; }
      });
      row.append(description, revoke); list.append(row);
    }
  } catch (e) { if (list.isConnected) list.textContent = e.message; }
}
function renderSettings() {
  const body = $('settingsBody');
  const account = localStorage.getItem(LS.account) || '';
  const dark = isDark();
  body.innerHTML = `<div class="set-card"><h3>Appearance</h3><div class="caption">Saved in this browser. The Android app has its own copy of these settings.</div>
    <span class="field-label" id="setModeLbl">Theme</span>
    <div class="chip-row" role="group" aria-labelledby="setModeLbl">${[['system', 'System'], ['light', 'Light'], ['dark', 'Dark']].map(([k, l]) => `<button type="button" class="chip${prefs.mode === k ? ' selected' : ''}" aria-pressed="${prefs.mode === k}" data-mode="${k}">${l}</button>`).join('')}</div>
    <span class="field-label" id="setStyleLbl">Style</span>
    <div class="chip-row" role="group" aria-labelledby="setStyleLbl">${STYLES.map(([k, l]) => `<button type="button" class="chip${prefs.style === k ? ' selected' : ''}" aria-pressed="${prefs.style === k}" data-style="${k}">${l}</button>`).join('')}</div>
    <span class="field-label" id="setAccentLbl">Accent colour</span>
    <div class="swatch-row" role="group" aria-labelledby="setAccentLbl">${Object.entries(ACCENTS[prefs.style]).map(([k, [light, darkHex, name]]) => `<button type="button" class="swatch${prefs.accent === k ? ' on' : ''}" data-accent="${k}" style="background:${dark ? darkHex : light}" aria-pressed="${prefs.accent === k}" aria-label="Accent ${name}" title="${name}">${prefs.accent === k ? svg('check', 16) : ''}</button>`).join('')}</div></div>
    <div class="set-card"><h3>Habit list</h3>
    <div class="set-row"><div><b>Dense layout</b><div class="caption">Compact habit rows without the schedule line. For long lists.</div></div>
      <label class="switch"><input type="checkbox" id="setDense" aria-label="Dense layout"${prefs.dense ? ' checked' : ''}><i></i></label></div>
    <div class="set-row"><div><b>Hide points</b><div class="caption">Hides impact points on habits, the run summary and the points charts.</div></div>
      <label class="switch"><input type="checkbox" id="setHide" aria-label="Hide points"${prefs.hidePoints ? ' checked' : ''}><i></i></label></div></div>
    <div class="set-card"><h3>Sync server</h3><div class="caption">This page is served by your sync server, so it is the same address the Android app connects to.</div>
    <div class="set-row"><div><b>${esc(location.origin)}</b><div class="caption">Signed in as ${esc(account)}</div></div><button class="btn" id="setSignOut">Sign out</button></div></div>
    <div class="set-card"><h3>API tokens</h3><div class="caption">Connect another app with read-only access to your synced habits, runs, run memberships and check-ins. Tokens expire after 90 days. Treat them like passwords.</div>
    <form id="apiTokenForm"><label class="field-label" for="apiTokenName">Token name</label><input class="field" id="apiTokenName" required maxlength="80" placeholder="For example, My calendar"><button class="build-button" type="submit" id="apiTokenCreate">Create token</button></form>
    <div id="apiTokenResult" hidden><p class="caption">Copy this token now. It will not be shown again.</p><label class="field-label" for="apiTokenValue">New token</label><input class="field" id="apiTokenValue" readonly><button class="btn" type="button" id="apiTokenCopy">Copy token</button><p class="caption">API address: ${esc(location.origin)}/api/integration/habit</p></div>
    <div id="apiTokenError" class="login-error" role="alert"></div><div id="apiTokenList" class="caption">Loading tokens…</div></div>`;
  body.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => { prefs.mode = b.dataset.mode; savePrefs(); applyPrefs(); renderSettings(); }));
  body.querySelectorAll('[data-style]').forEach((b) => b.addEventListener('click', () => {
    // Switching to Japanese starts on Ai indigo; the accent can be changed afterwards.
    if (b.dataset.style === 'wa' && prefs.style !== 'wa') prefs.accent = 'blue';
    prefs.style = b.dataset.style; savePrefs(); applyPrefs(); renderSettings();
    body.querySelector(`[data-style="${prefs.style}"]`)?.focus();
  }));
  body.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => body.querySelector(`[data-mode="${prefs.mode}"]`)?.focus()));
  body.querySelectorAll('[data-accent]').forEach((b) => b.addEventListener('click', () => { prefs.accent = b.dataset.accent; savePrefs(); applyPrefs(); renderSettings(); body.querySelector(`[data-accent="${prefs.accent}"]`)?.focus(); }));
  $('setDense').addEventListener('change', (e) => { prefs.dense = e.target.checked; savePrefs(); applyPrefs(); });
  $('setHide').addEventListener('change', (e) => { prefs.hidePoints = e.target.checked; savePrefs(); applyPrefs(); });
  $('setSignOut').addEventListener('click', () => signOut());
  $('apiTokenForm').addEventListener('submit', async (e) => {
    e.preventDefault(); const button = $('apiTokenCreate'), error = $('apiTokenError');
    button.disabled = true; error.classList.remove('show');
    try {
      const created = await tokenRequest('POST', '/api/tokens', { name: $('apiTokenName').value });
      $('apiTokenValue').value = created.token; $('apiTokenResult').hidden = false; $('apiTokenName').value = '';
      await refreshTokens(); $('apiTokenValue').focus(); $('apiTokenValue').select();
    } catch (err) { error.textContent = err.message; error.classList.add('show'); }
    finally { button.disabled = false; }
  });
  $('apiTokenCopy').addEventListener('click', async () => {
    $('apiTokenValue').select();
    try { await navigator.clipboard.writeText($('apiTokenValue').value); toast('Token copied'); }
    catch { toast('Select and copy the token'); }
  });
  refreshTokens();
}
function boot() {
  loadMirror();
  applyPrefs();
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyPrefs);
  $('loginForm').addEventListener('submit', onLogin);
  $('loginSwitch').addEventListener('click', () => setCreateMode(!creating));
  $('welcomeBack').addEventListener('click', () => { if (welcomePage) { welcomePage--; drawWelcome(); } else finishWelcome(); });
  $('welcomeNext').addEventListener('click', () => { if (welcomePage < welcomePages.length - 1) { welcomePage++; drawWelcome(); } else finishWelcome(); });
  // Hide "Create an account" when the server has sign-up turned off.
  fetch('/api/config').then((r) => r.json()).then((c) => { if (c && c.signup === false) $('loginSwitch').hidden = true; }).catch(() => {});
  $('acctBtn').addEventListener('click', (e) => { e.stopPropagation(); $('acctMenu').classList.toggle('open'); });
  document.addEventListener('click', (e) => { if (!e.target.closest('.acct-wrap')) $('acctMenu').classList.remove('open'); });
  if (localStorage.getItem(LS.key)) {
    ensureSingleActiveRun();
    setLoggedIn(true);
    showView('runs');
    showWelcomeIfNeeded();
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
  const area = b.actual ? `<defs><linearGradient id="${id}-g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:var(--accent)" stop-opacity=".22"/><stop offset="1" style="stop-color:var(--accent)" stop-opacity="0"/></linearGradient></defs>
    <path d="${b.actual} L ${b.X(dur - 1)} 116 L ${b.X(0)} 116 Z" fill="url(#${id}-g)" class="area-fill"/>` : '';
  return `<svg id="${id}" viewBox="0 0 330 148" class="burnup" role="img" aria-label="Points burnup">
    ${area}
    <line x1="20" y1="116" x2="320" y2="116" class="axis"/><line x1="20" y1="14" x2="20" y2="116" class="axis"/>
    <path d="${b.actual}" fill="none" class="line-actual" pathLength="100" stroke-width="3" stroke-linecap="round"/>
    <path d="${b.forecast}" fill="none" class="line-forecast" stroke-width="3" stroke-dasharray="6 5" stroke-linecap="round"/>
    <text x="20" y="136" class="axis-label" font-size="9">Day ${baseDay}</text><text x="285" y="136" class="axis-label" font-size="9">Day ${baseDay + dur - 1}</text>${dots}</svg>`;
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

/* ---------- Today (runs) ---------- */
function earnedOn(habits, day) {
  const pts = {};
  for (const h of habits) pts[h.uid] = h.impact_points;
  let total = 0;
  for (const c of Object.values(checkInsByDay(day))) {
    if (c.state === 'done' && pts[c.habit_uid]) total += pts[c.habit_uid];
  }
  return total;
}
/** Due/done counts for one day: resting interval habits and skipped habits are not due. */
function dayStatus(day, habits) {
  const ci = checkInsByDay(day);
  const resting = new Set(restingHabits(day, habits).map((h) => h.uid));
  let due = 0, done = 0, partial = 0;
  for (const h of habits) {
    if (resting.has(h.uid)) continue;
    const st = ci[h.uid] ? ci[h.uid].state : '';
    if (st === 'skipped') continue;
    due++;
    if (st === 'done') done++;
    else if (st === 'partial') partial++;
  }
  return { due, done, partial, complete: due > 0 && done + partial === due };
}
function runEnd(run) { return addDays(run.start_date, (run.duration_days || 21) - 1); }
/** Each day belongs to the most recently started run that covers it. */
function currentStreak(today) {
  const runs = allRuns();
  if (!runs.length) return 0;
  const floor = runs.reduce((m, r) => (r.start_date < m ? r.start_date : m), today);
  const habitCache = new Map();
  return streakLength(today, floor, (day) => {
    const run = runs.find((r) => r.start_date <= day && day <= runEnd(r));
    if (!run) return null;
    if (!habitCache.has(run.uid)) habitCache.set(run.uid, habitsForRun(run.uid));
    return dayStatus(day, habitCache.get(run.uid));
  });
}
function streakPill(n) {
  return n > 0 ? `<span class="streak-pill" role="img" aria-label="${n}-day streak">${svg('flame', 16)}<span aria-hidden="true">${n}-day streak</span></span>` : '';
}
const STATE_LABEL = { none: 'not done', done: 'done', partial: 'partial', skipped: 'skipped' };
function checkCircle(h, day, c, canLog) {
  const st = c ? safeState(c.state) || 'none' : 'none';
  const inner = st === 'done' ? svg('check', 22) : st === 'skipped' ? svg('minus', 20) : st === 'partial' ? '<i class="half" aria-hidden="true"></i>' : '';
  const label = `${h.title}, ${STATE_LABEL[st]}.`;
  return canLog
    ? `<button type="button" class="check ${st}" data-click="cycleCheck" data-a="${safeId(h.uid)}" data-b="${day}" aria-label="${esc(label)} Tap to change.">${inner}</button>`
    : `<span class="check ${st} readonly" role="img" aria-label="${esc(label)}">${inner}</span>`;
}
function habitRow(h, day, c, canLog, editable) {
  const st = c ? safeState(c.state) || 'none' : 'none';
  const meta = st === 'skipped' ? (day === todayLocal() ? 'Skipped today' : 'Skipped') : habitMeta(h, day);
  return `<div class="hrow ${st}"><span class="itile" aria-hidden="true">${svg(groupIconKey(h.group_name), 22)}</span>
    <div class="hinfo"><span class="hname">${esc(h.title)}<span class="iteration">${roman(h.iteration || 1)}</span></span><span class="hmeta">${meta}</span></div>
    ${st === 'done' ? `<span class="pts-pill pts">+${Number(h.impact_points) || 0}</span>` : ''}
    ${editable ? `<button type="button" class="icon-btn" data-click="openHabitEdit" data-a="${safeId(h.uid)}" aria-label="Edit ${esc(h.title)}" title="Edit habit">${svg('write', 16)}</button>` : ''}
    ${checkCircle(h, day, c, canLog)}</div>`;
}
/** Habits grouped by group name, each group a card of rows. */
function groupedRows(list, day, canLog, editable) {
  const groups = new Map();
  for (const h of list) {
    const g = h.group_name || 'General';
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g).push(h);
  }
  const ci = checkInsByDay(day);
  let html = '';
  for (const [g, items] of groups) {
    const done = items.filter((h) => ci[h.uid] && (ci[h.uid].state === 'done' || ci[h.uid].state === 'partial')).length;
    html += `<section class="hgroup"><div class="hgroup-head"><h2>${esc(g)}</h2><span>${done} of ${items.length} done</span></div>
      <div class="hcard">${items.map((h) => habitRow(h, day, ci[h.uid], canLog, editable)).join('')}</div></section>`;
  }
  return html;
}
function restingBlock(list, day, canLog, editable) {
  if (!list.length) return '';
  const ci = checkInsByDay(day);
  return `<details class="not-due"><summary>${svg('leaf', 18)}<span>Resting ${day === todayLocal() ? 'today' : 'this day'} · ${list.length} habit${list.length === 1 ? '' : 's'}</span>${svg('next', 16, 'chev')}</summary>
    <p class="caption">${canLog ? 'You can still log these habits whenever you choose.' : 'Past completions remain visible.'}</p>
    <div class="hcard">${list.map((h) => habitRow(h, day, ci[h.uid], canLog, editable)).join('')}</div></details>`;
}
/* ---------- day selection ---------- */
let selectedDay = '';
let selectedScope = '';
/** Keeps the selected day inside [min, max] for the current scope, defaulting to max. */
function pickDayIn(min, max, scope) {
  if (selectedScope !== scope || !selectedDay || selectedDay < min || selectedDay > max) {
    selectedScope = scope;
    selectedDay = max;
  }
  return selectedDay;
}
let dayRange = { min: '', max: '' };
function weekCard(day, min, max, statusFor) {
  dayRange = { min, max };
  const start = weekStart(day);
  const today = todayLocal();
  let cells = '';
  for (let i = 0; i < 7; i++) {
    const d = addDays(start, i);
    const date = new Date(d + 'T12:00:00');
    const inRange = d >= min && d <= max;
    let result = '';
    if (inRange && d <= today) {
      const s = statusFor(d);
      result = !s || !s.due ? '' : s.complete ? 'done' : s.done + s.partial ? 'partial' : d < today ? 'missed' : '';
    }
    const label = date.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' }) + (result ? `, ${result === 'done' ? 'all done' : result}` : '');
    cells += `<button type="button" class="wday${d === day ? ' selected' : ''}${d === today ? ' today' : ''}${result ? ' ' + result : ''}" data-click="pickDay" data-a="${d}" aria-pressed="${d === day}" aria-label="${esc(label)}"${inRange ? '' : ' disabled'}>
      <span class="dow" aria-hidden="true">${esc(date.toLocaleDateString(undefined, { weekday: 'narrow' }))}</span><span class="num" aria-hidden="true">${date.getDate()}</span><span class="wdot" aria-hidden="true"></span></button>`;
  }
  const hasPrev = addDays(start, -1) >= min;
  const hasNext = addDays(start, 7) <= max;
  const month = new Date(start + 'T12:00:00').toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
  return `<section class="card week-card" aria-label="Pick a day"><div class="card-head"><h2>${day === today ? 'This week' : esc(month)}</h2>
    <span class="week-nav"><button type="button" class="icon-btn" data-click="weekShift" data-a="-7" aria-label="Previous week"${hasPrev ? '' : ' disabled'}>${svg('prev', 18)}</button>
    <button type="button" class="icon-btn" data-click="weekShift" data-a="7" aria-label="Next week"${hasNext ? '' : ' disabled'}>${svg('next', 18)}</button></span></div>
    <div class="week">${cells}</div>${day !== today && max >= today && min <= today ? '<button type="button" class="btn-text small" data-click="pickDay" data-a="' + today + '">Back to today</button>' : ''}</section>`;
}
function shiftSelectedDay(n) {
  const target = addDays(selectedDay || todayLocal(), n);
  selectedDay = target < dayRange.min ? dayRange.min : target > dayRange.max ? dayRange.max : target;
  renderCurrent();
  document.querySelector(`[data-click="weekShift"][data-a="${n}"]:not([disabled])`)?.focus();
}
function selectDay(day) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day || '') || day < dayRange.min || day > dayRange.max) return;
  selectedDay = day;
  renderCurrent();
  document.querySelector(`[data-click="pickDay"][data-a="${day}"]`)?.focus();
}
/* ---------- check-in cycling ---------- */
function canLogHabit(uid, day) {
  const today = todayLocal();
  if (day > today) return false;
  if (unlinkedHabits().some((h) => h.uid === uid)) return true;
  const run = displayedRun();
  return !!run && run.status === 'active' && !isRunEnded(run, today) && day >= run.start_date && habitsForRun(run.uid).some((h) => h.uid === uid);
}
const NEXT_STATE = { '': 'done', done: 'partial', partial: 'skipped' };
/** Tap cycle: not done → done → partial → skipped → not done (the check-in is removed). */
function cycleCheck(habitUid, day) {
  if (!canLogHabit(habitUid, day)) return;
  const key = liveCheckInKey(habitUid, day);
  const cur = key ? row('check_in', key).v.state : '';
  if (cur === 'skipped') deleteRow('check_in', key);
  else {
    popKey = `[data-click="cycleCheck"][data-a="${CSS.escape(habitUid)}"][data-b="${day}"]`;
    setTimeout(() => { popKey = ''; }, 1000);
    logCheckIn(habitUid, day, Object.hasOwn(NEXT_STATE, cur) ? NEXT_STATE[cur] : 'done');
    renderCurrent();
  }
  document.querySelector(`[data-click="cycleCheck"][data-a="${CSS.escape(habitUid)}"][data-b="${day}"]`)?.focus();
}
/** Marks done, or skipped when already done (kept for scripted checks). */
function toggleDay(habitUid, day) {
  if (!canLogHabit(habitUid, day)) return;
  const cur = checkInsByDay(day)[habitUid];
  logCheckIn(habitUid, day, cur && cur.state === 'done' ? 'skipped' : 'done');
  renderCurrent();
}
/* ---------- sections ---------- */
function standaloneSection(day, title = 'Standalone habits') {
  const list = unlinkedHabits();
  const add = `<button type="button" class="add-dashed" data-click="openStandalone">${svg('plus', 18)} New standalone habit</button>`;
  if (!list.length) return add;
  const ci = checkInsByDay(day);
  return `<section class="hgroup standalone"><div class="hgroup-head"><h2>${esc(title)}</h2><span>Not in any run · always due</span></div>
    <div class="hcard">${list.map((h) => habitRow(h, day, ci[h.uid], day <= todayLocal(), true)).join('')}</div></section>${add}`;
}
function ringSvg(done, due, size) {
  const r = size / 2 - 6, c = 2 * Math.PI * r, pct = due ? done / due : 0;
  return `<div class="ring" style="width:${size}px;height:${size}px"><svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" role="img" aria-label="${done} of ${due} done">
    <circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" class="ring-track" stroke-width="10"/>
    <circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" class="ring-fill" stroke-width="10" stroke-linecap="round" pathLength="100" stroke-dasharray="${(pct * 100).toFixed(1)} 100" transform="rotate(-90 ${size / 2} ${size / 2})"/></svg>
    <div class="ring-label" aria-hidden="true"><b>${done}/${due}</b><span>done</span></div></div>`;
}
function chartCards(perDay, todayIdx, rangeLabel, caption, burnHtml, burnKey) {
  return `<section class="card chart-card pts-card"><div class="card-head"><h2>Points per day</h2><span class="caption">${caption}</span></div>
    ${barChart(perDay, todayIdx)}
    <div class="chart-key"><span>Earned impact points</span><span>${rangeLabel}</span></div></section>
    <section class="card chart-card pts-card"><div class="card-head"><h2>Points burnup</h2><span class="caption">Hover the line for values</span></div>
    ${burnHtml}<div class="chart-key"><span>━ Actual</span><span>┄ ${burnKey}</span></div></section>`;
}
function runManagement() {
  const runs = allRuns();
  if (!runs.length) return '';
  return `<section class="runs-section" id="allRuns"><div class="section-head"><h3>Your runs</h3><button type="button" class="btn tonal" data-click="openBuilder">${svg('plus', 16)} New run</button></div>
    <details class="run-management"><summary>Manage runs · ${runs.length}</summary><p class="caption">Stop an active run before starting another. Deleting a run keeps its habits and check-in history.</p>
    ${runs.map((r) => `<div class="run-management-row"><div><strong>${esc(r.name)}</strong><div class="caption">${esc(r.start_date)} · ${r.duration_days} days · ${r.status === 'active' ? 'Active' : 'Stopped'}</div></div><div class="head-btns">${r.status === 'stopped' ? `<button type="button" class="btn" data-click="showRunHistory" data-a="${safeId(r.uid)}">History</button><button type="button" class="btn" data-click="activateRun" data-a="${safeId(r.uid)}">Make active</button>` : ''}<button type="button" class="btn" data-click="openEdit" data-a="${safeId(r.uid)}">Edit</button>${r.status === 'active' ? `<button type="button" class="btn" data-click="stopRun" data-a="${safeId(r.uid)}">Stop run</button>` : ''}<button type="button" class="btn danger" data-click="deleteRun" data-a="${safeId(r.uid)}">Delete run</button></div></div>`).join('')}</details></section>`;
}
function openAllRuns() {
  const box = document.querySelector('#allRuns .run-management');
  if (!box) return;
  box.open = true;
  box.scrollIntoView({ block: 'start', behavior: 'smooth' });
  box.querySelector('summary').focus({ preventScroll: true });
}
function activateRun(uid) {
  const target = allRuns().find((r) => r.uid === uid && r.status === 'stopped');
  if (!target) return;
  if (!window.confirm(`Make “${target.name}” active? The current active run will be stopped. Habits and check-ins will be kept.`)) return;
  for (const run of allRuns().filter((r) => r.status === 'active')) putRow('run', run.uid, { status: 'stopped' }, false);
  putRow('run', uid, { status: 'active' }, false);
  historyRunUid = '';
  scheduleSync();
  renderRuns();
}
function showRunHistory(uid) {
  const run = allRuns().find((r) => r.uid === uid);
  if (!run || run.status !== 'stopped') return;
  historyRunUid = uid;
  showView('runs');
}
function stopRun(uid) {
  const run = row('run', uid)?.v;
  if (!run || run._deleted || run.status !== 'active') return;
  if (!window.confirm(`Stop “${run.name}”? You can still view its history.`)) return;
  putRow('run', uid, { status: 'stopped' });
  renderCurrent();
}
function deleteRun(uid) {
  const run = row('run', uid)?.v;
  if (!run || run._deleted) return;
  if (!window.confirm(`Delete “${run.name}”? Its habits and check-in history will be kept.`)) return;
  for (const link of rows('run_habit').filter((r) => r.run_uid === uid)) deleteRow('run_habit', link.uid);
  deleteRow('run', uid);
  if (historyRunUid === uid) historyRunUid = '';
  renderRuns();
}
function renderRuns() {
  const body = $('runsBody');
  const today = todayLocal();
  if (currentView === 'runs') $('topActions').innerHTML = streakPill(currentStreak(today));
  const run = displayedRun();
  if (!run) {
    body.innerHTML = (infiniteOn() ? infiniteView(today) : emptyRunsView(today)) + runManagement();
    wireBarTips(body); wireBurnupTips(body);
    return;
  }
  const dur = run.duration_days || 21;
  const ended = isRunEnded(run, today);
  const readOnly = ended || run.status !== 'active';
  const endDay = runEnd(run);
  const elapsed = ended ? dur : Math.min(Math.max(runDayIndex(run, today) + 1, 1), dur);
  const lastDay = endDay < today ? endDay : today;
  const started = run.start_date <= today;
  const day = started ? pickDayIn(run.start_date, lastDay, run.uid) : today;
  const canLog = !readOnly && started;
  const habits = habitsForRun(run.uid);
  const resting = restingHabits(day, habits);
  const restingIds = new Set(resting.map((h) => h.uid));
  const visibleHabits = habits.filter((h) => !restingIds.has(h.uid));
  let earned = 0, possible = 0;
  const perDay = [];
  for (let i = 0; i < elapsed; i++) {
    const date = addDays(run.start_date, i);
    const e = earnedOn(habits, date);
    perDay.push(e);
    earned += e;
    const restingIdsForDay = new Set(restingHabits(date, habits).map((h) => h.uid));
    possible += habits.filter((h) => !restingIdsForDay.has(h.uid)).reduce((s, h) => s + h.impact_points, 0);
  }
  const pct = possible ? Math.round((earned / possible) * 100) : 0;
  const st = dayStatus(day, habits);
  const doneCount = st.done + st.partial;
  const dayPts = earnedOn(habits, day);
  const dayIdx = Math.min(Math.max(runDayIndex(run, day) + 1, 1), dur);
  const isToday = day === today;
  const headline = !started ? `Starts ${fmtDay(run.start_date)}`
    : historyRunUid ? `Past run · ${fmtDay(run.start_date)} – ${fmtDay(endDay)}`
      : ended ? `Run complete · ${pct}% overall`
        : !isToday ? `Logging ${new Date(day + 'T12:00:00').toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}`
          : !st.due ? 'Nothing due today'
            : doneCount === st.due ? 'Perfect day!'
              : doneCount === 0 ? 'Fresh day — let’s go' : `Nice pace — ${st.due - doneCount} to go`;
  const eyebrow = `${esc(run.name)} · ${historyRunUid ? 'Stopped' : ended ? 'Completed' : `Day ${dayIdx} of ${dur}`}`;
  let bars = '';
  for (let i = 0; i < dur; i++) {
    const d = addDays(run.start_date, i);
    let cls = 'future';
    if (d <= today) {
      const s = dayStatus(d, habits);
      cls = !s.due ? 'rest' : s.complete ? 'done' : s.done + s.partial ? 'partial' : 'missed';
    }
    bars += `<span class="rday ${cls}${d === day ? ' sel' : ''}" title="${esc(fmtDay(d))}"></span>`;
  }
  const completeDays = perDay.length ? Array.from({ length: elapsed }, (_, i) => dayStatus(addDays(run.start_date, i), habits)).filter((s) => s.complete).length : 0;
  const actions = historyRunUid ? '<button type="button" class="btn surface" data-click="closeRunHistory">Back to current run</button>'
    : ended ? `<button type="button" class="btn primary" data-click="startNextRun" data-a="${safeId(run.uid)}">${svg('plus', 16)} Start next run</button>`
      : `<button type="button" class="btn surface" data-click="openEdit" data-a="${safeId(run.uid)}">${svg('write', 14)} Edit run</button>`;
  body.innerHTML = `<section class="hero run-card" aria-label="${historyRunUid ? 'Past run' : 'Active run'}">
    <div class="hero-top">${ringSvg(doneCount, st.due, 96)}
      <div class="hero-text"><span class="eyebrow">${eyebrow}</span><span class="hero-headline">${esc(headline)}</span>
      <span class="hero-meta"><span class="pts">+${dayPts} pts ${isToday ? 'today' : 'that day'} · </span>${pct}% of run so far${readOnly ? ` · ${ended ? 'Run completed' : 'Stopped run'} · history is read-only` : ''}</span></div>
      <div class="hero-stats"><div class="pts"><span>${isToday ? 'Today' : 'That day'}</span><b>+${dayPts} pts</b></div><div><span>Run so far</span><b>${pct}%</b></div><div class="pts"><span>Total</span><b>${earned} pts</b></div><div class="no-pts"><span>Complete days</span><b>${completeDays} / ${elapsed}</b></div></div></div>
    <div class="run-days" role="img" aria-label="Run days: ${completeDays} of ${elapsed} complete so far, ${dur} in total" style="--n:${dur}">${bars}</div>
    <div class="hero-actions">${actions}<button type="button" class="btn ghost" data-click="openAllRuns">All runs</button></div></section>
    <div class="today-grid"><div class="today-main">
    ${visibleHabits.length ? groupedRows(visibleHabits, day, canLog, !readOnly) : `<div class="empty">${habits.length ? 'Every habit is resting on this day.' : 'This run has no habits yet. Use Edit run to add some.'}</div>`}
    ${restingBlock(resting, day, canLog, !readOnly)}
    ${canLog ? '<p class="hint">Tap a circle: done → partial → skipped → clear.</p>' : ''}
    ${standaloneSection(day <= today ? day : today)}
    </div><aside class="today-side">
    ${started ? weekCard(day, run.start_date, lastDay, (d) => dayStatus(d, habits)) : ''}
    ${chartCards(perDay.concat(Array(Math.max(0, dur - elapsed)).fill(0)), elapsed - 1, `${fmtDay(run.start_date)} – ${fmtDay(endDay)}`, 'Whole run', burnupSvg('burnSvg', perDay, elapsed, dur, 1), 'Predicted')}
    </aside></div>
    ${runManagement()}`;
  wireBarTips(body); wireBurnupTips(body);
}
function emptyRunsView(today) {
  return `<section class="card empty-hero"><div class="empty-art" aria-hidden="true"><span class="t1">${svg('sun', 30)}</span><span class="t2">${svg('check', 36)}</span><span class="t3">${svg('moon', 30)}</span><i class="d1"></i><i class="d2"></i></div>
    <h2>Let's build your first run</h2><p>A run is a short stretch — 7, 14 or 21 days — of habits you want to try. You'll check them off here each day.</p>
    <div class="start-cards"><button type="button" class="source-card on" data-click="openBuilderFrom" data-a="scratch"><span class="stile">${svg('blank', 20)}</span><span class="st-title">Start blank</span><span class="st-sub">Pick habits yourself</span></button>
    <button type="button" class="source-card" data-click="openBuilderFrom" data-a="yaml"><span class="stile tile-yaml">${svg('code', 20)}</span><span class="st-title">Paste YAML</span><span class="st-sub">Plan it with an AI chat</span></button></div>
    <button type="button" class="btn-primary block" data-click="openBuilder">Build a run</button>
    <button type="button" class="btn-text" data-click="infiniteOn">Continue without a run</button></section>
    <div class="tip">${svg('leaf', 22)}<span>Tip: start with 3–5 habits. You can add more any time from Edit run.</span></div>
    ${standaloneSection(today)}`;
}
function infiniteView(today) {
  const list = unlinkedHabits();
  const start = addDays(today, -20);
  const day = pickDayIn(start, today, 'infinite');
  const perDay = [];
  for (let i = 0; i < 21; i++) perDay.push(earnedOn(list, addDays(start, i)));
  // Burnup carries the all-time baseline so the line never starts at zero.
  let baseline = 0;
  for (const c of rows('check_in')) {
    if (c.state !== 'done' || c.day >= start) continue;
    const h = row('habit', c.habit_uid);
    if (h && !h.v.archived) baseline += h.v.impact_points;
  }
  const st = dayStatus(day, list);
  let html = `<section class="hero run-card" aria-label="Without a run"><div class="hero-top">${ringSvg(st.done + st.partial, st.due, 96)}
    <div class="hero-text"><span class="eyebrow">Infinite run · last 21 days</span><span class="hero-headline">${day === today ? 'Today' : esc(fmtDay(day))}</span>
    <span class="hero-meta"><span class="pts">+${earnedOn(list, day)} pts · </span>Standalone habits stay due with or without a run.</span></div></div>
    <div class="hero-actions"><button type="button" class="btn primary" data-click="openBuilder">Build a run</button><button type="button" class="btn ghost" data-click="infiniteOff">Exit</button></div></section>
    <div class="today-grid"><div class="today-main">`;
  if (!list.length) {
    html += `<div class="empty">No standalone habits yet.<br>Add one below — it stays due with or without a run.</div>`;
  } else {
    const resting = restingHabits(day, list);
    const restingIds = new Set(resting.map((h) => h.uid));
    html += groupedRows(list.filter((h) => !restingIds.has(h.uid)), day, true, true) + restingBlock(resting, day, true, true);
  }
  html += `<button type="button" class="add-dashed" data-click="openStandalone">${svg('plus', 18)} New standalone habit</button></div>
    <aside class="today-side">${weekCard(day, start, today, (d) => dayStatus(d, list))}
    ${chartCards(perDay, 20, `${fmtDay(start)} – ${fmtDay(today)}`, 'Last 21 days', infiniteBurnup(perDay, baseline), 'Pace')}</aside></div>`;
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
  const area = `${actual} L ${X(20)} 116 L ${X(0)} 116 Z`;
  return `<svg viewBox="0 0 330 148" class="burnup" role="img" aria-label="Points burnup">
    <defs><linearGradient id="infinite-g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:var(--accent)" stop-opacity=".22"/><stop offset="1" style="stop-color:var(--accent)" stop-opacity="0"/></linearGradient></defs>
    <path d="${area}" fill="url(#infinite-g)" class="area-fill"/>
    <line x1="20" y1="116" x2="320" y2="116" class="axis"/><line x1="20" y1="14" x2="20" y2="116" class="axis"/>
    <path d="${actual}" fill="none" class="line-actual" pathLength="100" stroke-width="3" stroke-linecap="round"/>
    <path d="${forecast}" fill="none" class="line-forecast" stroke-width="3" stroke-dasharray="6 5" stroke-linecap="round"/>
    <text x="20" y="136" class="axis-label" font-size="9">${fmtDay(addDays(todayLocal(), -20))}</text><text x="285" y="136" class="axis-label" font-size="9">Today</text>${dots}</svg>`;
}

/* ---------- Quick Log (kept, nav hidden) ---------- */
let quickList = [], quickResults = [], quickCommentOpen = false, quickLogged = new Set(), quickSession = '';
function renderQuick() {
  const today = todayLocal();
  const run = activeRun();
  const endedRun = !run ? allRuns().find((candidate) => candidate.status === 'active' && isRunEnded(candidate, today)) : null;
  const host = $('quickBody');
  if (endedRun) {
    quickList = [];
    quickResults = [];
    host.innerHTML = `<div class="quick-summary"><h3 style="font-size:20px;font-weight:500">Run completed!</h3><p class="sub">There are no habits due today. Start a new run to resume Quick Log.</p><button class="btn primary" data-click="startNextRun" data-a="${safeId(endedRun.uid)}">Start next run</button></div>`;
    return;
  }
  const session = today + '|' + (run ? run.uid : '');
  if (session !== quickSession) { quickSession = session; quickLogged = new Set(); quickResults = []; }
  const pool = run ? habitsForRun(run.uid).concat(unlinkedHabits()) : unlinkedHabits();
  quickList = dueHabits(today, pool).filter((h) => !quickLogged.has(h.uid));
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
    <div class="quick-art-icon">${svg(groupIconKey(h.group_name), 46)}</div></div>
    <div class="quick-habit-name">${esc(h.title)}</div>
    <div class="quick-meta">${esc(recurrenceLabel(h))} · due today</div>
    <div class="quick-impact">${h.impact_points} impact points</div>
    <div class="comment-box" id="quickComment" style="display:${quickCommentOpen ? 'block' : 'none'}">
    <textarea class="field" id="quickNoteText" placeholder="Add a note about this habit today"></textarea></div>
    </article></div>
    <div class="quick-actions"><button class="quick-action partial" data-click="recordQuick" data-a="partial"><span>◐</span> Partial</button>
    <button class="comment-btn" data-click="toggleQuickComment">✎ Comment</button></div>
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
  if (!unlinkedHabits().some((item) => item.uid === h.uid) && !habitsForRun(activeRun()?.uid).some((item) => item.uid === h.uid)) {
    renderQuick();
    return;
  }
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
    <button class="build-button" style="margin-bottom:0" data-click="addGoal">Add goal</button></div></div>`;
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
      <button class="btn primary" data-click="logTracker" data-a="${safeId(t.uid)}">Log</button></div></div>`;
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

/* ---------- Run editor: one screen for New run and Edit run ---------- */
let editor = null;
function blankHabit() {
  return { uid: null, name: '', group: 'General', points: 1, interval: false, count: 3 };
}
function rowToEditor(h) {
  return { uid: h.uid, name: h.title, group: h.group_name, points: h.impact_points, interval: h.recurrence_type === 'interval', count: h.recurrence_value };
}
const isPresetLength = (d) => [7, 14, 21].includes(d);
const clampDays = (d) => Math.min(Math.max(Number(d) || 21, 1), 365);
const validStart = (s) => (/^\d{4}-\d{2}-\d{2}$/.test(s || '') ? s : todayLocal());
function openBuilder(source) {
  const run = latestRun() || allRuns()[0] || null;
  editor = {
    mode: 'new', uid: '', source: run ? 'copy' : 'scratch', copyFrom: run ? run.uid : '',
    name: '', days: run?.duration_days || 21, custom: !!run && !isPresetLength(run.duration_days), start: todayLocal(),
    includeStandalone: false, showGuide: false, yamlText: '', yamlErrors: [], yamlLoaded: false, attachOpen: false,
    habits: run ? habitsForRun(run.uid).map(rowToEditor) : [],
  };
  if (source === 'scratch' || source === 'yaml') { editor.source = source; editor.habits = []; }
  showView('editor');
}
function startNextRun(runUid) {
  openBuilder();
  if (runUid) builderCopyFrom(runUid);
}
function builderMode(m) {
  if (!editor || editor.mode !== 'new' || !['copy', 'scratch', 'yaml'].includes(m)) return;
  editor.source = m;
  editor.yamlLoaded = false;
  if (m === 'scratch' || m === 'yaml') editor.habits = [];
  if (m === 'copy') builderCopyFrom(editor.copyFrom || (latestRun() || {}).uid || '');
  else renderEditor();
  document.querySelector(`[data-click="builderMode"][data-a="${m}"]`)?.focus();
}
function builderCopyFrom(runUid) {
  editor.copyFrom = runUid;
  const run = allRuns().find((r) => r.uid === runUid);
  editor.habits = run ? habitsForRun(run.uid).map(rowToEditor) : [];
  if (editor.includeStandalone) {
    for (const h of unlinkedHabits()) editor.habits.push(rowToEditor(h));
  }
  if (run) { editor.days = run.duration_days || 21; editor.custom = !isPresetLength(editor.days); }
  renderEditor();
}
function openEdit(runUid) {
  const run = allRuns().find((r) => r.uid === runUid) || latestRun();
  if (!run) { toast('No run to edit.'); return; }
  editor = {
    mode: 'edit', uid: run.uid, name: run.name, days: run.duration_days || 21,
    custom: !isPresetLength(run.duration_days || 21), start: run.start_date, attachOpen: false,
    habits: habitsForRun(run.uid).map(rowToEditor),
  };
  showView('editor');
}
function scheduleText(h) {
  return h.interval ? (Number(h.count) === 1 ? 'every day' : `every ${h.count} days`) : `${h.count}× a week`;
}
const normName = (s) => String(s || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase();
function previewFacts(e) {
  const days = clampDays(e.days);
  const start = validStart(e.start);
  const named = e.habits.filter((h) => (h.name || '').trim());
  let perWeek = 0, ptsWeek = 0;
  for (const h of named) {
    const n = h.interval ? 7 / Math.max(1, Number(h.count) || 1) : Math.min(7, Math.max(1, Number(h.count) || 1));
    perWeek += n;
    ptsWeek += n * (Number(h.points) || 0);
  }
  return {
    days, range: `${fmtDay(start)} – ${fmtDay(addDays(start, days - 1))}`, count: named.length,
    name: (e.name || '').trim() || (e.mode === 'new' ? `${days}-day run` : 'Untitled run'),
    perWeek: Math.round(perWeek), ptsWeek: Math.round(ptsWeek),
  };
}
/** Typing in the basics updates the preview in place, so focus stays in the field. */
function updatePreview() {
  if (!editor || !$('pvName')) return;
  const f = previewFacts(editor);
  $('pvName').textContent = f.name;
  $('pvDays').textContent = `${f.days} days`;
  $('pvRange').textContent = f.range;
}
function editorSlot(e) {
  if (e.mode === 'edit') {
    const run = allRuns().find((r) => r.uid === e.uid);
    if (!run) return '';
    const today = todayLocal();
    const dur = run.duration_days || 21;
    const habits = habitsForRun(run.uid);
    const elapsed = isRunEnded(run, today) ? dur : Math.min(Math.max(runDayIndex(run, today) + 1, 0), dur);
    let earned = 0, possible = 0;
    for (let i = 0; i < elapsed; i++) {
      const d = addDays(run.start_date, i);
      earned += earnedOn(habits, d);
      const restingIds = new Set(restingHabits(d, habits).map((h) => h.uid));
      possible += habits.filter((h) => !restingIds.has(h.uid)).reduce((s, h) => s + h.impact_points, 0);
    }
    const pct = possible ? Math.round((earned / possible) * 100) : 0;
    const streak = currentStreak(today);
    const shared = e.habits.filter((h) => h.uid && runsUsingHabit(h.uid).length > 1).length;
    return `<section class="ed-block" aria-labelledby="slotTitle"><h2 id="slotTitle" class="sec-title">This run so far</h2>
      <div class="source-grid">
      <div class="source-card stat"><span class="stile tile-copy">${svg('cal', 18)}</span><span class="st-title">${elapsed ? `Day ${elapsed} of ${dur}` : 'Not started'}</span><span class="st-sub">Started ${esc(fmtDay(run.start_date))}</span></div>
      <div class="source-card stat"><span class="stile tile-scratch">${svg('chart', 18)}</span><span class="st-title">${pct}% so far</span><span class="st-sub pts">${earned} pts earned</span></div>
      <div class="source-card stat"><span class="stile tile-streak">${svg('flame', 18)}</span><span class="st-title">${streak}-day streak</span><span class="st-sub">${habits.length} habit${habits.length === 1 ? '' : 's'} in this run</span></div></div>
      <p class="note">${svg('info', 16)}<span>Your check-ins are kept. Editing a habit updates every run that uses it${shared ? ` — ${shared} of these ${shared === 1 ? 'is' : 'are'} shared with other runs` : ''}.</span></p></section>`;
  }
  const runs = allRuns();
  const copyRun = runs.find((r) => r.uid === e.copyFrom);
  const cards = [
    ['copy', 'Copy a run', copyRun ? `${copyRun.name} · ${habitsForRun(copyRun.uid).length} habits` : 'No runs yet', 'copy'],
    ['scratch', 'Blank', 'Pick habits yourself', 'blank'],
    ['yaml', 'Paste YAML', 'Plan it with an AI chat', 'code'],
  ];
  const notes = {
    copy: 'Copied habits stay linked to the original run — editing one here updates both. Past check-ins on overlapping dates count toward the new run.',
    scratch: 'Start empty, then add new habits or attach ones you already have.',
    yaml: 'Ask an AI chat for a run file, paste it below, then fine-tune it here.',
  };
  let html = `<section class="ed-block" aria-labelledby="slotTitle"><h2 id="slotTitle" class="sec-title">Start from</h2><div class="source-grid">
    ${cards.map(([k, title, sub, icon]) => `<button type="button" class="source-card${e.source === k ? ' on' : ''}" aria-pressed="${e.source === k}" data-click="builderMode" data-a="${k}"${k === 'copy' && !runs.length ? ' disabled' : ''}>
      <span class="stile tile-${k}">${svg(icon, 18)}</span><span class="st-title">${title}</span><span class="st-sub">${esc(sub)}</span></button>`).join('')}</div>
    <p class="note">${svg('info', 16)}<span>${notes[e.source]}</span></p>`;
  if (e.source === 'copy' && runs.length) {
    html += `<div class="card copy-box"><div class="copy-row"><label for="previousRun">Copy from</label>
      <select class="field" id="previousRun" data-change="copyFrom">${runs.map((r) => `<option value="${esc(safeId(r.uid))}"${r.uid === e.copyFrom ? ' selected' : ''}>${esc(r.name)} · ${r.duration_days} days</option>`).join('')}</select></div>
      <label class="checkline"><input type="checkbox" id="includeStandalone"${e.includeStandalone ? ' checked' : ''} data-change="includeStandalone"> Also include standalone habits</label></div>`;
  }
  return html + '</section>';
}
function yamlCard(e) {
  return `<section class="card yaml-card" aria-labelledby="yamlTitle"><h2 id="yamlTitle" class="card-title">1 · Copy the prompt into any AI chat</h2>
    <div class="yaml-actions"><button type="button" class="btn tonal" data-click="copyGuidePrompt">Copy AI prompt</button>
    <button type="button" class="btn" data-click="toggleGuide" aria-expanded="${!!e.showGuide}">${e.showGuide ? 'Hide prompt' : 'Show prompt'}</button></div>
    ${e.showGuide ? `<pre class="prompt">${esc(YAML_GUIDE_PROMPT)}</pre>` : ''}
    <label class="card-title" for="yamlText">2 · Paste the answer</label>
    <textarea class="field code" id="yamlText" placeholder="name: Reset week&#10;days: 14&#10;habits:&#10;  - name: Morning light&#10;    every: 1 day" data-input="editor" data-field="yamlText">${esc(e.yamlText)}</textarea>
    <div class="banner error${e.yamlErrors.length ? '' : ' hide'}" id="yamlErrors" role="alert">${e.yamlErrors.map(esc).join('<br>')}</div>
    <button type="button" class="btn-dark" data-click="loadYamlIntoEditor">Load into editor</button></section>`;
}
function draftRow(h, i, dupKey) {
  const isDup = !!dupKey && normName(h.name) === dupKey;
  const name = (h.name || '').trim() || 'Unnamed habit';
  return `<div class="hrow draft${isDup ? ' dup' : ''}"><span class="itile" aria-hidden="true">${svg(groupIconKey(h.group), 22)}</span>
    <div class="hinfo"><span class="hname">${esc(name)}</span><span class="hmeta">${isDup ? 'Same name as another habit' : `${esc(h.group || 'General')} · ${esc(scheduleText(h))}`}</span></div>
    <span class="pts-pill pts">${Number(h.points) || 0} pts</span>
    <button type="button" class="btn small" data-click="editDraft" data-a="${i}" aria-label="Edit ${esc(name)}">Edit ${svg('next', 14)}</button></div>`;
}
function renderEditor() {
  const body = $('editorBody');
  const e = editor;
  if (!e) { body.innerHTML = `<div class="empty">Pick a run to edit.</div><button type="button" class="btn" data-click="showView" data-a="runs">Back to Today</button>`; return; }
  const isNew = e.mode === 'new';
  $('topTitle').textContent = isNew ? 'New run' : 'Edit run';
  const f = previewFacts(e);
  const run = isNew ? null : allRuns().find((r) => r.uid === e.uid);
  const active = isNew ? activeRun() : null;
  const today = todayLocal();
  let eyebrow = 'New run · preview';
  if (run) {
    const dur = run.duration_days || 21;
    eyebrow = isRunEnded(run, today) ? 'Completed' : run.status !== 'active' ? 'Stopped' : run.start_date > today ? 'Starts soon' : `Active · Day ${Math.min(runDayIndex(run, today) + 1, dur)} of ${dur}`;
  }
  const dup = duplicateHabitName(e.habits);
  const dupKey = dup ? normName(dup) : '';
  const inList = new Set(e.habits.map((h) => h.uid).filter(Boolean));
  const unattached = rows('habit').filter((h) => !h.archived && !inList.has(h.uid)).sort((a, b) => (a.title < b.title ? -1 : 1));
  const tiles = e.habits.slice(0, 8).map((h) => `<span class="itile sm">${svg(groupIconKey(h.group), 17)}</span>`).join('');
  const activeDur = active ? active.duration_days || 21 : 0;
  body.innerHTML = `<div class="editor-bar"><button type="button" class="icon-btn lg" data-click="showView" data-a="runs" aria-label="Back to Today">${svg('back', 20)}</button>
    <span class="spacer"></span>${isNew ? '' : `<button type="button" class="btn" data-click="downloadRunYaml">${svg('download', 16)} Download YAML</button>`}</div>
    <div class="editor-grid">
    <div class="editor-side">
      <section class="hero preview" aria-label="Run preview"><span class="eyebrow">${esc(eyebrow)}</span><span class="pv-name" id="pvName">${esc(f.name)}</span>
        <div class="chips"><span class="chip-static" id="pvDays">${f.days} days</span><span class="chip-static" id="pvRange">${esc(f.range)}</span><span class="chip-static">${f.count} habit${f.count === 1 ? '' : 's'}</span></div>
        ${tiles ? `<div class="pv-tiles" aria-hidden="true">${tiles}</div>` : ''}
        <dl class="pv-facts"><div><dt>Check-ins per week</dt><dd>≈ ${f.perWeek}</dd></div><div class="pts"><dt>Max points per week</dt><dd>≈ ${f.ptsWeek}</dd></div></dl>
      </section>
      <div class="editor-actions"><button type="button" class="btn-text" data-click="showView" data-a="runs">Cancel</button>
        <button type="button" class="btn-primary" data-click="${isNew ? 'createRun' : 'saveEdit'}">${isNew ? 'Create run' : 'Save changes'}</button></div>
    </div>
    <div class="editor-main">
      <div id="runError" class="banner error hide" role="alert"></div>
      ${editorSlot(e)}
      ${active ? `<div class="banner warn" role="status">${svg('warn', 20)}<div><p>${esc(active.name)} is still running (day ${Math.min(Math.max(runDayIndex(active, today) + 1, 1), activeDur)} of ${activeDur}). Stop it before you create a new run.</p>
        <button type="button" class="btn surface small" data-click="editorStopRun" data-a="${safeId(active.uid)}">Stop ${esc(active.name)}</button></div></div>` : ''}
      ${isNew && e.source === 'yaml' ? yamlCard(e) : ''}
      <section class="card basics" aria-label="Run basics"><div class="form-grid">
        <div class="fld"><label for="runName">Run name</label><input class="field" id="runName" placeholder="Name this run" maxlength="200" value="${esc(e.name)}" data-input="editor" data-field="name"></div>
        <div class="fld"><label for="runStart">Starts</label><input class="field" id="runStart" type="date" value="${esc(e.start)}" data-input="editor" data-field="start"></div>
        <div class="fld span2"><span class="lbl" id="lenLbl">Length</span><div class="chip-row" role="group" aria-labelledby="lenLbl">
          ${[7, 14, 21].map((d) => `<button type="button" class="chip${!e.custom && e.days === d ? ' selected' : ''}" aria-pressed="${!e.custom && e.days === d}" data-click="builderDays" data-a="${d}">${d} days</button>`).join('')}
          <button type="button" class="chip${e.custom ? ' selected' : ''}" aria-pressed="${!!e.custom}" data-click="builderCustom">Custom</button></div>
          ${e.custom ? `<div class="custom-days"><input class="field num" id="customDays" type="number" min="1" max="365" value="${clampDays(e.days)}" aria-label="Custom length in days" data-input="editor" data-field="days"><span>days (1–365)</span></div>` : ''}</div>
      </div></section>
      <section class="ed-block" aria-labelledby="habitsTitle"><div class="sec-head"><h2 id="habitsTitle" class="sec-title">Habits · ${e.habits.length}</h2>
        <button type="button" class="btn tonal" data-click="toggleAttach" aria-expanded="${!!e.attachOpen}" aria-controls="attachBox">Add existing</button></div>
        ${e.attachOpen ? `<div class="card attach" id="attachBox"><label for="attachSel">Add a habit you already have</label><div class="attach-row">
          <select class="field" id="attachSel" data-change="attachUid"><option value="">${unattached.length ? 'Choose…' : 'No other habits'}</option>
          ${unattached.map((h) => `<option value="${esc(safeId(h.uid))}">${esc(h.title)} · ${esc(h.group_name)}</option>`).join('')}</select>
          <button type="button" class="btn" data-click="attachExisting">Add</button></div></div>` : ''}
        ${dup ? `<div class="banner error" role="alert">${svg('warn', 18)}<span>Two habits are called “${esc(dup)}”. Rename one to continue.</span></div>` : ''}
        ${e.habits.length ? `<div class="hcard" id="editorHabits">${e.habits.map((h, i) => draftRow(h, i, dupKey)).join('')}</div>` : '<div class="empty" id="editorHabits">No habits added yet. Add the first habit below.</div>'}
        <button type="button" class="add-dashed" data-click="newDraft">${svg('plus', 18)} New habit</button>
        ${isNew ? '' : '<p class="caption">YAML contains the run settings and habits, not check-ins or habit IDs. Importing it creates a new run.</p>'}
      </section>
    </div></div>`;
}
function copyGuidePrompt() {
  const done = () => toast('Prompt copied — paste it into any AI chat.');
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(YAML_GUIDE_PROMPT).then(done, () => toast('Copy failed — show the prompt and copy it manually.'));
  } else toast('Clipboard unavailable — show the prompt and copy it manually.');
}
function loadYamlIntoEditor() { applyYaml($('yamlText').value); }
/* Parses pasted YAML into the editor. Returns true when it loaded; otherwise shows the errors in the YAML card. */
function applyYaml(text) {
  const parsed = parseRunYaml(text);
  if (!parsed.ok) {
    editor.yamlErrors = parsed.errors;
    editor.yamlText = text;
    renderEditor();
    const box = $('yamlErrors');
    if (box && box.scrollIntoView) box.scrollIntoView({ block: 'center' });
    return false;
  }
  editor.yamlLoaded = true;
  editor.yamlErrors = [];
  editor.yamlText = text;
  if (parsed.run.name) editor.name = parsed.run.name;
  editor.days = parsed.run.days;
  editor.custom = !isPresetLength(parsed.run.days);
  if (parsed.run.start) editor.start = parsed.run.start;
  editor.habits = parsed.habits.map((h) => ({ uid: null, name: h.name, group: h.group, points: h.points, interval: h.interval > 0, count: h.interval > 0 ? h.interval : h.weekly }));
  toast(`Loaded ${editor.habits.length} habits — review and create the run.`);
  renderEditor();
  return true;
}
function showRunError(message) {
  const host = $('runError');
  if (host) { host.textContent = message; host.classList.remove('hide'); host.scrollIntoView({ block: 'center' }); }
  else toast(message);
}
/** Writes a draft habit: new habits get a uid, linked ones are updated in place. */
function saveDraftHabit(h) {
  const uid = h.uid || `h-${slug(h.name)}-${randHex(2)}`;
  const values = {
    title: h.name.trim(), group_name: (h.group || '').trim() || 'General',
    impact_points: Math.max(0, Number(h.points) || 0),
    recurrence_type: h.interval ? 'interval' : 'weekly',
    recurrence_value: Math.max(1, Number(h.count) || 1),
  };
  if (!h.uid) putRow('habit', uid, { uid, ...values, iteration: 1, archived: 0 });
  else if (row('habit', uid)) putRow('habit', uid, values);
  return uid;
}
function createRun() {
  const e = editor;
  if (activeRun()) { showRunError('Stop the active run before creating another. Use the Stop button above or All runs on Today.'); return; }
  // Pasted YAML that was never loaded would otherwise be ignored (create used whatever habits were in the editor).
  if (e.source === 'yaml' && !e.yamlLoaded) {
    const box = $('yamlText');
    const text = box ? box.value : e.yamlText;
    if (!String(text || '').trim()) { toast('Paste your YAML first.'); return; }
    if (!applyYaml(text)) { toast('The YAML has problems — see the red text above.'); return; }
  }
  const days = clampDays(e.days);
  const start = validStart(e.start);
  const named = e.habits.filter((h) => (h.name || '').trim());
  if (!named.length) { showRunError('Add at least one habit.'); return; }
  const duplicate = duplicateHabitName(named);
  if (duplicate) { showRunError(`“${duplicate}” appears more than once. Give each habit a different name.`); return; }
  for (const r of rows('run').filter((r) => r.status === 'active')) {
    putRow('run', r.uid, { status: 'stopped' }, false);
  }
  const runId = `run-${start}-${randHex(4)}`;
  putRow('run', runId, { uid: runId, name: (e.name || '').trim() || `${days}-day run`, start_date: start, duration_days: days, status: 'active' });
  historyRunUid = '';
  for (const h of named) {
    const uid = saveDraftHabit(h);
    const luid = linkUid(runId, uid);
    putRow('run_habit', luid, { uid: luid, run_uid: runId, habit_uid: uid });
  }
  quickLogged = new Set(); quickResults = []; quickSession = '';
  selectedScope = '';
  toast('Run created.');
  showView('runs');
}
function downloadRunYaml() {
  const e = editor;
  const habits = e.habits.filter((h) => (h.name || '').trim());
  if (!habits.length) { toast('Add at least one named habit before exporting.'); return; }
  const yaml = serializeRunYaml(e, habits);
  const parsed = parseRunYaml(yaml);
  if (!parsed.ok) { toast('Check the run date, length and habit schedules before exporting.'); return; }
  const url = URL.createObjectURL(new Blob([yaml], { type: 'application/yaml;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `${slug(e.name) || 'run'}.yaml`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function attachExisting() {
  const uid = editor.attachUid;
  if (!uid) { toast('Choose a habit first.'); return; }
  const h = row('habit', uid);
  if (!h || h.v._deleted) return;
  editor.habits.push(rowToEditor(h.v));
  editor.attachUid = '';
  renderEditor();
  $('attachSel')?.focus();
}
function saveEdit() {
  const e = editor;
  const named = e.habits.filter((x) => (x.name || '').trim());
  const duplicate = duplicateHabitName(named);
  if (duplicate) { showRunError(`“${duplicate}” appears more than once. Give each habit a different name.`); return; }
  const days = clampDays(e.days);
  const start = validStart(e.start);
  putRow('run', e.uid, { uid: e.uid, name: (e.name || '').trim() || 'Untitled run', start_date: start, duration_days: days });
  const keep = new Set();
  for (const h of named) {
    const uid = saveDraftHabit(h);
    const existing = rows('run_habit').find((r) => r.run_uid === e.uid && r.habit_uid === uid);
    const luid = existing ? existing.uid : linkUid(e.uid, uid);
    if (!existing) putRow('run_habit', luid, { uid: luid, run_uid: e.uid, habit_uid: uid });
    keep.add(luid);
  }
  // Detach habits removed in the editor (history stays on the habit).
  for (const rh of rows('run_habit').filter((r) => r.run_uid === e.uid)) {
    if (!keep.has(rh.uid)) deleteRow('run_habit', rh.uid);
  }
  selectedScope = '';
  toast('Run saved.');
  showView('runs');
}
function editorStopRun(uid) {
  stopRun(uid);
  if (currentView === 'editor') renderEditor();
}

/* ---------- Habit sheet (editor drafts, Today edits, new standalone habits) ---------- */
/**
 * target.kind: 'draft' (editor.habits[index]), 'draft-new', 'habit' (synced habit uid) or 'standalone'.
 * Native <dialog> gives Esc-to-close and keeps focus inside; focus returns to the opener on close.
 */
function openHabitSheet(target) {
  let src;
  if (target.kind === 'draft') src = editor && editor.habits[target.index];
  else if (target.kind === 'habit') {
    const h = row('habit', target.uid)?.v;
    if (!h || h._deleted || h.archived) return;
    src = rowToEditor(h);
  } else src = blankHabit();
  if (!src) return;
  const isNew = target.kind === 'draft-new' || target.kind === 'standalone';
  let restoreSel = target.kind === 'draft' ? `[data-click="editDraft"][data-a="${target.index}"]`
    : target.kind === 'habit' ? `[data-click="openHabitEdit"][data-a="${CSS.escape(target.uid)}"]`
      : target.kind === 'standalone' ? '[data-click="openStandalone"]' : '[data-click="newDraft"]';
  const opener = document.activeElement;
  const groups = [...new Set(rows('habit').filter((h) => !h.archived).map((h) => h.group_name || 'General')
    .concat(editor ? editor.habits.map((h) => h.group || 'General') : []))].sort().slice(0, 10);
  let kind = src.interval ? 'interval' : 'weekly';
  const dialog = document.createElement('dialog');
  dialog.className = 'sheet';
  dialog.setAttribute('aria-labelledby', 'sheetTitle');
  dialog.innerHTML = `<form method="dialog" class="sheet-form"><div class="sheet-grip" aria-hidden="true"></div>
    <div class="sheet-head"><span class="itile xl" aria-hidden="true" id="sheetIcon">${svg(groupIconKey(src.group), 30)}</span>
      <div class="spacer"><h2 id="sheetTitle">${isNew ? 'New habit' : 'Edit habit'}</h2><span class="caption" id="sheetSummary"></span></div>
      ${target.kind === 'draft' ? '<button type="button" class="btn danger" id="sheetRemove">Remove</button>' : ''}</div>
    ${target.kind === 'habit' ? '<p class="caption">Changes apply everywhere this habit is used. Check-ins are kept.</p>' : ''}
    ${target.kind === 'standalone' ? '<p class="caption">Standalone habits are not in any run. They always appear, and count toward a run once attached to one.</p>' : ''}
    <div class="fld"><label for="habitEditName">Name</label><input class="field" id="habitEditName" required maxlength="200" autocomplete="off" value="${esc(src.name)}"></div>
    <fieldset class="fld"><legend>How often</legend>
      <div class="segmented" role="group" aria-label="Schedule type"><button type="button" data-kind="weekly">Days per week</button><button type="button" data-kind="interval">Every N days</button></div>
      <div class="stepper"><button type="button" class="step" data-for="habitEditCount" data-d="-1" aria-label="Fewer">${svg('minus', 18)}</button>
        <input class="field num" id="habitEditCount" type="number" min="1" step="1" required value="${Math.max(1, Number(src.count) || 1)}">
        <button type="button" class="step" data-for="habitEditCount" data-d="1" aria-label="More">${svg('plus', 18)}</button><span class="unit" id="sheetUnit"></span></div>
      <div class="week-preview" aria-hidden="true" id="sheetPreview"></div>
      <p class="helper" id="sheetHelper"></p></fieldset>
    <div class="sheet-row">
      <div class="fld pts-field"><label for="habitEditPoints">Points each time</label><div class="stepper">
        <button type="button" class="step" data-for="habitEditPoints" data-d="-1" aria-label="Fewer points">${svg('minus', 18)}</button>
        <input class="field num" id="habitEditPoints" type="number" min="0" step="1" required value="${Math.max(0, Number(src.points) || 0)}">
        <button type="button" class="step" data-for="habitEditPoints" data-d="1" aria-label="More points">${svg('plus', 18)}</button></div></div>
      <div class="fld"><label for="habitEditGroup">Group</label>
        <div class="chip-row" id="sheetGroups">${groups.map((g) => `<button type="button" class="chip small" data-group="${esc(g)}">${esc(g)}</button>`).join('')}</div>
        <input class="field" id="habitEditGroup" maxlength="200" value="${esc(src.group || 'General')}" placeholder="New group name"></div>
    </div>
    <p class="form-error" role="alert" id="sheetError"></p>
    <div class="sheet-actions"><button type="button" class="btn-text" id="sheetCancel">Cancel</button><button type="submit" class="btn-primary" value="save">Done</button></div></form>`;
  document.body.append(dialog);
  const q = (sel) => dialog.querySelector(sel);
  const nameEl = q('#habitEditName'), countEl = q('#habitEditCount'), pointsEl = q('#habitEditPoints'), groupEl = q('#habitEditGroup');
  const refresh = () => {
    countEl.max = kind === 'weekly' ? '7' : '365';
    countEl.setAttribute('aria-label', kind === 'weekly' ? 'Days per week' : 'Repeat every N days');
    const n = Math.max(1, Math.round(Number(countEl.value) || 1));
    dialog.querySelectorAll('[data-kind]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.kind === kind)));
    q('#sheetUnit').textContent = kind === 'weekly' ? 'days per week' : n === 1 ? 'every day' : 'days between';
    q('#sheetHelper').textContent = kind === 'weekly'
      ? `Do it on any ${n} day${n === 1 ? '' : 's'} each week. It shows as due until you hit the target.`
      : `Due every ${n === 1 ? 'day' : n + ' days'}. If you miss it, it stays due until done.`;
    q('#sheetPreview').innerHTML = ['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d, i) => `<span class="${(kind === 'interval' ? i % n === 0 : i < n) ? 'hit' : ''}">${d}</span>`).join('');
    const group = groupEl.value.trim() || 'General';
    q('#sheetSummary').innerHTML = `${esc(kind === 'interval' ? (n === 1 ? 'Every day' : `Every ${n} days`) : `${n}× a week`)}<span class="pts"> · ${Math.max(0, Number(pointsEl.value) || 0)} pts</span> · ${esc(group)}`;
    q('#sheetIcon').innerHTML = svg(groupIconKey(group), 30);
    dialog.querySelectorAll('[data-group]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.group === group)));
  };
  const close = () => dialog.close();
  dialog.addEventListener('close', () => {
    dialog.remove();
    const back = opener && opener.isConnected ? opener : document.querySelector(restoreSel);
    if (back) back.focus();
  });
  dialog.querySelectorAll('[data-kind]').forEach((b) => b.addEventListener('click', () => { kind = b.dataset.kind; refresh(); }));
  dialog.querySelectorAll('.step').forEach((b) => b.addEventListener('click', () => {
    const input = q('#' + b.dataset.for);
    const min = Number(input.min || 0), max = input.max ? Number(input.max) : Infinity;
    input.value = String(Math.min(max, Math.max(min, Math.round(Number(input.value) || 0) + Number(b.dataset.d))));
    refresh();
  }));
  dialog.querySelectorAll('[data-group]').forEach((b) => b.addEventListener('click', () => { groupEl.value = b.dataset.group; refresh(); }));
  [countEl, pointsEl, groupEl].forEach((el) => el.addEventListener('input', refresh));
  q('#sheetCancel').addEventListener('click', close);
  q('#sheetRemove')?.addEventListener('click', () => {
    editor.habits.splice(target.index, 1);
    restoreSel = '[data-click="newDraft"]';
    renderEditor();
    close();
  });
  q('form').addEventListener('submit', (event) => {
    event.preventDefault();
    const error = q('#sheetError');
    const name = nameEl.value.trim();
    if (!name) { error.textContent = 'Name the habit first.'; nameEl.focus(); return; }
    const draft = {
      name, group: groupEl.value.trim() || 'General', points: Math.max(0, Math.round(Number(pointsEl.value) || 0)),
      interval: kind === 'interval', count: Math.max(1, Math.round(Number(countEl.value) || 1)),
    };
    if (target.kind === 'habit') {
      const uid = target.uid;
      const duplicate = runsUsingHabit(uid).some((run) => duplicateHabitName(habitsForRun(run.uid).map((habit) => ({ name: habit.uid === uid ? name : habit.title }))));
      if (duplicate) { error.textContent = 'A habit with this name already exists in a run that uses it.'; return; }
      putRow('habit', uid, { title: name, group_name: draft.group, impact_points: draft.points, recurrence_type: draft.interval ? 'interval' : 'weekly', recurrence_value: draft.count });
      renderCurrent();
      toast('Habit saved.');
    } else if (target.kind === 'standalone') {
      const uid = `h-${slug(name)}-${randHex(2)}`;
      putRow('habit', uid, { uid, title: name, group_name: draft.group, iteration: 1, impact_points: draft.points, recurrence_type: draft.interval ? 'interval' : 'weekly', recurrence_value: draft.count, archived: 0 });
      restoreSel = `[data-click="openHabitEdit"][data-a="${CSS.escape(uid)}"]`;
      renderCurrent();
      toast('Standalone habit added.');
    } else if (target.kind === 'draft') {
      editor.habits[target.index] = { ...src, ...draft };
      renderEditor();
    } else {
      editor.habits.push({ uid: null, ...draft });
      restoreSel = `[data-click="editDraft"][data-a="${editor.habits.length - 1}"]`;
      renderEditor();
    }
    close();
  });
  refresh();
  dialog.showModal();
  nameEl.focus();
}
function openHabitEdit(uid) { openHabitSheet({ kind: 'habit', uid }); }
function openStandalone() { openHabitSheet({ kind: 'standalone' }); }

/* ---------- module → window (scripted checks) ---------- */
Object.assign(window, {
  showView, openBuilder, startNextRun, builderMode, builderCopyFrom,
  copyGuidePrompt, loadYamlIntoEditor, createRun, openEdit, attachExisting, saveEdit,
  openStandalone, toggleDay, cycleCheck, setInfinite, recordQuick, setQuickComment,
  addGoal, logTracker, renderRuns, renderQuick, renderEditor, signOut, blankHabit,
});
Object.defineProperties(window, {
  editor: { get: () => editor, set: (v) => { editor = v; } },
  quickCommentOpen: { get: () => quickCommentOpen, set: (v) => { quickCommentOpen = v; } },
});

/* ---------- UI events ----------
 * Delegated from the document so the page contains no inline scripts and the
 * CSP can forbid them. Markup carries data-click / data-input / data-change.
 */
const ACTIONS = {
  cycleCheck: (a, b) => cycleCheck(a, b),
  pickDay: (a) => selectDay(a),
  weekShift: (a) => shiftSelectedDay(Number(a)),
  openAllRuns: () => openAllRuns(),
  openStandalone: () => openStandalone(),
  openHabitEdit: (a) => openHabitEdit(a),
  openBuilder: () => openBuilder(),
  openBuilderFrom: (a) => openBuilder(a),
  startNextRun: (a) => startNextRun(a),
  openEdit: (a) => openEdit(a),
  showRunHistory: (a) => showRunHistory(a),
  activateRun: (a) => activateRun(a),
  closeRunHistory: () => { historyRunUid = ''; selectedScope = ''; renderRuns(); },
  stopRun: (a) => stopRun(a),
  deleteRun: (a) => deleteRun(a),
  infiniteOn: () => { setInfinite(true); renderRuns(); },
  infiniteOff: () => { setInfinite(false); renderRuns(); },
  recordQuick: (a) => recordQuick(a),
  toggleQuickComment: () => setQuickComment(!quickCommentOpen),
  addGoal: () => addGoal(),
  logTracker: (a) => logTracker(a),
  builderMode: (a) => builderMode(a),
  copyGuidePrompt: () => copyGuidePrompt(),
  toggleGuide: () => { editor.showGuide = !editor.showGuide; renderEditor(); $('yamlTitle')?.parentElement.querySelector('[data-click="toggleGuide"]')?.focus(); },
  loadYamlIntoEditor: () => loadYamlIntoEditor(),
  builderDays: (a) => { editor.days = Number(a); editor.custom = false; renderEditor(); document.querySelector(`[data-click="builderDays"][data-a="${Number(a)}"]`)?.focus(); },
  builderCustom: () => { editor.custom = true; renderEditor(); $('customDays')?.focus(); },
  toggleAttach: () => { editor.attachOpen = !editor.attachOpen; renderEditor(); ($('attachSel') || document.querySelector('[data-click="toggleAttach"]'))?.focus(); },
  newDraft: () => openHabitSheet({ kind: 'draft-new' }),
  editDraft: (a) => openHabitSheet({ kind: 'draft', index: Number(a) }),
  editorStopRun: (a) => editorStopRun(a),
  createRun: () => createRun(),
  showView: (a) => showView(a),
  attachExisting: () => attachExisting(),
  downloadRunYaml: () => downloadRunYaml(),
  saveEdit: () => saveEdit(),
};
const RUN_FIELDS = { name: (v) => v, start: (v) => v, days: (v) => Number(v) || 21, yamlText: (v) => v };
document.addEventListener('click', (e) => {
  const el = e.target instanceof Element ? e.target.closest('[data-click]') : null;
  if (!el || el.disabled) return;
  const act = Object.hasOwn(ACTIONS, el.dataset.click) ? ACTIONS[el.dataset.click] : null;
  if (act) act(el.dataset.a, el.dataset.b, el.dataset.c);
});
document.addEventListener('input', (e) => {
  const el = e.target instanceof Element ? e.target.closest('[data-input]') : null;
  if (!el || !editor || el.dataset.input !== 'editor' || !Object.hasOwn(RUN_FIELDS, el.dataset.field)) return;
  editor[el.dataset.field] = RUN_FIELDS[el.dataset.field](el.value);
  if (el.dataset.field === 'yamlText') editor.yamlLoaded = false;
  else updatePreview();
});
document.addEventListener('change', (e) => {
  const el = e.target instanceof Element ? e.target.closest('[data-change]') : null;
  if (!el || !editor) return;
  if (el.dataset.change === 'attachUid') editor.attachUid = el.value;
  else if (el.dataset.change === 'copyFrom') builderCopyFrom(el.value);
  else if (el.dataset.change === 'includeStandalone') { editor.includeStandalone = el.checked; builderCopyFrom(editor.copyFrom); }
  if (el.id && el.id !== 'attachSel') $(el.id)?.focus();
});

document.addEventListener('DOMContentLoaded', boot);
