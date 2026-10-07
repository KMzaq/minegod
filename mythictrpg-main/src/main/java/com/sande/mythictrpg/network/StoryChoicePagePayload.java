package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded page of server-authorized, currently eligible Story choices. */
public record StoryChoicePagePayload(int page, int total, boolean open, Optional<Offer> offer)
        implements CustomPacketPayload {
    public static final Type<StoryChoicePagePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "story_choice_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StoryChoicePagePayload> STREAM_CODEC =
            StreamCodec.ofMember(StoryChoicePagePayload::encode, StoryChoicePagePayload::decode);

    public StoryChoicePagePayload {
        offer = Objects.requireNonNull(offer, "offer");
        if (total < 0 || total > 1_000_000 || page < 0 || (total == 0 && page != 0)
                || (total > 0 && page >= total)
                || (total == 0) == offer.isPresent()) {
            throw new IllegalArgumentException("Invalid Story choice page");
        }
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(page);
        buffer.writeVarInt(total);
        buffer.writeBoolean(open);
        buffer.writeBoolean(offer.isPresent());
        offer.ifPresent(value -> {
            buffer.writeUtf(value.instanceId(), 256);
            buffer.writeVarLong(value.revision());
            buffer.writeResourceLocation(value.eventId());
            buffer.writeUtf(value.displayName(), 256);
            buffer.writeUtf(value.description(), 600);
            buffer.writeVarInt(value.options().size());
            for (Option option : value.options()) {
                buffer.writeResourceLocation(option.id());
                buffer.writeUtf(option.displayName(), 256);
                buffer.writeUtf(option.description(), 600);
            }
        });
    }

    private static StoryChoicePagePayload decode(RegistryFriendlyByteBuf buffer) {
        int page = buffer.readVarInt();
        int total = buffer.readVarInt();
        boolean open = buffer.readBoolean();
        if (!buffer.readBoolean()) return new StoryChoicePagePayload(page, total, open, Optional.empty());
        String instanceId = buffer.readUtf(256);
        long revision = buffer.readVarLong();
        ResourceLocation eventId = buffer.readResourceLocation();
        String displayName = buffer.readUtf(256);
        String description = buffer.readUtf(600);
        int count = buffer.readVarInt();
        if (count < 1 || count > 32) throw new IllegalArgumentException("Invalid Story choice option count");
        List<Option> options = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            options.add(new Option(buffer.readResourceLocation(), buffer.readUtf(256), buffer.readUtf(600)));
        }
        return new StoryChoicePagePayload(page, total, open,
                Optional.of(new Offer(instanceId, revision, eventId, displayName, description, options)));
    }

    public record Offer(String instanceId, long revision, ResourceLocation eventId,
            String displayName, String description, List<Option> options) {
        public Offer {
            instanceId = required(instanceId, 256, "instanceId");
            if (revision < 0) throw new IllegalArgumentException("Negative Story choice revision");
            Objects.requireNonNull(eventId, "eventId");
            displayName = required(displayName, 256, "displayName");
            description = optional(description, 600, "description");
            options = List.copyOf(Objects.requireNonNull(options, "options"));
            if (options.isEmpty() || options.size() > 32 || options.stream().map(Option::id).distinct().count() != options.size())
                throw new IllegalArgumentException("Invalid Story choice options");
        }
    }

    public record Option(ResourceLocation id, String displayName, String description) {
        public Option {
            Objects.requireNonNull(id, "id");
            displayName = required(displayName, 256, "displayName");
            description = optional(description, 600, "description");
        }
    }

    private static String required(String value, int limit, String field) {
        value = optional(value, limit, field);
        if (value.isEmpty()) throw new IllegalArgumentException("Blank " + field);
        return value;
    }

    private static String optional(String value, int limit, String field) {
        Objects.requireNonNull(value, field);
        if (value.length() > limit) throw new IllegalArgumentException("Overlong " + field);
        return value;
    }
}
