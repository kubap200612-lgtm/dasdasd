# Rynek Analiza 0.3.0 - auto rynek z analizą cen (Minecraft 1.21.4, Fabric)

Od 0.3.0 mod jest **samodzielnym auto rynkiem** - BK-Rynek nie jest już potrzebny (po pierwszym uruchomieniu możesz go usunąć).
Mod to teraz "centrum": własna lista przedmiotów (na start zaimportowana z BK), a przy niej:
- **snajper** - kupuje oferty z zakładki **Najnowsze** taniej niż cena skupu,
- **wystawianie** - co kilka minut wystawia kupione przedmioty po cenie sprzedaży,
- **anty-AFK** - co 4 minuty zamyka rynek, skacze 3 razy i wraca,
- **restarty rynku** - co 60 s w trakcie snajpienia (i od razu, gdy rynek się zatnie) zamyka `/ah` i otwiera go od nowa,
- **analiza cen** (bez zmian w stosunku do 0.2.9): sprzedaż = średnia 3 najtańszych x ilość, skup = rynek x 0.96 minus marża progu;
  **autopilot** co 12 minut analizuje ceny wszystkich przedmiotów (auto rynek stoi na czas skanu i sam wraca), a między skanami czyta
  okna rynku otwierane przez snajpera.

**Uczciwie:** wszystko było kompilowane i testowane tylko na atrapach API Minecrafta i na symulowanym serwerze (rynek, okno potwierdzenia,
`/wystaw`, skoki). Prawdziwy build zrobi GitHub Actions. Mod w tej wersji **nie był uruchomiony w grze** - pierwszy start zrób ostrożnie
(niski budżet, kilka przedmiotów z "Kup: TAK"), patrz "Pierwszy test w grze".

## Nowość w 0.3.0
- **Auto rynek** (klawisz **F9** albo zakładka **Auto rynek** w panelu) - opis niżej.
- **Własna lista przedmiotów** `config/rynek-analiza-przedmioty.json`. Przy pierwszym starcie mod sam importuje listę i ustawienia rynku
  z BK-Rynku (na czacie: "Zaimportowano z BK-Rynku N przedmiotów..."). Ponowny import: zakładka Auto rynek, "Importuj listę z BK"
  (drugi klik w ciągu 5 s potwierdza; poprzednia lista zostaje w `config/rynek-analiza-przedmioty-kopia.json`).
- **Dopasowanie i odczyt ceny przeniesione z BK** (nazwa z kolorem, lore z zakresami, enchanty, CMD, mikstury, pety, wzór ceny
  z profilu) - mod kupuje i liczy dokładnie te same oferty co BK, bez BK.
- **Saldo** z tablicy wyników ("Stan konta: ...") albo z czatu - dla limitu "skup najwyżej 70% salda" i dla snajpera.
- **Zakupy i sprzedaże** do historii i autopilota: zakupy zapisuje snajper, sprzedaże rozpoznaje z komunikatów serwera
  ("Twój przedmiot ... został kupiony ... za X$" itp. - te same zwroty co BK).
- W panelu nowa zakładka **Auto rynek**, w Ustawieniach nowa grupa "Auto rynek: ...", klawisze działają też przy otwartym rynku.
- Napisy "BK" w panelu i na czacie zamienione (ceny trafiają "na listę", a nie "do BK").

## Jak zbudować i zainstalować
1. Repo z plikami budowania (`build.gradle`, `gradle.properties`, `settings.gradle`, `.github/workflows/build.yml`) i folderem `src/`
   z tej paczki. Build leci w zakładce Actions, jar jest w artefakcie "rynek-analiza".
   - Na GitHubie (Add file -> Upload files) **przeciągnij cały folder `src`**, nie pojedyncze pliki. W repo musi być
     `src/main/java/pl/rynekanaliza/` z **18 plikami `.java`** i `src/main/resources/fabric.mod.json`.
   - Stary folder `src` najpierw usuń albo nadpisz w całości (w 0.3.0 doszły pliki AutoMarket, ItemMatcher, ItemStore, MarketItem, Wallet).
   - Dobry jar `rynek-analiza-0.3.0.jar` ma ok. 200-240 KB (0.2.9 miał ok. 130-150 KB). Jar ok. 1 KB = build bez plików moda.
