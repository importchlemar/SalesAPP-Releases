SALESAPP 1.34.0 — JEDNA APLIKACJA PRODUKCYJNA
================================================

OD TEJ WERSJI
-------------
Utrzymujemy tylko jedną aplikację: SalesAPP.
Nie publikujemy osobnego TEST dla użytkowników.

PRODUKCJA
---------
Firebase urządzenia:
salesapp_devices
salesapp_users/<uid>/devices

GitHub:
importchlemar/SalesAPP-Releases

Każdy release musi zawierać dokładnie:
SalesAPP.apk

Pierwszy release:
v1.34.0

Kolejne:
v1.34.0
v1.35.0
...

POWIADOMIENIE O NOWEJ WERSJI
----------------------------
Przy uruchomieniu SalesAPP sprawdza najnowszy GitHub Release.
Jeżeli jest nowsza wersja:
- Android pokazuje systemowe powiadomienie:
  "Dostępna nowa wersja SalesAPP"
- po dotknięciu otwiera się okno z przyciskiem AKTUALIZUJ,
- aplikacja pobiera SalesAPP.apk,
- uruchamia instalator Androida.

Dla niewykonanej aktualizacji powiadomienie może przypomnieć się ponownie
po około 6 godzinach.

WAŻNE — REPO GITHUB
--------------------
Updater nie przechowuje w APK prywatnego tokenu GitHub.
Dlatego repo SalesAPP-Releases musi być PUBLICZNE.
W repo nie musisz publikować kodu — mogą być tylko GitHub Releases z APK.

PODPIS / JEDNA APLIKACJA
------------------------
Android wymaga tego samego podpisu przy każdej aktualizacji.

Uruchom najpierw:
1_PRZYGOTUJ_STALY_PODPIS.bat

Skrypt najpierw kopiuje istniejący:
%USERPROFILE%\.android\debug.keystore

Jeżeli wcześniejsze SalesAPP było budowane na tym samym komputerze,
1.34.0 ma szansę wejść jako aktualizacja bez odinstalowania.

Jeżeli podpis obecnej aplikacji jest inny:
- Android odrzuci instalację,
- wtedy tylko RAZ odinstaluj starą aplikację,
- zainstaluj 1.34.0,
- od tej chwili kolejne wersje będą aktualizowały tę samą aplikację.

KONIECZNIE ZRÓB BACKUP
----------------------
signing\salesapp-release.jks
keystore.properties

Nie wrzucaj tych plików do GitHub.
Utrata klucza oznacza utratę możliwości aktualizacji tej samej aplikacji.

BUDOWANIE
----------
1. 1_PRZYGOTUJ_STALY_PODPIS.bat
2. 2_ZBUDUJ_PRODUKCYJNY_APK.bat

Gotowy:
RELEASE\SalesAPP.apk

PUBLIKACJA
----------
3_OPUBLIKUJ_AKTUALIZACJE_GITHUB.bat

lub wszystko:
4_PELNE_WYDANIE.bat

UWAGA TECHNICZNA
----------------
Wewnętrzny applicationId pozostaje zgodny z aktualnym klientem Firebase,
aby zachować obecne Firebase Auth / Firestore / FCM i google-services.json.
Dla użytkownika nazwa aplikacji to wyłącznie SalesAPP.
