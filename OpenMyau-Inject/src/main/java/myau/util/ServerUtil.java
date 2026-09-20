package myau.util;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.init.Blocks;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ServerUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static ArrayList<String> getScoreboardLines() {
        if (ServerUtil.mc.theWorld == null) {
            return new ArrayList<>();
        }
        Scoreboard scoreboard = ServerUtil.mc.theWorld.getScoreboard();
        if (scoreboard == null) {
            return new ArrayList<>();
        }
        ScoreObjective scoreObjective = scoreboard.getObjectiveInDisplaySlot(1);
        if (scoreObjective == null) {
            return new ArrayList<>();
        }
        return (ArrayList<String>) scoreboard.getSortedScores(scoreObjective).stream().map(score -> ScorePlayerTeam.formatPlayerName(scoreboard.getPlayersTeam(score.getPlayerName()), score.getPlayerName())).collect(Collectors.toList());
    }

    public static boolean isHypixel() {
        ArrayList<String> arrayList = ServerUtil.getScoreboardLines();
        if (arrayList.isEmpty()) return false;
        if (arrayList.get(0).equals("§ewww.hypixel.ne🎂§et")) return true;
        return arrayList.get(0).equals("§ewww.hypixel.ne§g§et");
    }

    public static boolean hasPlayerCountInfo() {
        for (String s : ServerUtil.getScoreboardLines()) {
            if (!s.matches(".*Players: §a\\d+/\\d+.*")) continue;
            return true;
        }
        return false;
    }

    private static final Pattern HYPIXEL_DOMAIN = Pattern.compile(
            "^(?:[a-zA-Z0-9-]+\\.)*(?:hypixel\\.net|hypixel\\.io|technoblade\\.club)(?:\\.)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HYPIXEL_BRAND =
            Pattern.compile("Hypixel BungeeCord \\(.+\\) <- .+");
    private static final int LOBBY_COLUMN_THRESHOLD = 16;
    private static long watchdogCacheBucket = Long.MIN_VALUE;
    private static boolean watchdogCached = false;

    public static boolean isWatchdogServer() {
        if (mc.thePlayer == null || mc.theWorld == null || mc.isIntegratedServerRunning()) {
            return false;
        }
        long bucket = mc.theWorld.getTotalWorldTime() / 20L;
        if (watchdogCacheBucket == bucket) {
            return watchdogCached;
        }
        watchdogCacheBucket = bucket;
        watchdogCached = computeWatchdogServer();
        return watchdogCached;
    }

    private static boolean computeWatchdogServer() {
        String address = currentAddress();
        if (address.isEmpty() || address.contains("test")) {
            return false;
        }
        if (!HYPIXEL_DOMAIN.matcher(address).matches()) {
            return false;
        }
        String brand = mc.thePlayer.getClientBrand();
        if (brand != null && !brand.trim().isEmpty() && !HYPIXEL_BRAND.matcher(brand).matches()) {
            return false;
        }
        if (isSuperflatLobby()) {
            return false;
        }
        return scoreboardContains("www.hypixel");
    }

    private static String currentAddress() {
        ServerData data = mc.getCurrentServerData();
        if (data == null || data.serverIP == null) {
            return "";
        }
        String ip = data.serverIP.trim().toLowerCase(Locale.ENGLISH);
        int colon = ip.indexOf(58);
        return colon >= 0 ? ip.substring(0, colon) : ip;
    }

    private static boolean isSuperflatLobby() {
        int baseX = MathHelper.floor_double(mc.thePlayer.posX);
        int baseZ = MathHelper.floor_double(mc.thePlayer.posZ);
        int matches = 0;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (isLobbyColumn(baseX + x, baseZ + z)) {
                    matches++;
                }
            }
        }
        return matches >= LOBBY_COLUMN_THRESHOLD;
    }

    private static boolean isLobbyColumn(int x, int z) {
        return blockAt(x, 0, z) == Blocks.bedrock
                && blockAt(x, 1, z) == Blocks.dirt
                && blockAt(x, 2, z) == Blocks.dirt
                && blockAt(x, 3, z) == Blocks.grass
                && blockAt(x, 4, z) == Blocks.air
                && blockAt(x, 5, z) == Blocks.air;
    }

    private static Block blockAt(int x, int y, int z) {
        return mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock();
    }

    private static boolean scoreboardContains(String needle) {
        for (String line : getScoreboardLines()) {
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
            if (plain != null && plain.toLowerCase(Locale.ENGLISH).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