2. Jar wrzuć do `mods/` (stary jar tego moda usuń). **Pierwsze uruchomienie zrób jeszcze z configiem BK na miejscu** (folder
   `config/BK-Mods/BK-Rynek` albo `config/bkrynek-config`) - mod zaimportuje z niego listę. Potem **usuń jar BK-Rynku** z `mods/`
   (dwa boty na jednym rynku by sobie przeszkadzały). Config BK może zostać - przyda się do ponownego importu.

## Auto rynek
**Start / stop:** klawisz **F9** (działa też przy otwartym rynku), przycisk w zakładce Auto rynek albo `/analiza auto`.
Panel zamyka się przy starcie (rynek potrzebuje ekranu). Na czacie: "[Auto rynek] Start: kupowanie WŁ (N przedm. z ceną skupu),
wystawianie WŁ, anty-AFK co 4 min, restart rynku co 60 s. Stop: F9." Gdy autopilot jest wyłączony, mod od razu ostrzega, że ceny
nie będą się same odświeżać co 12 minut.

**Pętla:**
1. Otwiera rynek komendą z ustawień (domyślnie `/ah`, z profilu BK) i ustawia sortowanie **Najnowsze**: PPM na przycisku sortowania
   (slot 53), aż w jego opisie zaznaczona (złota/żółta albo podkreślona) będzie opcja "Najnowsze". Gdy po 6 kliknięciach tego nie widać,
   pisze o tym i snajpi przy obecnym sortowaniu.
2. Przy każdej zmianie okna sprawdza wszystkie oferty. Kupuje ofertę, gdy:
   - pasuje do przedmiotu z listy z **"Kup: TAK"** i ceną skupu **większą niż 0** (skup 0 albo brak = **nigdy**; BK kupował wtedy wszystko),
   - jest **taniej** niż skup (jak w BK: "mniejsze niż", oferta równa skupowi - nie),
   - tryb **Unit** (domyślnie, z profilu BK): cena za sztukę, oferta ma co najmniej "ilość kupna" sztuk; tryb **Sum**: cena całej oferty,
     która ma dokładnie "ilość kupna" sztuk,
   - nie jest Twoja (nick w opisie oferty) i nie jest droższa niż saldo konta (gdy saldo jest znane),
   - pet/kostium: ma co najmniej "minimum dni" z listy.
   Przy kilku pasujących przedmiotach liczy się ten z najniższym skupem (jak BK).
3. Zakup: LPM na ofercie, a w oknie potwierdzenia mod **sprawdza, że to ten sam przedmiot i nie drożej** (gdy serwer pokaże inny
   przedmiot albo wyższą cenę - "Anuluję zakup ...", okno zamknięte). Przycisk potwierdzenia szuka po nazwie ("potwierdź", "kup"/"zakup",
   "akceptuj", "confirm", "buy", "tak"; pomija sam przedmiot i przyciski "anuluj"/"nie"/"wróć"), zapasowo slot z ustawień (z profilu BK: 11).
   Wynik z czatu ("Pomyślnie zakupiłeś...", "już sprzedany", "brak środków"...) albo z ekwipunku (przedmiot przybył).
   Brak środków = minuta przerwy w kupowaniu. Nieudana oferta jest pomijana przez 30 s, kupiona - przez minutę.
   Na czacie: "[Auto rynek] Kupiono: Anarchiczny hełm za 13000$ (skup 14000$)."
