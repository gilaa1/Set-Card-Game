package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * main thread of the dealer
     */
    private Thread dealerThread;

    /**
     * true if dealer is currently changing the table
     */
    private boolean isChanging = false;

    private BlockingQueue<Integer> slotsOfSetsToCheck;
    private BlockingQueue<Player> playersWhoPlacedSets;

    public final Object dealerLock;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.slotsOfSetsToCheck = new LinkedBlockingQueue<>(Integer.MAX_VALUE);
        this.playersWhoPlacedSets = new LinkedBlockingQueue<>(Integer.MAX_VALUE);
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        Collections.shuffle(deck);
        this.dealerLock = new Object();

    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        this.dealerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        for (Player player : players) {
            Thread playerThread = new Thread(player, player.id + " ");
            playerThread.start();
        }
        while (!shouldFinish()) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
            while (noSetsOnTable() && !deck.isEmpty()) {
                removeAllCardsFromTable();
                placeCardsOnTable();
                updateTimerDisplay(true);
            }
            if (noSetsOnTable() && deck.isEmpty()) {
                terminate();
                announceWinners();
                env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
            }

        }
    }

    /**
     * all of the actions needed for a confirmed set
     */
    private void dealWithSet(int[] set) {
        for (int i = 0; i < env.config.featureSize; i++) {
            Integer slot = table.cardToSlot[set[i]];
            if (slot != null)
                table.removeCard(slot);
            for (Player otherPlayer : players) {
                if (slot != null)
                    otherPlayer.removeToken(slot);
            }
        }
        updateTimerDisplay(true);
        dealerThread.interrupt();
    }

    private boolean noSetsOnTable() {
        LinkedList<Integer> arrayToList = new LinkedList<>();
        for (int i = 0; i < table.slotToCard.length; i++) {
            if (table.slotToCard[i] != null)
                arrayToList.add(table.slotToCard[i]);
        }
        return env.util.findSets(arrayToList, 1).isEmpty();
    }

    public void addToSlotsOfSetsToCheck(Player player, int[] slots) {
        try {
            for (int slot : slots)
                slotsOfSetsToCheck.put(slot);
            playersWhoPlacedSets.put(player);
            dealerThread.interrupt();
        } catch (InterruptedException e) {
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for (Player player : players)
            player.terminate();
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || (env.util.findSets(deck, 1).isEmpty() && noSetsOnTable());
    }

    private void removeCardsFromTable() {
        isChanging = true;
        int[] setToCheck = new int[3];
        if (slotsOfSetsToCheck.size() > 2) {
            boolean check = true;
            try {
                for (int i = 0; i < env.config.featureSize; i++) {
                    int slot = slotsOfSetsToCheck.take();
                    if (table.slotToCard[slot] != null)
                        setToCheck[i] = table.slotToCard[slot];
                    else
                        check = false;
                }
                Player player = playersWhoPlacedSets.take();
                synchronized (dealerLock) {
                    if (check && env.util.testSet(setToCheck)) {
                        player.setPointOrPenalty(1);
                        dealWithSet(setToCheck);
                    } else if (check) {
                        player.setPointOrPenalty(0);
                    }
                    dealerLock.notifyAll();
                }
            } catch (InterruptedException e) {
            }
        }

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        isChanging = true;
        List<Integer> slots = IntStream.range(0, env.config.tableSize).boxed().collect(Collectors.toList());
        Collections.shuffle(slots);
        for (int slot : slots) {
            if (table.slotToCard[slot] == null && !deck.isEmpty()) {
                Integer cardToPlace = deck.remove(deck.size() - 1);
                table.placeCard(cardToPlace, slot);
            }
        }
        isChanging = false;
        synchronized (dealerLock) {
            dealerLock.notifyAll();
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try {
            dealerThread.sleep(1);
        } catch (InterruptedException e) {

        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset && !shouldFinish()) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        } else if (reshuffleTime - System.currentTimeMillis() > env.config.turnTimeoutWarningMillis)
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        else
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), true);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        isChanging = true;
        removeAllTokens();
        List<Integer> slots = IntStream.range(0, env.config.tableSize).boxed().collect(Collectors.toList());
        Collections.shuffle(slots);
        for (int slot : slots) {
            Integer cardToRemove = table.slotToCard[slot];
            if (cardToRemove != null) {
                table.removeCard(slot);
                deck.add(cardToRemove);
            }
        }
        slotsOfSetsToCheck.clear();
        playersWhoPlacedSets.clear();
        Collections.shuffle(deck);

    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    public void announceWinners() {
        int[] playersScoresById = new int[players.length];
        for (Player player : players) {
            playersScoresById[player.id] = player.score();
        }
        int maxScore = 0;
        for (int score : playersScoresById) {
            if (score > maxScore) {
                maxScore = score;
            }
        }
        Queue<Player> winners = new LinkedList<>();
        for (Player player : players) {
            if (player.score() == maxScore)
                winners.add(player);
        }
        int[] winnersArray = new int[winners.size()];
        int i = 0;
        while (!winners.isEmpty()) {
            winnersArray[i] = winners.remove().id;
            i++;
        }
        env.ui.announceWinner(winnersArray);
    }

    public void removeAllTokens() {
        for (Player player : players) {
            player.deleteTokens();
        }
    }

    public boolean getIsChanging() {
        return isChanging;
    }
}
