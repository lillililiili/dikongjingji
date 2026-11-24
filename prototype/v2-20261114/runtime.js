// Minimal browser runtime for .dc.html boards: {{holes}}, sc-if, sc-for, onClick / onChange, setState re-render.
// The board's template is mounted into a container; links to X.dc.html are rewritten to X.html so pages chain inside the phone shell.
(function () {
  class DCLogic {
    constructor(props) { this.props = props || {}; this.state = {}; }
    setState(update) {
      const patch = typeof update === 'function' ? update(this.state) : update;
      Object.assign(this.state, patch || {});
      if (this.__rerender) this.__rerender();
    }
    forceUpdate() { if (this.__rerender) this.__rerender(); }
  }
  window.DCLogic = DCLogic;

  const HOLE = /\{\{([^}]+)\}\}/g;
  const LIT = { true: true, false: false, null: null };
  function lookup(scope, expr) {
    const e = expr.trim();
    if (e in LIT) return LIT[e];
    if (/^-?\d+(\.\d+)?$/.test(e)) return Number(e);
    const path = e.split('.');
    let cur = scope;
    for (const key of path) { if (cur == null) return undefined; cur = cur[key]; }
    return cur;
  }
  function whole(value) { const m = /^\s*\{\{([^}]+)\}\}\s*$/.exec(value); return m ? m[1] : null; }
  function interp(value, scope) {
    return value.replace(HOLE, (_, expr) => { const v = lookup(scope, expr); return v == null ? '' : String(v); });
  }
  function truthy(v) { return !(v === false || v == null || v === '' || v === 0 || (Array.isArray(v) && v.length === 0)); }

  function render(node, scope, out) {
    if (node.nodeType === 3) {
      if (node.nodeValue.includes('{{')) { out.appendChild(document.createTextNode(interp(node.nodeValue, scope))); }
      else out.appendChild(node.cloneNode(false));
      return;
    }
    if (node.nodeType !== 1) return;
    const tag = node.tagName.toLowerCase();
    if (tag === 'sc-if') {
      const raw = node.getAttribute('value') || '';
      const w = whole(raw);
      const v = w !== null ? lookup(scope, w) : raw;
      if (truthy(v)) for (const child of Array.from(node.childNodes)) render(child, scope, out);
      return;
    }
    if (tag === 'sc-for') {
      const w = whole(node.getAttribute('list') || '');
      const list = w !== null ? lookup(scope, w) : [];
      const as = node.getAttribute('as') || 'item';
      (Array.isArray(list) ? list : []).forEach((item, index) => {
        const inner = Object.assign(Object.create(scope), { [as]: item, [as + 'Index']: index });
        for (const child of Array.from(node.childNodes)) render(child, inner, out);
      });
      return;
    }
    // svg (and anything else foreign) must be created in its own namespace or it will not draw
    const el = node.namespaceURI && node.namespaceURI !== 'http://www.w3.org/1999/xhtml' ? document.createElementNS(node.namespaceURI, node.localName) : document.createElement(tag);
    for (const attr of Array.from(node.attributes)) {
      const name = attr.name;
      const w = whole(attr.value);
      const value = w !== null ? lookup(scope, w) : interp(attr.value, scope);
      if (/^on[a-z]/i.test(name)) {
        if (typeof value === 'function') {
          const ev = name.slice(2).toLowerCase();
          el.addEventListener(ev === 'change' && (tag === 'input' || tag === 'textarea') ? 'input' : ev, (e) => { e.preventDefault && ev === 'click' && e.preventDefault(); value(e); });
          if (ev === 'change') el.setAttribute('data-ev-change', attr.value);
        }
        continue;
      }
      if (name === 'disabled' || name === 'checked' || name === 'selected') {
        const on = attr.value === '' || (truthy(value) && value !== 'false');
        if (on) el.setAttribute(name, '');
        if (name === 'checked') el.checked = on;
        continue;
      }
      if (name === 'href' && typeof value === 'string') {
        el.setAttribute('href', value.replace(/\.dc\.html(?=$|[?#])/, '.html'));
        continue;
      }
      if (name === 'value' && (tag === 'input' || tag === 'textarea')) { el.value = value == null ? '' : String(value); el.setAttribute('value', el.value); continue; }
      if (value === false || value == null) continue;
      el.setAttribute(name, String(value));
    }
    for (const child of Array.from(node.childNodes)) render(child, scope, el);
    out.appendChild(el);
  }

  window.mountBoard = function (templateHtml, Component, props, root) {
    const tpl = document.createElement('template');
    tpl.innerHTML = templateHtml;
    const inst = new Component(Object.assign({}, props));
    let focusKey = null, focusPos = 0;
    const draw = () => {
      const active = document.activeElement;
      if (active && active.getAttribute && active.getAttribute('data-ev-change')) { focusKey = active.getAttribute('data-ev-change'); focusPos = active.selectionStart || 0; }
      const scope = inst.renderVals() || {};
      const frag = document.createDocumentFragment();
      for (const child of Array.from(tpl.content.childNodes)) render(child, scope, frag);
      root.innerHTML = '';
      root.appendChild(frag);
      if (window.applyLightDesign) window.applyLightDesign(root);
      if (focusKey) {
        const again = root.querySelector('[data-ev-change="' + focusKey.replace(/"/g, '\\"') + '"]');
        if (again) { again.focus(); try { again.setSelectionRange(focusPos, focusPos); } catch (e) {} }
      }
    };
    inst.__rerender = draw;
    draw();
    window.__board = inst;
    const selects = document.querySelectorAll('.side select[data-prop]');
    selects.forEach((sel) => sel.addEventListener('change', () => { inst.props[sel.getAttribute('data-prop')] = sel.value; draw(); }));
    return inst;
  };
})();
