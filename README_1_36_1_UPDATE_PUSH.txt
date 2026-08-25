SalesAPP 1.36.1 - PUSH O NOWEJ WERSJI

Dodano:
- osobny topic FCM: salesapp_app_updates,
- obsługę wiadomości type=APP_UPDATE,
- powiadomienie o nowej wersji również przy zamkniętej aplikacji,
- kliknięcie powiadomienia otwiera istniejący mechanizm pobrania/instalacji APK z GitHub Releases,
- aplikacja ignoruje push, jeżeli wersja z powiadomienia nie jest nowsza niż zainstalowana,
- powiadomienia reklamacji i przedstawicieli pozostają na dotychczasowych tokenach i nie są mieszane z globalnym topicem aktualizacji.

WAŻNE:
Aby GitHub Release wysyłał push automatycznie, repozytorium SalesAPP-Releases musi mieć workflow wysyłający FCM na topic salesapp_app_updates.
Plik wzorcowy znajduje się w folderze GITHUB_RELEASE_PUSH.
