# Third-party notices

## Max Verstappen helmet image

“Max Verstappen Integralhelm 2021” by Auge=mit, from Wikimedia Commons:
https://commons.wikimedia.org/wiki/File:Max_Verstappen_Integralhelm_2021.png

Licensed under Creative Commons Attribution-ShareAlike 4.0 International
(CC BY-SA 4.0): https://creativecommons.org/licenses/by-sa/4.0/

The image is distributed unmodified. It is a reusable driver-helmet fallback,
not a photograph from a particular Grand Prix.

## São Paulo Grand Prix victory photo

“Fórmula 1 – Grande Prêmio do Brasil de F1 2019 (49080039653)” by Governo do
Estado de São Paulo:
https://commons.wikimedia.org/wiki/File:F%C3%B3rmula_1_%E2%80%93_Grande_Pr%C3%AAmio_do_Brasil_de_F1_2019_(49080039653).jpg

Licensed under Creative Commons Attribution 2.0 Generic (CC BY 2.0):
https://creativecommons.org/licenses/by/2.0/

The archival 2019 podium image is displayed only for the São Paulo Grand Prix.
Max Verstappen's win in the 2019 Brazilian Grand Prix is verified in the
race-result data; the archival image does not imply a result for a future race.

## Red Bull RB19 race-car photo

“FIA F1 Austria 2023 Nr. 1 (1)” by Lukas Raich:
https://commons.wikimedia.org/wiki/File:FIA_F1_Austria_2023_Nr._1_(1).jpg

Licensed under Creative Commons Attribution-ShareAlike 4.0 International
(CC BY-SA 4.0): https://creativecommons.org/licenses/by-sa/4.0/

The vehicle is shown and captioned as a 2023 RB19 photograph from the Austrian
Grand Prix; it is not presented as a photograph of the São Paulo event.

## Victory gallery photos (app/src/main/assets/victory-gallery/)

The gallery loads Max Verstappen's Grand Prix wins and current-season standings
from Jolpica. It displays the latest eight wins; a local photo is used only
when it is an archive image for that exact Grand Prix:

- "Verstappen Spain 2016.jpg" by Lala_77 — Spain 2016, CC BY-SA 2.0:
  https://commons.wikimedia.org/wiki/File:Verstappen_Spain_2016.jpg
- "Max Verstappen 2017 Malaysia 3.jpg" — Malaysia 2017, CC BY-SA
- "Fórmula 1 – Grande Prêmio do Brasil de F1 2019 (49080039653).jpg" — São Paulo 2019 podium, CC BY 2.0
- "FIA F1 Austria 2022 Podium Race (1).jpg" — Austria 2022, CC BY-SA
- "2022 Japanese Grand Prix Award Ceremony 1.jpg" — Japan 2022, CC BY-SA
- "Max Verstappen 2024 Chinese GP.jpg" — China 2024, CC BY-SA

Wins without a matching photo show a neutral card rather than an unrelated
image. Exact author and license per file are on its Wikimedia Commons file page.
The bundled but currently unused gallery images also retain their attribution:
"2019 Formula 1 Hungarian GP podium.jpg" (CC BY-SA 4.0) depicts Verstappen
finishing second in Hungary 2019, and "Max Verstappen (Austin 2021) (cropped).jpg"
(CC BY-SA 4.0) depicts the 2021 United States Grand Prix, not France.
`download-victories.ps1` resolves each Commons file title and license through
the Wikimedia Commons API before downloading the thumbnail.

## Circuit layout data

Circuit geometry is loaded from Sergio Sediq’s `f1-circuits` dataset:
https://github.com/SergioSediq/f1-circuits

The dataset is licensed under the MIT License. Copyright (c) 2019-2025
Tomislav Bacinger. Its MIT license is reproduced in the project README.

## Session schedule

Session names and scheduled dates are retrieved from the Jolpica API:
https://jolpi.ca/. The application is unofficial and is not affiliated with
or endorsed by Formula One or any racing team.
