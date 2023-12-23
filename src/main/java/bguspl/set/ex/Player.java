package bguspl.set.ex;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import bguspl.set.Env;
import java.lang.Thread;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * Dealer entity
     */
    private final Dealer dealer;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The key presses a player pressed
     */
    private BlockingQueue<Integer> presses;

    /**
     * current tokens state
     */
    private int[] myTokens;

    /*
     * Amount of tokens on the grid
     */
    private int amountOfTokens;

    /*
     * point or penalty?
     */
    private int pointOrPenalty;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.dealer = dealer;
        this.presses = new LinkedBlockingQueue<>(3);
        this.myTokens = new int[env.config.featureSize];
        for (int i = 0; i < env.config.featureSize; i++)
            this.myTokens[i] = -1;
        this.pointOrPenalty = -1;
        this.amountOfTokens = 0;
        this.id = id;
        this.human = human;
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        if (!human)
            createArtificialIntelligence();
        while (!terminate) {
            try {
                synchronized (dealer.dealerLock) {
                    if (pointOrPenalty == -1 && !presses.isEmpty()) {
                        int press = presses.remove();
                        if (!removeToken(press) && table.slotToCard[press] != null && placeToken(press)
                                && amountOfTokens == env.config.featureSize && !dealer.getIsChanging()) {
                            int[] array = { table.slotToCard[myTokens[0]], table.slotToCard[myTokens[1]],
                                    table.slotToCard[myTokens[2]] };
                            env.logger.info(Thread.currentThread().getName() + " sent the set "
                                    + Arrays.toString(myTokens) + " is legal: " + env.util.testSet(array));
                            dealer.addToSlotsOfSetsToCheck(this, myTokens.clone());

                            dealer.dealerLock.wait();
                        }
                    }
                }
                if (pointOrPenalty == 0) {
                    penalty();
                }
                if (pointOrPenalty == 1) {
                    point();
                }
            } catch (InterruptedException ignored) {
            }
        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
            Random random = new Random();
            while (!terminate) {
                int press = random.nextInt(env.config.tableSize);
                keyPressed(press);
            }
            env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (presses.size() < env.config.featureSize && !dealer.getIsChanging()) {
            try {
                presses.put(slot);
            } catch (InterruptedException e) {
            }
        }

    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // int ignored = table.countCards(); // this part is just for demonstration in
        // the unit tests
        this.score++;
        env.ui.setScore(id, score);
        try {
            for (long i = env.config.pointFreezeMillis; i > 0; i -= 1000) {
                env.ui.setFreeze(id, i);
                playerThread.sleep(1000);
            }
            env.ui.setFreeze(id, 0);
        } catch (InterruptedException e) {
        }
        presses.clear();
        pointOrPenalty = -1;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        try {
            for (long i = env.config.penaltyFreezeMillis; i > 0; i -= 1000) {
                env.ui.setFreeze(id, i);
                playerThread.sleep(1000);
            }
            env.ui.setFreeze(id, 0);
        } catch (InterruptedException e) {
        }
        presses.clear();
        pointOrPenalty = -1;
    }

    /**
     * sets the point or penalty field according to the dealer's decision
     */
    public void setPointOrPenalty(int decision) {
        this.pointOrPenalty = decision;
    }

    /**
     * return the score of the player
     */
    public int score() {
        return score;
    }

    /**
     * placing the token on the board, adding it to myTokens, increases the
     * amountOfTokens field (if needed)
     */
    public boolean placeToken(int slot) {
        if (amountOfTokens < env.config.featureSize) {
            boolean exists = false;
            int spot = -1;
            for (int i = 0; i < env.config.featureSize; i++) {
                if (myTokens[i] == slot)
                    exists = true;
                if (myTokens[i] == -1)
                    spot = i;
            }
            if (!exists && spot != -1) {
                myTokens[spot] = slot;
                table.placeToken(id, slot);
                amountOfTokens++;
                return true;
            }
        }
        return false;
    }

    /**
     * removing the token from the table, removing from myTokens and decreasing
     * amountOfTokens field
     */
    public boolean removeToken(int slot) {
        for (int i = 0; i < env.config.featureSize; i++) {
            if (myTokens[i] == slot) {
                myTokens[i] = -1;
                table.removeToken(id, slot);
                amountOfTokens--;
                return true;
            }
        }
        return false;
    }

    /**
     * deletes the tokens of this player
     */
    public void deleteTokens() {
        for (int i = 0; i < env.config.featureSize; i++) {
            if (myTokens[i] != -1) {
                table.removeToken(id, myTokens[i]);
                amountOfTokens--;
                myTokens[i] = -1;
            }
        }
    }

    public int getId(){
        return id;
    }

    public void setScore(int newScore) {
        score=newScore;
    }

    public int getAmountOfTokens() {
        return amountOfTokens;
    }

    public int[] getMyTokens() {
        return myTokens;
    }

}
