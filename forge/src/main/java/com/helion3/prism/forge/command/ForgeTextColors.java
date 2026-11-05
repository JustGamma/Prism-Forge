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

import net.minecraft.util.EnumChatFormatting;
import org.spongepowered.api.text.format.TextColor;
import org.spongepowered.api.util.Color;

import java.util.Locale;

/** Concrete API text values, without Sponge's registry-backed dummy catalog values. */
public final class ForgeTextColors {
    public static final TextColor BLACK = color(EnumChatFormatting.BLACK, 0x000000);
    public static final TextColor DARK_BLUE = color(EnumChatFormatting.DARK_BLUE, 0x0000aa);
    public static final TextColor DARK_GREEN = color(EnumChatFormatting.DARK_GREEN, 0x00aa00);
    public static final TextColor DARK_AQUA = color(EnumChatFormatting.DARK_AQUA, 0x00aaaa);
    public static final TextColor DARK_RED = color(EnumChatFormatting.DARK_RED, 0xaa0000);
    public static final TextColor DARK_PURPLE = color(EnumChatFormatting.DARK_PURPLE, 0xaa00aa);
    public static final TextColor GOLD = color(EnumChatFormatting.GOLD, 0xffaa00);
    public static final TextColor GRAY = color(EnumChatFormatting.GRAY, 0xaaaaaa);
    public static final TextColor DARK_GRAY = color(EnumChatFormatting.DARK_GRAY, 0x555555);
    public static final TextColor BLUE = color(EnumChatFormatting.BLUE, 0x5555ff);
    public static final TextColor GREEN = color(EnumChatFormatting.GREEN, 0x55ff55);
    public static final TextColor AQUA = color(EnumChatFormatting.AQUA, 0x55ffff);
    public static final TextColor RED = color(EnumChatFormatting.RED, 0xff5555);
    public static final TextColor LIGHT_PURPLE = color(EnumChatFormatting.LIGHT_PURPLE, 0xff55ff);
    public static final TextColor YELLOW = color(EnumChatFormatting.YELLOW, 0xffff55);
    public static final TextColor WHITE = color(EnumChatFormatting.WHITE, 0xffffff);
    public static final TextColor RESET = color(EnumChatFormatting.RESET, 0xffffff);

    private ForgeTextColors() {
    }

    private static TextColor color(EnumChatFormatting formatting, int rgb) {
        return new NativeColor(formatting, rgb);
    }

    static EnumChatFormatting formatting(TextColor color) {
        if (color instanceof NativeColor) {
            return ((NativeColor) color).formatting;
        }
        if (color.getClass().getName().startsWith("org.spongepowered.api.util.dummy.")) {
            // API argument errors carry unbound catalog colors. The native root
            // wraps these errors in Prism's concrete red format, inherited here.
            return null;
        }
        if ("NONE".equals(color.getId())) {
            return null;
        }
        String id = color.getId();
        int separator = id.indexOf(':');
        return EnumChatFormatting.valueOf(id.substring(separator + 1).toUpperCase(Locale.ROOT));
    }

    private static final class NativeColor implements TextColor {
        private final EnumChatFormatting formatting;
        private final Color color;

        private NativeColor(EnumChatFormatting formatting, int rgb) {
            this.formatting = formatting;
            this.color = Color.ofRgb(rgb);
        }

        @Override
        public String getId() {
            return "minecraft:" + this.formatting.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public String getName() {
            return this.formatting.name();
        }

        @Override
        public Color getColor() {
            return this.color;
        }

        @Override
        public String toString() {
            return getId();
        }
    }
}
