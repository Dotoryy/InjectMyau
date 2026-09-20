package myau.inject;

import myau.inject.HookRegistry.Position;

public final class Hooks {
    private static final String V = "()V";
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String KEY_BINDING = "net.minecraft.client.settings.KeyBinding";
    private static final String ITEM_RENDERER = "net.minecraft.client.renderer.ItemRenderer";
    private static final String MINECRAFT_GAME_SETTINGS =
            "net.minecraft.client.settings.GameSettings";
    private static final String GUI_INGAME = "net.minecraft.client.gui.GuiIngame";
    private static final String GUI_SCREEN = "net.minecraft.client.gui.GuiScreen";
    private static final String GUI_INGAME_FORGE = "net.minecraftforge.client.GuiIngameForge";
    private static final String ENTITY_RENDERER = "net.minecraft.client.renderer.EntityRenderer";
    private static final String NETWORK_MANAGER = "net.minecraft.network.NetworkManager";
    private static final String NET_HANDLER_PLAY_CLIENT =
            "net.minecraft.client.network.NetHandlerPlayClient";
    private static final String BLOCK = "net.minecraft.block.Block";
    private static final String VIS_GRAPH = "net.minecraft.client.renderer.chunk.VisGraph";
    private static final String WORLD = "net.minecraft.world.World";
    private static final String WORLD_RENDERER = "net.minecraft.client.renderer.WorldRenderer";
    private static final String BLOCK_LAYER = "()Lnet/minecraft/util/EnumWorldBlockLayer;";
    private static final String FONT_RENDERER = "net.minecraft.client.gui.FontRenderer";
    private static final String RENDER_MANAGER = "net.minecraft.client.renderer.entity.RenderManager";
    private static final String LIVING_RENDERER =
            "net.minecraft.client.renderer.entity.RendererLivingEntity";
    private static final String ENTITY = "net.minecraft.entity.Entity";
    private static final String LIVING = "net.minecraft.entity.EntityLivingBase";
    private static final String PLAYER = "net.minecraft.entity.player.EntityPlayer";
    private static final String ABSTRACT_PLAYER =
            "net.minecraft.client.entity.AbstractClientPlayer";
    private static final String LOCAL_PLAYER = "net.minecraft.client.entity.EntityPlayerSP";
    private static final String CONTROLLER = "net.minecraft.client.multiplayer.PlayerControllerMP";
    private static final String POTION_CHECK =
            "(Lnet/minecraft/potion/Potion;)Z";
    private Hooks() {
    }
    public static void register() {
        minecraft();
        blocks();
        world();
        renderers();
        entities();
        localPlayer();
        camera();
        controller();
    }
    private static void minecraft() {

        HookRegistry.hook(MINECRAFT, "runTick", V).at(Position.HEAD).calls("tickPre").add();
        HookRegistry.hook(MINECRAFT, "runTick", V).at(Position.RETURN).calls("tickPost").add();
        HookRegistry.hook(ITEM_RENDERER, "renderItemInFirstPerson", "(F)V")
                .at(Position.REPLACE_INVOKE)
                .invoking(ABSTRACT_PLAYER, "getItemInUseCount", "()I").membersOf(PLAYER)
                .calls("itemInUseCountForRender",
                        "(Lnet/minecraft/entity/player/EntityPlayer;)I")
                .in(RenderCallbacks.OWNER).optional().add();
        HookRegistry.hook(MINECRAFT, "runTick", V)
                .at(Position.BEFORE_FIELD)
                .field(MINECRAFT_GAME_SETTINGS, "chatVisibility",
                        "Lnet/minecraft/entity/player/EntityPlayer$EnumChatVisibility;")
                .calls("prePlayerInteract").optional().add();
        HookRegistry.hook(MINECRAFT, "runInputTick", V)
                .at(Position.BEFORE_FIELD)
                .field(MINECRAFT_GAME_SETTINGS, "chatVisibility",
                        "Lnet/minecraft/entity/player/EntityPlayer$EnumChatVisibility;")
                .calls("prePlayerInteract").optional().add();
        HookRegistry.hook(MINECRAFT, "rightClickMouse", V)
                .at(Position.HEAD).calls("rightClickMouse", "()Z").cancellable().add();
        HookRegistry.hook(MINECRAFT, "clickMouse", V)
                .at(Position.HEAD).calls("clickMouse", "()Z").cancellable().add();
        HookRegistry.hook(MINECRAFT, "sendClickBlockToController", "(Z)V")
                .at(Position.HEAD).calls("sendClickBlockToController", "()Z").cancellable().add();

        HookRegistry.hook(KEY_BINDING, "setKeyBindState", "(IZ)V")
                .at(Position.HEAD).calls("keyBindStatePre", "(IZ)Z").args("0,1")
                .cancellable().add();
        HookRegistry.hook(KEY_BINDING, "setKeyBindState", "(IZ)V")
                .at(Position.RETURN).calls("keyBindStatePost").add();

        HookRegistry.hook(GUI_SCREEN, "handleKeyboardInput", V)
                .at(Position.HEAD).calls("guiKeyboardInput").optional().add();

        HookRegistry.hook(MINECRAFT, "setIngameNotInFocus", V)
                .at(Position.RETURN).calls("ingameNotInFocus").add();
        HookRegistry.hook(MINECRAFT, "loadWorld",
                        "(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V")
                .at(Position.HEAD).calls("loadWorld").add();

        HookRegistry.hook(GUI_INGAME_FORGE, "renderGameOverlay", "(F)V")
                .at(Position.HEAD).calls("render2DPre", "(F)V").args("0")
                .namesFrom(GUI_INGAME).optional().add();
        HookRegistry.hook(GUI_INGAME_FORGE, "renderGameOverlay", "(F)V")
                .at(Position.AFTER_INVOKE).calls("render2DPost")
                .namesFrom(GUI_INGAME).optional()
                .invokingUnmapped(GUI_INGAME_FORGE, "renderTitle", "(IIF)V").add();
        HookRegistry.hook(GUI_INGAME, "renderGameOverlay", "(F)V")
                .at(Position.HEAD).calls("render2DPre", "(F)V").args("0").add();
        HookRegistry.hook(GUI_INGAME, "renderGameOverlay", "(F)V")
                .at(Position.RETURN).calls("render2DPost").add();
        HookRegistry.hook(GUI_INGAME_FORGE, "renderGameOverlay", "(F)V")
                .at(Position.RETURN).calls("render2DFrameEnd")
                .namesFrom(GUI_INGAME).optional().add();
        HookRegistry.hook(GUI_INGAME, "renderGameOverlay", "(F)V")
                .at(Position.RETURN).calls("render2DFrameEnd").add();
        HookRegistry.hook(ENTITY_RENDERER, "renderWorldPass", "(IFJ)V")
                .at(Position.HEAD).calls("render3DPre", "(F)V").args("1").add();
        HookRegistry.hook(ENTITY_RENDERER, "renderWorldPass", "(IFJ)V")
                .at(Position.BEFORE_FIELD).calls("render3DPost")
                .field(ENTITY_RENDERER, "renderHand", "Z").add();

        HookRegistry.hook(NET_HANDLER_PLAY_CLIENT, "handlePlayerPosLook",
                        "(Lnet/minecraft/network/play/server/S08PacketPlayerPosLook;)V")
                .at(Position.REPLACE_INVOKE)
                .invoking(PLAYER, "setPositionAndRotation", "(DDDFF)V").membersOf(ENTITY)
                .calls("teleportRotation",
                        "(Lnet/minecraft/entity/player/EntityPlayer;DDDFF)V").add();

        HookRegistry.hook(NETWORK_MANAGER, "channelRead0",
                        "(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V")
                .at(Position.HEAD).calls("packetReceive", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .args("this,1").cancellable().add();
        HookRegistry.hook(NETWORK_MANAGER, "sendPacket", "(Lnet/minecraft/network/Packet;)V")
                .at(Position.HEAD).calls("packetSend", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .args("this,0").cancellable().add();
        HookRegistry.hook(NETWORK_MANAGER, "sendPacket",
                        "(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;"
                                + "[Lio/netty/util/concurrent/GenericFutureListener;)V")
                .at(Position.HEAD).calls("packetSendWithListeners", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .args("this,0").cancellable().add();
    }
    private static void blocks() {
        String world = WorldCallbacks.OWNER;
        HookRegistry.hook(BLOCK, "shouldSideBeRendered",
                        "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/BlockPos;"
                                + "Lnet/minecraft/util/EnumFacing;)Z").in(world)
                .at(Position.HEAD).cancellable().args("this,0,1,2")
                .calls("shouldSideBeRendered",
                        "(Lnet/minecraft/block/Block;Lnet/minecraft/world/IBlockAccess;"
                                + "Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)"
                                + "Ljava/lang/Object;").add();
        HookRegistry.hook(BLOCK, "getBlockLayer", BLOCK_LAYER).in(world).at(Position.HEAD).cancellable().args("this")
                .calls("getBlockLayer", "(Lnet/minecraft/block/Block;)Ljava/lang/Object;").add();

        for (String subclass : new String[]{
                "net.minecraft.block.BlockBush",
                "net.minecraft.block.BlockGrass",
                "net.minecraft.block.BlockLadder",
                "net.minecraft.block.BlockLeaves",
                "net.minecraft.block.BlockPane",
                "net.minecraft.block.BlockWeb"}) {
            HookRegistry.hook(subclass, "getBlockLayer", BLOCK_LAYER)
                    .in(world).at(Position.HEAD).cancellable()
                    .calls("getBlockLayerAlwaysTranslucent", "()Ljava/lang/Object;").add();
        }

        HookRegistry.hook("net.minecraft.client.renderer.BlockModelRenderer", "renderModel",
                        "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/resources/model/IBakedModel;"
                                + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;"
                                + "Lnet/minecraft/client/renderer/WorldRenderer;Z)Z")
                .in(world).at(Position.HEAD).cancellable().args("this,0,1,2,3,4,5")
                .calls("renderModel",
                        "(Lnet/minecraft/client/renderer/BlockModelRenderer;"
                                + "Lnet/minecraft/world/IBlockAccess;"
                                + "Lnet/minecraft/client/resources/model/IBakedModel;"
                                + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;"
                                + "Lnet/minecraft/client/renderer/WorldRenderer;Z)Ljava/lang/Object;").add();
        HookRegistry.hook("net.minecraft.client.renderer.BlockRendererDispatcher", "renderBlock",
                        "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;"
                                + "Lnet/minecraft/world/IBlockAccess;"
                                + "Lnet/minecraft/client/renderer/WorldRenderer;)Z").in(world).at(Position.HEAD).args("0,1")
                .calls("renderBlock",
                        "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;)V").add();
        HookRegistry.hook(VIS_GRAPH, "func_178606_a", "(Lnet/minecraft/util/BlockPos;)V").in(world)
                .at(Position.HEAD).cancellable().calls("setOpaqueCube", "()Z").add();
        HookRegistry.hook(VIS_GRAPH, "computeVisibility",
                        "()Lnet/minecraft/client/renderer/chunk/SetVisibility;").in(world)
                .at(Position.HEAD).cancellable()
                .calls("computeVisibility", "()Ljava/lang/Object;").add();
        HookRegistry.hook("net.minecraft.item.ItemStack", "hasEffect", "()Z").in(world)
                .at(Position.HEAD).cancellable().args("this")
                .calls("hasEffect", "(Lnet/minecraft/item/ItemStack;)Ljava/lang/Object;").add();
    }
    private static void world() {
        String callbacks = WorldCallbacks.OWNER;
        HookRegistry.hook(WORLD_RENDERER, "putColorMultiplier", "(FFFI)V").in(callbacks)
                .at(Position.REPLACE_INVOKE)
                .invokingUnmapped("java.nio.IntBuffer", "put", "(II)Ljava/nio/IntBuffer;")
                .calls("putColorMultiplier", "(Ljava/nio/IntBuffer;II)Ljava/nio/IntBuffer;").add();
        HookRegistry.hook(WORLD, "handleMaterialAcceleration",
                        "(Lnet/minecraft/util/AxisAlignedBB;Lnet/minecraft/block/material/Material;"
                                + "Lnet/minecraft/entity/Entity;)Z").in(callbacks)
                .at(Position.REPLACE_INVOKE)
                .invoking("net.minecraft.entity.Entity", "isPushedByWater", "()Z")
                .calls("isPushedByWater", "(Lnet/minecraft/entity/Entity;)Z").add();
        HookRegistry.hook(WORLD, "rayTraceBlocks",
                        "(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;ZZZ)"
                                + "Lnet/minecraft/util/MovingObjectPosition;")
                .in(callbacks).at(Position.REPLACE_INVOKE)
                .invoking(WORLD, "getBlockState",
                        "(Lnet/minecraft/util/BlockPos;)Lnet/minecraft/block/state/IBlockState;")
                .calls("rayTraceBlockState",
                        "(Lnet/minecraft/world/World;Lnet/minecraft/util/BlockPos;)"
                                + "Lnet/minecraft/block/state/IBlockState;").add();
    }
    private static void renderers() {
        String render = RenderCallbacks.OWNER;
        String string = "(Ljava/lang/String;)Ljava/lang/String;";

        HookRegistry.hook(FONT_RENDERER, "renderString", "(Ljava/lang/String;FFIZ)I").in(render)
                .at(Position.MODIFY_ARGUMENT).args("0").calls("text", string).add();
        HookRegistry.hook(FONT_RENDERER, "getStringWidth", "(Ljava/lang/String;)I").in(render)
                .at(Position.MODIFY_ARGUMENT).args("0").calls("text", string).add();
        HookRegistry.hook(FONT_RENDERER, "getStringWidth", "(Ljava/lang/String;)I").in(render)
                .at(Position.REPLACE_INVOKE).ordinal(1)
                .invokingUnmapped("java.lang.String", "charAt", "(I)C")
                .calls("formattingCode", "(Ljava/lang/String;I)C").add();

        HookRegistry.hook(RENDER_MANAGER, "renderEntityStatic",
                        "(Lnet/minecraft/entity/Entity;FZ)Z").in(render)
                .at(Position.HEAD).args("0")
                .calls("renderEntityStaticPre", "(Lnet/minecraft/entity/Entity;)V").add();
        HookRegistry.hook(RENDER_MANAGER, "renderEntityStatic",
                        "(Lnet/minecraft/entity/Entity;FZ)Z").in(render)
                .at(Position.RETURN).calls("renderEntityStaticPost").add();
        HookRegistry.hook(LIVING_RENDERER, "doRender",
                        "(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V").in(render)
                .at(Position.HEAD).args("0")
                .calls("renderLivingPre", "(Lnet/minecraft/entity/EntityLivingBase;)V").add();
        HookRegistry.hook(LIVING_RENDERER, "doRender",
                        "(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V").in(render)
                .at(Position.RETURN).calls("renderLivingPost").add();
        HookRegistry.hook(LIVING_RENDERER, "canRenderName",
                        "(Lnet/minecraft/entity/EntityLivingBase;)Z").in(render)
                .at(Position.HEAD).cancellable().args("0")
                .calls("canRenderName",
                        "(Lnet/minecraft/entity/EntityLivingBase;)Ljava/lang/Object;").add();

        HookRegistry.hook(ABSTRACT_PLAYER, "getFovModifier", "()F").in(render)
                .at(Position.HEAD).args("this")
                .calls("fovModifierPre", "(Ljava/lang/Object;)V").add();
        HookRegistry.hook(ABSTRACT_PLAYER, "getFovModifier", "()F").in(render)
                .at(Position.REPLACE_INVOKE)
                .invoking("net.minecraft.entity.ai.attributes.IAttributeInstance",
                        "getAttributeValue", "()D")
                .calls("fovAttributeValue",
                        "(Lnet/minecraft/entity/ai/attributes/IAttributeInstance;)D").add();
    }
    private static void entities() {
        String entity = EntityCallbacks.OWNER;
        HookRegistry.hook(ENTITY, "setVelocity", "(DDD)V").in(entity)
                .at(Position.HEAD).cancellable().args("this,0,1,2")
                .calls("setVelocity", "(Ljava/lang/Object;DDD)Z").add();
        HookRegistry.hook(ENTITY, "setAngles", "(FF)V").in(entity)
                .at(Position.HEAD).cancellable().args("this,0,1")
                .calls("setAngles", "(Ljava/lang/Object;FF)Z").add();

        HookRegistry.hook(ENTITY, "moveEntity", "(DDD)V").in(entity)
                .at(Position.HEAD).args("this")
                .calls("enterMoveEntity", "(Ljava/lang/Object;)V").add();
        HookRegistry.hook(ENTITY, "moveEntity", "(DDD)V").in(entity)
                .at(Position.MODIFY_STORE).storing("Z").ordinal(0)
                .calls("safeWalk", "(Z)Z").add();
        HookRegistry.hook(LIVING, "jump", V).in(entity)
                .at(Position.HEAD).args("this")
                .calls("enterJump", "(Ljava/lang/Object;)V").add();
        HookRegistry.hook(LIVING, "jump", V).in(entity)
                .at(Position.MODIFY_STORE).storing("F").ordinal(0)
                .calls("jumpYaw", "(F)F").add();
        HookRegistry.hook(LIVING, "moveEntityWithHeading", "(FF)V").in(entity)
                .at(Position.HEAD).args("this")
                .calls("enterMoveEntityWithHeading", "(Ljava/lang/Object;)V").add();
        HookRegistry.hook(LIVING, "moveEntityWithHeading", "(FF)V").in(entity)
                .at(Position.REPLACE_INVOKE)
                .invoking(LIVING, "moveFlying", "(FFF)V").membersOf(ENTITY)
                .calls("moveFlying", "(Lnet/minecraft/entity/EntityLivingBase;FFF)V").add();

        HookRegistry.hook(LIVING, "moveEntityWithHeading", "(FF)V").in(entity)
                .at(Position.REPLACE_INVOKE)
                .invoking(LIVING, "moveEntity", "(DDD)V").membersOf(ENTITY)
                .calls("moveEntity", "(Lnet/minecraft/entity/EntityLivingBase;DDD)V").add();

        HookRegistry.hook(LIVING, "moveEntityWithHeading", "(FF)V").in(entity)
                .at(Position.MODIFY_STORE).storing("F").ordinal(2)
                .calls("depthStrider", "(F)F").add();
        HookRegistry.hook(PLAYER, "attackTargetEntityWithCurrentItem",
                        "(Lnet/minecraft/entity/Entity;)V").in(entity)
                .at(Position.MODIFY_CONSTANT).replacing("0.6")
                .calls("attackSlowdown", "(D)D").add();
        HookRegistry.hook(PLAYER, "attackTargetEntityWithCurrentItem",
                        "(Lnet/minecraft/entity/Entity;)V").in(entity)
                .at(Position.REPLACE_INVOKE)
                .invoking(PLAYER, "setSprinting", "(Z)V").membersOf(ENTITY)
                .calls("setSprinting", "(Lnet/minecraft/entity/player/EntityPlayer;Z)V").add();
    }
    private static void localPlayer() {
        String local = LocalPlayerCallbacks.OWNER;
        String update = "()V";
        HookRegistry.hook(LOCAL_PLAYER, "onUpdate", update).in(local)
                .at(Position.HEAD).args("this")
                .calls("onUpdatePre", "(Ljava/lang/Object;)V").add();
        HookRegistry.hook(LOCAL_PLAYER, "onUpdate", update).in(local)
                .at(Position.RETURN).calls("onUpdatePost").add();

        HookRegistry.hook(LOCAL_PLAYER, "onUpdate", update).in(local)
                .at(Position.REPLACE_INVOKE)
                .invoking(LOCAL_PLAYER, "isRiding", "()Z").membersOf(ENTITY)
                .calls("isRidingDuringUpdate",
                        "(Lnet/minecraft/client/entity/EntityPlayerSP;)Z").add();
        HookRegistry.hook(LOCAL_PLAYER, "onUpdate", update).in(local)
                .at(Position.BEFORE_INVOKE)
                .invoking(LOCAL_PLAYER, "onUpdateWalkingPlayer", "()V")
                .calls("onMotionUpdate").add();
        HookRegistry.hook(LOCAL_PLAYER, "onUpdateWalkingPlayer", update).in(local)
                .at(Position.REPLACE_INVOKE)
                .invoking(LOCAL_PLAYER, "isCurrentViewEntity", "()Z")
                .calls("isCurrentViewEntityForMotion",
                        "(Lnet/minecraft/client/entity/EntityPlayerSP;)Z").add();
        HookRegistry.hook(LOCAL_PLAYER, "onLivingUpdate", update).in(local)
                .at(Position.BEFORE_INVOKE)
                .invoking(ABSTRACT_PLAYER, "onLivingUpdate", "()V").membersOf(LIVING)
                .calls("onLivingUpdate").add();
        HookRegistry.hook(LOCAL_PLAYER, "onLivingUpdate", update).in(local)
                .at(Position.AFTER_INVOKE)
                .invoking("net.minecraft.util.MovementInput", "updatePlayerMoveState", "()V")
                .calls("onMoveInput").add();
        HookRegistry.hook(LOCAL_PLAYER, "onLivingUpdate", update).in(local)
                .at(Position.REPLACE_INVOKE)
                .invoking(LOCAL_PLAYER, "isUsingItem", "()Z").membersOf(PLAYER)
                .calls("isUsingItem", "(Lnet/minecraft/client/entity/EntityPlayerSP;)Z").add();
        HookRegistry.hook(LOCAL_PLAYER, "onLivingUpdate", update).in(local)
                .at(Position.REPLACE_INVOKE)
                .invoking(LOCAL_PLAYER, "isPotionActive", POTION_CHECK).membersOf(LIVING)
                .calls("isPotionActive",
                        "(Lnet/minecraft/client/entity/EntityPlayerSP;"
                                + "Lnet/minecraft/potion/Potion;)Z").add();
    }
    private static void camera() {
        String camera = CameraCallbacks.OWNER;

        HookRegistry.hook(ENTITY_RENDERER, "updateCameraAndRender", "(FJ)V").in(camera)
                .at(Position.HEAD).calls("cameraPre").add();
        HookRegistry.hook(ENTITY_RENDERER, "updateCameraAndRender", "(FJ)V").in(camera)
                .at(Position.RETURN).calls("cameraPost").add();
        HookRegistry.hook(ENTITY_RENDERER, "updateRenderer", V).in(camera)
                .at(Position.HEAD).calls("rendererPre").add();
        HookRegistry.hook(ENTITY_RENDERER, "updateRenderer", V).in(camera)
                .at(Position.RETURN).calls("rendererPost").add();
        HookRegistry.hook(ENTITY_RENDERER, "hurtCameraEffect", "(F)V").in(camera)
                .at(Position.MODIFY_CONSTANT).replacing("14.0").ordinal(0)
                .calls("hurtCameraAngle", "(F)F").add();

        HookRegistry.hook(ENTITY_RENDERER, "getMouseOver", "(F)V").in(camera)
                .at(Position.MODIFY_CONSTANT).replacing("3.0").ordinal(1)
                .calls("pickRange", "(D)D").add();
        HookRegistry.hook(ENTITY_RENDERER, "getMouseOver", "(F)V").in(camera)
                .at(Position.MODIFY_STORE).storing("D").ordinal(0)
                .calls("raytraceRange", "(D)D").add();
        HookRegistry.hook(ENTITY_RENDERER, "getMouseOver", "(F)V").in(camera)
                .at(Position.REPLACE_INVOKE).ordinal(0)
                .invokingUnmapped("java.util.List", "size", "()I")
                .calls("entityCandidates", "(Ljava/util/List;)I").add();
        HookRegistry.hook(ENTITY_RENDERER, "orientCamera", "(F)V").in(camera)
                .at(Position.REPLACE_INVOKE)
                .invoking("net.minecraft.util.Vec3", "distanceTo",
                        "(Lnet/minecraft/util/Vec3;)D")
                .calls("cameraDistance",
                        "(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;)D").add();
        HookRegistry.hook(ENTITY_RENDERER, "setupFog", "(IF)V").in(camera)
                .at(Position.REPLACE_INVOKE)
                .invoking(BLOCK, "getMaterial", "()Lnet/minecraft/block/material/Material;")
                .calls("fogMaterial",
                        "(Lnet/minecraft/block/Block;)Lnet/minecraft/block/material/Material;").add();
        HookRegistry.hook(ENTITY_RENDERER, "setupFog", "(IF)V").in(camera)
                .at(Position.REPLACE_INVOKE)
                .invoking(LIVING, "isPotionActive", POTION_CHECK)
                .calls("blindnessForFog",
                        "(Lnet/minecraft/entity/EntityLivingBase;"
                                + "Lnet/minecraft/potion/Potion;)Z").add();
        HookRegistry.hook(ENTITY_RENDERER, "updateFogColor", "(F)V").in(camera)
                .at(Position.REPLACE_INVOKE)
                .invoking(LIVING, "isPotionActive", POTION_CHECK)
                .calls("blindnessForFog",
                        "(Lnet/minecraft/entity/EntityLivingBase;"
                                + "Lnet/minecraft/potion/Potion;)Z").add();
        HookRegistry.hook(ENTITY_RENDERER, "setupCameraTransform", "(FI)V").in(camera)
                .at(Position.REPLACE_INVOKE)
                .invoking(LOCAL_PLAYER, "isPotionActive", POTION_CHECK).membersOf(LIVING)
                .calls("nauseaForCamera",
                        "(Lnet/minecraft/client/entity/EntityPlayerSP;"
                                + "Lnet/minecraft/potion/Potion;)Z").add();
    }
    private static void controller() {
        String player = PlayerCallbacks.OWNER;
        HookRegistry.hook(CONTROLLER, "attackEntity",
                        "(Lnet/minecraft/entity/player/EntityPlayer;"
                                + "Lnet/minecraft/entity/Entity;)V").in(player)
                .at(Position.HEAD).args("1")
                .calls("attackEntity", "(Ljava/lang/Object;)Z").cancellable().add();

        HookRegistry.hook(CONTROLLER, "sendUseItem",
                        "(Lnet/minecraft/entity/player/EntityPlayer;"
                                + "Lnet/minecraft/world/World;"
                                + "Lnet/minecraft/item/ItemStack;)Z")
                .at(Position.HEAD).args("2")
                .calls("sendUseItem", "(Ljava/lang/Object;)Ljava/lang/Boolean;")
                .cancellable().add();
        HookRegistry.hook(CONTROLLER, "windowClick",
                        "(IIIILnet/minecraft/entity/player/EntityPlayer;)"
                                + "Lnet/minecraft/item/ItemStack;").in(player)
                .at(Position.HEAD).cancellable().args("0,1,2,3")
                .calls("windowClick", "(IIII)Ljava/lang/Object;").add();
        HookRegistry.hook(CONTROLLER, "onStoppedUsingItem",
                        "(Lnet/minecraft/entity/player/EntityPlayer;)V").in(player)
                .at(Position.HEAD).cancellable()
                .calls("onStoppedUsingItem", "()Z").add();
        HookRegistry.hook(KEY_BINDING, "isPressed", "()Z").in(player)
                .at(Position.HEAD).args("this")
                .calls("isPressedPre", "(Ljava/lang/Object;)V").add();
        HookRegistry.hook(KEY_BINDING, "isPressed", "()Z").in(player)
                .at(Position.MODIFY_RETURN).calls("isPressed", "(Z)Z").add();
        HookRegistry.hook(GUI_INGAME, "updateTick", V).in(player)
                .at(Position.REPLACE_INVOKE)
                .invoking("net.minecraft.entity.player.InventoryPlayer", "getCurrentItem",
                        "()Lnet/minecraft/item/ItemStack;")
                .calls("heldItemForDisplay",
                        "(Lnet/minecraft/entity/player/InventoryPlayer;)"
                                + "Lnet/minecraft/item/ItemStack;").add();
        HookRegistry.hook(GUI_INGAME_FORGE, "renderExperience", "(II)V").in(player)
                .at(Position.REPLACE_FIELD).optional()
                .field(LOCAL_PLAYER, "experience", "F").membersOf(PLAYER)
                .calls("experience", "(Lnet/minecraft/client/entity/EntityPlayerSP;)F").add();
        HookRegistry.hook(GUI_INGAME_FORGE, "renderExperience", "(II)V").in(player)
                .at(Position.REPLACE_FIELD).optional()
                .field(LOCAL_PLAYER, "experienceLevel", "I").membersOf(PLAYER)
                .calls("experienceLevel",
                        "(Lnet/minecraft/client/entity/EntityPlayerSP;)I").add();
        HookRegistry.hook("club.sk1er.patcher.util.fov.FovHandler", "fovChange").in(player)
                .at(Position.REPLACE_INVOKE).optional()
                .invokingUnmapped(PLAYER, "func_70051_ag", "()Z")
                .calls("fovSprinting", "(Lnet/minecraft/entity/player/EntityPlayer;)Z").add();
    }
}
