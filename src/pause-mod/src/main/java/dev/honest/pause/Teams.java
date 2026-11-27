package dev.honest.pause;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Teams from config/naked-eye/teams.txt, set up as vanilla scoreboard teams (team chat with /teammsg, colours,
 * friendly fire, seeing invisible team-mates — all the game's own):
 *
 *   red: Agent, Bob
 *   blue: Carol, Dave
 *   friendly_fire: false        (optional, default false)
 *
 * A team named after a colour (red, blue, green, yellow, aqua, gold, …) gets that colour. Re-read when it changes.
 */
public final class Teams {
    static long stamp = -2;
    static final TeamColor[] CYCLE = {TeamColor.RED, TeamColor.BLUE, TeamColor.GREEN, TeamColor.YELLOW, TeamColor.AQUA,
            TeamColor.GOLD, TeamColor.LIGHT_PURPLE, TeamColor.WHITE};

    static void tick(MinecraftServer s) {
        if (s.getTickCount() % 20 != 0) return;
        try {
            Path f = Path.of("config", "naked-eye", "teams.txt");
            long st = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : -1;
            if (st == stamp) return;
            stamp = st;
            if (st < 0) return;
            Map<String, List<String>> teams = new LinkedHashMap<>(); boolean ff = false;
            for (String l : Files.readAllLines(f)) {
                l = l.strip(); if (l.isEmpty() || l.startsWith("#") || !l.contains(":")) continue;
                String k = l.substring(0, l.indexOf(':')).strip(), v = l.substring(l.indexOf(':') + 1).strip();
                if (k.equals("friendly_fire")) { ff = Boolean.parseBoolean(v); continue; }
                List<String> m = new ArrayList<>();
                for (String n : v.split("[,\\s]+")) if (!n.isEmpty()) m.add(n);
                teams.put(k, m);
            }
            var sb = s.getScoreboard(); int i = 0;
            for (var e : teams.entrySet()) {
                PlayerTeam t = sb.getPlayerTeam(e.getKey());
                if (t == null) t = sb.addPlayerTeam(e.getKey());
                TeamColor c; try { c = TeamColor.byName(e.getKey()); } catch (Exception x) { c = null; }
                t.setColor(Optional.of(c != null ? c : CYCLE[i % CYCLE.length])); i++;
                t.setAllowFriendlyFire(ff);
                t.setSeeFriendlyInvisibles(true);
                for (String n : e.getValue()) sb.addPlayerToTeam(n, t);
            }
            org.slf4j.LoggerFactory.getLogger("naked-eye").info("[teams] {} (friendly fire {})", teams, ff);
        } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("naked-eye").warn("[teams] couldn't read teams.txt: {}", e.toString()); }
    }
}
