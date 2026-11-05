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
package com.helion3.prism.forge.command;

import com.helion3.prism.commands.PrismCommands;
import com.helion3.prism.util.Format;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.args.ArgumentParseException;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.text.Text;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Forge registration around Prism's original command hierarchy and argument parser. */
public final class ForgeRootCommand extends CommandBase {
    private final CommandSpec command;

    public ForgeRootCommand() {
        ForgeTextBootstrap.initialize();
        this.command = PrismCommands.getCommand();
    }

    @Override
    public String getCommandName() {
        return "prism";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("pr");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/prism " + ForgeText.plain(this.command.getUsage(ForgeCommandSource.wrap(sender)));
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        // Individual retained child CommandSpecs enforce their original prism.* nodes.
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] arguments) {
        ForgeCommandSource source = ForgeCommandSource.wrap(sender);
        if (arguments.length > 0 && "__callback".equals(arguments[0])) {
            if (arguments.length == 2) {
                ForgeChatActions.execute(source, arguments[1]);
            } else {
                source.sendMessage(Format.error("Invalid Prism chat action."));
            }
            return;
        }
        if (arguments.length > 0) {
            String child = arguments[0].toLowerCase(Locale.ROOT);
            if ("l".equals(child) || "lookup".equals(child) || "near".equals(child)) {
                source = source.withPermission("prism.lookup");
            }
        }
        try {
            this.command.process(source, String.join(" ", arguments));
        } catch (CommandException ex) {
            Text message = ex.getText();
            source.sendMessage(Format.error(message == null ? Text.of("Prism command failed.") : message));
            if (ex.shouldIncludeUsage()) {
                Text usage = ex instanceof ArgumentParseException.WithUsage
                        ? ((ArgumentParseException.WithUsage) ex).getUsage() : this.command.getUsage(source);
                source.sendMessage(Format.error(Text.of("Usage: /prism ", usage)));
            }
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] arguments) {
        try {
            return this.command.getSuggestions(ForgeCommandSource.wrap(sender), String.join(" ", arguments), null);
        } catch (CommandException ex) {
            return Collections.emptyList();
        }
    }
}
