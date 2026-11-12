# Tests und Verifikation

## Automatisierte Prüfungen

Ein vollständiges JDK und Android SDK 35 müssen eingerichtet sein. Im Projektverzeichnis:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Die Reports liegen unter `app/build/reports/tests/` und `app/build/reports/lint-results-debug.html`. Ein erfolgreicher Build bestätigt die Kompilierbarkeit und Paketierung, jedoch keine Qualität einer realen Kameraufnahme.

Für Android-Instrumentierung einen entsperrten Emulator oder ein per ADB autorisiertes Gerät verwenden:

```bash
adb devices
./gradlew connectedDebugAndroidTest
```

Die auf Android ausgeführten Tests sind insbesondere für OpenCV, Bitmaps, OCR, PDF und lokale Speicherung relevant. JVM-Tests allein können die nativen Android- und OpenCV-Komponenten nicht validieren. Der GitHub-Workflow baut und prüft ohne angeschlossene Kamera.

| Testklasse | Schwerpunkt |
| --- | --- |
| `QuadGeometryTest` | Gültige, gekreuzte, entartete und ungültige Ecken |
| `DocumentProcessorInstrumentedTest` | Synthetische Dokumentseiten, schwache Linien und Bildverarbeitung |
| `PhotographedDocumentTest` | Echtes fotografiertes Papier mit Schatten und Tabellengitter; alle Scanmodi |
| `DocumentRepositoryTest` | Entwurfswiederherstellung, durchsuchbare mehrseitige PDFs, Zusammenführung und Erhalt bei Fehlern |
| `RetakeRecoveryTest` | Fehlgeschlagene Neuaufnahme darf die bereits erfolgreiche Seite nicht ersetzen |
| `MotionSettingsTest` | Animationsmaßstab der tatsächlichen Compose-Komposition folgt der Einstellung auch nach Stop/Resume |

Quelle, Lizenz und Grenzen des fotografierten Testbilds sind in [TEST_FIXTURES.md](TEST_FIXTURES.md) dokumentiert.

## Verifikationsstand

Die erste Bereitstellung muss die tatsächlich ausgeführten Prüfungen in den Release Notes ausweisen. Ohne ergänzenden Nachweis bleiben Tests auf physischer Hardware, reale Schulunterlagen und das Zusammenspiel mit herstellerspezifischen Kameras offen. Eine synthetische Testseite ist kein Ersatz für echte Fotos.

## Manuelle Abnahme mit echten Dokumenten

Die Originalaufnahme und das exportierte PDF nebeneinander vergleichen. Besonders die Ränder, schwache Striche, Tabellengitter und Exponenten prüfen; ein weißer Hintergrund allein bedeutet noch keinen guten Scan.

| Dokument / Aufnahme | Prüfung |
| --- | --- |
| Gedrucktes A4-Arbeitsblatt | Vollständiger Inhalt, lesbare kleine Schrift, keine abgeschnittenen Ränder |
| Handschrift mit Kugelschreiber | Durchgängige dünne Striche und lesbare enge Zeilen |
| Schwache Bleistiftschrift | Handschrift-Modus testen; helle Linien müssen sichtbar bleiben |
| Tabelle und kariertes Papier | Gitterlinien und Ziffern nicht zusammenführen oder entfernen |
| Mathematische Formeln | Brüche, Wurzeln, Indizes und Exponenten erhalten |
| Schatten und seitliches Licht | Hintergrund aufhellen, Schrift im Schatten erhalten |
| Schräg fotografiertes Blatt | Alle vier Ecken prüfen, Perspektive entzerren, manuelle Korrektur testen |
| Verdeckte oder unklare Kanten | Keine blinde automatische Speicherung; vollständiges Bild manuell zuschneiden |
| Verwackelte Aufnahme | Warnung verständlich; erneute Aufnahme möglich |
| Dunkle Umgebung und Blitz | Hinweise, Fokus und alle verfügbaren Blitzmodi prüfen |
| Fünf oder mehr Seiten | Kamera bleibt bedienbar, Reihenfolge und Seitenzahl stimmen |
| JPG/PNG/WEBP aus verschiedenen Quellen | Import, EXIF-Drehung, Zuschnitt und Export prüfen |

## Funktions- und Zuverlässigkeitsfälle

1. Kamera erstmalig erlauben, verweigern, dauerhaft verweigern und nachträglich in Android freigeben. Zurück zur Startseite muss möglich bleiben.
2. Mit Flugmodus und deaktiviertem WLAN vom ersten Start an fotografieren, OCR ausführen, speichern, suchen und eine bereits installierte lokale PDF-App öffnen.
3. Einen mehrseitigen Scan erstellen; Seiten drehen, verschieben, löschen und ergänzen. A4, A5 und automatisches Format in einem unabhängigen Reader öffnen.
4. Standard, Handschrift und hohen Kontrast am selben Original vergleichen. Den Intensitätsregler verwenden und Originalerhalt kontrollieren.
5. OCR mit gedrucktem Deutsch einschließlich Umlauten und Englisch prüfen. Im PDF suchen und kopieren; OCR-Fehler mit dem sichtbaren Scan vergleichen. OCR deaktivieren und erneut exportieren.
6. App nach einer fertig verarbeiteten Seite beenden, aus der Übersicht entfernen und erneut öffnen. Den Entwurf fortsetzen. Explizites Verwerfen separat prüfen.
7. Sehr große Bilder und viele Seiten importieren. Fortschrittsanzeige und Rückmeldung beobachten; kein UI-Stillstand oder Speicherabsturz.
8. Beschädigte Datei importieren und einen externen Ordnerzugriff widerrufen. Fehlermeldungen müssen verständlich sein und bereits gespeicherte Dokumente erhalten bleiben.
9. Speicherplatz vor einem Export knapp werden lassen. Nach dem Freigeben von Platz den Entwurf erneut speichern.
10. Dokumente umbenennen, suchen, nach Name/Datum sortieren, in einen Ordner verschieben und mehrere PDFs vereinigen. Löschung bestätigen und abbrechen.
11. PDF teilen und über SAF exportieren. Einen vorhandenen Dateinamen erneut verwenden; die erste Datei darf nicht unbemerkt verschwinden.
12. Light, Dark, Systemdarstellung und dynamische Farben kontrollieren. Kleine und große Displays, Android-Schriftvergrößerung, deutsche und englische Beschriftung prüfen.
13. Home-Verknüpfung und App-Icon-Verknüpfung verwenden; bei vorhandener Scansitzung keine Seiten verlieren.
14. Temporäre Daten bereinigen, während ein Entwurf existiert. Der Entwurf und die Bibliotheks-PDFs müssen erhalten bleiben.

## Release-Prüfung

Vor jeder Veröffentlichung dieselbe signierte APK installieren, die angehängt wird. Signatur mit `apksigner verify`, Versionsnummer mit `aapt dump badging` und SHA-256-Prüfsumme kontrollieren. Ein Update über eine vorherige Version benötigt denselben Signierschlüssel. Keine Schlüssel, Passwörter, privaten Dokumente oder lokalen SDK-Pfade in Git einchecken.

