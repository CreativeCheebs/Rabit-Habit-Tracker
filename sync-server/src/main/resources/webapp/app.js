'use strict';
/* Habit Lab desktop web client: thin sync mirror + desktop screens. */
import { parseRunYaml, serializeRunYaml, YAML_GUIDE_PROMPT } from './yaml.js';

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
let selectedRunUid = '';
function latestRun() {
  const runs = rows('run').filter((r) => r.status === 'active');
  runs.sort((a, b) => b.start_date.localeCompare(a.start_date));
  return runs.find((r) => r.uid === selectedRunUid) || runs[0] || null;
}
function allRuns() {
  const runs = rows('run');
  runs.sort((a, b) => (a.start_date < b.start_date ? 1 : -1));
  return runs;
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
function showRunError(message) {
  const host = $(currentView === 'edit' ? 'editRunError' : 'builderRunError');
  if (host) { host.textContent = message; host.classList.add('show'); host.scrollIntoView({ block: 'center' }); }
  else toast(message);
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
const TITLES = { settings: 'Settings', runs: 'Runs & Habits', analytics: 'Analytics', goals: 'Goals', trackers: 'Trackers', quicklog: 'Quick Log', builder: 'New run', edit: 'Edit run' };
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
const ACCENTS = [['green', 'Green', '#a4d0ad'], ['teal', 'Teal', '#8fd3cf'], ['blue', 'Blue', '#a9c7ff'], ['purple', 'Purple', '#cdb7f5'],
  ['pink', 'Pink', '#f2b1c9'], ['orange', 'Orange', '#f5bc8a'], ['yellow', 'Yellow', '#e3ce7a'], ['red', 'Red', '#f0a9a0']];
let prefs = { mode: 'system', accent: 'green', dense: false, hidePoints: false };
try { prefs = { ...prefs, ...JSON.parse(localStorage.getItem(PREF_KEY) || '{}') }; } catch (e) { /* keep defaults */ }
function savePrefs() { try { localStorage.setItem(PREF_KEY, JSON.stringify(prefs)); } catch (e) { /* private mode */ } }
const hexToRgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
function mix(a, b, t) {
  const x = hexToRgb(a), y = hexToRgb(b);
  return '#' + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, '0')).join('');
}
/* Same recipe as the Android palette so both clients look alike. */
function buildPalette(dark, seed) {
  if (dark) {
    const surface = mix('#161b17', seed, .05);
    const s1 = mix(surface, '#ffffff', .03);
    return { bg: mix('#101311', seed, .04), page: mix('#0b0e0c', seed, .03), surface, surface1: s1, surface2: mix(surface, '#ffffff', .07), surface3: mix(surface, '#ffffff', .11),
      ink: '#e5ece6', muted: '#a3afa6', outline: mix(surface, '#ffffff', .22), primary: seed, 'on-primary': mix(seed, '#000000', .78), secondary: '#b9cbbd',
      tertiary: '#dfbd82', error: '#ffb4ab', partial: '#efcf90', soft: mix(surface, seed, .26), soft2: mix(surface, seed, .14), 'on-soft': mix(seed, '#ffffff', .6),
      track: mix(surface, seed, .32), divider: mix(surface, '#ffffff', .1), line2: mix(surface, '#ffffff', .07), dim: mix(surface, '#ffffff', .12),
      skipped: '#8a5757', 'skip-soft': '#342927', 'skip-border': '#60423e', 'partial-soft': '#3c3424', 'partial-border': '#655633' };
  }
  const accent = mix(seed, '#000000', .42);
  const surface = '#ffffff';
  return { bg: mix('#f6f7f5', seed, .06), page: mix('#eef0ed', seed, .08), surface, surface1: mix('#ffffff', seed, .06), surface2: mix('#ffffff', seed, .14), surface3: mix('#ffffff', seed, .22),
    ink: '#191c1a', muted: '#5a635d', outline: mix('#ffffff', '#000000', .22), primary: accent, 'on-primary': '#ffffff', secondary: '#4b554e',
    tertiary: '#9a6b10', error: '#b3261e', partial: '#b77b10', soft: mix('#ffffff', seed, .55), soft2: mix('#ffffff', seed, .28), 'on-soft': '#191c1a',
    track: mix('#ffffff', seed, .5), divider: mix('#ffffff', '#000000', .1), line2: mix('#ffffff', '#000000', .08), dim: mix('#ffffff', '#000000', .08),
    skipped: '#d08c87', 'skip-soft': '#f7d9d6', 'skip-border': '#e3a8a3', 'partial-soft': '#f6e3be', 'partial-border': '#e0c185' };
}
function applyPrefs() {
  const dark = prefs.mode === 'dark' || (prefs.mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  const seed = (ACCENTS.find((a) => a[0] === prefs.accent) || ACCENTS[0])[2];
  const root = document.documentElement;
  for (const [k, v] of Object.entries(buildPalette(dark, seed))) root.style.setProperty('--' + k, v);
  root.style.colorScheme = dark ? 'dark' : 'light';
  document.body.classList.toggle('dense', !!prefs.dense);
  document.body.classList.toggle('hide-points', !!prefs.hidePoints);
}
function habitMeta(h) {
  const rec = esc(recurrenceLabel(h).toLowerCase());
  return prefs.hidePoints ? rec : `${h.impact_points} impact pts · ${rec}`;
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
  body.innerHTML = `<div class="set-card"><h3>Appearance</h3><div class="caption">Saved in this browser. The Android app has its own copy of these settings.</div>
    <label class="field-label" style="margin-top:0">Theme</label>
    <div class="chip-row">${[['system', 'System'], ['light', 'Light'], ['dark', 'Dark']].map(([k, l]) => `<button class="duration-chip${prefs.mode === k ? ' selected' : ''}" data-mode="${k}">${l}</button>`).join('')}</div>
    <label class="field-label">Accent color</label>
    <div class="swatch-row">${ACCENTS.map(([k, l, c]) => `<button class="swatch${prefs.accent === k ? ' on' : ''}" data-accent="${k}" style="background:${c}" aria-label="Accent ${l}" title="${l}">${prefs.accent === k ? '✓' : ''}</button>`).join('')}</div></div>
    <div class="set-card"><h3>Habit list</h3>
    <div class="set-row"><div><b>Dense layout</b><div class="caption">One compact line per habit, small round day dots. For long lists.</div></div>
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
  body.querySelectorAll('[data-accent]').forEach((b) => b.addEventListener('click', () => { prefs.accent = b.dataset.accent; savePrefs(); applyPrefs(); renderSettings(); }));
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
  return `<svg id="${id}" viewBox="0 0 330 148" style="width:100%;height:auto;margin-top:8px" role="img" aria-label="Points burnup">
    <line x1="20" y1="116" x2="320" y2="116" style="stroke:var(--outline)"/><line x1="20" y1="14" x2="20" y2="116" style="stroke:var(--outline)"/>
    <path d="${b.actual}" fill="none" style="stroke:var(--primary)" stroke-width="3" stroke-linecap="round"/>
    <path d="${b.forecast}" fill="none" style="stroke:var(--tertiary)" stroke-width="3" stroke-dasharray="6 5" stroke-linecap="round"/>
    <text x="20" y="136" style="fill:var(--muted)" font-size="9">Day ${baseDay}</text><text x="285" y="136" style="fill:var(--muted)" font-size="9">Day ${baseDay + dur - 1}</text>${dots}</svg>`;
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
  const cols = `grid-template-columns:repeat(${count},minmax(22px,1fr))`;
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
      <div class="habit-meta">${habitMeta(h)}</div></div>
      <div class="dots" style="${cols}">`;
    for (let d = 0; d < count; d++) {
      const day = addDays(startDate, d);
      const c = checkInsByDay(day)[h.uid];
      const future = day > todayIso;
      body += future
        ? `<span class="dot future"></span>`
        : `<button class="dot ${c ? safeState(c.state) : ''}${day === todayIso ? ' today' : ''}" aria-label="${esc(h.title)} ${day}" data-click="toggleDay" data-a="${safeId(h.uid)}" data-b="${day}"></button>`;
    }
    body += `</div><div class="point">${h.impact_points}</div></div>`;
  }
  return `<div class="htable" style="--day-count:${count}">${head}<div class="run-table">${body}</div></div>`;
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
      <div class="habit-meta">${habitMeta(h)}</div></div>
      <div><button class="btn${c && c.state === 'done' ? ' primary' : ''}" data-click="toggleDay" data-a="${safeId(h.uid)}" data-b="${today}">${c && c.state === 'done' ? '✓ Done today' : c ? esc(c.state) + ' · tap for done' : 'Log today'}</button></div>
      <div class="point">${h.impact_points}</div></div>`;
  }
  html += `</div><button class="add-habit" data-click="openStandalone">＋ New standalone habit</button><div id="standaloneForm"></div>`;
  return html;
}
function runManagement() {
  const runs = allRuns();
  if (!runs.length) return '';
  return `<details class="run-management"><summary>Manage runs · ${runs.length}</summary><p class="caption">Stop an active run before starting another. Deleting a run keeps its habits and check-in history.</p>
    ${runs.map((r) => `<div class="run-management-row"><div><strong>${esc(r.name)}</strong><div class="caption">${esc(r.start_date)} · ${r.duration_days} days · ${r.status === 'active' ? 'Active' : 'Stopped'}</div></div><div class="head-btns"><button class="btn" data-click="viewRun" data-a="${safeId(r.uid)}">View</button><button class="btn" data-click="openEdit" data-a="${safeId(r.uid)}">Edit</button>${r.status === 'active' ? `<button class="btn" data-click="stopRun" data-a="${safeId(r.uid)}">Stop run</button>` : ''}<button class="btn" data-click="deleteRun" data-a="${safeId(r.uid)}">Delete run</button></div></div>`).join('')}</details>`;
}
function stopRun(uid) {
  const run = row('run', uid)?.v;
  if (!run || run._deleted || run.status !== 'active') return;
  if (!window.confirm(`Stop “${run.name}”? You can still view its history.`)) return;
  putRow('run', uid, { status: 'stopped' });
  selectedRunUid = uid;
  renderRuns();
}
function deleteRun(uid) {
  const run = row('run', uid)?.v;
  if (!run || run._deleted) return;
  if (!window.confirm(`Delete “${run.name}”? Its habits and check-in history will be kept.`)) return;
  for (const link of rows('run_habit').filter((r) => r.run_uid === uid)) deleteRow('run_habit', link.uid);
  deleteRow('run', uid);
  if (selectedRunUid === uid) selectedRunUid = '';
  renderRuns();
}
function renderRuns() {
  const body = $('runsBody');
  const run = allRuns().find((r) => r.uid === selectedRunUid) || latestRun();
  const today = todayLocal();
  if (!run) {
    body.innerHTML = (infiniteOn() ? infiniteView(today) : emptyRunsView()) + runManagement();
    wireBarTips(body); wireBurnupTips(body);
    return;
  }
  const dur = run.duration_days || 21;
  const elapsed = Math.min(Math.max(runDayIndex(run, today) + 1, 1), dur);
  const endDay = addDays(run.start_date, dur - 1);
  const habits = habitsForRun(run.uid);
  const resting = restingHabits(today, habits);
  const restingIds = new Set(resting.map((h) => h.uid));
  const visibleHabits = habits.filter((h) => !restingIds.has(h.uid));
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
    <div class="caption" style="margin-top:8px;color:var(--on-soft)">${earned} / ${possible}</div></div>
    <div class="metrics-card"><div class="metrics">
    <div class="metric"><small>Total impact</small><b>${earned} pts</b></div>
    <div class="metric"><small>Average impact</small><b>${elapsed ? (earned / elapsed).toFixed(1) : '0.0'} / day</b></div>
    <div class="metric"><small>Days logged</small><b>${perDay.filter((e) => e > 0).length} / ${elapsed}</b></div>
    <div class="metric"><small>Habits</small><b>${habits.length}</b></div>
    </div></div></div>
    <div class="section-head"><h3>Habits</h3><span class="head-btns"><label for="activeRunSelect">Run</label><select class="field" id="activeRunSelect" data-change="activeRun">${allRuns().map((r) => `<option value="${esc(safeId(r.uid))}"${r.uid === run.uid ? ' selected' : ''}>${esc(r.name)} · ${r.status === 'active' ? 'Active' : 'Stopped'} · ${esc(r.start_date)}</option>`).join('')}</select><button class="btn" data-click="openBuilder">＋ New run</button><button class="btn" data-click="openEdit" data-a="${safeId(run.uid)}">Edit run</button></span></div>
    ${habitTable(visibleHabits, run.start_date, dur, today, 1)}
    ${resting.length ? `<details class="not-due"><summary>Not due today · ${resting.length}</summary><p class="caption">You can still log these habits whenever you choose.</p>${habitTable(resting, run.start_date, dur, today, 1)}</details>` : ''}
    <div class="charts-2">
    <div class="card pts-card"><div class="chart-title">Points per day</div><div class="caption">Whole run · hover a bar for values</div>
    ${barChart(perDay.concat(Array(Math.max(0, dur - elapsed)).fill(0)), elapsed - 1)}
    <div class="chart-key"><span>Earned impact points</span><span>${fmtDay(run.start_date)} – ${fmtDay(endDay)}</span></div></div>
    <div class="card pts-card"><div class="chart-title">Points burnup</div><div class="caption">Cumulative actual and predicted · hover the line for values</div>
    ${burnupSvg('burnSvg', perDay, elapsed, dur, 1)}
    <div class="chart-key"><span>━ Actual</span><span>┄ Predicted</span></div></div>
    </div>
    ${standaloneSection()}${runManagement()}`;
  body.innerHTML = html;
  wireBarTips(body); wireBurnupTips(body);
}
function emptyRunsView() {
  return `<div class="page-intro"><div class="eyebrow">Runs</div><h1 style="font-size:26px;font-weight:500;margin:0 0 4px">No run running</h1>
    <div class="sub">Build a fixed-length run, or continue without one.</div></div>
    <div class="empty">Nothing here yet.<br><br><button class="btn primary" data-click="openBuilder">Build a run</button> <button class="btn" data-click="infiniteOn">Continue without a run</button></div>
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
  let html = `<div class="section-head"><h3>Habits</h3><span class="head-btns"><span class="caption">Infinite run · last 21 days</span><button class="btn primary" data-click="openBuilder">Build a run</button><button class="btn" data-click="infiniteOff">Exit</button></span></div>`;
  if (!list.length) {
    html += `<div class="empty">No standalone habits yet.<br>Add one below — it stays due with or without a run.</div>`;
  } else {
    const resting = restingHabits(today, list);
    const restingIds = new Set(resting.map((h) => h.uid));
    html += habitTable(list.filter((h) => !restingIds.has(h.uid)), start, 21, today, 1);
    if (resting.length) html += `<details class="not-due"><summary>Not due today · ${resting.length}</summary><p class="caption">You can still log these habits whenever you choose.</p>${habitTable(resting, start, 21, today, 1)}</details>`;
  }
  html += `<div class="charts-2">
    <div class="card pts-card"><div class="chart-title">Points per day</div><div class="caption">Last 21 days · hover a bar for values</div>
    ${barChart(perDay, 20)}
    <div class="chart-key"><span>Earned impact points</span><span>${fmtDay(start)} – ${fmtDay(today)}</span></div></div>
    <div class="card pts-card"><div class="chart-title">Points burnup</div><div class="caption">Last 21 days from a ${baseline}-point baseline · hover for values</div>
    ${infiniteBurnup(perDay, baseline)}
    <div class="chart-key"><span>━ Actual</span><span>┄ Pace</span></div></div>
    </div>
    <button class="add-habit" data-click="openStandalone">＋ New standalone habit</button><div id="standaloneForm"></div>`;
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
    <line x1="20" y1="116" x2="320" y2="116" style="stroke:var(--outline)"/><line x1="20" y1="14" x2="20" y2="116" style="stroke:var(--outline)"/>
    <path d="${actual}" fill="none" style="stroke:var(--primary)" stroke-width="3" stroke-linecap="round"/>
    <path d="${forecast}" fill="none" style="stroke:var(--tertiary)" stroke-width="3" stroke-dasharray="6 5" stroke-linecap="round"/>
    <text x="20" y="136" style="fill:var(--muted)" font-size="9">${fmtDay(addDays(todayLocal(), -20))}</text><text x="285" y="136" style="fill:var(--muted)" font-size="9">Today</text>${dots}</svg>`;
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

/* ---------- Run builder ---------- */
let builder = null;
function blankHabit() {
  return { uid: null, name: '', group: 'General', points: 1, interval: false, count: 3 };
}
function rowToEditor(h) {
  return { uid: h.uid, name: h.title, group: h.group_name, points: h.impact_points, interval: h.recurrence_type === 'interval', count: h.recurrence_value };
}
function openBuilder() {
  const run = latestRun() || allRuns()[0] || null;
  builder = {
    mode: run ? 'copy' : 'scratch',
    copyFrom: run ? run.uid : '',
    name: '', days: run?.duration_days || 21, custom: !!run && ![7, 14, 21].includes(run.duration_days), start: todayLocal(),
    includeStandalone: false, showGuide: false, yamlText: '', yamlErrors: [],
    habits: run ? habitsForRun(run.uid).map(rowToEditor) : [],
  };
  showView('builder');
}
function builderMode(m) {
  builder.mode = m;
  builder.yamlLoaded = false;
  if (m === 'scratch' || m === 'yaml') builder.habits = [];
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
  const edCtx = ctx === 'builder.habits' ? 'builder' : 'edit';
  return `<div class="habit-editor"><div class="editor-head">
    <input class="field" value="${esc(h.name)}" aria-label="Habit name" placeholder="Habit name" data-input="habit" data-ctx="${edCtx}" data-i="${i}" data-field="name">
    <input class="field editor-impact" value="${esc(h.group)}" aria-label="Group" data-input="habit" data-ctx="${edCtx}" data-i="${i}" data-field="group">
    <button class="remove-habit" aria-label="Remove" data-click="removeHabit" data-a="${edCtx}" data-b="${i}">×</button></div>
    <label class="field-label">Impact points per completion</label>
    <div class="editor-head"><input class="field editor-impact" type="number" min="0" value="${h.points}" data-input="habit" data-ctx="${edCtx}" data-i="${i}" data-field="points"><span class="caption">points</span></div>
    <div class="segmented"><button class="${h.interval ? '' : 'selected'}" data-click="habitInterval" data-a="${edCtx}" data-b="${i}" data-c="weekly">Days per week</button>
    <button class="${h.interval ? 'selected' : ''}" data-click="habitInterval" data-a="${edCtx}" data-b="${i}" data-c="interval">Every N days</button></div>
    ${h.interval
      ? `<div><label class="field-label">Repeat interval</label><div class="interval-row">Every <input class="field" type="number" min="1" value="${h.count}" data-input="habit" data-ctx="${edCtx}" data-i="${i}" data-field="count"> days</div>
        <div class="helper">If missed, it stays due until completed. After a Done it hides for N-1 days.</div></div>`
      : `<div><label class="field-label">Days per week</label><div class="interval-row"><input class="field" type="number" min="1" max="7" value="${h.count}" data-input="habit" data-ctx="${edCtx}" data-i="${i}" data-field="count"> days per week</div>
        <div class="helper">Set a weekly target; complete it on any days. Due every day until done.</div></div>`}</div>`;
}
function renderBuilder() {
  const body = $('builderBody');
  const b = builder;
  const runs = allRuns();
  let html = `<div class="eyebrow">New run</div>
    <h1 style="font-size:26px;font-weight:500;margin:4px 0">Build a run</h1>
    <div class="sub">Copy the last run, start from scratch, or paste YAML from any AI chatbot.</div>
    <div id="builderRunError" class="form-errors" role="alert"></div>
    ${rows('run').some((r) => r.status === 'active') ? '<p class="helper">Stop all active runs before creating another.</p>' : ''}
    <div class="builder-source">
    <button class="source-btn${b.mode === 'copy' ? ' active' : ''}" data-click="builderMode" data-a="copy"><b>Copy previous</b>Reuse habits and edit them</button>
    <button class="source-btn${b.mode === 'scratch' ? ' active' : ''}" data-click="builderMode" data-a="scratch"><b>Start from scratch</b>Choose habits yourself</button>
    <button class="source-btn${b.mode === 'yaml' ? ' active' : ''}" data-click="builderMode" data-a="yaml"><b>From YAML</b>Configure using external AI</button></div>`;
  if (b.mode === 'yaml') {
    html += `<div class="card" style="max-width:760px"><div class="chart-title">Paste a run file</div>
      <div class="helper">Copy the guide prompt into any chatbot, describe the habits you want, paste the YAML answer here, then load it into the editor below.</div>
      <div class="yaml-actions"><button class="btn" data-click="copyGuidePrompt">Copy guide prompt</button>
      <button class="btn" data-click="toggleGuide">${b.showGuide ? 'Hide guide' : 'Show guide'}</button></div>
      ${b.showGuide ? `<pre class="prompt">${esc(YAML_GUIDE_PROMPT)}</pre>` : ''}
      <label class="field-label" for="yamlText">Run YAML</label>
      <textarea class="field code" id="yamlText" placeholder="name: Reset week&#10;days: 14&#10;habits:&#10;  - name: Morning light&#10;    every: 1 day" data-input="builder" data-field="yamlText">${esc(b.yamlText)}</textarea>
      <div class="form-errors${b.yamlErrors.length ? ' show' : ''}" id="yamlErrors">${b.yamlErrors.map(esc).join('<br>')}</div>
      <div class="yaml-actions"><button class="btn primary" data-click="loadYamlIntoEditor">Load into editor</button></div></div>`;
  }
  if (b.mode === 'copy') {
    html += `<div class="card form-narrow"><label class="field-label" for="previousRun" style="margin-top:0">Copy from</label>
      <select class="field" id="previousRun" data-change="copyFrom">
      ${runs.map((r) => `<option value="${esc(safeId(r.uid))}"${r.uid === b.copyFrom ? ' selected' : ''}>${esc(r.name)} · ${r.duration_days} days</option>`).join('')}</select>
      <label class="checkline"><input type="checkbox"${b.includeStandalone ? ' checked' : ''} data-change="includeStandalone"> Also include standalone habits</label>
      <div class="helper">Copied habits are shared with the original run. Changes to them update every run that uses them. Past check-ins on overlapping dates count toward the new run.</div></div>`;
  }
  html += `<div class="form-row form-narrow"><div><label class="field-label" for="runName">Run name</label>
    <input class="field" id="runName" placeholder="Name this run" value="${esc(b.name)}" data-input="builder" data-field="name"></div>
    <div><label class="field-label" for="runStart">Start date</label>
    <input class="field" id="runStart" type="date" value="${esc(b.start)}" data-input="builder" data-field="start"></div></div>
    <div class="form-narrow"><label class="field-label">Run length</label><div class="duration-row">
    ${[7, 14, 21].map((d) => `<button class="duration-chip${!b.custom && b.days === d ? ' selected' : ''}" data-click="builderDays" data-a="${d}">${d} days</button>`).join('')}
    <button class="duration-chip${b.custom ? ' selected' : ''}" data-click="builderCustom">Custom</button></div>
    ${b.custom ? `<div class="interval-row" style="margin-top:8px"><input class="field" id="customDays" type="number" min="1" max="365" value="${b.days}" data-input="builder" data-field="days"> days</div>` : ''}</div>
    <div class="section-head"><h3>Habits in this run</h3><span class="caption">${b.habits.length} habit${b.habits.length === 1 ? '' : 's'}</span></div>
    <div id="builderHabits">`;
  if (!b.habits.length) html += `<div class="empty-editor" style="border:1px dashed var(--outline);color:var(--muted);border-radius:16px;text-align:center;padding:20px 12px;font-size:12px;max-width:760px">No habits added yet. Add the first habit below.</div>`;
  b.habits.forEach((h, i) => { html += habitEditorHTML(h, i, 'builder.habits'); });
  html += `</div><button class="add-habit" data-click="addHabit" data-a="builder">＋ Add a habit</button><br>
    <button class="build-button" data-click="createRun">Create run</button> <button class="btn" data-click="showView" data-a="runs">Cancel</button>`;
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
function loadYamlIntoEditor() { applyYaml($('yamlText').value); }
/* Parses pasted YAML into the builder. Returns true when it loaded; otherwise shows the errors in the YAML card. */
function applyYaml(text) {
  const parsed = parseRunYaml(text);
  if (!parsed.ok) {
    builder.yamlErrors = parsed.errors;
    builder.yamlText = text;
    renderBuilder();
    const box = $('yamlErrors');
    if (box && box.scrollIntoView) box.scrollIntoView({ block: 'center' });
    return false;
  }
  builder.yamlLoaded = true;
  builder.yamlErrors = [];
  builder.yamlText = text;
  if (parsed.run.name) builder.name = parsed.run.name;
  builder.days = parsed.run.days;
  builder.custom = ![7, 14, 21].includes(parsed.run.days);
  if (parsed.run.start) builder.start = parsed.run.start;
  builder.habits = parsed.habits.map((h) => ({ uid: null, name: h.name, group: h.group, points: h.points, interval: h.interval > 0, count: h.interval > 0 ? h.interval : h.weekly }));
  toast(`Loaded ${builder.habits.length} habits — review and create the run.`);
  renderBuilder();
  return true;
}
function createRun() {
  const b = builder;
  if (rows('run').some((r) => r.status === 'active')) { showRunError('Stop the active run before creating another. Use Manage runs on the Runs page.'); return; }
  // Pasted YAML that was never loaded would otherwise be ignored (create used whatever habits were in the editor).
  if (b.mode === 'yaml' && !b.yamlLoaded) {
    const box = $('yamlText');
    const text = box ? box.value : b.yamlText;
    if (!String(text || '').trim()) { toast('Paste your YAML first.'); return; }
    if (!applyYaml(text)) { toast('The YAML has problems — see the red text above.'); return; }
  }
  const days = Math.min(Math.max(b.days || 21, 1), 365);
  const start = /^\d{4}-\d{2}-\d{2}$/.test(b.start || '') ? b.start : todayLocal();
  const named = b.habits.filter((h) => (h.name || '').trim());
  if (!named.length) { toast('Add at least one habit.'); return; }
  const duplicate = duplicateHabitName(named);
  if (duplicate) { showRunError(`“${duplicate}” appears more than once. Give each habit a different name.`); return; }
  const runId = `run-${start}-${randHex(4)}`;
  putRow('run', runId, { uid: runId, name: (b.name || '').trim() || `${days}-day run`, start_date: start, duration_days: days, status: 'active' });
  selectedRunUid = runId;
  for (const h of named) {
    const uid = h.uid || `h-${slug(h.name)}-${randHex(2)}`;
    if (!h.uid) {
      putRow('habit', uid, {
        uid, title: h.name.trim(), group_name: (h.group || '').trim() || 'General', iteration: 1,
        impact_points: Math.max(0, h.points || 0),
        recurrence_type: h.interval ? 'interval' : 'weekly',
        recurrence_value: Math.max(1, h.count || 1), archived: 0,
      });
    } else {
      putRow('habit', uid, {
        title: h.name.trim(), group_name: (h.group || '').trim() || 'General',
        impact_points: Math.max(0, h.points || 0),
        recurrence_type: h.interval ? 'interval' : 'weekly',
        recurrence_value: Math.max(1, h.count || 1),
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
    <div id="editRunError" class="form-errors" role="alert"></div>
    <div class="form-row form-narrow"><div><label class="field-label" for="editName">Run name</label>
    <input class="field" id="editName" value="${esc(e.name)}" data-input="edit" data-field="name"></div>
    <div><label class="field-label" for="editStart">Start date</label>
    <input class="field" id="editStart" type="date" value="${esc(e.start)}" data-input="edit" data-field="start"></div></div>
    <div class="form-narrow"><label class="field-label">Run length</label><div class="duration-row">
    ${[7, 14, 21].map((d) => `<button class="duration-chip${!e.custom && e.days === d ? ' selected' : ''}" data-click="editDays" data-a="${d}">${d} days</button>`).join('')}
    <button class="duration-chip${e.custom ? ' selected' : ''}" data-click="editCustom">Custom</button></div>
    ${e.custom ? `<div class="interval-row" style="margin-top:8px"><input class="field" type="number" min="1" max="365" value="${e.days}" data-input="edit" data-field="days"> days</div>` : ''}</div>
    <div class="section-head"><h3>Habits in this run</h3><span class="caption">${e.habits.length} habit${e.habits.length === 1 ? '' : 's'}</span></div>
    <div class="helper" style="max-width:760px">Editing a habit here updates it everywhere it is used${others}.</div>`;
  e.habits.forEach((h, i) => { html += habitEditorHTML(h, i, 'editRun.habits'); });
  html += `<div class="form-row form-narrow"><div><label class="field-label" for="attachSel">Attach an existing habit</label>
    <select class="field" id="attachSel" data-change="attachUid">
    <option value="">Choose…</option>
    ${unattached.map((h) => `<option value="${esc(safeId(h.uid))}">${esc(h.title)} · ${esc(h.group_name)}</option>`).join('')}</select></div>
    <div style="align-self:end"><button class="btn" data-click="attachExisting">Attach</button></div></div>
    <button class="add-habit" data-click="addHabit" data-a="edit">＋ New habit in this run</button><br>
    <button class="build-button" data-click="saveEdit">Save changes</button> <button class="btn" data-click="downloadRunYaml">Download YAML</button> <button class="btn" data-click="showView" data-a="runs">Cancel</button>
    <div class="helper">YAML contains the run settings and habits, not check-ins or habit IDs. Importing it creates a new run.</div>`;
  body.innerHTML = html;
}
function downloadRunYaml() {
  const e = editRun;
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
  const duplicate = duplicateHabitName(e.habits);
  if (duplicate) { showRunError(`“${duplicate}” appears more than once. Give each habit a different name.`); return; }
  const days = Math.min(Math.max(e.days || 21, 1), 365);
  const start = /^\d{4}-\d{2}-\d{2}$/.test(e.start || '') ? e.start : todayLocal();
  putRow('run', e.uid, { uid: e.uid, name: (e.name || '').trim() || 'Untitled run', start_date: start, duration_days: days });
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
    <button class="build-button" data-click="createStandalone">Add habit</button></div>`;
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

/* ---------- UI events ----------
 * Delegated from the document so the page contains no inline scripts and the
 * CSP can forbid them. Markup carries data-click / data-input / data-change.
 */
const editorList = (ctx) => (ctx === 'builder' ? builder.habits : editRun.habits);
const editorRender = (ctx) => (ctx === 'builder' ? renderBuilder() : renderEdit());
const ACTIONS = {
  toggleDay: (a, b) => toggleDay(a, b),
  openStandalone: () => openStandalone(),
  openBuilder: () => openBuilder(),
  openEdit: (a) => openEdit(a),
  viewRun: (a) => { selectedRunUid = a; showView('runs'); },
  stopRun: (a) => stopRun(a),
  deleteRun: (a) => deleteRun(a),
  infiniteOn: () => { setInfinite(true); renderRuns(); },
  infiniteOff: () => { setInfinite(false); renderRuns(); },
  recordQuick: (a) => recordQuick(a),
  toggleQuickComment: () => setQuickComment(!quickCommentOpen),
  addGoal: () => addGoal(),
  logTracker: (a) => logTracker(a),
  removeHabit: (ctx, i) => { editorList(ctx).splice(Number(i), 1); editorRender(ctx); },
  habitInterval: (ctx, i, mode) => { editorList(ctx)[Number(i)].interval = mode === 'interval'; editorRender(ctx); },
  addHabit: (ctx) => { editorList(ctx).push(blankHabit()); editorRender(ctx); },
  builderMode: (a) => builderMode(a),
  copyGuidePrompt: () => copyGuidePrompt(),
  toggleGuide: () => { builder.showGuide = !builder.showGuide; renderBuilder(); },
  loadYamlIntoEditor: () => loadYamlIntoEditor(),
  builderDays: (a) => builderDays(Number(a)),
  builderCustom: () => builderCustom(),
  createRun: () => createRun(),
  showView: (a) => showView(a),
  editDays: (a) => { editRun.days = Number(a); editRun.custom = false; renderEdit(); },
  editCustom: () => { editRun.custom = true; renderEdit(); },
  attachExisting: () => attachExisting(),
  downloadRunYaml: () => downloadRunYaml(),
  saveEdit: () => saveEdit(),
  createStandalone: () => createStandalone(),
};
const HABIT_FIELDS = { name: (v) => v, group: (v) => v, points: (v) => Number(v) || 0, count: (v) => Number(v) || 1 };
const RUN_FIELDS = { name: (v) => v, start: (v) => v, days: (v) => Number(v) || 21, yamlText: (v) => v };
function setRunField(target, field, value) {
  if (Object.hasOwn(RUN_FIELDS, field)) target[field] = RUN_FIELDS[field](value);
}
document.addEventListener('click', (e) => {
  const el = e.target instanceof Element ? e.target.closest('[data-click]') : null;
  if (!el) return;
  const act = Object.hasOwn(ACTIONS, el.dataset.click) ? ACTIONS[el.dataset.click] : null;
  if (act) act(el.dataset.a, el.dataset.b, el.dataset.c);
});
document.addEventListener('input', (e) => {
  const el = e.target instanceof Element ? e.target.closest('[data-input]') : null;
  if (!el) return;
  const d = el.dataset;
  if (d.input === 'habit' && Object.hasOwn(HABIT_FIELDS, d.field)) {
    const h = editorList(d.ctx)[Number(d.i)];
    if (h) h[d.field] = HABIT_FIELDS[d.field](el.value);
  } else if (d.input === 'builder') {
    setRunField(builder, d.field, el.value);
    if (d.field === 'yamlText') builder.yamlLoaded = false;
  } else if (d.input === 'edit') {
    setRunField(editRun, d.field, el.value);
  }
});
document.addEventListener('change', (e) => {
  const el = e.target instanceof Element ? e.target.closest('[data-change]') : null;
  if (!el) return;
  if (el.dataset.change === 'attachUid') editRun.attachUid = el.value;
  else if (el.dataset.change === 'activeRun') { selectedRunUid = el.value; renderRuns(); }
  else if (el.dataset.change === 'copyFrom') builderCopyFrom(el.value);
  else if (el.dataset.change === 'includeStandalone') { builder.includeStandalone = el.checked; builderCopyFrom(builder.copyFrom); }
});

document.addEventListener('DOMContentLoaded', boot);
