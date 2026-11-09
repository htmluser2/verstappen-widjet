(() => {
  "use strict";

  const API = "https://api.openf1.org/v1";
  const CACHE_KEY = "verstappen-racedash-openf1-v1";
  const $ = (id) => document.getElementById(id);
  const state = {
    session: null,
    data: null,
    official: {},
    feedStatus: "DISCONNECTED",
    tab: "leaderboard",
    timer: 0,
    renderTimer: 0,
    pendingTopics: new Set(),
    selectedDriverNumber: null,
    mapGeometry: null,
    mapLocations: [],
    mapSamples: {},
    mapBounds: null,
    mapFrame: 0,
    mapRendered: null,
    loading: false
  };

  // Playback clock for the map dot. Pausing freezes the dot at its last
  // interpolated position; resuming continues from real telemetry time.
  const playback = { paused: false, frozenPoint: null };
  window.raceDashPlayback = {
    get paused() { return playback.paused; },
    pause() { playback.paused = true; if (state.mapFrame) { cancelAnimationFrame(state.mapFrame); state.mapFrame = 0; } },
    resume() { playback.paused = false; playback.frozenPoint = null; if (state.tab === "map" && state.mapRendered) state.mapRendered(); },
    toggle() { this.paused ? this.resume() : this.pause(); }
  };
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

  function f1TimeInSeconds(value) {
    if (value == null) return null;
    const parts = String(value).split(":").map(Number);
    if (!parts.length || parts.some((part) => !Number.isFinite(part))) return null;
    return parts.length === 2 ? parts[0] * 60 + parts[1] : parts[0];
  }

  function officialData() {
    const rawDrivers = state.official.DriverList?.Drivers || state.official.DriverList || {};
    const lines = state.official.TimingData?.Lines || {};
    const appLines = state.official.TimingAppData?.Lines || {};
    const drivers = Object.entries(rawDrivers).map(([key, item]) => ({
      driver_number: item.RacingNumber || key,
      name_acronym: item.Tla || item.BroadcastName || `#${key}`,
      team_name: item.TeamName || "",
      team_colour: item.TeamColour || ""
    }));
    const positions = [];
    const intervals = [];
    const laps = [];
    const stints = [];

    drivers.forEach((driver) => {
      const number = String(driver.driver_number);
      const line = lines[number] || lines[driver.driver_number] || {};
      const position = Number(line.Position);
      if (Number.isFinite(position) && position > 0) {
        positions.push({ driver_number: number, position });
      }

      const interval = line.IntervalToPositionAhead?.Value
        || line.GapToLeader?.Value;
      if (interval != null) {
        intervals.push({ driver_number: number, gap_to_leader: interval });
      }

      const lastLap = f1TimeInSeconds(line.LastLapTime?.Value);
      if (lastLap != null) {
        laps.push({ driver_number: number, lap_duration: lastLap, date_start: Date.now() });
      }

      const stintValues = Object.values(appLines[number]?.Stints || {});
      const stint = stintValues.at(-1);
      if (stint?.Compound) {
        stints.push({
          driver_number: number,
          compound: stint.Compound,
          tyre_age: stint.TotalLaps
        });
      }
    });

    return { drivers, positions, intervals, laps, stints };
  }

  function officialPositionRecords() {
    const payload = state.official["Position.z"];
    if (!payload || typeof payload !== "object") return [];
    const root = payload.Position || payload;
    const entries = root.Entries || root;
    if (!entries || typeof entries !== "object") return [];

    return Object.entries(entries).flatMap(([number, value]) => {
      if (!/^\d+$/.test(number) || !value || typeof value !== "object") return [];
      const rawPoints = value.Entries;
      const points = Array.isArray(rawPoints)
        ? rawPoints
        : rawPoints && typeof rawPoints === "object"
          ? Object.values(rawPoints)
          : [value];
      const point = points.at(-1)?.Position || points.at(-1);
      if (!point || !Number.isFinite(Number(point.X)) || !Number.isFinite(Number(point.Y))) return [];
      return [{
        driver_number: number,
        x: Number(point.X),
        y: Number(point.Y),
        status: point.Status || value.Status || "",
        date: point.Timestamp || point.Utc || ""
      }];
    });
  }

  function officialMessages() {
    const source = state.official.RaceControlMessages || {};
    const messages = source.Messages || source;
    return Array.isArray(messages) ? messages : Object.values(messages);
  }

  function renderOfficialEvents() {
    const panel = $("livePanel");
    panel.replaceChildren();
    const events = officialMessages()
      .filter((item) => item && typeof item === "object" && (item.Message || item.Category))
      .sort((a, b) => dateValue(b.Utc || b.Date || b.Timestamp) - dateValue(a.Utc || a.Date || a.Timestamp));
    const heading = el("div", "live-panel-heading");
    heading.append(el("strong", "", "RACE CONTROL"), el("span", "", "F1 OFFICIAL FEED"));
    panel.appendChild(heading);
    if (!events.length) {
      panel.appendChild(el("div", "live-empty", "No race-control messages have been sent for this session."));
      return;
    }
    events.slice(0, 80).forEach((item) => {
      const row = el("div", "live-event");
      row.append(el("time", "", shortTime(item.Utc || item.Date || item.Timestamp)));
      const copy = el("div", "", [item.Flag, item.Category, item.Message].filter(Boolean).join(" · "));
      if (item.RacingNumber || item.Scope) {
        copy.appendChild(el("small", "", [item.RacingNumber && `Driver ${item.RacingNumber}`, item.Scope].filter(Boolean).join(" · ")));
      }
      row.appendChild(copy);
      panel.appendChild(row);
    });
  }

  function renderOfficialSession(renderPanel = true) {
    const info = state.official.SessionInfo;
    if (!info && !state.official.DriverList) return false;
    if (state.timer) {
      clearInterval(state.timer);
      state.timer = 0;
    }

    const meeting = info?.Meeting || {};
    const circuit = meeting.Circuit || {};
    const statusValue = state.official.SessionStatus;
    const sessionStatus = typeof statusValue === "string"
      ? statusValue
      : statusValue?.Status || statusValue?.Message || "";
    const sessionIsActive = /started|active|green/i.test(sessionStatus);
    const live = sessionIsActive && state.feedStatus === "CONNECTED";
    const final = /finished|finalised|complete/i.test(sessionStatus);
    const badge = $("liveStatus");
    badge.textContent = live ? "LIVE" : final ? "FINAL" : state.feedStatus === "CONNECTED" ? "CONNECTED" : "STALE";
    badge.dataset.live = String(live);
    badge.dataset.state = state.feedStatus.startsWith("ERROR") ? "error" : "";

    setText("liveGrandPrix", meeting.Name || info?.MeetingName || info?.Name || "F1 timing feed");
    setText("liveCircuit", [circuit.ShortName || meeting.Location, meeting.Country?.Name].filter(Boolean).join(" · "));
    setText("liveSessionType", info?.Name || info?.Type || "—");

    const lapCount = state.official.LapCount || {};
    const lines = state.official.TimingData?.Lines || {};
    const completedLaps = Number(lapCount.CurrentLap)
      || Math.max(0, ...Object.values(lines).map((line) => Number(line.NumberOfLaps) || 0));
    const totalLaps = Number(lapCount.TotalLaps || info?.TotalLaps);
    setText("liveLap", completedLaps
      ? `${completedLaps}${totalLaps ? ` / ${totalLaps}` : ""}`
      : "—");

    const weather = state.official.WeatherData || {};
    const track = weather.TrackTemp?.Value ?? weather.TrackTemp;
    const air = weather.AirTemp?.Value ?? weather.AirTemp;
    setText("liveWeather", track != null || air != null
      ? `${track == null ? "—" : `${track}°`} / ${air == null ? "—" : `${air}°`}C`
      : "—");
    $("liveNextGp").hidden = live;
    $("liveCacheNote").textContent = state.feedStatus === "CONNECTED"
      ? `F1 OFFICIAL FEED · ${live ? "LIVE" : final ? "SESSION FINAL" : "CONNECTED"} · UPDATED ${new Date().toLocaleTimeString("en-GB")}`
      : `${state.feedStatus} · LAST F1 DATA ${new Date().toLocaleTimeString("en-GB")}`;

    state.data = officialData();
    state.session = {
      circuit_key: circuit.Key ?? state.session?.circuit_key,
      circuit_short_name: circuit.ShortName || state.session?.circuit_short_name,
      session_key: null
    };
    if (renderPanel) setTab(state.tab);
    return true;
  }

  function updateOfficialTopic(topic, incoming) {
    const previous = state.official[topic];
    if (topic === "SessionInfo" && previous?.Key && incoming?.Key && previous.Key !== incoming.Key) {
      state.official = {};
    }
    if (topic === "TimingData" && incoming && typeof incoming === "object" && incoming.Lines) {
      const lines = { ...(previous?.Lines || {}) };
      Object.entries(incoming.Lines).forEach(([number, line]) => {
        lines[number] = { ...(lines[number] || {}), ...line };
      });
      state.official[topic] = { ...(previous || {}), ...incoming, Lines: lines };
    } else if (topic === "Position.z") {
      const previousPosition = previous?.Position || previous || {};
      const incomingPosition = incoming?.Position || incoming || {};
      const previousEntries = previousPosition.Entries || previousPosition;
      const incomingEntries = incomingPosition.Entries || incomingPosition;
      const mergedEntries = { ...previousEntries };
      Object.entries(incomingEntries).forEach(([number, position]) => {
        if (/^\d+$/.test(number) && position && typeof position === "object") {
          mergedEntries[number] = { ...(mergedEntries[number] || {}), ...position };
        }
      });
      state.official[topic] = {
        ...previous,
        ...incoming,
        Position: {
          ...previousPosition,
          ...incomingPosition,
          Entries: mergedEntries
        }
      };
    } else if (topic === "RaceControlMessages" && incoming?.Messages && previous?.Messages) {
      state.official[topic] = {
        ...previous,
        ...incoming,
        Messages: { ...previous.Messages, ...incoming.Messages }
      };
    } else if (incoming && typeof incoming === "object" && !Array.isArray(incoming) && previous && typeof previous === "object") {
      state.official[topic] = { ...previous, ...incoming };
    } else {
      state.official[topic] = incoming;
    }

    state.pendingTopics.add(topic);
    if (state.renderTimer) return;
    state.renderTimer = window.setTimeout(() => {
      state.renderTimer = 0;
      const changedTopics = new Set(state.pendingTopics);
      state.pendingTopics.clear();
      if (!renderOfficialSession(false)) return;
      if (state.tab === "leaderboard"
        && ["DriverList", "TimingData", "TimingAppData"].some((name) => changedTopics.has(name))) {
        setTab(state.tab);
      } else if (state.tab === "events" && changedTopics.has("RaceControlMessages")) {
        setTab(state.tab);
      } else if (state.tab === "map" && changedTopics.has("Position.z") && state.mapGeometry) {
        renderMap(state.data || officialData(), state.mapGeometry, state.mapLocations);
      }
    }, 350);
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
    badge.dataset.state = "";

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
    heading.append(el("strong", "", "SESSION CLASSIFICATION"),
      el("span", "", `${records.length} DRIVERS · ${state.official.DriverList ? "F1 OFFICIAL" : "OPENF1"}`));
    const scroll = el("div", "live-table-scroll");
    const table = el("table", "live-table");
    const thead = document.createElement("thead");
    const headerRow = document.createElement("tr");
    ["POS", "DRIVER", "GAP", "LAST LAP", "TYRE"].forEach((label) => headerRow.appendChild(el("th", "", label)));
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
      const driverCopy = el("span", "live-driver-copy");
      driverCopy.append(
        el("strong", "", driver.name_acronym || driver.broadcast_name || `#${driver.driver_number}`),
        el("small", "", driver.team_name || "Formula 1")
      );
      driverLockup.append(stripe, driverCopy);
      driverCell.appendChild(driverLockup);
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
      row.append(positionCell, driverCell, gap, lastLap, tyreCell);
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

  // Builds per-driver timestamped position samples so the map dot can be
  // interpolated smoothly between real telemetry frames. No invented data:
  // only records the feed actually delivered are used.
  function buildMapSamples(positionRecords) {
    const samples = {};
    positionRecords.forEach((item) => {
      if (!Number.isFinite(Number(item.x)) || !Number.isFinite(Number(item.y))) return;
      const number = String(item.driver_number);
      if (!samples[number]) samples[number] = [];
      samples[number].push({ t: dateValue(item.date), x: Number(item.x), y: Number(item.y) });
    });
    Object.values(samples).forEach((list) => list.sort((a, b) => a.t - b.t));
    return samples;
  }

  // Linear interpolation between the two telemetry samples that bracket the
  // requested time. Returns null outside the covered range (status shown
  // instead of a fabricated position).
  function interpolateSample(samples, atTime) {
    if (!samples || samples.length < 1) return null;
    if (samples.length === 1) return samples[0];
    if (atTime <= samples[0].t) return samples[0];
    if (atTime >= samples[samples.length - 1].t) return samples[samples.length - 1];
    let low = 0;
    let high = samples.length - 1;
    while (high - low > 1) {
      const mid = (low + high) >> 1;
      if (samples[mid].t <= atTime) low = mid; else high = mid;
    }
    const a = samples[low], b = samples[high];
    const span = b.t - a.t;
    if (span <= 0) return b;
    const f = (atTime - a.t) / span;
    return { t: atTime, x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f };
  }

  function mapProjection(records) {
    const xs = records.map((item) => Number(item.x)).filter(Number.isFinite);
    const ys = records.map((item) => Number(item.y)).filter(Number.isFinite);
    if (!xs.length || !ys.length) return null;
    return {
      minX: Math.min(...xs), maxX: Math.max(...xs),
      minY: Math.min(...ys), maxY: Math.max(...ys)
    };
  }

  function projectPoint(bounds, x, y) {
    return {
      x: 24 + ((x - bounds.minX) / (bounds.maxX - bounds.minX || 1)) * 352,
      y: 226 - ((y - bounds.minY) / (bounds.maxY - bounds.minY || 1)) * 202
    };
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
    const feedPositions = officialPositionRecords()
      .filter((item) => !item.status || /ontrack|pitlane/i.test(item.status));
    const positionRecords = feedPositions.length
      ? feedPositions
      : Array.from(latestByDriver(locations).values());
    const isLive = feedPositions.length > 0;
    const heading = el("div", "live-panel-heading");
    heading.append(el("strong", "", state.session.circuit_short_name || "TRACK MAP"),
      el("span", "", isLive ? "LIVE CAR POSITIONS" : locations.length ? "OPENF1 GPS SNAPSHOT" : "CIRCUIT GEOMETRY"));
    const playbackBar = el("div", "map-playback-bar");
    const playPause = el("button", "map-play-btn", playback.paused ? "▶ RESUME" : "⏸ PAUSE");
    playPause.type = "button";
    playPause.setAttribute("aria-label", playback.paused ? "Resume map dot motion" : "Pause map dot motion");
    playPause.addEventListener("click", () => {
      window.raceDashPlayback.toggle();
      playPause.textContent = window.raceDashPlayback.paused ? "▶ RESUME" : "⏸ PAUSE";
    });
    const status = el("span", "map-playback-status",
      playback.paused ? "ПАУЗА · точка зупинена" : isLive ? "ТЕЛЕМЕТРІЯ F1 · ПОЗИЦІЯ РЕАЛЬНА" : "GPS ЗНІМОК · ОСТАННЯ ВІДОМА ПОЗИЦІЯ");
    playbackBar.append(playPause, status);
    const wrap = el("div", "live-map-wrap");
    const svgNS = "http://www.w3.org/2000/svg";
    const geometry = makeCircuitPath(geoJson);
    const svg = document.createElementNS(svgNS, "svg");
    svg.setAttribute("class", "live-map");
    svg.setAttribute("viewBox", "0 0 400 250");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "Circuit outline and selectable driver positions");
    const glow = document.createElementNS(svgNS, "path");
    glow.setAttribute("class", "track-glow");
    glow.setAttribute("d", geometry.path);
    const path = document.createElementNS(svgNS, "path");
    path.setAttribute("class", "track-line");
    path.setAttribute("d", geometry.path);
    svg.append(glow, path);

    const byNumber = new Map((data.drivers || []).map((driver) => [String(driver.driver_number), driver]));
    const positions = latestByDriver(data.positions || []);
    state.mapSamples = buildMapSamples(positionRecords);
    state.mapBounds = mapProjection(positionRecords);
    let selectedPosition = positionRecords.find((item) =>
      String(item.driver_number) === String(state.selectedDriverNumber)
    );
    if (!selectedPosition && positionRecords.length) {
      selectedPosition = positionRecords[0];
      state.selectedDriverNumber = String(selectedPosition.driver_number);
    }

    // Static competitor dots at their latest real telemetry position.
    if (state.mapBounds) {
      positionRecords.forEach((location) => {
        const number = String(location.driver_number);
        if (number === String(state.selectedDriverNumber)) return;
        const p = projectPoint(state.mapBounds, Number(location.x), Number(location.y));
        const driver = byNumber.get(number);
        const dot = document.createElementNS(svgNS, "circle");
        dot.setAttribute("class", "car-dot");
        dot.setAttribute("cx", p.x.toFixed(1));
        dot.setAttribute("cy", p.y.toFixed(1));
        dot.setAttribute("r", "5");
        dot.setAttribute("fill", colorFor(driver || {}));
        dot.setAttribute("role", "button");
        dot.setAttribute("tabindex", "0");
        dot.setAttribute("aria-label", `${driver?.name_acronym || `Driver ${number}`}${positions.get(number)?.position ? `, position ${positions.get(number).position}` : ""}`);
        const selectDriver = () => {
          state.selectedDriverNumber = number;
          renderMap(data, geoJson, locations);
        };
        dot.addEventListener("click", selectDriver);
        dot.addEventListener("keydown", (event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            selectDriver();
          }
        });
        const title = document.createElementNS(svgNS, "title");
        title.textContent = `${driver?.name_acronym || `#${number}`}${positions.get(number)?.position ? ` · P${positions.get(number).position}` : ""}`;
        dot.appendChild(title);
        svg.appendChild(dot);
      });
    }

    // Animated dot for the selected driver. Moves through real telemetry
    // samples with linear interpolation; pauses freeze it, resumes continue.
    if (selectedPosition && state.mapBounds) {
      const number = String(selectedPosition.driver_number);
      const driver = byNumber.get(number) || {};
      const animated = document.createElementNS(svgNS, "g");
      animated.setAttribute("class", "tracked-dot");
      const halo = document.createElementNS(svgNS, "circle");
      halo.setAttribute("class", "tracked-dot-halo");
      halo.setAttribute("r", "12");
      halo.setAttribute("fill", colorFor(driver));
      const core = document.createElementNS(svgNS, "circle");
      core.setAttribute("class", "tracked-dot-core");
      core.setAttribute("r", "7");
      core.setAttribute("fill", colorFor(driver));
      core.setAttribute("stroke", "#fff");
      core.setAttribute("stroke-width", "2");
      const label = document.createElementNS(svgNS, "text");
      label.setAttribute("class", "tracked-dot-label");
      label.setAttribute("text-anchor", "middle");
      label.setAttribute("dy", "-14");
      label.textContent = driver.name_acronym || `#${number}`;
      animated.append(halo, core, label);
      svg.appendChild(animated);

      const placeAnimated = (point) => {
        if (!point) return;
        const p = projectPoint(state.mapBounds, point.x, point.y);
        animated.setAttribute("transform", `translate(${p.x.toFixed(1)} ${p.y.toFixed(1)})`);
      };

      const samples = state.mapSamples[number] || [];
      const startTime = samples.length ? samples[0].t : dateValue(selectedPosition.date);
      const endTime = samples.length ? samples[samples.length - 1].t : startTime;
      if (samples.length > 1) {
        const tick = () => {
          if (state.tab !== "map") { state.mapFrame = 0; return; }
          if (playback.paused) {
            placeAnimated(playback.frozenPoint);
          } else {
            // Real-time clock: the dot tracks the actual telemetry timeline,
            // clamped to the session range the feed actually delivered.
            const t = Math.min(Date.now(), endTime);
            const point = t < startTime ? null : interpolateSample(samples, t);
            if (point) {
              playback.frozenPoint = point;
              placeAnimated(point);
            }
          }
          state.mapFrame = requestAnimationFrame(tick);
        };
        if (state.mapFrame) cancelAnimationFrame(state.mapFrame);
        state.mapFrame = requestAnimationFrame(tick);
      } else {
        placeAnimated(interpolateSample(samples, Date.now()));
      }
      state.mapRendered = () => renderMap(data, geoJson, locations);
    }

    const legend = el("div", "map-legend");
    positionRecords.forEach((location) => {
      const number = String(location.driver_number);
      const driver = byNumber.get(number) || {};
      const item = el("span");
      const dot = el("i");
      dot.style.setProperty("--team-color", colorFor(driver));
      item.append(dot, document.createTextNode(`${driver.name_acronym || `#${number}`}${positions.get(number)?.position ? ` · P${positions.get(number).position}` : ""}`));
      legend.appendChild(item);
    });
    wrap.append(svg, playbackBar);
    if (legend.childNodes.length) {
      wrap.append(legend);
    } else {
      wrap.appendChild(el("div", "live-empty", "No real GPS positions are available for this session snapshot."));
    }
    if (selectedPosition) {
      const number = String(selectedPosition.driver_number);
      const driver = byNumber.get(number) || {};
      const detail = el("div", "map-driver-detail");
      const stripe = el("i", "map-driver-color");
      stripe.style.setProperty("--team-color", colorFor(driver));
      const copy = el("div", "map-driver-copy");
      copy.append(
        el("strong", "", `${driver.name_acronym || `#${number}`} · ${driver.team_name || "Formula 1"}`),
        el("span", "", `${positions.get(number)?.position ? `P${positions.get(number).position} · ` : ""}${isLive ? "LIVE POSITION" : "LATEST GPS POSITION"}`)
      );
      const videoLink = el("a", "map-video-link", "WATCH ON F1 TV ↗");
      videoLink.href = "https://f1tv.formula1.com/";
      videoLink.target = "_blank";
      videoLink.rel = "noreferrer noopener";
      videoLink.setAttribute("aria-label", `Open F1 TV for ${driver.name_acronym || `driver ${number}`}; camera selection is made on F1 TV`);
      detail.append(stripe, copy, videoLink);
      wrap.append(detail);
    }
    wrap.append(el("div", "map-video-note", "F1 TV may require an active subscription. Choose the onboard camera there; video is not included in the timing feed."));
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
    if (tab === "events") {
      if (state.official.RaceControlMessages) renderOfficialEvents();
      else renderEvents(state.data);
    }
    if (tab === "radio") renderRadio(state.data);
    if (tab === "map") loadAndRenderMap();
  }

  async function loadAndRenderMap() {
    const panel = $("livePanel");
    panel.replaceChildren(el("div", "live-empty", "Loading circuit geometry and available GPS records…"));
    try {
      const circuitKey = Number(state.session?.circuit_key || state.official.SessionInfo?.Meeting?.Circuit?.Key);
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
      if (state.session?.session_key) {
        try {
          locations = await loadLocations(state.session);
        } catch (error) {
          console.info("OpenF1 GPS coordinates are unavailable for this map.", error);
        }
      }
      state.mapGeometry = geoJson;
      state.mapLocations = locations;
      renderMap(state.data || officialData(), state.mapGeometry, state.mapLocations);
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
      if (!renderOfficialSession()) {
        renderHeader(state.session, state.active, state.data);
        $("liveCacheNote").textContent = state.active
          ? "LAST RESORT · OpenF1 refreshes every 30 seconds"
          : `OPENF1 HISTORY · ${new Date(dateValue(state.session.date_start)).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric", timeZone: "UTC" })}`;
        setTab(state.tab);
      }
      if (state.timer) clearInterval(state.timer);
      state.timer = state.feedStatus === "CONNECTED"
        ? 0
        : window.setInterval(refreshLive, state.active ? 30000 : 5 * 60 * 1000);
    } catch (error) {
      console.warn("Live Timing refresh failed.", error);
      if (cached?.data && cached.sessionKey) {
        state.session = cached.sessions.find((item) => String(item.session_key) === String(cached.sessionKey));
        if (state.session) {
          state.data = cached.data;
          state.active = false;
          state.next = cached.next || null;
          if (!renderOfficialSession()) {
            renderHeader(state.session, false, state.data);
            $("liveStatus").textContent = "CACHED";
            $("liveCacheNote").textContent = `CACHED · ${new Date(cached.savedAt || Date.now()).toLocaleString("en-GB")}`;
            $("livePanel").replaceChildren(el("div", "live-empty", "Showing the last saved OpenF1 data. Live updates are unavailable."));
          }
          if (state.timer) clearInterval(state.timer);
          state.timer = state.feedStatus === "CONNECTED"
            ? 0
            : window.setInterval(refreshLive, 5 * 60 * 1000);
          return;
        }
      }
      if (!renderOfficialSession()) {
        $("liveStatus").textContent = state.feedStatus.startsWith("ERROR") ? "OFFLINE" : "CONNECTING";
        $("liveStatus").dataset.live = "false";
        $("liveStatus").dataset.state = state.feedStatus.startsWith("ERROR") ? "error" : "";
        $("liveCacheNote").textContent = state.feedStatus.startsWith("ERROR")
          ? state.feedStatus
          : "Waiting for the free F1 live feed; OpenF1 is unavailable during an active session.";
        $("livePanel").replaceChildren(el("div", "live-empty", `OpenF1 fallback unavailable: ${error.message}. Waiting for the official F1 timing stream.`));
      }
    } finally {
      state.loading = false;
    }
  }

  document.querySelectorAll("[data-screen]").forEach((button) => {
    button.addEventListener("click", () => {
      const target = button.dataset.screen;
      $("homeScreen").hidden = target !== "homeScreen";
      $("liveScreen").hidden = target !== "liveScreen";
      $("watchScreen").hidden = target !== "watchScreen";
      document.querySelectorAll("[data-screen]").forEach((item) => {
        item.setAttribute("aria-current", String(item === button));
      });
      if (target === "liveScreen") refreshLive();
      if (target === "liveScreen") window.AndroidF1?.startLiveFeed();
      else {
        if (state.timer) {
          clearInterval(state.timer);
          state.timer = 0;
        }
        window.AndroidF1?.stopLiveFeed();
        if (state.renderTimer) {
          clearTimeout(state.renderTimer);
          state.renderTimer = 0;
        }
        state.pendingTopics.clear();
        state.official = {};
        if (typeof window.refreshDashboard === "function") window.refreshDashboard();
      }
    });
  });

  document.querySelectorAll("[data-live-tab]").forEach((button) => {
    button.addEventListener("click", () => setTab(button.dataset.liveTab));

    // ================= WATCH SCOUT AI =================
    const ai = {
      busy: false,
      timer: 0,
      log: $("watchAiLog"),
      status: $("watchAiStatus"),
      query: $("watchAiQuery"),
      result: $("watchAiResult"),
      dot: $("watchAiDot"),
      root: $("watchAi")
    };

    const SOURCES = {
      paid: [
        { name: "F1 TV Pro", url: "https://f1tv.formula1.com/", note: "Офіційна трансляція всіх сесій у всьому світі. Коментар українською — Макс Подзігун." },
        { name: "Apple TV (США)", url: "https://tv.apple.com/", note: "Ексклюзивний мовник F1 у США. 7 днів безкоштовного пробного періоду." }
      ],
      free: [
        { name: "F1 Live Timing", url: "https://www.formula1.com/en/f1-live", note: "Офіційна безкоштовна лайв-таблиця з телеметрією для будь-якої сесії." },
        { name: "Офіційний застосунок F1", url: "https://play.google.com/store/apps/details?hl=en_GB&id=com.softpauer.f1timingapp2014.basic", note: "Безкоштовний leaderboard у телефоні." },
        { name: "RTBF Auvio (Бельгія)", url: "https://www.rtbf.be/auvio", note: "Державний мовник, весь вік-енд безкоштовно. Геообмежено." },
        { name: "ORF ON (Австрія)", url: "https://on.orf.at/", note: "Державний мовник, 14 етапів сезону. Геообмежено." },
        { name: "ServusTV ON (Австрія)", url: "https://www.servustv.com/", note: "11 етапів сезону, включно з ГП Австрії. Геообмежено." },
        { name: "SRF (Швейцарія)", url: "https://www.srf.ch/sport/formel-1", note: "Всі сесії трьома мовами. Геообмежено." }
      ],
      grey: [
        { name: "F1 TV Archive", url: "https://f1tv.formula1.com/page/493/archive", note: "Архів минулих сесій. Частково безкоштовний." },
        { name: "Telegram-канали фенів", url: null, note: "Прямі посилання не надаються — високий ризик вірусів і шахрайства." }
      ]
    };

    function aiLog(text, delay) {
      return new Promise((resolve) => {
        window.setTimeout(() => {
          const line = document.createElement("div");
          line.textContent = text;
          ai.log.appendChild(line);
          resolve();
        }, delay);
      });
    }

    function nextSessionFromSchedule() {
      try {
        const raw = window.AndroidF1?.getSchedule();
        if (!raw) return null;
        const data = JSON.parse(raw);
        if (!Array.isArray(data)) return null;
        const now = Date.now();
        const sessions = data
          .filter((s) => s && !s.is_cancelled && s.date_start && s.date_end)
          .map((s) => ({ ...s, startMs: Date.parse(s.date_start), endMs: Date.parse(s.date_end) }))
          .filter((s) => Number.isFinite(s.startMs) && Number.isFinite(s.endMs))
          .sort((a, b) => a.startMs - b.startMs);
        return sessions.find((s) => s.endMs > now) || null;
      } catch { return null; }
    }

    function sessionKind(session) {
      const name = String(session.session_name || "").toLowerCase();
      if (name.includes("practice")) return "practice";
      if (name.includes("quali") || name.includes("shootout")) return "quali";
      if (name.includes("race")) return "race";
      return "other";
    }

    function kindLabel(kind) {
      if (kind === "practice") return "ПРАКТИКА";
      if (kind === "quali") return "КВАЛІФІКАЦІЯ";
      if (kind === "race") return "ГОНКА";
      return "СЕСІЯ";
    }

    function pickFor(kind) {
      const paid = SOURCES.paid[0];
      const freeTiming = SOURCES.free[0];
      const broadcasters = SOURCES.free.slice(2);
      if (kind === "practice") {
        return [
          { ...freeTiming, price: "free", reason: "Найкращий варіант для практики — офіційна телеметрія безкоштовно." },
          { ...paid, price: "paid", reason: "Платно, але з повним відео всіх практик." },
          { ...broadcasters[0], price: "free", reason: "Якщо ти в Бельгії — повна трансляція практики безкоштовно." },
          { ...SOURCES.grey[0], price: "grey", reason: "Архів минулих практик, якщо пропустив наживо." }
        ];
      }
      if (kind === "quali") {
        return [
          { ...paid, price: "paid", reason: "Найнадійніший спосіб дивитись кваліфікацію наживо." },
          { ...freeTiming, price: "free", reason: "Безкоштовний live timing — бачиш часи кола в реальному часі." },
          { ...broadcasters[1], price: "free", reason: "Австрійський мовник показує кваліфікацію безкоштовно." },
          { ...SOURCES.grey[0], price: "grey", reason: "Повтор кваліфікації з архіву." }
        ];
      }
      return [
        { ...paid, price: "paid", reason: "Головна рекомендація — повна гонка наживо з українським коментарем." },
        { ...broadcasters[0], price: "free", reason: "Безкоштовна державна трансляція, якщо ти в Бельгії." },
        { ...broadcasters[3], price: "free", reason: "Швейцарський мовник — гонка безкоштовно." },
        { ...SOURCES.grey[0], price: "grey", reason: "Повтор гонки з офіційного архіву." }
      ];
    }

    async function runWatchAi() {
      if (ai.busy || !ai.root || ai.root.hidden) return;
      ai.busy = true;
      ai.dot.dataset.busy = "true";
      ai.log.replaceChildren();
      ai.result.replaceChildren();
      ai.result.hidden = true;
      const session = nextSessionFromSchedule();
      if (!session) {
        ai.status.textContent = "ПОМИЛКА";
        ai.query.textContent = "Календар недоступний — спробуй пізніше.";
        ai.busy = false;
        ai.dot.dataset.busy = "false";
        return;
      }
      const kind = sessionKind(session);
      const when = new Date(session.startMs).toLocaleString("uk-UA", {
        day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit"
      });
      ai.status.textContent = "АНАЛІЗУЮ…";
      ai.query.textContent = `→ ${kindLabel(kind)}: ${session.location || session.country_name || "ГП"} · ${when}`;
      await aiLog("Читаю календар з офіційного Jolpica фіду…", 500);
      await aiLog(`Визначено найближчу сесію: ${kindLabel(kind)}`, 700);
      await aiLog(`Ключ сесії: ${session.session_key || "—"} · коло: ${session.meeting_key || "—"}`, 600);
      await aiLog("Порівнюю офіційні та безкоштовні джерела…", 700);
      await aiLog("Сортую за надійністю та ціною…", 600);
      await aiLog("Формую рекомендації…", 500);
      const picks = pickFor(kind);
      ai.result.replaceChildren();
      picks.forEach((pick, index) => {
        const node = document.createElement(pick.url ? "a" : "div");
        node.className = "watch-ai-pick";
        node.dataset.price = pick.price;
        if (pick.url) {
          node.href = pick.url;
          node.target = "_blank";
          node.rel = "noreferrer noopener";
        }
        const rank = document.createElement("span");
        rank.className = "watch-ai-rank";
        rank.textContent = String(index + 1);
        const main = document.createElement("div");
        main.className = "watch-ai-main";
        const strong = document.createElement("strong");
        strong.textContent = pick.name;
        const host = document.createElement("span");
        host.className = "watch-ai-host";
        try { host.textContent = pick.url ? new URL(pick.url).hostname.replace(/^www\./, "") : "без посилання"; }
        catch { host.textContent = ""; }
        const desc = document.createElement("span");
        desc.textContent = `${pick.reason} ${pick.note || ""}`.trim();
        main.append(strong, host, desc);
        if (index === 0) {
          const best = document.createElement("span");
          best.className = "watch-ai-best";
          best.textContent = "ТОП ВИБІР";
          main.appendChild(best);
        }
        const badge = document.createElement("em");
        badge.className = "watch-ai-badge";
        badge.textContent = pick.price === "paid" ? "ПЛАТНО" : pick.price === "grey" ? "РИЗИК" : "БЕЗКОШТОВНО";
        node.append(rank, main, badge);
        ai.result.appendChild(node);
      });
      ai.result.hidden = false;
      ai.status.textContent = `ГОТОВО · ${picks.length} ВАРІАНТИ`;  
      ai.dot.dataset.busy = "false";
      ai.busy = false;
    }

    const aiRefresh = $("watchAiRefresh");
    if (aiRefresh) aiRefresh.addEventListener("click", runWatchAi);
    window.runWatchAi = runWatchAi;
  });

  document.querySelectorAll("[data-screen]").forEach((button) => {
    button.addEventListener("click", () => {
      if (button.dataset.screen === "watchScreen") {
        window.setTimeout(() => window.runWatchAi?.(), 250);
      }
    });
  });
  $("liveNextGp").addEventListener("click", (event) => {
    event.preventDefault();
    document.querySelector('[data-screen="homeScreen"]').click();
  });

  window.onF1FeedStatus = (status) => {
    state.feedStatus = String(status || "DISCONNECTED");
    const hasOfficialData = renderOfficialSession();
    if (!hasOfficialData) {
      const badge = $("liveStatus");
      badge.dataset.state = state.feedStatus.startsWith("ERROR") ? "error" : "";
      if (state.feedStatus.startsWith("ERROR")) {
        badge.textContent = "OFFLINE";
        $("liveCacheNote").textContent = state.feedStatus;
      } else if (state.feedStatus === "CONNECTED") {
        badge.textContent = "SYNCING";
        $("liveCacheNote").textContent = "F1 feed connected · waiting for session data";
      } else if (state.feedStatus === "RECONNECTING") {
        badge.textContent = "RETRYING";
        $("liveCacheNote").textContent = "F1 feed disconnected · reconnecting automatically";
      }
    }
  };

  window.onF1FeedMessage = (topic, rawPayload) => {
    try {
      updateOfficialTopic(String(topic), JSON.parse(rawPayload));
    } catch (error) {
      console.warn(`Could not read F1 live topic ${topic}.`, error);
      state.feedStatus = "ERROR: invalid live timing payload";
      $("liveCacheNote").textContent = state.feedStatus;
    }
  };
})();
