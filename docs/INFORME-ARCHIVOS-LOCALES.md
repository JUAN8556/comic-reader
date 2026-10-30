# Informe: archivos locales con el motor de la sonda

Generado por `LocalComicsReportTest` (rama `sonda/archive`) el 2026-10-30.
Máquina: Mac OS X x86_64, 16 núcleos, Java 17.0.20.1, heap máx. 512 MB. junrar 8.1.2 es Java puro: en el Helio G100 irá varias veces más lento, pero las proporciones valen.

Ficheros: 159 (la lista de los que están físicamente en la Mac). Tiempo máximo por fichero: 60 s.

Las conclusiones están al final (escritas a mano; regenerar el informe las conserva).

## Resumen

| Formato real (cabecera) | Ficheros | .cbr | .cbz | Abren bien | Fallan |
|---|---|---|---|---|---|
| ZIP | 15 | 7 | 8 | 15 | 0 |
| RAR4 | 141 | 141 | 0 | 141 | 0 |
| RAR5 | 2 | 2 | 0 | 2 | 0 |
| SEVEN_ZIP | 1 | 1 | 0 | 0 | 1 |

- Disfrazados (la extensión miente): 8 — Leyendas de America Novaro/230 Bosque en llamas - Arcano 9.cbr → ZIP; Vidas Ilustres Novaro - COMPLETO - AIT/208 Silvestre Revueltas - Arcano9.cbr → ZIP; Marvel/M4RV3L C0M1CS/Immortal Hulk/Immortal Hulk #6.cbr → ZIP; Marvel/M4RV3L C0M1CS/League of Legends - Zed/Zed #06.cbr → ZIP; Marvel/M4RV3L C0M1CS/Punisher Kill Krew#1-5/Punisher Kill Krew 02.cbr → SEVEN_ZIP; Marvel/M4RV3L C0M1CS/Age Of X/4.- X-Men Legacy #245.cbr → ZIP; Marvel/M4RV3L C0M1CS/Spider Woman #1-8/5.- Spider-Gwen #8.cbr → ZIP; Marvel/House Of M Vol. 1/4.- Iron Man House Of M #1.cbr → ZIP
- Sólidos: 0 (sniffer RAR4_SOLID: 0; junrar `isSolid`: 0).
- Sniffer (byte 10 & 0x08) contra junrar `getMainHeader().isSolid()`: 141 RAR4 comparados, 0 discrepancias.
- Método de compresión de la primera página (RAR4 0x30 guardado … 0x35 máxima; RAR5 0-5; ZIP 0 guardado / 8 deflate): RAR4:0x30 × 23, RAR4:0x33 × 113, RAR4:0x35 × 5, RAR5:0x0 × 2, ZIP:8 × 15
- Ventana (diccionario) máxima: RAR4:1024 KB × 49, RAR4:128 KB × 1, RAR4:2048 KB × 50, RAR4:256 KB × 3, RAR4:4096 KB × 8, RAR4:512 KB × 30, RAR5:1024 KB × 1, RAR5:2048 KB × 1
- Cabeceras rotas toleradas por junrar: 0 en 0 ficheros.
- Páginas GUARDADAS leídas directamente (sin junrar, con CRC32): 1487 en 50 RAR (de 143 RAR que llegaron a «todo en orden»).

### Fallos

- #86 Marvel/M4RV3L C0M1CS/Punisher Kill Krew#1-5/Punisher Kill Krew 02.cbr (cbr, cabecera SEVEN_ZIP): UnsupportedArchiveException: «Punisher Kill Krew 02.cbr» es un 7z (aunque se llame .cbr o .cb7). Esta versión todavía no lee 7z: llegará con libarchive.

### Tiempos (los que abren bien)

| Formato | n | abrir p50 / p95 ms | p0 p50 / p95 | p(n/2) p50 / p95 | última p50 / p95 | atrás p50 / p95 | todo en orden p50 MB/s (mín.) |
|---|---|---|---|---|---|---|---|
| ZIP | 15 | 1 / 3 | 4 / 9 | 3 / 6 | 2 / 7 | 2 / 7 | 139 (81) |
| RAR4 | 141 | 2 / 11 | 8 / 23 | 8 / 26 | 6 / 22 | 7 / 22 | 66 (31) |
| RAR5 | 2 | 4 / 4 | 1 / 1 | 11 / 11 | 2 / 2 | 5 / 5 | 59 (59) |

## Sólidos: qué hace junrar 8.1.2 y la mitigación

Leído en el bytecode (`javap -c com.github.junrar.Archive`, `extractFile`): en un sólido junrar recuerda la última entrada descomprimida (`lastProcessedFileIndex`). Si se pide una posterior, descomprime las de en medio a un `NullOutputStream`; si se pide una anterior o la misma, llama a `Unpack.init(null)` y vuelve a empezar desde la primera entrada. El «acceso aleatorio en sólidos» de 7.6.0 es correcto pero cuesta O(k) por página: pasar páginas hacia atrás (o en un orden natural distinto del físico) es O(n²).

Como entre los 159 ficheros locales no hay ningún RAR sólido, se midió con un RAR4 sólido escrito por la prueba (60 páginas de 1.0 MB, entradas guardadas). MB leídos = bytes comprimidos que pasa junrar (contados con `UnrarCallback.volumeProgressChanged`), independiente de la máquina:

| Patrón | junrar a pelo, sólido | con caché, sólido | junrar, no sólido |
|---|---|---|---|
| adelante 0→59 | 139 ms · 60 MB leídos | 265 ms · 60 MB leídos | 174 ms · 60 MB leídos |
| atrás 59→0 | 570 ms · 1830 MB leídos | 221 ms · 60 MB leídos | 116 ms · 60 MB leídos |
| última y luego la primera | 24 ms · 61 MB leídos | 125 ms · 60 MB leídos | 7 ms · 2 MB leídos |
| saltos (0, 30, 5, 59, 29) | 42 ms · 121 MB leídos | 123 ms · 60 MB leídos | 12 ms · 5 MB leídos |

## Tabla por fichero

Tiempos en ms. «p0», «p(n/2)» y «última» = abrir de nuevo y extraer solo esa página (lo que se espera al retomar ahí); «abrir» es del primer intento (incluye la lectura en frío de las cabeceras). «atrás» = la penúltima justo después de la última, en la misma instancia. «MB leídos» = comprimidos que leyó junrar para esa página. «todo» = las n páginas en orden con una instancia nueva.

