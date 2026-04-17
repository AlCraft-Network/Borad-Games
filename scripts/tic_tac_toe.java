package scripts;

import java.util.Arrays;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.Event;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

import dev.lone.itemsadder.api.*;
import dev.lone.itemsadder.api.scriptinginternal.ItemScript;
import static dev.lone.itemsadder.api.scriptinginternal.EntityUtils.*;
import static dev.lone.itemsadder.api.scriptinginternal.PlayerUtils.*;
import static dev.lone.itemsadder.api.scriptinginternal.WorldUtils.*;
import static dev.lone.itemsadder.api.scriptinginternal.ScriptingUtils.*;
import static dev.lone.itemsadder.api.FontImages.FontImageWrapper.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class tic_tac_toe extends ItemScript {
    private class Settings {
        public static final boolean ENABLE_RESTARTING = true;
        public static final int RESET_DELAY_SECONDS = 5;
        public static final String  RESET_MSG = "§7If you want to restart the game, use §fSHIFT + CLICK";
        public static final String  RESTARTING_MSG = "§7Restarting in §f{s} s§7...";
        public static final boolean ENABLE_PERSISTENCE = true;
    }

    private final int BOARD_HEIGHT = 3;
    private final int BOARD_WIDTH = 3;
    
    private final String BOARD_KEY = "ttt_board";
    private final String TURN_KEY = "ttt_turn";
    private final String WINNER_KEY = "ttt_winner";
    private final String RESET_PENDING_KEY = "ttt_reset_pending";
    private final String RESET_AVAILABLE_KEY = "ttt_reset_available";
    private final String NAMESPACE = "game";

    private final String DEFAULT_TURN = "0";
    private final String DEFAULT_WINNER = "";
    private final String DEFAULT_BOARD = " ".repeat(9);

    private final String WINNER_X = "X";
    private final String WINNER_O = "O";

    private boolean hasRestored = false;

    @Override
    public void handleEvent(Plugin plugin, Event event, Player player, CustomStack item, ItemStack vanillaItem) {
        if (event instanceof PlayerInteractEvent e) {
            // Get board entity
            CustomFurniture furniture = CustomFurniture.byAlreadySpawned(entityInFront(player));
            if (furniture == null) return;

            // Get click location
            Block blockLoc = furniture.getEntity().getLocation().clone().subtract(0, 0.1, 0).getBlock();
            Block block = e.getClickedBlock();
            if (block == null || !block.equals(blockLoc)) return;

            Location clickLoc = e.getInteractionPoint();
            if (clickLoc == null || e.getBlockFace() != BlockFace.UP) return;

            // Get board position
            int[] pos = getClickedPos(clickLoc, block, furniture);

            // Get or generate board
            TextDisplay board = setUpBoard(block, furniture);

            if (Settings.ENABLE_RESTARTING && handleReset(board, player)) return;

            // Update board
            playMove(board, pos[0], pos[1]);
            
        // Break board logic
        } else {
            try { // Cancel break event
                if (event instanceof Cancellable cancellableEvent) cancellableEvent.setCancelled(true);
            } catch (Exception ignored) {}

            CustomFurniture furniture = CustomFurniture.byAlreadySpawned(entityInFront(player));
            if (furniture == null) return;

            Block block = furniture.getEntity().getLocation().clone().subtract(0, 0.1, 0).getBlock();
            Location loc = block.getLocation().add(0.5, 1, 0.5);
            ItemStack boardItem = furniture.getItemStack();
            
            for (Entity e : block.getWorld().getNearbyEntities(loc, 0.5, 0.5, 0.5)) {
                if (e instanceof TextDisplay display) {
                    if (Settings.ENABLE_PERSISTENCE) saveBoardStateToItem(display, boardItem);
                    display.remove();
                }
            }

            furniture.getEntity().remove();
            playSound(loc, "minecraft:block.wood.break");
            if (player.getGameMode() != GameMode.CREATIVE) block.getWorld().dropItemNaturally(loc, boardItem);
        }
    }

    // ========== PERSISTING DATA MECHANIC ===========
    private void saveBoardStateToItem(TextDisplay display, ItemStack item) {
        if (item == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        var container = meta.getPersistentDataContainer();
        setDataString(container, NAMESPACE, BOARD_KEY, getDataString(display, NAMESPACE, BOARD_KEY, DEFAULT_BOARD));
        setDataString(container, NAMESPACE, TURN_KEY, getDataString(display, NAMESPACE, TURN_KEY, DEFAULT_TURN));
        setDataString(container, NAMESPACE, WINNER_KEY, getDataString(display, NAMESPACE, WINNER_KEY, DEFAULT_WINNER));

        item.setItemMeta(meta);
    }
    // =============================================

    private int[] getClickedPos(Location clickLoc, Block block, CustomFurniture furniture) {
        double locX = clickLoc.getX() - block.getX();
        double locZ = clickLoc.getZ() - block.getZ();

        int row, col;
        
        float yaw = furniture.getEntity().getLocation().getYaw();
        yaw = (yaw % 360 + 360) % 360;
        
        if (yaw >= 315 || yaw < 45) {  // SOUTH
            col = (int) ((1.0 - locX) * BOARD_WIDTH);
            row = (int) ((1.0 - locZ) * BOARD_HEIGHT);
        } else if (yaw >= 45 && yaw < 135) { // WEST
            col = (int) ((1.0 - locZ) * BOARD_WIDTH);
            row = (int) (locX * BOARD_HEIGHT);
        } else if (yaw >= 135 && yaw < 225) { // NORTH
            col = (int) (locX * BOARD_WIDTH);
            row = (int) (locZ * BOARD_HEIGHT);
        } else { // EAST
            col = (int) (locZ * BOARD_WIDTH);
            row = (int) ((1.0 - locX) * BOARD_HEIGHT);
        }

        return new int[]{ 
            Math.max(0, Math.min(BOARD_HEIGHT - 1, row)),
            Math.max(0, Math.min(BOARD_WIDTH - 1, col))
        };
    }

    private TextDisplay setUpBoard(Block block, CustomFurniture furniture) {
        // Check for an existing board
        for (Entity e : block.getWorld().getNearbyEntities(block.getLocation().add(0.5, 1, 0.5), 0.5, 0.5, 0.5)) {
            if (e instanceof TextDisplay display) return display;
        }

        // Create a new text display
        Location loc = furniture.getEntity().getLocation().add(0, 0.017, 0);
        TextDisplay display = (TextDisplay) block.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);

        display.setBillboard(Display.Billboard.FIXED);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setShadowed(false);
        display.setSeeThrough(false);
        display.setBackgroundColor(Color.fromARGB(0));

        Quaternionf rotation = new Quaternionf().rotateXYZ((float) Math.toRadians(-90), 0, (float) Math.toRadians(-180));
        Vector3f scale = new Vector3f(0.63f, 0.63f, 0.63f);
        Vector3f translation = new Vector3f(0.0f, 0.0f, -0.4f);
        display.setTransformation(new Transformation(translation, rotation, scale, new Quaternionf()));
        
        // Check if item has saved data
        ItemStack boardItem = furniture.getItemStack();
        ItemMeta meta = boardItem.getItemMeta();
        if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(boardItem.getType());

        var container = meta.getPersistentDataContainer();
        String boardData  = getDataString(container, NAMESPACE, BOARD_KEY, DEFAULT_BOARD);
        String turnData   = getDataString(container, NAMESPACE, TURN_KEY, DEFAULT_TURN);
        String winnerData = getDataString(container, NAMESPACE, WINNER_KEY, DEFAULT_WINNER);

        this.hasRestored = !boardData.equals(DEFAULT_BOARD);

        // Initialize empty board
        setBoard(display, boardData, turnData, winnerData);
        return display;
    }

    private void setBoard(TextDisplay display, String boardData, String turnData, String winnerData) {
        TicTacToeGame game = new TicTacToeGame(BOARD_HEIGHT, BOARD_WIDTH, boardData, Integer.parseInt(turnData));

        if (!winnerData.isEmpty() && !winnerData.equals("T")) game.checkWinner();
        display.text(Component.text(formatBoard(game.getBoardAsSymbolsArray()), NamedTextColor.BLACK));

        setDataString(display, NAMESPACE, BOARD_KEY, boardData);
        setDataString(display, NAMESPACE, TURN_KEY, turnData);
        setDataString(display, NAMESPACE, WINNER_KEY, winnerData);
    }

    // ============== RESET MECHANIC ==============
    private boolean handleReset(TextDisplay board, Player player) {
        String winner = getDataString(board, NAMESPACE, WINNER_KEY, DEFAULT_WINNER);
        boolean resetAvailable = getDataBool(board, NAMESPACE, RESET_AVAILABLE_KEY, false);

        if (this.hasRestored || (!winner.isEmpty() && !resetAvailable)) {
            setDataBool(board, NAMESPACE, RESET_AVAILABLE_KEY, true);
            msg(player, Settings.RESET_MSG);
            return true;
        }

        if (getDataBool(board, NAMESPACE, RESET_PENDING_KEY, false)) return true;

        if (!resetAvailable) return false;
        if (player.isSneaking()) {
            resetBoard(board, player);
            return true;
        } 
        
        if (winner.isEmpty()) setDataBool(board, NAMESPACE, RESET_AVAILABLE_KEY, false);

        return false;
    }

    private void resetBoard(TextDisplay display, Player player) {
        if (getDataBool(display, NAMESPACE, RESET_PENDING_KEY, false)) return;

        setDataBool(display, NAMESPACE, RESET_PENDING_KEY, true);
        setDataBool(display, NAMESPACE, RESET_AVAILABLE_KEY, false);

        for (int i = Settings.RESET_DELAY_SECONDS; i >= 0; i--) {
            final int secondsLeft = i;
            _runDelayed((Settings.RESET_DELAY_SECONDS - i) * 20L, () -> {
                if (!display.isValid()) return;

                if (secondsLeft > 0) {
                    playSound(display.getLocation(), "minecraft:block.note_block.hat");
                    playParticle(display.getLocation(), "minecraft:end_rod", 1, 0.3, 0.3, 0.3, 0.02);
                    msg(player, Settings.RESTARTING_MSG.replace("{s}", String.valueOf(secondsLeft)));
                } else {
                    setBoard(display, DEFAULT_BOARD, DEFAULT_TURN, DEFAULT_WINNER);
                    setDataBool(display, NAMESPACE, RESET_PENDING_KEY, false);

                    playSound(display.getLocation(), "minecraft:block.amethyst_block.chime");
                    playParticle(display.getLocation(), "minecraft:firework", 8, 0.4, 0.4, 0.4, 0.05);
                }
            });
        }
    }
    // ==============================================

    private void playMove(TextDisplay display, int row, int col) {
        // 1. LOAD saved data
        String boardData = getDataString(display, NAMESPACE, BOARD_KEY, DEFAULT_BOARD);
        int playerTurn = Integer.parseInt(getDataString(display, NAMESPACE, TURN_KEY, DEFAULT_TURN));
        String winner = getDataString(display, NAMESPACE, WINNER_KEY, DEFAULT_WINNER);

        // If there's already a winner, do nothing
        if (!winner.isEmpty()) return;

        // 2. PROCESS with game logic
        TicTacToeGame game = new TicTacToeGame(BOARD_HEIGHT, BOARD_WIDTH, boardData, playerTurn);
        
        if (game.playMove(row, col)) {
            // Move sound
            playSound(display.getLocation(), "minecraft:block.wood.place");
            
            // Check for winner
            if (game.checkWinner()) {
                winner = (playerTurn == 0) ? WINNER_X : WINNER_O;
                playSound(display.getLocation(), "minecraft:entity.firework_rocket.blast");
                playSound(display.getLocation(), "minecraft:block.note_block.bell");
                playParticle(display.getLocation(), "minecraft:happy_villager", 5, 0.5, 0.5, 0.5, 0.1);
                playParticle(display.getLocation(), "minecraft:firework", 5, 0.5, 0.5, 0.5, 0.1);
            }

            // Change turn
            playerTurn = game.changeTurn();
            boardData = game.getBoardAsCharacters();

            if (winner == DEFAULT_WINNER && boardData.indexOf(" ") == -1) {
                winner = "T";
                playSound(display.getLocation(), "minecraft:block.note_block.cow_bell");
                playParticle(display.getLocation(), "minecraft:cloud", 5, 0.5, 0.5, 0.5, 0.1);
                playParticle(display.getLocation(), "minecraft:crit", 5, 0.5, 0.5, 0.5, 0.1);
            }
        }

        // 3. SAVE updated data
        setDataString(display, NAMESPACE, BOARD_KEY, boardData);
        setDataString(display, NAMESPACE, TURN_KEY, String.valueOf(playerTurn));
        setDataString(display, NAMESPACE, WINNER_KEY, winner);

        // 4. VISUALIZE (update display)
        updateDisplay(display, game);
    }

    private void updateDisplay(TextDisplay display, TicTacToeGame game) {
        String[] symbols = game.getBoardAsSymbolsArray();
        display.text(Component.text(formatBoard(symbols), NamedTextColor.BLACK));
    }

    private String formatBoard(String[] symbols) {
        String[] rendered = new String[9];
        for (int i = 0; i < 9; i++) {
            rendered[i] = replaceFontImages(symbols[i]);
        }
        
        return
                applyPixelsOffsetToString(rendered[0], -2) + "  " + rendered[1] + "  " + applyPixelsOffsetToString(rendered[2], 2) + "\n\n" +
                applyPixelsOffsetToString(rendered[3], -2) + "  " + rendered[4] + "  " + applyPixelsOffsetToString(rendered[5], 2) + "\n\n" +
                applyPixelsOffsetToString(rendered[6], -2) + "  " + rendered[7] + "  " + applyPixelsOffsetToString(rendered[8], 2);
    }

    // ============================================================================
    // GAME LOGIC
    // ============================================================================
    class TicTacToeGame {
        private int boardHeight;
        private int boardWidth;
        private Piece[][] board;
        private int playerTurn;

        private final String EMPTY_CHARACTER = " ";
        private final String EMPTY_SYMBOL = ":board_games_empty:";

        public TicTacToeGame(int height, int width, String data, int turn) {
            this.boardHeight = height;
            this.boardWidth = width;
            this.playerTurn = turn;
            this.board = new Piece[height][width];
            convertDataToBoard(data);
        }

        private void convertDataToBoard(String data) {
            for (int i = 0; i < data.length() && i < (boardHeight * boardWidth); i++) {
                int row = i / boardWidth;
                int col = i % boardWidth;
                String c = String.valueOf(data.charAt(i));
                board[row][col] = (c.equals(EMPTY_CHARACTER)) ? null : new Piece(c);
            }
        }

        public String getBoardAsCharacters() {
            StringBuilder sb = new StringBuilder();
            for (Piece[] row : board) {
                for (Piece piece : row) {
                    sb.append(piece != null ? piece.getCharacter() : EMPTY_CHARACTER);
                }
            }
            return sb.toString();
        }

        public String[] getBoardAsSymbolsArray() {
            String[] symbols = new String[boardHeight * boardWidth];
            int index = 0;
            for (Piece[] row : board) {
                for (Piece piece : row) {
                    symbols[index++] = (piece != null) ? piece.getSymbol() : EMPTY_SYMBOL;
                }
            }
            return symbols;
        }

        public boolean playMove(int x, int y) {
            if (board[x][y] == null) {
                board[x][y] = new Piece(this.playerTurn);
                return true;
            }
            return false;
        }

        public int changeTurn() {
            playerTurn ^= 1;
            return playerTurn;
        }

        public boolean checkWinner() {
            int[][] wins = {
                {0,0, 0,1, 0,2}, // Row 0
                {1,0, 1,1, 1,2}, // Row 1
                {2,0, 2,1, 2,2}, // Row 2
                {0,0, 1,0, 2,0}, // Col 0
                {0,1, 1,1, 2,1}, // Col 1
                {0,2, 1,2, 2,2}, // Col 2
                {0,0, 1,1, 2,2}, // Diagonal 1
                {0,2, 1,1, 2,0}  // Diagonal 2
            };
            
            for (int[] win : wins) {
                Piece p1 = board[win[0]][win[1]];
                Piece p2 = board[win[2]][win[3]];
                Piece p3 = board[win[4]][win[5]];
                
                if (p1 != null && p1.isSamePlayer(p2) && p1.isSamePlayer(p3)) {
                    p1.mark();
                    p2.mark();
                    p3.mark();
                    return true;
                }
            }
            
            return false;
        }
    }

    class Piece {
        public static final String[] CHARACTERS = {"X", "O"};
        public static final String[] SYMBOLS = {":tic_tac_toe_x:", ":tic_tac_toe_o:"};
        public static final String[] WIN_CHARACTERS = {"x", "o"};
        public static final String[] WIN_SYMBOLS = {":tic_tac_toe_x_win:", ":tic_tac_toe_o_win:"};
        private boolean marked = false;
        private int player;

        public Piece(int player) {
            this.player = player;
        }

        public Piece(String character) {
            this.player = Arrays.asList(CHARACTERS).indexOf(character.toUpperCase());
        }

        public String getCharacter() {
            return CHARACTERS[player];
        }

        public String getSymbol() {
            return marked ? WIN_SYMBOLS[player] : SYMBOLS[player];
        }

        public void mark() {
            marked = true;
        }

        public boolean isSamePlayer(Piece piece) {
            if (piece == null) return false;
            return CHARACTERS[player].equals(piece.getCharacter());
        }
    }
}