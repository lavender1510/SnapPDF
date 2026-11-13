# Verifikation von SnapPDF 1.0.0

Stand: 13. November 2026. Die Veröffentlichung ist eine installierbare Vorabversion.

| Prüfung | Ergebnis |
| --- | --- |
| Kotlin-/Android-Build | Debug und signiertes Release erfolgreich |
| JVM-Tests | 4 bestanden |
| Android-Instrumentierung | 24 bestanden, 0 Fehler, 0 übersprungen; Android 15 / API 35 |
| Android Lint | Debug und Release: 0 Fehler; 24 Hinweise, hauptsächlich verfügbare Bibliotheksupdates |
| Reale Bilddaten | Fotografierte Zeitungsseite mit Schatten und Tabellenlinien in allen drei Scanmodi geprüft |
| Offline-Pfad | JPEG-Import → OpenCV → mitgelieferte OCR → durchsuchbare mehrseitige PDF → nativer PDF-Renderer |
| Produktions-APK | Genau die angehängte Datei auf Android 15 installiert und erfolgreich gestartet |
| Datenschutz | Installiertes Paket ohne INTERNET und ACCESS_NETWORK_STATE; kein Konto erforderlich |
| Signatur | apksigner erfolgreich; RSA 3072, APK-Signaturschema v2; nicht debuggable |
| 16-KB-Speicherseiten | ELF-LOAD-Alignment sämtlicher 12 ARM64-/x86_64-Bibliotheken ≥ 16 KB |

- [Build, Unit-, Lint- und Android-Testlauf](https://github.com/lavender1510/SnapPDF/actions/runs/43402329013)
- [Installation und Start der signierten Produktions-APK](https://github.com/lavender1510/SnapPDF/actions/runs/43399838735)
- [Exakter Build-Quellstand](https://github.com/lavender1510/SnapPDF/tree/3c2d46e4a573fec6e1080997b335fd55cf5b4bca)

Nach diesem Build wurden ausschließlich Tests und Dokumentation geändert. Der Anwendungscode und die Build-Konfiguration entsprechen unverändert dem getesteten APK-Quellstand.

## Dateiintegrität

Datei: `SnapPDF-v1.0.0.apk`

SHA-256:

```text
a201f283b56ac96fa9aa136598b207249a01e280e8fed24dd41be3318d53c767
```

SHA-256 des öffentlichen Signierzertifikats:

```text
404cdf2df406f39338ec971af157898d326fc8ef924d5cde086d64596cb05ad7
```

Der private Signierschlüssel ist weder Teil des Repositorys noch der GitHub-Release-Dateien. Er muss für spätere installierbare Updates sicher aufbewahrt werden.

## Noch nicht abgenommen

Physische Smartphone-Kameras, herstellerspezifischer Autofokus/Blitz und unterschiedliche echte Bleistift-/Kugelschreiber-Schulhandschriften wurden nicht auf Hardware abgenommen. Synthetische schwache Linien und ein echtes fotografiertes Druckdokument sind automatisiert geprüft. Die manuelle Abnahmematrix steht in [TESTING.md](TESTING.md). Die Texterkennung für schwer lesbare Handschrift und mathematische Formeln ist nicht zuverlässig; die sichtbare Bildseite bleibt erhalten.
