package scripts;

import java.util.Arrays;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

import dev.lone.itemsadder.api.CustomFurniture;
import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.scriptinginternal.ItemScript;
import static dev.lone.itemsadder.api.scriptinginternal.EntityUtils.*;
import static dev.lone.itemsadder.api.scriptinginternal.EntityUtils.getDataBool;
import static dev.lone.itemsadder.api.scriptinginternal.EntityUtils.getDataString;
import static dev.lone.itemsadder.api.scriptinginternal.EntityUtils.setDataBool;
import static dev.lone.itemsadder.api.scriptinginternal.EntityUtils.setDataString;
import static dev.lone.itemsadder.api.scriptinginternal.PlayerUtils.*;
import static dev.lone.itemsadder.api.scriptinginternal.WorldUtils.*;
import static dev.lone.itemsadder.api.scriptinginternal.ScriptingUtils.*;
import static dev.lone.itemsadder.api.scriptinginternal.ScriptingUtils.getDataBool;
import static dev.lone.itemsadder.api.scriptinginternal.ScriptingUtils.getDataString;
import static dev.lone.itemsadder.api.scriptinginternal.ScriptingUtils.setDataBool;
import static dev.lone.itemsadder.api.scriptinginternal.ScriptingUtils.setDataString;
import static dev.lone.itemsadder.api.FontImages.FontImageWrapper.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class checkers extends ItemScript {
    class Utils {
        public static void decrementDurability(ItemStack item, Player player) {
            var meta = (Damageable) item.getItemMeta();
            if (meta.getDamage() + 1 >= item.getType().getMaxDurability()){
                playSound(player.getLocation(), "minecraft:entity.item.break");
                player.getWorld().spawnParticle(Particle.ITEM, player.getLocation().add(0, 1.0, 0), 15, 0.2, 0.2, 0.2, 0.1, item);
                item.setAmount(0);
            } else {
                meta.setDamage(meta.getDamage() + 1);
                item.setItemMeta(meta);
            }   
        }

        public static boolean isAxe(Material material) {
            return material.name().endsWith("_AXE");
        }
    }

    private class Settings {
        public static final boolean ENABLE_WAXABLE = true;
        public static final boolean ENABLE_RESTARTING = true;
        public static final int RESET_DELAY_SECONDS = 5;
        public static final String  RESET_MSG = "§7If you want to restart the game, use §fSHIFT + CLICK";
        public static final String  RESTARTING_MSG = "§7Restarting in §f{s} s§7...";
        public static final boolean ENABLE_PERSISTENCE = true;
    }

    private final int BOARD_HEIGHT = 8;
    private final int BOARD_WIDTH = 8;
    
    private final String BOARD_KEY = "checkers_board";
    private final String TURN_KEY = "checkers_turn";
    private final String WINNER_KEY = "checkers_winner";
    private final String STREAK_KEY = "checkers_streak";
    private final String WAXED_KEY  = "checkers_waxed";
    private final String RESET_PENDING_KEY = "checkers_reset_pending";
    private final String RESET_AVAILABLE_KEY = "checkers_reset_available";
    private final String NAMESPACE = "game";

    private final String DEFAULT_TURN = "0";
    private final String DEFAULT_WINNER = "";
    private final String DEFAULT_STREAK = "false";
    private final String DEFAULT_BOARD = " B B B B" +
                                         "B B B B " + 
                                         " B B B B" + 
                                         " ".repeat(16) +
                                         "W W W W " +
                                         " W W W W" + 
                                         "W W W W ";

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

            if (Settings.ENABLE_WAXABLE && handleWax(board, player)) return;
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

    // ========== PERSISTING DATA ===========
    private void saveBoardStateToItem(TextDisplay display, ItemStack item) {
        if (item == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        var container = meta.getPersistentDataContainer();
        setDataString(container, NAMESPACE, BOARD_KEY, getDataString(display, NAMESPACE, BOARD_KEY, DEFAULT_BOARD));
        setDataString(container, NAMESPACE, TURN_KEY, getDataString(display, NAMESPACE, TURN_KEY, DEFAULT_TURN));
        setDataString(container, NAMESPACE, WINNER_KEY, getDataString(display, NAMESPACE, WINNER_KEY, DEFAULT_WINNER));
        setDataString(container, NAMESPACE, STREAK_KEY, getDataString(display, NAMESPACE, STREAK_KEY, DEFAULT_STREAK));
        setDataBool(container, NAMESPACE, WAXED_KEY, getDataBool(display, NAMESPACE, WAXED_KEY, false));

        item.setItemMeta(meta);
    }
    // =============================================

    // ============== WAXING ==============
    private boolean handleWax(TextDisplay display, Player player) {
        boolean isWaxed = getDataBool(display, NAMESPACE, WAXED_KEY, false);
        ItemStack handItem = player.getInventory().getItemInMainHand();
        Material material = handItem.getType();

        if (!isWaxed && material != Material.HONEYCOMB) return false;

        if (!isWaxed) {
            applyWax(display, player, handItem);
        } else if (Utils.isAxe(material)) {
            removeWax(display, player, handItem);
        } else {
            playSound(display.getLocation(), "minecraft:block.copper.hit");
        }

        return true;
    }

    private void removeWax(TextDisplay display, Player player, ItemStack item) {
        setDataBool(display, NAMESPACE, WAXED_KEY, false);

        playSound(display.getLocation(), "minecraft:item.axe.scrape");
        playParticle(display.getLocation(), "minecraft:wax_off", 5, 0.3, 0.3, 0.3, 0.05);
        if (player.getGameMode() != GameMode.CREATIVE) Utils.decrementDurability(item, player);
    }

    private void applyWax(TextDisplay display, Player player, ItemStack item) {
        setDataBool(display, NAMESPACE, WAXED_KEY, true);

        playSound(display.getLocation(), "minecraft:item.honeycomb.wax_on");
        playParticle(display.getLocation(), "minecraft:wax_on", 5, 0.3, 0.3, 0.3, 0.05);
        if (player.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
    }
    // ==============================================

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

        Quaternionf rotation = new Quaternionf().rotateXYZ((float) Math.toRadians(-90), 0, (float) Math.toRadians(180));
        Vector3f scale = new Vector3f(0.5f, 0.5f, 0.5f);
        Vector3f translation = new Vector3f(0.006f, 0.0f, -0.63f);
        display.setTransformation(new Transformation(translation, rotation, scale, new Quaternionf()));

        // Check if item has saved data
        ItemStack boardItem = furniture.getItemStack();
        ItemMeta meta = boardItem.getItemMeta();
        if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(boardItem.getType());

        var container = meta.getPersistentDataContainer();
        String boardData  = getDataString(container, NAMESPACE, BOARD_KEY, DEFAULT_BOARD);
        String turnData   = getDataString(container, NAMESPACE, TURN_KEY, DEFAULT_TURN);
        String winnerData = getDataString(container, NAMESPACE, WINNER_KEY, DEFAULT_WINNER);
        String streakData = getDataString(container, NAMESPACE, STREAK_KEY, DEFAULT_STREAK);
        boolean waxedData = getDataBool(container, NAMESPACE, WAXED_KEY, false);

        this.hasRestored = !boardData.equals(DEFAULT_BOARD);

        // Initialize empty board
        setBoard(display, boardData, turnData, winnerData, streakData, waxedData);

        return display;
    }

    private void setBoard(TextDisplay display, String boardData, String turnData, String winnerData, String streakData, boolean waxedData) {
        CheckersGame game = new CheckersGame(BOARD_HEIGHT, BOARD_WIDTH, boardData, Integer.parseInt(turnData), false);

        display.text(Component.text(formatBoard(game.getBoardAsSymbolsArray()), NamedTextColor.BLACK));

        setDataString(display, NAMESPACE, BOARD_KEY, boardData);
        setDataString(display, NAMESPACE, TURN_KEY, turnData);
        setDataString(display, NAMESPACE, WINNER_KEY, winnerData);
        setDataString(display, NAMESPACE, STREAK_KEY, streakData);
        setDataBool(display, NAMESPACE, WAXED_KEY, waxedData);
    }

    // ============== RESET ==============
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
                    boolean waxedData = getDataBool(display, NAMESPACE, WAXED_KEY, false);
                    setBoard(display, DEFAULT_BOARD, DEFAULT_TURN, DEFAULT_WINNER, DEFAULT_STREAK, waxedData);
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
        boolean streak = Boolean.parseBoolean(getDataString(display, NAMESPACE, STREAK_KEY, DEFAULT_STREAK));

        // If there's already a winner, do nothing
        if (!winner.isEmpty()) return;

        // 2. PROCESS with game logic
        CheckersGame game = new CheckersGame(BOARD_HEIGHT, BOARD_WIDTH, boardData, playerTurn, streak);
        
        if (game.isSelected()) {
            if (game.playMove(row, col)) {
                // Move sound
                playSound(display.getLocation(), "minecraft:block.wood.place");
                
                // Check for winner
                if (game.checkWinner()) {
                    winner = (playerTurn == 0) ? "W" : "B";
                    playSound(display.getLocation(), "minecraft:entity.firework_rocket.blast");
                    playSound(display.getLocation(), "minecraft:block.note_block.bell");
                    playParticle(display.getLocation(), "minecraft:happy_villager", 5, 0.5, 0.5, 0.5, 0.1);
                    playParticle(display.getLocation(), "minecraft:firework", 5, 0.5, 0.5, 0.5, 0.1);
                }
                
                // Change turn
                playerTurn = game.changeTurn();
            }
            if (game.hasCaptured()) playSound(display.getLocation(), "minecraft:block.wool.break");
            boardData = game.getBoardAsCharacters();
        } else {
            game.selectPiece(row, col);
            boardData = game.getBoardAsCharacters();
        }

        // 3. SAVE updated data
        setDataString(display, NAMESPACE, BOARD_KEY, boardData);
        setDataString(display, NAMESPACE, TURN_KEY, String.valueOf(playerTurn));
        setDataString(display, NAMESPACE, WINNER_KEY, winner);
        setDataString(display, NAMESPACE, STREAK_KEY, String.valueOf(game.getStreak()));

        // 4. VISUALIZE (update display)
        updateDisplay(display, game);
    }

    private void updateDisplay(TextDisplay display, CheckersGame game) {
        String[] symbols = game.getBoardAsSymbolsArray();
        display.text(Component.text(formatBoard(symbols), NamedTextColor.BLACK));
    }

    private String formatBoard(String[] symbols) {
        StringBuilder sb = new StringBuilder();
        
        for (int row = 0; row < BOARD_HEIGHT; row++) {
            for (int col = 0; col < BOARD_WIDTH; col++) {
                int index = row * BOARD_WIDTH + col;
                sb.append(replaceFontImages(symbols[index]));
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }

    // ============================================================================
    // GAME LOGIC
    // ============================================================================
    private static class CheckersGame {
        private int boardHeight;
        private int boardWidth;
        private Piece[][] board;
        private int playerTurn;
        private int[] selectedXY;
        private boolean streak; // Indicates if there's a multi-capture in progress
        private boolean didCapture = false;

        private final String EMPTY_CHARACTER = " ";
        private final String EMPTY_SYMBOL = ":board_games_empty:";

        public CheckersGame(int height, int width, String data, int turn, boolean streak) {
            this.boardHeight = height;
            this.boardWidth = width;
            this.playerTurn = turn;
            this.streak = streak;
            this.board = new Piece[height][width];
            this.selectedXY = findSelectedPiece(data);
            convertDataToBoard(data);
        }

        private int[] findSelectedPiece(String data) {
            if (data == null || data.isEmpty()) return null;
            for (int index = 0; index < data.length(); index++) {
                if (Character.isLowerCase(data.charAt(index))) {
                    return new int[]{index / boardWidth, index % boardWidth};
                }
            }
            return null;
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
            // if a piece is clicked try to select/unselect
            if (board[x][y] != null && !selectPiece(x, y)) return false;
            
            int fromX = selectedXY[0];
            int fromY = selectedXY[1];
            if (!isPathValid(fromX, fromY, x, y)) return false;
            // Get selected piece
            Piece selectedPiece = board[fromX][fromY];

            // Check if a piece is captured 
            int[] captured = selectedPiece.isValidCapture(fromX, fromY, x, y);
            didCapture = false;
            
            if (captured != null && board[captured[0]][captured[1]] != null) {
                board[captured[0]][captured[1]] = null;
                didCapture = true;
            }

            if (streak && !didCapture) return false; 

            if (!selectedPiece.isValidMove(fromX, fromY, x, y) && !didCapture) return false;
            
            // Move piece
            board[x][y] = selectedPiece;
            board[fromX][fromY] = null;
            selectedXY = new int[]{x, y};
            
            // Promotion
            if (x == 0 || x == boardHeight - 1) board[x][y].promote();
            
            // If there was a capture, check if there are more possible captures
            streak = false;
            if (didCapture && hasCaptures(x, y)) {
                // Keep piece selected and activate streak
                streak = true;
                return false; // Don't change turn
            }

            deselectCurrent();
            return true;
        }

        public int changeTurn() {
            playerTurn ^= 1;
            return playerTurn;
        }

        public boolean selectPiece(int x, int y) {
            Piece piece = board[x][y];
            if (streak || piece == null || piece.getPlayer() != playerTurn) return false;

            // If already selected -> deselect
            if (selectedXY != null && selectedXY[0] == x && selectedXY[1] == y) {
                deselectCurrent();
                return false;
            }

            selectAt(x, y);
            return true;
        }

        private void selectAt(int x, int y) {
            deselectCurrent();

            if (board[x][y] != null) {
                board[x][y].setSelected(true);
                selectedXY = new int[]{x, y};
            }
        }

        private void deselectCurrent() {
            if (selectedXY != null) {
                Piece piece = board[selectedXY[0]][selectedXY[1]];
                if (piece != null) piece.setSelected(false);
                selectedXY = null;
            }
        }

        public boolean isSelected() {
            return selectedXY != null;
        }

        public boolean getStreak() {
            return streak;
        }

        public boolean hasCaptured() {
            return didCapture;
        }

        public boolean checkWinner() {
            for (int fromX = 0; fromX < boardHeight; fromX++) {
                for (int fromY = 0; fromY < boardWidth; fromY++) {
                    Piece piece = board[fromX][fromY];
                    if (piece == null || piece.getPlayer() == playerTurn) continue;

                    for (int toX = 0; toX < boardHeight; toX++) {
                        for (int toY = 0; toY < boardWidth; toY++) {

                            if (fromX == toX && fromY == toY) continue;
                            if (!isPathValid(fromX, fromY, toX, toY)) continue;

                            if (piece.isValidMove(fromX, fromY, toX, toY)) return false;
                            int[] captured = piece.isValidCapture(fromX, fromY, toX, toY);
                            if (captured != null && board[captured[0]][captured[1]] != null) return false;
                        }
                    }
                }
            }
            return true;
        }

        public boolean hasCaptures(int x, int y) {
            Piece piece = board[x][y];
            if (piece == null) return false;

            // Diagonal directions
            int[][] directions = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

            for (int[] dir : directions) {
                // For normal pieces, only check the valid direction
                if (!piece.isQueen()) {
                    int validDir = (piece.getPlayer() == 0) ? -1 : 1;
                    if (dir[0] != validDir) continue;
                }

                int maxDistance = piece.isQueen() ? Math.max(boardHeight, boardWidth) : 2;

                for (int distance = 1; distance < maxDistance; distance++) {
                    int checkX = x + dir[0] * distance;
                    int checkY = y + dir[1] * distance;

                    if (checkX < 0 || checkX >= boardHeight || checkY < 0 || checkY >= boardWidth) break;

                    Piece target = board[checkX][checkY];

                    if (target == null) continue; // empty square, keep searching

                    if (target.getPlayer() == piece.getPlayer()) break; // friendly piece, direction blocked

                    // enemy piece found, check landing square
                    int landX = checkX + dir[0];
                    int landY = checkY + dir[1];

                    if (landX >= 0 && landX < boardHeight && landY >= 0 && landY < boardWidth && board[landX][landY] == null) {
                        return true; // capture possible
                    }

                    break; // direction blocked after enemy piece
                }
            }

            return false;
        }

        public boolean isPathValid(int fromX, int fromY, int toX, int toY) {
            if (board[toX][toY] != null) return false;

            Piece piece = board[fromX][fromY];
            int diffX = toX - fromX;
            int diffY = toY - fromY;

            if (Math.abs(diffX) != Math.abs(diffY)) return false;

            int stepX = Integer.compare(diffX, 0);
            int stepY = Integer.compare(diffY, 0);

            int enemyX = -1, enemyY = -1;
            boolean foundEnemy = false;

            for (int i = 1; i < Math.abs(diffX); i++) {
                int checkX = fromX + stepX * i;
                int checkY = fromY + stepY * i;
                Piece target = board[checkX][checkY];

                if (target == null) continue;

                if (target.getPlayer() == piece.getPlayer()) return false;

                if (!foundEnemy) {
                    enemyX = checkX;
                    enemyY = checkY;
                    foundEnemy = true;
                } else {
                    return false; // Second piece
                }
            }

            if (foundEnemy) return (toX == enemyX + stepX) && (toY == enemyY + stepY);
            return true; // Clear path without captures
        }
        
    }

    private static class Piece {
        public static final String[] CHARACTERS = {"W", "B", "E", "N"};
        public static final String[] SYMBOLS = {":checkers_w:", ":checkers_b:", ":checkers_w_queen:", ":checkers_b_queen:"};
        public static final String[] SEL_CHARACTERS = {"w", "b", "e", "n"};
        public static final String[] SEL_SYMBOLS = {":checkers_w_sel:", ":checkers_b_sel:", ":checkers_w_queen_sel:", ":checkers_b_queen_sel:"};
        
        private boolean selected = false;
        private boolean isQueen = false;
        private int player;

        public Piece(String character) {
            this.player = Arrays.asList(CHARACTERS).indexOf(character.toUpperCase());
            if (this.player > 1) {
                this.player -= 2;
                this.isQueen = true;
            }
            if (character.equals(character.toLowerCase()) && !character.equals(" ")) {
                this.selected = true;
            }
        }

        public String getCharacter() {
            int index = player + (isQueen ? 2 : 0);
            return selected ? SEL_CHARACTERS[index] : CHARACTERS[index];
        }

        public String getSymbol() {
            int index = player + (isQueen ? 2 : 0);
            return selected ? SEL_SYMBOLS[index] : SYMBOLS[index];
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        public int getPlayer() {
            return player;
        }

        public boolean isQueen() {
            return isQueen;
        }

        public void promote() {
            isQueen = true;
        }

        public boolean isValidMove(int fromX, int fromY, int toX, int toY) {
            int diffX = toX - fromX;
            int diffY = Math.abs(toY - fromY);

            // Queens: any diagonal distance (path already validated in CheckersGame)
            if (isQueen) return diffY > 0;

            // Normal pieces: only 1 square
            if (diffY != 1) return false;
            return (player == 0) ? diffX == -1 : diffX == 1;
        }

        public int[] isValidCapture(int fromX, int fromY, int toX, int toY) {
            int diffX = toX - fromX;
            int diffY = toY - fromY;

            // Must be diagonal
            if (Math.abs(diffX) != Math.abs(diffY)) return null;
            
            // Must have distance to capture (minimum 2 squares)
            if (Math.abs(diffX) < 2) return null;

            // Move direction
            int stepX = Integer.compare(diffX, 0);
            int stepY = Integer.compare(diffY, 0);

            // The captured piece is right before the destination
            int capturedX = toX - stepX;
            int capturedY = toY - stepY;

            // Validate direction for normal pieces
            if (!isQueen) {
                boolean validDirection = (player == 0) ? diffX < 0 : diffX > 0;
                if (!validDirection) return null;
            }

            return new int[]{capturedX, capturedY};
        }
    }
}