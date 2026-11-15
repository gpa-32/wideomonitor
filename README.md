# Wideodomofon – aplikacja na tablet Android (SIP, Akuvox)

Aplikacja działa stale w tle. Gdy bramofon zadzwoni (przez serwer SIP albo bezpośrednio na adres IP
tabletu), na wierzchu pojawia się obraz z kamery z przyciskami **Odbierz**, **Otwórz furtkę**,
**Odrzuć**. Po zakończeniu na ekran wraca aplikacja, która była otwarta wcześniej.
Cała konfiguracja odbywa się przez przeglądarkę.

## Telefon i tablet – jedna aplikacja

Ten sam plik APK działa na tablecie i na telefonie. Na wąskim ekranie (telefon) układ jest pionowy,
a panel www jest domyślnie wyłączony – całą konfigurację robi się z menu aplikacji:

- **Ustawienia** – wszystkie opcje (SIP, kamera, furtka, dźwięk, zachowanie, panel www).
- **Wgraj ustawienia kodem QR** – skanuje kod z innego urządzenia z tą aplikacją
  (menu „Pokaż kod QR z ustawieniami” albo sekcja „Kod QR” w panelu www tabletu)
  lub kod / link z panelu serwera Domofon SIP (pobiera konto SIP).
- **Uprawnienia** – na urządzeniu bez root nadaje po kolei: mikrofon, powiadomienia,
  wyświetlanie nad aplikacjami, pracę w tle bez oszczędzania baterii.
- **Dziennik i diagnostyka** – dziennik zdarzeń i test połączenia z kamerą.

Telefon poza siecią domową nie odbierze połączenia bezpośrednio po IP – potrzebna jest
rejestracja na serwerze SIP dostępnym z internetu, a obraz RTSP i otwieranie furtki przez HTTP
działają tylko, gdy telefon ma dostęp do sieci bramofonu (Wi-Fi w domu lub VPN).

## Instalacja

1. Pobierz `wideodomofon.apk` z zakładki **Releases** repozytorium i zainstaluj na tablecie.
2. Uruchom aplikację raz. Przy pytaniu Magisk/SuperSU o dostęp root wybierz **Zezwól na stałe**.
   Aplikacja sama nada sobie uprawnienia (mikrofon, wyświetlanie nad innymi aplikacjami,
   praca w tle) i uruchomi zewnętrzny watchdog.
3. Na ekranie zobaczysz adres panelu (`http://IP_TABLETU:8080`), login `admin` i hasło.
4. Otwórz panel z komputera lub telefonu i uzupełnij ustawienia.

Tablet powinien mieć stały adres IP (rezerwacja DHCP w routerze).

## Ustawienia bramofonu Akuvox

