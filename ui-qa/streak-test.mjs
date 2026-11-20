// Streak rule used by the browser app: node --test ui-qa/streak-test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { streakLength, shiftIso } from '../sync-server/src/main/resources/webapp/streak.js';

const today = '2026-11-20';
const statuses = (map) => (day) => map[day] || null;

test('counts consecutive complete days ending today', () => {
  const s = { '2026-11-20': { due: 2, complete: true }, '2026-11-19': { due: 3, complete: true }, '2026-11-18': { due: 1, complete: false } };
  assert.equal(streakLength(today, '2026-11-01', statuses(s)), 2);
});

test('an open today does not break the streak that ended yesterday', () => {
  const s = { '2026-11-20': { due: 2, complete: false }, '2026-11-19': { due: 3, complete: true }, '2026-11-18': { due: 1, complete: true } };
  assert.equal(streakLength(today, '2026-11-01', statuses(s)), 2);
});

test('days with nothing due neither break nor extend the streak', () => {
  const s = { '2026-11-20': { due: 1, complete: true }, '2026-11-19': { due: 0, complete: false }, '2026-11-17': { due: 2, complete: true }, '2026-11-16': { due: 2, complete: false } };
  assert.equal(streakLength(today, '2026-11-01', statuses(s)), 2);
});

test('an incomplete past day ends the streak', () => {
  const s = { '2026-11-20': { due: 1, complete: true }, '2026-11-19': { due: 1, complete: false }, '2026-11-18': { due: 1, complete: true } };
  assert.equal(streakLength(today, '2026-11-01', statuses(s)), 1);
});

test('stops at the floor and returns 0 without data', () => {
  assert.equal(streakLength(today, '2026-11-15', () => ({ due: 1, complete: true })), 6);
  assert.equal(streakLength(today, '2026-11-01', () => null), 0);
});

test('shiftIso crosses month and year boundaries', () => {
  assert.equal(shiftIso('2026-03-01', -1), '2026-02-28');
  assert.equal(shiftIso('2026-12-31', 1), '2027-01-01');
});
