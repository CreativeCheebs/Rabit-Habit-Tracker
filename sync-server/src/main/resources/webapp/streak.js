'use strict';
/* Streak rule shared in spirit with the Android app; kept free of DOM and storage so it can be tested in Node. */

/** ISO day (YYYY-MM-DD) shifted by n days, in local time. */
export function shiftIso(iso, n) {
  const d = new Date(iso + 'T12:00:00');
  d.setDate(d.getDate() + n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * Consecutive complete days ending today, or yesterday while today is still open.
 * statusOn(day) returns { due, complete } where `due` excludes resting and skipped
 * habits and `complete` means every due habit is done or partial; null or due 0
 * means nothing was due, and such days neither break nor extend the streak.
 * The walk stops at `floor` (the earliest day that can have anything due).
 */
export function streakLength(today, floor, statusOn) {
  let streak = 0;
  for (let day = today; day >= floor; day = shiftIso(day, -1)) {
    const status = statusOn(day);
    if (!status || !status.due) continue;
    if (status.complete) streak++;
    else if (day !== today) break;
  }
  return streak;
}
