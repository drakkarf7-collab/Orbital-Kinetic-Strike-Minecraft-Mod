package fr.orbitalstrike.client.gui;

import fr.orbitalstrike.client.ClientConfig;
import fr.orbitalstrike.client.ClientState;
import fr.orbitalstrike.client.targeting.TargetingController;
import fr.orbitalstrike.crater.CraterProfile;
import fr.orbitalstrike.registry.ModItems;
import fr.orbitalstrike.strike.CinematicMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Card-based Detonator menu:
 * <ul>
 *   <li>on the left, the item card (preview, description, status, cooldown) and toggles
 *       (cutscene, captions, world camera);</li>
 *   <li>on the right, the skill card with its keybinding and integrated sliders (power, speed).</li>
 * </ul>
 * Styling: translucent dark chamfered panels, sapphire-blue borders, animated open transition.
 * All widgets are custom-drawn here (no standard Minecraft buttons).
 */
public class DetonatorScreen extends Screen {
    private static final Identifier ICON = Identifier.of("orbital_strike", "textures/gui/skill/kinetic_strike.png");
    private static final int AMBER = 0xFFE8A438, PANEL = 0xD20E1016, PANEL_HOVER = 0xE0181B24, INSET = 0xFF07080C;
    private static final int TEXT = 0xFFE8E8EC, MUTED = 0xFF9A9CA6, GOOD = 0xFF5FD38A, BAD = 0xFFFF5A47, CYAN = 0xFF5FE6FF;
    private static final int LEFT_W = 150, RIGHT_W = 250, GAP = 10, ROW_H = 20, ROW_GAP = 3, CUT = 3;
    private static final int ITEM_CARD_H = 142, SKILL_CARD_H = 44;
    /** Width reserved for the value label displayed to the right of a slider. */
    private static final int SLIDER_VALUE_W = 92;
    private static final float OPEN_MS = 170f;
    private static final float SPEED_MIN = 0.5f, SPEED_MAX = 2.0f, SPEED_STEP = 0.5f;

    /** Active slider being dragged: 0 = none, 1 = power, 2 = speed. */
    private int dragging = 0;
    private boolean capturing = false;
    private long openedAt;
    private int left, top, rightX;

    public DetonatorScreen() {
        super(Text.translatable("gui.orbital_strike.menu.title"));
    }

    @Override
    protected void init() {
        left = (width - LEFT_W - GAP - RIGHT_W) / 2;
        rightX = left + LEFT_W + GAP;
        top = Math.max(34, height / 2 - 112);
        if (openedAt == 0) openedAt = Util.getMeasuringTimeMs();
    }

    // ------------------------------------------------------------------ layout geometry

    private int toggleY(int index) {
        return top + ITEM_CARD_H + 6 + index * (ROW_H + ROW_GAP);
    }

    private int sliderY(int index) {
        return top + SKILL_CARD_H + ROW_GAP + index * (ROW_H + ROW_GAP);
    }

    private int sliderTrackX() {
        return rightX + 76;
    }

    private int sliderTrackW() {
        return RIGHT_W - 76 - SLIDER_VALUE_W;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ rendering

    /** Dark panel with chamfered corners. */
    private static void panel(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x + CUT, y, x + w - CUT, y + h, color);
        ctx.fill(x, y + CUT, x + CUT, y + h - CUT, color);
        ctx.fill(x + w - CUT, y + CUT, x + w, y + h - CUT, color);
        for (int i = 1; i < CUT; i++) {
            ctx.fill(x + i, y + CUT - i, x + CUT, y + CUT, color);
            ctx.fill(x + w - CUT, y + CUT - i, x + w - i, y + CUT, color);
            ctx.fill(x + i, y + h - CUT, x + CUT, y + h - CUT + i, color);
            ctx.fill(x + w - CUT, y + h - CUT, x + w - i, y + h - CUT + i, color);
        }
    }

    private static String spaced(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) out.append(s.charAt(i)).append(i < s.length() - 1 ? " " : "");
        return out.toString();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        float open = Math.min(1f, (Util.getMeasuringTimeMs() - openedAt) / OPEN_MS);
        float ease = 1f - (1f - open) * (1f - open);
        ClientConfig cfg = ClientConfig.get();
        MinecraftClient mc = MinecraftClient.getInstance();

