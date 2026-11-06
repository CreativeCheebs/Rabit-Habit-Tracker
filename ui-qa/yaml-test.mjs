import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { parseRunYaml, YAML_GUIDE_PROMPT } from '../sync-server/src/main/resources/webapp/yaml.js';

describe('parseRunYaml', () => {
  it('accepts the Android-only icon key', () => {
    const r = parseRunYaml(['habits:', '  - name: Run', '    icon: run', '    every: 1 day'].join('\n'));
    assert.equal(r.ok, true, r.errors.join(';'));
    assert.equal(r.habits[0].icon, 'run');
  });

  it('parses a full run file', () => {
    const r = parseRunYaml([
      '# comment',
      'name: Reset week',
      'days: 14',
      'start: 2026-11-06',
      'habits:',
      '  - name: Morning light',
      '    group: Wellbeing',
      '    points: 2',
      '    every: 1 day',
      '  - name: Plan the day',
      '    days_per_week: 5',
    ].join('\n'));
    assert.equal(r.ok, true);
    assert.deepEqual(r.run, { name: 'Reset week', days: 14, start: '2026-11-06' });
    assert.equal(r.habits.length, 2);
    assert.deepEqual(r.habits[0], { name: 'Morning light', group: 'Wellbeing', points: 2, weekly: 0, interval: 1, line: 6 });
    assert.equal(r.habits[1].weekly, 5);
  });

  it('applies defaults and inline dash items', () => {
    const r = parseRunYaml('habits:\n  - name: Drink water\n  -\n    name: Stretch\n    points: 3\n');
    assert.equal(r.ok, true);
    assert.deepEqual(r.run, { name: '', days: 21, start: '' });
    assert.equal(r.habits[0].group, 'General');
    assert.equal(r.habits[0].interval, 1);
    assert.equal(r.habits[1].points, 3);
  });

  it('rejects both schedules, bad days, missing names', () => {
    const r = parseRunYaml('days: 400\nhabits:\n  - group: X\n    days_per_week: 5\n    every: 2 days\n  - name: Ok\n    points: -1\n');
    assert.equal(r.ok, false);
    assert.match(r.errors.join('|'), /days must be 1\.\.365/);
    assert.match(r.errors.join('|'), /missing name/);
    assert.match(r.errors.join('|'), /not both/);
    assert.match(r.errors.join('|'), /points must be/);
  });

  it('rejects unknown keys and bad dates', () => {
    const r = parseRunYaml('frob: 1\nhabits:\n  - name: A\n    every: sometimes\nstart: 06-11-2026\n');
    assert.equal(r.ok, false);
    assert.match(r.errors.join('|'), /unknown run key/);
    assert.match(r.errors.join('|'), /every must look like/);
    assert.match(r.errors.join('|'), /start must be/);
  });

  it('guide prompt documents the format', () => {
    for (const s of ['days_per_week', 'every:', 'habits:', 'ONLY']) {
      assert.ok(YAML_GUIDE_PROMPT.includes(s), s);
    }
  });
});