4. Co **200 ms** odświeża listę (PPM na slocie 0 - filtr w lewym górnym rogu, z profilu BK), ale nie częściej niż serwer odpowiada.
5. **Restart rynku**: co **60 s** snajpienia zamyka rynek, czeka **1,5 s** (z profilu BK: `restart_delay`) i otwiera go od nowa.
   Od razu - gdy okno nie zmienia się przez 10 s ("Rynek bez zmian przez 10 s - restart."), gdy rynek się nie otworzy (5 razy z rzędu = stop)
   albo gdy zatnie się okno potwierdzenia.
6. **Wystawianie** co **3 minuty** (pierwsze 5 s po starcie) i od razu przy pełnym ekwipunku: zamyka rynek i wystawia
   - przedmioty **kupione przez snajpera** (licznik "do wyst." przy przedmiocie) - zawsze,
   - wszystkie pasujące z ekwipunku, gdy przedmiot ma **"Wyst.: TAK"**.
   Cena = cena sprzedaży z listy (z analizy / autopilota) za "ilość sprzedaży" sztuk; mniejszy stack proporcjonalnie (np. 48 jabłek przy
   3000$ za 64 = 2250$). Przedmiot z plecaka jest na chwilę zamieniany z wybranym slotem paska (potem zamiana z powrotem), komenda
   z ustawień (domyślnie `/wystaw {quantity} {price}`). Przedmiot bez ceny sprzedaży nie jest wystawiany. "Limit wystawionych" =
   następne wystawianie za 10 minut.
7. **Anty-AFK** co **4 minuty**: zamyka rynek, **3 skoki**, wraca na rynek.
8. Gdy otworzysz inne okno (czat, ekwipunek, panel) - auto rynek czeka i wraca po jego zamknięciu. Prawy Shift przy otwartym rynku
   zamyka rynek i otwiera panel. Analiza (Twoja albo skan autopilota) wstrzymuje auto rynek i wznawia go po sobie.
   Rozłączenie = stop. Pełny ekwipunek (i nic do wystawienia) = nie kupuje, dopóki nie zrobi się miejsce.

**Zakładka Auto rynek:** start/stop, przełączniki Kupowanie / Wystawianie / Anty-AFK, "Analiza co 12 min (autopilot)" i import z BK.
Pod spodem stan ("otwiera rynek", "snajpi", "kupuje", "wystawia", "anty-AFK (skoki)", "restart rynku", "czeka (otwarte inne okno)",
"wstrzymany (analiza)"), liczniki (kupiono, wydano, wystawiono, restarty,
nieudane), za ile następne wystawianie / skoki / restart / analiza, lista przedmiotów z cenami i przełącznikami
**"Kup: TAK/NIE"** i **"Wyst.: TAK / kupione"** (kliknięcie zmienia i zapisuje) oraz dziennik (najnowsze na górze).

**Ustawienia (grupa "Auto rynek"):** komenda rynku, odświeżanie (ms, 100-5000), restart co (s, 0 = nie), restart po zacięciu (s),
przerwa przy restarcie (ms), wystawianie co (min), anty-AFK co (min) i liczba skoków, skup za sztukę (tryb Unit), sloty
odświeżania / sortowania / potwierdzenia, nazwa przycisku potwierdzenia, komenda wystawiania. Do tego klawisz auto rynku.

## Import z BK-Rynku
Mod szuka profili BK w `config/BK-Mods/BK-Rynek/auto_market_configs/*.json` (BK 3.0) i listy `servers[].prices` w głównym configu
(`config/BK-Mods/BK-Rynek/bkrynek-config.json` albo plik z `selected.txt`), a także w starym miejscu `config/bkrynek-config/`.
Bierze **aktywny profil** (`defaultProfile`; gdy jest w kilku plikach - najnowszy plik), a bez niego profil z Anarchii albo najnowszy.
Z profilu przechodzą też ustawienia: komenda rynku, sloty odświeżania / sortowania / potwierdzenia, nazwa potwierdzenia, wzór ceny
(`lore_regex`, `lore_seller_regex`), tryb Unit/Sum, komenda wystawiania i przerwa przy restarcie.
Pola przedmiotów jak w BK: nazwa z kolorami, id, lore, zakazane lore, enchanty (mapa `enchantments`), zakazane enchanty, CMD,
ilość kupna (`requiredCount` / `minBuyCount`), ilość sprzedaży (`sellCount` / `minSellCount`), skup (`maxPrice`), sprzedaż (`sellPrice`),
kupowanie / sprzedawanie, minimum dni peta, mikstury. Klucze historii i wykresów się nie zmieniają (nazwa bez kolorów | id) - wykresy,
historia i stan autopilota z 0.2.x zostają.

