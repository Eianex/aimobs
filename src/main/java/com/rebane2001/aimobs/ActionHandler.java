package com.rebane2001.aimobs;

import com.rebane2001.aimobs.mixin.ChatHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.biome.Biome;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class ActionHandler {
    public static String prompts = "";
    public static String entityName = "";
    public static String entityBaseName = ""; // added new variable for base name
    public static String entityDisplayName = ""; // added new variable for display name
    public static int entityId = 0;
    public static UUID initiator = null;
    public static long lastRequest = 0;

    private static ChatHudLine.Visible waitMessage;
    private static List<ChatHudLine.Visible> getChatHudMessages() {
        return ((ChatHudAccessor)MinecraftClient.getInstance().inGameHud.getChatHud()).getVisibleMessages();
    }
    private static void showWaitMessage(String name) {
        if (waitMessage != null) getChatHudMessages().remove(waitMessage);
        waitMessage = new ChatHudLine.Visible(MinecraftClient.getInstance().inGameHud.getTicks(), OrderedText.concat(OrderedText.styledForwardsVisitedString("<" + name + "> ", Style.EMPTY),OrderedText.styledForwardsVisitedString("...", Style.EMPTY.withColor(Formatting.GRAY))), null, true);
        getChatHudMessages().add(0, waitMessage);
    }
    private static void hideWaitMessage() {
        if (waitMessage != null) getChatHudMessages().remove(waitMessage);
        waitMessage = null;
    }

    private static String getBiome(Entity entity) {
        Optional<RegistryKey<Biome>> biomeKey = entity.getEntityWorld().getBiomeAccess().getBiome(entity.getBlockPos()).getKey();
        if (biomeKey.isEmpty()) return "place";
        return I18n.translate(Util.createTranslationKey("biome", biomeKey.get().getValue()));
    }

    public static void startConversation(Entity entity, PlayerEntity player) {
        entityId = entity.getId();
        initiator = player.getUuid();
        prompts = createPrompt(entity, player);
        ItemStack heldItem = player.getMainHandStack();
        if (heldItem.getCount() > 0)
            prompts = "You are holding a " + heldItem.getName().getString() + " in your hand. " + prompts;
        showWaitMessage(entityDisplayName); // use display name here
        getResponse(player);
    }

    public static void getResponse(PlayerEntity player) {
        if (lastRequest + 1500L > System.currentTimeMillis()) return;
        if (AIMobsConfig.config.apiKey.length() == 0) {
            player.sendMessage(Text.of("[AIMobs] You have not set an API key! Get one from https://beta.openai.com/account/api-keys and set it with /aimobs setkey"));
            return;
        }
        lastRequest = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                String response = RequestHandler.getAIResponse(prompts);
                player.sendMessage(Text.of("<" + entityDisplayName + "> " + response)); // use display name here
                prompts += response + "\"\n";
            } catch (Exception e) {
                player.sendMessage(Text.of("[AIMobs] Error getting response"));
                e.printStackTrace();
            } finally {
                hideWaitMessage();
            }
        });
        t.start();
    }

    public static void replyToEntity(String message, PlayerEntity player) {
        if (entityId == 0) return;
        prompts += (player.getUuid() == initiator) ? "You say: \"" : ("Your friend " + player.getName().getString() + " says: \"");
        prompts += message.replace("\"", "'") + "\"\n The " + entityDisplayName + " says: \""; // use display name here
        getResponse(player);
    }

    private static boolean isEntityHurt(LivingEntity entity) {
        return entity.getHealth() * 1.2 < entity.getAttributeValue(EntityAttributes.GENERIC_MAX_HEALTH);
    }

    private static String createPromptVillager(VillagerEntity villager, PlayerEntity player) {
        boolean isHurt = isEntityHurt(villager);
        entityBaseName = "Villager";
        entityDisplayName = entityBaseName; // initially set display name to be the base name
        String villageName = villager.getVillagerData().getType().toString().toLowerCase(Locale.ROOT) + " village";
        int rep = villager.getReputation(player);
        if (rep < -5) villageName = villageName + " that sees you as horrible";
        if (rep > 5) villageName = villageName + " that sees you as reputable";
        if (villager.isBaby()) {
            entityBaseName = "Villager Kid";
            entityDisplayName = entityBaseName; // set display name to be the new base name
            return String.format("You see a kid in a %s. The kid shouts: \"", villageName);
        }
        String profession = StringUtils.capitalize(villager.getVillagerData().getProfession().toString().toLowerCase(Locale.ROOT).replace("none", "freelancer"));
        entityBaseName = profession; // overwrite base name with the profession
        entityDisplayName = entityBaseName; // set display name to be the new base name
        if (villager.getVillagerData().getLevel() >= 3) entityDisplayName = "skilled " + entityDisplayName; // modify display name
        if (isHurt) entityDisplayName = "hurt " + entityDisplayName; // modify display name
        Text customName = villager.getCustomName();
        if (customName != null)
            entityDisplayName = entityDisplayName + " called " + customName.getString(); // modify display name
        return String.format("You meet a %s in a %s. It talks to you and says directly to you: \"", entityDisplayName, villageName);
    }

    public static String createPromptLiving(LivingEntity entity) {
        boolean isHurt = isEntityHurt(entity);
        // set base name for the entity: cow, pig, sheep, etc.
        entityBaseName = entity.getType().getTranslationKey().replace("entity.minecraft.", "").replace("_", " ");
        entityDisplayName = entityBaseName; // initially set display name to be the base name
        Text customName = entity.getCustomName();
        if (customName != null)
            entityDisplayName = entityBaseName + " called " + customName.getString();  // modify display name
        if (isHurt) entityDisplayName = "hurt " + entityDisplayName; // modify display name
        return String.format("You meet a talking %s in the %s. The %s says to you: \"", entityDisplayName, getBiome(entity), entityBaseName);  // use base name here to keep the entity type in the conversation
    }
    
    public static String createPrompt(Entity entity, PlayerEntity player) {
        if (entity instanceof VillagerEntity villager) return createPromptVillager(villager, player);
        if (entity instanceof LivingEntity entityLiving) return createPromptLiving(entityLiving);
        entityBaseName = entity.getName().getString();
        entityDisplayName = entityBaseName; // initially set display name to be the base name
        return "You see a " + entityDisplayName + ". The " + entityBaseName + " says: \"";
    }

    public static void handlePunch(Entity entity, Entity player) {
        if (entity.getId() != entityId) return;
        prompts += "Suddenly, " + player.getName().getString() + " punches the " + entityDisplayName + ". The " + entityBaseName + " screams out in pain: \"";
        getResponse((PlayerEntity) player);
    }
}