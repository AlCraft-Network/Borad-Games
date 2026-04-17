package scripts;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
import static dev.lone.itemsadder.api.scriptinginternal.ScriptingUtils.getDataString;
import static dev.lone.itemsadder.api.scriptinginternal.ScriptingUtils.setDataString;
import static dev.lone.itemsadder.api.FontImages.FontImageWrapper.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class chess extends ItemScript {
    private class Settings {
        public static final boolean ENABLE_RESTARTING = true;
        public static final int RESET_DELAY_SECONDS = 5;
        public static final String  RESET_MSG = "§7If you want to restart the game, use §fSHIFT + CLICK";
        public static final String  RESTARTING_MSG = "§7Restarting in §f{s} s§7...";
        public static final boolean ENABLE_PERSISTENCE = true;
    }

    private final int BOARD_HEIGHT = 8;
    private final int BOARD_WIDTH = 8;
    
    private final String BOARD_KEY = "chess_board";
    private final String TURN_KEY = "chess_turn";
    private final String WINNER_KEY = "chess_winner";
    private final String CASTLING_KEY = "chess_castling";
    private final String RESET_PENDING_KEY = "ttt_reset_pending";
    private final String RESET_AVAILABLE_KEY = "ttt_reset_available";
    private final String NAMESPACE = "game";

    private final String DEFAULT_TURN = "0";
    private final String DEFAULT_WINNER = "";
    private final String DEFAULT_CASTLING = "0";
    private final String DEFAULT_BOARD = "TCAEMACT" + 
                                         "OOOOOOOO" + 
                                         " ".repeat(32) + 
                                         "PPPPPPPP" + 
                                         "RNBQKBNR";

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
        setDataString(container, NAMESPACE, CASTLING_KEY, getDataString(display, NAMESPACE, CASTLING_KEY, DEFAULT_CASTLING));

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
        String castlingData = getDataString(container, NAMESPACE, CASTLING_KEY, DEFAULT_CASTLING);

        this.hasRestored = !boardData.equals(DEFAULT_BOARD);

        // Initialize empty board
        setBoard(display, boardData, turnData, winnerData, castlingData);

        return display;
    }

    private void setBoard(TextDisplay display, String boardData, String turnData, String winnerData, String castlingData) {
        ChessGame game = new ChessGame(BOARD_HEIGHT, BOARD_WIDTH, boardData, Integer.parseInt(turnData), Integer.parseInt(castlingData));

        display.text(Component.text(formatBoard(game.getBoardAsSymbolsArray()), NamedTextColor.BLACK));

        setDataString(display, NAMESPACE, BOARD_KEY, boardData);
        setDataString(display, NAMESPACE, TURN_KEY, turnData);
        setDataString(display, NAMESPACE, WINNER_KEY, winnerData);
        setDataString(display, NAMESPACE, CASTLING_KEY, castlingData);
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
                    setBoard(display, DEFAULT_BOARD, DEFAULT_TURN, DEFAULT_WINNER, DEFAULT_CASTLING);
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
        int castlingRights = Integer.parseInt(getDataString(display, NAMESPACE, CASTLING_KEY, DEFAULT_CASTLING));

        // If there's already a winner, do nothing
        if (!winner.isEmpty()) return;

        // 2. PROCESS with game logic
        ChessGame game = new ChessGame(BOARD_HEIGHT, BOARD_WIDTH, boardData, playerTurn, castlingRights);
        
        if (game.isSelected()) {
            if (game.playMove(row, col)) {
                // Move sound
                playSound(display.getLocation(), "minecraft:block.wood.place");
                
                // Check for checkmate or stalemate for the OPPONENT (who will play next)
                int opponent = (playerTurn == 0) ? 1 : 0;
                if (game.isCheckmate(opponent)) {
                    // Opponent is in checkmate -> current player wins
                    winner = (playerTurn == 0) ? "W" : "B";
                    playSound(display.getLocation(), "minecraft:entity.firework_rocket.blast");
                    playSound(display.getLocation(), "minecraft:block.note_block.bell");
                    playParticle(display.getLocation(), "minecraft:happy_villager", 5, 0.5, 0.5, 0.5, 0.1);
                    playParticle(display.getLocation(), "minecraft:firework", 5, 0.5, 0.5, 0.5, 0.1);
                } else if (game.isStalemate(opponent)) {
                    // Stalemate
                    winner = "T";
                    playSound(display.getLocation(), "minecraft:block.note_block.cow_bell");
                    playParticle(display.getLocation(), "minecraft:cloud", 5, 0.5, 0.5, 0.5, 0.1);
                    playParticle(display.getLocation(), "minecraft:crit", 5, 0.5, 0.5, 0.5, 0.1);
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
        setDataString(display, NAMESPACE, CASTLING_KEY, String.valueOf(game.getCastlingRights()));

        // 4. VISUALIZE (update display)
        updateDisplay(display, game);
    }

    private void updateDisplay(TextDisplay display, ChessGame game) {
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
    class ChessGame {
        private int boardHeight;
        private int boardWidth;
        private Piece[][] board;
        private int playerTurn;
        private int[] selectedXY;
        private boolean didCapture = false;
        private int castlingRights; // Bit system for castling rights

        // Bit masks for castlingRights
        private static final int WHITE_KING_MOVED = 1 << 0;      // Bit 0
        private static final int WHITE_ROOK_LEFT_MOVED = 1 << 1; // Bit 1
        private static final int WHITE_ROOK_RIGHT_MOVED = 1 << 2; // Bit 2
        private static final int BLACK_KING_MOVED = 1 << 3;      // Bit 3
        private static final int BLACK_ROOK_LEFT_MOVED = 1 << 4; // Bit 4
        private static final int BLACK_ROOK_RIGHT_MOVED = 1 << 5; // Bit 5

        private final String EMPTY_CHARACTER = " ";
        private final String EMPTY_SYMBOL = ":board_games_empty:";

        public ChessGame(int height, int width, String data, int turn, int castlingRights) {
            this.boardHeight = height;
            this.boardWidth = width;
            this.playerTurn = turn;
            this.castlingRights = castlingRights;
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
                char c = data.charAt(i);
                board[row][col] = (String.valueOf(c).equals(EMPTY_CHARACTER)) ? null : createPiece(c);
            }
        }

        private int findIndexOf(String[] chars, char character) {
            for (int i = 0; i < chars.length; i++) {
                if (chars[i].charAt(0) == Character.toUpperCase(character)) return i;
            }
            return -1;
        }

        private Piece createPiece(char character) {
            int player;
            boolean selected = Character.isLowerCase(character);
            if ((player = findIndexOf(Pawn.CHARACTERS, character)) != -1) return new Pawn(player, selected);
            if ((player = findIndexOf(Rook.CHARACTERS, character)) != -1) return new Rook(player, selected);
            if ((player = findIndexOf(Knight.CHARACTERS, character)) != -1) return new Knight(player, selected);
            if ((player = findIndexOf(Bishop.CHARACTERS, character)) != -1) return new Bishop(player, selected);
            if ((player = findIndexOf(Queen.CHARACTERS, character)) != -1) return new Queen(player, selected);
            if ((player = findIndexOf(King.CHARACTERS, character)) != -1) return new King(player, selected);
            
            return null;
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

        public int getCastlingRights() {
            return castlingRights;
        }

        private boolean isCastlingMove(int fromX, int fromY, int toX, int toY) {
            // Detect if the move is castling (king moves 2 squares)
            if (!(board[fromX][fromY] instanceof King)) return false;
            return Math.abs(toY - fromY) == 2;
        }

        private boolean canCastle(int player, boolean kingSide) {
            int homeRow = (player == 0) ? 7 : 0;
            int offset = player * 3; // 0–2 white, 3–5 black

            // King moved
            if ((castlingRights & (1 << offset)) != 0) return false;

            // Rook moved
            int rookBit = kingSide ? offset + 2 : offset + 1;
            if ((castlingRights & (1 << rookBit)) != 0) return false;

            // Squares between king and rook
            int[] cols = kingSide ? new int[]{5, 6} : new int[]{1, 2, 3};

            for (int col : cols) {
                if (board[homeRow][col] != null) return false;
            }

            return true;
        }

        private boolean tryCastling(int fromX, int fromY, int toX, int toY) {
            boolean kingSide = toY > fromY;
            if (!canCastle(playerTurn, kingSide)) return false;
            if (isInCheck(playerTurn)) return false;

            int middleCol = kingSide ? fromY + 1 : fromY - 1;

            if (wouldBeInCheck(fromX, fromY, fromX, middleCol, playerTurn)) return false;
            if (wouldBeInCheck(fromX, fromY, toX, toY, playerTurn)) return false;

            deselectCurrent();
            executeCastling(fromX, fromY, toX, toY);
            markPieceMoved(toX, toY);

            selectedXY = null;
            return true;
        }

        private void markPieceMoved(int row, int col) {
            Piece piece = board[row][col];
            if (piece == null) return;

            int player = piece.getPlayer();

            // === KING ===
            if (piece instanceof King) {
                castlingRights |= (player == 0) ? WHITE_KING_MOVED : BLACK_KING_MOVED;
                return;
            }

            // === ROOK ===
            if (!(piece instanceof Rook)) return;

            int homeRow = (player == 0) ? 7 : 0;
            if (row != homeRow) return;
            if (col == 0) castlingRights |= (player == 0) ? WHITE_ROOK_LEFT_MOVED : BLACK_ROOK_LEFT_MOVED;
            if (col == 7) castlingRights |= (player == 0) ? WHITE_ROOK_RIGHT_MOVED : BLACK_ROOK_RIGHT_MOVED;
        }

        private void executeCastling(int fromX, int fromY, int toX, int toY) {
            boolean kingSide = toY > fromY;

            // Move the king
            board[toX][toY] = board[fromX][fromY];
            board[fromX][fromY] = null;

            // Determine rook and destination square
            int rookFromCol = kingSide ? 7 : 0;
            int rookToCol = kingSide ? toY - 1 : toY + 1;

            // Move the rook
            board[toX][rookToCol] = board[toX][rookFromCol];
            board[toX][rookFromCol] = null;
        }

        public boolean isPathClear(int fromX, int fromY, int toX, int toY) {
            Piece movingPiece = board[fromX][fromY];

            // Destination occupied by own piece
            Piece destPiece = board[toX][toY];
            if (destPiece != null && destPiece.getPlayer() == movingPiece.getPlayer()) return false;

            int stepX = Integer.compare(toX - fromX, 0);
            int stepY = Integer.compare(toY - fromY, 0);

            int length = Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY));

            for (int i = 1; i < length; i++) {
                if (board[fromX + stepX * i][fromY + stepY * i] != null) return false;
            }

            return true;
        }

        public boolean playMove(int x, int y) {
            if (!isSelected()) return selectPiece(x, y);

            int fromX = selectedXY[0];
            int fromY = selectedXY[1];

            if (fromX == x && fromY == y) {
                deselectCurrent();
                return false;
            }

            Piece selectedPiece = board[fromX][fromY];

            if (board[x][y] != null && board[x][y].getPlayer() == playerTurn) {
                selectAt(x, y);
                return false;
            }

            // Castling attempt
            if (isCastlingMove(fromX, fromY, x, y)) return tryCastling(fromX, fromY, x, y);

            // Normal move validations
            if (!selectedPiece.isValidMove(fromX, fromY, x, y, board[x][y] != null)) return false;
            if (!selectedPiece.canJump() && !isPathClear(fromX, fromY, x, y)) return false;
            if (wouldBeInCheck(fromX, fromY, x, y, playerTurn)) return false;

            didCapture = board[x][y] != null;

            // Execute move
            markPieceMoved(fromX, fromY);

            deselectCurrent();
            board[x][y] = selectedPiece;
            board[fromX][fromY] = null;

            // Promotion
            if (selectedPiece instanceof Pawn && (x == 0 || x == boardHeight - 1)) {
                board[x][y] = new Queen(selectedPiece.getPlayer(), false);
            }

            return true;
        }

        public int changeTurn() {
            playerTurn ^= 1;
            return playerTurn;
        }

        public boolean selectPiece(int x, int y) {
            Piece piece = board[x][y];
            if (piece == null || piece.getPlayer() != playerTurn) return false;

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

        public boolean hasCaptured() {
            return didCapture;
        }

        public boolean checkWinner() {
            for (Piece[] row : board) {
                for (Piece piece : row) {
                    if (piece != null && piece.getPlayer() != playerTurn && piece instanceof King) {
                        return false;
                    }
                }
            }
            return true;
        }

        private int[] findKing(int player) {
            // Find the position of a player's king
            for (int i = 0; i < boardHeight; i++) {
                for (int j = 0; j < boardWidth; j++) {
                    Piece piece = board[i][j];
                    if (piece instanceof King && piece.getPlayer() == player) {
                        return new int[]{i, j};
                    }
                }
            }
            return null;
        }

        public boolean isInCheck(int player) {
            int[] kingXY = findKing(player);
            if (kingXY == null) return false; // King captured (shouldn't happen)
            
            // Check if any enemy piece can attack the king
            for (int i = 0; i < boardHeight; i++) {
                for (int j = 0; j < boardWidth; j++) {
                    Piece piece = board[i][j];
                    if (piece != null && piece.getPlayer() != player) {
                        // Can this enemy piece attack the king?
                        if (piece.isValidMove(i, j, kingXY[0], kingXY[1], board[kingXY[0]][kingXY[1]] != null) && 
                            (piece.canJump() || isPathClear(i, j, kingXY[0], kingXY[1]))) {
                                return true; // King in check
                        }
                    }
                }
            }
            return false;
        }


        private boolean wouldBeInCheck(int fromX, int fromY, int toX, int toY, int player) {
            Piece movingPiece = board[fromX][fromY];
            Piece temp = board[toX][toY];

            // Simulate move
            board[toX][toY] = movingPiece;
            board[fromX][fromY] = null;

            boolean result = isInCheck(player);

            // Undo move
            board[fromX][fromY] = movingPiece;
            board[toX][toY] = temp;

            return result;
        }

        public boolean checkLegalMoves(int player) {
            int totalSquares = boardHeight * boardWidth;
            for (int fromIndex = 0; fromIndex < totalSquares; fromIndex++) {
                int fromX = fromIndex / boardWidth;
                int fromY = fromIndex % boardWidth;

                Piece piece = board[fromX][fromY];
                if (piece == null || piece.getPlayer() != player) continue;

                for (int toIndex = 0; toIndex < totalSquares; toIndex++) {
                    int toX = toIndex / boardWidth;
                    int toY = toIndex % boardWidth;

                    if (fromX == toX && fromY == toY) continue; // Skip same position
                    if (!piece.isValidMove(fromX, fromY, toX, toY, board[toX][toY] != null)) continue; // Check if move is valid according to piece rules
                    if (board[toX][toY] != null && board[toX][toY].getPlayer() == player) continue; // Check that it doesn't capture own piece
                    if (!piece.canJump() && !isPathClear(fromX, fromY, toX, toY)) continue; // Check clear path (except knight)

                    // Simulate the move and check if still in check
                    if (!wouldBeInCheck(fromX, fromY, toX, toY, player)) return false;
                }
            }

            return true; // No legal moves available
        }

        public boolean isCheckmate(int player) {
            return isInCheck(player) && checkLegalMoves(player);
        }

        public boolean isStalemate(int player) {
            return !isInCheck(player) && checkLegalMoves(player);
        }
    }

    // ============================================================================
    // PIECE CLASSES
    // ============================================================================

    // Abstract base class
    abstract class Piece {
        protected int player; // 0 = white, 1 = black
        protected boolean selected;
        private String[] characters;
        private String[] selCharacters;
        private String[] symbols;
        private String[] selSymbols;

        public Piece(int player, boolean selected, String[] characters, String[] selCharacters, 
                    String[] symbols, String[] selSymbols) {
            this.player = player;
            this.selected = selected;
            this.characters = characters;
            this.selCharacters = selCharacters;
            this.symbols = symbols;
            this.selSymbols = selSymbols;
        }

        public String getCharacter() {
            return selected ? selCharacters[player] : characters[player];
        }

        public String getSymbol() {
            return selected ? selSymbols[player] : symbols[player];
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        public boolean isSelected() {
            return selected;
        }

        public int getPlayer() {
            return player;
        }

        public boolean canJump() {
            return false; // By default pieces cannot jump
        }

        public abstract boolean isValidMove(int fromX, int fromY, int toX, int toY, boolean isCapture);
    }

    class Pawn extends Piece {
        public static final String[] CHARACTERS = {"P", "O"};
        public static final String[] SEL_CHARACTERS = {"p", "o"};
        public static final String[] SYMBOLS = {":chess_w_pawn:", ":chess_b_pawn:"};
        public static final String[] SEL_SYMBOLS = {":chess_w_pawn_sel:", ":chess_b_pawn_sel:"};
        
        public Pawn(int player, boolean selected) {
            super(player, selected, CHARACTERS, SEL_CHARACTERS, SYMBOLS, SEL_SYMBOLS);
        }

        @Override
        public boolean isValidMove(int fromX, int fromY, int toX, int toY, boolean isCapture) {
            int diffX = toX - fromX;
            int diffY = toY - fromY;
            int absDiffY = Math.abs(diffY);

            // Direction based on player (white moves up = -1, black moves down = +1)
            int direction = (player == 0) ? -1 : 1;
            
            // Normal move (1 square forward)
            if (!isCapture && diffX == direction && diffY == 0) return true;
            
            // Initial move (2 squares forward from starting position)
            int startRow = (player == 0) ? 6 : 1;
            if (!isCapture && fromX == startRow && diffX == direction * 2 && diffY == 0) return true;
            
            // Diagonal capture
            if (isCapture && diffX == direction && absDiffY == 1) return true;
            
            return false;
        }
    }

    class Rook extends Piece {
        public static final String[] CHARACTERS = {"R", "T"};
        public static final String[] SEL_CHARACTERS = {"r", "t"};
        public static final String[] SYMBOLS = {":chess_w_rook:", ":chess_b_rook:"};
        public static final String[] SEL_SYMBOLS = {":chess_w_rook_sel:", ":chess_b_rook_sel:"};
        
        public Rook(int player, boolean selected) {
            super(player, selected, CHARACTERS, SEL_CHARACTERS, SYMBOLS, SEL_SYMBOLS);
        }

        @Override
        public boolean isValidMove(int fromX, int fromY, int toX, int toY, boolean isCapture) {
            int diffX = toX - fromX;
            int diffY = toY - fromY;
            return (diffX == 0 || diffY == 0) && (diffX != 0 || diffY != 0);
        }
    }

    class Knight extends Piece {
        public static final String[] CHARACTERS = {"N", "C"};
        public static final String[] SEL_CHARACTERS = {"n", "c"};
        public static final String[] SYMBOLS = {":chess_w_knight:", ":chess_b_knight:"};
        public static final String[] SEL_SYMBOLS = {":chess_w_knight_sel:", ":chess_b_knight_sel:"};
        
        public Knight(int player, boolean selected) {
            super(player, selected, CHARACTERS, SEL_CHARACTERS, SYMBOLS, SEL_SYMBOLS);
        }

        @Override
        public boolean canJump() {
            return true;
        }

        @Override
        public boolean isValidMove(int fromX, int fromY, int toX, int toY, boolean isCapture) {
            int absDiffX = Math.abs(toX - fromX);
            int absDiffY = Math.abs(toY - fromY);
            return (absDiffX == 2 && absDiffY == 1) || (absDiffX == 1 && absDiffY == 2);
        }
    }

    class Bishop extends Piece {
        public static final String[] CHARACTERS = {"B", "A"};
        public static final String[] SEL_CHARACTERS = {"b", "a"};
        public static final String[] SYMBOLS = {":chess_w_bishop:", ":chess_b_bishop:"};
        public static final String[] SEL_SYMBOLS = {":chess_w_bishop_sel:", ":chess_b_bishop_sel:"};
        
        public Bishop(int player, boolean selected) {
            super(player, selected, CHARACTERS, SEL_CHARACTERS, SYMBOLS, SEL_SYMBOLS);
        }

        @Override
        public boolean isValidMove(int fromX, int fromY, int toX, int toY, boolean isCapture) {
            int absDiffX = Math.abs(toX - fromX);
            int absDiffY = Math.abs(toY - fromY);
            return absDiffX == absDiffY && absDiffX > 0;
        }
    }

    class Queen extends Piece {
        public static final String[] CHARACTERS = {"Q", "E"};
        public static final String[] SEL_CHARACTERS = {"q", "e"};
        public static final String[] SYMBOLS = {":chess_w_queen:", ":chess_b_queen:"};
        public static final String[] SEL_SYMBOLS = {":chess_w_queen_sel:", ":chess_b_queen_sel:"};
        
        public Queen(int player, boolean selected) {
            super(player, selected, CHARACTERS, SEL_CHARACTERS, SYMBOLS, SEL_SYMBOLS);
        }

        @Override
        public boolean isValidMove(int fromX, int fromY, int toX, int toY, boolean isCapture) {
            int diffX = toX - fromX;
            int diffY = toY - fromY;
            int absDiffX = Math.abs(diffX);
            int absDiffY = Math.abs(diffY);

            return ((diffX == 0 || diffY == 0) || (absDiffX == absDiffY)) && (absDiffX > 0 || absDiffY > 0);
        }
    }

    class King extends Piece {
        public static final String[] CHARACTERS = {"K", "M"};
        public static final String[] SEL_CHARACTERS = {"k", "m"};
        public static final String[] SYMBOLS = {":chess_w_king:", ":chess_b_king:"};
        public static final String[] SEL_SYMBOLS = {":chess_w_king_sel:", ":chess_b_king_sel:"};
        
        public King(int player, boolean selected) {
            super(player, selected, CHARACTERS, SEL_CHARACTERS, SYMBOLS, SEL_SYMBOLS);
        }

        @Override
        public boolean isValidMove(int fromX, int fromY, int toX, int toY, boolean isCapture) {
            int absDiffX = Math.abs(toX - fromX);
            int absDiffY = Math.abs(toY - fromY);

            return (absDiffX <= 1 && absDiffY <= 1 && (absDiffX > 0 || absDiffY > 0))
                   || (absDiffX == 0 && absDiffY == 2); // Castling
        }
    }
}