## Panel (GUI)
- Klawisz **prawy Shift** (domyślnie) otwiera/zamyka panel, **F8** = start/stop analizy, **F9** = auto rynek. Klawisze zmienisz
  w zakładce **Ustawienia** (kliknij przycisk i naciśnij klawisz; Delete/Backspace = brak, Esc = anuluj) albo w Sterowaniu Minecrafta.
- Zakładki: **Przedmioty**, **Auto rynek**, **Autopilot**, **Historia**, **Ustawienia** (na wąskim ekranie skróty nazw).
- **Przedmioty**: wszystkie przedmioty z listy na jednej przewijanej liście (kółko, pasek po prawej, PageUp/PageDown/Home/End).
  Wyszukiwarka, "Start analizy" (z wpisaną frazą tylko pasujące) i "Stop". W wierszu: nazwa, trend, **Śr. 3**, **Sprzedaż** (cena
  z listy; "551$/16" = 551$ za wystawkę 16 szt.), pole **Skup ($)** i **Wykres** (rozwija pod wierszem wykres i pasek autopilota:
  "Auto: TAK/NIE", "Min. sprzedaż", "Max skup").
- **Autopilot**: włączanie, tryb (próba / na żywo), następny skan, statystyki dnia, progi skupu, saldo i dziennik decyzji.
- **Historia**: zakupy i sprzedaże ("Zakupy" / "Wg przedmiotów"), "Wyczyść historię".
- **Ustawienia**: klawisze, szybkość analizy, min. ofert do ceny, podatek, skup (mnożnik, % salda, progi), wznawianie auto rynku po
  analizie i przerwa przed/po analizie (3 s), ulepszenia %, komponenty, historia cen, obserwacja rynku, autopilot, auto rynek.
- Pliki: ustawienia `config/rynek-analiza.json`, lista `config/rynek-analiza-przedmioty.json`, autopilot `config/rynek-analiza-autopilot.json`,
  historia zakupów `config/rynek-analiza-historia.json`, historia cen `config/rynek-analiza-history.json`,
  diagnostyka `config/rynek-analiza-diagnostyka.txt`.

## Autopilot (analiza co 12 minut)
**Włączanie.** Zakładka Autopilot -> "Włącz autopilota" (albo w zakładce Auto rynek "Analiza co 12 min"). Startuje w trybie
**PRÓBNYM** - liczy, ale nie zmienia cen na liście. **NA ŻYWO**: przycisk "Tryb: PRÓBNY" i drugi klik w ciągu 5 s. Dopiero na żywo
analiza co 12 minut aktualizuje ceny, po których kupuje snajper i wystawia auto rynek.

**Kiedy skanuje.** Co **12 minut** (od startu poprzedniego skanu) - **wszystkie przedmioty z listy**. Tylko gdy auto rynek pracuje,
nie jest w trakcie zakupu / wystawiania / skoków i nie masz otwartego innego okna. Na czas skanu auto rynek stoi (przerwa 3 s przed
i po) i potem sam wraca. Po skanie auto rynek pracuje co najmniej 2 minuty. **Ponowny skan po zakupach**: gdy snajper kupi ten sam
przedmiot 3 razy od jego ostatniego skanu, autopilot skanuje go od razu (najwcześniej 30 s po poprzednim skanie).
Między skanami autopilot czyta okna rynku otwierane przez snajpera: gdy co najmniej 2 cudze oferty są tańsze od Twojej, obniża sprzedaż.

