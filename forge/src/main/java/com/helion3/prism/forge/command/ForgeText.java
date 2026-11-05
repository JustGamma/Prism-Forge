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

import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import org.spongepowered.api.text.LiteralText;
import org.spongepowered.api.text.ScoreText;
import org.spongepowered.api.text.SelectorText;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.TranslatableText;
import org.spongepowered.api.text.action.ClickAction;
import org.spongepowered.api.text.action.HoverAction;
import org.spongepowered.api.text.format.TextStyle;

import java.util.Locale;

/** Converts the original API Text tree to Forge's native chat tree. */
public final class ForgeText {
    private ForgeText() {
    }

    public static String plain(Text text) {
        StringBuilder content = new StringBuilder(single(text, Locale.getDefault()));
        for (Text child : text.getChildren()) {
            content.append(plain(child));
        }
        return content.toString();
    }

    static String single(Text text, Locale locale) {
        if (text instanceof LiteralText) {
            return ((LiteralText) text).getContent();
        }
        if (text instanceof TranslatableText) {
            TranslatableText translated = (TranslatableText) text;
            Object[] arguments = translated.getArguments().stream()
                    .map(argument -> argument instanceof Text ? plain((Text) argument) : argument).toArray();
            return translated.getTranslation().get(locale, arguments);
        }
        if (text instanceof ScoreText) {
            ScoreText score = (ScoreText) text;
            return score.getOverride().orElseGet(() -> Integer.toString(score.getScore().getScore()));
        }
        if (text instanceof SelectorText) {
            return ((SelectorText) text).getSelector().toPlain();
        }
        throw new UnsupportedOperationException("Unsupported API Text type: " + text.getClass().getName());
    }

    public static IChatComponent render(Text text, ForgeCommandSource recipient) {
        IChatComponent nativeText = new ChatComponentText(single(text, recipient.getLocale()));
        ChatStyle style = new ChatStyle();
        EnumChatFormatting color = ForgeTextColors.formatting(text.getColor());
        if (color == EnumChatFormatting.RESET) {
            // Native RESET is a formatting code, not a color for ChatStyle's serializer.
            style.setColor(EnumChatFormatting.WHITE);
        } else if (color != null) {
            style.setColor(color);
        }
        TextStyle apiStyle = text.getStyle();
        apiStyle.isBold().ifPresent(style::setBold);
        apiStyle.isItalic().ifPresent(style::setItalic);
        apiStyle.hasUnderline().ifPresent(style::setUnderlined);
        apiStyle.hasStrikethrough().ifPresent(style::setStrikethrough);
        apiStyle.isObfuscated().ifPresent(style::setObfuscated);

        text.getClickAction().ifPresent(action -> applyClick(style, action, recipient));
        text.getHoverAction().ifPresent(action -> {
            if (action instanceof HoverAction.ShowText) {
                style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        render(((HoverAction.ShowText) action).getResult(), recipient)));
            }
        });
        nativeText.setChatStyle(style);
        for (Text child : text.getChildren()) {
            nativeText.appendSibling(render(child, recipient));
        }
        return nativeText;
    }

    private static void applyClick(ChatStyle style, ClickAction<?> action, ForgeCommandSource recipient) {
        if (action instanceof ClickAction.OpenUrl) {
            style.setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, action.getResult().toString()));
        } else if (action instanceof ClickAction.RunCommand) {
            style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, (String) action.getResult()));
        } else if (action instanceof ClickAction.SuggestCommand) {
            style.setChatClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, (String) action.getResult()));
        } else if (action instanceof ClickAction.ExecuteCallback) {
            String token = ForgeChatActions.register(recipient, ((ClickAction.ExecuteCallback) action).getResult());
            style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/pr __callback " + token));
        }
    }
}
