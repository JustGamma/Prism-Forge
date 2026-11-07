/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015 Helion3 http://helion3.com/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.helion3.prism.forge.listeners;

import com.helion3.prism.Prism;
import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.api.query.ConditionGroup;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.forge.command.ForgeCommandSource;
import com.helion3.prism.forge.command.ForgeTextColors;
import com.helion3.prism.forge.world.ForgeLocation;
import com.helion3.prism.util.AsyncUtil;
import com.helion3.prism.util.Format;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.entity.player.EntityInteractEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import org.spongepowered.api.text.Text;

/** Native event boundary for the original inspection and wand cancellation rules. */
public final class ForgeRequiredInteractListener {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBreakWhileInspecting(BlockEvent.BreakEvent event) {
        if (!event.world.isRemote && Prism.getInstance().getActiveWands().contains(event.getPlayer().getUniqueID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlaceWhileInspecting(BlockEvent.PlaceEvent event) {
        if (!event.world.isRemote && Prism.getInstance().getActiveWands().contains(event.player.getUniqueID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.world.isRemote || !(event.entityPlayer instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.entityPlayer;
        if (!Prism.getInstance().getActiveWands().contains(player.getUniqueID())) {
            return;
        }

        event.setCanceled(true);
        if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_AIR) {
            return;
        }

        int x = event.x;
        int y = event.y;
        int z = event.z;
        if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            ForgeDirection side = ForgeDirection.getOrientation(event.face);
            x += side.offsetX;
            y += side.offsetY;
            z += side.offsetZ;
        }

        ForgeCommandSource source = ForgeCommandSource.wrap(player).withPermission("prism.inspect");
        ForgeLocation location = ForgeLocation.of(event.world, x, y, z);
        QuerySession session = new QuerySession(source);
        session.addFlag(Flag.NO_GROUP);
        session.newQuery().addCondition(ConditionGroup.from(location));

        Object blockId = Block.blockRegistry.getNameForObject(event.world.getBlock(x, y, z));
        source.sendMessage(Text.of(Format.prefix(), ForgeTextColors.GOLD,
                "--- Inspecting ", Format.item(String.valueOf(blockId), true),
                " at ", x, " ", y, " ", z, " ---"));
        AsyncUtil.lookup(session);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteractEntity(EntityInteractEvent event) {
        if (event.entityPlayer.worldObj.isRemote || !(event.entityPlayer instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.entityPlayer;
        if (!Prism.getInstance().getActiveWands().contains(player.getUniqueID())) {
            return;
        }
        event.setCanceled(true);
        ForgeCommandSource.wrap(player).sendMessage(
                Format.error(Text.of("Cannot interact with entities while inspection is active!")));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttackEntity(AttackEntityEvent event) {
        if (event.entityPlayer.worldObj.isRemote || !(event.entityPlayer instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.entityPlayer;
        if (!Prism.getInstance().getActiveWands().contains(player.getUniqueID())) {
            return;
        }
        event.setCanceled(true);
        ForgeCommandSource.wrap(player).sendMessage(
                Format.error(Text.of("Cannot interact with entities while inspection is active!")));
    }
}
