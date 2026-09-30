package com.sleepysoong.hoard.browser

/*
 * JavaScript run in the page (Runtime.evaluate, returnByValue). Kept free of `$`
 * (Kotlin raw strings would treat it as a template).
 *
 * Element ids: `window.__hoard` holds a per-document token, a WeakMap element → id
 * (so an element keeps its id across state calls on the same document) and the map
 * id → element of the LATEST state. A navigation creates a new document, a new token
 * and new ids, so an old id can never hit an element of a different page.
 */
internal object PageScripts {
    /** `(opts) => state`. opts: {maxElements, maxText}. */
    const val STATE = """(opts) => {
  let H = window.__hoard;
  if (!H || !H.ids) {
    H = { doc: Math.random().toString(36).slice(2, 10) + Date.now().toString(36), ids: new WeakMap(), els: new Map(), next: 1 };
    try { Object.defineProperty(window, '__hoard', { value: H, configurable: true, writable: true, enumerable: false }); } catch (e) { window.__hoard = H; }
  }
  H.els = new Map();
  const vw = window.innerWidth, vh = window.innerHeight;
  const clean = s => (s || '').replace(/\s+/g, ' ').trim();
  const cut = (s, n) => s.length > n ? s.slice(0, n - 1) + '…' : s;
  const styleOk = el => {
    const cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.visibility !== 'collapse' && cs.display !== 'none' && parseFloat(cs.opacity || '1') > 0.05;
  };
  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'HEAD', 'META', 'LINK', 'IFRAME', 'FRAME', 'OBJECT', 'EMBED']);
  const ROLES = new Set(['button', 'link', 'checkbox', 'radio', 'tab', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'option',
    'switch', 'combobox', 'textbox', 'searchbox', 'slider', 'spinbutton', 'treeitem', 'gridcell']);
  const interactive = el => {
    const tag = el.tagName;
    if (tag === 'A') return el.hasAttribute('href') || el.hasAttribute('onclick');
    if (tag === 'BUTTON' || tag === 'SELECT' || tag === 'TEXTAREA' || tag === 'SUMMARY') return true;
    if (tag === 'INPUT') return (el.type || '').toLowerCase() !== 'hidden';
    const role = el.getAttribute('role');
    if (role && ROLES.has(role)) return true;
    if (el.isContentEditable && !(el.parentElement && el.parentElement.isContentEditable)) return true;
    if (el.hasAttribute('onclick')) return true;
    const ti = el.getAttribute('tabindex');
    return ti !== null && parseInt(ti, 10) >= 0 && tag !== 'BODY' && tag !== 'HTML';
  };
  const pointerLike = el => {
    if (el === document.body || el === document.documentElement) return false;
    if (getComputedStyle(el).cursor !== 'pointer') return false;
    const p = el.parentElement;
    return !(p && getComputedStyle(p).cursor === 'pointer');
  };
  const kind = el => {
    const tag = el.tagName, role = el.getAttribute('role');
    if (tag === 'A') return 'link';
    if (tag === 'BUTTON' || tag === 'SUMMARY') return 'button';
    if (tag === 'SELECT') return 'select';
    if (tag === 'TEXTAREA') return 'textarea';
    if (tag === 'INPUT') {
      const t = (el.type || 'text').toLowerCase();
      if (t === 'button' || t === 'submit' || t === 'reset' || t === 'image') return 'button';
      if (t === 'checkbox' || t === 'radio') return t;
      return 'input';
    }
    if (role) return role === 'textbox' || role === 'searchbox' ? 'input' : role;
    if (el.isContentEditable) return 'editable';
    return 'clickable';
  };
  const nameOf = el => {
    const aria = el.getAttribute('aria-label');
    if (aria && clean(aria)) return aria;
    const by = el.getAttribute('aria-labelledby');
    if (by) {
      const t = by.split(/\s+/).map(i => { const n = document.getElementById(i); return n ? n.innerText || n.textContent : ''; }).join(' ');
      if (clean(t)) return t;
    }
    if (el.labels && el.labels.length) return Array.from(el.labels).map(l => l.innerText || '').join(' ');
    const title = el.getAttribute('title');
    if (title) return title;
    if (el.tagName === 'IMG') return el.alt || '';
    const img = el.querySelector && el.querySelector('img[alt]');
    return img ? img.alt : '';
  };
  const describe = (el, id, r) => {
    const o = { id: id, type: kind(el) };
    const tag = el.tagName;
    const field = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
    const text = field ? '' : clean(el.innerText || el.textContent);
    const name = clean(nameOf(el));
    if (text) o.text = cut(text, 100);
    if (name && name !== text) o.label = cut(name, 100);
    if (tag === 'INPUT') {
      const t = (el.type || 'text').toLowerCase();
      if (o.type === 'input' && t !== 'text') o.input_type = t;
      if (o.type === 'button' && el.value) o.text = cut(clean(el.value), 100);
    }
    if (el.placeholder) o.placeholder = cut(clean(el.placeholder), 100);
    const t = (el.type || '').toLowerCase();
    if ((tag === 'INPUT' || tag === 'TEXTAREA') && el.value && !['button', 'submit', 'reset', 'image', 'checkbox', 'radio', 'file'].includes(t)) {
      o.value = t === 'password' ? '(hidden)' : cut(el.value, 200);
    }
    if (el.isContentEditable && !field) { o.value = cut(clean(el.innerText), 200); delete o.text; }
    if (tag === 'SELECT') {
      const sel = el.selectedOptions && el.selectedOptions[0];
      if (sel) o.value = clean(sel.text);
      o.options = Array.from(el.options).slice(0, 25).map(op => cut(clean(op.text), 60));
    }
    if (tag === 'A') {
      const h = el.getAttribute('href');
      if (h && !h.trim().toLowerCase().startsWith('javascript:')) {
        // Same-site links as paths: half the size on link-heavy pages.
        let full = el.href;
        try { const u = new URL(el.href); if (u.origin === location.origin) full = u.pathname + u.search + u.hash; } catch (e) {}
        o.href = cut(full, 150);
      }
    }
    if (t === 'checkbox' || t === 'radio') o.checked = !!el.checked;
    else if (el.hasAttribute('aria-checked')) o.checked = el.getAttribute('aria-checked') === 'true';
    if (el.hasAttribute('aria-selected')) o.selected = el.getAttribute('aria-selected') === 'true';
    if (el.hasAttribute('aria-expanded')) o.expanded = el.getAttribute('aria-expanded') === 'true';
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') o.disabled = true;
    if (r.top >= vh || r.bottom <= 0) o.offscreen = true;
    return o;
  };
  const out = [];
  let more = 0;
  const add = (el, r) => {
    let id = H.ids.get(el);
    if (!id) { id = H.next++; H.ids.set(el, id); }
    H.els.set(id, el);
    out.push(describe(el, id, r));
  };
  const visit = (root, inside) => {
    for (let el = root.firstElementChild; el; el = el.nextElementSibling) {
      if (SKIP.has(el.tagName)) continue;
      const inter = interactive(el);
      const ptr = !inter && !inside && pointerLike(el);
      if (inter || ptr) {
        const r = el.getBoundingClientRect();
        if (r.width >= 1 && r.height >= 1 && styleOk(el)) {
          const near = r.bottom > -vh * 0.25 && r.top < vh * 1.75 && r.right > 0 && r.left < vw;
          if (near && out.length < opts.maxElements) add(el, r); else more++;
        }
      }
      const deeper = inside || inter || ptr;
      if (el.shadowRoot) visit(el.shadowRoot, deeper);
      if (el.tagName !== 'SELECT' && el.tagName.toLowerCase() !== 'svg') visit(el, deeper);
    }
  };
  visit(document.documentElement || document, false);

  const vis = new Map(), blocks = new Map();
  const blockOf = el => {
    const path = [];
    let b = null;
    for (let e = el; e; e = e.parentElement) {
      const c = blocks.get(e);
      if (c) { b = c; break; }
      path.push(e);
      const d = getComputedStyle(e).display || '';
      if (!d.startsWith('inline') && d !== 'contents') { b = e; break; }
    }
    b = b || document.body;
    path.forEach(p => blocks.set(p, b));
    return b;
  };
  let text = '', last = null;
  const root = document.body || document.documentElement;
  if (root) {
    const tw = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (text.length < opts.maxText) {
      const n = tw.nextNode();
      if (!n) break;
      const p = n.parentElement;
      if (!p || SKIP.has(p.tagName)) continue;
      const v = n.nodeValue;
      if (!v || !v.trim()) continue;
      let ok = vis.get(p);
      if (ok === undefined) {
        const r = p.getBoundingClientRect();
        ok = r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < vh * 1.5 && r.right > 0 && r.left < vw && styleOk(p);
        vis.set(p, ok);
      }
      if (!ok) continue;
      const b = blockOf(p);
      text += (text ? (b === last ? ' ' : '\n') : '') + clean(v);
      last = b;
    }
  }
  if (text.length > opts.maxText) text = text.slice(0, opts.maxText) + '…';
  const se = document.scrollingElement || document.documentElement;
  const y = se ? Math.round(se.scrollTop) : 0, h = se ? se.scrollHeight : vh;
  return {
    url: location.href, title: document.title, doc: H.doc, elements: out, more_elements: more, text: text,
    scroll: { y: y, height: h, viewport: vh, at_top: y <= 2, at_bottom: y + vh >= h - 4 }
  };
}"""

