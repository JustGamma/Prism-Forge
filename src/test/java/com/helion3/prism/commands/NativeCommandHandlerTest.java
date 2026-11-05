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


package com.helion3.prism.commands;

import com.helion3.prism.api.query.Sort;
import com.helion3.prism.forge.command.ForgeCommandSource;
import com.helion3.prism.forge.command.ForgeTextBootstrap;
import net.minecraft.command.ICommandSender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandPermissionException;
import org.spongepowered.api.command.spec.CommandSpec;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCommandHandlerTest {
    @BeforeAll
    static void initializeRetainedTextApi() {
        ForgeTextBootstrap.initialize();
    }

    @Test
    void originalHierarchyKeepsEveryAliasAndNamedPermission() throws Exception {
        CommandSpec command = PrismCommands.getCommand();
        Set<String> permissionChecks = new HashSet<>();
        ForgeCommandSource allowed = console(true, permissionChecks);
        assertEquals(Set.of("i", "wand", "l", "lookup", "near", "rb", "rollback",
                "rs", "restore", "undo", "ext", "?", "help"),
                new HashSet<>(command.getSuggestions(allowed, "", null)));
        assertEquals(Set.of("prism.inspect", "prism.lookup", "prism.rollback", "prism.undo",
                "prism.extinguish", "prism.help"), permissionChecks);
        assertTrue(command.getSuggestions(console(false, new HashSet<>()), "", null).isEmpty());
    }

    @Test
    void consoleCannotUsePlayerOnlyCommandsWithoutASpongeGame() {
        assertThrows(IllegalStateException.class, Sponge::getGame);
        ForgeCommandSource source = console(true, new HashSet<>());
        assertPlayerRequired(InspectCommand.getCommand(), source);
        assertPlayerRequired(NearCommand.getCommand(), source);
    }

    @Test
    void worldWritingCommandsReportTheMissingNativeBackendBeforeAnySpongeCall() {
        ForgeCommandSource source = console(true, new HashSet<>());
        assertUnavailable(ApplierCommand.getCommand(Sort.NEWEST_FIRST), source, "a:block-break");
        assertUnavailable(ApplierCommand.getCommand(Sort.OLDEST_FIRST), source, "a:block-break");
        assertUnavailable(UndoCommand.getCommand(), source, "");
        assertUnavailable(ExtinguishCommand.getCommand(), source, "5");
        assertThrows(IllegalStateException.class, Sponge::getGame);
    }

    @Test
    void originalPermissionCheckPrecedesThePlayerOnlyHandler() {
        ForgeCommandSource source = console(false, new HashSet<>());
        assertThrows(CommandPermissionException.class, () -> InspectCommand.getCommand().process(source, ""));
    }

    private static void assertPlayerRequired(CommandSpec command, ForgeCommandSource source) {
        CommandException failure = assertThrows(CommandException.class, () -> command.process(source, ""));
        assertTrue(failure.getMessage().contains("You must be a player"));
    }

    private static void assertUnavailable(CommandSpec command, ForgeCommandSource source, String arguments) {
        CommandException failure = assertThrows(CommandException.class, () -> command.process(source, arguments));
        assertTrue(failure.getMessage().contains("not available in this migration build yet"));
    }

    private static ForgeCommandSource console(boolean permitted, Set<String> permissionChecks) {
        ICommandSender sender = (ICommandSender) Proxy.newProxyInstance(ICommandSender.class.getClassLoader(),
                new Class<?>[]{ICommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("canCommandSenderUseCommand")) {
                        assertEquals(4, args[0]);
                        permissionChecks.add((String) args[1]);
                        return permitted;
                    }
                    if (method.getName().equals("getCommandSenderName")) {
                        return "native-console-test";
                    }
                    if (method.getName().equals("getEntityWorld") || method.getName().equals("getPlayerCoordinates")) {
                        return null;
                    }
                    throw new AssertionError("Unexpected native server call: " + method);
                });
        return ForgeCommandSource.wrap(sender);
    }
}
