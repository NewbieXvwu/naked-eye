package dev.honest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Crafting the way a player does it: click a recipe in the recipe book (which only lists recipes
 * you've unlocked), then click the result slot. Uses the 2x2 inventory grid, or the 3x3 grid if a
 * crafting table screen is open.
 */
public final class Crafting {
    private Crafting() {}

    public static String craft(Minecraft mc, String item, int times) {
        LocalPlayer pl = mc.player;
        if (!(pl.containerMenu instanceof AbstractCraftingMenu menu)) return "no crafting grid open (open a crafting table, or close other screens)";
        List<RecipeDisplayEntry> matches = new ArrayList<>();
        var ctx = SlotDisplayContext.fromLevel(mc.level);
        for (var coll : pl.getRecipeBook().getCollections())
            for (RecipeDisplayEntry e : coll.getRecipes())
                for (ItemStack r : e.resultItems(ctx))
                    if (Perception.itemName(r).equals(item)) { matches.add(e); break; }
        if (matches.isEmpty()) return "recipe for " + item + " not in your recipe book (not unlocked yet?)";
        Controller.start(mc, new Task(menu, matches, item, Math.max(1, Math.min(times, 64))));
        return "crafting";
    }

    static final class Task implements Controller.Task {
        final AbstractCraftingMenu menu; final List<RecipeDisplayEntry> options; final String item; final int times;
        int made = 0, phase = 0, wait = 0, option = 0;
        Task(AbstractCraftingMenu m, List<RecipeDisplayEntry> o, String item, int times) { menu = m; options = o; this.item = item; this.times = times; }
        public String describe() { return "craft " + item + " x" + times; }

        public String tick(Minecraft mc, LocalPlayer pl) {
            if (pl.containerMenu != menu) return "screen closed after " + made;
            if (wait-- > 0) return null;
            switch (phase) {
                case 0 -> { // click the recipe in the book
                    mc.gameMode.handlePlaceRecipe(menu.containerId, options.get(option).id(), false);
                    phase = 1; wait = 4;
                }
                case 1 -> { // click the result, put it in the inventory
                    ItemStack res = menu.getResultSlot().getItem();
                    if (res.isEmpty() || !Perception.itemName(res).equals(item)) {
                        if (++option < options.size()) { phase = 0; return null; }
                        clearGrid(mc, pl);
                        return made == 0 ? "missing ingredients (or recipe needs a bigger grid)" : "made " + made + ", then ran out";
                    }
                    mc.gameMode.handleContainerInput(menu.containerId, menu.getResultSlot().index, 0, ContainerInput.PICKUP, pl);
                    int dest = destination(pl, res);
                    if (dest < 0) { mc.gameMode.handleContainerInput(menu.containerId, menu.getResultSlot().index, 0, ContainerInput.PICKUP, pl); clearGrid(mc, pl); return "inventory full"; }
                    mc.gameMode.handleContainerInput(menu.containerId, dest, 0, ContainerInput.PICKUP, pl);
                    made++; phase = made >= times ? 2 : 3; wait = 3;
                }
                case 3, 2 -> { // never leave anything on the cursor
                    ItemStack carried = menu.getCarried();
                    if (!carried.isEmpty()) {
                        int dest = destination(pl, carried);
                        if (dest >= 0) mc.gameMode.handleContainerInput(menu.containerId, dest, 0, ContainerInput.PICKUP, pl);
                        wait = 2; return null;
                    }
                    if (phase == 3) { phase = 0; return null; }
                    clearGrid(mc, pl); return "made " + made + " " + item;
                }
            }
            return null;
        }

        int destination(LocalPlayer pl, ItemStack res) {
            int empty = -1;
            for (Slot s : menu.slots) {
                // main inventory + hotbar only (not armour / offhand), and only where it may go
                if (s.container != pl.getInventory() || s.getContainerSlot() >= 36 || !s.mayPlace(res)) continue;
                ItemStack st = s.getItem();
                if (ItemStack.isSameItemSameComponents(st, res) && st.getCount() + res.getCount() <= st.getMaxStackSize()) return s.index;
                if (st.isEmpty() && empty < 0) empty = s.index;
            }
            return empty;
        }

        void clearGrid(Minecraft mc, LocalPlayer pl) {
            for (Slot s : menu.getInputGridSlots())
                if (!s.getItem().isEmpty()) mc.gameMode.handleContainerInput(menu.containerId, s.index, 0, ContainerInput.QUICK_MOVE, pl);
        }
    }
}
