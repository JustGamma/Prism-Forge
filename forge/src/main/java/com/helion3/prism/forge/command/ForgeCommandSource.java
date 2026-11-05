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

import com.helion3.prism.forge.ForgeServerTasks;
import com.helion3.prism.forge.world.ForgeLocation;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.service.context.Context;
import org.spongepowered.api.service.permission.SubjectCollection;
import org.spongepowered.api.service.permission.SubjectData;
import org.spongepowered.api.service.permission.SubjectReference;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageChannel;
import org.spongepowered.api.util.Tristate;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** The narrow command/message/permission portion of CommandSource used by Prism. */
public final class ForgeCommandSource implements CommandSource {
    private final ICommandSender sender;
    private final String actionPermission;
    private MessageChannel channel;

    private ForgeCommandSource(ICommandSender sender, String actionPermission) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.actionPermission = actionPermission;
        this.channel = MessageChannel.fixed(this);
    }

    public static ForgeCommandSource wrap(ICommandSender sender) {
        return new ForgeCommandSource(sender, null);
    }

    /** Retains the permission of the originating lookup or inspection for later click actions. */
    public ForgeCommandSource withPermission(String permission) {
        return new ForgeCommandSource(this.sender, Objects.requireNonNull(permission, "permission"));
    }

    String actionPermission() {
        return this.actionPermission;
    }

    boolean sameRecipient(ForgeCommandSource other) {
        Optional<EntityPlayerMP> player = player(this);
        Optional<EntityPlayerMP> otherPlayer = player(other);
        return player.isPresent() && otherPlayer.isPresent()
                ? player.get().getUniqueID().equals(otherPlayer.get().getUniqueID())
                : this.sender == other.sender;
    }

    public static ICommandSender sender(CommandSource source) {
        if (!(source instanceof ForgeCommandSource)) {
            throw new IllegalArgumentException("Expected a native Forge Prism command source");
        }
        return ((ForgeCommandSource) source).sender;
    }

    public static Optional<EntityPlayerMP> player(CommandSource source) {
        if (!(source instanceof ForgeCommandSource)) {
            return Optional.empty();
        }
        ICommandSender sender = ((ForgeCommandSource) source).sender;
        return sender instanceof EntityPlayerMP ? Optional.of((EntityPlayerMP) sender) : Optional.empty();
    }

    public static Optional<ForgeLocation> location(CommandSource source) {
        if (!(source instanceof ForgeCommandSource)) {
            return Optional.empty();
        }
        Optional<EntityPlayerMP> player = player(source);
        if (player.isPresent()) {
            EntityPlayerMP nativePlayer = player.get();
            return Optional.of(ForgeLocation.of(nativePlayer.worldObj,
                    MathHelper.floor_double(nativePlayer.posX), MathHelper.floor_double(nativePlayer.posY),
                    MathHelper.floor_double(nativePlayer.posZ)));
        }
        ICommandSender sender = sender(source);
        if (sender.getEntityWorld() == null) {
            return Optional.empty();
        }
        ChunkCoordinates position = sender.getPlayerCoordinates();
        return position == null ? Optional.empty()
                : Optional.of(ForgeLocation.of(sender.getEntityWorld(), position.posX, position.posY, position.posZ));
    }

    @Override
    public String getName() {
        return this.sender.getCommandSenderName();
    }

    @Override
    public Locale getLocale() {
        return Locale.getDefault();
    }

    @Override
    public String getIdentifier() {
        return player(this).map(player -> player.getUniqueID().toString()).orElseGet(this::getName);
    }

    @Override
    public Optional<CommandSource> getCommandSource() {
        return Optional.of(this);
    }

    @Override
    public void sendMessage(Text message) {
        Objects.requireNonNull(message, "message");
        ForgeServerTasks.run(() -> this.sender.addChatMessage(ForgeText.render(message, this)));
    }

    @Override
    public MessageChannel getMessageChannel() {
        return this.channel;
    }

    @Override
    public void setMessageChannel(MessageChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    @Override
    public boolean hasPermission(String permission) {
        return ForgePermissions.hasPermission(this.sender, permission);
    }

    @Override
    public Tristate getPermissionValue(Set<Context> contexts, String permission) {
        if (!contexts.isEmpty()) {
            throw unsupported();
        }
        return hasPermission(permission) ? Tristate.TRUE : Tristate.FALSE;
    }

    @Override
    public Set<Context> getActiveContexts() {
        return Collections.emptySet();
    }

    @Override
    public boolean isSubjectDataPersisted() {
        return false;
    }

    @Override
    public SubjectCollection getContainingCollection() {
        throw unsupported();
    }

    @Override
    public SubjectReference asSubjectReference() {
        throw unsupported();
    }

    @Override
    public SubjectData getSubjectData() {
        throw unsupported();
    }

    @Override
    public SubjectData getTransientSubjectData() {
        throw unsupported();
    }

    @Override
    public boolean isChildOf(Set<Context> contexts, SubjectReference parent) {
        throw unsupported();
    }

    @Override
    public List<SubjectReference> getParents(Set<Context> contexts) {
        throw unsupported();
    }

    @Override
    public Optional<String> getOption(Set<Context> contexts, String key) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Native Prism delegates permission decisions to its provider; "
                + "Sponge permission storage, group management and contextual introspection are unavailable");
    }
}
