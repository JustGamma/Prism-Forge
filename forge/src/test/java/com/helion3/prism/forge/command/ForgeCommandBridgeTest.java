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

import com.helion3.prism.util.Format;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.IChatComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ForgeCommandBridgeTest {
    @BeforeAll
    static void initializeTextHost() {
        ForgeTextBootstrap.initialize();
    }

    @Test
    void retainedArgumentKeysAndJoinedParameterParserWorkWithoutSpongeGame() throws Exception {
        assertThrows(IllegalStateException.class, Sponge::getGame);
        AtomicReference<String> parsed = new AtomicReference<>();
        CommandSpec command = CommandSpec.builder()
                .arguments(GenericArguments.remainingJoinedStrings(Text.of("parameters")))
                .executor((source, arguments) -> {
                    parsed.set(arguments.<String>getOne("parameters").orElseThrow(AssertionError::new));
                    return CommandResult.success();
                }).build();
        command.process(ForgeCommandSource.wrap(sender("console", true)), "r:5 p:SomePlayer");
        assertEquals("r:5 p:SomePlayer", parsed.get());
        assertEquals("one two", Text.builder("one").append(Text.of(" two")).build().toPlain());
        assertEquals("one", Text.builder("one").append(Text.of(" two")).build().toPlainSingle());
        assertThrows(IllegalStateException.class, Sponge::getGame);
    }

    @Test
    void originalParserAndPermissionErrorsHaveReadableNativeMessages() {
        CommandSpec command = CommandSpec.builder().permission("prism.lookup")
                .arguments(GenericArguments.integer(Text.of("radius")))
                .executor((source, arguments) -> CommandResult.success()).build();
        ForgeCommandSource allowed = ForgeCommandSource.wrap(sender("console", true));
        CommandException invalid = assertThrows(CommandException.class, () -> command.process(allowed, "no-number"));
        assertTrue(invalid.getMessage().contains("no-number"));
        IChatComponent parsedError = ForgeText.render(Format.error(invalid.getText()), allowed);
        assertTrue(parsedError.getUnformattedText().contains("no-number"));
        assertFalse(parsedError.getUnformattedText().contains("UnsupportedOperationException"));

        CommandException denied = assertThrows(CommandException.class,
                () -> command.process(ForgeCommandSource.wrap(sender("guest", false)), "5"));
        assertNotNull(denied.getMessage());
        assertFalse(denied.getMessage().isEmpty());
        assertDoesNotThrow(() -> ForgeText.render(Format.error(denied.getText()), allowed));
    }

    @Test
    void permissionProviderReceivesIndividualNodesAndControlsDecisions() {
        List<String> requested = new ArrayList<>();
        ForgePermissions.Provider provider = (sender, node) -> {
            requested.add(node);
            return node.equals("prism.inspect");
        };
        ForgePermissions.register(provider);
        try {
            ForgeCommandSource source = ForgeCommandSource.wrap(sender("operator", true));
            assertTrue(source.hasPermission("prism.inspect"));
            assertFalse(source.hasPermission("prism.lookup"));
            assertEquals(java.util.Arrays.asList("prism.inspect", "prism.lookup"), requested);
        } finally {
            ForgePermissions.unregister(provider);
        }
        assertTrue(ForgePermissions.hasPermission(sender("operator", true), "prism.rollback"));
        assertFalse(ForgePermissions.hasPermission(sender("guest", false), "prism.rollback"));
    }

    @Test
    void nativeHoverAndCallbackRetainInspectPermissionAndRejectOtherRecipients() {
        AtomicBoolean permission = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        List<String> requested = new ArrayList<>();
        ForgePermissions.Provider provider = (sender, node) -> {
            requested.add(node);
            return permission.get() && node.equals("prism.inspect");
        };
        ForgePermissions.register(provider);
        try {
            ForgeCommandSource owner = ForgeCommandSource.wrap(sender("owner", false)).withPermission("prism.inspect");
            Text text = Text.builder("location").color(ForgeTextColors.AQUA)
                    .onHover(TextActions.showText(Text.of("Details")))
                    .onClick(TextActions.executeCallback(source -> calls.incrementAndGet())).build();
            IChatComponent nativeText = ForgeText.render(text, owner);
            assertEquals(HoverEvent.Action.SHOW_TEXT, nativeText.getChatStyle().getChatHoverEvent().getAction());
            assertEquals("Details", nativeText.getChatStyle().getChatHoverEvent().getValue().getUnformattedText());
            ClickEvent click = nativeText.getChatStyle().getChatClickEvent();
            assertEquals(ClickEvent.Action.RUN_COMMAND, click.getAction());
            String token = click.getValue().substring("/pr __callback ".length());

            ForgeChatActions.execute(ForgeCommandSource.wrap(sender("other", true)), token);
            assertEquals(0, calls.get());
            ForgeChatActions.execute(ForgeCommandSource.wrap(sender("owner", true)), token);
            assertEquals(0, calls.get(), "Different native senders with the same name must not share callbacks");
            permission.set(false);
            ForgeChatActions.execute(owner, token);
            assertEquals(0, calls.get());
            permission.set(true);
            ForgeChatActions.execute(owner, token);
            assertEquals(1, calls.get());
            assertEquals(java.util.Arrays.asList("prism.inspect", "prism.inspect"), requested);
            ForgeChatActions.execute(owner, token, System.currentTimeMillis() + 16 * 60 * 1000);
            assertEquals(1, calls.get(), "Expired callbacks must not execute even with permission");
        } finally {
            ForgeChatActions.clear();
            ForgePermissions.unregister(provider);
        }
    }

    private static ICommandSender sender(String name, boolean operator) {
        return (ICommandSender) Proxy.newProxyInstance(ICommandSender.class.getClassLoader(), new Class<?>[]{ICommandSender.class},
                (proxy, method, arguments) -> {
                    if ("getCommandSenderName".equals(method.getName())) {
                        return name;
                    }
                    if ("canCommandSenderUseCommand".equals(method.getName())) {
                        assertEquals(4, arguments[0]);
                        return operator;
                    }
                    throw new AssertionError("Unexpected native sender call: " + method.getName());
                });
    }
}
