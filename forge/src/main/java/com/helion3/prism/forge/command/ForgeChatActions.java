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
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Native delivery for retained lookup pagination and executeCallback text actions. */
public final class ForgeChatActions {
    private static final int LINES_PER_PAGE = 15;
    private static final int MAX_ACTIONS = 2048;
    private static final long LIFETIME_MILLIS = 15 * 60 * 1000;
    private static final Map<String, Action> ACTIONS = new LinkedHashMap<>();

    private ForgeChatActions() {
    }

    static synchronized String register(ForgeCommandSource recipient, Consumer<CommandSource> callback) {
        String permission = recipient.actionPermission();
        if (permission == null) {
            throw new IllegalStateException("An interactive Prism result must retain its originating permission");
        }
        prune();
        while (ACTIONS.size() >= MAX_ACTIONS) {
            Iterator<String> oldest = ACTIONS.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        String token = UUID.randomUUID().toString();
        ACTIONS.put(token, new Action(recipient, permission, callback));
        return token;
    }

    static void execute(ForgeCommandSource source, String token) {
        execute(source, token, System.currentTimeMillis());
    }

    static void execute(ForgeCommandSource source, String token, long now) {
        Action action;
        synchronized (ForgeChatActions.class) {
            prune(now);
            action = ACTIONS.get(token);
        }
        if (action == null || !action.recipient.sameRecipient(source)) {
            source.sendMessage(Format.error("This Prism chat action expired or belongs to another command source."));
        } else if (!source.hasPermission(action.permission)) {
            source.sendMessage(Format.error("You no longer have permission to use this Prism chat action."));
        } else {
            action.callback.accept(source.withPermission(action.permission));
        }
    }

    /** Drops saved chat callbacks when the server stops. */
    public static synchronized void clear() {
        ACTIONS.clear();
    }

    /** Preserves the original lookup's fifteen result entries per page. */
    public static void sendPages(CommandSource source, List<Text> contents) {
        if (!(source instanceof ForgeCommandSource)) {
            throw new IllegalArgumentException("Native Prism pagination requires a Forge command source");
        }
        showPage((ForgeCommandSource) source, new ArrayList<>(contents), 0);
    }

    private static void showPage(ForgeCommandSource source, List<Text> contents, int page) {
        int pages = Math.max(1, (contents.size() + LINES_PER_PAGE - 1) / LINES_PER_PAGE);
        source.sendMessage(Text.of(ForgeTextColors.DARK_GRAY, "===== ", ForgeTextColors.WHITE,
                "Prism ", page + 1, "/", pages, ForgeTextColors.DARK_GRAY, " ====="));
        int end = Math.min(contents.size(), (page + 1) * LINES_PER_PAGE);
        for (int index = page * LINES_PER_PAGE; index < end; index++) {
            source.sendMessage(contents.get(index));
        }
        if (pages > 1) {
            Text.Builder navigation = Text.builder();
            if (page > 0) {
                navigation.append(Text.builder("[Previous]").color(ForgeTextColors.AQUA)
                        .onClick(TextActions.executeCallback(next -> showPage((ForgeCommandSource) next, contents, page - 1))).build());
            }
            if (page + 1 < pages) {
                navigation.append(Text.builder(" [Next]").color(ForgeTextColors.AQUA)
                        .onClick(TextActions.executeCallback(next -> showPage((ForgeCommandSource) next, contents, page + 1))).build());
            }
            source.sendMessage(navigation.build());
        }
    }

    private static void prune() {
        prune(System.currentTimeMillis());
    }

    private static void prune(long now) {
        ACTIONS.values().removeIf(action -> action.expires <= now);
    }

    private static final class Action {
        private final ForgeCommandSource recipient;
        private final String permission;
        private final Consumer<CommandSource> callback;
        private final long expires = System.currentTimeMillis() + LIFETIME_MILLIS;

        private Action(ForgeCommandSource recipient, String permission, Consumer<CommandSource> callback) {
            this.recipient = recipient;
            this.permission = permission;
            this.callback = callback;
        }
    }
}
