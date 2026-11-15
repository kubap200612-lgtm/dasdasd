package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Auto rynek (zastępuje BK-Rynek): snajper ofert z zakładki "Najnowsze", wystawianie kupionych przedmiotów, anty-AFK
 * i restarty rynku. Ceny bierze z listy przedmiotów (maxPrice = skup, sellPrice = sprzedaż), które wpisuje analiza
 * i autopilot (co 12 min skan wszystkich przedmiotów - wtedy auto rynek stoi, a potem sam wraca).
 *
 * Pętla (co tick gry):
 *  - otwiera rynek komendą (domyślnie /ah), ustawia sortowanie "Najnowsze" (PPM na przycisku sortowania, aż ta opcja
 *    będzie zaznaczona),
 *  - przy każdej zmianie okna sprawdza oferty: przedmiot z listy (dopasowanie jak w BK), cena za sztukę (tryb "Unit")
 *    albo cała oferta o ilości kupna (tryb "Sum") NIŻSZA niż skup; skup 0 albo brak = nigdy nie kupuje; własne oferty,
 *    oferty droższe niż saldo konta i niedawno nieudane są pomijane,
 *  - kupno: LPM na ofercie, w oknie potwierdzenia sprawdza, że to ten sam przedmiot i nie drożej, klika "Potwierdź"
 *    (szukany po nazwie, zapasowo slot z ustawień), wynik z czatu albo z ekwipunku,
 *  - co amRefreshMs odświeża listę (PPM na slocie odświeżania),
 *  - co amRestartSec zamyka rynek, czeka amRestartPauseMs i otwiera go od nowa; tak samo, gdy okno nie zmienia się
 *    przez amStuckSec (zacięcie), nie otwiera się albo zatnie się okno potwierdzenia,
 *  - co amSellMin minut (i przy pełnym ekwipunku) zamyka rynek i wystawia przedmioty: kupione przez snajpera (licznik
 *    toSell) albo wszystkie z włączonym "Wystawiaj"; "/wystaw ilość cena" z ceny sprzedaży z listy,
 *  - co amAfkMin minut zamyka rynek, skacze amAfkJumps razy i wraca (anty-AFK),
 *  - gdy gracz otworzy inne okno (czat, ekwipunek, panel) - czeka; po jego zamknięciu wraca na rynek.
 */
public final class AutoMarket {
    private AutoMarket() {}

    enum State { OFF, OPEN, WAIT_OPEN, SORT, SORT_WAIT, SNIPE, BUY_WAIT, CONFIRM, RESULT, SELL, AFK, RESTART, HOLD, PAUSED }

    /** Zegar (podmieniany w testach). */
    static LongSupplier clock = System::currentTimeMillis;

    private static boolean on, paused;
    static State state = State.OFF;
    private static long stateAt, waitUntil;

    // rynek
    private static long sessionAt, lastChangeAt, lastRefreshAt, nextSellAt, nextAfkAt;
    private static int lastSig;
    private static boolean changedSinceRefresh, scanNeeded, fullWarned;
    private static int openFails, sortClicks, sortBaseSig, buyTimeouts;
    private static boolean sortConfirmed, sortGaveUp;

    /** Próba zakupu jednej oferty. */
    static final class Attempt {
        MarketItem item;
        ItemStack stack;
        String name = "";
        double total, limit;
        int count, slot, baseInv, confirmClicks;
        String id = "";
        long clickAt, confirmAt;
        boolean reclicked;
    }

    static Attempt cur;

    // komunikaty serwera (czas ostatniego komunikatu danego rodzaju)
    private static long chatOkAt = -1, chatFailAt = -1, chatNoMoneyAt = -1, chatListOkAt = -1, chatListLimitAt = -1;
    private static long noMoneyUntil, listLimitUntil;
    private static boolean listingBroken;
    private static final Map<String, Long> skipOffers = new HashMap<>();
    private static final Map<String, Long> notedOnce = new HashMap<>();

    /** Jedna wystawka: slot ekwipunku, przedmiot, ilość, cena całej wystawki. */
    static final class SellJob {
        final int inv;
        final MarketItem item;
        final int qty;
        final long price;

        SellJob(int inv, MarketItem item, int qty, long price) {
            this.inv = inv;
            this.item = item;
            this.qty = qty;
            this.price = price;
        }
    }

    private static final ArrayDeque<SellJob> sellQueue = new ArrayDeque<>();
    private static SellJob selling;
    private static int sellStep, savedSlot = -1, sellBaseCount, swappedFrom = -1;
    private static long sellAt;

    private static int jumpsDone, afkStep;
    private static long afkAt;

    /** Liczniki do zakładki "Auto rynek". */
    static final class Stats {
        long startedAt;
        int bought, listed, restarts, failed, afk;
        double spent, listedValue;
    }

    static final Stats stats = new Stats();
    private static final ArrayDeque<String> log = new ArrayDeque<>();

    // ---------- sterowanie ----------
    public static boolean enabled() {
        return on;
    }

    /** Czy pracuje (włączony i nie wstrzymany na analizę). */
    public static boolean running() {
        return on && !paused;
    }

    /** W trakcie zakupu, wystawiania albo skoków (wtedy skan autopilota czeka). */
    public static boolean busy() {
        return running() && (state == State.BUY_WAIT || state == State.CONFIRM || state == State.RESULT || state == State.SELL || state == State.AFK);
    }

    static AnalizaConfig cfg() {
        AnalizaConfig c = RynekAnalizaMod.config();
        return c != null ? c : new AnalizaConfig();
    }

