package myau.module.modules;

import myau.Myau;
import myau.enums.ChatColors;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.Render2DPostEvent;
import myau.module.Module;
import myau.util.ColorUtil;
import myau.util.shader.BlurUtils;
import myau.util.shader.RoundedShader;
import myau.ui.clickgui.GuiRender;
import myau.util.RenderUtil;
import myau.util.StencilUtil;
import myau.access.AccessorMinecraft;
import myau.util.font.MsdfAtlas;
import myau.util.font.Fonts;
import myau.util.font.MsdfFontRenderer;
import myau.util.font.RavenFontRenderer;
import myau.util.font.MsdfShader;
import myau.util.TeamUtil;
import myau.util.TimerUtil;
import myau.util.animation.Animation;
import myau.util.animation.ContinualAnimation;
import myau.util.animation.DecelerateAnimation;
import myau.util.animation.Direction;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import com.google.common.collect.Multimap;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collection;
import java.util.Locale;

public class TargetHUD extends Module {
    private static final int MODE_RAVEN = 1;
    private static final int MODE_ASTOLFO = 2;
    private static final int MODE_NOVOLINE = 3;
    private static final int MODE_OLD_ATMOSPHERE = 4;
    private static final int MODE_ATMOSPHERE = 5;
    private static final int MODE_ADJUST = 6;
    private static final int MODE_BMS = 7;
    private static final int MODE_MODERN = 8;
    private static final float VANTA_SCALE = 1.15F;
    private static final float NOVO_HEIGHT = 42.0F;
    private static final float NOVO_BASE_WIDTH = 74.0F;
    private static final float NOVO_HEAD = 40.0F;
    private static final float NOVO_CONTENT_X = 44.0F;
    private static final float NOVO_NAME_Y = 10.0F;
    private static final float NOVO_BAR_Y = 22.0F;
    private static final float NOVO_BAR_HEIGHT = 11.0F;
    private static final float NOVO_BAR_RIGHT_PAD = 4.0F;
    private static final float NOVO_FONT_DESCENT = 2.0F;
    private static final int NOVO_WIDTH_MS = 300;
    private static final int NOVO_HEALTH_MS = 500;