    private const val FIND = """
  const H = window.__hoard;
  if (!H || H.doc !== doc) return { error: 'stale' };
  let el = H.els && H.els.get(id);
  if (!el) return { error: 'unknown' };
  if (!el.isConnected) return { error: 'detached' };
  const within = (a, n) => { for (; n; n = n.parentNode || n.host) { if (n === a) return true; } return false; };"""

    /** `(id, doc, scroll) => {x, y, hit, covering?, select?}` — the element's center (after scrolling it into view). */
    const val LOCATE = """(id, doc, scroll) => {""" + FIND + """
  if (scroll) el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
  const r = el.getBoundingClientRect();
  if (r.width < 1 || r.height < 1) return { error: 'hidden' };
  const x = r.left + r.width / 2, y = r.top + r.height / 2;
  let top = document.elementFromPoint(x, y);
  while (top && top.shadowRoot) { const inner = top.shadowRoot.elementFromPoint(x, y); if (!inner || inner === top) break; top = inner; }
  const hit = !!top && (within(el, top) || top.control === el || (el.labels && Array.from(el.labels).includes(top)));
  const covering = !hit && top ? (top.tagName.toLowerCase() + (top.innerText ? ' "' + top.innerText.replace(/\s+/g, ' ').trim().slice(0, 40) + '"' : '')) : '';
  return { x: x, y: y, hit: hit, covering: covering, select: el.tagName === 'SELECT' };
}"""

