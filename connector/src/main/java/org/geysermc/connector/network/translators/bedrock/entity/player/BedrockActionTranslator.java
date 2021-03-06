/*
 * Copyright (c) 2019-2020 GeyserMC. http://geysermc.org
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
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network.translators.bedrock.entity.player;

import com.github.steveice10.mc.protocol.data.game.entity.metadata.Position;
import com.github.steveice10.mc.protocol.data.game.entity.player.PlayerAction;
import com.github.steveice10.mc.protocol.data.game.entity.player.PlayerState;
import com.github.steveice10.mc.protocol.data.game.world.block.BlockFace;
import com.github.steveice10.mc.protocol.packet.ingame.client.player.ClientPlayerAbilitiesPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.player.ClientPlayerActionPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.player.ClientPlayerStatePacket;
import com.nukkitx.math.vector.Vector3i;
import com.nukkitx.protocol.bedrock.data.LevelEventType;
import com.nukkitx.protocol.bedrock.data.entity.EntityEventType;
import com.nukkitx.protocol.bedrock.packet.EntityEventPacket;
import com.nukkitx.protocol.bedrock.packet.LevelEventPacket;
import com.nukkitx.protocol.bedrock.packet.PlayStatusPacket;
import com.nukkitx.protocol.bedrock.packet.PlayerActionPacket;
import org.geysermc.connector.entity.Entity;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.translators.PacketTranslator;
import org.geysermc.connector.network.translators.Translator;
import org.geysermc.connector.network.translators.collision.CollisionManager;
import org.geysermc.connector.network.translators.world.block.BlockTranslator;
import org.geysermc.connector.utils.BlockUtils;

import java.util.concurrent.TimeUnit;

@Translator(packet = PlayerActionPacket.class)
public class BedrockActionTranslator extends PacketTranslator<PlayerActionPacket> {

    @Override
    public void translate(PlayerActionPacket packet, GeyserSession session) {
        Entity entity = session.getPlayerEntity();
        if (entity == null)
            return;

        Vector3i vector = packet.getBlockPosition();
        Position position = new Position(vector.getX(), vector.getY(), vector.getZ());

        switch (packet.getAction()) {
            case RESPAWN:
                // Respawn process is finished and the server and client are both OK with respawning.
                EntityEventPacket eventPacket = new EntityEventPacket();
                eventPacket.setRuntimeEntityId(entity.getGeyserId());
                eventPacket.setType(EntityEventType.RESPAWN);
                eventPacket.setData(0);
                session.sendUpstreamPacket(eventPacket);
                // Resend attributes or else in rare cases the user can think they're not dead when they are, upon joining the server
                entity.updateBedrockAttributes(session);
                break;
            case START_SWIMMING:
                ClientPlayerStatePacket startSwimPacket = new ClientPlayerStatePacket((int) entity.getEntityId(), PlayerState.START_SPRINTING);
                session.sendDownstreamPacket(startSwimPacket);
                break;
            case STOP_SWIMMING:
                ClientPlayerStatePacket stopSwimPacket = new ClientPlayerStatePacket((int) entity.getEntityId(), PlayerState.STOP_SPRINTING);
                session.sendDownstreamPacket(stopSwimPacket);
                break;
            case START_GLIDE:
                // Otherwise gliding will not work in creative
                ClientPlayerAbilitiesPacket playerAbilitiesPacket = new ClientPlayerAbilitiesPacket(false);
                session.sendDownstreamPacket(playerAbilitiesPacket);
            case STOP_GLIDE:
                ClientPlayerStatePacket glidePacket = new ClientPlayerStatePacket((int) entity.getEntityId(), PlayerState.START_ELYTRA_FLYING);
                session.sendDownstreamPacket(glidePacket);
                break;
            case START_SNEAK:
                ClientPlayerStatePacket startSneakPacket = new ClientPlayerStatePacket((int) entity.getEntityId(), PlayerState.START_SNEAKING);
                session.sendDownstreamPacket(startSneakPacket);
                session.setSneaking(true);
                break;
            case STOP_SNEAK:
                ClientPlayerStatePacket stopSneakPacket = new ClientPlayerStatePacket((int) entity.getEntityId(), PlayerState.STOP_SNEAKING);
                session.sendDownstreamPacket(stopSneakPacket);
                session.setSneaking(false);
                break;
            case START_SPRINT:
                ClientPlayerStatePacket startSprintPacket = new ClientPlayerStatePacket((int) entity.getEntityId(), PlayerState.START_SPRINTING);
                session.sendDownstreamPacket(startSprintPacket);
                session.setSprinting(true);
                break;
            case STOP_SPRINT:
                ClientPlayerStatePacket stopSprintPacket = new ClientPlayerStatePacket((int) entity.getEntityId(), PlayerState.STOP_SPRINTING);
                session.sendDownstreamPacket(stopSprintPacket);
                session.setSprinting(false);
                break;
            case DROP_ITEM:
                ClientPlayerActionPacket dropItemPacket = new ClientPlayerActionPacket(PlayerAction.DROP_ITEM, position, BlockFace.values()[packet.getFace()]);
                session.sendDownstreamPacket(dropItemPacket);
                break;
            case STOP_SLEEP:
                ClientPlayerStatePacket stopSleepingPacket = new ClientPlayerStatePacket((int) entity.getEntityId(), PlayerState.LEAVE_BED);
                session.sendDownstreamPacket(stopSleepingPacket);
                break;
            case BLOCK_INTERACT:
                // Client means to interact with a block; cancel bucket interaction, if any
                if (session.getBucketScheduledFuture() != null) {
                    session.getBucketScheduledFuture().cancel(true);
                    session.setBucketScheduledFuture(null);
                }
                // Otherwise handled in BedrockInventoryTransactionTranslator
                break;
            case START_BREAK:
                if (session.getConnector().getConfig().isCacheChunks()) {
                    // Account for fire - the client likes to hit the block behind.
                    Vector3i fireBlockPos = BlockUtils.getBlockPosition(packet.getBlockPosition(), packet.getFace());
                    int blockUp = session.getConnector().getWorldManager().getBlockAt(session, fireBlockPos);
                    String identifier = BlockTranslator.getJavaIdBlockMap().inverse().get(blockUp);
                    if (identifier.startsWith("minecraft:fire") || identifier.startsWith("minecraft:soul_fire")) {
                        ClientPlayerActionPacket startBreakingPacket = new ClientPlayerActionPacket(PlayerAction.START_DIGGING, new Position(fireBlockPos.getX(),
                                fireBlockPos.getY(), fireBlockPos.getZ()), BlockFace.values()[packet.getFace()]);
                        session.sendDownstreamPacket(startBreakingPacket);
                        break;
                    }
                }
                ClientPlayerActionPacket startBreakingPacket = new ClientPlayerActionPacket(PlayerAction.START_DIGGING, new Position(packet.getBlockPosition().getX(),
                        packet.getBlockPosition().getY(), packet.getBlockPosition().getZ()), BlockFace.values()[packet.getFace()]);
                session.sendDownstreamPacket(startBreakingPacket);
                break;
            case CONTINUE_BREAK:
                LevelEventPacket continueBreakPacket = new LevelEventPacket();
                continueBreakPacket.setType(LevelEventType.PARTICLE_CRACK_BLOCK);
                continueBreakPacket.setData(BlockTranslator.getBedrockBlockId(session.getBreakingBlock()));
                continueBreakPacket.setPosition(packet.getBlockPosition().toFloat());
                session.sendUpstreamPacket(continueBreakPacket);
                break;
            case ABORT_BREAK:
                ClientPlayerActionPacket abortBreakingPacket = new ClientPlayerActionPacket(PlayerAction.CANCEL_DIGGING, new Position(packet.getBlockPosition().getX(),
                        packet.getBlockPosition().getY(), packet.getBlockPosition().getZ()), BlockFace.DOWN);
                session.sendDownstreamPacket(abortBreakingPacket);
                break;
            case STOP_BREAK:
                // Handled in BedrockInventoryTransactionTranslator
                break;
            case DIMENSION_CHANGE_SUCCESS:
                if (session.getPendingDimSwitches().decrementAndGet() == 0) {
                    //sometimes the client doesn't feel like loading
                    PlayStatusPacket spawnPacket = new PlayStatusPacket();
                    spawnPacket.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
                    session.sendUpstreamPacket(spawnPacket);
                    entity.updateBedrockAttributes(session);
                    session.getEntityCache().updateBossBars();
                }
                break;
            case JUMP:
                session.setJumping(true);
                session.getConnector().getGeneralThreadPool().schedule(() -> {
                    session.setJumping(false);
                }, 1, TimeUnit.SECONDS);
                break;
        }
    }
}
