package com.techdevgroup.guidechain.manager;

import com.techdevgroup.guidechain.data.CompletionCondition;
import com.techdevgroup.guidechain.data.ConditionType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Evaluates {@link CompletionCondition}s against live game state each tick.
 * Also provides a human-readable "live value" string for the debug panel.
 */
@Singleton
public class ConditionEvaluator
{
    @Inject
    private Client client;

    /**
     * Returns {@code true} if the condition is currently satisfied.
     * MANUAL conditions always return {@code false} here — they are
     * satisfied only when the player presses Skip/Done.
     */
    public boolean evaluate(CompletionCondition c)
    {
        if (c == null || c.type == null) return false;
        switch (c.type)
        {
            case QUEST:     return evalQuest(c);
            case VARBIT:    return evalVarbit(c);
            case SKILL:     return evalSkill(c);
            case ITEM_HELD: return evalItemHeld(c);
            case REGION:    return evalRegion(c);
            case MANUAL:    return false;
            default:        return false;
        }
    }

    /**
     * Returns a short string describing the current live value of whatever
     * the condition is watching, for display in the debug panel.
     */
    public String liveValue(CompletionCondition c)
    {
        if (c == null || c.type == null) return "?";
        try
        {
            switch (c.type)
            {
                case QUEST:
                    Quest q = Quest.valueOf(c.questName);
                    QuestState qs = q.getState(client);
                    return qs != null ? qs.name() : "null";
                case VARBIT:
                    return String.valueOf(client.getVarbitValue(c.varbitId));
                case SKILL:
                    Skill sk = Skill.valueOf(c.skill);
                    return String.valueOf(client.getRealSkillLevel(sk));
                case ITEM_HELD:
                    return String.valueOf(countInventory(c.itemId));
                case REGION:
                    WorldPoint loc = client.getLocalPlayer() != null
                        ? client.getLocalPlayer().getWorldLocation() : null;
                    return loc != null ? String.valueOf(loc.getRegionID()) : "?";
                case MANUAL:
                    return "awaiting click";
                default:
                    return "?";
            }
        }
        catch (Exception e)
        {
            return "err:" + e.getMessage();
        }
    }

    /**
     * Formats a human-readable expected-value string for the debug panel.
     */
    public String expectedValue(CompletionCondition c)
    {
        if (c == null || c.type == null) return "?";
        switch (c.type)
        {
            case QUEST:     return c.state != null ? c.state : "FINISHED";
            case VARBIT:    return (c.op != null ? c.op : "EQ") + " " + c.value;
            case SKILL:     return ">= " + c.level;
            case ITEM_HELD: return ">= " + c.qty;
            case REGION:    return String.valueOf(c.regionId);
            case MANUAL:    return "click Done";
            default:        return "?";
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean evalQuest(CompletionCondition c)
    {
        try
        {
            Quest q = Quest.valueOf(c.questName);
            QuestState qs = q.getState(client);
            if (qs == null) return false;
            String target = c.state != null ? c.state.toUpperCase() : "FINISHED";
            return qs.name().equalsIgnoreCase(target);
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }
    }

    private boolean evalVarbit(CompletionCondition c)
    {
        int live = client.getVarbitValue(c.varbitId);
        String op = c.op != null ? c.op.toUpperCase() : "EQ";
        switch (op)
        {
            case "EQ":  return live == c.value;
            case "NEQ": return live != c.value;
            case "GTE": return live >= c.value;
            case "LTE": return live <= c.value;
            case "GT":  return live >  c.value;
            case "LT":  return live <  c.value;
            default:    return live == c.value;
        }
    }

    private boolean evalSkill(CompletionCondition c)
    {
        try
        {
            Skill sk = Skill.valueOf(c.skill);
            return client.getRealSkillLevel(sk) >= c.level;
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }
    }

    private boolean evalItemHeld(CompletionCondition c)
    {
        return countInventory(c.itemId) >= c.qty;
    }

    private boolean evalRegion(CompletionCondition c)
    {
        if (client.getLocalPlayer() == null) return false;
        return client.getLocalPlayer().getWorldLocation().getRegionID() == c.regionId;
    }

    private int countInventory(int itemId)
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return 0;
        int count = 0;
        for (Item item : inv.getItems())
        {
            if (item != null && item.getId() == itemId)
            {
                count += item.getQuantity();
            }
        }
        return count;
    }
}