    public static void toggle() {
        if (on) stop("klawisz");
        else start();
    }

    public static void start() {
        if (on) return;
        ItemStore st = BkBridge.store();
        if (st == null || st.items().isEmpty()) {
            say("Lista przedmiotów jest pusta - zaimportuj ją z BK albo dodaj przedmioty (panel, zakładka \"Auto rynek\").");
            return;
        }
        AnalizaConfig c = cfg();
        long now = clock.getAsLong();
        on = true;
        paused = false;
        cur = null;
        sellQueue.clear();
        selling = null;
        skipOffers.clear();
        notedOnce.clear();
        openFails = buyTimeouts = 0;
        sortConfirmed = sortGaveUp = false;
        listingBroken = false;
        noMoneyUntil = listLimitUntil = 0;
        nextSellAt = now + 5_000L;
        nextAfkAt = now + c.amAfkMin * 60_000L;
        stats.startedAt = now;
        setState(State.OPEN, 0);
        int buyable = 0;
        for (MarketItem it : st.items()) if (!Boolean.FALSE.equals(it.buyEnabled) && it.maxPrice > 0) buyable++;
        say("Start: kupowanie " + onOff(c.amBuy) + " (" + buyable + " przedm. z ceną skupu), wystawianie " + onOff(c.amSell)
                + ", anty-AFK " + (c.amAntiAfk ? "co " + c.amAfkMin + " min" : "WYŁ") + ", restart rynku "
                + (c.amRestartSec > 0 ? "co " + c.amRestartSec + " s" : "WYŁ") + ". Stop: " + keyName() + ".");
        Autopilot ap = RynekAnalizaMod.autopilot();
        if (ap == null || !ap.enabled()) {
            say("Uwaga: autopilot jest wyłączony - ceny nie będą się same odświeżać co " + c.apIntervalMin + " min (zakładka Autopilot).");
        }
    }

    public static void stop(String why) {
        if (!on) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        releaseJump(mc);
        restoreSlot(mc);
        if (mc != null && mc.player != null && (state == State.CONFIRM || state == State.RESULT)
                && mc.currentScreen instanceof HandledScreen<?> hs && marketScreen(hs) == null) {
            mc.player.closeHandledScreen(); // nie zostawiamy otwartego okna potwierdzenia
        }
        on = false;
        paused = false;
        cur = null;
        sellQueue.clear();
        selling = null;
        state = State.OFF;
        say("Stop" + (why == null || why.isBlank() ? "" : " (" + why + ")") + ". Kupiono: " + stats.bought + " za "
                + AnalizaScreen.fmt(stats.spent) + "$, wystawiono: " + stats.listed + ".");
    }

    /** Analiza zatrzymuje auto rynek na czas skanu (dawne "zatrzymaj BK"). */
    public static void pause() {
        if (!on || paused) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        releaseJump(mc);
        restoreSlot(mc);
        paused = true;
        cur = null;
        sellQueue.clear();
        selling = null;
        state = State.PAUSED;
        note("Wstrzymany na analizę.");
    }

    /** Po analizie: wraca na rynek. */
    public static void resume() {
        if (!on || !paused) return;
        paused = false;
        setState(State.OPEN, 300);
        note("Wznowiony po analizie.");
    }

    private static void setState(State s, long delayMs) {
        long now = clock.getAsLong();
        state = s;
        stateAt = now;
        waitUntil = now + Math.max(0, delayMs);
    }

    // ---------- komunikaty serwera ----------
    /** Każdy komunikat z czatu serwera (zdarzenie GAME). */
    public static void onChat(String raw) {
        if (raw == null || raw.isEmpty()) return;
        String p = ItemMatcher.plainChat(raw);
        if (p.startsWith("[auto rynek]") || p.startsWith("[analiza]")) return;
        long now = clock.getAsLong();
        if (ItemMatcher.any(p, ItemMatcher.NO_MONEY)) chatNoMoneyAt = now;
        else if (ItemMatcher.any(p, ItemMatcher.BUY_FAIL)) chatFailAt = now;
        else if (ItemMatcher.any(p, ItemMatcher.BUY_OK)) chatOkAt = now;
        if (ItemMatcher.any(p, ItemMatcher.LIST_LIMIT)) chatListLimitAt = now;
        else if (ItemMatcher.any(p, ItemMatcher.LIST_OK)) chatListOkAt = now;
    }

