package mshaders;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.style.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;

/**
 * Pictures of what a setting does, beside the settings page ("like War Thunder: a picture next to the settings that
 * shows what changes"). The setting the pointer is on is named in a panel at the right of the screen, with the picture
 * of its effect as the setting stands now - on or off - changing the moment the setting is changed (at first it showed
 * off and on in turn: "better if it changes as I change the setting, and shows what the mouse is on").
 * A slider has the picture of the switch above it, "on" while both the switch is on and the slider is above nothing.
 *
 * The picture is drawn live ("later, render the previews separately"): in the main menu from a little world made for
 * the setting's group (PreviewWorld, in place of the scene behind the menu), in a game from the game itself - each
 * frame the world is drawn, the screen is copied (capture) and the panel shows the copy, so it shows the setting as it
 * stands, changing the moment it is changed. Where neither can be (the scenes behind the menu switched off), the old
 * shots of the test kit are shown, if the setting has them (tools/previews.ps1: previews/<setting>-off/-on.jpg).
 */
class Preview{
    private static Table panel;
    private static Image image;
    private static Label label;
    /** The copy of the screen, as wide and high as the screen; and the part of it shown (16:9, the right way up). */
    private static Texture shot;
    private static TextureRegion shown;
    private static TextureRegionDrawable shownDrawable;
    private static long shotFrame = -1;
    /** The row of the settings the pointer is on (the panel keeps clear of it), and the cells sized to the room left. */
    private static Element row;
    /** The page the row was on (the mod's settings), once seen: when it leaves the screen the preview ends. */
    private static Element page;
    /** (each setting's row, for the test kit, which shows a setting without the pointer on it) */
    private static final ObjectMap<String, Element> rows = new ObjectMap<>();
    private static Cell<Label> labelCell;
    private static Cell<Image> imageCell;

    static void init(){
        // in a game the world is drawn before the interface: copied just before the interface is drawn over it
        Events.run(mindustry.game.EventType.Trigger.uiDrawBegin, () -> {
            if(hovered != null && Vars.state.isGame() && panel != null && panel.parent != null) capture();
        });
    }

    /** Copies what is on the screen now (the world just drawn) for the panel. */
    static void capture(){
        try{
            int w = Core.graphics.getBackBufferWidth(), h = Core.graphics.getBackBufferHeight();
            if(w < 8 || h < 8) return;
            if(shot == null || shot.width != w || shot.height != h){
                if(shot != null) shot.dispose();
                Pixmap pm = new Pixmap(w, h);
                shot = new Texture(pm);
                pm.dispose();
                // (RGB: copying a screen that has no alpha into an RGBA picture fails on some drivers)
                shot.bind();
                Gl.texImage2D(Gl.texture2d, 0, Gl.rgb, w, h, 0, Gl.rgb, Gl.unsignedByte, null);
                shot.setFilter(Texture.TextureFilter.linear);
                // a 16:9 part from the middle, flipped (the screen's rows are read from the bottom up)
                int cw = w, ch = h;
                if(w * 9 > h * 16) cw = h * 16 / 9;
                else ch = w * 9 / 16;
                shown = new TextureRegion(shot, (w - cw) / 2, (h - ch) / 2, cw, ch);
                shown.flip(false, true);
                shownDrawable = new TextureRegionDrawable(shown);
                // (the panel never draws the picture just thrown away)
                if(image != null) image.setDrawable(shownDrawable);
            }
            Draw.flush();
            while(Gl.getError() != 0){}
            shot.bind();
            Gl.copyTexSubImage2D(Gl.texture2d, 0, 0, 0, 0, 0, w, h);
            Gl.activeTexture(Gl.texture0);
            // (a copy that failed is not shown: the panel falls back to the stored pictures)
            if(Gl.getError() == 0) shotFrame = Core.graphics.getFrameId();
        }catch(Throwable t){
            Log.err("[mshaders] the preview cannot copy the screen", t);
        }
    }

    /** Whether a live picture has been copied lately (this frame or the one before). */
    private static boolean live(){
        return shownDrawable != null && Core.graphics.getFrameId() - shotFrame <= 2;
    }
    /** The setting the pointer is on, the one whose pictures are shown, and the switch it goes by (or null). */
    private static String hovered, picture, switchKey;
    private static final ObjectMap<String, TextureRegion[]> pictures = new ObjectMap<>();
    private static final ObjectSet<String> missing = new ObjectSet<>();

    /** The panel shows "own" (with its pictures, or else those of "fallback", the switch above it) when the pointer comes onto that element. */
    static void attach(Element e, String own, String fallback){
        if(e == null || own == null) return;
        rows.put(own, e);
        e.hovered(() -> {
            row = e;
            page = null;
            // live: in the menu a little world for it, in a game the game (every setting has a picture then).
            // (Not in the menu of a phone: there a touch while scrolling the list "hovers" each row it passes, and
            // a little world made for each would stutter - the stored pictures there.)
            if(Vars.state.isGame() || (!Core.app.isMobile() && MenuWorld.preview(own))) show(own, own, fallback);
            else if(load(own) != null) show(own, own, null);
            else if(fallback != null && load(fallback) != null) show(own, fallback, fallback);
        });
    }

    /** For the test kit: as if the pointer were on that setting. */
    private static void show(String key){
        if(!Vars.state.isGame()) MenuWorld.preview(key);
        row = rows.get(key);
        page = null;
        show(key, key, null);
    }

    private static void show(String own, String pics, String sw){
        hovered = own;
        picture = pics;
        switchKey = sw;
        build();
    }

