# Verstappen Race Dash

An Android 12+ fan dashboard with resizable home-screen widgets and an
adaptive in-app race-control view.

## Widgets

Long-press an empty area of the Android home screen, open **Widgets**, find
**Verstappen Race Dash**, and add the layout that fits:

- **Compact (2 × 2):** next session, status, countdown, and the next two start times.
- **Wide (4 × 2):** race details, track outline, and weekend session start times.
- **Large (4 × 4):** expanded race card, circuit map, countdown, and timed weekend schedule.
- **Weekend Schedule (2 × 4):** a vertical, hour-by-hour list of each weekend
  session in Kyiv time, with the current or next session highlighted.

These are separate home-screen widget choices, so you can add more than one
size at the same time. Each keeps its intended layout rather than changing shape
when resized. The wide widget can be resized vertically to make more room for
the schedule. The compact widget shows the next two sessions; wider layouts
show session names in Ukrainian with dates and Kyiv start times. The vertical
schedule uses separate rows and highlights the current or next session.

## Live data and time

- The app has separate **HOME** and **LIVE TIMING** sections. Live Timing uses
  F1's unofficial SignalR stream for session, driver, timing, weather, and
  race-control updates. Track Map shows selectable live car markers from the
  F1 position stream when available. OpenF1 remains the historical-data
  fallback for positions, tyre stints, pit stops, team radio, and GPS locations.
  Completed sessions are clearly marked **FINAL**.
  The latest successful response is cached on-device and marked **CACHED**
  when the network is unavailable. Unsupported or unavailable data is shown as
  unavailable rather than being fabricated.
- Session calendar: [Jolpica](https://jolpi.ca/), refreshed when the app opens
  and periodically in the background (WorkManager checks every 15 minutes;
  Android may defer background work to save battery).
- Circuit geometry: [f1-circuits](https://github.com/SergioSediq/f1-circuits),
  mapped from Jolpica circuit IDs.
- The dashboard formats session times with JavaScript
  `Intl.DateTimeFormat` and the IANA zone `Europe/Kyiv`. Native home-screen
  widgets use Android's IANA time-zone database for the same zone.
- Session dates come from Jolpica. Session end times are estimated as one hour
  for track sessions and two hours for races; the app does not infer results
  or victories.
- The dashboard uses a clearly labelled archive photo of Max Verstappen on the
  podium after his verified 2019 Brazilian GP win as its hero image for every
  race. The archive label makes clear that it is not a photo of the current
  Grand Prix or a claim about its result. The track layout is rendered as a
  compact PNG so it fills its card without excess empty space.

The schedule and circuit feeds require internet access. Previously fetched
sessions remain available to widgets when offline. Android does not permit
home-screen widgets to request a guaranteed one-minute update interval;
background refreshes are best effort and may be deferred by battery controls.

## Build

Build with JDK 17 and Android SDK 35. The Gradle wrapper is included, so no
separate Gradle installation is needed:

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To submit
the source to a ZIP-to-APK service, zip the project contents with
`settings.gradle.kts` at the archive root; do not include `.gradle` or `build`
directories. The project requires Android SDK 35 and Android Gradle Plugin
8.9.2.

## Third-party assets and data

### Helmet artwork

“Max Verstappen Integralhelm 2021” by Auge=mit, Wikimedia Commons,
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/):
https://commons.wikimedia.org/wiki/File:Max_Verstappen_Integralhelm_2021.png

### São Paulo archive photo

“Fórmula 1 – Grande Prêmio do Brasil de F1 2019 (49080039653)” by Governo do
Estado de São Paulo, [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/):
https://commons.wikimedia.org/wiki/File:F%C3%B3rmula_1_%E2%80%93_Grande_Pr%C3%AAmio_do_Brasil_de_F1_2019_(49080039653).jpg

### Red Bull RB19 on-track photo

“FIA F1 Austria 2023 Nr. 1 (1)” by Lukas Raich,
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/):
https://commons.wikimedia.org/wiki/File:FIA_F1_Austria_2023_Nr._1_(1).jpg

### Circuit data

The circuit dataset is used under the MIT License:
https://github.com/SergioSediq/f1-circuits

Copyright (c) 2019-2025 Tomislav Bacinger

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

### Schedule API

Session schedule data comes from [Jolpica](https://jolpi.ca/), a community
continuation of the Ergast Developer API. This fan
application is unofficial and is not affiliated with or endorsed by Formula
One or any racing team.

### Live timing sources

Live Timing connects directly to F1's unofficial, undocumented SignalR stream
at [`wss://livetiming.formula1.com/signalrcore`](https://github.com/theOehrly/Fast-F1/blob/main/fastf1/livetiming/client.py).
The app obtains the load-balancer cookie, subscribes to timing topics, and
reconnects with backoff. The feed is free to access but is not a supported or
guaranteed public API; it can change, return partial data, or have no live
session to show. OpenF1 remains the historical-data fallback; its real-time API
requires a paid subscription. Jolpica supplies the race calendar, not telemetry.
