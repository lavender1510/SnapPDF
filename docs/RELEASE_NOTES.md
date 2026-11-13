# SnapPDF 1.0.0 – Vorabversion

Erste native Android-Version für lokale Dokumentenscans mit Jetpack Compose und Material 3.

- CameraX-Aufnahme und Bildimport mit mehrseitigen Scans.
- Dokumentenerkennung, manuelle Ecken und Perspektivkorrektur.
- Standard-, Handschrift- und Kontrastmodus mit einstellbarer Intensität.
- Lokale PDFs in automatischem Format, A4 oder A5; drei Qualitätsstufen.
- Bibliothek mit Suche, Ordnern, Teilen, Export und PDF-Zusammenführung.
- Optionales mitgeliefertes OCR für gedruckten deutschen und englischen Text.
- Light/Dark/Systemdarstellung, dynamische Farben und Scan-Verknüpfung.
- Keine Registrierung und keine Internetberechtigung.

## Verifikation

- 4 JVM-Tests und 24 native Android-Tests erfolgreich; [CI-Nachweis](https://github.com/lavender1510/SnapPDF/actions/runs/43402329013).
- Debug-/Release-Build und Android Lint erfolgreich, 0 Lint-Fehler.
- Exakt die angebotene signierte APK auf Android 15 installiert und gestartet; [Installationsnachweis](https://github.com/lavender1510/SnapPDF/actions/runs/43399838735).
- Offline-OCR und durchsuchbare mehrseitige PDFs mit nativer Darstellung geprüft.
- Echtes fotografiertes Druckdokument in allen Scanmodi sowie synthetische schwache Linien, Schatten und Dokumentecken geprüft.
- [Prüfsumme, Signatur und vollständiger Verifikationsstand](VERIFICATION.md).

Tests mit physischen Smartphone-Kameras und verschiedenen echten Schulhandschriften stehen noch aus. Deshalb wird diese Version als Vorabversion veröffentlicht.

## Bekannte Grenzen

OCR erkennt schwer lesbare Handschrift und mathematische Formeln nicht zuverlässig. Die Bildseite bleibt erhalten. Automatische Ecken und heuristische Schärfe-/Lichtwerte sollten bei schwierigen Aufnahmen manuell kontrolliert werden. Eine automatische Garantie für vollständige Inhalte oder korrekte Leserichtung gibt es nicht.