| # | Fichero | Ext | MB | Cabecera | Tipo | Sólido | Mét. | Págs | Abrir | p0 | p(n/2) | MB leídos | Última | MB leídos | Atrás | MB leídos | Todo (s) | MB/s | Resultado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Condorito/Condorito 335 1999.cbr | cbr | 216.2 | RAR4 | RAR4 | no | 0x33 | 68 | 11 | 205 | 38 | 3.2 | 4 | 3.3 | 27 | 1.2 | 2.5 | 86 | ok |
| 2 | Condorito/Condorito 448 2003.cbr | cbr | 204.0 | RAR4 | RAR4 | no | 0x33 | 68 | 7 | 35 | 32 | 2.9 | 34 | 3.1 | 27 | 2.5 | 2.4 | 85 | ok |
| 3 | Condorito/Condorito 465 2004.cbr | cbr | 192.7 | RAR4 | RAR4 | no | 0x30 | 68 | 6 | 4 | 34 | 2.6 | 35 | 2.8 | 28 | 2.4 | 2.4 | 80 | ok |
| 4 | Death Note/Death Note Tomo 12.cbz | cbz | 150.2 | ZIP | ZIP | — | 8 | 212 | 10 | 9 | 3 | — | 2 | — | 1 | — | 0.8 | 181 | ok |
| 5 | Marvel/M4RV3L C0M1CS/Ir0n M4n - Los Años Perdidos (Forum)/Iron Man - Los Años Perdidos 1 (Forum).cbr | cbr | 88.6 | RAR4 | RAR4 | no | 0x30 | 136 | 11 | 1 | 1 | 0.7 | 6 | 0.4 | 2 | 0.3 | 0.4 | 206 | ok |
| 6 | Marvel/M4RV3L C0M1CS/Superconan/Superconan nº06.cbr | cbr | 87.4 | RAR4 | RAR4 | no | 0x30 | 199 | 18 | 1 | 1 | 0.5 | 3 | 0.2 | 5 | 0.3 | 0.8 | 110 | ok |
| 7 | Marvel/M4RV3L C0M1CS/Dared3vil - El Regreso de Kingpin (Forum)/Daredevil - El Regreso de Kingpin (Forum).cbr | cbr | 78.2 | RAR4 | RAR4 | no | 0x30 | 149 | 11 | 3 | 1 | 0.3 | 2 | 0.5 | 2 | 0.4 | 0.2 | 336 | ok |
| 8 | Marvel/M4RV3L C0M1CS/M4rvel Knights - Hulk 003/Marvel Knights - Hulk 003.cbr | cbr | 76.8 | RAR4 | RAR4 | no | 0x33 | 21 | 2 | 25 | 50 | 3.7 | 22 | 1.7 | 115 | 6.5 | 1.2 | 63 | ok |
| 9 | Naruto/Naruto/Naruto - Tomo 54 (#505-514).cbr | cbr | 74.6 | RAR4 | RAR4 | no | 0x35 | 160 | 14 | 9 | 10 | 0.7 | 8 | 0.5 | 6 | 0.4 | 1.2 | 63 | ok |
| 10 | Marvel/M4RV3L C0M1CS/M4rvel 2099 1-14/Marvel 2099 09.cbr | cbr | 70.2 | RAR4 | RAR4 | no | 0x33 | 131 | 9 | 8 | 9 | 0.5 | 5 | 0.2 | 9 | 0.4 | 1.2 | 60 | ok |
| 11 | Marvel/M4RV3L C0M1CS/Biblioteca Marv3l -  Hulk (Forum)/Biblioteca Marvel - Hulk 21.cbr | cbr | 66.9 | RAR4 | RAR4 | no | 0x30 | 83 | 8 | 1 | 1 | 0.8 | 2 | 0.3 | 2 | 0.8 | 0.2 | 436 | ok |
| 12 | Marvel/M4RV3L C0M1CS/Superconan/Superconan nº07.cbr | cbr | 63.2 | RAR4 | RAR4 | no | 0x33 | 199 | 18 | 3 | 5 | 0.3 | 3 | 0.2 | 3 | 0.2 | 1.1 | 59 | ok |
| 13 | Marvel/M4RV3L C0M1CS/Biblioteca Marv3l - Nova (Forum)/Nova 02.cbr | cbr | 60.9 | RAR4 | RAR4 | no | 0x33 | 164 | 14 | 7 | 1 | 0.4 | 3 | 0.2 | 2 | 0.3 | 0.2 | 310 | ok |
| 14 | Vidas Ilustres Novaro - COMPLETO - AIT/036 Lope de Vega (bis 136) - Carasucia.cbr | cbr | 60.6 | RAR4 | RAR4 | no | 0x33 | 36 | 5 | 21 | 22 | 1.7 | 20 | 1.4 | 19 | 1.4 | 0.9 | 70 | ok |
| 15 | Vidas Ilustres Novaro - COMPLETO - AIT/153 Romain Rolland - Carasucia.cbr | cbr | 60.3 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 21 | 25 | 1.7 | 22 | 1.6 | 22 | 1.5 | 0.9 | 70 | ok |
| 16 | Vidas Ilustres Novaro - COMPLETO - AIT/033 El maravilloso doctor Paracelso (bis 269) - Carasucia.cbr | cbr | 58.9 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 22 | 23 | 1.7 | 20 | 1.3 | 22 | 1.6 | 0.9 | 69 | ok |
| 17 | Vidas Ilustres Novaro - COMPLETO - AIT/065 Edward Jenner - Carasucia.cbr | cbr | 58.8 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 23 | 23 | 1.7 | 103 | 1.1 | 18 | 1.2 | 0.9 | 67 | ok |
| 18 | Death Note/Death Note Tomo 06.cbz | cbz | 58.0 | ZIP | ZIP | — | 8 | 221 | 1 | 5 | 4 | — | 2 | — | 2 | — | 0.5 | 102 | ok |
| 19 | Mujeres Celebres Novaro/Mujeres Celebres 048 Mabel Hubbard - Carasucia.cbr | cbr | 57.6 | RAR4 | RAR4 | no | 0x33 | 36 | 5 | 25 | 26 | 1.6 | 21 | 1.5 | 25 | 1.6 | 0.9 | 67 | ok |
| 20 | Vidas Ilustres Novaro - COMPLETO - AIT/050 Ramon y Cajal - Carasucia.cbr | cbr | 57.4 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 17 | 27 | 1.6 | 16 | 1.1 | 21 | 1.2 | 1.0 | 60 | ok |
| 21 | Vidas Ilustres Novaro - COMPLETO - AIT/171 Fedor Chaliapin - Carasucia.cbr | cbr | 57.4 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 21 | 23 | 1.7 | 27 | 1.6 | 15 | 1.1 | 0.9 | 67 | ok |
| 22 | Condorito/Condorito 493 2005.cbr | cbr | 57.1 | RAR4 | RAR4 | no | 0x33 | 67 | 6 | 15 | 14 | 1.0 | 4 | 0.2 | 4 | 0.2 | 0.8 | 70 | ok |
| 23 | Death Note/Death Note Tomo 07.cbz | cbz | 56.6 | ZIP | ZIP | — | 8 | 210 | 1 | 3 | 2 | — | 1 | — | 2 | — | 0.5 | 109 | ok |
| 24 | Vidas Ilustres Novaro - COMPLETO - AIT/122 Sarmiento, patriota y educador - Carasucia.cbr | cbr | 54.7 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 21 | 23 | 1.7 | 17 | 1.2 | 17 | 1.2 | 0.8 | 69 | ok |
| 25 | Death Note/Death Note Tomo 11.cbz | cbz | 50.7 | ZIP | ZIP | — | 8 | 219 | 1 | 4 | 1 | — | 2 | — | 1 | — | 0.4 | 114 | ok |
| 26 | Death Note/Death Note Tomo 08.cbz | cbz | 50.2 | ZIP | ZIP | — | 8 | 199 | 1 | 2 | 2 | — | 1 | — | 2 | — | 0.5 | 103 | ok |
| 27 | Death Note/Death Note Tomo 04.cbz | cbz | 49.2 | ZIP | ZIP | — | 8 | 207 | 1 | 4 | 2 | — | 1 | — | 2 | — | 0.5 | 99 | ok |
| 28 | Leyendas de America Novaro/230 Bosque en llamas - Arcano 9.cbr | cbr | 45.8 | ZIP | ZIP | — | 8 | 36 | 3 | 4 | 10 | — | 7 | — | 6 | — | 0.3 | 139 | ok |
| 29 | Vidas Ilustres Novaro - COMPLETO - AIT/286 Mozart (bis 051) - Bogey.cbr | cbr | 44.8 | RAR4 | RAR4 | no | 0x30 | 36 | 4 | 2 | 2 | 1.2 | 2 | 0.9 | 2 | 0.8 | 0.1 | 472 | ok |
| 30 | Death Note/Death Note Tomo 02.cbz | cbz | 43.9 | ZIP | ZIP | — | 8 | 201 | 2 | 5 | 2 | — | 1 | — | 2 | — | 0.5 | 81 | ok |
| 31 | Leyendas de America Novaro/135 Cuando los duendes escapan - Figueroa.cbr | cbr | 43.6 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 15 | 21 | 1.3 | 16 | 1.1 | 12 | 0.8 | 0.6 | 70 | ok |
| 32 | Death Note/Death Note Tomo 10.cbz | cbz | 43.3 | ZIP | ZIP | — | 8 | 184 | 1 | 4 | 3 | — | 1 | — | 4 | — | 0.4 | 105 | ok |
| 33 | Marvel/M4RV3L C0M1CS/Heroes Reborn + Tie-Ins/Heroes Reborn #07.cbr | cbr | 42.8 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 24 | 18 | 1.3 | 5 | 0.2 | 21 | 1.4 | 0.7 | 67 | ok |
| 34 | Marvel/M4RV3L C0M1CS/Aveng3rs The initiative/Aveng3rs The initiative 20-25.cbr | cbr | 40.2 | RAR4 | RAR4 | no | 0x35 | 145 | 12 | 6 | 7 | 0.3 | 4 | 0.2 | 4 | 0.2 | 1.0 | 50 | ok |
| 35 | Marvel/Spiderman/Spider-man La Última Cacería de Kraven/Spider-man La Última Cacería de Kraven #04.cbr | cbr | 39.5 | RAR4 | RAR4 | no | 0x33 | 23 | 3 | 39 | 25 | 1.7 | 22 | 1.5 | 35 | 2.5 | 0.7 | 59 | ok |
| 36 | Leyendas de America Novaro/380 - Aurelia La Silenciosa (xJaviCollar & Mr.GAG).cbr | cbr | 38.8 | RAR5 | RAR5 | no | 0x0 | 36 | 11 | 2 | 60 | 1.0 | 2 | 1.1 | 122 | 1.1 | 0.7 | 59 | ok |
| 37 | Vidas Ilustres Novaro - COMPLETO - AIT/170 Horacio Quiroga - Arcano9.cbr | cbr | 38.1 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 14 | 16 | 1.0 | 16 | 1.1 | 18 | 1.2 | 0.6 | 66 | ok |
| 38 | Marvel/M4RV3L C0M1CS/Biblioteca Marv3l - Doctor Extraño (Forum)/Biblioteca Marvel - Doctor Extraño nº08.cbr | cbr | 36.8 | RAR4 | RAR4 | no | 0x33 | 83 | 5 | 4 | 1 | 0.4 | 4 | 0.1 | 7 | 0.4 | 0.2 | 210 | ok |
| 39 | Marvel/M4RV3L C0M1CS/Archivos Spiderm4n Vol.1 (Forum)/Archivos Spid3rman 2 - El Regreso de los 6 Siniestros.cbr | cbr | 35.8 | RAR4 | RAR4 | no | 0x30 | 144 | 15 | 1 | 1 | 0.2 | 1 | 0.2 | 1 | 0.2 | 0.1 | 249 | ok |
| 40 | Mujeres Celebres Novaro/Mujeres Celebres 088 Greta Garbo - Arcano9 (1).cbr | cbr | 35.2 | RAR4 | RAR4 | no | 0x33 | 36 | 2 | 20 | 15 | 1.0 | 14 | 0.9 | 13 | 0.7 | 0.6 | 63 | ok |
| 41 | Marvel/M4RV3L C0M1CS/Th0r - Relatos de Asgard/Thor - Relatos de Asgard 01.cbr | cbr | 34.7 | RAR4 | RAR4 | no | 0x33 | 51 | 4 | 18 | 10 | 0.6 | 18 | 0.7 | 16 | 0.7 | 0.6 | 63 | ok |
| 42 | Vidas Ilustres Novaro - COMPLETO - AIT/208 Silvestre Revueltas - Arcano9.cbr | cbr | 32.3 | ZIP | ZIP | — | 8 | 36 | 0 | 2 | 3 | — | 6 | — | 8 | — | 0.1 | 300 | ok |
| 43 | Mujeres Celebres Novaro/Mujeres Celebres 086 Murasaki - Arcano9.cbr | cbr | 32.1 | RAR4 | RAR4 | no | 0x30 | 36 | 2 | 2 | 14 | 0.9 | 13 | 0.8 | 8 | 0.5 | 0.5 | 71 | ok |
| 44 | Vidas Ilustres Novaro - COMPLETO - AIT/173 Mijail V. Lomonosov - Arcano9.cbr | cbr | 31.8 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 12 | 13 | 0.9 | 8 | 0.5 | 10 | 0.6 | 0.5 | 70 | ok |
| 45 | Marvel/M4RV3L C0M1CS/Act0s de Venganz4/Crossovers/Crossovers 14 - Los 4 Fantásticos Vol1 #100.cbr | cbr | 31.5 | RAR4 | RAR4 | no | 0x30 | 94 | 6 | 1 | 1 | 0.5 | 1 | 0.3 | 1 | 0.3 | 0.1 | 307 | ok |
| 46 | Marvel/M4RV3L C0M1CS/D4rk 4ngel y Warheads Vol.1 (Forum)/Dark Angel y Warheads 08.cbr | cbr | 30.5 | RAR4 | RAR4 | no | 0x33 | 50 | 4 | 13 | 10 | 0.7 | 11 | 0.7 | 11 | 0.7 | 0.5 | 66 | ok |
| 47 | Marvel/Superior Iron Man/Superior Iron Man #05.cbr | cbr | 30.2 | RAR4 | RAR4 | no | 0x33 | 22 | 2 | 19 | 28 | 1.8 | 14 | 0.9 | 21 | 1.4 | 0.5 | 68 | ok |
| 48 | Condorito/Condorito 318 1998.cbr | cbr | 29.9 | RAR4 | RAR4 | no | 0x30 | 68 | 7 | 1 | 1 | 0.4 | 1 | 0.4 | 5 | 0.3 | 0.4 | 80 | ok |
| 49 | Marvel/Civil war/Civil War - Chosing Sides.cbr | cbr | 27.4 | RAR4 | RAR4 | no | 0x33 | 48 | 4 | 14 | 9 | 0.6 | 9 | 0.5 | 8 | 0.5 | 0.4 | 65 | ok |
| 50 | Marvel/Superior Iron Man/Superior Iron Man #03.cbr | cbr | 26.1 | RAR4 | RAR4 | no | 0x33 | 22 | 2 | 13 | 19 | 1.3 | 10 | 0.5 | 14 | 0.8 | 0.4 | 67 | ok |
| 51 | Marvel/M4RV3L C0M1CS/Aventuras Bizarras (Forum)/Aventuras Bizarras 03.cbr | cbr | 25.5 | RAR4 | RAR4 | no | 0x33 | 59 | 4 | 5 | 19 | 1.0 | 8 | 0.4 | 7 | 0.4 | 0.5 | 52 | ok |
| 52 | Naruto/Naruto/Naruto - Tomo 12 (#100-108).cbr | cbr | 23.3 | RAR4 | RAR4 | no | 0x35 | 180 | 12 | 5 | 4 | 0.1 | 3 | 0.1 | 4 | 0.1 | 0.5 | 42 | ok |
| 53 | The Walking Dead/The Walking Dead 2003 #180 (2018).cbr | cbr | 23.3 | RAR4 | RAR4 | no | 0x33 | 29 | 2 | 10 | 12 | 0.8 | 4 | 0.2 | 13 | 0.9 | 0.4 | 68 | ok |
| 54 | Marvel/M4RV3L C0M1CS/Ka-Zar Volumen 1/Ka-Zar El Salvaje 02.cbr | cbr | 22.5 | RAR4 | RAR4 | no | 0x33 | 84 | 6 | 3 | 5 | 0.3 | 4 | 0.3 | 4 | 0.2 | 0.4 | 58 | ok |
| 55 | Leyendas de America Novaro/260 El árbol de la muerte (xCrisLeza).cbr | cbr | 22.5 | RAR5 | RAR5 | no | 0x0 | 36 | 4 | 1 | 11 | 0.6 | 7 | 0.4 | 5 | 0.3 | 0.4 | 62 | ok |
| 56 | Marvel/M4RV3L C0M1CS/Namor Vol.1/Namor el Hombre Submarino 48a por Vicu68 [CRG].cbr | cbr | 22.3 | RAR4 | RAR4 | no | 0x33 | 25 | 2 | 16 | 11 | 0.7 | 8 | 0.4 | 18 | 1.2 | 0.3 | 68 | ok |
| 57 | Marvel/M4RV3L C0M1CS/En Busca de Korvac/En Busca de Korvac 2 de 4 - Thor.cbr | cbr | 22.2 | RAR4 | RAR4 | no | 0x33 | 58 | 4 | 8 | 7 | 0.4 | 6 | 0.4 | 6 | 0.4 | 0.4 | 61 | ok |
| 58 | Vidas Ilustres Novaro - COMPLETO - AIT/106 Amado Nervo - Carasucia.cbr | cbr | 22.0 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 21 | 9 | 0.6 | 8 | 0.5 | 9 | 0.6 | 0.3 | 71 | ok |
| 59 | Marvel/M4RV3L C0M1CS/Capitán 4mérica - Último Combate (Forum)/Capitán América - Último Combate nº2.cbr | cbr | 21.4 | RAR4 | RAR4 | no | 0x33 | 47 | 3 | 10 | 8 | 0.5 | 6 | 0.4 | 7 | 0.5 | 0.3 | 64 | ok |
| 60 | Marvel/M4RV3L C0M1CS/Black Vortex #1-13/08 - Nova #28 - The Black Vortex Chapter 8.cbr | cbr | 21.3 | RAR4 | RAR4 | no | 0x33 | 23 | 2 | 9 | 12 | 0.8 | 6 | 0.4 | 12 | 0.9 | 0.3 | 70 | ok |
| 61 | Marvel/M4RV3L C0M1CS/Héroes de Alquiler Vol.3/Heroes for Hire - 04.cbr | cbr | 21.3 | RAR4 | RAR4 | no | 0x33 | 25 | 2 | 13 | 12 | 0.9 | 9 | 0.6 | 10 | 0.7 | 0.3 | 70 | ok |
| 62 | Condorito/Condorito 169 1988.cbr | cbr | 21.3 | RAR4 | RAR4 | no | 0x30 | 49 | 4 | 2 | 1 | 0.5 | 1 | 0.4 | 8 | 0.5 | 0.2 | 137 | ok |
| 63 | Marvel/M4RV3L C0M1CS/Shadowland #1-32/25 Shadowland Power Man 04.cbr | cbr | 20.9 | RAR4 | RAR4 | no | 0x33 | 27 | 2 | 16 | 11 | 0.7 | 10 | 0.7 | 13 | 1.0 | 0.3 | 69 | ok |
| 64 | Marvel/M4RV3L C0M1CS/Immortal Hulk/Immortal Hulk #6.cbr | cbr | 20.4 | ZIP | ZIP | — | 8 | 22 | 0 | 5 | 6 | — | 23 | — | 7 | — | 0.2 | 140 | ok |
| 65 | Marvel/M4RV3L C0M1CS/X-M3n Regenesis #1-39/05 Wolverine Vol 04 19.cbr | cbr | 19.7 | RAR4 | RAR4 | no | 0x33 | 22 | 2 | 7 | 12 | 0.9 | 11 | 0.7 | 13 | 0.9 | 0.3 | 70 | ok |
| 66 | Marvel/M4RV3L C0M1CS/Contest of Champions - Combate Final (Forum)/Contest of Champions - Combate Final (Forum).cbr | cbr | 19.6 | RAR4 | RAR4 | no | 0x33 | 129 | 9 | 3 | 4 | 0.1 | 2 | 0.1 | 3 | 0.1 | 0.4 | 55 | ok |
| 67 | The Walking Dead/The Walking Dead 2003 #103 (2012).cbr | cbr | 19.5 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 6 | 15 | 1.0 | 12 | 0.7 | 17 | 0.9 | 0.3 | 65 | ok |
| 68 | W0rld of W4rcraft/World of Warcraft #15.cbr | cbr | 19.5 | RAR4 | RAR4 | no | 0x33 | 21 | 2 | 12 | 11 | 0.8 | 12 | 0.8 | 10 | 0.7 | 0.3 | 68 | ok |
| 69 | Marvel/M4RV3L C0M1CS/League of Legends - Zed/Zed #06.cbr | cbr | 18.8 | ZIP | ZIP | — | 8 | 25 | 0 | 9 | 4 | — | 3 | — | 6 | — | 0.1 | 201 | ok |
| 70 | Marvel/M4RV3L C0M1CS/M4rvel Saga 85 - Dead No More The Clone Conspiracy (2016 - 2017)/08 Dead No More - The Clone Conspiracy Vol. 1 002.cbr | cbr | 18.6 | RAR4 | RAR4 | no | 0x33 | 20 | 2 | 17 | 13 | 0.9 | 8 | 0.5 | 13 | 0.9 | 0.3 | 68 | ok |
| 71 | Marvel/M4RV3L C0M1CS/M4rvel Saga 55 - Curse of Mutants (2010)/04 Blade.cbr | cbr | 18.4 | RAR4 | RAR4 | no | 0x33 | 26 | 2 | 10 | 13 | 0.8 | 10 | 0.7 | 9 | 0.6 | 0.3 | 68 | ok |
| 72 | Marvel/M4RV3L C0M1CS/M4rvel Saga 85 - Dead No More The Clone Conspiracy (2016 - 2017)/06 The Amazing Spider-Man Vol. 4 020 (www.ElAbueloSawa.com).cbr | cbr | 18.2 | RAR4 | RAR4 | no | 0x33 | 22 | 2 | 11 | 15 | 1.1 | 10 | 0.6 | 10 | 0.6 | 0.3 | 68 | ok |
| 73 | Marvel/M4RV3L C0M1CS/Th4nos Rising #1-5.rar/Thanos Rising - #03.cbr | cbr | 18.2 | RAR4 | RAR4 | no | 0x35 | 25 | 2 | 14 | 12 | 0.8 | 3 | 0.1 | 6 | 0.4 | 0.3 | 68 | ok |
| 74 | DC Comics/Superman/Superman - Origen Secreto/Superman - Origen Secreto #02.cbr | cbr | 18.2 | RAR4 | RAR4 | no | 0x30 | 34 | 3 | 1 | 9 | 0.6 | 6 | 0.4 | 8 | 0.5 | 0.2 | 74 | ok |
| 75 | Marvel/M4RV3L C0M1CS/La Cruzada del Infinito/La_Cruzada_Del_Infinito_02.cbr | cbr | 18.1 | RAR4 | RAR4 | no | 0x30 | 47 | 3 | 2 | 1 | 0.4 | 1 | 0.4 | 1 | 0.4 | 0.1 | 299 | ok |
| 76 | The Walking Dead/The Walking Dead 2003 #140 (2015).cbr | cbr | 17.8 | RAR4 | RAR4 | no | 0x33 | 23 | 2 | 12 | 15 | 0.9 | 7 | 0.5 | 10 | 0.7 | 0.2 | 72 | ok |
| 77 | Marvel/M4RV3L C0M1CS/Spider Island #1-27/16 The Amazing Spider-Man 669.cbr | cbr | 17.8 | RAR4 | RAR4 | no | 0x33 | 31 | 2 | 9 | 9 | 0.6 | 11 | 0.8 | 8 | 0.4 | 0.3 | 66 | ok |
| 78 | Marvel/M4RV3L C0M1CS/Ir0n Patri0t 1-5/#04 Iron Patriot Vol.1 (Marvel Now).cbr | cbr | 17.7 | RAR4 | RAR4 | no | 0x33 | 22 | 2 | 17 | 22 | 1.6 | 8 | 0.5 | 13 | 1.0 | 0.3 | 68 | ok |
| 79 | Marvel/M4RV3L C0M1CS/Falls Of The Hulks #1-27/09 Fall of The Hulks - The Savage She-hulks #01.cbr | cbr | 17.5 | RAR4 | RAR4 | no | 0x35 | 24 | 2 | 11 | 12 | 0.9 | 8 | 0.5 | 9 | 0.5 | 0.3 | 68 | ok |
| 80 | Marvel/M4RV3L C0M1CS/M4rvel Saga 77 - AXIS (2014)/34 Inhuman 09.cbr | cbr | 17.4 | RAR4 | RAR4 | no | 0x33 | 22 | 2 | 8 | 13 | 0.9 | 10 | 0.7 | 12 | 0.8 | 0.3 | 68 | ok |
| 81 | Marvel/Avenger vs X-men/25.- Avengers vs. X-Men - The Avengers #02.cbr | cbr | 17.0 | RAR4 | RAR4 | no | 0x33 | 21 | 2 | 16 | 2 | 1.0 | 13 | 0.9 | 13 | 0.9 | 0.2 | 76 | ok |
| 82 | Marvel/M4RV3L C0M1CS/W4r Of The Kings  #1-42/35 Guardians of the Galaxy 17.cbr | cbr | 16.7 | RAR4 | RAR4 | no | 0x33 | 27 | 2 | 8 | 10 | 0.6 | 8 | 0.5 | 11 | 0.7 | 0.3 | 66 | ok |
| 83 | Marvel/M4RV3L C0M1CS/Kang Dinasty (2001)/12 Avengers Vol3 51.cbr | cbr | 16.6 | RAR4 | RAR4 | no | 0x33 | 28 | 2 | 6 | 9 | 0.6 | 7 | 0.5 | 10 | 0.7 | 0.2 | 100 | ok |
| 84 | Robocop/Robocop de Frank Miller/Frank Miller's Robocop #9.cbr | cbr | 16.1 | RAR4 | RAR4 | no | 0x33 | 25 | 2 | 9 | 11 | 0.6 | 9 | 0.6 | 8 | 0.5 | 0.2 | 68 | ok |
| 85 | Marvel/M4RV3L C0M1CS/Inhumanity 1-22/20 Avengers Assemble 25.cbr | cbr | 16.1 | RAR4 | RAR4 | no | 0x33 | 25 | 2 | 11 | 10 | 0.6 | 6 | 0.3 | 10 | 0.5 | 0.3 | 68 | ok |
| 86 | Marvel/M4RV3L C0M1CS/Punisher Kill Krew#1-5/Punisher Kill Krew 02.cbr | cbr | 15.8 | SEVEN_ZIP | — | — | — | — | — | — | — | — | — | — | — | — | — | — | UnsupportedArchiveException: «Punisher Kill Krew 02.cbr» es un 7z (aunque se llame .cbr o .cb7). Esta versión todavía no lee 7z: llegará con libarchive. |
| 87 | Marvel/M4RV3L C0M1CS/Secret Invasion (2008)/115 Punisher War Journal 24.cbr | cbr | 15.7 | RAR4 | RAR4 | no | 0x33 | 26 | 3 | 6 | 13 | 0.6 | 3 | 0.2 | 5 | 0.2 | 0.2 | 66 | ok |
| 88 | Marvel/M4RV3L C0M1CS/M4rvel Saga 39 - Dark Reign (2009)/264 Invincible Iron Man 15.cbr | cbr | 15.7 | RAR4 | RAR4 | no | 0x33 | 35 | 3 | 7 | 7 | 0.4 | 12 | 0.8 | 3 | 0.2 | 0.3 | 64 | ok |
| 89 | Marvel/M4RV3L C0M1CS/M4rvel Saga 77 - AXIS (2014)/13 All New X-Factor 15.cbr | cbr | 15.5 | RAR4 | RAR4 | no | 0x33 | 21 | 2 | 14 | 11 | 0.7 | 9 | 0.6 | 10 | 0.7 | 0.3 | 66 | ok |
| 90 | Marvel/M4RV3L C0M1CS/Secret Invasion (2008)/122 Secret Invasion 8.cbr | cbr | 15.5 | RAR4 | RAR4 | no | 0x33 | 26 | 2 | 6 | 9 | 0.5 | 9 | 0.5 | 10 | 0.7 | 0.2 | 66 | ok |
| 91 | Marvel/M4RV3L C0M1CS/M4rvel Saga 40 - Manifest Destiny (2008-2009)/36 Uncanny X-men Vol1 507.cbr | cbr | 15.4 | RAR4 | RAR4 | no | 0x33 | 26 | 2 | 10 | 11 | 0.6 | 3 | 0.1 | 12 | 0.7 | 0.3 | 63 | ok |
| 92 | Marvel/M4RV3L C0M1CS/Retorno de Lobezno/Return of the Wolverine 003.cbr | cbr | 15.4 | RAR4 | RAR4 | no | 0x33 | 25 | 2 | 13 | 8 | 0.5 | 5 | 0.3 | 11 | 0.8 | 0.2 | 67 | ok |
| 93 | Marvel/M4RV3L C0M1CS/M4rvel Saga 36 - Brand new day (2008)/01 The Amazing Spider-Man Vol1 546.cbr | cbr | 15.2 | RAR4 | RAR4 | no | 0x30 | 28 | 2 | 2 | 1 | 0.6 | 1 | 0.5 | 1 | 0.6 | 0.0 | 411 | ok |
| 94 | Marvel/M4RV3L C0M1CS/Secret Invasion (2008)/056 Avengers The Initiative 15.cbr | cbr | 15.0 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 11 | 8 | 0.5 | 7 | 0.5 | 12 | 0.7 | 0.2 | 67 | ok |
| 95 | Marvel/World War Hulk/15.- World War Hulk - X-Men #02.cbr | cbr | 14.9 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 11 | 10 | 0.7 | 9 | 0.5 | 8 | 0.5 | 0.2 | 66 | ok |
| 96 | Marvel/M4RV3L C0M1CS/D3adpool Back in Black/Deadpool - Back In Black Vol.1 #04.cbr | cbr | 14.7 | RAR4 | RAR4 | no | 0x33 | 21 | 2 | 10 | 8 | 0.5 | 10 | 0.6 | 8 | 0.5 | 0.2 | 66 | ok |
| 97 | Marvel/M4RV3L C0M1CS/Namor Vol.1/Namor el Hombre Submarino 53 por Stanley Lieber & Nacho763 [CRG].cbr | cbr | 14.6 | RAR4 | RAR4 | no | 0x33 | 25 | 2 | 12 | 9 | 0.5 | 7 | 0.4 | 8 | 0.6 | 0.2 | 65 | ok |
| 98 | Marvel/M4RV3L C0M1CS/Age Of X/4.- X-Men Legacy #245.cbr | cbr | 14.6 | ZIP | ZIP | — | 8 | 25 | 0 | 2 | 4 | — | 5 | — | 4 | — | 0.1 | 147 | ok |
| 99 | Marvel/M4RV3L C0M1CS/M4rvel Saga 32 - Messiah Complex (2007)/07 X-Factor 26.cbr | cbr | 14.5 | RAR4 | RAR4 | no | 0x33 | 24 | 3 | 10 | 9 | 0.6 | 5 | 0.3 | 8 | 0.6 | 0.2 | 64 | ok |
| 100 | Marvel/M4RV3L C0M1CS/D4rk 4veng3rs Vol1 (2009-2010)/Dark Avengers Vol1 06.cbr | cbr | 14.4 | RAR4 | RAR4 | no | 0x33 | 25 | 2 | 9 | 10 | 0.6 | 5 | 0.3 | 10 | 0.6 | 0.2 | 63 | ok |
| 101 | Marvel/M4RV3L C0M1CS/M4rvel Saga 85 - Dead No More The Clone Conspiracy (2016 - 2017)/22 Silk Vol. 2 017 (www.ElAbueloSawa.com).cbr | cbr | 14.4 | RAR4 | RAR4 | no | 0x33 | 20 | 2 | 7 | 12 | 0.8 | 8 | 0.6 | 12 | 0.9 | 0.2 | 68 | ok |
| 102 | Leyendas de America Novaro/322 Dos joyas del Mexico colonial - Mja.cbr | cbr | 14.2 | RAR4 | RAR4 | no | 0x33 | 35 | 3 | 7 | 8 | 0.4 | 6 | 0.3 | 7 | 0.4 | 0.2 | 59 | ok |
| 103 | Marvel/M4RV3L C0M1CS/M4rvel Saga 39 - Dark Reign (2009)/146 Thor 602.cbr | cbr | 14.2 | RAR4 | RAR4 | no | 0x33 | 30 | 2 | 9 | 8 | 0.6 | 11 | 0.8 | 6 | 0.4 | 0.2 | 65 | ok |
| 104 | Gears of War Comic Series/G.0.W.#03.cbr | cbr | 13.8 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 7 | 9 | 0.6 | 9 | 0.6 | 9 | 0.6 | 0.2 | 66 | ok |
| 105 | Marvel/M4RV3L C0M1CS/Reino de los Reyes/25 Realm of Kings Hijo de Hulk 001.cbr | cbr | 13.7 | RAR4 | RAR4 | no | 0x33 | 27 | 2 | 8 | 8 | 0.5 | 2 | 0.1 | 7 | 0.5 | 0.2 | 65 | ok |
| 106 | Marvel/M4RV3L C0M1CS/Feart Itself 1-126/097 Fear Itself Black Widow 01.cbr | cbr | 13.5 | RAR4 | RAR4 | no | 0x33 | 28 | 2 | 9 | 8 | 0.5 | 7 | 0.4 | 8 | 0.4 | 0.2 | 65 | ok |
| 107 | Marvel/M4RV3L C0M1CS/Fantastic Four - La muerte de la Antorcha Humana/583.cbr | cbr | 13.3 | RAR4 | RAR4 | no | 0x33 | 26 | 2 | 9 | 10 | 0.5 | 9 | 0.6 | 6 | 0.4 | 0.2 | 65 | ok |
| 108 | Marvel/Civil war/Civil-War - Frontline #06.cbr | cbr | 13.1 | RAR4 | RAR4 | no | 0x33 | 33 | 3 | 10 | 7 | 0.4 | 7 | 0.4 | 6 | 0.4 | 0.2 | 63 | ok |
| 109 | Marvel/M4RV3L C0M1CS/M4rvel Saga 39 - Dark Reign (2009)/271 Invincible Iron Man 19.cbr | cbr | 13.1 | RAR4 | RAR4 | no | 0x33 | 39 | 3 | 5 | 6 | 0.4 | 7 | 0.5 | 5 | 0.3 | 0.2 | 62 | ok |
| 110 | Marvel/M4RV3L C0M1CS/Wolv3rine Noir/Wolverine Noir #3.cbr | cbr | 12.4 | RAR4 | RAR4 | no | 0x33 | 26 | 2 | 6 | 6 | 0.4 | 3 | 0.1 | 1 | 0.3 | 0.2 | 66 | ok |
| 111 | Marvel/M4RV3L C0M1CS/Spider Woman #1-8/5.- Spider-Gwen #8.cbr | cbr | 11.9 | ZIP | ZIP | — | 8 | 20 | 0 | 3 | 3 | — | 3 | — | 3 | — | 0.1 | 167 | ok |
| 112 | Marvel/M4RV3L C0M1CS/Apocalípsis - Los Doce (Forum)/(Apocalípsis Los Doce 5de8) Lobezno nº057 vol2.cbr | cbr | 11.8 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 9 | 7 | 0.5 | 7 | 0.4 | 7 | 0.4 | 0.1 | 91 | ok |
| 113 | Marvel/M4RV3L C0M1CS/Act0s de Venganz4/AdV 05 Los Vengadores vol1 097.cbr | cbr | 11.7 | RAR4 | RAR4 | no | 0x33 | 29 | 2 | 90 | 53 | 0.3 | 61 | 0.4 | 64 | 0.4 | 0.2 | 50 | ok |
| 114 | Marvel/M4RV3L C0M1CS/Namor Vol.1/Namor el Hombre Submarino 60 por Master Cosmico y Logan X-Tremo [CRG].cbr | cbr | 11.5 | RAR4 | RAR4 | no | 0x33 | 23 | 2 | 8 | 7 | 0.5 | 7 | 0.4 | 9 | 0.6 | 0.2 | 64 | ok |
| 115 | The Walking Dead/The Walking Dead 2003 #87 (2011).cbr | cbr | 11.5 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 12 | 8 | 0.5 | 6 | 0.3 | 8 | 0.5 | 0.2 | 65 | ok |
| 116 | Marvel/M4RV3L C0M1CS/PatrullaX Vol.2/021 PatrullaX Vol.2.cbr | cbr | 11.4 | RAR4 | RAR4 | no | 0x33 | 25 | 2 | 10 | 8 | 0.6 | 6 | 0.4 | 6 | 0.4 | 0.2 | 65 | ok |
| 117 | Marvel/M4RV3L C0M1CS/Los Dioses Perdidos/11- Los Dioses Perdidos.cbr | cbr | 11.4 | RAR4 | RAR4 | no | 0x33 | 23 | 2 | 9 | 2 | 0.5 | 1 | 0.5 | 1 | 0.5 | 0.0 | 233 | ok |
| 118 | The Walking Dead/The Walking Dead 2003 #44 (2007).cbr | cbr | 11.2 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 12 | 8 | 0.5 | 8 | 0.5 | 7 | 0.5 | 0.2 | 65 | ok |
| 119 | Marvel/M4RV3L C0M1CS/Doom 2099 Vol 1/Doom 2099 #39.cbr | cbr | 10.7 | RAR4 | RAR4 | no | 0x30 | 24 | 2 | 1 | 7 | 0.5 | 7 | 0.4 | 7 | 0.4 | 0.1 | 119 | ok |
| 120 | Marvel/M4RV3L C0M1CS/El tiempo se acaba/24 Miles Morales Ultimate Spider-Man 12.cbr | cbr | 10.4 | RAR4 | RAR4 | no | 0x33 | 20 | 2 | 5 | 9 | 0.5 | 4 | 0.2 | 8 | 0.5 | 0.2 | 65 | ok |
| 121 | Marvel/House Of M Vol. 1/4.- Iron Man House Of M #1.cbr | cbr | 10.1 | ZIP | ZIP | — | 8 | 26 | 0 | 2 | 3 | — | 2 | — | 2 | — | 0.1 | 190 | ok |
| 122 | Marvel/M4RV3L C0M1CS/Las Nuevas Aventuras de los X-Men (Forum)/Las Nuevas Aventuras De Los X-Men nº27.cbr | cbr | 9.5 | RAR4 | RAR4 | no | 0x30 | 25 | 2 | 1 | 1 | 0.3 | 6 | 0.4 | 5 | 0.3 | 0.1 | 95 | ok |
| 123 | Marvel/M4RV3L C0M1CS/Las Historias Jamás Contadas de Spiderman (Forum)/Las Historias Jamás Contadas de Spiderman Nº11.cbr | cbr | 9.3 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 7 | 1 | 0.4 | 1 | 0.4 | 1 | 0.4 | 0.1 | 154 | ok |
| 124 | Marvel/M4RV3L C0M1CS/Las Nuevas Aventuras de Spiderman Vol.2 (Forum)/Las nuevas aventuras de Spiderman vol2 06.cbr | cbr | 9.1 | RAR4 | RAR4 | no | 0x33 | 26 | 2 | 9 | 6 | 0.3 | 6 | 0.3 | 6 | 0.3 | 0.2 | 59 | ok |
| 125 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/255.cbr | cbr | 9.1 | RAR4 | RAR4 | no | 0x33 | 18 | 2 | 6 | 10 | 0.6 | 10 | 0.5 | 9 | 0.5 | 0.2 | 61 | ok |
| 126 | Mujeres Celebres Novaro/Mujeres Celebres 006 Nur-Mahal - Arcano9.cbr | cbr | 8.3 | RAR4 | RAR4 | no | 0x30 | 34 | 3 | 2 | 1 | 0.2 | 1 | 0.2 | 1 | 0.2 | 0.0 | 222 | ok |
| 127 | Mujeres Celebres Novaro/Mujeres Celebres 120 Helen Keller - Doncomic.cbr | cbr | 8.3 | RAR4 | RAR4 | no | 0x33 | 36 | 3 | 6 | 1 | 0.2 | 12 | 0.7 | 9 | 0.5 | 0.1 | 118 | ok |
| 128 | Marvel/M4RV3L C0M1CS/Execute Program/Execute Program #02.cbr | cbr | 8.2 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 6 | 6 | 0.4 | 5 | 0.3 | 6 | 0.3 | 0.1 | 61 | ok |
| 129 | Marvel/M4RV3L C0M1CS/Héroes de Alquiler Vol.1/Heroes de Alquiler nº06.cbr | cbr | 8.0 | RAR4 | RAR4 | no | 0x33 | 25 | 2 | 5 | 6 | 0.4 | 5 | 0.3 | 5 | 0.3 | 0.1 | 59 | ok |
| 130 | Marvel/M4RV3L C0M1CS/Execute Program/Execute Program #01.cbr | cbr | 7.7 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 5 | 6 | 0.4 | 6 | 0.3 | 5 | 0.3 | 0.1 | 59 | ok |
| 131 | Marvel/M4RV3L C0M1CS/Generacion X Volumen 1 (1994-2001)/Generation X 21.cbr | cbr | 7.7 | RAR4 | RAR4 | no | 0x33 | 23 | 2 | 3 | 1 | 0.4 | 5 | 0.3 | 6 | 0.3 | 0.1 | 90 | ok |
| 132 | Marvel/M4RV3L C0M1CS/La Patrulla X Vol.1/Patrulla X vol1 072.cbr | cbr | 7.5 | RAR4 | RAR4 | no | 0x33 | 23 | 2 | 4 | 6 | 0.4 | 5 | 0.3 | 6 | 0.3 | 0.1 | 58 | ok |
| 133 | Leyendas de America Novaro/038 EL ESPEJO REVELADOR - N. Bathes.cbr | cbr | 7.3 | RAR4 | RAR4 | no | 0x33 | 37 | 3 | 4 | 1 | 0.2 | 1 | 0.1 | 1 | 0.2 | 0.0 | 165 | ok |
| 134 | Mujeres Celebres Novaro/Mujeres Celebres 001 Amelia Earhart - N. Bathes.cbr | cbr | 7.3 | RAR4 | RAR4 | no | 0x33 | 35 | 3 | 5 | 1 | 0.2 | 2 | 0.1 | 1 | 0.2 | 0.0 | 169 | ok |
| 135 | Marvel/M4RV3L C0M1CS/Doom 2099 Vol 1/Doom 2099 #22.cbr | cbr | 7.2 | RAR4 | RAR4 | no | 0x30 | 23 | 2 | 1 | 2 | 0.3 | 1 | 0.3 | 1 | 0.3 | 0.0 | 303 | ok |
| 136 | Marvel/Spiderman/The Amazing Spiderman 700 Números/95 The Amazing Spider-Man #088.cbr | cbr | 7.0 | RAR4 | RAR4 | no | 0x33 | 20 | 2 | 5 | 6 | 0.4 | 6 | 0.3 | 6 | 0.3 | 0.1 | 61 | ok |
| 137 | Marvel/Spiderman/The Amazing Spiderman 700 Números/300 The Amazing Spider-Man #280.cbr | cbr | 7.0 | RAR4 | RAR4 | no | 0x33 | 23 | 2 | 4 | 6 | 0.3 | 6 | 0.3 | 5 | 0.3 | 0.1 | 57 | ok |
| 138 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/122.cbr | cbr | 7.0 | RAR4 | RAR4 | no | 0x33 | 18 | 1 | 6 | 7 | 0.4 | 7 | 0.4 | 6 | 0.4 | 0.1 | 58 | ok |
| 139 | Marvel/M4RV3L C0M1CS/La Patrulla X Vol.1/Patrulla X vol1 077.cbr | cbr | 6.7 | RAR4 | RAR4 | no | 0x33 | 21 | 1 | 7 | 5 | 0.3 | 5 | 0.3 | 6 | 0.3 | 0.1 | 58 | ok |
| 140 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/116.cbr | cbr | 6.7 | RAR4 | RAR4 | no | 0x33 | 18 | 2 | 6 | 6 | 0.4 | 6 | 0.4 | 7 | 0.4 | 0.1 | 59 | ok |
| 141 | Marvel/M4RV3L C0M1CS/D3adpool Sins of the Past/Sins of the Past #02.cbr | cbr | 6.6 | RAR4 | RAR4 | no | 0x33 | 23 | 2 | 5 | 5 | 0.3 | 5 | 0.3 | 6 | 0.4 | 0.2 | 31 | ok |
| 142 | Marvel/Spiderman/Amazing Spider-man Vol. 2/Amazing Spider-Man #27.cbr | cbr | 6.6 | RAR4 | RAR4 | no | 0x30 | 21 | 1 | 1 | 1 | 0.3 | 1 | 0.3 | 1 | 0.3 | 0.0 | 310 | ok |
| 143 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/227.cbr | cbr | 6.6 | RAR4 | RAR4 | no | 0x33 | 18 | 2 | 8 | 7 | 0.4 | 7 | 0.3 | 7 | 0.4 | 0.1 | 54 | ok |
| 144 | Marvel/M4RV3L C0M1CS/Onslaught/34 - IMPACTO2 - Hulk 016.cbr | cbr | 6.4 | RAR4 | RAR4 | no | 0x33 | 22 | 2 | 9 | 2 | 0.3 | 6 | 0.2 | 4 | 0.3 | 0.1 | 83 | ok |
| 145 | Marvel/Spiderman/Amazing Spider-man Vol. 2/Amazing Spider-Man #56.cbr | cbr | 6.4 | RAR4 | RAR4 | no | 0x30 | 22 | 1 | 1 | 1 | 0.3 | 1 | 0.3 | 1 | 0.2 | 0.0 | 294 | ok |
| 146 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/041.cbr | cbr | 6.2 | RAR4 | RAR4 | no | 0x33 | 18 | 1 | 7 | 6 | 0.3 | 6 | 0.4 | 6 | 0.3 | 0.1 | 46 | ok |
| 147 | Marvel/Spiderman/The Amazing Spiderman 700 Números/22 The Amazing Spider-Man #020.cbr | cbr | 6.2 | RAR4 | RAR4 | no | 0x33 | 21 | 2 | 14 | 5 | 0.3 | 5 | 0.3 | 5 | 0.3 | 0.1 | 58 | ok |
| 148 | Marvel/Spiderman/The Amazing Spiderman 700 Números/3 The Amazing Spider-Man #002.cbr | cbr | 6.0 | RAR4 | RAR4 | no | 0x33 | 28 | 2 | 4 | 4 | 0.2 | 2 | 0.2 | 3 | 0.2 | 0.1 | 61 | ok |
| 149 | Marvel/Spiderman/Amazing Spider-man Vol. 2/Amazing Spider-Man #10.cbr | cbr | 5.9 | RAR4 | RAR4 | no | 0x30 | 23 | 2 | 1 | 1 | 0.2 | 1 | 0.3 | 1 | 0.2 | 0.0 | 277 | ok |
| 150 | Marvel/M4RV3L C0M1CS/Generacion X Volumen 1 (1994-2001)/Generation X 12.cbr | cbr | 5.7 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 4 | 1 | 0.2 | 1 | 0.2 | 1 | 0.2 | 0.1 | 73 | ok |
| 151 | Marvel/Spiderman/The Amazing Spiderman 700 Números/102 The Amazing Spider-Man #094.cbr | cbr | 5.7 | RAR4 | RAR4 | no | 0x30 | 20 | 2 | 1 | 5 | 0.3 | 6 | 0.3 | 5 | 0.3 | 0.1 | 58 | ok |
| 152 | Marvel/M4RV3L C0M1CS/Los Caballeros de Pendragón (Forum)/Los Caballeros de Pendragón nº11.cbr | cbr | 5.1 | RAR4 | RAR4 | no | 0x33 | 25 | 2 | 4 | 6 | 0.2 | 5 | 0.3 | 4 | 0.2 | 0.1 | 54 | ok |
| 153 | Marvel/Los 4 Fantasticos (1-200 Vol1 USA)(de J.Kirby y S.Lee)(Ed.Marvel)(1961-78) /Los 4 Fantasticos 076 [MQ][por Orijua][CRG].cbr | cbr | 5.0 | RAR4 | RAR4 | no | 0x33 | 21 | 1 | 3 | 4 | 0.2 | 4 | 0.2 | 4 | 0.2 | 0.1 | 58 | ok |
| 154 | The Walking Dead/The Walking Dead 2003 #21 (2005).cbr | cbr | 4.6 | RAR4 | RAR4 | no | 0x33 | 24 | 2 | 9 | 4 | 0.2 | 3 | 0.1 | 3 | 0.2 | 0.1 | 50 | ok |
| 155 | Marvel/M4RV3L C0M1CS/M4rvel Saga 36 - Brand new day (2008)/19 The Amazing Spider-Man EXTRA 01.cbr | cbr | 4.1 | RAR4 | RAR4 | no | 0x33 | 9 | 1 | 7 | 7 | 0.5 | 6 | 0.4 | 7 | 0.4 | 0.1 | 64 | ok |
| 156 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/063.cbr | cbr | 4.1 | RAR4 | RAR4 | no | 0x33 | 18 | 1 | 4 | 4 | 0.2 | 5 | 0.3 | 4 | 0.2 | 0.1 | 54 | ok |
| 157 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/143.cbr | cbr | 4.1 | RAR4 | RAR4 | no | 0x33 | 18 | 1 | 4 | 4 | 0.2 | 7 | 0.4 | 5 | 0.2 | 0.1 | 49 | ok |
| 158 | Memin pinguin/(MEMIN ANTIGUO SEPIA)/311.cbr | cbr | 4.0 | RAR4 | RAR4 | no | 0x33 | 19 | 1 | 3 | 4 | 0.2 | 2 | 0.1 | 4 | 0.2 | 0.1 | 54 | ok |
| 159 | Marvel/Spiderman/The Amazing Spiderman 700 Números/509 The Amazing Spider-Man #475.cbr | cbr | 3.3 | RAR4 | RAR4 | no | 0x30 | 22 | 2 | 1 | 3 | 0.1 | 3 | 0.1 | 3 | 0.1 | 0.1 | 48 | ok |

<!-- conclusiones: escritas a mano; el generador conserva desde esta línea -->
## Conclusiones (29-30 de octubre)

1. **Censo de los 159 locales.** 141 RAR4 (todos NO sólidos), 2 RAR5 (no sólidos), 15 ZIP (7 de ellos con extensión .cbr) y 1 7z con extensión .cbr. Abren 158 de 159; el único fallo es el 7z, con el error claro previsto (`UnsupportedArchiveException`). Cero cifrados, cero multivolumen, cero cabeceras rotas. Ojo: son los 159 materializados de 9.399; la proporción de sólidos, RAR5 y 7z de la biblioteca entera puede ser otra (el censo real llega con el índice que se haga en la Mac).
2. **Sniffer.** El bit sólido de RAR4 está en los bytes 10-11 (HEAD_FLAGS del MAIN_HEAD, little-endian) con la máscara 0x0008 (MHD_SOLID): lo confirman la technote de RAR 4, las constantes de junrar (`BaseBlock.MHD_SOLID = 8`) y la CRC16 de un RAR real. En los 141 RAR4 el sniffer coincide con `getMainHeader().isSolid()` de junrar (0 discrepancias). Encontró los 8 disfrazados.
3. **Fallo de junrar 8.1.2 encontrado y esquivado.** En la primera corrida 2 de 141 RAR4 (Condorito 318 1998 y Biblioteca Marvel Doctor Extraño nº08) reventaron con `OutOfMemoryError` (heap de 512 MB). Causa, en el bytecode: `Unpack.doUnpack` llama a `unstoreFile()` para una entrada GUARDADA y, sin salir, cae también en `unpack29()`, que interpreta el búfer de la entrada anterior como tablas y a veces arranca un modelo PPMd de hasta 256 MB (`SubAllocator.startSubAllocator`). Arreglo: en RAR no sólidos las páginas guardadas se copian directamente del fichero comprobando su CRC32 (1.487 páginas en 50 RAR de la muestra). Tras el arreglo: 0 fallos en RAR. Sigue latente en sólidos con entradas guardadas (raro: WinRAR no lo hace por defecto). Vale la pena avisar a junrar.
4. **Sólidos.** No hay ninguno en la muestra (la propuesta ya contaba 0 de 141). junrar 8.1.2 en un sólido es correcto pero cuesta O(k) por página y reinicia desde la primera entrada al ir hacia atrás: en el RAR4 sólido sintético, pasar 60 páginas hacia atrás le hace leer 1.830 MB (n(n+1)/2) frente a 60 MB con la caché de sólidos (`SolidStager`), que descomprime en orden físico y deja cada página una vez. Coste de la caché: cada página se escribe dos veces (caché y destino) mientras el archivo está abierto; se borra al cerrar. Abrir un sólido en la página k sigue costando descomprimir todo hasta k (inevitable). Pendiente: repetir con un sólido COMPRIMIDO real (no hay `rar` en la Mac y no se descargó nada).
5. **Rendimiento (Mac i9-9980HK, Java 17).** Las cifras absolutas cambian casi 2× entre corridas según la carga de la Mac (otros frentes compilando a la vez): en esta corrida, una página suelta tarda p50 ~8 ms y p95 ~25 ms en RAR4, menos de 10 ms en ZIP; todo en orden, RAR4 con método 0x33 da una mediana de 66 MB/s (mín. 31), 0x35 63 MB/s, RAR5 59 MB/s y ZIP 139 MB/s. Con la Mac cargada, RAR4 0x33 bajó a ~25 MB/s. En el Helio G100 hay que contar con varias veces menos: una página comprimida de 2-3 MB puede tardar del orden de 0,1-0,5 s, así que el lector debe preparar 1-2 páginas por delante en segundo plano. Medirlo en la tablet con la sonda.
6. **Memoria.** Ventanas RAR4 de 128 KB a 4 MB y RAR5 de 1-2 MB: sobra memoria. El tope por defecto del diccionario (la mitad del heap) también es el tope del PPMd de junrar: en la tablet (heap de ~256 MB, tope de ~128 MB) un PPMd mayor da un error claro en vez de un OOM; en la Mac (tope de 256 MB) no frenó el fallo del punto 3.
7. **PDF.** No se puede probar en la JVM (`PdfRenderer` es de Android): queda para la tablet.
