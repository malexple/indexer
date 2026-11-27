(function () {
  'use strict';

  var MB = 1048576;
  var GB = 1073741824;

  var I18N = {
    en: {
      locale: 'en-US',
      mb: 'MB',
      sec: 's',
      asOf: 'Data as of ',
      dbName: 'DB_NAME',
      readKey: 'YOUR_READ_KEY',
      limits: {
        maxWorkspaces: 'Databases per user',
        maxDbBytes: 'Size of one database',
        uploadsPerDay: 'Database uploads per day',
        writeBytesPerDay: 'Upload volume per day',
        patchIntervalSeconds: 'Pause between patches',
        requestsPerDay: 'MCP requests per day',
        keyIssuesPerDay: 'Key issues per day'
      }
    },
    ru: {
      locale: 'ru-RU',
      mb: 'МБ',
      sec: 'с',
      asOf: 'Данные на ',
      dbName: 'ИМЯ_БАЗЫ',
      readKey: 'ВАШ_КЛЮЧ_ЧТЕНИЯ',
      limits: {
        maxWorkspaces: 'Баз на пользователя',
        maxDbBytes: 'Размер одной базы',
        uploadsPerDay: 'Загрузок базы в сутки',
        writeBytesPerDay: 'Объём загрузок и патчей в сутки',
        patchIntervalSeconds: 'Пауза между патчами',
        requestsPerDay: 'Запросов MCP в сутки',
        keyIssuesPerDay: 'Выдач ключей в сутки'
      }
    }
  };

  var lang = document.documentElement.lang === 'ru' ? 'ru' : 'en';
  var T = I18N[lang];

  function readPreference() {
    try { return window.localStorage.getItem('lang'); } catch (e) { return null; }
  }
  function savePreference(value) {
    try { window.localStorage.setItem('lang', value); } catch (e) { /* storage is blocked */ }
  }

  var wanted = new URLSearchParams(window.location.search).get('lang');
  if (wanted === 'ru' || wanted === 'en') {
    savePreference(wanted);
  }
  if (lang === 'en' && window.location.pathname === '/' && readPreference() === 'ru') {
    window.location.replace('/ru/');
    return;
  }
  document.querySelectorAll('[data-lang]').forEach(function (link) {
    link.addEventListener('click', function () { savePreference(link.getAttribute('data-lang')); });
  });

  function $(id) { return document.getElementById(id); }
  function set(id, text) { var el = $(id); if (el) { el.textContent = text; } }
  function num(value) { return typeof value === 'number' ? value.toLocaleString(T.locale) : '-'; }
  function mb(bytes) {
    return typeof bytes === 'number'
      ? (bytes / MB).toLocaleString(T.locale, { maximumFractionDigits: 1 }) : '-';
  }
  function gb(bytes) {
    return typeof bytes === 'number'
      ? (bytes / GB).toLocaleString(T.locale, { maximumFractionDigits: 1 }) : '-';
  }
  function seconds(value) { return num(value) + ' ' + T.sec; }
  function size(value) { return mb(value) + ' ' + T.mb; }

  document.querySelectorAll('.origin').forEach(function (el) { el.textContent = window.location.host; });
  set('example', JSON.stringify({
    mcpServers: {
      'mcp-sql': {
        url: window.location.origin + '/mcp/' + T.dbName,
        headers: { Authorization: 'Bearer ' + T.readKey }
      }
    }
  }, null, 2));

  var LIMITS = [
    ['maxWorkspaces', num],
    ['maxDbBytes', size],
    ['uploadsPerDay', num],
    ['writeBytesPerDay', size],
    ['patchIntervalSeconds', seconds],
    ['requestsPerDay', num],
    ['keyIssuesPerDay', num]
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
      set('generated', T.asOf + new Date(data.generatedAt).toLocaleString(T.locale));
    }
    var limits = data.limits || {};
    var body = $('limits');
    body.textContent = '';
    LIMITS.forEach(function (row) {
      if (limits[row[0]] === undefined) { return; }
      var tr = document.createElement('tr');
      var name = document.createElement('td');
      var value = document.createElement('td');
      name.textContent = T.limits[row[0]];
      value.textContent = row[1](limits[row[0]]);
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
