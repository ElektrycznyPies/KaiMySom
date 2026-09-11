# KaiMySom 
Wersja 1.1.1

Czy zdarzyło ci się jechać na motocyklu przez urokliwe miasteczko, którego nazwy nie znasz, bo nawigacja wyświetla tylko układ ulic, Biedronkę i szpital, ale nie nazwę miasta? 

Czy przejeżdżałaś kiedyś przez most i myślałaś: "Świetne miejsce na kajaki", ale nie wiedziałaś, co to za rzeka, bo nie było tabliczki? 

Czy chciałeś poznać nazwę pięknej puszczy, którą przemierzasz, aby kiedyś do niej wrócić, lecz na nawigacja pokazywała  zieloną plamę? 

Trzeba by się zatrzymać, zdjąć rękawice i grzebać w telefonie.

Nakładka nawigacyjna KaiMySom dla Androida służy głównie motocyklistom i innym zmotoryzowanym turystom w szybkim, wizualnym pozyskaniu danych bez konieczności zatrzymywania się: 
- przez jakie miasto przejeżdżam,
- jak nazywa się najbliższa rzeka lub kanał,
- jak nazywa się najbliższy kompleks leśny (las, puszcza, rezerwat, park narodowy - każdy, który posiada nazwę).

Informacje pojawiają się na przesuwalnej etykiecie nad innymi aplikacjami. Informacja o rzekach, lasach i obiektach przyrodniczych obejmuje także przybliżoną odległość, strzałkę realnego kierunku i kierunek świata. Użytkownik może wybrać, które kategorie pojawią się na etykiecie. Do poprawnego działania wymagane jest połączenie z internetem. Działa samodzielnie, niezależnie od aplikacji nawigacyjnych.  

# Wymagania i uprawnienia
- Android 8.0 lub nowszy, najlepiej 12.0 lub nowszy.
- Dostęp do internetu.
- Włączone usługi Google Play.
- Uprawnienie dokładnej lokalizacji.
- Uprawnienie do wyświetlania nad innymi aplikacjami.
- Działający geokoder systemowy.

# Instalacja
[Stąd pobierz **plik instalacyjny .apk**](https://github.com/ElektrycznyPies/KaiMySom/releases) - kliknij w plik kaimysom###.apk z najwyższym numerkiem wersji w nazwie. Zapisz go na telefonie, zainstaluj, zignoruj ostrzeżenia (plik pochodzi spoza sklepu Google) i nadaj uprawnienia, o które poprosi.

# Licencja
Do czasu ogłoszenia zmian w tym pliku na GitHubie aplikacja KaiMySom jest dostępna za darmo do celów prywatnych.

Chętnie wysłucham opinii i sugestii: 
- [Messenger](https://www.messenger.com/e2ee/t/9592393397506829)  
- [Telegram](https://web.telegram.org/a/#1916705323)

**(c) Dariusz Żukowski, 2026**

Aplikacja powstała częściowo przy użyciu GPT-6

# Info dla nerdów
Skąd pochodzą dane?

Z serwerów OpenStreetMap, których jsony zawierają dużo pożytecznych danych dla każdego odpytania geograficznego. Najciekawsza jest kategoria "Lasy, puszcze, przyroda": uwzględniłem tu lasy, lecz także wyspy i wysepki, plaże, parki narodowe, rezerwaty, obszary chronione różnej klasy, a także parki miejskie, ale tylko te, które mają ponad 8 hektarów. Nakładka podaje odległość do najbliższej granicy obszaru. Wszystkie te obiekty muszą mieć jakąś nazwę (Puszcza Bolimowska, Sierakowski Park Krajobrazowy itp.), żeby być brane pod uwagę, dzięki temu w nakładce pojawią się konkretne obiekty leśne i przyrodnicze, a nie obszary przypadkowych zalesień. 

Jak to działa?

- Miejscowości: aplikacja przekazuje pozycję GPS do geokodera i otrzymuje nazwę. Ponawia sprawdzenie zwykle po przemieszczeniu o 100 m, nie częściej niż co 10 sekund, na postoju - co około 5 minut. Przy błędzie chwilowo zachowuje poprzednią nazwę.
- Rzeki i przyroda: pobiera obiekty także nieco poza ustawionym promieniem - z zapasem 200–1500 m zależnie od prędkości. Urządzenie przechowuje ich geometrię i aktualizuje odległości bez każdorazowego pobierania danych.
- Wnętrze obszaru: może się zdarzyć, że wjedziesz w wielki obszar o promieniu większym niż maksymalny promień apki, dlatego żaden nowy obiekt nie zostanie wykryty, mimo że siedzisz w samym środku puszczy. Dlatego program bada także, czy już teraz nie jesteś w lesie lub innym wielkim obiekcie, by nie wyświetlać pustych kresek. 
- Żeby nazwy nie przeskakiwały: dotychczasowy obiekt pozostaje wybrany, dopóki konkurencyjny nie jest bliższy o ponad 100 m. Obiekt, w którego wnętrzu jesteś, ma pierwszeństwo przed odleglejszym.
- Dane o rzekach, lasach, parkach, wyspach i plażach pochodzą z OpenStreetMap i przechodzą przez Overpass API, a nazwę do współrzędnych dopasowuje geokoder urządzenia, który sam też obsługuje nazwy miast. 
