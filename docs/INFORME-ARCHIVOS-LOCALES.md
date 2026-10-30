# Informe: archivos locales con el motor de la sonda

Generado por `LocalComicsReportTest` (rama `sonda/archive`) el 2026-10-29.
Máquina: Mac OS X x86_64, 16 núcleos, Java 17.0.20.1, heap máx. 512 MB. junrar 8.1.2 es Java puro: en el Helio G100 irá varias veces más lento, pero las proporciones valen.

Ficheros: 159 (la lista de los que están físicamente en la Mac). Tiempo máximo por fichero: 60 s.

## Resumen

| Formato real (cabecera) | Ficheros | .cbr | .cbz | Abren bien | Fallan |
|---|---|---|---|---|---|
| ZIP | 15 | 7 | 8 | 15 | 0 |
| RAR4 | 141 | 141 | 0 | 139 | 2 |
| RAR5 | 2 | 2 | 0 | 2 | 0 |
| SEVEN_ZIP | 1 | 1 | 0 | 0 | 1 |

- Disfrazados (la extensión miente): 8 — Leyendas de America Novaro/230 Bosque en llamas - Arcano 9.cbr → ZIP; Vidas Ilustres Novaro - COMPLETO - AIT/208 Silvestre Revueltas - Arcano9.cbr → ZIP; Marvel/M4RV3L C0M1CS/Immortal Hulk/Immortal Hulk #6.cbr → ZIP; Marvel/M4RV3L C0M1CS/League of Legends - Zed/Zed #06.cbr → ZIP; Marvel/M4RV3L C0M1CS/Punisher Kill Krew#1-5/Punisher Kill Krew 02.cbr → SEVEN_ZIP; Marvel/M4RV3L C0M1CS/Age Of X/4.- X-Men Legacy #245.cbr → ZIP; Marvel/M4RV3L C0M1CS/Spider Woman #1-8/5.- Spider-Gwen #8.cbr → ZIP; Marvel/House Of M Vol. 1/4.- Iron Man House Of M #1.cbr → ZIP
- Sólidos: 0 (sniffer RAR4_SOLID: 0; junrar `isSolid`: 0).
- Sniffer (byte 10 & 0x08) contra junrar `getMainHeader().isSolid()`: 141 RAR4 comparados, 0 discrepancias.
- Método de compresión de la primera página (RAR4 0x30 guardado … 0x35 máxima; RAR5 0-5; ZIP 0 guardado / 8 deflate): RAR4:0x30 × 23, RAR4:0x33 × 113, RAR4:0x35 × 5, RAR5:0x0 × 2, ZIP:8 × 15
- Ventana (diccionario) máxima: RAR4:1024 KB × 49, RAR4:128 KB × 1, RAR4:2048 KB × 50, RAR4:256 KB × 3, RAR4:4096 KB × 8, RAR4:512 KB × 30, RAR5:1024 KB × 1, RAR5:2048 KB × 1
- Cabeceras rotas toleradas por junrar: 0 en 0 ficheros.

### Fallos

- #38 Marvel/M4RV3L C0M1CS/Biblioteca Marv3l - Doctor Extraño (Forum)/Biblioteca Marvel - Doctor Extraño nº08.cbr (cbr, cabecera RAR4): OutOfMemoryError: Java heap space
- #48 Condorito/Condorito 318 1998.cbr (cbr, cabecera RAR4): OutOfMemoryError: Java heap space
- #86 Marvel/M4RV3L C0M1CS/Punisher Kill Krew#1-5/Punisher Kill Krew 02.cbr (cbr, cabecera SEVEN_ZIP): UnsupportedArchiveException: «Punisher Kill Krew 02.cbr» es un 7z (aunque se llame .cbr o .cb7). Esta versión todavía no lee 7z: llegará con libarchive.

### Tiempos (los que abren bien)

| Formato | n | abrir p50 / p95 ms | p0 p50 / p95 | p(n/2) p50 / p95 | última p50 / p95 | atrás p50 / p95 | todo en orden p50 MB/s (mín.) |
|---|---|---|---|---|---|---|---|
| ZIP | 15 | 2 / 6 | 12 / 21 | 9 / 19 | 9 / 16 | 8 / 17 | 54 (32) |
| RAR4 | 139 | 19 / 96 | 21 / 62 | 22 / 62 | 18 / 49 | 21 / 58 | 24 (7) |
| RAR5 | 2 | 27 / 27 | 7 / 7 | 26 / 26 | 12 / 12 | 14 / 14 | 20 (20) |

## Sólidos: qué hace junrar 8.1.2 y la mitigación

Leído en el bytecode (`javap -c com.github.junrar.Archive`, `extractFile`): en un sólido junrar recuerda la última entrada descomprimida (`lastProcessedFileIndex`). Si se pide una posterior, descomprime las de en medio a un `NullOutputStream`; si se pide una anterior o la misma, llama a `Unpack.init(null)` y vuelve a empezar desde la primera entrada. El «acceso aleatorio en sólidos» de 7.6.0 es correcto pero cuesta O(k) por página: pasar páginas hacia atrás (o en un orden natural distinto del físico) es O(n²).

Como entre los 159 ficheros locales no hay ningún RAR sólido, se midió con un RAR4 sólido escrito por la prueba (60 páginas de 1.0 MB, entradas guardadas). MB leídos = bytes comprimidos que pasa junrar (contados con `UnrarCallback.volumeProgressChanged`), independiente de la máquina:

| Patrón | junrar a pelo, sólido | con caché, sólido | junrar, no sólido |
|---|---|---|---|
| adelante 0→59 | 356 ms · 60 MB leídos | 806 ms · 60 MB leídos | 493 ms · 60 MB leídos |
| atrás 59→0 | 1736 ms · 1830 MB leídos | 651 ms · 60 MB leídos | 270 ms · 60 MB leídos |
| última y luego la primera | 73 ms · 61 MB leídos | 359 ms · 60 MB leídos | 21 ms · 2 MB leídos |
| saltos (0, 30, 5, 59, 29) | 121 ms · 121 MB leídos | 307 ms · 60 MB leídos | 28 ms · 5 MB leídos |

## Tabla por fichero

Tiempos en ms. «p0», «p(n/2)» y «última» = abrir de nuevo y extraer solo esa página (lo que se espera al retomar ahí); «abrir» es del primer intento (incluye la lectura en frío de las cabeceras). «atrás» = la penúltima justo después de la última, en la misma instancia. «MB leídos» = comprimidos que leyó junrar para esa página. «todo» = las n páginas en orden con una instancia nueva.

