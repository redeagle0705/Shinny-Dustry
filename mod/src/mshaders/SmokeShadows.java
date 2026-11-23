package mshaders;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.entities.Effect.*;
import mindustry.graphics.*;

/**
 * Smoke casts a shadow: every smoke effect of the game (Fx fields with "smoke" in the name - smelters, generators,
 * turrets firing, rocket trails, burning) is drawn a second time as a soft dark silhouette on the ground, under the
 * blocks, moved away from the sun by how high the smoke has risen (more as it rises).
 */
public class SmokeShadows{
    private static Shader shader;
    private static float alpha;
    /** Copies of the effects' containers for the deferred shadow draw (the game reuses its own). */
    private static final Seq<EffectContainer> pool = new Seq<>(), used = new Seq<>();
    private static long frame = -1;
    private static final Color was = new Color();

    public static int install(){
        try{
            shader = new Shader(Cutaway.VERT,
                "varying lowp vec4 v_color;\n" +
                "varying highp vec2 v_texCoords;\n" +
                "uniform highp sampler2D u_texture;\n" +
                "uniform float u_alpha;\n" +
                "void main(){\n" +
                "    float a = texture2D(u_texture, v_texCoords).a * v_color.a * u_alpha;\n" +
                "    gl_FragColor = vec4(0.0, 0.0, 0.0, a);\n" +
                "}\n"){
                @Override
                public void apply(){
                    setUniformf("u_alpha", alpha);
                }
            };
        }catch(Throwable t){
            Log.err("[mshaders] smoke shadows unavailable", t);
            return 0;
        }
        int wrapped = 0;
        for(java.lang.reflect.Field field : Fx.class.getFields()){
            if(field.getType() != Effect.class || !field.getName().toLowerCase().contains("smoke")) continue;
            try{
                Effect effect = (Effect)field.get(null);
                if(effect == null) continue;
                Cons<EffectContainer> draw = effect.renderer;
                effect.renderer = e -> {
                    shadow(e, draw);
                    draw.get(e);
                };
                wrapped++;
            }catch(Throwable t){
                Log.err("[mshaders] smoke effect " + field.getName() + " left without a shadow", t);
            }
        }
        return wrapped;
    }

    private static void shadow(EffectContainer e, Cons<EffectContainer> draw){
        if(!Core.settings.getBool(ShaderMod.smokeShadowsOn, true) || Vars.state.isMenu()) return;
        float strength = ShaderMod.percent(ShaderMod.smokeShadowsStrength, 100) * ShaderMod.daylight();
        if(strength <= 0.01f) return;
        long now = Core.graphics.getFrameId();
        if(now != frame){
            frame = now;
            pool.addAll(used);
            used.clear();
        }
        // how high the smoke is: a little at first, more as it rises
        float height = 4f + 10f * e.fin();
        EffectContainer c = pool.isEmpty() ? new EffectContainer() : pool.pop();
        c.set(e.id, e.color, e.time, e.lifetime, e.rotation, e.x - ShaderMod.sunX() * height, e.y - ShaderMod.sunY() * height, e.data);
        used.add(c);
        // light: many puffs of one cloud lie over each other
        float a = 0.09f * strength;
        // (Draw.draw leaves the layer at its own: put back the effect's, or the smoke itself went under the blocks)
        float z = Draw.z();
        Draw.draw(Layer.blockUnder + 0.1f, () -> {
            alpha = a;
            Draw.shader(shader);
            was.set(Draw.getColor());
            draw.get(c);
            Draw.shader();
            Draw.color(was);
        });
        Draw.z(z);
    }
}