**Cena sprzedaży** (za całą wystawkę): najtańsza cudza oferta minus 1$, nie niżej niż podłoga (koszt / 0.97 x 1.02, 85% zwykłej ceny,
Twoje "Min. sprzedaż"); pomyłkowo tanie oferty pomijane; jedna tańsza oferta = czeka, od 2 = obniża; spadek ponad 20% musi się powtórzyć;
w górę najwyżej 10% naraz. Twoje oferty (nick w opisie) się nie liczą.
**Cena skupu** jak przy zwykłej analizie (niżej). **Zabezpieczenia na żywo:** budżet dzienny (25 000$; 0 = nic nie kupuje - wyłącza
"Kup" przedmiotów do jutra), hamulec strat (20% budżetu), max 3 niesprzedane zakupy na przedmiot, jeden zakup najwyżej 70% salda.
**Tryb próbny**: oferty tańsze niż wyliczony skup są "wirtualnymi zakupami" rozliczanymi po 2 h.

## Historia zakupów i sprzedaży
Każdy zakup snajpera (cena, sztuki) zapisuje się zawsze, także przy wyłączonym autopilocie. **Sprzedaż** - dopiero gdy przedmiot
naprawdę się sprzeda (komunikat serwera o sprzedaży; przedmiot po cenie wystawki x 0.97 albo samej cenie, także po cenach z ostatniej
doby, albo po nazwie w komunikacie). Gdy nie da się ustalić na pewno - nie jest wpisywana. Zysk po podatku 3%, strata na minus.
Widok "Wg przedmiotów": sprzedane z kupionych, wydane, zysk, średni czas do sprzedaży. "Wyczyść historię" z potwierdzeniem i kopią.

## Cena sprzedaży (analiza)
Analiza (Start / F8) dla każdego przedmiotu: `/ah search <nazwa>` -> sortowanie "Najniższej ceny" -> cena za sztukę z każdej pasującej
oferty -> `sprzedaż = floor( średnia 3 najtańszych za sztukę x ilość sprzedaży )`. Mniej ofert niż "Min. ofert do ceny" (3) = bez zmian.
Zapasowe frazy, gdy po pełnej nazwie nie ma żadnego przedmiotu tego id ("Anarchiczny hełm II" -> "Anarchiczny hełm" -> "Anarchiczny").
Przedmioty niestackowalne - skan do 3 ofert; stackowalne - wszystkie strony.

## Co dzieje się podczas analizy
Gdy auto rynek pracuje (skan autopilota albo Twoja analiza):
1. Auto rynek wstrzymany, jego okno rynku zamknięte, **przerwa** (domyślnie 3 s), żeby spóźnione odpowiedzi serwera nie mieszały się
   z analizą. Gdy auto rynek dalej by pracował - analiza się poddaje ("Auto rynek nie dał się zatrzymać..."), zamiast się nakładać.
2. Analiza przedmiotów (wyżej). Pomiar (Śr. 3, najtańsza oferta, liczba ofert, czas) trafia do historii (wykresy), cena sprzedaży na listę.
3. Rynek zamknięty, **przerwa** (te same 3 s; "Analiza zakończona. Auto rynek wróci za 3 s."), lista zapisana i auto rynek rusza
   od nowa (otwiera rynek i ustawia Najnowsze). Długość przerwy: Ustawienia, "Przerwa przed i po analizie, gdy działa auto rynek (s)"
   (0-15 s). Gdy auto rynek nie pracował, analiza rusza od razu.

**Przedmioty o tej samej nazwie** (np. 5 x "Zaklęta książka") są rozróżniane po pierwszej linii lore (potem CMD / enchanty) - w panelu
np. "Zaklęta książka [Regeneracji I]", każdy z osobnym wykresem. Własna fraza wyszukiwania (`search` w `config/rynek-analiza.json`)
wyłącza zapasowe frazy dla tego przedmiotu.

