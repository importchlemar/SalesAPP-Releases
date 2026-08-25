SalesAPP 1.35.0 - GROUPS FIX

Zmiana dotyczy tylko grupowania sprzedaży.

ZASADA:
- PRZEDSTAWICIELE = rozpoznani przedstawiciele SalesAPP/Optima,
- BIURO = KAŻDY pozostały operator,
- WSZYSCY = przedstawiciele + jedna zbiorcza pozycja BIURO.

Przykładowo do BIURO trafiają:
WMS, WMS2, NP, MONIKA, PRZEMEK, MAGAZYN, NIEPRZYPISANE
oraz każdy inny operator, który nie jest przedstawicielem.

Rozpoznawanie przedstawicieli:
1. konfiguracja representatives z Firebase,
2. aktywni użytkownicy z rolą HANDLOWIEC,
3. kody PRZED1, PRZED2, ... / PRZEDSTAWICIEL1, ...
4. starsze kody WOJTEK i MC.

Nie zmieniano logiki Firebase LIVE, konkursów, reklamacji ani synchronizacji.
