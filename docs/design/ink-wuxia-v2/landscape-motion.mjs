import {effectiveMode, shouldAnimate, createFrameLoop} from './motion.mjs';

export function installLandscapeMotion() {
  const system = matchMedia('(prefers-reduced-motion: reduce)');
  const dialog = document.querySelector('#demo-dialog');
  let selected = 'full';
  const request = callback => requestAnimationFrame(callback);
  const cancel = id => cancelAnimationFrame(id);
  function intersects(a, b) {
    return a.width > 0 && a.height > 0 && a.bottom > b.top && a.top < b.bottom && a.right > b.left && a.left < b.right;
  }
  const entries = [...document.querySelectorAll('.phone')].map(phone => {
    const hero = phone.querySelector('.landscape'), scroll = phone.querySelector('.phone-scroll');
    const canvas = document.createElement('canvas');
    canvas.className = 'landscape-motion'; canvas.setAttribute('aria-hidden', 'true'); hero.prepend(canvas);
    const feedbackCanvas = document.createElement('canvas');
    feedbackCanvas.className = 'completion-motion'; feedbackCanvas.setAttribute('aria-hidden', 'true'); phone.append(feedbackCanvas);
    const context = canvas.getContext('2d'), feedbackContext = feedbackCanvas.getContext('2d');
    // A fixed normalized pool stays in the valleys, clear of the brand and date line.
    const dust = Array.from({length:32}, (_, i) => ({x:.44 + ((i * 17) % 53) / 100, y:.55 + ((i * 11) % 30) / 100, size:.6 + (i % 8) * .2, alpha:.1 + (i % 5) * .035}));
    const entry = {phone, hero, scroll, canvas, feedbackCanvas, context, feedbackContext, dust, phase:0, elapsed:0, first:true, width:0, height:0, feedbackAnimation:null, scrollFrame:null};
    function size() {
      const dpr = Math.min(devicePixelRatio || 1, 2);
      entry.width = hero.clientWidth; entry.height = hero.clientHeight;
      for (const [layer, ctx, width, height] of [[canvas,context,entry.width,entry.height], [feedbackCanvas,feedbackContext,phone.clientWidth,phone.clientHeight]]) {
        layer.width = Math.round(width * dpr); layer.height = Math.round(height * dpr); ctx.setTransform(dpr,0,0,dpr,0,0);
      }
    }
    size();
    entry.loop = createFrameLoop({request, cancel, draw(dt) {
      entry.phase += dt / 1000; entry.elapsed += dt;
      if (!entry.first && entry.elapsed < 1000 / 30) return;
      entry.first = false; entry.elapsed = 0;
      const {width:w,height:h,phase:t} = entry;
      context.clearRect(0,0,w,h);
      const dark = phone.dataset.theme === 'dark';
      for (let layer = 0; layer < 2; layer++) {
        const x = w * (.64 + Math.sin(t * .12 + layer * 2) * .09), y = h * (.68 + layer * .12);
        context.save(); context.translate(x,y); context.scale(1,.17);
        const fog = context.createRadialGradient(0,0,0,0,0,w * .56);
        fog.addColorStop(0, dark ? 'rgba(190,219,203,.13)' : 'rgba(249,246,226,.26)'); fog.addColorStop(1,'rgba(230,240,216,0)');
        context.fillStyle = fog; context.fillRect(-w,-w,w * 2,w * 2); context.restore();
      }
      context.fillStyle = dark ? '#b6ccb8' : '#334d3c';
      for (const particle of dust) {
        const x = particle.x * w + Math.sin(t * .19 + particle.x * 31) * 5;
        const y = particle.y * h + Math.sin(t * .14 + particle.y * 37) * 3;
        context.globalAlpha = particle.alpha; context.beginPath(); context.ellipse(x,y,particle.size,particle.size * .55,particle.x * 4,0,Math.PI * 2); context.fill();
      }
      context.globalAlpha = 1;
    }});
    scroll.addEventListener('scroll', () => {
      refresh();
      if (phone.dataset.motionState !== 'running' || entry.scrollFrame !== null) return;
      entry.scrollFrame = request(() => {
        entry.scrollFrame = null;
        if (phone.dataset.motionState === 'running') canvas.style.transform = `translateY(${Math.min(6, scroll.scrollTop * .025)}px)`;
      });
    }, {passive:true});
    new ResizeObserver(() => {size(); refresh();}).observe(phone);
    const windowObserver = new IntersectionObserver(refresh);
    windowObserver.observe(hero);
    const scrollObserver = new IntersectionObserver(refresh, {root:scroll});
    scrollObserver.observe(hero);
    return entry;
  });
  function available(entry, heroRequired) {
    const windowRect = {top:0,left:0,right:innerWidth,bottom:innerHeight};
    return !document.hidden && !dialog.open && entry.phone.getClientRects().length > 0 &&
      intersects((heroRequired ? entry.hero : entry.phone).getBoundingClientRect(), windowRect) &&
      (!heroRequired || intersects(entry.hero.getBoundingClientRect(), entry.scroll.getBoundingClientRect()));
  }
  function clearFeedback(entry) {
    entry.feedbackLoop?.destroy(); entry.feedbackLoop = null;
    entry.feedbackAnimation?.cancel(); entry.feedbackAnimation = null;
    entry.feedbackContext.clearRect(0,0,entry.phone.clientWidth,entry.phone.clientHeight);
  }
  function refresh() {
    const mode = effectiveMode(selected, system.matches);
    document.body.dataset.motion = mode;
    for (const entry of entries) {
      const panelVisible = entry.phone.getClientRects().length > 0;
      const running = shouldAnimate({mode, pageVisible:!document.hidden, panelVisible, heroVisible:available(entry,true), dialogOpen:dialog.open});
      entry.phone.dataset.motionMode = mode;
      entry.phone.dataset.motionState = running ? 'running' : 'paused';
      entry.loop.setActive(running);
      if (!running) {
        entry.context.clearRect(0,0,entry.width,entry.height); entry.first = true; entry.elapsed = 0;
        if (entry.scrollFrame !== null) cancel(entry.scrollFrame);
        entry.scrollFrame = null; entry.canvas.style.transform = '';
      }
      if (mode !== 'full' || !available(entry,false)) clearFeedback(entry);
    }
  }
  function feedback(phone) {
    const entry = entries.find(item => item.phone === phone);
    if (!entry || !available(entry,false)) return;
    clearFeedback(entry);
    const mode = effectiveMode(selected,system.matches), button = phone.querySelector('.primary-button');
    if (mode === 'off') return;
    if (mode === 'reduced') {
      entry.feedbackAnimation = button.animate([{filter:'brightness(1)'},{filter:'brightness(1.16)'},{filter:'brightness(1)'}],{duration:180});
      return;
    }
    const bounds = phone.getBoundingClientRect(), anchor = button.getBoundingClientRect();
    const x = anchor.left - bounds.left + anchor.width / 2, y = anchor.top - bounds.top + 6;
    let elapsed = 0, sinceDraw = 0, first = true;
    entry.feedbackLoop = createFrameLoop({request,cancel,draw(dt) {
      if (!available(entry,false)) {clearFeedback(entry); return;}
      elapsed += dt; sinceDraw += dt;
      if (!first && sinceDraw < 1000 / 30 && elapsed < 450) return;
      first = false; sinceDraw = 0;
      const progress = elapsed / 450, ctx = entry.feedbackContext;
      ctx.clearRect(0,0,phone.clientWidth,phone.clientHeight);
      if (progress >= 1) {clearFeedback(entry); return;}
      ctx.fillStyle = phone.dataset.theme === 'dark' ? '#dba08d' : '#973a32'; ctx.globalAlpha = (1-progress) * .65;
      for (let i = 0; i < 8; i++) {
        const angle = Math.PI + (i / 7) * Math.PI;
        ctx.beginPath(); ctx.ellipse(x + Math.cos(angle) * (8 + progress * 22),y + Math.sin(angle) * progress * 25,1 + (i % 3) * .3,.7,angle,0,Math.PI * 2); ctx.fill();
      }
      ctx.globalAlpha = 1;
    }});
    entry.feedbackLoop.setActive(true);
  }
  function replay() {
    for (const entry of entries) entry.phone.classList.remove('motion-enter');
    // Force a style boundary so a repeat click restarts the finite CSS animations.
    void document.body.offsetWidth;
    for (const entry of entries) entry.phone.classList.add('motion-enter');
  }
  for (const button of document.querySelectorAll('[data-motion-button]')) button.addEventListener('click', () => {
    selected = button.dataset.motionButton;
    for (const sibling of document.querySelectorAll('[data-motion-button]')) sibling.setAttribute('aria-pressed',String(sibling === button));
    refresh(); replay();
  });
  document.querySelector('[data-motion-replay]').addEventListener('click',replay);
  document.addEventListener('visibilitychange',refresh);
  window.addEventListener('scroll',refresh,{passive:true}); window.addEventListener('resize',refresh);
  system.addEventListener('change',() => {refresh(); replay();});
  refresh(); replay();
  return {refresh,feedback,replay};
}