    private static final String ASTOLFO_NAME_SAMPLE = "WWWWWWWWWWWWWWWW";
    private static final float ASTOLFO_HEALTH_GAP = 6.0F;
    private static final float ASTOLFO_FONT_DESCENT = 2.0F;
    private static final float ASTOLFO_BAR_HEIGHT = 7.5F;
    private static final float ASTOLFO_BAR_DROP = 0.0F;
    private static final float ASTOLFO_BOTTOM_PAD = 3.0F;
    private static final float ASTOLFO_NAME_Y = 4.0F;
    private static final float ASTOLFO_WIDTH_PAD = 53.0F;
    private static final int ASTOLFO_MODEL_SCALE = 22;
    public final FloatProperty astolfoHealthScale = new FloatProperty("astolfo-health-scale",
            2.0F, 1.0F, 2.5F, 0.1F, () -> this.mode.getValue() == MODE_ASTOLFO);
    public final BooleanProperty astolfoSharpHealth = new BooleanProperty("astolfo-sharp-health",
            false, () -> this.mode.getValue() == MODE_ASTOLFO);
    private static final int ASTOLFO_HEALTH_MS = 18;
    private static final int ASTOLFO_OPEN_MS = 175;
    private static final double ASTOLFO_OPEN_ENDPOINT = 0.5;

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat healthFormat = new DecimalFormat("0.0", new DecimalFormatSymbols(Locale.US));
    private static final DecimalFormat diffFormat = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));
    private final TimerUtil lastAttackTimer = new TimerUtil();
    private final TimerUtil animTimer = new TimerUtil();
    private EntityLivingBase lastTarget = null;
    private EntityLivingBase target = null;
    private ResourceLocation headTexture = null;
    private float oldHealth = 0.0F;
    private float newHealth = 0.0F;
    private float maxHealth = 0.0F;

    private float ghostBar = Float.NaN;
    private double lastHealth = -1.0;
    private final BarTimer healthBarTimer = new BarTimer();
    private static final float GHOST_MS = 500.0F;
    private static final float GHOST_MIN_WIDTH = 3.0F;

    private static final float RAVEN_PADDING = 8.0F;

    private static final float RAVEN_RADIUS = 10.0F;
    private static final float RAVEN_MODERN_RADIUS = 8.0F;
    private static final int BMS_BLOOM_PASSES = 3;
    private static final float BMS_BLOOM_RADIUS = 2.0F;
    private static final int BMS_BLUR_PASSES = 2;
    private static final float BMS_BLUR_RADIUS = 3.0F;
    private static final float BMS_HEAD_RADIUS = 3.0F;
    private static final float BMS_PANEL_RADIUS = 6.0F;
    private static final long BMS_ADVANTAGE_CACHE_MS = 200L;
    private static final Color BMS_WINNING = new Color(85, 255, 85);
    private static final Color BMS_LOSING = new Color(255, 85, 85);
    private static final Color BMS_DRAWING = new Color(255, 255, 85);
    private static final int RAVEN_BLOOM_PASSES = 3;
    private static final float RAVEN_BLOOM_RADIUS = 2.0F;
    private static final int RAVEN_BLUR_PASSES = 2;
    private static final float RAVEN_BLUR_RADIUS = 3.0F;
    private static final int RAVEN_MAX_BACKGROUND_ALPHA = 210;
    private static final int RAVEN_MAX_OUTLINE_ALPHA = 255;
    private static final float RAVEN_BAR_RADIUS = 4.0F;
    private static final float RAVEN_BAR_HEIGHT = 5.0F;
    private static final DecimalFormat astolfoHealthFormat =
            new DecimalFormat("0.#", new DecimalFormatSymbols(Locale.US));
    private final ContinualAnimation astolfoHealth = new ContinualAnimation();
    private final Animation astolfoOpen =
            new DecelerateAnimation(ASTOLFO_OPEN_MS, ASTOLFO_OPEN_ENDPOINT, Direction.BACKWARDS);
    private EntityLivingBase astolfoTarget = null;
    private final ContinualAnimation novoWidth = new ContinualAnimation();
    private final ContinualAnimation novoHealth = new ContinualAnimation();
    private final Animation novoOpen =
            new DecelerateAnimation(ASTOLFO_OPEN_MS, ASTOLFO_OPEN_ENDPOINT, Direction.BACKWARDS);
    private EntityLivingBase novoTarget = null;
    private EntityLivingBase vantaBarTarget = null;
    private float vantaMainRatio = 0.0F;
    private float vantaGhostRatio = 0.0F;
    private long vantaFrameMs = 0L;
    private MsdfFontRenderer vantaNameFont;
    private MsdfFontRenderer vantaSmallFont;
    private MsdfFontRenderer vantaAdjustNameFont;
    private MsdfFontRenderer vantaAdjustSmallFont;
    private RavenFontRenderer bmsFont;
    private RavenFontRenderer modernLightFont;
    private RavenFontRenderer modernMediumFont;
    private EntityLivingBase modernTarget;
    private double modernRaw = 0.0;
    private double modernHealthValue = 0.0;
    private long modernFrameMs = 0L;
    private long bmsAdvantageTime = 0L;
    private float bmsAdvantageValue = 0.0F;
    private EntityLivingBase bmsTarget;
    private double bmsRaw = 0.0;
    private double bmsHealthValue = 0.0;
    private long bmsFrameMs = 0L;
    public final ModeProperty mode = new ModeProperty("mode", 0,
            new String[]{"MYAU", "RAVEN", "ASTOLFO", "NOVOLINE", "OLD_ATMOSPHERE", "ATMOSPHERE", "ADJUST",
                    "BMS", "MODERN"});
    public final ModeProperty ravenStyle = new ModeProperty("raven-style", 0, new String[]{"MODERN", "LEGACY"},
            () -> this.mode.getValue() == MODE_RAVEN);
    public final ModeProperty color = new ModeProperty("color", 0, new String[]{"DEFAULT", "HUD"});
    public final ModeProperty posX = new ModeProperty("position-x", 1, new String[]{"LEFT", "MIDDLE", "RIGHT"});
    public final ModeProperty posY = new ModeProperty("position-y", 1, new String[]{"TOP", "MIDDLE", "BOTTOM"});
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 1.5F);
    public final IntProperty offX = new IntProperty("offset-x", 0, -255, 255);
    public final IntProperty offY = new IntProperty("offset-y", 40, -255, 255);
    public final PercentProperty background = new PercentProperty("background", 25);
    public final BooleanProperty head = new BooleanProperty("head", true);
    public final BooleanProperty indicator = new BooleanProperty("indicator", true);
    public final BooleanProperty outline = new BooleanProperty("outline", false);
    public final BooleanProperty animations = new BooleanProperty("animations", true);
    public final BooleanProperty shadow = new BooleanProperty("shadow", true);
    public final BooleanProperty kaOnly = new BooleanProperty("ka-only", true);
    public final BooleanProperty chatPreview = new BooleanProperty("chat-preview", false);
    private EntityLivingBase resolveTarget() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura.isEnabled() && killAura.isAttackAllowed() && TeamUtil.isEntityLoaded(killAura.getTarget())) {
            return killAura.getTarget();
        } else if (!(Boolean) this.kaOnly.getValue()
                && !this.lastAttackTimer.hasTimeElapsed(1500L)
                && TeamUtil.isEntityLoaded(this.lastTarget)) {
            return this.lastTarget;
        } else {
            return this.chatPreview.getValue() && mc.currentScreen instanceof GuiChat ? mc.thePlayer : null;
        }
    }
    private ResourceLocation getSkin(EntityLivingBase entityLivingBase) {
        if (entityLivingBase instanceof EntityPlayer) {
            NetworkPlayerInfo playerInfo = mc.getNetHandler().getPlayerInfo(entityLivingBase.getName());
            if (playerInfo != null) {
                return playerInfo.getLocationSkin();
            }
        }
        return null;
    }
    private Color getTargetColor(EntityLivingBase entityLivingBase) {
        if (entityLivingBase instanceof EntityPlayer) {
            if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                return Myau.friendManager.getColor();
            }
            if (TeamUtil.isTarget((EntityPlayer) entityLivingBase)) {
                return Myau.targetManager.getColor();
            }
        }
        switch (this.color.getValue()) {
            case 0:
                if (!(entityLivingBase instanceof EntityPlayer)) {
                    return new Color(-1);
                }
                return TeamUtil.getTeamColor((EntityPlayer) entityLivingBase, 1.0F);
            case 1:
                int rgb = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis()).getRGB();
                return new Color(rgb);
            default:
                return new Color(-1);
        }
    }
    public TargetHUD() {
        super("Target HUD", false, true);
    }
    @EventTarget
    public void onRender(Render2DPostEvent event) {
        if (this.isEnabled() && mc.thePlayer != null) {
            EntityLivingBase entityLivingBase = this.target;
            this.target = this.resolveTarget();
            if (this.target != null) {
                float health = (mc.thePlayer.getHealth() + mc.thePlayer.getAbsorptionAmount()) / 2.0F;
                float abs = this.target.getAbsorptionAmount() / 2.0F;
                float heal = this.target.getHealth() / 2.0F + abs;
                if (this.target != entityLivingBase) {
                    this.headTexture = null;
                    this.animTimer.setTime();
                    this.oldHealth = heal;
                    this.newHealth = heal;
                }
                if (this.mode.getValue() == MODE_ASTOLFO) {
                    this.astolfoTarget = this.target;
                    this.astolfoOpen.setDirection(Direction.FORWARDS);
                    this.renderAstolfo(this.target);
                    return;
                }
                if (this.mode.getValue() == MODE_NOVOLINE) {
                    this.novoTarget = this.target;
                    this.novoOpen.setDirection(Direction.FORWARDS);
                    this.renderNovoline(this.target);
                    return;
                }
                if (this.mode.getValue() == MODE_OLD_ATMOSPHERE) {
                    this.renderOldAtmosphere(this.target);
                    return;
                }
                if (this.mode.getValue() == MODE_ATMOSPHERE) {
                    this.renderAtmosphere(this.target);
                    return;
                }
                if (this.mode.getValue() == MODE_ADJUST) {
                    this.renderAdjust(this.target);
                    return;
                }
                if (this.mode.getValue() == MODE_BMS) {
                    this.renderBms(this.target);
                    return;
                }
                if (this.mode.getValue() == MODE_MODERN) {
                    this.renderModern(this.target);
                    return;
                }
                if (!this.animations.getValue() || this.animTimer.hasTimeElapsed(150L)) {
                    this.oldHealth = this.newHealth;
                    this.newHealth = heal;
                    this.maxHealth = this.target.getMaxHealth() / 2.0F;
                    if (this.oldHealth != this.newHealth) {
                        this.animTimer.reset();
                    }
                }
                ResourceLocation resourceLocation = this.getSkin(this.target);
                if (resourceLocation != null) {
                    this.headTexture = resourceLocation;
                }
                float elapsedTime = (float) Math.min(Math.max(this.animTimer.getElapsedTime(), 0L), 150L);
                float healthRatio = Math.min(Math.max(RenderUtil.lerpFloat(this.newHealth, this.oldHealth, elapsedTime / 150.0F) / this.maxHealth, 0.0F), 1.0F);
                Color targetColor = this.getTargetColor(this.target);
                Color healthBarColor = this.color.getValue() == 0 ? ColorUtil.getHealthBlend(healthRatio) : targetColor;
                float healthDeltaRatio = Math.min(Math.max((health - heal + 1.0F) / 2.0F, 0.0F), 1.0F);
                Color healthDeltaColor = ColorUtil.getHealthBlend(healthDeltaRatio);
                ScaledResolution scaledResolution = new ScaledResolution(mc);
                String targetNameText = ChatColors.formatColor(String.format("&r%s&r", TeamUtil.stripName(this.target)));
                int targetNameWidth = mc.fontRendererObj.getStringWidth(targetNameText);
                String healthText = ChatColors.formatColor(
                        String.format("&r&f%s%s❤&r", healthFormat.format(heal), abs > 0.0F ? "&6" : "&c")
                );
                int healthTextWidth = mc.fontRendererObj.getStringWidth(healthText);
                String statusText = ChatColors.formatColor(String.format("&r&l%s&r", heal == health ? "D" : (heal < health ? "W" : "L")));
                int statusTextWidth = mc.fontRendererObj.getStringWidth(statusText);
                String healthDiffText = ChatColors.formatColor(
                        String.format("&r%s&r", heal == health ? "0.0" : diffFormat.format(health - heal))
                );
                int healthDiffWidth = mc.fontRendererObj.getStringWidth(healthDiffText);
                float barContentWidth = Math.max(
                        (float) targetNameWidth + (this.indicator.getValue() ? 2.0F + (float) statusTextWidth + 2.0F : 0.0F),
                        (float) healthTextWidth + (this.indicator.getValue() ? 2.0F + (float) healthDiffWidth + 2.0F : 0.0F)
                );
                float headIconOffset = this.head.getValue() && this.headTexture != null ? 25.0F : 0.0F;
                float barTotalWidth = Math.max(headIconOffset + 70.0F, headIconOffset + 2.0F + barContentWidth + 2.0F);
                float posX = this.offX.getValue().floatValue() / this.scale.getValue();
                switch (this.posX.getValue()) {
                    case 1:
                        posX += (float) scaledResolution.getScaledWidth() / this.scale.getValue() / 2.0F - barTotalWidth / 2.0F;
                        break;
                    case 2:
                        posX *= -1.0F;
                        posX += (float) scaledResolution.getScaledWidth() / this.scale.getValue() - barTotalWidth;
                }
                float posY = this.offY.getValue().floatValue() / this.scale.getValue();
                switch (this.posY.getValue()) {
                    case 1:
                        posY += (float) scaledResolution.getScaledHeight() / this.scale.getValue() / 2.0F - 13.5F;
                        break;
                    case 2:
                        posY *= -1.0F;
                        posY += (float) scaledResolution.getScaledHeight() / this.scale.getValue() - 27.0F;
                }
                if (this.mode.getValue() == MODE_RAVEN) {
                    this.renderRaven(scaledResolution, targetNameText, healthRatio,
                            heal == health ? 0 : (heal < health ? 1 : -1), healthBarColor);
                    return;
                }
                GlStateManager.pushMatrix();
                GlStateManager.scale(this.scale.getValue(), this.scale.getValue(), 0.0F);
                GlStateManager.translate(posX, posY, -450.0F);
                RenderUtil.enableRenderState();
                int backgroundColor = new Color(0.0F, 0.0F, 0.0F, (float) this.background.getValue() / 100.0F).getRGB();
                int outlineColor = this.outline.getValue() ? targetColor.getRGB() : new Color(0, 0, 0, 0).getRGB();
                RenderUtil.drawOutlineRect(0.0F, 0.0F, barTotalWidth, 27.0F, 1.5F, backgroundColor, outlineColor);
                RenderUtil.drawRect(headIconOffset + 2.0F, 22.0F, barTotalWidth - 2.0F, 25.0F, ColorUtil.darker(healthBarColor, 0.2F).getRGB());
                RenderUtil.drawRect(headIconOffset + 2.0F, 22.0F, headIconOffset + 2.0F + healthRatio * (barTotalWidth - 2.0F - headIconOffset - 2.0F), 25.0F, healthBarColor.getRGB());
                RenderUtil.disableRenderState();
                GlStateManager.disableDepth();
                GlStateManager.enableBlend();
                GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                mc.fontRendererObj.drawString(targetNameText, headIconOffset + 2.0F, 2.0F, -1, this.shadow.getValue());
                mc.fontRendererObj.drawString(healthText, headIconOffset + 2.0F, 12.0F, -1, this.shadow.getValue());
                if (this.indicator.getValue()) {
                    mc.fontRendererObj.drawString(statusText, barTotalWidth - 2.0F - (float) statusTextWidth, 2.0F, healthDeltaColor.getRGB(), this.shadow.getValue());
                    mc.fontRendererObj.drawString(healthDiffText, barTotalWidth - 2.0F - (float) healthDiffWidth, 12.0F, ColorUtil.darker(healthDeltaColor, 0.8F).getRGB(), this.shadow.getValue());
                }
                if (this.head.getValue() && this.headTexture != null) {
                    GlStateManager.color(1.0F, 1.0F, 1.0F);
                    mc.getTextureManager().bindTexture(this.headTexture);
                    Gui.drawScaledCustomSizeModalRect(2, 2, 8.0F, 8.0F, 8, 8, 23, 23, 64.0F, 64.0F);
                    Gui.drawScaledCustomSizeModalRect(2, 2, 40.0F, 8.0F, 8, 8, 23, 23, 64.0F, 64.0F);
                    GlStateManager.color(1.0F, 1.0F, 1.0F);
                }
                GlStateManager.disableBlend();
                GlStateManager.enableDepth();
                GlStateManager.popMatrix();
            } else if (this.mode.getValue() == MODE_ASTOLFO && this.astolfoTarget != null) {
                this.astolfoOpen.setDirection(Direction.BACKWARDS);
                if (this.astolfoOpen.finished(Direction.BACKWARDS)
                        || !TeamUtil.isEntityLoaded(this.astolfoTarget)) {
                    this.astolfoTarget = null;
                } else {
                    this.renderAstolfo(this.astolfoTarget);
                }
            } else if (this.mode.getValue() == MODE_NOVOLINE && this.novoTarget != null) {
                this.novoOpen.setDirection(Direction.BACKWARDS);
                if (this.novoOpen.finished(Direction.BACKWARDS)
                        || !TeamUtil.isEntityLoaded(this.novoTarget)) {
                    this.novoTarget = null;
                } else {
                    this.renderNovoline(this.novoTarget);
                }
            }
        }
    }


    private void renderNovoline(EntityLivingBase target) {
        float alpha = (float) Math.min(1.0, this.novoOpen.getOutput() * 2.0);
        if (alpha <= 0.0F) {
            return;
        }
        ScaledResolution resolution = new ScaledResolution(mc);
        String name = target.getName();
        float nameWidth = mc.fontRendererObj.getStringWidth(name);
        this.novoWidth.animate(NOVO_BASE_WIDTH + nameWidth, NOVO_WIDTH_MS);
        float width = this.novoWidth.getOutput();
        if (width <= 0.0F) {
            width = NOVO_BASE_WIDTH + nameWidth;
        }

        float absorption = target.getAbsorptionAmount();
        float health = Math.min(target.getHealth() + absorption, target.getMaxHealth() + absorption);
        float maxHealth = Math.max(1.0F, target.getMaxHealth() + absorption);
        float ratio = Math.min(Math.max(health / maxHealth, 0.0F), 1.0F);

        float scale = this.scale.getValue();
        float x = this.offX.getValue().floatValue() / scale;
        switch (this.posX.getValue()) {
            case 1:
                x += (float) resolution.getScaledWidth() / scale / 2.0F - width / 2.0F;
                break;
            case 2:
                x *= -1.0F;
                x += (float) resolution.getScaledWidth() / scale - width;
        }
        float y = this.offY.getValue().floatValue() / scale;
        switch (this.posY.getValue()) {
            case 1:
                y += (float) resolution.getScaledHeight() / scale / 2.0F - NOVO_HEIGHT / 2.0F;
                break;
            case 2:
                y *= -1.0F;
                y += (float) resolution.getScaledHeight() / scale - NOVO_HEIGHT;
        }

        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        Color accent = hud == null ? Color.WHITE : hud.getStaticColor();

        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, scale);
        RenderUtil.enableRenderState();

        RenderUtil.drawRect(x, y, x + width, y + NOVO_HEIGHT,
                applyOpacity(new Color(40, 40, 40), alpha).getRGB());

        float barX = x + NOVO_CONTENT_X;
        float barWidth = Math.max(1.0F, width - NOVO_CONTENT_X - NOVO_BAR_RIGHT_PAD);
        float barTop = y + NOVO_BAR_Y;
        float barBottom = barTop + NOVO_BAR_HEIGHT;
        RenderUtil.drawRect(barX, barTop, barX + barWidth, barBottom,
                applyOpacity(new Color(21, 21, 21, 150), alpha).getRGB());

        float filled = barWidth * ratio;
        this.novoHealth.animate(filled, NOVO_HEALTH_MS);
        float trail = Math.min(Math.max(this.novoHealth.getOutput(), 0.0F), barWidth);
        RenderUtil.drawRect(barX, barTop, barX + trail, barBottom,
                applyOpacity(accent.brighter(), alpha).getRGB());
        RenderUtil.drawRect(barX, barTop, barX + filled, barBottom,
                applyOpacity(accent, alpha).getRGB());

        RenderUtil.disableRenderState();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        mc.fontRendererObj.drawStringWithShadow(name, x + NOVO_CONTENT_X, y + NOVO_NAME_Y,
                applyOpacity(Color.WHITE, alpha).getRGB());

        String percent = String.format(Locale.US, "%.1f%%", ratio * 100.0F);
        float percentWidth = mc.fontRendererObj.getStringWidth(percent);
        mc.fontRendererObj.drawStringWithShadow(percent,
                barX + barWidth / 2.0F - percentWidth / 2.0F,
                barTop + (NOVO_BAR_HEIGHT - (mc.fontRendererObj.FONT_HEIGHT - NOVO_FONT_DESCENT)) / 2.0F,
                applyOpacity(Color.WHITE, alpha).getRGB());

        this.drawNovolineHead(target, x + 1.0F, y + 1.0F, NOVO_HEAD, alpha);

        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private void drawNovolineHead(EntityLivingBase target, float x, float y, float size, float alpha) {
        if (!(target instanceof AbstractClientPlayer)) {
            return;
        }
        AbstractClientPlayer player = (AbstractClientPlayer) target;
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(770, 771);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
        mc.getTextureManager().bindTexture(player.getLocationSkin());
        drawSkinQuad(x, y, size, 8.0F, 8.0F);
        drawSkinQuad(x, y, size, 40.0F, 8.0F);
        GlStateManager.resetColor();
    }

    private static void drawSkinQuad(float x, float y, float size, float u, float v) {
        float u0 = u / 64.0F;
        float v0 = v / 64.0F;
        float u1 = (u + 8.0F) / 64.0F;
        float v1 = (v + 8.0F) / 64.0F;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u0, v1);
        GL11.glVertex2f(x, y + size);
        GL11.glTexCoord2f(u1, v1);
        GL11.glVertex2f(x + size, y + size);
        GL11.glTexCoord2f(u1, v0);
        GL11.glVertex2f(x + size, y);
        GL11.glEnd();
    }

    private void renderAstolfo(EntityLivingBase target) {
        float alpha = (float) Math.min(1.0, this.astolfoOpen.getOutput() * 2.0);
        if (alpha <= 0.0F) {
            return;
        }
        ScaledResolution resolution = new ScaledResolution(mc);
        float healthScale = this.astolfoHealthScale.getValue();
        float glyphHeight = mc.fontRendererObj.FONT_HEIGHT - ASTOLFO_FONT_DESCENT;
        float healthTop = ASTOLFO_NAME_Y + glyphHeight + ASTOLFO_HEALTH_GAP;
        float healthBottom = healthTop + glyphHeight * healthScale;
        float barTop = healthBottom + ASTOLFO_HEALTH_GAP + ASTOLFO_BAR_DROP;
        float barBottom = barTop + ASTOLFO_BAR_HEIGHT;
        float panelHeight = barBottom + ASTOLFO_BOTTOM_PAD;
        float absorption = target.getAbsorptionAmount();
        float width = mc.fontRendererObj.getStringWidth(ASTOLFO_NAME_SAMPLE) + ASTOLFO_WIDTH_PAD;
        double healthPercentage = Math.min(Math.max(
                (target.getHealth() + absorption) / (target.getMaxHealth() + absorption), 0.0F), 1.0F);

        float scale = this.scale.getValue();
        float x = this.offX.getValue().floatValue() / scale;
        switch (this.posX.getValue()) {
            case 1:
                x += (float) resolution.getScaledWidth() / scale / 2.0F - width / 2.0F;
                break;
            case 2:
                x *= -1.0F;
                x += (float) resolution.getScaledWidth() / scale - width;
        }
        float y = this.offY.getValue().floatValue() / scale;
        switch (this.posY.getValue()) {
            case 1:
                y += (float) resolution.getScaledHeight() / scale / 2.0F - panelHeight / 2.0F;
                break;
            case 2:
                y *= -1.0F;
                y += (float) resolution.getScaledHeight() / scale - panelHeight;
        }

        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        Color first = hud == null ? Color.WHITE : hud.getStaticColor();
        Color c1 = applyOpacity(first, alpha);

        float openScale = (float) (0.5 + this.astolfoOpen.getOutput());

        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 0.0F);
        GlStateManager.translate(x + width / 2.0F, y + panelHeight / 2.0F, -450.0F);
        GlStateManager.scale(openScale, openScale, 1.0F);
        GlStateManager.translate(-(x + width / 2.0F), -(y + panelHeight / 2.0F), 0.0F);

        RenderUtil.enableRenderState();
        RenderUtil.drawRect(x, y, x + width, y + panelHeight,
                new Color(0.0F, 0.0F, 0.0F, 0.6F * alpha).getRGB());

        RenderUtil.drawRect(x + 34.0F, y + barTop, x + width - 4.0F, y + barBottom,
                c1.darker().darker().darker().darker().getRGB());

        float endWidth = (float) Math.max(0.0, (width - 34.0F) * healthPercentage);
        this.astolfoHealth.animate(endWidth, ASTOLFO_HEALTH_MS);
        float healthWidth = this.astolfoHealth.getOutput();

        RenderUtil.drawRect(x + 34.0F, y + barTop, x + 30.0F + healthWidth, y + barBottom,
                c1.darker().darker().getRGB());
        RenderUtil.drawRect(x + 34.0F, y + barTop,
                x + 30.0F + Math.min(endWidth, healthWidth), y + barBottom,
                c1.getRGB());
        RenderUtil.disableRenderState();

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GuiInventory.drawEntityOnScreen((int) x + 17, (int) (y + barBottom), ASTOLFO_MODEL_SCALE,
                target.rotationYaw, target.rotationPitch, target);

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        float textAlpha = Math.max(0.1F, alpha);
        mc.fontRendererObj.drawStringWithShadow(target.getName(), x + 34.0F, y + ASTOLFO_NAME_Y,
                applyOpacity(Color.WHITE, textAlpha).getRGB());

        float healthX = Math.round(x + 34.0F);
        float healthY = Math.round(y + healthTop);
        boolean previousUnicode = mc.fontRendererObj.getUnicodeFlag();
        if (this.astolfoSharpHealth.getValue()) {
            mc.fontRendererObj.setUnicodeFlag(true);
        }
        GlStateManager.pushMatrix();
        GlStateManager.scale(healthScale, healthScale, healthScale);
        mc.fontRendererObj.drawStringWithShadow(
                astolfoHealthFormat.format((target.getHealth() + absorption) / 2.0F) + " \u2764",
                healthX / healthScale, healthY / healthScale,
                applyOpacity(first, textAlpha).getRGB());
        GlStateManager.popMatrix();
        mc.fontRendererObj.setUnicodeFlag(previousUnicode);

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private float vantaScale() {
        return this.scale.getValue() * VANTA_SCALE;
    }

    private MsdfFontRenderer tahoma(float size) {
        return this.msdf("Tahoma", size);
    }

    private MsdfFontRenderer msdf(String name, float size) {
        MsdfAtlas atlas = MsdfShader.isSupported() ? MsdfAtlas.get(name) : null;
        return atlas == null ? null : new MsdfFontRenderer(atlas, size);
    }

    private void vantaText(MsdfFontRenderer font, String text, float x, float y, Color color, boolean shadow) {
        if (font != null) {
            font.drawString(text, x, y, color.getRGB(), shadow);
        } else {
            mc.fontRendererObj.drawString(text, x, y, color.getRGB(), shadow);
        }
    }

    private float vantaTextWidth(MsdfFontRenderer font, String text) {
        return font != null ? font.getStringWidth(text) : mc.fontRendererObj.getStringWidth(text);
    }

    private float vantaTextHeight(MsdfFontRenderer font) {
        return font != null ? font.getFontHeight() : mc.fontRendererObj.FONT_HEIGHT;
    }

    private Color vantaDamageTint(EntityLivingBase target) {
        float partial = AccessorMinecraft.getTimer(mc).renderPartialTicks;
        float hurt = Math.max(0.0F, Math.min(target.hurtTime == 0 ? 0.0F
                : (target.hurtTime - partial) * 0.3F, 1.0F));
        int greenBlue = (int) (255.0F + hurt * (171.0F - 255.0F));
        return new Color(255, greenBlue, greenBlue);
    }

    private void vantaHead(EntityLivingBase target, float x, float y, float size, Color tint) {
        if (!(target instanceof EntityPlayer) || this.headTexture == null) {
            return;
        }
        GlStateManager.color(tint.getRed() / 255.0F, tint.getGreen() / 255.0F, tint.getBlue() / 255.0F, 1.0F);
        mc.getTextureManager().bindTexture(this.headTexture);
        Gui.drawScaledCustomSizeModalRect((int) x, (int) y, 8.0F, 8.0F, 8, 8, (int) size, (int) size, 64.0F, 64.0F);
        Gui.drawScaledCustomSizeModalRect((int) x, (int) y, 40.0F, 8.0F, 8, 8, (int) size, (int) size, 64.0F, 64.0F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void vantaItem(ItemStack itemStack, float x, float y, float scale) {
        if (itemStack == null) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        RenderUtil.renderItemPlain(itemStack, 0, 0);
        GlStateManager.popMatrix();
    }

    private float vantaBarWidth(EntityLivingBase target, float barMax, boolean ghost) {
        float ratio = Math.min(Math.max(target.getHealth() / Math.max(1.0F, target.getMaxHealth()), 0.0F), 1.0F);
        long now = System.currentTimeMillis();
        float dt = this.vantaFrameMs == 0L ? 0.0F : Math.min(now - this.vantaFrameMs, 100L);
        this.vantaFrameMs = now;
        if (target != this.vantaBarTarget || !this.animations.getValue()) {
            this.vantaBarTarget = target;
            this.vantaMainRatio = ratio;
            this.vantaGhostRatio = ratio;
        } else {
            this.vantaMainRatio += (ratio - this.vantaMainRatio) * Math.min(1.0F, dt / 150.0F);
            this.vantaGhostRatio += (ratio - this.vantaGhostRatio) * Math.min(1.0F, dt / 450.0F);
        }
        return (ghost ? this.vantaGhostRatio : this.vantaMainRatio) * barMax;
    }

    private void vantaSetup(EntityLivingBase target, float x, float y, float width, float height) {
        this.vantaSetup(target, x, y, width, height, this.vantaScale());
    }

    private void vantaSetup(EntityLivingBase target, float x, float y, float width, float height,
            float scale) {
        this.headTexture = this.getSkin(target);
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 0.0F);
        GlStateManager.translate(0.0F, 0.0F, -450.0F);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private float vantaX(float width) {
        return this.vantaX(width, this.vantaScale());
    }

    private float vantaX(float width, float scale) {
        ScaledResolution resolution = new ScaledResolution(mc);
        float x = this.offX.getValue().floatValue() / scale;
        switch (this.posX.getValue()) {
            case 1:
                x += (float) resolution.getScaledWidth() / scale / 2.0F - width / 2.0F;
                break;
            case 2:
                x *= -1.0F;
                x += (float) resolution.getScaledWidth() / scale - width;
        }
        return x;
    }

    private float vantaY(float height) {
        return this.vantaY(height, this.vantaScale());
    }

    private float vantaY(float height, float scale) {
        ScaledResolution resolution = new ScaledResolution(mc);
        float y = this.offY.getValue().floatValue() / scale;
        switch (this.posY.getValue()) {
            case 1:
                y += (float) resolution.getScaledHeight() / scale / 2.0F - height / 2.0F;
                break;
            case 2:
                y *= -1.0F;
                y += (float) resolution.getScaledHeight() / scale - height;
        }
        return y;
    }

    private void renderOldAtmosphere(EntityLivingBase target) {
        if (this.vantaNameFont == null) {
            this.vantaNameFont = this.tahoma(9.0F);
        }
        if (this.vantaSmallFont == null) {
            this.vantaSmallFont = this.tahoma(8.0F);
        }
        float width = 140.0F;
        float height = 48.0F;
        float barMax = width - 36.0F;
        float widthOutline = width - 34.0F;
        float space = 28.0F;
        float x = this.vantaX(width);
        float y = this.vantaY(height);
        Color color = this.getTargetColor(target);
        this.vantaSetup(target, x, y, width, height);
        RenderUtil.enableRenderState();
        RenderUtil.drawRect(x, y, x + width, y + height, new Color(20, 20, 20, 150).getRGB());
        RenderUtil.drawRect(x + 33.0F, y + space - 1.0F, x + 33.0F + widthOutline, y + space - 1.0F + 5.0F,
                new Color(20, 20, 20, 255).getRGB());
        RenderUtil.drawRect(x + 34.0F, y + space, x + 34.0F + this.vantaBarWidth(target, barMax, true), y + space + 3.0F,
                color.darker().getRGB());
        RenderUtil.drawRect(x + 34.0F, y + space, x + 34.0F + this.vantaBarWidth(target, barMax, false), y + space + 3.0F,
                color.getRGB());
        RenderUtil.disableRenderState();

        float itemX = x + 22.0F;
        float itemY = y + 12.5F;
        ItemStack held = ((EntityPlayer) target).inventory.getCurrentItem();
        for (int slot = 3; slot >= 0; slot--) {
            ItemStack armor = ((EntityPlayer) target).inventory.armorItemInSlot(slot);
            if (armor != null) {
                itemX += 11.0F;
                this.vantaItem(armor, itemX, itemY, 0.8F);
            }
        }
        if (held != null) {
            itemX += 11.0F;
            this.vantaItem(held, itemX + 1.0F, itemY + 1.0F, 0.8F);
        }

        RenderUtil.enableRenderState();
        RenderUtil.drawRect(x + 1.0F, y + 34.0F, x + 1.0F + width - 2.0F, y + 34.0F + 12.0F,
                new Color(20, 20, 20, 200).getRGB());
        RenderUtil.disableRenderState();

        this.vantaHead(target, x + 2.0F, y + 2.0F, 30.0F, this.vantaDamageTint(target));
        this.vantaText(this.vantaNameFont, target.getName(), x + 33.0F, y + 3.0F, Color.WHITE, false);

        float healthPercent = target.getHealth() / Math.max(1.0F, target.getMaxHealth()) * 100.0F;
        String percentText = String.format(Locale.ROOT, "%.1f", healthPercent) + "%";
        float percentWidth = this.vantaTextWidth(this.vantaSmallFont, percentText);
        float diff = mc.thePlayer.getHealth() - target.getHealth();
        String diffText = String.format(Locale.ROOT, "%.1f", diff);
        String comparison;
        if (diff > 0.0F) {
            comparison = EnumChatFormatting.GRAY + "Winning" + EnumChatFormatting.DARK_GRAY + " (+" + diffText + ")";
        } else if (diff == 0.0F) {
            comparison = EnumChatFormatting.GRAY + "Tie" + EnumChatFormatting.DARK_GRAY + " (" + diffText + ")";
        } else {
            comparison = EnumChatFormatting.GRAY + "Losing" + EnumChatFormatting.DARK_GRAY + " (" + diffText + ")";
        }
        float lineHeight = this.vantaTextHeight(this.vantaSmallFont);
        this.vantaText(this.vantaSmallFont, comparison, x + 3.0F, y + 34.0F + 6.0F - lineHeight / 2.0F, Color.WHITE, false);
        this.vantaText(this.vantaSmallFont, percentText, x + width - percentWidth - 4.0F,
                y + 34.0F + 6.0F - lineHeight / 2.0F, Color.WHITE, false);

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private void renderAtmosphere(EntityLivingBase target) {
        if (this.vantaNameFont == null) {
            this.vantaNameFont = this.tahoma(9.0F);
        }
        if (this.vantaSmallFont == null) {
            this.vantaSmallFont = this.tahoma(8.0F);
        }
        float width = 130.0F;
        float height = 34.0F;
        float barMax = width - 36.0F;
        float widthOutline = width - 34.0F;
        float space = 28.0F;
        float x = this.vantaX(width);
        float y = this.vantaY(height);
        Color color = this.getTargetColor(target);
        this.vantaSetup(target, x, y, width, height);
        RenderUtil.enableRenderState();
        RenderUtil.drawRect(x, y, x + width, y + height, new Color(20, 20, 20, 150).getRGB());
        RenderUtil.drawRect(x + 33.0F, y + space - 1.0F, x + 33.0F + widthOutline, y + space - 1.0F + 5.0F,
                new Color(20, 20, 20, 255).getRGB());
        RenderUtil.drawRect(x + 34.0F, y + space, x + 34.0F + this.vantaBarWidth(target, barMax, true), y + space + 3.0F,
                color.darker().getRGB());
        RenderUtil.drawRect(x + 34.0F, y + space, x + 34.0F + this.vantaBarWidth(target, barMax, false), y + space + 3.0F,
                color.getRGB());
        RenderUtil.disableRenderState();

        float itemX = x + 22.0F;
        float itemY = y + 12.5F;
        ItemStack held = ((EntityPlayer) target).inventory.getCurrentItem();
        for (int slot = 3; slot >= 0; slot--) {
            ItemStack armor = ((EntityPlayer) target).inventory.armorItemInSlot(slot);
            if (armor != null) {
                itemX += 11.0F;
                this.vantaItem(armor, itemX, itemY, 0.8F);
            }
        }
        if (held != null) {
            itemX += 11.0F;
            this.vantaItem(held, itemX + 1.0F, itemY + 1.0F, 0.8F);
        }

        this.vantaHead(target, x + 2.0F, y + 2.0F, 30.0F, this.vantaDamageTint(target));
        this.vantaText(this.vantaNameFont, target.getName(), x + 33.0F, y + 3.0F, Color.WHITE, false);

        float diff = mc.thePlayer.getHealth() - target.getHealth();
        String diffText = String.format(Locale.ROOT, "%.1f", diff);
        if (diff > 0.0F) {
            diffText = "+" + diffText;
        }
        float diffWidth = this.vantaTextWidth(this.vantaSmallFont, diffText);
        this.vantaText(this.vantaSmallFont, diffText, x + width - diffWidth - 2.0F, y + 17.0F, Color.WHITE, false);

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private static double easeOutElastic(double progress) {
        if (progress <= 0.0) {
            return 0.0;
        }
        if (progress >= 1.0) {
            return 1.0;
        }
        return Math.pow(2.0, -10.0 * progress)
                * Math.sin((progress * 10.0 - 0.75) * (2.0 * Math.PI / 3.0)) + 1.0;
    }

    private void renderModern(EntityLivingBase target) {
        if (this.modernLightFont == null) {
            this.modernLightFont = Fonts.renderer("product_sans_light.ttf", 11.0F);
        }
        if (this.modernMediumFont == null) {
            this.modernMediumFont = Fonts.renderer("product_sans_medium.ttf", 11.0F);
        }
        float modernScale = this.scale.getValue();
        float head = 32.0F;
        float maxHealth = Math.max(1.0F, target.getMaxHealth());
        float rawHealth = Math.min(Math.round(target.getHealth() * 10.0F) / 10.0F, maxHealth);
        String health = String.valueOf(rawHealth);
        String name = target.getName();
        float advantage = this.bmsAdvantage(target);
        String state = advantage > 0.0F ? "Winning:" : advantage < 0.0F ? "Losing:" : "Drawing:";
        Color stateColor = advantage > 0.0F ? BMS_WINNING
                : advantage < 0.0F ? BMS_LOSING : BMS_DRAWING;
        float stateWidth = this.width(this.modernLightFont, state);
        float nameWidth = this.width(this.modernMediumFont, name);
        float healthWidth = this.width(this.modernMediumFont, health);
        float barWidth = Math.max(stateWidth + nameWidth + 35.0F - healthWidth, 65.0F);
        float panelWidth = 48.0F + barWidth + 4.0F + healthWidth + 8.0F;
        float panelHeight = 48.0F;
        float x = this.vantaX(panelWidth, modernScale);
        float y = this.vantaY(panelHeight, modernScale);

        long now = System.currentTimeMillis();
        float dt = this.modernFrameMs == 0L ? 0.0F : Math.min(now - this.modernFrameMs, 100L);
        this.modernFrameMs = now;
        float ratio = Math.min(Math.max(rawHealth / maxHealth, 0.0F), 1.0F);
        if (target != this.modernTarget || !this.animations.getValue()) {
            this.modernTarget = target;
            this.modernRaw = this.animations.getValue() ? 0.0 : 1.0;
            this.modernHealthValue = ratio * barWidth;
        } else {
            this.modernRaw = Math.min(1.0, this.modernRaw + dt / 850.0);
            this.modernHealthValue += (ratio * barWidth - this.modernHealthValue)
                    * Math.min(1.0, dt / 250.0);
        }
        double pop = this.animations.getValue() ? easeOutElastic(this.modernRaw) : 1.0;
        if (pop <= 0.0) {
            return;
        }

        float partial = AccessorMinecraft.getTimer(mc).renderPartialTicks;
        float hurt = this.animations.getValue() && target.hurtTime != 0
                ? Math.max(0.0F, (target.hurtTime - partial) * 0.5F) : 0.0F;
        float hurtShift = hurt / 2.0F;
        Color accent = this.getTargetColor(target);
        Color accentDark = accent.darker();
        Color headTint = lerpColor(Color.WHITE, Color.RED, Math.min(1.0F, hurt / 9.0F));

        this.bmsBlurPanel(x, y, panelWidth - 1.0F, panelHeight, modernScale, (float) pop, 16.0F);
        this.vantaSetup(target, x, y, panelWidth, panelHeight, modernScale);
        GlStateManager.translate((x + panelWidth / 2.0F) * (1.0 - pop),
                (y + panelHeight / 2.0F) * (1.0 - pop), 0.0);
        GlStateManager.scale(pop, pop, 1.0);

        RenderUtil.enableRenderState();
        this.bmsRound(x, y, panelWidth - 1.0F, panelHeight, 16.0F,
                new Color(18, 18, 22, BlurUtils.isReady() ? 120 : 210).getRGB());
        float barX = x + 8.0F + head + 7.0F;
        float barY = y + 8.0F + head - 4.0F - 7.0F;
        this.gradientRound(barX, barY, barWidth, 6.0F, 3.0F,
                new Color(0, 0, 0, 110).getRGB(), new Color(0, 0, 0, 200).getRGB());
        this.gradientRound(barX, barY, (float) this.modernHealthValue, 6.0F, 3.0F,
                lerpColor(accent, Color.WHITE, 0.5F).getRGB(),
                lerpColor(accent, Color.BLACK, 0.15F).getRGB());
        RenderUtil.disableRenderState();

        this.bmsHead(target, x + 8.0F + hurtShift, y + 8.0F + hurtShift, head - hurt,
                16.0F, headTint);
        this.text(this.modernLightFont, state, x + 8.0F + head + 7.0F, y + 14.0F, stateColor);
        this.text(this.modernMediumFont, name, x + 8.0F + head + 7.0F + stateWidth + 3.0F,
                y + 14.5F, accent);
        this.text(this.modernMediumFont, health, barX + barWidth + 4.0F,
                y + 8.0F + head - 4.0F - 8.0F, Color.WHITE);

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private float width(RavenFontRenderer font, String text) {
        return font == null ? mc.fontRendererObj.getStringWidth(text) : font.getStringWidth(text);
    }

    private void gradientRound(float x, float y, float width, float height, float radius,
            int top, int bottom) {
        if (width <= 0.0F) {
            return;
        }
        if (RoundedShader.isGradientReady()) {
            RoundedShader.drawRoundGradient(x, y, width, height, radius, top, bottom);
        } else {
            this.bmsRound(x, y, width, height, radius, bottom);
        }
    }

    private void text(RavenFontRenderer font, String text, float x, float y, Color color) {
        if (font != null) {
            font.drawString(text, x, y, color.getRGB(), false);
        } else {
            mc.fontRendererObj.drawString(text, x, y, color.getRGB(), false);
        }
    }

    private static Color lerpColor(Color from, Color to, float amount) {
        float inverse = 1.0F - amount;
        return new Color((int) (to.getRed() * amount + from.getRed() * inverse),
                (int) (to.getGreen() * amount + from.getGreen() * inverse),
                (int) (to.getBlue() * amount + from.getBlue() * inverse));
    }

    private void bmsBlurPanel(float x, float y, float width, float height, float base, float pop,
            float radius) {
        if (!BlurUtils.isReady() || !RoundedShader.isReady()) {
            return;
        }
        float effective = base * pop;
        float screenX = base * ((x + width / 2.0F) * (1.0F - pop) + pop * x);
        float screenY = base * ((y + height / 2.0F) * (1.0F - pop) + pop * y);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        BlurUtils.prepareBloom();
        RoundedShader.drawRound(screenX, screenY, width * effective, height * effective,
                radius * effective, new Color(0, 0, 0, 140).getRGB());
        BlurUtils.bloomEnd(BMS_BLOOM_PASSES, BMS_BLOOM_RADIUS);
        BlurUtils.prepareBlur();
        RoundedShader.drawRound(screenX, screenY, width * effective, height * effective,
                radius * effective, new Color(0, 0, 0, 255).getRGB());
        BlurUtils.blurEnd(BMS_BLUR_PASSES, BMS_BLUR_RADIUS);
    }

    private void bmsHead(EntityLivingBase target, float x, float y, float size) {
        this.bmsHead(target, x, y, size, BMS_HEAD_RADIUS, this.vantaDamageTint(target));
    }

    private void bmsHead(EntityLivingBase target, float x, float y, float size, float radius,
            Color tint) {
        if (!RoundedShader.isReady()) {
            this.vantaHead(target, x, y, size, tint);
            return;
        }
        StencilUtil.initStencil();
        StencilUtil.bindWriteStencilBuffer();
        RoundedShader.drawRound(x, y, size, size, radius, Color.WHITE.getRGB());
        StencilUtil.bindReadStencilBuffer(1);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableTexture2D();
        this.vantaHead(target, x, y, size, tint);
        GlStateManager.disableBlend();
        StencilUtil.uninitStencilBuffer();
    }

    private void bmsText(String text, float x, float y, Color color) {
        if (this.bmsFont != null) {
            this.bmsFont.drawString(text, x, y, color.getRGB(), false);
        } else {
            mc.fontRendererObj.drawString(text, x, y, color.getRGB(), false);
        }
    }

    private float bmsWidth(String text) {
        return this.bmsFont == null
                ? mc.fontRendererObj.getStringWidth(text)
                : this.bmsFont.getStringWidth(text);
    }

    private float bmsAdvantage(EntityLivingBase target) {
        long now = System.currentTimeMillis();
        if (now - this.bmsAdvantageTime > BMS_ADVANTAGE_CACHE_MS) {
            this.bmsAdvantageValue = this.bmsCalculateAdvantage(target);
            this.bmsAdvantageTime = now;
        }
        return this.bmsAdvantageValue;
    }

    private float bmsCalculateAdvantage(EntityLivingBase target) {
        float self = Math.max(0.1F, mc.thePlayer.getHealth());
        float other = Math.max(0.1F, target.getHealth());
        float ours = this.bmsDamage(mc.thePlayer.getHeldItem(), mc.thePlayer, target);
        float theirs = this.bmsDamage(target.getHeldItem(), target, mc.thePlayer);
        if (ours <= 0.01F && theirs <= 0.01F) {
            return 0.0F;
        }
        int steps;
        for (steps = 0; self > 0.0F && other > 0.0F && steps < 256; steps++) {
            other -= ours;
            self -= theirs;
        }
        return steps >= 256 ? ours - theirs : self - other;
    }

    private float bmsDamage(ItemStack stack, EntityLivingBase attacker, EntityLivingBase victim) {
        float damage = 1.0F;
        if (stack != null) {
            Item item = stack.getItem();
            if (item instanceof ItemSword || item instanceof ItemTool) {
                float weapon = bmsWeaponDamage(stack);
                if (weapon > 0.0F) {
                    damage = weapon;
                }
            }
            int sharpness = EnchantmentHelper.getEnchantmentLevel(
                    Enchantment.sharpness.effectId, stack);
            if (sharpness > 0) {
                damage += sharpness * 1.25F;
            }
        }
        if (attacker != null) {
            PotionEffect strength = attacker.getActivePotionEffect(Potion.damageBoost);
            int level = strength == null ? 0 : strength.getAmplifier() + 1;
            if (level > 0) {
                damage += level * 3.0F;
            }
        }
        if (victim != null) {
            damage *= 1.0F - Math.min(victim.getTotalArmorValue() * 0.04F, 0.8F);
            int points = Math.min(20, (int) Math.ceil(
                    Math.min(25, this.bmsProtectionPoints(victim)) * 0.75F));
            if (points > 0) {
                damage *= 1.0F - points * 0.04F;
            }
            PotionEffect resistance = victim.getActivePotionEffect(Potion.resistance);
            int level = resistance == null ? 0 : resistance.getAmplifier() + 1;
            if (level > 0) {
                damage *= 1.0F - Math.min(level * 0.2F, 0.8F);
            }
        }
        if (!Float.isFinite(damage)) {
            damage = 1.0F;
        }
        return Math.max(0.01F, damage);
    }

    private static float bmsWeaponDamage(ItemStack stack) {
        Multimap<String, AttributeModifier> modifiers = stack.getAttributeModifiers();
        if (modifiers == null) {
            return 0.0F;
        }
        Collection<AttributeModifier> attack = modifiers.get(
                SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName());
        if (attack == null) {
            return 0.0F;
        }
        double total = 0.0;
        for (AttributeModifier modifier : attack) {
            total += modifier.getAmount();
        }
        return (float) total;
    }

    private int bmsProtectionPoints(EntityLivingBase living) {
        int total = 0;
        for (int slot = 0; slot < 4; slot++) {
            ItemStack armor = living.getCurrentArmor(slot);
            int level = armor == null ? 0
                    : EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, armor);
            if (level > 0) {
                total += (int) Math.floor((6 + level * level) * 0.75F / 3.0F);
            }
        }
        return total;
    }

    private void bmsRound(float x, float y, float width, float height, float radius, int color) {
        if (RoundedShader.isReady()) {
            RoundedShader.drawRound(x, y, width, height, radius, color);
        } else {
            RenderUtil.drawRect(x, y, x + width, y + height, color);
        }
    }

    private void renderBms(EntityLivingBase target) {
        if (this.bmsFont == null) {
            this.bmsFont = Fonts.renderer("product_sans_light.ttf", 11.0F);
        }
        float bmsScale = this.scale.getValue();
        float padding = 4.0F;
        float head = 32.0F;
        float barWidth = 100.0F;
        float width = padding + head + padding + barWidth + padding + padding;
        float height = head + padding * 2.0F;
        float panelWidth = width - 4.0F;
        float x = this.vantaX(panelWidth, bmsScale);
        float y = this.vantaY(height, bmsScale);

        long now = System.currentTimeMillis();
        float dt = this.bmsFrameMs == 0L ? 0.0F : Math.min(now - this.bmsFrameMs, 100L);
        this.bmsFrameMs = now;
        float ratio = Math.min(Math.max(target.getHealth() / Math.max(1.0F, target.getMaxHealth()), 0.0F), 1.0F);
        if (target != this.bmsTarget || !this.animations.getValue()) {
            this.bmsTarget = target;
            this.bmsRaw = this.animations.getValue() ? 0.0 : 1.0;
            this.bmsHealthValue = ratio * 100.0;
        } else {
            this.bmsRaw = Math.min(1.0, this.bmsRaw + dt / 850.0);
            this.bmsHealthValue += (ratio * 100.0 - this.bmsHealthValue) * Math.min(1.0, dt / 250.0);
        }
        double scale = this.animations.getValue() ? easeOutElastic(this.bmsRaw) : 1.0;
        if (scale <= 0.0) {
            return;
        }

        Color accent = this.getTargetColor(target);
        this.bmsBlurPanel(x, y, panelWidth, height, bmsScale, (float) scale, BMS_PANEL_RADIUS);
        this.vantaSetup(target, x, y, panelWidth, height, bmsScale);
        GlStateManager.translate((x + panelWidth / 2.0F) * (1.0 - scale),
                (y + height / 2.0F) * (1.0 - scale), 0.0);
        GlStateManager.scale(scale, scale, 1.0);

        RenderUtil.enableRenderState();
        this.bmsRound(x, y, panelWidth, height, BMS_PANEL_RADIUS,
                new Color(18, 18, 22, BlurUtils.isReady() ? 120 : 210).getRGB());
        this.bmsRound(x + padding + head + padding, y + padding + head - padding - 10.0F,
                barWidth, 12.0F, 2.0F, Color.darkGray.darker().getRGB());
        this.bmsRound(x + padding + head + padding, y + padding + head - padding - 10.0F,
                (float) (this.bmsHealthValue / 100.0 * barWidth), 12.0F, 2.0F,
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 200).getRGB());
        RenderUtil.disableRenderState();

        this.bmsHead(target, x + padding, y + padding, head);
        String name = target.getName();
        float nameX = x + padding + head + padding;
        float nameY = y + padding + 2.0F;
        this.bmsText(name, nameX, nameY, Color.WHITE);
        float advantage = this.bmsAdvantage(target);
        String state = advantage > 0.0F ? "Winning" : advantage < 0.0F ? "Losing" : "Drawing";
        Color stateColor = advantage > 0.0F ? BMS_WINNING
                : advantage < 0.0F ? BMS_LOSING : BMS_DRAWING;
        this.bmsText(state, nameX + this.bmsWidth(name) + 4.0F, nameY, stateColor);
        String percent = Math.round(ratio * 100.0F) + "%";
        float percentWidth = this.bmsWidth(percent);
        this.bmsText(percent,
                x + padding + head + padding + barWidth / 2.0F - percentWidth / 2.0F,
                y + padding + head - padding - 8.0F, Color.WHITE);

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private void renderAdjust(EntityLivingBase target) {
        if (this.vantaAdjustNameFont == null) {
            this.vantaAdjustNameFont = this.tahoma(8.0F);
        }
        if (this.vantaAdjustSmallFont == null) {
            this.vantaAdjustSmallFont = this.tahoma(7.0F);
        }
        float width = 100.0F;
        float height = 30.0F;
        float barMax = width - 4.0F;
        float space = 24.5F;
        float x = this.vantaX(width);
        float y = this.vantaY(height);
        Color color = this.getTargetColor(target);
        this.vantaSetup(target, x, y, width, height);
        RenderUtil.enableRenderState();
        RenderUtil.drawRect(x, y, x + width, y + height, new Color(20, 20, 20, 200).getRGB());
        RenderUtil.drawRect(x + 1.75F, y + space - 0.25F, x + 1.75F + barMax + 0.5F, y + space - 0.25F + 3.75F,
                new Color(10, 10, 10).getRGB());
        RenderUtil.drawRect(x + 2.0F, y + space, x + 2.0F + this.vantaBarWidth(target, barMax, true), y + space + 3.0F,
                color.darker().getRGB());
        RenderUtil.drawRect(x + 2.0F, y + space, x + 2.0F + this.vantaBarWidth(target, barMax, false), y + space + 3.0F,
                color.getRGB());
        RenderUtil.disableRenderState();

        float itemX = x + 12.0F;
        float itemY = y + 10.0F;
        ItemStack held = ((EntityPlayer) target).inventory.getCurrentItem();
        if (held != null) {
            itemX += 11.0F;
            this.vantaItem(held, itemX + 1.0F, itemY + 1.0F, 0.65F);
        }
        for (int slot = 3; slot >= 0; slot--) {
            ItemStack armor = ((EntityPlayer) target).inventory.armorItemInSlot(slot);
            if (armor != null) {
                itemX += 11.0F;
                this.vantaItem(armor, itemX, itemY, 0.75F);
            }
        }

        this.vantaHead(target, x + 2.0F, y + 2.0F, 20.0F, this.vantaDamageTint(target));
        this.vantaText(this.vantaAdjustNameFont, target.getName(), x + 23.0F, y + 3.0F, Color.WHITE, false);

        float diff = mc.thePlayer.getHealth() - target.getHealth();
        String diffText = String.format(Locale.ROOT, "%.1f", diff);
        float diffWidth = this.vantaTextWidth(this.vantaAdjustSmallFont, diffText);
        this.vantaText(this.vantaAdjustSmallFont, diffText, x + width - diffWidth - 2.0F, y + 15.0F, Color.WHITE, false);

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private static Color applyOpacity(Color color, float opacity) {
        float clamped = Math.min(Math.max(opacity, 0.0F), 1.0F);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                (int) (color.getAlpha() * clamped));
    }

    private void renderRaven(ScaledResolution resolution, String name, float healthRatio,
                             int status, Color barColor) {
        String text = name;
        if (this.indicator.getValue()) {
            text = text + " " + ChatColors.formatColor(status >= 0 ? "&aW" : "&cL");
        }
        double health = Math.min(Math.max(healthRatio, 0.0F), 1.0F);
        if (health != this.lastHealth) {
            this.healthBarTimer.start();
            this.lastHealth = health;
        }
        float scale = this.scale.getValue();
        float screenWidth = resolution.getScaledWidth() / scale;
        float screenHeight = resolution.getScaledHeight() / scale;
        float textWidth = mc.fontRendererObj.getStringWidth(text) + RAVEN_PADDING;
        float x = screenWidth / 2.0F - textWidth / 2.0F + this.offX.getValue() / scale;
        float y = screenHeight / 2.0F + 15.0F + this.offY.getValue() / scale;
        float left = x - RAVEN_PADDING;
        float top = y - RAVEN_PADDING;
        float right = x + textWidth;
        float barTop = y + mc.fontRendererObj.FONT_HEIGHT - 1.0F + RAVEN_PADDING;
        float bottom = barTop + 13.0F;
        int background = new Color(0, 0, 0, Math.round(210.0F * this.background.getValue() / 25.0F) > 255
                ? 255 : Math.round(210.0F * this.background.getValue() / 25.0F)).getRGB();
        int[] gradient = this.ravenGradient(barColor);
        boolean modern = this.ravenStyle.getValue() == 0 && BlurUtils.isReady() && RoundedShader.isReady();
        if (modern) {
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            this.drawRavenModernPanel(left * scale, top * scale, right * scale, bottom * scale,
                    RAVEN_MODERN_RADIUS * scale);
        }
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1.0F);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (!modern) {
            if (this.outline.getValue()) {
                GuiRender.drawRoundedGradientOutlinedRect(left, top, right, bottom, RAVEN_RADIUS,
                        background, gradient[0], gradient[1]);
            } else {
                GuiRender.drawRoundedRect(left, top, right, bottom, RAVEN_RADIUS, background);
            }
        }
        float barLeft = left + 6.0F;
        float barRight = right - 6.0F;
        float barBottom = barTop + RAVEN_BAR_HEIGHT;
        GuiRender.drawRoundedRect(barLeft, barTop, barRight, barBottom, RAVEN_BAR_RADIUS,
                new Color(0, 0, 0, 110).getRGB());
        float healthBar = (int) (barRight + (barLeft - barRight) * (1.0F - health));
        boolean healing = false;
        if (!this.animations.getValue() || Float.isNaN(this.ghostBar)) {
            this.ghostBar = healthBar;
        } else if (healthBar != this.ghostBar && this.ghostBar - barLeft >= GHOST_MIN_WIDTH) {
            float difference = this.ghostBar - healthBar;
            if (difference > 0.0F) {
                this.ghostBar -= this.healthBarTimer.value(0.0F, difference);
            } else {
                healing = true;
                this.ghostBar = this.healthBarTimer.value(this.ghostBar, healthBar);
            }
        } else {
            this.ghostBar = healthBar;
        }
        if (this.ghostBar > barRight) {
            this.ghostBar = barRight;
        }

        GuiRender.drawRoundedRect(barLeft, barTop, this.ghostBar, barBottom, RAVEN_BAR_RADIUS,
                darken(gradient[1], 25));
        GuiRender.drawRoundedGradientRect(barLeft, barTop, healing ? this.ghostBar : healthBar,
                barBottom, RAVEN_BAR_RADIUS, gradient[0], gradient[1]);

        mc.fontRendererObj.drawString(text, x, y,
                new Color(220, 220, 220).getRGB(), this.shadow.getValue());
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }
    private void drawRavenModernPanel(float left, float top, float right, float bottom, float radius) {
        int scaled = Math.round(RAVEN_MAX_BACKGROUND_ALPHA * this.background.getValue() / 25.0F);
        int fillAlpha = Math.min(RAVEN_MAX_BACKGROUND_ALPHA, Math.max(0, scaled));
        float width = right - left;
        float height = bottom - top;
        BlurUtils.prepareBloom();
        RoundedShader.drawRound(left, top, width, height, radius, new Color(0, 0, 0, fillAlpha).getRGB());
        BlurUtils.bloomEnd(RAVEN_BLOOM_PASSES, RAVEN_BLOOM_RADIUS);
        BlurUtils.prepareBlur();
        RoundedShader.drawRound(left, top, width, height, radius,
                new Color(0, 0, 0, RAVEN_MAX_OUTLINE_ALPHA).getRGB());
        BlurUtils.blurEnd(RAVEN_BLUR_PASSES, RAVEN_BLUR_RADIUS);
    }
    private int[] ravenGradient(Color barColor) {
        if (this.color.getValue() != 1) {
            int flat = barColor.getRGB();
            return new int[]{flat, flat};
        }
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        long now = System.currentTimeMillis();
        return new int[]{hud.getColor(now, 0L).getRGB(), hud.getColor(now, 4L).getRGB()};
    }
    private static int darken(int color, int percent) {
        double factor = (100 - percent) / 100.0;
        int alpha = (color >> 24) & 0xFF;
        int red = (int) (((color >> 16) & 0xFF) * factor);
        int green = (int) (((color >> 8) & 0xFF) * factor);
        int blue = (int) ((color & 0xFF) * factor);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
    private static final class BarTimer {
        private long started;
        private float cached = Float.NaN;
        private void start() {
            this.cached = Float.NaN;
            this.started = System.currentTimeMillis();
        }
        private float value(float from, float to) {
            if (!Float.isNaN(this.cached) && this.cached == to) {
                return this.cached;
            }
            float t = (System.currentTimeMillis() - this.started) / GHOST_MS;
            t = t < 0.5F ? 2.0F * t * t : -1.0F + (4.0F - 2.0F * t) * t;
            float value = from + t * (to - from);
            if ((to > from && value > to) || (to < from && value < to)) {
                value = to;
            }
            if (value == to) {
                this.cached = value;
            }
            return value;
        }
    }
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.SEND && event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            if (packet.getAction() != Action.ATTACK) {
                return;
            }
            Entity entity = packet.getEntityFromWorld(mc.theWorld);
            if (entity instanceof EntityLivingBase) {
                if (entity instanceof EntityArmorStand) {
                    return;
                }
                this.lastAttackTimer.reset();
                this.lastTarget = (EntityLivingBase) entity;
            }
        }
    }
}
