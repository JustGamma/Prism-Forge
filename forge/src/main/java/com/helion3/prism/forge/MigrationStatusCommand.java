/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 * See LICENSE.txt for the full license.
 */
package com.helion3.prism.forge;

import com.helion3.prism.Prism;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

/**
 * Confirms that Forge registered Prism's server command, without touching world or database state.
 */
public final class MigrationStatusCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "prism-migration";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/prism-migration";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] arguments) {
        sender.addChatMessage(new ChatComponentText("Prism Forge migration test " + BuildInfo.VERSION
                + "; Java " + System.getProperty("java.version")
                + ". Storage " + (Prism.getInstance().isStorageReady() ? "ready" : "unavailable")
                + ". Gameplay recording, lookup commands and rollback are not enabled."));
    }
}
