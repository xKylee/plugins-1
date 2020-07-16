/*
 * Copyright (c) 2019, Owain van Brakel <https://github.com/Owain94>
 * Copyright (c) 2019, TheStonedTurtle <https://github.com/TheStonedTurtle>
 * Copyright (c) 2018, Cameron <https://github.com/noremac201>, SoyChai <https://github.com/SoyChai>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.experiencedrop;

import com.google.inject.Provides;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.IntStream;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EnumDefinition;
import net.runelite.api.EnumID;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import static net.runelite.api.ScriptID.*;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.api.VarClientInt;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetHiddenChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import static net.runelite.api.widgets.WidgetInfo.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.NPCManager;
import net.runelite.client.game.XpDropEvent;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "XP Drop",
	description = "Enable customization of the way XP drops are displayed",
	tags = {"experience", "levels", "tick", "prayer", "xpdrop"},
	type = PluginType.UTILITY
)
public class XpDropPlugin extends Plugin
{
	private static final int XPDROP_PADDING = 2; // space between xp drop icons
	private static final double HITPOINT_RATIO = 1.33; // Base rate of hp xp per point damage
	private static double DMM_MULTIPLIER_RATIO;

	@Inject
	private Client client;

	@Inject
	private XpDropConfig config;

	@Inject
	private NPCManager npcManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private XpDropOverlay overlay;

	@Getter(AccessLevel.PACKAGE)
	private int damage = 0;

	@Getter(AccessLevel.PACKAGE)
	private int tickShow = 0;

	@Getter(AccessLevel.PACKAGE)
	private Actor lastOpponent;

	private int tickCounter = 0;
	private int previousExpGained;
	private boolean hasDropped = false;
	private boolean correctPrayer;
	private Skill lastSkill = null;
	private PrayerType currentTickPrayer;
	private XpDropConfig.DamageMode damageMode;
	private Map<Skill, Integer> previousSkillExpTable = new EnumMap<>(Skill.class);

	@Provides
	XpDropConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(XpDropConfig.class);
	}

	@Override
	protected void startUp()
	{
		damageMode = config.showdamagedrops();

		if (damageMode == XpDropConfig.DamageMode.ABOVE_OPPONENT)
		{
			overlayManager.add(overlay);
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
	}

	@Subscribe
	private void onXpDropEvent(XpDropEvent event)
	{
		previousExpGained = event.getExp();
		lastSkill = event.getSkill();
		hasDropped = true;
	}

	@Subscribe
	private void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals("xpdrop"))
		{
			return;
		}

		if (damageMode != XpDropConfig.DamageMode.ABOVE_OPPONENT)
		{
			damageMode = config.showdamagedrops();

			if (damageMode == XpDropConfig.DamageMode.ABOVE_OPPONENT)
			{
				overlayManager.add(overlay);
			}
		}
		else
		{
			damageMode = config.showdamagedrops();

			if (damageMode != XpDropConfig.DamageMode.ABOVE_OPPONENT)
			{
				overlayManager.remove(overlay);
			}
		}
	}

	@Subscribe
	private void onGameStateChanged(GameStateChanged event)
	{
		tickShow = 0;
		damage = 0;
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired scriptPreFired)
	{
		if (scriptPreFired.getScriptId() == XPDROPS_SETDROPSIZE)
		{
			final int[] intStack = client.getIntStack();
			final int intStackSize = client.getIntStackSize();
			// This runs prior to the proc being invoked, so the arguments are still on the stack.
			// Grab the first argument to the script.
			final int widgetId = intStack[intStackSize - 4];
			processXpDrop(widgetId);
		}
	}

	private void processXpDrop(int widgetId)
	{
		final Widget xpdrop = client.getWidget(TO_GROUP(widgetId), TO_CHILD(widgetId));
		final Widget[] children = xpdrop.getChildren();
		// child 0 is the xpdrop text, everything else are sprite ids for skills
		final Widget text = children[0];
		if (config.hideSkillIcons())
		{
			// keep only text
			Arrays.fill(children, 1, children.length, null);
		}

		PrayerType prayer = getActivePrayerType();
		if (prayer == null)
		{
			resetTextColor(text);
			return;
		}

		final IntStream spriteIDs =
			Arrays.stream(children)
				.skip(1)
				.mapToInt(Widget::getSpriteId);

		int color = 0;

		switch (prayer)
		{
			case MELEE:
				if (correctPrayer || spriteIDs.anyMatch(id ->
					id == SpriteID.SKILL_ATTACK || id == SpriteID.SKILL_STRENGTH || id == SpriteID.SKILL_DEFENCE))
				{
					color = config.getMeleePrayerColor().getRGB();
					correctPrayer = true;
				}
				break;
			case RANGE:
				if (correctPrayer || spriteIDs.anyMatch(id -> id == SpriteID.SKILL_RANGED))
				{
					color = config.getRangePrayerColor().getRGB();
					correctPrayer = true;
				}
				break;
			case MAGIC:
				if (correctPrayer || spriteIDs.anyMatch(id -> id == SpriteID.SKILL_MAGIC))
				{
					color = config.getMagePrayerColor().getRGB();
					correctPrayer = true;
				}
				break;
		}

		if (color != 0)
		{
			text.setTextColor(color);
		}
		else
		{
			resetTextColor(text);
		}
	}

	private void resetTextColor(Widget widget)
	{
		EnumDefinition colorEnum = client.getEnum(EnumID.XPDROP_COLORS);
		int defaultColorId = client.getVar(Varbits.EXPERIENCE_DROP_COLOR);
		int color = colorEnum.getIntValue(defaultColorId);
		widget.setTextColor(color);
	}

	private PrayerType getActivePrayerType()
	{
		for (XpPrayer prayer : XpPrayer.values())
		{
			if (client.isPrayerActive(prayer.getPrayer()))
			{
				return prayer.getType();
			}
		}
		return null;
	}

	@Subscribe
	private void onGameTick(GameTick tick)
	{
		lastOpponent = client.getLocalPlayer().getInteracting();

		if (tickShow > 0)
		{
			tickShow--;
		}

		correctPrayer = false;

		final int fakeTickDelay = config.fakeXpDropDelay();

		if (fakeTickDelay == 0 || lastSkill == null)
		{
			return;
		}

		// If an xp drop was created this tick, reset the counter
		if (hasDropped)
		{
			hasDropped = false;
			tickCounter = 0;
			return;
		}

		if (++tickCounter % fakeTickDelay != 0)
		{
			return;
		}

		client.runScript(XPDROP_DISABLED, lastSkill.ordinal(), previousExpGained);
	}

	@Subscribe
	private void onFakeXpDrop(FakeXpDrop fakeXpDrop)
	{
		if (fakeXpDrop.getSkill() == Skill.HITPOINTS)
		{
			calculateDamageDealt(fakeXpDrop.getXp());
		}
	}

	@Subscribe
	private void onScriptCallbackEvent(ScriptCallbackEvent e)
	{
		if (config.showdamagedrops() == XpDropConfig.DamageMode.NONE)
		{
			return;
		}

		final String eventName = e.getEventName();

		if (eventName.equals("newXpDrop"))
		{
			damage = 0;
		}
		else if (eventName.equals("hpXpGained"))
		{
			final int[] intStack = client.getIntStack();
			final int intStackSize = client.getIntStackSize();

			final int exp = intStack[intStackSize - 1];
			calculateDamageDealt(exp);
		}
		else if (eventName.equals("xpDropAddDamage")
			&& damageMode == XpDropConfig.DamageMode.IN_XP_DROP
			&& damage > 0)
		{
			final String[] stringStack = client.getStringStack();
			final int stringStackSize = client.getStringStackSize();

			String builder =
				stringStack[stringStackSize - 1]
					+ ColorUtil.colorTag(config.getDamageColor())
					+ " ("
					+ damage
					+ ")";
			stringStack[stringStackSize - 1] = builder;
		}
	}

	private void calculateDamageDealt(int diff)
	{
		double damageDealt = diff / HITPOINT_RATIO;

		// DeadMan mode Tournament has an XP modifier of 10x in safezone, 15x outside.
		if (client.getWorldType().contains(WorldType.DEADMAN_TOURNAMENT))
		{
			if (client.getVar(VarClientInt.DMM_SAFEZONE) == 0)
			{
				DMM_MULTIPLIER_RATIO = 15;
			}
			if (client.getVar(VarClientInt.DMM_SAFEZONE) == 1)
			{
				DMM_MULTIPLIER_RATIO = 10;
			}
			damageDealt = damageDealt / DMM_MULTIPLIER_RATIO;
		}
		// DeadMan mode W45 has an XP modifier of 10x
		else if (!client.getWorldType().contains(WorldType.DEADMAN_TOURNAMENT)
			&& client.getWorldType().contains(WorldType.DEADMAN))
		{
			DMM_MULTIPLIER_RATIO = 10;
			damageDealt = damageDealt / DMM_MULTIPLIER_RATIO;
		}

		// Some NPCs have an XP modifier, account for it here.
		Actor a = client.getLocalPlayer().getInteracting();
		if (!(a instanceof NPC) && !(a instanceof Player))
		{
			// If we are interacting with nothing we may have clicked away at the perfect time fall back
			// to last tick
			if (!(lastOpponent instanceof NPC) && !(lastOpponent instanceof Player))
			{
				damage = (int) Math.rint(damageDealt);
				tickShow = 3;
				return;
			}

			a = lastOpponent;
		}

		if (a instanceof Player)
		{
			damage = (int) Math.rint(damageDealt);
			tickShow = 3;
			return;
		}

		NPC target = (NPC) a;
		damage = (int) Math.rint(damageDealt / npcManager.getXpModifier(target.getId()));
		tickShow = 3;
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		final Skill skill = statChanged.getSkill();
		final int xp = statChanged.getXp();

		lastSkill = skill;

		Integer previous = previousSkillExpTable.put(skill, xp);
		if (previous != null)
		{
			previousExpGained = xp - previous;
			hasDropped = true;
		}
	}
}