## Cena skupu
`skup (za sztukę) = cena rynku x 0.96 x (1 - marża progu)` - analiza i autopilot liczą tak samo.

| cena za sztukę | marża | przykład | zostaje na czysto |
|---|---|---|---|
| do 100$ | 8,5% | złote jabłko 70$ -> skup 61,48$ | ok. 6,40$ za sztukę |
| do 1 000$ | 33% | diamentowa część 300$ -> skup 192,96$ | ok. 97$ |
| do 15 000$ | 5% | Anarchiczna I 9 999$ -> skup 9 119$ | ok. 579$ |
| drożej | 9% | Anarchiczna II 22 000$ -> skup 19 219$ | ok. 2 120$ |

Przy granicy progu marża zmienia się płynnie. Skup nigdy wyżej niż sprzedaż po podatku z min. zyskiem, niż 70% salda i niż "Max skup".
Przedmiot z "Auto: NIE" zostaje przy swoim skupie. W trybie Sum skup dotyczy całej oferty "ilość kupna" sztuk. Skup wpiszesz też ręcznie
w polu **Skup ($)** (zapis ok. 1 s po ostatnim znaku).

## Dopasowanie i cena - jak w BK
- Najpierw id przedmiotu, potem zakazane lore, potem nazwa bez kolorów (bez wielkości liter, czcionka "small caps" jak zwykłe litery).
  Gdy nazwa na liście ma kolor (np. `§4Anarchiczny hełm`), pierwszy kolor nazwy oferty i jej pogrubienie/podkreślenie też muszą się
  zgadzać - podróbka w innym kolorze nie przejdzie (`§f` = bez wymagania koloru).
- Wymagane lore: każda część (rozdzielone `;` albo nową linią) musi być w opisie przedmiotu; zakres `Dodatkowe obrażenia 0-100%`
  pasuje do liczby z zakresu; linie z ceną, sprzedawcą, "kliknij", wygaśnięciem są pomijane.
- Enchanty z mapy `enchantments` (min. poziom; z przedmiotu albo z opisu, np. "Ochrona V"), zakazane enchanty, CMD, efekty mikstur.
  Z opisu liczy się tylko pełna nazwa: "Ochrona przed ogniem V" to nie "Ochrona", "Morskie szczęście III" to nie "Szczęście" (fortuna).
- **Ulepszenia %** ("Pomijaj ulepszenia %", domyślnie TAK): analiza i autopilot liczą 3 najtańsze przedmioty tej nazwy niezależnie
  od linii z %. **Snajper kupuje dokładnie wg listy** (z "Dodatkowe obrażenia 14%" w lore kupi tylko 14% - żeby kupował każdy %,
  wpisz zakres `Dodatkowe obrażenia 0-100%`).
- Cena oferty jak w BK: wzór z profilu (`lore_regex`, np. `(?i).*Koszt.*?([\d.,]+(?:mld|[km])?).*`), pomija linie "kosztuje ... sztuk",
  sprzedawcę, cenę w nawiasie bierze pierwszą; liczby "20 370", "3,289", "1.5k", "2mld".
- Diagnostyka przy braku ofert: ile przedmiotów tego id było, ile odrzuconych i dlaczego (np. "inny kolor/styl nazwy (&6 zamiast &4)"),
  a pełny opis odrzuconej oferty w `config/rynek-analiza-diagnostyka.txt`.

## Podgląd na rynku
- W trakcie analizy nad rynkiem pasek z ikoną i nazwą analizowanego przedmiotu, numerem w kolejce, stroną i liczbą ofert;
  3 najtańsze oferty (idą do ceny sprzedaży) mają **żółtą** ramkę. Po policzeniu w czacie: "Użyte oferty: str.1 r1 k1 = 950/szt ...".