**Dzwonienie bezpośrednio po IP (bez serwera SIP)** – w bramofonie jako numer docelowy przycisku
wpisz adres IP tabletu (Intercom → Basic / Push Button; „Direct IP" musi być włączone).
Port SIP tabletu to domyślnie 5060.

**Przez serwer SIP** – w panelu aplikacji włącz „Rejestracja na serwerze SIP" i podaj dane konta.

**Przez serwer Domofon SIP (konto pobierane automatycznie)** – w panelu aplikacji, w sekcji
„Połączenie z serwerem Domofon SIP", wklej link skopiowany z panelu serwera i kliknij „Pobierz konfigurację":
- *link parowania* (grupa → „Rozpocznij parowanie" → „Kopiuj link parowania") – tablet sam zakłada sobie konto
  i dołącza do grupy dzwonienia; ten sam link działa na kolejnych tabletach przez 30 minut,
- *link urządzenia* (urządzenie → „Kopiuj link" pod kodem QR) – dla tabletu dodanego wcześniej ręcznie.

Link musi zaczynać się od `https://` (wyjątek: serwer w sieci lokalnej pod adresem IP).

**Obraz** – dwie możliwości:
- *Wideo w połączeniu SIP* – obraz przychodzi w ramach połączenia (kodek H.264 włączony w bramofonie).
- *Strumień RTSP* – `rtsp://IP_BRAMOFONU/live/ch00_0` (Intercom → RTSP → włącz serwer RTSP).
  Obraz jest od pierwszej sekundy dzwonienia; zalecane, gdy wideo SIP przed odebraniem się nie pojawia.

**Furtka** – dwie metody, można używać obu naraz:
- *HTTP* – w bramofonie: Access Control → Relay → Open Relay via HTTP (włącz, ustaw użytkownika i hasło).
  Adres: `http://IP_BRAMOFONU/fcgi/OpenDoor?action=OpenDoor&DoorNum=1`, użytkownik i hasło w osobnych
  polach panelu. Starsze oprogramowanie:
  `http://IP_BRAMOFONU/fcgi/do?action=OpenDoor&UserName=...&Password=...&DoorNum=1`.
- *DTMF* – kod (domyślnie `#`) i sposób wysyłania muszą być zgodne z ustawieniami bramofonu
  (Access Control → Relay → DTMF oraz Account → Advanced → DTMF: RFC2833 lub Info).
  Kod DTMF da się wysłać tylko w zestawionym połączeniu, dlatego naciśnięcie „Otwórz furtkę"
  podczas dzwonienia odbiera połączenie z wyciszonym mikrofonem, wysyła kod i rozłącza.

## Odporność na zawieszenia

| Poziom | Co robi |
|---|---|
| Usługa pierwszoplanowa `START_STICKY` | system podnosi usługę po ubiciu procesu |
| Start po włączeniu tabletu i po aktualizacji | `BootReceiver` |
| Alarm podtrzymujący co 5 min | uruchamia usługę, jeśli nie działa |
| Watchdog wewnętrzny | osobny wątek; gdy wątek główny nie odpowiada 40 s – restart procesu |
| Watchdog zewnętrzny (root) | skrypt poza aplikacją; brak tętna przez 90 s → `force-stop` i ponowny start |
| Kontrola SIP | brak rejestracji przez 3 min → restart stosu SIP |
| Kontrola serwera www | automatyczny restart, gdy przestanie działać |
| Limity połączenia | maks. czas dzwonienia i rozmowy, naprawa „wiszącego" stanu |
| Nocny restart (opcjonalny) | profilaktyczny restart o wybranej godzinie |

Dziennik zdarzeń jest widoczny w panelu www.

## Budowanie

Każdy `push` do gałęzi `main` uruchamia GitHub Actions, które buduje i podpisuje APK
oraz publikuje je w **Releases**. Klucz podpisu (`keystore/release.jks`) jest w repozytorium,
żeby kolejne wersje instalowały się jako aktualizacja bez utraty ustawień – repozytorium
powinno być **prywatne**.

Lokalnie: Android Studio (JDK 17) → otwórz folder projektu → Build → Build APK.

## Struktura kodu (`app/src/main/java/pl/wideodomofon/app`)

- `IntercomService` – usługa w tle: stan połączenia, watchdog, przywracanie poprzedniej aplikacji
- `SipManager` – obsługa SIP (Linphone SDK)
- `CallActivity` – ekran połączenia
- `RtspPlayer` – podgląd RTSP (libVLC – ten sam silnik co odtwarzacz VLC)
- `ServerLink`, `Provision` – pobieranie konta SIP z serwera Domofon SIP na podstawie linku
- `Gate` – otwieranie furtki przez HTTP (Basic/Digest)
- `WebServer` + `assets/index.html` – panel www
- `MainActivity`, `SettingsActivity`, `LogActivity`, `Fields` – menu aplikacji, ustawienia, dziennik
- `Cfg`, `L`, `Root`, `BootReceiver`, `App` – ustawienia (także eksport/import QR), dziennik, root, autostart

Biblioteka Linphone SDK jest na licencji GPLv3, libVLC – LGPL.
