/* Modo TV: navegacion con el control remoto para la interfaz web de TV guia.
   No toca el proyecto web: se inyecta desde la app Android despues de cargar la pagina.
   - flechas: mueven el foco entre las tarjetas de canal
   - Enter / OK: reproduce el canal enfocado
   - arriba en la primera fila: vuelve al buscador
   - Enter en el buscador: cierra el teclado y pasa a la grilla
*/
(function () {
  if (window.__tvModo) return;
  window.__tvModo = true;

  var foco = -1;
    var estilo = document.createElement('style');
  estilo.textContent =
    '#grid .card.tvfoco{outline:3px solid #ff6b3d;outline-offset:2px;' +
    'transform:translateY(-2px);background:var(--card-h)}' +
    /* --- modo telefono: pantallas angostas (vertical) --- */
    '@media (max-width:640px){' +
      '.top{padding:10px 12px;gap:8px;flex-wrap:wrap}' +
      '.brand h1{font-size:17px}' +
      '.top .btn{padding:8px 12px;font-size:12.5px;border-radius:10px}' +
      '.search{order:3;flex:1 1 100%;max-width:none}' +
      '.groups{padding:8px 12px;gap:6px}' +
      '.groups .chip{font-size:12px;padding:6px 10px}' +
      '.filters{padding:8px 12px;gap:8px;flex-wrap:nowrap;overflow-x:auto}' +
      '.filters>*{flex:0 0 auto;white-space:nowrap}' +
      '.msel,.filters .btn{font-size:12px;padding:7px 11px}' +
      '.grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;padding:0 12px 16px}' +
      '.card{padding:8px;gap:9px;border-radius:11px}' +
      '.card .logo{width:40px;height:40px;flex:0 0 40px;padding:3px}' +
      '.card .nm{font-size:12px;line-height:1.2}' +
      '.card .num{font-size:9.5px;top:5px;right:7px}' +
      '.card .meta{line-height:1.2}' +
    '}';
  document.head.appendChild(estilo);

  function tarjetas() {
    var v = document.querySelectorAll('#grid .card');
    var out = [];
    for (var i = 0; i < v.length; i++) {
      if (v[i].offsetParent !== null) out.push(v[i]);
    }
    return out;
  }

  function columnas() {
    var v = tarjetas();
    if (v.length < 2) return 1;
    var arriba = v[0].getBoundingClientRect().top;
    var n = 0;
    for (var i = 0; i < v.length; i++) {
      if (Math.abs(v[i].getBoundingClientRect().top - arriba) < 4) n++;
      else break;
    }
    return Math.max(1, n);
  }

  function pintar() {
    var v = tarjetas();
    for (var i = 0; i < v.length; i++) v[i].classList.toggle('tvfoco', i === foco);
    if (v[foco]) v[foco].scrollIntoView({ block: 'nearest' });
  }

  function enReproductor() {
    var p = document.getElementById('player');
    return p && !p.classList.contains('hidden');
  }

  function buscador() { return document.getElementById('q'); }

  document.addEventListener('keydown', function (e) {
    if (enReproductor()) return;               // el reproductor ya maneja sus propias teclas
    var q = buscador();
    var enBuscador = q && document.activeElement === q;

    if (enBuscador) {
      if (e.key === 'Enter' || e.key === 'ArrowDown') {
        q.blur();
        foco = 0;
        pintar();
        e.preventDefault();
        e.stopPropagation();
      }
      return;
    }

    var v = tarjetas();
    if (!v.length) return;

    if (e.key === 'Enter' || e.key === 'OK' || e.key === ' ') {
      if (foco >= 0 && v[foco]) v[foco].click();
      e.preventDefault();
      e.stopPropagation();
      return;
    }

    var cols = columnas();
    var antes = foco;

    if (e.key === 'ArrowRight' || e.key === 'PageDown') {
      foco = Math.min(v.length - 1, foco + 1);
    } else if (e.key === 'ArrowLeft' || e.key === 'PageUp') {
      foco = Math.max(0, foco - 1);
    } else if (e.key === 'ArrowDown') {
      foco = Math.min(v.length - 1, foco + cols);
    } else if (e.key === 'ArrowUp') {
      if (foco - cols < 0) {
        if (q) q.focus();
        foco = -1;
        e.preventDefault();
        e.stopPropagation();
        return;
      }
      foco -= cols;
    } else {
      return;
    }

    if (foco !== antes) pintar();
    e.preventDefault();
    e.stopPropagation();
  }, true);

  // al cambiar los filtros la grilla se rehace: si habia foco, se mantiene en rango
  document.addEventListener('click', function () { if (foco >= 0) pintar(); }, true);
})();
