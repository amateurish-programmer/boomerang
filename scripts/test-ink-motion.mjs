import test from 'node:test';
import assert from 'node:assert/strict';
import {effectiveMode, shouldAnimate, createFrameLoop, shouldEnter, shouldCancelFeedback, parallaxOffset} from '../docs/design/ink-wuxia-v2/motion.mjs';

for (const selected of ['full', 'reduced', 'off']) {
  for (const system of [false, true]) test(`mode ${selected}, system reduction ${system}`, () => {
    assert.equal(effectiveMode(selected, system), selected === 'full' && system ? 'reduced' : selected);
  });
}
const visible = {mode:'full', pageVisible:true, panelVisible:true, heroVisible:true, dialogOpen:false};
test('visible full mode animates', () => assert.equal(shouldAnimate(visible), true));
for (const [key, value] of [['mode','reduced'], ['mode','off'], ['pageVisible',false], ['panelVisible',false], ['heroVisible',false], ['dialogOpen',true]]) {
  test(`pause on ${key}=${value}`, () => assert.equal(shouldAnimate({...visible, [key]:value}), false));
}
function harness(draw = () => {}) {
  let id = 0;
  const queued = new Map(), cancelled = [];
  const loop = createFrameLoop({request: callback => {queued.set(++id, callback); return id;}, cancel: key => {cancelled.push(key); queued.delete(key);}, draw});
  const frame = time => {const [key, callback] = queued.entries().next().value; queued.delete(key); callback(time);};
  return {loop, queued, cancelled, frame};
}
test('repeated activation maintains one pending frame', () => {
  const h = harness(); h.loop.setActive(true); h.loop.setActive(true);
  assert.equal(h.queued.size, 1); h.frame(10); assert.equal(h.queued.size, 1);
});
test('stop cancels pending callback', () => {
  const h = harness(); h.loop.setActive(true); h.loop.setActive(false);
  assert.equal(h.queued.size, 0); assert.equal(h.cancelled.length, 1);
});
test('cancelled late callback cannot revive or displace resumed frame', () => {
  const h = harness(); h.loop.setActive(true); const late = [...h.queued.values()][0];
  h.loop.setActive(false); h.loop.setActive(true); late(20);
  assert.equal(h.queued.size, 1); h.frame(30); assert.equal(h.queued.size, 1);
});
test('first and resumed frames are zero; deltas clamp to 0..50ms', () => {
  const deltas = [], h = harness(dt => deltas.push(dt));
  h.loop.setActive(true); h.frame(10); h.frame(30); h.frame(500); h.frame(400);
  h.loop.setActive(false); h.loop.setActive(true); h.frame(9000);
  assert.deepEqual(deltas, [0,20,50,0,0]);
});
test('destroy terminates permanently including late callbacks', () => {
  const h = harness(); h.loop.setActive(true); const late = [...h.queued.values()][0];
  h.loop.destroy(); h.loop.setActive(true); late(10); assert.equal(h.queued.size, 0);
});
test('draw can stop the loop without scheduling another frame', () => {
  const h = harness(() => h.loop.setActive(false)); h.loop.setActive(true); h.frame(10);
  assert.equal(h.queued.size, 0);
});

for (const mode of ['full', 'reduced', 'off']) {
  test(`finite entrance respects availability in ${mode}`, () => {
    assert.equal(shouldEnter(mode, true), mode !== 'off');
    assert.equal(shouldEnter(mode, false), false);
  });
}
test('unchanged reduced feedback survives unrelated refresh', () => {
  assert.equal(shouldCancelFeedback('reduced', 'reduced', true), false);
});
test('feedback ends on mode change, off, or unavailability', () => {
  assert.equal(shouldCancelFeedback('full', 'reduced', true), true);
  assert.equal(shouldCancelFeedback('reduced', 'full', true), true);
  assert.equal(shouldCancelFeedback('off', 'off', true), true);
  assert.equal(shouldCancelFeedback('reduced', 'reduced', false), true);
});
test('restored parallax uses current scroll position with bounded offset', () => {
  assert.equal(parallaxOffset(100), 2.5);
  assert.equal(parallaxOffset(1000), 6);
  assert.equal(parallaxOffset(-20), 0);
});