    // ---------- główna pętla ----------
    public static void tick(MinecraftClient mc) {
        if (!on) return;
        long now = clock.getAsLong();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            stop("rozłączono");
            return;
        }
        if (paused) return;
        if (MarketAnalyzer.isRunning()) return; // analiza ma rynek dla siebie
        try {
            step(mc, now);
        } catch (Throwable t) {
            note("Błąd: " + t);
            t.printStackTrace();
            restart(mc, "błąd (" + t.getClass().getSimpleName() + ")", now);
        }
    }

    private static void step(MinecraftClient mc, long now) {
        Screen scr = mc.currentScreen;
        HandledScreen<?> market = marketScreen(scr);
        boolean loading = scr instanceof HandledScreen<?> hs0 && MarketAnalyzer.isMarket(hs0); // okno rynku bez zawartości (jeszcze się wczytuje)
        if (scr != null && market == null && !loading && !expectsOtherScreen(scr)) {
            if (state != State.HOLD) {
                releaseJump(mc);
                if (state == State.SELL) restoreSlot(mc);
                note("Czekam - otwarte inne okno.");
                setState(State.HOLD, 0);
            }
            return;
        }
        if (state == State.HOLD) {
            if (cur != null && chatOkAt >= cur.clickAt) bought(cur, now, "czat");
            cur = null;
            sellQueue.clear();
            selling = null;
            setState(State.OPEN, market != null ? 0 : 500);
            return;
        }
        if (now < waitUntil) return;
        switch (state) {
            case OPEN -> open(mc, market, now);
            case WAIT_OPEN -> waitOpen(mc, market, now);
            case SORT -> sort(mc, market, now);
            case SORT_WAIT -> {
                if (market == null) setState(State.OPEN, cfg().amRestartPauseMs);
                else if (MarketAnalyzer.signature(market) != sortBaseSig || now - stateAt > 1500) setState(State.SORT, 100);
            }
            case SNIPE -> snipe(mc, market, now);
            case BUY_WAIT -> buyWait(mc, scr, market, now);
            case CONFIRM -> confirm(mc, scr, market, now);
            case RESULT -> result(mc, scr, market, now);
            case SELL -> sell(mc, scr, now);
            case AFK -> afk(mc, now);
            case RESTART -> setState(State.OPEN, 0);
            default -> {}
        }
    }

    /** W trakcie zakupu okno inne niż rynek to okno potwierdzenia; po "/wystaw" - okno, którego nie umiemy obsłużyć. */
    private static boolean expectsOtherScreen(Screen scr) {
        if (!(scr instanceof HandledScreen<?>)) return false;
        return state == State.BUY_WAIT || state == State.CONFIRM || state == State.RESULT || state == State.SELL;
    }

    static HandledScreen<?> marketScreen(Screen s) {
        if (!(s instanceof HandledScreen<?> hs)) return null;
        return MarketAnalyzer.isMarket(hs) && MarketAnalyzer.ready(hs) ? hs : null;
    }

    private static void open(MinecraftClient mc, HandledScreen<?> market, long now) {
        if (market != null) {
            marketOpened(market, now);
            return;
        }
        if (mc.currentScreen != null) { // niewczytane okno rynku - po 5 s zamknąć i otworzyć od nowa
            if (now - stateAt > 5000) {
                mc.player.closeHandledScreen();
                setState(State.OPEN, cfg().amRestartPauseMs);
            }
            return;
        }
        mc.player.networkHandler.sendChatCommand(cfg().amCommand);
        setState(State.WAIT_OPEN, 0);
    }

    private static void waitOpen(MinecraftClient mc, HandledScreen<?> market, long now) {
        if (market != null) {
            if (now - stateAt >= 100) marketOpened(market, now);
            return;
        }
        if (now - stateAt <= 5000) return;
        openFails++;
        if (openFails >= 5) {
            stop("rynek nie otworzył się 5 razy z rzędu - sprawdź komendę rynku w ustawieniach");
            return;
        }
        restart(mc, "Nie udało się otworzyć rynku - restart (" + openFails + "/5)", now);
    }

    private static void marketOpened(HandledScreen<?> market, long now) {
        openFails = 0;
        sessionAt = now;
        lastChangeAt = now;
        lastRefreshAt = now;
        lastSig = MarketAnalyzer.signature(market);
        scanNeeded = true;
        sortClicks = 0;
        setState(sortGaveUp ? State.SNIPE : State.SORT, 0);
    }

    private static int sortSlot(ScreenHandler h) {
        int size = MarketAnalyzer.containerSize(h);
        int s = cfg().amSortSlot;
        return s >= 0 && s < size ? s : size - 1;
    }

    private static void sort(MinecraftClient mc, HandledScreen<?> market, long now) {
        if (market == null) {
            setState(State.OPEN, cfg().amRestartPauseMs);
            return;
        }
        ScreenHandler h = market.getScreenHandler();
        int slot = sortSlot(h);
        if (ItemMatcher.sortSelected(h.slots.get(slot).getStack(), cfg().amSortKeyword)) {
            if (!sortConfirmed) note("Sortowanie: Najnowsze.");
            sortConfirmed = true;
            startSnipe(market, now);
            return;
        }
        if (sortClicks >= 6) {
            say("Nie widzę zaznaczonej opcji sortowania \"" + cfg().amSortKeyword + "\" - snajpię przy obecnym sortowaniu.");
            sortGaveUp = true;
            startSnipe(market, now);
            return;
        }
        click(mc, market, slot, 1); // PPM = następna opcja sortowania
        sortClicks++;
        sortBaseSig = MarketAnalyzer.signature(market);
        setState(State.SORT_WAIT, 0);
    }

    private static void startSnipe(HandledScreen<?> market, long now) {
        lastSig = MarketAnalyzer.signature(market);
        lastChangeAt = now;
        scanNeeded = true;
        changedSinceRefresh = true; // świeże okno - następne odświeżenie po zwykłym odstępie
        setState(State.SNIPE, 0);
    }

    // ---------- snajper ----------
    private static void snipe(MinecraftClient mc, HandledScreen<?> market, long now) {
        AnalizaConfig c = cfg();
        if (market == null) {
            boolean loading = mc.currentScreen instanceof HandledScreen<?> hs && MarketAnalyzer.isMarket(hs);
            if (loading && now - lastChangeAt < c.amStuckSec * 1000L) return; // okno rynku przeładowuje się
            note(loading ? "Okno rynku się nie wczytało - otwieram ponownie." : "Rynek zamknięty - otwieram ponownie.");
            if (loading) mc.player.closeHandledScreen();
            setState(State.OPEN, c.amRestartPauseMs);
            return;
        }
        int sig = MarketAnalyzer.signature(market);
        if (sig != lastSig) {
            lastSig = sig;
            lastChangeAt = now;
            changedSinceRefresh = true;
            scanNeeded = true;
        }
        PlayerInventory inv = mc.player.getInventory();
        boolean full = inv.getEmptySlot() < 0;
        if (full) {
            if (c.amSell && !listingBroken && now >= listLimitUntil && startSell(mc, now, "pełny ekwipunek")) return;
            if (!fullWarned) say("Pełny ekwipunek - nie kupuję, dopóki nie zrobi się miejsce.");
            fullWarned = true;
        } else {
            fullWarned = false;
        }
        if (c.amAntiAfk && now >= nextAfkAt) {
            startAfk(mc, now);
            return;
        }
        if (c.amSell && !listingBroken && now >= nextSellAt && now >= listLimitUntil) {
            if (startSell(mc, now, null)) return;
            nextSellAt = now + c.amSellMin * 60_000L;
        }
        if (c.amRestartSec > 0 && now - sessionAt >= c.amRestartSec * 1000L) {
            restart(mc, "Restart rynku (co " + c.amRestartSec + " s).", now);
            return;
        }
        if (now - lastChangeAt >= c.amStuckSec * 1000L) {
            restart(mc, "Rynek bez zmian przez " + c.amStuckSec + " s - restart.", now);
            return;
        }
        if (scanNeeded && !full) {
            scanNeeded = false;
            Attempt a = findOffer(mc, market, now);
            if (a != null) {
                buy(mc, market, a, now);
                return;
            }
        }
        if (now - lastRefreshAt >= c.amRefreshMs && (changedSinceRefresh || now - lastRefreshAt >= 2000)) {
            int slot = c.amRefreshSlot < MarketAnalyzer.containerSize(market.getScreenHandler()) ? c.amRefreshSlot : 0;
            click(mc, market, slot, 1); // PPM = odśwież
            lastRefreshAt = now;
            changedSinceRefresh = false;
        }
    }

    /** Przedmioty do kupna pogrupowane po id (pusty klucz = dowolne id). */
    private static Map<String, List<MarketItem>> buyables() {
        Map<String, List<MarketItem>> out = new LinkedHashMap<>();
        ItemStore st = BkBridge.store();
        if (st == null) return out;
        for (MarketItem it : st.items()) {
            if (Boolean.FALSE.equals(it.buyEnabled) || !(it.maxPrice > 0)) continue; // skup 0 = nigdy (BK kupował wtedy wszystko)
            out.computeIfAbsent(ItemMatcher.material(it), k -> new ArrayList<>()).add(it);
        }
        return out;
    }

    /**
     * Wartość oferty do porównania ze skupem (jak w BK): tryb "Unit" - cena za sztukę, gdy ofert ma co najmniej "ilość
     * kupna" sztuk; tryb "Sum" - cena całej oferty, gdy ma dokładnie tyle sztuk. NaN = oferta nie spełnia warunku ilości.
     */
    static double compareValue(MarketItem it, double total, int count, boolean globalUnit) {
        boolean unit = Boolean.TRUE.equals(it.unitMode) || globalUnit;
        int min = it.buyCount();
        if (unit ? count < min : count != min) return Double.NaN;
        return unit ? total / Math.max(1, count) : total;
    }

    /** Pierwsza oferta w oknie (od lewego górnego rogu = najnowsze), którą warto kupić; null = brak. */
    static Attempt findOffer(MinecraftClient mc, HandledScreen<?> market, long now) {
        AnalizaConfig c = cfg();
        if (!c.amBuy || now < noMoneyUntil) return null;
        Map<String, List<MarketItem>> byId = buyables();
        if (byId.isEmpty()) return null;
        skipOffers.values().removeIf(t -> t < now);
        ScreenHandler h = market.getScreenHandler();
        int end = Math.max(0, MarketAnalyzer.containerSize(h) - 9);
        double balance = -2;
        for (int i = 0; i < end; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty()) continue;
            List<MarketItem> cands = new ArrayList<>(byId.getOrDefault(ItemMatcher.itemId(st), List.of()));
            cands.addAll(byId.getOrDefault("", List.of()));
            if (cands.isEmpty()) continue;
            double total = ItemMatcher.offerPrice(st);
            if (!(total > 0)) continue;
            String id = MarketObserver.identity(st, total);
            if (skipOffers.containsKey(id)) continue;
            if (MarketAnalyzer.isOwn(st)) continue;
            MarketItem best = null;
            double bestCmp = 0;
            for (MarketItem it : cands) {
                double cmp = compareValue(it, total, st.getCount(), c.unitModeGlobal);
                if (Double.isNaN(cmp) || cmp > it.maxPrice) continue;
                if (!ItemMatcher.matches(st, it) || !ItemMatcher.meetsMinDays(it, st)) continue;
                if (best == null || it.maxPrice < best.maxPrice) {
                    best = it;
                    bestCmp = cmp;
                }
            }
            if (best == null || !(bestCmp < best.maxPrice)) continue;
            if (balance == -2) balance = BkBridge.walletBalance();
            if (balance > 0 && total > balance) {
                noteOnce("money:" + id, "Za mało pieniędzy na " + ItemMatcher.stripColors(st.getName().getString()) + " za " + AnalizaScreen.fmt(total)
                        + "$ (saldo " + AnalizaScreen.fmt(balance) + "$).", now);
                continue;
            }
            Attempt a = new Attempt();
            a.item = best;
            a.stack = st.copy();
            a.name = ItemMatcher.stripColors(st.getName().getString());
            a.total = total;
            a.limit = best.maxPrice;
            a.count = Math.max(1, st.getCount());
            a.slot = i;
            a.id = id;
            return a;
        }
        return null;
    }

    private static void buy(MinecraftClient mc, HandledScreen<?> market, Attempt a, long now) {
        a.baseInv = countMatching(mc.player.getInventory(), a.item);
        a.clickAt = now;
        cur = a;
        click(mc, market, a.slot, 0); // LPM = kup
        note("Kupuję: " + a.name + (a.count > 1 ? " x" + a.count : "") + " za " + AnalizaScreen.fmt(a.total) + "$ (skup " + AnalizaScreen.fmt(a.limit) + ").");
        setState(State.BUY_WAIT, 0);
    }

    private static void buyWait(MinecraftClient mc, Screen scr, HandledScreen<?> market, long now) {
        Attempt a = cur;
        if (a == null) {
            setState(State.SNIPE, 0);
            return;
        }
        if (scr instanceof HandledScreen<?> && market == null) { // okno potwierdzenia
            setState(State.CONFIRM, 0);
            return;
        }
        if (chatOkAt >= a.clickAt || chatFailAt >= a.clickAt || chatNoMoneyAt >= a.clickAt) { // zakup bez potwierdzenia
            setState(State.RESULT, 0);
            return;
        }
        if (market != null && !a.reclicked && now - a.clickAt >= 700) {
            ItemStack same = market.getScreenHandler().slots.get(a.slot).getStack();
            if (!same.isEmpty() && MarketObserver.identity(same, ItemMatcher.offerPrice(same)).equals(a.id)) {
                click(mc, market, a.slot, 0);
                a.reclicked = true;
            }
        }
        if (now - a.clickAt < 3000) return;
        if (countMatching(mc.player.getInventory(), a.item) >= a.baseInv + a.count) {
            bought(a, now, "ekwipunek");
            afterAttempt(mc, now);
            return;
        }
        failed(a, "brak odpowiedzi serwera", now);
        if (++buyTimeouts >= 3) {
            buyTimeouts = 0;
            restart(mc, "Timeout zakupu - restart.", now);
            return;
        }
        afterAttempt(mc, now);
    }

    private static void confirm(MinecraftClient mc, Screen scr, HandledScreen<?> market, long now) {
        Attempt a = cur;
        if (a == null) {
            afterAttempt(mc, now);
            return;
        }
        if (!(scr instanceof HandledScreen<?> hs) || market != null) { // okno zniknęło albo wrócił rynek
            setState(State.RESULT, 0);
            return;
        }
        ScreenHandler h = hs.getScreenHandler();
        int size = Math.max(0, MarketAnalyzer.containerSize(h));
        boolean any = false;
        for (int i = 0; i < size; i++) if (!h.slots.get(i).getStack().isEmpty()) { any = true; break; }
        if ((!any || now - stateAt < 50) && now - stateAt < 1000) return; // zawartość okna jeszcze nie doszła
        String bad = verifyConfirm(h, size, a);
        if (bad != null) {
            say("Anuluję zakup " + a.name + ": " + bad + ".");
            mc.player.closeHandledScreen();
            failed(a, bad, now);
            cur = null;
            setState(State.OPEN, 300);
            return;
        }
        int btn = confirmButton(h, size, a);
        if (btn < 0) {
            say("Nie widzę przycisku potwierdzenia zakupu - zamykam okno (ustaw slot/nazwę przycisku w ustawieniach).");
            mc.player.closeHandledScreen();
            failed(a, "brak przycisku potwierdzenia", now);
            cur = null;
            setState(State.OPEN, 300);
            return;
        }
        click(mc, hs, btn, 0);
        a.confirmClicks = 1;
        a.confirmAt = now;
        setState(State.RESULT, 0);
    }

    /** Czy w oknie potwierdzenia jest ten sam przedmiot i nie drożej; null = w porządku, inaczej powód. */
    static String verifyConfirm(ScreenHandler h, int size, Attempt a) {
        String wantId = ItemMatcher.itemId(a.stack);
        boolean sameId = false;
        for (int i = 0; i < size; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty() || !ItemMatcher.itemId(st).equals(wantId)) continue;
            sameId = true;
            if (!ItemMatcher.stripColors(st.getName().getString()).equalsIgnoreCase(a.name)) continue;
            double p = ItemMatcher.offerPrice(st);
            if (p > 0 && p > a.total + Math.max(0.01, a.total * 1e-4)) {
                return "w oknie potwierdzenia cena " + AnalizaScreen.fmt(p) + "$ zamiast " + AnalizaScreen.fmt(a.total) + "$";
            }
            return null;
        }
        return sameId ? "w oknie potwierdzenia jest inny przedmiot" : null;
    }

    private static final String[] CONFIRM_WORDS = {"potwierdz", "kup", "confirm", "akceptuj", "buy", "tak"};
    private static final String[] CANCEL_WORDS = {"anuluj", "cancel", "odrzuc", "wroc", "powrot", "zamknij", "rezygn"};

    /** Przycisk "Potwierdź": po nazwie z ustawień, po słowach w nazwie (pomijając sam przedmiot), zapasowo slot z ustawień. */
    static int confirmButton(ScreenHandler h, int size, Attempt a) {
        AnalizaConfig c = cfg();
        String want = BkChatGuard.plain(c.amConfirmName.trim().toLowerCase(Locale.ROOT));
        String wantId = a == null ? "" : ItemMatcher.itemId(a.stack);
        for (int i = 0; i < size; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty()) continue;
            String name = ItemMatcher.plainChat(st.getName().getString());
            if (!want.isEmpty()) {
                if (name.contains(want)) return i;
                continue;
            }
            if (ItemMatcher.itemId(st).equals(wantId) || sellerLore(st)) continue; // sam przedmiot
            if (ItemMatcher.any(name, CANCEL_WORDS) || name.matches("(?s).*\\bnie\\b.*")) continue;
            if (ItemMatcher.any(name, CONFIRM_WORDS)) return i;
        }
        int slot = c.amConfirmSlot;
        return slot >= 0 && slot < size && !h.slots.get(slot).getStack().isEmpty() ? slot : -1;
    }

    private static boolean sellerLore(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (Text t : lore.lines()) {
            String l = ItemMatcher.plainChat(t.getString());
            if (l.contains("wystawi") || l.contains("sprzedawca") || l.contains("seller")) return true;
        }
        return false;
    }

    private static void result(MinecraftClient mc, Screen scr, HandledScreen<?> market, long now) {
        Attempt a = cur;
        if (a == null) {
            afterAttempt(mc, now);
            return;
        }
        long since = a.confirmAt > 0 ? a.confirmAt : a.clickAt;
        if (chatNoMoneyAt >= since) {
            noMoneyUntil = now + 60_000L;
            say("Brak środków na " + a.name + " - kupowanie wstrzymane na minutę.");
            failed(a, "brak środków", now);
            afterAttempt(mc, now);
            return;
        }
        if (chatFailAt >= since) {
            failed(a, "ktoś był szybszy", now);
            afterAttempt(mc, now);
            return;
        }
        boolean invUp = now - since >= 100 && countMatching(mc.player.getInventory(), a.item) >= a.baseInv + a.count;
        if (chatOkAt >= since || invUp) {
            bought(a, now, chatOkAt >= since ? "czat" : "ekwipunek");
            afterAttempt(mc, now);
            return;
        }
        boolean confirmOpen = scr instanceof HandledScreen<?> && market == null;
        if (confirmOpen && a.confirmClicks > 0 && a.confirmClicks < 3 && now - a.confirmAt >= 1200) {
            HandledScreen<?> hs = (HandledScreen<?>) scr;
            ScreenHandler h = hs.getScreenHandler();
            int btn = confirmButton(h, MarketAnalyzer.containerSize(h), a);
            if (btn >= 0) {
                click(mc, hs, btn, 0);
                a.confirmClicks++;
                a.confirmAt = now;
                return;
            }
        }
        if (now - since < 4000) return;
        if (confirmOpen) {
            failed(a, "zacięte okno potwierdzenia", now);
            restart(mc, "Zacięcie na ekranie potwierdzenia - restart.", now);
            return;
        }
        failed(a, "brak potwierdzenia zakupu", now);
        afterAttempt(mc, now);
    }

    /** Po próbie zakupu: zamknij okno potwierdzenia, jeśli zostało, i wróć do snajpienia. */
    private static void afterAttempt(MinecraftClient mc, long now) {
        cur = null;
        Screen scr = mc.currentScreen;
        HandledScreen<?> market = marketScreen(scr);
        if (scr instanceof HandledScreen<?> && market == null) {
            mc.player.closeHandledScreen();
            setState(State.OPEN, 300);
            return;
        }
        if (market != null) {
            startSnipe(market, now);
            return;
        }
        setState(State.OPEN, 300);
    }

    private static void bought(Attempt a, long now, String how) {
        buyTimeouts = 0;
        skipOffers.put(a.id, now + 60_000L);
        BkBridge.recordPurchase(a.stack, a.total, a.count);
        a.item.toSell += a.count;
        ItemStore st = BkBridge.store();
        if (st != null) st.save();
        stats.bought++;
        stats.spent += a.total;
        say("Kupiono: " + a.name + (a.count > 1 ? " x" + a.count : "") + " za " + AnalizaScreen.fmt(a.total) + "$ (skup "
                + AnalizaScreen.fmt(a.limit) + (a.count > 1 && a.limit < a.total ? "$/szt." : "$") + ").");
    }

    private static void failed(Attempt a, String why, long now) {
        stats.failed++;
        skipOffers.put(a.id, now + 30_000L);
        note("Nie kupiono " + a.name + ": " + why + ".");
    }

    // ---------- wystawianie ----------
    /** Wystawki z ekwipunku: kupione przez snajpera (toSell) albo wszystkie z "Wystawiaj"; cena z listy. */
    static List<SellJob> sellJobs(PlayerInventory inv) {
        List<SellJob> out = new ArrayList<>();
        ItemStore st = BkBridge.store();
        if (st == null) return out;
        Map<MarketItem, Integer> left = new HashMap<>();
        java.util.Set<MarketItem> found = new java.util.HashSet<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            for (MarketItem it : st.items()) {
                if (!(it.sellPrice > 0)) continue;
                if (!ItemMatcher.matches(s, it)) continue;
                found.add(it);
                boolean all = Boolean.TRUE.equals(it.sellEnabled);
                int allowed = all ? Integer.MAX_VALUE : left.getOrDefault(it, it.toSell);
                if (allowed <= 0) break;
                int units = it.sellUnits();
                int qty = Math.min(s.getCount(), Math.min(units, allowed));
                long price = Math.round(it.sellPrice / units * qty);
                if (qty < 1 || price < 1) break;
                out.add(new SellJob(i, it, qty, price));
                if (!all) left.put(it, allowed - qty);
                break;
            }
        }
        // kupione wcześniej, ale już nie ma ich w ekwipunku (zużyte, sprzedane ręcznie) - licznik do zera
        boolean changed = false;
        for (MarketItem it : st.items()) {
            if (it.toSell > 0 && !found.contains(it)) {
                it.toSell = 0;
                changed = true;
            }
        }
        if (changed) st.touch();
        return out;
    }

    private static boolean startSell(MinecraftClient mc, long now, String why) {
        PlayerInventory inv = mc.player.getInventory();
        List<SellJob> jobs = sellJobs(inv);
        if (jobs.isEmpty()) return false;
        sellQueue.clear();
        sellQueue.addAll(jobs);
        selling = null;
        sellStep = 0;
        savedSlot = inv.selectedSlot;
        if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        note("Wystawiam " + jobs.size() + " przedm." + (why == null ? "" : " (" + why + ")") + ".");
        setState(State.SELL, 400);
        return true;
    }

    private static void sell(MinecraftClient mc, Screen scr, long now) {
        AnalizaConfig c = cfg();
        PlayerInventory inv = mc.player.getInventory();
        if (scr instanceof HandledScreen<?>) {
            if (marketScreen(scr) != null) { // spóźnione okno rynku - zamknąć i wystawiać dalej
                mc.player.closeHandledScreen();
                return;
            }
            // po "/wystaw" otworzyło się okno - tego nie umiemy potwierdzić
            say("Po wystawieniu otworzyło się okno, którego nie umiem obsłużyć - zamykam je i wyłączam wystawianie do restartu auto rynku.");
            mc.player.closeHandledScreen();
            listingBroken = true;
            finishSell(mc, now);
            return;
        }
        if (selling == null) {
            if (now < sellAt + 600 && sellStep != 0) return; // odstęp między wystawkami
            selling = sellQueue.poll();
            if (selling == null) {
                finishSell(mc, now);
                return;
            }
            ItemStack s = inv.getStack(selling.inv);
            if (s.isEmpty() || s.getCount() < selling.qty || !ItemMatcher.matches(s, selling.item)) {
                selling = null;
                sellStep = 3;
                return;
            }
            if (selling.inv < 9) {
                inv.selectedSlot = selling.inv;
            } else {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, selling.inv, inv.selectedSlot, SlotActionType.SWAP, mc.player);
                swappedFrom = selling.inv;
            }
            sellAt = now;
            sellStep = 1;
            return;
        }
        if (sellStep == 1) {
            if (now - sellAt < 350) return;
            ItemStack hand = inv.getMainHandStack();
            if (hand.isEmpty() || hand.getCount() < selling.qty || !ItemMatcher.matches(hand, selling.item)) {
                note("Nie trzymam przedmiotu do wystawienia (" + ItemMatcher.stripColors(selling.item.name) + ") - pomijam.");
                swapBack(mc);
                selling = null;
                sellStep = 3;
                return;
            }
            sellBaseCount = countMatching(inv, selling.item);
            String cmd = c.amSellCommand.replace("{quantity}", String.valueOf(selling.qty)).replace("{price}", String.valueOf(selling.price));
            mc.player.networkHandler.sendChatCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
            sellAt = now;
            sellStep = 2;
            return;
        }
        if (sellStep == 2) {
            if (chatListLimitAt >= sellAt) {
                say("Limit wystawionych przedmiotów - następne wystawianie za 10 min.");
                listLimitUntil = now + 10 * 60_000L;
                sellQueue.clear();
                selling = null;
                finishSell(mc, now);
                return;
            }
            boolean gone = countMatching(inv, selling.item) <= sellBaseCount - selling.qty;
            if (chatListOkAt >= sellAt || gone) {
                MarketItem it = selling.item;
                it.toSell = Math.max(0, it.toSell - selling.qty);
                stats.listed++;
                stats.listedValue += selling.price;
                say("Wystawiono: " + ItemMatcher.stripColors(it.name == null || it.name.isBlank() ? it.material : it.name)
                        + (selling.qty > 1 ? " x" + selling.qty : "") + " za " + selling.price + "$.");
                ItemStore st = BkBridge.store();
                if (st != null) st.save();
                swapBack(mc);
                selling = null;
                sellStep = 3;
                sellAt = now;
                return;
            }
            if (now - sellAt > 3000) {
                note("Brak potwierdzenia wystawienia " + ItemMatcher.stripColors(selling.item.name) + ".");
                swapBack(mc);
                selling = null;
                sellStep = 3;
                sellAt = now;
            }
        }
    }

    /** Przedmiot z plecaka był zamieniony z wybranym slotem paska - zamiana z powrotem (pasek gracza jak przed wystawianiem). */
    private static void swapBack(MinecraftClient mc) {
        if (swappedFrom < 0 || mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, swappedFrom, mc.player.getInventory().selectedSlot, SlotActionType.SWAP, mc.player);
        swappedFrom = -1;
    }

    private static void finishSell(MinecraftClient mc, long now) {
        restoreSlot(mc);
        sellQueue.clear();
        selling = null;
        nextSellAt = now + cfg().amSellMin * 60_000L;
        setState(State.OPEN, 300);
    }

    private static void restoreSlot(MinecraftClient mc) {
        swapBack(mc);
        if (savedSlot >= 0 && savedSlot < 9 && mc != null && mc.player != null) mc.player.getInventory().selectedSlot = savedSlot;
        savedSlot = -1;
    }

    // ---------- anty-AFK ----------
    private static void startAfk(MinecraftClient mc, long now) {
        if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        jumpsDone = 0;
        afkStep = 0;
        note("Anty-AFK: " + cfg().amAfkJumps + " skoki.");
        setState(State.AFK, 400);
    }

    private static void afk(MinecraftClient mc, long now) {
        AnalizaConfig c = cfg();
        if (afkStep == 0) {
            if (!mc.player.isOnGround() && now - stateAt < 3000) return;
            mc.options.jumpKey.setPressed(true);
            afkAt = now;
            afkStep = 1;
            return;
        }
        if (afkStep == 1) {
            if (now - afkAt < 100) return;
            mc.options.jumpKey.setPressed(false);
            jumpsDone++;
            afkAt = now;
            afkStep = 2;
            return;
        }
        if (now - afkAt < 500 || (!mc.player.isOnGround() && now - afkAt < 3000)) return;
        if (jumpsDone < Math.max(1, c.amAfkJumps)) {
            afkStep = 0;
            return;
        }
        stats.afk++;
        nextAfkAt = now + c.amAfkMin * 60_000L;
        setState(State.OPEN, 300);
    }

    private static void releaseJump(MinecraftClient mc) {
        if (state == State.AFK && mc != null && mc.options != null && mc.options.jumpKey != null) mc.options.jumpKey.setPressed(false);
    }

    // ---------- restart ----------
    private static void restart(MinecraftClient mc, String why, long now) {
        stats.restarts++;
        note(why);
        releaseJump(mc);
        cur = null;
        if (mc.player != null && mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        setState(State.RESTART, cfg().amRestartPauseMs);
    }

    // ---------- pomocnicze ----------
    private static void click(MinecraftClient mc, HandledScreen<?> hs, int slot, int button) {
        mc.interactionManager.clickSlot(hs.getScreenHandler().syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    /** Ile sztuk tego przedmiotu jest w ekwipunku (36 slotów). */
    static int countMatching(PlayerInventory inv, MarketItem it) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && ItemMatcher.matches(s, it)) n += s.getCount();
        }
        return n;
    }

    private static String onOff(boolean b) {
        return b ? "WŁ" : "WYŁ";
    }

    private static String keyName() {
        try {
            return RynekAnalizaMod.autoKey == null ? "F9" : RynekAnalizaMod.autoKey.getBoundKeyLocalizedText().getString();
        } catch (Throwable t) {
            return "F9";
        }
    }

    /** Komunikat na czat i do dziennika. */
    static void say(String msg) {
        note(msg);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) mc.player.sendMessage(Text.literal("[Auto rynek] " + msg), false);
    }

    /** Tylko do dziennika (zakładka "Auto rynek"). */
    static void note(String msg) {
        String t = java.time.LocalTime.now().withNano(0).toString();
        synchronized (log) {
            log.addLast(t + "  " + msg);
            while (log.size() > 60) log.removeFirst();
        }
    }

    private static void noteOnce(String key, String msg, long now) {
        Long until = notedOnce.get(key);
        if (until != null && now < until) return;
        notedOnce.put(key, now + 5 * 60_000L);
        if (notedOnce.size() > 200) {
            Iterator<Map.Entry<String, Long>> it = notedOnce.entrySet().iterator();
            while (it.hasNext()) if (it.next().getValue() < now) it.remove();
        }
        note(msg);
    }

    /** Ostatnie wpisy dziennika (najnowszy na końcu). */
    static List<String> logLines() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    /** Co teraz robi (do znaczka i zakładki). */
    static String stateLabel() {
        if (!on) return "wyłączony";
        if (paused) return "wstrzymany (analiza)";
        return switch (state) {
            case OPEN, WAIT_OPEN -> "otwiera rynek";
            case SORT, SORT_WAIT -> "ustawia sortowanie";
            case SNIPE -> "snajpi";
            case BUY_WAIT, CONFIRM, RESULT -> "kupuje";
            case SELL -> "wystawia";
            case AFK -> "anty-AFK (skoki)";
            case RESTART -> "restart rynku";
            case HOLD -> "czeka (otwarte inne okno)";
            default -> state.name().toLowerCase(Locale.ROOT);
        };
    }

    /** Za ile ms następne wystawianie / skoki / restart (do zakładki). */
    static long msToSell(long now) {
        return Math.max(0, Math.max(nextSellAt, listLimitUntil) - now);
    }

    static long msToAfk(long now) {
        return Math.max(0, nextAfkAt - now);
    }

    static long msToRestart(long now) {
        int s = cfg().amRestartSec;
        return s <= 0 || state != State.SNIPE ? -1 : Math.max(0, sessionAt + s * 1000L - now);
    }

    /** Tylko testy: stan od zera. */
    static void resetForTests() {
        on = paused = false;
        state = State.OFF;
        cur = null;
        sellQueue.clear();
        selling = null;
        skipOffers.clear();
        notedOnce.clear();
        chatOkAt = chatFailAt = chatNoMoneyAt = chatListOkAt = chatListLimitAt = -1;
        noMoneyUntil = listLimitUntil = 0;
        listingBroken = false;
        sortConfirmed = sortGaveUp = false;
        savedSlot = -1;
        stats.bought = stats.listed = stats.restarts = stats.failed = stats.afk = 0;
        stats.spent = stats.listedValue = 0;
        synchronized (log) {
            log.clear();
        }
    }
}
