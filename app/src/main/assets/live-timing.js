(() => {
  "use strict";

  const API = "https://api.openf1.org/v1";
  const CACHE_KEY = "verstappen-racedash-openf1-v1";
  const $ = (id) => document.getElementById(id);
  const state = { session: null, data: null, tab: "leaderboard", timer: 0, loading: false };
  let requestQueue = Promise.resolve();
  let lastRequestAt = 0;
  const colorFor = (driver) => {
    const color = String(driver.team_colour || "").replace(/^#/, "");
    return /^[0-9a-f]{6}$/i.test(color) ? `#${color}` : "#8595a8";
  };
  const dateValue = (value) => {
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : 0;
  };

  async function fetchJson(path) {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 12000);
    try {
      const response = await fetch(`${API}/${path}`, {
        cache: "no-store",
        signal: controller.signal,
        headers: { Accept: "application/json" }
      });
      if (!response.ok) throw new Error(`OpenF1 returned HTTP ${response.status}.`);
      const data = await response.json();
      if (!Array.isArray(data)) throw new Error("OpenF1 returned an unexpected response.");
      return data;
    } finally {
      window.clearTimeout(timer);
    }
  }

  function getJson(path) {
    const request = requestQueue.then(async () => {
      const delay = Math.max(0, 400 - (Date.now() - lastRequestAt));
      if (delay) await new Promise((resolve) => window.setTimeout(resolve, delay));
      lastRequestAt = Date.now();
      return fetchJson(path);
    });
    requestQueue = request.then(() => undefined, () => undefined);
    return request;
  }

  function readCache() {
    try {
      const parsed = JSON.parse(localStorage.getItem(CACHE_KEY) || "null");
      return parsed && Array.isArray(parsed.sessions) ? parsed : null;
    } catch (error) {
      console.warn("Could not read the Live Timing cache.", error);
      return null;
    }
  }

  function saveCache(value) {
    try {
      localStorage.setItem(CACHE_KEY, JSON.stringify({ ...value, savedAt: Date.now() }));
    } catch (error) {
      console.warn("Could not save the Live Timing cache.", error);
    }
  }

  function chooseSession(sessions, now) {
    const sorted = sessions.filter((item) => item.date_start && item.date_end)
      .sort((a, b) => dateValue(a.date_start) - dateValue(b.date_start));
    const active = sorted.find((item) => dateValue(item.date_start) <= now && dateValue(item.date_end) >= now);
    const last = sorted.filter((item) => dateValue(item.date_end) < now).at(-1) || null;
    const next = sorted.find((item) => dateValue(item.date_start) > now) || null;
    return { selected: active || last, active: Boolean(active), next };
  }

  function requestSessionData(sessionKey) {
    const endpoints = {
      drivers: "drivers",
      positions: "position",
      laps: "laps",
      stints: "stints",
      intervals: "intervals",
      weather: "weather",
      controls: "race_control",
      radio: "team_radio",
      pits: "pit"
    };
    return Promise.all(Object.entries(endpoints).map(async ([name, endpoint]) => {
      try {
        return [name, await getJson(`${endpoint}?session_key=${encodeURIComponent(sessionKey)}`)];
      } catch (error) {
        console.info(`OpenF1 ${endpoint} data is unavailable for session ${sessionKey}.`, error);
        return [name, []];
      }
    })).then((entries) => Object.fromEntries(entries));
  }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function setText(id, value) {
    $(id).textContent = value || "—";
  }

  function shortTime(value) {
    const timestamp = dateValue(value);
    return timestamp ? new Intl.DateTimeFormat("en-GB", {
      hour: "2-digit", minute: "2-digit", second: "2-digit", timeZone: "UTC"
    }).format(timestamp) : "—";
  }

  function duration(seconds) {
    const value = Number(seconds);
    if (!Number.isFinite(value) || value <= 0) return "—";
    const minutes = Math.floor(value / 60);
    return `${minutes ? `${minutes}:` : ""}${String(Math.floor(value % 60)).padStart(minutes ? 2 : 1, "0")}.${String(Math.floor((value % 1) * 1000)).padStart(3, "0")}`;
  }

  function latestByDriver(records, driverKey = "driver_number", timestampKey = "date") {
    const result = new Map();
    records.slice().sort((a, b) => dateValue(a[timestampKey]) - dateValue(b[timestampKey]))
      .forEach((item) => result.set(String(item[driverKey]), item));
    return result;
  }

  function renderHeader(session, live, data) {
    const meeting = session.meeting_name || session.location || session.country_name || "Formula 1";
    setText("liveGrandPrix", meeting);
    setText("liveCircuit", [session.circuit_short_name, session.country_name].filter(Boolean).join(" · "));
    setText("liveSessionType", session.session_name || session.session_type || "—");
    const badge = $("liveStatus");
    badge.textContent = live ? "LIVE" : "LAST SESSION";
    badge.dataset.live = String(live);

    const laps = data.laps || [];
    const currentLap = laps.reduce((maximum, lap) => Math.max(maximum, Number(lap.lap_number) || 0), 0);
    const lapTotal = Number(session.total_laps);
    setText("liveLap", currentLap ? `${currentLap}${lapTotal > 0 ? ` / ${lapTotal}` : ""}` : "—");

    const weather = (data.weather || []).slice().sort((a, b) => dateValue(a.date) - dateValue(b.date)).at(-1);
    setText("liveWeather", weather
      ? `${weather.track_temperature == null ? "—" : `${weather.track_temperature}°`} / ${weather.air_temperature == null ? "—" : `${weather.air_temperature}°`}C`
      : "—");
    $("liveNextGp").hidden = live;
  }

  function renderLeaderboard(data) {
    const panel = $("livePanel");
    panel.replaceChildren();
    const drivers = data.drivers || [];
    if (!drivers.length) {
      panel.appendChild(el("div", "live-empty", "No driver records are available from OpenF1 for this session."));
      return;
    }
    const positions = latestByDriver(data.positions || []);
    const laps = latestByDriver(data.laps || [], "driver_number", "date_start");
    const intervals = latestByDriver(data.intervals || []);
    const stints = latestByDriver(data.stints || [], "driver_number", "date_start");
    const records = drivers.map((driver) => ({
      driver,
      position: positions.get(String(driver.driver_number)),
      lap: laps.get(String(driver.driver_number)),
      interval: intervals.get(String(driver.driver_number)),
      stint: stints.get(String(driver.driver_number))
    })).sort((a, b) => {
      const posA = Number(a.position?.position) || Number.MAX_SAFE_INTEGER;
      const posB = Number(b.position?.position) || Number.MAX_SAFE_INTEGER;
      return posA - posB || Number(a.driver.driver_number) - Number(b.driver.driver_number);
    });

    const heading = el("div", "live-panel-heading");
    heading.append(el("strong", "", "SESSION CLASSIFICATION"), el("span", "", `${records.length} DRIVERS · OPENF1`));
    const scroll = el("div", "live-table-scroll");
    const table = el("table", "live-table");
    const thead = document.createElement("thead");
    const headerRow = document.createElement("tr");
    ["POS", "DRIVER", "TEAM", "GAP", "LAST LAP", "TYRE"].forEach((label) => headerRow.appendChild(el("th", "", label)));
    thead.appendChild(headerRow);
    const tbody = document.createElement("tbody");
    records.forEach(({ driver, position, lap, interval, stint }, index) => {
      const row = document.createElement("tr");
      if (Number(position?.position) === 1) row.className = "leader";
      const positionCell = el("td", "live-position", position?.position ? String(position.position) : "—");
      const driverCell = el("td");
      const driverLockup = el("div", "live-driver");
      const stripe = el("i", "team-stripe");
      stripe.style.setProperty("--team-color", colorFor(driver));
      driverLockup.append(stripe, el("span", "", driver.name_acronym || driver.broadcast_name || `#${driver.driver_number}`));
      driverCell.appendChild(driverLockup);
      const team = el("td", "live-team", driver.team_name || "—");
      const gapValue = interval?.gap_to_leader;
      const gap = el("td", "live-gap", Number(position?.position) === 1
        ? "LEADER"
        : gapValue !== undefined && gapValue !== null && gapValue !== ""
          ? (Number.isFinite(Number(gapValue)) ? `+${Number(gapValue).toFixed(3)}s` : String(gapValue))
          : "—");
      const lastLap = el("td", "", duration(lap?.lap_duration));
      const compound = String(stint?.compound || "").toUpperCase();
      const tyreCell = el("td");
      const tyre = el("span", `tyre-chip tyre-${compound.toLowerCase() || "unknown"}`,
        compound ? compound.slice(0, 1) : "—");
      tyre.title = compound ? `${compound}${stint.tyre_age ? ` · lap ${stint.tyre_age}` : ""}` : "Tyre data unavailable";
      tyreCell.appendChild(tyre);
      row.append(positionCell, driverCell, team, gap, lastLap, tyreCell);
      tbody.appendChild(row);
    });
    table.append(thead, tbody);
    scroll.appendChild(table);
    panel.append(heading, scroll);
  }

  function renderEvents(data) {
    const panel = $("livePanel");
    panel.replaceChildren();
    const events = [];
    (data.controls || []).forEach((item) => {
      events.push({
        date: item.date,
        text: [item.flag, item.category, item.message].filter(Boolean).join(" · "),
        detail: item.driver_number ? `Driver ${item.driver_number}${item.scope ? ` · ${item.scope}` : ""}` : (item.scope || "")
      });
    });
    (data.pits || []).forEach((item) => {
      events.push({
        date: item.date,
        text: `Pit stop${item.pit_duration ? ` · ${Number(item.pit_duration).toFixed(1)}s` : ""}`,
        detail: `Driver ${item.driver_number}${item.lap_number ? ` · Lap ${item.lap_number}` : ""}`
      });
    });

    const byDriver = new Map();
    (data.positions || []).slice().sort((a, b) => dateValue(a.date) - dateValue(b.date)).forEach((item) => {
      const key = String(item.driver_number);
      const previous = byDriver.get(key);
      if (previous && Number(previous.position) !== Number(item.position)) {
        events.push({
          date: item.date,
          text: `Position change · P${previous.position} → P${item.position}`,
          detail: `Driver ${item.driver_number}`
        });
      }
      byDriver.set(key, item);
    });
    events.sort((a, b) => dateValue(b.date) - dateValue(a.date));
    const heading = el("div", "live-panel-heading");
    heading.append(el("strong", "", "SESSION EVENTS"), el("span", "", "SOURCE DATA ONLY"));
    panel.appendChild(heading);
    if (!events.length) {
      panel.appendChild(el("div", "live-empty", "OpenF1 has not provided event records for this session. No events have been inferred."));
      return;
    }
    events.slice(0, 80).forEach((item) => {
      const row = el("div", "live-event");
      row.append(el("time", "", shortTime(item.date)));
      const copy = el("div", "", item.text || "Session event");
      if (item.detail) copy.appendChild(el("small", "", item.detail));
      row.appendChild(copy);
      panel.appendChild(row);
    });
  }

  function makeCircuitPath(geoJson) {
    const feature = geoJson.features?.find((item) => item.geometry?.type === "LineString")
      || geoJson.features?.[0];
    let coordinates = feature?.geometry?.coordinates;
    if (feature?.geometry?.type === "MultiLineString") coordinates = coordinates?.[0];
    if (!Array.isArray(coordinates) || coordinates.length < 3) throw new Error("Circuit outline is unavailable.");
    const points = coordinates.filter((point) =>
      Array.isArray(point) && Number.isFinite(Number(point[0])) && Number.isFinite(Number(point[1])));
    if (points.length < 3) throw new Error("Circuit outline is incomplete.");
    const xs = points.map((point) => Number(point[0]));
    const ys = points.map((point) => Number(point[1]));
    const minX = Math.min(...xs), maxX = Math.max(...xs);
    const minY = Math.min(...ys), maxY = Math.max(...ys);
    const width = 400, height = 250, pad = 24;
    const scale = Math.min((width - pad * 2) / (maxX - minX || 1), (height - pad * 2) / (maxY - minY || 1));
    const offsetX = (width - (maxX - minX) * scale) / 2;
    const offsetY = (height - (maxY - minY) * scale) / 2;
    const mapped = points.map((point) => [
      offsetX + (Number(point[0]) - minX) * scale,
      height - offsetY - (Number(point[1]) - minY) * scale
    ]);
    return {
      path: mapped.map(([x, y], index) => `${index ? "L" : "M"}${x.toFixed(1)},${y.toFixed(1)}`).join(" ") + " Z",
      bounds: { minX, maxX, minY, maxY, width, height, pad, scale, offsetX, offsetY }
    };
  }

  async function loadLocations(session) {
    const end = state.active
      ? Date.now()
      : dateValue(session.date_end);
    const start = Math.max(dateValue(session.date_start), end - 45_000);
    const query = new URLSearchParams({
      session_key: session.session_key,
      "date>=": new Date(start).toISOString(),
      "date<=": new Date(end).toISOString()
    });
    return getJson(`location?${query.toString()}`);
  }

  function renderMap(data, geoJson, locations) {
    const panel = $("livePanel");
    panel.replaceChildren();
    const heading = el("div", "live-panel-heading");
    heading.append(el("strong", "", state.session.circuit_short_name || "TRACK MAP"),
      el("span", "", locations.length ? "OPENF1 GPS SNAPSHOT" : "CIRCUIT GEOMETRY"));
    const wrap = el("div", "live-map-wrap");
    const svgNS = "http://www.w3.org/2000/svg";
    const geometry = makeCircuitPath(geoJson);
    const svg = document.createElementNS(svgNS, "svg");
    svg.setAttribute("class", "live-map");
    svg.setAttribute("viewBox", "0 0 400 250");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "Circuit outline and available OpenF1 car locations");
    const glow = document.createElementNS(svgNS, "path");
    glow.setAttribute("class", "track-glow");
    glow.setAttribute("d", geometry.path);
    const path = document.createElementNS(svgNS, "path");
    path.setAttribute("class", "track-line");
    path.setAttribute("d", geometry.path);
    svg.append(glow, path);

    const latestLocations = latestByDriver(locations);
    const byNumber = new Map((data.drivers || []).map((driver) => [String(driver.driver_number), driver]));
    const xs = locations.map((item) => Number(item.x)).filter(Number.isFinite);
    const ys = locations.map((item) => Number(item.y)).filter(Number.isFinite);
    if (xs.length && ys.length) {
      const minX = Math.min(...xs), maxX = Math.max(...xs);
      const minY = Math.min(...ys), maxY = Math.max(...ys);
      latestLocations.forEach((location, number) => {
        if (!Number.isFinite(Number(location.x)) || !Number.isFinite(Number(location.y))) return;
        const x = 24 + ((Number(location.x) - minX) / (maxX - minX || 1)) * 352;
        const y = 226 - ((Number(location.y) - minY) / (maxY - minY || 1)) * 202;
        const driver = byNumber.get(number);
        const dot = document.createElementNS(svgNS, "circle");
        dot.setAttribute("class", "car-dot");
        dot.setAttribute("cx", x.toFixed(1));
        dot.setAttribute("cy", y.toFixed(1));
        dot.setAttribute("r", "5");
        dot.setAttribute("fill", colorFor(driver || {}));
        const title = document.createElementNS(svgNS, "title");
        title.textContent = `${driver?.name_acronym || `#${number}`} · OpenF1 GPS ${location.x}, ${location.y}`;
        dot.appendChild(title);
        svg.appendChild(dot);
      });
    }
    const legend = el("div", "map-legend");
    const positions = latestByDriver(data.positions || []);
    Array.from(latestLocations.keys()).forEach((number) => {
      const driver = byNumber.get(number) || {};
      const item = el("span");
      const dot = el("i");
      dot.style.setProperty("--team-color", colorFor(driver));
      item.append(dot, document.createTextNode(`${driver.name_acronym || `#${number}`}${positions.get(number)?.position ? ` · P${positions.get(number).position}` : ""}`));
      legend.appendChild(item);
    });
    wrap.append(svg);
    if (legend.childNodes.length) {
      wrap.append(legend);
    } else {
      wrap.appendChild(el("div", "live-empty", "No real GPS positions are available for this session snapshot."));
    }
    const zoomControls = el("div", "map-legend");
    const zoomIn = el("button", "", "ZOOM +");
    const zoomOut = el("button", "", "ZOOM −");
    [zoomIn, zoomOut].forEach((button) => {
      button.type = "button";
      button.style.cssText = "border:1px solid rgba(132,160,194,.25);border-radius:4px;padding:7px 10px;color:#dce7f4;background:#101927;font-size:9px;font-weight:900";
    });
    let zoom = 1;
    const setZoom = () => {
      const w = 400 / zoom, h = 250 / zoom;
      svg.setAttribute("viewBox", `${(400 - w) / 2} ${(250 - h) / 2} ${w} ${h}`);
    };
    zoomIn.addEventListener("click", () => { zoom = Math.min(zoom * 1.25, 4); setZoom(); });
    zoomOut.addEventListener("click", () => { zoom = Math.max(zoom / 1.25, 1); setZoom(); });
    zoomControls.append(zoomIn, zoomOut);
    wrap.appendChild(zoomControls);
    panel.append(heading, wrap);
  }

  function renderRadio(data) {
    const panel = $("livePanel");
    panel.replaceChildren();
    const heading = el("div", "live-panel-heading");
    heading.append(el("strong", "", "TEAM RADIO"), el("span", "", "OPENF1 RECORDINGS"));
    panel.appendChild(heading);
    const radios = (data.radio || []).slice().sort((a, b) => dateValue(b.date) - dateValue(a.date));
    const recordings = radios.filter((item) => typeof item.recording_url === "string" && item.recording_url.startsWith("https://"));
    if (!recordings.length) {
      panel.appendChild(el("div", "live-empty",
        radios.length
          ? "OpenF1 returned radio event records, but no publicly accessible audio URL. No player is shown."
          : "OpenF1 has not provided publicly accessible team-radio audio for this session. No fake player is shown."));
      return;
    }
    const drivers = new Map((data.drivers || []).map((driver) => [String(driver.driver_number), driver]));
    recordings.forEach((item) => {
      const driver = drivers.get(String(item.driver_number));
      const card = el("div", "radio-card");
      const top = el("div", "radio-card-top");
      top.append(el("span", "", driver?.name_acronym || `Driver ${item.driver_number}`),
        el("time", "", shortTime(item.date)));
      const audio = document.createElement("audio");
      audio.controls = true;
      audio.preload = "none";
      audio.src = item.recording_url;
      card.append(top, audio);
      panel.appendChild(card);
    });
  }

  function setTab(tab) {
    state.tab = tab;
    document.querySelectorAll("[data-live-tab]").forEach((button) => {
      button.setAttribute("aria-selected", String(button.dataset.liveTab === tab));
    });
    if (!state.data) return;
    if (tab === "leaderboard") renderLeaderboard(state.data);
    if (tab === "events") renderEvents(state.data);
    if (tab === "radio") renderRadio(state.data);
    if (tab === "map") loadAndRenderMap();
  }

  async function loadAndRenderMap() {
    const panel = $("livePanel");
    panel.replaceChildren(el("div", "live-empty", "Loading circuit geometry and available GPS records…"));
    try {
      const circuitKey = Number(state.session.circuit_key);
      let raw = window.AndroidF1?.getTrackGeoJson(circuitKey) || "";
      if (!raw) {
        const files = {
          2:"gb-1948",4:"hu-1986",7:"be-1925",9:"us-2012",10:"au-1953",14:"br-1940",
          15:"es-1991",19:"at-1969",22:"mc-1929",23:"ca-1978",39:"it-1922",46:"jp-1962",
          49:"cn-2004",55:"nl-1948",61:"sg-2008",63:"bh-2002",65:"mx-1962",70:"ae-2009",
          144:"az-2016",149:"sa-2021",150:"qa-2004",151:"us-2022",152:"us-2023",153:"es-2026"
        };
        const file = files[circuitKey];
        if (!file) throw new Error("No mapped real circuit geometry is available.");
        const response = await fetch(`https://raw.githubusercontent.com/SergioSediq/f1-circuits/main/circuits/${file}.geojson`);
        if (!response.ok) throw new Error(`Circuit geometry returned HTTP ${response.status}.`);
        raw = await response.text();
      }
      const geoJson = JSON.parse(raw);
      let locations = [];
      try {
        locations = await loadLocations(state.session);
      } catch (error) {
        console.info("OpenF1 GPS coordinates are unavailable for this map.", error);
      }
      renderMap(state.data, geoJson, locations);
    } catch (error) {
      panel.replaceChildren(el("div", "live-empty", `Circuit map unavailable: ${error.message}`));
    }
  }

  async function refreshLive() {
    if (state.loading || !$("liveScreen") || $("liveScreen").hidden) return;
    state.loading = true;
    const cached = readCache();
    try {
      const year = new Date().getUTCFullYear();
      const sessions = await getJson(`sessions?year=${year}`);
      if (!sessions.length) throw new Error(`No OpenF1 sessions are published for ${year}.`);
      const selection = chooseSession(sessions, Date.now());
      if (!selection.selected) throw new Error("OpenF1 has not published a completed or active session yet.");
      state.session = selection.selected;
      state.active = selection.active;
      state.next = selection.next;
      state.data = await requestSessionData(selection.selected.session_key);
      const cacheValue = {
        sessions,
        sessionKey: selection.selected.session_key,
        active: selection.active,
        next: selection.next,
        data: state.data
      };
      saveCache(cacheValue);
      renderHeader(state.session, state.active, state.data);
      $("liveCacheNote").textContent = state.active
        ? "LIVE · OpenF1 refreshes every 30 seconds"
        : `LAST SESSION · ${new Date(dateValue(state.session.date_start)).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric", timeZone: "UTC" })}`;
      setTab(state.tab);
      if (state.timer) clearInterval(state.timer);
      state.timer = window.setInterval(refreshLive, state.active ? 30000 : 5 * 60 * 1000);
    } catch (error) {
      console.warn("Live Timing refresh failed.", error);
      if (cached?.data && cached.sessionKey) {
        state.session = cached.sessions.find((item) => String(item.session_key) === String(cached.sessionKey));
        if (state.session) {
          state.data = cached.data;
          state.active = false;
          state.next = cached.next || null;
          renderHeader(state.session, false, state.data);
          $("liveStatus").textContent = "CACHED";
          $("liveCacheNote").textContent = `CACHED · ${new Date(cached.savedAt || Date.now()).toLocaleString("en-GB")}`;
          $("livePanel").replaceChildren(el("div", "live-empty", "Showing the last saved OpenF1 data. Live updates are unavailable."));
          if (state.timer) clearInterval(state.timer);
          state.timer = window.setInterval(refreshLive, 5 * 60 * 1000);
          return;
        }
      }
      $("liveStatus").textContent = "OFFLINE";
      $("liveStatus").dataset.live = "false";
      $("liveCacheNote").textContent = "No saved OpenF1 session data is available on this device.";
      $("livePanel").replaceChildren(el("div", "live-empty", `Live Timing could not load: ${error.message}. Check your connection and try again.`));
    } finally {
      state.loading = false;
    }
  }

  document.querySelectorAll("[data-screen]").forEach((button) => {
    button.addEventListener("click", () => {
      const target = button.dataset.screen;
      $("homeScreen").hidden = target !== "homeScreen";
      $("liveScreen").hidden = target !== "liveScreen";
      document.querySelectorAll("[data-screen]").forEach((item) => {
        item.setAttribute("aria-current", String(item === button));
      });
      if (target === "liveScreen") refreshLive();
      else if (typeof window.refreshDashboard === "function") window.refreshDashboard();
    });
  });

  document.querySelectorAll("[data-live-tab]").forEach((button) => {
    button.addEventListener("click", () => setTab(button.dataset.liveTab));
  });
  $("liveNextGp").addEventListener("click", (event) => {
    event.preventDefault();
    document.querySelector('[data-screen="homeScreen"]').click();
  });
})();
