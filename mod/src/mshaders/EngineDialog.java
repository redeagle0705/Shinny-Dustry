package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

/**
 * The window in which the engines of each kind of unit are dressed (Settings > Shaders > Units).
 *
 * On the left every kind of unit that has engines; on the right the one that is picked: its engines burning - the
 * unit speeds up, slows down and turns a little, so the streak is seen short, long and curved - and under that what
 * can be chosen: what is laid on the streak (Boosters.styles), its colour (the team's, the unit's own, or any), its
 * length and its width. Every change shows at once, here and in the game behind the window, and is written to the
 * list of engine looks when the window is closed (Boosters.save).
 */
public class EngineDialog extends BaseDialog{
    private UnitType chosen;
    private final Boosters.Look look = new Boosters.Look();
    private final Table detail = new Table();

    public EngineDialog(){
        super(Core.bundle.get("mshaders.engines.title", "Engine flames"));
        addCloseButton();
        shown(this::build);
        hidden(Boosters::saveNow);
    }

    private void build(){
        cont.clear();
        Seq<UnitType> units = Vars.content.units().select(u -> u.engines.size > 0 && !u.isHidden() && (u.flying || u.canBoost || !u.useEngineElevation));
        if(chosen == null || !units.contains(chosen)) chosen = units.isEmpty() ? null : units.first();

        Table list = new Table();
        int column = 0;
        for(UnitType type : units){
            ImageButton button = list.button(new TextureRegionDrawable(type.uiIcon), Styles.clearTogglei, 40f, () -> pick(type)).size(52f).pad(2f).name("engine-" + type.name).get();
            button.update(() -> button.setChecked(chosen == type));
            button.addListener(new Tooltip(t -> t.background(Tex.button).add(type.localizedName)));
            if(++column % 5 == 0) list.row();
        }
        ScrollPane pane = new ScrollPane(list, Styles.smallPane);
        pane.setFadeScrollBars(false);
        cont.add(pane).width(300f).growY().top();
        cont.add(detail).grow().padLeft(12f);
        if(chosen != null) pick(chosen);
    }

    private void pick(UnitType type){
        chosen = type;
        look.set(Boosters.lookOf(type));
        detail.clear();
        detail.top();
        detail.add(type.localizedName).color(Pal.accent).left().row();
        detail.add(new Preview()).size(460f, 220f).padTop(6f).row();

        // what is laid on the streak
        detail.table(row -> {
            row.left();
            for(int i = 0; i < Boosters.styles.length; i++){
                int style = i;
                TextButton button = row.button(Core.bundle.get("mshaders.engines.style." + Boosters.styles[i], Boosters.styles[i]), Styles.flatTogglet, () -> {
                    look.style = style;
                    apply();
                }).size(112f, 44f).pad(2f).name("engine-style-" + Boosters.styles[i]).get();
                button.update(() -> button.setChecked(look.style == style));
            }
        }).left().padTop(10f).row();

        // its colour
        detail.table(row -> {
            row.left();
            TextButton team = row.button(Core.bundle.get("mshaders.engines.colour.team", "Team colour"), Styles.flatTogglet, () -> {
                look.colour = "team";
                apply();
            }).size(150f, 44f).pad(2f).get();
            team.update(() -> team.setChecked(look.colour.equals("team")));
            TextButton own = row.button(Core.bundle.get("mshaders.engines.colour.own", "Unit's colour"), Styles.flatTogglet, () -> {
                look.colour = "own";
                apply();
            }).size(150f, 44f).pad(2f).get();
            own.update(() -> own.setChecked(look.colour.equals("own")));
            TextButton custom = row.button(Core.bundle.get("mshaders.engines.colour.custom", "Pick..."), Styles.flatTogglet, () ->
                Vars.ui.picker.show(look.colour.equals("custom") ? look.custom : Vars.player.team().color, false, picked -> {
                    look.custom.set(picked);
                    look.custom.a = 1f;
                    look.colour = "custom";
                    apply();
                })).size(150f, 44f).pad(2f).get();
            custom.update(() -> custom.setChecked(look.colour.equals("custom")));
        }).left().row();
        if(!Boosters.hasOwnColour(type)){
            detail.add(Core.bundle.get("mshaders.engines.noown", "This unit has no colour of its own: \"Unit's colour\" is its team's.")).color(Color.lightGray).left().wrap().width(460f).row();
        }

        slider("mshaders.engines.length", "Length", look.length, value -> look.length = value);
        slider("mshaders.engines.width", "Width", look.width, value -> look.width = value);

        detail.button(Core.bundle.get("mshaders.engines.reset", "As usual"), Icon.refresh, Styles.flatt, 24f, () -> {
            // back to how the mod itself has this unit (not to the bare streak)
            look.set(Boosters.defaultOf(type));
            apply();
            pick(type);
        }).size(200f, 44f).left().padTop(10f).name("engine-reset");
    }

    private void slider(String key, String fallback, float value, Floatc set){
        detail.table(row -> {
            row.left();
            Label label = new Label("");
            Slider slider = new Slider(50f, 200f, 5f, false);
            slider.setValue(value * 100f);
            Runnable name = () -> label.setText(Core.bundle.get(key, fallback) + ": " + (int)slider.getValue() + "%");
            name.run();
            slider.moved(moved -> {
                set.get(moved / 100f);
                name.run();
                apply();
            });
            row.add(label).width(150f).left();
            row.add(slider).width(300f);
        }).left().padTop(6f).row();
    }

    /** A function of one number (arc has Floatc; named here so the lambda above reads plainly). */
    private interface Floatc{
        void get(float value);
    }

    private void apply(){
        if(chosen != null) Boosters.setLook(chosen, look);
    }

    /** The picked unit with its engines burning, seen from above, flying to the right. */
    private class Preview extends Element{
        @Override
        public void draw(){
            if(chosen == null) return;
            Draw.color(0.07f, 0.08f, 0.1f, parentAlpha);
            Fill.crect(x, y, width, height);
            Draw.color();
            if(!clipBegin(x, y, width, height)) return;
            TextureRegion body = chosen.fullIcon;
            float world = Math.max(body.width, body.height) / 4f;
            // as large as leaves room for the streak
            float scale = Mathf.clamp(height * 0.5f / Math.max(world, 1f), 1.2f, 7f);
            float clock = Time.globalTime;
            // speeding up, at full speed, slowing down, hovering - and a slight turn to and fro at speed
            float push = Mathf.clamp(0.5f - 0.75f * Mathf.cos(clock / 70f));
            float turning = 1.6f * Mathf.sin(clock / 45f) * push;
            float cx = x + width * 0.76f, cy = y + height / 2f;
            Boosters.preview(chosen, cx, cy, scale, 0f, push, turning, look, Vars.player == null ? Pal.accent : Vars.player.team().color);
            Draw.color(1f, 1f, 1f, parentAlpha);
            Draw.rect(body, cx, cy, body.width / 4f * scale, body.height / 4f * scale, -90f);
            Draw.reset();
            clipEnd();
        }
    }
}
