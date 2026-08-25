SalesAPP Android 1.36.0

ZMIANY

1. KONKURSY - NAZWY PRODUKTOW
- Android jest podpiety bezposrednio pod:
  PRODUKTY IMPORT -> KONKURSY_PRODUKTY
- Spreadsheet ID:
  1JumEbRtKkouHtXEG1Qf7us-Vwr5hL2eB4kFdnaMCHiU
- parser rozpoznaje aktualny uklad kolumn:
  KONKURS_ID, KONKURS_NAZWA, KOD, NAZWA, EAN, ..., MINIMUM, ..., JEDNOSTKA
- produkty sa filtrowane po KONKURS_ID
- zachowane zera wiodace w kodach (np. 00001)
- nie kopiujemy pelnego katalogu produktow do Firebase
- jezeli konkurs nie ma googleSheetUrl/googleSheetTab, aplikacja uzywa stalego
  aktualnego arkusza powyzej

UWAGA:
Arkusz Google musi byc mozliwy do odczytu z telefonu przez URL gviz.
Nie wolno umieszczac klucza service account w APK.

2. SPRAWDZANIE WERSJI
- w stopce jest przycisk:
  "Sprawdz aktualna wersje • 1.36.0"
- korzysta z istniejacego GitHubUpdater / SalesAPP-Releases
- pokazuje komunikat, gdy aplikacja jest aktualna

3. ODSWIEZ DANE
- przycisk nie przechodzi juz na ekran:
  "Ladowanie danych sprzedazowych..."
- aktualne dane zostaja na ekranie
- Firestore LIVE jest odswiezany w tle

4. REKLAMACJE / FCM
- aplikacja wypisuje telefon ze starych globalnych topicow:
  salesapp
  salesapp_test
  salesapp_stats_test
  salesapp_contests_test
- produkcyjne powiadomienia maja isc przez salesapp_devices / token FCM
  konkretnego UID / representativeCode
- COMPLAINT_UPDATE / COMPLAINT_DELETED sa dodatkowo filtrowane po:
  recipientUid
  recipientRepresentativeCode
  assignedTo / assignedRepresentativeCode
  reportedBy (fallback)
- usunieta reklamacja bez podanego odbiorcy nie jest wyswietlana

WAZNE DLA BACKENDU REKLAMACJI
Najpewniejszy format to DATA-ONLY FCM wyslany tylko na token przypisanej osoby:
type=COMPLAINT_UPDATE
recipientUid=<uid osoby przypisanej>
recipientRepresentativeCode=<PRZED...>
complaintId=<id>

Jesli backend wysyla pole notification do globalnego topicu, Android moze pokazac
je systemowo zanim kod aplikacji zdazy je odfiltrowac. Dlatego 1.36 usuwa stare
subskrypcje topicow; backend docelowo powinien wysylac tylko na token odbiorcy.

BUILD
Projekt jest gotowy do otwarcia w Android Studio.
Wersja:
versionCode 36
versionName 1.36.0


UWAGA O APK
Stary plik RELEASE/SalesAPP.apk z wersji 1.35 zostal usuniety z paczki,
zeby nie dalo sie go pomylkowo zainstalowac.
Nowy APK zbuduj z tego projektu w Android Studio / istniejacym skryptem release.
