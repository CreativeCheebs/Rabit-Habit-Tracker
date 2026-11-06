/* Minimal YAML subset for Habit Lab run files. No dependencies, no DOM.
 * Supported shape:
 *   name: Reset week        # optional run name
 *   days: 14                # optional run length 1..365 (default 21)
 *   start: 2026-11-06       # optional start date (default today)
 *   habits:                 # required list
 *     - name: Morning light # required per habit
 *       group: Wellbeing    # optional (default General)
 *       points: 2           # optional int >= 0 (default 1)
 *       icon: run           # optional; the Android app picks the icon, the web app ignores it
 *       days_per_week: 5    # weekly target 1..7, OR:
 *       every: 2 days       # repeat interval N day(s), N >= 1
 * Blank lines and `#` comments are ignored. Indentation is 2 spaces
 * per level; `- ` starts a habit; `- name: X` inline is accepted.
 */
'use strict';

function unquote(s) {
  s = s.trim();
  if (s.length >= 2 && ((s[0] === '"' && s[s.length - 1] === '"') || (s[0] === "'" && s[s.length - 1] === "'"))) {
    return s.slice(1, -1);
  }
  return s;
}

function parseEvery(raw) {
  const m = /^(\d+)\s*days?$/.exec(raw.trim().toLowerCase());
  if (!m) return null;
  const n = Number(m[1]);
  return n >= 1 ? n : null;
}

function isDate(raw) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(raw.trim())) return false;
  const d = new Date(raw.trim() + 'T12:00:00');
  return !Number.isNaN(d.getTime());
}

export function parseRunYaml(src) {
  const errors = [];
  const run = { name: '', days: 21, start: '' };
  const habits = [];
  let current = null;
  let inHabits = false;
  const lines = String(src == null ? '' : src).split('\n');
  lines.forEach((rawLine, idx) => {
    const lineNo = idx + 1;
    const line = rawLine.replace(/\r$/, '');
    if (!line.trim() || line.trimStart().startsWith('#')) return;
    const indent = line.length - line.trimStart().length;
    const body = line.trim();
    const dash = body.startsWith('- ') || body === '-';
    if (indent === 0 && !dash) {
      const colon = body.indexOf(':');
      if (colon < 0) { errors.push(`line ${lineNo}: expected "key: value"`); return; }
      const key = body.slice(0, colon).trim().toLowerCase();
      const val = unquote(body.slice(colon + 1));
      if (key === 'habits') { inHabits = true; current = null; return; }
      inHabits = false;
      if (key === 'name') run.name = val;
      else if (key === 'days') {
        const n = Number(val);
        if (!Number.isInteger(n) || n < 1 || n > 365) errors.push(`line ${lineNo}: days must be 1..365`);
        else run.days = n;
      } else if (key === 'start') {
        if (!isDate(val)) errors.push(`line ${lineNo}: start must be YYYY-MM-DD`);
        else run.start = val.trim();
      } else errors.push(`line ${lineNo}: unknown run key "${key}"`);
      return;
    }
    if (!inHabits) { errors.push(`line ${lineNo}: habit entries belong under "habits:"`); return; }
    if (dash) {
      current = { name: '', group: 'General', points: 1, weekly: 0, interval: 0, line: lineNo };
      habits.push(current);
      const rest = body === '-' ? '' : body.slice(2).trim();
      if (!rest) return;
      const colon = rest.indexOf(':');
      if (colon < 0) { errors.push(`line ${lineNo}: expected "key: value"`); return; }
      setHabitField(current, rest.slice(0, colon).trim().toLowerCase(), unquote(rest.slice(colon + 1)), errors, lineNo);
      return;
    }
    if (!current) { errors.push(`line ${lineNo}: expected "- " to start a habit`); return; }
    const colon = body.indexOf(':');
    if (colon < 0) { errors.push(`line ${lineNo}: expected "key: value"`); return; }
    setHabitField(current, body.slice(0, colon).trim().toLowerCase(), unquote(body.slice(colon + 1)), errors, lineNo);
  });
  habits.forEach((h, i) => {
    const where = `habit ${i + 1}`;
    if (!h.name) errors.push(`${where} (line ${h.line}): missing name`);
    if (h.weekly && h.interval) errors.push(`${where}: use either days_per_week or every, not both`);
    if (!h.weekly && !h.interval) h.interval = 1;
  });
  if (!habits.length && !errors.length) errors.push('no habits found under "habits:"');
  return { ok: errors.length === 0, run, habits, errors };
}

function setHabitField(h, key, val, errors, lineNo) {
  if (key === 'name') h.name = val;
  else if (key === 'group') h.group = val || 'General';
  else if (key === 'icon') h.icon = val; // Android-only icon key; accepted so one file works on both clients
  else if (key === 'points') {
    const n = Number(val);
    if (!Number.isInteger(n) || n < 0) errors.push(`line ${lineNo}: points must be an integer >= 0`);
    else h.points = n;
  } else if (key === 'days_per_week') {
    const n = Number(val);
    if (!Number.isInteger(n) || n < 1 || n > 7) errors.push(`line ${lineNo}: days_per_week must be 1..7`);
    else h.weekly = n;
  } else if (key === 'every') {
    const n = parseEvery(val);
    if (n == null) errors.push(`line ${lineNo}: every must look like "2 days"`);
    else h.interval = n;
  } else errors.push(`line ${lineNo}: unknown habit key "${key}"`);
}

/* Copy-prompt for external chatbots: explains the format and the rules
 * so any AI can produce a paste-ready run file. Shown in the builder.
 */
export const YAML_GUIDE_PROMPT = [
  'You are helping configure a habit run for Habit Lab, a habit tracker.',
  'Output ONLY a YAML run file, no explanations. The user will paste it into the app.',
  '',
  'FORMAT (2-space indent, "# " comments allowed):',
  '  name: <run name, optional, default "<days>-day run">',
  '  days: <run length 1..365, optional, default 21>',
  '  start: <YYYY-MM-DD start date, optional, default today>',
  '  habits:   # required, at least one',
  '    - name: <habit name, required>',
  '      group: <group label, optional, default General>',
  '      points: <impact points per completion, integer >= 0, optional, default 1>',
  '      days_per_week: <1..7, weekly target the user hits on any days>',
  '      every: <N days, e.g. "2 days"; repeat interval, 1 means daily>',
  '  Give each habit EXACTLY ONE of days_per_week / every.',
  '  daily habit -> every: 1 day.  weekdays habit -> days_per_week: 5.',
  '',
  'RULES THE APP ENFORCES (respect them when choosing schedules):',
  '- An "every N days" habit stays due until done: if missed today it is',
  '  still due tomorrow. After a Done it hides for N-1 days, then is due again.',
  '- A "days_per_week" habit is due every day until done that day.',
  '- Only Done earns impact points. Partial and Skipped earn nothing.',
  '- Keep 2-6 habits per run, 1-4 points each. Ask the user if unclear.',
  '',
  'Ask the user which habits they want (name, how often, group, points),',
  'then output ONLY the YAML file.',
].join('\n');
