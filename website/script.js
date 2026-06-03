// PalmVellum landing — tiny progressive enhancement.
// Animates the SYNC status counter and adds a typing effect to the
// status bar so the page feels alive without any heavy JS.

(() => {
  'use strict';

  /* Animate the SYNC: x% number in the status bar */
  const statusItems = document.querySelectorAll('.status-bar em');
  let syncTarget = null;
  statusItems.forEach((el) => {
    if (el.textContent.includes('%')) syncTarget = el;
  });

  if (syncTarget) {
    let n = 0;
    const tick = () => {
      n = (n + 1) % 101;
      syncTarget.textContent = String(n) + '%';
    };
    setInterval(tick, 80);
  }

  /* Pretend the cradle USB serial path is being polled */
  const cradle = Array.from(statusItems).find(
    (el) => el.textContent.indexOf('cu.usbserial') === 0,
  );
  if (cradle) {
    const variants = [
      'cu.usbserial-FT*',
      'cu.usbserial-FTBQNXYZ',
      'cu.usbserial-FT2A7CKQ',
      'cu.usbserial-FT*  [searching...]',
    ];
    let i = 0;
    setInterval(() => {
      cradle.textContent = variants[i % variants.length];
      i += 1;
    }, 2400);
  }

  /* Year in footer (just in case we want it dynamic — currently
   * literal in the markup; reserved for future). */
})();
