export function effectiveMode(selected, systemReduced) {
  return selected === 'off' ? 'off' : selected === 'reduced' || systemReduced ? 'reduced' : 'full';
}
export function shouldAnimate({mode, pageVisible, panelVisible, heroVisible, dialogOpen}) {
  return mode === 'full' && pageVisible && panelVisible && heroVisible && !dialogOpen;
}
// Finite effects may run in reduced mode while the continuous loop is paused.
export function shouldEnter(mode, available) {
  return mode !== 'off' && available;
}
export function shouldCancelFeedback(previousMode, mode, available) {
  return previousMode !== mode || mode === 'off' || !available;
}
export function parallaxOffset(scrollTop) {
  return Math.max(0, Math.min(6, scrollTop * .025));
}
export function createFrameLoop({request, cancel, draw}) {
  let active = false, destroyed = false, pending = null, lastTime = null, generation = 0;
  function schedule() {
    const token = generation;
    pending = request(timestamp => {
      // A cancelled callback may still arrive; it must not clear a newer pending frame.
      if (token !== generation || !active || destroyed) return;
      pending = null;
      const dt = lastTime === null ? 0 : Math.max(0, Math.min(50, timestamp - lastTime));
      lastTime = timestamp;
      draw(dt, timestamp);
      if (token === generation && active && !destroyed && pending === null) schedule();
    });
  }
  function setActive(next) {
    if (destroyed || active === Boolean(next)) return;
    active = Boolean(next); generation++; lastTime = null;
    if (pending !== null) cancel(pending);
    pending = null;
    if (active) schedule();
  }
  return {setActive, destroy() {setActive(false); destroyed = true; generation++;}};
}
