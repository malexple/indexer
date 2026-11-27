(function () {
  'use strict';

  var MB = 1048576;
  var GB = 1073741824;

  function $(id) { return document.getElementById(id); }
  function set(id, text) { var el = $(id); if (el) { el.textContent = text; } }
  function num(value) { return typeof value === 'number' ? value.toLocaleString('ru-RU') : '–'; }
  function mb(bytes) { return typeof bytes === 'number' ? (bytes / MB).toLocaleString('ru-RU', { maximumFractionDigits: 1 }) : '–'; }
  function gb(bytes) { return typeof bytes === 'number' ? (bytes / GB).toLocaleString('ru-RU', { maximumFractionDigits: 1 }) : '–'; }
  function seconds(value) { return num(value) + ' с'; }

  var origin = location.origin;
  document.querySelectorAll('.origin').forEach(function (el) { el.textContent = location.host; });
  set('example', JSON.stringify({
    mcpServers: {
      'mcp-sql': {
        url: origin + '/mcp/ИМЯ_БАЗЫ',
        headers: { Authorization: 'Bearer ВАШ_КЛЮЧ_ЧТЕНИЯ' }
      }
    }
  }, null, 2));

  var LIMITS = [
    ['maxWorkspaces', 'Баз на пользователя', num],
    ['maxDbBytes', 'Размер одной базы', function (v) { return mb(v) + ' МБ'; }],
    ['uploadsPerDay', 'Загрузок базы в сутки', num],
    ['writeBytesPerDay', 'Объём загрузок и патчей в сутки', function (v) { return mb(v) + ' МБ'; }],
    ['patchIntervalSeconds', 'Пауза между патчами', seconds],
    ['requestsPerDay', 'Запросов MCP в сутки', num],
    ['keyIssuesPerDay', 'Выдач ключей в сутки', num]
  ];

  function render(data, stale) {
    var users = data.users || {};
    var pairs = data.pairs || {};
    var storage = data.storage || {};
    $('stats-error').hidden = true;
    $('stats-stale').hidden = !stale;
    set('users-active', num(users.active));
    set('users-capacity', num(users.capacity));
    set('users-registered', num(users.registered));
    set('storage-used', mb(storage.usedBytes));
    set('storage-free', gb(storage.freeBytes));
    set('pairs-5h', num(pairs['5h']));
    set('pairs-1d', num(pairs['1d']));
    set('pairs-7d', num(pairs['7d']));
    var bar = $('bar');
    if (users.capacity > 0) {
      var share = Math.min(100, Math.round(100 * users.active / users.capacity));
      bar.style.width = share + '%';
      bar.className = share >= 100 ? 'full' : '';
    }
    if (data.generatedAt) {
      var when = new Date(data.generatedAt);
      set('generated', 'Данные на ' + when.toLocaleString('ru-RU'));
    }
    var limits = data.limits || {};
    var body = $('limits');
    body.textContent = '';
    LIMITS.forEach(function (row) {
      if (limits[row[0]] === undefined) { return; }
      var tr = document.createElement('tr');
      var name = document.createElement('td');
      var value = document.createElement('td');
      name.textContent = row[1];
      value.textContent = row[2](limits[row[0]]);
      tr.appendChild(name);
      tr.appendChild(value);
      body.appendChild(tr);
    });
  }

  function load() {
    fetch('/api/stats', { headers: { Accept: 'application/json' } })
      .then(function (response) {
        if (!response.ok) { throw new Error('HTTP ' + response.status); }
        var stale = response.headers.get('X-Stale') === 'true';
        return response.json().then(function (data) { render(data, stale); });
      })
      .catch(function () { $('stats-error').hidden = false; });
  }

  load();
  setInterval(load, 30000);
})();