    /** Settings that take effect only when the game starts again (the mod's own sprites are packed at the start). */
    static boolean needsRestart(String key){
        return ShaderMod.artOn.equals(key);
    }

    /** Is that setting on now: a switch that is on, a slider above nothing (the weather's "off" and "as the game has it" count as off). */
    private static boolean on(String key){
        if(key == null) return true;
        Object v = Core.settings.get(key, null);
        if(v instanceof Boolean b) return b;
        if(v instanceof Integer i) return i > 0;
        if(v instanceof Float f) return f > 0f;
        return true;
    }

    /** The two pictures of that setting, read once; null if it has none. */
    private static TextureRegion[] load(String key){
        TextureRegion[] got = pictures.get(key);
        if(got != null) return got;
        if(missing.contains(key)) return null;
        try{
            var mod = Vars.mods.getMod(ShaderMod.class);
            Fi folder = mod == null || mod.root == null ? null : mod.root.child("previews");
            Fi off = folder == null ? null : folder.child(key + "-off.jpg"), on = folder == null ? null : folder.child(key + "-on.jpg");
            if(off == null || on == null || !off.exists() || !on.exists()){
                missing.add(key);
                return null;
            }
            Texture a = new Texture(off), b = new Texture(on);
            a.setFilter(Texture.TextureFilter.linear);
            b.setFilter(Texture.TextureFilter.linear);
            got = new TextureRegion[]{new TextureRegion(a), new TextureRegion(b)};
            pictures.put(key, got);
            return got;
        }catch(Throwable t){
            Log.err("[mshaders] preview of " + key + " cannot be read", t);
            missing.add(key);
            return null;
        }
    }

    private static void build(){
        if(panel != null && panel.parent != null) return;
        panel = new Table(Tex.pane);
        panel.touchable = Touchable.disabled;
        image = new Image();
        label = new Label("");
        label.setWrap(true);
        labelCell = panel.add(label).width(480f).left().padBottom(4f);
        labelCell.row();
        imageCell = panel.add(image).size(480f, 270f);
        panel.pack();
        panel.update(() -> {
            // only while the settings are open, and on the mod's page: its row once shown is gone when the dialog's
            // "back" leads to another page (the dialog stays open; the game's sound page was heard muted then)
            // (the page, not the row: a section opened or closed rebuilds the rows, while the pointer is on its title)
            if(row != null && row.getScene() != null) page = row.parent != null ? row.parent : row;
            if(!Vars.ui.settings.isShown() || hovered == null || (page != null && page.getScene() == null)){
                panel.remove();
                hovered = picture = switchKey = null;
                row = null;
                page = null;
                // the scene behind the menu comes back; the screen-sized copy is freed
                MenuWorld.endPreview();
                if(shot != null){
                    shot.dispose();
                    shot = null;
                }
                shown = null;
                shownDrawable = null;
                shotFrame = -1;
                return;
            }
            // the scenes behind the menu switched off while their preview is shown: its little world goes
            if(Vars.state.isMenu() && PreviewWorld.current != null && !MenuWorld.on()) MenuWorld.endPreview();
            // as the setting stands now: the slider's own value and the switch it goes by
            boolean on = on(hovered) && on(switchKey);
            if(live()){
                image.setDrawable(shownDrawable);
                image.visible = true;
            }else{
                // (nothing to show - no copy yet, no stored pictures: the name alone, not a stale picture)
                TextureRegion[] got = pictures.get(picture);
                if(got != null) image.setDrawable(got[on ? 1 : 0]);
                image.visible = got != null;
            }
            label.setText(Core.bundle.get("setting." + hovered + ".name", hovered) + "  -  " + Core.bundle.get(on ? "mshaders.preview.on" : "mshaders.preview.off", on ? "on" : "off")
                + (needsRestart(hovered) ? "\n[scarlet]" + Core.bundle.get("mshaders.restart", "(restart needed)") : ""));
            // beside the settings, never over them ("what about this?" - in a small window the picture covered the
            // sliders): on the side of the row with more room, as wide as that room lets it be (at most as before)
            float sw = Core.graphics.getWidth(), margin = Scl.scl(16f), left = 0f, right = 0f;
            boolean known = row != null && row.getScene() != null;
            if(known){
                // (the whole list the row is in: a switch's row is narrower than a slider's)
                Element list = row.parent != null ? row.parent : row;
                left = list.localToStageCoordinates(Tmp.v1.set(0f, 0f)).x;
                right = left + list.getWidth();
            }
            boolean onRight = sw - right >= left;
            float room = (onRight ? sw - right : left) - margin * 2f - Scl.scl(12f);
            // (no room beside the list - a phone, a narrow window: none, rather than over the settings)
            // (see-through rather than hidden: a hidden panel is not updated - it would never come back, nor go)
            panel.color.a = !known || room / Scl.scl(1f) >= 200f ? 1f : 0f;
            float w = known ? Mathf.clamp(room / Scl.scl(1f), 200f, 480f) : 480f;
            labelCell.width(w);
            imageCell.size(w, w * 9f / 16f);
            panel.invalidateHierarchy();
            panel.pack();
            float x = onRight ? Math.max(right + margin, sw - panel.getWidth() - margin) : left - margin - panel.getWidth();
            panel.setPosition(Mathf.clamp(x, 0f, Math.max(0f, sw - panel.getWidth())), (Core.graphics.getHeight() - panel.getHeight()) / 2f);
            panel.toFront();
        });
        Core.scene.add(panel);
    }
}