| # | Fichero | Ext | MB | Cabecera | Tipo | Sólido | Mét. | Págs | Abrir | p0 | p(n/2) | MB leídos | Última | MB leídos | Atrás | MB leídos | Todo (s) | MB/s | Resultado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Condorito/Condorito 335 1999.cbr | cbr | 216.2 | RAR4 | RAR4 | no | 0x33 | 68 | 72 | 479 | 189 | 3.2 | 16 | 3.3 | 77 | 1.2 | 7.6 | 29 | ok |
| 2 | Condorito/Condorito 448 2003.cbr | cbr | 204.0 | RAR4 | RAR4 | no | 0x33 | 68 | 56 | 111 | 98 | 2.9 | 102 | 3.1 | 86 | 2.5 | 7.5 | 27 | ok |
| 3 | Condorito/Condorito 465 2004.cbr | cbr | 192.7 | RAR4 | RAR4 | no | 0x30 | 68 | 58 | 21 | 94 | 2.6 | 97 | 2.8 | 92 | 2.4 | 7.7 | 25 | ok |
| 4 | Death Note/Death Note Tomo 12.cbz | cbz | 150.2 | ZIP | ZIP | — | 8 | 212 | 28 | 31 | 9 | — | 9 | — | 5 | — | 2.5 | 60 | ok |
| 5 | Marvel/M4RV3L C0M1CS/Ir0n M4n - Los Años Perdidos (Forum)/Iron Man - Los Años Perdidos 1 (Forum).cbr | cbr | 88.6 | RAR4 | RAR4 | no | 0x30 | 136 | 95 | 8 | 6 | 0.7 | 12 | 0.4 | 9 | 0.3 | 1.8 | 49 | ok |
| 6 | Marvel/M4RV3L C0M1CS/Superconan/Superconan nº06.cbr | cbr | 87.4 | RAR4 | RAR4 | no | 0x30 | 199 | 152 | 9 | 5 | 0.5 | 13 | 0.2 | 19 | 0.3 | 2.8 | 31 | ok |
| 7 | Marvel/M4RV3L C0M1CS/Dared3vil - El Regreso de Kingpin (Forum)/Daredevil - El Regreso de Kingpin (Forum).cbr | cbr | 78.2 | RAR4 | RAR4 | no | 0x30 | 149 | 106 | 8 | 5 | 0.3 | 5 | 0.5 | 11 | 0.4 | 1.3 | 60 | ok |
| 8 | Marvel/M4RV3L C0M1CS/M4rvel Knights - Hulk 003/Marvel Knights - Hulk 003.cbr | cbr | 76.8 | RAR4 | RAR4 | no | 0x33 | 21 | 25 | 71 | 139 | 3.7 | 67 | 1.7 | 310 | 6.5 | 3.7 | 21 | ok |
| 9 | Naruto/Naruto/Naruto - Tomo 54 (#505-514).cbr | cbr | 74.6 | RAR4 | RAR4 | no | 0x35 | 160 | 98 | 34 | 30 | 0.7 | 21 | 0.5 | 20 | 0.4 | 3.4 | 22 | ok |
| 10 | Marvel/M4RV3L C0M1CS/M4rvel 2099 1-14/Marvel 2099 09.cbr | cbr | 70.2 | RAR4 | RAR4 | no | 0x33 | 131 | 92 | 16 | 19 | 0.5 | 16 | 0.2 | 25 | 0.4 | 3.4 | 22 | ok |
| 11 | Marvel/M4RV3L C0M1CS/Biblioteca Marv3l -  Hulk (Forum)/Biblioteca Marvel - Hulk 21.cbr | cbr | 66.9 | RAR4 | RAR4 | no | 0x30 | 83 | 66 | 8 | 8 | 0.8 | 5 | 0.3 | 7 | 0.8 | 0.5 | 139 | ok |
| 12 | Marvel/M4RV3L C0M1CS/Superconan/Superconan nº07.cbr | cbr | 63.2 | RAR4 | RAR4 | no | 0x33 | 199 | 159 | 11 | 19 | 0.3 | 13 | 0.2 | 9 | 0.2 | 3.2 | 20 | ok |
| 13 | Marvel/M4RV3L C0M1CS/Biblioteca Marv3l - Nova (Forum)/Nova 02.cbr | cbr | 60.9 | RAR4 | RAR4 | no | 0x33 | 164 | 111 | 13 | 6 | 0.4 | 11 | 0.2 | 6 | 0.3 | 1.5 | 41 | ok |
| 14 | Vidas Ilustres Novaro - COMPLETO - AIT/036 Lope de Vega (bis 136) - Carasucia.cbr | cbr | 60.6 | RAR4 | RAR4 | no | 0x33 | 36 | 24 | 59 | 59 | 1.7 | 49 | 1.4 | 51 | 1.4 | 2.5 | 24 | ok |
| 15 | Vidas Ilustres Novaro - COMPLETO - AIT/153 Romain Rolland - Carasucia.cbr | cbr | 60.3 | RAR4 | RAR4 | no | 0x33 | 36 | 24 | 66 | 62 | 1.7 | 62 | 1.6 | 52 | 1.5 | 2.2 | 27 | ok |
| 16 | Vidas Ilustres Novaro - COMPLETO - AIT/033 El maravilloso doctor Paracelso (bis 269) - Carasucia.cbr | cbr | 58.9 | RAR4 | RAR4 | no | 0x33 | 36 | 26 | 50 | 62 | 1.7 | 49 | 1.3 | 53 | 1.6 | 2.2 | 27 | ok |
| 17 | Vidas Ilustres Novaro - COMPLETO - AIT/065 Edward Jenner - Carasucia.cbr | cbr | 58.8 | RAR4 | RAR4 | no | 0x33 | 36 | 28 | 46 | 61 | 1.7 | 41 | 1.1 | 44 | 1.2 | 2.2 | 26 | ok |
| 18 | Death Note/Death Note Tomo 06.cbz | cbz | 58.0 | ZIP | ZIP | — | 8 | 221 | 4 | 13 | 12 | — | 7 | — | 7 | — | 1.6 | 33 | ok |
| 19 | Mujeres Celebres Novaro/Mujeres Celebres 048 Mabel Hubbard - Carasucia.cbr | cbr | 57.6 | RAR4 | RAR4 | no | 0x33 | 36 | 26 | 63 | 68 | 1.6 | 59 | 1.5 | 58 | 1.6 | 2.2 | 26 | ok |
| 20 | Vidas Ilustres Novaro - COMPLETO - AIT/050 Ramon y Cajal - Carasucia.cbr | cbr | 57.4 | RAR4 | RAR4 | no | 0x33 | 36 | 24 | 41 | 54 | 1.6 | 42 | 1.1 | 39 | 1.2 | 2.5 | 23 | ok |
| 21 | Vidas Ilustres Novaro - COMPLETO - AIT/171 Fedor Chaliapin - Carasucia.cbr | cbr | 57.4 | RAR4 | RAR4 | no | 0x33 | 36 | 23 | 51 | 60 | 1.7 | 62 | 1.6 | 45 | 1.1 | 2.6 | 22 | ok |
| 22 | Condorito/Condorito 493 2005.cbr | cbr | 57.1 | RAR4 | RAR4 | no | 0x33 | 67 | 53 | 41 | 41 | 1.0 | 20 | 0.2 | 11 | 0.2 | 2.3 | 25 | ok |
| 23 | Death Note/Death Note Tomo 07.cbz | cbz | 56.6 | ZIP | ZIP | — | 8 | 210 | 5 | 10 | 8 | — | 6 | — | 7 | — | 1.5 | 34 | ok |
| 24 | Vidas Ilustres Novaro - COMPLETO - AIT/122 Sarmiento, patriota y educador - Carasucia.cbr | cbr | 54.7 | RAR4 | RAR4 | no | 0x33 | 36 | 29 | 54 | 57 | 1.7 | 45 | 1.2 | 45 | 1.2 | 2.3 | 23 | ok |
| 25 | Death Note/Death Note Tomo 11.cbz | cbz | 50.7 | ZIP | ZIP | — | 8 | 219 | 2 | 13 | 4 | — | 13 | — | 3 | — | 1.2 | 40 | ok |
| 26 | Death Note/Death Note Tomo 08.cbz | cbz | 50.2 | ZIP | ZIP | — | 8 | 199 | 2 | 6 | 6 | — | 5 | — | 7 | — | 1.4 | 34 | ok |
| 27 | Death Note/Death Note Tomo 04.cbz | cbz | 49.2 | ZIP | ZIP | — | 8 | 207 | 3 | 11 | 5 | — | 2 | — | 4 | — | 1.3 | 35 | ok |
| 28 | Leyendas de America Novaro/230 Bosque en llamas - Arcano 9.cbr | cbr | 45.8 | ZIP | ZIP | — | 8 | 36 | 6 | 9 | 35 | — | 16 | — | 18 | — | 0.9 | 54 | ok |
| 29 | Vidas Ilustres Novaro - COMPLETO - AIT/286 Mozart (bis 051) - Bogey.cbr | cbr | 44.8 | RAR4 | RAR4 | no | 0x30 | 36 | 31 | 7 | 10 | 1.2 | 8 | 0.9 | 8 | 0.8 | 0.3 | 149 | ok |
| 30 | Death Note/Death Note Tomo 02.cbz | cbz | 43.9 | ZIP | ZIP | — | 8 | 201 | 2 | 16 | 8 | — | 4 | — | 6 | — | 1.1 | 35 | ok |
| 31 | Leyendas de America Novaro/135 Cuando los duendes escapan - Figueroa.cbr | cbr | 43.6 | RAR4 | RAR4 | no | 0x33 | 36 | 25 | 34 | 46 | 1.3 | 39 | 1.1 | 36 | 0.8 | 1.7 | 26 | ok |
| 32 | Death Note/Death Note Tomo 10.cbz | cbz | 43.3 | ZIP | ZIP | — | 8 | 184 | 4 | 10 | 8 | — | 3 | — | 10 | — | 1.3 | 32 | ok |
| 33 | Marvel/M4RV3L C0M1CS/Heroes Reborn + Tie-Ins/Heroes Reborn #07.cbr | cbr | 42.8 | RAR4 | RAR4 | no | 0x33 | 36 | 28 | 63 | 50 | 1.3 | 14 | 0.2 | 54 | 1.4 | 1.5 | 28 | ok |
| 34 | Marvel/M4RV3L C0M1CS/Aveng3rs The initiative/Aveng3rs The initiative 20-25.cbr | cbr | 40.2 | RAR4 | RAR4 | no | 0x35 | 145 | 99 | 11 | 26 | 0.3 | 16 | 0.2 | 10 | 0.2 | 2.4 | 20 | ok |
| 35 | Marvel/Spiderman/Spider-man La Última Cacería de Kraven/Spider-man La Última Cacería de Kraven #04.cbr | cbr | 39.5 | RAR4 | RAR4 | no | 0x33 | 23 | 20 | 100 | 58 | 1.7 | 60 | 1.5 | 87 | 2.5 | 1.6 | 27 | ok |
| 36 | Leyendas de America Novaro/380 - Aurelia La Silenciosa (xJaviCollar & Mr.GAG).cbr | cbr | 38.8 | RAR5 | RAR5 | no | 0x0 | 36 | 42 | 16 | 155 | 1.0 | 12 | 1.1 | 481 | 1.1 | 1.9 | 20 | ok |
| 37 | Vidas Ilustres Novaro - COMPLETO - AIT/170 Horacio Quiroga - Arcano9.cbr | cbr | 38.1 | RAR4 | RAR4 | no | 0x33 | 36 | 24 | 38 | 41 | 1.0 | 45 | 1.1 | 47 | 1.2 | 1.6 | 23 | ok |
| 38 | Marvel/M4RV3L C0M1CS/Biblioteca Marv3l - Doctor Extraño (Forum)/Biblioteca Marvel - Doctor Extraño nº08.cbr | cbr | 36.8 | RAR4 | RAR4 | no | 0x33 | 83 | 49 | 9 | 8 | 0.4 | 7 | 0.1 | 15 | 0.4 | — | — | OutOfMemoryError: Java heap space |
| 39 | Marvel/M4RV3L C0M1CS/Archivos Spiderm4n Vol.1 (Forum)/Archivos Spid3rman 2 - El Regreso de los 6 Siniestros.cbr | cbr | 35.8 | RAR4 | RAR4 | no | 0x30 | 144 | 96 | 11 | 4 | 0.2 | 4 | 0.2 | 7 | 0.2 | 0.7 | 52 | ok |
| 40 | Mujeres Celebres Novaro/Mujeres Celebres 088 Greta Garbo - Arcano9 (1).cbr | cbr | 35.2 | RAR4 | RAR4 | no | 0x33 | 36 | 22 | 41 | 36 | 1.0 | 37 | 0.9 | 38 | 0.7 | 1.5 | 25 | ok |
| 41 | Marvel/M4RV3L C0M1CS/Th0r - Relatos de Asgard/Thor - Relatos de Asgard 01.cbr | cbr | 34.7 | RAR4 | RAR4 | no | 0x33 | 51 | 37 | 31 | 22 | 0.6 | 32 | 0.7 | 24 | 0.7 | 1.5 | 24 | ok |
| 42 | Vidas Ilustres Novaro - COMPLETO - AIT/208 Silvestre Revueltas - Arcano9.cbr | cbr | 32.3 | ZIP | ZIP | — | 8 | 36 | 2 | 14 | 12 | — | 12 | — | 17 | — | 0.3 | 112 | ok |
| 43 | Mujeres Celebres Novaro/Mujeres Celebres 086 Murasaki - Arcano9.cbr | cbr | 32.1 | RAR4 | RAR4 | no | 0x30 | 36 | 22 | 9 | 33 | 0.9 | 31 | 0.8 | 19 | 0.5 | 1.3 | 25 | ok |
| 44 | Vidas Ilustres Novaro - COMPLETO - AIT/173 Mijail V. Lomonosov - Arcano9.cbr | cbr | 31.8 | RAR4 | RAR4 | no | 0x33 | 36 | 21 | 33 | 38 | 0.9 | 26 | 0.5 | 28 | 0.6 | 1.3 | 25 | ok |
| 45 | Marvel/M4RV3L C0M1CS/Act0s de Venganz4/Crossovers/Crossovers 14 - Los 4 Fantásticos Vol1 #100.cbr | cbr | 31.5 | RAR4 | RAR4 | no | 0x30 | 94 | 58 | 9 | 7 | 0.5 | 7 | 0.3 | 5 | 0.3 | 0.4 | 76 | ok |
| 46 | Marvel/M4RV3L C0M1CS/D4rk 4ngel y Warheads Vol.1 (Forum)/Dark Angel y Warheads 08.cbr | cbr | 30.5 | RAR4 | RAR4 | no | 0x33 | 50 | 32 | 30 | 23 | 0.7 | 32 | 0.7 | 25 | 0.7 | 1.4 | 22 | ok |
| 47 | Marvel/Superior Iron Man/Superior Iron Man #05.cbr | cbr | 30.2 | RAR4 | RAR4 | no | 0x33 | 22 | 11 | 48 | 73 | 1.8 | 36 | 0.9 | 58 | 1.4 | 1.4 | 24 | ok |
| 48 | Condorito/Condorito 318 1998.cbr | cbr | 29.9 | RAR4 | RAR4 | no | 0x30 | 68 | 46 | 5 | 11 | 0.4 | 11 | 0.4 | 10 | 0.3 | — | — | OutOfMemoryError: Java heap space |
| 49 | Marvel/Civil war/Civil War - Chosing Sides.cbr | cbr | 27.4 | RAR4 | RAR4 | no | 0x33 | 48 | 36 | 34 | 21 | 0.6 | 25 | 0.5 | 21 | 0.5 | 1.4 | 20 | ok |
| 50 | Marvel/Superior Iron Man/Superior Iron Man #03.cbr | cbr | 26.1 | RAR4 | RAR4 | no | 0x33 | 22 | 16 | 34 | 54 | 1.3 | 28 | 0.5 | 34 | 0.8 | 1.2 | 23 | ok |
| 51 | Marvel/M4RV3L C0M1CS/Aventuras Bizarras (Forum)/Aventuras Bizarras 03.cbr | cbr | 25.5 | RAR4 | RAR4 | no | 0x33 | 59 | 37 | 18 | 49 | 1.0 | 33 | 0.4 | 16 | 0.4 | 1.4 | 20 | ok |
| 52 | Naruto/Naruto/Naruto - Tomo 12 (#100-108).cbr | cbr | 23.3 | RAR4 | RAR4 | no | 0x35 | 180 | 113 | 18 | 5 | 0.1 | 6 | 0.1 | 6 | 0.1 | 1.5 | 16 | ok |
| 53 | The Walking Dead/The Walking Dead 2003 #180 (2018).cbr | cbr | 23.3 | RAR4 | RAR4 | no | 0x33 | 29 | 24 | 23 | 29 | 0.8 | 17 | 0.2 | 36 | 0.9 | 0.9 | 26 | ok |
| 54 | Marvel/M4RV3L C0M1CS/Ka-Zar Volumen 1/Ka-Zar El Salvaje 02.cbr | cbr | 22.5 | RAR4 | RAR4 | no | 0x33 | 84 | 58 | 10 | 10 | 0.3 | 16 | 0.3 | 14 | 0.2 | 1.1 | 21 | ok |
| 55 | Leyendas de America Novaro/260 El árbol de la muerte (xCrisLeza).cbr | cbr | 22.5 | RAR5 | RAR5 | no | 0x0 | 36 | 27 | 7 | 26 | 0.6 | 18 | 0.4 | 14 | 0.3 | 1.0 | 23 | ok |
| 56 | Marvel/M4RV3L C0M1CS/Namor Vol.1/Namor el Hombre Submarino 48a por Vicu68 [CRG].cbr | cbr | 22.3 | RAR4 | RAR4 | no | 0x33 | 25 | 17 | 44 | 31 | 0.7 | 23 | 0.4 | 47 | 1.2 | 1.0 | 24 | ok |
| 57 | Marvel/M4RV3L C0M1CS/En Busca de Korvac/En Busca de Korvac 2 de 4 - Thor.cbr | cbr | 22.2 | RAR4 | RAR4 | no | 0x33 | 58 | 37 | 19 | 24 | 0.4 | 12 | 0.4 | 21 | 0.4 | 1.0 | 22 | ok |
| 58 | Vidas Ilustres Novaro - COMPLETO - AIT/106 Amado Nervo - Carasucia.cbr | cbr | 22.0 | RAR4 | RAR4 | no | 0x33 | 36 | 20 | 54 | 24 | 0.6 | 23 | 0.5 | 22 | 0.6 | 1.3 | 17 | ok |
| 59 | Marvel/M4RV3L C0M1CS/Capitán 4mérica - Último Combate (Forum)/Capitán América - Último Combate nº2.cbr | cbr | 21.4 | RAR4 | RAR4 | no | 0x33 | 47 | 33 | 31 | 16 | 0.5 | 23 | 0.4 | 21 | 0.5 | 1.0 | 21 | ok |
| 60 | Marvel/M4RV3L C0M1CS/Black Vortex #1-13/08 - Nova #28 - The Black Vortex Chapter 8.cbr | cbr | 21.3 | RAR4 | RAR4 | no | 0x33 | 23 | 17 | 20 | 34 | 0.8 | 13 | 0.4 | 35 | 0.9 | 0.8 | 29 | ok |
| 61 | Marvel/M4RV3L C0M1CS/Héroes de Alquiler Vol.3/Heroes for Hire - 04.cbr | cbr | 21.3 | RAR4 | RAR4 | no | 0x33 | 25 | 14 | 32 | 34 | 0.9 | 16 | 0.6 | 29 | 0.7 | 0.8 | 26 | ok |
| 62 | Condorito/Condorito 169 1988.cbr | cbr | 21.3 | RAR4 | RAR4 | no | 0x30 | 49 | 28 | 12 | 6 | 0.5 | 4 | 0.4 | 20 | 0.5 | 0.5 | 45 | ok |
| 63 | Marvel/M4RV3L C0M1CS/Shadowland #1-32/25 Shadowland Power Man 04.cbr | cbr | 20.9 | RAR4 | RAR4 | no | 0x33 | 27 | 16 | 38 | 27 | 0.7 | 27 | 0.7 | 35 | 1.0 | 0.8 | 28 | ok |
| 64 | Marvel/M4RV3L C0M1CS/Immortal Hulk/Immortal Hulk #6.cbr | cbr | 20.4 | ZIP | ZIP | — | 8 | 22 | 2 | 15 | 19 | — | 54 | — | 14 | — | 0.4 | 55 | ok |
| 65 | Marvel/M4RV3L C0M1CS/X-M3n Regenesis #1-39/05 Wolverine Vol 04 19.cbr | cbr | 19.7 | RAR4 | RAR4 | no | 0x33 | 22 | 15 | 20 | 35 | 0.9 | 25 | 0.7 | 38 | 0.9 | 0.8 | 25 | ok |
| 66 | Marvel/M4RV3L C0M1CS/Contest of Champions - Combate Final (Forum)/Contest of Champions - Combate Final (Forum).cbr | cbr | 19.6 | RAR4 | RAR4 | no | 0x33 | 129 | 87 | 10 | 7 | 0.1 | 13 | 0.1 | 9 | 0.1 | 2.9 | 7 | ok |
| 67 | The Walking Dead/The Walking Dead 2003 #103 (2012).cbr | cbr | 19.5 | RAR4 | RAR4 | no | 0x33 | 24 | 14 | 12 | 50 | 1.0 | 36 | 0.7 | 39 | 0.9 | 0.9 | 24 | ok |
| 68 | W0rld of W4rcraft/World of Warcraft #15.cbr | cbr | 19.5 | RAR4 | RAR4 | no | 0x33 | 21 | 9 | 34 | 21 | 0.8 | 35 | 0.8 | 23 | 0.7 | 0.9 | 22 | ok |
| 69 | Marvel/M4RV3L C0M1CS/League of Legends - Zed/Zed #06.cbr | cbr | 18.8 | ZIP | ZIP | — | 8 | 25 | 1 | 21 | 14 | — | 12 | — | 13 | — | 0.3 | 66 | ok |
| 70 | Marvel/M4RV3L C0M1CS/M4rvel Saga 85 - Dead No More The Clone Conspiracy (2016 - 2017)/08 Dead No More - The Clone Conspiracy Vol. 1 002.cbr | cbr | 18.6 | RAR4 | RAR4 | no | 0x33 | 20 | 10 | 43 | 42 | 0.9 | 20 | 0.5 | 31 | 0.9 | 0.8 | 24 | ok |
| 71 | Marvel/M4RV3L C0M1CS/M4rvel Saga 55 - Curse of Mutants (2010)/04 Blade.cbr | cbr | 18.4 | RAR4 | RAR4 | no | 0x33 | 26 | 14 | 31 | 22 | 0.8 | 28 | 0.7 | 20 | 0.6 | 0.8 | 24 | ok |
| 72 | Marvel/M4RV3L C0M1CS/M4rvel Saga 85 - Dead No More The Clone Conspiracy (2016 - 2017)/06 The Amazing Spider-Man Vol. 4 020 (www.ElAbueloSawa.com).cbr | cbr | 18.2 | RAR4 | RAR4 | no | 0x33 | 22 | 18 | 31 | 37 | 1.1 | 27 | 0.6 | 19 | 0.6 | 0.9 | 22 | ok |
| 73 | Marvel/M4RV3L C0M1CS/Th4nos Rising #1-5.rar/Thanos Rising - #03.cbr | cbr | 18.2 | RAR4 | RAR4 | no | 0x35 | 25 | 15 | 37 | 24 | 0.8 | 11 | 0.1 | 18 | 0.4 | 0.8 | 23 | ok |
| 74 | DC Comics/Superman/Superman - Origen Secreto/Superman - Origen Secreto #02.cbr | cbr | 18.2 | RAR4 | RAR4 | no | 0x30 | 34 | 26 | 6 | 30 | 0.6 | 12 | 0.4 | 21 | 0.5 | 0.8 | 24 | ok |
| 75 | Marvel/M4RV3L C0M1CS/La Cruzada del Infinito/La_Cruzada_Del_Infinito_02.cbr | cbr | 18.1 | RAR4 | RAR4 | no | 0x30 | 47 | 32 | 16 | 6 | 0.4 | 8 | 0.4 | 10 | 0.4 | 0.3 | 63 | ok |
| 76 | The Walking Dead/The Walking Dead 2003 #140 (2015).cbr | cbr | 17.8 | RAR4 | RAR4 | no | 0x33 | 23 | 18 | 29 | 38 | 0.9 | 15 | 0.5 | 31 | 0.7 | 0.7 | 25 | ok |
| 77 | Marvel/M4RV3L C0M1CS/Spider Island #1-27/16 The Amazing Spider-Man 669.cbr | cbr | 17.8 | RAR4 | RAR4 | no | 0x33 | 31 | 19 | 20 | 23 | 0.6 | 36 | 0.8 | 12 | 0.4 | 0.8 | 23 | ok |
| 78 | Marvel/M4RV3L C0M1CS/Ir0n Patri0t 1-5/#04 Iron Patriot Vol.1 (Marvel Now).cbr | cbr | 17.7 | RAR4 | RAR4 | no | 0x33 | 22 | 15 | 45 | 54 | 1.6 | 25 | 0.5 | 33 | 1.0 | 1.0 | 19 | ok |
| 79 | Marvel/M4RV3L C0M1CS/Falls Of The Hulks #1-27/09 Fall of The Hulks - The Savage She-hulks #01.cbr | cbr | 17.5 | RAR4 | RAR4 | no | 0x35 | 24 | 13 | 32 | 31 | 0.9 | 17 | 0.5 | 24 | 0.5 | 0.8 | 24 | ok |
| 80 | Marvel/M4RV3L C0M1CS/M4rvel Saga 77 - AXIS (2014)/34 Inhuman 09.cbr | cbr | 17.4 | RAR4 | RAR4 | no | 0x33 | 22 | 11 | 25 | 31 | 0.9 | 27 | 0.7 | 40 | 0.8 | 0.8 | 22 | ok |
| 81 | Marvel/Avenger vs X-men/25.- Avengers vs. X-Men - The Avengers #02.cbr | cbr | 17.0 | RAR4 | RAR4 | no | 0x33 | 21 | 14 | 40 | 33 | 1.0 | 35 | 0.9 | 35 | 0.9 | 0.7 | 24 | ok |
| 82 | Marvel/M4RV3L C0M1CS/W4r Of The Kings  #1-42/35 Guardians of the Galaxy 17.cbr | cbr | 16.7 | RAR4 | RAR4 | no | 0x33 | 27 | 23 | 16 | 33 | 0.6 | 15 | 0.5 | 30 | 0.7 | 0.7 | 26 | ok |
| 83 | Marvel/M4RV3L C0M1CS/Kang Dinasty (2001)/12 Avengers Vol3 51.cbr | cbr | 16.6 | RAR4 | RAR4 | no | 0x33 | 28 | 24 | 13 | 34 | 0.6 | 24 | 0.5 | 31 | 0.7 | 0.7 | 26 | ok |
| 84 | Robocop/Robocop de Frank Miller/Frank Miller's Robocop #9.cbr | cbr | 16.1 | RAR4 | RAR4 | no | 0x33 | 25 | 18 | 21 | 34 | 0.6 | 19 | 0.6 | 25 | 0.5 | 0.7 | 23 | ok |
| 85 | Marvel/M4RV3L C0M1CS/Inhumanity 1-22/20 Avengers Assemble 25.cbr | cbr | 16.1 | RAR4 | RAR4 | no | 0x33 | 25 | 23 | 25 | 29 | 0.6 | 11 | 0.3 | 22 | 0.5 | 0.7 | 24 | ok |
| 86 | Marvel/M4RV3L C0M1CS/Punisher Kill Krew#1-5/Punisher Kill Krew 02.cbr | cbr | 15.8 | SEVEN_ZIP | — | — | — | — | — | — | — | — | — | — | — | — | — | — | UnsupportedArchiveException: «Punisher Kill Krew 02.cbr» es un 7z (aunque se llame .cbr o .cb7). Esta versión todavía no lee 7z: llegará con libarchive. |
| 87 | Marvel/M4RV3L C0M1CS/Secret Invasion (2008)/115 Punisher War Journal 24.cbr | cbr | 15.7 | RAR4 | RAR4 | no | 0x33 | 26 | 19 | 11 | 26 | 0.6 | 7 | 0.2 | 14 | 0.2 | 0.6 | 26 | ok |
| 88 | Marvel/M4RV3L C0M1CS/M4rvel Saga 39 - Dark Reign (2009)/264 Invincible Iron Man 15.cbr | cbr | 15.7 | RAR4 | RAR4 | no | 0x33 | 35 | 22 | 21 | 15 | 0.4 | 29 | 0.8 | 9 | 0.2 | 0.8 | 21 | ok |
| 89 | Marvel/M4RV3L C0M1CS/M4rvel Saga 77 - AXIS (2014)/13 All New X-Factor 15.cbr | cbr | 15.5 | RAR4 | RAR4 | no | 0x33 | 21 | 17 | 34 | 33 | 0.7 | 18 | 0.6 | 30 | 0.7 | 0.7 | 25 | ok |
| 90 | Marvel/M4RV3L C0M1CS/Secret Invasion (2008)/122 Secret Invasion 8.cbr | cbr | 15.5 | RAR4 | RAR4 | no | 0x33 | 26 | 22 | 14 | 29 | 0.5 | 20 | 0.5 | 31 | 0.7 | 0.6 | 25 | ok |
| 91 | Marvel/M4RV3L C0M1CS/M4rvel Saga 40 - Manifest Destiny (2008-2009)/36 Uncanny X-men Vol1 507.cbr | cbr | 15.4 | RAR4 | RAR4 | no | 0x33 | 26 | 13 | 34 | 27 | 0.6 | 9 | 0.1 | 22 | 0.7 | 0.7 | 23 | ok |
| 92 | Marvel/M4RV3L C0M1CS/Retorno de Lobezno/Return of the Wolverine 003.cbr | cbr | 15.4 | RAR4 | RAR4 | no | 0x33 | 25 | 18 | 35 | 20 | 0.5 | 20 | 0.3 | 38 | 0.8 | 0.7 | 25 | ok |
| 93 | Marvel/M4RV3L C0M1CS/M4rvel Saga 36 - Brand new day (2008)/01 The Amazing Spider-Man Vol1 546.cbr | cbr | 15.2 | RAR4 | RAR4 | no | 0x30 | 28 | 16 | 8 | 7 | 0.6 | 5 | 0.5 | 5 | 0.6 | 0.2 | 91 | ok |
| 94 | Marvel/M4RV3L C0M1CS/Secret Invasion (2008)/056 Avengers The Initiative 15.cbr | cbr | 15.0 | RAR4 | RAR4 | no | 0x33 | 24 | 20 | 32 | 20 | 0.5 | 21 | 0.5 | 25 | 0.7 | 0.7 | 22 | ok |
| 95 | Marvel/World War Hulk/15.- World War Hulk - X-Men #02.cbr | cbr | 14.9 | RAR4 | RAR4 | no | 0x33 | 24 | 16 | 26 | 28 | 0.7 | 21 | 0.5 | 23 | 0.5 | 0.6 | 24 | ok |
| 96 | Marvel/M4RV3L C0M1CS/D3adpool Back in Black/Deadpool - Back In Black Vol.1 #04.cbr | cbr | 14.7 | RAR4 | RAR4 | no | 0x33 | 21 | 17 | 29 | 22 | 0.5 | 38 | 0.6 | 22 | 0.5 | 0.6 | 26 | ok |
| 97 | Marvel/M4RV3L C0M1CS/Namor Vol.1/Namor el Hombre Submarino 53 por Stanley Lieber & Nacho763 [CRG].cbr | cbr | 14.6 | RAR4 | RAR4 | no | 0x33 | 25 | 22 | 26 | 24 | 0.5 | 18 | 0.4 | 29 | 0.6 | 0.6 | 26 | ok |
| 98 | Marvel/M4RV3L C0M1CS/Age Of X/4.- X-Men Legacy #245.cbr | cbr | 14.6 | ZIP | ZIP | — | 8 | 25 | 2 | 9 | 15 | — | 13 | — | 9 | — | 0.3 | 60 | ok |
| 99 | Marvel/M4RV3L C0M1CS/M4rvel Saga 32 - Messiah Complex (2007)/07 X-Factor 26.cbr | cbr | 14.5 | RAR4 | RAR4 | no | 0x33 | 24 | 19 | 19 | 33 | 0.6 | 13 | 0.3 | 26 | 0.6 | 0.6 | 27 | ok |
| 100 | Marvel/M4RV3L C0M1CS/D4rk 4veng3rs Vol1 (2009-2010)/Dark Avengers Vol1 06.cbr | cbr | 14.4 | RAR4 | RAR4 | no | 0x33 | 25 | 16 | 26 | 22 | 0.6 | 18 | 0.3 | 20 | 0.6 | 0.6 | 26 | ok |
| 101 | Marvel/M4RV3L C0M1CS/M4rvel Saga 85 - Dead No More The Clone Conspiracy (2016 - 2017)/22 Silk Vol. 2 017 (www.ElAbueloSawa.com).cbr | cbr | 14.4 | RAR4 | RAR4 | no | 0x33 | 20 | 12 | 25 | 31 | 0.8 | 22 | 0.6 | 33 | 0.9 | 0.5 | 27 | ok |
| 102 | Leyendas de America Novaro/322 Dos joyas del Mexico colonial - Mja.cbr | cbr | 14.2 | RAR4 | RAR4 | no | 0x33 | 35 | 29 | 22 | 20 | 0.4 | 16 | 0.3 | 16 | 0.4 | 0.7 | 20 | ok |
| 103 | Marvel/M4RV3L C0M1CS/M4rvel Saga 39 - Dark Reign (2009)/146 Thor 602.cbr | cbr | 14.2 | RAR4 | RAR4 | no | 0x33 | 30 | 17 | 25 | 21 | 0.6 | 29 | 0.8 | 20 | 0.4 | 0.6 | 26 | ok |
| 104 | Gears of War Comic Series/G.0.W.#03.cbr | cbr | 13.8 | RAR4 | RAR4 | no | 0x33 | 24 | 19 | 20 | 22 | 0.6 | 22 | 0.6 | 20 | 0.6 | 0.6 | 26 | ok |
| 105 | Marvel/M4RV3L C0M1CS/Reino de los Reyes/25 Realm of Kings Hijo de Hulk 001.cbr | cbr | 13.7 | RAR4 | RAR4 | no | 0x33 | 27 | 16 | 22 | 19 | 0.5 | 10 | 0.1 | 23 | 0.5 | 0.6 | 24 | ok |
| 106 | Marvel/M4RV3L C0M1CS/Feart Itself 1-126/097 Fear Itself Black Widow 01.cbr | cbr | 13.5 | RAR4 | RAR4 | no | 0x33 | 28 | 18 | 23 | 19 | 0.5 | 19 | 0.4 | 21 | 0.4 | 0.6 | 26 | ok |
| 107 | Marvel/M4RV3L C0M1CS/Fantastic Four - La muerte de la Antorcha Humana/583.cbr | cbr | 13.3 | RAR4 | RAR4 | no | 0x33 | 26 | 16 | 25 | 24 | 0.5 | 23 | 0.6 | 16 | 0.4 | 0.5 | 26 | ok |
| 108 | Marvel/Civil war/Civil-War - Frontline #06.cbr | cbr | 13.1 | RAR4 | RAR4 | no | 0x33 | 33 | 22 | 25 | 19 | 0.4 | 13 | 0.4 | 18 | 0.4 | 0.5 | 25 | ok |
| 109 | Marvel/M4RV3L C0M1CS/M4rvel Saga 39 - Dark Reign (2009)/271 Invincible Iron Man 19.cbr | cbr | 13.1 | RAR4 | RAR4 | no | 0x33 | 39 | 26 | 13 | 18 | 0.4 | 21 | 0.5 | 18 | 0.3 | 0.6 | 23 | ok |
| 110 | Marvel/M4RV3L C0M1CS/Wolv3rine Noir/Wolverine Noir #3.cbr | cbr | 12.4 | RAR4 | RAR4 | no | 0x33 | 26 | 17 | 15 | 13 | 0.4 | 13 | 0.1 | 225 | 0.3 | 0.7 | 18 | ok |
| 111 | Marvel/M4RV3L C0M1CS/Spider Woman #1-8/5.- Spider-Gwen #8.cbr | cbr | 11.9 | ZIP | ZIP | — | 8 | 20 | 1 | 12 | 9 | — | 9 | — | 10 | — | 0.2 | 64 | ok |
| 112 | Marvel/M4RV3L C0M1CS/Apocalípsis - Los Doce (Forum)/(Apocalípsis Los Doce 5de8) Lobezno nº057 vol2.cbr | cbr | 11.8 | RAR4 | RAR4 | no | 0x33 | 24 | 20 | 32 | 18 | 0.5 | 20 | 0.4 | 21 | 0.4 | 0.5 | 24 | ok |
| 113 | Marvel/M4RV3L C0M1CS/Act0s de Venganz4/AdV 05 Los Vengadores vol1 097.cbr | cbr | 11.7 | RAR4 | RAR4 | no | 0x33 | 29 | 23 | 62 | 25 | 0.3 | 30 | 0.4 | 45 | 0.4 | 1.1 | 11 | ok |
| 114 | Marvel/M4RV3L C0M1CS/Namor Vol.1/Namor el Hombre Submarino 60 por Master Cosmico y Logan X-Tremo [CRG].cbr | cbr | 11.5 | RAR4 | RAR4 | no | 0x33 | 23 | 19 | 27 | 28 | 0.5 | 18 | 0.4 | 32 | 0.6 | 0.6 | 18 | ok |
| 115 | The Walking Dead/The Walking Dead 2003 #87 (2011).cbr | cbr | 11.5 | RAR4 | RAR4 | no | 0x33 | 24 | 14 | 40 | 21 | 0.5 | 14 | 0.3 | 19 | 0.5 | 0.6 | 20 | ok |
| 116 | Marvel/M4RV3L C0M1CS/PatrullaX Vol.2/021 PatrullaX Vol.2.cbr | cbr | 11.4 | RAR4 | RAR4 | no | 0x33 | 25 | 17 | 28 | 34 | 0.6 | 21 | 0.4 | 29 | 0.4 | 0.7 | 16 | ok |
| 117 | Marvel/M4RV3L C0M1CS/Los Dioses Perdidos/11- Los Dioses Perdidos.cbr | cbr | 11.4 | RAR4 | RAR4 | no | 0x33 | 23 | 13 | 34 | 8 | 0.5 | 7 | 0.5 | 5 | 0.5 | 0.2 | 68 | ok |
| 118 | The Walking Dead/The Walking Dead 2003 #44 (2007).cbr | cbr | 11.2 | RAR4 | RAR4 | no | 0x33 | 24 | 20 | 26 | 30 | 0.5 | 18 | 0.5 | 26 | 0.5 | 0.6 | 19 | ok |
| 119 | Marvel/M4RV3L C0M1CS/Doom 2099 Vol 1/Doom 2099 #39.cbr | cbr | 10.7 | RAR4 | RAR4 | no | 0x30 | 24 | 19 | 10 | 23 | 0.5 | 14 | 0.4 | 22 | 0.4 | 0.3 | 33 | ok |
| 120 | Marvel/M4RV3L C0M1CS/El tiempo se acaba/24 Miles Morales Ultimate Spider-Man 12.cbr | cbr | 10.4 | RAR4 | RAR4 | no | 0x33 | 20 | 17 | 18 | 28 | 0.5 | 9 | 0.2 | 22 | 0.5 | 0.7 | 17 | ok |
| 121 | Marvel/House Of M Vol. 1/4.- Iron Man House Of M #1.cbr | cbr | 10.1 | ZIP | ZIP | — | 8 | 26 | 2 | 3 | 9 | — | 8 | — | 8 | — | 0.2 | 65 | ok |
| 122 | Marvel/M4RV3L C0M1CS/Las Nuevas Aventuras de los X-Men (Forum)/Las Nuevas Aventuras De Los X-Men nº27.cbr | cbr | 9.5 | RAR4 | RAR4 | no | 0x30 | 25 | 16 | 6 | 6 | 0.3 | 15 | 0.4 | 17 | 0.3 | 0.4 | 22 | ok |
| 123 | Marvel/M4RV3L C0M1CS/Las Historias Jamás Contadas de Spiderman (Forum)/Las Historias Jamás Contadas de Spiderman Nº11.cbr | cbr | 9.3 | RAR4 | RAR4 | no | 0x33 | 24 | 22 | 14 | 7 | 0.4 | 10 | 0.4 | 6 | 0.4 | 0.6 | 16 | ok |
| 124 | Marvel/M4RV3L C0M1CS/Las Nuevas Aventuras de Spiderman Vol.2 (Forum)/Las nuevas aventuras de Spiderman vol2 06.cbr | cbr | 9.1 | RAR4 | RAR4 | no | 0x33 | 26 | 19 | 25 | 16 | 0.3 | 21 | 0.3 | 11 | 0.3 | 0.4 | 22 | ok |
| 125 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/255.cbr | cbr | 9.1 | RAR4 | RAR4 | no | 0x33 | 18 | 13 | 21 | 28 | 0.6 | 28 | 0.5 | 31 | 0.5 | 0.6 | 17 | ok |
| 126 | Mujeres Celebres Novaro/Mujeres Celebres 006 Nur-Mahal - Arcano9.cbr | cbr | 8.3 | RAR4 | RAR4 | no | 0x30 | 34 | 29 | 7 | 4 | 0.2 | 4 | 0.2 | 4 | 0.2 | 0.2 | 49 | ok |
| 127 | Mujeres Celebres Novaro/Mujeres Celebres 120 Helen Keller - Doncomic.cbr | cbr | 8.3 | RAR4 | RAR4 | no | 0x33 | 36 | 19 | 19 | 6 | 0.2 | 29 | 0.7 | 24 | 0.5 | 0.4 | 24 | ok |
| 128 | Marvel/M4RV3L C0M1CS/Execute Program/Execute Program #02.cbr | cbr | 8.2 | RAR4 | RAR4 | no | 0x33 | 24 | 20 | 16 | 20 | 0.4 | 12 | 0.3 | 12 | 0.3 | 0.4 | 19 | ok |
| 129 | Marvel/M4RV3L C0M1CS/Héroes de Alquiler Vol.1/Heroes de Alquiler nº06.cbr | cbr | 8.0 | RAR4 | RAR4 | no | 0x33 | 25 | 14 | 17 | 14 | 0.4 | 17 | 0.3 | 22 | 0.3 | 0.4 | 23 | ok |
| 130 | Marvel/M4RV3L C0M1CS/Execute Program/Execute Program #01.cbr | cbr | 7.7 | RAR4 | RAR4 | no | 0x33 | 24 | 17 | 12 | 17 | 0.4 | 18 | 0.3 | 12 | 0.3 | 0.4 | 18 | ok |
| 131 | Marvel/M4RV3L C0M1CS/Generacion X Volumen 1 (1994-2001)/Generation X 21.cbr | cbr | 7.7 | RAR4 | RAR4 | no | 0x33 | 23 | 18 | 13 | 4 | 0.4 | 13 | 0.3 | 17 | 0.3 | 0.3 | 25 | ok |
| 132 | Marvel/M4RV3L C0M1CS/La Patrulla X Vol.1/Patrulla X vol1 072.cbr | cbr | 7.5 | RAR4 | RAR4 | no | 0x33 | 23 | 12 | 18 | 17 | 0.4 | 12 | 0.3 | 16 | 0.3 | 0.3 | 23 | ok |
| 133 | Leyendas de America Novaro/038 EL ESPEJO REVELADOR - N. Bathes.cbr | cbr | 7.3 | RAR4 | RAR4 | no | 0x33 | 37 | 20 | 18 | 10 | 0.2 | 5 | 0.1 | 4 | 0.2 | 0.3 | 27 | ok |
| 134 | Mujeres Celebres Novaro/Mujeres Celebres 001 Amelia Earhart - N. Bathes.cbr | cbr | 7.3 | RAR4 | RAR4 | no | 0x33 | 35 | 22 | 16 | 4 | 0.2 | 6 | 0.1 | 5 | 0.2 | 0.2 | 40 | ok |
| 135 | Marvel/M4RV3L C0M1CS/Doom 2099 Vol 1/Doom 2099 #22.cbr | cbr | 7.2 | RAR4 | RAR4 | no | 0x30 | 23 | 15 | 6 | 6 | 0.3 | 4 | 0.3 | 5 | 0.3 | 0.1 | 56 | ok |
| 136 | Marvel/Spiderman/The Amazing Spiderman 700 Números/95 The Amazing Spider-Man #088.cbr | cbr | 7.0 | RAR4 | RAR4 | no | 0x33 | 20 | 12 | 14 | 20 | 0.4 | 13 | 0.3 | 19 | 0.3 | 0.5 | 16 | ok |
| 137 | Marvel/Spiderman/The Amazing Spiderman 700 Números/300 The Amazing Spider-Man #280.cbr | cbr | 7.0 | RAR4 | RAR4 | no | 0x33 | 23 | 15 | 11 | 14 | 0.3 | 17 | 0.3 | 16 | 0.3 | 0.3 | 23 | ok |
| 138 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/122.cbr | cbr | 7.0 | RAR4 | RAR4 | no | 0x33 | 18 | 16 | 17 | 15 | 0.4 | 26 | 0.4 | 14 | 0.4 | 0.4 | 19 | ok |
| 139 | Marvel/M4RV3L C0M1CS/La Patrulla X Vol.1/Patrulla X vol1 077.cbr | cbr | 6.7 | RAR4 | RAR4 | no | 0x33 | 21 | 11 | 16 | 17 | 0.3 | 11 | 0.3 | 17 | 0.3 | 0.4 | 20 | ok |
| 140 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/116.cbr | cbr | 6.7 | RAR4 | RAR4 | no | 0x33 | 18 | 12 | 14 | 19 | 0.4 | 13 | 0.4 | 19 | 0.4 | 0.3 | 23 | ok |
| 141 | Marvel/M4RV3L C0M1CS/D3adpool Sins of the Past/Sins of the Past #02.cbr | cbr | 6.6 | RAR4 | RAR4 | no | 0x33 | 23 | 19 | 13 | 16 | 0.3 | 9 | 0.3 | 21 | 0.4 | 0.3 | 21 | ok |
| 142 | Marvel/Spiderman/Amazing Spider-man Vol. 2/Amazing Spider-Man #27.cbr | cbr | 6.6 | RAR4 | RAR4 | no | 0x30 | 21 | 20 | 10 | 4 | 0.3 | 5 | 0.3 | 4 | 0.3 | 0.1 | 72 | ok |
| 143 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/227.cbr | cbr | 6.6 | RAR4 | RAR4 | no | 0x33 | 18 | 15 | 20 | 19 | 0.4 | 20 | 0.3 | 17 | 0.4 | 0.4 | 17 | ok |
| 144 | Marvel/M4RV3L C0M1CS/Onslaught/34 - IMPACTO2 - Hulk 016.cbr | cbr | 6.4 | RAR4 | RAR4 | no | 0x33 | 22 | 14 | 31 | 7 | 0.3 | 11 | 0.2 | 17 | 0.3 | 0.4 | 17 | ok |
| 145 | Marvel/Spiderman/Amazing Spider-man Vol. 2/Amazing Spider-Man #56.cbr | cbr | 6.4 | RAR4 | RAR4 | no | 0x30 | 22 | 14 | 9 | 8 | 0.3 | 4 | 0.3 | 5 | 0.2 | 0.1 | 68 | ok |
| 146 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/041.cbr | cbr | 6.2 | RAR4 | RAR4 | no | 0x33 | 18 | 14 | 18 | 17 | 0.3 | 21 | 0.4 | 21 | 0.3 | 0.4 | 16 | ok |
| 147 | Marvel/Spiderman/The Amazing Spiderman 700 Números/22 The Amazing Spider-Man #020.cbr | cbr | 6.2 | RAR4 | RAR4 | no | 0x33 | 21 | 17 | 47 | 12 | 0.3 | 15 | 0.3 | 15 | 0.3 | 0.4 | 17 | ok |
| 148 | Marvel/Spiderman/The Amazing Spiderman 700 Números/3 The Amazing Spider-Man #002.cbr | cbr | 6.0 | RAR4 | RAR4 | no | 0x33 | 28 | 22 | 16 | 18 | 0.2 | 15 | 0.2 | 14 | 0.2 | 0.5 | 12 | ok |
| 149 | Marvel/Spiderman/Amazing Spider-man Vol. 2/Amazing Spider-Man #10.cbr | cbr | 5.9 | RAR4 | RAR4 | no | 0x30 | 23 | 20 | 7 | 5 | 0.2 | 10 | 0.3 | 6 | 0.2 | 0.1 | 50 | ok |
| 150 | Marvel/M4RV3L C0M1CS/Generacion X Volumen 1 (1994-2001)/Generation X 12.cbr | cbr | 5.7 | RAR4 | RAR4 | no | 0x33 | 24 | 22 | 11 | 6 | 0.2 | 5 | 0.2 | 5 | 0.2 | 0.3 | 18 | ok |
| 151 | Marvel/Spiderman/The Amazing Spiderman 700 Números/102 The Amazing Spider-Man #094.cbr | cbr | 5.7 | RAR4 | RAR4 | no | 0x30 | 20 | 12 | 4 | 18 | 0.3 | 10 | 0.3 | 14 | 0.3 | 0.3 | 20 | ok |
| 152 | Marvel/M4RV3L C0M1CS/Los Caballeros de Pendragón (Forum)/Los Caballeros de Pendragón nº11.cbr | cbr | 5.1 | RAR4 | RAR4 | no | 0x33 | 25 | 17 | 23 | 12 | 0.2 | 17 | 0.3 | 9 | 0.2 | 0.4 | 14 | ok |
| 153 | Marvel/Los 4 Fantasticos (1-200 Vol1 USA)(de J.Kirby y S.Lee)(Ed.Marvel)(1961-78) /Los 4 Fantasticos 076 [MQ][por Orijua][CRG].cbr | cbr | 5.0 | RAR4 | RAR4 | no | 0x33 | 21 | 14 | 13 | 10 | 0.2 | 14 | 0.2 | 15 | 0.2 | 0.3 | 20 | ok |
| 154 | The Walking Dead/The Walking Dead 2003 #21 (2005).cbr | cbr | 4.6 | RAR4 | RAR4 | no | 0x33 | 24 | 18 | 32 | 13 | 0.2 | 14 | 0.1 | 11 | 0.2 | 0.3 | 14 | ok |
| 155 | Marvel/M4RV3L C0M1CS/M4rvel Saga 36 - Brand new day (2008)/19 The Amazing Spider-Man EXTRA 01.cbr | cbr | 4.1 | RAR4 | RAR4 | no | 0x33 | 9 | 6 | 31 | 22 | 0.5 | 23 | 0.4 | 18 | 0.4 | 0.2 | 21 | ok |
| 156 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/063.cbr | cbr | 4.1 | RAR4 | RAR4 | no | 0x33 | 18 | 16 | 19 | 19 | 0.2 | 23 | 0.3 | 11 | 0.2 | 0.3 | 15 | ok |
| 157 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/143.cbr | cbr | 4.1 | RAR4 | RAR4 | no | 0x33 | 18 | 15 | 15 | 10 | 0.2 | 31 | 0.4 | 18 | 0.2 | 0.4 | 12 | ok |
| 158 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/311.cbr | cbr | 4.0 | RAR4 | RAR4 | no | 0x33 | 19 | 15 | 10 | 10 | 0.2 | 6 | 0.1 | 15 | 0.2 | 0.3 | 15 | ok |
| 159 | Marvel/Spiderman/The Amazing Spiderman 700 Números/509 The Amazing Spider-Man #475.cbr | cbr | 3.3 | RAR4 | RAR4 | no | 0x30 | 22 | 12 | 4 | 13 | 0.1 | 11 | 0.1 | 9 | 0.1 | 0.2 | 14 | ok |
