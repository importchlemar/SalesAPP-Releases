SalesAPP v13 — Reklamacje
=========================

Reklamacje korzystają z istniejącego projektu Firebase SalesAPP i istniejącego
app/google-services.json.

Autoryzacja:
- Firebase Authentication e-mail + hasło,
- brak anonymous auth,
- UID jest stałą tożsamością użytkownika.

Profil:
- salesapp_users/{UID}
- role
- representativeCode
- clientScope
- active
- permissions

Reklamacja zapisuje w reportedBy:
- uid
- email
- name / displayName
- representativeCode
- role

FCM:
- salesapp_devices/{UID} zachowane dla zgodności z modułem Windows,
- salesapp_users/{UID}/devices/{deviceId} jako nowy zapis per urządzenie,
- handlowiec zapisuje się również do topicu wg kodu PRZED.

Wyszukiwanie kontrahentów:
- indeks sales_search_customers,
- fallback po customer.name / customer.code / customer.nip,
- normalizacja polskich znaków,
- kilka słów może być podanych w dowolnej kolejności.

Wymagane reguły:
- FIRESTORE_RULES_REKLAMACJE.rules
- STORAGE_RULES_REKLAMACJE.rules
