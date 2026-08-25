SALESAPP 1.34.0 — FIREBASE LIVE FIX
=====================================

NAPRAWIONE
----------
1. Listener Firebase LIVE NIE uruchamia się przed logowaniem.
2. Listener startuje dopiero, gdy:
   - FirebaseAuth.currentUser istnieje,
   - profil salesapp_users/<uid> został odczytany,
   - profil jest active=true,
   - rola użytkownika została zaakceptowana.
3. Po WYLOGUJ wszystkie listenery danych SalesAPP są natychmiast usuwane.
4. Callback przychodzący po wylogowaniu jest ignorowany.
5. Przy zamykaniu Activity listenery są usuwane.
6. Fałszywy komunikat:
   Firebase LIVE: PERMISSION_DENIED
   nie pojawia się już na ekranie logowania.
7. USTAWIENIA:
   tekst "arkusz USTAWIENIA" został zastąpiony:
   "Ustawienia są zapisywane w Firebase LIVE i obowiązują wszystkich użytkowników."
8. Zapis ustawień globalnych wymaga faktycznie aktywnej sesji ADMIN.
9. Ekran logowania pokazuje SalesAPP 1.34.0.

FIRESTORE RULES
---------------
Ta wersja zakłada opublikowane reguły, w których:

match /salesapp_settings/{documentId} {
  allow read: if activeUser();

  allow create, update:
    if documentId == 'global'
    && activeUser()
    && profile().role == 'ADMIN';

  allow delete: if false;
}

Nie otwieramy Firestore dla użytkowników niezalogowanych.

AKTUALIZACJA
------------
versionCode = 34
versionName = 1.34.0

Po zbudowaniu gotowy APK opublikuj w:
importchlemar/SalesAPP-Releases

Tag:
v1.34.0

Asset:
SalesAPP.apk

Aplikacje 1.33.0 i starsze wykryją 1.34.0 jako nową wersję.
