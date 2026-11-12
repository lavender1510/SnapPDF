# SnapPDF 1.0.0

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

Vor der Veröffentlichung durch die tatsächlich ausgeführten Build-, Test- und Signaturprüfungen ergänzen. Reale Gerätetests und Scanqualität nicht allein aus einem erfolgreichen Build ableiten.

## Bekannte Grenzen

OCR erkennt schwer lesbare Handschrift und mathematische Formeln nicht zuverlässig. Die Bildseite bleibt erhalten. Automatische Ecken und heuristische Schärfe-/Lichtwerte sollten bei schwierigen Aufnahmen manuell kontrolliert werden. Eine automatische Garantie für vollständige Inhalte oder korrekte Leserichtung gibt es nicht.