    /** `(id, doc) => true` — DOM click (used when something covers the element). */
    const val JS_CLICK = """(id, doc) => {""" + FIND + """
  el.click();
  return { ok: true };
}"""

    /** `(id, doc, clear) => {kind}` — focuses a text field, selecting (clear) or moving to the end of its content. */
    const val FOCUS_FIELD = """(id, doc, clear) => {""" + FIND + """
  el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
  if (el.tagName === 'SELECT') return { kind: 'select' };
  const editable = x => x.tagName === 'INPUT' || x.tagName === 'TEXTAREA' || x.isContentEditable;
  if (!editable(el)) {
    const inner = el.querySelector && el.querySelector('input:not([type=hidden]), textarea, [contenteditable=""], [contenteditable="true"]');
    if (!inner) return { error: 'not_editable' };
    el = inner;
  }
  if (el.disabled || el.readOnly) return { error: 'readonly' };
  el.focus({ preventScroll: true });
  if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
    if (clear) {
      try { el.select(); } catch (e) {}
      let selected = false;
      try { selected = el.selectionStart === 0 && el.selectionEnd === el.value.length; } catch (e) {}
      if (el.value && !selected) {
        const d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
        if (d && d.set) d.set.call(el, ''); else el.value = '';
        el.dispatchEvent(new Event('input', { bubbles: true }));
      }
    } else {
      try { const n = el.value.length; el.setSelectionRange(n, n); } catch (e) {}
    }
  } else {
    const range = document.createRange();
    range.selectNodeContents(el);
    if (!clear) range.collapse(false);
    const sel = getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }
  const a = document.activeElement;
  return { kind: el.isContentEditable ? 'editable' : 'input', focused: a === el || within(el, a), had: !!(el.value || el.innerText) };
}"""

    /** `(id, doc, text) => {chosen} | {error, options}` — picks a <select> option by its text or value. */
    const val CHOOSE_OPTION = """(id, doc, text) => {""" + FIND + """
  if (el.tagName !== 'SELECT') return { error: 'not_select' };
  const want = text.trim().toLowerCase();
  const opts = Array.from(el.options);
  const norm = o => (o.text || '').replace(/\s+/g, ' ').trim().toLowerCase();
  const op = opts.find(o => norm(o) === want) || opts.find(o => (o.value || '').toLowerCase() === want) || opts.find(o => norm(o).includes(want));
  if (!op) return { error: 'no_option', options: opts.slice(0, 40).map(o => (o.text || '').trim()) };
  el.focus();
  el.value = op.value;
  op.selected = true;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return { chosen: (op.text || '').trim() };
}"""

    /** Resolves once the DOM has been quiet for 350 ms (max 2.5 s). */
    const val DOM_QUIET = """new Promise(res => {
  let t = null, done = false;
  const finish = () => { if (done) return; done = true; try { obs.disconnect(); } catch (e) {} res(true); };
  const obs = new MutationObserver(() => { clearTimeout(t); t = setTimeout(finish, 350); });
  try { obs.observe(document, { subtree: true, childList: true, attributes: true, characterData: true }); } catch (e) {}
  t = setTimeout(finish, 350);
  setTimeout(finish, 2500);
})"""

    /** Viewport size (for scrolling without an element). */
    const val VIEWPORT = """(() => ({ w: window.innerWidth, h: window.innerHeight }))()"""
}