- Wyłączenie podglądu: Ustawienia albo `"showOverlay": false`.

### Znaczek "Rynek Analiza: działa"
Przy każdym oknie rynku, w lewym górnym rogu ekranu (tylko rysuje - niczego nie klika):
- pierwsza linia: stan auto rynku ("auto rynek: snajpi, kupiono 3"),
- **zielony "działa"** - mod odczytał okno (ile cudzych ofert z listy, najtańsza za sztukę, "zapisano do wykresu", "autopilot sprawdził"),
  **żółty "czytam okno..."** - nowe okno jeszcze nie odczytane, **czerwony** - lista niedostępna albo błąd odczytu,
  **szary "nie obserwuje"** - wyłączone "Obserwuj rynek, gdy działa auto rynek" albo "Zapisuj historię cen" (i autopilot),
- "bez zapisu: nie od najtańszych" jest normalne przy sortowaniu Najnowsze (snajper) - takie okno nie idzie do wykresu,
- w trakcie analizy tylko "analiza trwa" / "skan autopilota".
Gdy auto rynek pracuje, a rynek jest zamknięty (np. skoki, wystawianie): jedna linia na ekranie gry - stan auto rynku, ostatni odczyt
i autopilot ("skan wszystkich za 8 min (25)"). Chowa się pod F1. Wyłączenie: Ustawienia, "Znaczek 'działa'".

## Komendy (opcjonalnie)
`/analiza start [fragment]`, `/analiza stop`, `/analiza lista`, `/analiza auto` (start/stop auto rynku).

## Pierwszy test w grze
1. Start gry z configiem BK: na czacie komunikat o imporcie; w panelu (prawy Shift) zakładka Przedmioty pokazuje listę.
   Usuń jar BK-Rynku z `mods/` i uruchom grę ponownie.
2. W zakładce Auto rynek zostaw "Kup: TAK" tylko przy 1-2 tanich przedmiotach, ustaw im skup wyraźnie niżej niż rynek (pole Skup).
3. F9: rynek ma się otworzyć i przełączyć na **Najnowsze** (dziennik: "Sortowanie: Najnowsze."). Jeśli zostaje "Nie widzę zaznaczonej
   opcji sortowania" - przyślij zrzut opisu przycisku sortowania.
4. Pierwszy zakup: sprawdź, że mod trafił w przycisk potwierdzenia. Jeśli pisze "Nie widzę przycisku potwierdzenia" - wpisz w Ustawieniach
   slot albo nazwę przycisku. Jeśli zakup przeszedł, a dziennik pisze "brak potwierdzenia zakupu" - przyślij treść komunikatu serwera
   (mod rozpoznaje zakup też po ekwipunku).
5. Wystawianie: po zakupie, w ciągu 3 minut, mod wystawi przedmiot po cenie sprzedaży z listy - sprawdź cenę wystawki na rynku.
6. Anty-AFK: po 4 minutach 3 skoki. Restart rynku co 60 s (dziennik w zakładce Auto rynek).
7. Autopilot na żywo dopiero, gdy powyższe działa; zacznij od niskiego budżetu.

## Szybkość analizy
Bot czeka na faktyczną odpowiedź serwera (okno przestało się zmieniać). Poziomy: Bezpieczna, Szybka (domyślna), Maksymalna.
Gdy serwer zacznie odrzucać kliknięcia - niższy poziom; dla auto rynku większe "odświeżanie co (ms)".

## Historia cen
`config/rynek-analiza-history.json`, do ok. 1000 punktów na przedmiot. Źródła: skany z analizy (domyślnie jedyne na wykresie)
i obserwacje okien rynku - zapisywane tylko wtedy, gdy okno pokazuje prawdziwe minimum (jedna strona albo strona 1 od najtańszych),
więc okna snajpera z sortowaniem "Najnowsze" zwykle nie są zapisywane. Obserwacje pokażesz na wykresie przełącznikiem
"Wykresy: też obserwacje rynku".
