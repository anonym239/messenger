# Notfall-Mesh

Messenger für Situationen **ohne Internet und ohne Mobilfunk** (z. B. im Wald, in den Bergen oder bei Netzausfall).
Handys mit dieser App verbinden sich automatisch direkt miteinander und **leiten Nachrichten für andere weiter**.
So entsteht ein Mesh-Netz: Jedes weitere Handy vergrößert die Reichweite.

## Funktionen

- **SOS-Knopf:** sendet einen Notruf mit GPS-Standort an alle Handys im Mesh. GPS funktioniert ohne Internet.
  Das SOS ist absichtlich unverschlüsselt, damit jeder Helfer es lesen kann. Es ist aber signiert und kann unterwegs nicht verfälscht werden.
- **Private Chats mit Ende-zu-Ende-Verschlüsselung:**
  - HPKE (RFC 9180) mit X25519, HKDF-SHA256 und ChaCha20-Poly1305, für jede Nachricht ein neuer Einmal-Schlüssel
  - Ed25519-Signatur des Absenders
  - Absender, Empfänger und Inhalt liegen **innerhalb** des Chiffrats. Weiterleitende Handys sehen nur verschlüsselte Bytes.
  - Nachrichten werden auf 512-Byte-Blöcke aufgefüllt, damit die Länge wenig verrät.
  - Private Schlüssel sind durch den Android-Keystore geschützt.
- **Store and Forward:** Wenn gerade niemand in Reichweite ist, wird die Nachricht bis zu 24 h gespeichert und weitergegeben, sobald ein Handy auftaucht.
  Pro Nachricht sind bis zu 15 Sprünge möglich.
- **Weiterleitung im Hintergrund:** Die Weiterleitung läuft automatisch, ohne dass man etwas tun muss. Android verlangt dabei eine dauerhafte Benachrichtigung („Notfall-Mesh aktiv“).

## Wie die Übertragung funktioniert

Die App nutzt die Funkchips des Handys, also Bluetooth, BLE und WLAN-Direkt, über Google *Nearby Connections*,
**aber ohne Internet, ohne Router und ohne Mobilfunknetz**. Reichweite pro Sprung: etwa 30–100 m, im Freien teils mehr.

Wichtig:
- Weiterleiten können **nur Handys, auf denen die App installiert ist**. Fremde Handys kann keine App heimlich als Verstärker benutzen.
- Die Mobilfunkfrequenzen (4G/5G) kann eine App nicht direkt ansteuern. Ein eigenes Senden darauf wäre in Deutschland auch verboten.
- Für Notfälle immer zuerst **112** versuchen. Der Notruf funktioniert auch über fremde Mobilfunknetze und ohne SIM-Karte.
  Neuere Handys (iPhone 14+, Pixel 9+, einige Samsung) haben außerdem **Satelliten-SOS**.
- Wer noch größere Reichweite (km) braucht: LoRa-Hardware mit [Meshtastic](https://meshtastic.org).
- Benötigt Google Play-Dienste und Android 8.0 oder neuer.

## Benutzung

1. APK unter **Releases** herunterladen und installieren. Dafür muss die Installation aus unbekannten Quellen erlaubt sein.
2. Alle Berechtigungen erlauben (Bluetooth, Geräte in der Nähe, Standort, Benachrichtigungen).
3. „Meine ID kopieren“ und die ID an Freunde geben. Deren ID unter „Kontakt hinzufügen“ eintragen.
4. Am besten von Angesicht zu Angesicht den **Fingerabdruck** vergleichen. So weißt du sicher, dass die ID echt ist.

## Neue Version veröffentlichen

Jeder Push auf `main` baut automatisch die APK (unter *Actions* → Lauf → *Artifacts*).
Für ein neues Release die Zahl in `version.txt` erhöhen (z. B. `1.0.0` → `1.1.0`) und auf `main` pushen.
GitHub Actions erstellt dann automatisch den Tag `v1.1.0` und ein Release mit `NotfallMesh-1.1.0.apk`.

### Einmalig: Signaturschlüssel einrichten (wichtig für Updates)

Android installiert ein Update nur, wenn es mit **demselben Schlüssel** signiert ist wie die alte Version.
Den Schlüssel einmal erzeugen und **niemals** ins Repo hochladen:

```bash
keytool -genkeypair -v -keystore release.jks -alias notfall -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks > release.jks.b64
```

Dann unter GitHub → *Settings* → *Secrets and variables* → *Actions* diese Secrets anlegen:

| Secret | Inhalt |
|---|---|
| `KEYSTORE_BASE64` | Inhalt von `release.jks.b64` |
| `KEYSTORE_PASSWORD` | Keystore-Passwort |
| `KEY_ALIAS` | `notfall` |
| `KEY_PASSWORD` | Schlüssel-Passwort |

Ohne diese Secrets wird mit einem wechselnden Debug-Schlüssel signiert. Dann muss man die App vor jedem Update deinstallieren.

## Projektstruktur

| Datei | Zweck |
|---|---|
| `Crypto.kt` | Schlüssel, Verschlüsselung, Signaturen |
| `Packet.kt` | Datenformat, das über Funk geht |
| `MeshService.kt` | Verbindungen, Weiterleitung, Zwischenspeicher, Benachrichtigungen |
| `MainActivity.kt` | Oberfläche (Jetpack Compose) |
