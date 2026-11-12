# Testbild: fotografierte Sudoku-Seite

`app/src/androidTest/assets/fixtures/opencv-sudoku.png` ist eine unveränderte Kopie von `samples/data/sudoku.png` aus **OpenCV 4.10.0**. Das Foto zeigt eine schräg aufgenommene Zeitungsseite mit ungleichmäßiger Beleuchtung und Tabellengitter. Es wird ausschließlich in der Test-APK mitgeliefert, nicht in der produktiven App.

- Quelle: [opencv/opencv, Tag 4.10.0](https://github.com/opencv/opencv/blob/4.10.0/samples/data/sudoku.png)
- Download: <https://raw.githubusercontent.com/opencv/opencv/4.10.0/samples/data/sudoku.png>
- SHA-256: `3cd9ab173f7178c0a16bba9f867062b58b5d60918d5aeb8ced85cba0b24c353a`
- Aktuelle OpenCV-Lizenz: [Apache 2.0](licenses/OpenCV-Apache-2.0.txt), Kopie der [Upstream-Lizenz](https://github.com/opencv/opencv/blob/4.10.0/LICENSE).
- Das Bild ist auch in älteren OpenCV-Veröffentlichungen enthalten; die historische [OpenCV-BSD-Lizenz](licenses/OpenCV-Legacy-BSD.txt) und die dortigen Copyright-Hinweise werden zusätzlich mitgeführt ([Quelle](https://github.com/opencv/opencv/blob/2.4/LICENSE)).

`PhotographedDocumentTest` verarbeitet das vollständige Foto mit allen drei Scanmodi. Er prüft binäre Ausgabe, einen plausiblen Anteil dunkler Pixel und den Erhalt der vier fotografierten Gitterränder. Das Testbild enthält Inhalt außerhalb des Gitters; der Test setzt deshalb bewusst das vollständige Bild als Zuschnitt. Eine gefundene Tabellenkontur belegt keine korrekte Erkennung der Papierkanten.

Diese Prüfung ergänzt die synthetischen Testfälle. Sie ist kein Nachweis für Fokus, Sensorqualität, Blitz oder Berechtigungsabläufe einer physischen Smartphone-Kamera und deckt keine realen Bleistiftnotizen ab.
