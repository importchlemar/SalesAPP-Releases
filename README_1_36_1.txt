SalesAPP Android 1.36.1

NAPRAWA PRODUKTOW KONKURSU

Przyczyna braku produktow w 1.36.0:
- refreshContestProducts zapisywal cache z podpisem:
  productDownloadSignature + sheetUrl + tab
- contestWithProducts odczytywal cache porownujac tylko:
  productDownloadSignature
- podpisy nigdy nie byly rowne
- produkty byly prawidlowo pobrane, ale ekran zawsze dostawal pusta liste

1.36.1:
- jeden wspolny podpis cache dla zapisu i odczytu
- nazwa: displayName -> name -> kod
- minimum: target -> minimum
- nadal czyta:
  PRODUKTY IMPORT -> KONKURSY_PRODUKTY
- filtr po KONKURS_ID pozostaje
- dodano contestProductsDebug w wewnetrznym JSON-ie diagnostycznym

Wersja:
versionCode 37
versionName 1.36.1

Po instalacji:
1. wyloguj/zaloguj sie
2. Konkurs
3. Odśwież dane
Powinny pojawic sie kod, pelna nazwa, EAN i minimum.
