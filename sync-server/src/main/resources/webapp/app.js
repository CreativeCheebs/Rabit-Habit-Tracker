'use strict';
/* Habit Lab web client: thin sync mirror + screens from the product mockups. */
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ROMAN = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII'];
const roman = (n) => ROMAN[n - 1] || String(n);
const GROUP_ICON = { wellbeing: '☀', focus: '◉', health: '♥', fitness: '⛹', learning: '✎', sleep: '☾' };
const groupIcon = (g) => GROUP_ICON[String(g || '').toLowerCase()] || '●';

function todayLocal(d) {
  const t = d || new Date();
  const y = t.getFullYear(), m = String(t.getMonth() + 1).padStart(2, '0'), day = String(t.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
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
  const el = $('snack');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(window.snackTimer);
  window.snackTimer = setTimeout(() => el.classList.remove('show'), 2000);
}

/* ---------- local mirror ---------- */
const LS = { account: 'habitlab.account', key: 'habitlab.key', device: 'habitlab.device', token: 'habitlab.token', clock: 'habitlab.clock', db: 'habitlab.db' };
const TABLES = ['habit', 'run', 'goal', 'tracker', 'run_habit', 'check_in', 'tracker_entry'];
let mirror = {};
let dirty = new Set();
function loadMirror() {
  mirror = {};
  try { mirror = JSON.parse(localStorage.getItem(LS.db) || '{}'); } catch { mirror = {}; }
  for (const t of TABLES) if (!mirror[t]) mirror[t] = {};
  try { dirty = new Set(JSON.parse(localStorage.getItem('habitlab.dirty') || '[]')); } catch { dirty = new Set(); }
}
function saveMirror() {
  localStorage.setItem(LS.db, JSON.stringify(mirror));
  localStorage.setItem(LS.dirty, JSON.stringify([...dirty]));
}
function rows(t) { return Object.values(mirror[t] || {}).map((r) => r.v); }
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
/** Write fields of one row: bump stamps only for the fields given. */
function putRow(t, u, values) {
  const cur = row(t, u) || { v: {}, s: {} };
  const c = nextClock(), d = deviceId();
  const v = { ...cur.v, ...values };
  const s = { ...cur.s };
  for (const k of Object.keys(values)) s[k] = { c, d };
  if (!mirror[t]) mirror[t] = {};
  mirror[t][u] = { v, s };
  dirty.add(t + '|' + u);
  saveMirror();
  scheduleSync();
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
  dot.className = 'sync-dot' + (state ? ' ' + state : '');
  $('syncLabel').textContent = label;
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
    const changes = [...dirty].map((k) => {
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
    dirty.clear();
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
function habitsForRun(runUid) {
  const ids = new Set(rows('run_habit').filter((r) => r.run_uid === runUid).map((r) => r.habit_uid));
  return rows('habit').filter((h) => ids.has(h.uid) && !h.archived)
    .sort((a, b) => (a.group_name + a.title < b.group_name + b.title ? -1 : 1));
}
function checkInsByDay(day) {
  const out = {};
  for (const c of rows('check_in')) if (c.day === day) out[c.habit_uid] = c;
  return out;
}
function recurrenceLabel(h) {
  return h.recurrence_type === 'interval' ? `Every ${h.recurrence_value} days` : `${h.recurrence_value} days per week`;
}
/** Due rules mirror the Android Quick Log exactly. */
function dueHabits(today) {
  const run = latestRun();
  if (!run) return [];
  const doneToday = new Set(Object.values(checkInsByDay(today)).filter((c) => c.state === 'done').map((c) => c.habit_uid));
  return habitsForRun(run.uid).filter((h) => {
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

/* ---------- navigation ---------- */
let currentScreen = 'runs';
function showScreen(id) {
  currentScreen = id;
  document.querySelectorAll('.screen').forEach((s) => s.classList.toggle('active', s.id === id));
  const nav = $('mainNav');
  nav.style.display = (id === 'login' || id === 'builder') ? 'none' : 'flex';
  document.querySelectorAll('#mainNav [data-screen]').forEach((b) => b.classList.toggle('active', b.dataset.screen === id));
  renderCurrent();
}
function renderCurrent() {
  if (currentScreen === 'runs') renderRuns();
  else if (currentScreen === 'analytics') renderAnalytics();
  else if (currentScreen === 'quicklog') renderQuick();
  else if (currentScreen === 'goals') renderGoals();
  else if (currentScreen === 'trackers') renderTrackers();
}
document.querySelectorAll('#mainNav [data-screen]').forEach((b) => b.addEventListener('click', () => showScreen(b.dataset.screen)));

/* ---------- login ---------- */
function signOut() {
  for (const k of Object.values(LS)) localStorage.removeItem(k);
  localStorage.removeItem('habitlab.dirty');
  mirror = {}; dirty = new Set();
  showScreen('login');
}
async function boot() {
  loadMirror();
  $('loginForm').addEventListener('submit', onLogin);
  $('signOutBtn').addEventListener('click', signOut);
  $('newRunBtn').addEventListener('click', () => openBuilder());
  $('builderBack').addEventListener('click', () => showScreen('runs'));
  if (localStorage.getItem(LS.key)) {
    showScreen('runs');
    setSync('', 'Connecting…');
    sync();
  } else {
    showScreen('login');
  }
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
    showScreen('runs');
    sync();
  } catch (ex) {
    err.textContent = ex.message === 'auth' ? 'Invalid username or password.' : 'Cannot reach the sync server. Check your connection.';
    err.classList.add('show');
  } finally {
    $('loginBtn').disabled = false;
  }
}

/* ---------- Runs ---------- */
let runsWeek = 0;
function earnedOn(run, day) {
  const habits = habitsForRun(run.uid);
  const pts = {};
  for (const h of habits) pts[h.uid] = h.impact_points;
  let total = 0;
  for (const c of Object.values(checkInsByDay(day))) {
    if (c.state === 'done' && pts[c.habit_uid]) total += pts[c.habit_uid];
  }
  return total;
}
function renderRuns() {
  const body = $('runsBody');
  const run = latestRun();
  const today = todayLocal();
  if (!run) {
    body.innerHTML = `<div class="page-intro"><div class="eyebrow">Runs</div><h1>No run yet</h1><div class="sub">Build your first run to start logging habits.</div></div>
      <div class="empty">Nothing synced yet.<br>Create a run to get going.</div>
      <button class="build-button" onclick="openBuilder()">Build a run</button>`;
    return;
  }
  const dur = run.duration_days || 21;
  const elapsed = Math.min(Math.max(runDayIndex(run, today) + 1, 1), dur);
  const endDay = addDays(run.start_date, dur - 1);
  const maxWeek = Math.ceil(dur / 7) - 1;
  runsWeek = Math.min(Math.max(runsWeek, 0), maxWeek);
  const from = runsWeek * 7, to = Math.min(dur, from + 7);
  const habits = habitsForRun(run.uid);
  let earned = 0;
  for (let i = 0; i < elapsed; i++) earned += earnedOn(run, addDays(run.start_date, i));
  const possible = habits.reduce((s, h) => s + h.impact_points, 0) * elapsed;
  const pct = possible ? Math.round((earned / possible) * 100) : 0;
  const groups = {};
  for (const h of habits) (groups[h.group_name] = groups[h.group_name] || []).push(h);
  let html = `<div class="card active-card"><div class="eyebrow">Active run · day ${elapsed} of ${dur}</div>
    <div class="run-title">${esc(run.name)}</div>
    <div class="run-dates">${fmtDay(run.start_date)} — ${fmtDay(endDay)} · ${habits.length} habits</div>
    <div class="progress-top"><b>${pct}%</b><span>IMPACT POINTS EARNED</span></div>
    <div class="track"><i style="width:${pct}%"></i></div>
    <div class="caption" style="margin-top:6px;color:#c4d2c7">${earned} of ${possible} planned impact points</div></div>
    <div class="section-head"><h3>Run habits</h3><span class="pill">${dur} days</span></div>
    <div class="week-controls"><button aria-label="Previous week" onclick="runsWeek--;renderRuns()">‹</button>
    <span class="week-label">WEEK ${runsWeek + 1} · DAYS ${from + 1}–${to}</span>
    <button aria-label="Next week" onclick="runsWeek++;renderRuns()">›</button></div>
    <div class="legend"><span><i></i>Logged</span><span>Tap a day to toggle</span></div>`;
  for (const [g, list] of Object.entries(groups)) {
    html += `<div class="group"><div class="group-title">${esc(g.toUpperCase())} · ${list.length} HABIT${list.length === 1 ? '' : 'S'}</div>`;
    for (const h of list) {
      html += `<div class="habit"><div class="symbol">${groupIcon(h.group_name)}</div>
        <div class="habit-main"><div class="habit-name">${esc(h.title)} <span class="iteration">${roman(h.iteration || 1)}</span></div>
        <div class="habit-meta">${h.impact_points} impact pts · ${esc(recurrenceLabel(h).toLowerCase())}</div></div>
        <div class="days">`;
      for (let d = from; d < to; d++) {
        const day = addDays(run.start_date, d);
        const c = checkInsByDay(day)[h.uid];
        const future = d >= elapsed && day > today;
        const cls = c ? c.state : '';
        html += future
          ? `<span class="day future"></span>`
          : `<button class="day ${cls}" aria-label="${esc(h.title)} day ${d + 1}" onclick="toggleDay('${h.uid}','${day}')"></button>`;
      }
      html += `</div><div class="point">${h.impact_points}</div></div>`;
    }
    html += `</div>`;
  }
  html += `<div class="section-head"><h3>Impact points by day</h3><span class="caption">This run</span></div>
    <div class="card chart-card" style="margin-top:8px"><div class="chart">`;
  const weekDays = [];
  for (let d = from; d < to; d++) weekDays.push(addDays(run.start_date, d));
  const maxE = Math.max(1, ...weekDays.map((d) => earnedOn(run, d)));
  for (const d of weekDays) {
    const e = earnedOn(run, d);
    html += `<div class="bar"><i style="height:${Math.max(3, Math.round((e / maxE) * 100))}%"></i><label>${new Date(d + 'T12:00:00').getDate()}</label></div>`;
  }
  html += `</div><div class="chart-key"><span>Earned impact points</span><span>${fmtDay(weekDays[0])}–${fmtDay(weekDays[weekDays.length - 1])}</span></div></div>`;
  // Burnup: cumulative actual + linear forecast.
  const cum = [];
  let acc = 0;
  for (let i = 0; i < elapsed; i++) { acc += earnedOn(run, addDays(run.start_date, i)); cum.push(acc); }
  const pace = elapsed ? acc / elapsed : 0;
  const W = 330, H = 148, base = 116;
  const yMax = Math.max(acc, pace * dur, 1);
  const pt = (i, v) => `${i ? 'L' : 'M'} ${(20 + (i * 300) / Math.max(dur - 1, 1)).toFixed(1)} ${(base - (v / yMax) * 96).toFixed(1)}`;
  const actual = cum.map((v, i) => pt(i, v)).join(' ');
  let forecast = '';
  for (let i = elapsed - 1; i < dur; i++) forecast += `${forecast ? 'L' : 'M'} ${(20 + (i * 300) / Math.max(dur - 1, 1)).toFixed(1)} ${(base - ((acc + pace * (i - elapsed + 1)) / yMax) * 96).toFixed(1)} `;
  html += `<div class="section-head"><h3>Impact points burnup</h3><span class="caption">Actual + forecast</span></div>
    <div class="card chart-card"><div class="caption">Cumulative impact points · actual and predicted</div>
    <svg class="auto-svg" viewBox="0 0 ${W} ${H}" role="img" aria-label="Cumulative impact points" style="width:100%;height:auto;margin-top:8px">
    <line x1="20" y1="${base}" x2="320" y2="${base}" stroke="#465349"/><line x1="20" y1="14" x2="20" y2="${base}" stroke="#465349"/>
    <path d="${actual}" fill="none" stroke="var(--primary)" stroke-width="3" stroke-linecap="round"/>
    <path d="${forecast}" fill="none" stroke="#dfbd82" stroke-width="3" stroke-dasharray="6 5" stroke-linecap="round"/>
    <text x="20" y="136" fill="#a3afa6" font-size="9">Day 1</text><text x="285" y="136" fill="#a3afa6" font-size="9">Day ${dur}</text></svg>
    <div class="chart-key"><span>━ Actual</span><span>┄ Predicted</span></div></div>`;
  body.innerHTML = html;
}
function toggleDay(habitUid, day) {
  const cur = checkInsByDay(day)[habitUid];
  const state = cur && cur.state === 'done' ? 'skipped' : 'done';
  const values = { uid: habitUid + ':' + day, habit_uid: habitUid, day, state };
  if (cur && cur.comment) values.comment = cur.comment;
  putRow('check_in', habitUid + ':' + day, values);
  renderRuns();
}

/* ---------- Quick Log ---------- */
let quickList = [], quickResults = [], quickCommentOpen = false, quickLogged = new Set(), quickSession = '';
function renderQuick() {
  const today = todayLocal();
  const run = latestRun();
  const session = today + '|' + (run ? run.uid : '');
  if (session !== quickSession) { quickSession = session; quickLogged = new Set(); quickResults = []; }
  quickList = dueHabits(today).filter((h) => !quickLogged.has(h.uid));
  const body = $('quickBody');
  if (!quickList.length) {
    const done = quickResults.filter((x) => x === 'done').length;
    const partial = quickResults.filter((x) => x === 'partial').length;
    const skipped = quickResults.filter((x) => x === 'skipped').length;
    $('quickCounter').textContent = 'Complete';
    body.innerHTML = `<div class="quick-summary"><div class="symbol" style="margin:auto">✓</div><h3>Quick Log complete</h3>
      <p>${done} done · ${partial} partial · ${skipped} skipped</p>
      <button class="build-button" onclick="showScreen('runs')">Back to runs</button></div>`;
    return;
  }
  const h = quickList[0];
  $('quickCounter').textContent = `${quickResults.length + 1} of ${quickResults.length + quickList.length}`;
  const next = quickList[1];
  body.innerHTML = `<div class="quick-stack">${next ? `<div class="quick-card quick-under"><div class="quick-under-name">${esc(next.title)}</div></div>` : ''}
    <article class="quick-card" id="swipeCard"><div class="swipe-stamp done" id="stampDone">DONE</div>
    <div class="swipe-stamp skip" id="stampSkip">SKIP</div>
    <div class="quick-art"><span class="quick-art-label">${esc((h.group_name || '').toUpperCase())} · DUE</span>
    <div class="quick-art-icon">${groupIcon(h.group_name)}</div></div>
    <div class="quick-habit-name">${esc(h.title)} <span class="iteration">${roman(h.iteration || 1)}</span></div>
    <div class="quick-meta">${esc(recurrenceLabel(h))} · due today</div>
    <div class="quick-impact">${h.impact_points} impact points</div>
    <div class="comment-box" id="quickComment" style="display:${quickCommentOpen ? 'block' : 'none'}">
    <textarea class="field" id="quickNoteText" placeholder="Add a note about this habit today"></textarea></div>
    </article></div>
    <div class="quick-actions"><button class="quick-action partial" onclick="recordQuick('partial')"><span>◐</span> Partial</button>
    <button class="comment-btn" id="quickCommentBtn" onclick="quickCommentOpen=!quickCommentOpen;renderQuick()"><span>✎</span> Comment</button></div>`;
  const card = $('swipeCard');
  card.addEventListener('pointerdown', startQuickDrag);
  card.addEventListener('pointermove', moveQuickDrag);
  card.addEventListener('pointerup', endQuickDrag);
  card.addEventListener('pointercancel', endQuickDrag);
}
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
function logCheckIn(habitUid, day, state, note) {
  const values = { uid: habitUid + ':' + day, habit_uid: habitUid, day, state };
  if (note) values.comment = note;
  putRow('check_in', habitUid + ':' + day, values);
}

/* ---------- Analytics ---------- */
function renderAnalytics() {
  const body = $('analyticsBody');
  const run = latestRun();
  const today = todayLocal();
  if (!run) {
    body.innerHTML = `<div class="page-intro"><div class="eyebrow">Analytics</div><h1>Habit group insights</h1>
      <div class="sub">No run data yet.</div></div><div class="empty">Log a run first — charts appear here.</div>`;
    return;
  }
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
  let html = `<div class="page-intro"><div class="eyebrow">This run</div><h1>Habit group insights</h1>
    <div class="sub">${esc(run.name)} · day ${elapsed} of ${dur}.</div></div>
    <div class="card chart-card"><div class="row"><div class="chart-title">Impact completion by group</div><span class="pill">This run</span></div>
    <div class="group-bars">`;
  const maxP = Math.max(1, ...stats.map((s) => s.pct));
  for (const s of stats) {
    html += `<div class="bar"><i style="height:${Math.max(4, Math.round((s.pct / maxP) * 100))}%"></i><label>${esc(s.g)} · ${s.pct}%</label></div>`;
  }
  html += `</div><div class="chart-key"><span>Earned vs possible so far</span><span>${fmtDay(run.start_date)}–${fmtDay(addDays(run.start_date, dur - 1))}</span></div></div>
    <div class="group"><div class="group-title">IMPACT POINTS EARNED · THIS RUN</div>`;
  for (const s of stats) {
    html += `<div class="row" style="padding:8px 0"><span>${esc(s.g)}</span><b>${s.pct}% · ${s.earned} / ${s.possible}</b></div>`;
  }
  html += `</div><div class="card chart-card"><div class="row"><div class="chart-title">Daily impact</div><span class="pill">${dur} days</span></div><div class="chart">`;
  const maxE = Math.max(1, ...Array.from({ length: elapsed }, (_, i) => earnedOn(run, addDays(run.start_date, i))));
  for (let i = 0; i < elapsed; i++) {
    const d = addDays(run.start_date, i), e = earnedOn(run, d);
    html += `<div class="bar"><i style="height:${Math.max(3, Math.round((e / maxE) * 100))}%"></i><label>${new Date(d + 'T12:00:00').getDate()}</label></div>`;
  }
  html += `</div><div class="chart-key"><span>Earned impact points</span><span>Day 1–${elapsed}</span></div></div>`;
  body.innerHTML = html;
}

/* ---------- Goals ---------- */
function renderGoals() {
  const body = $('goalsBody');
  const goals = rows('goal').sort((a, b) => (a.start_date < b.start_date ? 1 : -1));
  let html = `<div class="page-intro"><div class="eyebrow">Personal goals</div><h1>Your goals</h1>
    <div class="sub">Progress and history from each goal's start date.</div></div><div id="goalList">`;
  if (!goals.length) html += `<div class="empty">No goals yet.<br>Add your first goal below.</div>`;
  for (const g of goals) {
    const pct = Math.round((g.progress || 0) * 100);
    html += `<div class="goal"><div class="row"><div class="goal-title">${esc(g.description)}</div><div class="percent">${pct}%</div></div>
      <div class="goal-sub">Started ${fmtDay(g.start_date)}${g.target_date ? ' · target ' + fmtDay(g.target_date) : ''}</div>
      <div class="track"><i style="width:${pct}%"></i></div></div>`;
  }
  html += `</div><div class="habit-editor"><label class="field-label" for="goalDesc">New goal</label>
    <input class="field" id="goalDesc" placeholder="e.g. Read 20 pages daily">
    <label class="field-label" for="goalTarget">Target date (optional)</label>
    <input class="field" id="goalTarget" type="date">
    <button class="build-button" onclick="addGoal()">Add goal</button></div>`;
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

/* ---------- Trackers ---------- */
function renderTrackers() {
  const body = $('trackersBody');
  const trackers = rows('tracker').sort((a, b) => (a.name < b.name ? -1 : 1));
  let html = `<div class="page-intro"><div class="eyebrow">Personal measurements</div><h1>Trackers</h1>
    <div class="sub">Quick entries and recent trends.</div></div><div id="trackerList">`;
  if (!trackers.length) html += `<div class="empty">No trackers yet.<br>Add your first tracker below.</div>`;
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
      <div class="log-row"><input class="field" id="tv-${t.uid}" type="number" step="any" placeholder="New value (${esc(t.unit)})" aria-label="New value for ${esc(t.name)}">
      <button class="build-button" onclick="logTracker('${t.uid}')">Log</button></div></div>`;
  }
  html += `</div><div class="habit-editor"><label class="field-label" for="trackerName">New tracker</label>
    <div class="editor-head"><input class="field" id="trackerName" placeholder="e.g. Sleep quality">
    <input class="field editor-impact" id="trackerUnit" placeholder="unit" aria-label="Unit" value="/ 10"></div>
    <div class="segmented" id="trackerCadence"><button class="selected" data-cad="Daily">Daily</button><button data-cad="Weekly">Weekly</button></div>
    <button class="build-button" onclick="addTracker()">Add tracker</button></div>`;
  body.innerHTML = html;
  body.querySelectorAll('#trackerCadence button').forEach((b) => b.addEventListener('click', () => {
    body.querySelectorAll('#trackerCadence button').forEach((x) => x.classList.toggle('selected', x === b));
  }));
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
function addTracker() {
  const name = $('trackerName').value.trim();
  if (!name) { toast('Name the tracker first.'); return; }
  const unit = $('trackerUnit').value.trim() || 'units';
  const cad = ($('trackersBody').querySelector('#trackerCadence .selected') || {}).dataset?.cad || 'Daily';
  const uid = 't-' + randHex(4);
  putRow('tracker', uid, { uid, name, unit, cadence: cad });
  toast('Tracker added.');
  renderTrackers();
}

/* ---------- Run builder ---------- */
let builder = null;
function openBuilder() {
  const run = latestRun();
  builder = {
    mode: run ? 'copy' : 'scratch',
    name: '', days: 21, custom: false,
    habits: run ? habitsForRun(run.uid).map((h) => ({ uid: h.uid, name: h.title, group: h.group_name, points: h.impact_points, interval: h.recurrence_type === 'interval', count: h.recurrence_value })) : [],
  };
  renderBuilder();
  currentScreen = 'builder';
  document.querySelectorAll('.screen').forEach((s) => s.classList.toggle('active', s.id === 'builder'));
  $('mainNav').style.display = 'none';
}
function renderBuilder() {
  const body = $('builderBody');
  const b = builder;
  const runs = rows('run').sort((a, b2) => (a.start_date < b2.start_date ? 1 : -1));
  let html = `<div class="builder-source">
    <button class="source-btn${b.mode === 'copy' ? ' active' : ''}" onclick="builderMode('copy')"><b>Copy previous</b>Reuse habits and edit them</button>
    <button class="source-btn${b.mode === 'scratch' ? ' active' : ''}" onclick="builderMode('scratch')"><b>Start from scratch</b>Choose habits yourself</button></div>`;
  if (b.mode === 'copy') {
    html += `<div class="card"><label class="field-label" for="previousRun">Copy from</label>
      <select class="field" id="previousRun" onchange="builderCopyFrom(this.value)">
      ${runs.map((r) => `<option value="${r.uid}">${esc(r.name)} · ${r.duration_days} days</option>`).join('')}</select>
      <div class="helper">Copied habits stay editable. Changes here only affect this new run.</div></div>`;
  }
  html += `<div class="run-name-group"><label class="field-label" for="runName">Run name</label>
    <input class="field" id="runName" placeholder="Name this run" value="${esc(b.name)}" oninput="builder.name=this.value"></div>
    <div class="duration-group"><label class="field-label">Run length</label><div class="duration-row">
    ${[7, 14, 21].map((d) => `<button class="duration-chip${!b.custom && b.days === d ? ' selected' : ''}" onclick="builderDays(${d})">${d} days</button>`).join('')}
    <button class="duration-chip${b.custom ? ' selected' : ''}" onclick="builderCustom()">Custom</button></div>
    ${b.custom ? `<div class="interval-row" style="margin-top:8px"><input class="field" id="customDays" type="number" min="1" max="365" value="${b.days}" oninput="builder.days=Number(this.value)||21"> days</div>` : ''}</div>
    <div class="section-head"><h3>Habits in this run</h3><span class="caption">${b.habits.length} habit${b.habits.length === 1 ? '' : 's'}</span></div>
    <div id="builderHabits">`;
  if (!b.habits.length) html += `<div class="empty-editor">No habits added yet. Add the first habit below.</div>`;
  b.habits.forEach((h, i) => {
    html += `<div class="habit-editor"><div class="editor-head">
      <input class="field" value="${esc(h.name)}" aria-label="Habit name" oninput="builder.habits[${i}].name=this.value">
      <input class="field editor-impact" value="${esc(h.group)}" aria-label="Group" oninput="builder.habits[${i}].group=this.value">
      <button class="remove-habit" onclick="builder.habits.splice(${i},1);renderBuilder()">×</button></div>
      <label class="field-label">Impact points per completion</label>
      <div class="editor-head"><input class="field editor-impact" type="number" min="0" value="${h.points}" oninput="builder.habits[${i}].points=Number(this.value)||0"><span class="caption">points</span></div>
      <div class="segmented"><button class="${h.interval ? '' : 'selected'}" onclick="builder.habits[${i}].interval=false;renderBuilder()">Days per week</button>
      <button class="${h.interval ? 'selected' : ''}" onclick="builder.habits[${i}].interval=true;renderBuilder()">Every N days</button></div>
      ${h.interval
        ? `<div><label class="field-label">Repeat interval</label><div class="interval-row">Every <input class="field" type="number" min="1" value="${h.count}" oninput="builder.habits[${i}].count=Number(this.value)||1"> days</div>
          <div class="helper">If missed, it stays due until completed.</div></div>`
        : `<div><label class="field-label">Days per week</label><div class="interval-row"><input class="field" type="number" min="1" max="7" value="${h.count}" oninput="builder.habits[${i}].count=Number(this.value)||1"> days per week</div>
          <div class="helper">Set a weekly target; complete it on any days.</div></div>`}</div>`;
  });
  html += `</div><button class="add-habit" onclick="builder.habits.push({uid:null,name:'',group:'General',points:1,interval:false,count:3});renderBuilder()">＋ Add a habit</button>
    <button class="build-button" onclick="createRun()">Create run</button>`;
  body.innerHTML = html;
}
function builderMode(m) {
  builder.mode = m;
  if (m === 'scratch') builder.habits = [];
  else {
    const run = latestRun();
    if (run) builder.habits = habitsForRun(run.uid).map((h) => ({ uid: h.uid, name: h.title, group: h.group_name, points: h.impact_points, interval: h.recurrence_type === 'interval', count: h.recurrence_value }));
  }
  renderBuilder();
}
function builderCopyFrom(runUid) {
  builder.habits = habitsForRun(runUid).map((h) => ({ uid: h.uid, name: h.title, group: h.group_name, points: h.impact_points, interval: h.recurrence_type === 'interval', count: h.recurrence_value }));
  renderBuilder();
}
function builderDays(d) { builder.days = d; builder.custom = false; renderBuilder(); }
function builderCustom() { builder.custom = true; renderBuilder(); }
function slug(s) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 24) || 'habit';
}
function createRun() {
  const b = builder;
  const days = Math.min(Math.max(b.days || 21, 1), 365);
  const today = todayLocal();
  const named = b.habits.filter((h) => h.name.trim());
  if (!named.length) { toast('Add at least one habit.'); return; }
  const runId = `run-${today}-${randHex(4)}`;
  putRow('run', runId, { uid: runId, name: b.name.trim() || `${days}-day run`, start_date: today, duration_days: days, status: 'active' });
  for (const h of named) {
    const uid = h.uid || `h-${slug(h.name)}-${randHex(2)}`;
    if (!h.uid) {
      putRow('habit', uid, {
        uid, title: h.name.trim(), group_name: h.group.trim() || 'General', iteration: 1,
        impact_points: Math.max(0, h.points || 0),
        recurrence_type: h.interval ? 'interval' : 'weekly',
        recurrence_value: Math.max(1, h.count || 1), archived: 0,
      });
    }
    putRow('run_habit', `${runId}:${uid}`, { uid: `${runId}:${uid}`, run_uid: runId, habit_uid: uid });
  }
  runsWeek = 0; quickLogged = new Set(); quickResults = []; quickSession = '';
  toast('Run created.');
  showScreen('runs');
}

document.addEventListener('DOMContentLoaded', boot);