        ctx.getMatrices().push();
        // Opening transition: cards slide upward by a few pixels.
        ctx.getMatrices().translate(0, (1f - ease) * 10f, 0);

        // Title.
        String title = spaced(Text.translatable("gui.orbital_strike.menu.banner").getString());
        int titleW = textRenderer.getWidth(title);
        ctx.drawText(textRenderer, title, (width - titleW) / 2, top - 22, TEXT, false);
        ctx.fill((width - titleW) / 2, top - 11, (width + titleW) / 2, top - 10, AMBER);

        drawItemCard(ctx, mc);
        drawToggles(ctx, cfg, mouseX, mouseY);
        drawSkillCard(ctx, cfg, mouseX, mouseY);
        drawSliders(ctx, cfg, mouseX, mouseY);

        int hintY = sliderY(2) + 4;
        for (OrderedText line : textRenderer.wrapLines(Text.translatable("gui.orbital_strike.menu.hint"), RIGHT_W - 8)) {
            ctx.drawText(textRenderer, line, rightX + 4, hintY, MUTED, false);
            hintY += 10;
        }
        List<String> clashes = clashes(cfg.skillKey);
        if (!capturing && !clashes.isEmpty()) {
            ctx.drawText(textRenderer, Text.translatable("gui.orbital_strike.clash", String.join(", ", clashes)),
                    rightX + 4, hintY + 2, 0xFFFFD36A, false);
        }
        ctx.getMatrices().pop();
    }

    private void drawItemCard(DrawContext ctx, MinecraftClient mc) {
        panel(ctx, left, top, LEFT_W, ITEM_CARD_H, PANEL);
        ctx.fill(left + CUT, top, left + LEFT_W - CUT, top + 2, AMBER);
        String name = spaced(Text.translatable("gui.orbital_strike.menu.item").getString());
        ctx.drawText(textRenderer, name, left + (LEFT_W - textRenderer.getWidth(name)) / 2, top + 9, TEXT, false);

        // Enlarged item preview inside a dark inset frame.
        int cx = left + LEFT_W / 2, cy = top + 44;
        panel(ctx, cx - 22, cy - 22, 44, 44, INSET);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(cx - 16, cy - 16, 0);
        ctx.getMatrices().scale(2f, 2f, 1f);
        ctx.drawItem(new ItemStack(ModItems.ORBITAL_DETONATOR), 0, 0);
        ctx.getMatrices().pop();

        int y = top + 72;
        for (OrderedText line : textRenderer.wrapLines(Text.translatable("item.orbital_strike.orbital_detonator.lore"), LEFT_W - 16)) {
            ctx.drawText(textRenderer, line, left + (LEFT_W - textRenderer.getWidth(line)) / 2, y, MUTED, false);
            y += 10;
        }

        boolean carried = TargetingController.hasDetonator(mc);
        Text state = Text.translatable(carried ? "gui.orbital_strike.carried" : "gui.orbital_strike.missing");
        int pillW = textRenderer.getWidth(state) + 14;
        panel(ctx, cx - pillW / 2, top + ITEM_CARD_H - 38, pillW, 13, carried ? 0x5028A060 : 0x50C03020);
        ctx.drawText(textRenderer, state, cx - textRenderer.getWidth(state) / 2, top + ITEM_CARD_H - 35, carried ? GOOD : BAD, false);

        // Cooldown: bar and label.
        int remaining = ClientState.cooldownRemainingTicks();
        int barX = left + 12, barW = LEFT_W - 24, barY = top + ITEM_CARD_H - 9;
        float frac = remaining > 0 ? 1f - remaining / (float) Math.max(1, ClientState.cooldownTotalTicks()) : 1f;
        ctx.fill(barX, barY, barX + barW, barY + 2, INSET);
        ctx.fill(barX, barY, barX + (int) (barW * frac), barY + 2, remaining > 0 ? AMBER : GOOD);
        Text ready = remaining > 0 ? Text.translatable("gui.orbital_strike.recharging", (remaining + 19) / 20)
                : Text.translatable("gui.orbital_strike.ready");
        ctx.drawText(textRenderer, ready, cx - textRenderer.getWidth(ready) / 2, barY - 10, remaining > 0 ? MUTED : GOOD, false);
    }

    private void drawToggles(DrawContext ctx, ClientConfig cfg, int mouseX, int mouseY) {
        CinematicMode mode = cfg.mode();
        drawToggleRow(ctx, 0, Text.translatable("gui.orbital_strike.row.cinematic"),
                Text.translatable("gui.orbital_strike.cinematic." + mode.name().toLowerCase(Locale.ROOT)).getString(),
                mode != CinematicMode.OFF, mouseX, mouseY);
        drawToggleRow(ctx, 1, Text.translatable("gui.orbital_strike.row.camera"), null, cfg.worldCamera, mouseX, mouseY);
        drawToggleRow(ctx, 2, Text.translatable("gui.orbital_strike.row.captions"), null, cfg.directorCaptions, mouseX, mouseY);
    }

    private void drawToggleRow(DrawContext ctx, int index, Text label, String value, boolean on, int mouseX, int mouseY) {
        int y = toggleY(index);
        boolean hover = inside(mouseX, mouseY, left, y, LEFT_W, ROW_H);
        panel(ctx, left, y, LEFT_W, ROW_H, hover ? PANEL_HOVER : PANEL);
        ctx.drawText(textRenderer, label, left + 8, y + 6, TEXT, false);
        // Toggle switch: track and thumb, amber when enabled.
        int sx = left + LEFT_W - 30, sy = y + 5;
        panel(ctx, sx, sy, 22, 10, on ? 0xFF4D320C : INSET);
        ctx.fill(on ? sx + 13 : sx + 2, sy + 2, on ? sx + 20 : sx + 9, sy + 8, on ? AMBER : MUTED);
        if (value != null) ctx.drawText(textRenderer, value, sx - 6 - textRenderer.getWidth(value), y + 6, on ? AMBER : MUTED, false);
    }

    private void drawSkillCard(DrawContext ctx, ClientConfig cfg, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, rightX, top, RIGHT_W, SKILL_CARD_H);
        panel(ctx, rightX, top, RIGHT_W, SKILL_CARD_H, hover ? PANEL_HOVER : PANEL);
        // Left selection accent strip.
        ctx.fill(rightX, top + CUT, rightX + 2, top + SKILL_CARD_H - CUT, cfg.skillSelected ? AMBER : MUTED);
        panel(ctx, rightX + 8, top + 6, 32, 32, INSET);
        ctx.drawTexture(ICON, rightX + 8, top + 6, 0, 0, 32, 32, 32, 32);
        ctx.drawText(textRenderer, Text.translatable("skill.orbital_strike.kinetic_strike"), rightX + 48, top + 10, TEXT, false);
        ctx.drawText(textRenderer, Text.translatable("gui.orbital_strike.menu.subtitle"), rightX + 48, top + 23, MUTED, false);

        // Keybind badge: click to rebind.
        String key = capturing ? "..." : keyName(cfg.skillKey).getString();
        int badgeW = Math.max(18, textRenderer.getWidth(key) + 10);
        int bx = rightX + RIGHT_W - badgeW - 8, by = top + 14;
        panel(ctx, bx, by, badgeW, 16, capturing ? 0xFF14404A : INSET);
        ctx.drawText(textRenderer, key, bx + (badgeW - textRenderer.getWidth(key)) / 2, by + 4, capturing ? CYAN : TEXT, false);
    }

    private void drawSliders(DrawContext ctx, ClientConfig cfg, int mouseX, int mouseY) {
        int maxPower = Math.max(1, ClientState.maxPower());
        int power = Math.min(cfg.power, maxPower);
        float powerFrac = maxPower <= 1 ? 1f : (power - 1) / (float) (maxPower - 1);
        drawSlider(ctx, 0, Text.translatable("gui.orbital_strike.row.power"), powerFrac,
                Text.translatable("gui.orbital_strike.value.power", power, CraterProfile.basinRadius(power)).getString(),
                mouseX, mouseY);
        float speedFrac = (cfg.speed - SPEED_MIN) / (SPEED_MAX - SPEED_MIN);
        drawSlider(ctx, 1, Text.translatable("gui.orbital_strike.row.speed"), speedFrac,
                String.format(Locale.ROOT, "x%.1f", cfg.speed), mouseX, mouseY);
    }

    private void drawSlider(DrawContext ctx, int index, Text label, float fraction, String value, int mouseX, int mouseY) {
        int y = sliderY(index);
        boolean hover = inside(mouseX, mouseY, rightX, y, RIGHT_W, ROW_H) || dragging == index + 1;
        panel(ctx, rightX + 10, y, RIGHT_W - 10, ROW_H, hover ? PANEL_HOVER : PANEL);
        ctx.drawText(textRenderer, label, rightX + 18, y + 6, MUTED, false);
        int tx = sliderTrackX(), tw = sliderTrackW(), ty = y + 9;
        ctx.fill(tx, ty, tx + tw, ty + 2, INSET);
        int knob = tx + Math.round(tw * Math.max(0f, Math.min(1f, fraction)));
        ctx.fill(tx, ty, knob, ty + 2, AMBER);
        panel(ctx, knob - 4, ty - 4, 8, 10, hover ? 0xFFFFFFFF : TEXT);
        ctx.drawText(textRenderer, value, rightX + RIGHT_W - 8 - textRenderer.getWidth(value), y + 6, TEXT, false);
    }

    // ------------------------------------------------------------------ input handling

    private void click() {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    private void dragTo(double mouseX) {
        float f = (float) Math.max(0, Math.min(1, (mouseX - sliderTrackX()) / sliderTrackW()));
        ClientConfig cfg = ClientConfig.get();
        if (dragging == 1) {
            int maxPower = Math.max(1, ClientState.maxPower());
            cfg.power = 1 + Math.round(f * (maxPower - 1));
        } else if (dragging == 2) {
            float raw = SPEED_MIN + f * (SPEED_MAX - SPEED_MIN);
            cfg.speed = Math.round(raw / SPEED_STEP) * SPEED_STEP;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ClientConfig cfg = ClientConfig.get();
        if (capturing) {
            capturing = false;
            return true;
        }
        for (int i = 0; i < 2; i++) {
            if (inside(mouseX, mouseY, rightX, sliderY(i), RIGHT_W, ROW_H)) {
                dragging = i + 1;
                dragTo(mouseX);
                click();
                return true;
            }
        }
        if (inside(mouseX, mouseY, rightX, top, RIGHT_W, SKILL_CARD_H)) {
            // Clicking the key badge (or right-clicking the card) rebinds the key; otherwise toggle selection.
            boolean onBadge = mouseX >= rightX + RIGHT_W - 60;
            if (onBadge || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) capturing = true;
            else cfg.skillSelected = !cfg.skillSelected;
            click();
            return true;
        }
        for (int i = 0; i < 3; i++) {
            if (!inside(mouseX, mouseY, left, toggleY(i), LEFT_W, ROW_H)) continue;
            switch (i) {
                case 0 -> cfg.cinematicMode = cfg.mode().next().name();
                case 1 -> cfg.worldCamera = !cfg.worldCamera;
                default -> cfg.directorCaptions = !cfg.directorCaptions;
            }
            click();
            ClientConfig.save();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging != 0) {
            dragTo(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != 0) {
            dragging = 0;
            ClientConfig.save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (capturing) {
            capturing = false;
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                ClientConfig.get().skillKey = keyCode;
                ClientConfig.get().skillSelected = true;
                ClientConfig.save();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static Text keyName(int code) {
        return InputUtil.Type.KEYSYM.createFromCode(code).getLocalizedText();
    }

    /** Minecraft keybindings already bound to the same key. */
    private List<String> clashes(int code) {
        List<String> out = new ArrayList<>();
        if (client == null) return out;
        InputUtil.Key key = InputUtil.Type.KEYSYM.createFromCode(code);
        for (KeyBinding kb : client.options.allKeys) {
            if (kb.getBoundKeyTranslationKey().equals(key.getTranslationKey())) {
                out.add(Text.translatable(kb.getTranslationKey()).getString());
            }
        }
        return out;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        ClientConfig.save();
        super.close();
    }
}
